package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
    fun executesDelegateWhenPreviousToolResultConfirmed() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate)

        val result = tool.execute(
            ToolCall("intent-1", tool.name, JsonObject(emptyMap())),
            ToolExecutionContext(
                sessionId = "s1",
                priorMessages = listOf(
                    AgentMessage.Tool(
                        ToolResult(
                            toolCallId = "dialog-1",
                            name = "show_user_confirmation_dialog",
                            content = """{"selectedButtonId":"confirm"}"""
                        )
                    )
                )
            )
        )

        assertFalse(result.isError)
        assertEquals("executed", result.content)
        assertTrue(delegate.executed)
    }

    @Test
    fun executesDelegateWhenConfirmationIsFollowedByCurrentAssistantToolCall() = runBlocking {
        val delegate = RecordingTool()
        val tool = UserConfirmationRequiredTool(delegate)
        val intentCall = ToolCall("intent-1", tool.name, JsonObject(emptyMap()))

        val result = tool.execute(
            intentCall,
            ToolExecutionContext(
                sessionId = "s1",
                priorMessages = listOf(
                    AgentMessage.Tool(
                        ToolResult(
                            toolCallId = "dialog-1",
                            name = "show_user_confirmation_dialog",
                            content = """{"selectedButtonId":"confirm"}"""
                        )
                    ),
                    AgentMessage.Assistant(
                        content = "Launching now.",
                        toolCalls = listOf(intentCall)
                    )
                )
            )
        )

        assertFalse(result.isError)
        assertEquals("executed", result.content)
        assertTrue(delegate.executed)
    }

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
}
