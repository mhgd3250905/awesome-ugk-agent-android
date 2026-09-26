package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming contract is line/event framed, but the SDK cannot assume the
 * transport respects it.
 *
 * [HttpTransport.postStream] declares "逐行发射响应数据", yet its only abstract
 * member is [HttpTransport.post]: a host that implements just `post()` inherits
 * the default, which emits the whole body as ONE emission. Providers that treat
 * every emission as one SSE line then fail to parse it, drop every event, and
 * still report a successful empty answer. The same applies to a transport that
 * emits raw socket chunks, splitting one event across two emissions, and to the
 * spec-legal case of one event whose payload spans several `data:` lines.
 *
 * Silent loss is the real damage: the runtime stores the truncated transcript as
 * the model's final answer.
 */
class ProviderStreamFramingTest {

    @Test
    fun anthropicStreamsEveryEventWhenTheHostOnlyImplementsPost() = runBlocking {
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = HttpResponse(200, anthropicSse())
            }
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas().joinToString(separator = ""))
        assertEquals("第一段内容", chunks.completed().content)
    }

    @Test
    fun anthropicReadsEventsWhenOneEmissionCarriesSeveralLines() = runBlocking {
        val body = anthropicSse()
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = error("Unexpected non-stream call")

                // A transport that forwards whatever the socket delivered: each
                // emission holds several complete SSE lines.
                override fun postStream(request: HttpRequest): Flow<String> = flow {
                    body.lineSequence().chunked(3).forEach { emit(it.joinToString("\n")) }
                }
            }
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas().joinToString(separator = ""))
        assertEquals("第一段内容", chunks.completed().content)
    }

    @Test
    fun anthropicReportsAnEmissionCutMidLineInsteadOfAnsweringWithHalf() = runBlocking {
        val body = anthropicSse()
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = error("Unexpected non-stream call")

                // Violates the documented line framing: emissions end in the
                // middle of a line. Silent truncation into a "successful" answer
                // is the failure being prevented here, so the stream must fail.
                override fun postStream(request: HttpRequest): Flow<String> = flow {
                    var index = 0
                    while (index < body.length) {
                        emit(body.substring(index, minOf(index + 7, body.length)))
                        index += 7
                    }
                }
            }
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.generateStream(request()).toList() }
        }
        Unit
    }

    @Test
    fun anthropicJoinsDataLinesOfOneEvent() = runBlocking {
        // WHATWG event-stream framing: consecutive `data:` lines belong to the
        // same event and are joined with a newline, which stays valid JSON when
        // the split falls between members (a gateway that re-wraps long lines).
        val lines = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
            "",
            "event: content_block_start",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,",
            "data: \"delta\":{\"type\":\"text_delta\",\"text\":\"第一段内容\"}}",
            "",
            "event: content_block_stop",
            """data: {"type":"content_block_stop","index":0}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}"""
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = streamingTransport(lines)
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas().joinToString(separator = ""))
    }

    @Test
    fun anthropicFailsClosedOnAnUnrecoverableEventPayload() = runBlocking {
        // A payload that can never be completed (truncated mid-event) must not
        // be reported as a finished answer.
        val lines = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"断"""
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = streamingTransport(lines)
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.generateStream(request()).toList() }
        }
        assertTrue(
            "the failure must name the malformed event, got: ${error.message}",
            error.message?.contains("SSE") == true
        )
    }

    @Test
    fun openAiStreamsEveryEventWhenTheHostOnlyImplementsPost() = runBlocking {
        val body = listOf(
            """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"第一段内容"}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "",
            "data: [DONE]"
        ).joinToString("\n")
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = HttpResponse(200, body)
            }
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas().joinToString(separator = ""))
    }

    private fun streamingTransport(lines: List<String>) = object : HttpTransport {
        override suspend fun post(request: HttpRequest) = error("Unexpected non-stream call")
        override fun postStream(request: HttpRequest): Flow<String> = lines.asFlow()
    }

    private fun request() = ModelRequest(
        sessionId = "s1",
        messages = listOf(AgentMessage.User("hello")),
        tools = emptyList()
    )

    private fun List<ModelStreamChunk>.contentDeltas() =
        filterIsInstance<ModelStreamChunk.ContentDelta>().map { it.delta }

    private fun List<ModelStreamChunk>.completed(): ModelResponse {
        val completed = filterIsInstance<ModelStreamChunk.Completed>().single()
        return completed.response
    }

    /** A well-formed Anthropic SSE body, one event per `data:` line. */
    private fun anthropicSse(): String = listOf(
        "event: message_start",
        """data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
        "",
        "event: content_block_start",
        """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "",
        "event: content_block_delta",
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"第一段内容"}}""",
        "",
        "event: content_block_stop",
        """data: {"type":"content_block_stop","index":0}""",
        "",
        "event: message_delta",
        """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
        "",
        "event: message_stop",
        """data: {"type":"message_stop"}"""
    ).joinToString("\n")
}
