package com.ugk.pi.android.testapp

import android.Manifest
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses synthetic callbacks; never changes the device's permissions or creates an Agent run. */
@RunWith(AndroidJUnit4::class)
class PermissionGuideInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun eachPermissionExplainsItsActionInBothThemesWithoutRequestingOnDisplay() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            withSheet(mode) { _, sheet ->
                var actions = 0
                PermissionGuideStep.entries.forEachIndexed { index, step ->
                    main {
                        sheet.show(step, index + 1, PermissionGuideStep.entries.size, { actions++ }, { actions++ }, { actions++ })
                    }
                    settle()
                    main {
                        val window = requireNotNull(currentDialog(sheet).window)
                        assertEquals(Gravity.BOTTOM, window.attributes.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                        val title = tagged(sheet, "permission_guide_title") as TextView
                        assertTrue(title.text.isNotBlank())
                        assertTrue((tagged(sheet, "permission_guide_body") as TextView).text.isNotBlank())
                        for (tag in listOf("primary", "skip", "close")) {
                            assertFullyVisible(tagged(sheet, "permission_guide_$tag"))
                        }
                        assertEquals("Displaying an explanation must not launch a request", 0, actions)
                    }
                    capture("${mode.key}-${step.name.lowercase()}")
                }
            }
        }
    }

    @Test
    fun narrowWindowAtDoubleFontKeepsActionsVisibleAndExplanationScrollable() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            withSheet(mode, 2f) { activity, sheet ->
                for (step in listOf(PermissionGuideStep.ACCESSIBILITY, PermissionGuideStep.CAMERA, PermissionGuideStep.NOTIFICATIONS)) {
                    main {
                        sheet.show(step, 1, 3, {}, {}, {},
                            primaryLabel = if (step == PermissionGuideStep.NOTIFICATIONS) "开启任务提醒" else "去开启",
                            notificationChannelBlocked = step == PermissionGuideStep.NOTIFICATIONS)
                        currentDialog(sheet).window!!.setLayout(activity.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    settle()
                    main {
                        assertFullyVisible(tagged(sheet, "permission_guide_primary"))
                        assertFullyVisible(tagged(sheet, "permission_guide_skip"))
                        assertFullyVisible(tagged(sheet, "permission_guide_close"))
                        assertFullyVisible(tagged(sheet, "permission_guide_progress"))
                    }
                    capture("large-top-${mode.key}-${step.name.lowercase()}")
                    main {
                        val explanation = tagged(sheet, "permission_guide_body")
                        explanation.requestRectangleOnScreen(Rect(0, 0, explanation.width, explanation.height), true)
                    }
                    settle()
                    main {
                        val visible = Rect()
                        assertTrue("Explanation cannot be reached by scrolling",
                            tagged(sheet, "permission_guide_body").getGlobalVisibleRect(visible))
                        assertFullyVisible(tagged(sheet, "permission_guide_primary"))
                        assertFullyVisible(tagged(sheet, "permission_guide_skip"))
                    }
                    capture("large-${mode.key}-${step.name.lowercase()}")
                }
            }
        }
    }

    @Test
    fun channelInspectionPreservesBlockedSettingsAndDoesNotTreatSilentOrNewChannelsAsBlocked() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val context = instrumentation.targetContext
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val blockedId = "permission-guide-test-blocked-${UUID.randomUUID()}"
        val quietId = "permission-guide-test-quiet-${UUID.randomUUID()}"
        try {
            assertFalse(DemoNotificationSettings.isChannelBlocked(context, blockedId))
            assertNull("Inspection must not create a channel", manager.getNotificationChannel(blockedId))
            manager.createNotificationChannel(NotificationChannel(blockedId, "Permission guide test", NotificationManager.IMPORTANCE_NONE))
            manager.createNotificationChannel(NotificationChannel(quietId, "Permission guide quiet test", NotificationManager.IMPORTANCE_LOW))
            assertTrue(DemoNotificationSettings.isChannelBlocked(context, blockedId))
            assertFalse("Silent notifications still work; do not prompt to override that preference",
                DemoNotificationSettings.isChannelBlocked(context, quietId))
            val intent = DemoNotificationSettings.channelSettingsIntent(context, blockedId)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertEquals(blockedId, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            assertEquals("Inspection and settings navigation must not re-enable notifications",
                NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(blockedId).importance)
        } finally {
            manager.deleteNotificationChannel(blockedId)
            manager.deleteNotificationChannel(quietId)
        }
    }

    @Test
    fun disabledReminderCategoryRefreshesTheNotificationExplanationInBothThemes() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            withSheet(mode) { _, sheet ->
                main {
                    sheet.show(PermissionGuideStep.NOTIFICATIONS, 1, 1, {}, {}, {})
                    assertFalse((tagged(sheet, "permission_guide_body") as TextView).text.contains("已关闭"))
                    sheet.show(PermissionGuideStep.NOTIFICATIONS, 1, 1, {}, {}, {},
                        primaryLabel = "开启任务提醒", notificationChannelBlocked = true)
                }
                settle()
                main {
                    val body = (tagged(sheet, "permission_guide_body") as TextView).text
                    assertTrue(body.contains("系统通知已允许"))
                    assertTrue(body.contains("Agent 醒目提醒"))
                    assertTrue(body.contains("已关闭"))
                    assertFullyVisible(tagged(sheet, "permission_guide_primary"))
                    assertFullyVisible(tagged(sheet, "permission_guide_skip"))
                }
                capture("${mode.key}-notification-category-blocked")
            }
        }
    }

    @Test
    fun settingsEntryFollowsThemeAndManualGuidanceSurvivesRecreation() {
        val context = instrumentation.targetContext
        val previousTheme = ThemeStore(context).getThemeMode()
        try {
            main { ThemeManager.init(context); ThemeManager.setMode(context, AppThemeMode.LIGHT) }
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val entry = requireNotNull(activity.window.decorView.findViewWithTag<TextView>("settings_background_guidance"))
                    assertEquals(TaskNoteUi.Ink, entry.currentTextColor)
                    ThemeManager.setMode(activity, AppThemeMode.DARK)
                    assertEquals("The existing entry must update immediately with its page", TaskNoteUi.Ink, entry.currentTextColor)
                    entry.performClick()
                }
                settle()
                scenario.recreate()
                settle()
                scenario.onActivity { activity ->
                    val host = SettingsActivity::class.java.getDeclaredField("backgroundGuidance")
                        .apply { isAccessible = true }.get(activity) as DemoBackgroundGuidanceHost
                    assertTrue(host.isActive)
                    val sheet = DemoBackgroundGuidanceHost::class.java.getDeclaredField("sheet")
                        .apply { isAccessible = true }.get(host) as DemoPermissionGuideDialog
                    assertTrue(currentDialog(sheet).isShowing)
                    assertEquals("我已了解，不再提醒", (tagged(sheet, "permission_guide_primary") as TextView).text.toString())
                    tagged(sheet, "permission_guide_skip").performClick()
                    assertFalse(host.isActive)
                }
            }
        } finally {
            main { ThemeManager.setMode(context, previousTheme) }
        }
    }

    @Test
    fun backgroundAdviceOnlyRecordsReviewOnExplicitAcknowledgement() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("background_run_guidance", Context.MODE_PRIVATE)
        val previousVersion = if (prefs.contains("reviewed_version")) prefs.getInt("reviewed_version", 0) else null
        try {
            prefs.edit().remove("reviewed_version").commit()
            withSheet(AppThemeMode.LIGHT) { activity, sheet ->
                val store = DemoBackgroundRunGuidanceStore(activity)
                var battery = 0
                var autostart = 0
                var skip = 0
                var close = 0
                main {
                    sheet.show(PermissionGuideStep.BACKGROUND, 1, 1,
                        onPrimary = { store.markReviewed() }, onSkip = { skip++ }, onClose = { close++ },
                        onBackgroundBattery = { battery++ }, onBackgroundAutostart = { autostart++ })
                }
                settle()
                assertFalse(store.isReviewed())
                assertEquals(0, battery + autostart + skip + close)
                main { tagged(sheet, "background_battery_settings").performClick() }
                assertEquals(1, battery)
                assertEquals(0, autostart)
                assertFalse("Opening settings is not acknowledgement or a permission grant", store.isReviewed())
                main { tagged(sheet, "background_autostart_settings").performClick() }
                assertEquals(1, autostart)
                main { tagged(sheet, "permission_guide_skip").performClick() }
                main { tagged(sheet, "permission_guide_close").performClick() }
                assertEquals(1, skip)
                assertEquals(1, close)
                assertFalse(store.isReviewed())
                main { tagged(sheet, "permission_guide_primary").performClick() }
                assertTrue(DemoBackgroundRunGuidanceStore(activity).isReviewed())
            }
        } finally {
            val edit = prefs.edit()
            if (previousVersion == null) edit.remove("reviewed_version") else edit.putInt("reviewed_version", previousVersion)
            edit.commit()
        }
    }

    @Test
    fun backgroundAdviceAtDoubleFontKeepsSuggestionsScrollableAndFooterVisible() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            withSheet(mode, 2f) { activity, sheet ->
                main {
                    sheet.show(PermissionGuideStep.BACKGROUND, 1, 1, {}, {}, {})
                    currentDialog(sheet).window!!.setLayout(activity.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                settle()
                main {
                    for (tag in listOf("primary", "skip", "close", "progress")) {
                        assertFullyVisible(tagged(sheet, "permission_guide_$tag"))
                    }
                }
                capture("${mode.key}-background-large-top")
                for (tag in listOf("background_battery_settings", "background_autostart_settings", "background_recents_hint")) {
                    main {
                        val target = tagged(sheet, tag)
                        target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
                    }
                    settle()
                    main {
                        assertFullyVisible(tagged(sheet, tag))
                        assertFullyVisible(tagged(sheet, "permission_guide_primary"))
                        assertFullyVisible(tagged(sheet, "permission_guide_skip"))
                    }
                    capture("${mode.key}-background-large-$tag")
                }
            }
        }
    }

    @Test
    fun unavailableOrProtectedBatteryPagesFallBackToPublicSettings() = withSheet(AppThemeMode.LIGHT) { activity, _ ->
        val attempts = mutableListOf<Intent>()
        var opened = false
        main {
            opened = DemoBackgroundSettings.openBattery(activity) { intent ->
                attempts += intent
                when (attempts.size) {
                    1 -> throw ActivityNotFoundException()
                    2 -> throw SecurityException()
                }
            }
        }
        assertTrue(opened)
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Settings.ACTION_SETTINGS), attempts.map { it.action })
        assertEquals("package:${activity.packageName}", attempts.first().dataString)
        assertNull(attempts[1].data)
    }

    @Test
    fun runtimeHistoryPreservesOldRequestsAndNeverTreatsPastGrantsAsCurrentPermission() = withSheet(AppThemeMode.LIGHT) { activity, _ ->
        val prefs = activity.getSharedPreferences("permission_guide_requests", Context.MODE_PRIVATE)
        val legacyPermission = "${activity.packageName}.permission.TEST_LEGACY_${UUID.randomUUID()}"
        val observedPermission = "${activity.packageName}.permission.TEST_OBSERVED_${UUID.randomUUID()}"
        try {
            // Synthetic names keep real permissions untouched while exercising persisted migration.
            prefs.edit().putBoolean(legacyPermission, true).commit()
            val history = DemoRuntimePermissionHistory(activity)
            assertEquals(RuntimePermissionAction.SETTINGS, history.action(activity, legacyPermission))
            assertEquals(RuntimePermissionAction.REQUEST, history.action(activity, observedPermission))
            history.recordResult(observedPermission, true)
            val restored = DemoRuntimePermissionHistory(activity)
            assertFalse("A historical grant must not override Android's current denial", restored.observe(observedPermission))
            assertEquals(RuntimePermissionAction.SETTINGS, restored.action(activity, observedPermission))
            assertTrue(prefs.getBoolean("granted:$observedPermission", false))
            val cameraSettings = history.settingsIntent(Manifest.permission.CAMERA)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, cameraSettings.action)
            assertEquals("package:${activity.packageName}", cameraSettings.dataString)
            if (Build.VERSION.SDK_INT >= 26) {
                val notifications = history.settingsIntent(Manifest.permission.POST_NOTIFICATIONS)
                assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
                assertEquals(activity.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            }
        } finally {
            prefs.edit().remove(legacyPermission).remove(observedPermission)
                .remove("granted:$observedPermission").commit()
        }
    }

    @Test
    fun blockedRuntimePermissionRefreshesTheSameSheetAndKeepsRecoveryActionsReachable() {
        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) {
            withSheet(mode, 2f) { activity, sheet ->
                for (step in listOf(PermissionGuideStep.CAMERA, PermissionGuideStep.NOTIFICATIONS)) {
                    var actions = 0
                    main {
                        sheet.show(step, 1, 2, { actions++ }, { actions++ }, { actions++ }, primaryLabel = "允许开启")
                        // A routing change alone must refresh an already visible step.
                        sheet.show(step, 1, 2, { actions++ }, { actions++ }, { actions++ }, primaryLabel = "去系统设置")
                        assertEquals("去系统设置", (tagged(sheet, "permission_guide_primary") as TextView).text.toString())
                        sheet.show(step, 1, 2, { actions++ }, { actions++ }, { actions++ },
                            primaryLabel = "去系统设置", runtimeSettingsFallback = true)
                        currentDialog(sheet).window!!.setLayout(activity.dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    settle()
                    main {
                        val recovery = tagged(sheet, "permission_guide_recovery") as TextView
                        assertEquals("当前未获得权限，请在系统设置中开启。", recovery.text.toString())
                        recovery.requestRectangleOnScreen(Rect(0, 0, recovery.width, recovery.height), true)
                    }
                    settle()
                    main {
                        assertFullyVisible(tagged(sheet, "permission_guide_recovery"))
                        assertFullyVisible(tagged(sheet, "permission_guide_primary"))
                        assertFullyVisible(tagged(sheet, "permission_guide_skip"))
                        assertEquals("Recovery must wait for the user's choice", 0, actions)
                    }
                    capture("${mode.key}-${step.name.lowercase()}-settings-recovery")
                    main {
                        sheet.show(step, 1, 2, {}, {}, {}, primaryLabel = "允许开启")
                        assertNull(currentDialog(sheet).window!!.decorView.findViewWithTag<View>("permission_guide_recovery"))
                    }
                }
            }
        }
    }

    @Test
    fun primarySkipCloseAndBackHaveIndependentCallbacks() = withSheet(AppThemeMode.LIGHT) { _, sheet ->
        var requested = 0
        var skipped = 0
        var closed = 0
        fun show() = main {
            sheet.dismiss()
            sheet.show(PermissionGuideStep.CAMERA, 1, 1,
                onPrimary = { requested++; sheet.dismiss() },
                onSkip = { skipped++; sheet.dismiss() },
                onClose = { closed++; sheet.dismiss() })
        }
        show()
        main { tagged(sheet, "permission_guide_skip").performClick() }
        assertEquals(1, skipped)
        assertEquals(0, requested)
        show()
        main { tagged(sheet, "permission_guide_close").performClick() }
        assertEquals(1, closed)
        assertEquals(0, requested)
        show()
        main { currentDialog(sheet).cancel() }
        instrumentation.waitForIdleSync() // Dialog.cancel posts OnCancelListener to the main looper.
        assertEquals(2, closed)
        show()
        main { tagged(sheet, "permission_guide_primary").performClick() }
        assertEquals(1, requested)
        assertEquals(1, skipped)
        assertEquals(2, closed)
    }

    private fun withSheet(
        mode: AppThemeMode,
        fontScale: Float = 1f,
        block: (DemoDialogTestHostActivity, DemoPermissionGuideDialog) -> Unit
    ) {
        val context = instrumentation.targetContext
        val previousMode = ThemeStore(context).getThemeMode()
        val previousScale = DemoDialogTestHostActivity.fontScaleOverride
        var host: DemoDialogTestHostActivity? = null
        var sheet: DemoPermissionGuideDialog? = null
        try {
            main {
                ThemeManager.init(context)
                ThemeManager.setMode(context, mode)
                DemoDialogTestHostActivity.fontScaleOverride = fontScale
            }
            val activity = instrumentation.startActivitySync(
                Intent(context, DemoDialogTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as DemoDialogTestHostActivity
            host = activity
            val created = DemoPermissionGuideDialog(activity)
            sheet = created
            block(activity, created)
        } finally {
            main {
                sheet?.dismiss()
                host?.finish()
                DemoDialogTestHostActivity.fontScaleOverride = previousScale
                ThemeManager.setMode(context, previousMode)
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
    }
    private fun currentDialog(sheet: DemoPermissionGuideDialog): Dialog =
        DemoPermissionGuideDialog::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            .get(sheet) as Dialog
    private fun tagged(sheet: DemoPermissionGuideDialog, tag: String): View =
        requireNotNull(currentDialog(sheet).window!!.decorView.findViewWithTag(tag))

    private fun assertFullyVisible(view: View) {
        val rect = Rect()
        assertTrue("${view.tag} is not visible", view.getGlobalVisibleRect(rect))
        assertTrue("${view.tag} is clipped vertically", rect.height() >= view.height - 1)
        assertTrue("${view.tag} is clipped horizontally", rect.width() >= view.width - 1)
        if (view is TextView) {
            val layout = requireNotNull(view.layout)
            assertTrue("${view.tag} text is clipped",
                layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            for (line in 0 until layout.lineCount) {
                assertEquals(0, layout.getEllipsisCount(line))
                assertTrue("${view.tag} glyphs are clipped horizontally",
                    layout.getLineWidth(line) <= view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1)
            }
        }
    }

    private fun capture(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "permission-guide-ui")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
