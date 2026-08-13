package com.ugk.pi.android.testapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayPolicyTest {

    @Test
    fun foregroundNeverShowsOverlay() {
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = true,
                activityResumed = true,
            ),
        )
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = false,
                activityResumed = true,
            ),
        )
    }

    @Test
    fun backgroundAuthorizedShowsOverlayEvenWhenIdle() {
        assertTrue(
            AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = true,
                activityResumed = false,
            ),
        )
    }

    @Test
    fun missingPermissionOrForegroundActivityHidesOverlay() {
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = false,
                activityResumed = false,
            ),
        )
        assertFalse(
            AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = true,
                activityResumed = true,
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
