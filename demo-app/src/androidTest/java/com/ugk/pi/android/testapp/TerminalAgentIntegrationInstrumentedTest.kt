package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.UserConfirmationDialogPresenter
import com.ugk.pi.android.UserConfirmationDialogRequest
import com.ugk.pi.android.UserConfirmationDialogResult
import com.ugk.pi.android.UserConfirmationDialogTool
import com.ugk.pi.android.UserConfirmationRequiredTool
import com.ugk.pi.terminal.skill.TerminalAgentPlugin
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalAgentIntegrationInstrumentedTest {
    @Test
    fun confirmationThenTerminalExecutesRealRuntime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plugin = TerminalAgentPlugin(context)
        val terminalTool = plugin.tools().single { it.name == "terminal_bash_execute" }
        assertEquals(
            setOf(
                "terminal_bash_execute",
                "local_http_server_start",
                "local_http_server_status",
                "local_http_server_stop"
            ),
            plugin.tools().map { it.name }.toSet()
        )
        assertTrue(plugin.tools().single { it.name == "terminal_bash_execute" } is UserConfirmationRequiredTool)
        assertTrue(plugin.tools().single { it.name == "local_http_server_start" } is UserConfirmationRequiredTool)
        assertTrue(plugin.tools().single { it.name == "local_http_server_stop" } is UserConfirmationRequiredTool)
        assertFalse(plugin.tools().single { it.name == "local_http_server_status" } is UserConfirmationRequiredTool)
        assertTrue(plugin.skills().any { it.id == "local-http-server" })
        val confirmationTool = UserConfirmationDialogTool(RecordingPresenter("confirm"))

        val confirmation = confirmationTool.execute(
            confirmationCall("confirm"),
            ToolExecutionContext(sessionId = "demo-integration")
        )
        assertFalse("confirmation tool failed: $confirmation", confirmation.isError)

        val result = terminalTool.execute(
            ToolCall(
                id = "terminal-confirmed",
                name = terminalTool.name,
                input = buildJsonObject {
                    put(
                        "script",
                        "printf 'confirmed\\n'; " +
                            "python -c \"import ssl, sqlite3, hashlib; print('python=ok')\""
                    )
                }
            ),
            ToolExecutionContext(
                sessionId = "demo-integration",
                priorMessages = listOf(AgentMessage.Tool(confirmation))
            )
        )

        assertFalse("terminal tool failed: $result", result.isError)
        assertTrue(result.content.contains("\"exitCode\":0"))
        assertTrue(result.content.contains("confirmed"))
        assertTrue(result.content.contains("python=ok"))
    }

    @Test
    fun cancellationBlocksTerminalWithoutExecutingScript() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plugin = TerminalAgentPlugin(context)
        val terminalTool = plugin.tools().single { it.name == "terminal_bash_execute" }
        val confirmationTool = UserConfirmationDialogTool(RecordingPresenter("cancel"))
        val marker = File(context.filesDir, "terminal-cancel-must-not-run.txt")
        marker.delete()

        val confirmation = confirmationTool.execute(
            confirmationCall("cancel"),
            ToolExecutionContext(sessionId = "demo-integration")
        )
        val result = terminalTool.execute(
            ToolCall(
                id = "terminal-cancelled",
                name = terminalTool.name,
                input = buildJsonObject {
                    put("script", "printf executed > '${marker.absolutePath}'")
                }
            ),
            ToolExecutionContext(
                sessionId = "demo-integration",
                priorMessages = listOf(AgentMessage.Tool(confirmation))
            )
        )

        assertTrue("cancelled terminal call was not blocked", result.isError)
        assertTrue(result.content.contains("confirmation", ignoreCase = true))
        assertFalse("cancelled terminal script executed", marker.exists())
    }

    @Test
    fun agentRuntimeLoopsThroughConfirmationThenTerminalWithFakeProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val presenter = RecordingPresenter("confirm")
        val confirmationPlugin = object : AgentCapabilityPlugin {
            override val id = "test-user-confirmation"
            override fun tools() = listOf(UserConfirmationDialogTool(presenter))
            override fun skills() = emptyList<AndroidSkill>()
        }
        val terminalPlugin = TerminalAgentPlugin(context)
        val provider = ScriptedProvider(
            listOf(
                ModelResponse(
                    content = "requesting confirmation",
                    toolCalls = listOf(confirmationCall("agent-confirm"))
                ),
                ModelResponse(
                    content = "executing terminal",
                    toolCalls = listOf(terminalCall())
                ),
                ModelResponse(content = "done")
            )
        )
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(confirmationPlugin)
            .register(terminalPlugin)
            .build()

        val events = runtime.run(AgentSession("agent-integration"), "run a terminal command").toList()
        val finishedTools = events
            .filterIsInstance<AgentEvent.ToolFinished>()
            .map { it.result.name }

        assertEquals(listOf("show_user_confirmation_dialog", "terminal_bash_execute"), finishedTools)
        assertEquals("done", (events.last() as AgentEvent.Completed).content)
        assertTrue(provider.requests.first().tools.any { it.name == "terminal_bash_execute" })
        assertTrue(provider.requests.first().tools.any { it.name == "show_user_confirmation_dialog" })
        assertTrue(
            "SDK runtime AGENTS.md was not injected",
            provider.requests.first().messages
                .filterIsInstance<AgentMessage.System>()
                .any { it.content.contains("Never invoke `bash`") }
        )
    }

    private fun confirmationCall(selectedButtonId: String): ToolCall {
        return ToolCall(
            id = "confirmation-$selectedButtonId",
            name = "show_user_confirmation_dialog",
            input = buildJsonObject {
                put("title", "Terminal permission")
                put("message", "Allow the agent to execute this command?")
                putJsonArray("buttons") {
                    add(buildJsonObject {
                        put("id", "confirm")
                        put("label", "Allow")
                    })
                    add(buildJsonObject {
                        put("id", "cancel")
                        put("label", "Cancel")
                    })
                }
            }
        )
    }

    private fun terminalCall(): ToolCall {
        return ToolCall(
            id = "terminal-agent-call",
            name = "terminal_bash_execute",
            input = buildJsonObject {
                put(
                    "script",
                    "printf 'agent-confirmed\\n'; " +
                        "python -c \"import ssl, sqlite3, hashlib; print('agent-python=ok')\""
                )
            }
        )
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
