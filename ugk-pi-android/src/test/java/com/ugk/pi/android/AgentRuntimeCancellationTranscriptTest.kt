package com.ugk.pi.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that cancelling a run in the middle of a tool batch leaves the
 * session transcript valid for the next model request: every assistant
 * tool_use must have a tool_result. Providers reject the whole request
 * (HTTP 400) when a tool_use is missing its result, which would make the
 * session permanently unusable.
 */
class AgentRuntimeCancellationTranscriptTest {

    @Test
    fun `cancelling mid tool batch completes pending tool results`() = runBlocking {
        val enteredTool = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        val tool = object : AgentTool {
            override val name = "hanging_tool"
            override val description = "Suspends until released"
            override val inputSchema = JsonObject(emptyMap())

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                enteredTool.complete(Unit)
                releaseTool.await()
                return ToolResult(call.id, name, "late result")
            }
        }
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return if (requestCount == 1) {
                    ModelResponse(
                        content = "running the tool",
                        toolCalls = listOf(
                            ToolCall("call-1", "hanging_tool", JsonObject(emptyMap())),
                            ToolCall("call-2", "hanging_tool", JsonObject(emptyMap()))
                        )
                    )
                } else {
                    ModelResponse(content = "done")
                }
            }
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .toolRegistry(ToolRegistry().register(tool))
            .build()
        val session = AgentSession("cancellation-transcript")

        val job = launch {
            runtime.run(session, "start").collect { }
        }
        enteredTool.await()
        job.cancel()
        job.join()

        val assistant = session.messages.filterIsInstance<AgentMessage.Assistant>()
            .lastOrNull { it.toolCalls.isNotEmpty() }
        assertTrue("session must contain the assistant tool_use envelope", assistant != null)
        val expectedIds = assistant!!.toolCalls.map { it.id }.toSet()
        val resultIds = session.messages
            .filterIsInstance<AgentMessage.Tool>()
            .map { it.result.toolCallId }
            .toSet()
        assertTrue(
            "cancelled run must leave a tool_result for every tool_use: expected $expectedIds, got $resultIds",
            resultIds.containsAll(expectedIds)
        )
    }
}
