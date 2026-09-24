package com.ugk.pi.android

object ScreenAutomationSkills {
    fun accessibilityScreenControl(
        requireUserConfirmation: Boolean = true,
        includeVisualFallback: Boolean = false
    ): AndroidSkill {
        val confirmationInstruction = if (requireUserConfirmation) {
            if (includeVisualFallback) {
                "screen_capture_visual sends a cross-app screenshot to the configured model, while screen_perform_action, screen_visual_gesture, screen_gesture, screen_press_key, and screen_global_action change visible state or navigation. Immediately before each protected call, use show_user_confirmation_dialog with target.toolName set to the exact next Tool name and target.input set to that Tool's complete JSON input. Invoke the next Tool with identical name and input. Do not treat selectedButtonId alone as authorization."
            } else {
                "screen_perform_action, screen_gesture, screen_press_key, and screen_global_action change visible state or navigation. Immediately before each call, use show_user_confirmation_dialog with target.toolName set to the exact next Tool name and target.input set to that Tool's complete JSON input. Invoke the next Tool with identical name and input. Do not treat selectedButtonId alone as authorization."
            }
        } else {
            AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION
        }
        val observationStrategyInstructions = if (includeVisualFallback) {
            """
                Visual-first workflow (this backend supports screenshots):
                - At the start of each screen observation cycle, call screen_capture_visual and use the attached current screenshot as the primary evidence for screen state and visible target selection. After a mutating screen action, capture a fresh screenshot to verify the result.
                - The image is sent to the configured model and is attached only to the immediately following model request. If you query the tree for supporting evidence, carry the visual target description or selector into that query and use its fresh snapshot for semantic actions. If a later decision requires seeing a changed screen, capture a new image. Screenshot capture is a protected cross-app read; follow the exact confirmation flow. Avoid capturing an unchanged frame repeatedly or capturing screens unrelated to the task.
                - Use the View structure tree as supporting evidence when text, content descriptions, editability, supported actions, scrollable containers, or target disambiguation are useful. Use screen_find_ui_element for a known selector and screen_read_ui_tree when hierarchy or broader context is needed; do not make a full tree read mandatory for every screen.
                - For semantic text entry or a supported node action, obtain a fresh tree result and use its exact snapshotId and nodeId. Inspect enabled, visibleToUser, actions, clickable, scrollable, editable, text, contentDesc, viewId, and bounds. Any new read/find invalidates the previous node target. Never invent or reuse node IDs.
                - If a node action returns STALE_SNAPSHOT, SNAPSHOT_REQUIRED, NODE_NOT_FOUND, WINDOW_UNAVAILABLE, TARGET_NOT_INTERACTABLE, or ACTION_NOT_SUPPORTED, read/find again and select a fresh target; never retry the same action input.
                - Use screen_perform_action for supported click, long_click, scroll_forward, scroll_backward, focus, clear_focus, and set_text actions when the current tree confirms the intended node. Use set_text only when the value is explicitly known; an omitted text value never means clear the field. Press Enter only for an explicitly intended submit/search/send/go/done IME action.
                - A truncated=true tree result does not prove that a visual target is absent. Narrow the selector or use a visible scrollable node; after scrolling, capture a new screenshot before making the next visual decision.
                - For a visually identified target, return its normalized 0..1 rectangle (left, top, right, bottom) from the latest screenshot and call screen_visual_gesture with that exact observationId. Use the target center for tap/long_press and as the start point for directional swipes. Never convert coordinates from memory or assume a fixed resolution.
                - The backend rejects missing or stale observations, changed packages, changed screen dimensions/rotation, and invalid bounds. Gesture success only means AccessibilityService accepted the touch stream; verify the visible result with a fresh screenshot.
                - If a screenshot is unsupported, use the structure-tree workflow for the rest of the current task and do not capture again. For a transient screenshot failure or timeout, make at most one fresh capture attempt; after a second failure, use the tree for the rest of the current task. Secure/DRM surfaces may be blank; visual coordinates cannot replace semantic text entry when no editable node exists.
            """.trimIndent()
        } else {
            """
                Structure-tree workflow (this backend does not provide screenshots):
                - Use screen_find_ui_element when a text, content description, viewId, or type selector is known; use screen_read_ui_tree when the full visible hierarchy is needed. Both return a snapshotId.
                - Every node action must use the exact snapshotId and nodeId from the same fresh result. Any new read/find replaces the session's latest snapshot. Never invent or reuse a nodeId.
                - Inspect enabled, visibleToUser, actions, clickable, scrollable, editable, text, contentDesc, viewId, and bounds before choosing an operation. Do not click a disabled or invisible node.
                - If a result is STALE_SNAPSHOT, SNAPSHOT_REQUIRED, NODE_NOT_FOUND, WINDOW_UNAVAILABLE, TARGET_NOT_INTERACTABLE, or ACTION_NOT_SUPPORTED, read/find again and select a fresh target. SNAPSHOT_REQUIRED means no action ran; the next call must read/find before retrying with new values.
                - Prefer a unique viewId, then exact/unique text or content description, then type plus surrounding context. If multiple matches remain, use more context, scroll, or ask the user; do not guess.
                - A truncated=true result does not prove a target is absent. Narrow the query, increase max_nodes within the tool cap, or scroll a visible container and read again.
                - Prefer screen_perform_action with scroll_forward or scroll_backward on the nearest scrollable element. After each scroll, read/find again because the prior snapshot is invalid. Stop only when repeated reads show no change or the target is found.
                - If no reliable node action exists, use screen_gesture only with coordinates grounded in current reported dimensions and visible bounds. Never assume a fixed screen size or tap an unverified coordinate.
                - For editable fields, use focus if needed, set_text only with explicitly supplied text, and press Enter only when the intended IME action is submit/search/send/go/done. Verify after each mutating step.
            """.trimIndent()
        }
        return AndroidSkill(
            id = "android-accessibility-screen-automation",
            description = "Use the host AccessibilityService to observe Android screens visually, use UI structure as supporting context, and perform verified screen actions.",
            triggers = listOf(
                "screen",
                "ui",
                "accessibility",
                "click",
                "tap",
                "button",
                "scroll",
                "type",
                "input",
                "gesture",
                "read screen",
                "read UI",
                "UI tree",
                "screen structure",
                "find button",
                "click button",
                "tap element",
                "type text",
                "scroll list",
                "swipe screen",
                "press enter",
                "go back",
                "home screen",
                "无障碍",
                "屏幕",
                "界面",
                "点击",
                "按钮",
                "滚动",
                "输入",
                "手势",
                "界面结构",
                "界面树",
                "读取界面",
                "读取屏幕",
                "查找控件",
                "点击按钮",
                "输入文字",
                "滚动列表",
                "滑动屏幕",
                "返回",
                "打开通知栏"
            ),
            instructions = """
                This Android-Skill is the operating contract for screen automation through a host AccessibilityService.
                The Agent is inside a normal Android application. It does not have Android Shell, root, or an unrestricted
                view hierarchy. Use only the registered screen tools and interpret their structured result codes.

                Readiness and permission:
                1. Before cross-app screen work, call get_android_accessibility_status.
                2. Continue only when readyForScreenAutomation=true. If the service is disabled or disconnected, call
                   open_android_accessibility_settings, tell the user to enable the service manually, and wait for a new
                   status check. Never claim that the Agent can grant AccessibilityService permission silently.
                3. App discovery and launch are separate: use find_android_app followed by the protected launch tool.
                   Do not use terminal_bash_execute, am, pm, package-name guessing, or icon searching to launch an app.

                $observationStrategyInstructions

                Confirmation and verification:
                - screen_read_ui_tree and screen_find_ui_element are read-only and do not need confirmation.
                - $confirmationInstruction Full authorization never bypasses target validation.
                - After every accepted click, long click, text entry, scroll, gesture, key press, or global action, verify
                  the visible state using a fresh screenshot when visual capture is available, or a fresh read/find
                  result otherwise. A success=true result means Android accepted the request; it does not prove that
                  the user-visible operation completed. If neither screenshot nor structure tree exposes the result,
                  report that it could not be verified.
                - If a screen tool returns success=false, follow its structured error and recovery hint. Refresh a stale
                  visual observation before another visual gesture. Do not use terminal_bash_execute, relaunch the app,
                  or guess coordinates to recover from a screen-tool failure.
                - Use screen_global_action only for back, home, recents, notifications, quick_settings, power_dialog,
                  lock_screen, or take_screenshot. Confirm these actions separately and report their exact result.
                - Never use terminal commands, coordinate guessing, or a stale snapshot to bypass a failed target check.
            """.trimIndent(),
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "get_android_accessibility_status",
                    purpose = "Checks whether the host AccessibilityService is enabled and connected for screen automation.",
                    whenToUse = "Before reading or operating another app's UI.",
                    resultSemantics = "Only readyForScreenAutomation=true permits screen automation; otherwise the user must enable the service manually."
                ),
                AndroidSkillMethod(
                    toolName = "screen_read_ui_tree",
                    purpose = "Returns a bounded value snapshot of visible accessibility windows and UI elements.",
                    whenToUse = "Use when the full visible structure, screen dimensions, actions, or scroll containers are needed.",
                    resultSemantics = "Returns snapshotId, nodeId, bounds, capabilities, supported actions, nodeCount, and truncated. A new read invalidates the prior session snapshot."
                ),
                AndroidSkillMethod(
                    toolName = "screen_find_ui_element",
                    purpose = "Finds visible elements by partial/exact text, partial/exact content description, viewId, or class/type and returns exact targets.",
                    whenToUse = "Use when a selector is known and a compact result is faster and less ambiguous than a full tree.",
                    resultSemantics = "Returns matches plus snapshotId; count=0 means not visible in this snapshot, and ambiguous=true means do not act until the target is disambiguated."
                ),
                AndroidSkillMethod(
                    toolName = "screen_perform_action",
                    purpose = "Performs a verified node action using an exact snapshotId and nodeId.",
                    whenToUse = "Use for click, long_click, scrolling, focus, clear_focus, or explicitly requested text entry on a visible node.",
                    resultSemantics = "The backend re-resolves and fingerprints the target; stale, missing, disabled, unsupported, or failed actions return a structured error code."
                ),
                AndroidSkillMethod(
                    toolName = "screen_gesture",
                    purpose = "Dispatches a bounded tap, long press, or directional swipe by screen coordinates.",
                    whenToUse = if (includeVisualFallback) {
                        "Use only when a fresh visual observation is unavailable and the target bounds are independently grounded in a current UI snapshot."
                    } else {
                        "Use when the accessibility tree cannot expose a reliable node action and coordinates are grounded in current visible bounds."
                    },
                    resultSemantics = "Coordinates are checked against the current screen size; success means the gesture callback completed, not that the target state changed."
                ),
                AndroidSkillMethod(
                    toolName = "screen_press_key",
                    purpose = "Triggers the enter IME action on the currently focused input field when supported.",
                    whenToUse = "After an explicit set_text and only when the intended input action is submit/search/send/go/done.",
                    resultSemantics = "Requires a focused input and Android API 30+ IME support; success means the IME action was accepted and must be followed by a screen verification."
                ),
                AndroidSkillMethod(
                    toolName = "screen_global_action",
                    purpose = "Performs a global navigation or system action through AccessibilityService.",
                    whenToUse = "For back, home, recents, notifications, quick settings, power dialog, lock screen, or screenshot.",
                    resultSemantics = "success=true means AccessibilityService accepted the system action; verify the resulting screen before continuing."
                ),
                if (includeVisualFallback) {
                    AndroidSkillMethod(
                        toolName = "screen_capture_visual",
                        purpose = "Captures the current external screen and attaches it to the next model request for visual target identification.",
                        whenToUse = "At the start of each screen observation cycle and after mutating actions when visual verification is needed; it requires confirmation because screen content is sent to the configured model.",
                        resultSemantics = "Returns an observationId, screen metadata, and an image attachment. The observation is short-lived and must not be reused after a new capture."
                    )
                } else {
                    null
                },
                if (includeVisualFallback) {
                    AndroidSkillMethod(
                        toolName = "screen_visual_gesture",
                        purpose = "Performs a coordinate gesture against a fresh visual screen observation.",
                        whenToUse = "For a visible target identified from the latest screen_capture_visual image, using that result's exact observationId and normalized target rectangle.",
                        resultSemantics = "The backend validates observation freshness, package, dimensions, and normalized bounds; success still requires a follow-up screen verification."
                    )
                } else {
                    null
                },
                if (requireUserConfirmation) {
                    AndroidSkillMethod(
                        toolName = "show_user_confirmation_dialog",
                        purpose = "Confirms the exact next mutating screen tool call.",
                        whenToUse = "Immediately before screen_capture_visual, screen_perform_action, screen_visual_gesture, screen_gesture, screen_press_key, or screen_global_action.",
                        resultSemantics = "The confirmation target must match the next tool name and complete JSON input; a button id without a matching ticket is not authorization."
                    )
                } else {
                    null
                }
            )
                .filterNotNull()
        )
    }
}
