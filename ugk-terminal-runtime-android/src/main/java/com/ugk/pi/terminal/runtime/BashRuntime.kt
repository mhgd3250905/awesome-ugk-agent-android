package com.ugk.pi.terminal.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Executes the Bash payload packaged with the host APK.
 *
 * This is a process launcher, not an OS security boundary. A command runs as
 * the host application's Linux UID and can access whatever that UID can
 * access. Agent-facing callers should use a confirmation policy and expose a
 * dedicated workspace rather than treating this as a sandbox.
 */
class BashRuntime(context: Context) : BashCommandExecutor {
    private val appContext = context.applicationContext
    private val pythonDistribution = PythonDistribution(appContext)

    override fun execute(request: BashCommandRequest): BashCommandResult {
        require(request.script.isNotBlank()) { "script must not be blank" }
        require(request.timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 1 and $MAX_TIMEOUT_MILLIS"
        }
        require(request.maxCapturedBytes in 1..MAX_CAPTURED_BYTES) {
            "maxCapturedBytes must be between 1 and $MAX_CAPTURED_BYTES"
        }

        val workspace = request.workingDirectory ?: defaultWorkspace()
        if (!workspace.exists()) check(workspace.mkdirs()) {
            "Unable to create terminal workspace: ${workspace.absolutePath}"
        }

        require(request.environment.keys.none { it in MANAGED_ENVIRONMENT_VARIABLES }) {
            "environment must not override runtime-managed variables"
        }
        val environment = managedEnvironment(workspace).toMutableMap().apply {
            putAll(request.environment)
        }
        val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)

