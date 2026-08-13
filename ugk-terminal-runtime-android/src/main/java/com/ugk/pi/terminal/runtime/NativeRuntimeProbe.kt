package com.ugk.pi.terminal.runtime

import android.content.Context
import java.io.File

/**
 * Runtime packaging and relocation probe.
 *
 * The eventual terminal Runtime must execute ELF payloads installed with the
 * host APK, never ELF files copied into the app's writable data directory.
 * This type verifies that an executable packaged as a native payload can be
 * located through [Context.getApplicationInfo] regardless of applicationId.
 */
object NativeRuntimeProbe {
    const val executableFileName: String = "libugk_runtime_probe.so"

    private const val defaultTimeoutMillis = 5_000L
    private const val maxCapturedBytes = 64 * 1024

    fun executableFile(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, executableFileName)
    }

    fun execute(
        context: Context,
        arguments: List<String> = emptyList(),
        timeoutMillis: Long = defaultTimeoutMillis
    ): Result {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }

        val executable = executableFile(context)
        check(executable.isFile) {
            "Native Runtime probe is missing from nativeLibraryDir: ${executable.absolutePath}"
        }

        val result = NativeExecutableProcess.execute(
            executable = executable,
            arguments = arguments,
            runtimeDataDirectory = context.cacheDir,
            timeoutMillis = timeoutMillis,
            maxCapturedBytes = maxCapturedBytes
        )

        return Result(
            command = result.command,
            executablePath = result.executablePath,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMillis = result.durationMillis,
            timedOut = result.timedOut,
            outputTruncated = result.outputTruncated
        )
    }

    data class Result(
        val command: List<String>,
        val executablePath: String,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val durationMillis: Long,
        val timedOut: Boolean,
        val outputTruncated: Boolean
    )
}
