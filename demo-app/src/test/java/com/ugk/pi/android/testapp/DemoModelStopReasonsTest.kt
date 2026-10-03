package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo side of "the model did not finish", pinned member by member.
 *
 * Every truncation guard in `demo-app` now reads through `DemoModelStopReasons`
 * (`DemoOperationStepReviewer`, `DemoWorkflowCompiler`, `DemoWorkflowRunner`,
 * `DemoModelIntentRouter`, `DemoTeachingResponseParser`, `DemoTeachingSopAgent`),
 * which is what makes a single table like this one sufficient: removing a member
 * from the set, or dropping the normalization, turns the named row red. Before
 * this, four of those six compared the raw value and the router did not know
 * `max_output_tokens` at all, so the same response was "truncated" to one guard
 * and "complete" to another.
 *
 * The expected sets are written as literals on purpose: comparing the production
 * set against itself could never fail.
 */
class DemoModelStopReasonsTest {

    @Test
    fun truncatedSetIsExactlyTheReasonsThisRepoRefuses() {
        assertEquals(setOf("length", "max_tokens", "max_output_tokens"), DemoModelStopReasons.truncated)
        assertEquals(
            setOf("content_filter", "sensitive", "refusal"),
            DemoModelStopReasons.safety
        )
        assertEquals(
            "a reason cannot mean both truncation and refusal, the two guards answer differently",
            emptySet<String>(),
            DemoModelStopReasons.truncated.intersect(DemoModelStopReasons.safety)
        )
    }

    @Test
    fun everyTruncatedReasonIsRecognizedInEverySpellingTheWireUses() {
        val failures = mutableListOf<String>()
        // A literal list, not the production set: iterating `truncated` itself would
        // make this a tautology - drop a member from the set and this case simply
        // stops checking it and stays green, which is exactly what the first version
        // of this test did (the mutation matrix caught it).
        listOf("length", "max_tokens", "max_output_tokens").forEach { reason ->
            listOf(reason, reason.uppercase(), " $reason ", reason.replaceFirstChar { it.uppercase() })
                .forEach { spelling ->
                    if (!DemoModelStopReasons.isTruncated(spelling)) {
                        failures += "reason $reason not recognized when spelled \"$spelling\""
                    }
                }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun reasonsThatMeanTheAnswerFinishedAreNotTruncation() {
        listOf("stop", "end_turn", "tool_use", null, "").forEach { reason ->
            assertFalse("expected $reason to be a complete answer", DemoModelStopReasons.isTruncated(reason))
        }
    }

    /**
     * Control on the guard itself: the normalization is not so loose that it turns a
     * completion reason into a truncation one, which would fail every ordinary run.
     */
    @Test
    fun normalizationKeepsUnrelatedReasonsOut() {
        assertFalse(DemoModelStopReasons.isTruncated("MAX_TOKENS_USED"))
        assertFalse(DemoModelStopReasons.isSafety("end_turn"))
        assertTrue(DemoModelStopReasons.isSafety(" Content_Filter "))
    }
}
