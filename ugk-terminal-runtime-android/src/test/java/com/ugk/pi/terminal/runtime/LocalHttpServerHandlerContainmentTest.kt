package com.ugk.pi.terminal.runtime

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Drives the shipped [LocalHttpServerManager.TOKEN_HTTP_HANDLER_SCRIPT] under a
 * real CPython interpreter instead of asserting on its source text.
 *
 * The handler is the layer where the D-028 token gate and the served-root
 * containment actually take effect, and it is standard library only, so a host
 * interpreter runs the same bytes the device runs. Text `contains` assertions
 * over the script cannot see a semantic break: dropping the `+ os.sep` from the
 * prefix check, or comparing the unresolved path instead of its realpath, keeps
 * every asserted substring present while turning the boundary into a no-op.
 * This class therefore asks the live server what it actually answers.
 *
 * Needs a python on the host PATH and reports its absence as a JUnit skip (a
 * `skipped` row in the XML aggregate) rather than a silent pass.
 */
class LocalHttpServerHandlerContainmentTest {
    private val tempRoot = File(System.getProperty("java.io.tmpdir"), "ugk-http-handler-${System.nanoTime()}")
    private lateinit var servedRoot: File
    private lateinit var outsideDirectory: File

    @Before
    fun setUp() {
        assertTrue("unable to create test root", tempRoot.mkdirs())
        servedRoot = File(tempRoot, "site").apply { mkdirs() }
        outsideDirectory = File(tempRoot, "private").apply { mkdirs() }
        File(servedRoot, "index.html").writeText("<p>served-marker</p>", Charsets.UTF_8)
    }

    @After
    fun tearDown() {
        runCatching { tempRoot.deleteRecursively() }
    }

