package com.ugk.pi.android.testapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

internal enum class RuntimePermissionAction { GRANTED, REQUEST, SETTINGS }

internal object DemoRuntimePermissionPolicy {
    fun decide(granted: Boolean, shouldShowRationale: Boolean, everRequested: Boolean,
               everGranted: Boolean): RuntimePermissionAction = when {
        granted -> RuntimePermissionAction.GRANTED
        shouldShowRationale -> RuntimePermissionAction.REQUEST
        everRequested || everGranted -> RuntimePermissionAction.SETTINGS
        else -> RuntimePermissionAction.REQUEST
    }
}

/** History informs the route, never substitutes for the current Android grant. */
internal class DemoRuntimePermissionHistory(private val context: Context) {
    private val prefs = context.getSharedPreferences("permission_guide_requests", Context.MODE_PRIVATE)

    fun observe(permission: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        recordResult(permission, granted)
        return granted
    }

    fun recordResult(permission: String, granted: Boolean) {
        if (granted && !prefs.getBoolean("granted:$permission", false)) {
            prefs.edit().putBoolean("granted:$permission", true).apply()
        }
    }

    fun markRequested(permission: String) { prefs.edit().putBoolean(permission, true).apply() }

    fun action(activity: Activity, permission: String): RuntimePermissionAction = DemoRuntimePermissionPolicy.decide(
        granted = observe(permission),
        shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission),
        everRequested = prefs.getBoolean(permission, false),
        everGranted = prefs.getBoolean("granted:$permission", false)
    )

    fun settingsIntent(permission: String): Intent =
        if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
