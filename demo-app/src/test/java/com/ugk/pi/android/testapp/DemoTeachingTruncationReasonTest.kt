package com.ugk.pi.android.testapp

import com.ugk.pi.android.ModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The demo half of the truncation-reason set, pinned where it is enforced.
 *
 * `AgentRuntime.TRUNCATED_STOP_REASONS` in the core module and the copies in
 * `DemoTeachingResponseParser` / `DemoTeachingSopAgent` cannot share one list:
 * the demo module cannot reach an `internal` member of the published AAR, and
 * promoting it would widen the released API for a three-item set. The agreement
 * is therefore pinned by behavior on each side. The existing
 * `DemoTeachingCompilerTest.truncatedIntermediateNotesStopBeforeFinalGuideAndPreserveRecord`
 * covers `length` through the compiler; these cases cover `max_output_tokens`
 * and its all-caps spelling directly at this landing point, so deleting either
 * member from this module's set turns this red - which is what keeps the two
 * sides from drifting apart silently again.
 */
class DemoTeachingTruncationReasonTest {

    @Test
    fun maxOutputTokensStopReasonIsRefusedAsTruncated() {
        assertRefusedAsTruncated("max_output_tokens")
    }

    @Test
    fun upperCaseMaxOutputTokensStopReasonIsRefusedAsTruncated() {
        assertRefusedAsTruncated("MAX_OUTPUT_TOKENS")
    }

    /** Control: a clean stop is not truncation, so the guard is not a blanket refusal. */
    @Test
    fun stopReasonEndTurnIsNotRefusedAsTruncated() {
        DemoTeachingResponseParser.requireComplete(
            ModelResponse(content = "第一步：打开应用。", stopReason = "end_turn")
        )
    }

    private fun assertRefusedAsTruncated(stopReason: String) {
        val failure = assertThrows(DemoTeachingCompileException::class.java) {
            DemoTeachingResponseParser.requireComplete(
                ModelResponse(content = "第一步：打开应用。", stopReason = stopReason)
            )
        }
        assertEquals("OUTPUT_TRUNCATED", failure.code)
    }
}
