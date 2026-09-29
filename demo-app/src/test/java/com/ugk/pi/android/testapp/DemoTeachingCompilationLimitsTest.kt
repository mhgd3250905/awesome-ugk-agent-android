package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun stageOf(request: ModelRequest): DemoTeachingSopSkill.Stage =
    DemoTeachingSopSkill.Stage.entries.single { stage ->
        request.messages.filterIsInstance<AgentMessage.System>()
            .any { it.content.contains("## 当前阶段：" + stage.label) }
    }

/**
 * Limits of the teaching compilation pipeline: what a stalled merge costs, how much evidence one
 * request may carry, and whether a durable claim or a full record can still be got out of.
 */
class DemoTeachingCompilationLimitsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun skill() = DemoTeachingSopSkill.load { path ->
        java.io.File("src/main/assets", path).readText(Charsets.UTF_8)
    }

    private val document = """
        # 秒表

        ## 目标
        查看秒表，不启动计时。

        ## 操作步骤
        1. 打开时钟，确认当前页面。
        2. 切换到秒表，确认秒表页面已显示，不点击开始。

        ## 纠正与注意事项
        - 无。

        ## 完成检查
        - 看到秒表页面。

        ## 待核实
        - 执行时仍需现场确认当前页面。
    """.trimIndent()

    private fun respond(request: ModelRequest, notes: String): ModelResponse = when (stageOf(request)) {
        DemoTeachingSopSkill.Stage.REVIEW_SOP -> ModelResponse(content = "", toolCalls = listOf(
            ToolCall("delivery", "submit_reviewed_sop", buildJsonObject {
                put("document", document); put("reviewNotes", "已核对失败记录与用户纠正。")
            })
        ))
        DemoTeachingSopSkill.Stage.WRITE_SOP -> ModelResponse(content = document)
        else -> ModelResponse(content = notes)
    }

    private fun segment(number: Int, actions: Int, filler: Int) = DemoTeachingSegment(
        "segment-$number", "第${number}段指令", "completed", "第${number}段回答",
        (1..actions).map { action -> DemoTeachingAction(
            "action-$number-$action", "custom_observation", buildJsonObject { put("step", action) },
            "x".repeat(filler)
        ) })

    private fun record(id: String, store: DemoTeachingStore, segments: Int, actions: Int, filler: Int): Throwable? =
        runCatching {
            store.update(id) { it.copy(status = "finished", segments = (1..segments).map { number ->
                segment(number, actions, filler)
            }) }
        }.exceptionOrNull()

    private fun finishedRecord(store: DemoTeachingStore, id: String, title: String) {
        store.create(id, title)
        assertNull(record(id, store, 3, 10, 6_000))
    }

    /** A merge round that cannot shrink the material must stop, not be paid for again. */
    @Test fun stalledMergeRoundStopsWithoutDoublingPaidRequests() = runBlocking {
        val root = temporary.newFolder()
        val store = DemoTeachingStore(root)
        val id = UUID.randomUUID().toString()
        finishedRecord(store, id, "长摘要")
        val counts = mutableMapOf<String, Int>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                val stage = stageOf(request).name
                counts[stage] = (counts[stage] ?: 0) + 1
                return respond(request, "## 步骤笔记\n\n" + "x".repeat(60_000))
            }
        }
        val failure = runCatching {
            DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!)
        }.exceptionOrNull()
        val mergeCalls = counts["MERGE_NOTES"] ?: 0
        assertEquals("a stalled round may cost one batch set, not an exponential ladder",
            true, mergeCalls <= 12)
        assertEquals("EVIDENCE_MERGE_NOT_SHRINKING", (failure as? DemoTeachingCompileException)?.code)
        // The guard must also be legible afterwards: the diagnostic carries the code, not a blank.
        val diagnostics = java.io.File(root, "$id/compilation-diagnostics.jsonl").readText(Charsets.UTF_8)
        assertTrue("diagnostics must record why the merge stopped: $diagnostics",
            diagnostics.contains("\"failureCode\":\"EVIDENCE_MERGE_NOT_SHRINKING\""))
    }

    /** Counter-example on main: a merge that does shrink still delivers. */
    @Test fun convergingMergeRoundsStillDeliverAGuide() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "很多教学段")
        assertNull(record(id, store, 24, 8, 12_000))
        var mergeCalls = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                if (stageOf(request) == DemoTeachingSopSkill.Stage.MERGE_NOTES) mergeCalls++
                return respond(request, "## 步骤笔记\n\n" + "x".repeat(9_000))
            }
        }
        val guide = DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!)
        assertTrue("expected more than one merge round", mergeCalls > 0)
        assertTrue(guide.document.contains("查看秒表"))
    }

    /** Screenshots reach the wire as Base64 text, so the request budget must count that. */
    @Test fun screenshotPayloadIsBoundedByEncodedSize() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "大截图")
        val raw = ByteArray(2_000_000) { (it % 251).toByte() }
        val images = (1..6).map { store.saveImage(id, raw) }
        store.update(id) { record -> record.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("s1", "指令", "completed", "回答", images.mapIndexed { index, name ->
                DemoTeachingAction("a$index", "custom_observation", buildJsonObject { put("step", index) },
                    "结果$index", isError = false, afterImage = name) })
        )) }
        val imageChars = mutableListOf<Int>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                imageChars += request.messages.filterIsInstance<AgentMessage.User>()
                    .sumOf { message -> message.images.sumOf { it.base64Data.length } }
                return respond(request, "笔记")
            }
        }
        runCatching { DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!) }
        // Stated as a literal, not read back from the constant under test: asserting against the code's
        // own budget would pass even with the budget removed.
        assertTrue("the evidence must reach the model at all: $imageChars", imageChars.isNotEmpty())
        imageChars.forEach { chars -> assertTrue("one request carried $chars characters of image text", chars <= 4_000_000) }
    }

    /** Counter-example: legitimate screenshots still all ride one request, unchanged. */
    @Test fun typicalScreenshotsAllStillRideTheDraftRequest() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "普通截图")
        val raw = ByteArray(300_000) { (it % 251).toByte() }
        val images = (1..6).map { store.saveImage(id, raw) }
        store.update(id) { record -> record.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("s1", "指令", "completed", "回答", images.mapIndexed { index, name ->
                DemoTeachingAction("a$index", "custom_observation", buildJsonObject { put("step", index) },
                    "结果$index", isError = false, afterImage = name) })
        )) }
        val requests = mutableListOf<ModelRequest>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += request
                return respond(request, "笔记")
            }
        }
        DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!)
        val draft = requests.first { stageOf(it) == DemoTeachingSopSkill.Stage.WRITE_SOP }
        assertEquals(6, draft.messages.filterIsInstance<AgentMessage.User>().sumOf { it.images.size })
    }

    /** A claim must survive every exit of the compilation body, including an Error. */
    @Test fun compilationClaimIsReleasedEvenWhenTheBodyRaisesAnError() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "整理中")
        assertNull(record(id, store, 1, 1, 10))
        store.update(id) { it.copy(status = "finished") }
        val error = runCatching {
            DemoTeachingCompilationClaim.withClaim(store, id) { throw OutOfMemoryError("image payloads") }
        }.exceptionOrNull()
        assertTrue("the body's Error must keep propagating", error is OutOfMemoryError)
        assertEquals("failed", store.read(id)!!.compilationStatus)
        // The record must be usable again in the same process: compile, resume and delete all
        // refused while the claim leaked.
        val second = runCatching { DemoTeachingCompilationClaim.withClaim(store, id) { it } }
        assertFalse(second.isFailure)
        store.update(id) { it.copy(compilationStatus = "not_started", status = "finished") }
        store.resumeTeaching(id)
        store.update(id) { it.copy(status = "finished", compilationStatus = "not_started") }
        store.delete(id)
    }

    /** A refused claim must not touch the claim another compilation holds. */
    @Test fun refusedClaimKeepsTheHoldersStatus() {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "占用")
        assertNull(record(id, store, 1, 1, 10))
        store.update(id) { it.copy(status = "finished", compilationStatus = "compiling") }
        val failure = runCatching { DemoTeachingCompilationClaim.claim(store, id) }.exceptionOrNull()
        assertEquals("教学记录正在使用，请稍后重试", failure?.message)
        assertEquals("compiling", store.read(id)!!.compilationStatus)
    }

    /** Growing past the durable byte limit is a capacity stop, not an internal save failure. */
    @Test fun oversizedGrowthIsRefusedAsCapacityAndTheRecordStaysUsable() {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "容量")
        assertNull(record(id, store, 5, 10, 12_000))
        var refusal: Throwable? = null
        var accepted = 5
        while (refusal == null && accepted < 80) {
            accepted++
            val error = record(id, store, accepted, 10, 12_000) ?: continue
            refusal = error
        }
        assertTrue("expected a capacity refusal while growing evidence, got $refusal",
            refusal is DemoTeachingCapacityException)
        assertEquals("本次教学记录已达上限，请结束后开始新教学", refusal?.message)
        // Everything a stuck session needs must still work after the refusal.
        store.update(id) { it.copy(status = "finished") }
        assertEquals("finished", store.read(id)!!.status)
        store.delete(id)
    }

    /** Counter-example: many small actions still stop at the advertised count ceiling. */
    @Test fun shortActionsStillStopAtTheCountCeiling() {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "计数上限")
        assertNull(record(id, store, 60, 10, 20))
        val failure = record(id, store, 61, 10, 20)
        assertTrue("count ceiling must report capacity, got $failure",
            failure?.message == "本次教学记录已达上限，请结束后开始新教学")
    }

    /** Preconditions report their own codes so a failed compilation is diagnosable. */
    @Test fun compilationPreconditionsCarryFailureCodes() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "未结束")
        store.update(id) { it.copy(status = "active", segments = listOf(segment(1, 1, 10))) }
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse = respond(request, "笔记")
        }
        val compiler = DemoTeachingCompiler({ provider }, store, ::skill)
        val active = runCatching { compiler.compile(store.read(id)!!) }.exceptionOrNull()
        assertEquals("TEACHING_NOT_FINISHED", (active as? DemoTeachingCompileException)?.code)
        assertEquals("请先完成至少一段教学并结束", active?.message)
    }

    /** A stream that ends before the completed response reports an incomplete stream. */
    @Test fun streamWithoutCompletedResponseReportsIncomplete() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "断流")
        store.update(id) { it.copy(status = "finished", segments = listOf(segment(1, 1, 10))) }
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse = respond(request, "笔记")
            override fun generateStream(request: ModelRequest): Flow<ModelStreamChunk> = flow {
                emit(ModelStreamChunk.ContentDelta("半句话"))
            }
        }
        val failure = runCatching {
            DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!)
        }.exceptionOrNull()
        assertEquals("STREAM_INCOMPLETE", (failure as? DemoTeachingCompileException)?.code)
    }
}
