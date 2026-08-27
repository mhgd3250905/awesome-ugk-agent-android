package com.ugk.pi.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun screenErrorResult(
    callId: String,
    toolName: String,
    code: String,
    message: String
): ToolResult {
    val payload = buildJsonObject {
        put("code", code)
        put("message", message)
        put("recovery", screenRecoveryHint(code))
        put("recoveryTool", screenRecoveryTool(code))
    }
    return ToolResult(
        toolCallId = callId,
        name = toolName,
        content = payload.toString(),
        isError = true,
        metadata = payload
    )
}

private fun screenRecoveryHint(code: String): String = when (code) {
    ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE ->
        "Call get_android_accessibility_status, then read the screen again when readyForScreenAutomation is true."
    else ->
        "Call screen_read_ui_tree or screen_find_ui_element now and use only its latest snapshotId and nodeId. Do not use terminal_bash_execute or relaunch the app to recover."
}

private fun screenRecoveryTool(code: String): String = when (code) {
    ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE -> "get_android_accessibility_status"
    else -> "screen_read_ui_tree"
}

class ScreenReadUiTreeTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_read_ui_tree"
) : AgentTool {
    override val description: String =
        "Reads the current AccessibilityService UI snapshot, including visible elements, supported actions, screen bounds, and a snapshotId required for later node actions."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_depth") {
                put("type", "integer")
                put("description", "Maximum traversal depth. Default 15, capped at 30.")
            }
            putJsonObject("max_nodes") {
                put("type", "integer")
                put("description", "Maximum nodes to return. Default 200, capped at 500.")
            }
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val maxDepth = call.input.intValue("max_depth") ?: ScreenAutomationLimits.DEFAULT_MAX_DEPTH
        val maxNodes = call.input.intValue("max_nodes") ?: ScreenAutomationLimits.DEFAULT_MAX_NODES
        val result = backend.readUiTree(context.sessionId, maxDepth, maxNodes)
        val snapshot = result.snapshot
        return if (result.success && snapshot != null) {
            ToolResult(call.id, name, snapshot.toJson().toString())
        } else {
            screenErrorResult(
                callId = call.id,
                toolName = name,
                code = result.code,
                message = result.message ?: "Unable to read the accessibility UI tree."
            )
        }
    }
}

class ScreenFindUiElementTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_find_ui_element"
) : AgentTool {
    override val description: String =
        "Reads the current accessibility snapshot and returns matching UI elements by text, content description, viewId, or type. Use it to avoid sending an unnecessarily large full tree to the model."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string") }
            putJsonObject("text_exact") {
                put("type", "string")
                put("description", "Exact case-sensitive text match when substring matching is too broad.")
            }
            putJsonObject("content_desc") { put("type", "string") }
            putJsonObject("content_desc_exact") {
                put("type", "string")
                put("description", "Exact case-sensitive content description match.")
            }
            putJsonObject("view_id") { put("type", "string") }
            putJsonObject("type") {
                put("type", "string")
                put("description", "Short class name returned by the snapshot, for example Button or EditText.")
            }
            putJsonObject("max_results") {
                put("type", "integer")
                put("description", "Maximum matches to return. Default 20, capped at 50.")
            }
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val text = call.input.stringValue("text")
        val textExact = call.input.stringValue("text_exact")
        val contentDesc = call.input.stringValue("content_desc")
        val contentDescExact = call.input.stringValue("content_desc_exact")
        val viewId = call.input.stringValue("view_id")
        val type = call.input.stringValue("type")
        if (text == null && textExact == null && contentDesc == null && contentDescExact == null && viewId == null && type == null) {
            return screenErrorResult(
                callId = call.id,
                toolName = name,
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "Provide at least one selector: text, text_exact, content_desc, content_desc_exact, view_id, or type."
            )
        }

        val maxResults = (call.input.intValue("max_results") ?: 20).coerceIn(1, 50)
        val read = backend.readUiTree(
            sessionId = context.sessionId,
            maxDepth = ScreenAutomationLimits.DEFAULT_MAX_DEPTH,
            maxNodes = ScreenAutomationLimits.DEFAULT_MAX_NODES
        )
        val snapshot = read.snapshot
        if (!read.success || snapshot == null) {
            return screenErrorResult(
                callId = call.id,
                toolName = name,
                code = read.code,
                message = read.message ?: "Unable to read the accessibility UI tree."
            )
        }

        val allMatches = snapshot.elements.filter { element ->
            (text == null || element.text?.contains(text, ignoreCase = true) == true) &&
                (textExact == null || element.text == textExact) &&
                (contentDesc == null || element.contentDesc?.contains(contentDesc, ignoreCase = true) == true) &&
                (contentDescExact == null || element.contentDesc == contentDescExact) &&
                (viewId == null || element.viewId == viewId) &&
                (type == null || element.type.equals(type, ignoreCase = true))
        }
        val matches = allMatches.take(maxResults)

        return ToolResult(
            call.id,
            name,
            buildJsonObject {
                put("snapshotId", snapshot.snapshotId)
                put("package", snapshot.packageName)
                put("screenWidth", snapshot.screenWidth)
                put("screenHeight", snapshot.screenHeight)
                put("truncated", snapshot.truncated)
                put("count", matches.size)
                put("totalCount", allMatches.size)
                put("ambiguous", allMatches.size > 1)
                putJsonArray("matches") {
                    matches.forEach { add(it.toCompactJson()) }
                }
            }.toString()
        )
    }
}

class ScreenPerformActionTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_perform_action"
) : AgentTool {
    override val description: String =
        "Performs a node action from the latest screen snapshot. Requires the exact snapshotId and nodeId returned by screen_read_ui_tree or screen_find_ui_element. If the result is SNAPSHOT_REQUIRED or STALE_SNAPSHOT, do not retry the same input; read the screen again first."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("snapshotId") {
                put("type", "string")
                put("description", "snapshotId from the latest screen_read_ui_tree or screen_find_ui_element result.")
            }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Exact nodeId from the same snapshot, such as '0.1.2'.")
            }
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { ScreenActionNames.supported.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Required for set_text; an empty string is allowed when explicitly requested.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("snapshotId"))
            add(JsonPrimitive("nodeId"))
            add(JsonPrimitive("action"))
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val action = call.input.stringValue("action").orEmpty()
        val request = ScreenActionRequest(
            snapshotId = call.input.stringValue("snapshotId"),
            nodeId = call.input.stringValue("nodeId").orEmpty(),
            action = action,
            text = call.input["text"]?.jsonPrimitive?.contentOrNull
        )
        return backend.performAction(context.sessionId, request).toToolResult(call.id, name)
    }
}

class ScreenGestureTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_gesture"
) : AgentTool {
    override val description: String =
        "Performs a coordinate gesture using the current screen dimensions. Use only when the UI tree cannot expose the target."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { ScreenGestureNames.supported.forEach { add(JsonPrimitive(it)) } }
            }
            putJsonObject("x") { put("type", "integer") }
            putJsonObject("y") { put("type", "integer") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("x"))
            add(JsonPrimitive("y"))
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val result = backend.performGesture(
            ScreenGestureRequest(
                action = call.input.stringValue("action").orEmpty(),
                x = call.input.intValue("x"),
                y = call.input.intValue("y")
            )
        )
        return result.toToolResult(call.id, name)
    }
}

class ScreenPressKeyTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_press_key"
) : AgentTool {
    override val description: String =
        "Triggers an IME action on the currently focused input field. Currently supports key='enter'."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("key") {
                put("type", "string")
                putJsonArray("enum") { add(JsonPrimitive("enter")) }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("key")) }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        return backend.pressKey(
            ScreenKeyRequest(call.input.stringValue("key").orEmpty())
        ).toToolResult(call.id, name)
    }
}

class ScreenGlobalActionTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_global_action"
) : AgentTool {
    override val description: String =
        "Performs a confirmed global Android navigation action such as back, home, recents, notifications, or quick settings."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { ScreenGlobalActionNames.supported.forEach { add(JsonPrimitive(it)) } }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")) }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        return backend.performGlobalAction(
            ScreenGlobalActionRequest(call.input.stringValue("action").orEmpty())
        ).toToolResult(call.id, name)
    }
}

private fun ScreenUiSnapshot.toJson(): JsonObject = buildJsonObject {
    put("snapshotId", snapshotId)
    put("package", packageName)
    put("screenWidth", screenWidth)
    put("screenHeight", screenHeight)
    put("windowCount", windowCount)
    put("nodeCount", nodeCount)
    put("truncated", truncated)
    putJsonArray("elements") { elements.forEach { add(it.toJson()) } }
}

private fun ScreenUiElement.toJson(): JsonObject = buildJsonObject {
    put("nodeId", nodeId)
    put("windowIndex", windowIndex)
    put("package", packageName)
    put("type", type)
    text?.let { put("text", it) }
    contentDesc?.let { put("contentDesc", it) }
    hint?.let { put("hint", it) }
    putJsonArray("bounds") {
        add(JsonPrimitive(bounds.left))
        add(JsonPrimitive(bounds.top))
        add(JsonPrimitive(bounds.right))
        add(JsonPrimitive(bounds.bottom))
    }
    putJsonArray("actions") { actions.forEach { action -> add(action.toJson()) } }
    if (clickable) put("clickable", true)
    if (scrollable) put("scrollable", true)
    if (editable) put("editable", true)
    if (checkable) put("checkable", true)
    if (checked) put("checked", true)
    if (!enabled) put("enabled", false)
    if (focusable) put("focusable", true)
    if (!visibleToUser) put("visibleToUser", false)
    viewId?.takeIf { it.isNotBlank() }?.let { put("viewId", it) }
}

private fun ScreenUiElement.toCompactJson(): JsonObject = buildJsonObject {
    put("nodeId", nodeId)
    put("windowIndex", windowIndex)
    put("package", packageName)
    put("type", type)
    text?.let { put("text", it) }
    contentDesc?.let { put("contentDesc", it) }
    viewId?.let { put("viewId", it) }
    putJsonArray("bounds") {
        add(JsonPrimitive(bounds.left))
        add(JsonPrimitive(bounds.top))
        add(JsonPrimitive(bounds.right))
        add(JsonPrimitive(bounds.bottom))
    }
    putJsonArray("actions") { actions.forEach { add(it.toJson()) } }
}

private fun ScreenUiAction.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    label?.let { put("label", it) }
}

private fun ScreenOperationResult.toToolResult(toolCallId: String, toolName: String): ToolResult {
    val payload = buildJsonObject {
        put("success", success)
        put("code", code)
        message?.let { put("message", it) }
        nodeId?.let { put("nodeId", it) }
        action?.let { put("action", it) }
        snapshotId?.let { put("snapshotId", it) }
        metadata.forEach { (key, value) -> put(key, value) }
        if (!success) {
            put("recovery", screenRecoveryHint(code))
            put("recoveryTool", screenRecoveryTool(code))
        }
    }.toString()
    val resultMetadata = buildJsonObject {
        put("code", code)
        message?.let { put("message", it) }
        metadata.forEach { (key, value) -> put(key, value) }
        if (!success) {
            put("recovery", screenRecoveryHint(code))
            put("recoveryTool", screenRecoveryTool(code))
        }
    }
    return ToolResult(
        toolCallId = toolCallId,
        name = toolName,
        content = payload,
        isError = !success,
        metadata = resultMetadata
    )
}

private fun JsonObject.stringValue(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.intValue(name: String): Int? =
    this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
