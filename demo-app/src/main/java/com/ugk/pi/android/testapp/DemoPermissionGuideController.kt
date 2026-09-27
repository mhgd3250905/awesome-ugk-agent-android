package com.ugk.pi.android.testapp

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

internal object DemoPermissionForeground : Application.ActivityLifecycleCallbacks {
    val processId = java.util.UUID.randomUUID().toString()
    val state = PermissionGuideForegroundState()
    override fun onActivityStarted(activity: Activity) = state.start()
    override fun onActivityStopped(activity: Activity) = state.stop(activity.isChangingConfigurations)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal class DemoPermissionGuideController(
    private val activity: ComponentActivity,
    private val busy: () -> Boolean,
    private val onFinished: () -> Unit
) {
    private val state = DemoPermissionGuideState()
    val isAwaitingPermission: Boolean get() = state.awaitingPermission
    private val sheet = DemoPermissionGuideDialog(activity)
    private var resumed = false
    private var finishedGeneration = -1L
    private val history by lazy { DemoRuntimePermissionHistory(activity) }
    private val backgroundStore by lazy { DemoBackgroundRunGuidanceStore(activity) }
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val name = state.pendingRuntimePermission
        if (name != null) {
            history.recordResult(name, granted)
            state.completeRuntimeResult(granted,
                ActivityCompat.shouldShowRequestPermissionRationale(activity, name), onlyNotificationChannelBlocked())
            if (resumed) state.externalFlow = false
            reconsider()
        } else finishAttempt()
    }
    private val settings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishAttempt() }

    fun restore(bundle: Bundle?) {
        val saved = bundle?.getBundle("permission_guide") ?: return
        state.generation = saved.getLong("generation", -1)
        state.queue = saved.getStringArrayList("queue").orEmpty().mapNotNull { name -> PermissionGuideStep.entries.find { it.name == name } }
        state.position = saved.getInt("position")
        state.closed = saved.getBoolean("closed")
        state.externalFlow = saved.getBoolean("external")
        state.awaitingPermission = saved.getBoolean("awaiting")
        state.awaitingNotificationAppPermission = saved.getBoolean("awaiting_notification_app")
        state.pendingRuntimePermission = saved.getString("pending_runtime_permission")
        state.runtimeSettingsFallback = saved.getBoolean("runtime_settings_fallback")
        state.awaitingBackgroundSettings = saved.getBoolean("awaiting_background_settings")
        if (saved.getString("process") != DemoPermissionForeground.processId && !state.awaitingPermission) {
            state.generation = -1
            state.externalFlow = false
        }
    }

    fun save(bundle: Bundle) {
        bundle.putBundle("permission_guide", Bundle().apply {
            putString("process", DemoPermissionForeground.processId)
            putLong("generation", state.generation)
            putStringArrayList("queue", ArrayList(state.queue.map { it.name }))
            putInt("position", state.position)
            putBoolean("closed", state.closed)
            putBoolean("external", state.externalFlow)
            putBoolean("awaiting", state.awaitingPermission)
            putBoolean("awaiting_notification_app", state.awaitingNotificationAppPermission)
            putString("pending_runtime_permission", state.pendingRuntimePermission)
            putBoolean("runtime_settings_fallback", state.runtimeSettingsFallback)
            putBoolean("awaiting_background_settings", state.awaitingBackgroundSettings)
        })
    }

    fun markExternalFlow() { state.externalFlow = true }
    fun onExternalResult() {
        if (resumed && !state.awaitingPermission) state.externalFlow = false
    }
    fun canShowUpdate(): Boolean = resumed && !busy() && !state.awaitingPermission && state.current(missing().toSet()) == null
    fun onResume() {
        resumed = true
        state.enter(DemoPermissionForeground.state.generation, missing())
        reconsider()
    }
    fun onPause() { resumed = false; sheet.dismiss() }
    fun release() { resumed = false; sheet.dismiss() }

    fun reconsider() {
        if (!resumed || activity.isFinishing || activity.isDestroyed) return
        if (busy() || state.awaitingPermission) { sheet.dismiss(); return }
        val step = state.current(missing().toSet())
        if (step == null) {
            sheet.dismiss()
            if (finishedGeneration != state.generation) {
                finishedGeneration = state.generation
                onFinished()
            }
            return
        }
        sheet.show(step, state.position + 1, state.queue.size,
            onPrimary = {
                if (step == PermissionGuideStep.BACKGROUND) {
                    backgroundStore.markReviewed(); state.advance(); sheet.dismiss(); reconsider()
                } else request(step)
            },
            onSkip = { state.advance(); sheet.dismiss(); reconsider() },
            onClose = { state.close(); sheet.dismiss(); reconsider() }, primaryLabel = primaryLabel(step),
            notificationChannelBlocked = step == PermissionGuideStep.NOTIFICATIONS && onlyNotificationChannelBlocked(),
            runtimeSettingsFallback = state.runtimeSettingsFallback,
            onBackgroundBattery = { openBackgroundSettings(true) },
            onBackgroundAutostart = { openBackgroundSettings(false) })
    }

    private fun primaryLabel(step: PermissionGuideStep): String {
        if (step == PermissionGuideStep.NOTIFICATIONS && onlyNotificationChannelBlocked()) return "开启任务提醒"
        val runtime = when (step) {
            PermissionGuideStep.CAMERA -> Manifest.permission.CAMERA
            PermissionGuideStep.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) Manifest.permission.POST_NOTIFICATIONS else null
            else -> null
        }
        if (runtime != null && (state.runtimeSettingsFallback || history.action(activity, runtime) == RuntimePermissionAction.SETTINGS)) return "去系统设置"
        return when (step) {
            PermissionGuideStep.ACCESSIBILITY -> "同意并去开启"
            PermissionGuideStep.CAMERA -> "允许使用相机"
            PermissionGuideStep.NOTIFICATIONS -> "开启通知"
            PermissionGuideStep.OVERLAY -> "去开启"
            PermissionGuideStep.BACKGROUND -> "我已了解"
        }
    }

    private fun finishAttempt() {
        granted(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS)
        state.completeAttempt(continueWithNotificationChannel = onlyNotificationChannelBlocked())
        // Keep externalFlow until onResume if the callback arrived before it.
        if (resumed) state.externalFlow = false
        reconsider()
    }

    private fun missing(): List<PermissionGuideStep> {
        val cameraGranted = granted(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS)
        return PermissionGuideStep.entries.filter {
            when (it) {
                PermissionGuideStep.ACCESSIBILITY -> !accessibilityEnabled(activity)
                PermissionGuideStep.OVERLAY -> !Settings.canDrawOverlays(activity)
                PermissionGuideStep.CAMERA -> activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) && !cameraGranted
                PermissionGuideStep.NOTIFICATIONS -> !NotificationManagerCompat.from(activity).areNotificationsEnabled() ||
                    DemoNotificationSettings.isChannelBlocked(activity)
                PermissionGuideStep.BACKGROUND -> !backgroundStore.isReviewed()
            }
        }
    }

    private fun granted(name: String) = history.observe(name)

    private fun onlyNotificationChannelBlocked(): Boolean =
        NotificationManagerCompat.from(activity).areNotificationsEnabled() &&
            DemoNotificationSettings.isChannelBlocked(activity)

    private fun request(step: PermissionGuideStep) {
        sheet.dismiss()
        state.awaitingPermission = true
        state.awaitingNotificationAppPermission = step == PermissionGuideStep.NOTIFICATIONS &&
            !NotificationManagerCompat.from(activity).areNotificationsEnabled()
        markExternalFlow()
        try {
            when (step) {
                PermissionGuideStep.ACCESSIBILITY -> settings.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                PermissionGuideStep.OVERLAY -> settings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
                PermissionGuideStep.CAMERA -> requestRuntime(Manifest.permission.CAMERA)
                PermissionGuideStep.NOTIFICATIONS -> {
                    if (onlyNotificationChannelBlocked()) settings.launch(DemoNotificationSettings.channelSettingsIntent(activity))
                    else if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) requestRuntime(Manifest.permission.POST_NOTIFICATIONS)
                    else settings.launch(if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName) else appSettings())
                }
                PermissionGuideStep.BACKGROUND -> Unit // Acknowledgement is handled without requesting permission.
            }
        } catch (_: Exception) {
            state.awaitingPermission = false
            state.awaitingNotificationAppPermission = false
            state.pendingRuntimePermission = null
            state.externalFlow = false
            Toast.makeText(activity, "暂时无法打开系统设置，可以稍后再试或暂时跳过。", Toast.LENGTH_LONG).show()
            reconsider()
        }
    }

    private fun requestRuntime(name: String) {
        val action = if (state.runtimeSettingsFallback && !granted(name)) RuntimePermissionAction.SETTINGS
            else history.action(activity, name)
        when (action) {
            RuntimePermissionAction.GRANTED -> finishAttempt()
            RuntimePermissionAction.SETTINGS -> settings.launch(history.settingsIntent(name))
            RuntimePermissionAction.REQUEST -> {
                history.markRequested(name)
                state.pendingRuntimePermission = name
                permission.launch(name)
            }
        }
    }

    private fun openBackgroundSettings(battery: Boolean) {
        sheet.dismiss()
        state.awaitingPermission = true
        state.awaitingBackgroundSettings = true
        markExternalFlow()
        val launch: (Intent) -> Unit = { settings.launch(it) }
        val launched = if (battery) DemoBackgroundSettings.openBattery(activity, launch)
            else DemoBackgroundSettings.openAutostart(activity, launch)
        if (!launched) {
            state.awaitingPermission = false
            state.awaitingBackgroundSettings = false
            state.externalFlow = false
            reconsider()
        }
    }
    private fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

    companion object {
        fun accessibilityEnabled(activity: Activity): Boolean {
            if (AgentAccessibilityService.running) return true
            val expected = ComponentName(activity, AgentAccessibilityService::class.java)
            return Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        }
    }
}
