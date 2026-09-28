package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentMessage

import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemoWorkflowRepositoryTest {
    private fun plan() = DemoWorkflowPlan(UUID.randomUUID().toString(), createdAt = 10, title = "设置", goal = "打开设置", steps = listOf(DemoWorkflowStep("s1", "打开", "launch", "com.android.settings", postcondition = DemoWorkflowCondition("com.android.settings", listOf(DemoWorkflowSelector(text = "设置"))), sourceEventIds = listOf(1))))
    @Test fun versionsAreImmutableAndUsageDoesNotChangeDigest() {
        val repo = DemoWorkflowRepository(Files.createTempDirectory("workflow").toFile())
        val first = repo.saveNewVersion(plan())
        val second = repo.saveNewVersion(first.copy(title = "更新"))
        assertEquals(1, first.version); assertEquals(2, second.version)
        assertEquals(second, repo.read(first.draftId))
        assertEquals(first.digest(), first.copy(modelCalls = 99, imagesSent = 6).digest())
        assertNotEquals(first.digest(), second.digest())
    }
    @Test fun interruptedRunsNeverResume() {
        val repo = DemoWorkflowRepository(Files.createTempDirectory("workflow").toFile())
        val p = repo.saveNewVersion(plan())
        repo.saveRun(DemoWorkflowRunRecord(UUID.randomUUID().toString(), p.draftId, p.version, p.digest(), true, "running", 12, totalSteps = 1))
        repo.recoverInterrupted()
        assertEquals("interrupted", repo.records(p.draftId).single().status)
        assertNotNull(repo.records(p.draftId).single().endedAt)
    }
    @Test fun corruptVersionIsPreservedAndRejectsExtension() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val p = repo.saveNewVersion(plan())
        val file = java.io.File(root, "${p.draftId}/v1.json")
        file.writeText("broken")
        assertTrue(runCatching { repo.saveNewVersion(p) }.isFailure)
        assertEquals("broken", file.readText())
        assertTrue(runCatching { repo.read("../escape") }.isFailure)
    }
    @Test fun packageOnlyPostconditionRejected() {
        val p = plan()
        assertTrue(runCatching { DemoWorkflowJson.validate(p.copy(steps = listOf(p.steps.single().copy(postcondition = DemoWorkflowCondition("com.android.settings"))))) }.isFailure)
    }
    @Test fun deleteIsScopedAndIdempotent() {
        val repo = DemoWorkflowRepository(Files.createTempDirectory("workflow").toFile())
        val a = repo.saveNewVersion(plan())
        val b = repo.saveNewVersion(plan())
        repo.saveRun(DemoWorkflowRunRecord(UUID.randomUUID().toString(), a.draftId, a.version, a.digest(), true, "succeeded", 12, endedAt = 13, totalSteps = 1))
        repo.delete(a.draftId); repo.delete(a.draftId)
        assertNull(repo.read(a.draftId)); assertEquals(b, repo.read(b.draftId))
    }
    @Test fun deleteRejectsUnfinishedAndUnknownDataBeforeDeletingAnything() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val p = repo.saveNewVersion(plan())
        repo.saveRun(DemoWorkflowRunRecord(UUID.randomUUID().toString(), p.draftId, p.version, p.digest(), true, "started", 12, totalSteps = 1))
        assertTrue(runCatching { repo.delete(p.draftId) }.isFailure)
        repo.recoverInterrupted()
        val unknown = java.io.File(root, "${p.draftId}/unknown")
        unknown.mkdir()
        assertTrue(runCatching { repo.delete(p.draftId) }.isFailure)
        assertEquals(p, repo.read(p.draftId))
        assertTrue(unknown.isDirectory)
        assertTrue(runCatching { repo.delete("../escape") }.isFailure)
    }
    @Test fun deletePreservesCorruptVersion() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val p = repo.saveNewVersion(plan())
        val file = java.io.File(root, "${p.draftId}/v1.json")
        file.writeText("broken")
        assertTrue(runCatching { repo.delete(p.draftId) }.isFailure)
        assertEquals("broken", file.readText())
    }
    @Test fun deleteRejectsLinkedOwnedFile() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val p = repo.saveNewVersion(plan())
        val external = Files.createTempFile("outside-workflow", ".json")
        val linked = java.io.File(root, "${p.draftId}/v2.json").toPath()
        val linkedOk = runCatching { Files.createSymbolicLink(linked, external); true }.getOrDefault(false)
        org.junit.Assume.assumeTrue("Host does not permit symbolic links", linkedOk)
        assertTrue(runCatching { repo.delete(p.draftId) }.isFailure)
        assertTrue(Files.exists(external)); assertEquals(p, DemoWorkflowJson.decodePlan(kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(root, "${p.draftId}/v1.json").readText()).let { it as kotlinx.serialization.json.JsonObject }))
    }
    @Test fun recoveryQuarantinesOnlyDamagedDraft() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val damaged = repo.saveNewVersion(plan())
        val healthy = repo.saveNewVersion(plan())
        listOf(damaged, healthy).forEach { p -> repo.saveRun(DemoWorkflowRunRecord(UUID.randomUUID().toString(), p.draftId, p.version, p.digest(), true, "started", 12, totalSteps = 1)) }
        val broken = java.io.File(root, "${damaged.draftId}/runs").listFiles()!!.single()
        broken.writeText("broken")
        repo.recoverInterrupted()
        assertEquals("broken", broken.readText())
        assertTrue(runCatching { repo.records(damaged.draftId) }.isFailure)
        assertTrue(runCatching { repo.read(damaged.draftId) }.isFailure)
        assertEquals(healthy, repo.read(healthy.draftId))
        assertEquals("interrupted", repo.records(healthy.draftId).single().status)
    }
    @Test fun intentIsIndependentAndCanBeDeletedWithoutCompiledVersion() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val i = DemoWorkflowIntent(UUID.randomUUID().toString(), "目标", "看到完成文字", 10)
        repo.saveIntent(i)
        assertEquals(i, repo.readIntent(i.draftId)); assertNull(repo.read(i.draftId))
        val changed = i.copy(completionCriteria = "看到另一明确结果", updatedAt = 20)
        repo.saveIntent(changed)
        assertEquals(changed, repo.readIntent(i.draftId))
        repo.delete(i.draftId); assertNull(repo.readIntent(i.draftId))
    }
    @Test fun damagedIntentIsPreservedAndLimitsApplyIndependently() {
        val root = Files.createTempDirectory("workflow").toFile()
        val repo = DemoWorkflowRepository(root)
        val i = DemoWorkflowIntent(UUID.randomUUID().toString(), "g".repeat(1000), "c".repeat(1000), 10)
        repo.saveIntent(i)
        assertEquals(i, repo.readIntent(i.draftId))
        assertTrue(runCatching { repo.saveIntent(i.copy(goal = "g".repeat(1001))) }.isFailure)
        assertTrue(runCatching { repo.saveIntent(i.copy(completionCriteria = "c".repeat(1001))) }.isFailure)
        val file = java.io.File(root, "${i.draftId}/intent.json")
        file.writeText("broken")
        assertTrue(runCatching { repo.saveIntent(i) }.isFailure)
        assertTrue(runCatching { repo.delete(i.draftId) }.isFailure)
        assertEquals("broken", file.readText())
    }
    @Test fun criteriaChangeDigestWhileLegacyMissingFieldStaysCompatible() {
        val p = plan()
        val legacy = kotlinx.serialization.json.JsonObject(DemoWorkflowJson.plan(p).filterKeys { it != "completionCriteria" })
        assertEquals("", DemoWorkflowJson.decodePlan(legacy).completionCriteria)
        assertEquals(p.digest(), DemoWorkflowJson.decodePlan(legacy).digest())
        assertNotEquals(p.digest(), p.copy(completionCriteria = "明确完成结果").digest())
        assertEquals("明确完成结果", DemoWorkflowJson.decodePlan(DemoWorkflowJson.plan(p.copy(completionCriteria = "明确完成结果"))).completionCriteria)
    }
}

