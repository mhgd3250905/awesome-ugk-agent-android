package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentToolInterlockErrorCodes
import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

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
        "screen_capture_visual",
        "screen_visual_gesture",
        "screen_gesture",
        "screen_press_key",
        "screen_global_action"
    )

    fun isScreenWorkflowTool(name: String): Boolean =
        name.trim().lowercase(Locale.ROOT) in screenWorkflowTools

    /** Safe compact diagnostics; user-entered text is intentionally omitted. */
    fun screenToolCallDetail(call: ToolCall): String = when (call.name.trim().lowercase(Locale.ROOT)) {
        "screen_read_ui_tree" -> " [snapshot=read]"
        "screen_find_ui_element" -> " [snapshot=find]"
        "screen_capture_visual" -> " [visual=observe]"
        "screen_visual_gesture" -> {
            val action = call.input["action"]?.jsonPrimitive?.contentOrNull ?: "missing"
            val observation = call.input["observationId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            " [visualAction=$action observation=${if (observation == null) "missing" else "present"}]"
        }
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

    /** Keeps snapshot failures distinct from the generic capability interlock. */
    fun screenToolFailureHint(
        toolName: String,
        code: String?,
        recovery: String?,
        blockingCapability: String? = null
    ): String? {
        val isInterlock = code == AgentToolInterlockErrorCodes.BLOCKED
        if (!isScreenWorkflowTool(toolName) && !isInterlock) return null
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
            "VISUAL_OBSERVATION_REQUIRED",
            "VISUAL_OBSERVATION_STALE",
            "VISUAL_TARGET_INVALID",
            "VISUAL_SCREENSHOT_FAILED",
            "VISUAL_SCREENSHOT_TIMEOUT",
            "VISUAL_SCREENSHOT_UNSUPPORTED" ->
                "视觉屏幕操作未执行：请重新获取屏幕视觉观察，不要复用旧截图或猜测坐标。"
            AgentToolInterlockErrorCodes.BLOCKED ->
                "当前 Run 由 capability '${blockingCapability ?: "another capability"}' 持有；请继续当前工作流，暂不切换到被阻断的 Tool。"
            else -> recovery?.takeIf { it.isNotBlank() }
                ?: "屏幕工具执行失败：请重新读取屏幕并根据最新快照继续。"
        }
    }
}
