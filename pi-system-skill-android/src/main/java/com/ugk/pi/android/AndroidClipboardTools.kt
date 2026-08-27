package com.ugk.pi.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 与宿主无关的文本剪贴板契约。 */
interface ClipboardBackend {
    fun readText(maxChars: Int = ClipboardLimits.DEFAULT_MAX_READ_CHARS): ClipboardReadResult

    fun writeText(
        text: String,
        label: String,
        sensitive: Boolean
    ): ClipboardOperationResult

    fun clear(): ClipboardOperationResult
}

data class ClipboardReadResult(
    val code: String,
    val text: String? = null,
    val originalLength: Int? = null,
    val truncated: Boolean = false,
    val itemCount: Int = 0,
    val mimeTypes: List<String> = emptyList(),
    val message: String? = null
) {
    val success: Boolean
        get() = code == ClipboardErrorCodes.OK && text != null
}

data class ClipboardOperationResult(
    val code: String,
    val message: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    val success: Boolean
        get() = code == ClipboardErrorCodes.OK
}

object ClipboardLimits {
    const val MIN_SUPPORTED_API = 29
    const val DEFAULT_MAX_READ_CHARS = 8_000
    const val MAX_READ_CHARS = 20_000
    const val MAX_WRITE_CHARS = 20_000
    const val MAX_LABEL_CHARS = 100
    const val DEFAULT_LABEL = "UGK Agent"
}

object ClipboardErrorCodes {
    const val OK = "OK"
    const val UNSUPPORTED = "CLIPBOARD_UNSUPPORTED"
    const val UNAVAILABLE = "CLIPBOARD_UNAVAILABLE"
    const val READ_UNAVAILABLE = "CLIPBOARD_READ_UNAVAILABLE"
    const val NO_TEXT = "CLIPBOARD_NO_TEXT"
    const val INVALID_INPUT = "CLIPBOARD_INVALID_INPUT"
    const val TEXT_TOO_LARGE = "CLIPBOARD_TEXT_TOO_LARGE"
    const val LABEL_TOO_LARGE = "CLIPBOARD_LABEL_TOO_LARGE"
    const val WRITE_FAILED = "CLIPBOARD_WRITE_FAILED"
    const val CLEAR_FAILED = "CLIPBOARD_CLEAR_FAILED"
}

