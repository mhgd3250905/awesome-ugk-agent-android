package com.ugk.pi.android.testapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Cross-app, user-controlled Agent surface.
 *
 * The overlay is deliberately a renderer and interaction shell. Agent
 * execution remains owned by MainActivity, while this class exposes only
 * snapshots and user intents through callbacks.
 */
class AgentFloatingWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private var expandedView: View? = null
    private var collapsedView: View? = null
    private var contentContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var statusText: TextView? = null
    private var titleText: TextView? = null
    private var inputField: EditText? = null
    private var sendButton: TextView? = null
    private var stopButton: TextView? = null
    private var collapsedTitleText: TextView? = null
    private var collapsedStatusText: TextView? = null

    private var snapshot = AgentOverlaySnapshot(
        title = "Agent",
        statusLabel = "就绪"
    )
    private val legacyLogs = ArrayDeque<String>()
    private val expandedStepKeys = LinkedHashSet<String>()
    private var expandedX = dp(16)
    private var expandedY = dp(160)
    private var collapsedX = dp(16)
    private var collapsedY = dp(180)

    var onSendMessage: ((String) -> Unit)? = null
    var onStopAgent: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null

    private val expandedParams = WindowManager.LayoutParams().apply {
        width = expandedWidth()
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = overlayType
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        x = expandedX
        y = expandedY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private val collapsedParams = WindowManager.LayoutParams().apply {
        width = dp(112)
        height = dp(46)
        type = overlayType
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        x = collapsedX
        y = collapsedY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
    }

    fun show() {
        if (!Settings.canDrawOverlays(context) || isShowing()) return
        showCollapsed()
    }

    fun showExpanded() {
        if (!Settings.canDrawOverlays(context)) return
        if (expandedView != null) return

        collapsedX = collapsedParams.x
        collapsedY = collapsedParams.y
        hideCollapsed()
        expandedParams.width = expandedWidth()
        expandedParams.x = clampX(collapsedX, expandedParams.width)
        expandedParams.y = clampY(collapsedY)
        expandedX = expandedParams.x
        expandedY = expandedParams.y

        val view = buildExpandedView()
        if (addViewSafely(view, expandedParams)) {
            expandedView = view
            renderSnapshot()
        } else {
            collapsedX = expandedX
            collapsedY = expandedY
            showCollapsed()
        }
    }

    fun hide() {
        hideExpanded()
        hideCollapsed()
    }

    fun isShowing(): Boolean = expandedView != null || collapsedView != null

    /** Render a stable, complete snapshot without imposing a text length cap. */
    fun bindSnapshot(value: AgentOverlaySnapshot) {
        snapshot = value.copy(steps = value.steps.toList())
        expandedStepKeys.retainAll(snapshot.steps.indices.map(::stepKey).toSet())
        renderSnapshot()
    }

    /** Legacy bridge retained for callers that update only a status label. */
    fun setStatus(text: String) {
        snapshot = snapshot.copy(statusLabel = text, statusDetail = text)
        renderSnapshot()
    }

    /** Legacy bridge retained for callers that append an activity line. */
    fun addLog(text: String) {
        if (text.isNotBlank()) {
            legacyLogs.addLast(text)
            while (legacyLogs.size > 40) legacyLogs.removeFirst()
        }
        renderSnapshot()
    }

    fun clear() {
        legacyLogs.clear()
        expandedStepKeys.clear()
        snapshot = snapshot.copy(
            statusLabel = "Agent 就绪",
            statusDetail = null,
            latestMessage = null,
            latestMessageRole = null,
            steps = emptyList(),
            isBusy = false,
            queuedMessages = 0
        )
        renderSnapshot()
    }

    /** Keep input available while busy so new messages can be queued. */
    fun setSending(sending: Boolean) {
        snapshot = snapshot.copy(isBusy = sending)
        renderSnapshot()
    }

    private fun showCollapsed() {
        if (collapsedView != null) return
        collapsedParams.x = clampX(collapsedX, collapsedParams.width)
        collapsedParams.y = clampY(collapsedY)
        val view = buildCollapsedView()
        if (addViewSafely(view, collapsedParams)) collapsedView = view
    }

    private fun hideCollapsed() {
        collapsedView?.let(::removeViewSafely)
        collapsedView = null
    }

    private fun hideExpanded() {
        inputField?.let { field ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(field.windowToken, 0)
        }
        expandedView?.let(::removeViewSafely)
        expandedView = null
        contentContainer = null
        scrollView = null
        statusText = null
        titleText = null
        inputField = null
        sendButton = null
        stopButton = null
    }

    private fun collapseToBubble() {
        expandedX = expandedParams.x
        expandedY = expandedParams.y
        collapsedX = clampX(expandedX, collapsedParams.width)
        collapsedY = clampY(expandedY)
        hideExpanded()
        showCollapsed()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCollapsedView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = Ui.rounded(context, Ui.SurfaceElevated, 23, Ui.Outline)
            contentDescription = "Agent 悬浮窗，点击展开"
        }
        val icon = TextView(context).apply {
            text = "✦"
            textSize = 16f
            setTextColor(if (snapshot.isBusy) Ui.MintDark else Ui.Mint)
            gravity = Gravity.CENTER
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        collapsedTitleText = TextView(context).apply {
            text = snapshot.title
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        collapsedStatusText = TextView(context).apply {
            text = snapshot.statusLabel
            textSize = 10f
            setTextColor(statusColor(snapshot.statusLabel))
        }
        labels.addView(collapsedTitleText)
        labels.addView(collapsedStatusText)
        root.addView(icon, LinearLayout.LayoutParams(dp(20), dp(34)))
        root.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        setupDrag(root, collapsedParams) {
            showExpanded()
        }
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExpandedView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SurfaceElevated, 16, Ui.Outline)
            clipChildren = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(2), dp(6))
        }
        val headerText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(6), 0)
        }
        titleText = TextView(context).apply {
            text = snapshot.title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        statusText = TextView(context).apply {
            text = snapshot.statusLabel
            textSize = 11f
            setTextColor(statusColor(snapshot.statusLabel))
            setPadding(0, dp(2), 0, 0)
        }
        headerText.addView(titleText)
        headerText.addView(statusText)
        header.addView(headerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(actionButton("主界面", "打开完整 Agent 主界面") {
            onOpenApp?.invoke()
        })
        header.addView(actionButton("隐藏", "隐藏 Agent 悬浮窗") {
            onHide?.invoke()
        })
        header.addView(actionButton("收起", "收起 Agent 悬浮窗") {
            collapseToBubble()
        })
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setupDrag(header, expandedParams) { }

        scrollView = OverlayScrollView(context, maxScrollHeight()).apply {
            isFillViewport = false
            setBackgroundColor(Ui.Surface)
        }
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        scrollView?.addView(contentContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxScrollHeight()
        ))

        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = Ui.rounded(context, Ui.SurfaceSoft, 12)
            setPadding(dp(5), dp(3), dp(5), dp(3))
        }
        inputField = EditText(context).apply {
            hint = "给 Agent 发消息"
            setHintTextColor(Ui.TextMuted)
            setTextColor(Ui.TextPrimary)
            textSize = 13f
            minLines = 1
            maxLines = 5
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setPadding(dp(5), dp(3), dp(5), dp(3))
            contentDescription = "给 Agent 输入消息"
        }
        sendButton = actionButton("发送", "发送悬浮窗消息") { sendInput() }
        stopButton = actionButton("停止", "停止 Agent 当前任务") { onStopAgent?.invoke() }
        composer.addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(sendButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply { marginStart = dp(4) })
        composer.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply { marginStart = dp(4) })
        root.addView(composer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        return root
    }

    private fun actionButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.MintDark)
            gravity = Gravity.CENTER
            minWidth = dp(42)
            minHeight = dp(36)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = Ui.rounded(context, Ui.SurfaceSoft, 10)
            contentDescription = description
            setOnClickListener { action() }
        }

    private fun sendInput() {
        val field = inputField ?: return
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        field.setText("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        onSendMessage?.invoke(text)
    }

    private fun renderSnapshot() {
        collapsedTitleText?.text = snapshot.title
        collapsedStatusText?.apply {
            text = snapshot.statusLabel
            setTextColor(statusColor(snapshot.statusLabel))
        }
        titleText?.text = snapshot.title
        statusText?.apply {
            text = snapshot.statusLabel
            setTextColor(statusColor(snapshot.statusLabel))
        }
        stopButton?.visibility = if (snapshot.isBusy) View.VISIBLE else View.GONE
        if (expandedView == null) return

        val container = contentContainer ?: return
        container.removeAllViews()
        snapshot.statusDetail?.takeIf { it.isNotBlank() }?.let { detail ->
            addText(container, detail, 12f, Ui.TextSecondary, Ui.SurfaceSoft, dp(10))
        }

        snapshot.latestMessage?.takeIf { it.isNotBlank() }?.let { message ->
            val role = if (snapshot.latestMessageRole == "assistant") "Agent" else "你"
            addSectionLabel(container, role)
            addText(container, message, 13f, Ui.TextPrimary, Ui.SurfaceElevated, dp(10), selectable = true)
        }

        if (snapshot.steps.isNotEmpty()) {
            addSectionLabel(container, "Agent 过程")
            snapshot.steps.forEachIndexed { index, step -> addStep(container, index, step) }
        }

        if (legacyLogs.isNotEmpty()) {
            addSectionLabel(container, "活动记录")
            legacyLogs.forEach { line -> addText(container, line, 11f, Ui.TextSecondary, null, dp(2)) }
        }

        if (snapshot.queuedMessages > 0) {
            addText(
                container,
                "已排队 ${snapshot.queuedMessages} 条消息，当前任务完成后继续",
                11f,
                Ui.MintDark,
                Ui.SurfaceSoft,
                dp(8)
            )
        }
        if (container.childCount == 0) {
            addText(container, "等待 Agent 状态更新", 12f, Ui.TextSecondary, null, dp(8))
        }
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addSectionLabel(container: LinearLayout, text: String) {
        container.addView(TextView(context).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.MintDark)
            setPadding(dp(2), dp(8), dp(2), dp(4))
        })
    }

    private fun addText(
        container: LinearLayout,
        text: String,
        size: Float,
        color: Int,
        backgroundColor: Int?,
        padding: Int,
        selectable: Boolean = false
    ) {
        container.addView(TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(padding, padding, padding, padding)
            if (selectable) setTextIsSelectable(true)
            backgroundColor?.let { background = Ui.rounded(context, it, 10) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
    }

    private fun addStep(container: LinearLayout, index: Int, step: AgentOverlayStep) {
        val key = stepKey(index)
        val expanded = expandedStepKeys.contains(key)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, if (expanded) Ui.SurfaceSoft else Ui.SurfaceElevated, 10, Ui.Outline)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            contentDescription = "${step.title}，${step.statusLabel}，${if (expanded) "收起" else "展开"}详情"
            setOnClickListener {
                if (expanded) expandedStepKeys.remove(key) else expandedStepKeys.add(key)
                renderSnapshot()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val marker = TextView(context).apply {
            text = if (step.statusLabel.contains("失败")) "!" else if (step.statusLabel.contains("完成")) "✓" else "•"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(statusColor(step.statusLabel))
            gravity = Gravity.CENTER
        }
        val title = TextView(context).apply {
            text = step.title
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
            setPadding(dp(6), 0, dp(6), 0)
        }
        val disclosure = TextView(context).apply {
            text = if (expanded) "收起" else "详情"
            textSize = 10f
            setTextColor(Ui.MintDark)
            gravity = Gravity.CENTER
        }
        header.addView(marker, LinearLayout.LayoutParams(dp(20), dp(24)))
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (step.detail != null || step.resultSummary != null) {
            header.addView(disclosure, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        row.addView(header)
        step.detail?.takeIf { it.isNotBlank() }?.let {
            addInlineText(row, it, 11f, Ui.TextSecondary)
        }
        if (expanded) {
            step.resultSummary?.takeIf { it.isNotBlank() }?.let {
                addInlineText(row, it, 11f, Ui.TextPrimary, selectable = true)
            }
        }
        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
    }

    private fun addInlineText(
        container: LinearLayout,
        text: String,
        size: Float,
        color: Int,
        selectable: Boolean = false
    ) {
        container.addView(TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(dp(26), dp(3), dp(2), 0)
            if (selectable) setTextIsSelectable(true)
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(
        dragTarget: View,
        params: WindowManager.LayoutParams,
        onClick: () -> Unit
    ) {
        dragTarget.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (!dragging && (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8))) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = clampX(initialX + dx.toInt(), params.width)
                            params.y = clampY(initialY + dy.toInt())
                            if (params === expandedParams) {
                                expandedX = params.x
                                expandedY = params.y
                            } else {
                                collapsedX = params.x
                                collapsedY = params.y
                            }
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) onClick()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return true
            }
        })
    }

    private fun addViewSafely(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching {
            windowManager.addView(view, params)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to add Agent overlay window", it)
            false
        }

    private fun removeViewSafely(view: View) {
        runCatching { windowManager.removeView(view) }
    }

    private fun statusColor(status: String): Int = when {
        status.contains("失败") -> Ui.Danger
        status.contains("确认") || status.contains("等待") -> Ui.Warning
        status.contains("完成") -> Ui.Success
        else -> Ui.MintDark
    }

    private fun stepKey(index: Int): String = "step-$index"

    private fun expandedWidth(): Int = minOf(dp(360), (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(260)))

    private fun maxScrollHeight(): Int = minOf(dp(360), (context.resources.displayMetrics.heightPixels * 0.48f).toInt())

    private fun clampX(value: Int, width: Int): Int = value.coerceIn(dp(8), (context.resources.displayMetrics.widthPixels - width - dp(8)).coerceAtLeast(dp(8)))

    private fun clampY(value: Int): Int = value.coerceIn(dp(48), (context.resources.displayMetrics.heightPixels - dp(100)).coerceAtLeast(dp(48)))

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private class OverlayScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private companion object {
        const val TAG = "AgentFloatingWindow"
    }
}
