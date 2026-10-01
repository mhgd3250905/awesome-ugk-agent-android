package com.ugk.pi.terminal.runtime

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android-free parts of [LocalHttpServerManager]: the
 * per-start token, the token-gated URL, the reuse guard, and the embedded
 * handler script. The manager itself needs an Android Context and a native
 * process, so its lifecycle stays covered by the demo app instrumented test.
 */
class LocalHttpServerManagerTest {
    @Test
    fun tokenIsUnpaddedUrlSafeBase64OfSixteenRandomBytes() {
        val token = LocalHttpServerManager.generateToken()

        assertEquals(22, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun everyStartGeneratesAFreshToken() {
        assertNotEquals(LocalHttpServerManager.generateToken(), LocalHttpServerManager.generateToken())
    }

    @Test
    fun urlSafeBase64EncoderMatchesJdkReferenceEncoder() {
        val random = java.util.Random(42)
        for (size in 0..24) {
            val bytes = ByteArray(size).also(random::nextBytes)
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertEquals("size=$size", expected, LocalHttpServerManager.encodeBase64Url(bytes))
        }
    }

    @Test
    fun urlCarriesTheTokenPathSegmentForNewServers() {
        assertEquals(
            "http://127.0.0.1:8765/AbCdEfGhIjKlMnOpQrSt/",
            LocalHttpServerManager.urlFor(8_765, "AbCdEfGhIjKlMnOpQrSt")
        )
    }

    @Test
    fun legacyRecordsWithoutTokenKeepTheOldRootUrl() {
        assertEquals("http://127.0.0.1:8765/", LocalHttpServerManager.urlFor(8_765, null))
        assertEquals("http://127.0.0.1:8765/", LocalHttpServerManager.urlFor(8_765, ""))
    }

    @Test
    fun reusingTheSameServedDirectoryIsAllowed() {
        // Both callers pass canonical workspace directories, so equality on
        // the absolute path identifies the same served directory.
        val directory = File("/data/workspace/site")

        assertNull(LocalHttpServerManager.directoryReuseError(8_765, directory, directory))
        assertNull(
            LocalHttpServerManager.directoryReuseError(
                8_765,
                File("/data/workspace/site"),
                File(File("/data/workspace"), "site")
            )
        )
    }

    @Test
    fun reusingAPortServingADifferentDirectoryFailsWithPortInUse() {
        val runningDirectory = File("/data/workspace/weather-site")
        val requestedDirectory = File("/data/workspace/news-site")

        val failure = LocalHttpServerManager.directoryReuseError(8_765, runningDirectory, requestedDirectory)

        assertEquals("PORT_IN_USE", failure?.code)
        val message = failure?.message.orEmpty()
        assertTrue(message.contains("already serving a different directory"))
        assertTrue(message.contains("8765"))
        assertTrue(message.contains(runningDirectory.absolutePath))
        assertTrue(message.contains(requestedDirectory.absolutePath))
    }

    @Test
    fun handlerScriptGatesPathsOnTheTokenAndContainsServedRootContainment() {
        val script = LocalHttpServerManager.TOKEN_HTTP_HANDLER_SCRIPT

        // Structural smoke only. These substrings cannot tell a working
        // boundary from a no-op one: replacing the containment condition with
        // `False` keeps every assertion below green while publishing the
        // whole UID through the token URL. [LocalHttpServerHandlerContainmentTest]
        // is the executable lock for this script.
        //
        // Token gate: the first path segment must equal the token, and a
        // mismatch answers 404 (not 403, so the tree cannot be probed).
        assertTrue(script.contains("class TokenGatedRequestHandler(SimpleHTTPRequestHandler)"))
        assertTrue(script.contains("first_segment == self.token"))
        assertTrue(script.contains("send_error(404)"))

        // Symlink containment: the mapped local path must stay inside the
        // realpath of the served root, with a path separator on the prefix.
        assertTrue(script.contains("os.path.realpath(self.directory)"))
        assertTrue(script.contains("os.path.realpath(local)"))
        assertTrue(script.contains("root + os.sep"))
        assertTrue(script.contains("raise ServedRootEscape(SYMLINK_ESCAPE_MESSAGE)"))

        // Hard-link containment: realpath cannot tell a hard link from the file
        // it shares an inode with, so the inode actually opened is judged.
        // stat() rather than lstat() is load-bearing - the check must follow
        // the link chain, not stop at the first name.
        assertTrue(script.contains("opened = os.stat(resolved)"))
        assertTrue(script.contains("stat.S_ISREG(opened.st_mode) and opened.st_nlink > 1"))
        assertTrue(script.contains("raise ServedRootEscape(HARD_LINK_MESSAGE)"))

        // The index file send_head() picks for a directory request is judged
        // too, and by the names the standard library itself carries.
        assertTrue(script.contains("self.require_publishable(candidate)"))
        assertTrue(script.contains("getattr(self, \"index_pages\", FALLBACK_INDEX_PAGES)"))

        // Loopback-only binding and stdlib-only server bootstrap.
        assertTrue(script.contains("BIND_HOST = \"127.0.0.1\""))
        assertTrue(script.contains("ThreadingHTTPServer((BIND_HOST, arguments.port), handler)"))

        // CLI surface matches the manager's command: script PORT TOKEN
        // --directory DIR.
        assertTrue(script.contains("parser.add_argument(\"port\", type=int)"))
        assertTrue(script.contains("parser.add_argument(\"token\")"))
        assertTrue(script.contains("parser.add_argument(\"--directory\""))
    }

    @Test
    fun handlerScriptCompilesWithHostPythonWhenAvailable() {
        val pythonExecutable = listOf("python", "python3").firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
        }
        // A host without python used to return here, which counted as a pass
        // for a check that never ran. Report it as a skip so the JUnit
        // aggregate shows the missing coverage instead of hiding it.
        org.junit.Assume.assumeTrue(
            "py_compile smoke needs a python on the host PATH",
            pythonExecutable != null
        )

        val script = File.createTempFile("token-http-handler", ".py").apply {
            writeText(LocalHttpServerManager.TOKEN_HTTP_HANDLER_SCRIPT + "\n", Charsets.UTF_8)
            deleteOnExit()
        }
        val process = ProcessBuilder(pythonExecutable!!, "-m", "py_compile", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertTrue("py_compile failed (exit=$exitCode): $output", exitCode == 0)
    }

    @Test
    fun tokenAttributionCheckRequiresOurTokenPathToAnswer200() {
        val server = FakeHttpServer { path ->
            if (path == "/good-token/" || path == "/good-token") 200 else 404
        }
        try {
            assertTrue(LocalHttpServerManager.isTokenServed(server.port, "good-token"))
            assertFalse(
                "a foreign responder must not count as our server",
                LocalHttpServerManager.isTokenServed(server.port, "wrong-token")
            )
        } finally {
            server.stop()
        }
        // Nothing listening anymore: attribution must fail.
        assertFalse(LocalHttpServerManager.isTokenServed(server.port, "good-token"))
    }

    @Test
    fun tokenAttributionCheckHandlesDashAndUnderscoreLeadingTokens() {
        // The unpadded URL-safe Base64 alphabet includes '-' and '_', and
        // random bytes can produce a token starting with either.
        val server = FakeHttpServer { path ->
            if (path == "/-_dashy_token-9/" ) 200 else 404
        }
        try {
            assertTrue(LocalHttpServerManager.isTokenServed(server.port, "-_dashy_token-9"))
            assertFalse(LocalHttpServerManager.isTokenServed(server.port, "x-_dashy_token-9"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun tokenAttributionCheckRejectsForeignServerAnswering200OnEveryPath() {
        // A foreign bind-race winner that answers 200 to every path must not
        // be attributed as our token-gated handler: our handler 404s the bare
        // path and any unguessable decoy path, so attribution also requires
        // those probes to NOT answer 200. Without the negative probes,
        // start() would hand out a token URL that is actually served by
        // someone else's content.
        val server = FakeHttpServer { _ -> 200 }
        try {
            assertFalse(
                "a foreign responder answering 200 on every path must not count as our server",
                LocalHttpServerManager.isTokenServed(server.port, "good-token")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun tokenAttributionCheckRejectsForeignCatchAllWithRoot404() {
        // A foreign bind-race winner that 404s only the root but answers 200
        // to every other path would pass a bare-path-only second probe. The
        // unguessable decoy probe must still reject it: only a responder
        // that knows the real token 404s the decoy.
        val server = FakeHttpServer { path ->
            if (path == "/") 404 else 200
        }
        try {
            assertFalse(
                "a foreign catch-all responder (root 404, rest 200) must not count as our server",
                LocalHttpServerManager.isTokenServed(server.port, "good-token")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun tokenAttributionCheckStillAcceptsOurHandlerShape() {
        // Positive control for the hardened check: 200 on the token path and
        // 404 on the bare path (exactly what the token-gated handler serves)
        // must keep attributing.
        val server = FakeHttpServer { path ->
            if (path == "/good-token/" || path == "/good-token") 200 else 404
        }
        try {
            assertTrue(LocalHttpServerManager.isTokenServed(server.port, "good-token"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun tokenAttributionCheckKeepsLegacyBehaviorForBlankToken() {
        // Pre-existing contract: records written before token gating have no
        // token, and attribution for them stays a bare connectivity check.
        val server = FakeHttpServer { _ -> 404 }
        try {
            assertTrue(LocalHttpServerManager.isTokenServed(server.port, ""))
            assertTrue(LocalHttpServerManager.isTokenServed(server.port, "   "))
        } finally {
            server.stop()
        }
    }

    @Test
    fun tokenAttributionCheckFailsClosedWhenANegativeProbeCannotConnect() {
        // The token probe answers 200, then the responder vanishes before the
        // decoy probe: a probe that cannot connect must fail closed, not be
        // skipped or treated as "not 200".
        val serverSocket = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val thread = Thread {
            // Serve exactly one probe (the real-token probe), then disappear.
            val client = runCatching { serverSocket.accept() }.getOrNull()
            runCatching {
                client?.let {
                    val reader = it.getInputStream().bufferedReader()
                    reader.readLine()
                    var line: String?
                    do { line = reader.readLine() } while (!line.isNullOrEmpty())
                    it.getOutputStream().write(
                        "HTTP/1.0 200 X\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()
                    )
                    it.close()
                }
            }
            runCatching { serverSocket.close() }
        }.apply { isDaemon = true; start() }
        try {
            assertFalse(
                "a vanished responder must fail the attribution check",
                LocalHttpServerManager.isTokenServed(port, "good-token")
            )
        } finally {
            runCatching { serverSocket.close() }
            thread.join(2000)
        }
    }

    /** Minimal single-thread HTTP responder for the attribution helper. */
    private class FakeHttpServer(private val statusFor: (String) -> Int) {
        val port: Int
        private val serverSocket: java.net.ServerSocket
        private val thread: Thread

        init {
            serverSocket = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket.localPort
            thread = Thread {
                while (!serverSocket.isClosed) {
                    val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    runCatching {
                        val reader = client.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val path = requestLine.split(" ").getOrNull(1) ?: "/"
                        val body = "ok"
                        val status = statusFor(path)
                        client.getOutputStream().write(
                            ("HTTP/1.0 $status X\r\nContent-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray()
                        )
                    }
                    runCatching { client.close() }
                }
            }.apply { isDaemon = true; start() }
        }

        fun stop() {
            runCatching { serverSocket.close() }
            thread.join(1_000)
        }
    }
}
