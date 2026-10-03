package com.ugk.pi.android.testapp

import com.ugk.pi.android.ModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * One guard's behavior, at the landing point where it decides.
 *
 * The set itself is pinned member by member in `DemoModelStopReasonsTest`; this
 * class is the evidence that `DemoTeachingResponseParser.requireComplete` actually
 * consults it, in a code path the table cannot see. The existing
 * `DemoTeachingCompilerTest.truncatedIntermediateNotesStopBeforeFinalGuideAndPreserveRecord`
 * covers `length` through the compiler, so these two cases cover
 * `max_output_tokens` and its all-caps spelling here. The core module keeps its
 * own private copy of the set because `internal` members of the published AAR are
 * not visible from `demo-app`; the cross-side agreement is carried by
 * `StopReasonCompletenessContractTest` refusing the same reasons in core.
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
