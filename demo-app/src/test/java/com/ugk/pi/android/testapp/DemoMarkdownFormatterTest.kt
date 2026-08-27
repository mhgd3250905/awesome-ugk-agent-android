package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Markdown 格式化工具及流式表格平稳化（stabilizeStreamingMarkdown）的正确性。
 */
class DemoMarkdownFormatterTest {

    @Test
    fun nonTableTextIsUntouched() {
        val raw = "这是一段普通的回答文本，没有任何表格。"
        val stabilized = DemoMarkdownFormatter.stabilizeStreamingMarkdown(raw)
        assertEquals(raw, stabilized)
    }

    @Test
    fun incompleteSeparatorIsUntouched() {
        val raw = "| 元素 | 描述 |\n| ---"
        val stabilized = DemoMarkdownFormatter.stabilizeStreamingMarkdown(raw)
        assertEquals(raw, stabilized)
    }

    @Test
    fun incompleteTableRowMissingClosingPipeIsStabilized() {
        val raw = "| 元素 | 描述 |\n| :--- | :--- |\n| 背景"
        val stabilized = DemoMarkdownFormatter.stabilizeStreamingMarkdown(raw)
        assertTrue("Stabilized output should close the cell and row", stabilized.contains("| 背景 |"))
        assertTrue("Stabilized output should end with newline", stabilized.endsWith("\n"))
    }

    @Test
    fun incompleteTableRowMissingColumnsIsPadded() {
        val raw = "| 方案 | 权限 | 兼容性 | 场景 |\n|---|---|---|---|\n| MediaStore"
        val stabilized = DemoMarkdownFormatter.stabilizeStreamingMarkdown(raw)
        val lastLine = stabilized.trimEnd().lines().last()
        assertTrue("Should contain cell text", lastLine.contains("MediaStore"))
        assertTrue("Last line should end with pipe", lastLine.endsWith("|"))
        val totalPipes = lastLine.count { it == '|' }
        assertTrue("Should have at least 4 column delimiters, actual: $totalPipes", totalPipes >= 5)
    }

    @Test
    fun completedTablePreservesData() {
        val raw = "| 方案 | 权限 |\n|---|---|\n| MediaStore | 无需 |\n| SAF | 无需 |"
        val stabilized = DemoMarkdownFormatter.stabilizeStreamingMarkdown(raw)
        assertTrue(stabilized.contains("MediaStore"))
        assertTrue(stabilized.contains("SAF"))
    }
}
