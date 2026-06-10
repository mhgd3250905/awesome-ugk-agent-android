package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AgentAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        var running = false
        var instance: AgentAccessibilityService? = null
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
    }
}
