package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that any failure escaping the tool loop — not only cancellation —
 * repairs the assistant tool_use envelope before the run ends. Providers
 * reject the whole next request when a tool_use has no tool_result, so an
 * unrepaired envelope would make the session permanently unusable.
 */
class AgentRuntimeToolLoopFailureTest {

    @Test
    fun `malformed terminal metadata keeps the session usable for a subsequent run`() = runBlocking {
        val tool = object : AgentTool {
            override val name = "bad_metadata_tool"
            override val description = "Returns non-primitive terminal metadata"
            override val inputSchema = JsonObject(emptyMap())

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                return if (call.id == "call-1") {
                    ToolResult(
                        toolCallId = call.id,
                        name = name,
                        content = "raw stop",
                        metadata = JsonObject(
                            mapOf(
                                // Not a JsonPrimitive: parsing this used to
                                // throw IllegalArgumentException inside the
                                // tool loop.
                                "terminalForTurn" to JsonObject(
                                    mapOf("value" to JsonPrimitive(true))
                                )
                            )
                        )
                    )
                } else {
                    ToolResult(toolCallId = call.id, name = name, content = "ok")
                }
            }
        }
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return if (requestCount == 1) {
                    ModelResponse(
                        content = "checking",
                        toolCalls = listOf(
                            ToolCall("call-1", "bad_metadata_tool", JsonObject(emptyMap())),
                            ToolCall("call-2", "bad_metadata_tool", JsonObject(emptyMap()))
                        )
                    )
                } else {
                    ModelResponse(content = "recovered")
                }
            }
        }
        val runtime = AgentRuntime(provider, ToolRegistry().register(tool))
        val session = AgentSession("malformed-terminal-metadata")

        runCatching { runtime.run(session, "first").toList() }

        val secondEvents = runtime.run(session, "second").toList()
        assertTrue(
            "the second run must complete instead of failing transcript validation",
            secondEvents.last() is AgentEvent.Completed
        )
        assertEquals(AgentEvent.Completed("recovered"), secondEvents.last())
    }

    @Test
    fun `collector failure after a tool batch keeps the session usable for a subsequent run`() = runBlocking {
        val tool = object : AgentTool {
            override val name = "pair_tool"
            override val description = "Returns a plain result"
            override val inputSchema = JsonObject(emptyMap())

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                return ToolResult(toolCallId = call.id, name = name, content = "ok")
            }
        }
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return if (requestCount == 1) {
                    ModelResponse(
                        content = "checking",
                        toolCalls = listOf(
                            ToolCall("call-1", "pair_tool", JsonObject(emptyMap())),
                            ToolCall("call-2", "pair_tool", JsonObject(emptyMap()))
                        )
                    )
                } else {
                    ModelResponse(content = "recovered")
                }
            }
        }
        val runtime = AgentRuntime(provider, ToolRegistry().register(tool))
        val session = AgentSession("collector-failure")

        val firstRunError = runCatching {
            runtime.run(session, "first").collect { event ->
                if (event is AgentEvent.ToolFinished) {
                    // Explodes after the first tool result: call-2 is still
                    // unanswered inside the current tool_use envelope.
                    throw IllegalStateException("collector exploded")
                }
            }
        }.exceptionOrNull()
        assertTrue(firstRunError is IllegalStateException)

        val secondEvents = runtime.run(session, "second").toList()
        assertTrue(
            "the second run must complete instead of failing transcript validation",
            secondEvents.last() is AgentEvent.Completed
        )
        assertEquals(AgentEvent.Completed("recovered"), secondEvents.last())
        assertEquals(2, requestCount)
    }
}
