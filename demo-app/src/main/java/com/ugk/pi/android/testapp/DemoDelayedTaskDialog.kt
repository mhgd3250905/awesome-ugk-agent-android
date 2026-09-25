package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/** One visual language for the proposal and the occupied waiting state. */
internal class DemoDelayedTaskDialog(
    private val activity: Activity,
    private val taskState: () -> DemoDelayedTaskState
) {
    private enum class Phase { PROPOSAL, WAITING }

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var shownTaskId: String? = null
    private var shownPhase: Phase? = null
    private var countdown: TextView? = null
    private var progress: ProgressBar? = null
    private var remainingLabel: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val waiting = taskState() as? DemoDelayedTaskState.Waiting ?: return
            if (shownTaskId != waiting.task.id || shownPhase != Phase.WAITING) return
            updateWaiting(waiting)
            handler.postDelayed(this, 1000L)
        }
    }

    fun showProposal(
        task: DemoDelayedTask,
        queuedMessages: Int,
        onConfirm: () -> Unit,
        onReject: () -> Unit,
        onBackgroundSettings: () -> Unit
    ) {
        if (isShowing(task.id, Phase.PROPOSAL)) return
        dismiss()
        val (card, content) = card()
        content.addView(eyebrow(if (task.repeating) "周期任务" else "单次定时任务"))
        content.addView(title(if (task.repeating) "按间隔重复执行" else "稍后继续这件事"), fullWidth(top = 18))
        content.addView(body(if (task.repeating) {
            "确认后每隔一段时间在当前对话执行一次，首次在一个间隔后开始，直到你停止任务。"
        } else {
            "确认后开始计时，到点会在当前对话继续执行。"
        }), fullWidth(top = 6))

        val timing = panel(Ui.PrimaryContainer)
        timing.addView(caption(if (task.repeating) "执行间隔" else "等待时长", Ui.OnPrimaryContainer))
        timing.addView(TextView(activity).apply {
            text = formatDelay(task.delaySeconds)
            textSize = 38f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Ui.OnPrimaryContainer)
            includeFontPadding = false
            contentDescription = if (task.repeating) {
                "每隔 ${formatDelay(task.delaySeconds)} 执行一次"
            } else {
                "确认后等待 ${formatDelay(task.delaySeconds)}"
            }
        }, fullWidth(top = 6))
        content.addView(timing, fullWidth(top = 20))
        content.addView(taskPanel(if (task.repeating) "每次执行" else "到点执行", task.instruction), fullWidth(top = 12))

        if (queuedMessages > 0) {
            content.addView(TextView(activity).apply {
                text = "开始后会清空当前排队的 $queuedMessages 条消息"
                textSize = 12f
                setTextColor(Ui.WarningOnContainer)
                setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
                background = Ui.rounded(activity, Ui.WarningSoft, 12)
            }, fullWidth(top = 12))
        }

        content.addView(body("锁屏或离开 App 可以继续等待。建议允许自启动并放宽电池限制；进程结束后任务会中断。"), fullWidth(top = 16))
        content.addView(textAction("后台运行设置", onBackgroundSettings), fullWidth(top = 2))
        content.addView(action(if (task.repeating) "开始周期任务" else "开始等待", primary = true, onClick = onConfirm), fullWidth(top = 10))
        content.addView(action("取消", primary = false, onClick = onReject), fullWidth(top = 8))
        present(task.id, Phase.PROPOSAL, card)
    }

    fun showWaiting(waiting: DemoDelayedTaskState.Waiting, onStop: () -> Unit) {
        if (isShowing(waiting.task.id, Phase.WAITING)) {
            updateWaiting(waiting)
            return
        }
        dismiss()
        val (card, content) = card()
        content.addView(eyebrow(if (waiting.task.repeating) "周期任务等待中" else "等待中"))
        content.addView(title(if (waiting.task.repeating) "等待下一次执行" else "时间到了就继续"), fullWidth(top = 18))
        content.addView(body(if (waiting.task.repeating) {
            "当前对话会按这个间隔重复执行，直到你停止任务。"
        } else {
            "当前对话正在等待这个任务。"
        }), fullWidth(top = 6))

        val timing = panel(Ui.PrimaryContainer)
        timing.gravity = Gravity.CENTER_HORIZONTAL
        timing.addView(caption("剩余时间", Ui.OnPrimaryContainer).apply {
            gravity = Gravity.CENTER
        })
        countdown = TextView(activity).apply {
            textSize = 52f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Ui.OnPrimaryContainer)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 30, 52, 2, android.util.TypedValue.COMPLEX_UNIT_SP
            )
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        timing.addView(countdown, fullWidth(top = 8))
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.Primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SurfaceElevated)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        timing.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(8)).apply {
            topMargin = activity.dp(18)
        })
        remainingLabel = caption("", Ui.OnPrimaryContainer).apply { gravity = Gravity.CENTER }
        timing.addView(remainingLabel, fullWidth(top = 12))
        content.addView(timing, fullWidth(top = 20))
        content.addView(taskPanel(if (waiting.task.repeating) "每次执行" else "到点执行", waiting.task.instruction), fullWidth(top = 12))
        if (waiting.task.repeating && !waiting.latestResult.isNullOrBlank()) {
            content.addView(taskPanel("上次执行结果", waiting.latestResult), fullWidth(top = 12))
        }
        content.addView(body("可以锁屏或离开 App，应用进程存活时会继续等待。"), fullWidth(top = 16))
        content.addView(action("停止任务", primary = false, onClick = onStop, destructive = true), fullWidth(top = 20))
        present(waiting.task.id, Phase.WAITING, card)
        updateWaiting(waiting)
        handler.postDelayed(tick, 1000L)
    }

    fun dismiss() {
        handler.removeCallbacks(tick)
        dialog?.dismiss()
        dialog = null
        shownTaskId = null
        shownPhase = null
        countdown = null
        progress = null
        remainingLabel = null
    }

    private fun updateWaiting(waiting: DemoDelayedTaskState.Waiting) {
        val remainingMillis = (waiting.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val seconds = (remainingMillis + 999L) / 1000L
        countdown?.apply {
            text = if (seconds > 0L) formatDelay(seconds) else "即将执行"
            contentDescription = if (seconds > 0L) "剩余 ${formatDelay(seconds)}" else "即将执行"
        }
        val totalMillis = waiting.task.delaySeconds * 1000L
        progress?.progress = (((totalMillis - remainingMillis).coerceIn(0L, totalMillis) * 1000L) / totalMillis).toInt()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(waiting.deadlineWallMillis))
        remainingLabel?.text = if (waiting.task.repeating) {
            val completed = "已执行 ${waiting.completedRuns} 次"
            if (seconds > 0L) "$completed · 下次预计 $time" else "$completed · 正在接续当前对话"
        } else if (seconds > 0L) {
            "预计 $time 开始"
        } else {
            "正在接续当前对话"
        }
    }

    private fun isShowing(taskId: String, phase: Phase): Boolean =
        shownTaskId == taskId && shownPhase == phase && dialog?.isShowing == true

    private fun present(taskId: String, phase: Phase, content: View) {
        if (activity.isFinishing || activity.isDestroyed) return
        val next = Dialog(activity, Ui.dialogTheme()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog = next
        shownTaskId = taskId
        shownPhase = phase
        next.show()
        next.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.52f)
            setLayout(
                min(activity.resources.displayMetrics.widthPixels - activity.dp(32), activity.dp(420)),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun card(): Pair<ScrollView, LinearLayout> {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(24), activity.dp(24), activity.dp(20))
        }
        val scroll = LimitedHeightScrollView(activity).apply {
            background = Ui.rounded(activity, Ui.SurfaceElevated, 26, Ui.OutlineSubtle, 1)
            clipToOutline = true
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        return scroll to content
    }

    private fun eyebrow(label: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(activity, Ui.PrimaryContainer, 50)
        setPadding(activity.dp(12), activity.dp(7), activity.dp(12), activity.dp(7))
        addView(View(activity).apply {
            background = Ui.rounded(activity, Ui.Primary, 5)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(7), activity.dp(7)).apply {
            marginEnd = activity.dp(8)
        })
        addView(caption(label, Ui.OnPrimaryContainer).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun title(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 22f
        setTextColor(Ui.TextPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun body(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(Ui.TextSecondary)
        setLineSpacing(activity.dp(3).toFloat(), 1f)
    }

    private fun caption(value: String, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = 12f
        setTextColor(color)
        includeFontPadding = false
    }

    private fun panel(color: Int): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(activity, color, 18)
        setPadding(activity.dp(18), activity.dp(18), activity.dp(18), activity.dp(18))
    }

    private fun taskPanel(label: String, instruction: String): View = panel(Ui.SurfaceSoft).apply {
        addView(caption(label, Ui.TextSecondary))
        addView(TextView(activity).apply {
            text = instruction
            textSize = 16f
            setTextColor(Ui.TextPrimary)
            setLineSpacing(activity.dp(4).toFloat(), 1f)
        }, fullWidth(top = 8))
    }

    private fun action(
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
        destructive: Boolean = false
    ): View = TextView(activity).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Ui.OnPrimary else if (destructive) Ui.Danger else Ui.TextPrimary)
        gravity = Gravity.CENTER
        minHeight = activity.dp(50)
        isClickable = true
        isFocusable = true
        background = if (primary) {
            Ui.clickableRounded(activity, Ui.Primary, Ui.PrimaryPressed, 14)
        } else {
            Ui.clickableRounded(activity, Ui.SurfaceSoft, Ui.SurfaceSubtle, 14, Ui.OutlineSubtle)
        }
        setOnClickListener { onClick() }
    }

    private fun textAction(label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Ui.Primary)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = activity.dp(48)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun fullWidth(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dp(top)
        }

    private fun formatDelay(seconds: Long): String {
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val rest = seconds % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, rest)
        else "%02d:%02d".format(minutes, rest)
    }

    private class LimitedHeightScrollView(context: Context) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            val parentLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                limit
            } else {
                min(limit, View.MeasureSpec.getSize(heightMeasureSpec))
            }
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(parentLimit, View.MeasureSpec.AT_MOST))
        }
    }
}
