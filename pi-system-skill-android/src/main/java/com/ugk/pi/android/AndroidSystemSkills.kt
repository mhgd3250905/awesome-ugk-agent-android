package com.ugk.pi.android

object AndroidSystemSkills {
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

    fun permissionSettingsControl(): AndroidSkill {
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
                The agent must decide when confirmation is needed and call show_user_confirmation_dialog before surprising user-visible actions.

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

                Common app-facing intents:
                - camera_capture: launch camera photo capture after CAMERA is granted.
                - video_capture: launch camera video capture after CAMERA is granted.
                - pick_image: open a system/gallery picker for images.
                - record_audio: launch a system audio recorder when the user asks to record sound.
                - dial_phone: open the dialer with phone_number; this does not place a direct call.
                - send_sms: open SMS compose with phone_number and optional message.
                - send_email: open email compose with to, subject, and body.
                - open_url: open a web URL with url.
                - open_map: open a map with geo_uri or query.
                - share_text: open Android share sheet with text.
                - web_search: launch web search with query.
                - open_app_market: open an app marketplace details page with package_name.

                Prefer get_android_permission_status before requesting or opening settings.
                Before requesting sensitive permissions, opening settings pages, launching external app-facing intents, or triggering actions that may leave this app, call show_user_confirmation_dialog with clear title/message and explicit buttons.
                Continue only when the returned selectedButtonId corresponds to a confirming choice.
                Use request_android_runtime_permissions only for normal runtime permissions that Android can prompt for.
                Use launch_android_app_intent for whitelisted app-facing actions such as camera capture, media picking, dialer, SMS, email, URL, map, sharing, search, and app marketplace.
                Do not use settings-page tools for app-facing actions.
                Use open_android_settings_page when the required action is a whitelisted system page, system switch, app-specific settings page, or special app access page.
                If a method returns unavailable or failed, explain the fallback manually and do not claim the setting changed.
            """.trimIndent(),
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "get_android_permission_status",
                    purpose = "Reads current grant state for known or requested Android permissions.",
                    whenToUse = "Use before requesting permissions or directing the user to settings.",
                    resultSemantics = "granted=true means Android currently grants that permission; shouldShowRationale=false with granted=false may indicate the app needs settings recovery."
                ),
                AndroidSkillMethod(
                    toolName = "request_android_runtime_permissions",
                    purpose = "Shows Android's runtime permission prompt for requested permissions through the current Activity.",
                    whenToUse = "Use for CAMERA, location, Bluetooth runtime permissions, and POST_NOTIFICATIONS when the platform supports runtime prompts.",
                    resultSemantics = "Each result reports granted after the user responds; false means the app still cannot use that protected API."
                ),
                AndroidSkillMethod(
                    toolName = "show_user_confirmation_dialog",
                    purpose = "Shows a parameterized confirmation dialog and returns the selected button id to the agent loop.",
                    whenToUse = "Use before permission prompts, settings jumps, external intents, sharing, messaging, recording, camera, or other actions the user may not expect.",
                    resultSemantics = "selectedButtonId is only the user's choice; buttons do not execute host actions directly."
                ),
                AndroidSkillMethod(
                    toolName = "open_android_settings_page",
                    purpose = "Opens a controlled Android settings page such as app details, app permissions, notifications, battery optimization, Bluetooth, location, overlay, or exact alarm.",
                    whenToUse = "Use when a runtime prompt is unavailable, the user must change a system switch, or permission recovery requires Settings.",
                    resultSemantics = "opened=true only means Android accepted the settings Intent; the user still has to change the setting manually."
                ),
                AndroidSkillMethod(
                    toolName = "launch_android_app_intent",
                    purpose = "Launches a controlled Android app-facing Intent for common actions such as camera, media picker, recorder, dialer, SMS, email, URL, map, share, search, or app marketplace.",
                    whenToUse = "Use when the user wants to perform a common Android app action rather than inspect or change a system setting.",
                    resultSemantics = "launched=true only means Android accepted the Intent; returned activity results and content capture are outside this demo tool."
                )
            )
        )
    }
}
