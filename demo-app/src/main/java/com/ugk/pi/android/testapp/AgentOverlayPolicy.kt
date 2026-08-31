package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationTarget

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
    val label: String,
    val visualRole: ConfirmationVisualRole = ConfirmationVisualRole.WARNING
)

data class AgentOverlayConfirmationTarget(
    val toolName: String,
    val inputSummary: String
)

data class AgentOverlayConfirmation(
    val title: String,
    val message: String,
    val buttons: List<AgentOverlayConfirmationButton>,
    val target: AgentOverlayConfirmationTarget? = null
)

const val MAX_CONFIRMATION_INPUT_SUMMARY_CHARS = 512

fun UserConfirmationTarget.toConfirmationInputSummary(): String =
    input.toString().truncateForConfirmationDisplay(MAX_CONFIRMATION_INPUT_SUMMARY_CHARS)

fun UserConfirmationDialogRequest.toOverlayConfirmation(): AgentOverlayConfirmation {
    val visualRoles = confirmationVisualRoles()
    return AgentOverlayConfirmation(
        title = title,
        message = message,
        buttons = buttons.mapIndexed { index, button ->
            AgentOverlayConfirmationButton(
                id = button.id,
                label = button.label,
                visualRole = visualRoles[index]
            )
        },
        target = target?.let {
            AgentOverlayConfirmationTarget(
                toolName = it.toolName,
                inputSummary = it.toConfirmationInputSummary()
            )
        }
    )
}

private fun String.truncateForConfirmationDisplay(maxChars: Int): String {
    if (length <= maxChars) return this
    return take(maxChars - 1) + '\u2026'
}

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
     * system overlay permission has been granted, unless overlay is
     * suppressed for in-app navigation (e.g. jumping to SettingsActivity).
     *
     * The overlay is intentionally not tied to an active Agent run. An idle
     * capsule is still useful as a cross-app entry point for starting a new
     * message, and it makes the background behavior predictable.
     */
    fun shouldShowOnPause(
        overlayPermissionGranted: Boolean,
        activityResumed: Boolean,
        inAppNavigating: Boolean = false,
    ): Boolean = !activityResumed && overlayPermissionGranted && !inAppNavigating

    /**
     * Returns true when a running agent needs a non-blocking permission prompt.
     */
    fun shouldOfferPermission(
        permissionGranted: Boolean,
        hasActiveRun: Boolean,
    ): Boolean = hasActiveRun && !permissionGranted
}
