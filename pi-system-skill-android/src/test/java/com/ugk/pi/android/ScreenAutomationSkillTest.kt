package com.ugk.pi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAutomationSkillTest {
    @Test
    fun exposesTheCompleteAccessibilityScreenWorkflow() {
        val skill = ScreenAutomationSkills.accessibilityScreenControl()

        assertEquals("android-accessibility-screen-automation", skill.id)
        assertTrue(skill.triggers.contains("读取界面"))
        assertTrue(skill.instructions.contains("snapshotId"))
        assertTrue(skill.instructions.contains("STALE_SNAPSHOT"))
        assertTrue(skill.instructions.contains("screenWidth/screenHeight"))
        assertTrue(skill.instructions.contains("show_user_confirmation_dialog"))
        assertEquals(
            setOf(
                "get_android_accessibility_status",
                "screen_read_ui_tree",
                "screen_find_ui_element",
                "screen_perform_action",
                "screen_gesture",
                "screen_press_key",
                "screen_global_action",
                "show_user_confirmation_dialog"
            ),
            skill.methods.map { it.toolName }.toSet()
        )
    }

    @Test
    fun fullAuthorizationOmitsConfirmationMethodButKeepsTheSafetyContract() {
        val skill = ScreenAutomationSkills.accessibilityScreenControl(
            requireUserConfirmation = false
        )

        assertFalse(skill.methods.any { it.toolName == "show_user_confirmation_dialog" })
        assertTrue(skill.instructions.contains("Do not call show_user_confirmation_dialog"))
        assertTrue(skill.instructions.contains("target validation"))
    }

    @Test
    fun visualFallbackAddsScreenshotAndBoundedGestureWorkflow() {
        val skill = ScreenAutomationSkills.accessibilityScreenControl(
            requireUserConfirmation = true,
            includeVisualFallback = true
        )

        assertTrue(skill.instructions.contains("screen_capture_visual"))
        assertTrue(skill.instructions.contains("observationId"))
        assertTrue(skill.instructions.contains("normalized 0..1"))
        assertEquals(
            setOf(
                "get_android_accessibility_status",
                "screen_read_ui_tree",
                "screen_find_ui_element",
                "screen_perform_action",
                "screen_gesture",
                "screen_press_key",
                "screen_global_action",
                "screen_capture_visual",
                "screen_visual_gesture",
                "show_user_confirmation_dialog"
            ),
            skill.methods.map { it.toolName }.toSet()
        )
    }

    @Test
    fun parsesOnlyStrictNodePaths() {
        assertEquals(ScreenNodePath(0, emptyList()), parseScreenNodePath("0"))
        assertEquals(ScreenNodePath(2, listOf(0, 5, 11)), parseScreenNodePath("2.0.5.11"))
        assertEquals(ScreenNodePath(0, listOf(1)), parseScreenNodePath(" 0.1 "))
        assertNull(parseScreenNodePath(""))
        assertNull(parseScreenNodePath("0..1"))
        assertNull(parseScreenNodePath("0.1.bad"))
        assertNull(parseScreenNodePath("-1.2"))
    }

    @Test
    fun derivesGestureEndpointsFromReportedBounds() {
        assertEquals(
            ScreenGestureCoordinates(500, 1200, 500, 400, 300L),
            resolveScreenGestureCoordinates("swipe_up", 500, 1200, 1000, 2400)
        )
        assertEquals(
            ScreenGestureCoordinates(360, 500, 120, 500, 300L),
            resolveScreenGestureCoordinates("swipe_left", 360, 500, 720, 1200)
        )
        assertNull(resolveScreenGestureCoordinates("swipe_up", 10, 0, 400, 800))
        assertNull(resolveScreenGestureCoordinates("tap", 400, 10, 400, 800))
    }

    @Test
    fun mapsNormalizedVisualTargetToCurrentScreenCenter() {
        assertEquals(
            500 to 1_200,
            resolveScreenVisualTargetCenter(
                ScreenVisualTarget(left = 0.4, top = 0.4, right = 0.6, bottom = 0.6),
                screenWidth = 1_000,
                screenHeight = 2_400
            )
        )
        assertNull(
            resolveScreenVisualTargetCenter(
                ScreenVisualTarget(left = 0.7, top = 0.2, right = 0.6, bottom = 0.3),
                screenWidth = 1_000,
                screenHeight = 2_400
            )
        )
        assertNull(
            resolveScreenVisualTargetCenter(
                ScreenVisualTarget(left = 0.0, top = 0.0, right = 1.1, bottom = 0.5),
                screenWidth = 1_000,
                screenHeight = 2_400
            )
        )
    }

    @Test
    fun limitsAreExplicitAndFailClosed() {
        assertEquals(200, ScreenAutomationLimits.DEFAULT_MAX_NODES)
        assertEquals(500, ScreenAutomationLimits.MAX_MAX_NODES)
        assertFalse(ScreenAutomationErrorCodes.STALE_SNAPSHOT.isBlank())
        assertNotNull(ScreenGlobalActionNames.supported)
        assertTrue(ScreenActionNames.SET_TEXT in ScreenActionNames.supported)
    }
}
