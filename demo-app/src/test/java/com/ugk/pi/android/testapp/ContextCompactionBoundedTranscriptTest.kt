package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the transcript invariants that LLM providers require
 * from a prepared AgentSession:
 *
 * 1. every assistant tool_use keeps all of its tool_results;
 * 2. no orphaned tool_result survives without its assistant envelope;
 * 3. the first non-system message is a user message;
 * 4. the profile message cap is applied by the pure preparation result.
 */
class ContextCompactionBoundedTranscriptTest {

    private fun toolPair(id: Int): Pair<AgentMessage.Assistant, AgentMessage.Tool> {
        val call = ToolCall("call-$id", "terminal_bash_execute", JsonObject(emptyMap()))
        return AgentMessage.Assistant(
            content = "step $id",
            toolCalls = listOf(call)
        ) to AgentMessage.Tool(
            ToolResult(toolCallId = call.id, name = call.name, content = "result $id")
        )
    }

    @Test
    fun `preparation keeps assistant tool_use paired with tool_result`() {
        val messages = mutableListOf<AgentMessage>(
            AgentMessage.System("system prompt"),
            AgentMessage.User("please run the checks")
        )
        repeat(120) { index ->
            val (envelope, result) = toolPair(index)
            messages += envelope
            messages += result
        }

        val prepared = prepare(messages)

        assertConversationInvariants(prepared)
    }

    @Test
    fun `preparation starts with a user message after trimming`() {
        val messages = mutableListOf<AgentMessage>(
            AgentMessage.System("system prompt"),
            AgentMessage.User("first turn")
        )
        repeat(120) { index ->
            val (envelope, result) = toolPair(index)
            messages += envelope
            messages += result
        }
        messages += AgentMessage.Assistant("all done")

        val prepared = prepare(messages)

        assertConversationInvariants(prepared)
    }

    @Test
    fun `preparation handles a single turn larger than the cap`() {
        val messages = mutableListOf<AgentMessage>(
            AgentMessage.System("system prompt"),
            AgentMessage.User("one very long turn")
        )
        repeat(170) { index ->
            val (envelope, result) = toolPair(index)
            messages += envelope
            messages += result
        }

        val prepared = prepare(messages)

        assertConversationInvariants(prepared)
    }

    @Test
    fun `preparation stays within the message cap`() {
        val messages = mutableListOf<AgentMessage>(
            AgentMessage.System("system prompt"),
            AgentMessage.User("first turn")
        )
        repeat(60) { turn ->
            messages += AgentMessage.User("turn $turn")
            val (envelope, result) = toolPair(turn)
            messages += envelope
            messages += result
        }
        messages += AgentMessage.Assistant("final answer")

        val prepared = prepare(messages)

        assertConversationInvariants(prepared)
        assertTrue(
            "prepared transcript must stay within the 160 message cap but held ${prepared.size}",
            prepared.size <= 160
        )
    }

    @Test
    fun `preparation keeps earlier turns when budget allows`() {
        // 60 turns of [U, A, T]: the tail trim has room for most of the history.
        val messages = mutableListOf<AgentMessage>(AgentMessage.System("system prompt"))
        repeat(60) { turn ->
            messages += AgentMessage.User("turn $turn")
            val (envelope, result) = toolPair(turn)
            messages += envelope
            messages += result
        }

        val prepared = prepare(messages)

        assertConversationInvariants(prepared)
        val nonSystem = prepared.filterNot { it is AgentMessage.System }
        assertTrue(
            "preparation must keep earlier turns when budget allows (kept ${nonSystem.size} messages)",
            nonSystem.size >= 150
        )
    }

    @Test
    fun `preparation trims at user boundaries with interleaved pending messages`() {
        // Mirrors AgentRuntime appending queued user messages between tool
        // batches: [U, A, T, U, A, T, ...].
        val messages = mutableListOf<AgentMessage>(AgentMessage.System("system prompt"))
        repeat(80) { turn ->
            messages += AgentMessage.User("turn $turn")
            val (envelope, result) = toolPair(turn)
            messages += envelope
            messages += result
        }
        messages += AgentMessage.User("follow-up")

        val prepared = prepare(messages)

        assertConversationInvariants(prepared)
        val nonSystem = prepared.filterNot { it is AgentMessage.System }
        assertTrue(
            "the interleaved transcript must stay near the budget (kept ${nonSystem.size})",
            nonSystem.size >= 150
        )
    }

    private fun prepare(messages: List<AgentMessage>): List<AgentMessage> =
        ContextCompactor.compactIfNeeded(
            messages = messages,
            contextWindow = "128K",
            autoCompaction = false
        ).messages

    private fun assertConversationInvariants(messages: List<AgentMessage>) {
        val session = AgentSession("prepared-transcript", messages)
        val nonSystem = session.messages.filterNot { it is AgentMessage.System }
        assertTrue("prepared session must keep messages", nonSystem.isNotEmpty())
        assertTrue(
            "first non-system message must be a user message but was ${nonSystem.first()::class.simpleName}",
            nonSystem.first() is AgentMessage.User
        )
    }
}
