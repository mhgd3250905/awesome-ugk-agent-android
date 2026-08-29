package com.ugk.pi.terminal.skill

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.terminal.runtime.BashCommandExecutor
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashCommandResult
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BashCommandToolTest {
    @Test
    fun terminalSkillRequiresAnExactBoundConfirmationTarget() {
        val instructions = terminalBashSkill().instructions

        assertTrue(instructions.contains("target.toolName"))
        assertTrue(instructions.contains("target.input"))
        assertTrue(instructions.contains("selectedButtonId only records"))
        assertTrue(instructions.contains("does not authorize a protected Tool by itself"))
    }

    @Test
    fun terminalSkillOmitsConfirmationRoundWhenPolicyDisablesIt() {
        val instructions = terminalBashSkill(
            TerminalToolPolicy(requireUserConfirmation = false)
        ).instructions

        assertTrue(instructions.contains("explicitly disabled the terminal confirmation wrapper"))
        assertFalse(instructions.contains("requires an immediate user confirmation before terminal_bash_execute"))
    }

    @Test
    fun executesBoundedRequestInsideRelativeWorkspace() = runBlocking {
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = RecordingExecutor()
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(requireUserConfirmation = false, defaultTimeoutMillis = 5_000)
        )

        val result = tool.execute(
            ToolCall(
                id = "call-1",
                name = tool.name,
                input = buildJsonObject {
                    put("script", "printf 'hello'")
                    put("workingDirectory", "projects/demo")
                    put("timeoutMillis", 4_000)
                    putJsonObject("environment") {
                        put("LANG", "C.UTF-8")
                    }
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("hello"))
        val request = executor.lastRequest
        assertNotNull(request)
        assertEquals("printf 'hello'", request?.script)
        assertEquals(4_000L, request?.timeoutMillis)
        assertEquals("C.UTF-8", request?.environment?.get("LANG"))
        assertTrue(request?.workingDirectory?.canonicalPath?.endsWith("projects${java.io.File.separator}demo") == true)
        assertEquals(64 * 1024, request?.maxCapturedBytes)
    }

    @Test
    fun rejectsEscapingWorkspaceAndReservedEnvironment() = runBlocking {
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val tool = BashCommandTool(
            executor = RecordingExecutor(),
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )

        val escapedPath = tool.execute(
            ToolCall(
                id = "call-path",
                name = tool.name,
                input = buildJsonObject {
                    put("script", "pwd")
                    put("workingDirectory", "../outside")
                }
            ),
            ToolExecutionContext(sessionId = "session")
        )
        assertTrue(escapedPath.isError)
        assertTrue(escapedPath.content.contains("workingDirectory"))

        listOf(
            "PWD",
            "ANDROID_DATA",
            "ANDROID_ROOT",
            "BASH_ENV",
            "UGK_NATIVE_LIBRARY_DIR",
            "CURL_CA_BUNDLE",
            "LD_PRELOAD",
            "UGK_TERMINAL_SESSION_REPORT_FILE",
        ).forEach { reservedName ->
            val reservedEnvironment = tool.execute(
                ToolCall(
                    id = "call-env-$reservedName",
                    name = tool.name,
                    input = buildJsonObject {
                        put("script", "pwd")
                        putJsonObject("environment") {
                            put(reservedName, "/tmp")
                        }
                    }
                ),
                ToolExecutionContext(sessionId = "session")
            )
            assertTrue(reservedEnvironment.isError)
            assertTrue(reservedEnvironment.content.contains("environment"))
        }

        listOf(
            buildJsonObject { put("LANG", 7) },
            buildJsonObject { put("LANG", "contains\u0000nul") },
        ).forEachIndexed { index, invalidEnvironment ->
            val result = tool.execute(
                ToolCall(
                    id = "call-invalid-env-$index",
                    name = tool.name,
                    input = buildJsonObject {
                        put("script", "pwd")
                        put("environment", invalidEnvironment)
                    }
                ),
                ToolExecutionContext(sessionId = "session")
            )
            assertTrue(result.isError)
            assertEquals("INVALID_ENVIRONMENT", result.metadata?.get("code")?.toString()?.trim('"'))
        }
    }

    @Test
    fun coroutineCancellationInterruptsBlockingExecutor() = runBlocking {
        val interrupted = AtomicBoolean(false)
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = object : BashCommandExecutor {
            override fun execute(request: BashCommandRequest): BashCommandResult {
                try {
                    Thread.sleep(30_000)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                error("executor should have been interrupted")
            }
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )

        val job = launch {
            tool.execute(
                ToolCall(
                    id = "call-cancel",
                    name = tool.name,
                    input = buildJsonObject { put("script", "sleep 30") }
                ),
                ToolExecutionContext(sessionId = "session")
            )
        }
        delay(100)
        job.cancel()
        job.join()

        assertTrue(interrupted.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun explicitCancelInterruptsActiveCallAndReturnsStructuredError() = runBlocking {
        val interrupted = AtomicBoolean(false)
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = object : BashCommandExecutor {
            override fun execute(request: BashCommandRequest): BashCommandResult {
                try {
                    Thread.sleep(30_000)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                error("executor should have been interrupted")
            }
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )

        var result: com.ugk.pi.android.ToolResult? = null
        val job = launch {
            result = tool.execute(
                ToolCall(
                    id = "call-explicit-cancel",
                    name = tool.name,
                    input = buildJsonObject { put("script", "sleep 30") }
                ),
                ToolExecutionContext(sessionId = "session")
            )
        }
        delay(100)
        assertTrue(tool.cancel("call-explicit-cancel"))
        job.join()

        assertTrue(interrupted.get())
        assertTrue(result?.isError == true)
        assertTrue(result?.content?.contains("cancel", ignoreCase = true) == true)
        assertFalse(tool.cancel("call-explicit-cancel"))
    }

    @Test
    fun rejectsDuplicateCallIdBeforeItWaitsForAnExecutionSlot() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = object : BashCommandExecutor {
            override fun execute(request: BashCommandRequest): BashCommandResult {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test executor was not released" }
                return successfulResult(request)
            }
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(
                requireUserConfirmation = false,
                maxConcurrentExecutions = 1
            )
        )
        val call = ToolCall(
            id = "duplicate-call",
            name = tool.name,
            input = buildJsonObject { put("script", "printf first") }
        )

        val first = async {
            tool.execute(call, ToolExecutionContext(sessionId = "session"))
        }
        withTimeout(2_000) {
            while (started.count > 0) delay(10)
        }

        val duplicate = try {
            withTimeout(1_000) {
                tool.execute(call, ToolExecutionContext(sessionId = "session"))
            }
        } finally {
            release.countDown()
        }
        assertTrue(duplicate.isError)
        assertEquals("DUPLICATE_CALL_ID", duplicate.metadata?.get("code")?.toString()?.trim('"'))
        assertFalse(first.await().isError)
    }

    @Test
    fun explicitCancelStopsQueuedCallBeforeExecutorStarts() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executions = AtomicInteger(0)
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = object : BashCommandExecutor {
            override fun execute(request: BashCommandRequest): BashCommandResult {
                val executionNumber = executions.incrementAndGet()
                if (executionNumber == 1) {
                    firstStarted.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "first executor was not released" }
                }
                return successfulResult(request)
            }
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(
                requireUserConfirmation = false,
                maxConcurrentExecutions = 1
            )
        )

        val first = async {
            tool.execute(call("running-call"), ToolExecutionContext(sessionId = "session"))
        }
        withTimeout(2_000) {
            while (firstStarted.count > 0) delay(10)
        }
        val queued = async {
            tool.execute(call("queued-call"), ToolExecutionContext(sessionId = "session"))
        }
        withTimeout(2_000) {
            while (!tool.cancel("queued-call")) delay(10)
        }

        val queuedResult = withTimeout(1_000) { queued.await() }
        assertTrue(queuedResult.isError)
        assertEquals("CANCELLED", queuedResult.metadata?.get("code")?.toString()?.trim('"'))
        assertEquals(1, executions.get())

        releaseFirst.countDown()
        assertFalse(first.await().isError)
    }

    @Test
    fun cancelAllStopsRunningAndQueuedCallsExactlyOnce() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val executions = AtomicInteger(0)
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = object : BashCommandExecutor {
            override fun execute(request: BashCommandRequest): BashCommandResult {
                executions.incrementAndGet()
                firstStarted.countDown()
                try {
                    Thread.sleep(30_000)
                } catch (error: InterruptedException) {
                    interrupted.set(true)
                    throw error
                }
                error("executor should have been interrupted")
            }
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(
                requireUserConfirmation = false,
                maxConcurrentExecutions = 1
            )
        )

        val calls = listOf("running", "queued-a", "queued-b").map { id ->
            async {
                tool.execute(call(id), ToolExecutionContext(sessionId = "session"))
            }
        }
        withTimeout(2_000) {
            while (firstStarted.count > 0) delay(10)
        }
        repeat(10) { yield() }

        assertEquals(3, tool.cancelAll())
        assertEquals(0, tool.cancelAll())
        calls.forEach { invocation ->
            val result = withTimeout(2_000) { invocation.await() }
            assertEquals("CANCELLED", result.metadata?.get("code")?.toString()?.trim('"'))
        }
        assertTrue(interrupted.get())
        assertEquals(1, executions.get())
    }

    @Test
    fun queuesAboveConcurrencyLimitAndReleasesSlotsAndCallIdsAfterCompletion() = runBlocking {
        val firstWaveStarted = CountDownLatch(2)
        val releaseFirstWave = CountDownLatch(1)
        val executionCount = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = object : BashCommandExecutor {
            override fun execute(request: BashCommandRequest): BashCommandResult {
                val running = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { previous -> maxOf(previous, running) }
                val number = executionCount.incrementAndGet()
                try {
                    if (number <= 2) {
                        firstWaveStarted.countDown()
                        check(releaseFirstWave.await(5, TimeUnit.SECONDS)) {
                            "first execution wave was not released"
                        }
                    }
                    return successfulResult(request)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(
                requireUserConfirmation = false,
                maxConcurrentExecutions = 2
            )
        )

        val invocations = (1..4).map { number ->
            async {
                tool.execute(call("limited-$number"), ToolExecutionContext(sessionId = "session"))
            }
        }
        withTimeout(2_000) {
            while (firstWaveStarted.count > 0) delay(10)
        }
        delay(100)
        assertEquals(2, executionCount.get())
        assertEquals(2, maxInFlight.get())

        releaseFirstWave.countDown()
        invocations.forEach { invocation -> assertFalse(invocation.await().isError) }
        assertEquals(4, executionCount.get())
        assertEquals(2, maxInFlight.get())

        val reused = tool.execute(call("limited-1"), ToolExecutionContext(sessionId = "session"))
        assertFalse(reused.isError)
        assertEquals(5, executionCount.get())
    }

    @Test
    fun returnsStdoutStderrExitCodeDurationAndWorkingDirectorySynchronously() = runBlocking {
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = BashCommandExecutor { request ->
            BashCommandResult(
                command = listOf("bash", "-c", request.script),
                executablePath = "/fake/libugk_bash.so",
                exitCode = 7,
                stdout = "standard output",
                stderr = "standard error",
                durationMillis = 12,
                timedOut = false,
                outputTruncated = false,
                workingDirectory = request.workingDirectory!!.absolutePath
            )
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )

        val result = tool.execute(
            call("structured-result"),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(result.isError)
        assertEquals("\"standard output\"", result.metadata?.get("stdout").toString())
        assertEquals("\"standard error\"", result.metadata?.get("stderr").toString())
        assertEquals("7", result.metadata?.get("exitCode").toString())
        assertEquals("false", result.metadata?.get("timedOut").toString())
        assertEquals("12", result.metadata?.get("durationMillis").toString())
        assertEquals(
            "\"${workspace.canonicalPath.replace("\\", "\\\\")}\"",
            result.metadata?.get("workingDirectory").toString()
        )
    }

    @Test
    fun reportsStdoutAndStderrTruncationIndependently() = runBlocking {
        val workspace = Files.createTempDirectory("ugk-terminal-tool-test").toFile()
        val executor = BashCommandExecutor { request ->
            BashCommandResult(
                command = listOf("bash", "-c", request.script),
                executablePath = "/fake/libugk_bash.so",
                exitCode = 0,
                stdout = "bounded stdout",
                stderr = "complete stderr",
                durationMillis = 4,
                timedOut = false,
                outputTruncated = true,
                workingDirectory = request.workingDirectory!!.absolutePath,
                stdoutTruncated = true,
                stderrTruncated = false
            )
        }
        val tool = BashCommandTool(
            executor = executor,
            workspaceRoot = workspace,
            policy = TerminalToolPolicy(requireUserConfirmation = false)
        )

        val result = tool.execute(
            call("independent-truncation"),
            ToolExecutionContext(sessionId = "session")
        )

        assertFalse(result.isError)
        assertEquals("true", result.metadata?.get("outputTruncated").toString())
        assertEquals("true", result.metadata?.get("stdoutTruncated").toString())
        assertEquals("false", result.metadata?.get("stderrTruncated").toString())
    }

    private class RecordingExecutor : BashCommandExecutor {
        var lastRequest: BashCommandRequest? = null

        override fun execute(request: BashCommandRequest): BashCommandResult {
            lastRequest = request
            return BashCommandResult(
                command = listOf("bash", "-c", request.script),
                executablePath = "/fake/libugk_bash.so",
                exitCode = 0,
                stdout = "hello",
                stderr = "",
                durationMillis = 3,
                timedOut = false,
                outputTruncated = false,
                workingDirectory = request.workingDirectory!!.absolutePath
            )
        }
    }

    private companion object {
        fun call(id: String): ToolCall {
            return ToolCall(
                id = id,
                name = "terminal_bash_execute",
                input = buildJsonObject { put("script", "printf ok") }
            )
        }

        fun successfulResult(request: BashCommandRequest): BashCommandResult {
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
