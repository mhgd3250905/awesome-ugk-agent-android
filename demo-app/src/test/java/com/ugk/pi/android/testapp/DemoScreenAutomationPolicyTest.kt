package com.ugk.pi.android.testapp

import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScreenAutomationPolicyTest {
    @Test
    fun screenWorkflowToolsActivatePassiveOverlayMode() {
        assertTrue(DemoScreenAutomationPolicy.isScreenWorkflowTool("launch_android_app"))
        assertTrue(DemoScreenAutomationPolicy.isScreenWorkflowTool("screen_perform_action"))
        assertTrue(DemoScreenAutomationPolicy.isScreenWorkflowTool("SCREEN_READ_UI_TREE"))
        assertFalse(DemoScreenAutomationPolicy.isScreenWorkflowTool("terminal_bash_execute"))
        assertFalse(DemoScreenAutomationPolicy.isScreenWorkflowTool("get_android_accessibility_status"))
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
            "已拦截终端命令：屏幕自动化期间只能使用 screen_* 工具。",
            DemoScreenAutomationPolicy.screenToolFailureHint(
                toolName = "terminal_bash_execute",
                code = "SCREEN_AUTOMATION_TERMINAL_BLOCKED",
                recovery = null
            )
        )
    }
}
