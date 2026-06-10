package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProviderTest {
    @Test
    fun `serializes messages tools and parses tool calls`() = runBlocking {
        val transport = RecordingHttpTransport(
            responseBody = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "content": "checking device",
                        "reasoning_content": "private reasoning",
                        "tool_calls": [
                          {
                            "id": "call-1",
                            "type": "function",
                            "function": {
                              "name": "sample_action",
                              "arguments": "{\"deviceId\":\"abc\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent()
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "gpt-4o-mini",
            transport = transport
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(
                    AgentMessage.System("Use tools when needed."),
                    AgentMessage.User("check device"),
                    AgentMessage.Assistant(
                        content = "calling",
                        toolCalls = listOf(
                            ToolCall(
                                id = "previous-call",
                                name = "sample_action",
                                input = JsonObject(mapOf("deviceId" to JsonPrimitive("abc")))
                            )
                        ),
                        reasoningContent = "previous reasoning"
                    ),
                    AgentMessage.Tool(
                        ToolResult(
                            toolCallId = "previous-call",
                            name = "sample_action",
                            content = "ok"
                        )
                    )
                ),
                tools = listOf(
                    AgentToolDefinition(
                        name = "sample_action",
                        description = "Runs one sample action.",
                        inputSchema = JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(emptyMap())
                            )
                        )
                    )
                )
            )
        )

        assertEquals("checking device", response.content)
        assertEquals("tool_calls", response.stopReason)
        assertEquals("private reasoning", response.reasoningContent)
        assertEquals(
            ToolCall(
                id = "call-1",
                name = "sample_action",
                input = JsonObject(mapOf("deviceId" to JsonPrimitive("abc")))
            ),
            response.toolCalls.single()
        )
        assertEquals("https://api.openai.com/v1/chat/completions", transport.request.url)
        assertEquals("Bearer test-key", transport.request.headers["Authorization"])
        assertTrue(transport.request.body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(transport.request.body.contains("\"role\":\"system\""))
        assertTrue(transport.request.body.contains("\"role\":\"tool\""))
        assertTrue(transport.request.body.contains("\"tool_call_id\":\"previous-call\""))
        assertTrue(transport.request.body.contains("\"reasoning_content\":\"previous reasoning\""))
        assertTrue(transport.request.body.contains("\"tools\""))
    }

    private class RecordingHttpTransport(
        private val responseBody: String
    ) : HttpTransport {
        lateinit var request: HttpRequest

        override suspend fun post(request: HttpRequest): HttpResponse {
            this.request = request
            return HttpResponse(statusCode = 200, body = responseBody)
        }
    }
}
