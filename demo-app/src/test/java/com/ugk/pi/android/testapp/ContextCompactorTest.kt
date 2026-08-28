package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import java.lang.reflect.InvocationTargetException
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
    fun testCompactionReturnsAResultWithoutMutatingInput() {
        val messages = mutableListOf<AgentMessage>(
            AgentMessage.User("原始问题"),
            AgentMessage.Assistant("原始回答")
        )
        val before = messages.toList()

        val result = ContextCompactor.compactIfNeeded(
            messages = messages,
            contextWindow = "32K",
            autoCompaction = false
        )

        assertEquals(before, messages)
        assertEquals(before, result.messages)
    }

    @Test
    fun testCompactionResultOwnsOuterAndAssistantToolCallLists() {
        val toolCall = ToolCall("owned-call", "tool", emptyJson)
        val sourceToolCalls = mutableListOf(toolCall)
        val result = ContextCompactor.compactIfNeeded(
            messages = listOf(
                AgentMessage.User("question"),
                AgentMessage.Assistant("calling", toolCalls = sourceToolCalls),
                AgentMessage.Tool(ToolResult("owned-call", "tool", "done"))
            ),
            contextWindow = "32K",
            autoCompaction = false
        )

        sourceToolCalls += ToolCall("outside", "tool", emptyJson)
        val outerFailure = runCatching {
            java.util.List::class.java
                .getMethod("add", Any::class.java)
                .invoke(result.messages, AgentMessage.User("outside"))
        }.exceptionOrNull()
        val toolCalls = result.messages
            .filterIsInstance<AgentMessage.Assistant>()
            .single()
            .toolCalls
        val innerFailure = runCatching {
            java.util.List::class.java
                .getMethod("add", Any::class.java)
                .invoke(toolCalls, ToolCall("outside-2", "tool", emptyJson))
        }.exceptionOrNull()

        assertTrue(
            ((outerFailure as? InvocationTargetException)?.cause ?: outerFailure)
                is UnsupportedOperationException
        )
        assertTrue(
            ((innerFailure as? InvocationTargetException)?.cause ?: innerFailure)
                is UnsupportedOperationException
        )
        assertEquals(1, toolCalls.size)
        assertEquals("owned-call", toolCalls.single().id)
    }

    @Test
    fun testCompactIfNeededTriggeredAndUntriggered() {
        val sessionMessages = listOf(
            AgentMessage.User("简单问答"),
            AgentMessage.Assistant("简短回答")
        )

        // Untriggered on small content
        val untriggered = ContextCompactor.compactIfNeeded(
            messages = sessionMessages,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )
        assertFalse(untriggered.summary.triggered)

        // Create heavy session with multiple large turns to exceed 70% of 32K (~23K tokens / ~75K chars)
        val heavyMessages = mutableListOf<AgentMessage>()
        for (i in 1..10) {
            heavyMessages.add(AgentMessage.User("第 $i 轮大型任务查询"))
            heavyMessages.add(AgentMessage.Assistant("执行大型分析任务 $i", toolCalls = listOf(ToolCall("id_$i", "cmd", emptyJson))))
            heavyMessages.add(AgentMessage.Tool(ToolResult("id_$i", "cmd", "DATA_CHUNK_" + "ABCD_".repeat(2500))))
        }

        val triggered = ContextCompactor.compactIfNeeded(
            messages = heavyMessages,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )

        assertTrue(triggered.summary.triggered)
        assertTrue(triggered.summary.savedChars > 0)
        assertTrue(triggered.summary.savedRatio > 0.3)
    }

    @Test
    fun `auto compaction level one keeps the first user and complete tool groups`() {
        val longToolOutput = "START_" + "X".repeat(120_000) + "_END"
        val messages = listOf(
            AgentMessage.System("system prompt"),
            AgentMessage.User("old request"),
            AgentMessage.Assistant(
                "old tool call",
                toolCalls = listOf(ToolCall("old-call", "tool", emptyJson))
            ),
            AgentMessage.Tool(ToolResult("old-call", "tool", longToolOutput)),
            AgentMessage.User("recent request"),
            AgentMessage.Assistant("recent answer"),
            AgentMessage.User("latest request"),
            AgentMessage.Assistant("latest answer")
        )

        val result = ContextCompactor.compactIfNeeded(
            messages = messages,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )

        assertTrue(result.summary.triggered)
        assertEquals(1, result.summary.level)
        assertConversationInvariants(result.messages)
    }

    @Test
    fun `auto compaction level two keeps the first user and complete tool groups`() {
        val messages = buildList {
            repeat(8) { turn ->
                add(AgentMessage.User("request $turn " + "x".repeat(10_000)))
                add(AgentMessage.Assistant("answer $turn " + "y".repeat(10_000)))
            }
        }

        val result = ContextCompactor.compactIfNeeded(
            messages = messages,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )

        assertTrue(result.summary.triggered)
        assertEquals(2, result.summary.level)
        assertConversationInvariants(result.messages)
    }

    @Test
    fun `auto compaction level two preserves multi tool envelopes across queued transcript`() {
        val messages = buildList {
            add(AgentMessage.System("system prompt"))
            repeat(10) { turn ->
                val firstCall = ToolCall("level2-$turn-first", "tool", emptyJson)
                val secondCall = ToolCall("level2-$turn-second", "tool", emptyJson)
                add(AgentMessage.User("request $turn " + "x".repeat(10_000)))
                add(
                    AgentMessage.Assistant(
                        "working $turn " + "y".repeat(10_000),
                        toolCalls = listOf(firstCall, secondCall)
                    )
                )
                add(AgentMessage.Tool(ToolResult(firstCall.id, firstCall.name, "result-$turn-first")))
                add(AgentMessage.Tool(ToolResult(secondCall.id, secondCall.name, "result-$turn-second")))
                if (turn % 2 == 0) {
                    add(AgentMessage.User("queued follow-up $turn " + "q".repeat(2_000)))
                }
            }
        }

        val result = ContextCompactor.compactIfNeeded(
            messages = messages,
            contextWindow = "32K",
            thresholdRatio = 0.70,
            autoCompaction = true
        )

        assertTrue(result.summary.triggered)
        assertEquals(2, result.summary.level)
        assertConversationInvariants(result.messages)

        val session = AgentSession("level-two-output", result.messages)
        assertConversationInvariants(session.messages)
    }

    private fun assertConversationInvariants(messages: List<AgentMessage>) {
        val nonSystem = messages.filterNot { it is AgentMessage.System }
        assertTrue(nonSystem.isNotEmpty())
        assertTrue(nonSystem.first() is AgentMessage.User)

        var index = 0
        while (index < nonSystem.size) {
            when (val message = nonSystem[index]) {
                is AgentMessage.User -> index++
                is AgentMessage.Assistant -> {
                    if (message.toolCalls.isEmpty()) {
                        index++
                        continue
                    }
                    val expectedIds = message.toolCalls.map { it.id }.toSet()
                    val results = mutableListOf<String>()
                    var resultIndex = index + 1
                    while (resultIndex < nonSystem.size && nonSystem[resultIndex] is AgentMessage.Tool) {
                        results += (nonSystem[resultIndex] as AgentMessage.Tool).result.toolCallId
                        resultIndex++
                    }
                    assertEquals(expectedIds, results.toSet())
                    assertEquals(expectedIds.size, results.size)
                    index = resultIndex
                }
                is AgentMessage.Tool -> {
                    throw AssertionError("orphan tool result")
                }
                is AgentMessage.System -> index++
            }
        }
    }
}
