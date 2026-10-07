package com.ugk.pi.system.skill
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun screenErrorResult(
    callId: String,
    toolName: String,
    code: String,
    message: String,
    recoveryHint: String = screenRecoveryHint(code),
    recoveryTool: String = screenRecoveryTool(code)
): ToolResult {
    val payload = buildJsonObject {
        put("code", code)
        put("message", message)
        put("recovery", recoveryHint)
        put("recoveryTool", recoveryTool)
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
    ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_UNSUPPORTED ->
        "Screen capture is unsupported by this device or host. Use screen_read_ui_tree or screen_find_ui_element for the rest of this task; do not capture again."
    ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_FAILED,
    ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_TIMEOUT ->
        "Retry screen_capture_visual once. If the fresh capture also fails, continue with screen_read_ui_tree or screen_find_ui_element."
    ScreenAutomationErrorCodes.VISUAL_OBSERVATION_REQUIRED,
    ScreenAutomationErrorCodes.VISUAL_OBSERVATION_STALE,
    ScreenAutomationErrorCodes.VISUAL_TARGET_INVALID ->
        "Call screen_capture_visual to obtain a fresh visual observation before using screen_visual_gesture."
    else ->
        "Call screen_read_ui_tree or screen_find_ui_element now and use only its latest snapshotId and nodeId. Do not use terminal_bash_execute or relaunch the app to recover."
}

/**
 * An argument that was sent but cannot be read is refused by name, at the Tool,
 * before anything is dispatched. The private helpers this replaced returned null for
 * it, and every caller read null as "the model left it out".
 */
private fun unusableArgument(
    call: ToolCall,
    toolName: String,
    key: String,
    input: JsonObject
): ToolResult = screenErrorResult(
    callId = call.id,
    toolName = toolName,
    code = ScreenAutomationErrorCodes.INVALID_INPUT,
    message = unusableArgumentMessage(key, input[key])
)

private val selectorKeys = listOf(
    "text",
    "text_exact",
    "content_desc",
    "content_desc_exact",
    "view_id",
    "type"
)

/**
 * Every argument `screen_find_ui_element` reads. A key outside this list is refused where the
 * arguments are read: the selector loop only ever visits [selectorKeys], so an unsupported
 * key never reaches `matchesSelector` and its `else -> false` arm - it is simply not applied,
 * which drops a constraint and widens the match set the caller then acts on.
 */
private val findUiElementArgumentKeys = selectorKeys + "max_results"

private const val DEFAULT_MAX_RESULTS = 20
private const val MAX_RESULTS_LIMIT = 50

/**
 * The selectors that pick an element *out* by an exact value. A blank one of these was
 * dropped by the old reader and the query then matched more than the caller asked; a
 * blank substring selector asks for "contains nothing in particular", which the drop
 * already expresses.
 */
private val identitySelectorKeys = setOf("text_exact", "content_desc_exact", "view_id", "type")

/** Every field the visual target schema declares as required, so none can be missed. */
private val visualTargetFields = listOf("left", "top", "right", "bottom")

/**
 * One arm per selector key. Adding a selector to [selectorKeys] without adding its arm here
 * can only ever narrow the result set, never silently widen it to "everything".
 *
 * This does not cover a key the *caller* invented: the argument reader only visits
 * [selectorKeys], so an unsupported key is refused at [findUiElementArgumentKeys] instead of
 * reaching this `else`.
 */
private fun ScreenUiElement.matchesSelector(key: String, value: String): Boolean = when (key) {
    "text" -> text?.contains(value, ignoreCase = true) == true
    "text_exact" -> text == value
    "content_desc" -> contentDesc?.contains(value, ignoreCase = true) == true
    "content_desc_exact" -> contentDesc == value
    "view_id" -> viewId == value
    "type" -> type.equals(value, ignoreCase = true)
    else -> false
}

private fun screenRecoveryTool(code: String): String = when (code) {
    ScreenAutomationErrorCodes.ACCESSIBILITY_UNAVAILABLE -> "get_android_accessibility_status"
    ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_UNSUPPORTED -> "screen_read_ui_tree"
    ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_FAILED,
    ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_TIMEOUT,
    ScreenAutomationErrorCodes.VISUAL_OBSERVATION_REQUIRED,
    ScreenAutomationErrorCodes.VISUAL_OBSERVATION_STALE,
    ScreenAutomationErrorCodes.VISUAL_TARGET_INVALID -> "screen_capture_visual"
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
        val maxDepth = when (val declared = call.input.declaredInt("max_depth")) {
            DeclaredArgument.Undeclared -> ScreenAutomationLimits.DEFAULT_MAX_DEPTH
            DeclaredArgument.Unusable -> return unusableArgument(call, name, "max_depth", call.input)
            is DeclaredArgument.Of -> declared.value
        }
        val maxNodes = when (val declared = call.input.declaredInt("max_nodes")) {
            DeclaredArgument.Undeclared -> ScreenAutomationLimits.DEFAULT_MAX_NODES
            DeclaredArgument.Unusable -> return unusableArgument(call, name, "max_nodes", call.input)
            is DeclaredArgument.Of -> declared.value
        }
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
        val maxResultsArgument = call.input.declaredInt("max_results")
        val selectors = linkedMapOf<String, String>()
        for (key in selectorKeys) {
            when (val declared = call.input.declaredString(key)) {
                DeclaredArgument.Undeclared -> Unit
                DeclaredArgument.Unusable -> return unusableArgument(call, name, key, call.input)
                is DeclaredArgument.Of -> {
                    val value = declared.value
                    if (key in identitySelectorKeys) {
                        // An empty *identity* selector (exact text, exact description,
                        // viewId, type) is a filter the caller stated, and the old reader
                        // dropped it - so the match set silently grew to everything the
                        // other selectors allow. Refused, blank included: a whitespace-only
                        // exact match asks for a field that is all blanks, which no caller
                        // means and the old code silently widened.
                        if (value.isBlank()) {
                            return screenErrorResult(
                                callId = call.id,
                                toolName = name,
                                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                                message = "'$key' was sent empty. An empty selector of this " +
                                    "kind does not mean 'no constraint': it would return " +
                                    "every element the other selectors allow. Drop the " +
                                    "argument or name what to look for."
                            )
                        }
                        selectors[key] = value
                    } else {
                        // A *substring* selector only means "no constraint" when it is
                        // literally empty; " " or "\t" is a real filter, and dropping it
                        // would widen the result set the same way the old reader did.
                        if (value.isNotEmpty()) selectors[key] = value
                    }
                }
            }
        }
        // Checked before "give me a selector": an unreadable limit is the argument the
        // caller has to fix, and a refusal that names a different one sends them away.
        if (maxResultsArgument === DeclaredArgument.Unusable) {
            return unusableArgument(call, name, "max_results", call.input)
        }
        // Before "give me a selector": a call whose only selector is one this tool does not
        // implement has to learn which key it cannot honour, not that it sent no selectors.
        call.input.keys.firstOrNull { it !in findUiElementArgumentKeys }?.let { unsupportedKey ->
            return screenErrorResult(
                callId = call.id,
                toolName = name,
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "'$unsupportedKey' is not an argument this tool reads. Supported: " +
                    "${findUiElementArgumentKeys.joinToString(", ")}. An argument that cannot be " +
                    "honoured is refused rather than dropped: dropping a selector removes a " +
                    "constraint and returns every element the remaining ones allow."
            )
        }
        if (selectors.isEmpty()) {
            return screenErrorResult(
                callId = call.id,
                toolName = name,
                code = ScreenAutomationErrorCodes.INVALID_INPUT,
                message = "Provide at least one selector: ${selectorKeys.joinToString(", ")}."
            )
        }

        val maxResults = when (maxResultsArgument) {
            DeclaredArgument.Undeclared -> DEFAULT_MAX_RESULTS
            // Named arm, not `else`: an `else` here would quietly read an unreadable
            // limit as the default again if the check above ever moved.
            DeclaredArgument.Unusable -> return unusableArgument(call, name, "max_results", call.input)
            is DeclaredArgument.Of -> maxResultsArgument.value.coerceIn(1, MAX_RESULTS_LIMIT)
        }
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
            selectors.all { (key, value) -> element.matchesSelector(key, value) }
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

class ScreenCaptureVisualTool(
    private val backend: ScreenVisualAutomationBackend,
    override val name: String = "screen_capture_visual"
) : AgentTool {
    private val failedCapturesBySession = LinkedHashMap<String, Int>()

    override val description: String =
        "Captures the current external screen for an unknown interface, visual-only content, insufficient or ambiguous tree evidence, or a judgment requiring visual understanding, and attaches the image to the immediately following model request. Use this as the first choice for understanding the interface, locating click/swipe targets, and checking visible results. Do not query the View tree first; reuse an already current image when sufficient. The image is sent to the configured model; capture only task-relevant screens."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val result = backend.captureVisualObservation(context.sessionId)
        val observation = result.observation
        return if (result.success && observation != null) {
            clearCaptureFailures(context.sessionId)
            val payload = observation.toJson()
            ToolResult(
                toolCallId = call.id,
                name = name,
                content = payload.toString(),
                metadata = payload,
                images = listOf(observation.image),
                imageContext =
                    "已附带当前屏幕截图。请只根据截图中实际可见内容判断目标；若执行视觉坐标手势，必须使用该 observationId，并返回目标区域的 0..1 归一化 left/top/right/bottom。语义节点操作仍需使用最新结构树中的 snapshotId 和 nodeId。"
            )
        } else {
            val failureCount = if (
                result.code == ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_FAILED ||
                result.code == ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_TIMEOUT
            ) {
                recordCaptureFailure(context.sessionId)
            } else {
                clearCaptureFailures(context.sessionId)
                0
            }
            val retryCapture = failureCount == 1
            val recoveryHint = when {
                result.code == ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_UNSUPPORTED ->
                    "Screen capture is unsupported by this device or host. Use screen_read_ui_tree or screen_find_ui_element for the rest of this task; do not capture again."
                failureCount >= 2 ->
                    "Screen capture failed twice. Use screen_read_ui_tree or screen_find_ui_element for the rest of this task; do not capture again."
                retryCapture ->
                    "Retry screen_capture_visual once. If the fresh capture also fails, continue with screen_read_ui_tree or screen_find_ui_element."
                else -> screenRecoveryHint(result.code)
            }
            val recoveryTool = when {
                result.code == ScreenAutomationErrorCodes.VISUAL_SCREENSHOT_UNSUPPORTED -> "screen_read_ui_tree"
                failureCount >= 2 -> "screen_read_ui_tree"
                else -> screenRecoveryTool(result.code)
            }
            screenErrorResult(
                callId = call.id,
                toolName = name,
                code = result.code,
                message = result.message ?: "Unable to capture the current screen.",
                recoveryHint = recoveryHint,
                recoveryTool = recoveryTool
            )
        }
    }

    private fun recordCaptureFailure(sessionId: String): Int = synchronized(failedCapturesBySession) {
        val count = (failedCapturesBySession[sessionId] ?: 0) + 1
        failedCapturesBySession[sessionId] = count
        while (failedCapturesBySession.size > MAX_TRACKED_CAPTURE_FAILURE_SESSIONS) {
            failedCapturesBySession.entries.firstOrNull()?.let { failedCapturesBySession.remove(it.key) }
        }
        count
    }

    private fun clearCaptureFailures(sessionId: String) = synchronized(failedCapturesBySession) {
        failedCapturesBySession.remove(sessionId)
        Unit
    }

    private companion object {
        const val MAX_TRACKED_CAPTURE_FAILURE_SESSIONS = 16
    }
}

class ScreenVisualGestureTool(
    private val backend: ScreenVisualAutomationBackend,
    override val name: String = "screen_visual_gesture"
) : AgentTool {
    override val description: String =
        "Performs a bounded coordinate gesture against the latest screen_capture_visual observation without a pre-gesture pixel comparison or age timeout. Animations do not block dispatch; session, app, display geometry and target bounds must still match. Each observation can be used for only one gesture. Use the latest observationId and a normalized target rectangle identified from that screenshot."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("observationId") {
                put("type", "string")
                put("description", "Exact observationId returned by the latest screen_capture_visual result.")
            }
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    ScreenGestureNames.supported.forEach { add(JsonPrimitive(it)) }
                }
            }
            putJsonObject("target") {
                put("type", "object")
                put("description", "Target bounds in normalized 0..1 screen coordinates, not image pixels.")
                putJsonObject("properties") {
                    putJsonObject("left") { put("type", "number") }
                    putJsonObject("top") { put("type", "number") }
                    putJsonObject("right") { put("type", "number") }
                    putJsonObject("bottom") { put("type", "number") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("left"))
                    add(JsonPrimitive("top"))
                    add(JsonPrimitive("right"))
                    add(JsonPrimitive("bottom"))
                }
            }
            putJsonObject("targetDescription") {
                put("type", "string")
                put("description", "Short description of the visible target, for confirmation and diagnostics.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("observationId"))
            add(JsonPrimitive("action"))
            add(JsonPrimitive("target"))
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val observationIdArgument = call.input.declaredNonBlank("observationId")
        val actionArgument = call.input.declaredNonBlank("action")
        val targetDescriptionArgument = call.input.declaredNonBlank("targetDescription")
        // Every declared argument is checked before the target block, and an absent
        // required argument is reported as absent: a refusal that says "was sent as an
        // unreadable value" about a key nobody sent sends the caller after the wrong fix.
        listOf(
            "observationId" to observationIdArgument,
            "action" to actionArgument,
            "targetDescription" to targetDescriptionArgument
        ).forEach { (key, declared) ->
            if (declared === DeclaredArgument.Unusable) {
                return unusableArgument(call, name, key, call.input)
            }
        }
        val targetObject = call.input["target"] as? JsonObject
            ?: return screenErrorResult(
                callId = call.id,
                toolName = name,
                code = ScreenAutomationErrorCodes.VISUAL_TARGET_INVALID,
                message = if (call.input.optionalElement("target") == null) {
                    // Absent or JSON null: nobody sent a target. Saying "was sent as an
                    // unreadable value" here would describe a value that does not exist.
                    "target is required: an object with numeric left, top, right and bottom " +
                        "values in normalized 0..1 coordinates."
                } else {
                    unusableArgumentMessage("target", call.input["target"])
                }
            )
        val targetValues = linkedMapOf<String, Double>()
        for (field in visualTargetFields) {
            when (val declared = targetObject.declaredDouble(field)) {
                is DeclaredArgument.Of -> targetValues[field] = declared.value
                else -> return screenErrorResult(
                    callId = call.id,
                    toolName = name,
                    code = ScreenAutomationErrorCodes.VISUAL_TARGET_INVALID,
                    message = if (declared === DeclaredArgument.Unusable) {
                        unusableArgumentMessage("target." + field, targetObject[field])
                    } else {
                        "target." + field + " is missing; target needs numeric left, top, right " +
                            "and bottom values in normalized 0..1 coordinates."
                    }
                )
            }
        }
        val target = ScreenVisualTarget(
            left = targetValues.getValue("left"),
            top = targetValues.getValue("top"),
            right = targetValues.getValue("right"),
            bottom = targetValues.getValue("bottom")
        )

        val result = backend.performVisualGesture(
            sessionId = context.sessionId,
            request = ScreenVisualGestureRequest(
                observationId = (observationIdArgument as? DeclaredArgument.Of)?.value,
                action = (actionArgument as? DeclaredArgument.Of)?.value.orEmpty(),
                target = target,
                targetDescription = (targetDescriptionArgument as? DeclaredArgument.Of)?.value
            )
        )
        return result.toToolResult(call.id, name)
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
        call.input.firstStructuredArgument(listOf("snapshotId", "nodeId", "action", "text"))?.let { key ->
            return unusableArgument(call, name, key, call.input)
        }
        val action = (call.input.declaredNonBlank("action") as? DeclaredArgument.Of)?.value.orEmpty()
        val textArgument = call.input.declaredString("text")
        val request = ScreenActionRequest(
            snapshotId = (call.input.declaredNonBlank("snapshotId") as? DeclaredArgument.Of)?.value,
            nodeId = (call.input.declaredNonBlank("nodeId") as? DeclaredArgument.Of)?.value.orEmpty(),
            action = action,
            // An empty text is a real request here: it clears the field. Only JSON
            // null and a missing key mean "no text given", which the backend refuses
            // rather than reading as an implicit clear.
            text = (textArgument as? DeclaredArgument.Of)?.value
        )
        return backend.performAction(context.sessionId, request).toToolResult(call.id, name)
    }
}

