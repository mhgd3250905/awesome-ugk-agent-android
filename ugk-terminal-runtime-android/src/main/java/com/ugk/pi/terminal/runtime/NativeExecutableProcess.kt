package com.ugk.pi.terminal.runtime

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal data class NativeExecutableProcessResult(
    val command: List<String>,
    val executablePath: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean
)

/**
 * Runs an ELF that was installed in an APK native-library directory.
 *
 * This deliberately does not copy executable content into an app-writable
 * directory. See [BashRuntime] for the public command API.
 */
internal object NativeExecutableProcess {
    fun execute(
        executable: File,
        arguments: List<String>,
        workingDirectory: File? = null,
        runtimeDataDirectory: File,
        environment: Map<String, String> = emptyMap(),
        timeoutMillis: Long,
        maxCapturedBytes: Int
    ): NativeExecutableProcessResult {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        require(maxCapturedBytes > 0) { "maxCapturedBytes must be greater than zero" }

        check(executable.isFile) {
            "Native executable is missing from nativeLibraryDir: ${executable.absolutePath}"
        }
        if (workingDirectory != null) {
            require(workingDirectory.isDirectory) {
                "Working directory does not exist or is not a directory: ${workingDirectory.absolutePath}"
            }
        }
        if (!runtimeDataDirectory.exists()) check(runtimeDataDirectory.mkdirs()) {
            "Unable to create terminal runtime data directory: ${runtimeDataDirectory.absolutePath}"
        }
        require(runtimeDataDirectory.isDirectory) {
            "Terminal runtime data directory is not a directory: ${runtimeDataDirectory.absolutePath}"
        }

        val command = buildList {
            add(executable.absolutePath)
            addAll(arguments)
        }
        val nativeLibraryDirectory = executable.parentFile
            ?: throw IllegalStateException("Native executable has no parent directory: ${executable.absolutePath}")
        val sessionLauncher = File(nativeLibraryDirectory, SESSION_LAUNCHER_FILE_NAME)
        check(sessionLauncher.isFile) {
            "Terminal session launcher is missing from nativeLibraryDir: ${sessionLauncher.absolutePath}"
        }
        val sessionReport = createSessionReport(runtimeDataDirectory)
        val builder = ProcessBuilder(buildList {
            add(sessionLauncher.absolutePath)
            addAll(command)
        })
        if (workingDirectory != null) builder.directory(workingDirectory)
        builder.environment().apply {
            clear()
            putAll(environment)
            put(SESSION_REPORT_ENVIRONMENT_VARIABLE, sessionReport.absolutePath)
        }

        val startedAt = SystemClock.elapsedRealtime()
        var process: Process? = null
        try {
            process = builder.start()
            val stdout = OutputCollector(maxCapturedBytes)
            val stderr = OutputCollector(maxCapturedBytes)
            val stdoutThread = drain(process.inputStream, stdout, "ugk-native-stdout")
            val stderrThread = drain(process.errorStream, stderr, "ugk-native-stderr")

            val exitCode = waitForExit(process, timeoutMillis)
            val timedOut = exitCode == null
            val finalExitCode = if (timedOut) {
                stop(process, sessionReport)
            } else {
                // The documented contract ties the whole process group to one
                // call. A non-interactive Bash exits without waiting for its
                // background jobs, so any residual child would otherwise be
                // reparented to init and survive the call.
                sweepResidualProcessGroup(sessionReport)
                exitCode
            }

            stdoutThread.join(1_000)
            stderrThread.join(1_000)

            return NativeExecutableProcessResult(
                command = command,
                executablePath = executable.absolutePath,
                exitCode = finalExitCode,
                stdout = stdout.text(),
                stderr = stderr.text(),
                durationMillis = SystemClock.elapsedRealtime() - startedAt,
                timedOut = timedOut,
                outputTruncated = stdout.truncated || stderr.truncated,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated
            )
        } catch (interrupted: InterruptedException) {
            val runningProcess = process?.takeIf(::isAlive)
            if (runningProcess != null) {
                stop(runningProcess, sessionReport)
            } else {
                // The direct child already exited, so the timeout path's
                // group-wide stop never ran; the sweep still covers children
                // that outlived it.
                sweepResidualProcessGroup(sessionReport)
            }
            throw interrupted
        } finally {
            if (sessionReport.exists()) sessionReport.delete()
        }
    }

