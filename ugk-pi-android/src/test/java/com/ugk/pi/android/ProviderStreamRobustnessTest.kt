package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-5 P0 review regressions for stream/parse robustness:
 *
 * - C1: a mid-stream OpenAI `data: {"error": ...}` event must fail the run
 *   instead of being silently dropped (which finished a truncated answer as
 *   a normal completion).
 * - C2: tool_calls continuation chunks that omit `index` must extend the
 *   active draft instead of fabricating a phantom draft whose blank id gets
 *   the real arguments dropped and the first tool executed with `{}`.
 * - C3: the HttpTransport.postStream default fallback must split the body
 *   into lines; emitting the whole SSE body as one item dropped every event.
 * - C4: a 200 response whose body is an error JSON must surface the API
 *   error instead of parsing into a blank successful ModelResponse.
 */
class ProviderStreamRobustnessTest {

    private class LineTransport(private val lines: List<String>) : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse =
            HttpResponse(200, lines.joinToString("\n"))

        override fun postStream(request: HttpRequest): Flow<String> = flowOf(*lines.toTypedArray())
    }

    /** Implements only post(): exercises the postStream default fallback. */
    private class PostOnlyTransport(private val body: String) : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse = HttpResponse(200, body)
    }

    private fun modelRequest() = ModelRequest(
        sessionId = "s1",
        messages = listOf(AgentMessage.User("hi")),
        tools = emptyList()
    )

    // ------------------------------------------------------------------
    // C1: OpenAI mid-stream error event
    // ------------------------------------------------------------------

    @Test
    fun `openai stream mid-stream error event fails instead of completing truncated`() = runBlocking {
        val transport = LineTransport(
            listOf(
                """data: {"choices":[{"delta":{"content":"partial ans"}}]}""",
                """data: {"error":{"message":"rate limited","type":"rate_limit_error"}}""",
                "data: [DONE]"
            )
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = transport
        )

        try {
            provider.generateStream(modelRequest()).toList()
            fail("expected the mid-stream error event to fail the stream")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "error message should be surfaced, was: ${expected.message}",
                expected.message.orEmpty().contains("rate limited")
            )
        }
    }

    // ------------------------------------------------------------------
    // C2: OpenAI tool_calls continuation without `index`
    // ------------------------------------------------------------------

    @Test
    fun `openai tool call continuation chunks without index extend the active draft`() = runBlocking {
        val transport = LineTransport(
            listOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"sample_action","arguments":""}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"deviceId\":\"abc\"}"}}]}}]}""",
                "data: [DONE]"
            )
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = transport
        )

        var completed: ModelResponse? = null
        provider.generateStream(modelRequest()).collect { chunk ->
            if (chunk is ModelStreamChunk.Completed) completed = chunk.response
        }

        val response = completed!!
        assertEquals(1, response.toolCalls.size)
        val call = response.toolCalls[0]
        assertEquals("call-1", call.id)
        assertEquals("sample_action", call.name)
        assertEquals(
            "continuation arguments must land on the active draft",
            """{"deviceId":"abc"}""",
            call.input.toString()
        )
    }

    @Test
    fun `openai tool call chunks without index still keep distinct parallel calls`() = runBlocking {
        val transport = LineTransport(
            listOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"tool_a","arguments":"{\"x\":1}"}}]}}]}""",
                """data: {"choices":[{"delta":{"tool_calls":[{"id":"call-2","type":"function","function":{"name":"tool_b","arguments":"{\"y\":2}"}}]}}]}""",
                "data: [DONE]"
            )
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = transport
        )

        var completed: ModelResponse? = null
        provider.generateStream(modelRequest()).collect { chunk ->
            if (chunk is ModelStreamChunk.Completed) completed = chunk.response
        }

        val response = completed!!
        assertEquals(2, response.toolCalls.size)
        assertEquals("tool_a", response.toolCalls[0].name)
        assertEquals("tool_b", response.toolCalls[1].name)
    }

    // ------------------------------------------------------------------
    // C3: default postStream must split lines
    // ------------------------------------------------------------------

    @Test
    fun `anthropic provider consumes a whole sse body returned by a post-only transport`() = runBlocking {
        val body = buildString {
            append("event: message_start\n")
            append("""data: {"type":"message_start"}""")
            append("\n\n")
            append("event: content_block_delta\n")
            append("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello world"}}""")
            append("\n\n")
            append("event: message_delta\n")
            append("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            append("\n\n")
            append("event: message_stop\n")
            append("""data: {"type":"message_stop"}""")
            append("\n\n")
        }
        val provider = AnthropicMessagesProvider(
            apiKey = "k",
            model = "m",
            baseUrl = "https://example.test",
            transport = PostOnlyTransport(body)
        )

        var content = ""
        var completedResponse: ModelResponse? = null
        provider.generateStream(modelRequest()).collect { chunk ->
            when (chunk) {
                is ModelStreamChunk.ContentDelta -> content += chunk.delta
                is ModelStreamChunk.Completed -> completedResponse = chunk.response
                else -> Unit
            }
        }

        assertEquals("hello world", content)
        val response = completedResponse!!
        assertEquals("hello world", response.content)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun `openai provider consumes a whole sse body returned by a post-only transport`() = runBlocking {
        val body = buildString {
            append("""data: {"choices":[{"delta":{"content":"hello openai"}}]}""")
            append("\n\n")
            append("""data: {"choices":[{"finish_reason":"stop"}]}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = PostOnlyTransport(body)
        )

        var content = ""
        var completedResponse: ModelResponse? = null
        provider.generateStream(modelRequest()).collect { chunk ->
            when (chunk) {
                is ModelStreamChunk.ContentDelta -> content += chunk.delta
                is ModelStreamChunk.Completed -> completedResponse = chunk.response
                else -> Unit
            }
        }

        assertEquals("hello openai", content)
        assertEquals("stop", completedResponse!!.stopReason)
    }

    // ------------------------------------------------------------------
    // C4: 200 + error JSON body must surface the API error
    // ------------------------------------------------------------------

    @Test
    fun `anthropic generate with error json body throws the api error`() = runBlocking {
        val transport = RecordingErrorBodyTransport(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "k",
            model = "m",
            baseUrl = "https://example.test",
            transport = transport
        )

        try {
            provider.generate(modelRequest())
            fail("expected the error body to fail the request")
        } catch (expected: IllegalStateException) {
            assertTrue(
                expected.message.orEmpty().contains("Overloaded")
            )
        }
    }

    @Test
    fun `openai generate with error json body throws the api error`() = runBlocking {
        val transport = RecordingErrorBodyTransport(
            """{"error":{"message":"insufficient_quota","type":"insufficient_quota"}}"""
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = transport
        )

        try {
            provider.generate(modelRequest())
            fail("expected the error body to fail the request")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("insufficient_quota"))
        }
    }

    private class RecordingErrorBodyTransport(private val body: String) : HttpTransport {
        val request: HttpRequest = HttpRequest("", emptyMap(), "")
        override suspend fun post(request: HttpRequest): HttpResponse = HttpResponse(200, body)
        override fun postStream(request: HttpRequest): Flow<String> =
            throw UnsupportedOperationException("non-stream tests must not call postStream()")
    }

    // ------------------------------------------------------------------
    // Review follow-up: a non-SSE backend answering the streaming request
    // with a full error JSON body must surface the API error instead of
    // degrading into a blank end-of-stream completion.
    // ------------------------------------------------------------------

    @Test
    fun `anthropic stream with full error json body fails instead of completing blank`() = runBlocking {
        val provider = AnthropicMessagesProvider(
            apiKey = "k",
            model = "m",
            baseUrl = "https://example.test",
            transport = PostOnlyTransport(
                """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
            )
        )

        try {
            provider.generateStream(modelRequest()).toList()
            fail("expected the error body to fail the stream")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Overloaded"))
        }
    }

    @Test
    fun `openai stream with full error json body fails instead of completing blank`() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = PostOnlyTransport(
                """{"error":{"message":"insufficient_quota","type":"insufficient_quota"}}"""
            )
        )

        try {
            provider.generateStream(modelRequest()).toList()
            fail("expected the error body to fail the stream")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("insufficient_quota"))
        }
    }

    // ------------------------------------------------------------------
    // C9: adjacent assistant messages must serialize as one assistant
    // message (the Messages API rejects consecutive assistant roles)
    // ------------------------------------------------------------------

    @Test
    fun `anthropic serializer merges adjacent assistant messages`() = runBlocking {
        val transport = RequestCaptureTransport()
        val provider = AnthropicMessagesProvider(
            apiKey = "k",
            model = "m",
            baseUrl = "https://example.test",
            transport = transport
        )

        provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(
                    AgentMessage.User("hi"),
                    AgentMessage.Assistant("part one"),
                    AgentMessage.Assistant("part two")
                ),
                tools = emptyList()
            )
        )

        val root = Json.parseToJsonElement(transport.capturedBody).jsonObject
        val messages = root["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val contentBlocks = assistant["content"]!!.jsonArray
        assertEquals(2, contentBlocks.size)
        assertEquals("part one", contentBlocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("part two", contentBlocks[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private class RequestCaptureTransport : HttpTransport {
        lateinit var capturedBody: String
        override suspend fun post(request: HttpRequest): HttpResponse {
            capturedBody = request.body
            return HttpResponse(200, """{"content":[{"type":"text","text":"ok"}]}""")
        }

        override fun postStream(request: HttpRequest): Flow<String> =
            throw UnsupportedOperationException("non-stream tests must not call postStream()")
    }
}
