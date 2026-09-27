package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducers for the round-7 review: the authorization half of the
 * confirmation gate ignored the host's `withoutUserDecision` declaration, so a
 * result the host explicitly says nobody decided still opened the protected
 * Tool. `docs/sdk-confirmation-ticket-contract.md` requires the opposite
 * ("票据照常返回但依旧不可执行").
 */
class UserConfirmationWithoutUserDecisionTest {
    @Test
    fun doesNotAuthorizeWhenTheHostDeclaresNoUserDecision() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val acceptedByHostDefault = AgentMessage.Tool(
            ToolResult(
                toolCallId = "dialog-1",
                name = "show_user_confirmation_dialog",
                content = buildJsonObject {
                    put("selectedButtonId", "confirm")
                    put("withoutUserDecision", true)
                    put("ticket", ticket(SESSION, tool.name, input).toJsonObject())
                }.toString()
            )
        )

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(acceptedByHostDefault))
        )

        assertTrue(result.isError)
        assertFalse(
            "a result the host declares nobody decided must not execute the protected Tool",
            delegate.executed
        )
    }

    @Test
    fun stillAuthorizesARealUserDecision() = runBlocking {
        // Guard the other direction: the fix must not turn every confirmation
        // into a refusal.
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val decided = AgentMessage.Tool(
            ToolResult(
                toolCallId = "dialog-1",
                name = "show_user_confirmation_dialog",
                content = buildJsonObject {
                    put("selectedButtonId", "confirm")
                    put("ticket", ticket(SESSION, tool.name, input).toJsonObject())
                }.toString()
            )
        )

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(decided))
        )

        assertFalse(result.isError)
        assertTrue(delegate.executed)
    }

    @Test
    fun unansweredDialogWordingsTellTheModelNotToReOpenTheDialog() = runBlocking {
        // Round 6 removed the "declined -> call the dialog again" loop. An
        // unanswered dialog is the same trap: the host that could not reach a
        // user cannot reach one by being asked again, so the answer must not
        // read like a missing confirmation.
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val ticket = ticket(SESSION, tool.name, input)

        listOf("confirm", "cancel").forEach { buttonId ->
            val unanswered = AgentMessage.Tool(
                ToolResult(
                    toolCallId = "dialog-1",
                    name = "show_user_confirmation_dialog",
                    content = buildJsonObject {
                        put("selectedButtonId", buttonId)
                        put("withoutUserDecision", true)
                        put("ticket", ticket.toJsonObject())
                    }.toString()
                )
            )

            val result = tool.execute(
                ToolCall("intent-1", tool.name, input),
                ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(unanswered))
            )

            assertTrue(result.isError)
            assertFalse(delegate.executed)
            assertFalse(
                "wording for '$buttonId' must not invite another dialog: ${result.content}",
                result.content.contains("then retry")
            )
            assertTrue(
                "wording for '$buttonId' must say the user was not reached: ${result.content}",
                result.content.contains("without a user decision") ||
                    result.content.contains("could not be reached")
            )
            assertFalse(
                "an unanswered dialog is not the user's refusal: ${result.content}",
                result.content.contains("The user declined")
            )
        }
    }

    private fun ticket(
        sessionId: String,
        toolName: String,
        input: JsonObject
    ): UserConfirmationTicket = UserConfirmationTicket(
        version = UserConfirmationTicket.CURRENT_VERSION,
        sessionId = sessionId,
        toolName = toolName,
        inputFingerprint = UserConfirmationInputFingerprint.sha256(input),
        nonce = NONCE,
        issuedAtEpochMillis = NOW,
        expiresAtEpochMillis = NOW + UserConfirmationTicket.DEFAULT_TTL_MILLIS
    )

    private class RecordingTool : AgentTool {
        var executed = false
        override val name: String = "launch_android_app_intent"
        override val description: String = "Launches a test intent."
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            executed = true
            return ToolResult(call.id, name, "executed")
        }
    }

    private companion object {
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