    /**
     * Terminates background children that outlived an already-exited script.
     *
     * The session leader (the direct child) is gone at this point, so signals
     * only reach residual members of its process group. Host processes are in
     * a different session: session_launcher calls setsid before exec, and the
     * local HTTP server manager launches its servers through the same launcher
     * into their own dedicated groups, so neither can be hit from here. This
     * is best effort: a failure must never turn an already-successful call
     * into a failure, and there is no logging surface in this runtime module,
     * so misses are silently absorbed here.
     */
    private fun sweepResidualProcessGroup(sessionReport: File) {
        try {
            val processGroupId = readSessionGroupId(sessionReport) ?: return
            if (!NativeProcessGroupControl.processGroupExists(processGroupId)) return
            NativeProcessGroupControl.signalProcessGroup(processGroupId, SIGNAL_TERMINATE)
            if (waitForProcessGroupExit(processGroupId, SWEEP_GRACE_PERIOD_MILLIS)) return
            NativeProcessGroupControl.signalProcessGroup(processGroupId, SIGNAL_KILL)
            waitForProcessGroupExit(processGroupId, STOP_KILL_WAIT_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: RuntimeException) {
            // Best effort only; the caller's exit code stays authoritative.
        }
    }

    private fun stop(process: Process, sessionReport: File): Int? {
        val processGroupId = awaitSessionGroupId(sessionReport)
        if (processGroupId != null && NativeProcessGroupControl.signalProcessGroup(processGroupId, SIGNAL_TERMINATE)) {
            val groupExitedAfterTerminate = waitForProcessGroupExit(
                processGroupId,
                STOP_GRACE_PERIOD_MILLIS
            )
            if (!groupExitedAfterTerminate) {
                NativeProcessGroupControl.signalProcessGroup(processGroupId, SIGNAL_KILL)
                waitForProcessGroupExit(processGroupId, STOP_KILL_WAIT_MILLIS)
            }
            waitForExit(process, STOP_KILL_WAIT_MILLIS)?.let { return it }
        }

        // This fallback only guarantees the direct child. The launcher and JNI
        // helper are packaged together, so normal Android Runtime execution
        // takes the process-group path above.
        process.destroy()
        return waitForExit(process, STOP_GRACE_PERIOD_MILLIS)
    }

    /**
     * `Process.isAlive()` was added after the SDK's minSdk 24 API surface.
     * Use the older exitValue contract so cancellation remains safe on API 24.
     */
    private fun isAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun waitForProcessGroupExit(processGroupId: Int, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!NativeProcessGroupControl.processGroupExists(processGroupId)) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return !NativeProcessGroupControl.processGroupExists(processGroupId)
    }

    private fun createSessionReport(runtimeDataDirectory: File): File {
        val reportDirectory = File(runtimeDataDirectory, SESSION_REPORT_DIRECTORY)
        if (!reportDirectory.exists()) check(reportDirectory.mkdirs()) {
            "Unable to create terminal session report directory: ${reportDirectory.absolutePath}"
        }
        return File.createTempFile("session-", ".pid", reportDirectory)
    }

    private fun awaitSessionGroupId(sessionReport: File): Int? {
        val deadline = SystemClock.elapsedRealtime() + SESSION_REPORT_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            readSessionGroupId(sessionReport)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return readSessionGroupId(sessionReport)
    }

