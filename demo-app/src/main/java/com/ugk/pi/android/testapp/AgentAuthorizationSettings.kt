package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.UserConfirmationDialogButton

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

/** Pure button policy so the high-risk auto-approval rule stays testable. */
object AgentAuthorizationPolicy {
    private val acceptedButtonIds = setOf("confirm", "continue", "ok", "yes", "allow")
    private val cancellationButtonIds = setOf(
        "cancel",
        "deny",
        "no",
        "reject",
        "stop",
        "close"
    )

    fun autoApproveButtonId(buttons: List<UserConfirmationDialogButton>): String =
        buttons.firstOrNull { it.id.lowercase() in acceptedButtonIds }?.id
            ?: buttons.firstOrNull { it.id.lowercase() !in cancellationButtonIds }?.id
            ?: buttons.firstOrNull()?.id
            ?: "cancel"
}
