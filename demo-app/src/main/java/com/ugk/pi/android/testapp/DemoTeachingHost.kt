package com.ugk.pi.android.testapp

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Connects the existing chat/runtime to durable teaching evidence. All visible state is process-owned. */
internal class DemoTeachingHost(private val context: Context, private val process: DemoProcessScope) {
    val store = DemoTeachingStore(File(context.filesDir, "operation-teaching"))
    val compiler = DemoTeachingCompiler(context, store)
    private val window get() = process.overlayController.window
    private val authorization = AgentAuthorizationSettingsStore(context)
    private val confirmation by lazy { ActivityUserConfirmationDialogPresenter(
        isFullAuthorizationEnabled = { authorization.isFullAuthorizationEnabled() }, overlayHost = window) }
    private var recordId: String? = null
    private var title = ""
    private var currentSegment: String? = null
    private var streaming = ""
    private var lastRender = 0L
    private var lastRecord: DemoTeachingRecord? = null
    private var resumeRecord: DemoTeachingRecord? = null

    val controller: DemoTeachingController = DemoTeachingController(
        runtimeFactory = { decorator -> DemoAgentRuntimeFactory.create(
            context = context, confirmationPresenter = confirmation,
            shouldBypassConfirmation = { authorization.isFullAuthorizationEnabled() },
            toolDecorator = decorator, supportsBackgroundPromptExecution = false, maxIterations = 40,
            additionalAgentInstructions = DemoTeachingController.AGENT_INSTRUCTIONS,
            enableTeachingExperience = false
        ) },
        hooks = object : DemoTeachingController.Hooks {
            override fun onStarted(sessionId: String) {
                resumeRecord?.let { old ->
                    store.resumeTeaching(old.id)
                    recordId = old.id; lastRecord = store.read(old.id)
                    currentSegment = null; streaming = ""
                    return
                }
                val id = UUID.randomUUID().toString()
                store.create(id, title); recordId = id; lastRecord = store.read(id)
            }
            override fun onInstruction(segmentId: String, text: String) {
                require(text.length <= 12_000) { "这段指令过长，请拆成几段" }
                updateRecord { it.copy(segments = it.segments + DemoTeachingSegment(segmentId, text)) }
                currentSegment = segmentId; streaming = ""
            }
            override suspend fun beforeTool(segmentId: String, call: ToolCall) {
                if (!DemoScreenAutomationPolicy.isScreenWorkflowTool(call.name)) return
                withContext(Dispatchers.Main.immediate) {
                    check(Settings.canDrawOverlays(context)) { "悬浮窗权限已关闭，请结束教学后重新授权" }
                    check(AgentAccessibilityService.instance != null) { "无障碍服务已断开，请结束教学后重新连接" }
                    if (isMutation(call.name)) capture(segmentId, call, before = true)
                }
            }
            override suspend fun afterTool(segmentId: String, call: ToolCall, result: ToolResult?, error: Throwable?) {
                if (!DemoScreenAutomationPolicy.isScreenWorkflowTool(call.name)) return
                withContext(Dispatchers.Main.immediate) {
                    if (error == null && isMutation(call.name)) { delay(450); capture(segmentId, call, before = false) }
                    if (error != null) updateAction(segmentId, call) { it.copy(isError = true, gaps = it.gaps + "操作被中断或执行失败，不能视为成功") }
                }
            }
            override fun onSegmentFinished(segmentId: String, event: AgentEvent) {
                updateRecord { record -> record.copy(segments = record.segments.map { segment ->
                    if (segment.id != segmentId) segment else segment.copy(
                        status = if (event is AgentEvent.Completed) "completed" else "failed",
                        reply = when (event) { is AgentEvent.Completed -> event.content.take(20_000)
                            is AgentEvent.Failed -> event.message.take(4000); else -> "本段未完成" })
                }) }
                streaming = ""; currentSegment = null
            }
            override fun onFinished(cancelled: Boolean) {
                try {
                    updateRecord { it.copy(status = if (cancelled) "interrupted" else "finished") }
                } finally {
                    confirmation.cancelPending()
                    window.onFinishTeaching = null
                    window.setTeachingState(false)
                    process.overlayController.setTeachingCommands(null)
                    window.hide()
                    recordId?.let { id -> context.startActivity(Intent(context, DemoOperationLearningActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        putExtra(DemoOperationLearningActivity.EXTRA_TEACHING_ID, id)
                    }) }
                }
            }
        }
    ).apply {
        attach(this@DemoTeachingHost, onChanged = { render(it) }, onEvent = ::onEvent)
    }

