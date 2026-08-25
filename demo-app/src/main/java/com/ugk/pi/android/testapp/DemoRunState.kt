package com.ugk.pi.android.testapp

import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * 语义化工具映射与参数解析器，为过程展示提供直观清晰的 Summary 与 动作描述。
 */
object DemoToolSemanticMapper {
    fun friendlyName(name: String): String = when (name.lowercase()) {
        "screen_read_ui_tree" -> "读取屏幕 UI 树"
        "screen_perform_action" -> "执行屏幕控件操作"
        "screen_launch_app" -> "启动应用"
        "screen_gesture" -> "执行屏幕手势"
        "screen_press_key" -> "触发按键"
        "screen_global_action" -> "系统全局按键"
        "get_android_accessibility_status" -> "检查无障碍服务状态"
        "show_user_confirmation_dialog" -> "请求用户确认授权"
        "bash", "terminal_exec" -> "执行终端命令"
        "file_read" -> "读取私有文件"
        "file_write" -> "写入私有文件"
        "file_list" -> "列出私有目录"
        "schedule_create" -> "创建定时任务"
        "schedule_list" -> "查看定时任务"
        "schedule_cancel" -> "取消定时任务"
        "system_open_url" -> "打开系统链接"
        "system_request_permission" -> "请求系统权限"
        else -> name
    }

    fun formatInputSummary(name: String, input: JsonObject): String = when (name.lowercase()) {
        "screen_launch_app" -> {
            val pkg = input["packageName"]?.jsonPrimitive?.contentOrNull
            val app = input["appName"]?.jsonPrimitive?.contentOrNull
            when {
                !app.isNullOrBlank() -> "应用: $app"
                !pkg.isNullOrBlank() -> "包名: $pkg"
                else -> "准备启动应用"
            }
        }
        "screen_perform_action" -> {
            val action = input["action"]?.jsonPrimitive?.contentOrNull ?: "操作"
            val text = input["text"]?.jsonPrimitive?.contentOrNull
            val nodeId = input["nodeId"]?.jsonPrimitive?.contentOrNull
            when {
                !text.isNullOrBlank() -> "输入: \"$text\" (节点 $nodeId)"
                !nodeId.isNullOrBlank() -> "操作: $action (节点 $nodeId)"
                else -> "操作: $action"
            }
        }
        "screen_gesture" -> {
            val gesture = input["gesture"]?.jsonPrimitive?.contentOrNull ?: "手势"
            "手势: $gesture"
        }
        "screen_press_key" -> {
            val key = input["key"]?.jsonPrimitive?.contentOrNull ?: "按键"
            "按键: $key"
        }
        "screen_read_ui_tree" -> "读取当前屏幕可见 UI 元素"
        "bash", "terminal_exec" -> {
            val cmd = input["command"]?.jsonPrimitive?.contentOrNull ?: input["cmd"]?.jsonPrimitive?.contentOrNull
            if (!cmd.isNullOrBlank()) "命令: $cmd" else "执行 Shell 命令"
        }
        "file_read", "file_write" -> {
            val path = input["path"]?.jsonPrimitive?.contentOrNull ?: input["file"]?.jsonPrimitive?.contentOrNull
            if (!path.isNullOrBlank()) "文件: $path" else "文件操作"
        }
        "show_user_confirmation_dialog" -> {
            val message = input["message"]?.jsonPrimitive?.contentOrNull ?: input["prompt"]?.jsonPrimitive?.contentOrNull
            if (!message.isNullOrBlank()) "提示: $message" else "等待授权确认"
        }
        else -> {
            val first = input.entries.firstOrNull()
            if (first != null) "${first.key}: ${first.value}" else "执行工具操作"
        }
    }

