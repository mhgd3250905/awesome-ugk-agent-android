package com.ugk.pi.android.testapp

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.ugk.pi.android.AccessibilityScreenAutomationBackend
import com.ugk.pi.android.AnthropicRetryPolicy
import com.ugk.pi.android.AccessibilityServiceProvider
import com.ugk.pi.android.ScreenVisualAutomationBackend
import com.ugk.pi.android.ScreenVisualCaptureResult
import com.ugk.pi.android.ScreenVisualGestureRequest
import com.ugk.pi.android.ScreenOperationResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DemoWorkflowPhase { IDLE, COMPILING, SAVING, RUNNING, JUDGING }

internal data class DemoWorkflowSnapshot(
    val phase: DemoWorkflowPhase = DemoWorkflowPhase.IDLE,
    val draftId: String? = null,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val message: String = "",
    val modelCalls: Int = 0,
    val imagesSent: Int = 0,
    val revision: Long = 0
)

/** Process-owned work survives Activity recreation; no run resumes after process death. */
internal class DemoWorkflowController(
    context: Context,
    private val recorder: () -> DemoOperationRecorder,
    private val startBlockReason: () -> String?,
    private val onFinished: (String) -> Unit
) {
    private val context = context.applicationContext
    private val repository = DemoWorkflowRepository(File(this.context.filesDir, "operation-workflows"))
    private val storeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = Any()
    private val observers = LinkedHashMap<Any, (DemoWorkflowSnapshot) -> Unit>()
    private var state = DemoWorkflowSnapshot()
    private var job: Job? = null
    private var gateway: DemoWorkflowActionGateway? = null
    private var authorized = false
    private var stopMessage = "已停止"
    private val recovered = scope.async(Dispatchers.IO) { storeLock.withLock { repository.recoverInterrupted() } }
    var onCaptureVisibilityChanged: ((Boolean) -> Unit)? = null

    fun snapshot(): DemoWorkflowSnapshot = state
    fun isBusy(): Boolean = state.phase != DemoWorkflowPhase.IDLE
    fun isRunning(): Boolean = state.phase == DemoWorkflowPhase.RUNNING || state.phase == DemoWorkflowPhase.JUDGING
    fun attach(owner: Any, observer: (DemoWorkflowSnapshot) -> Unit) { observers[owner] = observer; observer(state) }
    fun detach(owner: Any) { observers.remove(owner) }

    private suspend fun <T> storage(block: () -> T): T {
        recovered.await()
        return withContext(Dispatchers.IO) { storeLock.withLock { block() } }
    }

    suspend fun load(draftId: String): DemoWorkflowPlan? = storage { repository.read(draftId) }
    suspend fun records(draftId: String): List<DemoWorkflowRunRecord> = storage { repository.records(draftId) }
    suspend fun readIntent(draftId: String): DemoWorkflowIntent? = storage { repository.readIntent(draftId) }

    fun saveIntent(draftId: String, goal: String, completionCriteria: String): Result<Unit> = runCatching {
        checkIdle()
        require(goal.isNotBlank() && goal.length <= 1000) { "请填写 1000 字以内的操作目标" }
        require(completionCriteria.isNotBlank() && completionCriteria.length <= 1000) { "请填写 1000 字以内的完成标准" }
        check(recorder().snapshot().phase == DemoOperationPhase.IDLE) { "请先结束录制" }
        publish(state.copy(phase = DemoWorkflowPhase.SAVING, draftId = draftId, message = "正在保存完成标准"))
        launchWork {
            try {
                storage { repository.saveIntent(DemoWorkflowIntent(draftId, goal.trim(), completionCriteria.trim(), System.currentTimeMillis())) }
                finishState("完成标准已保存，可继续整理操作")
            } catch (cancelled: CancellationException) {
                finishState("已取消保存，原说明保留")
            } catch (_: Exception) {
                finishState("完成标准未能保存，请重试；录制素材已保留")
            }
        }
    }

    suspend fun delete(draftId: String): Result<Unit> = runCatching {
        checkIdle()
        check(recorder().snapshot().phase == DemoOperationPhase.IDLE) { "请先结束录制" }
        publish(state.copy(phase = DemoWorkflowPhase.SAVING, draftId = draftId, message = "正在删除"))
        withContext(NonCancellable) {
            try {
                // Once confirmed, Activity rotation cannot split the two deletion phases.
                storage { repository.delete(draftId) }
                withContext(Dispatchers.IO) { recorder().deleteDraft(draftId).getOrThrow() }
            } finally { finishState("删除操作已结束") }
        }
    }

    fun compile(draftId: String, goal: String, completionCriteria: String = ""): Result<Unit> = runCatching {
        checkIdle()
        require(goal.isNotBlank() && goal.length <= 1000) { "请填写 1000 字以内的操作目标" }
        require(completionCriteria.isNotBlank() && completionCriteria.length <= 1000) { "请先说明怎样算完成，最多 1000 字" }
        check(recorder().snapshot().phase == DemoOperationPhase.IDLE) { "请先结束录制" }
        val config = ApiProviderSettingsStore(context).activeConfig()
            ?: error("请先在设置中配置支持图片的模型")
        check(config.apiKey.isNotBlank() && config.model.isNotBlank()) { "请先补全模型配置" }
        val rawProvider = workflowProvider(config)
        var calls = 0
        var images = 0
        val provider = object : com.ugk.pi.android.LLMProvider {
            override suspend fun generate(request: com.ugk.pi.android.ModelRequest): com.ugk.pi.android.ModelResponse {
                calls++
                images += request.messages.filterIsInstance<com.ugk.pi.android.AgentMessage.User>().sumOf { it.images.size }
                withContext(Dispatchers.Main.immediate) {
                    publish(state.copy(modelCalls = calls, imagesSent = images))
                }
                return rawProvider.generate(request)
            }
        }
        publish(DemoWorkflowSnapshot(DemoWorkflowPhase.COMPILING, draftId, message = "正在整理操作", revision = state.revision + 1))
        launchWork {
            try {
                val draft = withContext(Dispatchers.IO) { recorder().readDraft(draftId) }
                    ?: error("这份录制已不可用")
                check(draft.endedAt != null && draft.events.isNotEmpty()) { "请先完成一份有操作记录的演示" }
                storage { repository.saveIntent(DemoWorkflowIntent(draftId, goal.trim(), completionCriteria.trim(), System.currentTimeMillis())) }
                val plan = withTimeout(180_000) {
                    withContext(Dispatchers.IO) {
                        DemoWorkflowCompiler(provider).compile(draft, goal.trim(), completionCriteria.trim()) { frame ->
                            recorder().frameFile(draftId, frame.fileName)?.takeIf { it.length() <= 3 * 1024 * 1024 }?.readBytes()
                        }
                    }
                }
                storage { repository.saveNewVersion(plan) }
                finishState("已整理，请审阅步骤后试跑", plan.modelCalls, plan.imagesSent)
            } catch (cancelled: CancellationException) {
                finishState(if (cancelled is TimeoutCancellationException) "整理超时，原草稿已保留，可重试" else stopMessage, calls, images)
            } catch (error: DemoWorkflowCompileException) {
                finishState(error.message ?: "整理未完成，原草稿已保留", calls, images)
            } catch (_: Exception) {
                // API exceptions may contain raw HTTP responses; never put these into UI or run history.
                finishState("整理未完成，原草稿已保留。请检查模型是否支持图片、目标是否清楚后重试", calls, images)
            }
        }
    }

    fun saveRevision(plan: DemoWorkflowPlan): Result<Unit> = runCatching {
        checkIdle()
        check(recorder().snapshot().phase == DemoOperationPhase.IDLE) { "请先结束录制" }
        publish(state.copy(phase = DemoWorkflowPhase.SAVING, draftId = plan.draftId, message = "正在保存新版本"))
        launchWork {
            try {
                storage {
                    val current = repository.read(plan.draftId) ?: error("操作已不可用")
                    check(current.version == plan.version) { "操作版本已更新，请重新打开" }
                    check(current.completionCriteria == plan.completionCriteria) { "完成标准变更后，请重新整理步骤" }
                    repository.saveNewVersion(plan)
                }
                finishState("已保存新版本，请重新试跑")
            } catch (cancelled: CancellationException) {
                finishState(stopMessage)
            } catch (_: Exception) {
                finishState("未能保存修改，原版本已保留，请重新打开后重试")
            }
        }
    }

    /** Called only by the positive action of the concrete plan review dialog. */
    fun start(plan: DemoWorkflowPlan, isTrial: Boolean): Result<Unit> = runCatching {
        checkIdle()
        check(Build.VERSION.SDK_INT >= 30) { "操作运行需要 Android 11 或更新版本" }
        check(plan.completionCriteria.isNotBlank()) { "请先补充完成标准并重新整理步骤" }
        check(plan.steps.firstOrNull()?.action == "launch") { "操作需要以打开目标 App 开始，请重新整理步骤" }
        check(recorder().snapshot().phase == DemoOperationPhase.IDLE) { "请先结束当前录制" }
        startBlockReason()?.let { error(it) }
        check(deviceReady()) { "请开启无障碍和悬浮窗，并解锁屏幕" }
        // Freeze the selected API profile for the complete run; it is never persisted into a plan.
        val config = ApiProviderSettingsStore(context).activeConfig()
        if (plan.steps.any { it.postcondition.visualQuestion != null }) {
            check(config != null && config.apiKey.isNotBlank() && config.model.isNotBlank()) {
                "此操作包含 AI 判断，请先配置支持图片的模型"
            }
        }
        val provider = config?.let(::workflowProvider)
        check(DemoCapabilityInterlock.tryAcquireWorkflow(owner)) { "其他任务正在使用屏幕，请先结束" }
        authorized = true
        stopMessage = "已停止"
        publish(DemoWorkflowSnapshot(DemoWorkflowPhase.RUNNING, plan.draftId, totalSteps = plan.steps.size,
            message = "准备${if (isTrial) "试跑" else "运行"}", revision = state.revision + 1))
        launchWork {
            var record: DemoWorkflowRunRecord? = null
            var safetyMonitor: Job? = null
            try {
                val stored = storage { repository.read(plan.draftId) } ?: error("操作已不可用")
                check(stored.version == plan.version && stored.digest() == plan.digest()) { "操作版本已经变化" }
                val intent = storage { repository.readIntent(plan.draftId) }
                if (intent != null && intent.completionCriteria != stored.completionCriteria) {
                    throw DemoWorkflowPrerequisiteException("完成标准已变化，请重新整理步骤后再试跑")
                }
                if (!isTrial) check(storage { repository.records(plan.draftId) }.any {
                    it.isTrial && it.status == "succeeded" && it.version == stored.version && it.planDigest == stored.digest()
                }) { "此版本尚未通过试跑" }
                val initialRecord = DemoWorkflowRunRecord(UUID.randomUUID().toString(), stored.draftId,
                    stored.version, stored.digest(), isTrial, "started", System.currentTimeMillis(), totalSteps = stored.steps.size)
                storage { repository.saveRun(initialRecord) }
                record = initialRecord
                val backend = AccessibilityScreenAutomationBackend(
                    serviceProvider = AccessibilityServiceProvider { AgentAccessibilityService.instance },
                    ownPackageName = context.packageName
                )
                val visual = object : ScreenVisualAutomationBackend {
                    override suspend fun captureVisualObservation(sessionId: String): ScreenVisualCaptureResult {
                        onCaptureVisibilityChanged?.invoke(true)
                        return try { delay(100); backend.captureVisualObservation(sessionId) }
                        finally { onCaptureVisibilityChanged?.invoke(false) }
                    }
                    override suspend fun performVisualGesture(sessionId: String, request: ScreenVisualGestureRequest): ScreenOperationResult =
                        error("已学操作不接受模型生成的任意坐标动作")
                }
                val actionGateway = DemoWorkflowActionGateway(
                    stored,
                    isAuthorized = { authorized && DemoCapabilityInterlock.isWorkflowOwnedBy(owner) && deviceReady() },
                    launchPackage = { packageName ->
                        check(packageName != context.packageName) { "不能把本应用作为操作目标" }
                        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                            ?: error("目标应用未安装或没有可打开的入口")
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    },
                    backend = backend,
                    nowMillis = SystemClock::elapsedRealtime
                )
                gateway = actionGateway
                safetyMonitor = scope.launch {
                    while (isActive) { delay(400); if (!deviceReady()) { stop("设备锁定、权限或服务发生变化，已停止"); break } }
                }
                val result = withTimeout(300_000) {
                    DemoWorkflowRunner(backend, visual, provider, actionGateway, nowMillis = SystemClock::elapsedRealtime).run(stored) { progress ->
                        publish(state.copy(phase = if (progress.judging) DemoWorkflowPhase.JUDGING else DemoWorkflowPhase.RUNNING,
                            completedSteps = progress.completedSteps, totalSteps = progress.totalSteps,
                            message = progress.message, modelCalls = progress.modelCalls, imagesSent = progress.imagesSent))
                        val checkpoint = initialRecord.copy(completedSteps = progress.completedSteps,
                            modelCalls = progress.modelCalls, imagesSent = progress.imagesSent, message = progress.message)
                        record = checkpoint
                        storage { repository.saveRun(checkpoint) }
                    }
                }
                record = initialRecord.copy(status = "succeeded", endedAt = System.currentTimeMillis(),
                    completedSteps = result.completedSteps, modelCalls = result.modelCalls,
                    imagesSent = result.imagesSent, message = result.message)
                finishState(if (isTrial) "试跑通过，现在可以再次运行" else "操作已完成", result.modelCalls, result.imagesSent)
            } catch (cancelled: CancellationException) {
                val message = if (cancelled is TimeoutCancellationException) "运行超时，已停止" else stopMessage
                record = record?.copy(status = "cancelled", endedAt = System.currentTimeMillis(),
                    completedSteps = state.completedSteps, modelCalls = state.modelCalls, imagesSent = state.imagesSent, message = message)
                finishState(message)
            } catch (error: Exception) {
                val message = if (error is DemoWorkflowPrerequisiteException || error is DemoWorkflowExecutionException) error.message.orEmpty()
                    else "未能确认当前步骤，已停止。请查看页面状态后重新审阅或试跑"
                record = record?.copy(status = "failed", endedAt = System.currentTimeMillis(),
                    completedSteps = state.completedSteps, modelCalls = state.modelCalls, imagesSent = state.imagesSent, message = message)
                finishState(message)
            } finally {
                authorized = false
                gateway?.invalidate()
                gateway = null
                safetyMonitor?.cancel()
                withContext(NonCancellable) {
                    try { record?.let { finalRecord -> storage { repository.saveRun(finalRecord) } } }
                    catch (_: Exception) { finishState("运行记录保存失败；此版本不会因此标为试跑通过") }
                    finally {
                        DemoCapabilityInterlock.releaseWorkflow(owner)
                        // Persist first, then publish completion so the UI sees the actual trial receipt.
                        publish(state.copy(phase = DemoWorkflowPhase.IDLE, revision = state.revision + 1))
                        runCatching { onFinished(plan.draftId) }
                    }
                }
            }
        }
    }

    fun stop(message: String = "用户已停止，未继续后续操作") {
        checkMainThread()
        stopMessage = message
        authorized = false
        gateway?.invalidate()
        job?.cancel()
    }

    fun onServiceUnavailable() { if (isRunning()) stop("无障碍服务已断开，操作已停止") }

    private fun launchWork(block: suspend () -> Unit) {
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            finally { job = null; if (state.phase != DemoWorkflowPhase.IDLE) finishState(stopMessage) }
        }
        job = launched
        launched.start()
    }

    private fun finishState(message: String, calls: Int = state.modelCalls, images: Int = state.imagesSent) {
        // Keep the screen lease until runner cleanup and its durable receipt have completed.
        val finishingRun = DemoCapabilityInterlock.isWorkflowOwnedBy(owner)
        publish(state.copy(phase = if (finishingRun) DemoWorkflowPhase.SAVING else DemoWorkflowPhase.IDLE,
            message = message, modelCalls = calls, imagesSent = images, revision = state.revision + 1))
    }

    private fun publish(next: DemoWorkflowSnapshot) {
        state = next
        observers.values.toList().forEach { runCatching { it(next) } }
    }

    private fun deviceReady(): Boolean = AgentAccessibilityService.instance != null &&
        Settings.canDrawOverlays(context) &&
        !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked &&
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun checkIdle() { checkMainThread(); check(job == null && !isBusy()) { "请先结束当前整理或运行" } }
    // Each visible model decision is one HTTP attempt; retries must not hide cost
    // or exceed the compiler/runner request budget.
    private fun workflowProvider(config: ApiProviderConfig) = ProviderProfile.from(config).createRuntimeProvider(
        JavaNetDemoHttpTransport(), AnthropicRetryPolicy(maxAttempts = 1)
    )
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "操作控制必须在主线程调用" } }
}

private class DemoWorkflowPrerequisiteException(message: String) : IllegalStateException(message)
