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
            .tableCellPadding(dp(8))
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
                }
            })
            .build()
    }

    /**
     * 将大模型输出中过度松散的连续空行进行紧凑化，调小段落间的间距。
     */
    private fun compactSpacing(markdown: String): String {
        return markdown.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * 将 Markdown 直接高效渲染到 TextView 上。
     */
    fun setMarkdown(textView: TextView, markdown: String) {
        if (markdown.isBlank()) {
            textView.text = ""
            return
        }
        getMarkwon(textView.context).setMarkdown(textView, compactSpacing(markdown))
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
