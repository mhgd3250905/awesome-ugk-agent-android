package com.ugk.pi.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
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

    @Test
    fun `a repaired session completes a subsequent run`() = runBlocking {
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
        val seenRequests = mutableListOf<ModelRequest>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                seenRequests += request
                return if (requestCount == 1) {
                    ModelResponse(
                        content = "running the tool",
                        toolCalls = listOf(ToolCall("call-1", "hanging_tool", JsonObject(emptyMap())))
                    )
                } else {
                    ModelResponse(content = "recovered after cancellation")
                }
            }
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .toolRegistry(ToolRegistry().register(tool))
            .build()
        val session = AgentSession("cancellation-recovery")

        val firstRun = launch {
            runtime.run(session, "start").collect { }
        }
        enteredTool.await()
        firstRun.cancel()
        firstRun.join()

        var completed: AgentEvent.Completed? = null
        runtime.run(session, "continue after cancel").collect { event ->
            if (event is AgentEvent.Completed) completed = event
        }

        assertEquals("recovered after cancellation", completed?.content)
        val resumeRequest = seenRequests.last()
        val lastAssistantWithCalls = resumeRequest.messages
            .filterIsInstance<AgentMessage.Assistant>()
            .lastOrNull { it.toolCalls.isNotEmpty() }
        assertTrue(
            "the cancelled envelope must still be present in the resumed request",
            lastAssistantWithCalls != null
        )
        val answeredIds = resumeRequest.messages
            .filterIsInstance<AgentMessage.Tool>()
            .map { it.result.toolCallId }
            .toSet()
        assertTrue(
            "the resumed request must answer every tool_use of the cancelled envelope",
            answeredIds.containsAll(lastAssistantWithCalls!!.toolCalls.map { it.id })
        )
    }

    @Test
    fun `cancelling after partial tool completion keeps real and synthetic results`() = runBlocking {
        val firstDone = CompletableDeferred<Unit>()
        val enteredSecond = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val tool = object : AgentTool {
            override val name = "pair_tool"
            override val description = "First call is quick, second suspends"
            override val inputSchema = JsonObject(emptyMap())

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                return when (call.id) {
                    "quick" -> {
                        firstDone.complete(Unit)
                        ToolResult(call.id, name, "quick result")
                    }

                    else -> {
                        enteredSecond.complete(Unit)
                        releaseSecond.await()
                        ToolResult(call.id, name, "late result")
                    }
                }
            }
        }
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return if (requestCount == 1) {
                    ModelResponse(
                        content = "running the tools",
                        toolCalls = listOf(
                            ToolCall("quick", "pair_tool", JsonObject(emptyMap())),
                            ToolCall("hanging", "pair_tool", JsonObject(emptyMap()))
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
        val session = AgentSession("cancellation-partial")

        val job = launch {
            runtime.run(session, "start").collect { }
        }
        enteredSecond.await()
        job.cancel()
        job.join()

        val results = session.messages.filterIsInstance<AgentMessage.Tool>()
        assertEquals(2, results.size)
        assertEquals("quick", results[0].result.toolCallId)
        assertEquals("quick result", results[0].result.content)
        assertEquals("hanging", results[1].result.toolCallId)
        assertTrue("the unanswered call needs an error result", results[1].result.isError)
    }

    @Test
    fun `reused tool call ids across envelopes are still repaired`() = runBlocking {
        // OpenAI-compatible local servers commonly reuse ids such as call_0 in
        // every response; repair must still answer the cancelled envelope even
        // when an older result with the same id already exists.
        val enteredSecondRun = CompletableDeferred<Unit>()
        var executionCount = 0
        val tool = object : AgentTool {
            override val name = "hanging_tool"
            override val description = "First execution is quick, later ones suspend"
            override val inputSchema = JsonObject(emptyMap())

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                executionCount++
                if (executionCount == 1) {
                    return ToolResult(call.id, name, "first result")
                }
                enteredSecondRun.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return when (requestCount) {
                    1, 3 -> ModelResponse(
                        content = "running the tool",
                        toolCalls = listOf(ToolCall("call_0", "hanging_tool", JsonObject(emptyMap())))
                    )

                    else -> ModelResponse(content = "turn finished")
                }
            }
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .toolRegistry(ToolRegistry().register(tool))
            .build()
        val session = AgentSession("cancellation-id-reuse")

        // First run completes normally, leaving a real result for call_0.
        runtime.run(session, "first").collect { }

        val job = launch {
            runtime.run(session, "second").collect { }
        }
        enteredSecondRun.await()
        job.cancel()
        job.join()

        val envelopes = session.messages
            .filterIsInstance<AgentMessage.Assistant>()
            .filter { it.toolCalls.isNotEmpty() }
        assertEquals(2, envelopes.size)
        val resultsForReusedId = session.messages
            .filterIsInstance<AgentMessage.Tool>()
            .filter { it.result.toolCallId == "call_0" }
        assertEquals(
            "the reused id needs its original result plus one synthetic repair result",
            2,
            resultsForReusedId.size
        )
        assertTrue(
            "the synthetic repair result must be an error result",
            resultsForReusedId.last().result.isError
        )
    }
}
