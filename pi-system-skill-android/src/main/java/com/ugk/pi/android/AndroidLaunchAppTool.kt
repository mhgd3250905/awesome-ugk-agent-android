package com.ugk.pi.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Launches an installed app through its Android launcher activity. */
class AndroidLaunchAppTool(
    private val context: Context,
    override val name: String = "launch_android_app",
    private val launchIntentForPackage: (String) -> Intent? = { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
    },
    private val startActivity: (Intent) -> Unit = { intent ->
        context.startActivity(intent)
    }
) : AgentTool {
    override val description: String =
        "Launches an installed Android app by package name using its launcher Activity. It does not require AccessibilityService."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package_name") {
                put("type", "string")
                put("description", "Exact Android package name returned by find_android_app.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("package_name"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val packageName = call.input["package_name"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) {
            return error(call, "INVALID_PACKAGE_NAME", "package_name must be an exact Android package name.")
        }

        val launchIntent = launchIntentForPackage(packageName) ?: return error(
            call,
            "APP_NOT_FOUND",
            "No launchable Activity was found for package '$packageName'. Use find_android_app or ask for the exact package name."
        )

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("packageName", packageName)
                    put("launchActivity", launchIntent.component?.className ?: "")
                    put("launched", true)
                    put("requiresAccessibilityForNextStep", true)
                }.toString()
            )
        } catch (error: ActivityNotFoundException) {
            error(call, "NO_LAUNCH_HANDLER", error.message ?: "Android could not launch this package.")
        } catch (error: RuntimeException) {
            error(call, "LAUNCH_FAILED", error.message ?: error::class.java.name)
        }
    }

    private fun error(call: ToolCall, code: String, message: String): ToolResult {
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("error", code)
                put("message", message)
            }.toString(),
            isError = true,
            metadata = buildJsonObject {
                put("code", code)
            }
        )
    }

    private companion object {
        val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
