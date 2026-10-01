package com.ugk.pi.system.skill
import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogTool
import com.ugk.pi.android.UserConfirmationRequiredTool

import android.app.Activity

class AndroidSystemAgentPlugin(
    private val activity: Activity,
    private val permissionRequester: AndroidRuntimePermissionRequester,
    private val confirmationPresenter: UserConfirmationDialogPresenter,
    private val shouldBypassConfirmation: () -> Boolean = { false }
) : AgentCapabilityPlugin {
    override val id: String = "android-system"

    override fun tools(): List<AgentTool> {
        val appContext = activity.applicationContext
        return buildList {
            add(AppEnvironmentInfoTool(appContext))
            add(AndroidPermissionStatusTool(activity))
            if (!shouldBypassConfirmation()) {
                add(UserConfirmationDialogTool(confirmationPresenter))
            }
            add(
                UserConfirmationRequiredTool(
                    AndroidRuntimePermissionRequestTool(activity, permissionRequester),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
            add(
                UserConfirmationRequiredTool(
                    AndroidAppIntentTool(appContext),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
            add(
                UserConfirmationRequiredTool(
                    AndroidSystemPageTool(appContext),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
            addAll(clipboardTools(appContext, shouldBypassConfirmation))
        }
    }

    override fun skills(): List<AndroidSkill> {
        val requireUserConfirmation = !shouldBypassConfirmation()
        return listOf(
            AndroidSystemSkills.appSettingsInspection(),
            AndroidSystemSkills.permissionSettingsControl(requireUserConfirmation),
            AndroidSystemSkills.appFacingIntentControl(requireUserConfirmation),
            AndroidSystemSkills.clipboardControl(requireUserConfirmation)
        )
    }
}
