package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.system.skill.AndroidLaunchAppTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit probe: opens only the installed Play Store, never installs/updates anything. */
@RunWith(AndroidJUnit4::class)
class AppLaunchOverlayProbe {
    @Test fun backgroundLaunchRetainsVisibleOverlay() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("launchOverlayProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withTimeout(30_000) { while (AgentAccessibilityService.instance == null) delay(100) }
        lateinit var window: AgentFloatingWindow
        try {
            withContext(Dispatchers.Main) { window = AgentFloatingWindow(context); window.show(); assertTrue(window.isShowing()) }
            delay(500)
            val tool = screenOverlayDecorator(AgentToolDecorator { it },
                { error("Launch must not detach the visible window") }, { error("No detachment to restore") })
                .decorate(AndroidLaunchAppTool(context))
            val result = tool.execute(ToolCall("launch-probe", tool.name, buildJsonObject { put("package_name", "com.android.vending") }),
                ToolExecutionContext("launch-probe"))
            assertFalse(result.isError)
            withTimeout(5000) {
                while (true) {
                    val root = AgentAccessibilityService.instance?.rootInActiveWindow
                    val foreground = try { root?.packageName?.toString() } finally { root?.recycle() }
                    if (foreground == "com.android.vending") break
                    delay(150)
                }
            }
            withContext(Dispatchers.Main) { assertTrue("Visible overlay must remain attached across app launch", window.isShowing()) }
        } finally { withContext(Dispatchers.Main) { runCatching { window.hide() } } }
    }
}
