package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
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

    @Test fun compilationUsesActualCorrectionsErrorsAndReviewedDelivery() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "测试")
        store.update(id) { it.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("1", "打开计时器", "failed", "没有成功"),
            DemoTeachingSegment("2", "纠正：我要秒表", "completed", "秒表已显示")
        )) }
        val stages = mutableListOf<DemoTeachingSopSkill.Stage>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                stages += request.stage()
                val text = request.messages.filterIsInstance<AgentMessage.User>().joinToString { it.content }
                assertTrue(text.contains("纠正：我要秒表")); assertTrue(text.contains("failed"))
                return request.respond()
            }
        }
        val guide = DemoTeachingCompiler({ provider }, store, ::loadTeachingSkill).compile(store.read(id)!!)
        assertEquals(listOf(DemoTeachingSopSkill.Stage.WRITE_SOP, DemoTeachingSopSkill.Stage.REVIEW_SOP), stages)
        assertEquals("秒表", guide.title)
        assertTrue(guide.document.contains("用户把计时器纠正为秒表"))
        assertTrue(guide.document.contains("已核对失败记录和用户纠正"))
        assertNull(store.read(id)!!.guide) // Generation alone is not a durable acceptance or an execution.
    }

    @Test fun longTeachingKeepsAllStepEvidenceAndFinalCorrectionWithinRequestBudget() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "查看秒表")
        val images = (1..3).map { store.saveImage(id, byteArrayOf(1, 2, 3)) }
        val segments = (1..3).map { segment -> largeSegment(segment, 10).let {
            it.copy(actions = it.actions.mapIndexed { index, action ->
                if (index == 0) action.copy(beforeImage = images[segment - 1]) else action
            })
        } }.mapIndexed { index, segment -> if (index == 2)
            segment.copy(instruction = "纠正：只查看秒表，不要启动，也不要继续计时器") else segment }
        store.update(id) { it.copy(status = "finished", segments = segments) }
        val before = DemoTeachingStore.encode(store.read(id)!!).toString()
        val requests = mutableListOf<ModelRequest>()
        val progress = mutableListOf<String>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests += request
                assertTrue(request.userText().length <= 200_000)
                return request.respond("保留本批步骤与失败证据")
            }
        }
        val compilation = DemoTeachingCompiler({ provider }, store, ::loadTeachingSkill).compileWithReport(store.read(id)!!) { progress += it }
        val sourceRequests = requests.filter { it.stage() == DemoTeachingSopSkill.Stage.STEP_NOTES }
        assertTrue(sourceRequests.size >= 3)
        val allSource = sourceRequests.joinToString("\n") { it.userText() }
        segments.forEachIndexed { index, segment ->
            assertTrue(allSource.contains(segment.instruction))
            assertTrue(allSource.contains(segment.reply))
            (1..10).forEach { action -> assertTrue(allSource.contains("MIDDLE_${index + 1}_$action")) }
        }
        assertTrue(allSource.contains("失败原因仍待确认"))
        assertEquals(3, sourceRequests.sumOf { r -> r.messages.filterIsInstance<AgentMessage.User>().sumOf { it.images.size } })
        assertEquals(0, requests.last().messages.filterIsInstance<AgentMessage.User>().sumOf { it.images.size })
        assertTrue(requests.last().userText().contains(segments.last().instruction))
        assertTrue(requests.last().userText().contains("step_evidence_summary"))
        assertEquals(requests.count { it.isNotesRequest() }, compilation.summaryRequests)
        assertTrue(progress.any { it.contains("填写步骤笔记") })
        assertEquals(before, DemoTeachingStore.encode(store.read(id)!!).toString())
    }

    @Test fun oversizedIndividualEvidenceIsSplitWithoutLosingMiddleContent() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "长单步")
        val markers = (1..40).map { "完整证据标记$it：😀" }
        val largeInput = markers.joinToString("") { "x".repeat(9_000) + it }
        store.update(id) { it.copy(status = "finished", segments = listOf(DemoTeachingSegment(
            "segment", "保留中间纠正", "failed", "没有验证成功", listOf(DemoTeachingAction(
                "action", "custom_tool", buildJsonObject { put("longEvidence", largeInput) }, "失败未恢复", true
            ))
        ))) }
        val sources = mutableListOf<String>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                assertTrue(request.userText().length <= 200_000)
                if (request.stage() == DemoTeachingSopSkill.Stage.STEP_NOTES) sources += request.userText()
                return request.respond("片段证据，尚待合并")
            }
        }
        DemoTeachingCompiler({ provider }, store, ::loadTeachingSkill).compile(store.read(id)!!)
        assertTrue(sources.size >= 3)
        markers.forEach { marker -> assertTrue(sources.any { it.contains(marker) }) }
        assertTrue(sources.joinToString().contains("原文片段"))
        assertTrue(sources.joinToString().contains("失败未恢复"))
        assertEquals(largeInput, store.read(id)!!.segments.single().actions.single().input.getValue("longEvidence").jsonPrimitive.content)
    }

    @Test fun manyStepSummariesAreMergedWithoutDroppingLastSourceReference() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "很多教学段")
        store.update(id) { it.copy(status = "finished", segments = (1..24).map { largeSegment(it, 8) }) }
        var initialBatches = 0
        var mergeBatches = 0
        var finalText = ""
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                val text = request.userText()
                assertTrue(text.length <= 200_000)
                return when (request.stage()) {
                    DemoTeachingSopSkill.Stage.STEP_NOTES -> {
                        initialBatches++
                        request.respond("事实" + "x".repeat(9_000))
                    }
                    DemoTeachingSopSkill.Stage.MERGE_NOTES -> {
                        mergeBatches++
                        assertTrue(text.contains("step_evidence_summary"))
                        request.respond("已合并本批有来源的教学证据")
                    }
                    DemoTeachingSopSkill.Stage.WRITE_SOP -> {
                        finalText = text
                        request.respond()
                    }
                    DemoTeachingSopSkill.Stage.REVIEW_SOP -> request.respond()
                }
            }
        }
        val result = DemoTeachingCompiler({ provider }, store, ::loadTeachingSkill).compileWithReport(store.read(id)!!)
        assertTrue(initialBatches >= 20)
        assertTrue(mergeBatches > 0)
        assertEquals(initialBatches + mergeBatches, result.summaryRequests)
        assertTrue(finalText.contains("segment-1:instruction"))
        assertTrue(finalText.contains("segment-24:action-8"))
    }

    @Test fun truncatedIntermediateNotesStopBeforeFinalGuideAndPreserveRecord() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "部分失败")
        store.update(id) { it.copy(status = "finished", segments = (1..3).map { largeSegment(it, 10) }) }
        val before = DemoTeachingStore.encode(store.read(id)!!).toString()
        var requests = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests++
                assertEquals(DemoTeachingSopSkill.Stage.STEP_NOTES, request.stage())
                return request.respond("第 $requests 批证据").copy(stopReason = if (requests == 1) "stop" else "length")
            }
        }
        val failure = runCatching { DemoTeachingCompiler({ provider }, store, ::loadTeachingSkill).compile(store.read(id)!!) }.exceptionOrNull()
        assertEquals("OUTPUT_TRUNCATED", (failure as? DemoTeachingCompileException)?.code)
        assertEquals(2, requests)
        assertEquals(before, DemoTeachingStore.encode(store.read(id)!!).toString())
    }

    @Test fun cancellationStopsFurtherBatchesWithoutProducingGuide() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "取消")
        store.update(id) { it.copy(status = "finished", segments = (1..3).map { largeSegment(it, 10) }) }
        var requests = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                requests++
                throw CancellationException("用户取消")
            }
        }
        val failure = runCatching { DemoTeachingCompiler({ provider }, store, ::loadTeachingSkill).compile(store.read(id)!!) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, requests)
        assertNull(store.read(id)!!.guide)
    }

    private fun loadTeachingSkill() = DemoTeachingSopSkill.load { path ->
        java.io.File("src/main/assets", path).readText(Charsets.UTF_8)
    }

    private fun largeSegment(number: Int, actionCount: Int) = DemoTeachingSegment(
        "segment-$number", "第${number}段指令", "completed", "第${number}段回答仅为Agent声明",
        (1..actionCount).map { action -> DemoTeachingAction(
            "action-$number-$action", "custom_observation", buildJsonObject { put("step", action) },
            "x".repeat(5_900) + "MIDDLE_${number}_$action" + "y".repeat(5_900),
            isError = action == 2, gaps = if (action == 2) listOf("失败原因仍待确认") else emptyList()
        ) }
    )

    private val document = """
        # 秒表

        ## 目标
        查看秒表，不启动计时。

        ## 操作步骤
        1. 打开时钟，确认当前页面。
        2. 切换到秒表，确认秒表页面已显示，不点击开始。

        ## 纠正与注意事项
        - 用户把计时器纠正为秒表，前段打开计时器失败，不能当作成功路径。

        ## 完成检查
        - 看到秒表页面。

        ## 待核实
        - 未开始计时，执行时仍需现场确认当前页面。
    """.trimIndent()

    private fun ModelRequest.respond(fact: String = "保留本批步骤与失败证据"): ModelResponse = when (stage()) {
        DemoTeachingSopSkill.Stage.STEP_NOTES, DemoTeachingSopSkill.Stage.MERGE_NOTES -> {
            assertTrue(tools.isEmpty())
            ModelResponse(content = "## 步骤笔记\n\n$fact\n\n## 证据缺口\n\n仅为证据摘要，尚未验证任务完成。")
        }
        DemoTeachingSopSkill.Stage.WRITE_SOP -> {
            assertTrue(tools.isEmpty())
            ModelResponse(content = document)
        }
        DemoTeachingSopSkill.Stage.REVIEW_SOP -> {
            assertEquals(setOf("read_teaching_evidence", "submit_reviewed_sop"), tools.map { it.name }.toSet())
            ModelResponse(content = "", toolCalls = listOf(ToolCall(
                "review-delivery", "submit_reviewed_sop", buildJsonObject {
                    put("document", document)
                    put("reviewNotes", "已核对失败记录和用户纠正；实际执行仍需确认秒表页面，不能启动计时。")
                }
            )))
        }
    }

    private fun ModelRequest.userText() = messages.filterIsInstance<AgentMessage.User>().joinToString("\n") { it.content }
    private fun ModelRequest.stage() = DemoTeachingSopSkill.Stage.entries.single { stage ->
        messages.filterIsInstance<AgentMessage.System>().any { it.content.contains("## 当前阶段：${stage.label}") }
    }
    private fun ModelRequest.isNotesRequest() = stage() in setOf(
        DemoTeachingSopSkill.Stage.STEP_NOTES, DemoTeachingSopSkill.Stage.MERGE_NOTES
    )
}
