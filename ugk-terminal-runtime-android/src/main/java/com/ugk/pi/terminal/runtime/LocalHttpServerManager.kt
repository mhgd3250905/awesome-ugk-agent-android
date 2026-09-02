package com.ugk.pi.terminal.runtime

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Properties

/** A request for the SDK-managed, loopback-only Python HTTP server. */
data class LocalHttpServerRequest(
    val directory: String,
    val port: Int = DEFAULT_LOCAL_HTTP_SERVER_PORT
)

/** Structured state returned by the local HTTP server controller. */
data class LocalHttpServerStatus(
    val state: String,
    val port: Int,
    val directory: String? = null,
    val url: String? = null,
    val logFile: String? = null,
    val processGroupId: Int? = null,
    val managed: Boolean = true
) {
    companion object {
        fun notFound(port: Int): LocalHttpServerStatus {
            return LocalHttpServerStatus(
                state = "not_found",
                port = port,
                managed = false
            )
        }
    }
}

/**
 * The small lifecycle boundary used by Agent Tools. Keeping this interface
 * separate makes the Tool deterministic to unit-test without starting an
 * Android native process.
 */
interface LocalHttpServerController {
    fun start(request: LocalHttpServerRequest): LocalHttpServerStatus

    fun status(port: Int? = null): List<LocalHttpServerStatus>

    fun stop(port: Int): LocalHttpServerStatus

    fun stopAll(): Int
}

class LocalHttpServerException(
    val code: String,
    override val message: String
) : IllegalStateException(message)

/**
 * Owns Python HTTP servers started for the host application.
 *
 * This is deliberately not implemented by asking the model to compose
 * `nohup`, `disown`, or a shell background job. The manager launches the
 * verified Python launcher from nativeLibraryDir inside the same dedicated
 * POSIX session machinery as Bash, persists the process-group id, checks the
 * loopback port, and can terminate the whole group later.
 *
 * Every start writes a fixed token-gated handler script and serves it under
 * a fresh random token path segment: any App on the device shares the
 * loopback port space, so plain `python -m http.server` would let every
 * other App read the whole served tree. The handler rejects every path that
 * does not carry the token (404, not 403, so the tree cannot be probed) and
 * refuses local paths whose realpath escapes the served root, which blocks
 * symlink escapes planted inside the workspace.
 */
