package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ScreenPressKeyTool(
    override val name: String = "screen_press_key"
) : AgentTool {

    private val TAG = "ScreenPressKey"

    override val description: String =
        "Presses a keyboard key. Currently supports 'enter' to trigger the IME action (search, send, go, done) on the currently focused input field. Use after set_text to submit the input."

    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("key") {
                put("type", "string")
                put("description", "The key to press.")
                putJsonArray("enum") {
                    add(JsonPrimitive("enter"))
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("key"))
        }
    }

    override suspend fun execute(
        call: ToolCall,
        context: ToolExecutionContext
    ): ToolResult {
        val key = call.input["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (key != "enter") {
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Unsupported key: $key. Only 'enter' is supported.",
                isError = true
            )
        }

        val service = AgentAccessibilityService.instance
            ?: return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Accessibility service is not running.",
                isError = true
            )

        if (Build.VERSION.SDK_INT >= 30) {
            val result = pressEnterViaImeAction(service)
            Log.d(TAG, "execute: ACTION_IME_ACTION result=$result")
            return ToolResult(
                toolCallId = call.id,
                name = name,
                content = buildJsonObject {
                    put("key", key)
                    put("method", "ime_action")
                    put("success", result)
                }.toString()
            )
        }

        val result = pressEnterViaGesture(service)
        Log.d(TAG, "execute: gesture fallback result=$result")
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("key", key)
                put("method", "gesture")
                put("success", result)
            }.toString()
        )
    }

    private fun pressEnterViaImeAction(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusNode != null) {
                val result = focusNode.performAction(ACTION_IME_ACTION)
                focusNode.recycle()
                return result
            }
        } finally {
            root.recycle()
        }
        return false
    }

    private fun pressEnterViaGesture(service: AccessibilityService): Boolean {
        val screenW = service.resources.displayMetrics.widthPixels
        val screenH = service.resources.displayMetrics.heightPixels
        val path = android.graphics.Path().apply {
            moveTo((screenW - 80).toFloat(), (screenH - 80).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val result = arrayOf(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                result[0] = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                result[0] = false
                latch.countDown()
            }
        }, null)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return result[0]
    }

    companion object {
        private const val ACTION_IME_ACTION = 0x00200000
    }
}
