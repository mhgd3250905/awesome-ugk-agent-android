package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolInterlock
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoCapabilityInterlockTest {
    @Test
    fun workflowToolFinishedDoesNotReleaseOwnershipBeforeRunCompletion() = runBlocking {
        val interlock = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val delegate = RecordingTool()
        val guarded = AgentToolInterlock(delegate, interlock.toolInterlockPolicy())
        val terminalCall = ToolCall(
            id = "terminal-1",
            name = guarded.name,
            input = buildJsonObject { put("script", "printf ok") }
        )

        interlock.onRunStarted()
        interlock.onEvent(
            AgentEvent.ToolStarted(
                ToolCall("screen-1", " SCREEN_READ_UI_TREE ", JsonObject(emptyMap()))
            )
        )
        interlock.onEvent(
            AgentEvent.ToolFinished(
                ToolResult("screen-1", "screen_read_ui_tree", "{}")
            )
        )

        val blocked = guarded.execute(terminalCall, ToolExecutionContext(sessionId = "session"))

        assertTrue(blocked.isError)
        assertEquals("CAPABILITY_INTERLOCKED", blocked.metadata["code"]?.toString()?.trim('"'))
        assertEquals(0, delegate.calls)

        interlock.onEvent(AgentEvent.Completed("done"))
        val allowed = guarded.execute(
            terminalCall.copy(id = "terminal-2"),
            ToolExecutionContext(sessionId = "session")
        )

        assertTrue(!allowed.isError)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun failedCancelledAndFinallyBoundariesAllReleaseOwnership() {
        val interlock = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)

        interlock.onRunStarted()
        interlock.onEvent(workflowStarted())
        interlock.onEvent(AgentEvent.Failed("failed"))
        assertFalse(interlock.isCapabilityOwned())

        interlock.onRunStarted()
        interlock.onEvent(workflowStarted())
        interlock.onRunCancelled()
        assertFalse(interlock.isCapabilityOwned())

        interlock.onRunStarted()
        interlock.onEvent(workflowStarted())
        interlock.onRunFinished()
        interlock.onRunFinished()
        assertFalse(interlock.isCapabilityOwned())
    }

    @Test
    fun workflowMatcherUsesTrimmedCaseInsensitiveExactNames() {
        assertTrue(DemoScreenAutomationPolicy.isScreenWorkflowTool(" SCREEN_READ_UI_TREE "))
        assertFalse(DemoScreenAutomationPolicy.isScreenWorkflowTool("screen_read_ui_tree_extra"))
        assertFalse(DemoScreenAutomationPolicy.isScreenWorkflowTool("screen_"))
    }

    private fun workflowStarted(): AgentEvent.ToolStarted = AgentEvent.ToolStarted(
        ToolCall("screen-1", "screen_read_ui_tree", JsonObject(emptyMap()))
    )

    private class RecordingTool : AgentTool {
        var calls = 0
        override val name: String = "terminal_bash_execute"
        override val description: String = "delegate"
        override val inputSchema: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            call: ToolCall,
            context: ToolExecutionContext
        ): ToolResult {
            calls++
            return ToolResult(call.id, name, "ok")
        }
    }
}
