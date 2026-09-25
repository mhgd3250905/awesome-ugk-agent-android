package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

/** A bottom decision sheet and a top waiting card for the same timer slot. */
internal class DemoDelayedTaskDialog(
    private val activity: Activity,
    private val taskState: () -> DemoDelayedTaskState
) {
    private enum class Phase { PROPOSAL, WAITING }

    private data class Card(
        val root: LinearLayout,
        val content: LinearLayout,
        val footer: LinearLayout
    )

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var cardView: View? = null
    private var closingDialog: Dialog? = null
    private var closingCard: View? = null
    private var closingAction: (() -> Unit)? = null
    private var pendingPresentation: (() -> Unit)? = null
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
        if (deferUntilDismissed {
                showProposal(task, queuedMessages, onConfirm, onReject, onBackgroundSettings)
            }) return
        val card = card(Phase.PROPOSAL)
        val content = card.content
        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        heading.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(TaskNoteUi.label(activity, "等你点头"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = activity.dp(8) })
            addView(title("这份安排，确认吗？"), fullWidth(top = 18))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val owlSize = when {
            activity.resources.configuration.fontScale > 1.25f -> 72
            activity.resources.configuration.screenWidthDp < 380 -> 92
            else -> 104
        }
        heading.addView(TaskNoteUi.owl(activity, owlSize), LinearLayout.LayoutParams(
            activity.dp(owlSize), activity.dp(owlSize)
        ).apply { marginStart = activity.dp(4) })
        content.addView(heading, fullWidth(top = 12))

        content.addView(title(task.instruction).apply {
            textSize = 36f
            setLineSpacing(activity.dp(3).toFloat(), 1f)
        }, fullWidth(top = 10))
        content.addView(TaskNoteUi.marker(activity), LinearLayout.LayoutParams(activity.dp(144), activity.dp(20)).apply {
            gravity = Gravity.START
            marginStart = activity.dp(72)
        })

        val timing = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timing.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_note_schedule)
            setColorFilter(TaskNoteUi.Ink)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)).apply { marginEnd = activity.dp(8) })
        val duration = if (task.repeating) {
            "每轮间隔 ${formatDuration(task.delaySeconds)}"
        } else "${formatDuration(task.delaySeconds)}后"
        timing.addView(body(duration).apply {
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TaskNoteUi.Ink)
        }, if (duration.length > 12 || activity.resources.configuration.fontScale > 1.25f) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        timing.addView(View(activity).apply {
            setBackgroundColor(TaskNoteUi.Rule)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(1), activity.dp(18)).apply {
            marginStart = activity.dp(16)
            marginEnd = activity.dp(16)
        })
        timing.addView(caption(if (task.repeating) "周期任务" else "仅执行一次", TaskNoteUi.Secondary).apply {
            textSize = 14f
        })
        content.addView(timing, fullWidth(top = 2))
        content.addView(TaskNoteUi.divider(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(3)
        ).apply { topMargin = activity.dp(20) })
        content.addView(body("你点确认，我就开始计时。").apply {
            textSize = 17f
            setTextColor(TaskNoteUi.Ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, fullWidth(top = 16))
        content.addView(body(if (task.repeating) {
            "每轮完成后重新等待 ${formatDuration(task.delaySeconds)}，直到你停止。"
        } else "到点在当前对话执行"), fullWidth(top = 4))

        if (queuedMessages > 0) {
            content.addView(body("开始后将清空已排队的 $queuedMessages 条消息").apply {
                setTextColor(Ui.WarningOnContainer)
                setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
                background = Ui.rounded(activity, Ui.WarningSoft, 12)
            }, fullWidth(top = 10))
        }

        val backgroundRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backgroundRow.addView(caption("后台可等待 · 进程结束即停止", TaskNoteUi.Secondary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        backgroundRow.addView(textAction("后台设置", onBackgroundSettings))
        content.addView(backgroundRow, fullWidth(top = 4))

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("取消", primary = false, onClick = onReject),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(action("确认并开始", primary = true, onClick = onConfirm),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.9f).apply {
                marginStart = activity.dp(12)
            })
        card.footer.addView(actions, fullWidth())
        present(task.id, Phase.PROPOSAL, card.root)
    }

    fun showWaiting(waiting: DemoDelayedTaskState.Waiting, onStop: () -> Unit) {
        if (isShowing(waiting.task.id, Phase.WAITING)) {
            updateWaiting(waiting)
            return
        }
        if (deferUntilDismissed { showWaiting(waiting, onStop) }) return
        val card = card(Phase.WAITING)
        val content = card.content
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.clipChildren = false
        statusRow.addView(TaskNoteUi.label(activity, "正在等待"))
        statusRow.addView(caption(if (waiting.task.repeating) {
            "已完成 ${waiting.completedRuns} 轮"
        } else {
            "单次任务"
        }, TaskNoteUi.Secondary).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(statusRow, fullWidth(top = 12))

        val timer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timerText = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        timerText.addView(caption(if (waiting.task.repeating) "下一轮，还有" else "还有多久？", TaskNoteUi.Secondary).apply {
            textSize = 15f
        })
        countdown = TextView(activity).apply {
            textSize = 58f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setTextColor(TaskNoteUi.Ink)
            includeFontPadding = false
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 30, 58, 2, android.util.TypedValue.COMPLEX_UNIT_SP
            )
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        timerText.addView(countdown, fullWidth(top = 2))
        remainingLabel = caption("", TaskNoteUi.Secondary)
        timerText.addView(remainingLabel, fullWidth(top = 4))
        timer.addView(timerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timer.addView(TaskNoteUi.owl(activity, 110), LinearLayout.LayoutParams(activity.dp(110), activity.dp(110)).apply {
            marginStart = activity.dp(6)
        })
        content.addView(timer, fullWidth(top = 24))
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(TaskNoteUi.Primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(TaskNoteUi.Rule)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(5)).apply {
            topMargin = activity.dp(12)
        })
        content.addView(TaskNoteUi.divider(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(3)
        ).apply { topMargin = activity.dp(20) })
        content.addView(caption("到点后，我会继续这件事", TaskNoteUi.Secondary), fullWidth(top = 16))
        content.addView(TextView(activity).apply {
            text = waiting.task.instruction
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TaskNoteUi.Ink)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = waiting.task.instruction
        }, fullWidth(top = 4))
        card.footer.addView(action(
            if (waiting.task.repeating) "停止周期任务" else "停止任务",
            primary = false,
            onClick = onStop,
            destructive = true
        ), fullWidth())
        present(waiting.task.id, Phase.WAITING, card.root)
        updateWaiting(waiting)
        handler.postDelayed(tick, 1000L)
    }

    fun dismiss(animate: Boolean = true) {
        pendingPresentation = null
        dismissCurrent(animate)
        if (!animate) finishClosingImmediately()
    }

    private fun runButtonAction(onClick: () -> Unit, afterExit: Boolean = false) {
        if (dialog?.isShowing != true) return
        // Start the visual exit before the task-state callback can render another
        // sheet, remove this one, or navigate away from the Activity.
        if (afterExit) closingAction = onClick
        dismissCurrent(animate = true)
        if (!afterExit) onClick()
    }

    private fun deferUntilDismissed(show: () -> Unit): Boolean {
        if (dialog == null && closingDialog == null) return false
        pendingPresentation = show
        dismissCurrent(animate = true)
        return true
    }

    private fun dismissCurrent(animate: Boolean) {
        handler.removeCallbacks(tick)
        val current = dialog ?: return
        val content = cardView
        val phase = shownPhase
        dialog = null
        cardView = null
        shownTaskId = null
        shownPhase = null
        countdown = null
        progress = null
        remainingLabel = null
        if (!animate || activity.isFinishing || activity.isDestroyed ||
            !current.isShowing || content == null || phase == null
        ) {
            content?.clearAnimation()
            current.dismiss()
            val action = closingAction
            closingAction = null
            action?.invoke()
            showPendingPresentation()
            return
        }
        closingDialog = current
        closingCard = content
        content.clearAnimation()
        val distance = content.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
        content.animate()
            .translationY(if (phase == Phase.PROPOSAL) distance.toFloat() else -distance.toFloat())
            .setDuration(240L)
            .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .withEndAction { finishClosing(current) }
            .start()
    }

    private fun finishClosing(expected: Dialog) {
        if (closingDialog !== expected) return
        closingDialog = null
        closingCard = null
        expected.dismiss()
        val action = closingAction
        closingAction = null
        action?.invoke()
        showPendingPresentation()
    }

    private fun finishClosingImmediately() {
        val closing = closingDialog ?: return
        closingDialog = null
        closingCard?.animate()?.cancel()
        closingCard = null
        closingAction = null
        closing.dismiss()
    }

    private fun showPendingPresentation() {
        val show = pendingPresentation
        pendingPresentation = null
        if (!activity.isFinishing && !activity.isDestroyed) show?.invoke()
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
        remainingLabel?.text = if (seconds > 0L) "预计 $time 执行" else "正在接续当前对话"
    }

    private fun isShowing(taskId: String, phase: Phase): Boolean =
        shownTaskId == taskId && shownPhase == phase && dialog?.isShowing == true

    private fun present(taskId: String, phase: Phase, content: View) {
        if (activity.isFinishing || activity.isDestroyed) return
        val isTopSheet = phase == Phase.WAITING
        if (isTopSheet) {
            // This card starts at the top of a full-screen dialog. Only the
            // status-bar inset belongs above its content; a floating dialog
            // would start below that inset and leave the dimmed Activity visible.
            val topInset = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
                ?.takeIf { it > 0 }
                ?: Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }.top
            content.setPadding(content.paddingLeft, topInset, content.paddingRight, content.paddingBottom)
        }
        val dialogContent = if (isTopSheet) {
            FrameLayout(activity).apply {
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ))
            }
        } else {
            content
        }
        val next = Dialog(activity, if (isTopSheet) {
            R.style.DemoTopSheetDialog
        } else {
            Ui.dialogTheme()
        }).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            if (isTopSheet) {
                window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            }
            setContentView(dialogContent)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        next.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (isTopSheet) {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                statusBarColor = Color.TRANSPARENT
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (phase == Phase.PROPOSAL) 0.44f else 0.38f)
            attributes = attributes.apply {
                if (isTopSheet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
                if (isTopSheet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                gravity = if (isTopSheet) {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                } else {
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                y = 0
                windowAnimations = 0
            }
        }
        dialog = next
        cardView = content
        shownTaskId = taskId
        shownPhase = phase
        next.show()
        if (isTopSheet) {
            next.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !Ui.isDark
                    isAppearanceLightNavigationBars = !Ui.isDark
                }
            }
        }
        next.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, if (isTopSheet) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        })
        content.startAnimation(AnimationUtils.loadAnimation(activity, if (isTopSheet) {
            R.anim.demo_dialog_enter_top
        } else {
            R.anim.demo_dialog_enter_bottom
        }))
    }

    private fun card(phase: Phase): Card {
        val visible = Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        val availableHeight = visible.height().takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = if (phase == Phase.PROPOSAL) {
                Ui.asymmetricRounded(activity, TaskNoteUi.Paper, 28, 28, 0, 0)
            } else {
                Ui.asymmetricRounded(activity, TaskNoteUi.Paper, 0, 0, 28, 28)
            }
            clipToOutline = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(if (phase == Phase.PROPOSAL) 14 else 18),
                activity.dp(24), activity.dp(10))
            clipChildren = false
            clipToPadding = false
        }
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(20))
        }
        root.addView(LimitedHeightScrollView(activity) { widthSpec ->
            // Measure the fixed actions first. A compact window or large text
            // may scroll the paper, but can never spend the buttons' space.
            footer.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val budget = if (phase == Phase.PROPOSAL) (availableHeight * 0.74f).toInt() else availableHeight
            (budget - footer.measuredHeight - root.paddingTop - root.paddingBottom).coerceAtLeast(0)
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }, fullWidth())
        root.addView(footer, fullWidth())
        return Card(root, content, footer)
    }

    private fun title(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = if (activity.resources.configuration.screenWidthDp < 380) 22f else 24f
        setTextColor(TaskNoteUi.Ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun body(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 14f
        setTextColor(TaskNoteUi.Secondary)
        setLineSpacing(activity.dp(2).toFloat(), 1f)
    }

    private fun caption(value: String, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = 12f
        setTextColor(color)
        includeFontPadding = false
    }

    private fun action(
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
        destructive: Boolean = false
    ): View = TaskNoteUi.button(activity, label, primary) { runButtonAction(onClick) }.apply {
        if (destructive) {
            setTextColor(Ui.Danger)
            background = Ui.clickableRounded(activity, TaskNoteUi.Paper, Ui.DangerSoft, 16, TaskNoteUi.Rule)
        }
    }

    private fun textAction(label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(TaskNoteUi.Ink)
        gravity = Gravity.CENTER
        minHeight = activity.dp(48)
        setPadding(activity.dp(8), 0, 0, 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { runButtonAction(onClick, afterExit = true) }
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

    private fun formatDuration(seconds: Long): String = buildList {
        if (seconds / 3600 > 0) add("${seconds / 3600} 小时")
        if (seconds % 3600 / 60 > 0) add("${seconds % 3600 / 60} 分钟")
        if (seconds % 60 > 0) add("${seconds % 60} 秒")
    }.joinToString(" ")

    private class LimitedHeightScrollView(context: Context, private val heightLimit: (Int) -> Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxHeight = heightLimit(widthMeasureSpec)
            val parentLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                maxHeight
            } else {
                min(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec))
            }
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(parentLimit, View.MeasureSpec.AT_MOST))
        }
    }
}
