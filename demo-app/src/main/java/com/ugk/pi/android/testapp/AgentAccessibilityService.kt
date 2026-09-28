package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ugk.pi.android.AndroidAccessibilityServiceState
import com.ugk.pi.android.AndroidAccessibilityServiceStateProvider

class AgentAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        trackedExternalAccessibilityPackage(event?.packageName, packageName)?.let {
            activePackageName = it
        }
        DemoProcessScope.get(this).operationRecorder.onAccessibilityEvent(this, event)
    }

    override fun onInterrupt() {
        DemoProcessScope.get(this).operationRecorder.onServiceUnavailable()
    }

    companion object {
        @Volatile
        var running = false

        @Volatile
        var instance: AgentAccessibilityService? = null

        @Volatile
        var activePackageName: String? = null

        val runtimeStateProvider = AndroidAccessibilityServiceStateProvider {
            AndroidAccessibilityServiceState(
                connected = running && instance != null,
                activePackageName = activePackageName
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activePackageName = null
        running = true
        instance = this
    }

    override fun onDestroy() {
        DemoProcessScope.get(this).operationRecorder.onServiceUnavailable()
        super.onDestroy()
        running = false
        instance = null
        activePackageName = null
    }
}

internal fun trackedExternalAccessibilityPackage(
    eventPackageName: CharSequence?,
    ownPackageName: String
): String? = eventPackageName
    ?.toString()
    ?.takeIf { it.isNotBlank() && it != ownPackageName }