    fun resume(id: String): Result<Unit> {
        val previous = store.read(id) ?: return Result.failure(IllegalStateException("教学记录无法读取"))
        if (previous.status == "active" || previous.compilationStatus == "compiling")
            return Result.failure(IllegalStateException("请先停止教学或等待整理结束"))
        if (previous.segments.size >= 80 || previous.segments.sumOf { it.actions.size } >= 600)
            return Result.failure(IllegalStateException("本记录已达容量上限，请开始新教学"))
        return start(previous.title, previous)
    }

    fun start(name: String, previous: DemoTeachingRecord? = null): Result<Unit> = runCatching {
        check(!controller.snapshot().active) { "教学已经开始" }
        val config = ApiProviderSettingsStore(context).activeConfig()
        check(config != null && config.apiKey.isNotBlank() && config.model.isNotBlank() && config.baseUrl.isNotBlank()) { "必须先配置模型才能开始教学" }
        check(authorization.isFullAuthorizationEnabled()) { "请先在设置中开启全授权模式，避免教学时反复确认" }
        check(Settings.canDrawOverlays(context)) { "请先授予悬浮窗权限" }
        check(AgentAccessibilityService.instance != null) { "请先启用并连接无障碍服务" }
        check(!process.conversationRuntime.runCoordinator.isRunning() && process.conversationRuntime.runCoordinator.snapshot().queuedMessages == 0) { "请先结束当前任务和排队消息" }
        check(process.delayedTasks.snapshot() is DemoDelayedTaskState.Idle) { "请先结束定时任务" }
        check(!process.workflowController.isBusy()) { "请先结束当前整理或操作运行" }
        check(!process.urgentInteractionDispatcher.hasPending() && !window.hasBlockingPresentation()) { "请先处理当前提醒或确认窗口" }
        require(name.trim().isNotEmpty()) { "请填写教学名称" }
        title = name.trim().take(120)
        resumeRecord = previous
        try {
            window.setExternalAutomationMode(false)
            window.onFinishTeaching = { controller.finish() }
            window.setTeachingState(true)
            window.showExpanded()
            check(window.isTeachingChatShowing()) { "悬浮对话窗无法显示" }
            controller.start(previous).getOrThrow()
            process.overlayController.setTeachingCommands(DemoOverlayCommands(
                onSend = { text -> controller.submit(text).onFailure { notice(it.message) }.isSuccess },
                onStop = { controller.stopSegment() },
                onOpenApp = { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) },
                onHide = { window.hide() }, onDraftChanged = {}
            ))
        } catch (error: Throwable) {
            window.onFinishTeaching = null
            window.setTeachingState(false); window.hide(); throw error
        } finally { resumeRecord = null }
    }

    private fun onEvent(event: AgentEvent) {
        try {
            val segmentId = currentSegment
            when (event) {
                is AgentEvent.ToolStarted -> if (segmentId != null) updateAction(segmentId, event.call) { it }
                is AgentEvent.ToolFinished -> if (segmentId != null) updateRecord { record -> record.copy(segments = record.segments.map { s ->
                    if (s.id != segmentId) s else s.copy(actions = s.actions.map { a -> if (a.id != event.result.toolCallId) a
                        else a.copy(result = DemoTeachingEvidence.result(event.result), isError = event.result.isError) })
                }) }
                is AgentEvent.ModelContentDelta -> streaming = (streaming + event.delta).take(20_000)
                is AgentEvent.ModelRequestStarted -> streaming = ""
                else -> Unit
            }
            val now = System.currentTimeMillis()
            if (event !is AgentEvent.ModelContentDelta || now - lastRender > 100) { render(controller.snapshot()); lastRender = now }
        } catch (error: DemoTeachingCapacityException) {
            // A full record is a limit the user can act on, not a save failure: stop the teaching
            // through the normal ending path, which writes a terminal status instead of leaving the
            // record "active". The status write itself is not charged against the evidence limit.
            notice(error.message)
            controller.finish()
        } catch (_: Exception) {
            notice("记录保存失败，教学已停止；已有记录保留")
            controller.cancel()
        }
    }

    private fun render(state: DemoTeachingSnapshot) {
        if (!state.active) return
        window.setTeachingState(true, state.completedSegments)
        val record = lastRecord
        val messages = record?.segments.orEmpty().takeLast(8).flatMap { segment -> buildList {
            add(AgentOverlayMessage("${segment.id}:user", "user", segment.instruction))
            if (segment.reply.isNotBlank()) add(AgentOverlayMessage("${segment.id}:assistant", "assistant", segment.reply))
        } }.toMutableList()
        if (streaming.isNotBlank()) messages += AgentOverlayMessage("$currentSegment:stream", "assistant", streaming)
        val run = controller.coordinator.snapshot().state
        window.bindTeachingSnapshot(AgentOverlaySnapshot(
            title = title, statusLabel = if (state.isRunning) run.statusLabel else "等待下一段指令",
            statusDetail = state.message, conversationId = state.sessionId, messages = messages,
            isBusy = state.isRunning, process = run.toChatProcessState().takeIf { state.isRunning || run.steps.isNotEmpty() }
        ))
    }

    private fun updateRecord(change: (DemoTeachingRecord) -> DemoTeachingRecord) {
        val id = checkNotNull(recordId)
        store.update(id, change); lastRecord = store.read(id)
    }
    private fun updateAction(segmentId: String, call: ToolCall, change: (DemoTeachingAction) -> DemoTeachingAction) {
        updateRecord { record -> record.copy(segments = record.segments.map { segment ->
            if (segment.id != segmentId) segment else {
                val old = segment.actions.firstOrNull { it.id == call.id }
                    ?: DemoTeachingAction(call.id, call.name, DemoTeachingEvidence.input(call))
                val changed = change(old)
                segment.copy(actions = if (segment.actions.any { it.id == call.id }) segment.actions.map { if (it.id == call.id) changed else it }
                    else segment.actions + changed)
            }
        }) }
    }

    private suspend fun capture(segmentId: String, call: ToolCall, before: Boolean) {
        val result = try {
            withTimeout(4000) {
                val service = checkNotNull(AgentAccessibilityService.instance)
                val page = DemoOperationCapture.page(service, context.packageName)
                check(page != null && !page.sensitive) { "页面不可读取或含输入内容，本次未保存截图" }
                val image = suspendCancellableCoroutine<DemoOperationImage> { continuation ->
                    DemoOperationCapture.screenshot(service) { r -> if (continuation.isActive) continuation.resumeWith(r) }
                }
                check(page == DemoOperationCapture.page(service, context.packageName)) { "截图期间页面发生变化，本次未保存截图" }
                val id = checkNotNull(recordId)
                withContext(Dispatchers.IO) { store.saveImage(id, image.bytes) }
            }.let { Result.success(it) }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            Result.failure<String>(IllegalStateException("截图超时，保留工具证据"))
        } catch (error: Exception) { Result.failure<String>(error) }
        updateAction(segmentId, call) { action -> result.fold(
            onSuccess = { if (before) action.copy(beforeImage = it) else action.copy(afterImage = it) },
            onFailure = { action.copy(gaps = action.gaps + ((if (before) "操作前：" else "操作后：") + (it.message ?: "截图不可用"))) }
        ) }
    }
    private fun notice(message: String?) { Toast.makeText(context, message ?: "暂时无法操作", Toast.LENGTH_LONG).show() }
    private fun isMutation(name: String) = name in setOf("launch_android_app", "launch_android_app_intent", "screen_perform_action", "screen_visual_gesture", "screen_gesture", "screen_press_key", "screen_global_action")
}
