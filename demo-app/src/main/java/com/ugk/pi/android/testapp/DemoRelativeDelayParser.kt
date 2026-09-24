package com.ugk.pi.android.testapp

import java.util.Locale

/** Conservative fast path for explicit one-shot delays; ambiguous wording stays with the Agent. */
internal object DemoRelativeDelayParser {
    data class Request(val delaySeconds: Long, val instruction: String)

    private val requestPattern = Regex(
        """^\s*(?:(?:请|麻烦你|麻烦)\s*)?(?:在|过)?\s*([0-9]+(?:\.[0-9]+)?|半|[一二三四五六七八九十两]+)\s*(秒钟?|分钟?|小时|天|min(?:ute)?s?|sec(?:ond)?s?|h(?:ou)?rs?)\s*(?:以后|之后|后|再)\s*[,，:：]?\s*(.+?)\s*[。.!！]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val actionPattern = Regex(
        """^(?:请\s*)?(?:帮我|给我|跟我|对我|为我|告诉我|提醒我|通知我|发送|发|说|查询|查|搜索|打开|启动|执行|总结|检查|查看|写|播报|报告|继续|打个招呼|提醒|做|叫|喊|把|帮忙)"""
    )
    private val delayMentionPattern = Regex(
        """([0-9]+(?:\.[0-9]+)?|半|[一二三四五六七八九十两]+)\s*(秒钟?|分钟?|小时|天|min(?:ute)?s?|sec(?:ond)?s?|h(?:ou)?rs?)\s*(?:以后|之后|后)""",
        RegexOption.IGNORE_CASE
    )
    private val assistantProposalPattern = Regex(
        """^请确认[：:]\s*([0-9]{1,5})\s*秒后执行「(.+)」[。.]?$"""
    )

    fun parse(message: String): Request? {
        val match = requestPattern.matchEntire(message.trim()) ?: return null
        val seconds = durationSeconds(match.groupValues[1], match.groupValues[2]) ?: return null
        val instruction = match.groupValues[3].trim().trimEnd('。', '.', '!', '！')
        if (instruction.isEmpty() || instruction.length > 2_000 || !actionPattern.containsMatchIn(instruction)) {
            return null
        }
        return Request(seconds, instruction)
    }

    /** Recover a model's tool-shaped text only when the user actually requested a delay. */
    fun parseAssistantProposal(userMessage: String, assistantMessage: String): Request? {
        val userDelay = delayMentionPattern.find(userMessage) ?: return null
        val requestedSeconds = durationSeconds(userDelay.groupValues[1], userDelay.groupValues[2])
            ?: return null
        val match = assistantProposalPattern.matchEntire(assistantMessage.trim()) ?: return null
        val seconds = match.groupValues[1].toLongOrNull()?.takeIf { it in 1L..86_400L } ?: return null
        if (seconds != requestedSeconds) return null
        val instruction = match.groupValues[2].trim()
        if (instruction.isEmpty() || instruction.length > 2_000) return null
        return Request(seconds, instruction)
    }

    fun isUnconfirmedDelayClaim(userMessage: String, assistantMessage: String): Boolean =
        delayMentionPattern.containsMatchIn(userMessage) &&
            assistantMessage.trimStart().startsWith("请确认")

    private fun durationSeconds(amountText: String, unitText: String): Long? {
        val amount = parseAmount(amountText) ?: return null
        val unitSeconds = when (unitText.lowercase(Locale.ROOT)) {
            "秒", "秒钟", "sec", "secs", "second", "seconds" -> 1L
            "分", "分钟", "min", "mins", "minute", "minutes" -> 60L
            "小时", "h", "hr", "hrs", "hour", "hours" -> 3_600L
            "天" -> 86_400L
            else -> return null
        }
        val seconds = amount * unitSeconds
        if (seconds < 1.0 || seconds > 86_400.0 || seconds % 1.0 != 0.0) return null
        return seconds.toLong()
    }

    private fun parseAmount(value: String): Double? {
        if (value == "半") return 0.5
        value.toDoubleOrNull()?.let { return it }
        val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value == "十") return 10.0
        val tenIndex = value.indexOf('十')
        if (tenIndex < 0) return value.singleOrNull()?.let(digits::get)?.toDouble()
        val tens = if (tenIndex == 0) 1 else value.take(tenIndex).singleOrNull()?.let(digits::get)
            ?: return null
        val suffix = value.drop(tenIndex + 1)
        val ones = if (suffix.isEmpty()) 0 else suffix.singleOrNull()?.let(digits::get) ?: return null
        return (tens * 10 + ones).toDouble()
    }
}
