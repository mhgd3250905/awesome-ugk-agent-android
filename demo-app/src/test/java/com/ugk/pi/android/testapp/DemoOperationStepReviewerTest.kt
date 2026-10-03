package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class DemoOperationStepReviewerTest {
    private val event = DemoOperationEvent(1, 1, 1, "com.example.app", null, null, "打开", emptyList(), "before", "after")
    private val frames = listOf("before", "after").map { DemoOperationFrame(it, 1, "$it.jpg", "com.example.app", 1, 1, 1) }
    private val step = DemoOperationStep(1, listOf(1), "before", "after", aiSummary = "打开页面", confirmed = true)
    private val draft = DemoOperationDraft("00000000-0000-0000-0000-000000000001", "演示", 1, 2, events = listOf(event), frames = frames, steps = listOf(step), guided = true)
    private fun reviewer(answer: ModelResponse, capture: (ModelRequest) -> Unit = {}) = DemoOperationStepReviewer(object : LLMProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse { capture(request); return answer }
    })

    @Test fun correctionIsDataAndRequestHasNoTools() = runBlocking {
        var calls = 0
        val result = reviewer(ModelResponse("""{"action":"打开页面","result":"页面可见","gaps":"需要核对"}""")) {
            calls++
            assertTrue(it.tools.isEmpty())
            assertTrue(it.messages.filterIsInstance<AgentMessage.User>().any { message -> message.toString().contains("用户补充") })
            assertEquals(2, it.messages.filterIsInstance<AgentMessage.User>().sumOf { message -> message.images.size })
        }.review(draft, step, "用户补充") { byteArrayOf(1) }
        assertEquals(1, calls)
        assertTrue(result.contains("观察结果：页面可见"))
        assertEquals("", step.userCorrection)
    }

    @Test fun rejectsMalformedTruncatedAndOversizedResponsesWithoutRetry() = runBlocking {
        listOf(ModelResponse("SECRET"), ModelResponse("{}", stopReason = "length"), ModelResponse("x".repeat(12_001)),
            ModelResponse("{}", toolCalls = listOf(ToolCall("1", "click", JsonObject(emptyMap())))),
            ModelResponse("{}", stopReason = "max_output_tokens"),
            ModelResponse("{}", stopReason = "MAX_OUTPUT_TOKENS")).forEach { response ->
            var calls = 0
            val error = runCatching { reviewer(response) { calls++ }.review(draft, step, "") { byteArrayOf(1) } }.exceptionOrNull()
            assertTrue(error is DemoOperationStepReviewException)
            assertFalse(error!!.message!!.contains("SECRET"))
            assertEquals(1, calls)
        }
    }

    @Test fun oversizedCorrectionNeverCallsProvider() = runBlocking {
        var calls = 0
        assertTrue(runCatching { reviewer(ModelResponse("{}")) { calls++ }.review(draft, step, "x".repeat(2001)) { null } }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun missingBoundaryImageNeverCallsProvider() = runBlocking {
        var calls = 0
        val review = reviewer(ModelResponse("{}")) { calls++ }
        assertTrue(runCatching { review.review(draft, step, "") { if (it.id == "after") byteArrayOf(1) else null } }.isFailure)
        assertTrue(runCatching { review.review(draft, step, "") { if (it.id == "before") byteArrayOf(1) else null } }.isFailure)
        assertTrue(runCatching { review.review(draft, step.copy(postFrameId = null), "") { byteArrayOf(1) } }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun compilerOnlyRetainsConfirmedAttemptsAndTheirEvidence() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest): ModelResponse = error("not called") })
        val discarded = step.copy(id = 2, eventIds = listOf(2), confirmed = false, discarded = true)
        val retained = compiler.reviewedEvidence(draft.copy(events = listOf(event, event.copy(id = 2)), steps = listOf(step, discarded)))
        assertEquals(listOf(1), retained.events.map { it.id })
        assertEquals(listOf(step), retained.steps)
        assertTrue(runCatching { compiler.reviewedEvidence(draft.copy(steps = listOf(step.copy(confirmed = false)))) }.isFailure)
        assertTrue(runCatching { compiler.reviewedEvidence(draft.copy(frames = frames.take(1))) }.isFailure)
        assertTrue(runCatching { compiler.reviewedEvidence(draft.copy(steps = emptyList())) }.isFailure)
        assertTrue(runCatching { compiler.reviewedEvidence(draft.copy(steps = listOf(step.copy(aiSummary = null)))) }.isFailure)
    }

    @Test fun compilerDoesNotSilentlyDropMissingImagesOrOverBudgetEvidence() = runBlocking {
        var calls = 0
        val compiler = DemoWorkflowCompiler(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse { calls++; return ModelResponse("{}") }
        })
        assertTrue(runCatching { compiler.compile(draft, "打开") { null } }.exceptionOrNull()!!.message!!.contains("截图证据缺失"))
        val manyFrames = (1..21).map { frames.first().copy(id = "f$it") }
        val manySteps = manyFrames.mapIndexed { index, f -> step.copy(id = index + 1, preFrameId = f.id, postFrameId = f.id) }
        val many = draft.copy(steps = manySteps, frames = manyFrames, events = listOf(event.copy(preFrameId = "f1", postFrameId = "f2")))
        assertTrue(runCatching { compiler.compile(many, "打开") { byteArrayOf(1) } }.exceptionOrNull()!!.message!!.contains("20张"))
        assertEquals(0, calls)
    }

    @Test fun preparationLaunchUsesActualWindowEventAndItsBoundary() {
        val compiler = DemoWorkflowCompiler(object : LLMProvider { override suspend fun generate(request: ModelRequest): ModelResponse = error("not called") })
        val preparation = step.copy(preFrameId = null, postFrameId = "before", preparation = true)
        val window = event.copy(type = 32, preFrameId = null, postFrameId = "before")
        val recorded = draft.copy(events = listOf(window), steps = listOf(preparation))
        val retained = compiler.reviewedEvidence(recorded)
        val plan = DemoWorkflowPlan(draft.id, createdAt = 1, title = "打开", goal = "打开", steps = listOf(
            DemoWorkflowStep("launch", "打开应用", "launch", "com.example.app",
                postcondition = DemoWorkflowCondition("com.example.app", emptyList(), "目标页面是否可见？"), sourceEventIds = listOf(1))
        ))
        compiler.validateEvidence(plan, retained, setOf("before"))
        assertTrue(runCatching { compiler.reviewedEvidence(recorded.copy(events = listOf(window.copy(postFrameId = null)))) }.isFailure)
    }
}
