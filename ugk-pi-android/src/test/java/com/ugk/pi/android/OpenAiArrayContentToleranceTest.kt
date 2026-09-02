package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the array-content tolerance.
 *
 * OpenAI-compatible gateways may serialize `delta.content` /
 * `message.content` as an ARRAY of content parts (multi-part format) instead
 * of a plain string. Both paths of [OpenAiChatCompletionsProvider] used to
 * access content via `jsonPrimitive`, which throws on a
 * [kotlinx.serialization.json.JsonArray]: the exception escaped the SSE
 * collect (failing the whole stream) or [OpenAiChatCompletionsProvider.generate]
 * respectively.
 *
 * Both paths now extract text tolerantly: a primitive yields its string, an
 * array yields its text parts concatenated in order (other part types are
 * ignored), and any other shape degrades to an empty string.
 */
class OpenAiArrayContentToleranceTest {

    @Test
    fun `streaming extracts text when delta content is an array of content parts`() = runBlocking {
        val deltaLine = "data: " + buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        putJsonObject("delta") {
                            putJsonArray("content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "Hello ")
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:image/png;base64,AQID")
                                        }
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "world")
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
                    throw UnsupportedOperationException("stream tests must not call post()")

                override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                    kotlinx.coroutines.flow.flow {
                        emit(deltaLine)
                        emit("data: [DONE]")
                    }
            }
        )

        val chunks = provider.generateStream(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        ).toList()

        val completed = chunks.filterIsInstance<ModelStreamChunk.Completed>().single()
        assertEquals(
            "text parts must be concatenated in order with non-text parts ignored",
            "Hello world",
            completed.response.content
        )
    }

    @Test
    fun `non-streaming generate extracts text when message content is an array`() = runBlocking {
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
                                        put("text", "Hello ")
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "refusal")
                                        put("refusal", "must be ignored")
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "world")
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
                    throw UnsupportedOperationException("non-stream tests must not call postStream()")
            }
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )

        assertEquals("Hello world", response.content)
    }

    @Test
    fun `object and null content degrade to empty text without failing`() = runBlocking {
        // Non-text scalar shapes must never escape as an exception.
        val objectContentBody = buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("finish_reason", "stop")
                        putJsonObject("message") {
                            put("role", "assistant")
                            putJsonObject("content") {
                                put("unexpected", "shape")
                            }
                        }
                    }
                )
            }
        }.toString()
        val nullContentBody = buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("finish_reason", "stop")
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", null as String?)
                        }
                    }
                )
            }
        }.toString()

        for (body in listOf(objectContentBody, nullContentBody)) {
            val provider = OpenAiChatCompletionsProvider(
                apiKey = "test-key",
                model = "test-model",
                endpoint = "https://example.com/v1/chat/completions",
                transport = object : HttpTransport {
                    override suspend fun post(request: HttpRequest): HttpResponse =
                        HttpResponse(statusCode = 200, body = body)

                    override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                        throw UnsupportedOperationException("non-stream tests must not call postStream()")
                }
            )
            val response = provider.generate(
                ModelRequest(
                    sessionId = "s1",
                    messages = listOf(AgentMessage.User("hello")),
                    tools = emptyList()
                )
            )
            assertTrue("content must degrade to an empty string, was: '${response.content}'", response.content.isEmpty())
        }
    }

    @Test
    fun `reasoning_content arrays degrade like content arrays`() = runBlocking {
        val streamLine = "data: " + buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        putJsonObject("delta") {
                            putJsonArray("reasoning_content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "think ")
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "step")
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }.toString()
        val streamProvider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest): HttpResponse =
                    throw UnsupportedOperationException("streaming tests must not call post()")

                override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                    kotlinx.coroutines.flow.flowOf(
                        streamLine,
                        "data: [DONE]"
                    )
            }
        )
        val streamResponse = streamProvider.generateStream(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        ).toList()
        assertTrue(
            "stream reasoning must tolerate a content-parts array",
            streamResponse.filterIsInstance<ModelStreamChunk.ThinkingDelta>()
                .any { it.delta.contains("think") }
        )

        val nonStreamBody = buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("finish_reason", "stop")
                        putJsonObject("message") {
                            put("content", "answer")
                            putJsonArray("reasoning_content") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "reasoned")
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }.toString()
        val nonStreamProvider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = object : HttpTransport {
                override suspend fun post(request: HttpRequest): HttpResponse =
                    HttpResponse(statusCode = 200, body = nonStreamBody)

                override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                    throw UnsupportedOperationException("non-stream tests must not call postStream()")
            }
        )
        val response = nonStreamProvider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )
        assertEquals("answer", response.content)
        assertEquals("reasoned", response.reasoningContent)
    }
}
