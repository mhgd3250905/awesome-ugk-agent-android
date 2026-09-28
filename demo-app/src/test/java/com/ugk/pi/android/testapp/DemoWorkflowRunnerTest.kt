package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemoWorkflowRunnerTest {
    private fun node(id: String, text: String, clickable: Boolean = true) = ScreenUiElement(id, 0, "example.app", "TextView", text = text, bounds = ScreenBounds(10, 10, 100, 100), clickable = clickable)
    private fun plan() = DemoWorkflowPlan("draft", 1, 1, "Test", "Test", listOf(DemoWorkflowStep("one", "Open", "click", "example.app", DemoWorkflowSelector(text = "Open"), DemoWorkflowCondition("example.app", listOf(DemoWorkflowSelector(text = "Done"))))))

    private class Backend : ScreenAutomationBackend, ScreenVisualAutomationBackend {
        var nodes = listOf(ScreenUiElement("0.1", 0, "example.app", "TextView", text = "Open", bounds = ScreenBounds(1, 1, 30, 30), clickable = true))
        var packageName = "example.app"
        var actions = 0
        var backs = 0
        var reads = 0
        var accepted = true
        var transition = true
        var onAction: () -> Unit = {}
        var onBack: () -> Unit = {}
        var captures = 0
        var onCapture: () -> Unit = {}
        var captureTime: () -> Long = System::currentTimeMillis
        override fun readUiTree(sessionId: String, maxDepth: Int, maxNodes: Int): ScreenReadResult = ScreenReadResult(ScreenUiSnapshot("fresh-${++reads}", sessionId, packageName, 100, 100, 1, nodes.size, false, nodes))
        override suspend fun performAction(sessionId: String, request: ScreenActionRequest): ScreenOperationResult {
            assertEquals("fresh-$reads", request.snapshotId)
            assertEquals(nodes.single().nodeId, request.nodeId)
            actions++
            onAction()
            if (transition) nodes = nodes.map { it.copy(text = "Done", nodeId = "0.9") }
            return ScreenOperationResult(accepted, if (accepted) "OK" else "ACTION_FAILED")
        }
        override fun performGlobalAction(request: ScreenGlobalActionRequest): ScreenOperationResult { backs++; onBack(); return ScreenOperationResult(true, "OK") }
        override suspend fun performGesture(request: ScreenGestureRequest): ScreenOperationResult = error("Coordinates forbidden")
        override suspend fun pressKey(request: ScreenKeyRequest): ScreenOperationResult = error("Key forbidden")
        override suspend fun captureVisualObservation(sessionId: String): ScreenVisualCaptureResult {
            captures++
            onCapture()
            return ScreenVisualCaptureResult(ScreenVisualObservation("frame-$captures", sessionId, packageName, 100, 100, 100, 100, 0, 0, captureTime(), AgentImageContent("AA==")))
        }
        override suspend fun performVisualGesture(sessionId: String, request: ScreenVisualGestureRequest): ScreenOperationResult = error("Visual gestures forbidden")
    }

    @Test fun normalRunUsesFreshSemanticTargetWithZeroModelCalls() = runBlocking {
        val backend = Backend().apply { nodes = listOf(node("0.42", "Open")) }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val result = DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {}
        assertEquals(1, result.completedSteps)
        assertEquals(0, result.modelCalls)
        assertEquals(0, result.imagesSent)
        assertEquals(1, backend.actions)
        assertEquals(0, backend.captures)
        assertTrue(runCatching { gate.verify() }.isFailure)
    }

    @Test fun duplicateTargetsNeverDispatch() = runBlocking {
        val backend = Backend().apply { nodes = listOf(node("0.1", "Open"), node("0.2", "Open")) }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        assertTrue(runCatching { DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {} }.isFailure)
        assertEquals(0, backend.actions)
    }

    @Test fun resultPresenceMayMatchSeveralVisibleElementsWithoutModelRecovery() = runBlocking {
        val backend = Backend().apply {
            transition = false
            onAction = { nodes = listOf(node("0.8", "Done"), node("0.9", "Done")) }
        }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val result = DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {}
        assertEquals(1, result.completedSteps)
        assertEquals(1, backend.actions)
        assertEquals(0, result.modelCalls)
    }

    @Test fun changingCaptureSettlesLocallyBeforeRecapturingAndSendsOnlyFreshImage() = runBlocking {
        var elapsed = 0L
        var previousCaptureAt = -10_000L
        val backend = Backend().apply {
            onCapture = {
                assertTrue(elapsed - previousCaptureAt >= 350)
                previousCaptureAt = elapsed
                if (captures == 1) nodes = nodes.map { it.copy(bounds = ScreenBounds(20, 20, 120, 120)) }
            }
        }
        val plan = plan().let { it.copy(steps = it.steps.map { step -> step.copy(
            postcondition = step.postcondition.copy(visualQuestion = "Is the result visible?")) }) }
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest) = ModelResponse("{\"decision\":\"pass\"}")
        }
        val result = DemoWorkflowRunner(backend, backend, provider, gate, waitMillis = { elapsed += it }).run(plan) {}
        assertEquals(2, backend.captures)
        assertEquals(1, result.modelCalls)
        assertEquals(1, result.completedSteps)
    }

    @Test fun wrongPackageNeverDispatches() = runBlocking {
        val backend = Backend().apply { packageName = "other.app" }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        assertTrue(runCatching { DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {} }.isFailure)
        assertEquals(0, backend.actions)
    }

    @Test fun acceptedActionWithoutPostconditionStopsWithoutReplay() = runBlocking {
        val backend = Backend().apply { transition = false }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        assertTrue(runCatching { DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {} }.isFailure)
        assertEquals(1, backend.actions)
        assertEquals(0, backend.backs)
    }

    @Test fun cancellationAtDispatchInvalidatesRemainingWork() = runBlocking {
        val backend = Backend()
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        backend.onAction = gate::invalidate
        assertTrue(runCatching { DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {} }.isFailure)
        assertEquals(1, backend.actions)
        assertEquals(0, backend.backs)
    }

    @Test fun packageOnlyPostconditionIsRejectedBeforeAction() = runBlocking {
        val backend = Backend()
        val original = plan()
        val plan = original.copy(steps = original.steps.map { it.copy(postcondition = DemoWorkflowCondition("example.app")) })
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        assertTrue(runCatching { DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) {} }.isFailure)
        assertEquals(0, backend.actions)
    }

    @Test fun permitRejectsChangedPlanAndExpiredAuthorization() {
        val plan = plan()
        var time = 1L
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, Backend(), nowMillis = { time })
        assertTrue(runCatching { gate.verify(plan.copy(version = 2)) }.isFailure)
        time = 300_002L
        assertTrue(runCatching { gate.verify() }.isFailure)
    }

    @Test fun uniqueClickableParentAllowedOnlyFromCurrentTree() {
        val child = node("0.1.2", "Open", false)
        val parent = node("0.1", "", true)
        val snapshot = ScreenUiSnapshot("fresh", "session", "example.app", 100, 100, 1, 2, false, listOf(parent, child))
        assertEquals(parent, DemoWorkflowActionGateway.resolveTarget(snapshot, plan().steps.single()))
        assertNull(DemoWorkflowActionGateway.resolveTarget(snapshot.copy(elements = snapshot.elements + node("0", "", true)), plan().steps.single()))
        assertNull(DemoWorkflowActionGateway.resolveTarget(snapshot.copy(elements = listOf(parent.copy(windowIndex = 1), child)), plan().steps.single()))
        assertNull(DemoWorkflowActionGateway.resolveTarget(snapshot.copy(elements = listOf(parent.copy(nodeId = "w0:0.1"), child.copy(nodeId = "w0:0.1.2"))), plan().steps.single()))
    }

    @Test fun runAwaitsSuspendingVerifiedCheckpoint() = runBlocking {
        val backend = Backend()
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        var persisted = false
        DemoWorkflowRunner(backend, backend, null, gate, waitMillis = {}).run(plan) { progress ->
            if (progress.completedSteps == 1) {
                yield()
                persisted = true
            }
        }
        assertTrue(persisted)
    }

    @Test fun providerFailureNeverExposesRawResponseOrCause() = runBlocking {
        val backend = Backend().apply { nodes = listOf(node("0.4", "Unexpected")) }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val originalFailure = IllegalStateException("HTTP secret-token")
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse = throw originalFailure
        }
        val failure = runCatching { DemoWorkflowRunner(backend, backend, provider, gate, waitMillis = {}).run(plan) {} }.exceptionOrNull()
        assertTrue(failure is DemoWorkflowModelRequestException)
        // Coroutine stack-trace recovery can copy the sanitized exception and use
        // that sanitized original as its cause. The provider throwable must never survive.
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = failure
        while (current != null && seen.add(current)) {
            assertNotSame(originalFailure, current)
            assertFalse(current.message.orEmpty().contains("secret-token"))
            assertFalse(current.message.orEmpty().contains("HTTP"))
            current = current.cause
        }
    }

    @Test fun oneBackRecoveryRelocatesOriginalStepAndCountsExactlyOneImage() = runBlocking {
        val backend = Backend().apply { nodes = listOf(node("0.4", "Unexpected")) }
        backend.onBack = { backend.nodes = listOf(node("0.7", "Open")) }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                assertTrue(request.tools.isEmpty())
                assertEquals(1, (request.messages.last() as AgentMessage.User).images.size)
                val input = Json.parseToJsonElement((request.messages.last() as AgentMessage.User).content).jsonObject
                assertEquals(plan.goal, input["goal"]!!.jsonPrimitive.content)
                assertEquals("before_dispatch", input["phase"]!!.jsonPrimitive.content)
                assertEquals("example.app", input["stepPackage"]!!.jsonPrimitive.content)
                assertEquals("example.app", input["currentPackage"]!!.jsonPrimitive.content)
                assertEquals("Open", input["selector"]!!.jsonObject["text"]!!.jsonPrimitive.content)
                return ModelResponse("{\"decision\":\"back\"}")
            }
        }
        val result = DemoWorkflowRunner(backend, backend, provider, gate, waitMillis = {}).run(plan) {}
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.modelCalls)
        assertEquals(1, result.imagesSent)
        assertEquals(1, backend.backs)
        assertEquals(1, backend.actions)
    }

    @Test fun visualBackAfterDispatchCannotReplayAction() = runBlocking {
        val backend = Backend()
        val original = plan()
        val plan = original.copy(steps = original.steps.map { it.copy(postcondition = it.postcondition.copy(visualQuestion = "Is done visible?")) })
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest) = ModelResponse("{\"decision\":\"back\"}")
        }
        assertTrue(runCatching { DemoWorkflowRunner(backend, backend, provider, gate, waitMillis = {}).run(plan) {} }.isFailure)
        assertEquals(1, backend.actions)
        assertEquals(0, backend.backs)
    }

    @Test fun monotonicRunClockIsIndependentOfScreenshotEpochClock() = runBlocking {
        val epoch = 1_790_000_000_000L
        var elapsed = 42L
        val backend = Backend().apply { captureTime = { epoch } }
        val original = plan()
        val plan = original.copy(steps = original.steps.map { it.copy(postcondition = it.postcondition.copy(visualQuestion = "Is done visible?")) })
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend, nowMillis = { elapsed })
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                elapsed += 20_000L
                return ModelResponse("{\"decision\":\"pass\"}")
            }
        }
        val result = DemoWorkflowRunner(backend, backend, provider, gate, nowMillis = { elapsed }, waitMillis = {}, wallTimeMillis = { epoch + elapsed - 42L }).run(plan) {}
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.modelCalls)
    }

    @Test fun truncatedModelOutputCannotVerifyEvenValidJson() = runBlocking {
        val backend = Backend()
        val original = plan()
        val plan = original.copy(steps = original.steps.map { it.copy(postcondition = it.postcondition.copy(visualQuestion = "Is done visible?")) })
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        val provider = object : LLMProvider {
            override suspend fun generate(request: ModelRequest) = ModelResponse("{\"decision\":\"pass\"}", stopReason = "length")
        }
        val failure = runCatching { DemoWorkflowRunner(backend, backend, provider, gate, waitMillis = {}).run(plan) {} }.exceptionOrNull()
        assertTrue(failure?.message?.contains("截断") == true)
        assertEquals(1, backend.actions)
    }

    @Test fun networkPageCanSettleLocallyWithinFiveSecondsWithoutModel() = runBlocking {
        val backend = Backend().apply { transition = false }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        var waited = 0L
        val result = DemoWorkflowRunner(backend, backend, null, gate, waitMillis = { millis ->
            waited += millis
            if (waited >= 3_000L) backend.nodes = listOf(node("0.9", "Done"))
        }).run(plan) {}
        assertEquals(3_000L, waited)
        assertEquals(1, result.completedSteps)
        assertEquals(0, result.modelCalls)
    }

    @Test fun localPostconditionPollingStopsAtFiveSeconds() = runBlocking {
        val backend = Backend().apply { transition = false }
        val plan = plan()
        val gate = DemoWorkflowActionGateway(plan, { true }, {}, backend)
        var waited = 0L
        assertTrue(runCatching {
            DemoWorkflowRunner(backend, backend, null, gate, waitMillis = { waited += it }).run(plan) {}
        }.isFailure)
        assertEquals(5_000L, waited)
        assertEquals(1, backend.actions)
    }
}
