package com.ugk.pi.android.testapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ugk.pi.android.UserConfirmationDialogRequest
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Cross-app, user-controlled Agent surface.
 *
 * The overlay is deliberately a renderer and interaction shell. Agent
 * execution remains owned by MainActivity, while this class exposes only
 * snapshots and user intents through callbacks.
 */
class AgentFloatingWindow(private val context: Context) : ConfirmationOverlayHost {

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
    private var composerDraft = ""
    private var pendingConfirmation: AgentOverlayConfirmation? = null
    private var confirmationResult: ((String) -> Unit)? = null
    private var expandedX = dp(16)
    private var expandedY = dp(160)
    private var collapsedX = dp(16)
    private var collapsedY = dp(180)

    var onSendMessage: ((String) -> Boolean)? = null
    var onStopAgent: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null
    var onDraftChanged: ((String) -> Unit)? = null

    private val expandedParams = WindowManager.LayoutParams().apply {
        width = expandedWidth()
        height = expandedHeight()
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
        expandedParams.width = clampExpandedWidth(expandedParams.width)
        expandedParams.height = clampExpandedHeight(expandedParams.height)
        expandedParams.x = clampX(collapsedX, expandedParams.width)
        expandedParams.y = clampY(collapsedY, expandedParams.height)
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

    override fun showConfirmation(
        request: UserConfirmationDialogRequest,
        onResult: (String) -> Unit
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        confirmationResult = onResult
        pendingConfirmation = request.toOverlayConfirmation()
        snapshot = snapshot.copy(pendingConfirmation = pendingConfirmation)
        if (expandedView == null) showExpanded()
        renderSnapshot()
        return expandedView != null
    }

    override fun hideConfirmation() {
        pendingConfirmation = null
        confirmationResult = null
        snapshot = snapshot.copy(pendingConfirmation = null)
        renderSnapshot()
    }

    /** Render a stable, complete snapshot with bounded confirmation summaries. */
    fun bindSnapshot(value: AgentOverlaySnapshot) {
        val confirmation = pendingConfirmation ?: value.pendingConfirmation
        pendingConfirmation = confirmation
        snapshot = value.copy(
            steps = value.steps.toList(),
            pendingConfirmation = confirmation
        )
        expandedStepKeys.retainAll(snapshot.steps.map { it.id }.toSet())
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
        composerDraft = ""
        inputField?.setText("")
        onDraftChanged?.invoke("")
        pendingConfirmation = null
        confirmationResult = null
        snapshot = snapshot.copy(
            statusLabel = "Agent 就绪",
            statusDetail = null,
            latestMessage = null,
            latestMessageRole = null,
            steps = emptyList(),
            isBusy = false,
            queuedMessages = 0,
            pendingConfirmation = null
        )
        renderSnapshot()
    }

    /** Keep input available while busy so new messages can be queued. */
    fun setSending(sending: Boolean) {
        snapshot = snapshot.copy(isBusy = sending)
        renderSnapshot()
    }

    /** Synchronize the hidden overlay composer with the main Activity draft. */
    fun setComposerDraft(value: String) {
        if (composerDraft == value && inputField?.text?.toString() == value) return
        composerDraft = value
        inputField?.let { field ->
            if (field.text?.toString() != value) field.setText(value)
            field.setSelection(field.length())
        }
        onDraftChanged?.invoke(composerDraft)
    }

    private fun showCollapsed() {
        if (collapsedView != null) return
        collapsedParams.x = clampX(collapsedX, collapsedParams.width)
        collapsedParams.y = clampY(collapsedY, collapsedParams.height)
        val view = buildCollapsedView()
        if (addViewSafely(view, collapsedParams)) collapsedView = view
    }

    private fun hideCollapsed() {
        collapsedView?.let(::removeViewSafely)
        collapsedView = null
    }

    private fun hideExpanded() {
        inputField?.let { field ->
            composerDraft = field.text?.toString().orEmpty()
            onDraftChanged?.invoke(composerDraft)
        }
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
        collapsedY = clampY(expandedY, collapsedParams.height)
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

        setupDrag(root, root, collapsedParams) {
            showExpanded()
        }
        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildExpandedView(): View {
        val root = FrameLayout(context).apply {
            background = Ui.rounded(context, Ui.SurfaceElevated, 16, Ui.Outline)
            clipChildren = true
        }
        val contentRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The resize affordance is a transparent overlay on the corner,
            // so it does not reserve a visible block in the content layout.
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Keep the resize layer underneath the content. Child controls such
        // as the composer/send button must win hit testing in the overlap;
        // the handle receives touches only from the exposed rounded corner.
        val resizeHandle = ResizeCornerHandle(context).apply {
            contentDescription = "从右下角拖动调整 Agent 悬浮窗大小"
            isClickable = true
            isFocusable = true
        }
        root.addView(resizeHandle, FrameLayout.LayoutParams(
            dp(32),
            dp(32),
            Gravity.END or Gravity.BOTTOM
        ).apply {
            marginEnd = 0
            bottomMargin = 0
        })
        setupResize(resizeHandle, root)

        root.addView(contentRoot, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

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
        contentRoot.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // Only the title/status area moves the window. The three action
        // buttons keep their own click targets and never become drag handles.
        setupDrag(headerText, root, expandedParams) { }

        scrollView = ScrollView(context).apply {
            isFillViewport = true
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
        contentRoot.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = Ui.rounded(context, Ui.SurfaceElevated, 14, Ui.Outline)
            setPadding(dp(6), dp(3), dp(6), dp(3))
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
            setText(composerDraft)
            setSelection(length())
        }
        inputField?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                composerDraft = text?.toString().orEmpty()
                onDraftChanged?.invoke(composerDraft)
                renderSnapshot()
            }

            override fun afterTextChanged(editable: android.text.Editable?) = Unit
        })
        sendButton = actionButton("发送", "发送悬浮窗消息") { sendInput() }
        stopButton = actionButton("停止", "停止 Agent 当前任务") { onStopAgent?.invoke() }
        composer.addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(sendButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(38)
        ).apply { marginStart = dp(4) })
        composer.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(38)
        ).apply { marginStart = dp(4) })
        contentRoot.addView(composer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun actionButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 11.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.MintDark)
            gravity = Gravity.CENTER
            minWidth = dp(42)
            minHeight = dp(34)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = Ui.clickableRounded(context, Ui.SurfaceSubtle, Ui.SurfaceSoft, 10, Ui.Outline)
            contentDescription = description
            setOnClickListener { action() }
            // A swipe that starts on a button must stay a window drag, not
            // accidentally invoke Hide/Collapse when the finger is lifted.
            setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var moved = false

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            moved = false
                            view.isPressed = true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!moved && (
                                kotlin.math.abs(event.rawX - downX) > touchSlop() ||
                                    kotlin.math.abs(event.rawY - downY) > touchSlop()
                                )
                            ) {
                                moved = true
                                view.isPressed = false
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val shouldClick = !moved
                            view.isPressed = false
                            if (shouldClick) view.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            moved = true
                            view.isPressed = false
                        }
                    }
                    return true
                }
            })
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize(handle: View, windowRoot: View) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0
            private var initialHeight = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = expandedParams.width
                        initialHeight = expandedParams.height
                        touchX = event.rawX
                        touchY = event.rawY
                        view.isPressed = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val nextWidth = clampExpandedWidth(
                            initialWidth + (event.rawX - touchX).toInt()
                        )
                        val nextHeight = clampExpandedHeight(
                            initialHeight + (event.rawY - touchY).toInt()
                        )
                        if (nextWidth != expandedParams.width || nextHeight != expandedParams.height) {
                            expandedParams.width = nextWidth
                            expandedParams.height = nextHeight
                            expandedParams.x = clampX(expandedParams.x, nextWidth)
                            expandedParams.y = clampY(expandedParams.y, nextHeight)
                            expandedX = expandedParams.x
                            expandedY = expandedParams.y
                            runCatching {
                                windowManager.updateViewLayout(windowRoot, expandedParams)
                            }.onFailure {
                                Log.w(TAG, "Unable to resize Agent overlay window", it)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun sendInput() {
        val field = inputField ?: return
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val accepted = onSendMessage?.invoke(text) == true
        if (!accepted) {
            renderSnapshot()
            return
        }
        composerDraft = ""
        field.setText("")
        onDraftChanged?.invoke("")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(field.windowToken, 0)
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
        sendButton?.let { button ->
            val hasText = !inputField?.text.isNullOrBlank()
            button.isEnabled = hasText
            button.alpha = if (hasText) 1f else 0.55f
        }
        if (expandedView == null) return

        val container = contentContainer ?: return
        container.removeAllViews()
        snapshot.statusDetail?.takeIf { it.isNotBlank() }?.let { detail ->
            addText(container, detail, 12f, Ui.TextSecondary, Ui.SurfaceSoft, dp(10))
        }

        snapshot.pendingConfirmation?.let { confirmation ->
            addConfirmation(container, confirmation)
        }

        // Keep the user's prompt before the run, but place the assistant's
        // final answer after the process and activity history. This mirrors
        // the main conversation timeline instead of putting the answer above
        // the evidence that explains how it was produced.
        val latestMessage = snapshot.latestMessage?.takeIf { it.isNotBlank() }
        val showLatestBeforeProcess = latestMessage != null && snapshot.latestMessageRole != "assistant"
        if (showLatestBeforeProcess) {
            addLatestMessage(container, latestMessage!!, snapshot.latestMessageRole)
        }

        if (snapshot.steps.isNotEmpty()) {
            addSectionLabel(container, "Agent 过程")
            snapshot.steps.forEach { step -> addStep(container, step) }
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
        if (!showLatestBeforeProcess) {
            latestMessage?.let { message ->
                addLatestMessage(container, message, snapshot.latestMessageRole)
            }
        }
        if (container.childCount == 0) {
            addText(
                container,
                if (snapshot.isBusy) {
                    "等待 Agent 状态更新"
                } else {
                    "当前没有运行中的任务\n可以直接在这里发送消息"
                },
                12f,
                Ui.TextSecondary,
                null,
                dp(8)
            )
        }
        scrollView?.post {
            if (snapshot.pendingConfirmation != null) {
                scrollView?.scrollTo(0, 0)
            } else {
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun addLatestMessage(
        container: LinearLayout,
        message: String,
        role: String?
    ) {
        val label = if (role == "assistant") "Agent" else "你"
        addSectionLabel(container, label)
        addText(container, message, 13f, Ui.TextPrimary, Ui.SurfaceElevated, dp(10), selectable = true)
    }

    private fun addConfirmation(
        container: LinearLayout,
        confirmation: AgentOverlayConfirmation
    ) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SurfaceSoft, 12, Ui.Warning)
            setPadding(dp(10), dp(10), dp(10), dp(8))
            contentDescription = "需要确认：${confirmation.title}"
        }
        card.addView(TextView(context).apply {
            text = "需要你的确认"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.Warning)
        })
        card.addView(TextView(context).apply {
            text = confirmation.title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
            setPadding(0, dp(4), 0, dp(4))
        })
        card.addView(TextView(context).apply {
            text = confirmation.message
            textSize = 12f
            setTextColor(Ui.TextPrimary)
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(8))
        })
        confirmation.target?.let { target ->
            card.addView(TextView(context).apply {
                text = "目标 Tool：${target.toolName}"
                textSize = 12f
                setTextColor(Ui.TextPrimary)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
            card.addView(TextView(context).apply {
                text = "输入摘要：${target.inputSummary}"
                textSize = 11f
                setTextColor(Ui.TextSecondary)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(8))
            })
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = if (confirmation.buttons.size <= 2) {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            gravity = Gravity.END
        }
        confirmation.buttons.forEachIndexed { index, button ->
            val action = actionButton(button.label, "确认：${button.label}") {
                selectConfirmation(button.id)
            }
            val params = if (confirmation.buttons.size <= 2) {
                LinearLayout.LayoutParams(0, dp(40), 1f)
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40)
                )
            }
            if (index > 0) {
                if (confirmation.buttons.size <= 2) params.marginStart = dp(4)
                else params.topMargin = dp(4)
            }
            buttonRow.addView(action, params)
        }
        card.addView(buttonRow)
        container.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
    }

    private fun selectConfirmation(buttonId: String) {
        val callback = confirmationResult
        confirmationResult = null
        pendingConfirmation = null
        snapshot = snapshot.copy(pendingConfirmation = null)
        renderSnapshot()
        callback?.invoke(buttonId)
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

    private fun addStep(container: LinearLayout, step: AgentOverlayStep) {
        val key = step.id
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

    /**
     * A transparent touch target that strengthens the panel's existing
     * rounded bottom-right corner instead of adding an icon or a tile.
     */
    private inner class ResizeCornerHandle(context: Context) : View(context) {
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Ui.MintDark
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = dp(4).toFloat()
            val radius = dp(14).toFloat()
            val inset = dp(1).toFloat()
            cornerPaint.strokeWidth = stroke
            canvas.drawArc(
                RectF(
                    width - radius * 2 - inset,
                    height - radius * 2 - inset,
                    width - inset,
                    height - inset
                ),
                0f,
                90f,
                false,
                cornerPaint
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(
        dragTarget: View,
        windowRoot: View,
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
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop() || kotlin.math.abs(dy) > touchSlop())) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = clampX(initialX + dx.toInt(), params.width)
                            params.y = clampY(initialY + dy.toInt(), params.height)
                            if (params === expandedParams) {
                                expandedX = params.x
                                expandedY = params.y
                            } else {
                                collapsedX = params.x
                                collapsedY = params.y
                            }
                            runCatching {
                                windowManager.updateViewLayout(windowRoot, params)
                            }.onFailure {
                                Log.w(TAG, "Unable to move Agent overlay window", it)
                            }
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

    private fun expandedWidth(): Int = clampExpandedWidth(dp(360))

    private fun expandedHeight(): Int = clampExpandedHeight(dp(520))

    private fun clampExpandedWidth(value: Int): Int = value.coerceIn(
        minExpandedWidth(),
        maxExpandedWidth()
    )

    private fun clampExpandedHeight(value: Int): Int = value.coerceIn(
        minExpandedHeight(),
        maxExpandedHeight()
    )

    private fun minExpandedWidth(): Int = minOf(dp(280), availableWidth())

    private fun maxExpandedWidth(): Int = availableWidth().coerceAtLeast(minExpandedWidth())

    private fun minExpandedHeight(): Int = minOf(dp(240), availableHeight())

    private fun maxExpandedHeight(): Int = availableHeight().coerceAtLeast(minExpandedHeight())

    private fun availableWidth(): Int = (context.resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(dp(1))

    private fun availableHeight(): Int = (context.resources.displayMetrics.heightPixels - dp(56)).coerceAtLeast(dp(1))

    private fun clampX(value: Int, width: Int): Int = value.coerceIn(
        dp(8),
        (context.resources.displayMetrics.widthPixels - width - dp(8)).coerceAtLeast(dp(8))
    )

    private fun clampY(value: Int, height: Int): Int = value.coerceIn(
        dp(48),
        (context.resources.displayMetrics.heightPixels - height - dp(8)).coerceAtLeast(dp(48))
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun touchSlop(): Int = ViewConfiguration.get(context).scaledTouchSlop

    private companion object {
        const val TAG = "AgentFloatingWindow"
    }
}
