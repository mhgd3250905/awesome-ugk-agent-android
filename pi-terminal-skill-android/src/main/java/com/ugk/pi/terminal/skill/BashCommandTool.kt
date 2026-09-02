package com.ugk.pi.terminal.skill

import android.content.Context
import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentConfirmationPolicy
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillMethod
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import com.ugk.pi.android.UserConfirmationRequiredTool
import com.ugk.pi.terminal.runtime.BashCommandExecutor
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashCommandResult
import com.ugk.pi.terminal.runtime.BashRuntime
import com.ugk.pi.terminal.runtime.LocalHttpServerController
import com.ugk.pi.terminal.runtime.LocalHttpServerManager
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Agent-facing policy for a Bash terminal. It constrains resource use and
 * makes the dangerous nature of arbitrary shell execution opt-in at plugin
 * registration time.
 */
data class TerminalToolPolicy(
    val requireUserConfirmation: Boolean = true,
    val defaultTimeoutMillis: Long = BashRuntime.DEFAULT_TIMEOUT_MILLIS,
    val maxTimeoutMillis: Long = 60_000L,
    val maxCapturedBytes: Int = BashRuntime.DEFAULT_MAX_CAPTURED_BYTES,
    val maxConcurrentExecutions: Int = 2
) {
    init {
        require(defaultTimeoutMillis in 1..maxTimeoutMillis) {
            "defaultTimeoutMillis must be between 1 and maxTimeoutMillis"
        }
        require(maxTimeoutMillis <= BashRuntime.MAX_TIMEOUT_MILLIS) {
            "maxTimeoutMillis must not exceed ${BashRuntime.MAX_TIMEOUT_MILLIS}"
        }
        require(maxCapturedBytes in 1..BashRuntime.MAX_CAPTURED_BYTES) {
            "maxCapturedBytes must be between 1 and ${BashRuntime.MAX_CAPTURED_BYTES}"
        }
        require(maxConcurrentExecutions in 1..MAX_CONCURRENT_EXECUTIONS) {
            "maxConcurrentExecutions must be between 1 and $MAX_CONCURRENT_EXECUTIONS"
        }
    }

    companion object {
        const val MAX_CONCURRENT_EXECUTIONS = 4
    }
}

