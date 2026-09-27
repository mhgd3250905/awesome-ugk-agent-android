package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducers for the round-7 review: the Anthropic reader accepted a
 * `tool_use` block whose `id` or `name` was an empty string, and the OpenAI
 * reader explicitly rejects it (`buildFinalToolCalls`, with a comment).
 *
 * A blank id is not merely untidy: `AgentRuntime` appends the ToolResult under
 * that id, so every later request serializes `tool_use:{id:""}` and the real
 * API rejects the whole conversation for a min-length violation. The session
 * cannot recover, because the poison is already persisted in the transcript.
 * A custom-`baseUrl` gateway is exactly where such a block comes from.
 */
class AnthropicBlankToolCallIdTest {
    @Test
    fun streamDropsAToolCallWithABlankIdInsteadOfBrickingTheSession() = runBlocking {
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = lineTransport(
                """data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
                "",
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"","name":"web_search"}}""",
                "",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"k\"}"}}""",
                "",
                """data: {"type":"content_block_stop","index":0}""",
                "",
                """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                "",
                """data: {"type":"message_stop"}"""
            )
        )

        val response = provider.generateStream(request()).toList().completed()

        assertTrue(
            "a blank tool_use id gets persisted and then rejected by the API on every " +
                "later request: ${response.toolCalls}",
            response.toolCalls.none { it.id.isBlank() || it.name.isBlank() }
        )
    }

    @Test
    fun nonStreamDropsAToolCallWithABlankId() = runBlocking {
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = HttpResponse(
                    200,
                    """{"id":"msg_1","role":"assistant","stop_reason":"tool_use","content":[""" +
                        """{"type":"tool_use","id":"","name":"web_search","input":{"query":"k"}}]}"""
                )
            }
        )

        val response = provider.generate(request())

        assertTrue(
            "blank ids must not reach the transcript: ${response.toolCalls}",
            response.toolCalls.none { it.id.isBlank() || it.name.isBlank() }
        )
    }

    @Test
    fun nonStreamKeepsAToolCallWithARealId() = runBlocking {
        // The other direction: the guard must not discard valid tool calls.
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = HttpResponse(
                    200,
                    """{"id":"msg_1","role":"assistant","stop_reason":"tool_use","content":[""" +
                        """{"type":"tool_use","id":"toolu_1","name":"web_search","input":{"query":"k"}}]}"""
                )
            }
        )

        val response = provider.generate(request())

        assertEquals(1, response.toolCalls.size)
        assertEquals("toolu_1", response.toolCalls.single().id)
        assertEquals("k", response.toolCalls.single().input["query"]?.toString()?.trim('"'))
    }

    @Test
    fun nonStreamWithNullContentFieldDoesNotCrash() = runBlocking {
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = HttpResponse(
                    200,
                    """{"id":"msg_1","role":"assistant","content":null,"stop_reason":"end_turn"}"""
                )
            }
        )

        val response = provider.generate(request())

        assertTrue(response.toolCalls.isEmpty())
    }

    private fun lineTransport(vararg lines: String): HttpTransport = object : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse =
            error("Unexpected non-stream call")

        override fun postStream(request: HttpRequest): Flow<String> = flow {
            lines.forEach { emit(it) }
        }
    }

    private fun request() = ModelRequest(
        sessionId = "s1",
        messages = listOf(AgentMessage.User("hello")),
        tools = emptyList()
    )

    private fun List<ModelStreamChunk>.completed(): ModelResponse =
        filterIsInstance<ModelStreamChunk.Completed>().single().response
}
