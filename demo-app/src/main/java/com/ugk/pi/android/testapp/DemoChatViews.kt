package com.ugk.pi.android.testapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Chat-first 展示组件：消息保持轻量，Agent 过程以默认收起的可展开卡片承载。
 *
 * 该文件只负责 View 和展示回调，不持有 Activity、Runtime 或会话状态。
 * 组装方可使用 [DemoChatMessageView.bind]、[DemoChatMessageView.updateText]、
 * [DemoChatProcessCardView.bind] 和 [DemoChatProcessCardView.setExpanded] 更新 UI。
 */

/** 消息在聊天流中的视觉角色。 */
enum class DemoChatMessageRole(val accessibilityLabel: String) {
    USER("你"),
    ASSISTANT("助手")
}

/** 过程卡片可展示的 Agent 阶段。 */
enum class DemoChatProcessStage(
    val label: String,
    val accentColor: Int
) {
    THINKING("思考中", DemoChatPalette.mintDark),
    TOOL_CALL("调用工具", DemoChatPalette.mintDark),
    WAITING_CONFIRMATION("等待确认", DemoChatPalette.amber),
    RESULT("收到结果", DemoChatPalette.mintDark),
    COMPLETED("已完成", DemoChatPalette.mintDark),
    ERROR("执行失败", DemoChatPalette.danger)
}

/** 一行可验证的 Agent 过程步骤，不包含模型隐性思维内容。 */
enum class DemoChatProcessStepStatus {
    COMPLETE,
    ACTIVE,
    WAITING,
    ERROR,
    PENDING
}

data class DemoChatProcessStep(
    val id: String,
    val title: CharSequence,
    val status: DemoChatProcessStepStatus,
    val detail: CharSequence? = null,
    val resultSummary: CharSequence? = null
)

/** 过程卡片的可渲染状态。详情只在展开后出现。 */
data class DemoChatProcessState(
    val stage: DemoChatProcessStage,
    val toolName: CharSequence? = null,
    val resultSummary: CharSequence? = null,
    val steps: List<DemoChatProcessStep> = emptyList(),
    val footerLeft: CharSequence? = null,
    val footerRight: CharSequence? = null,
    val expanded: Boolean = false
)

/**
 * 一条聊天消息的原生 View。
 *
 * 每个实例只创建一个气泡子 View。用户消息右对齐，助手消息左对齐；气泡最大宽度
 * 随父容器变化，并对文本长度和行数做上限保护。
 */
class DemoChatMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val bubble = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(DemoChatPalette.textPrimary)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.14f)
        includeFontPadding = true
        minHeight = context.chatDp(44)
        setPadding(
            context.chatDp(16),
            context.chatDp(11),
            context.chatDp(16),
            context.chatDp(11)
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var role: DemoChatMessageRole = DemoChatMessageRole.ASSISTANT
    private var messageText: String = ""

    init {
        clipChildren = false
        clipToPadding = false
        setPadding(
            context.chatDp(12),
            context.chatDp(4),
            context.chatDp(12),
            context.chatDp(4)
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            bubble,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
            }
        )
        bind(role, messageText)
    }

    /** 绑定角色和消息正文；不会触发任何业务回调。 */
    fun bind(role: DemoChatMessageRole, text: CharSequence) {
        this.role = role
        // Chat content is user data. Do not silently trim it for presentation;
        // the parent ScrollView provides the bounded viewport instead.
        messageText = text.toString()
        bubble.text = messageText
        applyRoleStyle()
    }

    /** 只更新消息正文，保留当前角色。 */
    fun updateText(text: CharSequence) {
        bind(role, text)
    }

    /** 只更新消息角色，保留当前正文。 */
    fun updateRole(role: DemoChatMessageRole) {
        bind(role, messageText)
    }

    /** 同时更新角色和正文的便捷方法。 */
    fun update(role: DemoChatMessageRole, text: CharSequence) {
        bind(role, text)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        bubble.maxWidth = if (availableWidth > 0) {
            (availableWidth * MAX_BUBBLE_WIDTH_FRACTION).roundToInt()
        } else {
            context.chatDp(DEFAULT_BUBBLE_MAX_WIDTH_DP)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyRoleStyle() {
        val isUser = role == DemoChatMessageRole.USER
        bubble.background = roundedBackground(
            context = context,
            fillColor = if (isUser) DemoChatPalette.userBubble else DemoChatPalette.assistantBubble,
            strokeColor = if (isUser) DemoChatPalette.userStroke else DemoChatPalette.assistantStroke,
            radiusDp = 18
        )
        bubble.setTextColor(DemoChatPalette.textPrimary)
        val layoutParams = bubble.layoutParams as? LayoutParams ?: return
        layoutParams.gravity = if (isUser) Gravity.END else Gravity.START
        bubble.layoutParams = layoutParams
        contentDescription = buildString {
            append(role.accessibilityLabel)
            append("：")
            append(messageText.ifBlank { "空消息" })
        }
    }

    private companion object {
        const val DEFAULT_BUBBLE_MAX_WIDTH_DP = 320
        const val MAX_BUBBLE_WIDTH_FRACTION = 0.86f
    }
}

/**
 * Agent 过程卡片。
 *
 * 卡片默认收起，外层卡片和每个过程步骤拥有独立的展开状态。标题行始终展示阶段、
 * 工具名（如有）和外层展开状态；步骤默认只展示状态和一行摘要，详情由步骤自己的
 * 点击目标按需展开。
 */
class DemoChatProcessCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val header = LinearLayout(context)
    private val headerIcon = TextView(context)
    private val headerTitle = TextView(context)
    private val headerMeta = TextView(context)
    private val expansionView = TextView(context)
    private val collapsedSummaryView = TextView(context)
    private val stepsContainer = LinearLayout(context)
    private val footer = LinearLayout(context)
    private val footerLeftView = TextView(context)
    private val footerRightView = TextView(context)
    private val collapseFooterView = TextView(context)

    private var currentState = DemoChatProcessState(DemoChatProcessStage.THINKING)
    private var expanded = false
    private val expandedStepIds = linkedSetOf<String>()
    private var expandedChangeListener: ((Boolean) -> Unit)? = null
    private var stepExpandedChangeListener: ((String, Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(
            context.chatDp(14),
            context.chatDp(8),
            context.chatDp(14),
            context.chatDp(10)
        )
        minimumHeight = context.chatDp(56)
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        background = pressedCardBackground(context)

        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumHeight = context.chatDp(44)
        header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        configureHeaderIcon()
        configureHeaderTitle()
        configureHeaderMeta()
        configureExpansionView()
        configureCollapsedSummary()
        configureStepsContainer()
        configureFooter()
        configureCollapseFooter()

        header.addView(
            headerIcon,
            LayoutParams(context.chatDp(28), context.chatDp(28)).apply {
                marginEnd = context.chatDp(8)
            }
        )
        val headerText = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(headerTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(headerMeta, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        header.addView(headerText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            expansionView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.chatDp(8)
            }
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            collapsedSummaryView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            stepsContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        footer.addView(
            footerLeftView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        footer.addView(
            footerRightView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            collapseFooterView,
            LayoutParams(LayoutParams.MATCH_PARENT, context.chatDp(44))
        )

        setOnClickListener {
            setExpandedInternal(!expanded, notifyListener = true)
        }
        bind(currentState)
    }

    /** 绑定完整过程状态；不会触发展开回调，适合由外部状态刷新驱动。 */
    fun bind(state: DemoChatProcessState) {
        currentState = DemoChatProcessState(
            stage = state.stage,
            toolName = state.toolName?.toString()?.takeIf { it.isNotBlank() },
            resultSummary = state.resultSummary?.toString()?.takeIf { it.isNotBlank() },
            steps = state.steps,
            footerLeft = state.footerLeft?.toString()?.takeIf { it.isNotBlank() },
            footerRight = state.footerRight?.toString()?.takeIf { it.isNotBlank() },
            expanded = state.expanded
        )
        val stepIds = currentState.steps.mapTo(linkedSetOf()) { it.id }
        expandedStepIds.retainAll(stepIds)
        headerMeta.text = buildHeaderMeta()
        collapsedSummaryView.text = buildCollapsedSummary()
        renderSteps()
        footerLeftView.text = currentState.footerLeft ?: ""
        footerRightView.text = currentState.footerRight ?: ""
        footer.visibility = if (currentState.steps.isEmpty()) View.GONE else View.VISIBLE

        setExpandedInternal(currentState.expanded, notifyListener = false)
    }

    /** 用分散参数更新过程卡片，便于事件流直接映射到 View。 */
    fun update(
        stage: DemoChatProcessStage,
        toolName: CharSequence? = null,
        resultSummary: CharSequence? = null,
        steps: List<DemoChatProcessStep> = emptyList(),
        footerLeft: CharSequence? = null,
        footerRight: CharSequence? = null,
        expanded: Boolean = this.expanded
    ) {
        bind(
            DemoChatProcessState(
                stage = stage,
                toolName = toolName,
                resultSummary = resultSummary,
                steps = steps,
                footerLeft = footerLeft,
                footerRight = footerRight,
                expanded = expanded
            )
        )
    }

    /** 设置展开状态；状态发生变化时调用展示回调。 */
    fun setExpanded(expanded: Boolean) {
        setExpandedInternal(expanded, notifyListener = true)
    }

    /** 当前是否处于展开状态。 */
    fun isExpanded(): Boolean = expanded

    /** 注册或清除展开状态变化回调；回调只传递展示状态，不持有宿主引用。 */
    fun setOnExpandedChangeListener(listener: ((Boolean) -> Unit)?) {
        expandedChangeListener = listener
    }

    /** 注册步骤详情展开状态变化回调；回调只传递步骤 id 和展示状态。 */
    fun setOnStepExpandedChangeListener(listener: ((String, Boolean) -> Unit)?) {
        stepExpandedChangeListener = listener
    }

    private fun configureHeaderIcon() {
        headerIcon.apply {
            text = "✦"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(DemoChatPalette.mintDark)
            background = roundedBackground(
                context,
                DemoChatPalette.mintSoft,
                DemoChatPalette.mintStroke,
                14
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureHeaderTitle() {
        headerTitle.apply {
            text = "Agent 过程"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(DemoChatPalette.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureHeaderMeta() {
        headerMeta.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(DemoChatPalette.textSecondary)
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureExpansionView() {
        expansionView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(DemoChatPalette.mintDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureCollapsedSummary() {
        collapsedSummaryView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(DemoChatPalette.textSecondary)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            maxLines = 1
            setPadding(0, context.chatDp(5), 0, context.chatDp(2))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureStepsContainer() {
        stepsContainer.orientation = VERTICAL
        stepsContainer.setPadding(0, context.chatDp(4), 0, context.chatDp(2))
        stepsContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun configureFooter() {
        footer.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.chatDp(8), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        listOf(footerLeftView, footerRightView).forEach { view ->
            view.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(DemoChatPalette.textSecondary)
                maxLines = 1
            }
        }
    }

    private fun configureCollapseFooter() {
        collapseFooterView.apply {
            text = "收起整个过程 ︿"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(DemoChatPalette.mintDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            contentDescription = "收起整个过程"
            setOnClickListener { setExpanded(false) }
        }
    }

    private fun setExpandedInternal(value: Boolean, notifyListener: Boolean) {
        val changed = expanded != value
        expanded = value
        if (!value) {
            // Re-opening the outer card starts from a compact checklist again.
            expandedStepIds.clear()
        }
        currentState = currentState.copy(expanded = value)
        expansionView.text = if (value) "收起 ▲" else "展开 ▼"
        collapsedSummaryView.visibility = if (value) View.GONE else View.VISIBLE
        if (!value) {
            // The child views may still contain a previously expanded result.
            // Rebuild them while hidden so the next outer expansion is compact.
            renderSteps()
        }
        stepsContainer.visibility = if (value && currentState.steps.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        collapseFooterView.visibility = if (value && currentState.steps.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateAccessibilityState()

        if (notifyListener && changed) {
            expandedChangeListener?.invoke(value)
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun updateAccessibilityState() {
        val stateLabel = if (expanded) "已展开" else "已收起"
        val actionLabel = if (expanded) "点击收起" else "点击展开"
        contentDescription = buildString {
            append("过程卡片，阶段：")
            append(currentState.stage.label)
            append("，步骤 ")
            append(currentState.steps.size)
            append(" 个。当前")
            append(stateLabel)
            append("，")
            append(actionLabel)
            append("。")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = stateLabel
        }
    }

    private fun buildHeaderMeta(): String {
        val tool = currentState.toolName?.takeIf { it.isNotBlank() }
        return if (tool == null) {
            currentState.stage.label
        } else {
            "${currentState.stage.label} · $tool"
        }
    }

    private fun buildCollapsedSummary(): String {
        return when {
            currentState.resultSummary?.isNotBlank() == true -> "展开查看完整结果"
            currentState.steps.isNotEmpty() -> "${currentState.stage.label} · ${currentState.steps.size} 个步骤"
            else -> currentState.stage.label
        }
    }

    private fun renderSteps() {
        stepsContainer.removeAllViews()
        currentState.steps.forEachIndexed { index, step ->
            if (index > 0) {
                stepsContainer.addView(View(context), LayoutParams(
                    context.chatDp(1),
                    context.chatDp(12)
                ).apply {
                    marginStart = context.chatDp(14)
                })
            }
            stepsContainer.addView(buildStepRow(step))
        }
    }

    private fun buildStepRow(step: DemoChatProcessStep): View {
        val detailParts = listOfNotNull(
            step.detail?.toString()?.takeIf { it.isNotBlank() },
            step.resultSummary?.toString()?.takeIf { it.isNotBlank() }
        )
        val hasDetails = detailParts.isNotEmpty()
        val isStepExpanded = expandedStepIds.contains(step.id)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            minimumHeight = context.chatDp(44)
            isClickable = hasDetails
            isFocusable = hasDetails
            importantForAccessibility = if (hasDetails) {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val indicator = TextView(context).apply {
            text = stepIndicator(step.status)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(stepIndicatorTextColor(step.status))
            background = roundedBackground(
                context,
                stepIndicatorFill(step.status),
                stepIndicatorStroke(step.status),
                12
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(indicator, LayoutParams(context.chatDp(24), context.chatDp(24)).apply {
            marginEnd = context.chatDp(10)
        })

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.TOP
        }
        val title = TextView(context).apply {
            text = step.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(DemoChatPalette.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.12f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        textColumn.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (isStepExpanded) {
            val fullDetail = detailParts.joinToString("\n\n")
            textColumn.addView(TextView(context).apply {
                text = fullDetail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(DemoChatPalette.textSecondary)
                setLineSpacing(0f, 1.18f)
                setPadding(0, context.chatDp(3), 0, 0)
                setTextIsSelectable(true)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            val compactDetail = detailParts.firstOrNull() ?: if (hasDetails) {
                "点击展开查看完整结果"
            } else {
                null
            }
            if (compactDetail != null) {
                textColumn.addView(TextView(context).apply {
                    text = compactDetail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(DemoChatPalette.textSecondary)
                    setLineSpacing(0f, 1.12f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, context.chatDp(3), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        row.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val disclosure = TextView(context).apply {
            text = if (isStepExpanded) "收起" else "展开"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(DemoChatPalette.mintDark)
            gravity = Gravity.CENTER
            maxLines = 1
            visibility = if (hasDetails) View.VISIBLE else View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(disclosure, LayoutParams(LayoutParams.WRAP_CONTENT, context.chatDp(32)).apply {
            marginStart = context.chatDp(8)
        })

        if (hasDetails) {
            row.contentDescription = buildString {
                append(step.title)
                append("，")
                append(stepStatusLabel(step.status))
                append("，当前")
                append(if (isStepExpanded) "已展开，点击收起" else "已收起，点击展开")
            }
            row.setOnClickListener {
                val next = !expandedStepIds.contains(step.id)
                if (next) expandedStepIds.add(step.id) else expandedStepIds.remove(step.id)
                renderSteps()
                stepExpandedChangeListener?.invoke(step.id, next)
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        }
        return row
    }

    private fun stepStatusLabel(status: DemoChatProcessStepStatus): String = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> "已完成"
        DemoChatProcessStepStatus.ACTIVE -> "进行中"
        DemoChatProcessStepStatus.WAITING -> "等待确认"
        DemoChatProcessStepStatus.ERROR -> "执行失败"
        DemoChatProcessStepStatus.PENDING -> "待处理"
    }

    private fun stepIndicator(status: DemoChatProcessStepStatus): String = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> "✓"
        DemoChatProcessStepStatus.ACTIVE -> "•"
        DemoChatProcessStepStatus.WAITING -> "!"
        DemoChatProcessStepStatus.ERROR -> "×"
        DemoChatProcessStepStatus.PENDING -> "○"
    }

    private fun stepIndicatorTextColor(status: DemoChatProcessStepStatus): Int = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> DemoChatPalette.surface
        DemoChatProcessStepStatus.ACTIVE -> DemoChatPalette.mintDark
        DemoChatProcessStepStatus.WAITING -> DemoChatPalette.amber
        DemoChatProcessStepStatus.ERROR -> DemoChatPalette.danger
        DemoChatProcessStepStatus.PENDING -> DemoChatPalette.textMuted
    }

    private fun stepIndicatorFill(status: DemoChatProcessStepStatus): Int = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> DemoChatPalette.mintDark
        DemoChatProcessStepStatus.ACTIVE -> DemoChatPalette.mintSoft
        DemoChatProcessStepStatus.WAITING -> DemoChatPalette.amberSoft
        DemoChatProcessStepStatus.ERROR -> DemoChatPalette.dangerSoft
        DemoChatProcessStepStatus.PENDING -> DemoChatPalette.surface
    }

    private fun stepIndicatorStroke(status: DemoChatProcessStepStatus): Int = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> DemoChatPalette.mintDark
        DemoChatProcessStepStatus.ACTIVE -> DemoChatPalette.mintDark
        DemoChatProcessStepStatus.WAITING -> DemoChatPalette.amber
        DemoChatProcessStepStatus.ERROR -> DemoChatPalette.danger
        DemoChatProcessStepStatus.PENDING -> DemoChatPalette.outline
    }
}

private object DemoChatPalette {
    val surface = Color.rgb(248, 250, 247)
    val assistantBubble = Color.rgb(255, 255, 255)
    val assistantStroke = Color.rgb(229, 226, 221)
    val userBubble = Color.rgb(211, 244, 228)
    val userStroke = Color.rgb(184, 228, 207)
    val cardSurface = Color.rgb(255, 255, 255)
    val cardPressed = Color.rgb(241, 252, 247)
    val cardStroke = Color.rgb(222, 231, 225)
    val mintDark = Color.rgb(17, 126, 92)
    val mintSoft = Color.rgb(231, 248, 240)
    val mintStroke = Color.rgb(184, 228, 207)
    val amber = Color.rgb(153, 105, 25)
    val amberSoft = Color.rgb(252, 244, 224)
    val danger = Color.rgb(190, 45, 45)
    val dangerSoft = Color.rgb(253, 235, 235)
    val outline = Color.rgb(222, 231, 225)
    val textMuted = Color.rgb(135, 146, 143)
    val textPrimary = Color.rgb(28, 35, 33)
    val textSecondary = Color.rgb(85, 108, 100)
}

private fun Context.chatDp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

private fun roundedBackground(
    context: Context,
    fillColor: Int,
    strokeColor: Int,
    radiusDp: Int
): Drawable = GradientDrawable().apply {
    setColor(fillColor)
    cornerRadius = context.chatDp(radiusDp).toFloat()
    setStroke(context.chatDp(1), strokeColor)
}

private fun pressedCardBackground(context: Context): Drawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_pressed),
        roundedBackground(
            context = context,
            fillColor = DemoChatPalette.cardPressed,
            strokeColor = DemoChatPalette.cardStroke,
            radiusDp = 14
        )
    )
    addState(
        intArrayOf(),
        roundedBackground(
            context = context,
            fillColor = DemoChatPalette.cardSurface,
            strokeColor = DemoChatPalette.cardStroke,
            radiusDp = 14
        )
    )
}
