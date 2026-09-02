package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTranscriptPreparationTest {

    @Test
    fun `duplicate tool call ids fail before tool execution or transcript append`() = runBlocking {
        val duplicateFirst = ToolCall("duplicate", "should-not-run", JsonObject(emptyMap()))
        val duplicateSecond = ToolCall("duplicate", "should-not-run", JsonObject(emptyMap()))
        var executionCount = 0
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse =
                    ModelResponse(
                        content = "invalid tool response",
                        toolCalls = listOf(duplicateFirst, duplicateSecond)
                    )
            })
            .toolRegistry(ToolRegistry().register(object : AgentTool {
                override val name = "should-not-run"
                override val description = "Must not execute for duplicate IDs."
                override val inputSchema = JsonObject(emptyMap())

                override suspend fun execute(
                    call: ToolCall,
                    context: ToolExecutionContext
                ): ToolResult {
                    executionCount++
                    return ToolResult(call.id, name, "unexpected")
                }
            }))
            .build()
        val session = AgentSession("duplicate-tool-call-ids")

        val events = runtime.run(session, "hello").toList()

        assertEquals(
            AgentEvent.Failed("Model response contained duplicate tool call ids."),
            events.last()
        )
        assertEquals(0, executionCount)
        assertEquals(listOf(AgentMessage.User("hello")), session.messages)
    }

    @Test
    fun `terminal tool completion closes an unexecuted call before final preparation`() = runBlocking {
        val firstCall = ToolCall("terminal-first", "terminal-stop", JsonObject(emptyMap()))
        val secondCall = ToolCall("terminal-second", "terminal-stop", JsonObject(emptyMap()))
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(
                    content = "stop now",
                    toolCalls = listOf(firstCall, secondCall)
                )
            })
            .toolRegistry(ToolRegistry().register(object : AgentTool {
                override val name = "terminal-stop"
                override val description = "Ends the turn."
                override val inputSchema = JsonObject(emptyMap())

                override suspend fun execute(
                    call: ToolCall,
                    context: ToolExecutionContext
                ): ToolResult = ToolResult(
                    toolCallId = call.id,
                    name = name,
                    content = "stopped",
                    metadata = JsonObject(
                        mapOf(
                            "terminalForTurn" to JsonPrimitive(true),
                            "assistantMessage" to JsonPrimitive("stopped")
                        )
                    )
                )
            }))
            .build()
        val session = AgentSession("preparation-terminal-tool")

        val events = runtime.run(session, "stop").toList()

        assertEquals(AgentEvent.Completed("stopped"), events.last())
        val envelope = session.messages.filterIsInstance<AgentMessage.Assistant>()
            .single { it.toolCalls.isNotEmpty() }
        val results = session.messages.filterIsInstance<AgentMessage.Tool>()
        assertEquals(envelope.toolCalls.map { it.id }.toSet(), results.map { it.result.toolCallId }.toSet())
        assertTrue(results.last().result.isError)
    }

    @Test
    fun `run input images reach the provider without entering the session transcript`() = runBlocking {
        val image = AgentImageContent(base64Data = "AQID", mimeType = "image/png")
        val requests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requests += request
                    return ModelResponse("done")
                }
            })
            .build()
        val session = AgentSession("preparation-input-image")

        val events = runtime.run(
            session,
            AgentRunInput(content = "inspect this", images = listOf(image))
        ).toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertEquals(
            listOf(image),
            requests.single().messages.filterIsInstance<AgentMessage.User>().single().images
        )
        assertFalse(session.messages.any { message ->
            message is AgentMessage.User && message.images.isNotEmpty()
        })
    }

    @Test
    fun `input attachment survives policy rewrite and is re-armed for the incomplete-response retry`() = runBlocking {
        val image = AgentImageContent(base64Data = "AQID", mimeType = "image/png")
        val longInput = "long user request " + "x".repeat(2_000)
        val requests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requests += request
                    return if (requests.size == 1) {
                        ModelResponse(content = "partial", stopReason = "max_tokens")
                    } else {
                        ModelResponse(content = "done")
                    }
                }
            })
            .transcriptPreparationPolicy { snapshot ->
                TranscriptPreparation(
                    snapshot.map { message ->
                        if (message is AgentMessage.User && message.content == longInput) {
                            AgentMessage.User("short prepared request")
                        } else {
                            message
                        }
                    }
                )
            }
            .build()

        val events = runtime.run(
            AgentSession("input-attachment-policy-rewrite"),
            AgentRunInput(content = longInput, images = listOf(image))
        ).toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertEquals(2, requests.size)
        assertEquals(
            listOf(image),
            requests[0].messages.filterIsInstance<AgentMessage.User>().flatMap { it.images }
        )
        assertTrue(
            requests[0].messages.filterIsInstance<AgentMessage.User>()
                .any { it.content.endsWith("short prepared request") }
        )
        // Design change (2026-09 review round 4): the incomplete-response
        // retry prompt demands reproducing the complete answer from the
        // original inputs, which is contradictory when multimodal input is no
        // longer visible. The retry request therefore re-attaches the original
        // input images; total re-sends are bounded by
        // MAX_INCOMPLETE_RESPONSE_RETRIES and normal tool-iteration requests
        // keep the one-shot semantics.
        assertEquals(
            "incomplete-response retry must re-attach the input images",
            listOf(image),
            requests[1].messages.filterIsInstance<AgentMessage.User>().flatMap { it.images }
        )
    }

    @Test
    fun `transcript policy runs before every model request and provider sees prepared snapshot`() = runBlocking {
        val call = ToolCall("prepare-tool", "echo", JsonObject(emptyMap()))
        val requests = mutableListOf<ModelRequest>()
        val policySnapshots = mutableListOf<List<AgentMessage>>()
        val snapshotMutationFailures = mutableListOf<Throwable?>()
        val policy = TranscriptPreparationPolicy { snapshot ->
            policySnapshots += snapshot
            snapshotMutationFailures += runCatching {
                java.util.Collection::class.java.getMethod("clear").invoke(snapshot)
            }.exceptionOrNull()
            if (policySnapshots.size == 1) {
                TranscriptPreparation(snapshot + AgentMessage.User("prepared context"))
            } else {
                TranscriptPreparation(snapshot)
            }
        }
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += request
                return if (requests.size == 1) {
                    ModelResponse("calling tool", toolCalls = listOf(call))
                } else {
                    ModelResponse("done")
                }
            }
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .toolRegistry(ToolRegistry().register(EchoTool()))
            .transcriptPreparationPolicy(policy)
            .build()

        val events = runtime.run(AgentSession("preparation-requests"), "hello").toList()

        assertEquals(2, requests.size)
        assertTrue(policySnapshots.size >= 2)
        assertTrue(snapshotMutationFailures.all { failure ->
            ((failure as? InvocationTargetException)?.cause ?: failure) is UnsupportedOperationException
        })
        assertEquals(listOf(AgentMessage.User("hello")), policySnapshots.first())
        assertTrue(
            requests.first().messages
                .filterIsInstance<AgentMessage.User>()
                .any { it.content.endsWith("\nprepared context") }
        )
        assertEquals(AgentEvent.Completed("done"), events.last())
    }

    @Test
    fun `policy exception fails the run without damaging transcript and later run can continue`() = runBlocking {
        var failPreparation = true
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse =
                ModelResponse("recovered")
        }
        val policy = TranscriptPreparationPolicy { snapshot ->
            if (failPreparation) error("policy unavailable")
            TranscriptPreparation(snapshot)
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .transcriptPreparationPolicy(policy)
            .build()
        val session = AgentSession("preparation-exception")

        val failedEvents = runtime.run(session, "first").toList()

        assertEquals(
            AgentEvent.Failed("Transcript preparation failed: policy unavailable"),
            failedEvents.last()
        )
        assertEquals(listOf(AgentMessage.User("first")), session.messages)

        failPreparation = false
        val recoveredEvents = runtime.run(session, "second").toList()

        assertEquals(AgentEvent.Completed("recovered"), recoveredEvents.last())
        assertEquals(
            listOf(
                AgentMessage.User("first"),
                AgentMessage.User("second"),
                AgentMessage.Assistant("recovered")
            ),
            session.messages
        )
    }

    @Test
    fun `invalid policy output fails without replacing the original transcript`() = runBlocking {
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse =
                    ModelResponse("must not be called")
            })
            .transcriptPreparationPolicy {
                TranscriptPreparation(listOf(AgentMessage.Assistant("orphan")))
            }
            .build()
        val session = AgentSession("preparation-invalid")

        val events = runtime.run(session, "hello").toList()

        assertTrue(events.last() is AgentEvent.Failed)
        assertTrue((events.last() as AgentEvent.Failed).message.contains("Transcript preparation failed"))
        assertEquals(listOf(AgentMessage.User("hello")), session.messages)
    }

    @Test
    fun `orphan tool result policy output fails without changing the policy snapshot`() = runBlocking {
        assertInvalidPolicyOutput { snapshot ->
            snapshot + AgentMessage.Tool(
                ToolResult("orphan", "tool", "must be rejected")
            )
        }
    }

    @Test
    fun `unknown tool result policy output fails without changing the policy snapshot`() = runBlocking {
        val expectedCall = ToolCall("expected", "tool", JsonObject(emptyMap()))
        assertInvalidPolicyOutput { snapshot ->
            snapshot +
                AgentMessage.Assistant("working", toolCalls = listOf(expectedCall)) +
                AgentMessage.Tool(ToolResult("unknown", "tool", "must be rejected"))
        }
    }

    @Test
    fun `duplicate tool result policy output fails without changing the policy snapshot`() = runBlocking {
        val call = ToolCall("one-result", "tool", JsonObject(emptyMap()))
        assertInvalidPolicyOutput { snapshot ->
            snapshot +
                AgentMessage.Assistant("working", toolCalls = listOf(call)) +
                AgentMessage.Tool(ToolResult(call.id, call.name, "first")) +
                AgentMessage.Tool(ToolResult(call.id, call.name, "duplicate"))
        }
    }

    @Test
    fun `policy cannot erase an existing conversation with an empty result`() = runBlocking {
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse =
                    ModelResponse("must not be called")
            })
            .transcriptPreparationPolicy { TranscriptPreparation(emptyList()) }
            .build()
        val session = AgentSession("preparation-empty-invalid")

        val events = runtime.run(session, "hello").toList()

        assertTrue(events.last() is AgentEvent.Failed)
        assertEquals(listOf(AgentMessage.User("hello")), session.messages)
    }

    @Test
    fun `unconfigured transcript policy remains a no-op`() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    requests += request
                    return ModelResponse("done")
                }
            })
            .build()
        val session = AgentSession(
            "preparation-default",
            listOf(AgentMessage.User("old"))
        )

        val events = runtime.run(session, "hello").toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertEquals(1, requests.size)
        assertEquals(
            listOf(
                AgentMessage.User("old"),
                AgentMessage.User("hello"),
                AgentMessage.Assistant("done")
            ),
            session.messages
        )
    }

    @Test
    fun `completion boundary prepares transcript after final assistant is appended`() = runBlocking {
        val policySnapshots = mutableListOf<List<AgentMessage>>()
        val policy = TranscriptPreparationPolicy { snapshot ->
            policySnapshots += snapshot
            if (snapshot.size <= 3) {
                TranscriptPreparation(snapshot)
            } else {
                TranscriptPreparation(listOf(snapshot.first(), snapshot.last()))
            }
        }
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse =
                    ModelResponse("done")
            })
            .transcriptPreparationPolicy(policy)
            .build()
        val session = AgentSession(
            "preparation-completion",
            listOf(
                AgentMessage.User("old request"),
                AgentMessage.Assistant("old answer")
            )
        )

        val events = runtime.run(session, "new request").toList()

        assertEquals(AgentEvent.Completed("done"), events.last())
        assertTrue(policySnapshots.last().last() == AgentMessage.Assistant("done"))
        assertEquals(
            listOf(
                AgentMessage.User("old request"),
                AgentMessage.Assistant("done")
            ),
            session.messages
        )
    }

    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "Echoes a fixed result."
        override val inputSchema = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult = ToolResult(call.id, name, "echoed")
    }

    private suspend fun assertInvalidPolicyOutput(
        invalidOutput: (List<AgentMessage>) -> List<AgentMessage>
    ) {
        var providerCalls = 0
        var policySnapshot: List<AgentMessage>? = null
        val runtime = AgentRuntime.Builder()
            .llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    providerCalls++
                    return ModelResponse("must not be called")
                }
            })
            .transcriptPreparationPolicy { snapshot ->
                policySnapshot = snapshot
                TranscriptPreparation(invalidOutput(snapshot))
            }
            .build()
        val session = AgentSession(
            "preparation-invalid-matrix",
            listOf(
                AgentMessage.User("existing"),
                AgentMessage.Assistant("existing answer")
            )
        )
        val expectedUnchanged = listOf(
            AgentMessage.User("existing"),
            AgentMessage.Assistant("existing answer"),
            AgentMessage.User("current")
        )

        val events = runtime.run(session, "current").toList()

        assertTrue(events.last() is AgentEvent.Failed)
        assertTrue((events.last() as AgentEvent.Failed).message.contains("Transcript preparation failed"))
        assertEquals(0, providerCalls)
        assertEquals(expectedUnchanged, policySnapshot)
        assertEquals(expectedUnchanged, session.messages)
    }
}
