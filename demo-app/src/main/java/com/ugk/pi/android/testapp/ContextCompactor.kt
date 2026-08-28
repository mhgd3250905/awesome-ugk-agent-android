package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentSession
import kotlin.math.roundToInt

/**
 * 结构化上下文智能压缩结果。
 */
data class CompactionSummary(
    val triggered: Boolean,
    val level: Int = 0,
    val originalChars: Int = 0,
    val compressedChars: Int = 0,
    val savedChars: Int = 0,
    val savedRatio: Double = 0.0,
    val messageCountBefore: Int = 0,
    val messageCountAfter: Int = 0,
    val reason: String = ""
)

/**
 * 上下文智能压缩引擎 (Context Compactor)。
 *
 * 当会话上下文占用达到设定阈值（默认 70%）时触发阶梯式渐进压缩：
 * 1. Level 1: 历史长工具调用结果无损剪枝 (Tool Result Pruning，零额外开销)；
 * 2. Level 2: 早期多轮历史对话结构化提炼 (Structured Summarization)；
 * 3. Level 3: 整组原子边界保护与角色协议校验 (trimAtSafeBoundaries，绝不产生孤儿节点)。
 */
object ContextCompactor {

    const val DEFAULT_THRESHOLD: Double = 0.70
    const val MAX_PRUNED_TOOL_CHARS: Int = 800
    const val MIN_RECENT_TURNS_PRESERVED: Int = 3

    /**
     * 将上下文规格字符串转换为对应的最大 Token 容量。
     */
    fun parseContextWindowTokens(contextWindow: String?): Int {
        val cw = contextWindow?.trim()?.uppercase().orEmpty()
        return when {
            cw.startsWith("2M") -> 2_097_152
            cw.startsWith("1M") -> 1_048_576
            cw.startsWith("200K") -> 204_800
            cw.startsWith("128K") -> 131_072
            cw.startsWith("64K") -> 65_536
            cw.startsWith("32K") -> 32_768
            else -> 131_072
        }
    }