/** Registers Bash with the Agent SDK without adding a terminal UI. */
class TerminalAgentPlugin private constructor(
    private val policy: TerminalToolPolicy = TerminalToolPolicy(),
    private val shouldBypassConfirmation: () -> Boolean = { false },
    private val toolDecorator: AgentToolDecorator = AgentToolDecorator.Identity,
    private val components: Components
) : AgentCapabilityPlugin {
    constructor(
        context: Context,
        policy: TerminalToolPolicy = TerminalToolPolicy(),
        shouldBypassConfirmation: () -> Boolean = { false },
        toolDecorator: AgentToolDecorator = AgentToolDecorator.Identity
    ) : this(
        policy = policy,
        shouldBypassConfirmation = shouldBypassConfirmation,
        toolDecorator = toolDecorator,
        components = createComponents(context, policy)
    )

    private val runtimeAgentInstructions = components.runtimeAgentInstructions
    private val localHttpServerController = components.localHttpServerController
    private val terminalTool = components.terminalTool
    private val confirmationWrappedTerminalTool: AgentTool = if (policy.requireUserConfirmation) {
        UserConfirmationRequiredTool(
            terminalTool,
            shouldBypassConfirmation = shouldBypassConfirmation
        )
    } else {
        terminalTool
    }
    private val exposedTool: AgentTool = toolDecorator.decorate(confirmationWrappedTerminalTool)
    private val localHttpServerStartTool: AgentTool = toolDecorator.decorate(
        UserConfirmationRequiredTool(
            LocalHttpServerStartTool(localHttpServerController),
            shouldBypassConfirmation = shouldBypassConfirmation
        )
    )
    private val localHttpServerStopTool: AgentTool = toolDecorator.decorate(
        UserConfirmationRequiredTool(
            LocalHttpServerStopTool(localHttpServerController),
            shouldBypassConfirmation = shouldBypassConfirmation
        )
    )
    private val localHttpServerStatusTool: AgentTool = toolDecorator.decorate(
        LocalHttpServerStatusTool(localHttpServerController)
    )

    override val id: String = "terminal-bash"


    override fun tools(): List<AgentTool> = listOf(
        exposedTool,
        localHttpServerStartTool,
        localHttpServerStatusTool,
        localHttpServerStopTool
    )

    override fun skills(): List<AndroidSkill> = listOf(
        terminalBashSkill(
            policy.copy(
                requireUserConfirmation = policy.requireUserConfirmation && !shouldBypassConfirmation()
            )
        ),
        localHttpServerSkill(requireUserConfirmation = !shouldBypassConfirmation())
    )

    override fun agentInstructions(): List<String> = buildList {
        add(runtimeAgentInstructions)
        if (shouldBypassConfirmation()) {
            add(AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION)
        }
    }

    /** Interrupts a running or queued terminal call owned by this plugin, if present. */
    fun cancel(callId: String): Boolean = terminalTool.cancel(callId)

    /** Interrupts all running and queued terminal calls owned by this plugin. */
    override fun cancelAll(): Int = terminalTool.cancelAll()

    /** Stops services owned by this plugin instance when the host is shutting down. */
    fun stopAllLocalHttpServers(): Int = localHttpServerController.stopAll()

    /** Releases Runtime-managed local services owned by this plugin instance. */
    override fun close() {
        localHttpServerController.stopAll()
    }

    private data class Components(
        val runtimeAgentInstructions: String,
        val terminalTool: BashCommandTool,
        val localHttpServerController: LocalHttpServerController
    )

    companion object {
        /**
         * The exact tool names this plugin exposes. Hosts that gate the
         * terminal capability (for example a capability interlock) must
         * derive their name set from here instead of duplicating it, so a
         * tool added here cannot silently bypass the host's gate.
         */
        val TOOL_NAMES: Set<String> = setOf(
            "terminal_bash_execute",
            "local_http_server_start",
            "local_http_server_stop",
            "local_http_server_status"
        )

        private fun createComponents(context: Context, policy: TerminalToolPolicy): Components {
            val runtimeAgentInstructions = TerminalAgentInstructions.load(context)
            val runtime = BashRuntime(context)
            return Components(
                runtimeAgentInstructions = runtimeAgentInstructions,
                terminalTool = BashCommandTool(runtime, runtime.defaultWorkspace(), policy),
                localHttpServerController = LocalHttpServerManager(runtime)
            )
        }
    }
}