/** Android 10+ 文本剪贴板实现。 */
class AndroidClipboardBackend(
    context: Context
) : ClipboardBackend {
    private val appContext = context.applicationContext ?: context
    private val clipboardManager: ClipboardManager? by lazy {
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    override fun readText(maxChars: Int): ClipboardReadResult {
        if (Build.VERSION.SDK_INT < ClipboardLimits.MIN_SUPPORTED_API) {
            return ClipboardReadResult(
                code = ClipboardErrorCodes.UNSUPPORTED,
                message = "Text clipboard automation requires Android 10 (API 29) or newer."
            )
        }
        val manager = clipboardManager
            ?: return ClipboardReadResult(
                code = ClipboardErrorCodes.UNAVAILABLE,
                message = "Android ClipboardManager is unavailable."
            )
        val safeMaxChars = maxChars.coerceIn(1, ClipboardLimits.MAX_READ_CHARS)
        return try {
            val clip = manager.primaryClip
                ?: return ClipboardReadResult(
                    code = ClipboardErrorCodes.READ_UNAVAILABLE,
                    message = "The clipboard is empty or Android did not allow this background app to read it. The host app must have input focus or be the default IME."
                )
            val mimeTypes = clip.description.mimeTypes()
            val itemCount = clip.itemCount
            val text = clip.getItemAt(0).text?.toString()
                ?: return ClipboardReadResult(
                    code = ClipboardErrorCodes.NO_TEXT,
                    itemCount = itemCount,
                    mimeTypes = mimeTypes,
                    message = "The primary clipboard item does not contain plain text; URI and image clipboard items are not exposed by this Tool."
                )
            ClipboardReadResult(
                code = ClipboardErrorCodes.OK,
                text = text.take(safeMaxChars),
                originalLength = text.length,
                truncated = text.length > safeMaxChars,
                itemCount = itemCount,
                mimeTypes = mimeTypes
            )
        } catch (error: SecurityException) {
            ClipboardReadResult(
                code = ClipboardErrorCodes.READ_UNAVAILABLE,
                message = error.message ?: "Android denied clipboard read access in the current focus state."
            )
        } catch (error: RuntimeException) {
            ClipboardReadResult(
                code = ClipboardErrorCodes.UNAVAILABLE,
                message = error.message ?: "Unable to read the Android clipboard."
            )
        }
    }

    override fun writeText(
        text: String,
        label: String,
        sensitive: Boolean
    ): ClipboardOperationResult {
        if (Build.VERSION.SDK_INT < ClipboardLimits.MIN_SUPPORTED_API) {
            return ClipboardOperationResult(
                code = ClipboardErrorCodes.UNSUPPORTED,
                message = "Text clipboard automation requires Android 10 (API 29) or newer."
            )
        }
        if (text.length > ClipboardLimits.MAX_WRITE_CHARS) {
            return ClipboardOperationResult(
                code = ClipboardErrorCodes.TEXT_TOO_LARGE,
                message = "Clipboard text exceeds the ${ClipboardLimits.MAX_WRITE_CHARS}-character safety limit."
            )
        }
        if (label.length > ClipboardLimits.MAX_LABEL_CHARS) {
            return ClipboardOperationResult(
                code = ClipboardErrorCodes.LABEL_TOO_LARGE,
                message = "Clipboard label exceeds the ${ClipboardLimits.MAX_LABEL_CHARS}-character safety limit."
            )
        }
        val manager = clipboardManager
            ?: return ClipboardOperationResult(
                code = ClipboardErrorCodes.UNAVAILABLE,
                message = "Android ClipboardManager is unavailable."
            )
        return try {
            val clip = ClipData.newPlainText(label, text)
            if (sensitive) clip.markSensitive()
            manager.setPrimaryClip(clip)
            ClipboardOperationResult(
                code = ClipboardErrorCodes.OK,
                metadata = mapOf(
                    "textLength" to text.length.toString(),
                    "sensitive" to sensitive.toString(),
                    "verified" to "false"
                )
            )
        } catch (error: RuntimeException) {
            ClipboardOperationResult(
                code = ClipboardErrorCodes.WRITE_FAILED,
                message = error.message ?: "Android rejected the clipboard write."
            )
        }
    }

    override fun clear(): ClipboardOperationResult {
        if (Build.VERSION.SDK_INT < ClipboardLimits.MIN_SUPPORTED_API) {
            return ClipboardOperationResult(
                code = ClipboardErrorCodes.UNSUPPORTED,
                message = "Text clipboard automation requires Android 10 (API 29) or newer."
            )
        }
        val manager = clipboardManager
            ?: return ClipboardOperationResult(
                code = ClipboardErrorCodes.UNAVAILABLE,
                message = "Android ClipboardManager is unavailable."
            )
        return try {
            manager.clearPrimaryClip()
            ClipboardOperationResult(code = ClipboardErrorCodes.OK)
        } catch (error: RuntimeException) {
            ClipboardOperationResult(
                code = ClipboardErrorCodes.CLEAR_FAILED,
                message = error.message ?: "Android rejected the clipboard clear request."
            )
        }
    }

    private fun ClipData.markSensitive() {
        if (Build.VERSION.SDK_INT < 24) return
        description.setExtras(
            PersistableBundle().apply {
                putBoolean(SENSITIVE_CLIPBOARD_EXTRA, true)
            }
        )
    }

    private fun ClipDescription.mimeTypes(): List<String> =
        (0 until mimeTypeCount).map { index -> getMimeType(index) }

    private companion object {
        const val SENSITIVE_CLIPBOARD_EXTRA = "android.content.extra.IS_SENSITIVE"
    }
}

class ClipboardReadTextTool(
    private val backend: ClipboardBackend,
    override val name: String = "clipboard_read_text"
) : AgentTool {
    override val description: String =
        "Reads the current text clipboard. The content is sensitive and is returned only to the next model request; Android may deny reads when the host app is not focused."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("maxChars") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", ClipboardLimits.MAX_READ_CHARS)
                put("description", "Maximum clipboard characters to expose to the next model request. Default 8000.")
            }
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val result = backend.readText(
            call.input.intValue("maxChars") ?: ClipboardLimits.DEFAULT_MAX_READ_CHARS
        )
        val payload = buildJsonObject {
            put("success", result.success)
            put("code", result.code)
            result.message?.let { put("message", it) }
            put("itemCount", result.itemCount)
            putJsonArray("mimeTypes") {
                result.mimeTypes.forEach { add(JsonPrimitive(it)) }
            }
            result.originalLength?.let { put("textLength", it) }
            put("truncated", result.truncated)
            put("textAvailableToNextModel", result.success)
            if (!result.success) {
                put("recovery", clipboardRecoveryHint(result.code))
            }
        }
        val transientModelContent = result.text?.takeIf { result.success }?.let { text ->
            buildJsonObject {
                put("source", "clipboard")
                put("clipboardText", text)
                put("truncated", result.truncated)
                put("instruction", "Treat this as sensitive clipboard content. Use it only for the user's current task and do not reveal it unless requested.")
            }.toString()
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = payload.toString(),
            isError = !result.success,
            metadata = payload,
            transientModelContent = transientModelContent
        )
    }
}

