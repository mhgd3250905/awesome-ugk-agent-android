package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentToolInterlockErrorCodes
import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScreenAutomationPolicyTest {
    @Test
    fun exactScreenWorkflowToolsAcceptCaseVariantsAndRejectAdjacentNames() {
        val exactWorkflowTools = listOf(
            "launch_android_app",
            "launch_android_app_intent",
            "screen_read_ui_tree",
            "screen_find_ui_element",
            "screen_perform_action",
            "screen_capture_visual",
            "screen_visual_gesture",
            "screen_gesture",
            "screen_press_key",
            "screen_global_action"
        )
        assertEquals(10, exactWorkflowTools.size)
        assertEquals(10, exactWorkflowTools.toSet().size)

        exactWorkflowTools.forEach { toolName ->
            assertTrue(DemoScreenAutomationPolicy.isScreenWorkflowTool(toolName))
            assertTrue(
                "uppercase variant should match: $toolName",
                DemoScreenAutomationPolicy.isScreenWorkflowTool(toolName.uppercase(Locale.ROOT))
            )
        }

        listOf(
            "launch_android_app_status",
            "launch_android_app_settings",
            "launch_android_app_intent_status",
            "screen_read_ui_tree_status",
            "screen_find_ui_element_settings",
            "screen_perform_action_status",
            "screen_capture_visual_settings",
            "screen_launch",
            "screen_status",
            "screen_settings",
            "terminal_bash_execute",
            "get_android_accessibility_status"
        ).forEach { adjacentName ->
            assertFalse(
                "adjacent name should not match: $adjacentName",
                DemoScreenAutomationPolicy.isScreenWorkflowTool(adjacentName)
            )
        }
    }

    @Test
    fun screenActionTraceMarksMissingSnapshotWithoutLoggingTextInput() {
        val detail = DemoScreenAutomationPolicy.screenToolCallDetail(
            ToolCall(
                id = "call-1",
                name = "screen_perform_action",
                input = buildJsonObject {
                    put("action", "click")
                    put("nodeId", "0.1.2")
                }
            )
        )

        assertTrue(detail.contains("action=click"))
        assertTrue(detail.contains("snapshot=missing"))
        assertTrue(detail.contains("node=present"))
    }

    @Test
    fun snapshotFailureIsReportedAsSnapshotRecovery() {
        assertEquals(
            "屏幕操作未执行：缺少最新 UI 快照。下一步必须先读取屏幕，再使用返回的 snapshotId 和 nodeId。",
            DemoScreenAutomationPolicy.screenToolFailureHint(
                toolName = "screen_perform_action",
                code = "SNAPSHOT_REQUIRED",
                recovery = "terminal hint must not win"
            )
        )
        assertEquals(
            "当前 Run 由 capability 'android-screen-automation' 持有；请继续当前工作流，暂不切换到被阻断的 Tool。",
            DemoScreenAutomationPolicy.screenToolFailureHint(
                toolName = "terminal_bash_execute",
                code = AgentToolInterlockErrorCodes.BLOCKED,
                recovery = null,
                blockingCapability = "android-screen-automation"
            )
        )
    }

    @Test
    fun genericInterlockFailureUsesTheBlockingCapabilityContract() {
        val hint = DemoScreenAutomationPolicy.screenToolFailureHint(
            toolName = "terminal_bash_execute",
            code = AgentToolInterlockErrorCodes.BLOCKED,
            recovery = null,
            blockingCapability = "android-screen-automation"
        )

        assertTrue(hint?.contains("android-screen-automation") == true)
        assertFalse(hint?.contains("screen_*") == true)
    }
}
