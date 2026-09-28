package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming calls historically had no retry at all: a 429/5xx or a connect
 * failure surfaced straight to the host as [AgentEvent.Failed] even though the
 * same [AnthropicRetryPolicy] covers the non-streaming Anthropic call. The
 * safe window is "before the first emitted chunk" — the host has seen nothing,
 * so replaying the request is invisible — and that is the only window these
 * tests allow. After the first chunk (or on non-retryable failures like 401)
 * the failure must propagate untouched.
 */
class ProviderStreamRetryTest {

    private class FlakyStreamTransport(
        private val failures: Iterator<Throwable?>,
        private val body: String
    ) : HttpTransport {
        var streamCalls = 0
            private set

        override suspend fun post(request: HttpRequest) = error("Unexpected non-stream call")

        override fun postStream(request: HttpRequest): Flow<String> = flow {
            streamCalls++
            failures.next()?.let { throw it }
            emitAll(body.lineSequence().asFlow())
        }
    }

    private class FlakyPostTransport(
        private val responses: Iterator<HttpResponse>
    ) : HttpTransport {
        var postCalls = 0
            private set

        override suspend fun post(request: HttpRequest): HttpResponse {
            postCalls++
            return responses.next()
        }

        override fun postStream(request: HttpRequest) = error("Unexpected stream call")
    }

    @Test
    fun anthropicStreamRetriesARateLimitBeforeTheFirstChunk() = runBlocking {
        val transport = FlakyStreamTransport(
            failures = listOf(httpStatusFailure(429), httpStatusFailure(503), null).iterator(),
            body = anthropicSse()
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = instantRetryPolicy()
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas())
        assertEquals(3, transport.streamCalls)
    }

    @Test
    fun openAiStreamRetriesAConnectionFailureBeforeTheFirstChunk() = runBlocking {
        val transport = FlakyStreamTransport(
            failures = listOf(java.net.ConnectException("connect failed"), null).iterator(),
            body = openAiSse()
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = transport,
            retryPolicy = instantRetryPolicy()
        )

        val chunks = provider.generateStream(request()).toList()

        assertEquals("第一段内容", chunks.contentDeltas())
        assertEquals(2, transport.streamCalls)
    }

    @Test
    fun openAiGenerateRetriesARateLimit() = runBlocking {
        val transport = FlakyPostTransport(
            responses = listOf(
                HttpResponse(429, """{"error":{"message":"rate limited"}}"""),
                HttpResponse(200, openAiCompletionBody())
            ).iterator()
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = transport,
            retryPolicy = instantRetryPolicy()
        )

        val response = provider.generate(request())

        assertEquals("ok", response.content)
        assertEquals(2, transport.postCalls)
    }

    @Test
    fun streamDoesNotRetryAfterTheFirstChunkWasEmitted() {
        // A mid-stream failure cannot be replayed: the host already received
        // (and the runtime already stores) part of the answer.
        var streamCalls = 0
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = error("Unexpected non-stream call")

                override fun postStream(request: HttpRequest): Flow<String> {
                    streamCalls++
                    return flow {
                        emit(anthropicSse())
                        throw java.io.IOException("connection reset mid-stream")
                    }
                }
            },
            retryPolicy = instantRetryPolicy()
        )

        val error = assertThrows(java.io.IOException::class.java) {
            runBlocking { provider.generateStream(request()).toList() }
        }
        assertEquals("connection reset mid-stream", error.message)
        assertEquals(1, streamCalls)
    }

    @Test
    fun streamDoesNotRetryAnUnauthorizedResponse() {
        val transport = FlakyStreamTransport(
            failures = listOf(httpStatusFailure(401), null).iterator(),
            body = anthropicSse()
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = instantRetryPolicy()
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.generateStream(request()).toList() }
        }

        assertTrue(error.message!!.startsWith("HTTP request failed: 401"))
        assertEquals(1, transport.streamCalls)
    }

    @Test
    fun streamDoesNotRetryAMidReadIoFailureLikeAByteCapViolation() {
        // A maxStreamedBytes violation surfaces as a plain IOException raised
        // while the body is already being read. Replaying it would mask the
        // real failure (the server is typically gone by then) and re-bill the
        // request, so mid-read IO failures propagate on the first attempt.
        var streamCalls = 0
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest) = error("Unexpected non-stream call")

                override fun postStream(request: HttpRequest): Flow<String> {
                    streamCalls++
                    return flow {
                        emit("""data: {"type":"message_start","message":{"id":"m","role":"assistant","content":[]}}""")
                        throw java.io.IOException("response body exceeded maxStreamedBytes (4 bytes)")
                    }
                }
            },
            retryPolicy = instantRetryPolicy()
        )

        val error = assertThrows(java.io.IOException::class.java) {
            runBlocking { provider.generateStream(request()).toList() }
        }
        assertTrue(error.message!!.contains("maxStreamedBytes"))
        assertEquals(1, streamCalls)
    }

    private fun httpStatusFailure(status: Int) =
        IllegalStateException("HTTP request failed: $status rate-limited-by-gateway")

    private fun instantRetryPolicy() = AnthropicRetryPolicy(
        maxAttempts = 3,
        initialDelayMillis = 0,
        maxDelayMillis = 0
    )

    private fun request() = ModelRequest(
        sessionId = "s1",
        messages = listOf(AgentMessage.User("hello")),
        tools = emptyList()
    )

    private fun List<ModelStreamChunk>.contentDeltas(): String =
        filterIsInstance<ModelStreamChunk.ContentDelta>().joinToString(separator = "") { it.delta }

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

    private fun openAiSse(): String = listOf(
        """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"第一段内容"}}]}""",
        "",
        """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        "",
        "data: [DONE]"
    ).joinToString("\n")

    private fun openAiCompletionBody(): String = """
        {"id":"c1","object":"chat.completion","created":1,"model":"test-model",
         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
    """.trimIndent()
}