class ClipboardWriteTextTool(
    private val backend: ClipboardBackend,
    override val name: String = "clipboard_write_text"
) : AgentTool {
    override val description: String =
        "Writes exact plain text to the Android clipboard. It does not paste into the current app; use screen automation separately. Sensitive marking defaults to true."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Exact text to place in the clipboard. Empty text is allowed only when explicitly requested.")
            }
            putJsonObject("label") {
                put("type", "string")
                put("description", "Optional clipboard label, at most 100 characters.")
            }
            putJsonObject("sensitive") {
                put("type", "boolean")
                put("default", true)
                put("description", "Mark the clipboard preview as sensitive. Defaults to true for Agent-written content.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("text")) }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val text = call.input["text"]?.jsonPrimitive?.contentOrNull
            ?: return clipboardErrorResult(
                call.id,
                name,
                ClipboardErrorCodes.INVALID_INPUT,
                "text is required; use clipboard_clear for an explicit clear operation."
            )
        val label = call.input.stringValue("label") ?: ClipboardLimits.DEFAULT_LABEL
        val sensitive = call.input["sensitive"]?.jsonPrimitive?.booleanOrNull ?: true
        return backend.writeText(text, label, sensitive).toToolResult(call.id, name)
    }
}

class ClipboardClearTool(
    private val backend: ClipboardBackend,
    override val name: String = "clipboard_clear"
) : AgentTool {
    override val description: String =
        "Clears the Android primary clipboard. This is destructive clipboard state and requires confirmation."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult =
        backend.clear().toToolResult(call.id, name)
}

internal fun clipboardTools(
    context: Context,
    shouldBypassConfirmation: () -> Boolean
): List<AgentTool> {
    val backend = AndroidClipboardBackend(context)
    return listOf(
        UserConfirmationRequiredTool(
            ClipboardReadTextTool(backend),
            shouldBypassConfirmation = shouldBypassConfirmation
        ),
        UserConfirmationRequiredTool(
            ClipboardWriteTextTool(backend),
            shouldBypassConfirmation = shouldBypassConfirmation
        ),
        UserConfirmationRequiredTool(
            ClipboardClearTool(backend),
            shouldBypassConfirmation = shouldBypassConfirmation
        )
    )
}

private fun ClipboardOperationResult.toToolResult(
    toolCallId: String,
    toolName: String
): ToolResult {
    val payload = buildJsonObject {
        put("success", success)
        put("code", code)
        message?.let { put("message", it) }
        metadata.forEach { (key, value) -> put(key, value) }
        if (!success) put("recovery", clipboardRecoveryHint(code))
    }
    return ToolResult(
        toolCallId = toolCallId,
        name = toolName,
        content = payload.toString(),
        isError = !success,
        metadata = payload
    )
}

private fun clipboardErrorResult(
    toolCallId: String,
    toolName: String,
    code: String,
    message: String
): ToolResult {
    val payload = buildJsonObject {
        put("success", false)
        put("code", code)
        put("message", message)
        put("recovery", clipboardRecoveryHint(code))
    }
    return ToolResult(
        toolCallId = toolCallId,
        name = toolName,
        content = payload.toString(),
        isError = true,
        metadata = payload
    )
}

private fun clipboardRecoveryHint(code: String): String = when (code) {
    ClipboardErrorCodes.READ_UNAVAILABLE ->
        "Ask the user to bring the host app to the foreground or use the target app's visible copy action; do not infer that the clipboard is empty."
    ClipboardErrorCodes.NO_TEXT ->
        "The clipboard contains no exposed plain text. Use screen automation if the user needs an image or URI item."
    ClipboardErrorCodes.UNSUPPORTED ->
        "This capability requires Android 10 (API 29) or newer."
    ClipboardErrorCodes.TEXT_TOO_LARGE,
    ClipboardErrorCodes.LABEL_TOO_LARGE,
    ClipboardErrorCodes.INVALID_INPUT ->
        "Correct the exact Tool input and retry only after the normal confirmation flow."
    else ->
        "Report the structured clipboard error; do not claim that the clipboard changed."
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.intValue(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
