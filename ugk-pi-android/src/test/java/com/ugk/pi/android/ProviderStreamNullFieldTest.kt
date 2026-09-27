package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproducers for the round-7 review: the OpenAI stream reader used throwing
 * `jsonObject` / `jsonArray` accessors on fields that the protocol marks as
 * optional and that real gateways serialize as JSON `null`. A `null` element is
 * not a Kotlin `null`, so `?.jsonObject` does not short-circuit - it throws
 * `IllegalArgumentException`, which the runtime reports as a failed run.
 *
 * Both shapes below come from ordinary OpenAI-compatible servers (POJO/Jackson
 * serializations emit `"delta":null` and `"error":null` rather than omitting the
 * key), so a healthy endpoint ended the turn with an error instead of the
 * answer. The Anthropic reader in the same SDK already uses safe casts.
 */
class ProviderStreamNullFieldTest {
    @Test
    fun openAiStreamWithNullDeltaChunkCompletesNormally() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = lineTransport(
                """data: {"choices":[{"index":0,"delta":{"content":"第一段内容"},"finish_reason":null}]}""",
                "",
                """data: {"choices":[{"index":0,"delta":null,"finish_reason":"stop"}]}""",
                "",
                "data: [DONE]"
            )
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas().joinToString(separator = ""))
        assertEquals("第一段内容", chunks.completed().content)
    }

    @Test
    fun openAiStreamWithNullErrorFieldIsNotTreatedAsAnError() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = lineTransport(
                """data: {"error":null,"choices":[{"index":0,"delta":{"content":"第一段内容"}}]}""",
                "",
                """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                "",
                "data: [DONE]"
            )
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.completed().content)
    }

    @Test
    fun openAiStreamWithNullChoicesFieldIsSkippedNotFatal() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = lineTransport(
                """data: {"choices":null}""",
                "",
                """data: {"choices":[{"index":0,"delta":{"content":"第一段内容"}}]}""",
                "",
                """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                "",
                "data: [DONE]"
            )
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.completed().content)
    }

    @Test
    fun openAiStreamWithNullToolCallsFieldKeepsTheAnswer() = runBlocking {
        // A gateway that serializes its response DTO with default inclusion
        // emits `"tool_calls":null` on every ordinary text answer.
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = lineTransport(
                """data: {"choices":[{"index":0,"delta":{"content":"第一段内容","tool_calls":null},"finish_reason":null}]}""",
                "",
                """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                "",
                "data: [DONE]"
            )
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.completed().content)
        assertEquals(emptyList<Any>(), chunks.completed().toolCalls)
    }

    @Test
    fun openAiNonStreamResponseWithNullToolCallsFieldKeepsTheAnswer() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = HttpResponse(
                    200,
                    """{"id":"resp_1","choices":[{"index":0,"message":{"role":"assistant",""" +
                        """"content":"完整回答","tool_calls":null,"reasoning_content":null},""" +
                        """"finish_reason":"stop"}]}"""
                )
            }
        )

        val response = provider.generate(request())

        assertEquals("完整回答", response.content)
        assertEquals(emptyList<Any>(), response.toolCalls)
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

    private fun List<ModelStreamChunk>.contentDeltas() =
        filterIsInstance<ModelStreamChunk.ContentDelta>().map { it.delta }

    private fun List<ModelStreamChunk>.completed(): ModelResponse =
        filterIsInstance<ModelStreamChunk.Completed>().single().response
}
