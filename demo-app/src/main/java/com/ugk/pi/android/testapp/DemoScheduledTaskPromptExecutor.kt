package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRunInput
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.task.runtime.AgentTaskActionExecutionResult
import com.ugk.pi.task.runtime.AgentTaskPromptExecutor
import com.ugk.pi.task.runtime.AlarmManagerAgentTaskScheduler
import com.ugk.pi.task.runtime.AndroidAgentTaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicBoolean

/** Connects the generic task runtime to this Demo's real AgentRuntime graph. */
internal class DemoScheduledTaskPromptExecutor(context: Context) : AgentTaskPromptExecutor {
    private val appContext = context.applicationContext

    override suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult {
        val action = task.action as? AgentTaskAction.RunAgentPrompt
            ?: return failure("任务动作不是 RUN_AGENT_PROMPT。")
        val conversationStore = DemoActivityState.conversationStore(appContext)
        val conversation = conversationStore.get(task.sessionId)
            ?: return failure("找不到任务关联的会话：${task.sessionId}")

        val prompt = buildScheduledPrompt(task, action)
        val configuredProvider = ApiProviderSettingsStore(appContext).activeConfig()
        if (configuredProvider == null) {
            val message = "定时任务未执行：请先在设置中配置 API 源。"
            persistOutcome(conversationStore, conversation, prompt, message)
            return failure(message)
        }

        val screenAutomationActive = AtomicBoolean(false)
        val runtime = DemoAgentRuntimeFactory.create(
            context = appContext,
            scheduleStore = AndroidAgentTaskStore(appContext),
            scheduleScheduler = AlarmManagerAgentTaskScheduler(appContext),
            confirmationPresenter = HeadlessConfirmationDialogPresenter,
            shouldBypassConfirmation = {
                AgentAuthorizationSettingsStore(appContext).isFullAuthorizationEnabled()
            },
            shouldBlockForScreenAutomation = { screenAutomationActive.get() },
            supportsBackgroundPromptExecution = true,
            maxIterations = BACKGROUND_MAX_ITERATIONS,
            isBackgroundRun = true
        )

        var completedContent: String? = null
        var failureMessage: String? = null
        try {
            val session = createDemoAgentSession(conversation)
            runtime.run(
                session = session,
                input = AgentRunInput(
                    content = prompt,
                    source = AgentRunSource.SCHEDULED_TASK,
                    taskId = task.id,
                    visibleInConversation = true
                )
            ).collect { event ->
                when (event) {
                    is AgentEvent.Completed -> completedContent = event.content
                    is AgentEvent.Failed -> failureMessage = event.message
                    is AgentEvent.ToolStarted -> {
                        if (event.call.name.startsWith("screen_")) {
                            screenAutomationActive.set(true)
                        }
                    }
                    is AgentEvent.ToolFinished -> {
                        if (event.result.name.startsWith("screen_")) {
                            screenAutomationActive.set(false)
                        }
                    }
                    else -> Unit
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failureMessage = error.message ?: error::class.java.simpleName
        } finally {
            runtime.close()
        }

        val successfulContent = completedContent?.trim().orEmpty()
        if (successfulContent.isNotBlank()) {
            persistOutcome(conversationStore, conversation, prompt, successfulContent)
            return AgentTaskActionExecutionResult(true, successfulContent)
        }

        val message = "定时任务未完成：${failureMessage?.takeIf { it.isNotBlank() } ?: "Agent 未返回最终结果。"}"
        persistOutcome(conversationStore, conversation, prompt, message)
        return failure(message)
    }

    private fun persistOutcome(
        store: DemoConversationStore,
        original: DemoConversation,
        prompt: String,
        result: String
    ) {
        // Reload before writing so an Activity-side message added while the
        // background run was in flight is not overwritten by an old snapshot.
        val conversation = store.get(original.id) ?: original
        conversation.messages += DemoStoredMessage("user", prompt)
        conversation.messages += DemoStoredMessage("assistant", result)
        conversation.updatedAt = System.currentTimeMillis()
        store.saveAndFlush(conversation)
    }

    private fun buildScheduledPrompt(
        task: AgentTask,
        action: AgentTaskAction.RunAgentPrompt
    ): String = buildString {
        appendLine("这是一个由用户提前安排的定时 Agent 任务，请现在执行。")
        appendLine("任务标题：${task.title}")
        appendLine()
        append(action.prompt.trim())
    }

    private fun failure(message: String): AgentTaskActionExecutionResult =
        AgentTaskActionExecutionResult(false, message)

    private companion object {
        const val BACKGROUND_MAX_ITERATIONS = 8
    }
}
