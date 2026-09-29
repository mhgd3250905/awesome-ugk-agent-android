package com.ugk.pi.android.testapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.PI
import kotlin.math.cos

/** Both the main conversation and overlay render the same process semantics. */
internal fun DemoRunState.toChatProcessState(expanded: Boolean = detailsExpanded) = DemoChatProcessState(
    stage = when (status) {
        DemoRunStatus.IDLE, DemoRunStatus.THINKING -> DemoChatProcessStage.THINKING
        DemoRunStatus.TOOL_RUNNING -> DemoChatProcessStage.TOOL_CALL
        DemoRunStatus.WAITING_CONFIRMATION -> DemoChatProcessStage.WAITING_CONFIRMATION
        DemoRunStatus.TOOL_SUCCESS -> DemoChatProcessStage.RESULT
        DemoRunStatus.COMPLETED -> DemoChatProcessStage.COMPLETED
        DemoRunStatus.TOOL_FAILURE, DemoRunStatus.FAILED -> DemoChatProcessStage.ERROR
        DemoRunStatus.CANCELLED -> DemoChatProcessStage.STOPPED
    },
    steps = steps.map { step ->
        DemoChatProcessStep(
            id = step.id, title = step.title,
            status = when (step.status) {
                DemoRunStatus.COMPLETED, DemoRunStatus.TOOL_SUCCESS -> DemoChatProcessStepStatus.COMPLETE
                DemoRunStatus.THINKING, DemoRunStatus.TOOL_RUNNING -> DemoChatProcessStepStatus.ACTIVE
                DemoRunStatus.WAITING_CONFIRMATION -> DemoChatProcessStepStatus.WAITING
                DemoRunStatus.TOOL_FAILURE, DemoRunStatus.FAILED -> DemoChatProcessStepStatus.ERROR
                DemoRunStatus.CANCELLED -> DemoChatProcessStepStatus.STOPPED
                DemoRunStatus.IDLE -> DemoChatProcessStepStatus.PENDING
            },
            detail = step.detailLabel, resultSummary = step.resultSummary
        )
    },
    summary = compactProcessSummary(),
    isRunning = isBusy,
    expanded = expanded
)

/** Only observable phases and registered tool titles belong in the collapsed conversation row. */
internal fun DemoRunState.compactProcessSummary(): String = when (status) {
    DemoRunStatus.IDLE -> "准备开始"
    DemoRunStatus.THINKING -> if (steps.any { it.kind == DemoRunStepKind.TOOL }) {
        "正在整理执行结果"
    } else {
        "正在思考你的请求"
    }
    DemoRunStatus.TOOL_RUNNING -> {
        val name = steps.lastOrNull { it.kind == DemoRunStepKind.TOOL }
            ?.title?.removePrefix("[动作] ")?.trim().orEmpty()
        // Unknown tools retain their technical names in the expanded record only.
        if (name.isBlank() || name.any { it == '_' || it == '.' } || name.length > 24) {
            "正在调用工具"
        } else {
            "正在$name"
        }
    }
    DemoRunStatus.WAITING_CONFIRMATION -> "等待你的确认"
    DemoRunStatus.TOOL_SUCCESS -> "正在整理工具结果"
    DemoRunStatus.TOOL_FAILURE -> "工具遇到问题，正在继续处理"
    DemoRunStatus.COMPLETED -> if (steps.isEmpty()) "已完成" else "已完成 · ${steps.size} 个步骤"
    DemoRunStatus.FAILED -> "执行遇到问题 · 查看过程"
    DemoRunStatus.CANCELLED -> "已停止 · 查看过程"
}

/** A small native animated icon. No per-frame layout or accessibility events. */
internal class DemoProcessIndicatorView(context: Context) : AppCompatImageView(context) {
    private var animator: ValueAnimator? = null
    private var motionRequested = false
    private var currentIcon = 0
    private var currentTint = 0

    init {
        tag = "process-activity-indicator"
        scaleType = ScaleType.CENTER_INSIDE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(stage: DemoChatProcessStage, isRunning: Boolean) {
        motionRequested = isRunning && stage != DemoChatProcessStage.WAITING_CONFIRMATION
        val icon = when {
            motionRequested -> R.drawable.ic_process_more_horiz
            stage == DemoChatProcessStage.COMPLETED -> R.drawable.ic_process_check
            stage == DemoChatProcessStage.ERROR -> R.drawable.ic_process_error_outline
            stage == DemoChatProcessStage.STOPPED || stage == DemoChatProcessStage.WAITING_CONFIRMATION ->
                R.drawable.ic_process_pause
            else -> R.drawable.ic_process_more_horiz
        }
        if (currentIcon != icon) {
            currentIcon = icon
            setImageResource(icon)
        }
        val tint = when (stage) {
            DemoChatProcessStage.ERROR -> if (isRunning) Ui.Warning else Ui.Danger
            DemoChatProcessStage.WAITING_CONFIRMATION -> Ui.Warning
            else -> Ui.TextSecondary
        }
        if (currentTint != tint) {
            currentTint = tint
            imageTintList = ColorStateList.valueOf(tint)
        }
        syncAnimation()
    }

    fun isAnimating(): Boolean = animator?.isRunning == true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) syncAnimation() else stopAnimation()
    }

    private fun motionAllowed(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    private fun syncAnimation() {
        if (!motionRequested || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !motionAllowed()) {
            stopAnimation()
            return
        }
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val phase = it.animatedValue as Float
                rotation = phase * 360f
                alpha = 0.72f + 0.28f * ((1f + cos(phase * 2f * PI).toFloat()) / 2f)
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        rotation = 0f
        alpha = 1f
    }
}