class DemoWorkflowCompilerTest {
    private val id = UUID.randomUUID().toString()
    private val node = DemoOperationNode("0", "com.android.settings:id/title", "TextView", "设置", null, emptyList(), true, false, false)
    private val frame = DemoOperationFrame("f1", 5, "f.jpg", "com.android.settings", 10, 10, 1, listOf(node))
    private val event = DemoOperationEvent(1, 5, 1, "com.android.settings", "TextView", node.viewId, "设置", emptyList(), "f1", "f1")
    private fun draft() = DemoOperationDraft(id, "设置", 1, 6, "saved", listOf(event), listOf(frame))
    private fun plan() = DemoWorkflowPlan(id, createdAt = 5, title = "设置", goal = "设置", steps = listOf(DemoWorkflowStep("one", "点设置", "click", "com.android.settings", DemoWorkflowSelector(text = "设置"), DemoWorkflowCondition("com.android.settings", listOf(DemoWorkflowSelector(text = "设置"))), listOf(1))))
    private suspend fun actionCandidates(recorded: DemoOperationDraft): JsonArray {
        var captured: JsonArray? = null
        val compiler = DemoWorkflowCompiler(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                captured = Json.parseToJsonElement(request.messages.filterIsInstance<AgentMessage.User>().first().content)
                    .jsonObject.getValue("recordedEvidence").jsonObject.getValue("actionCandidates").jsonArray
                return ModelResponse("{\"error\":\"capture only\"}")
            }
        })
        runCatching { compiler.compile(recorded, "设置") { null } }
        return requireNotNull(captured)
    }
    @Test fun actionCandidatesLinkRealAncestorAndExcludeNeighbor() = runBlocking {
        val ancestor = node.copy(path = "0", className = "LinearLayout", viewId = null, text = null, bounds = listOf(0, 0, 10, 5))
        val child = node.copy(path = "0.0", clickable = false)
        val neighbor = node.copy(path = "1", text = "邻居", viewId = "neighbor", bounds = listOf(0, 5, 10, 10))
        val recorded = draft().copy(events = listOf(event.copy(className = "LinearLayout", viewId = null, label = null, bounds = ancestor.bounds, postFrameId = "f2")),
            frames = listOf(frame.copy(nodes = listOf(ancestor, child, neighbor)), frame.copy(id = "f2")))
        val candidate = actionCandidates(recorded).single().jsonObject
        assertEquals(1, candidate.getValue("sourceEventId").jsonPrimitive.int)
        assertEquals(1, candidate.getValue("type").jsonPrimitive.int)
        assertEquals("f1", candidate.getValue("preFrameId").jsonPrimitive.content)
        assertEquals("f2", candidate.getValue("postFrameId").jsonPrimitive.content)
        assertEquals(listOf(DemoWorkflowJson.selector(DemoWorkflowSelector(viewId = child.viewId, text = child.text))),
            candidate.getValue("candidateSelectors").jsonArray.toList())
    }
    @Test fun actionCandidatesExcludeZeroScrollAndMissingFrames() = runBlocking {
        val recorded = draft().copy(events = listOf(
            event.copy(id = 1, type = 4096, scrollDeltaX = 0, scrollDeltaY = 0),
            event.copy(id = 2, postFrameId = null), event.copy(id = 3, preFrameId = "missing"),
            event.copy(id = 4, postFrameId = "missing"), event.copy(id = 5, type = 32),
            event.copy(id = 6, type = 4096, scrollDeltaX = 0, scrollDeltaY = 20, postFrameId = "f2"),
            event.copy(id = 7, type = 2)), frames = listOf(frame, frame.copy(id = "f2")))
        val candidates = actionCandidates(recorded)
        assertEquals(listOf(6, 7), candidates.map { it.jsonObject.getValue("sourceEventId").jsonPrimitive.int })
        assertEquals(listOf(4096, 2), candidates.map { it.jsonObject.getValue("type").jsonPrimitive.int })
    }
    @Test fun actionCandidatesDoNotBorrowNeighborWithRepeatedRowId() = runBlocking {
        val first = node.copy(path = "0", viewId = "row", className = "LinearLayout", text = null, bounds = listOf(0, 0, 10, 5))
        val second = first.copy(path = "1", bounds = listOf(0, 5, 10, 10))
        val recorded = draft().copy(events = listOf(event.copy(viewId = "row", className = "LinearLayout", label = null, bounds = first.bounds)),
            frames = listOf(frame.copy(nodes = listOf(first, node.copy(path = "0.0", clickable = false), second,
                node.copy(path = "1.0", text = "邻居", clickable = false)))))
        val selectors = actionCandidates(recorded).single().jsonObject.getValue("candidateSelectors").jsonArray
        assertEquals(1, selectors.size)
        assertEquals("设置", selectors.single().jsonObject.getValue("text").jsonPrimitive.content)
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val unrelated = plan().let { it.copy(steps = listOf(it.steps.single().copy(selector = DemoWorkflowSelector(text = "邻居")))) }
        assertTrue(runCatching { compiler.validateEvidence(unrelated, recorded) }.isFailure)
    }
    @Test fun actionCandidatesAreUniqueAndBoundedWithoutChecked() = runBlocking {
        val ancestor = node.copy(path = "0", className = "LinearLayout", viewId = null, text = null, bounds = listOf(0, 0, 10, 10))
        val children = (1..5).map { node.copy(path = "0.$it", text = "标签$it", clickable = false, checkable = true, checked = true) }
        val ambiguous = node.copy(path = "1", text = "重复", clickable = false)
        val recorded = draft().copy(events = listOf(event.copy(className = "LinearLayout", viewId = null, label = null, bounds = ancestor.bounds)),
            frames = listOf(frame.copy(nodes = listOf(ancestor) + children + listOf(ambiguous, ambiguous.copy(path = "2")))))
        val selectors = actionCandidates(recorded).single().jsonObject.getValue("candidateSelectors").jsonArray
        assertEquals(3, selectors.size)
        selectors.forEach {
            assertFalse(it.jsonObject.containsKey("checked"))
            assertTrue(it.jsonObject.getValue("text").jsonPrimitive.content.startsWith("标签"))
        }
    }
    @Test fun oneRequestAndExplicitImageIdentity() = runBlocking {
        var calls = 0
        val compiler = DemoWorkflowCompiler(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                calls++
                assertTrue(request.messages.toString().contains("frameId=f1"))
                val p = plan()
                val ready = p.copy(steps = listOf(p.steps.single().copy(id = "launch", action = "launch", selector = null)) + p.steps)
                return ModelResponse("```json\n${DemoWorkflowJson.plan(ready)}\n```")
            }
        })
        val result = compiler.compile(draft(), "设置") { byteArrayOf(1) }
        assertEquals(1, calls); assertEquals(1, result.imagesSent); assertEquals(0, result.version)
    }
    @Test fun inventedEvidenceAndPostconditionRejected() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val p = plan()
        assertTrue(runCatching { compiler.validateEvidence(p.copy(steps = listOf(p.steps.single().copy(sourceEventIds = listOf(99)))), draft()) }.isFailure)
        assertTrue(runCatching { compiler.validateEvidence(p.copy(steps = listOf(p.steps.single().copy(postcondition = DemoWorkflowCondition("com.android.settings", listOf(DemoWorkflowSelector(text = "编造")))))), draft()) }.isFailure)
    }
    @Test fun excessiveEventsNeverSilentlyTruncated() = runBlocking {
        var called = false
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest): ModelResponse { called = true; error("not called") } })
        assertTrue(runCatching { compiler.compile(draft().copy(events = (1..101).map { event.copy(id = it) }), "设置") { null } }.isFailure)
        assertFalse(called)
    }
    @Test fun launchCanUseObservedInitialScreenWithoutLauncherEvent() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val p = plan()
        val initialOnly = draft().copy(events = listOf(event.copy(postFrameId = null)))
        compiler.validateEvidence(p.copy(steps = listOf(p.steps.single().copy(action = "launch", selector = null))), initialOnly)
        assertTrue(runCatching { compiler.validateEvidence(p, initialOnly) }.isFailure)
    }
    @Test fun inferredDirectionNeedsBothImagesActuallySent() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val after = frame.copy(id = "f2")
        val scrollDraft = draft().copy(events = listOf(event.copy(type = 4096, postFrameId = "f2")), frames = listOf(frame, after))
        val p = plan().let { it.copy(steps = listOf(it.steps.single().copy(action = "scroll_forward"))) }
        assertTrue(runCatching { compiler.validateEvidence(p, scrollDraft, setOf("f1")) }.isFailure)
        compiler.validateEvidence(p, scrollDraft, setOf("f1", "f2"))
        assertTrue(runCatching { compiler.validateEvidence(p, scrollDraft.copy(events = scrollDraft.events.map { it.copy(scrollDeltaX = 0, scrollDeltaY = 0) }), setOf("f1", "f2")) }.isFailure)
    }
    @Test fun providerFailureAndModelErrorAreSafeForUi() = runBlocking {
        val secret = "private-key-secret"
        val providerFailure = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest): ModelResponse = error(secret) })
        val first = runCatching { providerFailure.compile(draft(), "设置") { null } }.exceptionOrNull()
        assertTrue(first is DemoWorkflowCompileException)
        assertFalse(first!!.message!!.contains(secret)); assertNull(first.cause)
        val modelError = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = ModelResponse("{\"error\":\"$secret\"}") })
        val second = runCatching { modelError.compile(draft(), "设置") { null } }.exceptionOrNull()
        assertTrue(second is DemoWorkflowCompileException)
        assertFalse(second!!.message!!.contains(secret)); assertNull(second.cause)
    }
    @Test fun neighborAndBorrowedFutureFrameAreRejected() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val neighbor = node.copy(path = "1", text = "邻居", viewId = "neighbor")
        val p = plan()
        val d = draft().copy(frames = listOf(frame.copy(nodes = listOf(node, neighbor))))
        assertTrue(runCatching { compiler.validateEvidence(p.copy(steps = listOf(p.steps.single().copy(selector = DemoWorkflowSelector(text = "邻居")))), d) }.isFailure)
        val borrowed = draft().copy(events = listOf(event.copy(postFrameId = null), event.copy(id = 2, type = 32, preFrameId = null)))
        assertTrue(runCatching { compiler.validateEvidence(p.copy(steps = listOf(p.steps.single().copy(sourceEventIds = listOf(1, 2)))), borrowed) }.isFailure)
    }
    @Test fun mergedEventLabelCanIdentifyUniqueClickableAncestor() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val ancestor = node.copy(path = "0", className = "LinearLayout", viewId = null, text = null)
        val child = node.copy(path = "0.0", clickable = false)
        val summary = node.copy(path = "0.1", viewId = null, text = "摘要", clickable = false)
        val d = draft().copy(events = listOf(event.copy(className = "LinearLayout", viewId = null, label = "设置 摘要")), frames = listOf(frame.copy(nodes = listOf(ancestor, child, summary))))
        compiler.validateEvidence(plan(), d)
    }
    @Test fun compiledPlanRequiresLaunchFromReviewPage() = runBlocking {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = ModelResponse(DemoWorkflowJson.plan(plan()).toString()) })
        val error = runCatching { compiler.compile(draft(), "设置") { null } }.exceptionOrNull()
        assertTrue(error is DemoWorkflowCompileException)
        assertTrue(error!!.message!!.contains("先打开"))
    }
    @Test fun checkedFalseNeedsExplicitlyCheckableEvidence() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest) = error("not called") })
        val p = plan().let { it.copy(steps = listOf(it.steps.single().copy(postcondition = DemoWorkflowCondition("com.android.settings", listOf(DemoWorkflowSelector(text = "设置", checked = false)))))) }
        assertTrue(runCatching { compiler.validateEvidence(p, draft()) }.isFailure)
        assertTrue(runCatching { compiler.validateEvidence(p, draft().copy(frames = listOf(frame.copy(nodes = listOf(node.copy(checkable = false)))))) }.isFailure)
        compiler.validateEvidence(p, draft().copy(frames = listOf(frame.copy(nodes = listOf(node.copy(checkable = true))))))
    }
    @Test fun userCompletionCriteriaAreSentAndPreserved() = runBlocking {
        val criteria = "页面明确显示设置"
        val compiler = DemoWorkflowCompiler(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                assertTrue(request.messages.toString().contains(criteria))
                val p = plan()
                return ModelResponse(DemoWorkflowJson.plan(p.copy(steps = listOf(p.steps.single().copy(id = "launch", action = "launch", selector = null)) + p.steps, completionCriteria = "模型不能改写用户标准")).toString())
            }
        })
        assertEquals(criteria, compiler.compile(draft(), "设置", criteria) { null }.completionCriteria)
    }
    @Test fun launchHintsExcludeUnlinkedWindowAndFailureIdentifiesStepSafely() = runBlocking {
        val recorded = draft().copy(events = listOf(event.copy(type = 32, preFrameId = null, postFrameId = null), event.copy(id = 2)),
            frames = listOf(frame.copy(id = "unlinked", at = 1), frame))
        val compiler = DemoWorkflowCompiler(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                val evidence = request.messages.filterIsInstance<AgentMessage.User>().first().content
                assertTrue(evidence.contains("\"frameId\":\"f1\",\"sourceEventIds\":[2]"))
                val p = plan()
                // The unlinked window notification cannot substantiate a launch result.
                return ModelResponse(DemoWorkflowJson.plan(p.copy(steps = listOf(p.steps.single().copy(
                    action = "launch", selector = null, title = "private-model-value")))).toString())
            }
        })
        val failure = runCatching { compiler.compile(recorded, "设置") { null } }.exceptionOrNull()
        assertTrue(failure is DemoWorkflowCompileException)
        assertTrue(failure!!.message!!.contains("第 1 步"))
        assertTrue(failure.message!!.contains("结构证据"))
        assertFalse(failure.message!!.contains("private-model-value"))
        assertNull(failure.cause)
    }
}
