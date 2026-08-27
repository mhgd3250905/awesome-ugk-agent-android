package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * 表格数据模型。
 */
data class DemoTableData(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<Int>
) {
    companion object {
        /**
         * 将一段 Markdown 表格文本解析为结构化的表头与行数据。
         */
        fun parse(markdown: String): DemoTableData? {
            val lines = markdown.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size < 2) return null

            // 寻找分隔行位置
            var dividerIndex = -1
            for (i in 1 until lines.size) {
                if (isDividerRow(lines[i])) {
                    dividerIndex = i
                    break
                }
            }
            if (dividerIndex == -1) return null

            // 表头行
            val headerLine = lines[dividerIndex - 1]
            val headers = splitRow(headerLine)
            if (headers.isEmpty()) return null

            val colCount = headers.size

            // 对齐方式解析
            val dividerLine = lines[dividerIndex]
            val dividerCols = splitRow(dividerLine)
            val alignments = (0 until colCount).map { i ->
                val col = dividerCols.getOrNull(i)?.trim() ?: ""
                when {
                    col.startsWith(":") && col.endsWith(":") -> Gravity.CENTER
                    col.endsWith(":") -> Gravity.END
                    else -> Gravity.START
                }
            }

            // 数据行
            val rows = mutableListOf<List<String>>()
            for (i in (dividerIndex + 1) until lines.size) {
                val rowLine = lines[i]
                if (!rowLine.contains("|") && rowLine.startsWith("#")) break
                val rawCols = splitRow(rowLine)
                if (rawCols.isEmpty()) continue
                // 自动补齐列数，保证矩形结构
                val rowCols = (0 until colCount).map { colIdx ->
                    rawCols.getOrNull(colIdx)?.trim() ?: ""
                }
                rows.add(rowCols)
            }

            return DemoTableData(headers, rows, alignments)
        }

        private fun splitRow(row: String): List<String> {
            val trimmed = row.trim()
            val withoutEdges = trimmed
                .removePrefix("|")
                .removeSuffix("|")
            if (withoutEdges.isBlank()) return emptyList()
            return withoutEdges.split("|").map { it.trim() }
        }

        fun isDividerRow(line: String): Boolean {
            val trimmed = line.trim()
            if (!trimmed.contains("-")) return false
            val clean = trimmed.replace("|", "").replace(":", "").replace(" ", "").replace("-", "")
            return clean.isEmpty()
        }
    }
}

/**
 * 原生优雅卡片式 Markdown 表格组件：
 * 1. 彻底根治 ReplacementSpan 测量滞后导致的行高跳跃与重叠死循环；
 * 2. 内嵌 HorizontalScrollView，宽表格支持横向滑动，单元格文字不再换行过度堆叠；
 * 3. 完美适配米白/深灰主题美学与精致清晰字体（12sp）。
 */
class DemoTableView(context: Context) : FrameLayout(context) {

    private val scrollView = HorizontalScrollView(context).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private val tableLayout = TableLayout(context).apply {
        isStretchAllColumns = true
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private var currentMarkdown: String = ""

    init {
        val radiusPx = dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(cardBgColor())
            setStroke(dp(1), strokeColor())
        }
        clipToOutline = true

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        addView(scrollView, lp)
        scrollView.addView(tableLayout)
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun cardBgColor(): Int {
        return if (ThemeManager.isDark) {
            Color.parseColor("#232220")
        } else {
            Color.parseColor("#FAF9F6")
        }
    }

    private fun strokeColor(): Int {
        return if (ThemeManager.isDark) {
            Color.parseColor("#383734")
        } else {
            Color.parseColor("#E6E4DE")
        }
    }

    private fun headerBgColor(): Int {
        return if (ThemeManager.isDark) {
            Color.parseColor("#2C2B29")
        } else {
            Color.parseColor("#F0EEE9")
        }
    }

    private fun oddRowBgColor(): Int {
        return if (ThemeManager.isDark) {
            Color.parseColor("#262523")
        } else {
            Color.parseColor("#F5F3EF")
        }
    }

    private fun dividerColor(): Int {
        return if (ThemeManager.isDark) {
            Color.parseColor("#383734")
        } else {
            Color.parseColor("#E5E2DC")
        }
    }

    /**
     * 绑定并渲染 Markdown 表格数据。
     */
    fun bind(markdown: String) {
        if (currentMarkdown == markdown) return
        currentMarkdown = markdown

        val data = DemoTableData.parse(markdown) ?: return
        tableLayout.removeAllViews()

        // 1. 渲染表头
        val headerRow = TableRow(context).apply {
            setBackgroundColor(headerBgColor())
        }
        for (i in data.headers.indices) {
            val headerText = data.headers[i]
            val gravity = data.alignments.getOrElse(i) { Gravity.START }
            val tv = createCellTextView(isHeader = true, gravity = gravity).apply {
                text = DemoMarkdownFormatter.toMarkdown(context, headerText)
            }
            headerRow.addView(tv)
        }
        tableLayout.addView(headerRow)

        // 2. 渲染表头下方细分割线
        tableLayout.addView(createHorizontalDivider())

        // 3. 渲染数据行
        for (rowIndex in data.rows.indices) {
            val rowData = data.rows[rowIndex]
            val row = TableRow(context).apply {
                val isOdd = rowIndex % 2 == 1
                if (isOdd) {
                    setBackgroundColor(oddRowBgColor())
                } else {
                    setBackgroundColor(Color.TRANSPARENT)
                }
            }
            for (colIndex in 0 until data.headers.size) {
                val cellText = rowData.getOrNull(colIndex) ?: ""
                val gravity = data.alignments.getOrElse(colIndex) { Gravity.START }
                val tv = createCellTextView(isHeader = false, gravity = gravity).apply {
                    text = DemoMarkdownFormatter.toMarkdown(context, cellText)
                }
                row.addView(tv)
            }
            tableLayout.addView(row)

            // 数据行之间的极细分割线（末尾除外）
            if (rowIndex < data.rows.size - 1) {
                tableLayout.addView(createHorizontalDivider())
            }
        }
    }

    private fun createHorizontalDivider(): View {
        return View(context).apply {
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(dividerColor())
        }
    }

    private fun createCellTextView(isHeader: Boolean, gravity: Int): TextView {
        return TextView(context).apply {
            val textColor = if (ThemeManager.isDark) Color.parseColor("#EDEBE8") else Color.parseColor("#1C1B1A")
            setTextColor(textColor)
            this.gravity = gravity or Gravity.CENTER_VERTICAL
            setPadding(
                dp(12),
                dp(if (isHeader) 8 else 7),
                dp(12),
                dp(if (isHeader) 8 else 7)
            )
            includeFontPadding = false
            if (isHeader) {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }
    }
}
