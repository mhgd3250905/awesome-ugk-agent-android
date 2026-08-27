package com.ugk.pi.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesProviderTest {
    @Test
    fun `serializes messages tools and parses tool calls`() = runBlocking {
        val transport = RecordingHttpTransport(
            responseBody = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "thinking",
                      "thinking": "private reasoning"
                    },
                    {
                      "type": "text",
                      "text": "checking device"
                    },
                    {
                      "type": "tool_use",
                      "id": "toolu_1",
                      "name": "sample_action",
                      "input": {
                        "deviceId": "abc"
                      }
                    }
                  ],
                  "stop_reason": "tool_use"
                }
            """.trimIndent()
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "deepseek-v4-pro",
            baseUrl = "https://example.com/apps/anthropic",
            transport = transport
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(
                    AgentMessage.System("Use tools when needed."),
                    AgentMessage.User("check device"),
                    AgentMessage.Assistant(
                        content = "I will call a tool",
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
        assertEquals("tool_use", response.stopReason)
        assertEquals("private reasoning", response.reasoningContent)
        assertEquals(
            ToolCall(
                id = "toolu_1",
                name = "sample_action",
                input = JsonObject(mapOf("deviceId" to JsonPrimitive("abc")))
            ),
            response.toolCalls.single()
        )
        assertEquals("https://example.com/apps/anthropic/v1/messages", transport.request.url)
        assertEquals("test-key", transport.request.headers["x-api-key"])
        assertEquals("2023-06-01", transport.request.headers["anthropic-version"])
        assertTrue(transport.request.body.contains("\"model\":\"deepseek-v4-pro\""))
        assertTrue(transport.request.body.contains("\"max_tokens\":32768"))
        assertTrue(transport.request.body.contains("\"system\":\"Use tools when needed.\""))
        assertTrue(transport.request.body.contains("\"type\":\"thinking\""))
        assertTrue(transport.request.body.contains("\"thinking\":\"previous reasoning\""))
        assertTrue(transport.request.body.contains("\"type\":\"tool_use\""))
        assertTrue(transport.request.body.contains("\"type\":\"tool_result\""))
        assertTrue(transport.request.body.contains("\"tools\""))
    }

    @Test
    fun `serializes consecutive tool results in one anthropic user message`() = runBlocking {
        val transport = RecordingHttpTransport(
            responseBody = simpleTextResponse("done")
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "deepseek-v4-flash",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = AnthropicRetryPolicy(maxAttempts = 1, initialDelayMillis = 0)
        )

        provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(
                    AgentMessage.User("connect device"),
                    AgentMessage.Assistant(
                        content = "checking",
                        toolCalls = listOf(
                            ToolCall("call-1", "first_tool", JsonObject(emptyMap())),
                            ToolCall("call-2", "second_tool", JsonObject(emptyMap()))
                        )
                    ),
                    AgentMessage.Tool(ToolResult("call-1", "first_tool", "first ok")),
                    AgentMessage.Tool(ToolResult("call-2", "second_tool", "second ok"))
                ),
                tools = emptyList()
            )
        )

        val messages = Json.parseToJsonElement(transport.request.body)
            .jsonObject["messages"]!!
            .jsonArray
        val toolResultMessage = messages[2].jsonObject

        assertEquals("user", toolResultMessage["role"]?.jsonPrimitive?.content)
        assertEquals(2, toolResultMessage["content"]?.jsonArray?.size)
        assertEquals(
            listOf("call-1", "call-2"),
            toolResultMessage["content"]!!
                .jsonArray
                .map { it.jsonObject["tool_use_id"]?.jsonPrimitive?.content }
        )
    }

    @Test
    fun `retries retryable http failures then returns response`() = runBlocking {
        val transport = SequencedHttpTransport(
            HttpResponse(statusCode = 429, body = "rate limited"),
            HttpResponse(statusCode = 200, body = simpleTextResponse("ok"))
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "deepseek-v4-flash",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = AnthropicRetryPolicy(maxAttempts = 2, initialDelayMillis = 0)
        )

        val response = provider.generate(simpleRequest())

        assertEquals("ok", response.content)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `does not retry bad requests`() = runBlocking {
        val transport = SequencedHttpTransport(
            HttpResponse(statusCode = 400, body = "bad request")
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "deepseek-v4-flash",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = AnthropicRetryPolicy(maxAttempts = 3, initialDelayMillis = 0)
        )

        val error = runCatching { provider.generate(simpleRequest()) }.exceptionOrNull()

        assertEquals(1, transport.callCount)
        assertTrue(error?.message?.contains("400 bad request") == true)
    }

    @Test
    fun `retries transport exceptions`() = runBlocking {
        val transport = FailingOnceHttpTransport()
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "deepseek-v4-flash",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = AnthropicRetryPolicy(maxAttempts = 2, initialDelayMillis = 0)
        )

        val response = provider.generate(simpleRequest())

        assertEquals("ok", response.content)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `does not retry coroutine cancellation`() = runBlocking {
        var callCount = 0
        val transport = object : HttpTransport {
            override suspend fun post(request: HttpRequest): HttpResponse {
                callCount++
                throw CancellationException("cancelled")
            }
        }
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "deepseek-v4-flash",
            baseUrl = "https://example.com/anthropic",
            transport = transport,
            retryPolicy = AnthropicRetryPolicy(maxAttempts = 3, initialDelayMillis = 0)
        )

        val error = runCatching { provider.generate(simpleRequest()) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, callCount)
    }

    @Test
    fun `generateStream parses sse thinking and text deltas and builds complete response`() = runBlocking {
        val sseLines = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-3-7-sonnet","stop_reason":null}}""",
            "",
            "event: content_block_start",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think."}}""",
            "",
            "event: content_block_stop",
            """data: {"type":"content_block_stop","index":0}""",
            "",
            "event: content_block_start",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello "}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"world!"}}""",
            "",
            "event: content_block_stop",
            """data: {"type":"content_block_stop","index":1}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}""",
            ""
        )
        val transport = object : HttpTransport {
            override suspend fun post(request: HttpRequest): HttpResponse = error("Unexpected non-stream call")
            override fun postStream(request: HttpRequest): Flow<String> = sseLines.asFlow()
        }
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "claude-3-7-sonnet",
            baseUrl = "https://example.com/anthropic",
            transport = transport
        )

        val chunks = provider.generateStream(simpleRequest()).toList()

        assertEquals(ModelStreamChunk.ThinkingDelta("Let me think.") as ModelStreamChunk, chunks[0])
        assertEquals(ModelStreamChunk.ContentDelta("Hello ") as ModelStreamChunk, chunks[1])
        assertEquals(ModelStreamChunk.ContentDelta("world!") as ModelStreamChunk, chunks[2])
        val completed = chunks[3] as ModelStreamChunk.Completed
        assertEquals("Hello world!", completed.response.content)
        assertEquals("Let me think.", completed.response.reasoningContent)
        assertEquals("end_turn", completed.response.stopReason)
    }

    private fun simpleRequest(): ModelRequest {
        return ModelRequest(
            sessionId = "s1",
            messages = listOf(AgentMessage.User("hello")),
            tools = emptyList()
        )
    }

    private fun simpleTextResponse(text: String): String {
        return """
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "content": [
                {
                  "type": "text",
                  "text": "$text"
                }
              ],
              "stop_reason": "end_turn"
            }
        """.trimIndent()
    }

    @Test
    fun generateIncludesMultimodalImageContent() = runBlocking {
        val transport = RecordingHttpTransport(
            simpleTextResponse("识别完成：图中有小猫")
        )
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "GLM-5.3-Flash",
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            transport = transport
        )

        val response = provider.generate(
            ModelRequest(
                sessionId = "s-multimodal",
                messages = listOf(
                    AgentMessage.User(
                        content = "看看图里有什么",
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

        assertEquals("识别完成：图中有小猫", response.content)
        val body = Json.parseToJsonElement(transport.request.body).jsonObject
        val messages = body["messages"]?.jsonArray
        val userMsg = messages?.firstOrNull()?.jsonObject
        assertEquals("user", userMsg?.get("role")?.jsonPrimitive?.content)
        val contentBlocks = userMsg?.get("content")?.jsonArray
        assertNotNull(contentBlocks)
        assertEquals(2, contentBlocks!!.size)

        val imageBlock = contentBlocks[0].jsonObject
        assertEquals("image", imageBlock["type"]?.jsonPrimitive?.content)
        val source = imageBlock["source"]?.jsonObject
        assertEquals("base64", source?.get("type")?.jsonPrimitive?.content)
        assertEquals("image/png", source?.get("media_type")?.jsonPrimitive?.content)
        assertEquals("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", source?.get("data")?.jsonPrimitive?.content)

        val textBlock = contentBlocks[1].jsonObject
        assertEquals("text", textBlock["type"]?.jsonPrimitive?.content)
        assertEquals("看看图里有什么", textBlock["text"]?.jsonPrimitive?.content)
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

    private class SequencedHttpTransport(
        private vararg val responses: HttpResponse
    ) : HttpTransport {
        var callCount = 0

        override suspend fun post(request: HttpRequest): HttpResponse {
            val response = responses.getOrElse(callCount) { responses.last() }
            callCount += 1
            return response
        }
    }

    private inner class FailingOnceHttpTransport : HttpTransport {
        var callCount = 0

        override suspend fun post(request: HttpRequest): HttpResponse {
            callCount += 1
            if (callCount == 1) {
                throw java.io.IOException("network down")
            }
            return HttpResponse(statusCode = 200, body = simpleTextResponse("ok"))
        }
    }
}
