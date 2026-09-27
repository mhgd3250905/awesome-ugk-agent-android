package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConfirmationRequiredToolTest {
    @Test
    fun blocksDelegateWhenPreviousToolResultIsNotUserConfirmation() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate)

        val result = tool.execute(
            ToolCall("intent-1", tool.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "s1")
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("show_user_confirmation_dialog"))
        assertFalse(delegate.executed)
    }

    @Test
    fun answersARefusalAsARefusalInsteadOfInvitingAnotherDialog() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        // The user pressed a button outside the accepted set: a decline whose
        // ticket is inert, which must not read as "no confirmation yet".
        val declined = AgentMessage.Tool(
            confirmationResult(SESSION, tool.name, input, selectedButtonId = "cancel")
        )

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(declined))
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
        assertTrue(
            "the model must learn the user declined, got: ${result.content}",
            result.content.contains("declined")
        )
        assertFalse(
            "a refusal must not tell the model to request the dialog again, got: ${result.content}",
            result.content.contains("then retry")
        )
    }

    @Test
    fun doesNotClaimRefusalForAnUnrecognizedButtonId() = runBlocking {
        // An id the SDK does not know (an affirmative the host words differently,
        // or a button the model invented) must not be reported as the user's "no":
        // that would block an action the user may well have authorized. The model
        // instead sees the accepted id list and can ask again correctly.
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(
                        confirmationResult(SESSION, tool.name, input, selectedButtonId = "approve")
                    )
                )
            )
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
        assertFalse(result.content.contains("declined"))
        assertTrue(result.content.contains("User confirmation required"))
    }

    @Test
    fun doesNotClaimRefusalWhenTheHostResolvedWithoutAUserDecision() = runBlocking {
        // A destroyed dialog window resolves with its cancellation button, but the
        // user never answered; reporting that as a refusal would silently abandon
        // the action the user was about to approve.
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val ticket = confirmationTicket(SESSION, tool.name, input)
        val unresolved = AgentMessage.Tool(
            ToolResult(
                toolCallId = "dialog-1",
                name = "show_user_confirmation_dialog",
                content = buildJsonObject {
                    put("selectedButtonId", "cancel")
                    put("withoutUserDecision", true)
                    put("ticket", ticket.toJsonObject())
                }.toString()
            )
        )

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(unresolved))
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
        assertFalse(
            "an unanswered dialog is not a user refusal, got: ${result.content}",
            result.content.contains("declined")
        )
    }

    @Test
    fun keepsAskingWordingWhenNoConfirmationHasBeenShownAtAll() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(
                        ToolResult(
                            toolCallId = "other-1",
                            name = "screen_read",
                            content = "ok"
                        )
                    )
                )
            )
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
        assertTrue(result.content.contains("User confirmation required"))
        assertFalse(result.content.contains("declined"))
    }

    @Test
    fun executesDelegateWhenConfirmationBypassIsEnabled() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(
            delegate,
            shouldBypassConfirmation = { true }
        )

        val result = tool.execute(
            ToolCall("intent-1", tool.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "s1")
        )

        assertFalse(result.isError)
        assertEquals("executed", result.content)
        assertTrue(delegate.executed)
        assertTrue(tool.description.contains("full authorization"))
        assertFalse(tool.description.contains("Requires a prior show_user_confirmation_dialog"))
    }

    @Test
    fun executesDelegateWhenPreviousToolResultConfirmed() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(SESSION, tool.name, input))
                )
            )
        )

        assertFalse(result.isError)
        assertEquals("executed", result.content)
        assertTrue(delegate.executed)
    }

    @Test
    fun acceptsConfirmationBeforeTheRuntimeAssistantToolCallEnvelope() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val intentCall = ToolCall("intent-1", tool.name, buildJsonObject { put("target", "open_url") })

        val result = tool.execute(
            intentCall,
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(SESSION, tool.name, intentCall.input)),
                    AgentMessage.Assistant(
                        content = "Launching now.",
                        toolCalls = listOf(intentCall)
                    )
                )
            )
        )

        assertFalse(result.isError)
        assertTrue(delegate.executed)
    }

    @Test
    fun rejectsConfirmationWhenAssistantEnvelopeDoesNotContainCurrentCall() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate)
        val intentCall = ToolCall("intent-1", tool.name, buildJsonObject { put("target", "open_url") })
        val differentCall = ToolCall("different-1", tool.name, buildJsonObject { put("target", "camera_capture") })

        val result = tool.execute(
            intentCall,
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(SESSION, tool.name, intentCall.input)),
                    AgentMessage.Assistant(
                        content = "Launching another action.",
                        toolCalls = listOf(differentCall)
                    )
                )
            )
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
    }

    @Test
    fun rejectsConfirmationAfterUserSystemOrOtherToolMessage() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val call = ToolCall("intent-1", tool.name, buildJsonObject { put("target", "open_url") })
        val confirmation = AgentMessage.Tool(confirmationResult(SESSION, tool.name, call.input))
        val invalidHistories = listOf(
            listOf<AgentMessage>(confirmation, AgentMessage.User("new user request")),
            listOf<AgentMessage>(confirmation, AgentMessage.System("new system boundary")),
            listOf<AgentMessage>(
                confirmation,
                AgentMessage.Tool(ToolResult("other-1", "other_tool", "completed"))
            )
        )

        invalidHistories.forEach { priorMessages ->
            val result = tool.execute(
                call,
                ToolExecutionContext(sessionId = SESSION, priorMessages = priorMessages)
            )
            assertTrue(result.isError)
        }
        assertFalse(delegate.executed)
    }

    @Test
    fun rejectsChangedInputEvenWhenConfirmationButtonIsAccepted() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val approvedInput = buildJsonObject { put("target", "open_url") }
        val changedInput = buildJsonObject { put("target", "camera_capture") }

        val result = tool.execute(
            ToolCall("intent-1", tool.name, changedInput),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(SESSION, tool.name, approvedInput))
                )
            )
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
    }

    @Test
    fun rejectsToolAndSessionMismatch() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })

        val wrongTool = tool.execute(
            ToolCall("intent-1", "different_tool", input),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(SESSION, tool.name, input))
                )
            )
        )
        val wrongSession = tool.execute(
            ToolCall("intent-2", tool.name, input),
            ToolExecutionContext(
                sessionId = "other-session",
                priorMessages = listOf(
                    AgentMessage.Tool(confirmationResult(SESSION, tool.name, input))
                )
            )
        )

        assertTrue(wrongTool.isError)
        assertTrue(wrongSession.isError)
        assertFalse(delegate.executed)
    }

    @Test
    fun rejectsExpiredTicket() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val expired = confirmationResult(
            sessionId = SESSION,
            toolName = tool.name,
            input = input,
            issuedAt = 0L,
            expiresAt = NOW
        )

        val result = tool.execute(
            ToolCall("intent-1", tool.name, input),
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(AgentMessage.Tool(expired))
            )
        )

        assertTrue(result.isError)
        assertFalse(delegate.executed)
    }

    @Test
    fun rejectsDeniedButtonAndMalformedOrMissingTicket() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val call = ToolCall("intent-1", tool.name, input)
        val denied = buildJsonObject {
            put("selectedButtonId", "cancel")
            put("ticket", confirmationTicket(SESSION, tool.name, input).toJsonObject())
        }.toString()
        val malformed = """{"selectedButtonId":"confirm","ticket":{"version":1}}"""
        val missing = """{"selectedButtonId":"confirm"}"""
        val nonObjectTicket = """{"selectedButtonId":"confirm","ticket":"not-an-object"}"""
        val invalidJson = """{"selectedButtonId":"confirm","ticket":"""

        listOf(denied, malformed, missing, nonObjectTicket, invalidJson).forEach { content ->
            val result = tool.execute(
                call,
                ToolExecutionContext(
                    sessionId = SESSION,
                    priorMessages = listOf(
                        AgentMessage.Tool(ToolResult("dialog", "show_user_confirmation_dialog", content))
                    )
                )
            )
            assertTrue(result.isError)
        }
        assertFalse(delegate.executed)
    }

    @Test
    fun confirmationResultCannotBeReusedAfterDelegateResultIsAppended() = runBlocking {
        val delegate = RecordingTool()
        val input = buildJsonObject { put("target", "open_url") }
        val tool = UserConfirmationRequiredTool(delegate, nowEpochMillis = { NOW })
        val call = ToolCall("intent-1", tool.name, input)
        val confirmation = AgentMessage.Tool(confirmationResult(SESSION, tool.name, input))

        val first = tool.execute(
            call,
            ToolExecutionContext(sessionId = SESSION, priorMessages = listOf(confirmation))
        )
        val second = tool.execute(
            call,
            ToolExecutionContext(
                sessionId = SESSION,
                priorMessages = listOf(
                    confirmation,
                    AgentMessage.Tool(first)
                )
            )
        )

        assertFalse(first.isError)
        assertTrue(second.isError)
        assertEquals(1, delegate.executionCount)
    }

    private fun confirmationResult(
        sessionId: String,
        toolName: String,
        input: JsonObject,
        selectedButtonId: String = "confirm",
        issuedAt: Long = NOW,
        expiresAt: Long = NOW + UserConfirmationTicket.DEFAULT_TTL_MILLIS
    ): ToolResult {
        val ticket = confirmationTicket(sessionId, toolName, input, issuedAt, expiresAt)
        return ToolResult(
            toolCallId = "dialog-1",
            name = "show_user_confirmation_dialog",
            content = buildJsonObject {
                put("selectedButtonId", selectedButtonId)
                put("ticket", ticket.toJsonObject())
            }.toString()
        )
    }

    private fun confirmationTicket(
        sessionId: String,
        toolName: String,
        input: JsonObject,
        issuedAt: Long = NOW,
        expiresAt: Long = NOW + UserConfirmationTicket.DEFAULT_TTL_MILLIS
    ): UserConfirmationTicket {
        return UserConfirmationTicket(
            version = UserConfirmationTicket.CURRENT_VERSION,
            sessionId = sessionId,
            toolName = toolName,
            inputFingerprint = UserConfirmationInputFingerprint.sha256(input),
            nonce = NONCE,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = expiresAt
        )
    }

    private class RecordingTool : AgentTool {
        var executed = false
        var executionCount = 0
        override val name: String = "launch_android_app_intent"
        override val description: String = "Launches a test intent."
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            executed = true
            executionCount++
            return ToolResult(call.id, name, "executed")
        }
    }

    private companion object {
        const val SESSION = "s1"
        const val NOW = 1_000L
        const val NONCE = "AAAAAAAAAAAAAAAAAAAAAA"
    }
}
