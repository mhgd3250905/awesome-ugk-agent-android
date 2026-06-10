package com.ugk.pi.android.testapp

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ScreenReadUiTreeTool(
    override val name: String = "screen_read_ui_tree"
) : AgentTool {

    private val TAG = "ScreenReadUiTree"

    override val description: String =
        "Reads the current screen UI tree and returns a structured list of visible elements with their properties (text, type, bounds, clickable, etc). Excludes the agent's own overlay window."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_depth") {
                put("type", "integer")
                put("description", "Max traversal depth. Default 15.")
            }
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Accessibility service is not running. Please enable it in system settings.",
                isError = true
            )

        val ownPackage = service.packageName
        val maxDepth = call.input["max_depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
        val elements = mutableListOf<String>()
        var nodeCount = 0
        val maxNodes = 200
        var detectedPackage = "unknown"

        fun serializeNode(node: AccessibilityNodeInfo, depth: Int, path: String) {
            if (nodeCount >= maxNodes || depth > maxDepth) return
            val nodePkg = node.packageName?.toString()
            if (depth == 0 && nodePkg != null && nodePkg != ownPackage) {
                detectedPackage = nodePkg
            }
            if (nodePkg == ownPackage) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        serializeNode(child, depth + 1, "$path.$i")
                        child.recycle()
                    }
                }
                return
            }
            nodeCount++

            val text = node.text?.toString()?.take(200)
            val contentDesc = node.contentDescription?.toString()?.take(200)
            val hint = node.hintText?.toString()?.take(100)
            val className = node.className?.toString()?.substringAfterLast(".") ?: ""
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val clickable = node.isClickable
            val scrollable = node.isScrollable
            val editable = node.isEditable
            val checkable = node.isCheckable
            val checked = node.isChecked
            val enabled = node.isEnabled
            val focusable = node.isFocusable
            val viewId = node.viewIdResourceName

            val props = mutableListOf<String>()
            if (!text.isNullOrBlank()) props.add("\"text\":\"${text.replace("\"", "\\\"")}\"")
            if (!contentDesc.isNullOrBlank()) props.add("\"contentDesc\":\"${contentDesc.replace("\"", "\\\"")}\"")
            if (!hint.isNullOrBlank()) props.add("\"hint\":\"${hint.replace("\"", "\\\"")}\"")
            props.add("\"type\":\"$className\"")
            props.add("\"bounds\":[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
            if (clickable) props.add("\"clickable\":true")
            if (scrollable) props.add("\"scrollable\":true")
            if (editable) props.add("\"editable\":true")
            if (checkable) props.add("\"checkable\":true")
            if (checked) props.add("\"checked\":true")
            if (!enabled) props.add("\"enabled\":false")
            if (focusable) props.add("\"focusable\":true")
            if (!viewId.isNullOrBlank()) props.add("\"viewId\":\"$viewId\"")

            elements.add("{\"nodeId\":\"$path\",${props.joinToString(",")}}")

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    serializeNode(child, depth + 1, "$path.$i")
                    child.recycle()
                }
            }
        }

        val allWindows = service.windows
        Log.d(TAG, "execute: windows_count=${allWindows.size}")

        try {
            if (allWindows.isNotEmpty()) {
                var rootIdx = 0
                for (win in allWindows) {
                    val root = win.root ?: continue
                    val winPkg = root.packageName?.toString()
                    Log.d(TAG, "execute: win[$rootIdx].pkg=$winPkg childCount=${root.childCount}")
                    if (winPkg == ownPackage) continue
                    if (detectedPackage == "unknown") {
                        detectedPackage = winPkg ?: "unknown"
                    }
                    serializeNode(root, 0, "$rootIdx")
                    rootIdx++
                }
            } else {
                Log.w(TAG, "execute: no windows, using rootInActiveWindow fallback")
                val fallback = service.rootInActiveWindow
                    ?: return ToolResult(
                        toolCallId = call.id,
                        name = name,
                        content = "No active window. The screen may be locked or empty.",
                        isError = true
                    )
                detectedPackage = fallback.packageName?.toString() ?: "unknown"
                serializeNode(fallback, 0, "0")
            }
        } finally {
            allWindows.forEach { it.recycle() }
        }

        Log.d(TAG, "execute: pkg=$detectedPackage nodeCount=$nodeCount elements=${elements.size}")

        val content = """{"package":"$detectedPackage","nodeCount":$nodeCount,"elements":[${elements.joinToString(",")}]}"""

        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = content
        )
    }
}
