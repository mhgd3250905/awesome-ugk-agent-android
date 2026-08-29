package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.android.AgentTaskSchedule
import com.ugk.pi.android.AgentTaskStatus
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.AgentToolInterlockErrorCodes
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolRegistry
import com.ugk.pi.android.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScheduledTaskPromptExecutorTest {
    @Test
    fun backgroundExecutorKeepsTerminalBlockedAfterWorkflowFinishesAndReleasesOnCompleted() =
        runBlocking {
            val secondProviderEntered = CountDownLatch(1)
            val releaseSecondProvider = CountDownLatch(1)
            val terminalDelegate = RecordingTool("terminal_bash_execute")
            lateinit var guardedTerminal: AgentTool
            val executor = newExecutor { decorator ->
                guardedTerminal = decorator.decorate(terminalDelegate)
                AgentRuntime.Builder()
                    .toolRegistry(
                        ToolRegistry()
                            .register(RecordingTool("screen_read_ui_tree"))
                            .register(guardedTerminal)
                    )
                    .llmProvider(
                        sequenceProvider(
                            secondResponse = {
                                secondProviderEntered.countDown()
                                releaseSecondProvider.await()
                                ModelResponse(content = "background done")
                            }
                        )
                    )
                    .build()
            }

            val run = async(Dispatchers.Default) { executor.execute(sampleTask()) }
            assertTrue(secondProviderEntered.await(2, TimeUnit.SECONDS))

            val blocked = guardedTerminal.execute(
                ToolCall("during-run", guardedTerminal.name, JsonObject(emptyMap())),
                ToolExecutionContext(sessionId = SESSION_ID)
            )
            assertTrue(blocked.isError)
            assertEquals(
                AgentToolInterlockErrorCodes.BLOCKED,
                blocked.metadata["code"]?.toString()?.trim('"')
            )
            assertEquals(0, terminalDelegate.calls)

            releaseSecondProvider.countDown()
            val result = run.await()
            assertTrue(result.success)

            val allowed = guardedTerminal.execute(
                ToolCall("after-run", guardedTerminal.name, JsonObject(emptyMap())),
                ToolExecutionContext(sessionId = SESSION_ID)
            )
            assertFalse(allowed.isError)
            assertEquals(1, terminalDelegate.calls)
        }

    @Test
    fun backgroundExecutorReleasesInterlockOnFailedRuntime() = runBlocking {
        val terminalDelegate = RecordingTool("terminal_bash_execute")
        lateinit var guardedTerminal: AgentTool
        val executor = newExecutor { decorator ->
            guardedTerminal = decorator.decorate(terminalDelegate)
            AgentRuntime.Builder()
                .toolRegistry(backgroundTools(guardedTerminal))
                .llmProvider(
                    sequenceProvider(secondResponse = { error("background provider failed") })
                )
                .build()
        }

        val result = executor.execute(sampleTask())

        assertFalse(result.success)
        val allowed = guardedTerminal.execute(
            ToolCall("after-failed-run", guardedTerminal.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = SESSION_ID)
        )
        assertFalse(allowed.isError)
        assertEquals(1, terminalDelegate.calls)
    }

    @Test
    fun backgroundExecutorReleasesInterlockOnCancellationFinally() = runBlocking {
        val secondProviderEntered = CountDownLatch(1)
        val releaseSecondProvider = CountDownLatch(1)
        val terminalDelegate = RecordingTool("terminal_bash_execute")
        lateinit var guardedTerminal: AgentTool
        val executor = newExecutor { decorator ->
            guardedTerminal = decorator.decorate(terminalDelegate)
            AgentRuntime.Builder()
                .toolRegistry(backgroundTools(guardedTerminal))
                .llmProvider(
                    sequenceProvider(
                        secondResponse = {
                            secondProviderEntered.countDown()
                            releaseSecondProvider.await()
                            ModelResponse(content = "should be cancelled")
                        }
                    )
                )
                .build()
        }

        val run = async(Dispatchers.Default) { executor.execute(sampleTask()) }
        assertTrue(secondProviderEntered.await(2, TimeUnit.SECONDS))
        run.cancel()
        releaseSecondProvider.countDown()
        run.join()

        assertTrue(run.isCancelled)
        val allowed = guardedTerminal.execute(
            ToolCall("after-cancel", guardedTerminal.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = SESSION_ID)
        )
        assertFalse(allowed.isError)
        assertEquals(1, terminalDelegate.calls)
    }

    private fun newExecutor(
        runtimeFactory: (AgentToolDecorator) -> AgentRuntime
    ): DemoScheduledTaskPromptExecutor {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        return DemoScheduledTaskPromptExecutor(
            context = context,
            processScope = null,
            conversationOverride = DemoConversation(
                id = SESSION_ID,
                title = "background test",
                createdAt = 0L,
                updatedAt = 0L
            ),
            persistOutcomeOverride = { _, _, _ -> },
            isProviderConfigured = { true },
            runtimeFactory = runtimeFactory
        )
    }

    private fun backgroundTools(guardedTerminal: AgentTool): ToolRegistry = ToolRegistry()
        .register(RecordingTool("screen_read_ui_tree"))
        .register(guardedTerminal)

    private fun sequenceProvider(
        secondResponse: suspend () -> ModelResponse
    ): LLMProvider = object : LLMProvider {
        private var requestCount = 0

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requestCount++
            return if (requestCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(
                        ToolCall("screen", "screen_read_ui_tree", JsonObject(emptyMap())),
                        ToolCall("terminal", "terminal_bash_execute", JsonObject(emptyMap()))
                    )
                )
            } else {
                secondResponse()
            }
        }
    }

    private fun sampleTask(): AgentTask = AgentTask(
        id = "scheduled-interlock-test",
        sessionId = SESSION_ID,
        title = "background interlock",
        schedule = AgentTaskSchedule.OneShot(0L),
        action = AgentTaskAction.RunAgentPrompt("run screen workflow"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        nextRunAtMillis = 0L
    )

    private class RecordingTool(
        override val name: String
    ) : AgentTool {
        var calls = 0
        override val description: String = name
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            calls++
            return ToolResult(call.id, name, "ok")
        }
    }

    private companion object {
        const val SESSION_ID = "background-session"
    }
}
