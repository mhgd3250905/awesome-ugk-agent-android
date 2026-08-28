package com.ugk.pi.android.testapp

import kotlinx.coroutines.Dispatchers
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
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
    fun stopInvalidatesOldRunAndStillBoundsSessionAfterCancellation() {
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
        assertTrue(session.messages.size <= 160)
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
