package com.ugk.pi.android

object AndroidSystemSkills {
    fun androidAutomationControl(requireUserConfirmation: Boolean = true): AndroidSkill {
        val confirmationInstruction = if (requireUserConfirmation) {
            "Before every protected external action (`launch_android_app`, `launch_android_app_intent`, or `open_android_accessibility_settings`), call show_user_confirmation_dialog immediately before the protected Tool. Set the confirmation target.toolName to the exact next protected Tool name and target.input to the complete JSON input for that exact call. Invoke the next Tool with the identical name and input. selectedButtonId only records which dialog button the user chose; it does not authorize a protected Tool by itself, and a missing or mismatched target ticket must be treated as not authorized."
        } else {
            AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION
        }
        return AndroidSkill(
            id = "android-app-automation",
            description = "Plan cross-app Android tasks using PackageManager discovery, Android launch intents, AccessibilityService state, and the optional SDK screen automation Skill.",
            triggers = listOf(
                "launch app",
                "open app",
                "find app",
            "accessibility service",
                "启动应用",
                "打开应用",
                "查找应用",
                "点击应用",
                "操作应用",
                "无障碍",
                "读屏"
            ),
            instructions = """
                This Android-Skill describes app discovery, app launch, and AccessibilityService readiness.
                The separate android-accessibility-screen-automation Skill owns screen snapshots, target selection,
                node actions, gestures, and global screen operations. When screen tools are available, follow that
                Skill's snapshotId/nodeId and exact-confirmation contract instead of inferring screen tool inputs here.
                The Agent runs inside a normal Android host app. It is not Android Shell, root, or a full Linux distribution.

                Use find_android_app with the user's human-visible app name before launching an app. Treat more than one candidate as ambiguous and ask the user to choose; never guess a package name.
                Use launch_android_app with the exact packageName from find_android_app when the task is to open an installed app. This launch does not require AccessibilityService.
                For a user-visible URL, use launch_android_app_intent with target open_url. Do not use terminal_bash_execute, am, pm, or screen icon searching for app launch.

                $confirmationInstruction
                In confirmation mode, the confirmation target object is separate from any protected Tool input's own target field. In full authorization mode, never create a confirmation ToolCall.

                Before reading or operating another app's UI, call get_android_accessibility_status. Continue only when readyForScreenAutomation=true.
                If the service is disabled, call open_android_accessibility_settings, explain that the user must enable the host service manually, and wait for the user to return before checking status again. Android does not allow the Agent to grant this permission silently.
                Once ready, call screen_read_ui_tree before choosing a node. Use screen_perform_action for node actions and screen_gesture only when the UI tree cannot expose the target. After every launch, click, text entry, scroll, or gesture, read the screen again and verify the result.

                A successful launch or gesture only means Android accepted the request. It does not prove that the target screen or action completed.
            """.trimIndent(),
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "find_android_app",
                    purpose = "Resolves a human app name or partial package name to launchable package candidates.",
                    whenToUse = "Use before launching an app when the user did not provide an exact package name.",
                    resultSemantics = "count=0 means no launchable app matched; ambiguous=true means ask the user to disambiguate."
                ),
                AndroidSkillMethod(
                    toolName = "launch_android_app",
                    purpose = "Launches an installed app by exact package name through its launcher Activity.",
                    whenToUse = "Use after find_android_app returns a selected packageName.",
                    resultSemantics = "launched=true means Android accepted the launch request; read the screen to verify the target app."
                ),
                AndroidSkillMethod(
                    toolName = "get_android_accessibility_status",
                    purpose = "Checks whether the host AccessibilityService is enabled and connected for cross-app screen automation.",
                    whenToUse = "Use before screen_read_ui_tree or any cross-app screen action.",
                    resultSemantics = "readyForScreenAutomation=true is required before using host screen tools."
                ),
                AndroidSkillMethod(
                    toolName = "open_android_accessibility_settings",
                    purpose = "Opens Android Accessibility settings for the user to enable the host service.",
                    whenToUse = "Use only when get_android_accessibility_status reports enabledByUser=false.",
                    resultSemantics = "userMustEnableManually=true means the Agent must wait for the user and check status again."
                )
            )
        )
    }

    fun appSettingsInspection(): AndroidSkill {
        return AndroidSkill(
            id = "app-settings-inspection",
            description = "Use when the user asks about Android app settings, permissions, notification settings, battery optimization, background behavior, Bluetooth scan access, or location access.",
            triggers = listOf(
                "app settings",
                "settings",
                "permission",
                "permissions",
                "notification",
                "notifications",
                "battery",
                "background",
                "bluetooth",
                "location",
                "\u6743\u9650",
                "\u901a\u77e5",
                "\u540e\u53f0",
                "\u7535\u6c60",
                "\u8bbe\u7f6e",
                "\u84dd\u7259",
                "\u5b9a\u4f4d"
            ),
            instructions = """
                This Android-Skill explains the host app's prebuilt app environment inspection method.
                It is not a workflow and it does not create new capabilities.
                Use it when the conversation is about whether Android system settings are blocking this app.

                Prefer the prebuilt method over guessing from user wording. Interpret the result as a snapshot:
                package and version identify the installed app, notification state explains alert delivery,
                battery optimization explains background restrictions, and permission entries show whether
                Android runtime permissions are currently granted.

                If a setting is disabled or unknown, explain which Android settings page the user should check.
                Do not claim that a permission or setting is enabled unless the method result says so.
            """.trimIndent(),
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "get_app_environment_info",
                    purpose = "Reads app package/version, device SDK, notification state, battery optimization state, and selected Android runtime permission states.",
                    whenToUse = "Use when the user asks about app settings, missing notifications, background behavior, permission problems, Bluetooth scan access, or location access.",
                    resultSemantics = "notificationEnabled=false means alerts may be blocked; batteryOptimizationIgnored=false means Android may restrict background behavior; permission granted=false means the app cannot use that protected API."
                )
            )
        )
    }

    fun permissionSettingsControl(requireUserConfirmation: Boolean = true): AndroidSkill {
        val confirmationInstruction = if (requireUserConfirmation) {
            "Before each protected call to request_android_runtime_permissions, open_android_settings_page, or launch_android_app_intent, call show_user_confirmation_dialog with clear title/message, explicit buttons, and a target object. Set target.toolName to the exact next Tool name and target.input to the complete JSON input for that exact call; then invoke the next Tool with the identical name and input. selectedButtonId only records which dialog button the user chose; it does not authorize a protected Tool by itself. Proceed only when the latest confirmation result also contains the matching host-issued ticket; a missing or mismatched target must be treated as not authorized."
        } else {
            AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION
        }
        return AndroidSkill(
            id = "permission-settings-control",
            description = "Use when the user asks to enable, request, inspect, or open Android permissions and system settings for camera, Bluetooth, location, notifications, battery/background behavior, overlay, or exact alarms.",
            triggers = listOf(
                "permission",
                "permissions",
                "request permission",
                "open settings",
                "app settings",
                "camera",
                "bluetooth",
                "location",
                "notification",
                "battery",
                "background",
                "overlay",
                "alarm",
                "\u6743\u9650",
                "\u7533\u8bf7\u6743\u9650",
                "\u6253\u5f00\u6743\u9650",
                "\u8bbe\u7f6e",
                "\u7cfb\u7edf\u8bbe\u7f6e",
                "\u76f8\u673a",
                "\u84dd\u7259",
                "\u5b9a\u4f4d",
                "\u4f4d\u7f6e",
                "\u901a\u77e5",
                "\u7535\u6c60",
                "\u540e\u53f0",
                "\u60ac\u6d6e\u7a97",
                "\u95f9\u949f"
            ),
            instructions = """
                This Android-Skill is the permission and settings capability map for this host app.
                It explains prebuilt methods; it does not create permissions and it does not bypass user consent.
                $confirmationInstruction

                Runtime permission families:
                - Camera: android.permission.CAMERA. Request with request_android_runtime_permissions. If denied permanently, open app_permissions or app_details.
                - Camera capture: after CAMERA is granted, use launch_android_app_intent with target camera_capture when the user asks to take a photo or open the camera.
                - Bluetooth on Android 12+: android.permission.BLUETOOTH_SCAN and android.permission.BLUETOOTH_CONNECT. Use bluetooth settings if the system switch is off.
                - Bluetooth scan on Android 11 and below: location permissions may be required for scan visibility.
                - Location: ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION. Use location settings if the system location switch is off.
                - Notifications on Android 13+: POST_NOTIFICATIONS. Notification channel or global app notification issues require notification settings.

                Settings and special access:
                - app_details: app-level system page for manual permission recovery.
                - app_permissions: app details page used when the user needs to edit denied permissions.
                - notifications: app notification settings and channels.
                - battery_optimization: Android battery optimization page for background restrictions.
                - bluetooth: system Bluetooth settings.
                - location: system Location settings.
                - overlay: special app access for draw-over-other-apps.
                - exact_alarm: special app access for exact alarms on supported Android versions.

                Prefer get_android_permission_status before requesting or opening settings.
                Use request_android_runtime_permissions only for normal runtime permissions that Android can prompt for.
                Do not use settings-page tools for app-facing actions.
                Use open_android_settings_page when the required action is a whitelisted system page, system switch, app-specific settings page, or special app access page.
                If a method returns unavailable or failed, explain the fallback manually and do not claim the setting changed.
            """.trimIndent(),
            methods = buildList {
                add(
                    AndroidSkillMethod(
                        toolName = "get_android_permission_status",
                        purpose = "Reads current grant state for known or requested Android permissions.",
                        whenToUse = "Use before requesting permissions or directing the user to settings.",
                        resultSemantics = "granted=true means Android currently grants that permission; shouldShowRationale=false with granted=false may indicate the app needs settings recovery."
                    )
                )
                add(
                    AndroidSkillMethod(
                        toolName = "request_android_runtime_permissions",
                        purpose = "Shows Android's runtime permission prompt for requested permissions through the current Activity.",
                        whenToUse = "Use for CAMERA, location, Bluetooth runtime permissions, and POST_NOTIFICATIONS when the platform supports runtime prompts.",
                        resultSemantics = "Each result reports granted after the user responds; false means the app still cannot use that protected API."
                    )
                )
                if (requireUserConfirmation) {
                    add(
                        AndroidSkillMethod(
                            toolName = "show_user_confirmation_dialog",
                            purpose = "Shows a parameterized confirmation dialog and returns the selected button id to the agent loop.",
                            whenToUse = "Use before permission prompts, settings jumps, external intents, sharing, messaging, recording, camera, or other actions the user may not expect.",
                            resultSemantics = "The request must include target.toolName and target.input for the exact next protected Tool; selectedButtonId records the user's choice but does not authorize that Tool by itself."
                        )
                    )
                }
                add(
                    AndroidSkillMethod(
                        toolName = "open_android_settings_page",
                        purpose = "Opens a controlled Android settings page such as app details, app permissions, notifications, battery optimization, Bluetooth, location, overlay, or exact alarm.",
                        whenToUse = "Use when a runtime prompt is unavailable, the user must change a system switch, or permission recovery requires Settings.",
                        resultSemantics = "opened=true only means Android accepted the settings Intent; the user still has to change the setting manually."
                    )
                )
            }
        )
    }

    fun appFacingIntentControl(requireUserConfirmation: Boolean = true): AndroidSkill {
        val confirmationInstruction = if (requireUserConfirmation) {
            "Before any user-visible external action, call show_user_confirmation_dialog immediately before launch_android_app_intent. Set target.toolName to launch_android_app_intent and target.input to the complete JSON input for the exact next call, including the actual target and parameters values; then call launch_android_app_intent with the identical input. selectedButtonId only records which dialog button the user chose; it does not authorize a protected Tool by itself. Do not infer authorization from the button id when the target or its input is missing or changed; the host-issued ticket must match."
        } else {
            AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION
        }
        return AndroidSkill(
            id = "app-facing-intent-control",
            description = "Use Android's native Intent resolver for user-visible app actions such as opening a URL, camera, dialer, map, share sheet, or media picker.",
            triggers = listOf(
                "open url",
                "open website",
                "open link",
                "open browser",
                "launch app",
                "camera",
                "dial",
                "call",
                "sms",
                "email",
                "map",
                "share",
                "web search",
                "网页",
                "网站",
                "链接",
                "浏览器",
                "打开网址",
                "启动应用",
                "拍照",
                "拨号",
                "短信",
                "邮件",
                "地图",
                "分享"
            ),
            instructions = """
                This Android-Skill maps user-visible app actions to the prebuilt launch_android_app_intent tool.
                It calls Android's native Intent resolver; it is not a terminal command and it does not require Termux or accessibility access.

                For a website or link, use target open_url with parameters.url. Do not use terminal_bash_execute, am, pm, or shell commands to launch Android apps or to infer whether a browser is installed. The terminal runs inside the host app's private runtime and is not Android Shell.

                Supported targets include camera_capture, video_capture, pick_image, record_audio, dial_phone, send_sms, send_email, open_url, open_map, share_text, web_search, and open_app_market.
                $confirmationInstruction
                In confirmation mode, the confirmation target object is separate from any protected Tool input's own target field. In full authorization mode, never create a confirmation ToolCall.
                launched=true means Android accepted and dispatched the Intent; it does not prove that the target app completed its UI action. If the tool returns no_handler or launch_failed, report that exact limitation and do not claim the action happened.
            """.trimIndent(),
            methods = buildList {
                if (requireUserConfirmation) {
                    add(
                        AndroidSkillMethod(
                            toolName = "show_user_confirmation_dialog",
                            purpose = "Shows a parameterized confirmation dialog before a user-visible external Intent.",
                            whenToUse = "Use before opening a URL, launching an external app, sharing, messaging, dialing, recording, or using camera/media pickers.",
                            resultSemantics = "The request must include target.toolName and target.input for the exact next protected Tool; selectedButtonId records the user's choice but does not authorize that Tool by itself."
                        )
                    )
                }
                add(
                    AndroidSkillMethod(
                        toolName = "launch_android_app_intent",
                        purpose = "Dispatches a whitelisted Android app-facing Intent such as open_url, camera_capture, dial_phone, open_map, or share_text.",
                        whenToUse = "Use instead of terminal commands whenever the user asks to open or hand data to an Android application.",
                        resultSemantics = "launched=true means the Intent was dispatched; resolvedPackage identifies the selected Android handler when available."
                    )
                )
            }
        )
    }
}
