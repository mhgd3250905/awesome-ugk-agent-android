package com.ugk.pi.android.testapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayPolicyTest {

    @Test
    fun foregroundNeverShowsOverlay() {
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                agentRunActive = true,
                overlayPermissionGranted = true,
                activityResumed = true,
            ),
        )
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                agentRunActive = false,
                overlayPermissionGranted = false,
                activityResumed = true,
            ),
        )
    }

    @Test
    fun backgroundRunningAuthorizedShowsOverlay() {
        assertTrue(
            AgentOverlayPolicy.shouldShowOnPause(
                agentRunActive = true,
                overlayPermissionGranted = true,
                activityResumed = false,
            ),
        )
    }

    @Test
    fun missingPermissionOrInactiveRunHidesOverlay() {
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                agentRunActive = true,
                overlayPermissionGranted = false,
                activityResumed = false,
            ),
        )
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                agentRunActive = false,
                overlayPermissionGranted = true,
                activityResumed = false,
            ),
        )
    }

    @Test
    fun permissionOfferOnlyForUnauthorizedActiveRun() {
        assertTrue(
            AgentOverlayPolicy.shouldOfferPermission(
                permissionGranted = false,
                hasActiveRun = true,
            ),
        )
        assertFalse(
            AgentOverlayPolicy.shouldOfferPermission(
                permissionGranted = true,
                hasActiveRun = true,
            ),
        )
        assertFalse(
            AgentOverlayPolicy.shouldOfferPermission(
                permissionGranted = false,
                hasActiveRun = false,
            ),
        )
    }
}
