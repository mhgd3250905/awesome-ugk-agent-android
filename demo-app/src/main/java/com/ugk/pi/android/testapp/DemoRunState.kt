package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolCall

/**
 * The small set of states that the demo UI needs to render.
 *
 * Tool states remain busy because the runtime may immediately start another
 * model iteration after a tool result. Only terminal states release the
 * composer.
 */
enum class DemoRunStatus(
    val label: String,
    val defaultDetail: String,
    val isBusy: Boolean,
    val canRetry: Boolean
) {
    IDLE("就绪", "可以开始新任务", isBusy = false, canRetry = false),
    THINKING("思考中", "正在分析请求", isBusy = true, canRetry = false),
    TOOL_RUNNING("调用工具", "正在执行工具", isBusy = true, canRetry = false),
    WAITING_CONFIRMATION("等待确认", "等待你的确认", isBusy = true, canRetry = false),
    TOOL_SUCCESS("工具完成", "工具已返回结果", isBusy = true, canRetry = false),
    TOOL_FAILURE("工具失败", "工具返回了错误", isBusy = true, canRetry = false),
    COMPLETED("已完成", "任务已完成", isBusy = false, canRetry = false),
    FAILED("失败", "任务执行失败", isBusy = false, canRetry = true),
    CANCELLED("已停止", "任务已停止", isBusy = false, canRetry = true)
}

/** Alias that reads naturally in code that calls the value a phase. */
typealias DemoRunPhase = DemoRunStatus

enum class DemoRunStepKind {
    MODEL,
    TOOL,
    OUTCOME
}

/**
 * One compact item in the process timeline.
 *
 * [detailLabel] is a compact one-line status; [resultSummary] keeps the
 * complete observable tool/result text for the expanded process view.
 * [isExpanded] is local UI state and defaults to collapsed.
 */
data class DemoRunStep(
    val id: String,
    val kind: DemoRunStepKind,
    val title: String,
    val status: DemoRunStatus,
    val detailLabel: String = status.defaultDetail,
    val resultSummary: String? = null,
    val isExpanded: Boolean = false
) {
    val statusLabel: String
        get() = status.label

    val summary: String?
        get() = resultSummary

    val expanded: Boolean
        get() = isExpanded

    fun toggleExpanded(): DemoRunStep = copy(isExpanded = !isExpanded)

    fun setExpanded(expanded: Boolean): DemoRunStep = copy(isExpanded = expanded)
}

/**
 * Immutable, render-ready snapshot of one Agent run.
 *
 * UI code can consume [statusLabel], [detailLabel], [isBusy], [canRetry] and
 * [steps] without knowing anything about the SDK event stream.
 */
data class DemoRunState(
    val status: DemoRunStatus = DemoRunStatus.IDLE,
    val detailLabel: String = status.defaultDetail,
    val steps: List<DemoRunStep> = emptyList(),
    val detailsExpanded: Boolean = false,
    val resultSummary: String? = null,
    val sessionId: String? = null,
    val taskId: String? = null
) {
    val statusLabel: String
        get() = status.label

    val isBusy: Boolean
        get() = status.isBusy

    val canRetry: Boolean
        get() = status.canRetry

    val phase: DemoRunPhase
        get() = status

    val isProcessExpanded: Boolean
        get() = detailsExpanded

    val latestResultSummary: String?
        get() = resultSummary

    /** Apply one SDK event without exposing the reducer implementation. */
    fun reduce(event: AgentEvent): DemoRunState = DemoRunStateReducer.reduce(this, event)

    /** Synonym for callers that prefer event/update terminology. */
    fun update(event: AgentEvent): DemoRunState = reduce(event)

    fun toggleDetails(): DemoRunState = copy(detailsExpanded = !detailsExpanded)

    fun toggleProcessDetails(): DemoRunState = toggleDetails()

    fun setDetailsExpanded(expanded: Boolean): DemoRunState = copy(detailsExpanded = expanded)

    fun toggleStep(stepId: String): DemoRunState {
        if (stepId.isBlank()) return this
        return setStepExpanded(
            stepId = stepId,
            expanded = !steps.firstOrNull { it.id == stepId }?.isExpanded.orFalse()
        )
    }

    fun setStepExpanded(stepId: String, expanded: Boolean): DemoRunState {
        if (stepId.isBlank()) return this
        if (steps.none { it.id == stepId }) return this
        return copy(steps = steps.map { step ->
            if (step.id == stepId) step.setExpanded(expanded) else step
        })
    }

    /** Mark an in-flight run as stopped when the Flow is cancelled externally. */
    fun cancel(reason: String = "任务已停止"): DemoRunState =
        DemoRunStateReducer.cancel(this, reason)

    fun cancelled(reason: String = "任务已停止"): DemoRunState = cancel(reason)

    fun reset(): DemoRunState = initial()

    companion object {
        const val MAX_RESULT_SUMMARY_LENGTH: Int = DemoRunText.MAX_SUMMARY_LENGTH
        const val MAX_DETAIL_LENGTH: Int = DemoRunText.MAX_DETAIL_LENGTH
        const val MAX_STEP_COUNT: Int = DemoRunStateReducer.MAX_STEP_COUNT

        fun initial(): DemoRunState = DemoRunState()

        fun idle(): DemoRunState = initial()

        fun from(event: AgentEvent): DemoRunState = initial().reduce(event)

        fun reduce(state: DemoRunState, event: AgentEvent): DemoRunState =
            DemoRunStateReducer.reduce(state, event)
    }
}

