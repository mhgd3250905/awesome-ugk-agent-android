package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Focusable review window; transparent capture mode still blocks touches to the target app. */
internal class DemoOperationStepReviewOverlay(
    private val context: Context,
    private val onBegin: () -> Unit,
    private val onConfirm: (String, Boolean) -> Unit,
    private val onRevise: (String) -> Unit,
    private val onRetry: () -> Unit,
    private val onExit: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val frameFile: (String, String) -> File?,
    private val onAdjustPage: () -> Unit = {}
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: ScrollView? = null
    private var editor: EditText? = null
    private var key: String? = null
    private var captureHidden = false
    private val params = WindowManager.LayoutParams(-1, -1,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        0, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    fun bind(state: DemoOperationSnapshot) {
        if (state.phase == DemoOperationPhase.IDLE || state.phase == DemoOperationPhase.SAVING || state.phase == DemoOperationPhase.PAUSED ||
            state.guidePhase == DemoOperationGuidePhase.ACTING) { hide(); return }
        val nextKey = "${state.draftId}:${state.guidePhase}:${state.reviewStep}:${state.message}:${state.phase}"
        if (root != null && key == nextKey) return
        val correction = editor?.text?.toString()
        hide()
        key = nextKey
        val content = createContent(state, correction)
        val scroll = ScrollView(context).apply {
            tag = "operation_step_review_overlay"
            isFillViewport = true
            setBackgroundColor(TaskNoteUi.Paper)
            addView(content)
        }
        root = scroll
        applyCaptureVisibility(state)
        runCatching { manager.addView(scroll, params) }.onFailure {
            root = null
            onFailure("无法显示逐步确认悬浮窗，已停止录制：${it.message.orEmpty()}")
        }
    }

    internal fun createContent(state: DemoOperationSnapshot, correction: String? = null): LinearLayout {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(42), context.dp(24), context.dp(32))
        }
        fun line(value: String, size: Float = 16f) = TextView(context).apply {
            text = value; textSize = size; setTextColor(TaskNoteUi.Ink)
            setPadding(0, context.dp(8), 0, context.dp(8))
        }
        fun button(label: String, primary: Boolean = false, action: () -> Unit) {
            body.addView(TaskNoteUi.button(context, label, primary) { closeKeyboard(); action() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        }
        body.addView(TaskNoteUi.label(context, "逐步教我 · 第 ${state.stepNumber} 步"))
        val ready = state.guidePhase == DemoOperationGuidePhase.READY
        val review = state.guidePhase == DemoOperationGuidePhase.REVIEW
        val analyzing = state.guidePhase == DemoOperationGuidePhase.ANALYZING
        body.addView(line(if (ready) "请完成第 ${state.stepNumber} 步操作" else if (analyzing) "正在整理这一步" else "核对这一步", 25f))
        if (ready) {
            body.addView(line(if (state.stepNumber == 1)
                "点击「开始这一步」后，先打开目标 App，再点悬浮条上的「已完成」。核对后再做下一步。"
                else "点击下方按钮后，屏幕会交还给你。只做一步操作，完成后点悬浮条上的「已完成」。"))
            button("开始这一步", true, onBegin)
            body.addView(line("需要回到操作前页面？先调整页面期间不会记录，调整好后点悬浮条的继续按钮。", 14f))
            button("先调整页面", action = onAdjustPage)
        } else {
            state.reviewStep?.let { step ->
                val frameId = step.postFrameId ?: step.preFrameId
                val file = state.draftId?.let { id -> frameId?.let { frameFile(id, it) } }
                val bitmap = file?.let { BitmapFactory.decodeFile(it.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 2 }) }
                if (bitmap != null) body.addView(ImageView(context).apply {
                    setImageBitmap(bitmap); adjustViewBounds = true; maxHeight = context.dp(280)
                    scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "这一步的操作结果截图"
                }, LinearLayout.LayoutParams(-1, context.dp(260)))
                else body.addView(line("这一步暂无可用截图，请结合操作摘要核对。", 14f))
                body.addView(line(step.aiSummary ?: step.localSummary))
                editor = EditText(context).apply {
                    tag = "operation_step_correction"
                    hint = "哪里需要纠正？例如：这里点的是显示设置"
                    setText(correction ?: step.userCorrection)
                    setTextColor(TaskNoteUi.Ink); setHintTextColor(TaskNoteUi.Secondary)
                    minLines = 2; maxLines = 5
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    background = Ui.rounded(context, Ui.Surface, 12, TaskNoteUi.Rule)
                    setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                    isEnabled = review
                }
                body.addView(editor, LinearLayout.LayoutParams(-1, -2))
                if (review) {
                    if (state.guidedAiEnabled) button(if (step.aiSummary.isNullOrBlank()) "重新整理这一步" else "让 AI 根据纠正更新摘要") { onRevise(editor?.text?.toString().orEmpty()) }
                    if (!step.aiSummary.isNullOrBlank()) {
                        button("确认并进行下一步", true) { onConfirm(editor?.text?.toString().orEmpty(), false) }
                        button("确认并结束录制") { onConfirm(editor?.text?.toString().orEmpty(), true) }
                    } else body.addView(line("请先完成 AI 整理。可填写纠正后重试，或结束并保留已有证据。", 14f))
                    button("这一步重做") { onRetry() }
                }
            }
            state.message?.let { body.addView(line(it, 14f)) }
        }
        button(if (analyzing) "停止整理并结束，保留记录" else "结束录制，保留已有记录", action = onExit)
        return body
    }

    private var lastState: DemoOperationSnapshot? = null
    private fun applyCaptureVisibility(state: DemoOperationSnapshot) {
        lastState = state
        val capturing = captureHidden || state.guidePhase == DemoOperationGuidePhase.PREPARING ||
            state.guidePhase == DemoOperationGuidePhase.CAPTURING
        // Release accessibility focus without releasing the full-screen touch boundary.
        params.flags = if (capturing) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0
        params.alpha = if (capturing) 0f else 1f
        root?.let { view ->
            view.alpha = params.alpha
            if (view.isAttachedToWindow) runCatching { manager.updateViewLayout(view, params) }
                .onFailure { onFailure("无法更新逐步确认悬浮窗，已停止录制") }
        }
    }
    fun setCaptureHidden(hidden: Boolean) { captureHidden = hidden; lastState?.let(::applyCaptureVisibility) }
    private fun closeKeyboard() {
        root?.let { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        editor?.clearFocus()
    }
    fun hide() {
        closeKeyboard()
        root?.let { runCatching { manager.removeView(it) } }
        root = null; editor = null; key = null
    }
}
