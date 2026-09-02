package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider-level maxStreamedBytes parameter is only plumbed into the
 * default transport. Combining an explicitly supplied transport (any
 * implementation, including JavaNetHttpTransport) with an explicitly
 * non-default cap must fail fast instead of silently ignoring the cap.
 */
class ProviderMaxStreamedBytesGuardTest {

    private class StubTransport : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse =
            HttpResponse(statusCode = 200, body = "{}")

        override fun postStream(request: HttpRequest): Flow<String> =
            throw UnsupportedOperationException("not used")
    }

    private fun requireMessageOf(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        check(error is IllegalArgumentException) { "expected an IllegalArgumentException, got: $error" }
        return error.message.orEmpty()
    }

    @Test
    fun `anthropic rejects a custom transport with an explicit non-default cap`() {
        val message = requireMessageOf {
            AnthropicMessagesProvider(
                apiKey = "k",
                model = "m",
                baseUrl = "https://example.com",
                transport = StubTransport(),
                maxStreamedBytes = 1024
            )
        }
        assertTrue(message.contains("configure the cap on the supplied HttpTransport"))
    }

    @Test
    fun `openai rejects a custom transport with an explicit non-default cap`() {
        val message = requireMessageOf {
            OpenAiChatCompletionsProvider(
                apiKey = "k",
                model = "m",
                transport = StubTransport(),
                endpoint = "https://example.com/v1/chat/completions",
                maxStreamedBytes = 1024
            )
        }
        assertTrue(message.contains("configure the cap on the supplied HttpTransport"))
    }

    @Test
    fun `custom transports stay allowed with the default cap`() {
        AnthropicMessagesProvider(
            apiKey = "k",
            model = "m",
            baseUrl = "https://example.com",
            transport = StubTransport()
        )
        OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            transport = JavaNetHttpTransport(),
            endpoint = "https://example.com/v1/chat/completions"
        )
    }

    @Test
    fun `explicit caps stay allowed without a custom transport`() {
        AnthropicMessagesProvider(
            apiKey = "k",
            model = "m",
            baseUrl = "https://example.com",
            maxStreamedBytes = 64 * 1024
        )
        OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            endpoint = "https://example.com/v1/chat/completions",
            maxStreamedBytes = 64 * 1024
        )
    }
}
