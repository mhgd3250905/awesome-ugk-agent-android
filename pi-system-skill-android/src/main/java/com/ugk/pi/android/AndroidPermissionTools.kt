package com.ugk.pi.android

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AndroidPermissionStatusTool(
    private val activity: Activity
) : AgentTool {
    override val name: String = "get_android_permission_status"
    override val description: String =
        "Returns grant and rationale state for Android runtime permissions."
    override val inputSchema: JsonObject = permissionsInputSchema()

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val permissions = call.permissionsOrDefault()
        val result = buildJsonObject {
            putJsonArray("permissions") {
                permissions.forEach { permissionName ->
                    add(
                        buildJsonObject {
                            put("name", permissionName)
                            put("granted", permissionGranted(permissionName))
                            put("shouldShowRationale", activity.shouldShowRequestPermissionRationale(permissionName))
                        }
                    )
                }
            }
        }
        return ToolResult(call.id, name, result.toString())
    }

    private fun permissionGranted(permissionName: String): Boolean {
        return activity.checkSelfPermission(permissionName) == PackageManager.PERMISSION_GRANTED
    }
}

interface AndroidRuntimePermissionRequester {
    suspend fun request(
        activity: Activity,
        permissions: List<String>
    ): Map<String, Boolean>
}

class AndroidRuntimePermissionRequestTool(
    private val activity: Activity,
    private val requester: AndroidRuntimePermissionRequester
) : AgentTool {
    override val name: String = "request_android_runtime_permissions"
    override val description: String =
        "Requests Android runtime permissions through the current Activity."
    override val inputSchema: JsonObject = permissionsInputSchema()

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val permissions = call.permissionsOrDefault()
        val requestablePermissions = permissions.filter { it.isRuntimePromptSupported() }
        if (requestablePermissions.isEmpty()) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("requested", false)
                    put("reason", "No requestable runtime permissions for this Android version.")
                }.toString(),
                isError = true
            )
        }

        val results = requester.request(activity, requestablePermissions)
        val result = buildJsonObject {
            put("requested", true)
            putJsonArray("permissions") {
                requestablePermissions.forEach { permissionName ->
                    add(
                        buildJsonObject {
                            put("name", permissionName)
                            put("granted", results[permissionName] == true)
                        }
                    )
                }
            }
        }
        return ToolResult(call.id, name, result.toString())
    }

    private fun String.isRuntimePromptSupported(): Boolean {
        if (this == android.Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) {
            return false
        }
        if (
            (this == android.Manifest.permission.BLUETOOTH_SCAN ||
                this == android.Manifest.permission.BLUETOOTH_CONNECT) &&
            Build.VERSION.SDK_INT < 31
        ) {
            return false
        }
        return true
    }
}

private fun ToolCall.permissionsOrDefault(): List<String> {
    return input["permissions"]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.takeIf { it.isNotEmpty() }
        ?: AndroidPermissionCatalog.defaultRuntimePermissions()
}

private fun permissionsInputSchema(): JsonObject {
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("permissions") {
                put("type", "array")
                put("description", "Optional Android permission names.")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
        }
    }
}
