package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRunSource

/** Sends an elapsed delay as an ordinary new turn in the existing conversation. */
internal class DemoDelayedMessageDispatcher(
    context: Context,
    private val processScope: DemoProcessScope
) {
    private val appContext = context.applicationContext

    fun dispatch(task: DemoDelayedTask) {
        val conversationRuntime = processScope.conversationRuntime
        val conversationStore = conversationRuntime.conversationStore
        val coordinator = conversationRuntime.runCoordinator
        check(conversationRuntime.activeConversationId == task.conversationId) {
            "The active conversation changed while a delayed task was waiting."
        }
        check(!coordinator.isRunning()) { "The previous Agent turn is still running." }
        val runtime = checkNotNull(conversationRuntime.agentRuntime) {
            "The current conversation runtime is unavailable."
        }
        val conversation = checkNotNull(conversationStore.get(task.conversationId)) {
            "The delayed task's conversation no longer exists."
        }
        val session = conversationRuntime.sessionFor(task.conversationId)
            ?: createDemoAgentSession(conversation).also {
                conversationRuntime.rememberSession(task.conversationId, it)
            }
        check(session.id == task.sessionId) { "The original Agent session has changed." }

        // The timed turn is a visible user message, just as if it were sent
        // from the composer now. The source/task ID distinguish its origin in
        // runtime events without creating another conversation or runtime.
        checkNotNull(conversationStore.appendMessagesAndFlush(
            task.conversationId,
            listOf(DemoStoredMessage("user", task.instruction))
        )) { "Unable to append the delayed message to its conversation." }
        DemoAgentTraceStore(appContext).reset(task.conversationId, session.id)
        processScope.overlayController.window.apply {
            setSending(true)
            setStatus("定时任务执行中")
            addLog("到点执行：${task.instruction.take(48)}")
        }
        var resultPersisted = false
        coordinator.start(
            runtime = runtime,
            session = session,
            conversationId = task.conversationId,
            message = task.instruction,
            runLifecycle = conversationRuntime.capabilityInterlock,
            source = AgentRunSource.SCHEDULED_TASK,
            taskId = task.id,
            onOutcome = { event ->
                val completed = event is AgentEvent.Completed
                val answer = when (event) {
                    is AgentEvent.Completed -> event.content
                    is AgentEvent.Failed -> "任务未完成：${event.message}"
                    else -> ""
                }
                if (answer.isNotBlank()) {
                    checkNotNull(conversationStore.appendMessagesAndFlush(
                        task.conversationId,
                        listOf(DemoStoredMessage("assistant", answer))
                    )) { "Unable to persist the timed Agent result." }
                }
                resultPersisted = true
                processScope.overlayController.window.apply {
                    setSending(false)
                    setStatus(if (completed) "已完成" else "失败")
                    addLog(if (completed) "定时任务已完成" else "定时任务未完成")
                }
            },
            onFinished = {
                if (resultPersisted) {
                    processScope.delayedTasks.complete(task.id)
                } else {
                    processScope.delayedTasks.fail(task.id, "定时任务结果未能保存，请检查对话记录。")
                }
            }
        )
    }
}
