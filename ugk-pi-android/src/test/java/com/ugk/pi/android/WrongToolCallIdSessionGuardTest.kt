package com.ugk.pi.android

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the session-bricking defect.
 *
 * A third-party tool may return a [ToolResult] whose [ToolResult.toolCallId]
 * does not match the id of the executed [ToolCall]. The runtime used to
 * append that result without any id check, so the transcript held a
 * tool_result matching no open tool_use and [AgentSession.prepareTranscript]
 * rejected every later request of the session with "tool_result without a
 * matching tool_use" — the session was permanently unusable.
 *
 * The runtime now owns the envelope integrity and normalizes the result id
 * to the executed call while preserving the tool's whole business payload
 * (content, metadata, isError, images, transientModelContent), so the run
 * completes and the session stays healthy.
 */
class WrongToolCallIdSessionGuardTest {

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
    fun `a mismatched result id is normalized so the run completes and the session stays usable`() = runBlocking {
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
                    ModelResponse(content = "done")
                }
            }
        }
        val runtime = AgentRuntime(provider, ToolRegistry().register(mismatchedTool()))
        val session = AgentSession("wrong-id-guard")

        val firstEvents = runtime.run(session, "first").toList()

        // The first run completes instead of dying in transcript preparation.
        assertEquals(AgentEvent.Completed("done"), firstEvents.last())

        // The durable transcript holds the normalized envelope id, so no
        // tool_result without a matching tool_use is stored.
        val toolMessage = session.messages.filterIsInstance<AgentMessage.Tool>().single()
        assertEquals("call-1", toolMessage.result.toolCallId)

        // The transcript is not poisoned: a second run on the SAME session
        // reaches the provider and completes as well.
        val secondEvents = runtime.run(session, "second").toList()
        assertEquals(AgentEvent.Completed("done"), secondEvents.last())
        assertEquals(
            "both runs must reach the provider (tool round + final answer, then a direct answer)",
            3,
            requestCount
        )
    }

    @Test
    fun `normalization keeps the tool payload fields intact`() = runBlocking {
        val image = AgentImageContent(base64Data = "AQID", mimeType = "image/png")
        val requests = mutableListOf<ModelRequest>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += request
                return if (requests.size == 1) {
                    ModelResponse(
                        content = "calling tool",
                        toolCalls = listOf(
                            ToolCall("call-2", "payload_tool", JsonObject(emptyMap()))
                        )
                    )
                } else {
                    ModelResponse(content = "done")
                }
            }
        }
        val tool = object : AgentTool {
            override val name = "payload_tool"
            override val description = "Returns a rich payload with a wrong toolCallId"
            override val inputSchema = JsonObject(emptyMap())

            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                return ToolResult(
                    toolCallId = "totally-wrong-id",
                    name = name,
                    content = "payload content",
                    metadata = JsonObject(mapOf("assistantMessage" to JsonPrimitive("kept"))),
                    images = listOf(image),
                    imageContext = "image context",
                    transientModelContent = "transient text"
                )
            }
        }
        val runtime = AgentRuntime(provider, ToolRegistry().register(tool))
        val session = AgentSession("wrong-id-payload")

        val events = runtime.run(session, "hello").toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("call-2", finished.result.toolCallId)
        assertEquals("payload content", finished.result.content)
        assertEquals("kept", finished.result.metadata["assistantMessage"]?.jsonPrimitive?.content)

        // The transient multimodal payload still reaches the next request
        // even though the envelope id had to be repaired.
        assertEquals(
            listOf(image),
            requests[1].messages.filterIsInstance<AgentMessage.User>().flatMap { it.images }
        )
        assertTrue(
            requests[1].messages.any { it is AgentMessage.User && it.content.contains("transient text") }
        )

        // The durable tool result keeps its metadata but no transient payload.
        val stored = session.messages.filterIsInstance<AgentMessage.Tool>().single().result
        assertEquals("call-2", stored.toolCallId)
        assertEquals("payload content", stored.content)
        assertEquals("kept", stored.metadata["assistantMessage"]?.jsonPrimitive?.content)
        assertTrue(stored.images.isEmpty())
        assertTrue(stored.transientModelContent == null)
    }

    @Test
    fun `prepareTranscript rejects a tool result whose id matches no open tool_use`() {
        // Session-level evidence that validateTranscript still guards the
        // invariant (AgentSession.validateTranscript, the
        // `require(result.toolCallId in expectedIds)` branch); the runtime
        // guard above is what keeps poisoned entries from ever reaching it.
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
