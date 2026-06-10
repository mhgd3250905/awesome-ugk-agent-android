package com.ugk.pi.android.testapp

import android.util.Log
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ScreenLaunchAppTool(
    override val name: String = "screen_launch_app"
) : AgentTool {

    private val TAG = "ScreenLaunchApp"

    override val description: String =
        "Launches an app by package name using am start. Use this to open apps directly instead of searching for icons on screen."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package") {
                put("type", "string")
                put("description", "The package name of the app to launch, e.g. com.tencent.mm for WeChat, com.android.settings for Settings.")
            }
        }
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("package")) })
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Accessibility service is not running.",
                isError = true
            )

        val packageName = call.input["package"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Missing required parameter: package",
                isError = true
            )

        Log.d(TAG, "execute: launching $packageName")

        return try {
            val pm = service.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "App not found: $packageName. Try reading the screen first to find the correct app.",
                    isError = true
                )
            } else {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(launchIntent)
                Thread.sleep(1500)
                Log.d(TAG, "execute: launched $packageName ok")
                ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "Launched $packageName successfully."
                )
            }
        } catch (e: Exception) {
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Failed to launch $packageName: ${e.message}",
                isError = true
            )
        }
    }
}
