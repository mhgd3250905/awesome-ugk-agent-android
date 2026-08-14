package com.ugk.pi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemSkillsTest {
    @Test
    fun androidAutomationControlExplainsTheCrossAppWorkflow() {
        val skill = AndroidSystemSkills.androidAutomationControl()

        assertEquals("android-app-automation", skill.id)
        assertTrue(skill.triggers.contains("无障碍"))
        assertEquals(
            setOf(
                "find_android_app",
                "launch_android_app",
                "get_android_accessibility_status",
                "open_android_accessibility_settings"
            ),
            skill.methods.map { it.toolName }.toSet()
        )
        assertTrue(skill.instructions.contains("readyForScreenAutomation=true"))
        assertTrue(skill.instructions.contains("guess a package name"))
        assertBoundConfirmationInstructions(skill.instructions)
    }

    @Test
    fun permissionSettingsControlSkillExposesPermissionAndSettingsMethods() {
        val skill = AndroidSystemSkills.permissionSettingsControl()

        assertEquals("permission-settings-control", skill.id)
        assertTrue(skill.triggers.contains("\u76f8\u673a"))
        assertTrue(skill.triggers.contains("\u84dd\u7259"))
        assertTrue(skill.triggers.contains("\u6743\u9650"))
        assertEquals(
            setOf(
                "get_android_permission_status",
                "request_android_runtime_permissions",
                "show_user_confirmation_dialog",
                "open_android_settings_page"
            ),
            skill.methods.map { it.toolName }.toSet()
        )
        assertTrue(skill.instructions.contains("show_user_confirmation_dialog"))
        assertBoundConfirmationInstructions(skill.instructions)
    }

    @Test
    fun appFacingIntentControlExplainsNativeUrlLaunch() {
        val skill = AndroidSystemSkills.appFacingIntentControl()

        assertEquals("app-facing-intent-control", skill.id)
        assertTrue(skill.triggers.contains("浏览器"))
        assertEquals(
            setOf("show_user_confirmation_dialog", "launch_android_app_intent"),
            skill.methods.map { it.toolName }.toSet()
        )
        assertTrue(skill.instructions.contains("native Intent resolver"))
        assertTrue(skill.instructions.contains("Do not use terminal_bash_execute"))
        assertTrue(skill.instructions.contains("open_url"))
        assertBoundConfirmationInstructions(skill.instructions)
    }

    private fun assertBoundConfirmationInstructions(instructions: String) {
        assertTrue(instructions.contains("target.toolName"))
        assertTrue(instructions.contains("target.input"))
        assertTrue(instructions.contains("selectedButtonId only records"))
        assertTrue(instructions.contains("does not authorize a protected Tool by itself"))
        assertFalse(instructions.contains("continue only after a confirming selectedButtonId"))
    }
}
