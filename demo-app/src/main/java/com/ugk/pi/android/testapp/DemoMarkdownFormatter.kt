package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin

/**
 * 采用业界成熟优秀的 Markwon (基于 CommonMark AST + Android 原生 Spannable)
 * 渲染大模型 Markdown 回答。
 *
 * 原生支持表格（Tables）、代码块（Code Blocks）、行内代码、引用（Blockquotes）、
 * 任务列表（Task Lists）、删除线、各级标题与项目编号等。
 */
object DemoMarkdownFormatter {

    @Volatile
    private var lightMarkwon: Markwon? = null
    @Volatile
    private var darkMarkwon: Markwon? = null

    private fun getMarkwon(context: Context): Markwon {
        val isDark = ThemeManager.isDark
        return if (isDark) {
            darkMarkwon ?: synchronized(this) {
                darkMarkwon ?: buildMarkwon(context.applicationContext, isDark = true).also { darkMarkwon = it }
            }
        } else {
            lightMarkwon ?: synchronized(this) {
                lightMarkwon ?: buildMarkwon(context.applicationContext, isDark = false).also { lightMarkwon = it }
            }
        }
    }

    private fun buildMarkwon(context: Context, isDark: Boolean): Markwon {
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        // 全新美学设计系统：米白、橙红、淡绿与纯曜石炭黑
        val tableBorder = if (isDark) Color.rgb(47, 50, 56) else Color.rgb(229, 224, 216)
        val tableHeaderBg = if (isDark) Color.rgb(36, 38, 43) else Color.rgb(245, 242, 236)
        val tableOddBg = if (isDark) Color.rgb(30, 32, 36) else Color.rgb(251, 249, 245)

        val inlineCodeBg = if (isDark) Color.rgb(36, 38, 43) else Color.rgb(245, 242, 236)
        val inlineCodeText = if (isDark) Color.rgb(110, 231, 183) else Color.rgb(45, 106, 79)

        val codeBlockBg = if (isDark) Color.rgb(21, 22, 25) else Color.rgb(245, 242, 236)
        val codeBlockText = if (isDark) Color.rgb(110, 231, 183) else Color.rgb(45, 106, 79)

        val quoteColor = if (isDark) Color.rgb(255, 110, 74) else Color.rgb(234, 84, 52)

        val tableTheme = TableTheme.Builder()
            .tableBorderColor(tableBorder)
            .tableBorderWidth(dp(1))
            .tableCellPadding(dp(4))
            .tableHeaderRowBackgroundColor(tableHeaderBg)
            .tableEvenRowBackgroundColor(Color.TRANSPARENT)
            .tableOddRowBackgroundColor(tableOddBg)
            .build()

        return Markwon.builder(context)
            .usePlugin(TablePlugin.create(tableTheme))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBackgroundColor(inlineCodeBg)
                        .codeTextColor(inlineCodeText)
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeTextSize(dp(13))
                        .codeBlockBackgroundColor(codeBlockBg)
                        .codeBlockTextColor(codeBlockText)
                        .codeBlockTypeface(Typeface.MONOSPACE)
                        .codeBlockTextSize(dp(13))
                        .blockQuoteColor(quoteColor)
                        .blockQuoteWidth(dp(3))
                        .bulletWidth(dp(6))
                        .headingBreakHeight(0)
                }

                override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                    // 自定义段落访问器：段落结束时只保证单个换行，消除双换行导致的过大段间距
                    builder.on(org.commonmark.node.Paragraph::class.java) { visitor, paragraph ->
                        val length = visitor.length()
                        visitor.visitChildren(paragraph)
                        visitor.setSpans(length, visitor.configuration().spansFactory().get(org.commonmark.node.Paragraph::class.java))
                        if (visitor.hasNext(paragraph)) {
                            visitor.ensureNewLine()
                        }
                    }

