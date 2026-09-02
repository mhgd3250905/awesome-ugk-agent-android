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

    /**
     * Capability ownership is process-level: while the first run owns it, a
     * second instance sees its screen tools blocked but its terminal tools
     * stay available; once the owning run ends, the second run can acquire.
     */
    @Test
    fun ownershipIsProcessWideBlocksScreenToolsOfOtherRunsAndIsAcquirableAfterRelease() = runBlocking {
        val first = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val second = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val screenDelegate = RecordingTool("screen_read_ui_tree")
        val terminalDelegate = RecordingTool("terminal_bash_execute")
        val guardedScreenOnSecond = AgentToolInterlock(screenDelegate, second.toolInterlockPolicy())
        val guardedTerminalOnSecond = AgentToolInterlock(terminalDelegate, second.toolInterlockPolicy())

        first.onRunStarted()
        first.onEvent(workflowStarted())
        assertTrue(first.isCapabilityOwned())

        val blockedScreen = guardedScreenOnSecond.execute(
            ToolCall("screen-2", "screen_read_ui_tree", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session")
        )
        assertTrue(blockedScreen.isError)
        assertEquals(
            "CAPABILITY_INTERLOCKED",
            blockedScreen.metadata["code"]?.toString()?.trim('"')
        )
        assertEquals(0, screenDelegate.calls)

        val allowedTerminal = guardedTerminalOnSecond.execute(
            ToolCall("terminal-2", "terminal_bash_execute", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session")
        )
        assertFalse(allowedTerminal.isError)
        assertEquals(1, terminalDelegate.calls)

        first.onRunFinished()
        assertFalse(first.isCapabilityOwned())

        second.onRunStarted()
        second.onEvent(
            AgentEvent.ToolStarted(
                ToolCall("screen-3", "screen_read_ui_tree", JsonObject(emptyMap()))
            )
        )
        assertTrue(second.isCapabilityOwned())

        // Release the process-level state so other tests start clean.
        second.onRunFinished()
        assertFalse(second.isCapabilityOwned())
    }

    /** A starting run must never steal ownership that another active run holds. */
    @Test
    fun runStartDoesNotClearOwnershipHeldByAnotherActiveRun() = runBlocking {
        val foreground = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val background = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)
        val foregroundTerminal = AgentToolInterlock(
            RecordingTool("terminal_bash_execute"),
            foreground.toolInterlockPolicy()
        )
        val backgroundTerminal = AgentToolInterlock(
            RecordingTool("terminal_bash_execute"),
            background.toolInterlockPolicy()
        )

        foreground.onRunStarted()
        foreground.onEvent(workflowStarted())
        assertTrue(foreground.isCapabilityOwned())

        // The concurrent background run starts without touching the live owner.
        background.onRunStarted()
        assertTrue(foreground.isCapabilityOwned())

        // Ownership still belongs to the foreground run: its terminal stays
        // blocked while the background run's terminal is untouched.
        val foregroundBlocked = foregroundTerminal.execute(
            ToolCall("terminal-foreground", "terminal_bash_execute", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session")
        )
        val backgroundAllowed = backgroundTerminal.execute(
            ToolCall("terminal-background", "terminal_bash_execute", JsonObject(emptyMap())),
            ToolExecutionContext(sessionId = "session")
        )
        assertTrue(foregroundBlocked.isError)
        assertFalse(backgroundAllowed.isError)

        // The background run cannot take ownership while it is held; only
        // after the foreground run's terminal boundary can it acquire.
        background.onEvent(workflowStarted())
        assertFalse(background.isCapabilityOwned())
        foreground.onRunFinished()
        background.onEvent(workflowStarted())
        assertTrue(background.isCapabilityOwned())

        background.onRunFinished()
        assertFalse(background.isCapabilityOwned())
    }

    /**
     * The foreground instance is reused across sequential runs; a new run on
     * the same instance retires its previous run's unreleased ownership.
     */
    @Test
    fun runStartOnSameInstanceRetiresUnreleasedPreviousRunOwnership() {
        val interlock = DemoCapabilityInterlock(DemoScreenAutomationPolicy::isScreenWorkflowTool)

        interlock.onRunStarted()
        interlock.onEvent(workflowStarted())
        assertTrue(interlock.isCapabilityOwned())

        interlock.onRunStarted()
        assertFalse(interlock.isCapabilityOwned())

        interlock.onEvent(workflowStarted())
        assertTrue(interlock.isCapabilityOwned())

        interlock.onRunFinished()
        assertFalse(interlock.isCapabilityOwned())
    }

    private fun workflowStarted(): AgentEvent.ToolStarted = AgentEvent.ToolStarted(
        ToolCall("screen-1", "screen_read_ui_tree", JsonObject(emptyMap()))
    )

    private class RecordingTool(
        override val name: String = "terminal_bash_execute"
    ) : AgentTool {
        var calls = 0
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
