package com.ugk.pi.android.testapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 消息内容块：正文文本块或独立代码块。
 */
sealed class DemoContentBlock {
    data class Text(val markdown: String) : DemoContentBlock()
    data class Code(val language: String, val code: String) : DemoContentBlock()
    data class Table(val tableMarkdown: String) : DemoContentBlock()
}

/**
 * 将 Markdown 消息内容切分为正文块、代码块与表格块的解析器。
 */
object DemoCodeBlockParser {

    fun splitBlocks(markdown: String): List<DemoContentBlock> {
        val trimmed = markdown.trim()
        if (trimmed.isBlank()) return emptyList()

        if (!markdown.contains("```")) {
            return extractTableAndTextBlocks(trimmed)
        }

        val blocks = mutableListOf<DemoContentBlock>()
        val lines = markdown.lines()
        val textBuffer = StringBuilder()
        val codeBuffer = StringBuilder()
        var inCodeBlock = false
        var currentLang = ""

        for (line in lines) {
            val currentTrimmed = line.trim()
            if (currentTrimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // 代码块闭合
                    blocks.add(DemoContentBlock.Code(currentLang, codeBuffer.toString()))
                    codeBuffer.clear()
                    currentLang = ""
                    inCodeBlock = false
                } else {
                    // 遇到代码块开始：先提交前面的正文
                    if (textBuffer.isNotEmpty()) {
                        val text = textBuffer.toString().trim()
                        if (text.isNotBlank()) {
                            blocks.addAll(extractTableAndTextBlocks(text))
                        }
                        textBuffer.clear()
                    }
                    currentLang = currentTrimmed.removePrefix("```").trim()
                    inCodeBlock = true
                }
            } else {
                if (inCodeBlock) {
                    if (codeBuffer.isNotEmpty()) codeBuffer.append("\n")
                    codeBuffer.append(line)
                } else {
                    if (textBuffer.isNotEmpty()) textBuffer.append("\n")
                    textBuffer.append(line)
                }
            }
        }

        if (inCodeBlock) {
            // 未闭合的代码块（流式生成中）
            blocks.add(DemoContentBlock.Code(currentLang, codeBuffer.toString()))
        } else if (textBuffer.isNotEmpty()) {
            val text = textBuffer.toString().trim()
            if (text.isNotBlank()) {
                blocks.addAll(extractTableAndTextBlocks(text))
            }
        }

        return blocks
    }

    private fun extractTableAndTextBlocks(markdown: String): List<DemoContentBlock> {
        val lines = markdown.lines()
        val result = mutableListOf<DemoContentBlock>()
        val textBuffer = StringBuilder()
        val tableBuffer = StringBuilder()
        var inTable = false

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (!inTable) {
                val isNextDivider = if (i + 1 < lines.size) {
                    DemoTableData.isDividerRow(lines[i + 1])
                } else false

                if ((trimmed.startsWith("|") || trimmed.contains("|")) && isNextDivider) {
                    if (textBuffer.isNotEmpty()) {
                        val t = textBuffer.toString().trim()
                        if (t.isNotBlank()) result.add(DemoContentBlock.Text(t))
                        textBuffer.clear()
                    }
                    inTable = true
                    tableBuffer.append(line)
                } else {
                    if (textBuffer.isNotEmpty()) textBuffer.append("\n")
                    textBuffer.append(line)
                }
            } else {
                val isStillTable = trimmed.startsWith("|") || (trimmed.contains("|") && !trimmed.startsWith("#"))
                if (isStillTable) {
                    if (tableBuffer.isNotEmpty()) tableBuffer.append("\n")
                    tableBuffer.append(line)
                } else if (trimmed.isBlank()) {
                    result.add(DemoContentBlock.Table(tableBuffer.toString()))
                    tableBuffer.clear()
                    inTable = false
                } else {
                    result.add(DemoContentBlock.Table(tableBuffer.toString()))
                    tableBuffer.clear()
                    inTable = false
                    if (textBuffer.isNotEmpty()) textBuffer.append("\n")
                    textBuffer.append(line)
                }
            }
            i++
        }

        if (inTable && tableBuffer.isNotEmpty()) {
            result.add(DemoContentBlock.Table(tableBuffer.toString()))
        } else if (textBuffer.isNotEmpty()) {
            val t = textBuffer.toString().trim()
            if (t.isNotBlank()) result.add(DemoContentBlock.Text(t))
        }

        return result
    }
}

