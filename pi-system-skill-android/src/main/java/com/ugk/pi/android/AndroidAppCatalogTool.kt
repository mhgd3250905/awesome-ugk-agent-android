package com.ugk.pi.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Finds user-launchable Android applications by label or package name.
 *
 * This is deliberately a PackageManager capability, not a screen-reading
 * shortcut. It lets the Agent resolve a human app name before it asks Android
 * to launch the app or the host's AccessibilityService to inspect its UI.
 */
class AndroidAppCatalogTool(
    private val context: Context,
    override val name: String = "find_android_app"
) : AgentTool {
    override val description: String =
        "Finds installed user-launchable Android apps by visible label or package name and returns candidate package names."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Human-visible app name or an exact/partial package name, such as Chrome or com.android.chrome.")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum candidates to return. Defaults to 8 and is capped at 20.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("query"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val query = call.input["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isBlank()) {
            return error(call, "INVALID_QUERY", "query must be a non-empty app label or package name.")
        }

        val maxResults = call.input["max_results"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: DEFAULT_MAX_RESULTS
        if (maxResults !in 1..MAX_RESULTS) {
            return error(call, "INVALID_MAX_RESULTS", "max_results must be between 1 and $MAX_RESULTS.")
        }

        val candidates = try {
            launchableApps()
        } catch (error: RuntimeException) {
            return error(call, "APP_QUERY_FAILED", error.message ?: error::class.java.name)
        }

        val matches = candidates
            .mapNotNull { candidate ->
                matchScore(candidate, query)?.let { score -> candidate to score }
            }
            .sortedWith(
                compareByDescending<Pair<AppCandidate, Int>> { it.second }
                    .thenBy { it.first.label.lowercase(Locale.ROOT) }
                    .thenBy { it.first.packageName }
            )
            .take(maxResults)
            .map { it.first }

        val result = buildJsonObject {
            put("query", query)
            put("count", matches.size)
            put("ambiguous", matches.size > 1)
            putJsonArray("apps") {
                matches.forEach { candidate ->
                    add(
                        buildJsonObject {
                            put("label", candidate.label)
                            put("packageName", candidate.packageName)
                            put("launchActivity", candidate.launchActivity)
                            put("enabled", candidate.enabled)
                        }
                    )
                }
            }
            if (matches.isEmpty()) {
                put(
                    "message",
                    "No launchable app matched the query. Ask the user for the exact package name or confirm that the app is installed."
                )
            } else if (matches.size > 1) {
                put("message", "Multiple apps matched. Do not choose silently; ask the user to disambiguate unless one candidate is an exact match.")
            }
        }

        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = result.toString(),
            metadata = buildJsonObject {
                put("source", "android_package_manager")
            }
        )
    }

    private fun launchableApps(): List<AppCandidate> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = context.packageManager.queryIntentActivities(
            launcherIntent,
            // Launcher filters normally declare MAIN + LAUNCHER rather than
            // CATEGORY_DEFAULT. MATCH_DEFAULT_ONLY would hide those apps.
            PackageManager.MATCH_ALL
        )

        return activities
            .mapNotNull(::candidateFor)
            .groupBy { it.packageName }
            .map { (_, packageCandidates) ->
                packageCandidates.firstOrNull { it.enabled } ?: packageCandidates.first()
            }
    }

    private fun candidateFor(resolveInfo: ResolveInfo): AppCandidate? {
        val activityInfo = resolveInfo.activityInfo ?: return null
        val applicationInfo = activityInfo.applicationInfo ?: return null
        return AppCandidate(
            label = context.packageManager.getApplicationLabel(applicationInfo).toString(),
            packageName = applicationInfo.packageName,
            launchActivity = activityInfo.name,
            enabled = applicationInfo.enabled && activityInfo.enabled
        )
    }

    private fun matchScore(candidate: AppCandidate, rawQuery: String): Int? {
        val query = rawQuery.lowercase(Locale.ROOT)
        val label = candidate.label.lowercase(Locale.ROOT)
        val packageName = candidate.packageName.lowercase(Locale.ROOT)
        return when {
            label == query || packageName == query -> 3
            label.startsWith(query) || packageName.startsWith(query) -> 2
            label.contains(query) || packageName.contains(query) -> 1
            else -> null
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

    private data class AppCandidate(
        val label: String,
        val packageName: String,
        val launchActivity: String,
        val enabled: Boolean
    )

    private companion object {
        const val DEFAULT_MAX_RESULTS = 8
        const val MAX_RESULTS = 20
    }
}
