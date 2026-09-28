package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*

/** Main-thread controller. Its single writer serializes immutable checkpoints and finish. */
internal class DemoOperationRecorder(
    context: Context,
    private val startBlockReason: () -> String?,
    private val acquireScreen: () -> Boolean,
    private val releaseScreen: () -> Unit
) {
    private val context = context.applicationContext
    private val store = DemoOperationDraftStore(File(this.context.filesDir, "operation-learning"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        Handler(Looper.getMainLooper()).post { finish("录制异常，已停止：${error.message}") }
    })
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "operation-draft-writer").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val observers = LinkedHashMap<Any, (DemoOperationSnapshot) -> Unit>()
    private var phase = DemoOperationPhase.IDLE
    private var draft: DemoOperationDraft? = null
    private var message: String? = null
    private var startedElapsed = 0L
    private var activeElapsed = 0L
    private var activeSince = 0L
    private var generation = 0L
    private var observationRevision = 0L
    private var stableFrame: DemoOperationFrame? = null
    private val postEvidence = DemoOperationPostEvidence()
    private var captureJob: Job? = null
    private var ticker: Job? = null
    private var captureInFlight = false
    private var frameWritePending = false
    private var captureTicket = 0L
    private var ownerHeld = false
    private var lastCaptureAt = 0L
    private var imePackages = emptySet<String>()
    private var launcherPackages = emptySet<String>()
    private var launcherGapRecorded = false
    var onCaptureVisibilityChanged: ((Boolean) -> Unit)? = null

    init { scope.launch(writer) { store.recover() } }

    fun snapshot() = DemoOperationSnapshot(phase, draft?.id, draft?.title.orEmpty(),
        activeElapsed + if (phase == DemoOperationPhase.RECORDING) (SystemClock.elapsedRealtime() - activeSince).coerceAtLeast(0) else 0,
        draft?.events?.size ?: 0, draft?.frames?.size ?: 0, message)

    fun attach(owner: Any, onChanged: (DemoOperationSnapshot) -> Unit) {
        observers[owner] = onChanged; onChanged(snapshot())
    }
    fun detach(owner: Any) { observers.remove(owner) }

    fun start(title: String): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.IDLE) { "已有录制正在进行" }
        check(Build.VERSION.SDK_INT >= 30) { "演示录制需要 Android 11 或更新版本" }
        check(AgentAccessibilityService.instance != null) { "请先开启无障碍服务" }
        check(Settings.canDrawOverlays(context)) { "请先允许悬浮窗，以便随时暂停或停止录制" }
        check(!locked()) { "请先解锁屏幕" }
        startBlockReason()?.let { error(it) }
        check(acquireScreen()) { "屏幕正被其他任务占用" }
        ownerHeld = true
        try {
            val value = DemoOperationDraft(UUID.randomUUID().toString(), title.trim().take(120).ifBlank { "操作演示" }, System.currentTimeMillis())
            // This small durable write precedes accepting any event. A failed start owns no screen.
            store.create(value)
            draft = value; startedElapsed = SystemClock.elapsedRealtime(); generation++
            activeElapsed = 0; activeSince = startedElapsed
            stableFrame = null; observationRevision = 0; lastCaptureAt = 0
            postEvidence.invalidate()
            launcherGapRecorded = false
            launcherPackages = listOfNotNull(context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName).toSet()
            imePackages = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .inputMethodList.map { it.packageName }.toSet()
            phase = DemoOperationPhase.RECORDING; message = "仅本地录制；输入页面将自动暂停"
            ticker = scope.launch {
                while (isActive && phase != DemoOperationPhase.IDLE && phase != DemoOperationPhase.SAVING) {
                    delay(1000)
                    if (locked()) { finish("设备已锁定或熄屏"); break }
                    if (!Settings.canDrawOverlays(context)) { finish("悬浮窗权限已关闭"); break }
                    if (SystemClock.elapsedRealtime() - startedElapsed >= DemoOperationLimits.MAX_DURATION_MILLIS) {
                        finish("已达10分钟录制上限"); break
                    }
                    publish()
                }
            }
            publish()
        } catch (error: Exception) {
            releaseOwner(); throw error
        }
    }

    fun pause() {
        checkMainThread()
        if (phase != DemoOperationPhase.RECORDING) return
        stopActiveClock()
        invalidateCapture(); phase = DemoOperationPhase.PAUSED
        draft = draft?.copy(status = "paused")
        addGap("用户暂停；暂停期间无事件或截图")
        message = "暂停期间不会记录，可继续或结束。"; checkpoint(); publish()
    }

    fun resume(): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.PAUSED) { "当前未暂停" }
        check(!locked()) { "请先解锁屏幕" }
        check(Settings.canDrawOverlays(context)) { "请先恢复悬浮窗权限" }
        val service = AgentAccessibilityService.instance ?: error("无障碍服务已断开")
        val root = service.rootInActiveWindow ?: error("无法确认当前页面，请待界面稳定后再继续")
        val currentPackage = try { root.packageName?.toString().orEmpty() } finally { root.recycle() }
        check(currentPackage.isNotBlank()) { "无法确认当前页面所属应用，请待界面稳定后再继续" }
        val waitingForTarget = currentPackage == context.packageName || currentPackage in launcherPackages
        if (!waitingForTarget) {
            val page = DemoOperationCapture.page(service, context.packageName, launcherPackages)
                ?: error("无法确认当前外部页面，请待界面稳定后再继续")
            check(!page.sensitive) { "当前页面包含输入控件或输入法，请离开该页面后继续" }
        }
        generation++; phase = DemoOperationPhase.RECORDING
        message = if (waitingForTarget) "请切回目标App，输入页面会自动暂停；宿主和桌面不采集" else "继续本地录制"
        activeSince = SystemClock.elapsedRealtime(); draft = draft?.copy(status = "recording")
        checkpoint(); publish()
    }

    /** Returns immediately. SAVING remains busy until the final checkpoint finishes. */
    fun finish(interruptedReason: String? = null) {
        checkMainThread()
        if (phase == DemoOperationPhase.IDLE || phase == DemoOperationPhase.SAVING) return
        stopActiveClock()
        invalidateCapture(); ticker?.cancel(); ticker = null
        val current = draft ?: run { releaseOwner(); phase = DemoOperationPhase.IDLE; publish(); return }
        draft = current.copy(endedAt = System.currentTimeMillis(),
            status = if (interruptedReason == null) "draft" else "interrupted",
            gaps = (current.gaps + listOfNotNull(interruptedReason) +
                current.events.filter { it.postFrameId == null }.map { "事件${it.id}未关联稳定后置帧；不可当作验证通过" }).takeLast(100))
        phase = DemoOperationPhase.SAVING; message = "正在保存本地草稿"; publish()
        val finalDraft = draft!!
        scope.launch {
            val saved = withContext(writer) { runCatching { store.write(finalDraft) } }
            message = saved.fold({ if (interruptedReason == null) "草稿已保存，尚未整理或试跑" else "已保存中断草稿：$interruptedReason" },
                { "草稿保存失败，已停止；保留之前的检查点：${it.message}" })
            phase = DemoOperationPhase.IDLE; releaseOwner(); publish()
        }
    }

    fun onServiceUnavailable() { finish("无障碍服务中断或断开") }
    fun listDrafts(): List<DemoOperationDraft> = store.list()
    fun readDraft(id: String): DemoOperationDraft? = store.read(id)
    fun frameFile(draftId: String, fileName: String): File? = store.frameFile(draftId, fileName)
    fun deleteDraft(id: String): Result<Unit> = if (phase != DemoOperationPhase.IDLE && draft?.id == id)
        Result.failure(IllegalStateException("不能删除正在录制或保存的草稿")) else store.delete(id)

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (phase != DemoOperationPhase.RECORDING || event == null) return
        if (locked()) { finish("设备已锁定或熄屏"); return }
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return
        if (pkg == context.packageName) {
            val activeRoot = service.rootInActiveWindow
            val isHostPage = try { activeRoot?.packageName?.toString() == context.packageName }
                finally { activeRoot?.recycle() }
            if (isHostPage) invalidateCapture()
            return
        }
        if (pkg in imePackages) { invalidateCapture(); return }
        if (pkg in launcherPackages) {
            invalidateCapture()
            if (!launcherGapRecorded) {
                launcherGapRecorded = true; addGap("桌面选App阶段不采集事件或截图；进入目标App后开始记录")
                checkpoint(); publish()
            }
            return
        }
        val page = runCatching { DemoOperationCapture.page(service, context.packageName, launcherPackages) }.getOrNull()
        if (page == null) { invalidateCapture(); return }
        if (page.sensitive || event.isPassword || event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            stopActiveClock()
            invalidateCapture(); phase = DemoOperationPhase.PAUSED
            draft = draft?.copy(status = "paused")
            addGap("检测到输入或敏感页面，自动暂停；未保存该事件及截图")
            message = "输入页面已暂停录制，请离开后手动继续"; checkpoint(); publish(); return
        }
        postEvidence.observePackage(page.packageName)
        if (page.packageName in imePackages || page.packageName != pkg) return
        observationRevision++
        val current = draft ?: return
        val actionEvent = event.eventType in ACTION_EVENTS
        if (actionEvent) {
            postEvidence.invalidate()
            if (current.events.size >= DemoOperationLimits.MAX_EVENTS) { finish("已达500条事件上限"); return }
            val pre = stableFrame?.takeIf { it.packageName == pkg && System.currentTimeMillis() - it.at <= 10_000 }?.id
            val copied = runCatching { DemoOperationCapture.event(event, current.events.size + 1, pre) }.getOrNull()
            if (copied == null) addGap("事件源读取失败")
            else {
                draft = current.copy(events = current.events + copied,
                    gaps = if (pre == null) (current.gaps + "事件${copied.id}缺少可靠前置帧").takeLast(100) else current.gaps)
                postEvidence.recordEvent(copied.id, copied.packageName)
            }
            stableFrame = null
            checkpoint(); publish()
        }
        stableFrame = null
        scheduleCapture(service, page.packageName)
    }

    private fun scheduleCapture(service: AccessibilityService, packageName: String) {
        captureJob?.cancel()
        val token = generation
        val revision = observationRevision
        captureJob = scope.launch {
            delay(maxOf(600L, 1500L - (SystemClock.elapsedRealtime() - lastCaptureAt)))
            if (!accepts(token, revision) || captureInFlight || frameWritePending) return@launch
            val current = draft ?: return@launch
            if (current.frames.size >= DemoOperationLimits.MAX_FRAMES) {
                finish("已达40张关键帧上限"); return@launch
            }
            val before = DemoOperationCapture.page(service, context.packageName, launcherPackages)
            if (before == null || before.sensitive || before.packageName != packageName) return@launch
            captureVisibility(true)
            try { delay(120) } catch (cancelled: CancellationException) {
                captureVisibility(false); throw cancelled
            }
            if (!accepts(token, revision)) { captureVisibility(false); return@launch }
            val capturePage = DemoOperationCapture.page(service, context.packageName, launcherPackages)
            if (capturePage == null || capturePage.sensitive || capturePage.packageName != packageName) {
                captureVisibility(false); return@launch
            }
            captureInFlight = true
            val ticket = ++captureTicket
            val timeout = scope.launch {
                delay(4000)
                if (captureInFlight && captureTicket == ticket) {
                    captureInFlight = false; captureTicket++; captureVisibility(false)
                    if (accepts(token, revision)) { addGap("关键帧截图超时"); checkpoint(); publish() }
                }
            }
            lastCaptureAt = SystemClock.elapsedRealtime()
            val pendingPostEventId = postEvidence.pendingFor(packageName)
            DemoOperationCapture.screenshot(service) { result ->
                if (captureTicket != ticket) return@screenshot
                timeout.cancel()
                captureInFlight = false; captureVisibility(false)
                if (!accepts(token, revision)) return@screenshot
                val after = runCatching { DemoOperationCapture.page(service, context.packageName, launcherPackages) }.getOrNull()
                if (after == null || after.sensitive || after.packageName != packageName || locked()) return@screenshot
                result.fold(onSuccess = { image -> persistFrame(token, revision, packageName, pendingPostEventId, image, capturePage.nodes) },
                    onFailure = { addGap("关键帧不可用：${it.message}"); checkpoint(); publish() })
            }
        }
    }

    private fun persistFrame(token: Long, revision: Long, pkg: String, pendingPostEventId: Int?, image: DemoOperationImage, nodes: List<DemoOperationNode>) {
        val current = draft ?: return
        if (current.frames.sumOf { it.bytes } + image.bytes.size > DemoOperationLimits.MAX_BYTES) {
            finish("已达12MB素材上限"); return
        }
        val id = UUID.randomUUID().toString()
        val frame = DemoOperationFrame(id, System.currentTimeMillis(), "frame-$id.jpg", pkg, image.width, image.height, image.bytes.size, nodes,
            treeTruncated = nodes.size >= 200)
        frameWritePending = true
        scope.launch {
            val written = withContext(writer) { runCatching { store.saveFrame(current.id, frame.fileName, image.bytes) } }
            frameWritePending = false
            if (!accepts(token, revision)) {
                // The private, unreferenced output belongs to this pending capture only.
                withContext(writer) { written.getOrNull()?.delete() }
                return@launch
            }
            written.onFailure { finish("素材写入失败：${it.message}"); return@launch }
            val live = draft ?: return@launch
            val attachPost = postEvidence.complete(pendingPostEventId, pkg)
            draft = live.copy(frames = live.frames + frame, events = live.events.map { e ->
                if (attachPost && e.id == pendingPostEventId && e.packageName == pkg && e.postFrameId == null)
                    e.copy(postFrameId = id) else e
            })
            stableFrame = frame; checkpoint(); publish()
        }
    }

    private fun checkpoint() {
        val value = draft ?: return
        scope.launch {
            val result = withContext(writer) { runCatching { store.write(value) } }
            if (result.isFailure && draft?.id == value.id && phase != DemoOperationPhase.IDLE && phase != DemoOperationPhase.SAVING) {
                finish("增量保存失败：${result.exceptionOrNull()?.message}")
            }
        }
    }
    private fun addGap(value: String) { draft = draft?.let { it.copy(gaps = (it.gaps + value).takeLast(100)) } }
    private fun accepts(token: Long, revision: Long) = phase == DemoOperationPhase.RECORDING && generation == token && observationRevision == revision
    private fun invalidateCapture() {
        generation++; stableFrame = null; captureJob?.cancel(); captureJob = null
        postEvidence.invalidate()
        captureTicket++; captureInFlight = false
        captureVisibility(false)
    }
    private fun releaseOwner() { if (ownerHeld) { ownerHeld = false; releaseScreen() } }
    private fun captureVisibility(hidden: Boolean) { runCatching { onCaptureVisibilityChanged?.invoke(hidden) } }
    private fun locked() =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked ||
            !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    private fun stopActiveClock() {
        if (phase == DemoOperationPhase.RECORDING) activeElapsed += (SystemClock.elapsedRealtime() - activeSince).coerceAtLeast(0)
    }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "录制控制必须在主线程调用" } }
    private fun publish() { val value = snapshot(); observers.values.toList().forEach { runCatching { it(value) } } }
    private companion object {
        val ACTION_EVENTS = setOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }
}
