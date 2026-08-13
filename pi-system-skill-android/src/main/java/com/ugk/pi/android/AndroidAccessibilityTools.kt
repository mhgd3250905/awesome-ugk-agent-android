package com.ugk.pi.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Snapshot supplied by the host's AccessibilityService implementation. */
data class AndroidAccessibilityServiceState(
    val connected: Boolean,
    val activePackageName: String? = null
)

fun interface AndroidAccessibilityServiceStateProvider {
    fun snapshot(): AndroidAccessibilityServiceState
}

/**
 * Reports both the user-enabled state and the host service connection state.
 * Android does not let an SDK infer that its own service is usable from a
 * generic AccessibilityManager flag, so the host supplies the connection
 * snapshot.
 */
class AndroidAccessibilityStatusTool(
    private val context: Context,
    private val serviceComponent: ComponentName,
    private val stateProvider: AndroidAccessibilityServiceStateProvider,
    override val name: String = "get_android_accessibility_status"
) : AgentTool {
    override val description: String =
        "Reports whether the host's AccessibilityService is enabled by the user, connected, and ready to read or operate other app screens."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val state = stateProvider.snapshot()
        val enabled = isServiceEnabled()
        val ready = enabled && state.connected
        val result = buildJsonObject {
            put("serviceComponent", serviceComponent.flattenToString())
            put("enabledByUser", enabled)
            put("connected", state.connected)
            put("readyForScreenAutomation", ready)
            put("canReadWindowContent", ready)
            put("canPerformActions", ready)
            state.activePackageName?.let { put("activePackageName", it) }
            put(
                "nextAction",
                when {
                    ready -> "Call screen_read_ui_tree before interacting with another app."
                    !enabled -> "Call open_android_accessibility_settings and wait for the user to enable the host service."
                    else -> "The service is enabled but not connected yet; wait for onServiceConnected and check again."
                }
            )
        }
        return ToolResult(call.id, name, result.toString())
    }

    private fun isServiceEnabled(): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return raw.split(':').any { encoded ->
            encoded.equals(serviceComponent.flattenToString(), ignoreCase = true) ||
                encoded.equals(serviceComponent.flattenToShortString(), ignoreCase = true) ||
                ComponentName.unflattenFromString(encoded) == serviceComponent
        }
    }
}

/** Opens the system page where the user can enable the host AccessibilityService. */
class AndroidAccessibilitySettingsTool(
    private val context: Context,
    override val name: String = "open_android_accessibility_settings",
    private val startActivity: (Intent) -> Unit = { intent ->
        context.startActivity(intent)
    }
) : AgentTool {
    override val description: String =
        "Opens Android Accessibility settings so the user can manually enable the host service. It cannot grant the permission silently."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            ToolResult(
                call.id,
                name,
                buildJsonObject {
                    put("opened", true)
                    put("userMustEnableManually", true)
                    put("action", Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }.toString()
            )
        } catch (error: RuntimeException) {
            ToolResult(
                call.id,
                name,
                buildJsonObject {
                    put("error", "SETTINGS_LAUNCH_FAILED")
                    put("message", error.message ?: error::class.java.name)
                }.toString(),
                isError = true
            )
        }
    }
}
