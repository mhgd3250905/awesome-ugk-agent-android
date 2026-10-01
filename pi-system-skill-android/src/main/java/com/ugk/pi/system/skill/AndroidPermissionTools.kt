package com.ugk.pi.system.skill
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
        val permissions = call.permissionsOrDefault() ?: return call.unusablePermissions(name)
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
        val permissions = call.permissionsOrDefault() ?: return call.unusablePermissions(name)
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

internal fun ToolCall.permissionsOrDefault(): List<String>? =
    when (val declared = input.declaredStringList("permissions")) {
        is DeclaredStringList.Values ->
            declared.values.takeIf { it.isNotEmpty() } ?: AndroidPermissionCatalog.defaultRuntimePermissions()
        DeclaredStringList.Undeclared -> AndroidPermissionCatalog.defaultRuntimePermissions()
        DeclaredStringList.Unusable -> null
    }

/**
 * The answer when `permissions` was declared with something that is not a list.
 *
 * `?.jsonArray` used to throw here, which the runtime reports as a tool error naming
 * a serialization class - the one message the caller cannot act on. A declared but
 * unusable list is refused on purpose: the alternative is silently scanning a
 * default permission set nobody asked for.
 */
internal fun ToolCall.unusablePermissions(toolName: String): ToolResult = ToolResult(
    toolCallId = id,
    name = toolName,
    content = buildJsonObject {
        put("error", "invalid_permissions")
        put("message", "permissions must be a list of permission names; leave it out for the default set.")
    }.toString(),
    isError = true
)

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