    @Test
    fun tokenGateServesOnlyTheTokenPrefixedTree() {
        val fixture = startHandler()
        try {
            assertEquals("200", fixture.get("/${fixture.token}/").status)
            val index = fixture.get("/${fixture.token}/index.html")
            assertEquals("200", index.status)
            assertTrue(index.body.contains("served-marker"))
            // Anything that does not carry the per-start token answers 404, not
            // 403, so a device neighbour cannot enumerate the served tree.
            assertEquals("404", fixture.get("/").status)
            assertEquals("404", fixture.get("/not-the-token/index.html").status)
            assertEquals(
                "an unguessable decoy token must not be served",
                "404",
                fixture.get("/${LocalHttpServerManager.generateToken()}/index.html").status
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * Defence-in-depth rather than this handler's own check: the standard
     * library `translate_path` drops `..`, `.` and absolute segments, so a
     * traversal URL cannot leave the served root in the first place. Pinned
     * because the containment below is the only other thing standing between a
     * token holder and the whole app UID.
     */
    @Test
    fun traversalUrlNeverLeavesTheServedRoot() {
        File(outsideDirectory, "topsecret.txt").writeText(TOP_SECRET, Charsets.UTF_8)
        val fixture = startHandler()
        try {
            assertEquals(
                "traversal escaped the served root",
                "404",
                fixture.get("/${fixture.token}/../../../../topsecret.txt").status
            )
            assertEquals(
                "encoded traversal escaped the served root",
                "404",
                fixture.get("/${fixture.token}/%2e%2e/%2e%2e/%2e%2e/topsecret.txt").status
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * `realpath()` and `stat()` raise `ValueError`, not `OSError`, for a path
     * carrying an embedded NUL. Uncaught, that escapes the request handler and
     * the connection closes with no response at all - which every "must not be
     * served" oracle in this class would happily accept as a refusal.
     */
    @Test
    fun encodedNulIsRefusedWithAnAnswerNotWithAClosedConnection() {
        val fixture = startHandler()
        try {
            assertEquals(
                "a NUL-bearing path must be answered, not dropped",
                "404",
                fixture.get("/${fixture.token}/index.html%00.png").status
            )
            assertEquals("200", fixture.get("/${fixture.token}/index.html").status)
        } finally {
            fixture.close()
        }
    }

    /**
     * A hard link is not a symlink: both names are real paths, so
     * `os.path.realpath` reports the served-tree name and the containment check
     * that blocks symlink escapes is satisfied by it. `ln <secret> site/x`
     * therefore publishes any same-UID file through the token URL.
     */
    @Test
    fun hardLinkIntoServedRootIsNotServed() {
        Assume.assumeTrue("host filesystem cannot create hard links", hostSupportsHardLinks())
        val secret = File(outsideDirectory, "api-keys.txt").apply { writeText(TOP_SECRET, Charsets.UTF_8) }
        val link = File(servedRoot, "index-copy.txt")
        Files.createLink(link.toPath(), secret.toPath())
        assertTrue(
            "fixture must publish the secret through a second link, not a copy",
            Files.isSameFile(link.toPath(), secret.toPath())
        )

        val fixture = startHandler()
        try {
            val response = fixture.get("/${fixture.token}/index-copy.txt")
            // A refusal must be an explicit 404: "not 200" would also be
            // satisfied by a connection abort, a timeout, or a missing file,
            // none of which prove the containment did anything.
            assertEquals(
                "hard-linked file was served through the token URL, body='${response.body.take(48)}'",
                "404",
                response.status
            )
            // Positive control: refusing extra links must not take the normal
            // single-link files of the served tree down with it.
            assertEquals("200", fixture.get("/${fixture.token}/index.html").status)
        } finally {
            fixture.close()
        }
    }

    /** Symlink containment, proven against the live interpreter instead of the script text. */
    @Test
    fun symlinkOutOfServedRootIsNotServed() {
        Assume.assumeTrue("host cannot create symlinks", hostSupportsSymlinks())
        val secret = File(outsideDirectory, "memory.db").apply { writeText(TOP_SECRET, Charsets.UTF_8) }
        val link = File(servedRoot, "notes.md")
        Files.createSymbolicLink(link.toPath(), secret.toPath())

        val fixture = startHandler()
        try {
            val response = fixture.get("/${fixture.token}/notes.md")
            assertEquals("symlinked file escaped the served root", "404", response.status)
            assertFalse(response.body.contains(TOP_SECRET))
            assertEquals("200", fixture.get("/${fixture.token}/index.html").status)
        } finally {
            fixture.close()
        }
    }

    /**
     * Catches a containment check that compares prefixes without a path
     * separator: `site` is a prefix of `site-evil`, so a link into the sibling
     * would satisfy `resolved.startswith(root)` while sitting outside it.
     */
    @Test
    fun siblingDirectorySharingTheRootNamePrefixIsNotServed() {
        Assume.assumeTrue("host cannot create symlinks", hostSupportsSymlinks())
        val sibling = File(tempRoot, "site-evil").apply { mkdirs() }
        val secret = File(sibling, "leaked.txt").apply { writeText(TOP_SECRET, Charsets.UTF_8) }
        val link = File(servedRoot, "leaked.txt")
        Files.createSymbolicLink(link.toPath(), secret.toPath())

        val fixture = startHandler()
        try {
            val response = fixture.get("/${fixture.token}/leaked.txt")
            assertEquals(
                "a root-prefix comparison without a path separator served the sibling directory",
                "404",
                response.status
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * `send_head()` resolves a directory request to `<dir>/index.html` itself,
     * after `translate_path()` has already run, so a containment check that
     * only sees translate_path's answer never looks at the file that gets
     * opened.
     */
    @Test
    fun hardLinkPublishedAsADirectoryIndexIsNotServed() {
        Assume.assumeTrue("host filesystem cannot create hard links", hostSupportsHardLinks())
        val secret = File(outsideDirectory, "tokens.json").apply { writeText(TOP_SECRET, Charsets.UTF_8) }
        val sub = File(servedRoot, "sub").apply { mkdirs() }
        val index = File(sub, "index.html")
        Files.createLink(index.toPath(), secret.toPath())

        val fixture = startHandler()
        try {
            val response = fixture.get("/${fixture.token}/sub/")
            assertEquals(
                "a hard-linked index.html was opened for a directory request: " +
                    "body='${response.body.take(48)}'",
                "404",
                response.status
            )
            assertEquals("200", fixture.get("/${fixture.token}/index.html").status)
        } finally {
            fixture.close()
        }
    }

    /**
     * A symlink is not itself a regular file, so judging the link rather than
     * what it resolves to lets `ln secret site/real` + `ln -s real site/link`
     * through while realpath still reports a path inside the root.
     */
    @Test
    fun symlinkResolvingToAHardLinkIsNotServed() {
        Assume.assumeTrue("host cannot create both links", hostSupportsHardLinks() && hostSupportsSymlinks())
        val secret = File(outsideDirectory, "session.db").apply { writeText(TOP_SECRET, Charsets.UTF_8) }
        val hardLink = File(servedRoot, "real.txt")
        Files.createLink(hardLink.toPath(), secret.toPath())
        val symlink = File(servedRoot, "alias.txt")
        Files.createSymbolicLink(symlink.toPath(), hardLink.toPath())

        val fixture = startHandler()
        try {
            val response = fixture.get("/${fixture.token}/alias.txt")
            assertEquals(
                "a symlink to a multi-link file inside the root was served: " +
                    "body='${response.body.take(48)}'",
                "404",
                response.status
            )
            assertEquals("200", fixture.get("/${fixture.token}/index.html").status)
        } finally {
            fixture.close()
        }
    }

    private fun hostSupportsHardLinks(): Boolean = probeLink { source, target ->
        Files.createLink(target, source)
    }

    private fun hostSupportsSymlinks(): Boolean = probeLink { source, target ->
        Files.createSymbolicLink(target, source)
    }

    private fun probeLink(create: (java.nio.file.Path, java.nio.file.Path) -> Unit): Boolean {
        val source = File(tempRoot, "probe-src.txt").apply { writeText("x", Charsets.UTF_8) }
        val target = File(tempRoot, "probe-dst.txt")
        return try {
            create(source.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            false
        } finally {
            target.delete()
        }
    }

    private fun startHandler(): RunningHandler {
        val script = File(tempRoot, "token_http_handler.py").apply {
            writeText(LocalHttpServerManager.TOKEN_HTTP_HANDLER_SCRIPT, Charsets.UTF_8)
        }
        // A free port is only free until the interpreter binds it, so fixtures
        // launched back to back can hand the same number to a process that no
        // longer holds it. Retry on "never served" only: every other failure is
        // a real answer from the server and must reach the test unchanged.
        var lastFailure: Throwable? = null
        repeat(LAUNCH_ATTEMPTS) {
            val candidate = runCatching {
                RunningHandler(script, servedRoot, LocalHttpServerManager.generateToken())
            }
            candidate.getOrNull()?.let { return it }
            val failure = candidate.exceptionOrNull()!!
            if (failure.message?.contains(NEVER_SERVED_MARKER) != true) throw failure
            lastFailure = failure
        }
        throw lastFailure!!
    }

    private data class HandlerResponse(val status: String, val body: String)

    /** A live token-gated server on a free loopback port, launched the way the manager launches it. */
    private inner class RunningHandler(scriptFile: File, root: File, val token: String) {
        val port: Int = freeLoopbackPort()

        val process: Process = ProcessBuilder(
            pythonExecutable(),
            scriptFile.absolutePath,
            port.toString(),
            token,
            "--directory",
            root.absolutePath
        )
            .directory(tempRoot)
            .redirectErrorStream(true)
            .apply {
                // The host shell may export these; the handler is stdlib only
                // and must start from the interpreter's own library.
                environment().remove("PYTHONHOME")
                environment().remove("PYTHONPATH")
            }
            .start()

        private val log = StringBuilder()
        private val drainThread = Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(log) { log.append(line).append('\n') }
                    }
                }
            }
        }, "ugk-handler-output").apply { isDaemon = true; start() }

        init {
            awaitServing()
        }

        private fun awaitServing() {
            val deadline = System.currentTimeMillis() + START_BUDGET_MILLIS
            var lastStatus = ""
            while (System.currentTimeMillis() < deadline) {
                lastStatus = runCatching { get("/$token/").status }.getOrDefault("connect-failed")
                if (lastStatus == "200") return
                Thread.sleep(100L)
            }
            process.destroy()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            fail(NEVER_SERVED_MARKER + " handler did not answer its token path (last probe: $lastStatus). log:\n$log")
        }

        fun get(path: String): HandlerResponse {
            return Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MILLIS)
                socket.soTimeout = READ_TIMEOUT_MILLIS
                socket.getOutputStream().bufferedWriter().apply {
                    write("GET $path HTTP/1.0\r\n")
                    write("Host: $LOOPBACK\r\n\r\n")
                    flush()
                }
                val reader = socket.getInputStream().bufferedReader()
                val statusLine = reader.readLine() ?: return HandlerResponse("", "")
                var contentLength = -1
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                }
                val body = if (contentLength >= 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(chars, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    String(chars, 0, read)
                } else {
                    reader.readText()
                }
                HandlerResponse(statusLine.split(" ").getOrNull(1).orEmpty(), body)
            }
        }

        fun close() {
            // Order matters on Windows: a live child keeps the temp tree
            // locked, so the process must be reaped before the fixture deletes.
            process.destroy()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            drainThread.join(1_000)
        }

        private fun fail(message: String): Nothing = throw AssertionError(message)
    }

    private fun freeLoopbackPort(): Int =
        ServerSocket(0, 4, java.net.InetAddress.getByName(LOOPBACK)).use { it.localPort }

    private fun pythonExecutable(): String {
        val candidate = PYTHON_CANDIDATES.firstOrNull { name ->
            runCatching {
                val child = ProcessBuilder(name, "--version").redirectErrorStream(true).start()
                val output = child.inputStream.bufferedReader().readText()
                child.waitFor() == 0 && output.contains("Python ")
            }.getOrDefault(false)
        }
        Assume.assumeTrue("no python interpreter on the host PATH", candidate != null)
        return candidate!!
    }

    companion object {
        private const val TOP_SECRET = "API_KEY=sk-should-never-be-served"
        private const val LOOPBACK = "127.0.0.1"

        // Per attempt, not per test: a bound-then-released port can be taken
        // again before the interpreter gets it, so the launcher retries.
        private const val START_BUDGET_MILLIS = 15_000L
        private const val LAUNCH_ATTEMPTS = 3
        private const val NEVER_SERVED_MARKER = "never served:"
        private const val CONNECT_TIMEOUT_MILLIS = 4_000
        private const val READ_TIMEOUT_MILLIS = 8_000
        private val PYTHON_CANDIDATES = listOf("python", "python3")
    }
}