    fun formatResultSummary(result: ToolResult): String {
        if (result.isError) {
            val err = result.content.lines().firstOrNull { it.isNotBlank() } ?: result.content
            return "执行失败: ${err.take(60)}"
        }
        return when (result.name.lowercase()) {
            "screen_read_ui_tree" -> {
                val nodeCount = Regex("""nodeCount=(\d+)""").find(result.content)?.groupValues?.get(1)
                if (nodeCount != null) "已读取屏幕 UI（共 $nodeCount 个节点）" else "已获取屏幕 UI 结构"
            }
            "screen_perform_action" -> "控件操作已执行完成"
            "screen_launch_app" -> "已发起目标应用启动"
            "screen_gesture" -> "手势操作已完成"
            "bash", "terminal_exec" -> {
                val lines = result.content.lines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) "输出: ${lines.first().take(60)}" else "命令执行成功"
            }
            else -> "工具已返回结果"
        }
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
        val detail = "第 ${event.iteration} 轮 · 正在分析需求并规划操作"
        val step = DemoRunStep(
            id = "$MODEL_STEP_PREFIX${event.iteration}",
            kind = DemoRunStepKind.MODEL,
            title = "第 ${event.iteration} 轮 · 思考规划",
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
        val hasTools = toolCount > 0

        // 优先提取大模型输出的意图文案（content）或深度思考（reasoningContent）
        val rawSummary = event.content.trim().ifBlank {
            event.reasoningContent?.trim()
        }

        val (title, detail, fullSummary) = if (hasTools) {
            val toolsDesc = event.toolCalls.joinToString("、") { DemoToolSemanticMapper.friendlyName(it.name) }
            val intentText = if (!rawSummary.isNullOrBlank()) {
                rawSummary.lines().firstOrNull { it.isNotBlank() } ?: rawSummary
            } else {
                "规划执行 $toolCount 个操作：$toolsDesc"
            }
            val compactDetail = DemoRunText.summarize(intentText, DemoRunText.MAX_DETAIL_LENGTH)
                ?: "已规划 $toolCount 个工具步骤"
            val modelTurnIndex = (state.steps.count { it.kind == DemoRunStepKind.MODEL }).coerceAtLeast(1)
            Triple(
                "第 $modelTurnIndex 轮 · 意图规划",
                compactDetail,
                rawSummary ?: "规划调用工具：$toolsDesc"
            )
        } else {
            val isDirectAnswer = state.steps.none { it.kind == DemoRunStepKind.TOOL }
            val intentText = if (!rawSummary.isNullOrBlank()) {
                rawSummary.lines().firstOrNull { it.isNotBlank() } ?: rawSummary
            } else {
                "已生成最终回答"
            }
            val compactDetail = DemoRunText.summarize(intentText, DemoRunText.MAX_DETAIL_LENGTH)
                ?: if (isDirectAnswer) "已完成需求分析并生成回答" else "已整合工具结果并生成回答"
            Triple(
                if (isDirectAnswer) "直接生成回答" else "整合结果并生成回答",
                compactDetail,
                rawSummary ?: event.content
            )
        }

        val currentModelStep = state.steps.asReversed().firstOrNull {
            it.kind == DemoRunStepKind.MODEL && it.status == DemoRunStatus.THINKING
        }
        val step = if (currentModelStep != null) {
            currentModelStep.copy(
                title = title,
                status = DemoRunStatus.COMPLETED,
                detailLabel = detail,
                resultSummary = fullSummary
            )
        } else {
            DemoRunStep(
                id = "$MODEL_STEP_PREFIX response:${state.steps.count { it.kind == DemoRunStepKind.MODEL }}",
                kind = DemoRunStepKind.MODEL,
                title = title,
                status = DemoRunStatus.COMPLETED,
                detailLabel = detail,
                resultSummary = fullSummary
            )
        }
        return state.copy(
            status = DemoRunStatus.THINKING,
            detailLabel = detail,
            resultSummary = fullSummary
        ).withStep(step)
    }

