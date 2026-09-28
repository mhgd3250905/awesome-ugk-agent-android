package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoTeachingCompilerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val valid = """{"title":"秒表","goal":"查看秒表","prerequisites":[],"steps":["打开时钟后切换秒表"],"corrections":["用户把计时器纠正为秒表"],"completionChecks":["看到秒表页面"],"uncertainties":["未开始计时"]}"""

    @Test fun rejectedTruncatedOrExecutableOutputsCannotBecomeGuide() {
        assertTrue(runCatching { DemoTeachingCompiler.parse("{\"steps\":[]}") }.isFailure)
        assertTrue(runCatching { DemoTeachingCompiler.parse(valid.dropLast(1) + ",\"execute\":true}") }.isFailure)
        assertEquals("用户把计时器纠正为秒表", DemoTeachingCompiler.parse(valid).corrections.single())
    }

    @Test fun searchMetadataIsOptionalForLegacyButValidatedWhenPresent() {
        val indexed = valid.dropLast(1) + ",\"intentAliases\":[\"查看秒表\"],\"targetApps\":[\"时钟\"],\"notApplicable\":[\"启动计时\"]}"
        val parsed = DemoTeachingCompiler.parse(indexed)
        assertEquals(listOf("查看秒表"), parsed.intentAliases)
        assertEquals(listOf("启动计时"), parsed.notApplicable)
        assertTrue(runCatching { DemoTeachingCompiler.parse(valid.dropLast(1) + ",\"intentAliases\":[123]}") }.isFailure)
    }

    @Test fun compilationUsesActualCorrectionsErrorsAndNoTools() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "测试")
        store.update(id) { it.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("1", "打开计时器", "failed", "没有成功"),
            DemoTeachingSegment("2", "纠正：我要秒表", "completed", "秒表已显示")
        )) }
        var observed = false
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                observed = true
                assertTrue(request.tools.isEmpty())
                val text = request.messages.filterIsInstance<AgentMessage.User>().joinToString { it.content }
                assertTrue(text.contains("纠正：我要秒表")); assertTrue(text.contains("failed"))
                return ModelResponse(content = valid)
            }
        }
        val guide = DemoTeachingCompiler({ provider }, store).compile(store.read(id)!!)
        assertTrue(observed); assertEquals("秒表", guide.title)
        assertNull(store.read(id)!!.guide) // Generation alone is not a durable acceptance or an execution.
    }
}
