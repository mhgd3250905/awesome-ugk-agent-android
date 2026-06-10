package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.os.Build
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ScreenGlobalActionTool(
    override val name: String = "screen_global_action"
) : AgentTool {

    override val description: String =
        "Performs a global system action. Does not require a nodeId. Use for navigation (back, home, recents), notifications, quick settings, etc."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Global action to perform.")
                putJsonArray("enum") {
                    add(JsonPrimitive("back"))
                    add(JsonPrimitive("home"))
                    add(JsonPrimitive("recents"))
                    add(JsonPrimitive("notifications"))
                    add(JsonPrimitive("quick_settings"))
                    add(JsonPrimitive("power_dialog"))
                    add(JsonPrimitive("lock_screen"))
                    add(JsonPrimitive("take_screenshot"))
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("action"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val action = call.input["action"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val service = AgentAccessibilityService.instance
            ?: return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Accessibility service is not running.",
                isError = true
            )

        val globalAction = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "take_screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Unknown global action: $action",
                isError = true
            )
        }

        val result = service.performGlobalAction(globalAction)

        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("action", action)
                put("success", result)
            }.toString()
        )
    }
}
