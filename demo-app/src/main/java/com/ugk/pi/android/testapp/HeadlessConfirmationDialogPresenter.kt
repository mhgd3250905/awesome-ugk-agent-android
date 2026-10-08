package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationButtonIntent
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogResult
import com.ugk.pi.android.userConfirmationButtonIntent

/**
 * Explicitly denies protected confirmation requests when no UI is present.
 * Full authorization is handled by the Tool wrapper, not by this presenter.
 */
internal object HeadlessConfirmationDialogPresenter : UserConfirmationDialogPresenter {
    override suspend fun showConfirmationDialog(
        request: UserConfirmationDialogRequest
    ): UserConfirmationDialogResult {
        val cancellationId = request.buttons.firstOrNull {
            userConfirmationButtonIntent(it.id) == UserConfirmationButtonIntent.DECLINED
        }?.id
        // The button list is authored by the model, so "the last button" was
        // never a safe denial: a set such as [confirm] answered the protected
        // Tool with an id the SDK reads as the user's approval. Fall back to a
        // button that cannot read as approval, and when the model offered only
        // approval ids report honestly that nobody decided at all.
        val selectedButtonId = cancellationId
            ?: request.buttons.firstOrNull {
                userConfirmationButtonIntent(it.id) != UserConfirmationButtonIntent.ACCEPTED
            }?.id
            ?: request.buttons.lastOrNull()?.id
            ?: "cancel"
        return UserConfirmationDialogResult(
            selectedButtonId = selectedButtonId,
            withoutUserDecision = cancellationId == null
        )
    }
}
