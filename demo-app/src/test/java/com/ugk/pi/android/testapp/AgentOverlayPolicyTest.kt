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

    @Test
    fun confirmationVisualPolicyInspectsDangerousTargetInput() {
        val request = UserConfirmationDialogRequest(
            title = "执行终端脚本",
            message = "是否继续？",
            buttons = listOf(UserConfirmationDialogButton("confirm", "执行")),
            target = UserConfirmationTarget(
                "terminal_bash_execute",
                buildJsonObject { put("script", "rm -rf /tmp/demo") }
            )
        )

        assertEquals(
            ConfirmationVisualRole.DANGER,
            request.confirmationVisualRole(request.buttons.single())
        )
    }

    @Test
    fun overlayConversionPreservesPrecomputedVisualRoles() {
        val request = UserConfirmationDialogRequest(
            title = "清空剪贴板",
            message = "将清除当前剪贴板内容。",
            buttons = listOf(
                UserConfirmationDialogButton("cancel", "取消"),
                UserConfirmationDialogButton("confirm", "清空")
            ),
            target = UserConfirmationTarget(
                "clipboard_clear",
                buildJsonObject { put("confirmed", true) }
            )
        )

        val confirmation = request.toOverlayConfirmation()

        assertEquals(ConfirmationVisualRole.CANCEL, confirmation.buttons[0].visualRole)
        assertEquals(ConfirmationVisualRole.DANGER, confirmation.buttons[1].visualRole)
        assertEquals("clipboard_clear", confirmation.target?.toolName)
    }

    @Test
    fun onlyClearOrdinaryConfirmationGetsPrimaryOtherwiseWarning() {
        val ordinary = UserConfirmationDialogRequest(
            title = "打开应用",
            message = "允许打开目标应用？",
            buttons = listOf(UserConfirmationDialogButton("confirm", "打开")),
            target = UserConfirmationTarget(
                "launch_android_app",
                buildJsonObject { put("package_name", "com.example.target") }
            )
        )
        val unknown = UserConfirmationDialogRequest(
            title = "需要确认",
            message = "请确认这个操作。",
            buttons = listOf(UserConfirmationDialogButton("confirm", "继续"))
        )

        assertEquals(
            ConfirmationVisualRole.PRIMARY,
            ordinary.confirmationVisualRole(ordinary.buttons.single())
        )
        assertEquals(
            ConfirmationVisualRole.WARNING,
            unknown.confirmationVisualRole(unknown.buttons.single())
        )
    }
}
