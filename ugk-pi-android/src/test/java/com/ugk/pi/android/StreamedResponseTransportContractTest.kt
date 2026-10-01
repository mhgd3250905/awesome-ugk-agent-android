package com.ugk.pi.android

import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the providers through a real socket instead of a stand-in transport.
 *
 * The property is "an endpoint that ignores `stream` and answers a whole JSON
 * document still produces that document's content". A pretty-printed document is
 * the interesting shape: it arrives as several lines, and a reader that only
 * recognises a JSON document when it appears on one line loses the answer and
 * completes with an empty response.
 *
 * Line framing is what a streaming endpoint needs; a non-streaming document has
 * to survive intact until a provider can classify it. Both `postStream`
 * implementations in this module carry that obligation, so the document cases run
 * against each of them.
 */
class StreamedResponseTransportContractTest {

    private val anthropicPrettyJson = """
        {
          "id": "msg_1",
          "type": "message",
          "role": "assistant",
          "content": [{"type": "text", "text": "第一段内容"}],
          "stop_reason": "end_turn"
        }
    """.trimIndent()

    private val anthropicCompactJson =
        """{"id":"msg_1","type":"message","role":"assistant",""" +
            """"content":[{"type":"text","text":"第一段内容"}],"stop_reason":"end_turn"}"""

    private val openAiPrettyJson = """
        {
          "id": "chatcmpl-1",
          "object": "chat.completion",
          "choices": [
            {
              "index": 0,
              "message": {"role": "assistant", "content": "第一段内容"},
              "finish_reason": "stop"
            }
          ]
        }
    """.trimIndent()

    private val openAiCompactJson =
        """{"id":"chatcmpl-1","object":"chat.completion","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":"第一段内容"},"finish_reason":"stop"}]}"""

