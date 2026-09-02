package com.ugk.pi.terminal.runtime

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
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
    fun handlerScriptGatesPathsOnTheTokenAndContainsSymlinkEscapeCheck() {
        val script = LocalHttpServerManager.TOKEN_HTTP_HANDLER_SCRIPT

        // Token gate: the first path segment must equal the token, and a
        // mismatch answers 404 (not 403, so the tree cannot be probed).
        assertTrue(script.contains("class TokenGatedRequestHandler(SimpleHTTPRequestHandler)"))
        assertTrue(script.contains("first_segment == self.token"))
        assertTrue(script.contains("send_error(404)"))

        // Symlink containment: the mapped local path must stay inside the
        // realpath of the served root.
        assertTrue(script.contains("os.path.realpath(self.directory)"))
        assertTrue(script.contains("os.path.realpath(local)"))
        assertTrue(script.contains("raise SymlinkEscape(path)"))

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
        if (pythonExecutable == null) {
            // Optional smoke: the host running the JVM tests has no python.
            System.err.println("py_compile smoke skipped: no python on PATH")
            return
        }

        val script = File.createTempFile("token-http-handler", ".py").apply {
            writeText(LocalHttpServerManager.TOKEN_HTTP_HANDLER_SCRIPT + "\n", Charsets.UTF_8)
            deleteOnExit()
        }
        val process = ProcessBuilder(pythonExecutable, "-m", "py_compile", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertTrue("py_compile failed (exit=$exitCode): $output", exitCode == 0)
    }
}
