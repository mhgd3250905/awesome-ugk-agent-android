package com.ugk.pi.android.testapp

import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tool names that indicate the Agent has entered an Android screen workflow.
 * The demo uses this boundary to keep its own overlay passive and to stop
 * unrelated terminal exploration from taking over the workflow.
 */
internal object DemoScreenAutomationPolicy {
    private val screenWorkflowTools = setOf(
        "launch_android_app",
        "launch_android_app_intent",
        "screen_read_ui_tree",
        "screen_find_ui_element",
        "screen_perform_action",
        "screen_gesture",
        "screen_press_key",
        "screen_global_action"
    )

    fun isScreenWorkflowTool(name: String): Boolean =
        name.trim().lowercase() in screenWorkflowTools

    /** Safe compact diagnostics; user-entered text is intentionally omitted. */
    fun screenToolCallDetail(call: ToolCall): String = when (call.name.trim().lowercase()) {
        "screen_read_ui_tree" -> " [snapshot=read]"
        "screen_find_ui_element" -> " [snapshot=find]"
        "screen_perform_action" -> {
            val action = call.input["action"]?.jsonPrimitive?.contentOrNull ?: "missing"
            val snapshot = call.input["snapshotId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val node = call.input["nodeId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            " [action=$action snapshot=${if (snapshot == null) "missing" else "present"} " +
                "node=${if (node == null) "missing" else "present"}]"
        }
        else -> ""
    }

    /** Keeps snapshot failures distinct from the separate terminal guard. */
    fun screenToolFailureHint(
        toolName: String,
        code: String?,
        recovery: String?
    ): String? {
        val isTerminalBlock = code == "SCREEN_AUTOMATION_TERMINAL_BLOCKED"
        if (!isScreenWorkflowTool(toolName) && !isTerminalBlock) return null
        return when (code) {
            "SNAPSHOT_REQUIRED" ->
                "屏幕操作未执行：缺少最新 UI 快照。下一步必须先读取屏幕，再使用返回的 snapshotId 和 nodeId。"
            "STALE_SNAPSHOT" ->
                "屏幕操作未执行：UI 快照已过期。下一步必须重新读取屏幕，禁止复用旧 snapshotId/nodeId。"
            "NODE_NOT_FOUND" ->
                "屏幕操作未执行：目标节点已不存在。请重新读取屏幕并重新定位目标。"
            "TARGET_NOT_INTERACTABLE" ->
                "屏幕操作未执行：目标当前不可交互。请重新读取屏幕并确认目标可见、启用。"
            "ACCESSIBILITY_UNAVAILABLE" ->
                "屏幕操作未执行：无障碍服务不可用。请先检查无障碍状态，再重新读取屏幕。"
            "SCREEN_AUTOMATION_TERMINAL_BLOCKED" ->
                "已拦截终端命令：屏幕自动化期间只能使用 screen_* 工具。"
            else -> recovery?.takeIf { it.isNotBlank() }
                ?: "屏幕工具执行失败：请重新读取屏幕并根据最新快照继续。"
        }
    }
}
