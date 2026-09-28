package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Process-owned execution controls. Compilation never creates a floating window. */
internal class DemoWorkflowOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onOpen: () -> Unit
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var handle: View? = null
    private var stop: ImageButton? = null
    private var snapshot = DemoWorkflowSnapshot()
    private var captureHidden = false
    private var hostVisible = false
    private val params = WindowManager.LayoutParams(
        context.dp(232), context.dp(52),
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = context.dp(12); y = context.dp(96) }

    fun bind(value: DemoWorkflowSnapshot) {
        snapshot = value
        if (!isRunning(value) || hostVisible || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        if (root == null) {
            params.width = overlayWidth()
            val card = createControls().apply { visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE }
            runCatching { manager.addView(card, params) }.onSuccess { root = card }
        }
        bindControls(value)
        root?.post { keepOnScreen() }
    }

    internal fun createControls(): LinearLayout {
        val card = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            tag = "workflow_running_overlay"
            background = Ui.rounded(context, TaskNoteUi.Paper, 26, TaskNoteUi.Rule)
            elevation = context.dp(8).toFloat()
            setPadding(context.dp(4), context.dp(2), context.dp(4), context.dp(2))
        }
        val area = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            setPadding(context.dp(10), 0, context.dp(4), 0)
            isFocusable = true
            tag = "workflow_overlay_handle"
        }
        area.addView(View(context).apply {
            background = Ui.rounded(context, TaskNoteUi.Primary, 5)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { rightMargin = context.dp(8) })
        status = TextView(context).apply {
            textSize = 13f; setTextColor(TaskNoteUi.Ink)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "workflow_overlay_status"
        }
        area.addView(status, LinearLayout.LayoutParams(0, -1, 1f))
        handle = area
        installDrag(area)
        card.addView(area, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        stop = ImageButton(context).apply {
            tag = "workflow_overlay_stop"
            contentDescription = "停止本次操作"
            if (Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
            setImageDrawable(WorkflowStopDrawable(TaskNoteUi.Ink))
            background = Ui.clickableRounded(context, TaskNoteUi.Paper, TaskNoteUi.Sticker, 24)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            setOnClickListener { if (isEnabled) onStop() }
        }
        card.addView(stop, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepOnScreen() }
        bindControls(snapshot)
        return card
    }

    internal fun bindControls(value: DemoWorkflowSnapshot) {
        snapshot = value
        val phase = if (value.phase == DemoWorkflowPhase.JUDGING) "AI 判断" else "执行中"
        status?.text = "$phase · ${value.completedSteps}/${value.totalSteps}"
        handle?.contentDescription = "$phase，已完成 ${value.completedSteps} 共 ${value.totalSteps} 步，拖动可移动，轻点查看操作"
        stop?.isEnabled = isRunning(value)
    }

    fun setHostVisible(visible: Boolean) {
        hostVisible = visible
        bind(snapshot)
    }

    fun setCaptureHidden(hidden: Boolean) {
        captureHidden = hidden
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun hide() {
        root?.let { runCatching { manager.removeView(it) } }
        root = null; status = null; handle = null; stop = null
    }

    fun release() = hide()

    private fun isRunning(value: DemoWorkflowSnapshot) =
        value.phase == DemoWorkflowPhase.RUNNING || value.phase == DemoWorkflowPhase.JUDGING

    private fun overlayWidth() = minOf(context.dp(232),
        (context.resources.displayMetrics.widthPixels - context.dp(16)).coerceAtLeast(context.dp(48)))

    private fun keepOnScreen() {
        val view = root ?: return
        val display = context.resources.displayMetrics
        val width = overlayWidth()
        val x = params.x.coerceIn(0, (display.widthPixels - width).coerceAtLeast(0))
        val y = params.y.coerceIn(context.dp(24),
            (display.heightPixels - context.dp(84)).coerceAtLeast(context.dp(24)))
        if (params.width != width || params.x != x || params.y != y) {
            params.width = width; params.x = x; params.y = y
            runCatching { manager.updateViewLayout(view, params) }
        }
    }

    private fun installDrag(view: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnClickListener { onOpen() }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        val display = context.resources.displayMetrics
                        params.x = (startX + dx.toInt()).coerceIn(0, (display.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(context.dp(24),
                            (display.heightPixels - context.dp(84)).coerceAtLeast(context.dp(24)))
                        root?.let { runCatching { manager.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) target.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }
}

private class WorkflowStopDrawable(color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val inset = bounds.width() * .22f
        canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
            bounds.right - inset, bounds.bottom - inset, 2f, 2f, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
