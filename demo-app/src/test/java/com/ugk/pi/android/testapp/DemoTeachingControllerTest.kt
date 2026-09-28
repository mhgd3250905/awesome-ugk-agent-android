package com.ugk.pi.android.testapp

import com.ugk.pi.android.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DemoTeachingControllerTest {
    @Test fun resumedHistoryWaitsForNewInstructionAndRequiresFreshScreenBeforeAction() {
        val finished = CountDownLatch(1)
        var requests = 0
        var clicks = 0
        var observations = 0
        var recoveredHistory = false
        val previous = DemoTeachingRecord("old", "检查应用更新", 1, 1, "interrupted", listOf(
            DemoTeachingSegment("a", "打开商店", "completed", "已打开"),
            DemoTeachingSegment("b", "纠正：仅检查，不安装", "interrupted", "未完成")
        ))
        val controller = DemoTeachingController(runtimeFactory = { decorator ->
            val registry = ToolRegistry()
            listOf("screen_gesture", "screen_read_ui_tree").forEach { toolName -> registry.register(decorator.decorate(object : AgentTool {
                override val name = toolName
                override val description = "test"
                override val inputSchema = JsonObject(emptyMap())
                override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                    if (name == "screen_gesture") clicks++ else observations++
                    return ToolResult(call.id, name, "ok")
                }
            })) }
            AgentRuntime.Builder().toolRegistry(registry).llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest): ModelResponse {
                    recoveredHistory = request.messages.filterIsInstance<AgentMessage.User>().any {
                        it.content.contains("纠正：仅检查，不安装") && it.content.contains("interrupted")
                    }
                    val name = when (requests++) { 0, 2 -> "screen_gesture"; 1 -> "screen_read_ui_tree"; else -> null }
                    return if (name == null) ModelResponse(content = "完成") else ModelResponse(
                        content = "", toolCalls = listOf(ToolCall("call-$requests", name, JsonObject(emptyMap()))))
                }
            }).build()
        }, hooks = object : DemoTeachingController.Hooks {
            override fun onSegmentFinished(segmentId: String, event: AgentEvent) { finished.countDown() }
        }, coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined))
        try {
            assertTrue(controller.start(previous).isSuccess)
            assertEquals(0, requests)
            assertFalse(controller.snapshot().isRunning)
            assertEquals(1, controller.snapshot().completedSegments)
            assertTrue(controller.submit("继续查看待更新列表").isSuccess)
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(recoveredHistory)
            assertEquals(1, observations)
            assertEquals(1, clicks)
        } finally { controller.cancel() }
    }

    @Test
    fun runtimePluginsCloseOnFailedStartAndNormalFinish() {
        var closes = 0
        var cancellations = 0
        val factory: (AgentToolDecorator) -> AgentRuntime = {
            AgentRuntime.Builder().register(object : AgentCapabilityPlugin {
                override val id = "test-resources"
                override fun tools() = emptyList<AgentTool>()
                override fun skills() = emptyList<AndroidSkill>()
                override fun cancelAll(): Int { cancellations++; return 0 }
                override fun close() { closes++ }
            }).llmProvider(object : LLMProvider {
                override suspend fun generate(request: ModelRequest) = ModelResponse(content = "done")
            }).build()
        }
        val failed = DemoTeachingController(factory, object : DemoTeachingController.Hooks {
            override fun onStarted(sessionId: String) { error("disk unavailable") }
        }, DemoAgentRunCoordinator(Dispatchers.Unconfined))
        assertTrue(failed.start().isFailure)
        assertEquals(1, closes)
        assertEquals(1, cancellations)
        assertFalse(DemoCapabilityInterlock.isScreenOperationOwned())
        val normal = DemoTeachingController(factory, coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined))
        try {
            assertTrue(normal.start().isSuccess)
            normal.finish()
            assertEquals(2, closes)
            assertEquals(2, cancellations)
        } finally {
            normal.cancel()
        }
    }

    @Test
    fun stoppingSegmentKeepsTeachingAndAllowsNextInstructionAfterCleanup() {
        val entered = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        var requests = 0
        val controller = DemoTeachingController(
            runtimeFactory = {
                AgentRuntime.Builder().llmProvider(object : LLMProvider {
                    override suspend fun generate(request: ModelRequest): ModelResponse {
                        if (++requests == 1) {
                            entered.countDown()
                            awaitCancellation()
                        }
                        return ModelResponse(content = "next segment done")
                    }
                }).build()
            },
            coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        )
        controller.attach(this, onChanged = { snapshot ->
            if (snapshot.active && !snapshot.isRunning && requests > 0) {
                if (snapshot.completedSegments == 0) firstFinished.countDown() else secondFinished.countDown()
            }
        })
        try {
            assertTrue(controller.start().isSuccess)
            val sessionId = controller.snapshot().sessionId
            assertTrue(controller.submit("first").isSuccess)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.stopSegment()
            assertTrue(firstFinished.await(2, TimeUnit.SECONDS))
            assertTrue(controller.snapshot().active)
            assertTrue(DemoCapabilityInterlock.isScreenOperationOwned())
            assertEquals(0, controller.snapshot().completedSegments)
            assertTrue(controller.submit("correction").isSuccess)
            assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
            assertEquals(sessionId, controller.snapshot().sessionId)
            assertEquals(1, controller.snapshot().completedSegments)
            controller.finish()
            assertFalse(controller.snapshot().active)
            assertFalse(DemoCapabilityInterlock.isScreenOperationOwned())
        } finally {
            controller.cancel()
        }
    }

    @Test
    fun cancellationRestoresToolBeforeReleasingTeachingOwnership() {
        val entered = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var ownedDuringRestore = false
        val controller = DemoTeachingController(
            runtimeFactory = { decorator ->
                val tool = object : AgentTool {
                    override val name = "screen_read_ui_tree"
                    override val description = "test"
                    override val inputSchema = JsonObject(emptyMap())
                    override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                        entered.countDown()
                        awaitCancellation()
                    }
                }
                AgentRuntime.Builder()
                    .toolRegistry(ToolRegistry().register(decorator.decorate(tool)))
                    .llmProvider(object : LLMProvider {
                        override suspend fun generate(request: ModelRequest) = ModelResponse(
                            content = "", toolCalls = listOf(ToolCall("call", tool.name, JsonObject(emptyMap())))
                        )
                    }).build()
            },
            hooks = object : DemoTeachingController.Hooks {
                override suspend fun afterTool(segmentId: String, call: ToolCall, result: ToolResult?, error: Throwable?) {
                    ownedDuringRestore = DemoCapabilityInterlock.isScreenOperationOwned()
                    restored.countDown()
                }
                override fun onFinished(cancelled: Boolean) { finished.countDown() }
            },
            coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        )
        try {
            assertTrue(controller.start().isSuccess)
            assertTrue(controller.submit("read screen").isSuccess)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(controller.submit("must wait").isFailure)
            controller.cancel()
            assertTrue(restored.await(2, TimeUnit.SECONDS))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(ownedDuringRestore)
            assertFalse(controller.snapshot().active)
            assertEquals(0, controller.snapshot().completedSegments)
            assertFalse(DemoCapabilityInterlock.isScreenOperationOwned())
        } finally {
            controller.cancel()
        }
    }

    @Test
    fun failedRuntimeConstructionReleasesOwnership() {
        val controller = DemoTeachingController(
            runtimeFactory = { error("missing configuration") },
            coordinator = DemoAgentRunCoordinator(Dispatchers.Unconfined)
        )
        assertTrue(controller.start().isFailure)
        assertFalse(controller.snapshot().active)
        assertFalse(DemoCapabilityInterlock.isScreenOperationOwned())
    }
}
