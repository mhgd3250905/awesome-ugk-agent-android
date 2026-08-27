package com.ugk.pi.android

import android.content.ComponentName
import android.content.Context

/**
 * Full Android app-automation entry point for hosts that provide an
 * AccessibilityService implementation.
 *
 * App discovery and app launch remain usable without AccessibilityService;
 * screen reading and actions are supplied by the optional host-injected
 * ScreenAutomationBackend after the service is enabled and connected.
 */
class AndroidAutomationAgentPlugin(
    context: Context,
    private val confirmationPresenter: UserConfirmationDialogPresenter,
    private val accessibilityServiceComponent: ComponentName,
    private val accessibilityStateProvider: AndroidAccessibilityServiceStateProvider,
    private val shouldBypassConfirmation: () -> Boolean = { false },
    private val screenAutomationBackend: ScreenAutomationBackend? = null
) : AgentCapabilityPlugin {
    /** Keeps the pre-screen-backend constructor available to compiled hosts. */
    constructor(
        context: Context,
        confirmationPresenter: UserConfirmationDialogPresenter,
        accessibilityServiceComponent: ComponentName,
        accessibilityStateProvider: AndroidAccessibilityServiceStateProvider,
        shouldBypassConfirmation: () -> Boolean
    ) : this(
        context = context,
        confirmationPresenter = confirmationPresenter,
        accessibilityServiceComponent = accessibilityServiceComponent,
        accessibilityStateProvider = accessibilityStateProvider,
        shouldBypassConfirmation = shouldBypassConfirmation,
        screenAutomationBackend = null
    )

    private val appContext = context.applicationContext ?: context

    override val id: String = "android-automation"

    override fun tools(): List<AgentTool> = buildList {
        add(AndroidAppCatalogTool(appContext))
        add(
            AndroidAccessibilityStatusTool(
                context = appContext,
                serviceComponent = accessibilityServiceComponent,
                stateProvider = accessibilityStateProvider
            )
        )
        if (!shouldBypassConfirmation()) {
            add(UserConfirmationDialogTool(confirmationPresenter))
        }
        add(
            UserConfirmationRequiredTool(
                AndroidLaunchAppTool(appContext),
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
                AndroidAccessibilitySettingsTool(appContext),
                shouldBypassConfirmation = shouldBypassConfirmation
            )
        )
        addAll(clipboardTools(appContext, shouldBypassConfirmation))

        val backend = screenAutomationBackend ?: return@buildList
        add(ScreenReadUiTreeTool(backend))
        add(ScreenFindUiElementTool(backend))
        add(
            UserConfirmationRequiredTool(
                ScreenPerformActionTool(backend),
                shouldBypassConfirmation = shouldBypassConfirmation
            )
        )
        add(
            UserConfirmationRequiredTool(
                ScreenGestureTool(backend),
                shouldBypassConfirmation = shouldBypassConfirmation
            )
        )
        add(
            UserConfirmationRequiredTool(
                ScreenPressKeyTool(backend),
                shouldBypassConfirmation = shouldBypassConfirmation
            )
        )
        add(
            UserConfirmationRequiredTool(
                ScreenGlobalActionTool(backend),
                shouldBypassConfirmation = shouldBypassConfirmation
            )
        )
        val visualBackend = backend as? ScreenVisualAutomationBackend
        if (visualBackend != null) {
            add(
                UserConfirmationRequiredTool(
                    ScreenCaptureVisualTool(visualBackend),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
            add(
                UserConfirmationRequiredTool(
                    ScreenVisualGestureTool(visualBackend),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
        }
    }

    override fun skills(): List<AndroidSkill> = buildList {
        val requireUserConfirmation = !shouldBypassConfirmation()
        add(AndroidSystemSkills.androidAutomationControl(requireUserConfirmation))
        add(AndroidSystemSkills.appFacingIntentControl(requireUserConfirmation))
        add(AndroidSystemSkills.clipboardControl(requireUserConfirmation))
        if (screenAutomationBackend != null) {
            add(
                ScreenAutomationSkills.accessibilityScreenControl(
                    requireUserConfirmation = requireUserConfirmation,
                    includeVisualFallback = screenAutomationBackend is ScreenVisualAutomationBackend
                )
            )
        }
    }

    override fun agentInstructions(): List<String> = buildList {
        val requireUserConfirmation = !shouldBypassConfirmation()
        add(androidRuntimeAgentContract(requireUserConfirmation))
        if (screenAutomationBackend != null) {
            add(SCREEN_AUTOMATION_AGENT_CONTRACT)
            if (screenAutomationBackend is ScreenVisualAutomationBackend) {
                add(SCREEN_VISUAL_AGENT_CONTRACT)
            }
        }
    }

    private fun androidRuntimeAgentContract(requireUserConfirmation: Boolean): String {
        val confirmationInstruction = if (requireUserConfirmation) {
            "Before each protected launch or accessibility-settings action, call show_user_confirmation_dialog with target.toolName set to the exact next protected Tool name and target.input set to that Tool's complete JSON input. Invoke the next Tool with the identical name and input. selectedButtonId only records the button choice; it does not authorize a protected Tool by itself, and a missing or mismatched target ticket must be treated as not authorized."
        } else {
            AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION
        }
        return """
            You are operating inside a normal Android host application, not Android Shell, root, or a full Linux distribution.
            Treat Android system state, app discovery, app launch, AccessibilityService state, and screen actions as separate capabilities.
            Use find_android_app to resolve a human app name to an exact packageName; do not guess package names.
            Use launch_android_app or launch_android_app_intent to open another app; never use terminal_bash_execute, am, or pm for app launch.
            $confirmationInstruction
            Cross-app screen reading and clicking require get_android_accessibility_status to report readyForScreenAutomation=true.
            If accessibility is disabled, call open_android_accessibility_settings, tell the user to enable the service manually, and wait for a new status check.
            After launching an app or performing a screen action, use the screen automation Skill's snapshot-first workflow and verify the observed state before claiming success.
            Do not claim that an Android action happened merely because a tool call was planned; use the structured tool result and a follow-up screen observation.
        """.trimIndent()
    }

    private companion object {
        val SCREEN_AUTOMATION_AGENT_CONTRACT = """
            Screen automation is available only because this host supplied an AccessibilityService backend.
            Treat screen_read_ui_tree and screen_find_ui_element as read-only snapshot producers. Treat every returned snapshotId as single-session state: any new read/find invalidates the previous target. Mutating screen tools must use the exact latest snapshotId and nodeId and must be preceded by the host's exact-input confirmation flow unless full authorization is active.
            SNAPSHOT_REQUIRED means no screen action was executed. Never retry the same screen_perform_action input; the next tool call must be screen_read_ui_tree or screen_find_ui_element, followed by a new action using both values from that fresh result.
            Prefer semantic node actions and supported actions reported by the snapshot. Use gestures only when the UI tree is unavailable or insufficient, and derive coordinates from the latest reported screen dimensions. After each mutating action, read or find again and verify the result.
            If a semantic screen tool returns success=false, the next recovery call must be screen_read_ui_tree or screen_find_ui_element (or get_android_accessibility_status when the error code is ACCESSIBILITY_UNAVAILABLE). If screen_capture_visual or screen_visual_gesture returns success=false, follow its visual error code and capture a fresh observation when required. Do not call terminal_bash_execute, relaunch the app, or guess coordinates to recover from a screen-tool failure.
        """.trimIndent()

        val SCREEN_VISUAL_AGENT_CONTRACT = """
            When the accessibility tree cannot expose a reliable visible target, use screen_capture_visual as the visual fallback. This sends the current screen image to the configured model and returns a short-lived observationId plus screen metadata; do not capture repeatedly or use it for unrelated inspection.
            The model must return a visible target rectangle in normalized 0..1 left/top/right/bottom coordinates. Call screen_visual_gesture with the exact latest observationId and that rectangle; never invent raw pixel coordinates, reuse an older observation, or use screen_visual_gesture without a fresh screenshot.
            screen_capture_visual and screen_visual_gesture are protected by the exact confirmation flow unless full authorization is active. After every visual gesture, call screen_read_ui_tree or screen_capture_visual and verify the visible state. A successful gesture only means AccessibilityService accepted the touch stream; it does not prove the intended control activated.
            Visual fallback is not universal: secure/DRM surfaces may be blank, dynamic screens may become stale, and visual coordinates cannot replace semantic text entry when no editable node exists. Never use a visual guess for destructive, financial, authentication, or irreversible actions without explicit user confirmation.
        """.trimIndent()
    }
}
