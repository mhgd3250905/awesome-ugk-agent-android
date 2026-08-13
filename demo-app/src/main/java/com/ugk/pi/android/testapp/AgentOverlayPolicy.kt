package com.ugk.pi.android.testapp

/**
 * A concise process item that can be rendered by the in-app chat or the
 * cross-app agent overlay.
 */
data class AgentOverlayStep(
    val title: String,
    val statusLabel: String,
    val detail: String? = null,
    val resultSummary: String? = null,
)

/**
 * The renderable state of the agent overlay.
 *
 * [steps] is exposed as a read-only Kotlin [List]. Callers should pass a
 * stable snapshot and must not mutate the source list after construction.
 */
data class AgentOverlaySnapshot(
    val title: String,
    val statusLabel: String,
    val statusDetail: String? = null,
    val latestMessage: String? = null,
    val latestMessageRole: String? = null,
    val steps: List<AgentOverlayStep> = emptyList(),
    val isBusy: Boolean = false,
    val queuedMessages: Int = 0,
)

/**
 * Pure, lifecycle-independent decisions for displaying the agent overlay.
 */
object AgentOverlayPolicy {

    /**
     * Returns true only while the activity is not foreground, an agent run is
     * active, and the system overlay permission has been granted.
     */
    fun shouldShowOnPause(
        agentRunActive: Boolean,
        overlayPermissionGranted: Boolean,
        activityResumed: Boolean,
    ): Boolean = !activityResumed && agentRunActive && overlayPermissionGranted

    /**
     * Returns true when a running agent needs a non-blocking permission prompt.
     */
    fun shouldOfferPermission(
        permissionGranted: Boolean,
        hasActiveRun: Boolean,
    ): Boolean = hasActiveRun && !permissionGranted
}