        val result = NativeExecutableProcess.execute(
            executable = executableFile(appContext),
            arguments = listOf("--noprofile", "--norc", "-c", request.script),
            workingDirectory = workspace,
            runtimeDataDirectory = appContext.cacheDir,
            environment = environment,
            timeoutMillis = request.timeoutMillis,
            maxCapturedBytes = request.maxCapturedBytes
        )
        return BashCommandResult(
            command = result.command,
            executablePath = result.executablePath,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMillis = result.durationMillis,
            timedOut = result.timedOut,
            outputTruncated = result.outputTruncated,
            workingDirectory = workspace.absolutePath,
            stdoutTruncated = result.stdoutTruncated,
            stderrTruncated = result.stderrTruncated
        )
    }

    fun defaultWorkspace(): File = File(appContext.filesDir, DEFAULT_WORKSPACE_DIRECTORY)

    /**
     * Returns the verified environment shared by Bash and Runtime-managed
     * native commands such as the local HTTP server.
     *
     * This is intentionally internal: Agent-facing callers should use a
     * structured Tool rather than constructing arbitrary native processes.
     */
    internal fun managedEnvironment(workingDirectory: File = defaultWorkspace()): Map<String, String> {
        val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
        val bashEnvironment = commandProfile(nativeLibraryDir)
        val caBundle = certificateBundle()
        val pythonLibrary = File(nativeLibraryDir, PythonDistribution.PYTHON_LIBRARY_FILE_NAME)
        check(pythonLibrary.isFile) {
            "Python runtime library is missing from nativeLibraryDir: ${pythonLibrary.absolutePath}"
        }
        val pythonHome = pythonDistribution.home()
        return linkedMapOf(
            "HOME" to workingDirectory.absolutePath,
            // ProcessBuilder changes the actual directory, but it does not
            // update the inherited PWD variable. Keep the child view coherent.
            "PWD" to workingDirectory.absolutePath,
            "TMPDIR" to File(appContext.cacheDir, "ugk-terminal-tmp").apply { mkdirs() }.absolutePath,
            "PATH" to "$nativeLibraryDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            // Android's older bionic timezone lookup requires these runtime
            // roots. ProcessBuilder starts from a cleared environment, so
            // preserve the platform-defined values explicitly.
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "UGK_NATIVE_LIBRARY_DIR" to nativeLibraryDir.absolutePath,
            "BASH_ENV" to bashEnvironment.absolutePath,
            // curl's compiled-in path is deliberately non-existent because
            // APK assets are not ordinary files. Publish a hash-verified copy
            // to app-private data instead of extracting an executable there.
            "CURL_CA_BUNDLE" to caBundle.absolutePath,
            // CPython's executable launcher and every extension module remain
            // in nativeLibraryDir. Only pure standard-library data is copied
            // to the verified private PYTHONHOME directory.
            "UGK_PYTHON_LIBRARY" to pythonLibrary.absolutePath,
            "UGK_PYTHON_EXTENSION_DIRECTORY" to nativeLibraryDir.absolutePath,
            "PYTHONHOME" to pythonHome.absolutePath,
            "PYTHONPATH" to nativeLibraryDir.absolutePath,
            "PYTHONNOUSERSITE" to "1",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONUNBUFFERED" to "1",
            "SSL_CERT_FILE" to caBundle.absolutePath,
        )
    }

    /** Returns the real packaged launcher, not the Bash `python3` function. */
    internal fun pythonExecutableFile(): File {
        return File(
            appContext.applicationInfo.nativeLibraryDir,
            pythonExecutableFileName
        )
    }

    /** Returns the session launcher used to create a dedicated process group. */
    internal fun sessionLauncherFile(): File {
        return File(
            appContext.applicationInfo.nativeLibraryDir,
            SESSION_LAUNCHER_FILE_NAME
        )
    }

    /** App-private state for Runtime-managed services and their bounded logs. */
    internal fun managedServiceDirectory(): File {
        return File(appContext.filesDir, MANAGED_SERVICE_DIRECTORY)
    }

    /**
     * APK native payloads must keep their .so names to be extracted into
     * nativeLibraryDir. A non-interactive Bash profile turns the verified
     * payload names into ordinary command names without creating executable
     * files under app-writable storage.
     */
    @Synchronized
    private fun commandProfile(nativeLibraryDir: File): File {
        val profileDirectory = File(appContext.cacheDir, COMMAND_PROFILE_DIRECTORY)
        if (!profileDirectory.exists()) check(profileDirectory.mkdirs()) {
            "Unable to create terminal command profile directory: ${profileDirectory.absolutePath}"
        }

        val content = buildString {
            appendLine("# Generated by UGK Terminal Runtime. Do not edit.")
            PACKAGED_COMMANDS
                .filter { File(nativeLibraryDir, it.executableFileName).isFile }
                .forEach { command ->
                    appendLine("${command.shellName}() {")
                    appendLine("  \"\${UGK_NATIVE_LIBRARY_DIR}/${command.executableFileName}\" \"\$@\"")
                    appendLine("}")
                }
        }
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val profile = File(profileDirectory, "$COMMAND_PROFILE_FILE_PREFIX${profileFingerprint(content)}.bash")
        if (profile.isFile && profile.length() == contentBytes.size.toLong() && profile.readText() == content) {
            return profile
        }

        val stagedProfile = File.createTempFile("commands-", ".tmp", profileDirectory)
        try {
            stagedProfile.writeBytes(contentBytes)
            if (profile.exists() && !profile.delete()) {
                throw IllegalStateException("Unable to replace terminal command profile: ${profile.absolutePath}")
            }
            if (!stagedProfile.renameTo(profile)) {
                throw IllegalStateException("Unable to publish terminal command profile: ${profile.absolutePath}")
            }
        } finally {
            if (stagedProfile.exists()) stagedProfile.delete()
        }
        return profile
    }

    private fun profileFingerprint(content: String): String {
        return sha256(content.toByteArray(Charsets.UTF_8))
    }

    /**
     * Materializes the locked CA bundle as data, never as executable code.
     * The integrity check also repairs a stale or tampered copy on the next
     * terminal invocation.
     */
    @Synchronized
    private fun certificateBundle(): File {
        val certificateDirectory = File(appContext.filesDir, CERTIFICATE_DIRECTORY)
        if (!certificateDirectory.exists()) check(certificateDirectory.mkdirs()) {
            "Unable to create terminal certificate directory: ${certificateDirectory.absolutePath}"
        }

        val certificate = File(certificateDirectory, CERTIFICATE_FILE_NAME)
        if (certificate.isFile && sha256(certificate) == CA_BUNDLE_SHA256) return certificate

        val stagedCertificate = File.createTempFile("cert-", ".tmp", certificateDirectory)
        try {
            appContext.assets.open(CA_BUNDLE_ASSET_PATH).use { input ->
                stagedCertificate.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            check(sha256(stagedCertificate) == CA_BUNDLE_SHA256) {
                "Packaged terminal CA bundle failed its SHA-256 verification"
            }
            if (certificate.exists() && !certificate.delete()) {
                throw IllegalStateException("Unable to replace terminal CA bundle: ${certificate.absolutePath}")
            }
            check(stagedCertificate.renameTo(certificate)) {
                "Unable to publish terminal CA bundle: ${certificate.absolutePath}"
            }
            return certificate
        } finally {
            if (stagedCertificate.exists()) stagedCertificate.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }

    private fun ByteArray.toHex(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val executableFileName: String = "libugk_bash.so"
        const val sqliteExecutableFileName: String = "libugk_sqlite3.so"
        const val curlExecutableFileName: String = "libugk_curl.so"
        const val opensslExecutableFileName: String = "libugk_openssl.so"
        const val pythonExecutableFileName: String = "libugk_python.so"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
        const val DEFAULT_MAX_CAPTURED_BYTES: Int = 64 * 1024
        const val MAX_TIMEOUT_MILLIS: Long = 120_000L
        const val MAX_CAPTURED_BYTES: Int = 512 * 1024

        fun executableFile(context: Context): File {
            return File(context.applicationInfo.nativeLibraryDir, executableFileName)
        }

        private const val DEFAULT_WORKSPACE_DIRECTORY = "ugk-terminal-workspace"
        private const val COMMAND_PROFILE_DIRECTORY = "ugk-terminal-profile"
        private const val COMMAND_PROFILE_FILE_PREFIX = "commands-"
        private const val CERTIFICATE_DIRECTORY = "ugk-terminal-runtime/certificates"
        private const val CERTIFICATE_FILE_NAME = "cert.pem"
        private const val CA_BUNDLE_ASSET_PATH = "ugk-terminal-runtime/cert.pem"
        private const val CA_BUNDLE_SHA256 = "3ff344e30b9b1ed2971044eabb438a08f2e2245ddb5f8ab1a3ad8b63ab4eaf91"
        private const val DEFAULT_HASH_BUFFER_BYTES = 16 * 1024
        private const val SESSION_LAUNCHER_FILE_NAME = "libugk_session_launcher.so"
        private const val MANAGED_SERVICE_DIRECTORY = "ugk-terminal-services"
        private val MANAGED_ENVIRONMENT_VARIABLES = setOf(
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
        private val PACKAGED_COMMANDS = listOf(
            PackagedCommand(shellName = "sqlite3", executableFileName = sqliteExecutableFileName),
            PackagedCommand(shellName = "curl", executableFileName = curlExecutableFileName),
            PackagedCommand(shellName = "openssl", executableFileName = opensslExecutableFileName),
            PackagedCommand(shellName = "python", executableFileName = pythonExecutableFileName),
            PackagedCommand(shellName = "python3", executableFileName = pythonExecutableFileName),
        )
    }

    private data class PackagedCommand(
        val shellName: String,
        val executableFileName: String
    )
}

data class BashCommandRequest(
    val script: String,
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = BashRuntime.DEFAULT_TIMEOUT_MILLIS,
    val maxCapturedBytes: Int = BashRuntime.DEFAULT_MAX_CAPTURED_BYTES
)

data class BashCommandResult(
    val command: List<String>,
    val executablePath: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val workingDirectory: String,
    val stdoutTruncated: Boolean = outputTruncated,
    val stderrTruncated: Boolean = outputTruncated
)

fun interface BashCommandExecutor {
    fun execute(request: BashCommandRequest): BashCommandResult
}
