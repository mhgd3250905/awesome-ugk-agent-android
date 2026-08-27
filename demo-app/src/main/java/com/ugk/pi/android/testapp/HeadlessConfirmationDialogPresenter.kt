package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogResult

/**
 * Explicitly denies protected confirmation requests when no UI is present.
 * Full authorization is handled by the Tool wrapper, not by this presenter.
 */
internal object HeadlessConfirmationDialogPresenter : UserConfirmationDialogPresenter {
    override suspend fun showConfirmationDialog(
        request: UserConfirmationDialogRequest
    ): UserConfirmationDialogResult {
        val cancellationId = request.buttons.firstOrNull {
            it.id.lowercase() in CANCELLATION_BUTTON_IDS
        }?.id
        return UserConfirmationDialogResult(
            selectedButtonId = cancellationId ?: request.buttons.lastOrNull()?.id ?: "cancel"
        )
    }

    private val CANCELLATION_BUTTON_IDS = setOf(
        "cancel",
        "deny",
        "no",
        "reject",
        "stop",
        "close"
    )
}
