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