/**
 * 专用于代码块的横向滚动视图，智能处理与外层垂直列表的手势冲突。
 */
class DemoHorizontalCodeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private var xDistance = 0f
    private var yDistance = 0f
    private var lastX = 0f
    private var lastY = 0f

    init {
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = true
        isFillViewport = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                xDistance = 0f
                yDistance = 0f
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val curX = ev.x
                val curY = ev.y
                xDistance += abs(curX - lastX)
                yDistance += abs(curY - lastY)
                lastX = curX
                lastY = curY
                if (xDistance > yDistance) {
                    // 水平滑动意图明显，禁止父层拦截
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}

private fun Context.codeDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/**
 * 单行不换行、支持横向平滑滑动的现代代码块视图。
 */
class DemoCodeBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            context.codeDp(12),
            context.codeDp(5),
            context.codeDp(12),
            context.codeDp(5)
        )
        background = Ui.rounded(context, Ui.SurfaceSubtle, 6, Ui.Outline, 1)
    }

    private val langLabel = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.025f
        setTextColor(Ui.TextSecondary)
    }

    private val copyButton = TextView(context).apply {
        text = "复制代码"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Ui.AccentDark)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.015f
        setPadding(
            context.codeDp(8),
            context.codeDp(3),
            context.codeDp(8),
            context.codeDp(3)
        )
    }

    private val horizontalScrollView = DemoHorizontalCodeScrollView(context)

    private val codeTextView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(Ui.CodeText)
        setLineSpacing(0f, 1.24f)
        setHorizontallyScrolling(true) // 核心：强制不自动折行
        includeFontPadding = false
        setTextIsSelectable(true)
        setPadding(
            context.codeDp(14),
            context.codeDp(10),
            context.codeDp(14),
            context.codeDp(12)
        )
    }

    private var currentCode: String = ""

    init {
        orientation = VERTICAL
        background = Ui.rounded(context, Ui.CodeBg, 8, Ui.Outline, 1)

        header.addView(langLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(copyButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        horizontalScrollView.addView(
            codeTextView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(horizontalScrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        copyButton.setOnClickListener {
            if (currentCode.isBlank()) return@setOnClickListener
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@setOnClickListener
            clipboard.setPrimaryClip(ClipData.newPlainText("Code", currentCode))
            Toast.makeText(context, "代码已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
        applyThemeColors()
    }

    private fun applyThemeColors() {
        val isDark = ThemeManager.isDark
        // 全新色彩：浅色米白调，深色纯曜石炭黑（拒绝绿感深色）
        val cardBg = if (isDark) android.graphics.Color.rgb(21, 22, 25) else android.graphics.Color.rgb(245, 242, 236)
        val headerBg = if (isDark) android.graphics.Color.rgb(31, 32, 36) else android.graphics.Color.rgb(240, 236, 229)
        val border = if (isDark) android.graphics.Color.rgb(47, 50, 56) else android.graphics.Color.rgb(229, 224, 216)
        val codeColor = if (isDark) android.graphics.Color.rgb(110, 231, 183) else android.graphics.Color.rgb(45, 106, 79)
        val labelColor = if (isDark) android.graphics.Color.rgb(156, 161, 174) else android.graphics.Color.rgb(107, 102, 94)
        val copyColor = if (isDark) android.graphics.Color.rgb(255, 110, 74) else android.graphics.Color.rgb(234, 84, 52)

        background = Ui.rounded(context, cardBg, 8, border, 1)
        header.background = Ui.rounded(context, headerBg, 6, border, 1)
        langLabel.setTextColor(labelColor)
        copyButton.setTextColor(copyColor)
        codeTextView.setTextColor(codeColor)
    }

    fun bind(language: String, code: String) {
        applyThemeColors()
        currentCode = code
        langLabel.text = if (language.isNotBlank()) language.uppercase() else "CODE"
        codeTextView.text = code
    }
}
