package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.UserConfirmationButtonIntent
import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.userConfirmationButtonIntent

/** Stores the local, explicit opt-in for skipping high-impact confirmations. */
class AgentAuthorizationSettingsStore(context: Context) {
    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isFullAuthorizationEnabled(): Boolean =
        prefs.getBoolean(FULL_AUTHORIZATION_KEY, false)

    fun setFullAuthorizationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(FULL_AUTHORIZATION_KEY, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "agent_authorization_settings"
        const val FULL_AUTHORIZATION_KEY = "full_authorization_enabled"
    }
}

/**
 * Pure button policy so the high-risk auto-approval rule stays testable.
 *
 * Both vocabularies come from the SDK through [userConfirmationButtonIntent]. They were
 * hand-copied here, and the copied refusal list carried only six of the eleven published ids,
 * so `later`, `not_now`, `abort`, `dismiss` and `decline` were handed back as the auto-approval
 * by a host that an earlier round had already made draw them as Cancel buttons - the answer the
 * protected Tool then reports is the user saying no.
 */
object AgentAuthorizationPolicy {
    /**
     * The button full authorization resolves a confirmation with: an id the SDK reads as
     * approval if the model offered one, otherwise a button that means neither yes nor no,
     * and only then the first offered button.
     */
    fun autoApproveButtonId(buttons: List<UserConfirmationDialogButton>): String =
        buttons.firstOrNull {
            userConfirmationButtonIntent(it.id) == UserConfirmationButtonIntent.ACCEPTED
        }?.id
            ?: buttons.firstOrNull {
                userConfirmationButtonIntent(it.id) == UserConfirmationButtonIntent.UNRECOGNIZED
            }?.id
            ?: buttons.firstOrNull()?.id
            ?: "cancel"
}