/** Central event-to-snapshot mapper. It is stateless and safe to call on any thread. */
object DemoRunStateReducer {
    const val MAX_STEP_COUNT: Int = 50

    private const val MODEL_STEP_PREFIX = "model:"
    private const val OUTCOME_STEP_ID = "outcome"
    private const val MAX_TITLE_LENGTH = 80
    private const val MAX_STEP_ID_LENGTH = 96
    private const val CONFIRMATION_TOOL = "show_user_confirmation_dialog"

    fun reduce(state: DemoRunState, event: AgentEvent): DemoRunState = when (event) {
        is AgentEvent.Started -> state.copy(
            status = DemoRunStatus.THINKING,
            detailLabel = "正在准备任务",
            steps = emptyList(),
            detailsExpanded = false,
            resultSummary = null,
            sessionId = event.sessionId,
            taskId = event.taskId
        )

        is AgentEvent.ModelRequestStarted -> onModelRequestStarted(state, event)
        is AgentEvent.ModelResponded -> onModelResponded(state, event)
        is AgentEvent.ToolStarted -> onToolStarted(state, event.call)
        is AgentEvent.ToolProgress -> onToolProgress(state, event.call, event.progress)
        is AgentEvent.ToolFinished -> onToolFinished(state, event.result)
        is AgentEvent.Completed -> onCompleted(state, event.content)
        is AgentEvent.Failed -> onFailed(state, event.message)
        is AgentEvent.UserMessageAppended -> onUserMessageAppended(state)
    }

    fun cancel(state: DemoRunState, reason: String = "任务已停止"): DemoRunState {
        if (!state.isBusy) return state

        val summary = DemoRunText.summarize(reason)
        val detail = summary ?: DemoRunStatus.CANCELLED.defaultDetail
        val outcome = DemoRunStep(
            id = OUTCOME_STEP_ID,
            kind = DemoRunStepKind.OUTCOME,
            title = DemoRunStatus.CANCELLED.label,
            status = DemoRunStatus.CANCELLED,
            detailLabel = detail,
            resultSummary = summary
        )
        return state.copy(
            status = DemoRunStatus.CANCELLED,
            detailLabel = detail,
            resultSummary = summary
        ).withStep(outcome)
    }

    private fun onModelRequestStarted(
        state: DemoRunState,
        event: AgentEvent.ModelRequestStarted
    ): DemoRunState {
        val detail = DemoRunText.summarize(
            text = "第 ${event.iteration} 轮 · 上下文 ${event.messageCount} 条 · 可用工具 ${event.toolCount} 个",
            maxLength = DemoRunText.MAX_DETAIL_LENGTH
        ) ?: DemoRunStatus.THINKING.defaultDetail
        val step = DemoRunStep(
            id = "$MODEL_STEP_PREFIX${event.iteration}",
            kind = DemoRunStepKind.MODEL,
            title = DemoRunStatus.THINKING.label,
            status = DemoRunStatus.THINKING,
            detailLabel = detail
        )
        return state.copy(
            status = DemoRunStatus.THINKING,
            detailLabel = detail,
            resultSummary = null
        ).withStep(step)
    }

