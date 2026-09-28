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
    private var stableFrame: DemoOperationFrame? = null
    private var captureJob: Job? = null
    private var ticker: Job? = null
    private var ownerHeld = false
    private var imePackages = emptySet<String>()
    private var launcherPackages = emptySet<String>()
    private var launcherGapRecorded = false
    private var guidePhase = DemoOperationGuidePhase.READY
    private var guidedAiEnabled = false
    private var stepEventStart = 0
    private var stepPreFrame: String? = null
    private var reviewStep: DemoOperationStep? = null
    var onStepReviewReady: ((DemoOperationDraft, DemoOperationStep) -> Unit)? = null
    var onCaptureVisibilityChanged: ((Boolean) -> Unit)? = null

    init { scope.launch(writer) { store.recover() } }

    fun snapshot() = DemoOperationSnapshot(phase, draft?.id, draft?.title.orEmpty(),
        activeElapsed + if (phase == DemoOperationPhase.RECORDING) (SystemClock.elapsedRealtime() - activeSince).coerceAtLeast(0) else 0,
        draft?.events?.size ?: 0, draft?.frames?.size ?: 0, message, guidePhase,
        (draft?.steps?.count { it.confirmed && !it.discarded } ?: 0) + 1, reviewStep, guidedAiEnabled)

    fun attach(owner: Any, onChanged: (DemoOperationSnapshot) -> Unit) {
        observers[owner] = onChanged; onChanged(snapshot())
    }
    fun detach(owner: Any) { observers.remove(owner) }

    fun start(title: String, guidedAiEnabled: Boolean = true): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.IDLE) { "已有录制正在进行" }
        check(guidedAiEnabled) { "逐步录制必须启用模型整理" }
        val config = ApiProviderSettingsStore(context).activeConfig()
        check(config != null && config.apiKey.isNotBlank() && config.model.isNotBlank() && config.baseUrl.isNotBlank()) { "请先配置可用的模型" }
        check(Build.VERSION.SDK_INT >= 30) { "演示录制需要 Android 11 或更新版本" }
        check(AgentAccessibilityService.instance != null) { "请先开启无障碍服务" }
        check(Settings.canDrawOverlays(context)) { "请先允许悬浮窗，以便随时暂停或停止录制" }
        check(!locked()) { "请先解锁屏幕" }
        startBlockReason()?.let { error(it) }
        check(acquireScreen()) { "屏幕正被其他任务占用" }
        ownerHeld = true
        this.guidedAiEnabled = guidedAiEnabled
        guidePhase = DemoOperationGuidePhase.READY; reviewStep = null
        try {
            val value = DemoOperationDraft(UUID.randomUUID().toString(), title.trim().take(120).ifBlank { "操作演示" }, System.currentTimeMillis(), guided = true)
            // This small durable write precedes accepting any event. A failed start owns no screen.
            store.create(value)
            draft = value; startedElapsed = SystemClock.elapsedRealtime(); generation++
            activeElapsed = 0; activeSince = startedElapsed
            stableFrame = null
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
        reviewStep?.takeIf { !it.confirmed }?.let { replaceStep(it.copy(discarded = true)) }
        invalidateCapture(); guidePhase = DemoOperationGuidePhase.READY; reviewStep = null; phase = DemoOperationPhase.PAUSED
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
        generation++; guidePhase = DemoOperationGuidePhase.READY; reviewStep = null; phase = DemoOperationPhase.RECORDING
        message = if (waitingForTarget) "请切回目标App，输入页面会自动暂停；宿主和桌面不采集" else "继续本地录制"
        activeSince = SystemClock.elapsedRealtime(); draft = draft?.copy(status = "recording")
        checkpoint(); publish()
    }

    /** Returns immediately. SAVING remains busy until the final checkpoint finishes. */
    fun finish(interruptedReason: String? = null) {
        checkMainThread()
        if (phase == DemoOperationPhase.IDLE || phase == DemoOperationPhase.SAVING) return
        stopActiveClock()
        invalidateCapture(); guidePhase = DemoOperationGuidePhase.READY; reviewStep = null; ticker?.cancel(); ticker = null
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
    fun readDraft(id: String): DemoOperationDraft? = draft?.takeIf { it.id == id } ?: store.read(id)
    fun frameFile(draftId: String, fileName: String): File? = store.frameFile(draftId, fileName)
    fun deleteDraft(id: String): Result<Unit> = if (phase != DemoOperationPhase.IDLE && draft?.id == id)
        Result.failure(IllegalStateException("不能删除正在录制或保存的草稿")) else store.delete(id)

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (phase != DemoOperationPhase.RECORDING || guidePhase != DemoOperationGuidePhase.ACTING || event == null) return
        if (locked()) { finish("设备已锁定或熄屏"); return }
        val pkg = event.packageName?.toString().orEmpty()
        val windowChange = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (pkg.isBlank() && !windowChange) return
        // The guided controller itself changes host focus; never consume the external baseline.
        if (pkg == context.packageName && !windowChange) return
        if (pkg in imePackages && !windowChange) { invalidateCapture(); return }
        if (pkg in launcherPackages && !windowChange) {
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
            invalidateCapture(); guidePhase = DemoOperationGuidePhase.READY; reviewStep = null; phase = DemoOperationPhase.PAUSED
            draft = draft?.copy(status = "paused")
            addGap("检测到输入或敏感页面，自动暂停；未保存该事件及截图")
            message = "输入页面已暂停录制，请离开后手动继续"; checkpoint(); publish(); return
        }
        if (page.packageName in imePackages || page.packageName != pkg && !windowChange) { invalidateCapture(); return }
        val current = draft ?: return
        val actionEvent = event.eventType in ACTION_EVENTS
        val scroll = runCatching { DemoOperationCapture.scroll(event) }.getOrNull()
        val pageNotification = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || scroll?.isZeroMovement == true
        if (actionEvent) {
            if (current.events.size >= DemoOperationLimits.MAX_EVENTS) { finish("已达500条事件上限"); return }
            val pre = DemoOperationStepPolicy.preFrame(stableFrame, pkg)
            val copied = runCatching { DemoOperationCapture.event(event, current.events.size + 1, pre, scroll) }.getOrNull()
            if (copied == null) addGap("事件源读取失败")
            else {
                draft = current.copy(events = current.events + copied,
                    gaps = if (pre == null) (current.gaps + "事件${copied.id}缺少可靠前置帧").takeLast(100) else current.gaps)
            }
            if (!pageNotification) stableFrame = null
            checkpoint(); publish()
        }
        // Only explicit boundaries capture screenshots. Notifications do not consume the step baseline.
    }

    fun beginStep(): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.RECORDING && guidePhase == DemoOperationGuidePhase.READY) { "当前不能开始下一步" }
        check(Settings.canDrawOverlays(context)) { "请先恢复悬浮窗权限" }
        invalidateCapture(); reviewStep = null
        stepEventStart = draft?.events?.size ?: 0
        stepPreFrame = null
        guidePhase = DemoOperationGuidePhase.PREPARING; message = "正在准备操作前截图"; publish()
        captureBoundary { frame, preparationAllowed ->
            if (frame == null && !preparationAllowed) {
                guidePhase = DemoOperationGuidePhase.READY
                message = "未取得操作前截图，请保持目标页面稳定后重试"; checkpoint(); publish()
                return@captureBoundary
            }
            stepPreFrame = frame?.id; stableFrame = frame
            guidePhase = DemoOperationGuidePhase.ACTING
            message = if (frame == null) "请先打开目标App，然后点击已完成；此步作为准备步骤" else "请只完成一个操作，然后点击已完成"
            publish()
        }
    }

    fun completeStep(): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.RECORDING && guidePhase == DemoOperationGuidePhase.ACTING) { "当前没有正在执行的步骤" }
        guidePhase = DemoOperationGuidePhase.CAPTURING; message = "正在保存本步证据"; publish()
        captureBoundary { frame, _ ->
            val current = draft ?: return@captureBoundary
            val events = current.events.drop(stepEventStart)
            val actions = events.filter { it.type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !it.isZeroMovementScrollNotification() }
            val preparation = stepPreFrame == null
            val summary = when {
                preparation -> "准备步骤：进入目标App；桌面不采集。" + if (frame == null) "未取得目标页面截图，请重录。" else "当前应用：${frame.packageName}。"
                actions.isEmpty() -> "未观察到可确认的点击或滑动，请说明操作或重录。"
                actions.size > 1 -> "检测到${actions.size}个操作，请核对；建议重录为一个操作。"
                else -> "${when(actions.single().type) { 4096 -> "滑动"; 2 -> "长按"; else -> "点击" }}：${actions.single().label ?: actions.single().viewId ?: "未命名控件"}"
            } + if (!preparation && frame == null) "；缺少后置截图，不能作为已验证结果。" else ""
            val step = DemoOperationStep((current.steps.maxOfOrNull { it.id } ?: 0) + 1,
                events.map { it.id }, stepPreFrame, frame?.id, summary, preparation = preparation)
            // A boundary proves the whole step, never an invented intermediate action relation.
            val single = if (preparation) events.lastOrNull { it.type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && it.packageName == frame?.packageName } else actions.singleOrNull()
            draft = current.copy(steps = current.steps + step, events = current.events.map { event ->
                if (single != null && event.id == single.id && frame?.packageName == event.packageName)
                    event.copy(postFrameId = frame?.id) else event
            })
            reviewStep = step; guidePhase = DemoOperationGuidePhase.REVIEW; message = "核对本步结果，可补充纠正后确认"; checkpoint(); publish()
            runCatching { onStepReviewReady?.invoke(draft!!, step) }
        }
    }

    fun confirmStep(correction: String = "", finish: Boolean = false): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.RECORDING && guidePhase == DemoOperationGuidePhase.REVIEW) { "请等待本步整理完成" }
        val step = checkNotNull(reviewStep)
        check(!step.aiSummary.isNullOrBlank()) { "请先完成本步模型整理" }
        DemoOperationStepPolicy.confirmationError(step, draft?.events.orEmpty())?.let { error(it) }
        check(correction.length <= 1000) { "纠正说明请控制在1000字以内" }
        check(DemoOperationStepPolicy.correctionReviewed(step, correction)) { "纠正已修改，请先让模型按纠正重新整理" }
        replaceStep(step.copy(userCorrection = correction.trim().take(4000), confirmed = true))
        reviewStep = null; guidePhase = DemoOperationGuidePhase.READY; message = "本步已确认，可以开始下一步"
        checkpoint(); publish()
        if (finish) this.finish()
    }

    fun retryStep(): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.RECORDING && guidePhase in setOf(DemoOperationGuidePhase.REVIEW, DemoOperationGuidePhase.ANALYZING)) { "当前不能重录" }
        replaceStep(checkNotNull(reviewStep).copy(discarded = true))
        invalidateCapture(); reviewStep = null; guidePhase = DemoOperationGuidePhase.READY
        message = "原步已保留并排除，请回到操作前页面后开始重录"; checkpoint(); publish()
    }

    fun beginStepAnalysis(stepId: Int, correction: String = ""): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.RECORDING && guidePhase == DemoOperationGuidePhase.REVIEW && reviewStep?.id == stepId)
        check(correction.length <= 1000) { "纠正说明请控制在1000字以内" }
        replaceStep(checkNotNull(reviewStep).copy(userCorrection = correction.trim(), aiSummary = null))
        checkpoint(); guidePhase = DemoOperationGuidePhase.ANALYZING; message = "正在整理本步"; publish()
    }

    fun finishStepAnalysis(stepId: Int, summary: String?, error: String?): Result<Unit> = runCatching {
        checkMainThread()
        check(phase == DemoOperationPhase.RECORDING && guidePhase == DemoOperationGuidePhase.ANALYZING && reviewStep?.id == stepId)
        summary?.let { replaceStep(checkNotNull(reviewStep).copy(aiSummary = it.take(8000))) }
        guidePhase = DemoOperationGuidePhase.REVIEW; message = error ?: "请核对本步整理结果"; checkpoint(); publish()
    }

    private fun replaceStep(step: DemoOperationStep) {
        draft = draft?.let { it.copy(steps = it.steps.map { existing -> if (existing.id == step.id) step else existing }) }
        reviewStep = step
    }

    private fun captureBoundary(done: (DemoOperationFrame?, Boolean) -> Unit) {
        val token = generation
        captureJob = scope.launch {
            captureVisibility(true)
            try {
                delay(450)
                val service = AgentAccessibilityService.instance ?: error("无障碍服务已断开")
                val before = DemoOperationCapture.page(service, context.packageName, launcherPackages)
                if (before == null) {
                    val pkg = DemoOperationCapture.foregroundPackage(service, context.packageName)
                    val firstTargetEntry = draft?.steps.orEmpty().none { it.confirmed && !it.discarded }
                    done(null, firstTargetEntry && (pkg == context.packageName || pkg in launcherPackages))
                    return@launch
                }
                if (before.sensitive) { pause(); message = "输入页面已暂停录制，请离开后继续"; publish(); return@launch }
                val current = draft ?: return@launch
                if (current.frames.size >= DemoOperationLimits.MAX_FRAMES) { finish("已达40张关键帧上限"); return@launch }
                val image = withTimeoutOrNull(4000) {
                    suspendCancellableCoroutine<DemoOperationImage?> { continuation ->
                        DemoOperationCapture.screenshot(service) { result ->
                            if (continuation.isActive) continuation.resumeWith(Result.success(result.getOrNull()))
                        }
                    }
                }
                if (generation != token || phase != DemoOperationPhase.RECORDING) return@launch
                val after = DemoOperationCapture.page(service, context.packageName, launcherPackages)
                if (image == null || after != before || locked()) { addGap("步骤边界截图不可用或页面发生变化"); done(null, false); return@launch }
                if (current.frames.sumOf { it.bytes } + image.bytes.size > DemoOperationLimits.MAX_BYTES) { finish("已达12MB素材上限"); return@launch }
                val id = UUID.randomUUID().toString()
                val frame = DemoOperationFrame(id, System.currentTimeMillis(), "frame-$id.jpg", before.packageName,
                    image.width, image.height, image.bytes.size, before.nodes, before.nodes.size >= 200)
                withContext(writer) { store.saveFrame(current.id, frame.fileName, image.bytes) }
                if (generation != token || phase != DemoOperationPhase.RECORDING) return@launch
                draft = draft?.let { it.copy(frames = it.frames + frame) }
                checkpoint(); done(frame, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { finish("步骤采集失败：${error.message}") }
            finally { captureVisibility(false) }
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
    private fun invalidateCapture() {
        generation++; stableFrame = null; captureJob?.cancel(); captureJob = null
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

/** A reviewed explanation cannot manufacture missing or ambiguous action evidence. */
internal object DemoOperationStepPolicy {
    fun correctionReviewed(step: DemoOperationStep, correction: String): Boolean =
        !step.aiSummary.isNullOrBlank() && correction.trim() == step.userCorrection

    // Explicit boundary evidence belongs to this user-controlled step, independent of reading time.
    fun preFrame(frame: DemoOperationFrame?, packageName: String): String? =
        frame?.takeIf { it.packageName == packageName }?.id

    fun confirmationError(step: DemoOperationStep, events: List<DemoOperationEvent>): String? {
        if (step.discarded) return "此步已排除，请重录"
        if (step.postFrameId == null || step.eventIds.isEmpty()) return "缺少操作事件或后置截图，请重录此步"
        val selected = events.filter { it.id in step.eventIds }
        if (selected.size != step.eventIds.size) return "步骤事件证据不完整，请重录"
        if (step.preparation) return if (selected.any { it.type == 32 && it.postFrameId == step.postFrameId }) null else "未观察到进入目标应用并关联目标截图，请重录准备步骤"
        if (step.preFrameId == null) return "缺少前置截图，请重录此步"
        val actions = selected.filter { it.type != 32 && !it.isZeroMovementScrollNotification() }
        if (actions.size != 1) return "每步需要一个明确操作，当前观察到${actions.size}个，请重录此步"
        if (actions.single().preFrameId == null || actions.single().postFrameId != step.postFrameId)
            return "操作前后关系不完整，请回到操作前页面重录"
        return null
    }
}
