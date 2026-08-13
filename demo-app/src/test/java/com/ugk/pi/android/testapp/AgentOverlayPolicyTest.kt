package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun fullAuthorizationPrefersAnExplicitAcceptButton() {
        assertEquals(
            "allow",
            AgentAuthorizationPolicy.autoApproveButtonId(
                listOf(
                    UserConfirmationDialogButton("cancel", "取消"),
                    UserConfirmationDialogButton("allow", "允许")
                )
            )
        )
    }

    @Test
    fun fullAuthorizationFallsBackToTheFirstNonCancellationButton() {
        assertEquals(
            "open",
            AgentAuthorizationPolicy.autoApproveButtonId(
                listOf(
                    UserConfirmationDialogButton("cancel", "取消"),
                    UserConfirmationDialogButton("open", "打开")
                )
            )
        )
    }

    @Test
    fun fullAuthorizationUsesCancellationWhenAllButtonsCancel() {
        assertEquals(
            "deny",
            AgentAuthorizationPolicy.autoApproveButtonId(
                listOf(UserConfirmationDialogButton("deny", "拒绝"))
            )
        )
        assertEquals(
            "cancel",
            AgentAuthorizationPolicy.autoApproveButtonId(emptyList())
        )
    }
}
