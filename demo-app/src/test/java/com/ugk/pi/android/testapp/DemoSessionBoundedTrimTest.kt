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
