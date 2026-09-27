package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-7 follow-ups from the independent review: the blank-`tool_use`-id policy
 * added for Anthropic streaming had to hold on the sibling paths too, and the
 * non-stream Anthropic reader was fabricating an empty argument object for an
 * `input` the model never sent as an object.
 *
 * A fabricated `{}` is not harmless: the confirmation ticket is bound to the
 * input that is executed, so a wrong-typed `input` would run a protected Tool
 * with arguments nobody chose while the user approved something else.
 */
class ProviderToolCallIntegrityTest {
    @Test
    fun openAiNonStreamDropsAToolCallWithABlankId() = runBlocking {
        val body = buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", "")
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("id", "")
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", "terminal_bash_execute")
                                    put("arguments", "{}")
                                }
                            })
                        }
                    }
                })
            }
        }.toString()
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "k",
            model = "m",
            endpoint = "https://example.com/v1/chat/completions",
            transport = staticTransport(body)
        )

        val response = provider.generate(request())

        assertTrue(
            "a blank id would brick every later request: ${response.toolCalls}",
            response.toolCalls.none { it.id.isBlank() || it.name.isBlank() }
        )
    }

    @Test
    fun anthropicNonStreamDropsAToolCallWhoseInputIsNotAnObject() = runBlocking {
        val body = buildJsonObject {
            put("id", "msg_1")
            put("role", "assistant")
            put("stop_reason", "tool_use")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", "toolu_1")
                    put("name", "terminal_bash_execute")
                    put("input", "rm -rf /")
                })
            }
        }.toString()
        val provider = AnthropicMessagesProvider(
            apiKey = "k",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = staticTransport(body)
        )

        val response = provider.generate(request())

        assertTrue(
            "a wrong-typed input must be dropped, not turned into {} : ${response.toolCalls}",
            response.toolCalls.none { it.input.isEmpty() }
        )
    }

    @Test
    fun anthropicNonStreamKeepsANoArgumentToolCallWhoseInputIsAbsent() = runBlocking {
        // Reverse check for both guards: an absent `input` is the protocol's
        // legitimate no-argument call and must still execute.
        val body = buildJsonObject {
            put("id", "msg_1")
            put("role", "assistant")
            put("stop_reason", "tool_use")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", "toolu_2")
                    put("name", "screen_read")
                })
            }
        }.toString()
        val provider = AnthropicMessagesProvider(
            apiKey = "k",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = staticTransport(body)
        )

        val response = provider.generate(request())

        assertEquals(1, response.toolCalls.size)
        assertEquals("toolu_2", response.toolCalls.single().id)
        assertTrue(response.toolCalls.single().input.isEmpty())
    }

    private fun staticTransport(body: String): HttpTransport = object : HttpTransport {
        override suspend fun post(request: HttpRequest) = HttpResponse(200, body)
    }

    private fun request() = ModelRequest(
        sessionId = "s1",
        messages = listOf(AgentMessage.User("hello")),
        tools = emptyList()
    )
}
