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
            DemoTeachingCompiler({ provider }, store, ::skill).compileWithReport(store.read(id)!!)
        }.exceptionOrNull()
        val mergeCalls = counts["MERGE_NOTES"] ?: 0
        val paid = (failure as? DemoTeachingCompileException)
        assertEquals("EVIDENCE_MERGE_NO_PROGRESS", paid?.code)
        assertEquals("教学证据暂时无法合并，原始记录已保留", paid?.message)
        assertTrue("a stalled merge must not ladder: $mergeCalls merge calls", mergeCalls <= 12)
        // The record remains usable and the failure is legible afterwards.
        assertNull(store.read(id)!!.guide)
        // The guard must also be legible: the diagnostic carries the code, not a blank.
        val diagnostics = java.io.File(root, "$id/compilation-diagnostics.jsonl").readText(Charsets.UTF_8)
        assertTrue("diagnostics must record why the merge stopped: $diagnostics",
            diagnostics.contains("\"failureCode\":\"EVIDENCE_MERGE_NO_PROGRESS\""))
    }

    /**
     * A round that halves the batch count while the re-summarised text does not shrink is
     * progress, not a stall: comparing the batch axis against the pre-round count is what
     * keeps it alive. Judged against the post-round count (which is groups' own mapping)
     * the batch axis would compare groups with itself, always pass, and kill this round.
     */
    @Test fun mergeRoundThatShrinksBatchesButNotTextIsNotKilled() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "先并批再缩文")
        assertNull(record(id, store, 24, 8, 12_000))
        var mergeCalls = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                val stage = stageOf(request)
                if (stage == DemoTeachingSopSkill.Stage.MERGE_NOTES) {
                    mergeCalls++
                    val material = request.messages.filterIsInstance<AgentMessage.User>()
                        .joinToString { it.content }
                    // Round 1 re-summarises the step notes without shortening them (the
                    // material still carries the round-1 marker); every later part,
                    // including the markerless head of a split round-1 note, converges.
                    return if (material.contains("MERGE1")) {
                        ModelResponse(content = "## 步骤笔记\n\n" + "x".repeat(58_000) + "\nMERGE2\n")
                    } else {
                        ModelResponse(content = "## 步骤笔记\n\n" + "y".repeat(1_000))
                    }
                }
                if (stage == DemoTeachingSopSkill.Stage.STEP_NOTES) {
                    return ModelResponse(content = "## 步骤笔记\n\n" + "x".repeat(9_000) + "\nMERGE1\n")
                }
                return respond(request, "笔记")
            }
        }
        val guide = DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!)
        assertTrue("the surviving round must be followed by a converging one: $mergeCalls",
            mergeCalls >= 2)
        assertTrue(guide.document.contains("查看秒表"))
    }

    /** Screenshots reach the wire as Base64 text, so the request budget must count that. */
    @Test fun screenshotPayloadIsBoundedByEncodedSize() = runBlocking {
        val root = temporary.newFolder()
        val store = DemoTeachingStore(root)
        val id = UUID.randomUUID().toString(); store.create(id, "大截图")
        val raw = ByteArray(2_000_000) { (it % 251).toByte() }
        val images = (1..6).map { store.saveImage(id, raw) }
        store.update(id) { record -> record.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("s1", "指令", "completed", "回答", images.mapIndexed { index, name ->
                DemoTeachingAction("a$index", "custom_observation", buildJsonObject { put("step", index) },
                    "结果$index", isError = false, afterImage = name) })
        )) }
        val imageChars = mutableListOf<Int>()
        val stages = mutableListOf<String>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                stages += stageOf(request).name
                imageChars += request.messages.filterIsInstance<AgentMessage.User>()
                    .sumOf { message -> message.images.sumOf { it.base64Data.length } }
                return respond(request, "笔记")
            }
        }
        runCatching { DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!) }
        assertTrue("the draft stage must have been reached, not an earlier failure: $stages",
            stages.contains("WRITE_SOP"))
        // Stated as a literal, not read back from the constant under test: asserting against the code's
        // own budget would pass even with the budget removed. A zero-length entry must not count as
        // evidence that images were sent.
        assertTrue("the draft request must have carried at least one screenshot: $imageChars",
            imageChars.any { chars -> chars > 0 })
        imageChars.forEach { chars -> assertTrue("one request carried $chars characters of image text", chars <= 4_000_000) }
        // A screenshot dropped for budget must leave a trace, not vanish silently.
        val diagnostics = java.io.File(root, "$id/compilation-diagnostics.jsonl").readText(Charsets.UTF_8)
        assertTrue("the dropped screenshots must be recorded: $diagnostics",
            diagnostics.contains("\"event\":\"images_budget_dropped\""))
    }

    /** No-regression guard: a long tail of screenshots still reaches the later batches. */
    @Test fun longTailOfScreenshotsStillReachesLaterRequests() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "很多截图")
        val raw = ByteArray(300_000) { (it % 251).toByte() }
        val images = (1..20).map { store.saveImage(id, raw) }
        store.update(id) { record -> record.copy(status = "finished", segments = images.mapIndexed { index, name ->
            DemoTeachingSegment("s$index", "第${index}段指令内容较长以便分批".repeat(300), "completed", "回答",
                listOf(DemoTeachingAction("a$index", "custom_observation", buildJsonObject { put("step", index) },
                    "结果$index", isError = false, afterImage = name)))
        }) }
        val imageCharsPerRequest = mutableListOf<List<Int>>()
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                imageCharsPerRequest += request.messages.filterIsInstance<AgentMessage.User>()
                    .flatMap { message -> message.images.map { it.base64Data.length } }
                return respond(request, "## 步骤笔记\n\n" + "x".repeat(3_000))
            }
        }
        runCatching { DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!) }
        val sent = imageCharsPerRequest.filter { it.isNotEmpty() }
        // The point is that no screenshot is silently dropped: all 20 must ride some request.
        assertEquals("every screenshot must reach a request, not just spread across two",
            20, sent.sumOf { it.size })
        sent.forEach { request ->
            assertTrue("one request carried ${request.sum()} characters of image text",
                request.sum() <= 4_000_000)
        }
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
        // Everything a stuck session needs must still work after the refusal, including the real
        // terminal transition (active -> finished), which lengthens the JSON by a few bytes.
        store.update(id) { it.copy(status = "active") }
        store.update(id) { it.copy(status = "finished") }
        assertEquals("finished", store.read(id)!!.status)
        // The whole point of charging only evidence growth: a record stopped at the limit must still
        // have room for the compiled guide it just paid for, and for the writes that end the teaching.
        val room = 4L * 1024 * 1024 - DemoTeachingStore.encode(store.read(id)!!).toString()
            .toByteArray(Charsets.UTF_8).size
        assertTrue("room must remain for the compiled result, room=$room", room >= 300_000)
        store.saveGuide(id, DemoTeachingGuide("标题", "目标", emptyList(), List(60) { "步骤" + "y".repeat(1_000) },
            emptyList(), emptyList(), emptyList(), document = "z".repeat(130_000)))
        assertNotNull(store.read(id)!!.guide)
        assertEquals("completed", store.read(id)!!.compilationStatus)
        store.delete(id)
    }

    /** Counter-example: many small actions still stop at the advertised count ceiling. */
    @Test fun shortActionsStillStopAtTheCountCeiling() {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "计数上限")
        assertNull(record(id, store, 60, 10, 20))
        val failure = record(id, store, 61, 10, 20)
        assertTrue("the count ceiling must report capacity too, got $failure",
            failure is DemoTeachingCapacityException &&
                failure.message == "本次教学记录已达上限，请结束后开始新教学")
    }

    /** A released claim must not rewrite a record that no longer carries it. */
    @Test fun releaseDoesNotRewriteACompletedRecord() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "保存")
        assertNull(record(id, store, 1, 1, 10))
        store.update(id) { it.copy(status = "finished") }
        DemoTeachingCompilationClaim.withClaim(store, id) { }
        assertEquals("failed", store.read(id)!!.compilationStatus)
        store.update(id) { it.copy(compilationStatus = "completed") }
        val saved = store.read(id)!!
        Thread.sleep(20) // updatedAt is a wall clock; without a tick a rewrite could land in the same ms.
        DemoTeachingCompilationClaim.release(store, id)
        val after = store.read(id)!!
        assertEquals("release must not touch a record that is not claimed", saved.updatedAt, after.updatedAt)
        assertEquals("completed", after.compilationStatus)
    }

    /** A claim refused for a held record must leave that holder's claim untouched. */
    @Test fun withClaimOnHeldRecordLeavesTheHolderAlone() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "占用")
        assertNull(record(id, store, 1, 1, 10))
        store.update(id) { it.copy(status = "finished", compilationStatus = "compiling") }
        val failure = runCatching {
            DemoTeachingCompilationClaim.withClaim(store, id) { throw AssertionError("must not run") }
        }.exceptionOrNull()
        assertEquals("教学记录正在使用，请稍后重试", failure?.message)
        assertEquals("compiling", store.read(id)!!.compilationStatus)
    }

    /** Preconditions report their own codes so a failed compilation is diagnosable. */
    @Test fun compilationPreconditionsCarryFailureCodes() = runBlocking {
        val root = temporary.newFolder()
        val store = DemoTeachingStore(root)
        val id = UUID.randomUUID().toString(); store.create(id, "未结束")
        store.update(id) { it.copy(status = "active", segments = listOf(segment(1, 1, 10))) }
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse = respond(request, "笔记")
        }
        val compiler = DemoTeachingCompiler({ provider }, store, ::skill)
        val active = runCatching { compiler.compile(store.read(id)!!) }.exceptionOrNull()
        assertEquals("TEACHING_NOT_FINISHED", (active as? DemoTeachingCompileException)?.code)
        assertEquals("请先完成至少一段教学并结束", active?.message)
        // The other disjunct of the same guard: a finished record with no segment at all.
        store.update(id) { it.copy(status = "finished", segments = emptyList()) }
        val empty = runCatching { compiler.compile(store.read(id)!!) }.exceptionOrNull()
        assertEquals("TEACHING_NOT_FINISHED", (empty as? DemoTeachingCompileException)?.code)
        // A guard that fires outside a model request must still leave a coded trace behind.
        val diagnostics = java.io.File(root, "$id/compilation-diagnostics.jsonl").readText(Charsets.UTF_8)
        assertTrue("guard failures must be diagnosable: $diagnostics",
            diagnostics.contains("\"event\":\"compile_failed\"") &&
                diagnostics.contains("\"failureCode\":\"TEACHING_NOT_FINISHED\""))
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

    /** The evidence read-back must cap what it attaches and tell the Agent the true availability. */
    @Test fun reviewEvidenceReadBackCapsImagesAndReportsTrueAvailability() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "回查截图")
        val raw = ByteArray(2_000_000) { (it % 251).toByte() }
        val images = (1..6).map { store.saveImage(id, raw) }
        store.update(id) { record -> record.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("s1", "指令", "completed", "回答", images.mapIndexed { index, name ->
                DemoTeachingAction("a$index", "custom_observation", buildJsonObject { put("step", index) },
                    "结果$index", isError = false, afterImage = name) })
        )) }
        val reviewImageChars = mutableListOf<Int>()
        val toolContent = mutableListOf<String>()
        var reviewCalls = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                if (stageOf(request) != DemoTeachingSopSkill.Stage.REVIEW_SOP) return respond(request, "笔记")
                reviewCalls++
                reviewImageChars += request.messages.filterIsInstance<AgentMessage.User>()
                    .sumOf { message -> message.images.sumOf { it.base64Data.length } }
                toolContent += request.messages.filterIsInstance<AgentMessage.Tool>().map { it.result.content }
                return if (reviewCalls == 1) ModelResponse(content = "", toolCalls = listOf(ToolCall(
                    "read-1", "read_teaching_evidence", buildJsonObject {
                        put("index", 1); put("offset", 0); put("includeImages", true)
                    }))) else respond(request, "笔记")
            }
        }
        runCatching { DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!) }
        assertTrue("the review must take a second model call after reading evidence: $reviewCalls",
            reviewCalls >= 2)
        val attached = reviewImageChars[1]
        assertTrue("the read-back attached $attached characters for a batch of six 2 MB screenshots",
            attached in 1..4_000_000)
        // The Agent must be told the batch really holds six, not the capped one it received.
        val page = toolContent.joinToString("\n")
        assertTrue("imagesAvailable must report the true batch size: $page",
            page.contains("\"imagesAvailable\":6") && page.contains("\"imagesSupplied\":1"))
        // A single-batch record has nowhere else to fetch the capped screenshots from,
        // so the read-back must not advise switching batches.
        assertFalse("single-batch read-back must not advise switching batches: $page",
            page.contains("可换批次回查"))
    }

    /**
     * `offset` and `includeImages` are optional with defaults, and a Java/Pojo
     * gateway serializes them as JSON null instead of omitting them. A raw
     * presence test read that as "the model asked for a broken offset" and refused
     * the read-back, so the Agent was told its own optional argument was invalid.
     */
    @Test fun evidenceReadBackTreatsNullOptionalArgumentsAsAbsent() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "空参数")
        val raw = ByteArray(2048) { (it % 251).toByte() }
        val images = (1..2).map { store.saveImage(id, raw) }
        store.update(id) { record -> record.copy(status = "finished", segments = listOf(
            DemoTeachingSegment("s1", "指令", "completed", "回答", images.mapIndexed { index, name ->
                DemoTeachingAction("a$index", "custom_observation", buildJsonObject { put("step", index) },
                    "观察$index", isError = false, afterImage = name) })
        )) }
        val toolContent = mutableListOf<String>()
        var reviewCalls = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                if (stageOf(request) != DemoTeachingSopSkill.Stage.REVIEW_SOP) return respond(request, "笔记")
                reviewCalls++
                toolContent += request.messages.filterIsInstance<AgentMessage.Tool>().map { it.result.content }
                return if (reviewCalls == 1) ModelResponse(content = "", toolCalls = listOf(ToolCall(
                    "read-null-arguments", "read_teaching_evidence", buildJsonObject {
                        put("index", 1); put("offset", JsonNull); put("includeImages", JsonNull)
                    }))) else respond(request, "笔记")
            }
        }
        runCatching { DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!) }
        assertTrue("the null-argument read must still be answered: $reviewCalls", reviewCalls >= 2)
        val page = toolContent.joinToString("\n")
        // Only a read that ran can report where it started and how much it returned,
        // and `includeImages` null has to mean the default: text without screenshots.
        assertTrue("read-back payload must carry the served page: $page",
            page.contains("\"offset\":0") && page.contains("\"returnedChars\""))
        assertTrue("a null includeImages must not attach screenshots: $page",
            page.contains("\"imagesSupplied\":0") && page.contains("\"imagesAvailable\":2"))
    }

    /**
     * The other side of that rule: a value the model did supply, in a shape it
     * cannot mean, must still be refused. Reading null as absent may not degrade
     * into reading garbage as the default offset.
     */
    @Test fun evidenceReadBackStillRefusesADeclaredMalformedOffset() = runBlocking {
        val store = DemoTeachingStore(temporary.newFolder())
        val id = UUID.randomUUID().toString(); store.create(id, "畸形偏移")
        store.update(id) { it.copy(status = "finished", segments = listOf(segment(1, 1, 4000))) }
        val toolContent = mutableListOf<String>()
        var reviewCalls = 0
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                if (stageOf(request) != DemoTeachingSopSkill.Stage.REVIEW_SOP) return respond(request, "笔记")
                reviewCalls++
                toolContent += request.messages.filterIsInstance<AgentMessage.Tool>().map { it.result.content }
                return if (reviewCalls == 1) ModelResponse(content = "", toolCalls = listOf(ToolCall(
                    "read-malformed-offset", "read_teaching_evidence", buildJsonObject {
                        put("index", 1); put("offset", buildJsonObject { put("from", 0) })
                    }))) else respond(request, "笔记")
            }
        }
        runCatching { DemoTeachingCompiler({ provider }, store, ::skill).compile(store.read(id)!!) }
        assertTrue("the malformed read must reach the tool once: $reviewCalls", reviewCalls >= 2)
        val page = toolContent.joinToString("\n")
        assertFalse("a malformed offset must not be served as a page: $page", page.contains("\"returnedChars\""))
        assertTrue("the refusal must name the field: $page", page.contains("offset"))
    }
}
