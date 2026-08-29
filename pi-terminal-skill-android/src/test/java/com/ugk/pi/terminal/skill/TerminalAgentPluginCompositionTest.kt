package com.ugk.pi.terminal.skill

import android.content.Context
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.AgentToolInterlock
import com.ugk.pi.android.AgentToolInterlockDecision
import com.ugk.pi.android.AgentToolInterlockErrorCodes
import com.ugk.pi.android.AgentToolInterlockPolicy
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.android.UserConfirmationInputFingerprint
import com.ugk.pi.android.UserConfirmationTicket
import com.ugk.pi.terminal.runtime.BashCommandExecutor
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashCommandResult
import com.ugk.pi.terminal.runtime.LocalHttpServerController
import com.ugk.pi.terminal.runtime.LocalHttpServerRequest
import com.ugk.pi.terminal.runtime.LocalHttpServerStatus
import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAgentPluginCompositionTest {
    @Test
    fun publicConstructorsKeepRuntimeCollaboratorsOffTheJvmSurface() {
        val publicConstructors = TerminalAgentPlugin::class.java.constructors.toList()

        assertTrue(publicConstructors.isNotEmpty())
        assertTrue(
            publicConstructors.all { constructor ->
                constructor.parameterTypes.firstOrNull() == Context::class.java
            }
        )
        assertTrue(
            publicConstructors.any { constructor ->
                constructor.parameterTypes.contains(AgentToolDecorator::class.java)
            }
        )
        assertFalse(
            publicConstructors.any { constructor ->
                constructor.parameterTypes.any { parameterType ->
                    parameterType == BashCommandTool::class.java ||
                        parameterType == LocalHttpServerController::class.java
                }
            }
        )
    }

    @Test
    fun normalAuthorizationKeepsConfirmationBeforeBashExecution() = runBlocking {
        val executor = RecordingExecutor()
        val plugin = plugin(executor)
        val tool = terminalTool(plugin)
        val call = call(tool)

        val withoutConfirmation = tool.execute(
            call,
            ToolExecutionContext(sessionId = SESSION)
        )
        assertTrue(withoutConfirmation.isError)
        assertTrue(withoutConfirmation.content.contains("show_user_confirmation_dialog"))
        assertEquals(0, executor.calls)

        val withConfirmation = tool.execute(
            call.copy(id = "confirmed-call"),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(tool.name, call.input))
                )
            )
        )
        assertFalse(withConfirmation.isError)
        assertEquals(1, executor.calls)
    }

    @Test
    fun fullAuthorizationSkipsConfirmationAndExecutesTheComposedTool() = runBlocking {
        val executor = RecordingExecutor()
        val plugin = plugin(executor, shouldBypassConfirmation = { true })
        val tool = terminalTool(plugin)

        val result = tool.execute(
            call(tool),
            ToolExecutionContext(sessionId = SESSION)
        )

        assertFalse(result.isError)
        assertFalse(result.content.contains("show_user_confirmation_dialog"))
        assertEquals(1, executor.calls)
    }

    @Test
    fun outerGenericInterlockBlocksBeforeConfirmationAndBash() = runBlocking {
        val executor = RecordingExecutor()
        val plugin = plugin(
            executor,
            toolDecorator = AgentToolDecorator { tool ->
                AgentToolInterlock(
                    delegate = tool,
                    policy = AgentToolInterlockPolicy { _, _, _ ->
                        AgentToolInterlockDecision("exclusive-capability")
                    }
                )
            }
        )
        val tool = terminalTool(plugin)

        val result = tool.execute(
            call(tool),
            ToolExecutionContext(sessionId = SESSION)
        )

        assertTrue(result.isError)
        assertEquals(
            AgentToolInterlockErrorCodes.BLOCKED,
            result.metadata["code"]?.toString()?.trim('"')
        )
        assertFalse(result.content.contains("show_user_confirmation_dialog"))
        assertEquals(0, executor.calls)
    }

    private fun plugin(
        executor: RecordingExecutor,
        shouldBypassConfirmation: () -> Boolean = { false },
        toolDecorator: AgentToolDecorator = AgentToolDecorator.Identity
    ): TerminalAgentPlugin {
        val terminalTool = BashCommandTool(
            executor = executor,
            workspaceRoot = Files.createTempDirectory("ugk-terminal-plugin-test").toFile(),
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )
        val componentsClass = TerminalAgentPlugin::class.java.declaredClasses.single {
            it.simpleName == "Components"
        }
        val componentsConstructor = componentsClass.declaredConstructors.single {
            it.parameterTypes.size == 3
        }.apply {
            isAccessible = true
        }
        val components = componentsConstructor.newInstance(
            "Terminal composition test instructions",
            terminalTool,
            EmptyLocalHttpServerController
        )

        val primaryConstructor = TerminalAgentPlugin::class.java.declaredConstructors.single {
            it.parameterTypes.size == 4 && it.parameterTypes.last() == componentsClass
        }.apply {
            assertTrue(Modifier.isPrivate(modifiers))
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        return primaryConstructor.newInstance(
            TerminalToolPolicy(),
            shouldBypassConfirmation,
            toolDecorator,
            components
        ) as TerminalAgentPlugin
    }

    private fun terminalTool(plugin: TerminalAgentPlugin): AgentTool =
        plugin.tools().single { it.name == "terminal_bash_execute" }

    private fun call(tool: AgentTool): ToolCall = ToolCall(
        id = "terminal-call",
        name = tool.name,
        input = buildJsonObject { put("script", "printf ok") }
    )

    private fun confirmationResult(toolName: String, input: JsonObject): ToolResult {
        val issuedAt = System.currentTimeMillis()
        val ticket = UserConfirmationTicket(
            version = UserConfirmationTicket.CURRENT_VERSION,
            sessionId = SESSION,
            toolName = toolName,
            inputFingerprint = UserConfirmationInputFingerprint.sha256(input),
            nonce = NONCE,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = issuedAt + UserConfirmationTicket.DEFAULT_TTL_MILLIS
        )
        return ToolResult(
            toolCallId = "confirmation",
            name = "show_user_confirmation_dialog",
            content = buildJsonObject {
                put("selectedButtonId", "confirm")
                put("ticket", ticket.toJsonObject())
            }.toString()
        )
    }

    private class RecordingExecutor : BashCommandExecutor {
        var calls = 0

        override fun execute(request: BashCommandRequest): BashCommandResult {
            calls++
            return BashCommandResult(
                command = listOf("bash", "-c", request.script),
                executablePath = "/fake/libugk_bash.so",
                exitCode = 0,
                stdout = "ok",
                stderr = "",
                durationMillis = 1,
                timedOut = false,
                outputTruncated = false,
                workingDirectory = request.workingDirectory!!.absolutePath
            )
        }
    }

    private object EmptyLocalHttpServerController : LocalHttpServerController {
        override fun start(request: LocalHttpServerRequest): LocalHttpServerStatus =
            LocalHttpServerStatus.notFound(request.port)

        override fun status(port: Int?): List<LocalHttpServerStatus> = emptyList()

        override fun stop(port: Int): LocalHttpServerStatus =
            LocalHttpServerStatus.notFound(port)

        override fun stopAll(): Int = 0
    }

    private companion object {
        const val SESSION = "terminal-plugin-session"
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
