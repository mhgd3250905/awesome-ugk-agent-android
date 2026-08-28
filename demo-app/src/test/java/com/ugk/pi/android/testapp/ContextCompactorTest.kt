package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private val emptyJson = JsonObject(emptyMap())

    @Test
    fun testParseContextWindowTokens() {
        assertEquals(2_097_152, ContextCompactor.parseContextWindowTokens("2M"))
        assertEquals(1_048_576, ContextCompactor.parseContextWindowTokens("1M"))
        assertEquals(204_800, ContextCompactor.parseContextWindowTokens("200K"))
        assertEquals(131_072, ContextCompactor.parseContextWindowTokens("128K"))
        assertEquals(65_536, ContextCompactor.parseContextWindowTokens("64K"))
        assertEquals(32_768, ContextCompactor.parseContextWindowTokens("32K"))
        assertEquals(131_072, ContextCompactor.parseContextWindowTokens(null))
    }

    @Test
    fun testFormatTokenCount() {
        assertEquals("0", ContextCompactor.formatTokenCount(0))
        assertEquals("450", ContextCompactor.formatTokenCount(450))
        assertEquals("1.5K", ContextCompactor.formatTokenCount(1500))
        assertEquals("128K", ContextCompactor.formatTokenCount(128000))
        assertEquals("1M", ContextCompactor.formatTokenCount(1000000))
        assertEquals("2.1M", ContextCompactor.formatTokenCount(2100000))
    }

    @Test
    fun testEstimateTokens() {
        val messages = listOf(
            AgentMessage.User("你好，请帮我分析这份日志"),
            AgentMessage.Assistant("好的，我将使用工具读取文件内容。"),
            AgentMessage.Tool(ToolResult(toolCallId = "call_1", name = "file_read", content = "line 1\nline 2\nline 3"))
        )
        val tokens = ContextCompactor.estimateTokens(messages)
        assertTrue(tokens > 10)
    }

    @Test
    fun testLevel1ToolResultPruning() {
        val longContent = "START_" + "X".repeat(2000) + "_END"
        val messages = mutableListOf(
            // Turn 1 (Old)
            AgentMessage.User("查询旧日志"),
            AgentMessage.Assistant("正在运行", toolCalls = listOf(ToolCall("1", "bash", emptyJson))),
            AgentMessage.Tool(ToolResult("1", "bash", longContent)),
            // Turn 2 (Old)
            AgentMessage.User("分析旧日志"),
            AgentMessage.Assistant("分析完成"),
            // Turn 3 (Recent)
            AgentMessage.User("读取当前配置文件"),
            AgentMessage.Assistant("正在读取", toolCalls = listOf(ToolCall("2", "file_read", emptyJson))),
            AgentMessage.Tool(ToolResult("2", "file_read", "config_data_content")),
            // Turn 4 (Recent)
            AgentMessage.User("执行更新"),
            AgentMessage.Assistant("更新完毕")
        )

        val pruned = ContextCompactor.pruneOldToolResults(messages)
        assertEquals(messages.size, pruned.size)

        // Old tool in Turn 1 should be folded
        val oldTool = pruned[2] as AgentMessage.Tool
        assertTrue(oldTool.result.content.contains("历史输出已折叠"))
        assertTrue(oldTool.result.content.contains("START_"))
        assertTrue(oldTool.result.content.contains("_END"))
        assertTrue(oldTool.result.content.length < 1000)

        // Recent tool in Turn 3 should be intact
        val recentTool = pruned[7] as AgentMessage.Tool
        assertEquals("config_data_content", recentTool.result.content)
    }

    @Test
    fun testLevel2SummarizationCompaction() {
        val messages = mutableListOf<AgentMessage>()
        // 6 turns of conversation
        for (i in 1..6) {
            messages.add(AgentMessage.User("第 $i 轮用户问题: 请执行操作 $i"))
            messages.add(AgentMessage.Assistant("第 $i 轮助手回答: 操作 $i 已经成功完成。"))
        }

        val compacted = ContextCompactor.compactOlderTurnsIntoSummary(messages)
        // Earlier turns should be replaced with summary
        assertTrue(compacted.size < messages.size)
        val firstUserMsg = compacted.first { it is AgentMessage.User } as AgentMessage.User
        assertTrue(firstUserMsg.content.contains("系统上下文压缩摘要"))
        // Latest turns should be preserved
        val lastAssistant = compacted.last() as AgentMessage.Assistant
        assertEquals("第 6 轮助手回答: 操作 6 已经成功完成。", lastAssistant.content)
    }

    @Test
    fun testLevel3AtomicInvariants() {
        val messages = listOf(
            AgentMessage.User("合法首条用户消息"),
            AgentMessage.Assistant("调用工具", toolCalls = listOf(ToolCall("call_x", "tool_x", emptyJson))),
            AgentMessage.Tool(ToolResult("call_x", "tool_x", "tool_result_ok")),
            AgentMessage.User("后续对话")
        )

        val validated = ContextCompactor.ensureAtomicInvariants(messages)
        assertEquals(4, validated.size)
        assertTrue(validated.first() is AgentMessage.User)
    }

    @Test
    fun testCompactIfNeededTriggeredAndUntriggered() {
        val session = AgentSession(id = "test-session")
        session.messages.add(AgentMessage.User("简单问答"))
        session.messages.add(AgentMessage.Assistant("简短回答"))

        // Untriggered on small content
        val untriggered = ContextCompactor.compactIfNeeded(
            session = session,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )
        assertFalse(untriggered.triggered)

        // Create heavy session with multiple large turns to exceed 70% of 32K (~23K tokens / ~75K chars)
        val heavySession = AgentSession(id = "heavy-session")
        for (i in 1..10) {
            heavySession.messages.add(AgentMessage.User("第 $i 轮大型任务查询"))
            heavySession.messages.add(AgentMessage.Assistant("执行大型分析任务 $i", toolCalls = listOf(ToolCall("id_$i", "cmd", emptyJson))))
            heavySession.messages.add(AgentMessage.Tool(ToolResult("id_$i", "cmd", "DATA_CHUNK_" + "ABCD_".repeat(2500))))
        }

        val triggered = ContextCompactor.compactIfNeeded(
            session = heavySession,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )

        assertTrue(triggered.triggered)
        assertTrue(triggered.savedChars > 0)
        assertTrue(triggered.savedRatio > 0.3)
    }
}
