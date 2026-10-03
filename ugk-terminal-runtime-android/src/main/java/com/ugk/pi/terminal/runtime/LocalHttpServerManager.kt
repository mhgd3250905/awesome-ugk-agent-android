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

    /**
     * Releases the resources this controller instance owns. The default
     * mirrors the historical behavior and stops everything this process
     * recorded (test doubles keep that); [LocalHttpServerManager] narrows
     * it to the servers that instance started, so a side runtime closing
     * cannot terminate a foreground server (D-031).
     */
    fun close() {
        stopAll()
    }
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
 * then applies two containment rules to what a token holder may read: a
 * local path whose realpath escapes the served root is refused, which blocks
 * symlink escapes planted inside the workspace, and a regular file carrying
 * more than one link is refused, because a hard link resolves to its own
 * served-tree name and would otherwise publish any same-UID file.
 */
class LocalHttpServerManager(
    private val runtime: BashRuntime
) : LocalHttpServerController, AutoCloseable {
    constructor(context: Context) : this(BashRuntime(context))

    /**
     * Identifies this instance inside the process-wide record table (D-031).
     * The table itself lives in the companion: the lock, the port space, the
     * service directory and the persisted metadata are all shared process
     * resources, so an instance-private copy of the records was never more
     * than a stale view of them.
     */
    private val ownerId: Long = OWNER_ID_SEQUENCE.incrementAndGet()

    override fun start(request: LocalHttpServerRequest): LocalHttpServerStatus {
        synchronized(PROCESS_LOCK) {
            ensureMetadataLoaded()
            validatePort(request.port)
            val directory = resolveWorkspaceDirectory(request.directory)
            check(directory.isDirectory) {
                "Local HTTP server directory does not exist: ${directory.absolutePath}"
            }

            records[request.port]?.let { existing ->
                val existingStatus = statusFor(existing)
                // Reuse only what this call can still attribute to the server
                // it is describing; an unattributable row is rebuilt, not reused.
                val attributable = queryDispositionFor(existing) == QueryDisposition.REPORT
                if (existingStatus.state in RUNNING_STATES && attributable) {
                    directoryReuseError(request.port, existing.directory, directory)?.let { failure ->
                        throw failure
                    }
                    return existingStatus
                }
                // Nothing answers this port as our server, so honouring the
                // request means taking the port over - but only once that is
                // established. Dropping the record first deletes the persisted
                // metadata, the only surviving copy of the issued token, and the
                // port check below can still refuse the start; that ordering
                // leaves a live server with no record at all, which is exactly
                // the state status() was changed away from. An earlier shape of
                // this method did it in that order.
                if (isPortListening(request.port)) {
                    throw LocalHttpServerException(
                        code = ERROR_PORT_IN_USE,
                        message = "Port ${request.port} is answering on 127.0.0.1 but the record for " +
                            "${existing.directory.absolutePath} can no longer be attributed to a server " +
                            "this Runtime can describe. Stop it first or choose another port."
                    )
                }
                removeRecord(existing)
            }

            if (isPortListening(request.port)) {
                throw LocalHttpServerException(
                    code = ERROR_PORT_IN_USE,
                    message = "Port ${request.port} is already in use on 127.0.0.1."
                )
            }
            if (records.size >= MAX_MANAGED_SERVERS) {
                // After an app restart the records reload from disk and only a
                // lifecycle call prunes them - a host that starts a new server
                // without calling status() first would deterministically hit
                // this cap on records whose servers are long dead. Free every
                // record this call cannot attribute to a live service, and do
                // it through the same disposition status()/stop() read: an
                // earlier shape pruned only "process group gone" rows, and once
                // status() stopped forgetting unattributable ones that left the
                // cap permanently full. Dropping is bookkeeping only - no
                // signal is sent to a group that may belong to somebody else.
                records.values.toList().forEach { candidate ->
                    if (queryDispositionFor(candidate) != QueryDisposition.REPORT) {
                        removeRecord(candidate)
                    }
                }
                if (records.size >= MAX_MANAGED_SERVERS) {
                    throw LocalHttpServerException(
                        code = ERROR_TOO_MANY_SERVERS,
                        message = "The Runtime allows at most $MAX_MANAGED_SERVERS managed local HTTP servers."
                    )
                }
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
            val builder = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(processEnvironment)
                }
            val process = try {
                // Same spawn helper as Bash: this child is a server, never an
                // interactive reader, and an open stdin pipe is a descriptor
                // the parent would otherwise hold for the server's whole life.
                NativeExecutableProcess.spawnWithStdinClosed(builder)
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
                startedAtMillis = System.currentTimeMillis(),
                ownerId = this.ownerId
            )
            records[request.port] = server
            persist(server)

            // A bare port wait cannot distinguish our server from a foreign
            // bind-race winner: waitForPort only proves that SOMETHING
            // accepted a connection, and our own server completes the TCP
            // handshake at bind/listen time slightly before serve_forever
            // answers requests. Poll for OUR token path within the same
            // start budget; only then is the token URL we report actually
            // served by this server.
            if (!waitForTokenServed(request.port, token)) {
                // Inspect the port BEFORE tearing our group down: after
                // stopRecord() a killed listener would look like "server
                // exited" and muddle the diagnosis.
                val foreignOrDeafListener = isPortListening(request.port)
                removeRecord(server)
                stopRecord(server)
                val logTail = runCatching { logFile.readText().takeLast(600) }.getOrDefault("")
                if (foreignOrDeafListener) {
                    throw LocalHttpServerException(
                        code = ERROR_PORT_IN_USE,
                        message = "Port ${request.port} is listening but not serving this server's token path; " +
                            "another process likely took the port during startup. " +
                            "Log ${logFile.absolutePath}: $logTail"
                    )
                }
                throw LocalHttpServerException(
                    code = ERROR_START_FAILED,
                    message = "The managed HTTP server exited or did not listen on 127.0.0.1:${request.port}. " +
                        "Log ${logFile.absolutePath}: $logTail"
                )
            }
            return statusFor(server)
        }
    }

    override fun status(port: Int?): List<LocalHttpServerStatus> {
        synchronized(PROCESS_LOCK) {
            ensureMetadataLoaded()
            if (port != null) validatePort(port)
            val selected = records.values
                .filter { port == null || it.port == port }
                .toList()
            return selected.mapNotNull { server ->
                when (queryDispositionFor(server)) {
                    QueryDisposition.FORGET_CONFIRMED_DEAD -> {
                        removeRecord(server)
                        null
                    }
                    QueryDisposition.REPORT_UNATTRIBUTABLE ->
                        // A query must not forget what it only failed to
                        // observe: dropping the record here would delete the
                        // persisted metadata, so the token that the caller was
                        // handed would exist nowhere and the port could never
                        // be stopped or described again.
                        unattributableStatusFor(server)
                    QueryDisposition.REPORT -> statusFor(server)
                }
            }
        }
    }

    override fun stop(port: Int): LocalHttpServerStatus {
        synchronized(PROCESS_LOCK) {
            ensureMetadataLoaded()
            validatePort(port)
            val server = records[port] ?: return LocalHttpServerStatus.notFound(port)
            val reported = when (stopDispositionFor(server)) {
                StopDisposition.DROP_CONFIRMED_DEAD -> {
                    // Nothing is running under that process-group id, so
                    // "stopped" is a claim this call can actually support.
                    removeRecord(server)
                    STATE_STOPPED
                }
                StopDisposition.DROP_UNATTRIBUTABLE -> {
                    // The recorded id may have been recycled by an unrelated
                    // group, so signalling it could kill innocent processes.
                    // Dropping the record is the safe half; claiming the
                    // service is down is not, because it may well be up.
                    removeRecord(server)
                    STATE_UNATTRIBUTABLE
                }
                StopDisposition.SIGNAL_PROCESS_GROUP -> {
                    if (!stopRecord(server)) {
                        throw LocalHttpServerException(
                            code = ERROR_STOP_FAILED,
                            message = "Unable to terminate the managed HTTP server process group ${server.processGroupId}."
                        )
                    }
                    removeRecord(server)
                    STATE_STOPPED
                }
            }
            return LocalHttpServerStatus(
                state = reported,
                port = server.port,
                directory = server.directory.absolutePath,
                url = urlFor(server.port, server.token),
                logFile = server.logFile.absolutePath,
                processGroupId = server.processGroupId
            )
        }
    }

    override fun stopAll(): Int = synchronized(PROCESS_LOCK) {
        releaseRecords { true }
    }

    /**
     * Releases only the servers this instance started (D-031). A host runs
     * several runtimes side by side - the demo app keeps the main
     * conversation runtime alive while a teaching runtime comes and goes -
     * and the previous whole-table stopAll() here let a closing side runtime
     * terminate a foreground server, and delete its token metadata, that
     * another live instance was still serving. Records rehydrated from disk
     * have no owner and stay available to explicit stop()/stopAll().
     */
    override fun close() {
        synchronized(PROCESS_LOCK) {
            releaseRecords { closeReleasesRecord(it.ownerId, ownerId) }
        }
    }

    private fun releaseRecords(release: (ManagedServer) -> Boolean): Int {
        ensureMetadataLoaded()
        val servers = records.values.toList()
        var stopped = 0
        servers.forEach { server ->
            if (!release(server)) return@forEach
            when (stopDispositionFor(server)) {
                // Both drop-without-signalling rows: the group is either
                // provably gone or no longer attributable, and in neither
                // case may stopAll() signal it. Neither is counted in the
                // returned total, because nothing was terminated here - the
                // total is the number of groups this call actually brought
                // down, so a caller must not read "stopped N" as "N records
                // were cleared". No caller today consumes it.
                StopDisposition.DROP_CONFIRMED_DEAD,
                StopDisposition.DROP_UNATTRIBUTABLE -> removeRecord(server)
                StopDisposition.SIGNAL_PROCESS_GROUP -> {
                    // Keep the record for a group that survived the kill
                    // window: dropping it would orphan a live process group
                    // that the tool can no longer see or stop. This mirrors
                    // stop()'s contract, which raises STOP_FAILED instead.
                    if (stopRecord(server)) {
                        stopped++
                        removeRecord(server)
                    }
                }
            }
        }
        return stopped
    }

    private fun resolveWorkspaceDirectory(relativePath: String): File {
        directoryScreeningError(relativePath)?.let { reason ->
            throw IllegalArgumentException(reason)
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
        val portListening = answersAsOurServer(server)
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

    /**
     * Description of a record this call already probed and could not
     * attribute. Deliberately not built through [statusFor]: that helper re-runs
     * the process-group and port probes that [queryDisposition] has just paid
     * for, so each unattributable row would cost a second pair of probes.
     */
    private fun unattributableStatusFor(server: ManagedServer): LocalHttpServerStatus {
        return LocalHttpServerStatus(
            state = STATE_UNATTRIBUTABLE,
            port = server.port,
            directory = server.directory.absolutePath,
            url = urlFor(server.port, server.token),
            logFile = server.logFile.absolutePath,
            processGroupId = server.processGroupId
        )
    }

    private fun hasProcess(server: ManagedServer): Boolean = processEvidencePresent(
        handleAlive = server.process?.let(::isAlive) == true,
        probeUsable = NativeProcessGroupControl.isAvailable(),
        probeAnswer = { NativeProcessGroupControl.processGroupExists(server.processGroupId) }
    )

    /**
     * Whether the responder on this port is this record's own server.
     *
     * A bare connect only proves something holds the port. For a record this
     * process did not start there is no other attribution available: the
     * process-group probe cannot do it either, because
     * [NativeProcessGroupControl.processGroupExists] deliberately treats
     * `EPERM` as "exists", so a group recycled to any other owner keeps the
     * record looking alive. Only the token-gated handler can identify itself,
     * and this is the same helper start() uses to attribute a server it just
     * launched - without it a foreign listener makes status() report `running`
     * with a token URL nobody is serving, and start() reuses that port.
     *
     * A record this process started is attributed by the handle alone: its
     * process-group id came from our own session launcher, so a plain connect
     * is enough and stays cheap on the hot status path.
     */
    private fun answersAsOurServer(server: ManagedServer): Boolean {
        return if (server.process != null) {
            isPortListening(server.port)
        } else {
            isTokenServed(server.port, server.token)
        }
    }

    /**
     * One of the two observations the disposition tables need. The grace period
     * exists because kill(-pgid, 0) cannot distinguish a dead server from an
     * unrelated group that later recycled the same id: a normal start listens
     * within seconds, and a start that never listens is rolled back at once, so
     * a record that is still deaf after this long is no longer evidence of a
     * server this Runtime owns. Kept separate from the port probe so
     * [queryDisposition] and [stopDisposition] can skip the socket connect
     * entirely for a record that has not aged.
     */
    private fun isPastStaleGrace(server: ManagedServer): Boolean =
        System.currentTimeMillis() - server.startedAtMillis >= STALE_RECORD_GRACE_MILLIS

    private fun queryDispositionFor(server: ManagedServer): QueryDisposition = queryDisposition(
        processHandleAlive = server.process?.let(::isAlive) == true,
        processGroupExists = { groupProbe(server) },
        pastStaleGrace = isPastStaleGrace(server),
        portListening = { answersAsOurServer(server) }
    )

    private fun stopDispositionFor(server: ManagedServer): StopDisposition = stopDisposition(
        processHandleAlive = server.process?.let(::isAlive) == true,
        processGroupExists = { groupProbe(server) },
        pastStaleGrace = isPastStaleGrace(server),
        portListening = { answersAsOurServer(server) }
    )

    /**
     * Group existence as the lifecycle tables may treat it (D-031): a probe
     * that cannot run does not say "absent". Treating an unobservable group
     * as gone let status() FORGET a live record - deleting the only copy of
     * its issued token - and let stop() report `stopped` for a server still
     * running.
     */
    private fun groupProbe(server: ManagedServer): Boolean = groupExistsForDisposition(
        probeUsable = NativeProcessGroupControl.isAvailable(),
        probeAnswer = NativeProcessGroupControl.processGroupExists(server.processGroupId)
    )

    private fun stopRecord(server: ManagedServer): Boolean {
        // Fail closed (D-031): without the native probe a process group can be
        // neither signalled nor observed. A record this instance still holds a
        // Process handle for is torn down through that handle, and the handle
        // stands in for group evidence; a handle-less record reports failure
        // instead of claiming a stop that cannot be performed or verified.
        val plan = groupStopPlan(
            probeUsable = NativeProcessGroupControl.isAvailable(),
            groupExists = { NativeProcessGroupControl.processGroupExists(server.processGroupId) },
            holdsProcessHandle = server.process != null
        )
        var groupStopped = plan == GroupStopPlan.ALREADY_GONE
        if (plan == GroupStopPlan.SIGNAL_AND_VERIFY) {
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
        // Properties.store issues several small writes; an in-place write
        // leaves a truncated file behind when the process dies mid-store.
        // ensureMetadataLoaded then deletes the unreadable record while the
        // Python server keeps running — the port becomes un-stoppable and
        // un-rebuildable for the rest of the process lifetime. Stage + rename
        // keeps the metadata file all-or-nothing.
        val staged = File.createTempFile(".http-${server.port}-", ".tmp", metadataDirectory)
        try {
            FileOutputStream(staged).use { output ->
                properties.store(output, "UGK managed local HTTP server")
            }
            // No delete-then-rename here. This file is the only surviving copy of
            // the token handed to the caller, and deleting it before a second
            // rename attempt can leave a live server whose record - and therefore
            // whose stop() and its own URL - no longer exist, which is exactly the
            // state D-031 was written to prevent. File.renameTo does replace an
            // existing target on Android's filesystem; on a JVM where it cannot,
            // the failure is surfaced loudly and the existing record is left
            // intact instead of being destroyed on the way to a retry.
            check(staged.renameTo(server.metadataFile)) {
                "Unable to persist local HTTP server metadata for port ${server.port}; " +
                    "the record already on disk for that port is unchanged."
            }
        } finally {
            if (staged.exists()) staged.delete()
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
                        // Rehydrated rows belong to no live instance: a side
                        // runtime's close() must not claim them (D-031).
                        ownerId = null,
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

    /**
     * Polls until OUR token-gated handler actually answers. The previous
     * port-only wait bounded kernel-level binding, which completes at
     * listen() — long before CPython reaches serve_forever on a cold start
     * (seconds on slow emulators / low-end devices). Reporting success at
     * bind time hands the caller a token URL that is not served yet, and a
     * foreign bind-race winner is indistinguishable from our own bind, so
     * the poll targets a token GET instead and carries its own, longer
     * budget: a start whose server never serves is a failure worth waiting a
     * few extra seconds to detect correctly.
     */
    private fun waitForTokenServed(port: Int, token: String): Boolean {
        val deadline = System.currentTimeMillis() + SERVE_START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (isTokenServed(port, token)) return true
            Thread.sleep(PORT_POLL_INTERVAL_MILLIS)
        }
        return isTokenServed(port, token)
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
        // Which manager instance started this server (D-031): close()
        // releases only matching records. Null for records rehydrated from
        // disk, which no live instance owns - a side runtime closing must
        // not claim them either. Not persisted: ownership is a property of
        // this process, and start() reuse deliberately keeps the original
        // owner so a coexisting instance cannot steal the record.
        val ownerId: Long?,
        val metadataFile: File = File(
            logFile.parentFile ?: logFile,
            "http-$port.properties"
        )
    )

    internal companion object {
        /**
         * All manager instances in a process share the same on-disk service
         * directory (one fixed handler script file name) and the same
         * device-wide loopback port space, so per-instance monitors cannot
         * keep two instances from interleaving their starts and corrupting
         * the shared handler script. Every operation synchronizes on this
         * process-wide lock instead of the instance.
         */
        private val PROCESS_LOCK = Any()

        /**
         * The process-wide record table (D-031). It used to be an instance
         * field while the lock, the port space, the service directory and the
         * persisted metadata were all shared, so one instance's close()
         * rehydrated the shared disk records into its own stale view and
         * terminated servers another live instance was still serving. The
         * table is loaded once per process; [ManagedServer.ownerId] carries
         * the per-instance attribution close() filters on.
         */
        private val records = linkedMapOf<Int, ManagedServer>()

        private var metadataLoaded = false

        private val OWNER_ID_SEQUENCE = java.util.concurrent.atomic.AtomicLong()

        /**
         * Whether close() on the instance identified by [closingOwnerId] may
         * release the record started by [recordOwnerId] (D-031). Split out as
         * a pure rule so the ownership contract is pinnable on the JVM:
         * widening this to `true` restores the cross-instance kill.
         */
        internal fun closeReleasesRecord(recordOwnerId: Long?, closingOwnerId: Long): Boolean =
            recordOwnerId == closingOwnerId

        /**
         * Screens a model-authored directory before it becomes an argv element of
         * the server process and the root its handler serves from. Returns the
         * refusal reason, or null when the value may be resolved.
         *
         * Split out from [resolveWorkspaceDirectory] because the rest of that
         * path needs an Android Context and a native process: the screen itself
         * is pure, so JVM tests can pin it.
         */
        internal fun directoryScreeningError(relativePath: String): String? = when {
            relativePath.isBlank() -> "directory must not be blank"
            File(relativePath).isAbsolute || relativePath.contains('\\') -> {
                "directory must be a relative path inside the terminal workspace"
            }
            // argv entries end at a NUL, so an unscreened value would make the
            // child serve a truncated prefix of the directory the caller - and
            // the confirmation ticket - named.
            relativePath.contains('\u0000') -> "directory must not contain a NUL character"
            else -> null
        }

        const val MIN_PORT = 1_024
        const val MAX_PORT = 65_535
        const val LOOPBACK_HOST = "127.0.0.1"
        const val STATE_RUNNING = "running"
        const val STATE_STARTING = "starting"
        const val STATE_STOPPED = "stopped"
        const val STATE_NOT_FOUND = "not_found"

        /**
         * A record the Runtime can no longer attribute to a process it started:
         * the persisted process-group id is still taken, but it may have been
         * recycled by an unrelated group, so signalling it could kill innocent
         * processes. The Runtime has dropped or kept the record without
         * terminating anything, and the service may still be up. Reporting
         * `stopped` here would tell the model a loopback file server is down
         * when it might be serving its workspace to the token URL right now.
         */
        const val STATE_UNATTRIBUTABLE = "unattributable"

        /**
         * Group existence as the disposition tables may treat it, split out so
         * the rule is pinnable on the JVM: a probe that cannot run must not be
         * read as "the group is gone" (D-031). Reverting this to
         * `probeAnswer` alone lets status() forget live records and stop()
         * report `stopped` for servers still running whenever the native
         * library fails to load.
         */
        internal fun groupExistsForDisposition(probeUsable: Boolean, probeAnswer: Boolean): Boolean =
            if (probeUsable) probeAnswer else true

        /**
         * Whether one record may still be treated as backed by a live process.
         *
         * [groupExistsForDisposition] alone is not the answer: a process this
         * instance still holds is direct evidence, and the group probe only
         * substitutes for it. Pinning the composition here is what makes
         * `hasProcess()` decidable on the host at all - before it, dropping the
         * probe term or reading an unobservable group as "gone" left every JVM
         * test green.
         *
         * [probeAnswer] stays lazy because it is a JNI `kill(-pgid, 0)`: a live
         * handle, or an unusable probe, must not pay for it. That is the same
         * reason `queryDisposition` and `stopDisposition` take their probes as
         * lambdas.
         */
        internal fun processEvidencePresent(
            handleAlive: Boolean,
            probeUsable: Boolean,
            probeAnswer: () -> Boolean
        ): Boolean = handleAlive || if (probeUsable) probeAnswer() else true

        /**
         * The three observations `stopRecord()` can make, as one decision table.
         *
         * An unusable probe says nothing about the group, so only a handle this
         * instance holds can stand in for group evidence (D-031); without one,
         * claiming "stopped" would delete the record and its only copy of the
         * issued token while a server may still be serving.
         *
         * [groupExists] is lazy for the same reason as above: with no probe to
         * signal anything, asking whether the group exists costs a JNI call and
         * answers nothing.
         */
        internal fun groupStopPlan(
            probeUsable: Boolean,
            groupExists: () -> Boolean,
            holdsProcessHandle: Boolean
        ): GroupStopPlan = when {
            !probeUsable -> if (holdsProcessHandle) GroupStopPlan.ALREADY_GONE else GroupStopPlan.UNVERIFIABLE
            !groupExists() -> GroupStopPlan.ALREADY_GONE
            else -> GroupStopPlan.SIGNAL_AND_VERIFY
        }

        /**
         * What status() may conclude about one record from four observations.
         *
         * The ruler is whether a process this instance still holds is alive, not
         * whether a handle object merely exists: once our own session leader has
         * been reaped, its process-group id is on its way back to the kernel's
         * pool and no longer says anything about our server.
         *
         * Split out because deciding it needs no Android Context and no live
         * process, while every surrounding path needs both: this is the only
         * place the rule can be pinned with a host test.
         *
         * The two probes are lazy on purpose - the truth table is asserted with
         * a counter for how often each runs.
         */
        internal fun queryDisposition(
            processHandleAlive: Boolean,
            processGroupExists: () -> Boolean,
            pastStaleGrace: Boolean,
            portListening: () -> Boolean
        ): QueryDisposition {
            if (!processHandleAlive && !processGroupExists()) return QueryDisposition.FORGET_CONFIRMED_DEAD
            if (processHandleAlive) return QueryDisposition.REPORT
            if (!pastStaleGrace) return QueryDisposition.REPORT
            return if (portListening()) {
                QueryDisposition.REPORT
            } else {
                // Nothing we started is demonstrably running behind that id, and it
                // has been deaf past the grace period: the group id behind it
                // may belong to somebody else now. Report that honestly and
                // keep the record - a query that deletes it would destroy the
                // only place the issued token still exists.
                QueryDisposition.REPORT_UNATTRIBUTABLE
            }
        }

        /**
         * What stop() and stopAll() may do to one record, on the same ruler
         * status() reads from.
         *
         * Three things grant attribution, and any one is enough: a process this
         * instance started is still running; the record is younger than the
         * grace period (its metadata was written during a start that either
         * succeeded or rolled itself back, so the group is recent enough to be
         * ours by history); or the port still answers this record's own token
         * path. When none of them hold - an aged, deaf record rehydrated from
         * disk - the persisted id may have been recycled to an unrelated group,
         * so it is dropped rather than signalled. That leaks a process group in
         * the case where it really was our own orphan, which is the cheaper of
         * the two failures and the stance this class has taken since D-028.
         */
        internal fun stopDisposition(
            processHandleAlive: Boolean,
            processGroupExists: () -> Boolean,
            pastStaleGrace: Boolean,
            portListening: () -> Boolean
        ): StopDisposition {
            if (processHandleAlive) return StopDisposition.SIGNAL_PROCESS_GROUP
            if (!pastStaleGrace) return StopDisposition.SIGNAL_PROCESS_GROUP
            if (portListening()) return StopDisposition.SIGNAL_PROCESS_GROUP
            return if (processGroupExists()) {
                StopDisposition.DROP_UNATTRIBUTABLE
            } else {
                StopDisposition.DROP_CONFIRMED_DEAD
            }
        }
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
        private const val SERVE_START_TIMEOUT_MILLIS = 10_000L
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
         * True only when the responder on [port] proves it is OUR token-gated
         * handler: the token path answers 200, a fresh unguessable decoy
         * path does not, and the bare path does not.
         *
         * The token path alone cannot attribute the responder: a foreign
         * process that won the bind race and answers 200 to every path would
         * satisfy a token-only check, and start() would then hand out a token
         * URL served by someone else's content. The bare-path probe alone is
         * not enough either: a foreign catch-all that 404s only the root
         * would pass it. Our handler 404s every path that does not start with
         * the real token, so a responder that answers 200 to the real token
         * but not to an unguessable decoy (nor to the bare path) must know
         * the token. Legacy tokenless records keep the bare-connect contract
         * and always attribute.
         */
        internal fun isTokenServed(
            port: Int,
            token: String?,
            connectTimeoutMillis: Int = SOCKET_CONNECT_TIMEOUT_MILLIS
        ): Boolean {
            if (token.isNullOrBlank()) return true
            if (!isStatusLine(port, "/$token/", " 200 ", connectTimeoutMillis)) return false
            // Fail closed: when either negative probe itself errors, the
            // responder is not provably ours.
            val decoyStatusLine = probeStatusLine(port, "/${generateToken()}/", connectTimeoutMillis)
                ?: return false
            if (decoyStatusLine.contains(" 200 ")) return false
            val bareStatusLine = probeStatusLine(port, "/", connectTimeoutMillis)
                ?: return false
            return !bareStatusLine.contains(" 200 ")
        }

        private fun isStatusLine(
            port: Int,
            path: String,
            expectedFragment: String,
            connectTimeoutMillis: Int
        ): Boolean {
            return probeStatusLine(port, path, connectTimeoutMillis)
                ?.contains(expectedFragment) == true
        }

        private fun probeStatusLine(
            port: Int,
            path: String,
            connectTimeoutMillis: Int
        ): String? {
            // Raw socket HTTP/1.0 probe: HttpURLConnection routes through the
            // JVM proxy selector and response pooling, neither of which is
            // wanted for a loopback liveness check.
            return runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(
                        java.net.InetSocketAddress(LOOPBACK_HOST, port),
                        connectTimeoutMillis
                    )
                    socket.soTimeout = connectTimeoutMillis
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("GET $path HTTP/1.0\r\n")
                    writer.write("Host: $LOOPBACK_HOST\r\n\r\n")
                    writer.flush()
                    socket.getInputStream().bufferedReader().readLine()
                }
            }.getOrNull()
        }

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
         * URL path segment equals the per-start token; and refuse any file the
         * request would actually open whose realpath leaves the served root or
         * which shares its inode with another name - covering both symlink and
         * hard-link containment, and the index file `send_head()` selects for a
         * directory request after `translate_path()` has already run.
         *
         * Locked by [LocalHttpServerHandlerContainmentTest], which runs these
         * exact bytes under a real interpreter. The text assertions in
         * LocalHttpServerManagerTest are a structural smoke check only: they
         * stay green when the logic below is semantically broken (measured,
         * see the round report).
         */
        internal const val TOKEN_HTTP_HANDLER_SCRIPT = """# Token-gated static HTTP server for the UGK Android Terminal Runtime.
#
# Standard library only. Every request URL must begin with the per-start
# random token path segment created by the Runtime; any other path answers
# 404, so other apps on the shared loopback interface cannot enumerate the
# served tree. A request that carries the token is then narrowed to what this
# root may actually publish, by judging the file that would really be opened:
#
#   * it must resolve inside the served root, which blocks a symlink planted
#     in the workspace pointing at any other app-private file; and
#   * it must not share its inode with another name, which blocks a hard link,
#     because a hard link resolves to its own served-tree name and realpath
#     cannot tell it apart from the file it was made from.
#
# Both rules follow the link chain: a symlink inside the root that lands on a
# multi-link file is refused too, and a directory request is judged again for
# the index file the standard library picks on its own.
#
# Known limit, stated rather than papered over: the decision is taken on the
# resolved path before open(), so a process already running as this same UID
# could swap the path for a link in between. That racer can read and copy the
# file directly, so the window adds no capability this UID does not already
# have - the boundary being protected here is another app's, not this app's.

import argparse
import os
import stat
import urllib.parse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

BIND_HOST = "127.0.0.1"
# Used only if the standard library stops exposing index_pages; it is then a
# floor rather than a full account of what the handler may open.
FALLBACK_INDEX_PAGES = ("index.html", "index.htm")

SYMLINK_ESCAPE_MESSAGE = "Path resolves outside the served root"
HARD_LINK_MESSAGE = "Multiple-link file refused"
MALFORMED_PATH_MESSAGE = "Path could not be resolved"


class ServedRootEscape(Exception):
    # Raised when a mapped local path must not be published by this root.
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
            self.refuse()
            return
        try:
            super().do_GET()
        except ServedRootEscape as error:
            self.refuse(error)

    def do_HEAD(self):
        if not self.token_matches():
            self.refuse()
            return
        try:
            super().do_HEAD()
        except ServedRootEscape as error:
            self.refuse(error)

    def refuse(self, error=None):
        if error is None:
            # No detail: an unauthenticated probe must not be able to tell an
            # absent path from a refused one, so the tree cannot be probed.
            self.send_error(404)
        else:
            # Only a token holder reaches this branch, so naming the cause is
            # safe and keeps the bounded server log attributable. Both
            # messages are fixed constants: no requested path reaches the response.
            self.send_error(404, str(error))

    def require_publishable(self, local):
        # realpath() and stat() raise ValueError - not OSError - for a path
        # carrying an embedded NUL, and an exception escaping from here aborts
        # the connection with no answer at all. A refusal is the honest shape,
        # which is why the tests insist on 404 rather than merely "not 200".
        try:
            self.require_publishable_name(local)
        except ValueError:
            raise ServedRootEscape(MALFORMED_PATH_MESSAGE)

    def require_publishable_name(self, local):
        root = os.path.realpath(self.directory)
        resolved = os.path.realpath(local)
        if resolved != root and not resolved.startswith(root + os.sep):
            raise ServedRootEscape(SYMLINK_ESCAPE_MESSAGE)
        # stat(), not lstat(): the link chain has just been resolved, and what
        # matters is the inode open() would read. A dangling name cannot leak
        # content, so a failure to stat is left to the standard library's 404.
        try:
            opened = os.stat(resolved)
        except OSError:
            return
        if stat.S_ISREG(opened.st_mode) and opened.st_nlink > 1:
            raise ServedRootEscape(HARD_LINK_MESSAGE)

    def translate_path(self, path):
        local = super().translate_path(self.path_without_token(path))
        self.require_publishable(local)
        # send_head() resolves a directory request to its index file itself,
        # after translate_path() returns, so that candidate has to be judged
        # here. The names come from the handler class rather than a copy of
        # them, and only where the standard library would really pick one.
        if os.path.isdir(local):
            for name in getattr(self, "index_pages", FALLBACK_INDEX_PAGES):
                candidate = os.path.join(local, name)
                if os.path.isfile(candidate):
                    self.require_publishable(candidate)
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

/** What a status() query may conclude, and may therefore do to, one record. */
internal enum class QueryDisposition { REPORT, REPORT_UNATTRIBUTABLE, FORGET_CONFIRMED_DEAD }

/** What stop()/stopAll() may do to one record. */
internal enum class StopDisposition { SIGNAL_PROCESS_GROUP, DROP_UNATTRIBUTABLE, DROP_CONFIRMED_DEAD }

/**
 * How one `stopRecord()` attempt may treat the process group, decided from the
 * three things the call can actually observe. Split out because the equivalent
 * decision used to be spelled inline in `stopRecord()`, where no test on any
 * host could reach it (`:ugk-terminal-runtime-android` cannot construct a
 * manager without an Android Context, and a device always has a working probe).
 */
internal enum class GroupStopPlan {
    /** The group is provably gone, or this instance can prove the teardown through its own handle. */
    ALREADY_GONE,

    /** The group is live and the probe can signal it: TERM, then KILL, then re-verify. */
    SIGNAL_AND_VERIFY,

    /** Neither the probe nor a held handle can prove anything: the stop must fail loudly. */
    UNVERIFIABLE
}

/** Single source of the default loopback port; the companion keeps no duplicate. */
const val DEFAULT_LOCAL_HTTP_SERVER_PORT: Int = 8_765