class ScreenGestureTool(
    private val backend: ScreenAutomationBackend,
    override val name: String = "screen_gesture"
) : AgentTool {
    override val description: String =
        "Performs a coordinate gesture using the current screen dimensions. Prefer screen_visual_gesture when a fresh screenshot observation is available; otherwise ground coordinates in a current accessibility snapshot."

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
        call.input.firstStructuredArgument(listOf("action", "x", "y"))?.let { key ->
            return unusableArgument(call, name, key, call.input)
        }
        val result = backend.performGesture(
            ScreenGestureRequest(
                action = when (val declared = call.input.declaredNonBlank("action")) {
                    DeclaredArgument.Unusable -> return unusableArgument(call, name, "action", call.input)
                    else -> (declared as? DeclaredArgument.Of)?.value.orEmpty()
                },
                // An argument nobody sent keeps its existing downstream code, so the
                // backend's missing-coordinate report does not change class; a value
                // that WAS sent in an unreadable shape is refused by name here, because
                // it used to be indistinguishable from "not sent" and then blamed the
                // screen bounds.
                x = when (val declared = call.input.declaredInt("x")) {
                    DeclaredArgument.Unusable -> return unusableArgument(call, name, "x", call.input)
                    is DeclaredArgument.Of -> declared.value
                    else -> null
                },
                y = when (val declared = call.input.declaredInt("y")) {
                    DeclaredArgument.Unusable -> return unusableArgument(call, name, "y", call.input)
                    is DeclaredArgument.Of -> declared.value
                    else -> null
                }
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
        val key = when (val declared = call.input.declaredNonBlank("key")) {
            DeclaredArgument.Unusable -> return unusableArgument(call, name, "key", call.input)
            else -> (declared as? DeclaredArgument.Of)?.value.orEmpty()
        }
        return backend.pressKey(ScreenKeyRequest(key)).toToolResult(call.id, name)
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
            ScreenGlobalActionRequest(
                when (val declared = call.input.declaredNonBlank("action")) {
                    DeclaredArgument.Unusable -> return unusableArgument(call, name, "action", call.input)
                    else -> (declared as? DeclaredArgument.Of)?.value.orEmpty()
                }
            )
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

private fun ScreenVisualObservation.toJson(): JsonObject = buildJsonObject {
    put("observationId", observationId)
    put("sessionId", sessionId)
    put("package", packageName)
    put("screenWidth", screenWidth)
    put("screenHeight", screenHeight)
    put("imageWidth", imageWidth)
    put("imageHeight", imageHeight)
    put("displayId", displayId)
    put("rotation", rotation)
    put("capturedAtEpochMillis", capturedAtEpochMillis)
    put("coordinateSpace", "normalized_0_to_1")
    put("preGesturePixelCheck", false)
    put("singleUse", true)
    put("imageAttached", true)
}