    private fun onToolStarted(state: DemoRunState, call: ToolCall): DemoRunState {
        val waiting = isConfirmationTool(call.name)
        val status = if (waiting) {
            DemoRunStatus.WAITING_CONFIRMATION
        } else {
            DemoRunStatus.TOOL_RUNNING
        }
        val toolFriendly = DemoToolSemanticMapper.friendlyName(call.name)
        val inputSummary = DemoToolSemanticMapper.formatInputSummary(call.name, call.input)
        val detail = if (waiting) "等待用户确认: $inputSummary" else inputSummary
        val step = DemoRunStep(
            id = toolStepId(call.id),
            kind = DemoRunStepKind.TOOL,
            title = "[动作] $toolFriendly",
            status = status,
            detailLabel = detail,
            resultSummary = "【输入参数】\n${call.input}"
        )
        return state.copy(
            status = status,
            detailLabel = combinedDetail(toolFriendly, detail),
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
        val toolFriendly = existing?.title ?: "[动作] ${DemoToolSemanticMapper.friendlyName(call.name)}"
        val detail = progressDetail(progress, waiting)
        val step = DemoRunStep(
            id = toolStepId(call.id),
            kind = DemoRunStepKind.TOOL,
            title = toolFriendly,
            status = status,
            detailLabel = detail,
            resultSummary = existing?.resultSummary
        )
        return state.copy(
            status = status,
            detailLabel = combinedDetail(toolFriendly, detail),
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
        val toolFriendly = "[动作] ${DemoToolSemanticMapper.friendlyName(result.name)}"
        val existing = state.steps.firstOrNull { it.id == toolStepId(result.toolCallId) }
        val resultDetail = DemoToolSemanticMapper.formatResultSummary(result)

        val fullResultSummary = buildString {
            if (!existing?.resultSummary.isNullOrBlank()) {
                append(existing?.resultSummary)
                append("\n\n")
            }
            append("【执行结果】\n")
            append(result.content)
        }
        val step = DemoRunStep(
            id = toolStepId(result.toolCallId),
            kind = DemoRunStepKind.TOOL,
            title = toolFriendly,
            status = status,
            detailLabel = resultDetail,
            resultSummary = fullResultSummary
        )
        return state.copy(
            status = status,
            detailLabel = combinedDetail(toolFriendly, resultDetail),
            resultSummary = DemoRunText.fullText(result.content)
        ).withStep(step)
    }

    private fun onCompleted(state: DemoRunState, content: String): DemoRunState {
        val summary = DemoRunText.fullText(content)
        val hasToolSteps = state.steps.any { it.kind == DemoRunStepKind.TOOL }

        if (!hasToolSteps) {
            // 单轮直接问答（无工具）：保留单个清晰的“直接生成回答”步骤，避免重复
            val singleStep = DemoRunStep(
                id = OUTCOME_STEP_ID,
                kind = DemoRunStepKind.OUTCOME,
                title = "直接生成回答",
                status = DemoRunStatus.COMPLETED,
                detailLabel = "已分析需求并生成回答",
                resultSummary = summary
            )
            return state.copy(
                status = DemoRunStatus.COMPLETED,
                detailLabel = "回答已生成",
                steps = listOf(singleStep),
                resultSummary = summary
            )
        } else {
            // 多步工具执行：添加最终任务总结完成步骤
            val outcomeStep = DemoRunStep(
                id = OUTCOME_STEP_ID,
                kind = DemoRunStepKind.OUTCOME,
                title = "任务已完成",
                status = DemoRunStatus.COMPLETED,
                detailLabel = "已完成全部工具调用并整合回答",
                resultSummary = summary
            )
            return state.copy(
                status = DemoRunStatus.COMPLETED,
                detailLabel = "回答已生成",
                resultSummary = summary
            ).withStep(outcomeStep)
        }
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
        DemoRunText.summarize(DemoToolSemanticMapper.friendlyName(name), MAX_TITLE_LENGTH) ?: "工具"

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
