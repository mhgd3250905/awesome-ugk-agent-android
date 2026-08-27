package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRunStateTest {

    @Test
    fun directAnswerProducesSingleCleanStepWithoutDuplicates() {
        var state = DemoRunState.initial()
            .reduce(AgentEvent.Started("session-1"))
            .reduce(AgentEvent.ModelRequestStarted(iteration = 1, messageCount = 2, toolCount = 5))
            .reduce(AgentEvent.ModelResponded(content = "北京今天天气晴朗", toolCalls = emptyList()))
            .reduce(AgentEvent.Completed("北京今天天气晴朗"))

        assertEquals(1, state.steps.size)
        assertEquals("直接生成回答", state.steps[0].title)
        assertEquals("北京今天天气晴朗", state.steps[0].resultSummary)
    }

    @Test
    fun toolCallingProducesSummaryFollowedByActionSequence() {
        val toolCall = com.ugk.pi.android.ToolCall(
            id = "call-1",
            name = "screen_read_ui_tree",
            input = kotlinx.serialization.json.buildJsonObject {}
        )
        val toolResult = com.ugk.pi.android.ToolResult(
            toolCallId = "call-1",
            name = "screen_read_ui_tree",
            content = "nodeCount=28 root=[0,0][1080,2400]"
        )

        var state = DemoRunState.initial()
            .reduce(AgentEvent.Started("session-1"))
            .reduce(AgentEvent.ModelRequestStarted(iteration = 1, messageCount = 2, toolCount = 5))
            .reduce(AgentEvent.ModelResponded(
                content = "我将先读取屏幕 UI 结构查找目标按钮",
                toolCalls = listOf(toolCall)
            ))
            .reduce(AgentEvent.ToolStarted(toolCall))
            .reduce(AgentEvent.ToolFinished(toolResult))
            .reduce(AgentEvent.ModelRequestStarted(iteration = 2, messageCount = 4, toolCount = 5))
            .reduce(AgentEvent.ModelResponded(
                content = "已找到目标按钮并完成任务",
                toolCalls = emptyList()
            ))
            .reduce(AgentEvent.Completed("已找到目标按钮并完成任务"))

        // Sequence: Model Step 1 (Summary/Intent) -> Tool Step 1 (Action) -> Model Step 2 -> Outcome
        assertTrue(state.steps.size >= 3)
        assertEquals("第 1 轮 · 意图规划", state.steps[0].title)
        assertTrue(state.steps[0].detailLabel.contains("我将先读取屏幕 UI 结构"))
        
        assertEquals("[动作] 读取屏幕 UI 树", state.steps[1].title)
        assertTrue(state.steps[1].detailLabel.contains("28 个节点"))
        assertTrue(state.steps[1].resultSummary?.contains("nodeCount=28") == true)

        assertEquals("任务已完成", state.steps.last().title)
    }

    @Test
    fun clipboardTraceSummariesDoNotExposeClipboardText() {
        val rawText = "secret-clipboard-value"
        val writeCall = com.ugk.pi.android.ToolCall(
            id = "clipboard-write",
            name = "clipboard_write_text",
            input = kotlinx.serialization.json.buildJsonObject {
                put("text", rawText)
                put("sensitive", true)
            }
        )
        val writeResult = ToolResult(
            toolCallId = writeCall.id,
            name = writeCall.name,
            content = "{\"success\":true,\"textLength\":${rawText.length}}"
        )

        val inputSummary = DemoToolSemanticMapper.formatInputSummary(writeCall.name, writeCall.input)
        val resultSummary = DemoToolSemanticMapper.formatResultSummary(writeResult)

        assertTrue(inputSummary.contains("${rawText.length}"))
        org.junit.Assert.assertFalse(inputSummary.contains(rawText))
        org.junit.Assert.assertFalse(resultSummary.contains(rawText))
        assertEquals("剪贴板文本已写入", resultSummary)
    }
}
