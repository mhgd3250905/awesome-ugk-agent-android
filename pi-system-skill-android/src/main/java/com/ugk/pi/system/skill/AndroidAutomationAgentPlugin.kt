package com.ugk.pi.system.skill
import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentConfirmationPolicy
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogTool
import com.ugk.pi.android.UserConfirmationRequiredTool

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
            After launching an app or performing a screen action, follow the screen automation Skill's observation workflow. When screenshots are available, prefer visual observation for interface understanding, click targets, and visible result verification; use the tree as supporting evidence for semantic input or unresolved visual ambiguity. If neither can show the result, report that it could not be verified.
            Do not claim that an Android action happened merely because a tool call was planned; use the structured tool result and a follow-up screen observation.
        """.trimIndent()
    }

    private companion object {
        val SCREEN_AUTOMATION_AGENT_CONTRACT = """
            Screen automation is available only because this host supplied an AccessibilityService backend.
            Treat screen_read_ui_tree and screen_find_ui_element as read-only snapshot producers. Every returned snapshotId is single-session state; any new read/find invalidates the previous node target. When using screen_perform_action, submit the exact latest snapshotId and nodeId. Mutating screen tools require the host's exact-input confirmation flow unless full authorization is active.
            SNAPSHOT_REQUIRED means no node action ran. Never retry the same screen_perform_action input; obtain a fresh read/find result and submit its new values.
            Follow the active screen Skill's observation strategy. When visual tools are available, use their visual-first workflow rather than routinely reading the tree before clicking. Node actions always require a current tree snapshot.
            If a screen tool returns success=false, follow its structured error and recovery hint. Never call terminal_bash_execute, relaunch the app, or guess coordinates to recover from a screen-tool failure.
        """.trimIndent()

        val SCREEN_VISUAL_AGENT_CONTRACT = """
            Use a visual-first workflow: call screen_capture_visual to understand the interface and identify what to tap, long-press, or swipe, then use screen_visual_gesture. Do not read/find the View tree as a prerequisite, even for familiar apps or known button names. Many apps expose only empty or root-only trees; these do not imply the visible target is absent. The image is sent to the configured model and attached only to the immediately following model request.
            Use the tree only to support semantic text entry, an explicit accessibility/hierarchy inspection, unresolved visual ambiguity, or fallback when screenshots are unavailable. A single empty/root-only/unhelpful query is enough to return to vision; do not cycle through selectors on the same unchanged page. Do not require both a tree and screenshot when one is sufficient. One fresh post-action observation may also support the next step if the screen has not changed and its snapshot/observation remains current; avoid redundant reads or captures. Use screen_perform_action only with the exact snapshotId and nodeId from a fresh tree result; use set_text only with explicitly supplied text.
            For a target identified visually, return its normalized 0..1 left/top/right/bottom rectangle and call screen_visual_gesture with the exact latest observationId. Never invent raw pixel coordinates or reuse an already-used/replaced observation. There is no pre-action pixel comparison or age timeout; animations and slow inference do not block dispatch. App and display geometry checks still apply.
            screen_capture_visual and screen_visual_gesture are protected by the exact confirmation flow unless full authorization is active. After every mutating action, verify the result with a fresh observation: prefer a screenshot for visible success conditions, with tree evidence reserved for semantic input or required supporting details. A successful gesture only means AccessibilityService accepted the touch stream. If screenshots are unavailable or blank and tree data cannot show the result, report that it could not be verified.
            Gesture diagnostics separate dispatched=true from effectVerified=false. A completed callback does not prove the app handled a touch. screenChange=unchanged means a bounded local comparison saw no visible change; changed may be unrelated animation, and unavailable cannot verify an effect. Never report task success from dispatch alone. If the target does not respond, do not blindly repeat the same coordinates or vary durations/coordinates in a loop. For the same page and target, allow at most one independently grounded alternative after fresh observation; then ask the user to perform the blocked step manually and reobserve. Do not assert app/platform protection as a confirmed cause from these diagnostics alone.
            If screenshot capture is unsupported, use the structure-tree workflow for the rest of the current task and do not capture again. Retry a transient screenshot failure or timeout at most once; after a second failure, use the tree for the rest of the task. Secure/DRM surfaces may be blank, dynamic screens may become stale, and visual coordinates cannot replace semantic text entry. Never use a visual guess for destructive, financial, authentication, or irreversible actions without explicit user confirmation.
        """.trimIndent()
    }
}
