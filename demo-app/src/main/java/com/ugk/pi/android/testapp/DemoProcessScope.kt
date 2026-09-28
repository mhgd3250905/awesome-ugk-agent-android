package com.ugk.pi.android.testapp

import android.content.Context
import android.content.Intent

/**
 * Composition root for the demo process.
 *
 * The Application owns one instance, while durable storage and the actual
 * overlay window remain lazy until a caller needs them.
 */
class DemoProcessScope private constructor(context: Context) {

    private val appContext = context.applicationContext

    val conversationRuntime: DemoConversationRuntime by lazy {
        DemoConversationRuntime(appContext)
    }
    val confirmationPresenter: ActivityUserConfirmationDialogPresenter =
        ActivityUserConfirmationDialogPresenter()
    val overlayController: DemoOverlayController = DemoOverlayController(appContext)
    private val recordingOwner = Any()
    private var openDraftAfterFinish: String? = null
    private val recordingUiOwners = mutableSetOf<Any>()
    private val workflowUiOwner = Any()
    private val workflowOverlay by lazy {
        DemoWorkflowOverlay(appContext,
            onStop = { workflowController.stop() },
            onOpen = { openOperationLearning(workflowController.snapshot().draftId) })
    }
    internal val workflowController: DemoWorkflowController by lazy {
        DemoWorkflowController(appContext, recorder = { operationRecorder },
            startBlockReason = {
                when {
                    conversationRuntime.runCoordinator.isRunning() -> "请先停止当前任务"
                    conversationRuntime.runCoordinator.snapshot().queuedMessages > 0 -> "请先处理排队消息"
                    delayedTasks.snapshot() !is DemoDelayedTaskState.Idle -> "请先停止当前定时任务"
                    urgentInteractionDispatcher.hasPending() -> "请先处理悬浮提醒中的操作"
                    overlayController.window.hasBlockingPresentation() -> "请先关闭提醒或确认窗口"
                    else -> null
                }
            },
            onFinished = { openOperationLearning(it) }
        ).apply {
            onCaptureVisibilityChanged = { hidden -> workflowOverlay.setCaptureHidden(hidden) }
            attach(workflowUiOwner) { state ->
                if (state.phase == DemoWorkflowPhase.RUNNING || state.phase == DemoWorkflowPhase.JUDGING) {
                    overlayController.window.hide()
                }
                overlayController.window.setExternalAutomationMode(
                    DemoCapabilityInterlock.isScreenOperationOwned() || conversationRuntime.capabilityInterlock.isCapabilityOwned()
                )
                workflowOverlay.bind(state)
            }
        }
    }
    private val recordingOverlay by lazy {
        DemoOperationRecordingOverlay(
            appContext,
            onPauseResume = {
                if (operationRecorder.snapshot().phase == DemoOperationPhase.RECORDING) operationRecorder.pause()
                else operationRecorder.resume().onFailure {
                    android.widget.Toast.makeText(appContext, it.message, android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onFinish = {
                openDraftAfterFinish = operationRecorder.snapshot().draftId
                operationRecorder.finish()
            },
            onOpen = { openOperationLearning() }
        )
    }
    internal val operationRecorder: DemoOperationRecorder by lazy {
        DemoOperationRecorder(
            context = appContext,
            startBlockReason = {
                when {
                    workflowController.isBusy() -> "请先结束当前整理或操作运行"
                    conversationRuntime.runCoordinator.isRunning() -> "请先停止当前任务，再开始演示。"
                    conversationRuntime.runCoordinator.snapshot().queuedMessages > 0 -> "请先处理或停止排队消息。"
                    delayedTasks.snapshot() !is DemoDelayedTaskState.Idle -> "请先停止待确认、等待中或执行中的定时任务。"
                    urgentInteractionDispatcher.hasPending() -> "请先处理悬浮提醒中的操作。"
                    overlayController.window.hasBlockingPresentation() -> "请先关闭当前提醒或确认窗口。"
                    else -> null
                }
            },
            acquireScreen = { DemoCapabilityInterlock.tryAcquireRecording(recordingOwner) },
            releaseScreen = { DemoCapabilityInterlock.releaseRecording(recordingOwner) }
        ).apply {
            onCaptureVisibilityChanged = { hidden -> recordingOverlay.setCaptureHidden(hidden) }
            attach(recordingOwner) { state ->
                if (state.phase != DemoOperationPhase.IDLE) overlayController.window.hide()
                overlayController.window.setExternalAutomationMode(
                    state.phase != DemoOperationPhase.IDLE || conversationRuntime.capabilityInterlock.isCapabilityOwned()
                )
                recordingOverlay.bind(state)
                if (state.phase == DemoOperationPhase.IDLE) {
                    val id = openDraftAfterFinish
                    openDraftAfterFinish = null
                    if (id != null) openOperationLearning(id, reviewCompletion = true)
                }
            }
        }
    }

    internal fun openOperationLearning(draftId: String? = null, reviewCompletion: Boolean = false) {
        appContext.startActivity(Intent(appContext, DemoOperationLearningActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            draftId?.let { putExtra(DemoOperationLearningActivity.EXTRA_DRAFT_ID, it) }
            if (reviewCompletion) putExtra(DemoOperationLearningActivity.EXTRA_REVIEW_COMPLETION, true)
        })
    }

    internal fun setOperationUiVisible(owner: Any, visible: Boolean) {
        if (visible) recordingUiOwners.add(owner) else recordingUiOwners.remove(owner)
        recordingOverlay.setHostVisible(recordingUiOwners.isNotEmpty())
        workflowOverlay.setHostVisible(recordingUiOwners.isNotEmpty())
    }

    internal fun stopOperationWork() {
        if (DemoCapabilityInterlock.isRecordingOwned()) operationRecorder.finish("用户停止录制")
        if (workflowController.isBusy()) workflowController.stop()
    }
    internal val urgentMessagePresenter = DemoUrgentMessagePresenter(overlayController) { sessionId ->
        conversationRuntime.activeConversationId?.takeIf { id ->
            conversationRuntime.sessionFor(id)?.id == sessionId
        }
    }
    internal val urgentInteractionDispatcher: DemoUrgentInteractionDispatcher by lazy {
        DemoUrgentInteractionDispatcher(appContext, this)
    }
    internal val delayedTasks: DemoDelayedTaskController by lazy {
        DemoDelayedTaskController(appContext, conversationRuntime) { task ->
            delayedMessageDispatcher.dispatch(task)
        }
    }
    private val delayedMessageDispatcher: DemoDelayedMessageDispatcher by lazy {
        DemoDelayedMessageDispatcher(appContext, this)
    }

    init {
        overlayController.onUrgentInteraction = { event -> urgentInteractionDispatcher.submit(event) }
        overlayController.setFallbackCommands(DemoOverlayCommands(
            onSend = {
                overlayController.window.addLog("请打开主对话后发送消息")
                false
            },
            onStop = {
                stopOperationWork()
                urgentInteractionDispatcher.cancelPending()
                conversationRuntime.runCoordinator.clearQueue()
                delayedTasks.stop()
                conversationRuntime.agentRuntime?.cancelAllPlugins()
                conversationRuntime.runCoordinator.stop()
                overlayController.window.setSending(false)
                overlayController.window.setStatus("已停止")
            },
            onOpenApp = {
                appContext.startActivity(Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
            },
            onHide = { overlayController.window.hide() },
            onDraftChanged = { value -> conversationRuntime.draft = value }
        ))
    }

    companion object {
        @Volatile
        private var shared: DemoProcessScope? = null

        fun get(context: Context): DemoProcessScope {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: DemoProcessScope(context.applicationContext).also { shared = it }
            }
        }
    }
}