    private fun onModelResponded(
        state: DemoRunState,
        event: AgentEvent.ModelResponded
    ): DemoRunState {
        val toolCount = event.toolCalls.size
        val detail = if (toolCount > 0) {
            "已规划 $toolCount 个工具步骤"
        } else {
            "正在整理最终回答"
        }
        val currentModelStep = state.steps.asReversed().firstOrNull {
            it.kind == DemoRunStepKind.MODEL && it.status == DemoRunStatus.THINKING
        }
        val step = if (currentModelStep != null) {
            currentModelStep.copy(
                title = if (toolCount > 0) "已完成分析" else "已生成回答",
                status = DemoRunStatus.COMPLETED,
                detailLabel = detail
            )
        } else {
            DemoRunStep(
                id = "$MODEL_STEP_PREFIX response:${state.steps.count { it.kind == DemoRunStepKind.MODEL }}",
                kind = DemoRunStepKind.MODEL,
                title = if (toolCount > 0) "已完成分析" else "已生成回答",
                status = DemoRunStatus.COMPLETED,
                detailLabel = detail
            )
        }
        return state.copy(
            status = DemoRunStatus.THINKING,
            detailLabel = detail,
            resultSummary = null
        ).withStep(step)
    }

    private fun onToolStarted(state: DemoRunState, call: ToolCall): DemoRunState {
        val waiting = isConfirmationTool(call.name)
        val status = if (waiting) {
            DemoRunStatus.WAITING_CONFIRMATION
        } else {
            DemoRunStatus.TOOL_RUNNING
        }
        val toolName = toolName(call.name)
        val detail = if (waiting) "等待用户确认后继续" else "正在执行"
        val step = DemoRunStep(
            id = toolStepId(call.id),
            kind = DemoRunStepKind.TOOL,
            title = toolName,
            status = status,
            detailLabel = detail
        )
        return state.copy(
            status = status,
            detailLabel = combinedDetail(toolName, detail),
            resultSummary = null
        ).withStep(step)
    }

    private fun onToolProgress(
        state: DemoRunState,
        call: ToolCall,
        progress: com.ugk.pi.android.ToolProgress
    ): DemoRunState {
        val existing = state.steps.firstOrNull { it.id == toolStepId(call.id) }
        val waiting = isConfirmationTool(call.name) ||
            existing?.status == DemoRunStatus.WAITING_CONFIRMATION
        val status = if (waiting) {
            DemoRunStatus.WAITING_CONFIRMATION
        } else {
            DemoRunStatus.TOOL_RUNNING
        }
        val toolName = existing?.title ?: toolName(call.name)
        val detail = progressDetail(progress, waiting)
        val step = DemoRunStep(
            id = toolStepId(call.id),
            kind = DemoRunStepKind.TOOL,
            title = toolName,
            status = status,
            detailLabel = detail
        )
        return state.copy(
            status = status,
            detailLabel = combinedDetail(toolName, detail),
            resultSummary = null
        ).withStep(step)
    }

    private fun onToolFinished(
        state: DemoRunState,
        result: com.ugk.pi.android.ToolResult
    ): DemoRunState {
        val status = if (result.isError) {
            DemoRunStatus.TOOL_FAILURE
        } else {
            DemoRunStatus.TOOL_SUCCESS
        }
        val toolName = toolName(result.name)
        val summary = DemoRunText.fullText(result.content)
        val detail = if (result.isError) "工具执行失败" else "工具已完成"
        val step = DemoRunStep(
            id = toolStepId(result.toolCallId),
            kind = DemoRunStepKind.TOOL,
            title = toolName,
            status = status,
            detailLabel = detail,
            resultSummary = summary
        )
        return state.copy(
            status = status,
            detailLabel = combinedDetail(toolName, detail),
            resultSummary = summary
        ).withStep(step)
    }

    private fun onCompleted(state: DemoRunState, content: String): DemoRunState {
        val summary = DemoRunText.fullText(content)
        val detail = "回答已生成"
        val step = DemoRunStep(
            id = OUTCOME_STEP_ID,
            kind = DemoRunStepKind.OUTCOME,
            title = DemoRunStatus.COMPLETED.label,
            status = DemoRunStatus.COMPLETED,
            detailLabel = detail,
            resultSummary = summary
        )
        return state.copy(
            status = DemoRunStatus.COMPLETED,
            detailLabel = detail,
            resultSummary = summary
        ).withStep(step)
    }

    private fun onFailed(state: DemoRunState, message: String): DemoRunState {
        val summary = DemoRunText.fullText(message)
        val detail = DemoRunText.summarize(
            message,
            maxLength = DemoRunText.MAX_DETAIL_LENGTH
        ) ?: DemoRunStatus.FAILED.defaultDetail
        val step = DemoRunStep(
            id = OUTCOME_STEP_ID,
            kind = DemoRunStepKind.OUTCOME,
            title = DemoRunStatus.FAILED.label,
            status = DemoRunStatus.FAILED,
            detailLabel = detail,
            resultSummary = summary
        )
        return state.copy(
            status = DemoRunStatus.FAILED,
            detailLabel = detail,
            resultSummary = summary
        ).withStep(step)
    }

