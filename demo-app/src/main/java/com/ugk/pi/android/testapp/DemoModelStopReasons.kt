package com.ugk.pi.android.testapp

import java.util.Locale

/**
 * One reading of "the model did not finish", for every response this app consumes.
 *
 * The same three reasons used to be written out at six call sites with three
 * different behaviors: `DemoModelIntentRouter` knew only `length` and `max_tokens`
 * and compared them case-sensitively, the step reviewer / workflow compiler /
 * workflow runner knew all three but compared the raw value, and only the two
 * teaching readers normalized first. A gateway that spells the reason in caps
 * (`MAX_OUTPUT_TOKENS`) or pads it with a space therefore still got treated as a
 * complete answer by four of the six, while the core runtime refused the same
 * response - the divergence this object exists to remove.
 *
 * The core module keeps its own private `TRUNCATED_STOP_REASONS` in
 * `AgentRuntime.kt`, because `internal` members of the published AAR are not
 * visible from `demo-app` and promoting two constants would widen the released
 * API surface. Both sides are
 * pinned by behavior instead: `DemoModelStopReasonsTest` folds over every member of
 * the sets below, and every consumer in this module now goes through them, so
 * removing a member or the normalization turns this module red at the member that
 * was dropped.
 */
internal object DemoModelStopReasons {
    val truncated: Set<String> = setOf("length", "max_tokens", "max_output_tokens")
    val safety: Set<String> = setOf("content_filter", "sensitive", "refusal")

    fun normalized(stopReason: String?): String? = stopReason?.trim()?.lowercase(Locale.ROOT)

    fun isTruncated(stopReason: String?): Boolean = normalized(stopReason) in truncated

    fun isSafety(stopReason: String?): Boolean = normalized(stopReason) in safety
}
