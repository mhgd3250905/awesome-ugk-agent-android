package com.ugk.pi.android.testapp

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoTeachingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedTeachingPreservesCorrectionsAndEvidenceWithoutResuming() {
        val root = temporary.newFolder()
        val store = DemoTeachingStore(root)
        val id = UUID.randomUUID().toString()
        store.create(id, "切换时钟标签")
        store.update(id) { it.copy(segments = listOf(
            DemoTeachingSegment("one", "打开时钟", "completed", "已打开"),
            DemoTeachingSegment("two", "刚才说错了，改为秒表", actions = listOf(DemoTeachingAction("a", "screen_perform_action", buildJsonObject {}, result = "正在执行")))
        )) }
        val recovered = DemoTeachingStore(root).list().single()
        assertEquals("interrupted", recovered.status)
        assertEquals("completed", recovered.segments.first().status)
        assertEquals("interrupted", recovered.segments.last().status)
        assertEquals("刚才说错了，改为秒表", recovered.segments.last().instruction)
        assertEquals("正在执行", recovered.segments.last().actions.single().result)
    }

    @Test fun corruptedRecordIsNotOverwritten() {
        val root = temporary.newFolder(); val store = DemoTeachingStore(root)
        val id = UUID.randomUUID().toString(); store.create(id, "教学")
        val file = File(root, "$id/record.json"); file.writeText("broken original")
        assertNull(store.read(id))
        assertTrue(runCatching { store.update(id) { it.copy(title = "new") } }.isFailure)
        assertEquals("broken original", file.readText())
        assertNull(store.imageFile(id, "../../secret"))
    }

    @Test fun durableEvidenceOmitsInputSecretsAndTransientModelContent() {
        val input = DemoTeachingEvidence.input(ToolCall("a", "screen_perform_action", buildJsonObject {
            put("action", "set_text"); put("text", "private text"); put("apiKey", "secret key")
        })).toString()
        assertFalse(input.contains("private text")); assertFalse(input.contains("secret key"))
        val result = DemoTeachingEvidence.result(ToolResult("a", "screen_capture_visual", "public evidence",
            transientModelContent = "secret clipboard value"))
        assertEquals("public evidence", result)
        assertFalse(DemoTeachingEvidence.result(ToolResult("a", "terminal_bash_execute", "private terminal output")).contains("private terminal output"))
    }

    @Test fun guidePersistsSeparatelyFromRawSteps() {
        val store = DemoTeachingStore(temporary.newFolder()); val id = UUID.randomUUID().toString()
        store.create(id, "教学")
        store.update(id) { it.copy(status = "finished", segments = listOf(DemoTeachingSegment("1", "打开时钟", "completed", "完成"))) }
        val guide = DemoTeachingGuide("时钟", "查看秒表", emptyList(), listOf("打开时钟，确认首页出现"), emptyList(), listOf("秒表标签可见"), listOf("未验证开始计时"))
        store.saveGuide(id, guide)
        val result = store.read(id)!!
        assertEquals(guide, result.guide)
        assertEquals("打开时钟", result.segments.single().instruction)
        assertTrue(guide.readableText().contains("未验证开始计时"))
    }
}