class BashCommandTool(
    private val executor: BashCommandExecutor,
    workspaceRoot: File,
    private val policy: TerminalToolPolicy = TerminalToolPolicy(),
    override val name: String = "terminal_bash_execute"
) : AgentTool {
    private val executionSlots = Semaphore(policy.maxConcurrentExecutions)
    private val executions = ConcurrentHashMap<String, ExecutionState>()

    private val canonicalWorkspaceRoot: File by lazy {
        workspaceRoot.mkdirs()
        workspaceRoot.canonicalFile
    }

    override val description: String =
        "Runs a non-interactive Bash script in the app-private terminal workspace and returns stdout, stderr, exit code, and timeout status. The current runtime profile includes Bash, CPython 3.14 as python/python3, sqlite3, curl, and openssl."

    /**
     * Requests cancellation of a running or queued call. The Runtime observes the
     * thread interruption and terminates the call's process group.
     */
    fun cancel(callId: String): Boolean {
        return executions[callId]?.requestCancellation() ?: false
    }

    /** Requests cancellation of all active calls and returns the number hit. */
    fun cancelAll(): Int {
        return executions.values.count(ExecutionState::requestCancellation)
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("script") {
                put("type", "string")
                put("description", "Non-interactive Bash source code to run.")
            }
            putJsonObject("workingDirectory") {
                put("type", "string")
                put("description", "Optional relative directory inside the app-private terminal workspace.")
            }
            putJsonObject("timeoutMillis") {
                put("type", "integer")
                put("minimum", 1)
                put("description", "Optional timeout. It is capped by the host app policy.")
            }
            putJsonObject("environment") {
                put("type", "object")
                put("description", "Optional non-reserved environment variables with string values.")
                putJsonObject("additionalProperties") {
                    put("type", "string")
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("script"))
        }
    }

    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val script = call.input.string("script")?.takeIf { it.isNotBlank() }
            ?: return error(call, "MISSING_SCRIPT", "script is required.")
        val timeoutMillis = (call.input["timeoutMillis"] as? JsonPrimitive)?.longOrNull
            ?: policy.defaultTimeoutMillis
        if (timeoutMillis !in 1..policy.maxTimeoutMillis) {
            return error(
                call,
                "INVALID_TIMEOUT",
                "timeoutMillis must be between 1 and ${policy.maxTimeoutMillis}."
            )
        }

        val workingDirectory = resolveWorkingDirectory(call.input.string("workingDirectory"))
            ?: return error(
                call,
                "INVALID_WORKSPACE_PATH",
                "workingDirectory must be a relative path inside the terminal workspace and must not contain . or ..."
            )
        val environment = parseEnvironment(call.input["environment"])
            ?: return error(
                call,
                "INVALID_ENVIRONMENT",
                "environment must contain at most 32 non-reserved variable names with string values up to 4096 bytes."
            )

        val execution = ExecutionState()
        if (executions.putIfAbsent(call.id, execution) != null) {
            return error(
                call,
                "DUPLICATE_CALL_ID",
                "A terminal call with id '${call.id}' is already running or queued."
            )
        }

        return try {
            supervisorScope {
                val worker = async(start = CoroutineStart.LAZY) {
                    executionSlots.withPermit {
                        runInterruptible(Dispatchers.IO) {
                            executor.execute(
                                BashCommandRequest(
                                    script = script,
                                    workingDirectory = workingDirectory,
                                    environment = environment,
                                    timeoutMillis = timeoutMillis,
                                    maxCapturedBytes = policy.maxCapturedBytes
                                )
                            )
                        }
                    }
                }
                worker.invokeOnCompletion { execution.markCompleted() }
                execution.attach(worker)
                worker.start()
                worker.await().toToolResult(call)
            }
        } catch (error: CancellationException) {
            if (!execution.cancellationRequested()) throw error
            error(call, "CANCELLED", error.message ?: "Terminal execution cancelled.")
        } catch (error: IOException) {
            error(call, "PROCESS_START_FAILED", error.message ?: error::class.java.name)
        } catch (error: InterruptedException) {
            // A direct cancel(callId) interrupts the Runtime worker. Clear the
            // pooled thread's interrupt flag before returning a structured result.
            Thread.interrupted()
            error(call, "CANCELLED", error.message ?: "Terminal execution cancelled.")
        } catch (error: IllegalArgumentException) {
            error(call, "INVALID_REQUEST", error.message ?: error::class.java.name)
        } catch (error: IllegalStateException) {
            error(call, "RUNTIME_UNAVAILABLE", error.message ?: error::class.java.name)
        } finally {
            execution.markCompleted()
            executions.remove(call.id, execution)
        }
    }

    private fun resolveWorkingDirectory(rawPath: String?): File? {
        val normalized = rawPath?.trim().orEmpty()
        if (normalized.isEmpty()) return canonicalWorkspaceRoot
        if (File(normalized).isAbsolute || normalized.startsWith('/') || normalized.contains('\\')) return null

        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null

        val directory = File(canonicalWorkspaceRoot, segments.joinToString(File.separator)).canonicalFile
        if (!directory.isInside(canonicalWorkspaceRoot)) return null
        if (!directory.exists() && !directory.mkdirs()) return null
        return directory.takeIf { it.isDirectory }
    }

    private fun parseEnvironment(value: kotlinx.serialization.json.JsonElement?): Map<String, String>? {
        if (value == null) return emptyMap()
        val environment = value as? JsonObject ?: return null
        if (environment.size > MAX_ENVIRONMENT_ENTRIES) return null

        return buildMap {
            environment.forEach { (name, jsonValue) ->
                val primitive = jsonValue as? JsonPrimitive ?: return null
                if (!primitive.isString) return null
                val content = primitive.contentOrNull ?: return null
                if (!ENVIRONMENT_NAME.matches(name) || name in RESERVED_ENVIRONMENT_VARIABLES) return null
                if ('\u0000' in content) return null
                if (content.toByteArray(Charsets.UTF_8).size > MAX_ENVIRONMENT_VALUE_BYTES) return null
                put(name, content)
            }
        }
    }

    private fun File.isInside(root: File): Boolean {
        val rootPath = root.path
        val candidatePath = this.path
        return candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
    }

    private fun BashCommandResult.toToolResult(call: ToolCall): ToolResult {
        val payload = buildJsonObject {
            put("stdout", stdout)
            put("stderr", stderr)
            put("exitCode", exitCode)
            put("timedOut", timedOut)
            put("outputTruncated", outputTruncated)
            put("stdoutTruncated", stdoutTruncated)
            put("stderrTruncated", stderrTruncated)
            put("durationMillis", durationMillis)
            put("workingDirectory", workingDirectory)
        }
        return ToolResult(
            toolCallId = call.id,
            name = name,
            content = signalExitNote()?.let { note -> payload.toString() + "\n" + note }
                ?: payload.toString(),
            isError = timedOut || exitCode == null || exitCode != 0,
            metadata = payload
        )
    }

    /**
     * The JVM reports a signal-terminated process with a negative exit value
     * (-9 for SIGKILL). Explaining it in the content keeps the model from
     * reading the Runtime's own timeout, cancellation, or end-of-call process
     * group sweep as a normal script failure.
     */
    private fun BashCommandResult.signalExitNote(): String? {
        val signalExitCode = exitCode ?: return null
        if (signalExitCode >= 0) return null
        val signal = -signalExitCode
        val signalName = SIGNAL_NAMES[signal]?.let { " ($it)" }.orEmpty()
        return "exitCode=$signalExitCode means the process was terminated by " +
            "signal $signal$signalName. The Runtime itself terminates a call's " +
            "process group on timeout or cancellation and sweeps leftover " +
            "background processes when a call ends, so this usually reflects " +
            "Runtime termination rather than a normal script exit."
    }

    private fun error(call: ToolCall, code: String, message: String): ToolResult =
        terminalToolError(call.id, name, code, message)

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private class ExecutionState {
        private var worker: Job? = null
        private var cancellationRequested = false
        private var completed = false

        @Synchronized
        fun attach(job: Job) {
            worker = job
            if (cancellationRequested) {
                job.cancel(CancellationException("Terminal execution cancelled."))
            }
        }

        @Synchronized
        fun requestCancellation(): Boolean {
            if (completed || cancellationRequested) return false
            cancellationRequested = true
            worker?.cancel(CancellationException("Terminal execution cancelled."))
            return true
        }

        @Synchronized
        fun cancellationRequested(): Boolean = cancellationRequested

        @Synchronized
        fun markCompleted() {
            completed = true
            worker = null
        }
    }

    private companion object {
        val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val SIGNAL_NAMES = mapOf(
            1 to "SIGHUP",
            2 to "SIGINT",
            3 to "SIGQUIT",
            4 to "SIGILL",
            5 to "SIGTRAP",
            6 to "SIGABRT",
            7 to "SIGBUS",
            8 to "SIGFPE",
            9 to "SIGKILL",
            10 to "SIGUSR1",
            11 to "SIGSEGV",
            12 to "SIGUSR2",
            13 to "SIGPIPE",
            14 to "SIGALRM",
            15 to "SIGTERM"
        )
        val RESERVED_ENVIRONMENT_VARIABLES = setOf(
            "HOME",
            "PWD",
            "TMPDIR",
            "PATH",
            "LD_LIBRARY_PATH",
            "ANDROID_DATA",
            "ANDROID_ROOT",
            "LD_PRELOAD",
            "UGK_NATIVE_LIBRARY_DIR",
            "UGK_TERMINAL_SESSION_REPORT_FILE",
            "BASH_ENV",
            "CURL_CA_BUNDLE",
            "UGK_PYTHON_LIBRARY",
            "UGK_PYTHON_EXTENSION_DIRECTORY",
            "PYTHONHOME",
            "PYTHONPATH",
            "PYTHONNOUSERSITE",
            "PYTHONDONTWRITEBYTECODE",
            "PYTHONUNBUFFERED",
            "SSL_CERT_FILE",
        )
        const val MAX_ENVIRONMENT_ENTRIES = 32
        const val MAX_ENVIRONMENT_VALUE_BYTES = 4 * 1024
    }
}

