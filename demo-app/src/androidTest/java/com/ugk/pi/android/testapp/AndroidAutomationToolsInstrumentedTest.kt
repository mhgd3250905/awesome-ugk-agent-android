package com.ugk.pi.android.testapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AndroidAccessibilityServiceState
import com.ugk.pi.android.AndroidAccessibilitySettingsTool
import com.ugk.pi.android.AndroidAccessibilityStatusTool
import com.ugk.pi.android.AndroidAppCatalogTool
import com.ugk.pi.android.AndroidLaunchAppTool
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutomationToolsInstrumentedTest {
    private val baseContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun findsTheHostAppByExactPackageName() = runBlocking {
        val tool = AndroidAppCatalogTool(baseContext)

        val result = tool.execute(
            ToolCall(
                id = "find-host-app",
                name = tool.name,
                input = buildJsonObject {
                    put("query", baseContext.packageName)
                }
            ),
            ToolExecutionContext(sessionId = "automation-tools")
        )

        assertFalse("app lookup failed: $result", result.isError)
        assertTrue(result.content.contains(baseContext.packageName))
        assertTrue(result.content.contains("\"count\":1"))
    }

    @Test
    fun launchesAnAppWithoutAccessibilityUsingAndroidIntent() = runBlocking {
        var startedIntent: Intent? = null
        val tool = AndroidLaunchAppTool(
            context = baseContext,
            launchIntentForPackage = { packageName ->
                Intent(Intent.ACTION_MAIN).setClassName(packageName, "com.example.TargetActivity")
            },
            startActivity = { intent -> startedIntent = Intent(intent) }
        )

        val result = tool.execute(
            ToolCall(
                id = "launch-app",
                name = tool.name,
                input = buildJsonObject {
                    put("package_name", "com.example.target")
                }
            ),
            ToolExecutionContext(sessionId = "automation-tools")
        )

        assertFalse("app launch failed: $result", result.isError)
        assertTrue(result.content.contains("\"launched\":true"))
        assertNotNull(startedIntent)
        assertTrue(startedIntent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun reportsAccessibilityConnectionAndActivePackage() = runBlocking {
        val component = ComponentName(baseContext, AgentAccessibilityService::class.java)
        val tool = AndroidAccessibilityStatusTool(
            context = baseContext,
            serviceComponent = component,
            stateProvider = {
                AndroidAccessibilityServiceState(
                    connected = true,
                    activePackageName = "com.android.chrome"
                )
            }
        )

        val result = tool.execute(
            ToolCall(id = "accessibility-status", name = tool.name, input = buildJsonObject {}),
            ToolExecutionContext(sessionId = "automation-tools")
        )

        assertFalse("accessibility status failed: $result", result.isError)
        assertTrue(result.content.contains("\"connected\":true"))
        assertTrue(result.content.contains("com.android.chrome"))
        assertTrue(result.content.contains("readyForScreenAutomation"))
    }

    @Test
    fun opensAccessibilitySettingsWithoutGrantingPermissionSilently() = runBlocking {
        var openedIntent: Intent? = null
        val tool = AndroidAccessibilitySettingsTool(
            context = baseContext,
            startActivity = { intent -> openedIntent = Intent(intent) }
        )

        val result = tool.execute(
            ToolCall(id = "accessibility-settings", name = tool.name, input = buildJsonObject {}),
            ToolExecutionContext(sessionId = "automation-tools")
        )

        assertFalse("settings launch failed: $result", result.isError)
        assertTrue(result.content.contains("\"userMustEnableManually\":true"))
        assertNotNull(openedIntent)
        assertTrue(openedIntent!!.action == android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
