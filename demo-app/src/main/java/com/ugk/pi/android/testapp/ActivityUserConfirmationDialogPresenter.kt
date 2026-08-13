package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.AlertDialog
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

/** Presents agent-requested confirmations without leaving a suspended call behind. */
class ActivityUserConfirmationDialogPresenter(
    private val activity: Activity
) : UserConfirmationDialogPresenter {
    private var active: PendingConfirmation? = null

    override suspend fun showConfirmationDialog(
        request: UserConfirmationDialogRequest
    ): UserConfirmationDialogResult = withContext(Dispatchers.Main.immediate) {
        if (!isActivityUsable()) {
            return@withContext UserConfirmationDialogResult(CANCEL_BUTTON_ID)
        }

        active?.cancelFromLifecycle()
        suspendCancellableCoroutine { continuation ->
            val buttons = request.buttons.ifEmpty {
                listOf(UserConfirmationDialogButton(CANCEL_BUTTON_ID, "Cancel"))
            }
            val fallbackId = buttons.firstOrNull(::isCancellationButton)?.id ?: CANCEL_BUTTON_ID
            val pending = PendingConfirmation(
                continuation = continuation,
                fallbackResult = UserConfirmationDialogResult(fallbackId)
            )
            val buttonPanel = createButtonPanel(buttons) { buttonId ->
                pending.select(buttonId)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(request.title)
                .setMessage(request.message)
                .setCancelable(true)
                .setView(buttonPanel)
                .create()

            pending.dialog = dialog
            dialog.setOnCancelListener { pending.cancelFromDialog() }
            dialog.setOnDismissListener { pending.cancelFromDialog() }
            active = pending
            continuation.invokeOnCancellation {
                runOnMain { pending.cancelFromCoroutine() }
            }

            if (isActivityUsable()) {
                dialog.show()
            } else {
                pending.cancelFromLifecycle()
            }
        }
    }

    private fun createButtonPanel(
        buttons: List<UserConfirmationDialogButton>,
        onSelected: (String) -> Unit
    ): View {
        val isHorizontal = buttons.size <= 2
        return LinearLayout(activity).apply {
            orientation = if (isHorizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(16), dp(4), dp(16), dp(8))

            buttons.forEachIndexed { index, button ->
                val actionButton = Button(activity).apply {
                    text = button.label
                    isAllCaps = false
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
        return (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** Cancels an open dialog when the owning Activity is being destroyed. */
    fun cancelPending() {
        runOnMain { active?.cancelFromLifecycle() }
    }

    private fun isActivityUsable(): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun isCancellationButton(button: UserConfirmationDialogButton): Boolean {
        return button.id.lowercase() in setOf("cancel", "deny", "no", "reject", "stop", "close")
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            activity.runOnUiThread(action)
        }
    }

    private inner class PendingConfirmation(
        private val continuation: CancellableContinuation<UserConfirmationDialogResult>,
        private val fallbackResult: UserConfirmationDialogResult
    ) {
        lateinit var dialog: AlertDialog
        private var completed = false

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
            dismissDialog()
        }

        private fun finish(
            result: UserConfirmationDialogResult,
            dismiss: Boolean
        ) {
            if (completed) return
            completed = true
            clearActive()
            if (continuation.isActive) {
                continuation.resume(result)
            }
            if (dismiss) {
                dismissDialog()
            }
        }

        private fun clearActive() {
            if (active === this) {
                active = null
            }
        }

        private fun dismissDialog() {
            if (::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private companion object {
        const val CANCEL_BUTTON_ID = "cancel"
    }
}