fun terminalBashSkill(policy: TerminalToolPolicy = TerminalToolPolicy()): AndroidSkill {
    val confirmationRule = if (policy.requireUserConfirmation) {
        "This host requires an immediate user confirmation before terminal_bash_execute. Call show_user_confirmation_dialog first with target.toolName set to terminal_bash_execute and target.input set to the complete JSON input for the exact next call, then call terminal_bash_execute with the identical input. selectedButtonId only records the dialog button choice; it does not authorize a protected Tool by itself."
    } else {
        "This host has explicitly disabled the terminal confirmation wrapper."
    }
    return AndroidSkill(
        id = "terminal-bash",
        description = "Run controlled non-interactive Bash commands in the host app's private terminal workspace.",
        triggers = listOf("terminal", "bash", "shell", "command line", "curl", "python", "sqlite", "openssl", "终端", "命令行", "执行命令", "脚本"),
        instructions = """
            This tool runs a real Bash executable packaged inside the host APK. It is not a terminal UI.
            The host injects the SDK runtime AGENTS.md as a global system instruction. Treat that document as the authoritative environment contract for this tool.
            $confirmationRule
            When confirmation is enabled, the target binding must cover the complete input, including script, workingDirectory, timeoutMillis, and environment values when present. Never reuse a confirmation for a different command, workspace path, timeout, environment, or network request.

            Pass Bash source directly as the script. Do not invoke bash, bash -c, sh, or sh -c as a child command because this tool is already running Bash. Use non-interactive scripts only. Work only in the provided app-private workspace; use relative workingDirectory values such as projects/demo.
            The process runs with the host app's Android UID. It is not a security sandbox and must never be treated as a way to protect host secrets from an untrusted model.
            Use conservative timeouts, keep this tool bounded and non-daemon, and inspect stdout/stderr/exitCode before taking a follow-up action. Do not use nohup, disown, setsid, or a shell background job to maintain a persistent HTTP service; use local_http_server_start, local_http_server_status, and local_http_server_stop instead. The host limits this tool to ${policy.maxConcurrentExecutions} concurrent execution(s); cancelling the Agent coroutine also interrupts and terminates the active terminal process group.
            The current runtime contains Bash, CPython 3.14 available as python and python3, a SQLite CLI available as sqlite3, curl, and openssl. Python includes its bundled standard library plus ssl, sqlite3, hashlib, and subprocess; its native extension modules stay in nativeLibraryDir and its standard library is hash-verified in app-private data. curl supports only file/http/https in this profile and uses the same managed CA bundle through CURL_CA_BUNDLE. HTTPS access requires the host's merged INTERNET permission and should be treated as network egress requiring confirmation. Do not disable TLS verification unless the user specifically directs it.
            Node.js, Git, and SSH are not packaged in the current profile. Do not claim them as available or attempt to install packages or executable native extensions at runtime.
        """.trimIndent(),
        methods = listOf(
            AndroidSkillMethod(
                toolName = "terminal_bash_execute",
                purpose = "Runs one non-interactive Bash script and returns its complete bounded execution result.",
                whenToUse = "Use for shell built-ins and for verified runtime commands included by the host app.",
                resultSemantics = "Returns stdout, stderr, exitCode, timedOut, outputTruncated, stdoutTruncated, stderrTruncated, durationMillis, and workingDirectory as JSON. A nonzero exit code or timeout is reported as a tool error."
            )
        )
    )
}

