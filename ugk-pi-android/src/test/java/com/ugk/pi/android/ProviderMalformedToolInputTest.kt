package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truncated tool-call arguments must never be silently replaced with an
 * empty input object. When a model response is cut off mid-JSON (typically
 * with stop_reason=max_tokens / finish_reason=length), the accumulated
 * argument string is unparseable; executing the tool with fabricated `{}`
 * runs it with inputs the model never chose. Both providers must drop such
 * calls instead, which routes the response into the existing
 * incomplete-response retry path of the runtime.
 *
 * An *empty* argument string stays a legitimate "no arguments" call and must
 * keep serializing as an empty object.
 */
class ProviderMalformedToolInputTest {

    @Test
    fun `anthropic stream drops a tool call whose truncated input cannot be parsed`() = runBlocking {
        val lines = listOf(
            "data: {\"type\":\"message_start\",\"message\":{}}",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"terminal_bash_execute\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"rm\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}",
            "data: {\"type\":\"message_stop\"}"
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "https://example.com",
            transport = ListedLinesTransport(lines)
        )

        val chunks = provider.generateStream(
            ModelRequest(sessionId = "s1", messages = listOf(AgentMessage.User("hello")), tools = emptyList())
        ).toList()
        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().single()

        assertTrue(
            "truncated tool call must be dropped, was: ${completed.response.toolCalls}",
            completed.response.toolCalls.isEmpty()
        )
        assertTrue(
            "stop reason must be preserved for the retry path, was: ${completed.response.stopReason}",
            completed.response.stopReason == "max_tokens"
        )
    }

    @Test
    fun `anthropic stream keeps an empty argument string as a no-argument call`() = runBlocking {
        val lines = listOf(
            "data: {\"type\":\"message_start\",\"message\":{}}",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"list_skills\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}",
            "data: {\"type\":\"message_stop\"}"
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "https://example.com",
            transport = ListedLinesTransport(lines)
        )

        val chunks = provider.generateStream(
            ModelRequest(sessionId = "s1", messages = listOf(AgentMessage.User("hello")), tools = emptyList())
        ).toList()
        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().single()

        assertTrue(
            "empty-argument call must survive with empty input, was: ${completed.response.toolCalls}",
            completed.response.toolCalls.size == 1 &&
                completed.response.toolCalls.single().input.toString() == "{}"
        )
    }

    @Test
    fun `openai stream drops a tool call whose truncated arguments cannot be parsed`() = runBlocking {
        val lines = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"terminal_bash_execute\",\"arguments\":\"{\\\"command\\\":\\\"rm\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
            "data: [DONE]"
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = ListedLinesTransport(lines)
        )

        val chunks = provider.generateStream(
            ModelRequest(sessionId = "s1", messages = listOf(AgentMessage.User("hello")), tools = emptyList())
        ).toList()
        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().single()

        assertTrue(
            "truncated tool call must be dropped, was: ${completed.response.toolCalls}",
            completed.response.toolCalls.isEmpty()
        )
        assertTrue(
            "finish reason must be preserved for the retry path, was: ${completed.response.stopReason}",
            completed.response.stopReason == "length"
        )
    }

    @Test
    fun `openai stream keeps an empty arguments string as a no-argument call`() = runBlocking {
        val lines = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_2\",\"function\":{\"name\":\"list_skills\",\"arguments\":\"\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "data: [DONE]"
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = ListedLinesTransport(lines)
        )

        val chunks = provider.generateStream(
            ModelRequest(sessionId = "s1", messages = listOf(AgentMessage.User("hello")), tools = emptyList())
        ).toList()
        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().single()

        assertTrue(
            "empty-argument call must survive with empty input, was: ${completed.response.toolCalls}",
            completed.response.toolCalls.size == 1 &&
                completed.response.toolCalls.single().input.toString() == "{}"
        )
    }

    private class ListedLinesTransport(
        private val lines: List<String>
    ) : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse =
            throw UnsupportedOperationException("stream tests must not call post()")

        override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
            kotlinx.coroutines.flow.flow { lines.forEach { emit(it) } }
    }
}
