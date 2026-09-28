package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DemoScreenOverlayDecoratorTest {
    private val events = mutableListOf<String>()
    private fun tool(name: String = "screen_visual_gesture", execute: suspend () -> Unit = { events += "execute" }) = object : AgentTool {
        override val name = name
        override val description = "test"
        override val inputSchema = JsonObject(emptyMap())
        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            execute()
            return ToolResult(call.id, name, "ok")
        }
    }
    private fun evidenceDecorator() = AgentToolDecorator { delegate ->
        object : AgentTool by delegate {
            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                try {
                    events += "before"
                    return delegate.execute(call, context)
                } finally {
                    withContext(NonCancellable) { delay(1); events += "after" }
                }
            }
        }
    }
    private suspend fun AgentTool.run() = execute(ToolCall("call", name, JsonObject(emptyMap())), ToolExecutionContext(sessionId = "test"))

    @Test fun screenToolsEncloseEvidenceInsideOneGuard() = runBlocking {
        for (name in listOf("screen_visual_gesture", "screen_capture_visual")) {
            events.clear()
            screenOverlayDecorator(evidenceDecorator(), { events += "prepare" }, { events += "finish" })
                .decorate(tool(name)).run()
            assertEquals(listOf("prepare", "before", "execute", "after", "finish"), events)
        }
    }
    @Test fun launchesKeepVisibleOverlayAndStillRecordEvidence() = runBlocking {
        for (name in listOf("launch_android_app", "launch_android_app_intent")) {
            events.clear()
            screenOverlayDecorator(evidenceDecorator(), { events += "prepare" }, { events += "finish" })
                .decorate(tool(name)).run()
            assertEquals(listOf("before", "execute", "after"), events)
        }
    }
    @Test fun cancellationRestoresAfterEvidenceDespiteCancelledCaller() = runBlocking {
        val wrapped = screenOverlayDecorator(evidenceDecorator(), { events += "prepare" }, {
            delay(1)
            events += "finish"
        }).decorate(tool { events += "execute"; awaitCancellation() })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { wrapped.run() }
        job.cancelAndJoin()
        assertEquals(listOf("prepare", "before", "execute", "after", "finish"), events)
    }
    @Test fun partialPreparationFailureStillRestoresWithoutExecutingEvidence() = runBlocking {
        val wrapped = screenOverlayDecorator(evidenceDecorator(), {
            events += "prepare"
            throw IllegalStateException("detachment failed")
        }, { events += "finish" }).decorate(tool())
        assertFalse(runCatching { wrapped.run() }.isSuccess)
        assertEquals(listOf("prepare", "finish"), events)
    }
    @Test fun nonScreenToolsKeepDecoratorButSkipOverlayGuard() = runBlocking {
        screenOverlayDecorator(evidenceDecorator(), { events += "prepare" }, { events += "finish" })
            .decorate(tool("terminal_bash_execute")).run()
        assertEquals(listOf("before", "execute", "after"), events)
    }
    @Test fun innerRejectionCannotExecuteToolAndStillRestoresOverlay() = runBlocking {
        val rejecting = AgentToolDecorator { delegate -> object : AgentTool by delegate {
            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                events += "rejected"
                return ToolResult(call.id, name, "blocked", isError = true)
            }
        } }
        screenOverlayDecorator(rejecting, { events += "prepare" }, { events += "finish" })
            .decorate(tool()).run()
        assertEquals(listOf("prepare", "rejected", "finish"), events)
    }
}
