package com.ugk.pi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemSkillsTest {
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
                "open_android_settings_page",
                "launch_android_app_intent"
            ),
            skill.methods.map { it.toolName }.toSet()
        )
        assertTrue(skill.instructions.contains("open_url"))
        assertTrue(skill.instructions.contains("share_text"))
        assertTrue(skill.instructions.contains("dial_phone"))
        assertTrue(skill.instructions.contains("show_user_confirmation_dialog"))
    }
}
