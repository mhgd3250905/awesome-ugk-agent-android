package com.ugk.pi.android

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the streamed-response total byte cap.
 *
 * The non-streaming path has always been bounded by maxResponseBytes
 * (default 4 MB, JavaNetHttpTransport.post), but the SSE streaming path used
 * to have NO total limit: the per-line cap cannot bound a stream of many
 * small events, and a hostile or broken endpoint could push unbounded deltas
 * into the host (previously verified with 8 MB of text and 4.9 MB of tool
 * arguments arriving in full).
 *
 * JavaNetHttpTransport now caps the SUM of all streamed line bytes at
 * maxStreamedBytes (default 8 MiB, constructor-overridable and exposed by
 * both providers) and fails the stream with an IOException. AgentRuntime
 * converts that failure to AgentEvent.Failed, matching the non-streaming
 * size-violation semantics. Legitimate streams below the cap are unaffected.
 */
class AnthropicStreamByteCapTest {

    @Test
    fun `a streamed response larger than the default 8MiB cap fails with IOException`() = runBlocking {
        // ~9.4 MiB of text deltas: past the default maxStreamedBytes cap of
        // 8 MiB. Generated lazily so the fixture is never fully materialized.
        val server = SseServer { output ->
            output.writeSseLine("{\"type\":\"message_start\",\"message\":{}}")
            output.writeSseLine("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}")
            val chunk = "A".repeat(4096)
            repeat(2304) {
                output.writeSseLine(
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$chunk\"}}"
                )
            }
        }
        try {
            // No transport argument: the provider must fall back to a
            // JavaNetHttpTransport with the default 8 MiB streamed cap.
            val provider = AnthropicMessagesProvider(
                apiKey = "test-key",
                model = "test-model",
                baseUrl = server.baseUrl
            )

            val chunks = mutableListOf<ModelStreamChunk>()
            val failure = runCatching {
                provider.generateStream(
                    ModelRequest(
                        sessionId = "s1",
                        messages = listOf(AgentMessage.User("hello")),
                        tools = emptyList()
                    )
                ).collect { chunks += it }
            }.exceptionOrNull()

            assertTrue(
                "stream over the cap must fail with IOException, was: $failure",
                failure is IOException
            )
            assertTrue(
                "failure must be a maxStreamedBytes violation, was: ${failure?.message}",
                failure?.message?.contains("maxStreamedBytes") == true
            )
            assertTrue(
                "no Completed chunk with the accumulated content may be emitted, was: " +
                    chunks.filterIsInstance<ModelStreamChunk.Completed>(),
                chunks.filterIsInstance<ModelStreamChunk.Completed>().isEmpty()
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `streamed tool arguments beyond a configured cap fail the stream`() = runBlocking {
        val server = SseServer { output ->
            output.writeSseLine("{\"type\":\"message_start\",\"message\":{}}")
            output.writeSseLine(
                "{\"type\":\"content_block_start\",\"index\":0," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_big\",\"name\":\"echo\"}}"
            )
            val piece = "x".repeat(4096)
            // ~262 KiB of argument deltas against a 64 KiB cap.
            repeat(64) {
                output.writeSseLine(
                    "{\"type\":\"content_block_delta\",\"index\":0," +
                        "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"$piece\"}}"
                )
            }
        }
        try {
            val provider = AnthropicMessagesProvider(
                apiKey = "test-key",
                model = "test-model",
                baseUrl = server.baseUrl,
                maxStreamedBytes = 64 * 1024
            )

            val chunks = mutableListOf<ModelStreamChunk>()
            val failure = runCatching {
                provider.generateStream(
                    ModelRequest(
                        sessionId = "s1",
                        messages = listOf(AgentMessage.User("hello")),
                        tools = emptyList()
                    )
                ).collect { chunks += it }
            }.exceptionOrNull()

            assertTrue(
                "argument stream over the cap must fail with IOException, was: $failure",
                failure is IOException
            )
            assertTrue(
                "failure must be a maxStreamedBytes violation, was: ${failure?.message}",
                failure?.message?.contains("maxStreamedBytes") == true
            )
            assertTrue(
                "the oversized tool call must never complete, was: " +
                    chunks.filterIsInstance<ModelStreamChunk.Completed>(),
                chunks.filterIsInstance<ModelStreamChunk.Completed>().isEmpty()
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a legitimate 1MB stream completes with the full content`() = runBlocking {
        val chunk = "B".repeat(4096)
        val chunkCount = 256 // exactly 1 MiB of text
        val server = SseServer { output ->
            output.writeSseLine("{\"type\":\"message_start\",\"message\":{}}")
            output.writeSseLine("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}")
            repeat(chunkCount) {
                output.writeSseLine(
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$chunk\"}}"
                )
            }
            output.writeSseLine("{\"type\":\"content_block_stop\",\"index\":0}")
            output.writeSseLine("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")
            output.writeSseLine("{\"type\":\"message_stop\"}")
        }
        try {
            val provider = AnthropicMessagesProvider(
                apiKey = "test-key",
                model = "test-model",
                baseUrl = server.baseUrl
            )

            val completed = provider.generateStream(
                ModelRequest(
                    sessionId = "s1",
                    messages = listOf(AgentMessage.User("hello")),
                    tools = emptyList()
                )
            ).toList().filterIsInstance<ModelStreamChunk.Completed>().single()

            assertEquals(
                "a legitimate 1 MiB stream must complete without truncation",
                chunkCount * chunk.length,
                completed.response.content.length
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a runtime run reports an over-cap stream as Failed`() = runBlocking {
        val server = SseServer { output ->
            output.writeSseLine("{\"type\":\"message_start\",\"message\":{}}")
            output.writeSseLine("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}")
            val chunk = "A".repeat(4096)
            repeat(64) {
                output.writeSseLine(
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$chunk\"}}"
                )
            }
        }
        try {
            val provider = AnthropicMessagesProvider(
                apiKey = "test-key",
                model = "test-model",
                baseUrl = server.baseUrl,
                maxStreamedBytes = 64 * 1024
            )
            val runtime = AgentRuntime(provider, ToolRegistry())

            val events = runtime.run(AgentSession("stream-cap-runtime"), "hello").toList()

            val failure = events.last()
            assertTrue("run must end Failed, was: $failure", failure is AgentEvent.Failed)
            assertTrue(
                "failure must name the streamed cap, was: ${(failure as AgentEvent.Failed).message}",
                failure.message.contains("maxStreamedBytes")
            )
        } finally {
            server.close()
        }
    }

    /**
     * Minimal one-shot SSE server on the loopback interface. It fully drains
     * the client request before responding — closing a socket with unread
     * request data makes TCP send RST instead of FIN, which would fail the
     * client mid-body — and then streams the body produced by [body] as SSE
     * data lines. Writing stops quietly once the client disconnects
     * mid-body, which is the expected outcome when the byte cap trips.
     */
    private class SseServer(private val body: (OutputStream) -> Unit) {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private var client: Socket? = null

        init {
            thread(isDaemon = true) {
                try {
                    val accepted = serverSocket.accept()
                    client = accepted
                    drainRequest(accepted.getInputStream())
                    val output = accepted.getOutputStream()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    output.flush()
                    body(output)
                } catch (_: IOException) {
                    // Client disconnected mid-body (expected when the cap trips).
                } finally {
                    runCatching { client?.close() }
                }
            }
        }

        val baseUrl: String
            get() = "http://127.0.0.1:${serverSocket.localPort}"

        fun close() {
            runCatching { client?.close() }
            runCatching { serverSocket.close() }
        }

        /** Reads the request headers plus exactly the Content-Length body. */
        private fun drainRequest(input: java.io.InputStream) {
            val headers = StringBuilder()
            val single = ByteArray(1)
            while (!headers.endsWith("\r\n\r\n")) {
                if (input.read(single) < 0) return
                headers.append(single[0].toInt().toChar())
            }
            val contentLength = Regex("content-length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headers.toString())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: 0
            var remaining = contentLength
            val scratch = ByteArray(4096)
            while (remaining > 0) {
                val count = input.read(scratch, 0, minOf(scratch.size, remaining))
                if (count < 0) return
                remaining -= count
            }
        }
    }

    private fun OutputStream.writeSseLine(data: String) {
        write("data: $data\n".toByteArray(Charsets.UTF_8))
        flush()
    }
}