                    // 表格内文字与代码紧凑化排版：将单元格文字缩放到 0.80f（约 12sp），大幅减少换行与表格纵向高度堆叠
                    builder.on(org.commonmark.node.Text::class.java) { visitor, text ->
                        val inTable = isInsideTableCell(text)
                        val start = visitor.length()
                        visitor.builder().append(text.literal)
                        if (inTable) {
                            visitor.builder().setSpan(
                                android.text.style.RelativeSizeSpan(0.80f),
                                start,
                                visitor.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }

                    builder.on(org.commonmark.node.Code::class.java) { visitor, code ->
                        val length = visitor.length()
                        visitor.builder().append(code.literal)
                        visitor.setSpans(length, visitor.configuration().spansFactory().get(org.commonmark.node.Code::class.java))
                        if (isInsideTableCell(code)) {
                            visitor.builder().setSpan(
                                android.text.style.RelativeSizeSpan(0.80f),
                                length,
                                visitor.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
            })
            .build()
    }

    private fun isInsideTableCell(node: org.commonmark.node.Node): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent is org.commonmark.ext.gfm.tables.TableCell) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * 将大模型输出中过度松散的连续空行进行紧凑化，调小段落间的间距。
     */
    private fun compactSpacing(markdown: String): String {
        return markdown.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * 对流式生成中的 Markdown 进行表格结构平稳化。
     *
     * 大模型流式输出表格时，最后一行通常尚未闭合（缺少结尾 '|' 或列数暂时少于表头），
     * 这会导致 CommonMark 无法稳定识别表格、在每一字符输出时引起列宽剧烈跳变和网格重叠抖动。
     * 本方法在流式渲染前临时补齐未完成表格行的单元格与闭合符，使表格在流式全过程中保持列数恒定、稳定平滑。
     */
    fun stabilizeStreamingMarkdown(markdown: String): String {
        if (!markdown.contains('|')) return markdown

        val lines = markdown.lines()
        if (lines.size < 2) return markdown

        // 寻找最后一个表格的分隔行（如 |---|---| 或 |:---:|---:|）
        var sepIndex = -1
        val separatorRegex = Regex("""^\s*\|?\s*[-:]+\s*\|[\s-:|]*$""")
        for (i in lines.indices.reversed()) {
            if (separatorRegex.matches(lines[i])) {
                sepIndex = i
                break
            }
        }
        if (sepIndex <= 0) return markdown

        val headerLine = lines[sepIndex - 1].trim()
        val headerCells = headerLine.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val colCount = headerCells.size
        if (colCount <= 0) return markdown

        val lastIndex = lines.lastIndex
        if (lastIndex <= sepIndex) {
            return markdown
        }

        val lastLine = lines[lastIndex]
        val trimmedLast = lastLine.trim()
        if (trimmedLast.isEmpty()) return markdown

        if (trimmedLast.startsWith("|")) {
            val currentCells = trimmedLast.split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val currentCount = currentCells.size
            val isClosed = trimmedLast.endsWith("|")

            val neededPadding = colCount - currentCount
            if (neededPadding > 0 || !isClosed) {
                val sb = StringBuilder()
                for (i in 0 until lastIndex) {
                    sb.append(lines[i]).append("\n")
                }
                sb.append(lastLine)
                if (!isClosed) {
                    sb.append(" |")
                }
                if (neededPadding > 0) {
                    repeat(neededPadding) {
                        sb.append("  |")
                    }
                }
                sb.append("\n")
                return sb.toString()
            }
        }

        return markdown
    }

    /**
     * 将 Markdown 直接高效渲染到 TextView 上。
     *
     * @param isStreaming 是否处于流式打字生成中；若为 true 则开启表格平稳化补齐，防止网格重叠抖动
     */
    fun setMarkdown(textView: TextView, markdown: String, isStreaming: Boolean = false) {
        if (markdown.isBlank()) {
            textView.text = ""
            return
        }
        val textToRender = if (isStreaming) {
            stabilizeStreamingMarkdown(compactSpacing(markdown))
        } else {
            compactSpacing(markdown)
        }
        getMarkwon(textView.context).setMarkdown(textView, textToRender)
    }

    /**
     * 将 Markdown 解析为 SpannedCharSequence。
     */
    fun toMarkdown(context: Context, markdown: String): CharSequence {
        if (markdown.isBlank()) return ""
        return getMarkwon(context).toMarkdown(compactSpacing(markdown))
    }

    /**
     * 兼容旧接口：格式化字符串为 CharSequence。
     */
    fun format(rawText: String): CharSequence {
        val instance = if (ThemeManager.isDark) darkMarkwon ?: lightMarkwon else lightMarkwon ?: darkMarkwon
        return if (instance != null) {
            instance.toMarkdown(compactSpacing(rawText))
        } else {
            rawText
        }
    }
}
