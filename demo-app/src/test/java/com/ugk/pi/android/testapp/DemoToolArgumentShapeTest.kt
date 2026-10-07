package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolRegistry
import com.ugk.pi.android.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Tool argument the model sent as an object or array is a value the caller did
 * write, only in a shape this client cannot interpret.
 *
 * Both demo-side readers of that shape used the throwing kotlinx accessor
 * (`element.jsonPrimitive`), which raises IllegalArgumentException for an object or
 * array. One of them runs inside
 * `DemoRunState.reduce`, which `DemoAgentRunCoordinator.dispatch` calls outside any
 * `runCatching`, so the throw escaped the event fold and the run's own
 * `catch (error: Throwable)` ended the whole conversation turn as Failed - a label
 * formatting function aborting a run whose Tool never even executed.
 *
 * The contract these cases pin: an unusable argument never aborts a run, and the
 * summary says it could not be read instead of inventing a value.
 */
class DemoToolArgumentShapeTest {
    private val objectShapedAction = buildJsonObject {
        putJsonObject("action") { put("name", "click") }
    }
    private val arrayShapedNodeId = buildJsonObject {
        put("action", "click")
        put("snapshotId", "snapshot-1")
        putJsonArray("nodeId") { add(JsonPrimitive("0")) }
    }

    @Test
    fun theRunStateFoldSummarisesAnActionTheModelSentAsAnObject() {
        val state = DemoRunState.initial().reduce(
            AgentEvent.ToolStarted(
                ToolCall("call-1", "screen_perform_action", objectShapedAction)
            )
        )

        assertEquals(
            "an unreadable argument must still be a running Tool step, not a lost event",
            DemoRunStatus.TOOL_RUNNING,
            state.status
        )
        assertTrue(
            "the summary must say the argument could not be read: " + state.detailLabel,
            state.detailLabel.contains(UNREADABLE_MARKER)
        )
        assertFalse(
            "a value nobody wrote must not appear as the action: " + state.detailLabel,
            state.detailLabel.contains("操作: 操作")
        )
    }

    @Test
    fun theScreenToolLogSurvivesANodeIdTheModelSentAsAnArray() {
        val detail = DemoScreenAutomationPolicy.screenToolCallDetail(
            ToolCall("call-2", "screen_perform_action", arrayShapedNodeId)
        )

        assertTrue(
            "a log line must survive an array-shaped nodeId instead of throwing: $detail",
            detail.contains("[action=click")
        )
        assertTrue(
            "an argument that WAS sent but cannot be read must not be logged as absent: $detail",
            detail.contains("node=unparseable")
        )
    }

    @Test
    fun aWellFormedScreenArgumentStillSummarisesExactlyAsBefore() {
        val call = ToolCall(
            "call-3",
            "screen_perform_action",
            buildJsonObject {
                put("snapshotId", "snapshot-1")
                put("nodeId", "0.1.2")
                put("action", "click")
            }
        )
        val state = DemoRunState.initial().reduce(AgentEvent.ToolStarted(call))

        assertEquals(DemoRunStatus.TOOL_RUNNING, state.status)
        assertTrue(state.detailLabel, state.detailLabel.contains("操作: click (节点 0.1.2)"))
        assertEquals(
            " [action=click snapshot=present node=present]",
            DemoScreenAutomationPolicy.screenToolCallDetail(call)
        )
    }

    @Test
    fun anUnreadableScreenArgumentDoesNotAbortTheConversationRun() {
        val session = AgentSession("session-unreadable-argument")
        val coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        val events = mutableListOf<AgentEvent>()
        coordinator.attach(owner = Any(), onEvent = { events.add(it) }, onFinished = {})
        coordinator.start(
            runtime = runtimeWithOneMalformedScreenCall(),
            session = session,
            conversationId = "conversation-1",
            message = "点一下继续按钮"
        )

        val terminal = awaitTerminalEvent(events)
        assertFalse(
            "a display-side reader must never end the turn; the run failed with: " +
                (terminal as? AgentEvent.Failed)?.message,
            terminal is AgentEvent.Failed
        )
        assertTrue(
            "the Tool result of the refused call must still reach the conversation, got: $terminal",
            terminal is AgentEvent.Completed
        )
        assertEquals("最终回答", (terminal as AgentEvent.Completed).content)
    }

