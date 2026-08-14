package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AndroidAppIntentTool
import com.ugk.pi.android.AndroidIntentAgentPlugin
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidIntentIntegrationInstrumentedTest {
    @Test
    fun agentConfirmationThenIntentDispatchesNativeOpenUrl() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val recordingContext = RecordingContext(baseContext)
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(
                    content = "requesting intent confirmation",
                    toolCalls = listOf(confirmationCall())
                ),
                ModelResponse(
                    content = "opening website",
                    toolCalls = listOf(openUrlCall())
                ),
                ModelResponse(content = "opened")
            )
        )

        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(AndroidIntentAgentPlugin(recordingContext, RecordingPresenter("confirm")))
            .build()

        val events = runtime.run(AgentSession("intent-integration"), "打开刚才生成的网站").toList()
        val finishedTools = events
            .filterIsInstance<AgentEvent.ToolFinished>()
            .map { it.result.name }

        assertEquals(
            listOf("show_user_confirmation_dialog", "launch_android_app_intent"),
            finishedTools
        )
        assertEquals("opened", (events.last() as AgentEvent.Completed).content)
        val intentResult = events
            .filterIsInstance<AgentEvent.ToolFinished>()
            .last()
            .result
        assertFalse("native Intent dispatch failed: $intentResult", intentResult.isError)
        assertTrue(intentResult.content.contains("\"launched\":true"))
        assertTrue(intentResult.content.contains("\"action\":\"android.intent.action.VIEW\""))
        assertNotNull(recordingContext.startedIntent)
        assertTrue(provider.requests.first().tools.any { it.name == "launch_android_app_intent" })
        assertTrue(
            provider.requests.first().messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("Do not use terminal_bash_execute") }
        )
    }

    @Test
    fun cancelledConfirmationDoesNotDispatchNativeIntent() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val recordingContext = RecordingContext(baseContext)
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(content = "", toolCalls = listOf(confirmationCall())),
                ModelResponse(content = "", toolCalls = listOf(openUrlCall())),
                ModelResponse(content = "not opened")
            )
        )

        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(AndroidIntentAgentPlugin(recordingContext, RecordingPresenter("cancel")))
            .build()

        val events = runtime.run(AgentSession("intent-cancel"), "打开刚才生成的网站").toList()
        val intentResult = events
            .filterIsInstance<AgentEvent.ToolFinished>()
            .last()
            .result

        assertTrue(intentResult.isError)
        assertTrue(intentResult.content.contains("confirmation", ignoreCase = true))
        assertNull(recordingContext.startedIntent)
    }

    @Test
    fun noHandlerIsReportedWithoutStartingActivity() = runBlocking {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        var started = false
        val tool = AndroidAppIntentTool(
            context = baseContext,
            resolveActivity = { null },
            startActivity = { started = true }
        )

        val result = tool.execute(
            openUrlCall(),
            com.ugk.pi.android.ToolExecutionContext(sessionId = "intent-no-handler")
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("no_handler"))
        assertFalse(started)
    }

    private fun confirmationCall(): ToolCall {
        return ToolCall(
            id = "intent-confirmation",
            name = "show_user_confirmation_dialog",
            input = buildJsonObject {
                put("title", "打开网页")
                put("message", "是否使用浏览器打开刚才生成的网页？")
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
                    put("toolName", "launch_android_app_intent")
                    putJsonObject("input") {
                        put("target", "open_url")
                        putJsonObject("parameters") {
                            put("url", "http://127.0.0.1:8787/weather.html")
                        }
                    }
                }
            }
        )
    }

    private fun openUrlCall(): ToolCall {
        return ToolCall(
            id = "intent-open-url",
            name = "launch_android_app_intent",
            input = buildJsonObject {
                put("target", "open_url")
                putJsonObject("parameters") {
                    put("url", "http://127.0.0.1:8787/weather.html")
                }
            }
        )
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun getApplicationContext(): Context = this

        override fun startActivity(intent: Intent) {
            startedIntent = Intent(intent)
        }
    }

    private class RecordingPresenter(
        private val selectedButtonId: String
    ) : UserConfirmationDialogPresenter {
        override suspend fun showConfirmationDialog(
            request: UserConfirmationDialogRequest
        ): UserConfirmationDialogResult {
            return UserConfirmationDialogResult(selectedButtonId)
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
