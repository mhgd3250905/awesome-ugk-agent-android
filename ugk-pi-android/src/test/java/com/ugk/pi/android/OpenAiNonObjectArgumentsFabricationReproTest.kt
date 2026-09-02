package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REPRO for the fabricated non-object arguments candidate defect (candidate 4).
 *
 * The OpenAI streaming path establishes a "drop, never fabricate" policy for
 * tool arguments that parse but are not JSON objects
 * (parseToolArgumentsOrNull returns null for them; see
 * ProviderMalformedToolInputTest). The non-streaming path does the opposite:
 * toToolCallOrNull wraps any parseable non-object arguments into
 * `{"value": <parsed>}` and returns an executable tool call, i.e. it
 * fabricates arguments the model never chose in that shape.
 *
 * The tests pin the CURRENT behavior:
 * - non-streaming: arguments '"str"' (a JSON string), '[1,2]' and '123'
 *   produce ToolCall.input {"value": ...} instead of being dropped;
 * - streaming (control): the same '"str"' arguments are dropped, confirming
 *   the two paths disagree.
 */
class OpenAiNonObjectArgumentsFabricationReproTest {

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
            throw UnsupportedOperationException("non-stream repro must not call postStream()")
    }

    @Test
    fun `non-streaming wraps a JSON-string arguments into value key`() = runBlocking {
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

        val call = response.toolCalls.single()
        // Fabricated input: {"value":"str"}
        assertEquals(
            "current behavior: non-object arguments are fabricated into {\"value\": ...}",
            JsonObject(mapOf("value" to JsonPrimitive("str"))),
            call.input
        )
        assertEquals("{\"value\":\"str\"}", call.input.toString())
    }

    @Test
    fun `non-streaming wraps a JSON-array arguments into value key`() = runBlocking {
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

        val call = response.toolCalls.single()
        assertTrue(
            "current behavior: array arguments are fabricated into {\"value\": [...]}, was: ${call.input}",
            call.input["value"] != null && call.input.containsKey("value")
        )
        assertEquals("[1,2]", call.input["value"].toString())
    }

    @Test
    fun `non-streaming wraps a JSON-number arguments into value key`() = runBlocking {
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

        val call = response.toolCalls.single()
        assertEquals(
            "current behavior: number arguments are fabricated into {\"value\": 123}",
            JsonPrimitive(123),
            call.input["value"]
        )
    }

    @Test
    fun `streaming control drops the same non-object arguments`() = runBlocking {
        // Same payload as the non-streaming case ('"str"'), but delivered
        // through the streaming path: the established policy drops it.
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
