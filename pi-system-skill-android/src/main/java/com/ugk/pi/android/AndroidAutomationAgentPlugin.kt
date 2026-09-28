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
    private val screenAutomationBackend: ScreenAutomationBackend? = null,
    private val toolDecorator: AgentToolDecorator = AgentToolDecorator.Identity
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

    /** Keeps the pre-decorator constructor available to compiled hosts. */
    constructor(
        context: Context,
        confirmationPresenter: UserConfirmationDialogPresenter,
        accessibilityServiceComponent: ComponentName,
        accessibilityStateProvider: AndroidAccessibilityServiceStateProvider,
        shouldBypassConfirmation: () -> Boolean,
        screenAutomationBackend: ScreenAutomationBackend?
    ) : this(
        context = context,
        confirmationPresenter = confirmationPresenter,
        accessibilityServiceComponent = accessibilityServiceComponent,
        accessibilityStateProvider = accessibilityStateProvider,
        shouldBypassConfirmation = shouldBypassConfirmation,
        screenAutomationBackend = screenAutomationBackend,
        toolDecorator = AgentToolDecorator.Identity
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
            toolDecorator.decorate(
                UserConfirmationRequiredTool(
                    AndroidLaunchAppTool(appContext),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
        )
        add(
            toolDecorator.decorate(
                UserConfirmationRequiredTool(
                    AndroidAppIntentTool(appContext),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
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
        add(toolDecorator.decorate(ScreenReadUiTreeTool(backend)))
        add(toolDecorator.decorate(ScreenFindUiElementTool(backend)))
        add(
            toolDecorator.decorate(
                UserConfirmationRequiredTool(
                    ScreenPerformActionTool(backend),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
        )
        add(
            toolDecorator.decorate(
                UserConfirmationRequiredTool(
                    ScreenGestureTool(backend),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
        )
        add(
            toolDecorator.decorate(
                UserConfirmationRequiredTool(
                    ScreenPressKeyTool(backend),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
        )
        add(
            toolDecorator.decorate(
                UserConfirmationRequiredTool(
                    ScreenGlobalActionTool(backend),
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            )
        )
        val visualBackend = backend as? ScreenVisualAutomationBackend
        if (visualBackend != null) {
            add(
                toolDecorator.decorate(
                    UserConfirmationRequiredTool(
                        ScreenCaptureVisualTool(visualBackend),
                        shouldBypassConfirmation = shouldBypassConfirmation
                    )
                )
            )
            add(
                toolDecorator.decorate(
                    UserConfirmationRequiredTool(
                        ScreenVisualGestureTool(visualBackend),
                        shouldBypassConfirmation = shouldBypassConfirmation
                    )
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
            After launching an app or performing a screen action, follow the screen automation Skill's observation workflow. Use fresh tree evidence when it uniquely establishes the relevant state; use visual observation when the interface is unknown or the judgment requires it. If neither can show the result, report that it could not be verified.
            Do not claim that an Android action happened merely because a tool call was planned; use the structured tool result and a follow-up screen observation.
        """.trimIndent()
    }

    private companion object {
        val SCREEN_AUTOMATION_AGENT_CONTRACT = """
            Screen automation is available only because this host supplied an AccessibilityService backend.
            Treat screen_read_ui_tree and screen_find_ui_element as read-only snapshot producers. Every returned snapshotId is single-session state; any new read/find invalidates the previous node target. When using screen_perform_action, submit the exact latest snapshotId and nodeId. Mutating screen tools require the host's exact-input confirmation flow unless full authorization is active.
            SNAPSHOT_REQUIRED means no node action ran. Never retry the same screen_perform_action input; obtain a fresh read/find result and submit its new values.
            Follow the active screen Skill's evidence-driven observation strategy. Fresh structure-tree evidence may establish targets and success conditions without screenshots; use visual observation when available and needed. Node actions always require a current tree snapshot.
            If a screen tool returns success=false, follow its structured error and recovery hint. Never call terminal_bash_execute, relaunch the app, or guess coordinates to recover from a screen-tool failure.
        """.trimIndent()

        val SCREEN_VISUAL_AGENT_CONTRACT = """
            Choose sufficient fresh evidence for the current question. When tree evidence uniquely identifies the target and relevant success condition, use read/find and semantic actions without mandatory screenshots. For an unknown interface, visual-only content, insufficient tree evidence, unresolved ambiguity, or a judgment requiring visual understanding, call screen_capture_visual rather than blindly probing the tree. The image is sent to the configured model and attached only to the immediately following model request.
            Use screen_find_ui_element for a known selector and screen_read_ui_tree for hierarchy or broader context. Do not require both a tree and screenshot when one is sufficient. One fresh post-action observation may also support the next step if the screen has not changed and its snapshot/observation remains current; avoid redundant reads or captures. Use screen_perform_action only with the exact snapshotId and nodeId from a fresh tree result; use set_text only with explicitly supplied text.
            For a target identified visually, return its normalized 0..1 left/top/right/bottom rectangle and call screen_visual_gesture with the exact latest observationId. Never invent raw pixel coordinates, reuse an old observation, or gesture without a fresh screenshot.
            screen_capture_visual and screen_visual_gesture are protected by the exact confirmation flow unless full authorization is active. After every mutating action, verify the result with a fresh observation: read/find when it establishes the success condition, a screenshot when visual judgment is needed. A successful gesture only means AccessibilityService accepted the touch stream. If screenshots are unavailable or blank and tree data cannot show the result, report that it could not be verified.
            If screenshot capture is unsupported, use the structure-tree workflow for the rest of the current task and do not capture again. Retry a transient screenshot failure or timeout at most once; after a second failure, use the tree for the rest of the task. Secure/DRM surfaces may be blank, dynamic screens may become stale, and visual coordinates cannot replace semantic text entry. Never use a visual guess for destructive, financial, authentication, or irreversible actions without explicit user confirmation.
        """.trimIndent()
    }
}
