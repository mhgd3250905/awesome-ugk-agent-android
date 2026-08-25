package com.ugk.pi.android.testapp

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 针对 Demo 聊天场景优化的轻量级原生 Markdown 文本格式化器。
 *
 * 无需引入重量级第三方依赖，直接生成带有样式 Span 的 [SpannableStringBuilder]，
 * 支持粗体、行内代码、标题、列表项目与代码块的优美呈现。
 */
object DemoMarkdownFormatter {

    private val CODE_BG_COLOR get() = Ui.CodeBg
    private val CODE_TEXT_COLOR get() = Ui.CodeText
    private val HEADER_TEXT_COLOR get() = Ui.TextPrimary
    private val BULLET_COLOR get() = Ui.Mint

    /**
     * 将输入的 markdown 原文字符串格式化为富文本 [CharSequence]。
     */
    fun format(rawText: String): CharSequence {
        if (rawText.isBlank()) return rawText

        val lines = rawText.lines()
        val builder = SpannableStringBuilder()

        var inCodeBlock = false
        val codeBlockLines = mutableListOf<String>()

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. 处理代码块 ```
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // 代码块结束
                    val codeContent = codeBlockLines.joinToString("\n")
                    appendCodeBlock(builder, codeContent)
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    // 代码块开始
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(line)
                continue
            }

            // 2. 普通行：处理标题、列表与行内标记
            if (i > 0 && builder.isNotEmpty() && !builder.endsWith("\n")) {
                builder.append("\n")
            }

            when {
                // 标题 # / ## / ###
                trimmed.startsWith("### ") -> {
                    val content = trimmed.removePrefix("### ").trim()
                    appendHeader(builder, content, 1.12f)
                }
                trimmed.startsWith("## ") -> {
                    val content = trimmed.removePrefix("## ").trim()
                    appendHeader(builder, content, 1.20f)
                }
                trimmed.startsWith("# ") -> {
                    val content = trimmed.removePrefix("# ").trim()
                    appendHeader(builder, content, 1.30f)
                }
                // 列表项 - / * / •
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    val content = trimmed.substring(2).trim()
                    appendBulletItem(builder, content)
                }
                // 普通段落
                else -> {
                    appendInlineFormatted(builder, line)
                }
            }
        }

        // 未闭合的代码块兜底
        if (inCodeBlock && codeBlockLines.isNotEmpty()) {
            val codeContent = codeBlockLines.joinToString("\n")
            appendCodeBlock(builder, codeContent)
        }

        return builder
    }

    private fun appendHeader(builder: SpannableStringBuilder, text: String, sizeMultiplier: Float) {
        val start = builder.length
        appendInlineFormatted(builder, text)
        val end = builder.length
        builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(RelativeSizeSpan(sizeMultiplier), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(HEADER_TEXT_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendBulletItem(builder: SpannableStringBuilder, text: String) {
        val start = builder.length
        builder.append("•  ")
        builder.setSpan(ForegroundColorSpan(BULLET_COLOR), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendInlineFormatted(builder, text)
    }

    private fun appendCodeBlock(builder: SpannableStringBuilder, code: String) {
        if (builder.isNotEmpty() && !builder.endsWith("\n")) {
            builder.append("\n")
        }
        val start = builder.length
        builder.append(code)
        val end = builder.length
        builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(BackgroundColorSpan(CODE_BG_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append("\n")
    }

    private fun appendInlineFormatted(builder: SpannableStringBuilder, line: String) {
        var cursor = 0
        val len = line.length

        while (cursor < len) {
            // 粗体 **...**
            if (cursor + 1 < len && line[cursor] == '*' && line[cursor + 1] == '*') {
                val endBold = line.indexOf("**", cursor + 2)
                if (endBold != -1) {
                    val boldText = line.substring(cursor + 2, endBold)
                    val start = builder.length
                    appendInlineFormatted(builder, boldText)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    cursor = endBold + 2
                    continue
                }
            }

            // 行内代码 `...`
            if (line[cursor] == '`') {
                val endCode = line.indexOf('`', cursor + 1)
                if (endCode != -1) {
                    val codeText = line.substring(cursor + 1, endCode)
                    val start = builder.length
                    builder.append(" ").append(codeText).append(" ")
                    val end = builder.length
                    builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(CODE_BG_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(CODE_TEXT_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    cursor = endCode + 1
                    continue
                }
            }

            // 普通字符
            builder.append(line[cursor])
            cursor++
        }
    }
}
