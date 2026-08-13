package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentSession

/**
 * Process-scoped state for the demo Activity.
 *
 * The demo is intentionally a single-screen test harness. Keeping the
 * conversation outside the Activity prevents a normal Activity recreation
 * (launcher re-entry, configuration change, or permission/settings return)
 * from looking like a full app restart. It does not hold Views or an Activity
 * reference, so it cannot keep the old window alive.
 */
data class DemoTranscriptEntry(
    val role: String,
    val text: String
)

object DemoActivityState {
    var session: AgentSession? = null
    val transcript: MutableList<DemoTranscriptEntry> = mutableListOf()

    var accessibilityPromptShown: Boolean = false
    var overlayPromptShown: Boolean = false
}
