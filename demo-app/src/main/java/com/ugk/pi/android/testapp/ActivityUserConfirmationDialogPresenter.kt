package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Host for confirmations while the Activity is covered by another app. */
interface ConfirmationOverlayHost {
    fun showConfirmation(
        request: UserConfirmationDialogRequest,
        onResult: (String) -> Unit
    ): Boolean

    fun hideConfirmation()
}

/**
 * Presents agent confirmations on the currently visible surface.
 *
 * An Activity dialog is preferred in the foreground. When the Activity pauses,
 * the same suspended confirmation is moved into the cross-app overlay instead
 * of being cancelled or left behind where the user cannot see it.
 */
class ActivityUserConfirmationDialogPresenter(
    private var activity: Activity? = null,
    private var isActivityResumed: () -> Boolean = { false },
    private var isFullAuthorizationEnabled: () -> Boolean = { false },
    private var overlayHost: ConfirmationOverlayHost? = null
) : UserConfirmationDialogPresenter {
    private var active: PendingConfirmation? = null
    private var density = 1f

    /** Attach the current Activity without replacing an in-flight request. */
    fun attach(
        activity: Activity,
        isResumed: () -> Boolean,
        isFullAuthorizationEnabled: () -> Boolean,
        overlayHost: ConfirmationOverlayHost?
    ) {
        this.activity = activity
        this.isActivityResumed = isResumed
        this.isFullAuthorizationEnabled = isFullAuthorizationEnabled
        this.overlayHost = overlayHost
        density = activity.resources.displayMetrics.density
        if (isResumed()) runOnMain { active?.moveToActivity() }
    }

    /** Detach a destroyed Activity while keeping an in-flight confirmation. */
    fun detach(activity: Activity) {
        runOnMain {
            if (this.activity !== activity) return@runOnMain
            active?.moveToOverlay()
            this.activity = null
            this.isActivityResumed = { false }
            this.isFullAuthorizationEnabled = { false }
        }
    }

    override suspend fun showConfirmationDialog(
        request: UserConfirmationDialogRequest
    ): UserConfirmationDialogResult = withContext(Dispatchers.Main.immediate) {
        val buttons = request.buttons.ifEmpty {
            listOf(UserConfirmationDialogButton(CANCEL_BUTTON_ID, "Cancel"))
        }
        if (isFullAuthorizationEnabled()) {
            return@withContext UserConfirmationDialogResult(
                AgentAuthorizationPolicy.autoApproveButtonId(buttons)
            )
        }

        active?.cancelFromLifecycle()
        suspendCancellableCoroutine { continuation ->
            val fallbackId = buttons.firstOrNull { it.isCancellationButton() }?.id ?: CANCEL_BUTTON_ID
            val pending = PendingConfirmation(
                request = request.copy(buttons = buttons),
                buttons = buttons,
                continuation = continuation,
                fallbackResult = UserConfirmationDialogResult(fallbackId)
            )
            active = pending
            continuation.invokeOnCancellation {
                runOnMain { pending.cancelFromCoroutine() }
            }
            pending.presentInitial()
        }
    }

    /** Called after the Activity has entered the background. */
    fun onActivityPaused() {
        runOnMain { active?.moveToOverlay() }
    }

    /** Called after the Activity has become the visible foreground surface. */
    fun onActivityResumed() {
        runOnMain { active?.moveToActivity() }
    }

    /** Cancels an open confirmation when the owning Activity is destroyed. */
    fun cancelPending() {
        runOnMain { active?.cancelFromLifecycle() }
    }

    /**
     * Release every host reference when the owning Activity really finishes.
     * A configuration change uses [detach] so an in-flight request can move to
     * the overlay; a terminal Activity finish must not retain the old window
     * or its authorization callbacks.
     */
    fun release() {
        runOnMain {
            active?.cancelFromLifecycle()
            active = null
            activity = null
            isActivityResumed = { false }
            isFullAuthorizationEnabled = { false }
            overlayHost = null
        }
    }

    private fun createButtonPanel(
        buttons: List<UserConfirmationDialogButton>,
        request: UserConfirmationDialogRequest,
        onSelected: (String) -> Unit
    ): View {
        val hostActivity = requireNotNull(activity) { "Activity confirmation host is not attached" }
        val isHorizontal = buttons.size <= 2
        val visualRoles = request.confirmationVisualRoles(buttons)
        return LinearLayout(hostActivity).apply {
            orientation = if (isHorizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(16), dp(4), dp(16), dp(8))

            buttons.forEachIndexed { index, button ->
                val visualRole = visualRoles[index]
                val actionButton = Button(hostActivity).apply {
                    text = button.label
                    isAllCaps = false
                    minHeight = dp(48)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setTextColor(
                        when (visualRole) {
                            ConfirmationVisualRole.CANCEL -> Ui.stateColorList(
                                normal = Ui.TextSecondary,
                                pressed = Ui.TextPrimary
                            )
                            ConfirmationVisualRole.PRIMARY -> Ui.stateColorList(
                                normal = Ui.OnPrimary,
                                pressed = Ui.OnPrimary
                            )
                            ConfirmationVisualRole.WARNING -> Ui.stateColorList(
                                normal = Ui.WarningOnContainer,
                                pressed = Ui.WarningOnContainer
                            )
                            ConfirmationVisualRole.DANGER -> Ui.stateColorList(
                                normal = Ui.DangerOnContainer,
                                pressed = Ui.OnDanger
                            )
                        }
                    )
                    background = Ui.clickableRounded(
                        hostActivity,
                        normalColor = when (visualRole) {
                            ConfirmationVisualRole.CANCEL -> Ui.SurfaceSubtle
                            ConfirmationVisualRole.PRIMARY -> Ui.Primary
                            ConfirmationVisualRole.WARNING -> Ui.WarningSoft
                            ConfirmationVisualRole.DANGER -> Ui.DangerSoft
                        },
                        pressedColor = when (visualRole) {
                            ConfirmationVisualRole.CANCEL -> Ui.SurfaceSoft
                            ConfirmationVisualRole.PRIMARY -> Ui.PrimaryPressed
                            ConfirmationVisualRole.WARNING -> Ui.SurfaceSoft
                            ConfirmationVisualRole.DANGER -> Ui.Danger
                        },
                        radiusDp = 10,
                        strokeColor = when (visualRole) {
                            ConfirmationVisualRole.CANCEL -> Ui.OutlineSubtle
                            ConfirmationVisualRole.PRIMARY -> Ui.Primary
                            ConfirmationVisualRole.WARNING -> Ui.Warning
                            ConfirmationVisualRole.DANGER -> Ui.Danger
                        }
                    )
                    setOnClickListener { onSelected(button.id) }
                }
                val layoutParams = if (isHorizontal) {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                if (index > 0) {
                    if (isHorizontal) {
                        layoutParams.marginStart = dp(4)
                    } else {
                        layoutParams.topMargin = dp(4)
                    }
                }
                addView(actionButton, layoutParams)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * density + 0.5f).toInt()
    }

    private fun isActivityUsable(): Boolean {
        val hostActivity = activity ?: return false
        return !hostActivity.isFinishing && !hostActivity.isDestroyed
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            activity?.runOnUiThread(action)
                ?: Handler(Looper.getMainLooper()).post(action)
        }
    }

    private inner class PendingConfirmation(
        private val request: UserConfirmationDialogRequest,
        private val buttons: List<UserConfirmationDialogButton>,
        private val continuation: CancellableContinuation<UserConfirmationDialogResult>,
        private val fallbackResult: UserConfirmationDialogResult
    ) {
        private var dialog: AlertDialog? = null
        private var overlayVisible = false
        private var suppressDialogDismiss = false
        private var completed = false

        fun presentInitial() {
            if (isFullAuthorizationEnabled()) {
                finish(
                    UserConfirmationDialogResult(AgentAuthorizationPolicy.autoApproveButtonId(buttons)),
                    dismiss = true
                )
            } else if (isActivityResumed() && isActivityUsable()) {
                presentActivity()
            } else {
                presentOverlayIfPossible()
            }
        }

        fun moveToOverlay() {
            if (completed) return
            if (isFullAuthorizationEnabled()) {
                finish(
                    UserConfirmationDialogResult(AgentAuthorizationPolicy.autoApproveButtonId(buttons)),
                    dismiss = true
                )
                return
            }
            presentOverlayIfPossible()
            dismissActivityDialogForTransition()
        }

        fun moveToActivity() {
            if (completed || !isActivityResumed() || !isActivityUsable()) return
            if (overlayVisible) {
                overlayHost?.hideConfirmation()
                overlayVisible = false
            }
            presentActivity()
        }

        fun select(buttonId: String) {
            finish(UserConfirmationDialogResult(buttonId), dismiss = true)
        }

        fun cancelFromDialog() {
            finish(fallbackResult, dismiss = true)
        }

        fun cancelFromLifecycle() {
            finish(fallbackResult, dismiss = true)
        }

        fun cancelFromCoroutine() {
            if (completed) return
            completed = true
            clearActive()
            hideOverlay()
            dismissDialog()
        }

        private fun presentOverlayIfPossible() {
            if (completed) return
            val host = overlayHost
            val shown = host?.showConfirmation(request) { buttonId ->
                select(buttonId)
            } == true
            overlayVisible = shown
            if (!shown) {
                // There is no safe visible surface. Resolve explicitly with
                // the cancellation fallback instead of leaving Agent pending
                // forever while the Activity is in the background.
                cancelFromLifecycle()
            }
        }

        private fun presentActivity() {
            if (completed || !isActivityResumed() || !isActivityUsable()) return
            if (dialog?.isShowing == true) return
            val hostActivity = activity ?: return
            if (overlayVisible) {
                overlayHost?.hideConfirmation()
                overlayVisible = false
            }

            val nextDialog = AlertDialog.Builder(hostActivity, Ui.dialogTheme())
                .setTitle(request.title)
                .setMessage(
                    request.target?.let { target ->
                        request.message + "\n\n目标 Tool：${target.toolName}" +
                            "\n输入摘要：${target.toConfirmationInputSummary()}"
                    } ?: request.message
                )
                .setCancelable(true)
                .setView(createButtonPanel(buttons, request) { buttonId -> select(buttonId) })
                .create()
            dialog = nextDialog
            nextDialog.setOnCancelListener { cancelFromDialog() }
            nextDialog.setOnDismissListener {
                if (!suppressDialogDismiss) cancelFromDialog()
            }
            if (isActivityResumed() && isActivityUsable()) {
                runCatching { nextDialog.show() }.onFailure {
                    dialog = null
                    presentOverlayIfPossible()
                }
            } else {
                dismissActivityDialogForTransition()
            }
        }

        private fun finish(
            result: UserConfirmationDialogResult,
            dismiss: Boolean
        ) {
            if (completed) return
            completed = true
            clearActive()
            hideOverlay()
            if (continuation.isActive) {
                continuation.resume(result)
            }
            if (dismiss) dismissDialog()
        }

        private fun clearActive() {
            if (active === this) active = null
        }

        private fun hideOverlay() {
            if (overlayVisible) {
                overlayHost?.hideConfirmation()
                overlayVisible = false
            }
        }

        private fun dismissActivityDialogForTransition() {
            val current = dialog ?: return
            dialog = null
            suppressDialogDismiss = true
            try {
                if (current.isShowing) current.dismiss()
            } finally {
                suppressDialogDismiss = false
            }
        }

        private fun dismissDialog() {
            val current = dialog ?: return
            dialog = null
            if (current.isShowing) current.dismiss()
        }
    }

    private companion object {
        const val CANCEL_BUTTON_ID = "cancel"
    }
}
