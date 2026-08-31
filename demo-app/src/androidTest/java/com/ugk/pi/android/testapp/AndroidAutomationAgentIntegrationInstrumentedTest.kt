package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AndroidAccessibilityServiceState
import com.ugk.pi.android.AndroidAutomationAgentPlugin
import com.ugk.pi.android.ScreenActionRequest
import com.ugk.pi.android.ScreenAutomationBackend
import com.ugk.pi.android.ScreenGlobalActionRequest
import com.ugk.pi.android.ScreenGestureRequest
import com.ugk.pi.android.ScreenKeyRequest
import com.ugk.pi.android.ScreenOperationResult
import com.ugk.pi.android.ScreenReadResult
import com.ugk.pi.android.ScreenVisualAutomationBackend
import com.ugk.pi.android.ScreenVisualCaptureResult
import com.ugk.pi.android.ScreenVisualGestureRequest
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.UserConfirmationRequiredTool
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutomationAgentIntegrationInstrumentedTest {
    @Test
    fun injectedScreenBackendRegistersSdkToolsAndSkill() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plugin = AndroidAutomationAgentPlugin(
            context = context,
            confirmationPresenter = ConfirmingPresenter(),
            accessibilityServiceComponent = ComponentName(
                context,
                AgentAccessibilityService::class.java
            ),
            accessibilityStateProvider = {
                AndroidAccessibilityServiceState(connected = false)
            },
            screenAutomationBackend = EmptyScreenBackend
        )

        val tools = plugin.tools()
        assertTrue(tools.any { it.name == "screen_read_ui_tree" })
        assertTrue(tools.any { it.name == "screen_find_ui_element" })
        assertTrue(tools.any { it.name == "screen_perform_action" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "screen_gesture" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "screen_press_key" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "screen_global_action" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "screen_capture_visual" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "screen_visual_gesture" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "clipboard_read_text" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "clipboard_write_text" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "clipboard_clear" && it is UserConfirmationRequiredTool })
        assertTrue(tools.any { it.name == "screen_read_ui_tree" && it !is UserConfirmationRequiredTool })
        assertTrue(plugin.skills().any { it.id == "android-accessibility-screen-automation" })
        assertTrue(plugin.skills().any { it.id == "android-clipboard-control" })
        assertTrue(plugin.agentInstructions().any { it.contains("snapshot-first") })
        assertTrue(plugin.agentInstructions().any { it.contains("screen_capture_visual") })
    }

    @Test
    fun fullAuthorizationRunsProtectedLaunchWithoutConfirmationRound() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val recordingContext = RecordingContext(baseContext)
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(
                    content = "finding app",
                    toolCalls = listOf(findAppCall(baseContext.packageName))
                ),
                ModelResponse(
                    content = "launching app",
                    toolCalls = listOf(launchAppCall(baseContext.packageName))
                ),
                ModelResponse(content = "app launch requested")
            )
        )
        val plugin = AndroidAutomationAgentPlugin(
            context = recordingContext,
            confirmationPresenter = ConfirmingPresenter(),
            accessibilityServiceComponent = ComponentName(
                baseContext,
                AgentAccessibilityService::class.java
            ),
            accessibilityStateProvider = {
                AndroidAccessibilityServiceState(connected = false)
            },
            shouldBypassConfirmation = { true },
            screenAutomationBackend = EmptyScreenBackend
        )
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(plugin)
            .build()

        val events = runtime.run(
            AgentSession("android-automation-full-authorization"),
            "打开这个应用"
        ).toList()
        val finishedTools = events
            .filterIsInstance<AgentEvent.ToolFinished>()
            .map { it.result.name }

        assertEquals(listOf("find_android_app", "launch_android_app"), finishedTools)
        assertTrue(provider.requests.all { request ->
            request.tools.none { it.name == "show_user_confirmation_dialog" }
        })
        assertTrue(plugin.skills().all { skill ->
            skill.methods.none { it.toolName == "show_user_confirmation_dialog" }
        })
        assertTrue(plugin.agentInstructions().any {
            it.contains("Do not call show_user_confirmation_dialog")
        })
        assertFalse(events.any { it is AgentEvent.Failed })
    }

    @Test
    fun agentResolvesAppRequestsConfirmationAndLaunchesByPackage() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val recordingContext = RecordingContext(baseContext)
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(
                    content = "finding app",
                    toolCalls = listOf(findAppCall(baseContext.packageName))
                ),
                ModelResponse(
                    content = "asking permission",
                    toolCalls = listOf(confirmationCall(baseContext.packageName))
                ),
                ModelResponse(
                    content = "launching app",
                    toolCalls = listOf(launchAppCall(baseContext.packageName))
                ),
                ModelResponse(content = "app launch requested")
            )
        )
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(
                AndroidAutomationAgentPlugin(
                    context = recordingContext,
                    confirmationPresenter = ConfirmingPresenter(),
                    accessibilityServiceComponent = ComponentName(
                        baseContext,
                        AgentAccessibilityService::class.java
                    ),
                    accessibilityStateProvider = {
                        AndroidAccessibilityServiceState(connected = false)
                    }
                )
            )
            .build()

        val events = runtime.run(
            AgentSession("android-automation-integration"),
            "打开这个应用"
        ).toList()
        val finishedTools = events
            .filterIsInstance<AgentEvent.ToolFinished>()
            .map { it.result.name }

        assertEquals(
            listOf(
                "find_android_app",
                "show_user_confirmation_dialog",
                "launch_android_app"
            ),
            finishedTools
        )
        assertEquals("app launch requested", (events.last() as AgentEvent.Completed).content)
        assertNotNull(recordingContext.startedIntent)
        assertEquals(baseContext.packageName, recordingContext.startedIntent?.component?.packageName)
        assertTrue(
            provider.requests.first().tools.any { it.name == "find_android_app" } &&
                provider.requests.first().tools.any { it.name == "launch_android_app" } &&
                provider.requests.first().tools.any { it.name == "get_android_accessibility_status" }
        )
        assertTrue(
            provider.requests.first().messages
                .filterIsInstance<com.ugk.pi.android.AgentMessage.System>()
                .any { it.content.contains("normal Android host application") }
        )
        assertFalse(events.any { it is AgentEvent.Failed })
    }

    private fun findAppCall(packageName: String): ToolCall {
        return ToolCall(
            id = "find-app",
            name = "find_android_app",
            input = buildJsonObject { put("query", packageName) }
        )
    }

    private fun confirmationCall(packageName: String): ToolCall {
        return ToolCall(
            id = "launch-confirmation",
            name = "show_user_confirmation_dialog",
            input = buildJsonObject {
                put("title", "打开应用")
                put("message", "是否打开这个应用？")
                putJsonArray("buttons") {
                    add(buildJsonObject {
                        put("id", "confirm")
                        put("label", "继续")
                    })
                    add(buildJsonObject {
                        put("id", "cancel")
                        put("label", "取消")
                    })
                }
                putJsonObject("target") {
                    put("toolName", "launch_android_app")
                    putJsonObject("input") {
                        put("package_name", packageName)
                    }
                }
            }
        )
    }

    private fun launchAppCall(packageName: String): ToolCall {
        return ToolCall(
            id = "launch-app",
            name = "launch_android_app",
            input = buildJsonObject { put("package_name", packageName) }
        )
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun getApplicationContext(): Context = this

        override fun startActivity(intent: Intent) {
            startedIntent = Intent(intent)
        }
    }

    private class ConfirmingPresenter : UserConfirmationDialogPresenter {
        override suspend fun showConfirmationDialog(
            request: UserConfirmationDialogRequest
        ): UserConfirmationDialogResult {
            return UserConfirmationDialogResult("confirm")
        }
    }

    private class ScriptedProvider(
        private val responses: List<ModelResponse>
    ) : LLMProvider {
        val requests = mutableListOf<ModelRequest>()
        private var nextResponse = 0

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return responses[nextResponse++]
        }
    }

    private object EmptyScreenBackend : ScreenAutomationBackend, ScreenVisualAutomationBackend {
        override fun readUiTree(sessionId: String, maxDepth: Int, maxNodes: Int) = ScreenReadResult()

        override suspend fun performAction(sessionId: String, request: ScreenActionRequest) =
            ScreenOperationResult(false, "EMPTY")

        override suspend fun performGesture(request: ScreenGestureRequest) =
            ScreenOperationResult(false, "EMPTY")

        override suspend fun pressKey(request: ScreenKeyRequest) =
            ScreenOperationResult(false, "EMPTY")

        override fun performGlobalAction(request: ScreenGlobalActionRequest) =
            ScreenOperationResult(false, "EMPTY")

        override suspend fun captureVisualObservation(sessionId: String) =
            ScreenVisualCaptureResult()

        override suspend fun performVisualGesture(
            sessionId: String,
            request: ScreenVisualGestureRequest
        ) = ScreenOperationResult(false, "EMPTY")
    }
}
