package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoCodeBlockParserTest {

    @Test
    fun testPlainTextReturnsSingleTextBlock() {
        val input = "这是普通的段落，没有代码块。"
        val blocks = DemoCodeBlockParser.splitBlocks(input)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is DemoContentBlock.Text)
        assertEquals("这是普通的段落，没有代码块。", (blocks[0] as DemoContentBlock.Text).markdown)
    }

    @Test
    fun testCodeBlockWithLanguage() {
        val input = """
            前置说明：
            ```bash
            adb shell dumpsys window | grep -E "mCurrentFocus"
            ```
            后置说明。
        """.trimIndent()

        val blocks = DemoCodeBlockParser.splitBlocks(input)
        assertEquals(3, blocks.size)

        assertTrue(blocks[0] is DemoContentBlock.Text)
        assertEquals("前置说明：", (blocks[0] as DemoContentBlock.Text).markdown)

        assertTrue(blocks[1] is DemoContentBlock.Code)
        val code = blocks[1] as DemoContentBlock.Code
        assertEquals("bash", code.language)
        assertEquals("adb shell dumpsys window | grep -E \"mCurrentFocus\"", code.code.trim())

        assertTrue(blocks[2] is DemoContentBlock.Text)
        assertEquals("后置说明。", (blocks[2] as DemoContentBlock.Text).markdown)
    }

    @Test
    fun testUnclosedCodeBlockDuringStreaming() {
        val input = """
            流式输出中：
            ```kotlin
            fun main() {
                println("hello")
        """.trimIndent()

        val blocks = DemoCodeBlockParser.splitBlocks(input)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is DemoContentBlock.Text)
        assertTrue(blocks[1] is DemoContentBlock.Code)
        val code = blocks[1] as DemoContentBlock.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("println(\"hello\")"))
    }

    @Test
    fun testTableBlockSplit() {
        val input = """
            这是表格前的说明：
            | 维度 | 内部存储 | 外部存储 |
            |---|---|---|
            | 权限 | 无需权限 | 需授权 |
            | 隐私 | Linux UID 强隔离 | 共享空间 |

            这是表格后的总结。
        """.trimIndent()

        val blocks = DemoCodeBlockParser.splitBlocks(input)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is DemoContentBlock.Text)
        assertEquals("这是表格前的说明：", (blocks[0] as DemoContentBlock.Text).markdown)

        assertTrue(blocks[1] is DemoContentBlock.Table)
        val table = blocks[1] as DemoContentBlock.Table
        assertTrue(table.tableMarkdown.contains("内部存储"))

        val data = DemoTableData.parse(table.tableMarkdown)
        org.junit.Assert.assertNotNull(data)
        assertEquals(3, data!!.headers.size)
        assertEquals("维度", data.headers[0])
        assertEquals("内部存储", data.headers[1])
        assertEquals("外部存储", data.headers[2])
        assertEquals(2, data.rows.size)
        assertEquals("无需权限", data.rows[0][1])

        assertTrue(blocks[2] is DemoContentBlock.Text)
        assertEquals("这是表格后的总结。", (blocks[2] as DemoContentBlock.Text).markdown)
    }
}
