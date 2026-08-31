package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRunInput
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.task.runtime.AgentTaskActionExecutionResult
import com.ugk.pi.task.runtime.AgentTaskPromptExecutor
import com.ugk.pi.task.runtime.AlarmManagerAgentTaskScheduler
import com.ugk.pi.task.runtime.AndroidAgentTaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** Connects the generic task runtime to this Demo's real AgentRuntime graph. */
internal class DemoScheduledTaskPromptExecutor(
    context: Context,
    private val processScope: DemoProcessScope? = null,
    private val conversationStoreOverride: DemoConversationStore? = null,
    private val conversationOverride: DemoConversation? = null,
    private val persistOutcomeOverride: ((DemoConversation, String, String) -> Unit)? = null,
    private val isProviderConfigured: (() -> Boolean)? = null,
    private val runtimeFactory: ((AgentToolDecorator) -> AgentRuntime)? = null
) : AgentTaskPromptExecutor {
    private val appContext = context.applicationContext

    override suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult {
        val action = task.action as? AgentTaskAction.RunAgentPrompt
            ?: return failure("任务动作不是 RUN_AGENT_PROMPT。")
        val conversationStore = conversationStoreOverride
            ?: if (conversationOverride == null || persistOutcomeOverride == null) {
                (processScope ?: DemoProcessScope.get(appContext)).conversationRuntime.conversationStore
            } else {
                null
            }
        val conversation = conversationOverride ?: conversationStore?.get(task.sessionId)
            ?: return failure("找不到任务关联的会话：${task.sessionId}")

        val prompt = buildScheduledPrompt(task, action)
        val configured = isProviderConfigured?.invoke()
            ?: (ApiProviderSettingsStore(appContext).activeConfig() != null)
        if (!configured) {
            val message = "定时任务未执行：请先在设置中配置 API 源。"
            persistOutcome(conversationStore, conversation, prompt, message)
            return failure(message)
        }

        val capabilityInterlock = DemoCapabilityInterlock(
            DemoScreenAutomationPolicy::isScreenWorkflowTool
        )
        val toolDecorator = capabilityInterlock.toolDecorator()
        val runtime = runtimeFactory?.invoke(toolDecorator)
            ?: DemoAgentRuntimeFactory.create(
                context = appContext,
                scheduleStore = AndroidAgentTaskStore(appContext),
                scheduleScheduler = AlarmManagerAgentTaskScheduler(appContext),
                confirmationPresenter = HeadlessConfirmationDialogPresenter,
                shouldBypassConfirmation = {
                    AgentAuthorizationSettingsStore(appContext).isFullAuthorizationEnabled()
                },
                toolDecorator = toolDecorator,
                supportsBackgroundPromptExecution = true,
                maxIterations = BACKGROUND_MAX_ITERATIONS,
                isBackgroundRun = true
            )

        var completedContent: String? = null
        var failureMessage: String? = null
        try {
            capabilityInterlock.onRunStarted()
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
                capabilityInterlock.onEvent(event)
                when (event) {
                    is AgentEvent.Completed -> completedContent = event.content
                    is AgentEvent.Failed -> failureMessage = event.message
                    else -> Unit
                }
            }
        } catch (error: CancellationException) {
            capabilityInterlock.onRunCancelled()
            throw error
        } catch (error: Throwable) {
            failureMessage = error.message ?: error::class.java.simpleName
        } finally {
            capabilityInterlock.onRunFinished()
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
        store: DemoConversationStore?,
        original: DemoConversation,
        prompt: String,
        result: String
    ) {
        persistOutcomeOverride?.invoke(original, prompt, result)
        if (persistOutcomeOverride != null) return
        val resolvedStore = requireNotNull(store) {
            "DemoScheduledTaskPromptExecutor requires a conversation store when no outcome writer is supplied"
        }
        // Append under the store lock instead of replacing a reloaded
        // snapshot: a foreground Activity saving its own (possibly stale)
        // snapshot must not erase this background turn, and an append cannot
        // overwrite concurrent UI writes either.
        val persisted = resolvedStore.appendMessagesAndFlush(
            conversationId = original.id,
            messages = listOf(
                DemoStoredMessage("user", prompt),
                DemoStoredMessage("assistant", result)
            )
        )
        // Null means the conversation was deleted while the background run
        // was in flight; the outcome is dropped rather than resurrecting a
        // conversation the user already deleted.
        if (persisted == null) return
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
