package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.terminal.runtime.BashCommandExecutor
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashCommandResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `terminal_bash_execute` read `workingDirectory` through a helper that returned null
 * for a structured value, and null meant "run in the workspace root". The confirmation
 * ticket is bound to the model's declared input, so a call that wrote
 * `{"workingDirectory": {"path": "sites/demo"}}` was approved against that value and
 * then executed somewhere else - the divergence this file's own instruction text forbids
 * ("the target binding must cover the complete input, including script,
 * workingDirectory, timeoutMillis, and environment values when present"), and the same
 * reason the existing NUL screen is there.
 *
 * Both directions are pinned: the unusable declaration must be refused before anything
 * runs, and an endpoint's JSON null must keep taking the workspace root, because a guard
 * that rejects a legal call gets removed.
 */
class BashCommandToolArgumentShapeTest {
    private val workspaces = mutableListOf<File>()

    @After
    fun cleanUpWorkspaces() {
        workspaces.forEach { it.deleteRecursively() }
    }

    private class CapturingExecutor : BashCommandExecutor {
        var reached = false
        var request: BashCommandRequest? = null

        override fun execute(request: BashCommandRequest): BashCommandResult {
            reached = true
            this.request = request
            return BashCommandResult(
                command = listOf("bash", "-c", request.script),
                executablePath = "/system/bin/bash",
                exitCode = 0,
                stdout = "ok",
                stderr = "",
                durationMillis = 1L,
                timedOut = false,
                outputTruncated = false,
                workingDirectory = request.workingDirectory?.path ?: "",
                stdoutTruncated = false,
                stderrTruncated = false
            )
        }
    }

    private fun tool(executor: BashCommandExecutor): BashCommandTool {
        val root = Files.createTempDirectory("ugk-terminal-argument-shape").toFile()
        workspaces += root
        return BashCommandTool(
            executor = executor,
            workspaceRoot = root,
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )
    }

    @Test
    fun anObjectShapedWorkingDirectoryIsRefusedBeforeAnyProcessStarts() {
        val executor = CapturingExecutor()
        val result = runBlocking {
            tool(executor).execute(
                ToolCall(
                    id = "call-object-cwd",
                    name = "terminal_bash_execute",
                    input = buildJsonObject {
                        put("script", "pwd")
                        putJsonObject("workingDirectory") { put("path", "sites/demo") }
                    }
                ),
                ToolExecutionContext(sessionId = "session")
            )
        }

        assertTrue(result.isError)
        assertFalse("nothing may run at a path nobody confirmed", executor.reached)
        assertTrue(
            "the refusal must name the argument and say what arrived: " + result.content,
            result.content.contains("workingDirectory") && result.content.contains("object")
        )
        assertFalse(
            "the declared value must not be pasted into the refusal: " + result.content,
            result.content.contains("sites/demo")
        )
    }

    @Test
    fun anArrayShapedWorkingDirectoryIsRefusedToo() {
        val executor = CapturingExecutor()
        val result = runBlocking {
            tool(executor).execute(
                ToolCall(
                    id = "call-array-cwd",
                    name = "terminal_bash_execute",
                    input = JsonObjectWith("workingDirectory", JsonArray(listOf(JsonPrimitive("sites"))))
                ),
                ToolExecutionContext(sessionId = "session")
            )
        }
        assertTrue(result.isError)
        assertFalse(executor.reached)
        assertTrue(result.content, result.content.contains("array"))
    }

    @Test
    fun aJsonNullWorkingDirectoryStillMeansTheWorkspaceRootAndStillRuns() {
        val executor = CapturingExecutor()
        val result = runBlocking {
            tool(executor).execute(
                ToolCall(
                    id = "call-null-cwd",
                    name = "terminal_bash_execute",
                    input = buildJsonObject {
                        put("script", "pwd")
                        put("workingDirectory", JsonNull)
                    }
                ),
                ToolExecutionContext(sessionId = "session")
            )
        }

        assertFalse("an endpoint's unfilled field must not become an error: " + result.content, result.isError)
        assertTrue("and the command must run", executor.reached)
        assertEquals(
            "at the workspace root",
            workspaces.last().canonicalFile.path,
            executor.request?.workingDirectory?.canonicalFile?.path
        )
    }

    /**
     * The object/array screen alone was not enough: a bare `true` or `20` is a JSON
     * primitive, and the first version of this guard let it through, so the runtime
     * created a directory named "true" under the workspace and ran the confirmed command
     * there. A path argument has to arrive as a string.
     */
    @Test
    fun aNonStringPrimitiveWorkingDirectoryIsRefusedAndCreatesNoDirectory() {
        listOf(JsonPrimitive(true), JsonPrimitive(20), JsonPrimitive(0.5)).forEach { declared ->
            val executor = CapturingExecutor()
            val root = Files.createTempDirectory("ugk-terminal-primitive-cwd").toFile()
            workspaces += root
            val result = runBlocking {
                BashCommandTool(
                    executor = executor,
                    workspaceRoot = root,
                    policy = TerminalToolPolicy(requireUserConfirmation = false)
                ).execute(
                    ToolCall(
                        id = "call-primitive-cwd",
                        name = "terminal_bash_execute",
                        input = kotlinx.serialization.json.JsonObject(
                            mapOf("script" to JsonPrimitive("pwd"), "workingDirectory" to declared)
                        )
                    ),
                    ToolExecutionContext(sessionId = "session")
                )
            }
            assertTrue("$declared must not become a directory name: " + result.content, result.isError)
            assertFalse("$declared must not reach the process", executor.reached)
            assertFalse(
                "and nothing may be created for it",
                File(root, "true").exists() || File(root, "20").exists()
            )
        }
    }

    private fun JsonObjectWith(key: String, value: kotlinx.serialization.json.JsonElement) =
        kotlinx.serialization.json.JsonObject(mapOf("script" to JsonPrimitive("pwd"), key to value))
}
