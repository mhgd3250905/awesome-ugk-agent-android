package com.ugk.pi.android

import android.app.Activity

class AndroidSystemAgentPlugin(
    private val activity: Activity,
    private val permissionRequester: AndroidRuntimePermissionRequester,
    private val confirmationPresenter: UserConfirmationDialogPresenter
) : AgentCapabilityPlugin {
    override val id: String = "android-system"

    override fun tools(): List<AgentTool> {
        val appContext = activity.applicationContext
        return listOf(
            AppEnvironmentInfoTool(appContext),
            AndroidPermissionStatusTool(activity),
            UserConfirmationDialogTool(confirmationPresenter),
            UserConfirmationRequiredTool(AndroidRuntimePermissionRequestTool(activity, permissionRequester)),
            UserConfirmationRequiredTool(AndroidAppIntentTool(appContext)),
            UserConfirmationRequiredTool(AndroidSystemPageTool(appContext))
        )
    }

    override fun skills(): List<AndroidSkill> {
        return listOf(
            AndroidSystemSkills.appSettingsInspection(),
            AndroidSystemSkills.permissionSettingsControl()
        )
    }
}
