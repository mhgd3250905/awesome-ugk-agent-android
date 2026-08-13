package com.ugk.pi.android

import android.content.Context

/**
 * Registers user-visible Android app-facing Intent actions without requiring
 * the host to expose the full permission/settings plugin.
 */
class AndroidIntentAgentPlugin(
    private val context: Context,
    private val confirmationPresenter: UserConfirmationDialogPresenter
) : AgentCapabilityPlugin {
    override val id: String = "android-intent"

    override fun tools(): List<AgentTool> {
        val appContext = context.applicationContext ?: context
        return listOf(
            UserConfirmationDialogTool(confirmationPresenter),
            UserConfirmationRequiredTool(AndroidAppIntentTool(appContext))
        )
    }

    override fun skills(): List<AndroidSkill> {
        return listOf(AndroidSystemSkills.appFacingIntentControl())
    }
}
