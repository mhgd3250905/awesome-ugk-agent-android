package com.ugk.pi.android.testapp

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

internal object DemoBackgroundGuidancePolicy {
    const val VERSION = 1
    fun needsReview(reviewedVersion: Int): Boolean = reviewedVersion < VERSION
}

/** Acknowledgement is separate from all real Android permission state. */
internal class DemoBackgroundRunGuidanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("background_run_guidance", Context.MODE_PRIVATE)
    fun isReviewed(): Boolean = !DemoBackgroundGuidancePolicy.needsReview(prefs.getInt("reviewed_version", 0))
    fun markReviewed() { prefs.edit().putInt("reviewed_version", DemoBackgroundGuidancePolicy.VERSION).apply() }
}

internal object DemoBackgroundSettings {
    fun openBattery(activity: Activity, launch: (Intent) -> Unit): Boolean = open(activity, listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    ), launch)

    fun openAutostart(activity: Activity, launch: (Intent) -> Unit): Boolean =
        open(activity, buildList {
            if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) add(Intent().apply {
                component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            })
            add(Intent(Settings.ACTION_SETTINGS))
        }, launch)

    private fun open(activity: Activity, intents: List<Intent>, launch: (Intent) -> Unit): Boolean {
        for (intent in intents) {
            try { launch(intent); return true }
            catch (_: android.content.ActivityNotFoundException) { /* Try the public fallback. */ }
            catch (_: SecurityException) { /* OEM pages can exist but refuse external callers. */ }
        }
        Toast.makeText(activity, "暂时无法打开设置，可稍后在系统设置中查看。", Toast.LENGTH_LONG).show()
        return false
    }
}

/** Explicit settings/timer entry, independent of the automatic permission queue. */
internal class DemoBackgroundGuidanceHost(
    private val activity: ComponentActivity,
    private val beforeExternal: () -> Unit = {},
    private val onClosed: () -> Unit = {}
) {
    private val sheet = DemoPermissionGuideDialog(activity)
    private val store by lazy { DemoBackgroundRunGuidanceStore(activity) }
    var isActive = false
        private set
    private var resumed = false
    private var awaitingSettings = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        awaitingSettings = false
        present()
    }

    fun restore(bundle: Bundle?) {
        isActive = bundle?.getBoolean("background_guidance_active") == true
        awaitingSettings = bundle?.getBoolean("background_guidance_awaiting") == true
    }
    fun save(bundle: Bundle) {
        bundle.putBoolean("background_guidance_active", isActive)
        bundle.putBoolean("background_guidance_awaiting", awaitingSettings)
    }
    fun show() { isActive = true; present() }
    fun onResume() { resumed = true; present() }
    fun onPause() { resumed = false; sheet.dismiss() }
    fun release() { resumed = false; sheet.dismiss() }

    private fun present() {
        if (!isActive || !resumed || awaitingSettings || activity.isFinishing || activity.isDestroyed) return
        sheet.show(PermissionGuideStep.BACKGROUND, 1, 1,
            onPrimary = { store.markReviewed(); close() }, onSkip = ::close, onClose = ::close,
            primaryLabel = "我已了解", onBackgroundBattery = { openSettings(true) },
            onBackgroundAutostart = { openSettings(false) })
    }
    private fun openSettings(battery: Boolean) {
        sheet.dismiss()
        awaitingSettings = true
        beforeExternal()
        val launch: (Intent) -> Unit = { launcher.launch(it) }
        val launched = if (battery) DemoBackgroundSettings.openBattery(activity, launch)
            else DemoBackgroundSettings.openAutostart(activity, launch)
        if (!launched) { awaitingSettings = false; present() }
    }
    private fun close() { isActive = false; sheet.dismiss(); onClosed() }
}
