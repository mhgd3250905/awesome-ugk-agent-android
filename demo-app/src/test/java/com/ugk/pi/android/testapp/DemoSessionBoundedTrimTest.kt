package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the conversation invariants that LLM providers
 * require from a resumed AgentSession:
 *
 * 1. every assistant tool_use must be followed by its tool_result(s);
 * 2. no orphaned tool_result may survive without its assistant envelope;
 * 3. the first non-system message must be a user message.
 *
 * DemoActivityState.boundSession() trims long in-memory sessions after a
 * run, so its cut point must respect these invariants. Breaking them makes
 * every later model request on that session fail with a provider 400.
 */
class DemoSessionBoundedTrimTest {

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
    fun `bound session keeps assistant tool_use paired with tool_result`() {
        val session = AgentSession("session-trim")
        session.messages += AgentMessage.System("system prompt")
        session.messages += AgentMessage.User("please run the checks")
        repeat(120) { index ->
            val (envelope, result) = toolPair(index)
            session.messages += envelope
            session.messages += result
        }

        DemoActivityState.boundSession(session)

        assertConversationInvariants(session.messages)
    }

    @Test
    fun `bound session starts with a user message after trimming`() {
        val session = AgentSession("session-first-message")
        session.messages += AgentMessage.System("system prompt")
        session.messages += AgentMessage.User("first turn")
        repeat(120) { index ->
            val (envelope, result) = toolPair(index)
            session.messages += envelope
            session.messages += result
        }
        session.messages += AgentMessage.Assistant("all done")

        DemoActivityState.boundSession(session)

        assertConversationInvariants(session.messages)
    }

    @Test
    fun `bound session handles a single turn larger than the cap`() {
        val session = AgentSession("session-mega-turn")
        session.messages += AgentMessage.System("system prompt")
        session.messages += AgentMessage.User("one very long turn")
        repeat(170) { index ->
            val (envelope, result) = toolPair(index)
            session.messages += envelope
            session.messages += result
        }

        DemoActivityState.boundSession(session)

        assertConversationInvariants(session.messages)
    }

    @Test
    fun `bound session stays within the message cap`() {
        val session = AgentSession("session-cap")
        session.messages += AgentMessage.System("system prompt")
        session.messages += AgentMessage.User("first turn")
        repeat(60) { turn ->
            session.messages += AgentMessage.User("turn $turn")
            val (envelope, result) = toolPair(turn)
            session.messages += envelope
            session.messages += result
        }
        session.messages += AgentMessage.Assistant("final answer")

        DemoActivityState.boundSession(session)

        assertConversationInvariants(session.messages)
        assertTrue(
            "trimmed session must stay within the 160 message cap " +
                "but held ${session.messages.size}",
            session.messages.size <= 160
        )
    }

    @Test
    fun `bound session keeps earlier turns when budget allows`() {
        // 60 turns of [U, A, T]: the naive "keep only the last turn" trim
        // would retain 3 messages; the budget has room for ~159.
        val session = AgentSession("session-retention")
        session.messages += AgentMessage.System("system prompt")
        repeat(60) { turn ->
            session.messages += AgentMessage.User("turn $turn")
            val (envelope, result) = toolPair(turn)
            session.messages += envelope
            session.messages += result
        }

        DemoActivityState.boundSession(session)

        assertConversationInvariants(session.messages)
        val nonSystem = session.messages.filterNot { it is AgentMessage.System }
        assertTrue(
            "trim must keep earlier turns when the budget allows (kept ${nonSystem.size} messages)",
            nonSystem.size >= 150
        )
    }

    @Test
    fun `bound session trims on user boundaries with interleaved pending messages`() {
        // Mirrors AgentRuntime appending queued user messages between tool
        // batches: [U, A, T, U, A, T, ...].
        val session = AgentSession("session-interleaved")
        session.messages += AgentMessage.System("system prompt")
        repeat(80) { turn ->
            session.messages += AgentMessage.User("turn $turn")
            val (envelope, result) = toolPair(turn)
            session.messages += envelope
            session.messages += result
        }
        session.messages += AgentMessage.User("follow-up")

        DemoActivityState.boundSession(session)

        assertConversationInvariants(session.messages)
        val nonSystem = session.messages.filterNot { it is AgentMessage.System }
        assertTrue(
            "the interleaved transcript must stay near the budget (kept ${nonSystem.size})",
            nonSystem.size >= 150
        )
    }

    private fun assertConversationInvariants(messages: List<AgentMessage>) {
        val nonSystem = messages.filterNot { it is AgentMessage.System }
        assertTrue("trimmed session must keep messages", nonSystem.isNotEmpty())

        val first = nonSystem.first()
        assertTrue(
            "first non-system message must be a user message but was ${first::class.simpleName}",
            first is AgentMessage.User
        )

        val answeredToolCallIds = mutableSetOf<String>()
        nonSystem.forEach { message ->
            when (message) {
                is AgentMessage.Tool -> {
                    val envelopeSeen = nonSystem.takeWhile { it !== message }
                        .filterIsInstance<AgentMessage.Assistant>()
                        .any { assistant -> assistant.toolCalls.any { it.id == message.result.toolCallId } }
                    assertTrue(
                        "tool_result ${message.result.toolCallId} has no preceding assistant tool_use",
                        envelopeSeen
                    )
                    answeredToolCallIds += message.result.toolCallId
                }

                is AgentMessage.Assistant -> Unit
                else -> Unit
            }
        }
        nonSystem.filterIsInstance<AgentMessage.Assistant>().forEach { assistant ->
            assistant.toolCalls.forEach { call ->
                assertTrue(
                    "assistant tool_use ${call.id} has no tool_result after trimming",
                    call.id in answeredToolCallIds
                )
            }
        }
    }
}
