package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLifecyclePolicyInstrumentedTest {
    @Test
    fun backgroundFloatingWindowRequiresActiveRunAndOverlayPermission() {
        assertFalse(shouldShowFloatingWindowOnPause(agentRunActive = true, overlayPermissionGranted = false))
        assertFalse(shouldShowFloatingWindowOnPause(agentRunActive = false, overlayPermissionGranted = true))
        assertTrue(shouldShowFloatingWindowOnPause(agentRunActive = true, overlayPermissionGranted = true))
    }
}
