package com.ugk.pi.android.testapp

import com.ugk.pi.android.UserConfirmationDialogButton
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationTarget
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun confirmationTargetMapsToolNameAndInputSummary() {
        val input = buildJsonObject { put("package_name", "com.example.target") }
        val confirmation = UserConfirmationDialogRequest(
            title = "打开应用",
            message = "是否继续？",
            buttons = listOf(UserConfirmationDialogButton("confirm", "继续")),
            target = UserConfirmationTarget("launch_android_app", input)
        ).toOverlayConfirmation()

        assertNotNull(confirmation.target)
        assertEquals("launch_android_app", confirmation.target?.toolName)
        assertEquals(input.toString(), confirmation.target?.inputSummary)
    }

    @Test
    fun confirmationInputSummaryIsBounded() {
        val confirmation = UserConfirmationDialogRequest(
            title = "执行命令",
            message = "是否继续？",
            buttons = listOf(UserConfirmationDialogButton("confirm", "继续")),
            target = UserConfirmationTarget(
                "terminal_bash_execute",
                buildJsonObject { put("script", "x".repeat(MAX_CONFIRMATION_INPUT_SUMMARY_CHARS * 2)) }
            )
        ).toOverlayConfirmation()

        val summary = confirmation.target?.inputSummary
        assertNotNull(summary)
        assertEquals(MAX_CONFIRMATION_INPUT_SUMMARY_CHARS, summary?.length)
        assertTrue(summary?.endsWith('\u2026') == true)
    }

    @Test
    fun legacyConfirmationDoesNotInventTarget() {
        val confirmation = UserConfirmationDialogRequest(
            title = "旧请求",
            message = "继续？",
            buttons = listOf(UserConfirmationDialogButton("confirm", "继续"))
        ).toOverlayConfirmation()

        assertNull(confirmation.target)
    }
}
