package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REPRO for the session-bricking candidate defect (candidate 1).
 *
 * A third-party tool may return a [ToolResult] whose [ToolResult.toolCallId]
 * does not match the id of the executed [ToolCall]. The runtime appends that
 * result to the transcript without any id check
 * (AgentRuntime.runInternal: `session.append(AgentMessage.Tool(durableResult))`
 * has no `result.toolCallId == call.id` guard), so the transcript now holds a
 * tool_result that matches no open tool_use.
 *
 * On the next model request (or completion boundary) the runtime calls
 * [AgentSession.prepareTranscript], whose validateTranscript rejects the
 * poisoned transcript with
 * "Transcript contains a tool_result without a matching tool_use"
 * (AgentSession.kt, the `require(result.toolCallId in expectedIds)` branch).
 * Because the poisoned Tool message is never repaired, every subsequent run
 * of the same session fails the same way without ever reaching the provider:
 * the session is permanently unusable.
 *
 * The assertions below pin the CURRENT (buggy) behavior so a later fix can
 * flip them into regression coverage.
 */
class WrongToolCallIdSessionBrickReproTest {

    private fun mismatchedTool() = object : AgentTool {
        override val name = "mismatched_result_tool"
        override val description = "Returns a ToolResult with a wrong toolCallId"
        override val inputSchema = JsonObject(emptyMap())

        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            return ToolResult(
                toolCallId = "wrong-id",
                name = name,
                content = "result whose id matches no tool_use"
            )
        }
    }

    @Test
    fun `run fails at transcript preparation when a tool returns a mismatched result id`() = runBlocking {
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return ModelResponse(
                    content = "calling tool",
                    toolCalls = listOf(
                        ToolCall("call-1", "mismatched_result_tool", JsonObject(emptyMap()))
                    )
                )
            }
        }
        val runtime = AgentRuntime(provider, ToolRegistry().register(mismatchedTool()))
        val session = AgentSession("wrong-id-first-run")

        val events = runtime.run(session, "first").toList()

        // Current behavior: the run dies in transcript preparation, not at the
        // tool boundary where the mismatch is detectable.
        val failure = events.last()
        assertTrue(
            "run must end Failed, was: $failure",
            failure is AgentEvent.Failed
        )
        assertTrue(
            "failure must name the transcript validation error, was: ${(failure as AgentEvent.Failed).message}",
            failure.message.contains("tool_result without a matching tool_use")
        )
        assertEquals(1, requestCount)

        // The poisoned entries stay in the durable transcript.
        val toolMessage = session.messages.filterIsInstance<AgentMessage.Tool>().single()
        assertEquals("wrong-id", toolMessage.result.toolCallId)
        val envelope = session.messages.filterIsInstance<AgentMessage.Assistant>()
            .single { it.toolCalls.isNotEmpty() }
        assertEquals(listOf("call-1"), envelope.toolCalls.map { it.id })
    }

    @Test
    fun `session stays unusable for a subsequent run after a mismatched result id`() = runBlocking {
        var requestCount = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requestCount++
                return if (requestCount == 1) {
                    ModelResponse(
                        content = "calling tool",
                        toolCalls = listOf(
                            ToolCall("call-1", "mismatched_result_tool", JsonObject(emptyMap()))
                        )
                    )
                } else {
                    ModelResponse(content = "must not be reached")
                }
            }
        }
        val runtime = AgentRuntime(provider, ToolRegistry().register(mismatchedTool()))
        val session = AgentSession("wrong-id-brick")

        runCatching { runtime.run(session, "first").toList() }

        // Second run on the SAME session: currently fails identically and
        // never reaches the provider, which proves the bricking.
        val secondEvents = runtime.run(session, "second").toList()
        val secondFailure = secondEvents.last()
        assertTrue(
            "second run must also end Failed under the current behavior, was: $secondFailure",
            secondFailure is AgentEvent.Failed
        )
        assertTrue(
            "second failure must be the same transcript validation error, was: ${(secondFailure as AgentEvent.Failed).message}",
            secondFailure.message.contains("tool_result without a matching tool_use")
        )
        assertEquals(
            "second run must not reach the provider: preparation rejects the poisoned transcript",
            1,
            requestCount
        )
    }

    @Test
    fun `prepareTranscript rejects a tool result whose id matches no open tool_use`() {
        // Session-level evidence pinpointing validateTranscript as the throw
        // site (AgentSession.validateTranscript, the
        // `require(result.toolCallId in expectedIds)` branch).
        val session = AgentSession("wrong-id-direct")
        session.append(AgentMessage.User("hi"))
        session.append(
            AgentMessage.Assistant(
                content = "working",
                toolCalls = listOf(ToolCall("call-1", "t", JsonObject(emptyMap())))
            )
        )
        session.append(AgentMessage.Tool(ToolResult("wrong-id", "t", "bad")))

        val error = runCatching {
            session.prepareTranscript(NoOpTranscriptPreparationPolicy)
        }.exceptionOrNull()

        assertTrue(
            "prepareTranscript must throw, was: $error",
            error != null
        )
        assertEquals(
            "Transcript contains a tool_result without a matching tool_use",
            error?.message
        )
    }
}
