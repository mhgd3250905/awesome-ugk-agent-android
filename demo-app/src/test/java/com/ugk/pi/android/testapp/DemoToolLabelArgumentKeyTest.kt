package com.ugk.pi.android.testapp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run timeline is the only place a user can see which screen gesture the Agent
 * chose, and it read a key no Tool declares.
 *
 * `ScreenGestureTool.inputSchema` names its action field `action`
 * (pi-system-skill-android ScreenAutomationTools), while this label read
 * `input["gesture"]`, so every gesture rendered as the generic word and the timeline
 * claimed to name it. `DemoAgentTraceStore` read the same wrong key, and both files
 * also carried a `screen_launch_app` branch while the registered Tool is
 * `launch_android_app` with a `package_name` argument.
 */
class DemoToolLabelArgumentKeyTest {
    @Test
    fun theGestureLabelNamesTheGestureTheToolActuallyDeclares() {
        val summary = DemoToolSemanticMapper.formatInputSummary(
            "screen_gesture",
            buildJsonObject {
                put("action", "swipe_up")
                put("x", 500)
                put("y", 1200)
            }
        )

        assertEquals("手势: swipe_up", summary)
    }

    @Test
    fun theAppLaunchLabelUsesTheRegisteredToolNameAndArgument() {
        val summary = DemoToolSemanticMapper.formatInputSummary(
            "launch_android_app",
            buildJsonObject { put("package_name", "com.example.target") }
        )

        assertEquals("包名: com.example.target", summary)
    }

    @Test
    fun theAppLaunchToolIsNamedAndDescribedByItsRegisteredName() {
        assertEquals(
            "the friendly-name branch keyed screen_launch_app never fired for a real launch",
            "启动应用",
            DemoToolSemanticMapper.friendlyName("launch_android_app")
        )
        val summary = DemoToolSemanticMapper.formatResultSummary(
            com.ugk.pi.android.ToolResult(
                toolCallId = "call-1",
                name = "launch_android_app",
                content = "launched"
            )
        )
        assertEquals(
            "the result branch keyed screen_launch_app never fired either",
            "已发起目标应用启动",
            summary
        )
    }

    @Test
    fun anUnknownGestureFallsBackInsteadOfNamingNothing() {
        val summary = DemoToolSemanticMapper.formatInputSummary(
            "screen_gesture",
            buildJsonObject { put("x", 1); put("y", 2) }
        )

        assertTrue(summary, summary == "手势: 手势")
    }
}