    private fun awaitTerminalEvent(events: MutableList<AgentEvent>): AgentEvent {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull { it is AgentEvent.Completed || it is AgentEvent.Failed }?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no terminal event within 10s; events so far: $events")
    }

    private fun runtimeWithOneMalformedScreenCall(): AgentRuntime = AgentRuntime.Builder()
        .llmProvider(object : LLMProvider {
            private var calls = 0
            override suspend fun generate(request: ModelRequest): ModelResponse {
                calls++
                return if (calls == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-1",
                                name = "screen_perform_action",
                                input = objectShapedAction
                            )
                        )
                    )
                } else {
                    ModelResponse(content = "最终回答")
                }
            }
        })
        .toolRegistry(
            ToolRegistry().register(
                object : AgentTool {
                    override val name = "screen_perform_action"
                    override val description = "stands in for the accessibility Tool"
                    override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }

                    override suspend fun execute(
                        call: ToolCall,
                        context: ToolExecutionContext
                    ): ToolResult = ToolResult(
                        toolCallId = call.id,
                        name = name,
                        content = """{"success":false,"code":"INVALID_INPUT"}""",
                        isError = true
                    )
                }
            )
        )
        .build()

    /**
     * Every branch that renders an argument is driven with a structured value in the
     * slot it reads, because the first version of this round's fix only proved the two
     * branches someone happened to test. The expected count is asserted independently:
     * if a branch or key is dropped from the table, the fold no longer claims coverage
     * it does not have.
     */
    @Test
    fun everyLabelBranchSurvivesAStructuredArgumentInItsOwnSlot() {
        val probes = linkedMapOf(
            "screen_find_ui_element" to listOf("text", "content_desc", "view_id", "type"),
            "clipboard_write_text" to listOf("text", "sensitive"),
            "screen_visual_gesture" to listOf("action", "targetDescription"),
            "launch_android_app" to listOf("package_name"),
            "screen_perform_action" to listOf("action", "text", "nodeId"),
            "screen_gesture" to listOf("action"),
            "screen_press_key" to listOf("key"),
            "bash" to listOf("command", "cmd"),
            "file_read" to listOf("path", "file"),
            "show_user_confirmation_dialog" to listOf("message", "prompt"),
            "some_future_tool" to listOf("anything")
        )
        assertEquals("every reading branch of the mapper must be in the table", 21, probes.values.sumOf { it.size })

        probes.forEach { (tool, keys) ->
            keys.forEach { key ->
                val structured = buildJsonObject { put("nested", 1) }
                val failure = runCatching {
                    DemoToolSemanticMapper.formatInputSummary(tool, JsonObject(mapOf(key to structured)))
                }.exceptionOrNull()
                assertTrue(
                    "$tool.$key: a display label must never throw, got: $failure",
                    failure == null
                )
            }
        }

        val logProbes = linkedMapOf(
            "screen_visual_gesture" to listOf("action", "observationId"),
            "screen_perform_action" to listOf("action", "snapshotId", "nodeId")
        )
        logProbes.forEach { (tool, keys) ->
            keys.forEach { key ->
                val structured = buildJsonObject { put("nested", 1) }
                val failure = runCatching {
                    DemoScreenAutomationPolicy.screenToolCallDetail(
                        ToolCall("probe", tool, JsonObject(mapOf(key to structured)))
                    )
                }.exceptionOrNull()
                assertTrue(
                    "$tool.$key: the floating-window log line must never throw, got: $failure",
                    failure == null
                )
            }
        }
    }

    private companion object {
        const val UNREADABLE_MARKER = "无法解析"
    }
}
