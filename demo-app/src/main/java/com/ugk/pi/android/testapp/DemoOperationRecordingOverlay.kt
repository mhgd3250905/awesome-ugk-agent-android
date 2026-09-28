package com.ugk.pi.android.testapp

import android.content.Context
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.TextView
import kotlin.math.abs

/** Process-owned recording controls. No Activity, focus or keyboard ownership. */
internal class DemoOperationRecordingOverlay(
    private val context: Context,
    private val onPauseResume: () -> Unit,
    private val onFinish: () -> Unit,
    private val onOpen: () -> Unit,
    private val onCompleteStep: () -> Unit = {},
    private val onFailure: (String) -> Unit = {}
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var handle: View? = null
    private var indicator: RecordingIndicator? = null
    private var pause: ImageButton? = null
    private var finish: ImageButton? = null
    private var complete: TextView? = null
    private var captureHidden = false
    private var hostVisible = false
    private var snapshot = DemoOperationSnapshot()
    private val params = WindowManager.LayoutParams(
        context.dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = context.dp(12); y = context.dp(96) }

    fun bind(value: DemoOperationSnapshot) {
        snapshot = value
        if (value.phase == DemoOperationPhase.IDLE || hostVisible) { hide(); return }
        if (!Settings.canDrawOverlays(context)) { hide(); onFailure("悬浮窗权限已关闭，录制已停止"); return }
        if (value.phase != DemoOperationPhase.PAUSED && value.guidePhase != DemoOperationGuidePhase.ACTING) { hide(); return }
        if (root == null) show()
        bindControls(value)
        root?.post { keepOnScreen() }
    }

    /** State-only seam: never adds a WindowManager window. */
    internal fun bindControls(value: DemoOperationSnapshot) {
        snapshot = value
        status?.text = if (value.phase == DemoOperationPhase.PAUSED) "已暂停" else "第 ${value.stepNumber} 步"
        complete?.isEnabled = value.phase == DemoOperationPhase.RECORDING && value.guidePhase == DemoOperationGuidePhase.ACTING
        val phase = when (value.phase) {
            DemoOperationPhase.PAUSED -> "已暂停"
            DemoOperationPhase.SAVING -> "正在保存草稿"
            DemoOperationPhase.IDLE -> "未录制"
            DemoOperationPhase.RECORDING -> "录制中"
        }
        handle?.contentDescription = listOfNotNull(phase, operationDuration(value.elapsedMillis),
            value.message, "拖动可移动，轻点查看草稿").joinToString("，")
        pause?.apply {
            if (value.phase == DemoOperationPhase.PAUSED) setImageDrawable(RecordingActionDrawable(play = true, color = TaskNoteUi.Ink))
            else setImageResource(R.drawable.ic_process_pause)
            contentDescription = if (value.phase == DemoOperationPhase.PAUSED) "继续录制" else "暂停录制"
            if (Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
        }
        val enabled = value.phase == DemoOperationPhase.RECORDING || value.phase == DemoOperationPhase.PAUSED
        pause?.isEnabled = enabled
        finish?.isEnabled = enabled
        pause?.alpha = if (enabled) 1f else .5f
        finish?.alpha = if (enabled) 1f else .5f
        indicator?.setRecording(value.phase == DemoOperationPhase.RECORDING && !captureHidden && !hostVisible)
    }

    private fun show() {
        params.width = minOf(context.dp(300), context.resources.displayMetrics.widthPixels - context.dp(16))
        val card = createControls()
        card.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
        runCatching { manager.addView(card, params) }.onSuccess { root = card }
            .onFailure { onFailure("无法显示录制悬浮条，已停止录制") }
    }

    /** Native layout seam for narrow-window checks without granting overlay permission. */
    internal fun createControls(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "operation_recording_overlay"
            background = Ui.rounded(context, TaskNoteUi.Paper, 26, TaskNoteUi.Rule)
            elevation = context.dp(8).toFloat()
            setPadding(context.dp(4), context.dp(2), context.dp(4), context.dp(2))
        }
        val timing = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), 0, context.dp(4), 0)
            minimumHeight = context.dp(48)
            isFocusable = true
            tag = "operation_overlay_handle"
            if (Build.VERSION.SDK_INT >= 26) tooltipText = "拖动可移动，轻点查看草稿"
        }
        indicator = RecordingIndicator(context).apply { tag = "operation_overlay_indicator" }
        timing.addView(indicator, LinearLayout.LayoutParams(context.dp(10), context.dp(10)).apply { rightMargin = context.dp(8) })
        status = TextView(context).apply {
            text = "00:00"
            textSize = 14f; setTextColor(TaskNoteUi.Ink)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag = "operation_overlay_timer"
        }
        timing.addView(status, LinearLayout.LayoutParams(0, -1, 1f))
        handle = timing
        installDrag(timing)
        card.addView(timing, LinearLayout.LayoutParams(0, context.dp(48), 1f))
        pause = iconButton("operation_overlay_pause", "暂停录制", onPauseResume)
        finish = iconButton("operation_overlay_finish", "结束并保存草稿", onFinish).apply {
            setImageDrawable(RecordingActionDrawable(play = false, color = TaskNoteUi.Ink))
        }
        complete = TaskNoteUi.button(context, "已完成", true, onCompleteStep).apply { tag = "operation_overlay_complete" }
        card.addView(complete, LinearLayout.LayoutParams(context.dp(88), context.dp(48)))
        card.addView(pause, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        card.addView(finish, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepOnScreen() }
        bindControls(snapshot)
        return card
    }

    private fun iconButton(viewTag: String, label: String, action: () -> Unit) = ImageButton(context).apply {
        tag = viewTag; contentDescription = label
        if (Build.VERSION.SDK_INT >= 26) tooltipText = label
        imageTintList = ColorStateList.valueOf(TaskNoteUi.Ink)
        background = Ui.clickableRounded(context, TaskNoteUi.Paper, TaskNoteUi.Sticker, 24)
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        setOnClickListener { if (isEnabled) action() }
    }

    private fun keepOnScreen() {
        val view = root ?: return
        val display = context.resources.displayMetrics
        val width = minOf(context.dp(300), (display.widthPixels - context.dp(16)).coerceAtLeast(context.dp(48)))
        val x = params.x.coerceIn(0, (display.widthPixels - width).coerceAtLeast(0))
        val y = params.y.coerceIn(context.dp(24),
            (display.heightPixels - view.height - context.dp(32)).coerceAtLeast(context.dp(24)))
        if (width != params.width || x != params.x || y != params.y) {
            params.width = width; params.x = x; params.y = y
            runCatching { manager.updateViewLayout(view, params) }
        }
    }

    private fun installDrag(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnClickListener { onOpen() }
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        val display = context.resources.displayMetrics
                        params.x = (startX + dx.toInt()).coerceIn(0, (display.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(context.dp(24),
                            (display.heightPixels - (root?.height ?: context.dp(52)) - context.dp(32)).coerceAtLeast(context.dp(24)))
                        root?.let { runCatching { manager.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** Capture coordinator must restore in finally/cancellation as well as screenshot callbacks. */
    fun setCaptureHidden(hidden: Boolean) {
        captureHidden = hidden
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        indicator?.setRecording(snapshot.phase == DemoOperationPhase.RECORDING && !hidden && !hostVisible)
    }

    fun setHostVisible(visible: Boolean) {
        hostVisible = visible
        bind(snapshot)
    }

    fun hide() {
        indicator?.setRecording(false)
        root?.let { runCatching { manager.removeView(it) } }
        root = null; status = null; pause = null; finish = null; handle = null; indicator = null
    }

    /** Lifecycle test seam and host-facing alias for removing the recording window. */
    fun release() = hide()
}

/** Standard geometric play/stop icons; kept local to this small native control. */
private class RecordingActionDrawable(private val play: Boolean, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        if (play) {
            val path = Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
            canvas.drawPath(path, paint)
        } else canvas.drawRoundRect(5f, 5f, 19f, 19f, 1.5f, 1.5f, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class RecordingIndicator(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var recording = false
    private var pulse: ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setRecording(value: Boolean) {
        recording = value
        updateAnimation()
        invalidate()
    }

    private fun updateAnimation() {
        val animations = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
            else Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        val shouldAnimate = recording && isAttachedToWindow && isShown && windowVisibility == VISIBLE && animations
        if (shouldAnimate && pulse == null) {
            pulse = ValueAnimator.ofFloat(.4f, 1f).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { alpha = it.animatedValue as Float }
                start()
            }
        } else if (!shouldAnimate) {
            pulse?.cancel(); pulse = null; alpha = 1f
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { pulse?.cancel(); pulse = null; alpha = 1f; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateAnimation() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateAnimation() }
    override fun onDraw(canvas: Canvas) {
        paint.color = if (recording) Ui.Danger else TaskNoteUi.Secondary
        canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
    }
}

internal fun operationDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
