package com.ugk.pi.android

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AppEnvironmentInfoTool(
    private val context: Context
) : AgentTool {
    override val name: String = "get_app_environment_info"
    override val description: String =
        "Returns host app package, version, notification, battery optimization, and runtime permission state."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("permissions") {
                put("type", "array")
                put("description", "Optional Android permission names to inspect.")
                putJsonObject("items") {
                    put("type", "string")
                }
            }
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val requestedPermissions = call.input["permissions"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: AndroidPermissionCatalog.defaultRuntimePermissions()

        val result = buildJsonObject {
            put("packageName", this@AppEnvironmentInfoTool.context.packageName)
            put("appLabel", appLabel())
            putJsonObject("version") {
                val version = versionInfo()
                put("name", version.name)
                put("code", version.code)
            }
            putJsonObject("device") {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sdkInt", Build.VERSION.SDK_INT)
            }
            putJsonObject("notifications") {
                put("enabled", notificationsEnabled())
            }
            putJsonObject("batteryOptimization") {
                put("ignored", batteryOptimizationIgnored())
            }
            putJsonArray("permissions") {
                requestedPermissions.forEach { permissionName ->
                    add(
                        buildJsonObject {
                            put("name", permissionName)
                            put("granted", permissionGranted(permissionName))
                        }
                    )
                }
            }
        }

        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = result.toString(),
            metadata = buildJsonObject {
                put("source", JsonPrimitive("demo-app"))
            }
        )
    }

    private fun appLabel(): String {
        val applicationInfo = context.applicationInfo
        return context.packageManager.getApplicationLabel(applicationInfo).toString()
    }

    private fun versionInfo(): VersionInfo {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return VersionInfo(
            name = packageInfo.versionName ?: "",
            code = versionCode
        )
    }

    private fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 24) return true

        val manager = context.getSystemService(NotificationManager::class.java)
        return manager?.areNotificationsEnabled() ?: false
    }

    private fun batteryOptimizationIgnored(): Boolean {
        val manager = context.getSystemService(PowerManager::class.java)
        return manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    private fun permissionGranted(permissionName: String): Boolean {
        return try {
            context.checkSelfPermission(permissionName) == PackageManager.PERMISSION_GRANTED
        } catch (_: RuntimeException) {
            false
        }
    }

    private data class VersionInfo(
        val name: String,
        val code: Long
    )
}
