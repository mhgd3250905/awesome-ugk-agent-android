package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.ugk.pi.android.AndroidAccessibilityServiceState
import com.ugk.pi.android.AndroidAccessibilityServiceStateProvider

class AgentAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let {
            activePackageName = it
        }
    }

    override fun onInterrupt() {}

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
        running = true
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        instance = null
        activePackageName = null
    }
}