    private val anthropicSseBody = listOf(
        "event: message_start",
        """data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
        "",
        "event: content_block_start",
        """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "",
        "event: content_block_delta",
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"第一段"}}""",
        "",
        "event: content_block_delta",
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"内容"}}""",
        "",
        "event: message_stop",
        """data: {"type":"message_stop"}"""
    ).joinToString("\n")

    @Test
    fun anthropicReadsAPrettyJsonDocumentFromTheShippedTransport() {
        ScriptedEndpoint(anthropicPrettyJson, contentType = "application/json").use { endpoint ->
            val chunks = runBlocking {
                AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals(
                "a non-streaming JSON document must not be lost by line framing",
                "第一段内容",
                chunks.completedOrNull()?.content
            )
        }
    }

    @Test
    fun anthropicReadsACompactJsonDocumentFromTheShippedTransport() {
        ScriptedEndpoint(anthropicCompactJson, contentType = "application/json").use { endpoint ->
            val chunks = runBlocking {
                AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals("第一段内容", chunks.completedOrNull()?.content)
        }
    }

    @Test
    fun anthropicStillReceivesSseDeltasLineByLineFromTheShippedTransport() {
        ScriptedEndpoint(anthropicSseBody, contentType = "text/event-stream").use { endpoint ->
            val chunks = runBlocking {
                AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals(
                listOf("第一段", "内容"),
                chunks.filterIsInstance<ModelStreamChunk.ContentDelta>().map { it.delta }
            )
            assertEquals("第一段内容", chunks.completedOrNull()?.content)
        }
    }

    @Test
    fun openAiReadsAPrettyJsonDocumentFromTheShippedTransport() {
        ScriptedEndpoint(openAiPrettyJson, contentType = "application/json").use { endpoint ->
            val chunks = runBlocking {
                OpenAiChatCompletionsProvider(
                    apiKey = "test-key",
                    model = "gpt-test",
                    endpoint = endpoint.url,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals(
                "a non-streaming JSON document must not be lost by line framing",
                "第一段内容",
                chunks.completedOrNull()?.content
            )
        }
    }

    @Test
    fun openAiReadsACompactJsonDocumentFromTheShippedTransport() {
        ScriptedEndpoint(openAiCompactJson, contentType = "application/json").use { endpoint ->
            val chunks = runBlocking {
                OpenAiChatCompletionsProvider(
                    apiKey = "test-key",
                    model = "gpt-test",
                    endpoint = endpoint.url,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals("第一段内容", chunks.completedOrNull()?.content)
        }
    }

    /**
     * Folds one contract over every `postStream` implementation this module
     * ships. Asserting it against a single stand-in is what let the transport
     * used by default drift: the property stayed green on the fake while the
     * real class answered with an empty completion.
     */
    @Test
    fun everyPostStreamImplementationDeliversANonStreamDocumentIntact() {
        val implementations = listOf(
            "JavaNetHttpTransport (the default)" to JavaNetHttpTransport(
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 5_000
            ),
            "HttpTransport default fallback" to PostOnlyTransport(HttpResponse(200, anthropicPrettyJson))
        )
        for ((label, transport) in implementations) {
            ScriptedEndpoint(anthropicPrettyJson, contentType = "application/json").use { endpoint ->
                val provider = AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = if (transport is PostOnlyTransport) "https://example.invalid/anthropic" else endpoint.baseUrl,
                    transport = transport
                )
                val response = runBlocking { provider.generateStream(request()).toList() }.completedOrNull()
                assertEquals(
                    "$label must deliver a whole JSON document to the parser",
                    "第一段内容",
                    response?.content
                )
            }
        }
        // The shipped transport has to hand the document over without chopping it
        // into lines; that is the shape the provider tolerance branch reads.
        ScriptedEndpoint(anthropicPrettyJson, contentType = "application/json").use { endpoint ->
            val emissions = runBlocking {
                JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                    .postStream(HttpRequest(endpoint.url, emptyMap(), "{}"))
                    .toList()
            }
            assertTrue(
                "expected one intact document emission, got ${emissions.size} lines",
                emissions.any { it.trim().startsWith("{") && it.trim().endsWith("}") }
            )
        }
    }

    /**
     * A body announced as an event stream but carrying a document is the mirror
     * of the case above, and it cannot be repaired by the transport: the reader
     * has to say so instead of completing with nothing.
     */
    @Test
    fun anthropicFailsLoudlyWhenAnEventStreamAnnouncementCarriesNoEventAtAll() {
        ScriptedEndpoint(anthropicPrettyJson, contentType = "text/event-stream").use { endpoint ->
            val failure = assertThrows(Exception::class.java) {
                runBlocking {
                    AnthropicMessagesProvider(
                        apiKey = "test-key",
                        model = "claude-3-7-sonnet",
                        baseUrl = endpoint.baseUrl,
                        transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                    ).generateStream(request()).toList()
                }
            }
            assertTrue(
                "expected a framing failure, got: ${failure.message}",
                failure.message?.contains("no SSE event") == true
            )
        }
    }

    /**
     * Legitimate shape, opposite direction: an endpoint that streams real SSE
     * events but never declares the media type must still be read. Losing this
     * would make the header decision a false-positive guard.
     */
    @Test
    fun anthropicStillReadsSseEventsWhenTheEndpointOmitsTheContentType() {
        ScriptedEndpoint(anthropicSseBody, contentType = null).use { endpoint ->
            val chunks = runBlocking {
                AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals("第一段内容", chunks.completedOrNull()?.content)
        }
    }

    @Test
    fun openAiFailsLoudlyWhenAnEventStreamAnnouncementCarriesNoEventAtAll() {
        ScriptedEndpoint(openAiPrettyJson, contentType = "text/event-stream").use { endpoint ->
            val failure = assertThrows(Exception::class.java) {
                runBlocking {
                    OpenAiChatCompletionsProvider(
                        apiKey = "test-key",
                        model = "gpt-test",
                        endpoint = endpoint.url,
                        transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                    ).generateStream(request()).toList()
                }
            }
            assertTrue(
                "expected a framing failure, got: ${failure.message}",
                failure.message?.contains("no SSE event") == true
            )
        }
    }

    /**
     * A connection that closes before `message_stop` is a truncated answer, not
     * an unreadable shape: what it did deliver stays the answer. This is the
     * legitimate side of the framing failure guard, and the only case that tells
     * "no SSE framing at all" apart from "framing seen, stream cut short".
     */
    @Test
    fun anthropicKeepsTheAccumulatedAnswerWhenTheStreamEndsBeforeMessageStop() {
        val truncated = anthropicSseBody.substringBefore("event: message_stop")
        ScriptedEndpoint(truncated, contentType = "text/event-stream").use { endpoint ->
            val chunks = runBlocking {
                AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            assertEquals("第一段内容", chunks.completedOrNull()?.content)
        }
    }

    /**
     * Pins the shape each mode has to produce, not just the parsed answer.
     *
     * Selecting the document mode for everything would also make the parsed
     * content assertions above pass, because [asSseLines] re-splits a buffered
     * body into lines: only the emission shape distinguishes a transport that
     * still streams an event response from one that has silently started
     * buffering it.
     */
    @Test
    fun shippedTransportFramesAnEventStreamByLineAndABodyWithoutOneByEmission() {
        ScriptedEndpoint(anthropicSseBody, contentType = "text/event-stream").use { endpoint ->
            val emissions = runBlocking {
                JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                    .postStream(HttpRequest(endpoint.url, emptyMap(), "{}"))
                    .toList()
            }
            assertTrue(
                "an event stream must arrive as several emissions, got ${emissions.size}",
                emissions.size > 1
            )
            assertTrue(
                "no emission may carry a line of its own past a line terminator",
                emissions.none { it.contains('\n') || it.contains('\r') }
            )
        }
        ScriptedEndpoint(anthropicPrettyJson, contentType = "application/json").use { endpoint ->
            val emissions = runBlocking {
                JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                    .postStream(HttpRequest(endpoint.url, emptyMap(), "{}"))
                    .toList()
            }
            assertEquals(
                "a non-event response must be handed over as one document",
                1,
                emissions.size
            )
            assertTrue(emissions.single().contains('\n'))
        }
    }

    @Test
    fun onlyAnEventStreamMediaTypeSelectsLineFraming() {
        val cases = listOf(
            "text/event-stream" to true,
            "TEXT/EVENT-STREAM" to true,
            "  text/event-stream  " to true,
            "text/event-stream; charset=utf-8" to true,
            "application/json" to false,
            "application/problem+json" to false,
            "text/plain" to false,
            "text/event-streamx" to false,
            "xtext/event-stream" to false,
            "" to false,
            null to false
        )
        for ((value, expected) in cases) {
            assertEquals("Content-Type <$value>", expected, isEventStreamContentType(value))
        }
    }

    private class PostOnlyTransport(private val response: HttpResponse) : HttpTransport {
        override suspend fun post(request: HttpRequest) = response
    }

    private fun request() = ModelRequest(
        sessionId = "s1",
        messages = listOf(AgentMessage.User("hello")),
        tools = emptyList()
    )

    private fun List<ModelStreamChunk>.completedOrNull() =
        filterIsInstance<ModelStreamChunk.Completed>().lastOrNull()?.response

    /**
     * One-shot loopback endpoint on an ephemeral port, so it cannot collide with
     * the documented managed-server ports.
     */
    private class ScriptedEndpoint(body: String, contentType: String?) : Closeable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket -> handle(socket, body, contentType) }
            } catch (_: IOException) {
                // The client may hang up once it has the answer.
            }
        }

        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val url = "$baseUrl/chat/completions"

        private fun handle(socket: Socket, body: String, contentType: String?) {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val reader = input.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().use { output ->
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    if (contentType != null) append("Content-Type: $contentType\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(headers.toByteArray(Charsets.UTF_8))
                output.write(bytes)
                output.flush()
            }
            // Consume whatever the client already buffered so closing the socket
            // does not answer a half-written request with an RST.
            socket.soTimeout = 200
            val scratch = ByteArray(4096)
            try {
                while (input.read(scratch) >= 0) { /* drain */ }
            } catch (_: IOException) {
                // Timeout or the client hanging up is expected here.
            }
        }

        override fun close() {
            runCatching { server.close() }
            runCatching { worker.join(TimeUnit.SECONDS.toMillis(5)) }
        }
    }
}
