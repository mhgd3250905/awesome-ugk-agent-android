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
 * to survive intact until a provider can classify it. `everyPostStreamImplementationDeliversANonStreamDocumentIntact`
 * folds that one contract over both `postStream` implementations in this module;
 * the remaining cases drive the transport the SDK uses by default, because the
 * property previously held only against a post-only stand-in.
 *
 * The two `...ACompactJsonDocument...` cases are controls: they already passed
 * before the fix, and their job is to show the harness and the single-line
 * tolerance branch work, so a red elsewhere cannot be blamed on the fake
 * endpoint.
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

    private val openAiSseBody = listOf(
        """data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"第一段"}}]}""",
        "",
        """data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"内容"}}]}""",
        "",
        """data: {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        "",
        "data: [DONE]"
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
        ScriptedEndpoint(anthropicPrettyJson, contentType = "application/json").use { endpoint ->
            val implementations = listOf(
                "JavaNetHttpTransport (the default)" to JavaNetHttpTransport(
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 5_000
                ),
                // A host that implements only post() inherits the interface's
                // default postStream: a second implementation of the same
                // contract, so it is driven over the same socket rather than
                // handed a canned body.
                "HttpTransport default fallback" to PostOnlyTransport(endpoint.url)
            )
            for ((label, transport) in implementations) {
                val emissions = runBlocking {
                    transport.postStream(HttpRequest(endpoint.url, emptyMap(), "{}")).toList()
                }
                assertEquals(
                    "$label must hand a non-event response over as one document",
                    1,
                    emissions.size
                )
                assertTrue(
                    "$label lost the document's internal line breaks",
                    emissions.single().contains('\n') && isStandaloneJsonDocument(emissions.single())
                )
                val provider = AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
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
            assertEquals(
                "an event stream must arrive one line at a time",
                anthropicSseBody.lines().size,
                emissions.size
            )
            assertEquals("the framing must not invent or drop a line", anthropicSseBody.lines(), emissions)
        }
        ScriptedEndpoint(anthropicSseBody, contentType = "application/json").use { endpoint ->
            val chunks = runBlocking {
                AnthropicMessagesProvider(
                    apiKey = "test-key",
                    model = "claude-3-7-sonnet",
                    baseUrl = endpoint.baseUrl,
                    transport = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)
                ).generateStream(request()).toList()
            }
            // An endpoint that forgets the media type still has to stream: without
            // the first-line signal its answer arrives in one burst at EOF, and a
            // long answer would fall under the document size cap.
            assertEquals(
                listOf("第一段", "内容"),
                chunks.filterIsInstance<ModelStreamChunk.ContentDelta>().map { it.delta }
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

    /**
     * The two signals the transport is allowed to key framing on, over the shapes
     * a real gateway actually sends: media type with and without parameters, case
     * changes, blank, and the near-misses that must not be read as an event
     * stream. The end-to-end consequences of each decision are the socket cases
     * above, not this table.
     */
    @Test
    fun contentTypeAndFirstLineDecideFraming() {
        val mediaTypes = listOf(
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
        for ((value, expected) in mediaTypes) {
            assertEquals("Content-Type <$value>", expected, isEventStreamContentType(value))
        }
        val firstLines = listOf(
            "data: {\"type\":\"message_start\"}" to true,
            "event: message_start" to true,
            "id: 7" to true,
            "retry: 1000" to true,
            ": keep-alive comment" to true,
            "{" to false,
            "  {" to false,
            "[1,2]" to false,
            "" to false,
            "data" to false
        )
        for ((value, expected) in firstLines) {
            assertEquals("first line <$value>", expected, looksLikeEventStreamLine(value))
        }
    }

    /**
     * Both providers refuse to read an `error` body as a blank answer - but the
     * check runs on the whole body as one line, so a pretty-printed error
     * document used to slip past it and the turn ended as a successful empty
     * response instead of the named API failure.
     */
    @Test
    fun anthropicReportsAnApiErrorFromAPrettyPrintedDocument() {
        val body = """
            {
              "type": "error",
              "error": {
                "type": "overloaded_error",
                "message": "Overloaded"
              }
            }
        """.trimIndent()
        ScriptedEndpoint(body, contentType = "application/json").use { endpoint ->
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
                "expected the endpoint's own error text, got: ${failure.message}",
                failure.message?.contains("Overloaded") == true
            )
        }
    }

    @Test
    fun openAiReportsAnApiErrorFromAPrettyPrintedDocument() {
        val body = """
            {
              "error": {
                "message": "quota exceeded, top up to continue",
                "type": "insufficient_quota"
              }
            }
        """.trimIndent()
        ScriptedEndpoint(body, contentType = "application/json").use { endpoint ->
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
                "expected the endpoint's own error text, got: ${failure.message}",
                failure.message?.contains("quota exceeded") == true
            )
        }
    }

    /**
     * A stream whose only event is `[DONE]` carried nothing this reader could
     * understand. It used to fall through to the end-of-stream completion with an
     * empty body, which is the same silent blank answer this fix exists to stop -
     * recognising the `data:` prefix is not understanding an event.
     */
    @Test
    fun anthropicFailsLoudlyWhenTheStreamCarriedOnlyADoneMarker() {
        ScriptedEndpoint("data: [DONE]\n\n", contentType = "text/event-stream").use { endpoint ->
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

    /** Same rule for a payload that is a data event but not an event object. */
    @Test
    fun anthropicFailsLoudlyWhenTheEventPayloadIsNotAnObject() {
        ScriptedEndpoint("data: 123\n\ndata: \"text\"\n\n", contentType = "text/event-stream")
            .use { endpoint ->
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
     * A 200 document with an empty `choices` array is a legal content-filter
     * refusal shape, and the parser refuses it. The failure must name that, not
     * send the reader hunting for a framing bug that is not there.
     */
    @Test
    fun openAiNamesTheDocumentCauseInsteadOfBlamingTheFraming() {
        val body = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "choices": []
            }
        """.trimIndent()
        ScriptedEndpoint(body, contentType = "application/json").use { endpoint ->
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
                "expected the payload's own parse refusal to be reported, got: ${failure.message}",
                failure.message?.contains("whole-body document failed to parse") == true &&
                    failure.message?.contains("choices") == true
            )
        }
    }

    @Test
    fun openAiStillReceivesSseDeltasLineByLineFromTheShippedTransport() {
        ScriptedEndpoint(openAiSseBody, contentType = "text/event-stream").use { endpoint ->
            val chunks = runBlocking {
                OpenAiChatCompletionsProvider(
                    apiKey = "test-key",
                    model = "gpt-test",
                    endpoint = endpoint.url,
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

    /** Routes through the real socket, but implements only `post()`. */
    private class PostOnlyTransport(private val url: String) : HttpTransport {
        private val delegate = JavaNetHttpTransport(connectTimeoutMillis = 5_000, readTimeoutMillis = 5_000)

        override suspend fun post(request: HttpRequest) = delegate.post(request.copy(url = url))
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
        private val server = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
        private val worker = thread(isDaemon = true) {
            // One request per call: a case that asks the transport and then the
            // provider makes more than one connection, so the endpoint keeps
            // answering until it is closed.
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    socket.use { handle(it, body, contentType) }
                } catch (_: IOException) {
                    // The client may hang up once it has the answer.
                }
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
