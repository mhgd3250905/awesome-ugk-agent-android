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
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ToolCall
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
}
