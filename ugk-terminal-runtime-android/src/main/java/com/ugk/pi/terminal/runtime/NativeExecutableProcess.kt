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
            process?.takeIf(::isAlive)?.let { runningProcess ->
                stop(runningProcess, sessionReport)
            }
            throw interrupted
        } finally {
            if (sessionReport.exists()) sessionReport.delete()
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

    private class OutputCollector(private val limitBytes: Int) {
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
        fun text(): String = output.toString(Charsets.UTF_8.name())
    }

    private const val POLL_INTERVAL_MILLIS = 10L
    private const val STOP_GRACE_PERIOD_MILLIS = 500L
    private const val STOP_KILL_WAIT_MILLIS = 1_000L
    private const val SESSION_REPORT_WAIT_MILLIS = 250L
    private const val SESSION_REPORT_DIRECTORY = "ugk-terminal-session-reports"
    private const val SESSION_REPORT_ENVIRONMENT_VARIABLE = "UGK_TERMINAL_SESSION_REPORT_FILE"
    private const val SESSION_LAUNCHER_FILE_NAME = "libugk_session_launcher.so"
    private const val SIGNAL_TERMINATE = 15
    private const val SIGNAL_KILL = 9
}
