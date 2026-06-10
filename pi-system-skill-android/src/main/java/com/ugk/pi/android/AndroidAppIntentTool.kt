package com.ugk.pi.android

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.json.JsonObject
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
    override val name: String = "launch_android_app_intent"
) : AgentTool {
    override val description: String =
        "Launches a whitelisted Android app-facing intent such as camera capture."

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
        val target = call.input["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val parameters = call.input["parameters"]
            ?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?: call.input
                .filterKeys { it != "target" }
                .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
        val intent = AndroidAppIntentFactory.intentFor(target, parameters) ?: return ToolResult(
            toolCallId = call.id,
            name = name,
            content = "Unsupported Android app intent target or missing parameters: $target",
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
                    put("launched", true)
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
            "dial_phone" -> AndroidAppIntentSpec(
                action = Intent.ACTION_DIAL,
                dataUri = "tel:${parameters["phone_number"].orEmpty()}"
            )

            "send_sms" -> AndroidAppIntentSpec(
                action = Intent.ACTION_SENDTO,
                dataUri = "smsto:${parameters["phone_number"].orEmpty()}",
                extras = mapOf("sms_body" to parameters["message"].orEmpty())
            )

            "send_email" -> parameters["to"]?.takeIf { it.isNotBlank() }?.let { to ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_SENDTO,
                    dataUri = "mailto:$to",
                    extras = mapOf(
                        Intent.EXTRA_SUBJECT to parameters["subject"].orEmpty(),
                        Intent.EXTRA_TEXT to parameters["body"].orEmpty()
                    )
                )
            }

            "open_url" -> parameters["url"]?.takeIf { it.isNotBlank() }?.let { url ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_VIEW,
                    dataUri = url
                )
            }

            "open_map" -> {
                val geoUri = parameters["geo_uri"]
                    ?: parameters["query"]?.takeIf { it.isNotBlank() }?.let { query ->
                        "geo:0,0?q=${Uri.encode(query)}"
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

            "open_app_market" -> parameters["package_name"]?.takeIf { it.isNotBlank() }?.let { packageName ->
                AndroidAppIntentSpec(
                    action = Intent.ACTION_VIEW,
                    dataUri = "market://details?id=$packageName"
                )
            }

            else -> null
        }
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
