package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the "blank terminal completion permanently corrupts
 * the session" defect class.
 *
 * A tool that ends the turn with `terminalForTurn` metadata but no usable
 * text used to persist an `Assistant("")` message. Anthropic's Messages API
 * rejects an assistant message whose content array is empty, so every later
 * request for that session failed with 400 with no self-healing path — the
 * same "session permanently broken" class as the tool-loop failure fixed
 * earlier. These tests pin both defense layers:
 *
 *  1. the runtime must never append a blank, tool-less assistant message;
 *  2. the Anthropic provider must never serialize one into an empty content
 *     array, even if such a message already exists in a transcript.
 */
class AgentRuntimeBlankContentGuardTest {

    @Test
    fun `terminal tool result with blank content does not persist a blank assistant message`() = runBlocking {
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(
                    content = "calling terminal tool",
                    toolCalls = listOf(ToolCall("terminal-1", "terminal-blank", JsonObject(emptyMap())))
                )
            })
            .toolRegistry(ToolRegistry().register(object : AgentTool {
                override val name = "terminal-blank"
                override val description = "Ends the turn with no message."
                override val inputSchema = JsonObject(emptyMap())

                override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult =
                    ToolResult(
                        toolCallId = call.id,
                        name = name,
                        content = "   ",
                        metadata = JsonObject(
                            mapOf("terminalForTurn" to JsonPrimitive(true))
                        )
                    )
            }))
            .build()
        val session = AgentSession("blank-terminal-completion")

        val events = runtime.run(session, "hello").toList()

        // The run must still complete (the tool ended the turn) ...
        val completed = events.last()
        assertTrue("expected Completed event but was $completed", completed is AgentEvent.Completed)
        assertTrue(
            "completed text must be non-blank",
            (completed as AgentEvent.Completed).content.isNotBlank()
        )
        // ... but never with a persisted assistant message that is blank and
        // carries no tool calls. Such a message makes the transcript invalid
        // for Anthropic on every future request.
        val blankAssistant = session.messages.filterIsInstance<AgentMessage.Assistant>()
            .any { it.content.isBlank() && it.toolCalls.isEmpty() }
        assertFalse(
            "session must not contain a blank tool-less assistant message: ${session.messages}",
            blankAssistant
        )
    }

    @Test
    fun `anthropic serialization never emits an empty assistant content array`() = runBlocking {
        // Defense in depth: a transcript may already hold a blank tool-less
        // assistant message (legacy data, a future code path, or a host that
        // appends messages itself). The provider must repair it at the
        // serialization boundary instead of sending an invalid request.
        val transport = RecordingTransport()
        val provider = AnthropicMessagesProvider(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "https://example.com",
            transport = transport
        )

        provider.generate(
            ModelRequest(
                sessionId = "s1",
                messages = listOf(
                    AgentMessage.User("hello"),
                    AgentMessage.Assistant("")
                ),
                tools = emptyList()
            )
        )

        val body = Json.parseToJsonElement(transport.request!!.body).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertTrue(messages.size >= 2)
        messages.forEach { element ->
            val message = element.jsonObject
            if (message["role"]!!.toString().contains("assistant")) {
                val content = message["content"]
                if (content is kotlinx.serialization.json.JsonArray) {
                    assertTrue(
                        "assistant content array must never be empty",
                        content.size > 0
                    )
                }
            }
        }
    }

    @Test
    fun `blank run input fails fast without persisting an invalid user message`() = runBlocking {
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse =
                    ModelResponse("should not be reached")
            })
            .build()
        val session = AgentSession("blank-input")

        val events = runtime.run(session, "   ").toList()

        val last = events.last()
        assertTrue("expected Failed event but was $last", last is AgentEvent.Failed)
        assertFalse(
            "a blank user message must not enter the durable transcript",
            session.messages.any { it is AgentMessage.User && it.content.isBlank() && it.images.isEmpty() }
        )
    }

    private class RecordingTransport : HttpTransport {
        var request: HttpRequest? = null

        override suspend fun post(request: HttpRequest): HttpResponse {
            this.request = request
            return HttpResponse(
                statusCode = 200,
                body = """
                    {
                      "id": "msg_1",
                      "type": "message",
                      "role": "assistant",
                      "content": [{"type": "text", "text": "ok"}],
                      "stop_reason": "end_turn"
                    }
                """.trimIndent()
            )
        }
    }
}
