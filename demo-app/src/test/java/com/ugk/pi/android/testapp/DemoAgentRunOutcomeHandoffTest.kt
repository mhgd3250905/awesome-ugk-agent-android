package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.AgentRuntime
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the only channel by which a run's outcome owner reports "I could not
 * write this answer": throwing out of the `onOutcome` observer.
 *
 * `DemoAgentRunCoordinator.dispatch` computes
 * `handled = observer != null && runCatching { observer(event) }.isSuccess`, and
 * `MainActivity`'s SDK_EVENT and SCHEDULED_TASK branches persist the assistant
 * message exactly when `pendingOutcome?.handledByProcessOwner != true`. So the
 * swallowed exception is not "noise" - it is the handoff signal, and an observer
 * that catches everything makes the UI look finished while the answer is never
 * stored anywhere. This test exists because round 12's own first fix did exactly
 * that and was caught only on review.
 */
class DemoAgentRunOutcomeHandoffTest {
    @Test
    fun anObserverThatThrowsHandsTheOutcomeBackToTheConversationUi() {
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        val observerRan = AtomicBoolean(false)

        coordinator.start(
            runtime = runtimeReturning("回答内容"),
            session = AgentSession("session-throwing-observer"),
            conversationId = "conversation-1",
            message = "hello",
            source = AgentRunSource.SDK_EVENT,
            onOutcome = { event ->
                assertNotNull("a terminal event reaches the observer", event)
                observerRan.set(true)
                throw IllegalStateException("Unable to persist the urgent interaction result.")
            }
        )

        // `dispatch` assigns pendingOutcome AFTER invoking the observer, so the
        // assertion has to wait for the state it reads rather than for the
        // observer. Waiting on the observer alone passed in a targeted run and
        // failed in the full gate under load - a race in this test, not in the
        // production path.
        val outcome = awaitOutcome(coordinator)
        assertTrue("the observer was never invoked", observerRan.get())
        assertEquals(
            "a throwing observer is the retry channel MainActivity reads; reporting it as handled " +
                "silently drops the answer",
            false,
            outcome.handledByProcessOwner
        )
        assertEquals(AgentRunSource.SDK_EVENT, outcome.source)
    }

    @Test
    fun anObserverThatReturnsClaimsTheOutcomeSoTheUiDoesNotWriteItTwice() {
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        val observerRan = AtomicBoolean(false)

        coordinator.start(
            runtime = runtimeReturning("回答内容"),
            session = AgentSession("session-returning-observer"),
            conversationId = "conversation-2",
            message = "hello",
            source = AgentRunSource.SDK_EVENT,
            onOutcome = { observerRan.set(true) }
        )

        val outcome = awaitOutcome(coordinator)
        assertTrue("the observer was never invoked", observerRan.get())
        assertEquals(true, outcome.handledByProcessOwner)
    }

    private fun awaitOutcome(coordinator: DemoAgentRunCoordinator): DemoAgentRunOutcome {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            coordinator.snapshot().pendingOutcome?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no pending outcome within 10s; the run never reached a terminal event")
    }

    private fun runtimeReturning(content: String): AgentRuntime = AgentRuntime.Builder()
        .llmProvider(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                ModelResponse(content = content)
        })
        .build()
}
