package com.ugk.pi.android.testapp

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemoTeachingExperienceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun guide(title: String = "时钟秒表", aliases: List<String> = emptyList(), apps: List<String> = emptyList()) =
        DemoTeachingGuide(title, title, emptyList(), listOf("观察当前页面"), emptyList(), listOf("检查结果"), emptyList(), aliases, apps)
    private fun add(store: DemoTeachingStore, guide: DemoTeachingGuide = guide()): String {
        val id = UUID.randomUUID().toString()
        store.create(id, guide.title)
        store.update(id) { it.copy(status = "finished") }
        store.saveGuide(id, guide)
        return id
    }

    @Test fun deleteRemovesOnlySelectedRecordAndRejectsActiveOrCompilingRecords() {
        val root = temporary.newFolder(); val store = DemoTeachingStore(root)
        val id = add(store); val other = add(store)
        store.saveImage(id, byteArrayOf(1, 2, 3))
        store.recordUsage(id, 1, "success", "使用记录")
        store.delete(id)
        assertNull(store.read(id)); assertFalse(File(root, id).exists())
        assertNotNull(store.read(other))
        store.update(other) { it.copy(compilationStatus = "compiling") }
        assertTrue(runCatching { store.delete(other) }.isFailure)
        store.update(other) { it.copy(compilationStatus = "completed", status = "active") }
        assertTrue(runCatching { store.delete(other) }.isFailure)
        assertTrue(runCatching { store.delete("../outside") }.isFailure)
        assertNotNull(store.read(other))
    }

    @Test fun resumeKeepsEvidenceAndInvalidatesPriorGuideUntilRecompiled() {
        val store = DemoTeachingStore(temporary.newFolder()); val id = add(store)
        val image = store.saveImage(id, byteArrayOf(1, 2, 3))
        store.setAvailability(id, "available")
        store.update(id) { it.copy(status = "interrupted", segments = listOf(DemoTeachingSegment("old", "用户纠正：检查而非安装", "running"))) }
        val oldGuide = store.read(id)!!.guide
        store.resumeTeaching(id)
        val resumed = store.read(id)!!
        assertEquals("active", resumed.status)
        assertEquals("interrupted", resumed.segments.single().status)
        assertEquals(oldGuide, resumed.guide)
        assertEquals(2, resumed.guideRevision)
        assertEquals("not_started", resumed.compilationStatus)
        assertNotNull(store.imageFile(id, image))
        assertTrue(store.searchGuides("时钟秒表").isEmpty())
        assertTrue(runCatching { store.resumeTeaching(id) }.isFailure)
        store.update(id) { it.copy(status = "finished") }
        assertTrue(store.searchGuides("时钟秒表").isEmpty())
        store.saveGuide(id, guide())
        assertEquals(id, store.searchGuides("时钟秒表").single().id)
    }

    @Test fun legacyRecordDefaultsToUnvalidatedRevisionZeroAndRetainsEvidence() {
        val root = temporary.newFolder(); val store = DemoTeachingStore(root); val id = add(store)
        val file = File(root, "$id/record.json")
        val modern = Json.parseToJsonElement(file.readText()).jsonObject
        val legacyGuide = JsonObject(modern.getValue("guide").jsonObject.filterKeys {
            it !in setOf("intentAliases", "targetApps", "notApplicable")
        })
        file.writeText(JsonObject(modern.filterKeys { it !in setOf("guideRevision", "availability", "compilationStatus", "usageHistory") }
            + ("guide" to legacyGuide)).toString())
        val migrated = DemoTeachingStore(root).read(id)!!
        assertEquals(0, migrated.guideRevision)
        assertEquals("pending_validation", migrated.availability)
        assertEquals("completed", migrated.compilationStatus)
        assertTrue(migrated.guide!!.intentAliases.isEmpty())
        assertTrue(migrated.usageHistory.isEmpty())
        store.saveGuide(id, guide())
        assertEquals(1, store.read(id)!!.guideRevision)
    }

    @Test fun recompilationInvalidatesValidationAndRejectsStaleUsageWithoutChangingFile() {
        val root = temporary.newFolder(); val store = DemoTeachingStore(root); val id = add(store)
        store.setAvailability(id, "available")
        store.recordUsage(id, 1, "success", "已确认")
        store.saveGuide(id, guide("新的时钟经验"))
        val before = File(root, "$id/record.json").readText()
        assertTrue(runCatching { store.recordUsage(id, 1, "needs_revision", "旧版本反馈") }.isFailure)
        assertEquals(before, File(root, "$id/record.json").readText())
        val result = DemoTeachingStore(root).read(id)!!
        assertEquals(2, result.guideRevision)
        assertEquals("pending_validation", result.availability)
        assertEquals(1, result.usageHistory.single().revision)
    }

    @Test fun onlyExplicitRevisionFeedbackDowngradesAndSuccessDoesNotValidate() {
        val store = DemoTeachingStore(temporary.newFolder()); val id = add(store)
        store.recordUsage(id, 1, "success", "完成")
        assertEquals("pending_validation", store.read(id)!!.availability)
        store.setAvailability(id, "available")
        store.recordUsage(id, 1, "network_error", "网络不可用")
        store.recordUsage(id, 1, "failure", "权限未授予")
        assertEquals("available", store.read(id)!!.availability)
        store.recordUsage(id, 1, "needs_revision", "按钮已经移动")
        assertEquals("needs_revision", store.read(id)!!.availability)
        assertTrue(store.searchGuides("秒表").isEmpty())
        store.setAvailability(id, "disabled")
        store.recordUsage(id, 1, "needs_revision", "仍过期")
        assertEquals("disabled", store.read(id)!!.availability)
    }

    @Test fun retrievalRanksAliasesAndAppsRejectsWeakMatchesAndLimitsResults() {
        val store = DemoTeachingStore(temporary.newFolder())
        val titleMatch = add(store, guide("开始跑步计时"))
        val aliasMatch = add(store, guide("秒表", listOf("跑步计时"), listOf("com.example.clock")))
        assertEquals(aliasMatch, store.searchGuides("跑步计时").first().id)
        assertTrue(store.searchGuides("跑步计时").any { it.id == titleMatch })
        assertEquals(aliasMatch, store.searchGuides("com.example.clock").first().id)
        assertTrue(store.searchGuides("无关食谱").isEmpty())
        assertTrue(store.searchGuides("观察").isEmpty()) // A single procedural bigram is weak evidence.
        assertTrue(store.searchGuides("   ").isEmpty())
        repeat(6) { add(store, guide("跑步计时 $it")) }
        assertEquals(5, store.searchGuides("跑步计时").size)
        store.setAvailability(aliasMatch, "disabled")
        assertFalse(store.searchGuides("com.example.clock").any { it.id == aliasMatch })
        store.update(titleMatch) { it.copy(status = "active") }
        assertFalse(store.searchGuides("跑步计时").any { it.id == titleMatch })
    }
}
