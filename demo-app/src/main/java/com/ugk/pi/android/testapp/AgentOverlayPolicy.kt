package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogRequest

/**
 * A concise process item that can be rendered by the in-app chat or the
 * cross-app agent overlay.
 */
data class AgentOverlayStep(
    val id: String,
    val title: String,
    val statusLabel: String,
    val detail: String? = null,
    val resultSummary: String? = null,
)

data class AgentOverlayConfirmationButton(
    val id: String,
    val label: String
)

data class AgentOverlayConfirmation(
    val title: String,
    val message: String,
    val buttons: List<AgentOverlayConfirmationButton>
)

fun UserConfirmationDialogRequest.toOverlayConfirmation(): AgentOverlayConfirmation =
    AgentOverlayConfirmation(
        title = title,
        message = message,
        buttons = buttons.map { AgentOverlayConfirmationButton(it.id, it.label) }
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
    val pendingConfirmation: AgentOverlayConfirmation? = null,
    val isBusy: Boolean = false,
    val queuedMessages: Int = 0,
)

/**
 * Pure, lifecycle-independent decisions for displaying the agent overlay.
 */
object AgentOverlayPolicy {

    /**
     * Returns true while the host activity is in the background and the
     * system overlay permission has been granted.
     *
     * The overlay is intentionally not tied to an active Agent run. An idle
     * capsule is still useful as a cross-app entry point for starting a new
     * message, and it makes the background behavior predictable.
     */
    fun shouldShowOnPause(
        overlayPermissionGranted: Boolean,
        activityResumed: Boolean,
    ): Boolean = !activityResumed && overlayPermissionGranted

    /**
     * Returns true when a running agent needs a non-blocking permission prompt.
     */
    fun shouldOfferPermission(
        permissionGranted: Boolean,
        hasActiveRun: Boolean,
    ): Boolean = hasActiveRun && !permissionGranted
}
