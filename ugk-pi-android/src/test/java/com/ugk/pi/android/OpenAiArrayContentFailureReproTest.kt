package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REPRO for the array-content crash candidate defect (candidate 5).
 *
 * OpenAI-compatible gateways may serialize `delta.content` /
 * `message.content` as an ARRAY of content parts (multi-part format) instead
 * of a plain string. Both paths of [OpenAiChatCompletionsProvider] access
 * content via `jsonPrimitive`:
 * - streaming: `delta["content"]?.jsonPrimitive?.contentOrNull`
 * - non-streaming: `message["content"]?.jsonPrimitive?.contentOrNull`
 * `jsonPrimitive` on a [kotlinx.serialization.json.JsonArray] throws, and
 * nothing catches it: the exception escapes the SSE collect (failing the
 * whole stream) or [generate] respectively.
 *
 * The tests pin the CURRENT behavior: an array content blows up the stream /
 * the call instead of being tolerated (e.g. by joining the text parts).
 */
class OpenAiArrayContentFailureReproTest {

    @Test
    fun `stream fails when delta content is an array of content parts`() = runBlocking {
        val deltaLine = "data: " + buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        putJsonObject("delta") {
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "x")
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }.toString()
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest): HttpResponse =
                    throw UnsupportedOperationException("stream repro must not call post()")

                override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                    kotlinx.coroutines.flow.flow {
                        emit(deltaLine)
                        emit("data: [DONE]")
                    }
            }
        )

        val error = runCatching {
            provider.generateStream(
                ModelRequest(
                    sessionId = "s1",
                    messages = listOf(AgentMessage.User("hello")),
                    tools = emptyList()
                )
            ).toList()
        }.exceptionOrNull()

        assertTrue(
            "current behavior: array delta.content escapes as an exception and fails the stream, " +
                "got: ${error?.let { it::class.java.name + ": " + it.message }}",
            error != null
        )
        println("STREAM array-content failure -> ${error?.javaClass?.name}: ${error?.message}")
    }

    @Test
    fun `non-streaming generate fails when message content is an array`() = runBlocking {
        val body = buildJsonObject {
            put("id", "chatcmpl-1")
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("finish_reason", "stop")
                        putJsonObject("message") {
                            put("role", "assistant")
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "x")
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }.toString()
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest): HttpResponse =
                    HttpResponse(statusCode = 200, body = body)

                override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                    throw UnsupportedOperationException("non-stream repro must not call postStream()")
            }
        )

        val error = runCatching {
            provider.generate(
                ModelRequest(
                    sessionId = "s1",
                    messages = listOf(AgentMessage.User("hello")),
                    tools = emptyList()
                )
            )
        }.exceptionOrNull()

        assertTrue(
            "current behavior: array message.content escapes as an exception and fails generate, " +
                "got: ${error?.let { it::class.java.name + ": " + it.message }}",
            error != null
        )
        println("NON-STREAM array-content failure -> ${error?.javaClass?.name}: ${error?.message}")
    }
}
