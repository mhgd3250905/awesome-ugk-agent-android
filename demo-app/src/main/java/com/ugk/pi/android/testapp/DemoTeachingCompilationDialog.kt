package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** A modal, explicitly cancellable teaching compilation with real phase/batch progress. */
internal class DemoTeachingCompilationDialog(
    private val activity: Activity,
    recordTitle: String,
    private val onCancelCompilation: () -> Unit
) : Dialog(activity, Ui.dialogTheme()) {
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private val title = label("正在整理最佳实践", 23f, TaskNoteUi.Ink, true)
    private val detail = label("正在准备教学记录…", 15f, TaskNoteUi.Ink, true)
    private val elapsed = label("", 12f, TaskNoteUi.Secondary)
    private val hint = label("长教学会分批处理，请稍候。原始记录始终保留。", 13f, TaskNoteUi.Secondary)
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(TaskNoteUi.Primary)
        indeterminateTintList = ColorStateList.valueOf(TaskNoteUi.Primary)
        progressBackgroundTintList = ColorStateList.valueOf(TaskNoteUi.Rule)
        isIndeterminate = true
    }
    private val spinner = ProgressBar(activity).apply {
        indeterminateTintList = ColorStateList.valueOf(TaskNoteUi.Primary)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val enteredPhases = mutableSetOf<DemoTeachingCompilationPhase>()
    private val phases = listOf(
        "清理教学记录" to "提取指令、执行结果与关键画面",
        "填写步骤笔记" to "Agent 根据证据梳理每个操作环节",
        "合并教学经验" to "汇集步骤笔记与前后纠正",
        "编写 SOP 指南" to "Agent 整理操作顺序与完成条件",
        "Agent 审核交付" to "回查证据、修订问题并确认交付"
    )
    private val rows = mutableListOf<Triple<TextView, TextView, TextView>>()
    private val phaseList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val cancel: Button = TaskNoteUi.button(activity, "取消整理", false) { onCancelCompilation() }
    private var terminal = false
    private val tick = object : Runnable {
        override fun run() {
            if (!isShowing || terminal) return
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
            elapsed.text = "已用时 ${seconds / 60} 分 ${seconds % 60} 秒"
            handler.postDelayed(this, 1000)
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.asymmetricRounded(activity, TaskNoteUi.Paper, 28, 28, 0, 0)
            setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(24))
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TaskNoteUi.label(activity, "教学经验"), LinearLayout.LayoutParams(-2, -2))
        titles.addView(title, space(16))
        heading.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(TaskNoteUi.owl(activity, 76), LinearLayout.LayoutParams(activity.dp(76), activity.dp(76)))
        body.addView(heading)
        body.addView(label(recordTitle, 14f, TaskNoteUi.Secondary).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, space(10))
        body.addView(TaskNoteUi.divider(activity), LinearLayout.LayoutParams(-1, activity.dp(3)).apply {
            topMargin = activity.dp(20); bottomMargin = activity.dp(20)
        })
        val current = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.Surface, 16)
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(spinner, LinearLayout.LayoutParams(activity.dp(22), activity.dp(22)).apply {
                    rightMargin = activity.dp(12)
                })
                addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(progress, LinearLayout.LayoutParams(-1, activity.dp(5)).apply {
                topMargin = activity.dp(14); bottomMargin = activity.dp(12)
            })
            addView(elapsed)
        }
        body.addView(current)
        phases.forEachIndexed { index, phase ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, activity.dp(11), 0, activity.dp(11))
            }
            val number = label("${index + 1}", 13f, TaskNoteUi.Secondary, true).apply { gravity = Gravity.CENTER }
            row.addView(number, LinearLayout.LayoutParams(activity.dp(30), activity.dp(30)).apply {
                rightMargin = activity.dp(12)
            })
            val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val name = label(phase.first, 15f, TaskNoteUi.Ink, true)
            val caption = label(phase.second, 12f, TaskNoteUi.Secondary)
            copy.addView(name); copy.addView(caption, space(4))
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            phaseList.addView(row)
            rows += Triple(number, name, caption)
        }
        body.addView(phaseList, space(12))
        body.addView(hint, space(12))
        root.addView(object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    (resources.displayMetrics.heightPixels * .72f).toInt(), MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; addView(body) }, LinearLayout.LayoutParams(-1, -2))
        actions.addView(cancel)
        root.addView(actions, space(20))
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setDimAmount(.48f)
            setWindowAnimations(0)
        }
        setOnShowListener {
            window?.setLayout(minOf(activity.resources.displayMetrics.widthPixels, activity.dp(560)), -2)
            root.startAnimation(android.view.animation.AnimationUtils.loadAnimation(activity, R.anim.demo_dialog_enter_bottom))
            handler.post(tick)
        }
        setOnDismissListener { handler.removeCallbacks(tick) }
        render(DemoTeachingCompilationProgress(DemoTeachingCompilationPhase.PREPARING, "正在准备教学记录…"))
    }

    fun render(state: DemoTeachingCompilationProgress) {
        if (terminal) return
        enteredPhases += state.phase
        val active = state.phase.ordinal
        detail.text = state.message
        detail.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val countable = state.totalBatches > 0
        progress.isIndeterminate = !countable
        if (countable) {
            progress.max = state.totalBatches
            progress.progress = state.completedBatches.coerceIn(0, state.totalBatches)
        }
        progress.contentDescription = if (countable)
            "本阶段已完成 ${state.completedBatches} / ${state.totalBatches} 批" else state.message
        rows.forEachIndexed { index, (number, name, caption) ->
            val skipped = index < active && DemoTeachingCompilationPhase.entries[index] !in enteredPhases
            val done = index < active && !skipped
            val current = index == active
            number.text = if (done) "✓" else if (skipped) "—" else "${index + 1}"
            number.setTextColor(if (current) TaskNoteUi.OnPrimary else if (done) TaskNoteUi.Primary else TaskNoteUi.Secondary)
            number.background = Ui.rounded(activity,
                if (current) TaskNoteUi.Primary else if (done) Ui.PrimaryContainer else Ui.SurfaceSubtle, 15)
            name.setTextColor(if (current || done) TaskNoteUi.Ink else TaskNoteUi.Secondary)
            caption.text = when {
                skipped -> if (index == 1) "本次无需分批提炼" else "可直接汇总，无需额外合并"
                current && countable -> "已完成 ${state.completedBatches} / ${state.totalBatches} 批"
                done -> "已完成"
                else -> phases[index].second
            }
        }
    }

    fun saving() {
        detail.text = "正在保存最佳实践…"
        progress.isIndeterminate = true
        cancel.isEnabled = false
        cancel.alpha = .5f
        hint.text = "整理已完成，正在保存到这份教学记录。"
    }

    fun failed(message: String, retry: () -> Unit) {
        terminal = true
        handler.removeCallbacks(tick)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title.text = "这次整理未完成"
        detail.text = message
        detail.setTextColor(Ui.WarningOnContainer)
        spinner.visibility = View.GONE
        progress.visibility = View.GONE
        phaseList.visibility = View.GONE
        hint.text = "教学记录和已有经验都已保留。你可以稍后重试。"
        actions.removeAllViews()
        actions.addView(TaskNoteUi.button(activity, "重新整理", true) { dismiss(); retry() })
        actions.addView(TaskNoteUi.button(activity, "返回教学记录", false) { dismiss() }, space(8))
    }

    private fun space(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(top) }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
}
