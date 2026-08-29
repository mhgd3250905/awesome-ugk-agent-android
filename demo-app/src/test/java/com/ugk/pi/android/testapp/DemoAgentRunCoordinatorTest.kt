package com.ugk.pi.android.testapp

import kotlinx.coroutines.Dispatchers
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolRegistry
import com.ugk.pi.android.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoAgentRunCoordinatorTest {

    @Test
    fun overlayQueueIsBounded() {
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)

        repeat(20) { index ->
            assertTrue(coordinator.enqueue("message-$index"))
        }
        assertFalse(coordinator.enqueue("message-over-limit"))
        assertEquals(20, coordinator.snapshot().queuedMessages)
    }

    @Test
    fun startPublishesBusyBeforeRuntimeEmitsItsFirstEvent() {
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        val session = AgentSession("session-start")
        val finished = CountDownLatch(1)
        val runtime = runtimeReturning("done")

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(runtime, session, "conversation-start", "hello")

        assertEquals(DemoRunStatus.THINKING, coordinator.snapshot().state.status)
        assertTrue(coordinator.isRunning())
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(DemoRunStatus.COMPLETED, coordinator.snapshot().state.status)
    }

    @Test
    fun sessionFinalizerRunsForACompletedRun() {
        var finalizedSession: AgentSession? = null
        val finished = CountDownLatch(1)
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined) { session ->
            finalizedSession = session
        }
        val session = AgentSession("session-finalizer-complete")

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(runtimeReturning("done"), session, "conversation-finalizer-complete", "hello")

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertSame(session, finalizedSession)
    }

    @Test
    fun sessionFinalizerRunsForAFailedRun() {
        var finalizedSession: AgentSession? = null
        val finished = CountDownLatch(1)
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined) { session ->
            finalizedSession = session
        }
        val session = AgentSession("session-finalizer-failed")

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(runtimeFailing("provider failed"), session, "conversation-finalizer-failed", "hello")

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertSame(session, finalizedSession)
        assertEquals(DemoRunStatus.FAILED, coordinator.snapshot().state.status)
    }

    @Test
    fun failingSessionFinalizerDoesNotBlockCleanupOrNextRun() {
        val firstFinished = CountDownLatch(1)
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined) {
            error("boom")
        }
        val firstSession = AgentSession("session-finalizer-error-first")

        coordinator.attach(Any(), onEvent = {}, onFinished = { firstFinished.countDown() })
        coordinator.start(
            runtimeReturning("first"),
            firstSession,
            "conversation-finalizer-error-first",
            "hello"
        )

        assertTrue(firstFinished.await(2, TimeUnit.SECONDS))
        assertFalse(coordinator.isRunning())
        assertEquals(DemoRunStatus.COMPLETED, coordinator.snapshot().state.status)

        val secondFinished = CountDownLatch(1)
        val secondSession = AgentSession("session-finalizer-error-second")
        coordinator.attach(Any(), onEvent = {}, onFinished = { secondFinished.countDown() })
        coordinator.start(
            runtimeReturning("second"),
            secondSession,
            "conversation-finalizer-error-second",
            "again"
        )

        assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
        assertFalse(coordinator.isRunning())
        assertEquals(DemoRunStatus.COMPLETED, coordinator.snapshot().state.status)
    }

    @Test
    fun stopInvalidatesOldRunAndStillRunsSessionFinalizerAfterCancellation() {
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        var finalizedSession: AgentSession? = null
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined) { session ->
            finalizedSession = session
        }
        val session = AgentSession("session-stop")
        val finished = CountDownLatch(1)
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    enteredProvider.countDown()
                    releaseProvider.await()
                    return ModelResponse(content = "should not complete")
                }
            })
            .build()

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(runtime, session, "conversation-stop", "hello")
        assertTrue(enteredProvider.await(2, TimeUnit.SECONDS))
        val stopped = coordinator.stop()
        assertEquals(DemoRunStatus.CANCELLED, stopped.state.status)

        releaseProvider.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(DemoRunStatus.CANCELLED, coordinator.snapshot().state.status)
        assertSame(session, finalizedSession)
    }

    @Test
    fun coordinatorKeepsTerminalBlockedAfterWorkflowToolFinishes() {
        val interlock = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val terminalDelegate = RecordingTool("terminal_bash_execute")
        val tools = ToolRegistry()
            .register(RecordingTool("screen_read_ui_tree"))
            .register(interlock.toolDecorator().decorate(terminalDelegate))
        var requestCount = 0
        val runtime = AgentRuntime.Builder()
            .toolRegistry(tools)
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requestCount++
                    return if (requestCount == 1) {
                        ModelResponse(
                            content = "",
                            toolCalls = listOf(
                                ToolCall("screen", "screen_read_ui_tree", JsonObject(emptyMap())),
                                ToolCall(
                                    "terminal",
                                    "terminal_bash_execute",
                                    JsonObject(emptyMap())
                                )
                            )
                        )
                    } else {
                        ModelResponse(content = "done")
                    }
                }
            })
            .build()
        val finished = CountDownLatch(1)
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(
            runtime = runtime,
            session = AgentSession("session-interlock-coordinator"),
            conversationId = "conversation-interlock-coordinator",
            message = "run",
            runLifecycle = interlock
        )

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, terminalDelegate.calls)
        assertFalse(interlock.isCapabilityOwned())
        assertEquals(DemoRunStatus.COMPLETED, coordinator.snapshot().state.status)
    }

    @Test
    fun coordinatorCancelReleasesInterlockAfterWorkflowToolFinishes() = runBlocking {
        val secondProviderEntered = CountDownLatch(1)
        val releaseSecondProvider = CountDownLatch(1)
        val interlock = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val terminalDelegate = RecordingTool("terminal_bash_execute")
        val terminal = interlock.toolDecorator().decorate(terminalDelegate)
        val runtime = AgentRuntime.Builder()
            .toolRegistry(
                ToolRegistry()
                    .register(RecordingTool("screen_read_ui_tree"))
                    .register(terminal)
            )
            .llmProvider(object : LLMProvider {
                var requestCount = 0

                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requestCount++
                    if (requestCount == 1) {
                        return ModelResponse(
                            content = "",
                            toolCalls = listOf(
                                ToolCall("screen", "screen_read_ui_tree", JsonObject(emptyMap()))
                            )
                        )
                    }
                    secondProviderEntered.countDown()
                    releaseSecondProvider.await()
                    return ModelResponse(content = "should be cancelled")
                }
            })
            .build()
        val finished = CountDownLatch(1)
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        val terminalCall = ToolCall("terminal-before-stop", terminal.name, JsonObject(emptyMap()))

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(
            runtime = runtime,
            session = AgentSession("session-interlock-cancel"),
            conversationId = "conversation-interlock-cancel",
            message = "run",
            runLifecycle = interlock
        )

        assertTrue(secondProviderEntered.await(2, TimeUnit.SECONDS))
        assertTrue(interlock.isCapabilityOwned())
        val blockedBeforeStop = terminal.execute(
            terminalCall,
            ToolExecutionContext(sessionId = "session-interlock-cancel")
        )
        assertTrue(blockedBeforeStop.isError)
        assertEquals(0, terminalDelegate.calls)

        val stopped = coordinator.stop()
        assertEquals(DemoRunStatus.CANCELLED, stopped.state.status)
        assertFalse(interlock.isCapabilityOwned())

        releaseSecondProvider.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        interlock.onRunFinished()
        val allowedAfterCancel = terminal.execute(
            terminalCall.copy(id = "terminal-after-stop"),
            ToolExecutionContext(sessionId = "session-interlock-cancel")
        )
        assertFalse(allowedAfterCancel.isError)
        assertEquals(1, terminalDelegate.calls)
    }

    @Test
    fun coordinatorReleasesInterlockAfterRuntimeFailureAndFinallyIsIdempotent() = runBlocking {
        val interlock = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val terminalDelegate = RecordingTool("terminal_bash_execute")
        val terminal = interlock.toolDecorator().decorate(terminalDelegate)
        var requestCount = 0
        val runtime = AgentRuntime.Builder()
            .toolRegistry(
                ToolRegistry()
                    .register(RecordingTool("screen_read_ui_tree"))
                    .register(terminal)
            )
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requestCount++
                    return if (requestCount == 1) {
                        ModelResponse(
                            content = "",
                            toolCalls = listOf(
                                ToolCall("screen", "screen_read_ui_tree", JsonObject(emptyMap()))
                            )
                        )
                    } else {
                        error("provider failed after workflow")
                    }
                }
            })
            .build()
        val finished = CountDownLatch(1)
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)

        coordinator.attach(Any(), onEvent = {}, onFinished = { finished.countDown() })
        coordinator.start(
            runtime = runtime,
            session = AgentSession("session-interlock-failure"),
            conversationId = "conversation-interlock-failure",
            message = "run",
            runLifecycle = interlock
        )

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(DemoRunStatus.FAILED, coordinator.snapshot().state.status)
        assertFalse(interlock.isCapabilityOwned())

        interlock.onRunFinished()
        val allowedAfterFailure = terminal.execute(
            ToolCall("terminal-after-failure", terminal.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session-interlock-failure")
        )
        assertFalse(allowedAfterFailure.isError)
        assertEquals(1, terminalDelegate.calls)
    }

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

    private fun runtimeReturning(content: String): AgentRuntime = AgentRuntime.Builder()
        .llmProvider(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                ModelResponse(content = content)
        })
        .build()

    private fun runtimeFailing(message: String): AgentRuntime = AgentRuntime.Builder()
        .llmProvider(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                error(message)
            }
        })
        .build()
}
