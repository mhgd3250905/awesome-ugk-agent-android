package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the non-object tool arguments policy.
 *
 * The streaming path has an established "drop, never fabricate" policy for
 * tool arguments that parse but are not JSON objects
 * (parseToolArgumentsOrNull returns null for them; see
 * ProviderMalformedToolInputTest). The non-streaming path used to disagree:
 * toToolCallOrNull wrapped any parseable non-object arguments
 * ('"str"', '[1,2]', '123') into `{"value": ...}` and returned an executable
 * tool call, i.e. it fabricated arguments the model never chose in that
 * shape.
 *
 * Both paths now drop such calls: the tool must never run against input the
 * model did not complete choosing in object form. An empty or missing
 * arguments string stays a legitimate no-argument call.
 */
class OpenAiNonObjectArgumentsDroppedTest {

    private fun nonStreamingCompletionBody(argumentsJsonText: String): String =
        buildJsonObject {
            put("id", "chatcmpl-1")
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        put("finish_reason", "tool_calls")
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", "")
                            putJsonArray("tool_calls") {
                                add(
                                    buildJsonObject {
                                        put("id", "call_1")
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", "echo")
                                            put("arguments", argumentsJsonText)
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }.toString()

    private class FixedBodyTransport(private val body: String) : HttpTransport {
        override suspend fun post(request: HttpRequest): HttpResponse =
            HttpResponse(statusCode = 200, body = body)

        override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
            throw UnsupportedOperationException("non-stream tests must not call postStream()")
    }

    @Test
    fun `non-streaming drops a tool call whose arguments are a JSON string`() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = FixedBodyTransport(nonStreamingCompletionBody("\"str\""))
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )

        assertTrue(
            "string arguments must be dropped, not fabricated into {\"value\": ...}, was: ${response.toolCalls}",
            response.toolCalls.isEmpty()
        )
    }

    @Test
    fun `non-streaming drops a tool call whose arguments are a JSON array`() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = FixedBodyTransport(nonStreamingCompletionBody("[1,2]"))
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )

        assertTrue(
            "array arguments must be dropped, not fabricated into {\"value\": [...]}, was: ${response.toolCalls}",
            response.toolCalls.isEmpty()
        )
    }

    @Test
    fun `non-streaming drops a tool call whose arguments are a JSON number`() = runBlocking {
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = FixedBodyTransport(nonStreamingCompletionBody("123"))
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )

        assertTrue(
            "number arguments must be dropped, not fabricated into {\"value\": 123}, was: ${response.toolCalls}",
            response.toolCalls.isEmpty()
        )
    }

    @Test
    fun `non-streaming keeps object arguments and empty no-argument calls`() = runBlocking {
        // Control: the two legitimate shapes must keep working.
        val objectProvider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = FixedBodyTransport(nonStreamingCompletionBody("{\"a\":\"b\"}"))
        )
        val objectResponse = objectProvider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )
        assertEquals(
            JsonObject(mapOf("a" to JsonPrimitive("b"))),
            objectResponse.toolCalls.single().input
        )

        val emptyProvider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "test-model",
            endpoint = "https://example.com/v1/chat/completions",
            transport = FixedBodyTransport(nonStreamingCompletionBody(""))
        )
        val emptyResponse = emptyProvider.generate(
            ModelRequest(
                sessionId = "s2",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        )
        assertEquals(
            JsonObject(emptyMap()),
            emptyResponse.toolCalls.single().input
        )
    }

    @Test
    fun `streaming control drops the same non-object arguments`() = runBlocking {
        // Same payload as the non-streaming case ('"str"'), delivered through
        // the streaming path: the policy must stay identical on both paths.
        val delta = buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        putJsonObject("delta") {
                            putJsonArray("tool_calls") {
                                add(
                                    buildJsonObject {
                                        put("index", 0)
                                        put("id", "call_1")
                                        putJsonObject("function") {
                                            put("name", "echo")
                                            put("arguments", "\"str\"")
                                        }
                                    }
                                )
                            }
                        }
                        put("finish_reason", "tool_calls")
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
                    throw UnsupportedOperationException("stream control must not call post()")

                override fun postStream(request: HttpRequest): kotlinx.coroutines.flow.Flow<String> =
                    kotlinx.coroutines.flow.flow {
                        emit("data: $delta")
                        emit("data: [DONE]")
                    }
            }
        )

        val completed = provider.generateStream(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(AgentMessage.User("hello")),
                tools = emptyList()
            )
        ).toList().filterIsInstance<ModelStreamChunk.Completed>().single()

        assertTrue(
            "streaming path must drop non-object arguments (established policy), was: ${completed.response.toolCalls}",
            completed.response.toolCalls.isEmpty()
        )
    }
}
