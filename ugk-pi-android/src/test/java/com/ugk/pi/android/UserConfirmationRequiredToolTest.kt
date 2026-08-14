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
    fun rejectsConfirmationWhenItIsNotTheLastMessage() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate)
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

        assertTrue(result.isError)
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