    private fun readSessionGroupId(sessionReport: File): Int? {
        return runCatching { sessionReport.readText().trim().toInt() }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    private fun waitForExit(process: Process, timeoutMillis: Long): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        }
        return runCatching { process.exitValue() }.getOrNull()
    }

    private fun drain(input: InputStream, collector: OutputCollector, name: String): Thread {
        return Thread({
            try {
                input.use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead < 0) break
                        collector.append(buffer, bytesRead)
                    }
                }
            } catch (_: IOException) {
                // Android may close a Process pipe while its child is being
                // reaped. The process result and bytes captured so far remain
                // valid; an uncaught exception on this daemon thread would
                // otherwise crash the host application's instrumentation.
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    private const val POLL_INTERVAL_MILLIS = 10L
    private const val STOP_GRACE_PERIOD_MILLIS = 500L
    private const val STOP_KILL_WAIT_MILLIS = 1_000L
    private const val SWEEP_GRACE_PERIOD_MILLIS = 1_500L
    private const val SESSION_REPORT_WAIT_MILLIS = 250L
    private const val SESSION_REPORT_DIRECTORY = "ugk-terminal-session-reports"
    private const val SESSION_REPORT_ENVIRONMENT_VARIABLE = "UGK_TERMINAL_SESSION_REPORT_FILE"
    private const val SESSION_LAUNCHER_FILE_NAME = "libugk_session_launcher.so"
    private const val SIGNAL_TERMINATE = 15
    private const val SIGNAL_KILL = 9
}

/**
 * Bounded byte collector for captured process output.
 *
 * The byte cap can split a multi-byte UTF-8 code point, either inside the
 * chunk that reaches the limit or right before a later chunk that is then
 * dropped whole. [text] therefore trims a dangling partial code point when
 * [truncated] is set, so a truncated capture never ends in a replacement
 * character; [truncated] itself still reports the data loss.
 */
internal class OutputCollector(private val limitBytes: Int) {
    private val output = ByteArrayOutputStream()

    var truncated: Boolean = false
        private set

    @Synchronized
    fun append(buffer: ByteArray, bytesRead: Int) {
        val remaining = limitBytes - output.size()
        if (remaining <= 0) {
            truncated = true
            return
        }

        val bytesToWrite = minOf(remaining, bytesRead)
        output.write(buffer, 0, bytesToWrite)
        if (bytesToWrite < bytesRead) truncated = true
    }

    @Synchronized
    fun text(): String {
        val bytes = output.toByteArray()
        val end = if (truncated) completeUtf8PrefixLength(bytes) else bytes.size
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    companion object {
        private const val MAX_UTF8_SEQUENCE_BYTES = 4

        /**
         * Largest length that stays within `bytes.size` and ends on a
         * complete UTF-8 code point boundary. Walks back over at most three
         * trailing continuation bytes to the sequence lead; if the lead
         * announces more bytes than were captured, the partial sequence is
         * dropped (1-4 bytes).
         */
        internal fun completeUtf8PrefixLength(bytes: ByteArray): Int {
            var leadIndex = bytes.size - 1
            var checked = 0
            while (leadIndex >= 0 &&
                checked < MAX_UTF8_SEQUENCE_BYTES - 1 &&
                isContinuationByte(bytes[leadIndex])
            ) {
                leadIndex--
                checked++
            }
            if (leadIndex < 0) return 0
            val leadByte = bytes[leadIndex].toInt() and 0xff
            val expectedLength = when {
                leadByte < 0x80 -> 1
                leadByte ushr 5 == 0b110 -> 2
                leadByte ushr 4 == 0b1110 -> 3
                leadByte ushr 3 == 0b11110 -> 4
                else -> 1
            }
            return if (leadIndex + expectedLength > bytes.size) leadIndex else bytes.size
        }

        private fun isContinuationByte(byte: Byte): Boolean {
            return byte.toInt() and 0xc0 == 0x80
        }
    }
}
