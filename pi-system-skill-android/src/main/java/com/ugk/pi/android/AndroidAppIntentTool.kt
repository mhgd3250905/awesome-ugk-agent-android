package com.ugk.pi.android

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AndroidAppIntentTool(
    private val context: Context,
    override val name: String = "launch_android_app_intent",
    /**
     * Optional resolver used by hosts that need a preflight package lookup.
     * The production default is intentionally null: Android's activity
     * resolver is the source of truth when startActivity is called. A
     * PackageManager query can be hidden by Android 11+ package visibility
     * rules even though the same Intent is launchable.
     */
    private val resolveActivity: ((Intent) -> String?)? = null,
    private val startActivity: (Intent) -> Unit = { intent ->
        context.startActivity(intent)
    }
) : AgentTool {
    override val description: String =
        "Dispatches a whitelisted Android app-facing Intent such as open_url, camera capture, dialer, map, or sharing; it does not use the terminal."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "string")
                put("description", "Whitelisted app intent target.")
                putJsonArray("enum") {
                    AndroidAppIntentFactory.supportedTargets.forEach { target ->
                        add(JsonPrimitive(target))
                    }
                }
            }
            putJsonObject("parameters") {
                put("type", "object")
                put(
                    "description",
                    "Optional parameters such as phone_number, message, to, subject, body, url, query, text, package_name, or geo_uri."
                )
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
        val target = (call.input["target"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val parameters = call.input["parameters"]
            ?.jsonObject
            ?.stringParameters()
            ?: call.input
                .filterKeys { it != "target" }
                .stringParameters()
        val intent = AndroidAppIntentFactory.intentFor(target, parameters) ?: return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("error", "invalid_target_or_parameters")
                put("target", target)
            }.toString(),
            isError = true
        )

        val resolvedPackage = resolveActivity?.invoke(intent)
        if (resolveActivity != null && resolvedPackage == null) {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("error", "no_handler")
                    put("target", target)
                    put("message", "No Android activity can handle this app intent on the current device.")
                }.toString(),
                isError = true
            )
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("target", target)
                    put("launched", true)
                    put("action", intent.action.orEmpty())
                    resolvedPackage?.let { put("resolvedPackage", it) }
                }.toString()
            )
        } catch (error: ActivityNotFoundException) {
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("error", "no_handler")
                    put("target", target)
                    put("message", error.message ?: "No Android activity can handle this app intent.")
                }.toString(),
                isError = true
            )
        } catch (error: RuntimeException) {
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("error", "launch_failed")
                    put("target", target)
                    put("message", error.message ?: error::class.java.name)
                }.toString(),
                isError = true
            )
        }
    }

    private fun Map<String, JsonElement>.stringParameters(): Map<String, String> {
        return mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
    }
}

object AndroidAppIntentFactory {
    val supportedTargets: Set<String> = linkedSetOf(
        "camera_capture",
        "video_capture",
        "pick_image",
        "record_audio",
        "dial_phone",
        "send_sms",
        "send_email",
        "open_url",
        "open_map",
        "share_text",
        "web_search",
        "open_app_market"
    )

    fun intentFor(
        target: String,
        parameters: Map<String, String> = emptyMap()
    ): Intent? {
        return specFor(target, parameters)?.toIntent()
    }

    fun specFor(
        target: String,
        parameters: Map<String, String> = emptyMap()
    ): AndroidAppIntentSpec? {
        return when (target) {
            "camera_capture" -> AndroidAppIntentSpec(MediaStore.ACTION_IMAGE_CAPTURE)
            "video_capture" -> AndroidAppIntentSpec(MediaStore.ACTION_VIDEO_CAPTURE)
            "pick_image" -> AndroidAppIntentSpec(
                action = Intent.ACTION_PICK,
                dataUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString(),
                type = "image/*"
            )

            "record_audio" -> AndroidAppIntentSpec(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            "dial_phone" -> dialDataUri("tel", parameters["phone_number"])?.let { dataUri ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_DIAL,
                    dataUri = dataUri
                )
            }

            "send_sms" -> dialDataUri("smsto", parameters["phone_number"])?.let { dataUri ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_SENDTO,
                    dataUri = dataUri,
                    extras = mapOf("sms_body" to parameters["message"].orEmpty())
                )
            }

            "send_email" -> parameters["to"]?.takeIf { it.isValidEmailRecipient() }?.let { to ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_SENDTO,
                    dataUri = "mailto:$to",
                    extras = mapOf(
                        Intent.EXTRA_SUBJECT to parameters["subject"].orEmpty(),
                        Intent.EXTRA_TEXT to parameters["body"].orEmpty()
                    )
                )
            }

