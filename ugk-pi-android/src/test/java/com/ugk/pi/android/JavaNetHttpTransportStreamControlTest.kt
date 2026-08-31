package com.ugk.pi.android

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * Streaming transport must honor cooperative cancellation and a response
 * size bound:
 *
 *  1. cancelling the collector must close the underlying connection
 *     promptly — a blocking `readLine()` otherwise pins the socket and an IO
 *     thread until the full read timeout (default 180s);
 *  2. a single streamed line must never exceed the configured
 *     maxResponseBytes — an unbounded readLine() buffers arbitrary data and
 *     turns a hostile or broken server into an OOM.
 */
class JavaNetHttpTransportStreamControlTest {

    @Test
    fun `cancelling the collector closes the connection promptly`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val serverSawDisconnect = CountDownLatch(1)
        val serverThread = thread(isDaemon = true) {
            val client: Socket = server.accept()
            try {
                client.getInputStream().use { input ->
                    val scratch = ByteArray(4096)
                    // Drain the request body; the server never responds, so the
                    // client stays blocked inside its response read until the
                    // connection dies.
                    while (input.read(scratch) >= 0) { /* drain */ }
                }
                serverSawDisconnect.countDown()
            } catch (_: IOException) {
                serverSawDisconnect.countDown()
            } finally {
                runCatching { client.close() }
            }
        }

        val transport = JavaNetHttpTransport(
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 6_000
        )
        val request = HttpRequest(
            url = "http://127.0.0.1:${server.localPort}/v1/messages",
            headers = emptyMap(),
            body = "{}"
        )

        val collectorReturned = runBlocking {
            val job = launch {
                transport.postStream(request).collect { /* never reached */ }
            }
            delay(600) // let the request fly and the read block
            job.cancel()
            withTimeoutOrNull(2_000) { job.join(); true } ?: false
        }

        // The collector itself must return quickly ...
        assertTrue("collector must return promptly after cancellation", collectorReturned)
        // ... and the socket must be closed promptly instead of lingering
        // until the 6s read timeout: the server must observe the disconnect.
        val disconnectedPromptly = serverSawDisconnect.await(3, TimeUnit.SECONDS)
        runCatching { server.close() }
        serverThread.join(TimeUnit.SECONDS.toMillis(5))
        assertTrue(
            "cancellation must disconnect the underlying connection (server still connected)",
            disconnectedPromptly
        )
    }

    @Test
    fun `a single streamed line longer than maxResponseBytes fails the stream`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val requestDrained = CountDownLatch(1)
        val serverThread = thread(isDaemon = true) {
            val client: Socket = server.accept()
            try {
                // Drain the request body first so the client commits to
                // reading the response.
                thread(isDaemon = true) {
                    runCatching {
                        client.getInputStream().use { input ->
                            while (input.read(ByteArray(4096)) >= 0) { /* drain */ }
                        }
                    }
                    requestDrained.countDown()
                }
                val output = client.getOutputStream()
                output.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                output.flush()
                // One huge line (256 KiB) with no terminating newline; the
                // transport is configured for maxResponseBytes = 64 KiB.
                repeat(256) {
                    output.write(ByteArray(1024) { 'a'.code.toByte() })
                    output.flush()
                }
                // Hold the connection open: without a size bound the client
                // keeps buffering until the read timeout instead of failing.
                requestDrained.await(10, TimeUnit.SECONDS)
                runCatching { client.close() }
            } catch (_: IOException) {
            } finally {
                runCatching { server.close() }
            }
        }

        val transport = JavaNetHttpTransport(
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 10_000,
            maxResponseBytes = 64 * 1024
        )
        val request = HttpRequest(
            url = "http://127.0.0.1:${server.localPort}/v1/messages",
            headers = emptyMap(),
            body = "{}"
        )

        val receivedLines = mutableListOf<String>()
        val failure: Throwable? = runBlocking {
            try {
                transport.postStream(request).collect { receivedLines += it }
                null
            } catch (error: Throwable) {
                error
            }
        }
        serverThread.join(TimeUnit.SECONDS.toMillis(12))

        assertTrue(
            "oversized line must fail the stream with IOException, got lines=${receivedLines.size} error=$failure",
            failure is IOException
        )
        assertTrue(
            "failure must be a maxResponseBytes violation, was: ${failure?.message}",
            failure?.message?.contains("maxResponseBytes") == true
        )
        assertTrue(
            "no partial oversized line may be emitted, got ${receivedLines.size}",
            receivedLines.isEmpty()
        )
    }
}
