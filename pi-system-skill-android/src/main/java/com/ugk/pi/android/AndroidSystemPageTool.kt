package com.ugk.pi.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AndroidSystemPageTool(
    private val context: Context,
    override val name: String = "open_android_settings_page"
) : AgentTool {
    override val description: String =
        "Opens a whitelisted Android system settings page for this app or device."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "string")
                put("description", "Whitelisted target page name.")
                putJsonArray("enum") {
                    AndroidSystemPageIntentFactory.supportedTargets.forEach { target ->
                        add(JsonPrimitive(target))
                    }
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("target"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val target = call.input["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val intent = AndroidSystemPageIntentFactory.intentFor(
            target = target,
            packageName = this.context.packageName
        ) ?: return ToolResult(
            toolCallId = call.id,
            name = name,
            content = "Unsupported Android system page target: $target",
            isError = true
        )

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            this.context.startActivity(intent)
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("target", target)
                    put("opened", true)
                }.toString()
            )
        } catch (error: RuntimeException) {
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = error.message ?: error::class.java.name,
                isError = true
            )
        }
    }
}

object AndroidSystemPageIntentFactory {
    val supportedTargets: Set<String> = linkedSetOf(
        "app_details",
        "app_permissions",
        "notifications",
        "battery_optimization",
        "bluetooth",
        "location",
        "wifi",
        "nfc",
        "camera",
        "overlay",
        "exact_alarm",
        "accessibility",
        "usage_access",
        "install_unknown_apps"
    )

    fun intentFor(
        target: String,
        packageName: String
    ): Intent? {
        return specFor(target, packageName)?.toIntent()
    }

    fun specFor(
        target: String,
        packageName: String
    ): AndroidSystemPageSpec? {
        return when (target) {
            "app_details", "app_permissions", "camera" -> packageSettingsSpec(packageName)
            "notifications" -> if (Build.VERSION.SDK_INT >= 26) {
                AndroidSystemPageSpec(
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    extras = mapOf(Settings.EXTRA_APP_PACKAGE to packageName)
                )
            } else {
                packageSettingsSpec(packageName)
            }

            "battery_optimization" -> AndroidSystemPageSpec(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "bluetooth" -> AndroidSystemPageSpec(Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> AndroidSystemPageSpec(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "wifi" -> AndroidSystemPageSpec(Settings.ACTION_WIFI_SETTINGS)
            "nfc" -> AndroidSystemPageSpec(Settings.ACTION_NFC_SETTINGS)
            "overlay" -> AndroidSystemPageSpec(
                action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                packageUri = packageName
            )

            "exact_alarm" -> if (Build.VERSION.SDK_INT >= 31) {
                AndroidSystemPageSpec(
                    action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    packageUri = packageName
                )
            } else {
                packageSettingsSpec(packageName)
            }

            "accessibility" -> AndroidSystemPageSpec(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "usage_access" -> AndroidSystemPageSpec(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "install_unknown_apps" -> if (Build.VERSION.SDK_INT >= 26) {
                AndroidSystemPageSpec(
                    action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    packageUri = packageName
                )
            } else {
                packageSettingsSpec(packageName)
            }

            else -> null
        }
    }

    private fun packageSettingsSpec(packageName: String): AndroidSystemPageSpec {
        return AndroidSystemPageSpec(
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            packageUri = packageName
        )
    }
}

data class AndroidSystemPageSpec(
    val action: String,
    val packageUri: String? = null,
    val extras: Map<String, String> = emptyMap()
) {
    fun toIntent(): Intent {
        val intent = if (packageUri == null) {
            Intent(action)
        } else {
            Intent(action, Uri.fromParts("package", packageUri, null))
        }
        extras.forEach { (key, value) ->
            intent.putExtra(key, value)
        }
        return intent
    }
}