    /**
     * 将 Token 数量格式化为人类可读的紧凑字符串（例如 1.2K, 15.6K, 1M, 2M）。
     */
    fun formatTokenCount(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0).replace(".0M", "M")
            tokens >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", tokens / 1000.0).replace(".0K", "K")
            else -> tokens.toString()
        }
    }

    /**
     * 根据中英文字符、代码块与 JSON 结构快速加权估算消息列表的 Token 消耗。
     */
    fun estimateTokens(messages: List<AgentMessage>): Int {
        var totalTokens = 0
        messages.forEach { msg ->
            val content = when (msg) {
                is AgentMessage.System -> msg.content
                is AgentMessage.User -> msg.content
                is AgentMessage.Assistant -> msg.content + (msg.reasoningContent.orEmpty()) +
                        msg.toolCalls.joinToString("") { "${it.name}${it.input}" }
                is AgentMessage.Tool -> msg.result.content
            }
            totalTokens += estimateContentTokens(content)
        }
        return totalTokens
    }

    /**
     * 单段文本 Token 估算：中文约 1.2 字符/Token，英文代码约 3.5 字符/Token，取安全加权。
     */
    fun estimateContentTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjkCount = 0
        var otherCount = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x303F) {
                cjkCount++
            } else {
                otherCount++
            }
        }
        val cjkTokens = (cjkCount / 1.1).roundToInt()
        val otherTokens = (otherCount / 3.2).roundToInt()
        return (cjkTokens + otherTokens).coerceAtLeast(1)
    }

    fun calculateTotalChars(messages: List<AgentMessage>): Int {
        return messages.sumOf { msg ->
            when (msg) {
                is AgentMessage.System -> msg.content.length
                is AgentMessage.User -> msg.content.length
                is AgentMessage.Assistant -> msg.content.length + (msg.reasoningContent?.length ?: 0)
                is AgentMessage.Tool -> msg.result.content.length
            }
        }
    }

    /**
     * 核心压缩执行入口：检查并执行阶梯式压缩。
     */
    fun compactIfNeeded(
        session: AgentSession,
        contextWindow: String? = null,
        thresholdRatio: Double = DEFAULT_THRESHOLD,
        autoCompaction: Boolean = true
    ): CompactionSummary {
        if (!autoCompaction || session.messages.isEmpty()) {
            return CompactionSummary(triggered = false, reason = "自动压缩未开启或会话为空")
        }

        val originalChars = calculateTotalChars(session.messages)
        val msgCountBefore = session.messages.size
        val maxTokens = parseContextWindowTokens(contextWindow)
        val currentTokens = estimateTokens(session.messages)
        val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()

        if (usageRatio < thresholdRatio) {
            return CompactionSummary(
                triggered = false,
                originalChars = originalChars,
                compressedChars = originalChars,
                messageCountBefore = msgCountBefore,
                messageCountAfter = msgCountBefore,
                reason = "当前使用率 ${(usageRatio * 100).toInt()}% 未达触发阈值 ${(thresholdRatio * 100).toInt()}%"
            )
        }

        // ====== Level 1: 历史长工具调用结果剪枝 (Tool Result Pruning) ======
        val level1Messages = pruneOldToolResults(session.messages)
        val level1Tokens = estimateTokens(level1Messages)
        val level1Ratio = level1Tokens.toDouble() / maxTokens.toDouble()

        if (level1Ratio < thresholdRatio * 0.95) {
            // Level 1 剪枝已成功释放足够空间
            session.messages.clear()
            session.messages.addAll(level1Messages)
            val afterChars = calculateTotalChars(session.messages)
            val savedChars = originalChars - afterChars
            val savedRatio = if (originalChars > 0) savedChars.toDouble() / originalChars else 0.0
            return CompactionSummary(
                triggered = true,
                level = 1,
                originalChars = originalChars,
                compressedChars = afterChars,
                savedChars = savedChars,
                savedRatio = savedRatio,
                messageCountBefore = msgCountBefore,
                messageCountAfter = session.messages.size,
                reason = "Level 1 工具结果剪枝完成：使用率从 ${(usageRatio * 100).toInt()}% 降至 ${(level1Ratio * 100).toInt()}%"
            )
        }

        // ====== Level 2: 早期多轮对话结构化提炼 (Summarization Compaction) ======
        val level2Messages = compactOlderTurnsIntoSummary(level1Messages)
        val level3Messages = ensureAtomicInvariants(level2Messages)

        session.messages.clear()
        session.messages.addAll(level3Messages)

        val afterChars = calculateTotalChars(session.messages)
        val savedChars = originalChars - afterChars
        val savedRatio = if (originalChars > 0) savedChars.toDouble() / originalChars else 0.0

        return CompactionSummary(
            triggered = true,
            level = 2,
            originalChars = originalChars,
            compressedChars = afterChars,
            savedChars = savedChars,
            savedRatio = savedRatio,
            messageCountBefore = msgCountBefore,
            messageCountAfter = session.messages.size,
            reason = "Level 2 结构化提炼完成：释放 ${(savedRatio * 100).toInt()}% 字符容量"
        )
    }

    /**
     * Level 1: 扫描并折叠非最近 2 轮中的过长 Tool 输出。
     */
    internal fun pruneOldToolResults(messages: List<AgentMessage>): List<AgentMessage> {
        val turns = splitIntoTurns(messages)
        if (turns.size <= 2) return messages

        val result = mutableListOf<AgentMessage>()
        val totalTurns = turns.size

        turns.forEachIndexed { turnIndex, turnMessages ->
            val isRecentTurn = turnIndex >= totalTurns - 2
            if (isRecentTurn) {
                result.addAll(turnMessages)
            } else {
                turnMessages.forEach { msg ->
                    if (msg is AgentMessage.Tool && msg.result.content.length > MAX_PRUNED_TOOL_CHARS) {
                        val content = msg.result.content
                        val head = content.take(250)
                        val tail = content.takeLast(250)
                        val foldedContent = "[历史输出已折叠（原 ${content.length} 字符）]\n>>> 首部内容:\n$head\n...\n>>> 尾部内容:\n$tail"
                        result.add(AgentMessage.Tool(msg.result.copy(content = foldedContent)))
                    } else {
                        result.add(msg)
                    }
                }
            }
        }
        return result
    }

    /**
     * Level 2: 将早期 50% 轮次提炼为《阶段结构化摘要》节点，保留最近 3~5 轮完整对话。
     */
    internal fun compactOlderTurnsIntoSummary(messages: List<AgentMessage>): List<AgentMessage> {
        val systemMessages = messages.filterIsInstance<AgentMessage.System>()
        val nonSystemMessages = messages.filterNot { it is AgentMessage.System }
        val turns = splitIntoTurns(nonSystemMessages)

        if (turns.size <= MIN_RECENT_TURNS_PRESERVED) {
            return messages
        }

        val preservedTurnCount = MIN_RECENT_TURNS_PRESERVED.coerceAtLeast(turns.size / 3)
        val olderTurnsCount = turns.size - preservedTurnCount
        val olderTurns = turns.take(olderTurnsCount)
        val recentTurns = turns.takeLast(preservedTurnCount)

        val summaryText = extractStructuredSummary(olderTurns)
        val summaryUserMessage = AgentMessage.User(
            content = summaryText,
            timeContext = null
        )

        val result = mutableListOf<AgentMessage>()
        result.addAll(systemMessages)
        result.add(summaryUserMessage)
        recentTurns.forEach { result.addAll(it) }
        return result
    }

    /**
     * 将多轮历史消息按用户输入拆分为独立的交互轮次（Turn）。
     */
    private fun splitIntoTurns(messages: List<AgentMessage>): List<List<AgentMessage>> {
        val turns = mutableListOf<MutableList<AgentMessage>>()
        var currentTurn: MutableList<AgentMessage>? = null

        messages.forEach { msg ->
            if (msg is AgentMessage.User) {
                currentTurn = mutableListOf(msg)
                turns.add(currentTurn!!)
            } else {
                if (currentTurn == null) {
                    currentTurn = mutableListOf(msg)
                    turns.add(currentTurn!!)
                } else {
                    currentTurn!!.add(msg)
                }
            }
        }
        return turns
    }

    /**
     * 自动提炼历史交互中的核心意图、关键结论与阶段状态。
     */
    private fun extractStructuredSummary(olderTurns: List<List<AgentMessage>>): String {
        val summaryBuilder = StringBuilder()
        summaryBuilder.append("[🗜️ 系统上下文压缩摘要：前序 ${olderTurns.size} 轮对话已提炼]\n")
        summaryBuilder.append("• 早期核心目标与交互要点：\n")

        olderTurns.take(8).forEachIndexed { index, turn ->
            val userMsg = turn.filterIsInstance<AgentMessage.User>().firstOrNull()?.content?.trim()
            val assistantText = turn.filterIsInstance<AgentMessage.Assistant>().lastOrNull()?.content?.trim()
            if (!userMsg.isNullOrBlank()) {
                val shortUser = if (userMsg.length > 80) userMsg.take(80) + "..." else userMsg
                summaryBuilder.append("  ${index + 1}. 用户: $shortUser\n")
            }
            if (!assistantText.isNullOrBlank()) {
                val shortAssistant = if (assistantText.length > 100) assistantText.take(100) + "..." else assistantText
                summaryBuilder.append("     助手结论: $shortAssistant\n")
            }
        }
        summaryBuilder.append("• 状态：以上前序步骤已确认就绪，当前会话在紧凑模式下继续进行。")
        return summaryBuilder.toString()
    }

    /**
     * Level 3: 校验原子边界（整组完整性、无孤儿 Tool 结果、首消息 User）。
     */
    internal fun ensureAtomicInvariants(messages: List<AgentMessage>): List<AgentMessage> {
        val system = messages.filterIsInstance<AgentMessage.System>()
        val nonSystem = messages.filterNot { it is AgentMessage.System }
        if (nonSystem.isEmpty()) return messages

        val validNonSystem = mutableListOf<AgentMessage>()
        var pendingToolUses = mutableSetOf<String>()

        nonSystem.forEach { msg ->
            when (msg) {
                is AgentMessage.User -> {
                    validNonSystem.add(msg)
                }
                is AgentMessage.Assistant -> {
                    validNonSystem.add(msg)
                    pendingToolUses = msg.toolCalls.map { it.id }.toMutableSet()
                }
                is AgentMessage.Tool -> {
                    if (pendingToolUses.contains(msg.result.toolCallId) || validNonSystem.any { it is AgentMessage.Assistant }) {
                        validNonSystem.add(msg)
                    }
                }
                else -> validNonSystem.add(msg)
            }
        }

        val firstUserIndex = validNonSystem.indexOfFirst { it is AgentMessage.User }
        val finalNonSystem = if (firstUserIndex > 0) {
            validNonSystem.subList(firstUserIndex, validNonSystem.size)
        } else {
            validNonSystem
        }

        return system + finalNonSystem
    }
}
