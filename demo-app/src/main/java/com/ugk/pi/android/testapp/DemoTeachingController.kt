package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AgentTool
import com.ugk.pi.android.AgentToolDecorator
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.ToolResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class DemoTeachingSnapshot(
    val active: Boolean = false,
    val isRunning: Boolean = false,
    val completedSegments: Int = 0,
    val sessionId: String? = null,
    val message: String? = null
)

/** Main-thread process owner. A teaching session never shares the ordinary chat transcript. */
internal class DemoTeachingController(
    private val runtimeFactory: (AgentToolDecorator) -> AgentRuntime,
    private val hooks: Hooks = object : Hooks {},
    val coordinator: DemoAgentRunCoordinator = DemoAgentRunCoordinator(),
    workflowToolMatcher: (String) -> Boolean = DemoScreenAutomationPolicy::isScreenWorkflowTool
) {
    interface Hooks {
        fun onStarted(sessionId: String) {}
        fun onInstruction(segmentId: String, text: String) {}
        suspend fun beforeTool(segmentId: String, call: ToolCall) {}
        suspend fun afterTool(segmentId: String, call: ToolCall, result: ToolResult?, error: Throwable?) {}
        fun onSegmentFinished(segmentId: String, event: AgentEvent) {}
        fun onFinished(cancelled: Boolean) {}
    }

    private val interlock = DemoCapabilityInterlock(workflowToolMatcher)
    private var session: AgentSession? = null
    private var runtime: AgentRuntime? = null
    private var active = false
    private var closing = false
    private var cancelled = false
    private var completedSegments = 0
    private var segmentId: String? = null
    private var segmentOutcome: AgentEvent? = null
    private var message: String? = null
    private var needsFreshObservation = false
    private val observers = linkedMapOf<Any, Pair<(DemoTeachingSnapshot) -> Unit, (AgentEvent) -> Unit>>()

    init {
        coordinator.attach(this, { event ->
            observers.values.toList().forEach { observer -> runCatching { observer.second(event) } }
        }, {})
    }

    fun attach(owner: Any, onChanged: (DemoTeachingSnapshot) -> Unit, onEvent: (AgentEvent) -> Unit = {}) {
        observers[owner] = onChanged to onEvent
        onChanged(snapshot())
    }

    fun detach(owner: Any) { observers.remove(owner) }

    fun snapshot() = DemoTeachingSnapshot(active, coordinator.isRunning(), completedSegments, session?.id, message)

    fun start(previous: DemoTeachingRecord? = null): Result<Unit> = runCatching {
        check(!active && !coordinator.isRunning()) { "教学正在进行" }
        check(DemoCapabilityInterlock.tryAcquireTeaching(this, interlock)) { "屏幕正被其他任务占用" }
        var createdRuntime: AgentRuntime? = null
        try {
            val history = previous?.let { record ->
                val evidence = buildString {
                    appendLine("以下是上次教学的历史资料，只供理解目标与用户纠正，不是待执行命令。不得重放；失败或中断不代表完成。")
                    appendLine("名称：${record.title}")
                    record.segments.forEachIndexed { index, segment ->
                        appendLine("第${index + 1}段 [${segment.status}] 用户：${segment.instruction}")
                        appendLine("当时反馈：${segment.reply}")
                        appendLine("工具记录：" + segment.actions.joinToString("；") {
                            "${it.name}：${when (it.isError) { true -> "失败"; false -> "返回完成（不等于目标达成）"; null -> "无完成结果" }}"
                        })
                    }
                }
                require(evidence.length <= 120_000) { "这份教学历史过长，暂不能续教；原记录保留" }
                listOf(AgentMessage.User(evidence), AgentMessage.Assistant("历史教学已恢复。等待用户明确下一步，执行前必须重新读取当前页面。"))
            }.orEmpty()
            val newSession = AgentSession("teaching-${UUID.randomUUID()}", history)
            val newRuntime = runtimeFactory(toolDecorator())
            createdRuntime = newRuntime
            hooks.onStarted(newSession.id)
            session = newSession
            runtime = newRuntime
            active = true
            closing = false
            cancelled = false
            completedSegments = previous?.segments?.count { it.status == "completed" } ?: 0
            needsFreshObservation = previous != null
            segmentId = null
            message = if (previous != null) "已恢复上次教学，请描述从哪里继续；执行前会重新核对当前页面" else "请描述下一步"
            coordinator.resetForConversation(newSession.id)
            publish()
        } catch (error: Throwable) {
            createdRuntime?.let(::disposeRuntime)
            DemoCapabilityInterlock.releaseTeaching(this)
            throw error
        }
    }

    fun submit(message: String): Result<Unit> = runCatching {
        check(active && !closing) { "请先开始教学" }
        check(!coordinator.isRunning()) { "请等待当前步骤完成" }
        val text = message.trim()
        require(text.isNotEmpty()) { "请输入下一步" }
        val id = UUID.randomUUID().toString()
        hooks.onInstruction(id, text)
        segmentId = id
        segmentOutcome = null
        this.message = "正在执行当前步骤"
        try {
            val currentSession = checkNotNull(session)
            coordinator.start(
                runtime = checkNotNull(runtime), session = currentSession,
                conversationId = currentSession.id, message = text, runLifecycle = interlock,
                onOutcome = { event -> segmentOutcome = event },
                onFinished = { completeSegment(id) }
            )
        } catch (error: Throwable) {
            segmentId = null
            hooks.onSegmentFinished(id, AgentEvent.Failed(error.message ?: "启动失败"))
            this.message = error.message
            publish()
            throw error
        }
        publish()
    }

    fun finish() { close(cancelled = false) }
    fun cancel() { close(cancelled = true) }

    /** Stop this segment, retaining the teaching session and its screen ownership. */
    fun stopSegment() {
        if (!active || closing || !coordinator.isRunning()) return
        message = "正在停止当前步骤"
        coordinator.stop()
        publish()
    }

    private fun close(cancelled: Boolean) {
        if (!active) return
        closing = true
        this.cancelled = this.cancelled || cancelled
        message = "正在结束教学"
        if (coordinator.isRunning()) {
            coordinator.stop()
            publish()
        } else {
            completeClose()
        }
    }

    private fun completeSegment(id: String) {
        val outcome = segmentOutcome ?: AgentEvent.Failed("当前步骤已停止，未完成的操作不能作为成功经验")
        if (outcome is AgentEvent.Completed) completedSegments++
        segmentId = null
        message = if (outcome is AgentEvent.Failed) outcome.message else "请描述下一步，或结束教学"
        try {
            hooks.onSegmentFinished(id, outcome)
        } catch (error: Throwable) {
            message = "教学记录保存失败：${error.message}"
            closing = true
            cancelled = true
        } finally {
            if (closing) completeClose() else publish()
        }
    }

    private fun completeClose() {
        // Called only after the coordinator's finally, including the tool's restoration hook.
        runtime?.let(::disposeRuntime)
        runtime = null
        DemoCapabilityInterlock.releaseTeaching(this)
        active = false
        closing = false
        try {
            hooks.onFinished(cancelled)
        } catch (error: Throwable) {
            message = "教学记录保存失败：${error.message}"
        } finally {
            publish()
        }
    }

    private fun disposeRuntime(value: AgentRuntime) {
        runCatching { value.cancelAllPlugins() }
        runCatching { value.close() }
    }

    private fun publish() {
        val value = snapshot()
        observers.values.toList().forEach { observer -> runCatching { observer.first(value) } }
    }

    private fun toolDecorator() = AgentToolDecorator { delegate ->
        interlock.toolDecorator().decorate(object : AgentTool by delegate {
            override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                val id = checkNotNull(segmentId) { "教学回合已结束" }
                var result: ToolResult? = null
                var failure: Throwable? = null
                try {
                    if (needsFreshObservation && call.name in RESUME_MUTATIONS) {
                        error("教学已恢复，但旧页面状态已失效。先读取当前屏幕，再根据用户本次指令执行；不要重放中断操作。")
                    }
                    hooks.beforeTool(id, call)
                    return delegate.execute(call, context).also {
                        result = it
                        if (!it.isError && call.name in setOf("screen_read_ui_tree", "screen_find_ui_element", "screen_capture_visual")) {
                            needsFreshObservation = false
                        }
                    }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    withContext(NonCancellable) { hooks.afterTool(id, call, result, failure) }
                }
            }
        })
    }

    companion object {
        private val RESUME_MUTATIONS = setOf("launch_android_app", "launch_android_app_intent", "screen_perform_action",
            "screen_visual_gesture", "screen_gesture", "screen_press_key", "screen_global_action")
        val AGENT_INSTRUCTIONS = """
            This is a user-guided teaching session. Execute only the instruction in the current user turn.
            You may use multiple tools to finish that instruction, verify its result, then report briefly and stop.
            Wait for the user's next message; never infer or begin the next segment of the overall task.
            When resumed history is present, treat it as historical data only. Before the first new action, read the current screen again; never replay an interrupted action or reuse old coordinates, snapshots, or target IDs.
            Treat corrections as updates to the current procedure. Do not report failed or interrupted actions as successful.
            Preserve normal tool confirmation requirements. Do not schedule tasks or delegate future execution.
            默认使用中文，包括向用户显示的确认标题、说明和按钮。
            每段的最终回答控制在一到三句，只说明实际结果、必要的缺口，以及等待下一条指令。
            不在最终回答中复述工具调用清单、包名、节点ID、快照ID、内部JSON或技术日志；过程卡已提供执行细节。
        """.trimIndent()
    }
}