            "open_url" -> parameters["url"]?.let(::safeWebUrl)?.let { url ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_VIEW,
                    dataUri = url
                )
            }

            "open_map" -> {
                val geoUri = parameters["geo_uri"]?.let(::safeGeoUri)
                    ?: parameters["query"]?.takeIf { it.isEncodableQueryText() }?.let { query ->
                        "geo:0,0?q=${encodeQueryComponent(query)}"
                    }
                geoUri?.let {
                    AndroidAppIntentSpec(
                        action = Intent.ACTION_VIEW,
                        dataUri = it
                    )
                }
            }

            "share_text" -> parameters["text"]?.takeIf { it.isNotBlank() }?.let { text ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_SEND,
                    type = "text/plain",
                    extras = mapOf(Intent.EXTRA_TEXT to text)
                )
            }

            "web_search" -> parameters["query"]?.takeIf { it.isNotBlank() }?.let { query ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_WEB_SEARCH,
                    extras = mapOf(SearchManager.QUERY to query)
                )
            }

            "open_app_market" -> parameters["package_name"]
                ?.takeIf { packageNamePattern.matches(it) }
                ?.let { packageName ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_VIEW,
                    dataUri = "market://details?id=$packageName"
                )
            }

            else -> null
        }
    }

    private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private fun safeWebUrl(value: String): String? {
        val url = value.trim()
        if (!url.isSafeText()) return null
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return url
    }

    private fun safeGeoUri(value: String): String? {
        val trimmed = value.trim()
        // Pure-JVM scheme check: android.net.Uri is not available in JVM unit
        // tests, and runCatching around it would silently reject every geo URI
        // there.
        if (!trimmed.isSafeText()) return null
        if (!trimmed.lowercase().startsWith("geo:")) return null
        return trimmed
    }

    private fun String.isSafeText(): Boolean {
        return isNotBlank() && none { it.isWhitespace() || it.isISOControl() }
    }

    /**
     * Free-text query that will be percent-encoded into the geo search
     * parameter. Whitespace is expected here ("coffee shop"); only control
     * characters and blank text are rejected.
     */
    private fun String.isEncodableQueryText(): Boolean {
        return isNotBlank() && none { it.isISOControl() }
    }

    /**
     * Builds the data URI for dial/smsto targets. A missing number keeps the
     * historical empty form so the dialer still opens; a non-blank number is
     * accepted only when it consists of digits and visual separators, so
     * agent-supplied text cannot restructure the URI ('?', '&', '#', ':' and
     * control characters are rejected).
     */
    private fun dialDataUri(scheme: String, rawNumber: String?): String? {
        val number = rawNumber?.trim()
        if (number.isNullOrEmpty()) return "$scheme:"
        return if (DIAL_CHARACTER.matches(number)) "$scheme:$number" else null
    }

    /** Blocks mailto header/query injection from an agent-supplied recipient. */
    private fun String.isValidEmailRecipient(): Boolean {
        return isNotBlank() &&
            none { it.isWhitespace() || it.isISOControl() } &&
            EMAIL_RECIPIENT.matches(this)
    }

    /**
     * Dial input grammar: digits, DTMF '*', global-number '+', visual
     * separators and a literal space. Only these characters may enter a
     * tel:/smsto: URI unencoded; '#', '?', '&', ':' and control characters
     * are structural or invalid in URIs and are rejected.
     */
    private val DIAL_CHARACTER = Regex("[0-9+*()\\-., ]+")
    private val EMAIL_RECIPIENT = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+")

    /**
     * Percent-encodes [value] for a URI query component without relying on
     * android.net.Uri, so the factory stays unit-testable on the JVM.
     */
    private fun encodeQueryComponent(value: String): String {
        val output = StringBuilder(value.length)
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xff
            val isUnreserved = code in 0x41..0x5a ||
                code in 0x61..0x7a ||
                code in 0x30..0x39 ||
                code == '-'.code ||
                code == '_'.code ||
                code == '.'.code ||
                code == '~'.code
            if (isUnreserved) {
                output.append(code.toChar())
            } else {
                output.append('%').append(String.format(java.util.Locale.ROOT, "%02X", code))
            }
        }
        return output.toString()
    }
}

data class AndroidAppIntentSpec(
    val action: String,
    val dataUri: String? = null,
    val type: String? = null,
    val extras: Map<String, String> = emptyMap()
) {
    fun toIntent(): Intent {
        val intent = if (dataUri == null) {
            Intent(action)
        } else {
            Intent(action, Uri.parse(dataUri))
        }
        if (type != null) {
            intent.type = type
        }
        extras.forEach { (key, value) ->
            intent.putExtra(key, value)
        }
        return intent
    }
}
