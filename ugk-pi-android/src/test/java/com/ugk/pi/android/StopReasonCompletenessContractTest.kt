package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stop reasons mean "the answer was cut short".
 *
 * The runtime's incomplete-response check is the only thing standing between a
 * truncated model answer and the transcript: a response with no tool calls and
 * non-blank content is otherwise stored and shown as the model's final answer.
 * It recognized two reasons and compared them without normalizing, while this
 * repo's own demo layer treats three reasons as truncation and reads them
 * case-insensitively (`DemoTeachingResponseParser.TRUNCATED_STOP_REASONS`,
 * `DemoTeachingSopAgent.TRUNCATED_STOP_REASONS`, and the three inline
 * `require(...)` literals in `DemoOperationStepReviewer`,
 * `DemoWorkflowCompiler` and `DemoWorkflowRunner`). A gateway that answers
 * `max_output_tokens` - the spelling Gemini-compatible endpoints use, and one
 * of the three this repo already rejects on the demo side - therefore finished
 * an agent run with half a sentence as the "complete" answer.
 *
 * Safety-driven stops (`content_filter`, `sensitive`, `refusal`) are deliberately
 * NOT retried here: the demo side refuses to *save* such an answer as a teaching
 * experience, which is a different question from showing the user what the model
 * stopped saying. That distinction is pinned by
 * `safetyStopReasonCompletesInsteadOfRetrying`.
 *
 * The `length` and `end_turn` cases are controls: they hold on the pre-fix code
 * and their job is to show the harness distinguishes "refused" from "accepted",
 * so a red on the other cases cannot be blamed on the fake provider.
 */
class StopReasonCompletenessContractTest {

    @Test
    fun maxOutputTokensStopReasonIsNotAcceptedAsAFinalAnswer() {
        assertTruncationRetriesInsteadOfCompleting("max_output_tokens")
    }

    @Test
    fun upperCaseMaxOutputTokensStopReasonIsNotAcceptedAsAFinalAnswer() {
        assertTruncationRetriesInsteadOfCompleting("MAX_OUTPUT_TOKENS")
    }

    @Test
    fun lengthStopReasonIsNotAcceptedAsAFinalAnswer() {
        assertTruncationRetriesInsteadOfCompleting("length")
    }

    @Test
    fun endTurnStopReasonCompletesWithTheAnswer() {
        val provider = AlwaysRespondingProvider(
            ModelResponse(content = "完整回答。", stopReason = "end_turn")
        )
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runBlocking { runtime.run(AgentSession(id = "end-turn"), "answer").toList() }

        assertEquals(AgentEvent.Completed("完整回答。"), events.last())
        assertEquals(1, provider.requests)
    }

    @Test
    fun safetyStopReasonCompletesInsteadOfRetrying() {
        val provider = AlwaysRespondingProvider(
            ModelResponse(content = "我不能协助这个请求。", stopReason = "refusal")
        )
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runBlocking { runtime.run(AgentSession(id = "refusal"), "answer").toList() }

        assertEquals(AgentEvent.Completed("我不能协助这个请求。"), events.last())
        assertEquals("a refusal is what the model said, not a transport failure", 1, provider.requests)
    }

    private fun assertTruncationRetriesInsteadOfCompleting(stopReason: String) {
        val provider = AlwaysRespondingProvider(
            ModelResponse(content = "半句话说到一半", stopReason = stopReason)
        )
        val session = AgentSession(id = "truncated-$stopReason")
        val runtime = AgentRuntime(provider, ToolRegistry())

        val events = runBlocking { runtime.run(session, "answer").toList() }

        val last = events.last()
        assertTrue(
            "expected the run to refuse the truncated answer, got: $last",
            last is AgentEvent.Failed
        )
        assertTrue(
            "expected the incomplete-response failure to be named, got: ${(last as? AgentEvent.Failed)?.message}",
            (last as? AgentEvent.Failed)?.message?.contains("incomplete final response") == true
        )
        assertEquals(
            "the truncated text must not reach the transcript as the final answer",
            emptyList<AgentMessage.Assistant>(),
            session.messages.filterIsInstance<AgentMessage.Assistant>()
                .filter { it.content.contains("半句话说到一半") }
        )
    }

    /** Answers every request with the same response, so retries are observable. */
    private class AlwaysRespondingProvider(private val response: ModelResponse) : LLMProvider {
        var requests = 0
            private set

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += 1
            return response
        }
    }
}
