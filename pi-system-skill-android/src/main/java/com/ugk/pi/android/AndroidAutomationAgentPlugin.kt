package com.ugk.pi.android

import android.content.ComponentName
import android.content.Context

/**
 * Full Android app-automation entry point for hosts that provide an
 * AccessibilityService implementation.
 *
 * App discovery and app launch remain usable without AccessibilityService;
 * screen reading and actions are supplied by the host's screen tools after
 * the service is enabled and connected.
 */
class AndroidAutomationAgentPlugin(
    context: Context,
    private val confirmationPresenter: UserConfirmationDialogPresenter,
    private val accessibilityServiceComponent: ComponentName,
    private val accessibilityStateProvider: AndroidAccessibilityServiceStateProvider
) : AgentCapabilityPlugin {
    private val appContext = context.applicationContext ?: context

    override val id: String = "android-automation"

    override fun tools(): List<AgentTool> = listOf(
        AndroidAppCatalogTool(appContext),
        AndroidAccessibilityStatusTool(
            context = appContext,
            serviceComponent = accessibilityServiceComponent,
            stateProvider = accessibilityStateProvider
        ),
        UserConfirmationDialogTool(confirmationPresenter),
        UserConfirmationRequiredTool(AndroidLaunchAppTool(appContext)),
        UserConfirmationRequiredTool(AndroidAppIntentTool(appContext)),
        UserConfirmationRequiredTool(AndroidAccessibilitySettingsTool(appContext))
    )

    override fun skills(): List<AndroidSkill> = listOf(
        AndroidSystemSkills.androidAutomationControl(),
        AndroidSystemSkills.appFacingIntentControl()
    )

    override fun agentInstructions(): List<String> = listOf(ANDROID_RUNTIME_AGENT_CONTRACT)

    private companion object {
        val ANDROID_RUNTIME_AGENT_CONTRACT = """
            You are operating inside a normal Android host application, not Android Shell, root, or a full Linux distribution.
            Treat Android system state, app discovery, app launch, AccessibilityService state, and screen actions as separate capabilities.
            Use find_android_app to resolve a human app name to an exact packageName; do not guess package names.
            Use launch_android_app or launch_android_app_intent to open another app; never use terminal_bash_execute, am, or pm for app launch.
            Cross-app screen reading and clicking require get_android_accessibility_status to report readyForScreenAutomation=true.
            If accessibility is disabled, call open_android_accessibility_settings, tell the user to enable the service manually, and wait for a new status check.
            After launching an app or performing a screen action, call screen_read_ui_tree again and verify the observed state before claiming success.
            Do not claim that an Android action happened merely because a tool call was planned; use the structured tool result and a follow-up screen observation.
        """.trimIndent()
    }
}
