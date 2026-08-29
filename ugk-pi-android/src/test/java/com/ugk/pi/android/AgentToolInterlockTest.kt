package com.ugk.pi.android

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolInterlockTest {
    @Test
    fun blockedDecoratorShortCircuitsAnOuterConfirmationWrapper() = runBlocking {
        var delegateCalls = 0
        val delegate = object : AgentTool {
            override val name: String = "protected_tool"
            override val description: String = "delegate"
            override val inputSchema: JsonObject = JsonObject(emptyMap())

            override suspend fun execute(
                call: ToolCall,
                context: ToolExecutionContext
            ): ToolResult {
                delegateCalls++
                return ToolResult(call.id, name, "delegate")
            }
        }
        val confirmationWrapped = UserConfirmationRequiredTool(delegate)
        val interlocked = AgentToolInterlock(
            delegate = confirmationWrapped,
            policy = AgentToolInterlockPolicy { _, _, _ ->
                AgentToolInterlockDecision(
                    blockingCapability = "screen-automation",
                    message = "another capability owns this run"
                )
            }
        )

        val result = interlocked.execute(
            ToolCall(
                id = "call-1",
                name = interlocked.name,
                input = buildJsonObject { put("value", "x") }
            ),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(result.isError)
        assertEquals("CAPABILITY_INTERLOCKED", result.metadata["code"]?.toString()?.trim('"'))
        assertEquals(
            "screen-automation",
            result.metadata["blockingCapability"]?.toString()?.trim('"')
        )
        assertFalse(result.content.contains("User confirmation required"))
        assertEquals(0, delegateCalls)
    }

    @Test
    fun unblockedDecoratorPreservesNormalAndFullAuthorizationConfirmationModes() = runBlocking {
        val normalDelegate = RecordingTool()
        val normal = AgentToolInterlock(
            delegate = UserConfirmationRequiredTool(normalDelegate),
            policy = AgentToolInterlockPolicy { _, _, _ -> null }
        )
        val normalResult = normal.execute(
            ToolCall("normal", normal.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session")
        )

        val fullDelegate = RecordingTool()
        val fullAuthorization = AgentToolInterlock(
            delegate = UserConfirmationRequiredTool(
                fullDelegate,
                shouldBypassConfirmation = { true }
            ),
            policy = AgentToolInterlockPolicy { _, _, _ -> null }
        )
        val fullResult = fullAuthorization.execute(
            ToolCall("full", fullAuthorization.name, JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(normalResult.isError)
        assertTrue(normalResult.content.contains("show_user_confirmation_dialog"))
        assertEquals(0, normalDelegate.calls)
        assertFalse(fullResult.isError)
        assertEquals(1, fullDelegate.calls)
    }

    private class RecordingTool : AgentTool {
        var calls = 0
        override val name: String = "protected_tool"
        override val description: String = "delegate"
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            calls++
            return ToolResult(call.id, name, "delegate")
        }
    }
}
