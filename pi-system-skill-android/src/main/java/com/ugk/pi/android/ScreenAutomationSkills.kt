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
        val visualFallbackInstruction = if (includeVisualFallback) {
            """

                Visual fallback for missing or insufficient UI trees:
                - Use screen_capture_visual only after screen_read_ui_tree or screen_find_ui_element cannot expose a reliable target. It returns a screenshot attachment plus a short-lived observationId, package, dimensions, rotation, and image dimensions. The screenshot is sent to the configured model, so do not capture unrelated or repeated frames.
                - Ask the visual model to identify a visible target using a normalized 0..1 rectangle: left, top, right, bottom. Use the target center for tap/long_press and the target center as the start point for directional swipes. Never convert coordinates from memory or assume a fixed resolution.
                - Use screen_visual_gesture with the exact latest observationId and the model's normalized rectangle. The backend rejects missing, stale, changed-package, or changed-screen observations. Its success only means AccessibilityService accepted the touch stream.
                - After every visual gesture, call screen_read_ui_tree or screen_capture_visual and verify the visible state. Secure/DRM surfaces may be blank, animated screens may become stale, and visual coordinates cannot replace semantic text entry when no editable node exists.
            """.trimIndent()
        } else {
            ""
        }
        return AndroidSkill(
            id = "android-accessibility-screen-automation",
            description = "Use the host AccessibilityService to inspect Android UI structure, identify visible targets, and perform verified screen actions.",
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

                Snapshot-first targeting (mandatory):
                - Use screen_find_ui_element when a text, exact text, content description, exact content description,
                  viewId, or type selector is known and
                  screen_read_ui_tree when you need the full visible hierarchy. Both return a snapshotId.
                - Every element is identified by the exact nodeId from that same result. Call screen_perform_action with
                  both the exact snapshotId and nodeId; never invent, shorten, or reuse a nodeId from an older read.
                - Any new screen_read_ui_tree or screen_find_ui_element replaces the session's latest snapshot. If the
                  result is STALE_SNAPSHOT, SNAPSHOT_REQUIRED, NODE_NOT_FOUND, WINDOW_UNAVAILABLE, TARGET_NOT_INTERACTABLE, or the target is
                  ambiguous, read/find again and select a fresh, unique target before retrying.
                - SNAPSHOT_REQUIRED means no screen action was executed. Never retry the same screen_perform_action input;
                  the next tool call must be screen_read_ui_tree or screen_find_ui_element, followed by a new action using
                  both values from that fresh result.
                - Inspect the element's actions, clickable, scrollable, editable, enabled, visibleToUser, text,
                  contentDesc, viewId, and bounds before choosing an operation. Do not click a disabled or invisible node.
                - A truncated=true result is not proof that a target is absent. Increase max_nodes within the tool cap,
                  narrow the query with screen_find_ui_element, or scroll a visible scrollable container and read again.

                Target selection and scrolling:
                - Prefer a unique viewId, then an exact/unique text or content description, then a type plus surrounding
                  context. If more than one match remains, do not guess; use more context, scroll, or ask the user.
                - Prefer screen_perform_action with scroll_forward or scroll_backward on the nearest element whose
                  scrollable=true. After each scroll, read/find again because the previous snapshot is no longer valid.
                - scroll_forward reveals content farther down in the usual Android list direction; scroll_backward moves
                  toward earlier content. Stop only after repeated reads show no change or the target is found.

                Node actions:
                - Use screen_perform_action for click, long_click, scroll_forward, scroll_backward, focus, clear_focus,
                  and set_text. Use set_text only when text is explicitly known; the text field is required and an omitted
                  text must never be interpreted as clearing a field.
                - For editable fields, prefer focus when needed, set_text, then screen_press_key with key=enter only when
                  the intended control is a submit/search/send/go/done IME action. Read the screen after each mutating step.
                - If the target exposes no reliable node action or the app returns too little/blocked UI structure, use
                  screen_gesture as a last resort. Gestures use the latest reported screenWidth/screenHeight; x and y are
                  the start point, and swipe endpoints are derived and kept in bounds. Never assume a fixed 1080x2400
                  screen or tap an unverified coordinate.

                Confirmation and verification:
                - screen_read_ui_tree and screen_find_ui_element are read-only and do not need confirmation.
                - $confirmationInstruction Full authorization never bypasses target validation.
                - After every accepted click, long click, text entry, scroll, gesture, key press, or global action, call a
                  read/find tool and verify the observed state. A success=true result means Android accepted the request;
                  it does not prove that the user-visible operation completed.
                - If a semantic screen tool returns success=false, recover only with screen_read_ui_tree or
                  screen_find_ui_element (or get_android_accessibility_status when accessibility is unavailable). If
                  screen_capture_visual or screen_visual_gesture returns success=false, follow its visual error code;
                  when the observation is missing or stale, capture a fresh visual observation. Do not use
                  terminal_bash_execute, relaunch the app, or guess coordinates to recover.
                - Use screen_global_action only for back, home, recents, notifications, quick_settings, power_dialog,
                  lock_screen, or take_screenshot. Confirm these actions separately and report their exact result.
                - Never use terminal commands, coordinate guessing, or a stale snapshot to bypass a failed target check.
                $visualFallbackInstruction
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
                    whenToUse = "Use only when the accessibility tree cannot expose a reliable target or action.",
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
                        whenToUse = "After the accessibility tree cannot expose a reliable visible target; it requires confirmation because screen content leaves the device.",
                        resultSemantics = "Returns an observationId, screen metadata, and an image attachment. The observation is short-lived and must not be reused after a new capture."
                    )
                } else {
                    null
                },
                if (includeVisualFallback) {
                    AndroidSkillMethod(
                        toolName = "screen_visual_gesture",
                        purpose = "Performs a coordinate gesture against a fresh visual screen observation.",
                        whenToUse = "Only when no reliable accessibility node/action exists and the target rectangle is visible in the latest screen_capture_visual image.",
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
