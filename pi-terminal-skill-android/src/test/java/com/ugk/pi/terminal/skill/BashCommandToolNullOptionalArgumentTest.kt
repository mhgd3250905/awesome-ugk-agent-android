package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.terminal.runtime.BashCommandExecutor
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashCommandResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal_bash_execute` is the most-used Tool in the SDK, and a Java/Pojo gateway
 * emits `"timeoutMillis": null` / `"environment": null` for the optionals it does not
 * fill instead of leaving them out. `JsonNull` **is** a `JsonPrimitive`, so the
 * `null` arm of the timeout `when` was unreachable and the value fell into the
 * wrong-type branch: a call that should have taken the policy's default timeout was
 * refused with `INVALID_TIMEOUT`, and an unfilled `environment` with
 * `INVALID_ENVIRONMENT`.
 *
 * Round 11 established this rule on the providers' response side (round 7) and then
 * on tool arguments in four modules; the terminal module was missed by that sweep and
 * this file is the correction. A declared-but-unusable value is still refused: the
 * rule is about absence, not permission to accept anything.
 */
class BashCommandToolNullOptionalArgumentTest {
    private val createdWorkspaces = mutableListOf<File>()

    private fun workspace(): File =
        Files.createTempDirectory("ugk-terminal-null-optionals").toFile().also { createdWorkspaces += it }

    @After
    fun cleanUpWorkspaces() {
        val failedPaths = createdWorkspaces.mapNotNull { if (!it.deleteRecursively()) it.absolutePath else null }
        createdWorkspaces.clear()
        assertEquals(emptyList<String>(), failedPaths)
    }

    private fun tool(executor: RecordingExecutor): BashCommandTool = BashCommandTool(
        executor = executor,
        workspaceRoot = workspace(),
        policy = TerminalToolPolicy(requireUserConfirmation = false, defaultTimeoutMillis = 5_000)
    )

    @Test
    fun nullTimeoutMillisTakesThePolicyDefaultInsteadOfBeingRefused() = runBlocking {
        val executor = RecordingExecutor()
        val result = tool(executor).execute(
            ToolCall(
                id = "null-timeout",
                name = "terminal_bash_execute",
                input = buildJsonObject {
                    put("script", "printf ok")
                    put("timeoutMillis", JsonNull)
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse("a null timeout must not refuse the call: ${result.content}", result.isError)
        assertNotNull(executor.lastRequest)
        assertEquals(5_000L, executor.lastRequest?.timeoutMillis)
    }

    @Test
    fun nullEnvironmentRunsUnderTheDefaultEmptyEnvironment() = runBlocking {
        val executor = RecordingExecutor()
        val result = tool(executor).execute(
            ToolCall(
                id = "null-environment",
                name = "terminal_bash_execute",
                input = buildJsonObject {
                    put("script", "printf ok")
                    put("environment", JsonNull)
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse("a null environment must not refuse the call: ${result.content}", result.isError)
        assertEquals(emptyMap<String, String>(), executor.lastRequest?.environment)
    }

    /** The control: the documented defaults were already reachable when the key was absent. */
    @Test
    fun absentTimeoutAndEnvironmentTakeTheSameDefaults() = runBlocking {
        val executor = RecordingExecutor()
        val result = tool(executor).execute(
            ToolCall(
                id = "absent",
                name = "terminal_bash_execute",
                input = buildJsonObject { put("script", "printf ok") }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse(result.isError)
        assertEquals(5_000L, executor.lastRequest?.timeoutMillis)
        assertEquals(emptyMap<String, String>(), executor.lastRequest?.environment)
    }

    /** Declared-but-unusable stays refused; the null rule is not a wildcard. */
    @Test
    fun declaredStringTimeoutMillisIsStillRefused() = runBlocking {
        val executor = RecordingExecutor()
        val result = tool(executor).execute(
            ToolCall(
                id = "string-timeout",
                name = "terminal_bash_execute",
                input = buildJsonObject {
                    put("script", "printf ok")
                    put("timeoutMillis", "fast")
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue("a non-numeric timeout must be refused: ${result.content}", result.isError)
        assertEquals("INVALID_TIMEOUT", errorCode(result))
        assertNull(executor.lastRequest)
    }

    @Test
    fun declaredObjectTimeoutMillisIsStillRefused() = runBlocking {
        val executor = RecordingExecutor()
        val result = tool(executor).execute(
            ToolCall(
                id = "object-timeout",
                name = "terminal_bash_execute",
                input = buildJsonObject {
                    put("script", "printf ok")
                    putJsonObject("timeoutMillis") { put("value", 5_000) }
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(result.isError)
        assertEquals("INVALID_TIMEOUT", errorCode(result))
        assertNull(executor.lastRequest)
    }

    @Test
    fun declaredArrayEnvironmentIsStillRefused() = runBlocking {
        val executor = RecordingExecutor()
        val result = tool(executor).execute(
            ToolCall(
                id = "array-environment",
                name = "terminal_bash_execute",
                input = buildJsonObject {
                    put("script", "printf ok")
                    putJsonArray("environment") { add(buildJsonObject { put("LANG", "C") }) }
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(result.isError)
        assertEquals("INVALID_ENVIRONMENT", errorCode(result))
        assertNull(executor.lastRequest)
    }

    private fun errorCode(result: ToolResult): String? = result.metadata?.get("code")?.toString()?.trim('"')

    private class RecordingExecutor : BashCommandExecutor {
        var lastRequest: BashCommandRequest? = null

        override fun execute(request: BashCommandRequest): BashCommandResult {
            lastRequest = request
            return BashCommandResult(
                command = listOf("bash", "-c", request.script),
                executablePath = "/fake/libugk_bash.so",
                exitCode = 0,
                stdout = "ok",
                stderr = "",
                durationMillis = 3,
                timedOut = false,
                outputTruncated = false,
                workingDirectory = request.workingDirectory!!.absolutePath
            )
        }
    }
}