    private fun onUserMessageAppended(state: DemoRunState): DemoRunState {
        if (!state.isBusy) return state
        return state.copy(detailLabel = "已收到追加消息，继续处理")
    }

    private fun DemoRunState.withStep(step: DemoRunStep): DemoRunState {
        val index = steps.indexOfFirst { it.id == step.id }
        val replacement = if (index >= 0) {
            step.copy(isExpanded = steps[index].isExpanded)
        } else {
            step
        }
        val nextSteps = if (index >= 0) {
            steps.toMutableList().also { it[index] = replacement }
        } else {
            (steps + replacement).toMutableList()
        }
        return copy(steps = nextSteps.takeLast(MAX_STEP_COUNT))
    }

    private fun toolName(name: String): String =
        DemoRunText.summarize(name, MAX_TITLE_LENGTH) ?: "工具"

    private fun combinedDetail(toolName: String, detail: String): String =
        DemoRunText.summarize(
            "$toolName · $detail",
            DemoRunText.MAX_DETAIL_LENGTH
        ) ?: detail

    private fun toolStepId(callId: String): String {
        val visibleId = DemoRunText.summarize(callId, MAX_STEP_ID_LENGTH) ?: "unknown"
        return "tool:$visibleId"
    }

    private fun progressDetail(
        progress: com.ugk.pi.android.ToolProgress,
        waiting: Boolean
    ): String {
        val parts = mutableListOf<String>()
        DemoRunText.summarize(progress.title, DemoRunText.MAX_DETAIL_LENGTH)?.let(parts::add)
        DemoRunText.summarize(progress.detail, DemoRunText.MAX_DETAIL_LENGTH)?.let(parts::add)
        if (progress.current != null || progress.total != null) {
            parts += "进度 ${progress.current ?: "?"}/${progress.total ?: "?"}"
        }
        if (waiting && parts.isEmpty()) {
            parts += "等待用户确认后继续"
        }
        return DemoRunText.summarize(
            parts.joinToString(" · "),
            DemoRunText.MAX_DETAIL_LENGTH
        ) ?: if (waiting) "等待用户确认后继续" else "正在执行"
    }

    private fun isConfirmationTool(name: String): Boolean =
        name.equals(CONFIRMATION_TOOL, ignoreCase = true) ||
            name.contains("confirmation", ignoreCase = true) ||
            name.contains("confirm", ignoreCase = true)
}

/** Bounded, single-line summaries for the main UI state. */
object DemoRunText {
    const val MAX_SUMMARY_LENGTH: Int = 240
    const val MAX_DETAIL_LENGTH: Int = 160

    /** Preserve observable output for the expanded card, without adding an ellipsis. */
    fun fullText(text: String?): String? = text
        ?.takeIf { it.isNotBlank() }

    /**
     * Creates a compact label for the collapsed process card. This is never
     * used for the chat message or the expanded result details.
     */
    fun summarize(text: String?, maxLength: Int = MAX_SUMMARY_LENGTH): String? {
        if (text == null) return null
        require(maxLength > 0) { "maxLength must be greater than zero" }

        val output = StringBuilder(minOf(text.length, maxLength))
        var pendingSpace = false
        var index = 0
        var truncated = false

        while (index < text.length) {
            val character = text[index]
            if (character.isWhitespace()) {
                if (output.isNotEmpty()) pendingSpace = true
                index++
                continue
            }

            val requiredLength = if (pendingSpace && output.isNotEmpty()) 2 else 1
            if (output.length + requiredLength > maxLength) {
                truncated = true
                break
            }
            if (pendingSpace && output.isNotEmpty()) output.append(' ')
            output.append(character)
            pendingSpace = false
            index++
        }

        var value = output.toString().trimEnd()
        if (truncated) {
            value = if (maxLength == 1) {
                "…"
            } else {
                value.take(maxLength - 1).trimEnd() + "…"
            }
        }
        return value.takeIf { it.isNotEmpty() }
    }

    private fun StringBuilder.isNotEmpty(): Boolean = length > 0
}

fun reduceDemoRunState(state: DemoRunState, event: AgentEvent): DemoRunState =
    DemoRunStateReducer.reduce(state, event)

fun AgentEvent.toDemoRunState(previous: DemoRunState = DemoRunState.initial()): DemoRunState =
    previous.reduce(this)

private fun Boolean?.orFalse(): Boolean = this == true