class LocalHttpServerManager(
    private val runtime: BashRuntime
) : LocalHttpServerController, AutoCloseable {
    constructor(context: Context) : this(BashRuntime(context))

    private val records = linkedMapOf<Int, ManagedServer>()
    private var metadataLoaded = false

    override fun start(request: LocalHttpServerRequest): LocalHttpServerStatus {
        synchronized(this) {
            ensureMetadataLoaded()
            validatePort(request.port)
            val directory = resolveWorkspaceDirectory(request.directory)
            check(directory.isDirectory) {
                "Local HTTP server directory does not exist: ${directory.absolutePath}"
            }

            records[request.port]?.let { existing ->
                val existingStatus = statusFor(existing)
                if (existingStatus.state in RUNNING_STATES && !isStaleNonListening(existing)) {
                    directoryReuseError(request.port, existing.directory, directory)?.let { failure ->
                        throw failure
                    }
                    return existingStatus
                }
                discardRecord(existing)
            }

            if (isPortListening(request.port)) {
                throw LocalHttpServerException(
                    code = ERROR_PORT_IN_USE,
                    message = "Port ${request.port} is already in use on 127.0.0.1."
                )
            }
            if (records.size >= MAX_MANAGED_SERVERS) {
                throw LocalHttpServerException(
                    code = ERROR_TOO_MANY_SERVERS,
                    message = "The Runtime allows at most $MAX_MANAGED_SERVERS managed local HTTP servers."
                )
            }

            val serviceDirectory = runtime.managedServiceDirectory().apply {
                if (!exists()) check(mkdirs()) {
                    "Unable to create managed service directory: $absolutePath"
                }
            }
            // The handler script is a read-only constant: overwrite it on
            // every start so a stale or truncated copy cannot survive.
            val handlerScript = File(serviceDirectory, HANDLER_SCRIPT_FILE_NAME)
            handlerScript.writeText(TOKEN_HTTP_HANDLER_SCRIPT, Charsets.UTF_8)
            val token = generateToken()
            val logFile = File(serviceDirectory, "http-${request.port}.log")
            val reportFile = File(serviceDirectory, "session-${request.port}.pid")
            if (reportFile.exists()) reportFile.delete()
            // session_launcher opens this path with O_TRUNC (not O_CREAT),
            // matching NativeExecutableProcess' pre-created report file.
            reportFile.outputStream().use { }
            logFile.outputStream().use { }

            val processEnvironment = runtime.managedEnvironment(directory).toMutableMap().apply {
                // The session launcher consumes and clears this variable before
                // exec'ing Python. It is not exposed to the server process.
                put(SESSION_REPORT_ENVIRONMENT_VARIABLE, reportFile.absolutePath)
            }
            val command = listOf(
                runtime.sessionLauncherFile().absolutePath,
                runtime.pythonExecutableFile().absolutePath,
                handlerScript.absolutePath,
                request.port.toString(),
                token,
                "--directory",
                directory.absolutePath
            )
            val process = try {
                ProcessBuilder(command)
                    .directory(directory)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(processEnvironment)
                    }
                    .start()
            } catch (error: Exception) {
                throw LocalHttpServerException(
                    code = ERROR_START_FAILED,
                    message = "Unable to start the managed Python HTTP server: ${error.message ?: error::class.java.name}"
                )
            }
            val logDrainThread = startLogDrain(process, logFile)

            val processGroupId = awaitSessionGroupId(reportFile)
            if (reportFile.exists()) reportFile.delete()
            if (processGroupId == null) {
                process.destroy()
                throw LocalHttpServerException(
                    code = ERROR_START_FAILED,
                    message = "The managed HTTP server did not publish a process group id. See ${logFile.absolutePath}."
                )
            }

            val server = ManagedServer(
                port = request.port,
                directory = directory,
                token = token,
                logFile = logFile,
                processGroupId = processGroupId,
                process = process,
                logDrainThread = logDrainThread,
                startedAtMillis = System.currentTimeMillis()
            )
            records[request.port] = server
            persist(server)

            if (!waitForPort(request.port)) {
                removeRecord(server)
                stopRecord(server)
                throw LocalHttpServerException(
                    code = ERROR_START_FAILED,
                    message = "The managed HTTP server exited or did not listen on 127.0.0.1:${request.port}. See ${logFile.absolutePath}."
                )
            }
            return statusFor(server)
        }
    }

    override fun status(port: Int?): List<LocalHttpServerStatus> {
        synchronized(this) {
            ensureMetadataLoaded()
            if (port != null) validatePort(port)
            val selected = records.values
                .filter { port == null || it.port == port }
                .toList()
            return selected.mapNotNull { server ->
                if (!hasProcess(server)) {
                    removeRecord(server)
                    null
                } else if (isStaleNonListening(server) && server.process == null) {
                    // The persisted process-group id most likely died and was
                    // recycled. status() stays read-only, so drop the dead
                    // record without signaling and let start() rebuild.
                    removeRecord(server)
                    null
                } else {
                    statusFor(server)
                }
            }
        }
    }

    override fun stop(port: Int): LocalHttpServerStatus {
        synchronized(this) {
            ensureMetadataLoaded()
            validatePort(port)
            val server = records[port] ?: return LocalHttpServerStatus.notFound(port)
            if (isUnattributableStaleRecord(server)) {
                // The recorded process-group id can no longer be attributed to
                // this server and may since have been recycled to an unrelated
                // group, so signaling it could kill innocent processes. Only
                // drop the dead record so the port can be rebuilt.
                removeRecord(server)
            } else {
                val stopped = stopRecord(server)
                if (!stopped) {
                    throw LocalHttpServerException(
                        code = ERROR_STOP_FAILED,
                        message = "Unable to terminate the managed HTTP server process group ${server.processGroupId}."
                    )
                }
                removeRecord(server)
            }
            return LocalHttpServerStatus(
                state = STATE_STOPPED,
                port = server.port,
                directory = server.directory.absolutePath,
                url = urlFor(server.port, server.token),
                logFile = server.logFile.absolutePath,
                processGroupId = server.processGroupId
            )
        }
    }

    override fun stopAll(): Int {
        synchronized(this) {
            ensureMetadataLoaded()
            val servers = records.values.toList()
            var stopped = 0
            servers.forEach { server ->
                // stopAll()/close() must keep exactly the same process-group
                // recycling safety semantics as stop(): an unattributable
                // stale record (no live handle, aged, and no longer listening)
                // is only dropped without signaling, because its persisted
                // process-group id may already belong to an unrelated group.
                if (isUnattributableStaleRecord(server)) {
                    discardRecord(server)
                    return@forEach
                }
                // Keep the record for a group that survived the kill window:
                // dropping it would orphan a live process group that the tool
                // can no longer see or stop. This mirrors stop()'s contract.
                if (stopRecord(server)) {
                    stopped++
                    removeRecord(server)
                }
            }
            return stopped
        }
    }

    override fun close() {
        stopAll()
    }

    private fun resolveWorkspaceDirectory(relativePath: String): File {
        require(relativePath.isNotBlank()) { "directory must not be blank" }
        require(!File(relativePath).isAbsolute && !relativePath.contains('\\')) {
            "directory must be a relative path inside the terminal workspace"
        }
        val workspace = runtime.defaultWorkspace().apply {
            if (!exists()) check(mkdirs()) { "Unable to create terminal workspace: $absolutePath" }
        }.canonicalFile
        val candidate = File(workspace, relativePath).canonicalFile
        require(candidate.path == workspace.path || candidate.path.startsWith(workspace.path + File.separator)) {
            "directory must stay inside the terminal workspace"
        }
        return candidate
    }

    private fun validatePort(port: Int) {
        require(port in MIN_PORT..MAX_PORT) {
            "port must be between $MIN_PORT and $MAX_PORT"
        }
    }

    private fun statusFor(server: ManagedServer): LocalHttpServerStatus {
        val processAlive = hasProcess(server)
        val portListening = isPortListening(server.port)
        val state = when {
            processAlive && portListening -> STATE_RUNNING
            processAlive -> STATE_STARTING
            else -> STATE_STOPPED
        }
        return LocalHttpServerStatus(
            state = state,
            port = server.port,
            directory = server.directory.absolutePath,
            url = urlFor(server.port, server.token),
            logFile = server.logFile.absolutePath,
            processGroupId = server.processGroupId
        )
    }

    private fun hasProcess(server: ManagedServer): Boolean {
        val processAlive = server.process?.let(::isAlive) == true
        return processAlive || NativeProcessGroupControl.processGroupExists(server.processGroupId)
    }

    /**
     * Lazy liveness cross-check for records whose existence is only inferred
     * from a persisted process-group id. kill(-pgid, 0) cannot distinguish a
     * dead server from an unrelated group that later recycled the same id, so
     * an aged record that is still not listening is treated as dead. A normal
     * start listens within seconds (and a start that never listens is rolled
     * back immediately), so the grace period never overlaps a real starting
     * phase. No timer or thread is introduced: this runs inside the existing
     * start()/status()/stop() check paths.
     */
    private fun isStaleNonListening(server: ManagedServer): Boolean {
        if (System.currentTimeMillis() - server.startedAtMillis < STALE_RECORD_GRACE_MILLIS) return false
        return !isPortListening(server.port)
    }

    /**
     * True when a record can no longer be attributed to its persisted
     * process-group id: it has no live in-process handle, it is past the
     * stale grace period, and its port is not listening. The recorded group
     * id may since have been recycled to an unrelated group, so signaling it
     * could kill innocent processes — such a record must only be dropped,
     * never signaled. stop(), stopAll() and close() must all preserve this
     * pgid-recycling safety semantics, so they share this one predicate.
     */
    private fun isUnattributableStaleRecord(server: ManagedServer): Boolean {
        return isStaleNonListening(server) && server.process?.let(::isAlive) != true
    }

    /**
     * Removes a record that must not be reused. A record that still owns a
     * live in-process handle cannot have a recycled process-group id, so its
     * group is terminated through the normal stop path. A handle-less
     * (reloaded) record is dropped without signaling for the reason above.
     */
    private fun discardRecord(server: ManagedServer) {
        if (server.process?.let(::isAlive) == true) {
            stopRecord(server)
        }
        removeRecord(server)
    }

    private fun stopRecord(server: ManagedServer): Boolean {
        var groupStopped = !NativeProcessGroupControl.processGroupExists(server.processGroupId)
        if (!groupStopped) {
            NativeProcessGroupControl.signalProcessGroup(server.processGroupId, SIGNAL_TERMINATE)
            groupStopped = waitForProcessGroupExit(server.processGroupId, STOP_GRACE_PERIOD_MILLIS)
            if (!groupStopped) {
                NativeProcessGroupControl.signalProcessGroup(server.processGroupId, SIGNAL_KILL)
                groupStopped = waitForProcessGroupExit(server.processGroupId, STOP_KILL_WAIT_MILLIS)
            }
        }

        server.process?.let { process ->
            if (isAlive(process)) {
                process.destroy()
                waitForExit(process, STOP_KILL_WAIT_MILLIS)
            }
        }
        server.logDrainThread?.let { thread ->
            runCatching { thread.join(STOP_KILL_WAIT_MILLIS) }
        }
        val childStopped = server.process?.let { !isAlive(it) } ?: true
        return groupStopped && childStopped
    }

    private fun removeRecord(server: ManagedServer) {
        records.remove(server.port)
        server.metadataFile.delete()
    }

    private fun persist(server: ManagedServer) {
        val metadataDirectory = runtime.managedServiceDirectory()
        if (!metadataDirectory.exists()) check(metadataDirectory.mkdirs()) {
            "Unable to create managed service directory: ${metadataDirectory.absolutePath}"
        }
        val properties = Properties().apply {
            setProperty(KEY_PORT, server.port.toString())
            setProperty(KEY_DIRECTORY, server.directory.absolutePath)
            // Absent for records persisted before D-028; those reload as
            // legacy servers whose URL has no token path segment.
            server.token?.let { setProperty(KEY_TOKEN, it) }
            setProperty(KEY_LOG_FILE, server.logFile.absolutePath)
            setProperty(KEY_PROCESS_GROUP_ID, server.processGroupId.toString())
        }
        FileOutputStream(server.metadataFile).use { output ->
            properties.store(output, "UGK managed local HTTP server")
        }
    }

    private fun ensureMetadataLoaded() {
        if (metadataLoaded) return
        metadataLoaded = true
        val metadataDirectory = runtime.managedServiceDirectory()
        if (!metadataDirectory.isDirectory) return
        metadataDirectory.listFiles { file -> file.name.startsWith("http-") && file.name.endsWith(".properties") }
            ?.forEach { metadataFile ->
                runCatching {
                    val properties = Properties()
                    FileInputStream(metadataFile).use { input -> properties.load(input) }
                    val port = properties.getProperty(KEY_PORT).toInt()
                    val directory = File(properties.getProperty(KEY_DIRECTORY)).canonicalFile
                    val token = properties.getProperty(KEY_TOKEN)
                    val logFile = File(properties.getProperty(KEY_LOG_FILE)).canonicalFile
                    val processGroupId = properties.getProperty(KEY_PROCESS_GROUP_ID).toInt()
                    check(port in MIN_PORT..MAX_PORT)
                    check(processGroupId > 0)
                    records[port] = ManagedServer(
                        port = port,
                        directory = directory,
                        token = token,
                        logFile = logFile,
                        processGroupId = processGroupId,
                        process = null,
                        logDrainThread = null,
                        startedAtMillis = metadataFile.lastModified(),
                        metadataFile = metadataFile
                    )
                }.onFailure { metadataFile.delete() }
            }
    }

    private fun awaitSessionGroupId(reportFile: File): Int? {
        val deadline = System.currentTimeMillis() + SESSION_REPORT_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            readSessionGroupId(reportFile)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return readSessionGroupId(reportFile)
    }

    private fun readSessionGroupId(reportFile: File): Int? {
        return runCatching { reportFile.readText().trim().toInt() }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    private fun waitForPort(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + PORT_START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening(port)) return true
            Thread.sleep(PORT_POLL_INTERVAL_MILLIS)
        }
        return isPortListening(port)
    }

    private fun isPortListening(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK_HOST, port), SOCKET_CONNECT_TIMEOUT_MILLIS)
            }
            true
        }.getOrDefault(false)
    }

    private fun startLogDrain(process: Process, logFile: File): Thread {
        return Thread({
            runCatching {
                process.inputStream.use { input ->
                    FileOutputStream(logFile, false).use { output ->
                        val buffer = ByteArray(LOG_BUFFER_BYTES)
                        var captured = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (captured < MAX_LOG_BYTES) {
                                val writable = minOf(count, MAX_LOG_BYTES - captured)
                                output.write(buffer, 0, writable)
                                captured += writable
                            }
                        }
                    }
                }
            }
        }, "ugk-http-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun waitForProcessGroupExit(processGroupId: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!NativeProcessGroupControl.processGroupExists(processGroupId)) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return !NativeProcessGroupControl.processGroupExists(processGroupId)
    }

    private fun waitForExit(process: Process, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(process)) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return !isAlive(process)
    }

    /** `Process.isAlive()` is not available on the minSdk-24 API surface. */
    private fun isAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun urlFor(port: Int): String = "http://$LOOPBACK_HOST:$port/"

    private class ManagedServer(
        val port: Int,
        val directory: File,
        // Null only for records persisted before D-028 (legacy servers
        // whose URL has no token path segment).
        val token: String?,
        val logFile: File,
        val processGroupId: Int,
        val process: Process?,
        val logDrainThread: Thread?,
        // Reloaded records use the metadata file's mtime: persist() writes it
        // at start time and a failed start rolls the record back at once, so
        // it approximates the start time without changing the persisted
        // properties format.
        val startedAtMillis: Long,
        val metadataFile: File = File(
            logFile.parentFile ?: logFile,
            "http-$port.properties"
        )
    )

    internal companion object {
        const val MIN_PORT = 1_024
        const val MAX_PORT = 65_535
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_LOCAL_HTTP_SERVER_PORT = 8_765
        const val STATE_RUNNING = "running"
        const val STATE_STARTING = "starting"
        const val STATE_STOPPED = "stopped"
        const val STATE_NOT_FOUND = "not_found"
        const val ERROR_PORT_IN_USE = "PORT_IN_USE"
        const val ERROR_TOO_MANY_SERVERS = "TOO_MANY_SERVERS"
        const val ERROR_START_FAILED = "START_FAILED"
        const val ERROR_STOP_FAILED = "STOP_FAILED"
        const val KEY_PORT = "port"
        const val KEY_DIRECTORY = "directory"
        const val KEY_TOKEN = "token"
        const val KEY_LOG_FILE = "logFile"
        const val KEY_PROCESS_GROUP_ID = "processGroupId"
        const val HANDLER_SCRIPT_FILE_NAME = "token_http_handler.py"
        const val TOKEN_BYTES = 16
        const val BASE64_URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val SESSION_REPORT_ENVIRONMENT_VARIABLE = "UGK_TERMINAL_SESSION_REPORT_FILE"
        private const val SIGNAL_TERMINATE = 15
        private const val SIGNAL_KILL = 9
        private const val POLL_INTERVAL_MILLIS = 10L
        private const val PORT_POLL_INTERVAL_MILLIS = 50L
        private const val PORT_START_TIMEOUT_MILLIS = 3_000L
        private const val SESSION_REPORT_WAIT_MILLIS = 500L
        private const val STOP_GRACE_PERIOD_MILLIS = 500L
        private const val STOP_KILL_WAIT_MILLIS = 1_000L
        private const val STALE_RECORD_GRACE_MILLIS = 120_000L
        private const val SOCKET_CONNECT_TIMEOUT_MILLIS = 100
        private const val MAX_MANAGED_SERVERS = 4
        private const val MAX_LOG_BYTES = 64 * 1024
        private const val LOG_BUFFER_BYTES = 8 * 1024
        val RUNNING_STATES = setOf(STATE_RUNNING, STATE_STARTING)

        /**
         * Fresh token for one server start: 16 SecureRandom bytes in unpadded
         * URL-safe Base64 (22 characters). Other Apps on the device share the
         * loopback port space, so the URL must be unguessable, not just bound
         * to 127.0.0.1.
         */
        internal fun generateToken(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return encodeBase64Url(bytes)
        }

        /**
         * Minimal unpadded URL-safe Base64 encoder. `java.util.Base64` needs
         * API 26 and `android.util.Base64` is not callable from JVM unit
         * tests, so the Runtime ships its own encoder for this minSdk-24 path.
         */
        internal fun encodeBase64Url(bytes: ByteArray): String {
            val result = StringBuilder()
            var index = 0
            while (index < bytes.size) {
                val b0 = bytes[index].toInt() and 0xff
                val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xff else -1
                val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xff else -1
                result.append(BASE64_URL_ALPHABET[b0 ushr 2])
                result.append(
                    BASE64_URL_ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)]
                )
                if (b1 < 0) break
                result.append(
                    BASE64_URL_ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)]
                )
                if (b2 < 0) break
                result.append(BASE64_URL_ALPHABET[b2 and 0x3f])
                index += 3
            }
            return result.toString()
        }

        /**
         * Token-gated URL for a managed server. A null token is a legacy
         * pre-D-028 record and keeps the old root URL so status()/stop() keep
         * describing what that server actually serves.
         */
        internal fun urlFor(port: Int, token: String?): String {
            return if (token.isNullOrBlank()) {
                "http://$LOOPBACK_HOST:$port/"
            } else {
                "http://$LOOPBACK_HOST:$port/$token/"
            }
        }

        /**
         * Non-null when start() must not silently reuse a RUNNING server
         * because it serves a different directory than the one requested.
         * Returning the running status anyway would report success while the
         * port keeps serving the old tree.
         */
        internal fun directoryReuseError(
            port: Int,
            runningDirectory: File,
            requestedDirectory: File
        ): LocalHttpServerException? {
            if (runningDirectory.absolutePath == requestedDirectory.absolutePath) return null
            return LocalHttpServerException(
                code = ERROR_PORT_IN_USE,
                message = "Port $port is already serving a different directory " +
                    "(${runningDirectory.absolutePath} instead of ${requestedDirectory.absolutePath}). " +
                    "Stop it first or choose another port."
            )
        }

        /**
         * Fixed handler served by the managed Python process. Requirements:
         * standard library only; bind 127.0.0.1; answer 404 unless the first
         * URL path segment equals the per-start token; reject paths whose
         * realpath leaves the served root (symlink containment).
         */
        internal const val TOKEN_HTTP_HANDLER_SCRIPT = """# Token-gated static HTTP server for the UGK Android Terminal Runtime.
#
# Standard library only. Every request URL must begin with the per-start
# random token path segment created by the Runtime; any other path answers
# 404, so other apps on the shared loopback interface cannot enumerate the
# served tree. A path whose mapped local file resolves outside the served
# root, for example through a symlink, is rejected with 404 as well.

import argparse
import os
import urllib.parse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

BIND_HOST = "127.0.0.1"


class SymlinkEscape(Exception):
    # Raised when a requested path resolves outside the served root.
    pass


class TokenGatedRequestHandler(SimpleHTTPRequestHandler):
    token = ""

    def token_matches(self):
        path = urllib.parse.urlsplit(self.path).path
        first_segment = path.lstrip("/").split("/", 1)[0]
        return first_segment == self.token

    def path_without_token(self, path):
        requested = urllib.parse.urlsplit(path).path
        rest = requested.lstrip("/")
        if rest == self.token:
            return "/"
        if rest.startswith(self.token + "/"):
            return "/" + rest[len(self.token) + 1:]
        return requested

    def do_GET(self):
        if not self.token_matches():
            self.send_error(404)
            return
        try:
            super().do_GET()
        except SymlinkEscape:
            self.send_error(404)

    def do_HEAD(self):
        if not self.token_matches():
            self.send_error(404)
            return
        try:
            super().do_HEAD()
        except SymlinkEscape:
            self.send_error(404)

    def translate_path(self, path):
        local = super().translate_path(self.path_without_token(path))
        root = os.path.realpath(self.directory)
        resolved = os.path.realpath(local)
        if resolved != root and not resolved.startswith(root + os.sep):
            raise SymlinkEscape(path)
        return local


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("port", type=int)
    parser.add_argument("token")
    parser.add_argument("--directory", default=os.getcwd())
    arguments = parser.parse_args()
    TokenGatedRequestHandler.token = arguments.token
    handler = partial(TokenGatedRequestHandler, directory=arguments.directory)
    server = ThreadingHTTPServer((BIND_HOST, arguments.port), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
"""
    }
}

const val DEFAULT_LOCAL_HTTP_SERVER_PORT: Int = 8_765
