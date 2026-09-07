package com.ugk.pi.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-5 P0 review regressions for the run failure contract: every failed
 * run must end with an [AgentEvent.Failed] event instead of a raw exception
 * escaping the flow to the collector.
 */
class AgentRuntimeFailureContractTest {

    private class NoopTool : AgentTool {
        override val name: String = "noop"
        override val description: String = "Does nothing."
        override val inputSchema: JsonObject = JsonObject(
            mapOf("type" to JsonPrimitive("object"))
        )
        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult =
            ToolResult(toolCallId = call.id, name = name, content = "ok")
    }

    /** First request answers with a tool call, the second with a final answer. */
    private class ToolThenAnswerProvider : LLMProvider {
        var requests = 0
        override suspend fun generate(request: ModelRequest): ModelResponse =
            ModelResponse(content = "done")

        override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> {
            requests += 1
            return if (requests == 1) {
                flowOf(
                    ModelStreamChunk.Completed(
                        ModelResponse(
                            content = "",
                            toolCalls = listOf(
                                ToolCall(id = "call-1", name = "noop", input = JsonObject(emptyMap()))
                            ),
                            stopReason = "tool_use"
                        )
                    )
                )
            } else {
                flowOf(ModelStreamChunk.Completed(ModelResponse(content = "done")))
            }
        }
    }

    @Test
    fun `non positive maxIterations surfaces as a Failed event not a thrown exception`() = runBlocking {
        val runtime = AgentRuntime(
            llmProvider = ToolThenAnswerProvider(),
            toolRegistry = ToolRegistry().apply { register(NoopTool()) },
            maxIterations = 0
        )

        val events = runtime.run(AgentSession("s1"), "hi").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("maxIterations"))
    }

    @Test
    fun `a throwing pendingUserMessages callback surfaces as a Failed event`() = runBlocking {
        val runtime = AgentRuntime(
            llmProvider = ToolThenAnswerProvider(),
            toolRegistry = ToolRegistry().apply { register(NoopTool()) }
        )

        val events = runtime.run(
            AgentSession("s1"),
            AgentRunInput(content = "hi"),
            pendingUserMessages = { throw IllegalStateException("host callback exploded") }
        ).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(
            "expected the host error message, was: ${failure.message}",
            failure.message.contains("host callback exploded")
        )
    }
}
