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
import android.widget.Button
import android.widget.FrameLayout
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

/** A top decision card and a bottom waiting sheet for the same timer slot. */
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
        val card = card(Phase.PROPOSAL)
        val content = card.content
        content.addView(statusPill("等待确认", Ui.InfoSoft, Ui.Info))
        content.addView(title(if (task.repeating) "开启周期任务？" else "开始倒计时？"), fullWidth(top = 14))
        content.addView(body(if (task.repeating) {
            "首次到点执行；以后每轮完成，再重新计时。"
        } else {
            "确认后开始等待，到点在当前对话执行。"
        }), fullWidth(top = 6))

        val timing = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(activity, Ui.InfoSoft, 16)
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
        }
        timing.addView(body(if (task.repeating) "每轮间隔" else "等待时间").apply {
            setTextColor(Ui.InfoOnContainer)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timing.addView(TextView(activity).apply {
            text = formatDelay(task.delaySeconds)
            textSize = 27f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Ui.InfoOnContainer)
            includeFontPadding = false
            contentDescription = if (task.repeating) {
                "每轮完成后等待 ${formatDelay(task.delaySeconds)}"
            } else {
                "确认后等待 ${formatDelay(task.delaySeconds)}"
            }
        })
        content.addView(timing, fullWidth(top = 18))
        content.addView(taskPanel("将在当前对话执行", task.instruction), fullWidth(top = 10))

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
        backgroundRow.addView(caption("后台可等待 · 进程结束即停止", Ui.TextSecondary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        backgroundRow.addView(textAction("后台设置", onBackgroundSettings))
        content.addView(backgroundRow, fullWidth(top = 12))

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(action("取消", primary = false, onClick = onReject),
            LinearLayout.LayoutParams(0, activity.dp(50), 1f))
        actions.addView(action(if (task.repeating) "开始周期任务" else "开始等待", primary = true, onClick = onConfirm),
            LinearLayout.LayoutParams(0, activity.dp(50), 1.65f).apply {
                marginStart = activity.dp(10)
            })
        card.footer.addView(actions, fullWidth())
        present(task.id, Phase.PROPOSAL, card.root)
    }

    fun showWaiting(waiting: DemoDelayedTaskState.Waiting, onStop: () -> Unit) {
        if (isShowing(waiting.task.id, Phase.WAITING)) {
            updateWaiting(waiting)
            return
        }
        dismiss()
        val card = card(Phase.WAITING)
        val content = card.content
        content.addView(View(activity).apply {
            background = Ui.rounded(activity, Ui.OutlineSubtle, 3)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(36), activity.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(statusPill("正在等待", Ui.PrimaryContainer, Ui.Primary))
        statusRow.addView(caption(if (waiting.task.repeating) {
            "已完成 ${waiting.completedRuns} 轮"
        } else {
            "单次任务"
        }, Ui.TextSecondary).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(statusRow, fullWidth(top = 12))

        val timer = panel(Ui.PrimaryContainer)
        timer.addView(caption(if (waiting.task.repeating) "距离下一轮执行" else "距离任务执行", Ui.OnPrimaryContainer))
        countdown = TextView(activity).apply {
            textSize = 53f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Ui.OnPrimaryContainer)
            includeFontPadding = false
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 30, 53, 2, android.util.TypedValue.COMPLEX_UNIT_SP
            )
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        timer.addView(countdown, fullWidth(top = 2))
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.Primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SurfaceElevated)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        timer.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(5)).apply {
            topMargin = activity.dp(8)
        })
        remainingLabel = caption("", Ui.OnPrimaryContainer)
        timer.addView(remainingLabel, fullWidth(top = 8))
        content.addView(timer, fullWidth(top = 12))
        content.addView(caption("到点后执行", Ui.TextSecondary), fullWidth(top = 12))
        content.addView(TextView(activity).apply {
            text = waiting.task.instruction
            textSize = 15f
            setTextColor(Ui.TextPrimary)
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
        remainingLabel?.text = if (seconds > 0L) "预计 $time 执行" else "正在接续当前对话"
    }

    private fun isShowing(taskId: String, phase: Phase): Boolean =
        shownTaskId == taskId && shownPhase == phase && dialog?.isShowing == true

    private fun present(taskId: String, phase: Phase, content: View) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (phase == Phase.PROPOSAL) {
            // This card starts at the top of a full-screen dialog. Only the
            // status-bar inset belongs above its content; a floating dialog
            // would start below that inset and leave the dimmed Activity visible.
            val topInset = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
                ?.takeIf { it > 0 }
                ?: Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }.top
            content.setPadding(content.paddingLeft, topInset, content.paddingRight, content.paddingBottom)
        }
        val dialogContent = if (phase == Phase.PROPOSAL) {
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
        val next = Dialog(activity, if (phase == Phase.PROPOSAL) {
            R.style.DemoTopSheetDialog
        } else {
            Ui.dialogTheme()
        }).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            if (phase == Phase.PROPOSAL) {
                window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            }
            setContentView(dialogContent)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        next.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (phase == Phase.PROPOSAL) {
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                statusBarColor = Color.TRANSPARENT
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (phase == Phase.PROPOSAL) 0.44f else 0.38f)
            attributes = attributes.apply {
                if (phase == Phase.PROPOSAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
                if (phase == Phase.PROPOSAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                gravity = if (phase == Phase.PROPOSAL) {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                } else {
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                y = 0
                windowAnimations = if (phase == Phase.PROPOSAL) {
                    0
                } else {
                    R.style.DemoBottomDialogMotion
                }
            }
        }
        dialog = next
        shownTaskId = taskId
        shownPhase = phase
        next.show()
        if (phase == Phase.PROPOSAL) {
            next.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !Ui.isDark
                    isAppearanceLightNavigationBars = !Ui.isDark
                }
            }
            content.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.demo_dialog_enter_top))
        }
        next.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, if (phase == Phase.PROPOSAL) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        })
    }

    private fun card(phase: Phase): Card {
        val visible = Rect().also { activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        val availableHeight = visible.height().takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = if (phase == Phase.PROPOSAL) {
                Ui.asymmetricRounded(activity, Ui.SurfaceElevated, 0, 0, 28, 28)
            } else {
                Ui.asymmetricRounded(activity, Ui.SurfaceElevated, 28, 28, 0, 0)
            }
            clipToOutline = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(if (phase == Phase.PROPOSAL) 16 else 12),
                activity.dp(22), activity.dp(10))
        }
        if (phase == Phase.PROPOSAL) {
            val bodyMaxHeight = ((availableHeight * 0.47f).toInt() - activity.dp(82))
                .coerceAtLeast(activity.dp(150))
            root.addView(LimitedHeightScrollView(activity, bodyMaxHeight).apply {
                isFillViewport = false
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(content)
            }, fullWidth())
        } else {
            root.addView(content, fullWidth())
        }
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(6), activity.dp(22), activity.dp(18))
        }
        root.addView(footer, fullWidth())
        return Card(root, content, footer)
    }

    private fun statusPill(label: String, backgroundColor: Int, textColor: Int): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(activity, backgroundColor, 50)
            setPadding(activity.dp(11), activity.dp(7), activity.dp(12), activity.dp(7))
            addView(View(activity).apply {
                background = Ui.rounded(activity, textColor, 5)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(7), activity.dp(7)).apply {
                marginEnd = activity.dp(7)
            })
            addView(caption(label, textColor).apply {
                textSize = 12.5f
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
        textSize = 13.5f
        setTextColor(Ui.TextSecondary)
        setLineSpacing(activity.dp(2).toFloat(), 1f)
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
        setPadding(activity.dp(17), activity.dp(16), activity.dp(17), activity.dp(16))
    }

    private fun taskPanel(label: String, instruction: String): View = panel(Ui.SurfaceSoft).apply {
        addView(caption(label, Ui.TextSecondary))
        addView(TextView(activity).apply {
            text = instruction
            textSize = 15f
            setTextColor(Ui.TextPrimary)
            setLineSpacing(activity.dp(3).toFloat(), 1f)
        }, fullWidth(top = 6))
    }

    private fun action(
        label: String,
        primary: Boolean,
        onClick: () -> Unit,
        destructive: Boolean = false
    ): View = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Ui.OnPrimary else if (destructive) Ui.Danger else Ui.TextPrimary)
        gravity = Gravity.CENTER
        minHeight = activity.dp(50)
        isClickable = true
        isFocusable = true
        background = when {
            primary -> Ui.clickableRounded(activity, Ui.Primary, Ui.PrimaryPressed, 14)
            destructive -> Ui.clickableRounded(activity, Ui.DangerSoft, Ui.SurfaceSoft, 14, Ui.Danger)
            else -> Ui.clickableRounded(activity, Ui.SurfaceSoft, Ui.SurfaceSubtle, 14, Ui.OutlineSubtle)
        }
        setOnClickListener { onClick() }
    }

    private fun textAction(label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Ui.Info)
        gravity = Gravity.CENTER
        minHeight = activity.dp(44)
        setPadding(activity.dp(8), 0, 0, 0)
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

    private class LimitedHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val parentLimit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
                maxHeight
            } else {
                min(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec))
            }
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(parentLimit, View.MeasureSpec.AT_MOST))
        }
    }
}
