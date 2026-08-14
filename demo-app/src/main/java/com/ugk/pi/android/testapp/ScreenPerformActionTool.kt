package com.ugk.pi.android.testapp

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ScreenPerformActionTool(
    override val name: String = "screen_perform_action"
) : AgentTool {

    private val TAG = "ScreenPerformAction"

    override val description: String =
        "Performs an action on a UI element identified by nodeId from screen_read_ui_tree. Supports click, long_click, scroll_forward, scroll_backward, set_text, focus, clear_focus."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "The nodeId from screen_read_ui_tree, e.g. '0.1.2'.")
            }
            putJsonObject("action") {
                put("type", "string")
                put("description", "Action to perform.")
                putJsonArray("enum") {
                    add(kotlinx.serialization.json.JsonPrimitive("click"))
                    add(kotlinx.serialization.json.JsonPrimitive("long_click"))
                    add(kotlinx.serialization.json.JsonPrimitive("scroll_forward"))
                    add(kotlinx.serialization.json.JsonPrimitive("scroll_backward"))
                    add(kotlinx.serialization.json.JsonPrimitive("set_text"))
                    add(kotlinx.serialization.json.JsonPrimitive("focus"))
                    add(kotlinx.serialization.json.JsonPrimitive("clear_focus"))
                }
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to set (only for set_text action).")
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("nodeId"))
            add(kotlinx.serialization.json.JsonPrimitive("action"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val nodeId = call.input["nodeId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val action = call.input["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val text = call.input["text"]?.jsonPrimitive?.contentOrNull

        val service = AgentAccessibilityService.instance
            ?: return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Accessibility service is not running.",
                isError = true
            )

        val ownPackage = service.packageName
        val parsedPath = parseScreenNodePath(nodeId)
        if (parsedPath == null) {
            // A malformed or missing nodeId must never fall back to window 0:
            // the model would act on an unrelated node while believing it
            // targeted the one it read.
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Invalid nodeId '$nodeId'. Use a nodeId exactly as returned by screen_read_ui_tree, e.g. '0.1.2'.",
                isError = true
            )
        }
        val rootIdx = parsedPath.rootIndex
        val childPath = parsedPath.childIndices.joinToString(".")

        Log.d(TAG, "execute: nodeId=$nodeId action=$action rootIdx=$rootIdx childPath=$childPath")

        val allWindows = service.windows
        Log.d(TAG, "execute: allWindows.size=${allWindows.size}")
        var targetRoot: AccessibilityNodeInfo? = null
        var seenIdx = 0
        for (win in allWindows) {
            val root = win.root ?: continue
            val winPkg = root.packageName?.toString()
            Log.d(TAG, "execute: win[$seenIdx].pkg=$winPkg")
            if (winPkg == ownPackage) continue
            if (seenIdx == rootIdx) {
                targetRoot = root
                break
            }
            seenIdx++
        }
        allWindows.forEach { it.recycle() }

        if (targetRoot == null) {
            // Never silently re-target another window: the child indices in
            // nodeId were computed against the window list captured by
            // screen_read_ui_tree, so applying them to a different root would
            // act on an unrelated node while reporting success.
            Log.w(TAG, "execute: window $rootIdx from nodeId is no longer available")
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Window '$rootIdx' from nodeId is no longer available. " +
                    "The screen may have changed. Call screen_read_ui_tree again.",
                isError = true
            )
        }

        Log.d(TAG, "execute: targetRoot.pkg=${targetRoot.packageName}")

        try {
            val node = if (childPath.isEmpty()) targetRoot else findNodeByPath(targetRoot, parsedPath.childIndices)
            try {
                if (node == null) {
                    Log.e(TAG, "execute: node not found for path=$childPath in root with ${targetRoot.childCount} children")
                    return ToolResult(
                        toolCallId = call.id,
                        name = name,
                        content = "Node not found: $nodeId. The screen may have changed. Call screen_read_ui_tree first.",
                        isError = true
                    )
                }

                Log.d(TAG, "execute: found node className=${node.className} text=${node.text} clickable=${node.isClickable}")

                val result = when (action) {
                    "click" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    "scroll_forward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    "scroll_backward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    "focus" -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    "clear_focus" -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                    "set_text" -> {
                        val args = Bundle()
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text ?: "")
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                    else -> false
                }

                Log.d(TAG, "execute: action=$action result=$result")

                return ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = buildJsonObject {
                        put("nodeId", nodeId)
                        put("action", action)
                        put("success", result)
                    }.toString(),
                    isError = !result
                )
            } finally {
                if (node != null && node !== targetRoot) {
                    node.recycle()
                }
            }
        } finally {
            targetRoot.recycle()
        }
    }

    private fun findNodeByPath(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        if (path.isEmpty()) return null

        var current: AccessibilityNodeInfo = root
        var ownsCurrent = false
        try {
            for (idx in path) {
                val child = current.getChild(idx) ?: return null
                if (ownsCurrent) {
                    current.recycle()
                }
                current = child
                ownsCurrent = true
            }
            ownsCurrent = false
            return current
        } finally {
            if (ownsCurrent) {
                current.recycle()
            }
        }
    }
}

/** Strictly parsed form of a screen_read_ui_tree nodeId such as "0.1.2". */
internal data class ScreenNodePath(
    val rootIndex: Int,
    val childIndices: List<Int>
)

/**
 * Parses a nodeId into its window index and child indices. Every segment
 * must be a non-negative decimal integer; malformed paths (including blank
 * input) resolve to nothing so they can never silently target another node.
 */
internal fun parseScreenNodePath(nodeId: String): ScreenNodePath? {
    val trimmed = nodeId.trim()
    if (trimmed.isEmpty()) return null
    val segments = trimmed.split(".")
    if (segments.any { segment -> segment.toIntOrNull() == null }) return null
    val indices = segments.map { it.toInt() }
    if (indices.any { it < 0 }) return null
    return ScreenNodePath(rootIndex = indices.first(), childIndices = indices.drop(1))
}
