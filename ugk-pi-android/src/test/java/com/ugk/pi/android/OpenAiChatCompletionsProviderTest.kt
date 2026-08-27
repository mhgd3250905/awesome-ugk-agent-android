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

    @Test
    fun generateIncludesMultimodalImageContent() = runBlocking {
        val transport = RecordingHttpTransport(
            """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "识别到图片内容：红苹果"
                      }
                    }
                  ]
                }
            """.trimIndent()
        )
        val provider = OpenAiChatCompletionsProvider(
            apiKey = "test-key",
            model = "gpt-4o",
            transport = transport
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s-multimodal-openai",
                messages = listOf(
                    AgentMessage.User(
                        content = "识别图片",
                        images = listOf(
                            AgentImageContent(
                                base64Data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
                                mimeType = "image/png"
                            )
                        )
                    )
                ),
                tools = emptyList()
            )
        )

        assertEquals("识别到图片内容：红苹果", response.content)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(transport.request.body).let {
            it as kotlinx.serialization.json.JsonObject
        }
        val messages = body["messages"]?.let { it as kotlinx.serialization.json.JsonArray }
        val userMsg = messages?.firstOrNull()?.let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("user", userMsg?.get("role")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        val contentArray = userMsg?.get("content")?.let { it as kotlinx.serialization.json.JsonArray }
        org.junit.Assert.assertNotNull(contentArray)
        assertEquals(2, contentArray!!.size)

        val imgObj = contentArray[0] as kotlinx.serialization.json.JsonObject
        assertEquals("image_url", (imgObj["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        val imgUrlObj = imgObj["image_url"] as kotlinx.serialization.json.JsonObject
        val url = (imgUrlObj["url"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", url)

        val textObj = contentArray[1] as kotlinx.serialization.json.JsonObject
        assertEquals("text", (textObj["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("识别图片", (textObj["text"] as kotlinx.serialization.json.JsonPrimitive).content)
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