fun localHttpServerSkill(requireUserConfirmation: Boolean = true): AndroidSkill {
    val confirmationInstruction = if (requireUserConfirmation) {
        "local_http_server_start and local_http_server_stop require the normal user confirmation flow. Before each protected call, call show_user_confirmation_dialog with target.toolName set to the exact next Tool name and target.input set to its complete JSON input, then invoke that Tool with the identical name and input. The returned browser Intent also requires its own exact confirmation target. selectedButtonId only records the dialog button choice; it does not authorize a protected Tool by itself."
    } else {
        AgentConfirmationPolicy.FULL_AUTHORIZATION_AGENT_INSTRUCTION
    }
    return AndroidSkill(
        id = "local-http-server",
        description = "Start, inspect, and stop a Runtime-managed loopback HTTP server for an app-private workspace directory.",
        triggers = listOf(
            "local server",
            "http server",
            "localhost",
            "web server",
            "serve website",
            "preview website",
            "本地服务",
            "本地服务器",
            "启动网站",
            "启动网页",
            "预览网站",
            "网页服务"
        ),
        instructions = """
            Use the prebuilt local_http_server_start tool when the user asks to serve or preview files from the terminal workspace.
            The server is implemented by the SDK with the packaged CPython runtime and binds only to 127.0.0.1; do not write nohup, disown, setsid, or a shell background daemon yourself.
            The directory must already exist inside the terminal workspace. Use local_http_server_status to verify that the service is listening, and local_http_server_stop when the user asks to stop it or the temporary service is no longer needed.
            $confirmationInstruction
            For local_http_server_start, bind the exact directory and any explicitly supplied port; if the next input relies on the default port, omit port in both inputs. For local_http_server_stop, bind the exact port. local_http_server_status is read-only and does not require confirmation or a ticket.
            The returned URL is browser-visible only on the same Android device. Use launch_android_app_intent with target open_url to hand it to the browser. In confirmation mode, its confirmation target.toolName and target.input must match the next Intent input exactly.
        """.trimIndent(),
        methods = listOf(
            AndroidSkillMethod(
                toolName = "local_http_server_start",
                purpose = "Starts or reuses a managed Python HTTP server bound to 127.0.0.1.",
                whenToUse = "Use after the requested website files exist and the user asks to serve, preview, or open them in a browser.",
                resultSemantics = "Returns running state, loopback URL, served directory, log path, and managed process-group id."
            ),
            AndroidSkillMethod(
                toolName = "local_http_server_status",
                purpose = "Checks whether a managed local HTTP server is still running and listening.",
                whenToUse = "Use after start and before claiming that a local website is available; it is read-only.",
                resultSemantics = "Returns running, starting, stopped, or not_found state without changing the service."
            ),
            AndroidSkillMethod(
                toolName = "local_http_server_stop",
                purpose = "Stops one managed local HTTP server by port.",
                whenToUse = "Use when the user asks to stop the local website or the temporary service should be cleaned up.",
                resultSemantics = "Stops only a service recorded by this Runtime; it never kills an unmanaged process."
            )
        )
    )
}
