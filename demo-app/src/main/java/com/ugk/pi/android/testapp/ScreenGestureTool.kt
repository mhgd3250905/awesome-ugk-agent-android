package com.ugk.pi.android.testapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ScreenGestureTool(
    override val name: String = "screen_gesture"
) : AgentTool {

    private val TAG = "ScreenGesture"

    override val description: String =
        "Performs gesture actions by screen coordinates: tap, long_press, swipe. Use when UI tree reading is blocked or unreliable (e.g. WeChat)."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "Gesture type: tap, long_press, swipe_up, swipe_down, swipe_left, swipe_right")
                putJsonArray("enum") {
                    add(kotlinx.serialization.json.JsonPrimitive("tap"))
                    add(kotlinx.serialization.json.JsonPrimitive("long_press"))
                    add(kotlinx.serialization.json.JsonPrimitive("swipe_up"))
                    add(kotlinx.serialization.json.JsonPrimitive("swipe_down"))
                    add(kotlinx.serialization.json.JsonPrimitive("swipe_left"))
                    add(kotlinx.serialization.json.JsonPrimitive("swipe_right"))
                }
            }
            putJsonObject("x") {
                put("type", "integer")
                put("description", "X coordinate for tap/long_press. Center X for swipe.")
            }
            putJsonObject("y") {
                put("type", "integer")
                put("description", "Y coordinate for tap/long_press. Start Y for swipe.")
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("action"))
            add(kotlinx.serialization.json.JsonPrimitive("x"))
            add(kotlinx.serialization.json.JsonPrimitive("y"))
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
                content = "Accessibility service is not running.",
                isError = true
            )

        val action = call.input["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val x = call.input["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val y = call.input["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

        Log.d(TAG, "execute: action=$action x=$x y=$y")

        val screenH = service.resources.displayMetrics.heightPixels
        val screenW = service.resources.displayMetrics.widthPixels

        val gesture = when (action) {
            "tap" -> buildClick(x, y)
            "long_press" -> buildClick(x, y, duration = 500L)
            "swipe_up" -> buildSwipe(x, y, x, y - screenH / 3)
            "swipe_down" -> buildSwipe(x, y, x, y + screenH / 3)
            "swipe_left" -> buildSwipe(x, y, x - screenW / 3, y)
            "swipe_right" -> buildSwipe(x, y, x + screenW / 3, y)
            else -> return ToolResult(
                toolCallId = call.id,
                name = name,
                content = "Unknown action: $action",
                isError = true
            )
        }

        val result = dispatchGesture(service, gesture)
        Log.d(TAG, "execute: result=$result")

        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = buildJsonObject {
                put("action", action)
                put("x", x)
                put("y", y)
                put("success", result)
            }.toString()
        )
    }

    private fun buildClick(x: Int, y: Int, duration: Long = 50L): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun buildSwipe(x1: Int, y1: Int, x2: Int, y2: Int): GestureDescription {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun dispatchGesture(service: AccessibilityService, gesture: GestureDescription): Boolean {
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
}
