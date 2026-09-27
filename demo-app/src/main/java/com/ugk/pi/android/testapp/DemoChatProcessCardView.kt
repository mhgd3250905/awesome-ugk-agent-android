package com.ugk.pi.android.testapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A quiet activity row with an optional, independently expandable process record. */
class DemoChatProcessCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val style: DemoChatStyle = DemoChatStyle.STANDARD
) : LinearLayout(context, attrs) {
    private data class StepRowHolder(
        val rowView: LinearLayout,
        val toggleView: View,
        val indicatorView: ImageView,
        val titleView: TextView,
        val compactDetailView: TextView,
        val detailScrollView: StepDetailScrollView,
        val detailTextView: TextView,
        val disclosureView: ImageView,
        var isExpanded: Boolean
    )

    private val header = LinearLayout(context)
    private val indicator = DemoProcessIndicatorView(context)
    private val summaryView = TextView(context)
    private val expansionView = ImageView(context)
    private val stepsContainer = LinearLayout(context)
    private val collapseFooterView = TextView(context)
    private val stepHolders = linkedMapOf<String, StepRowHolder>()
    private val expandedStepIds = linkedSetOf<String>()
    private var currentState = DemoChatProcessState(DemoChatProcessStage.THINKING)
    private var expanded = false
    private var expandedChangeListener: ((Boolean) -> Unit)? = null
    private var stepExpandedChangeListener: ((String, Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        // The conversation canvas remains visible in both collapsed and expanded states.
        setPadding(context.dp(style.size(12, 8)), 0, context.dp(style.size(12, 8)), 0)
        minimumHeight = context.dp(48)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { setExpandedInternal(!expanded, notifyListener = true) }
        isFocusable = false

        header.apply {
            tag = "process-header"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            background = quietClickBackground()
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { setExpandedInternal(!expanded, notifyListener = true) }
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        if (expanded) "收起过程" else "展开过程"
                    ))
                }
            }
        }
        header.addView(indicator, LayoutParams(context.dp(style.size(24, 20)), context.dp(style.size(24, 20))).apply {
            marginEnd = context.dp(style.size(10, 8))
        })
        summaryView.apply {
            tag = "process-summary"
            textSize = style.size(15f, 13f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(Ui.TextSecondary)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(summaryView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        expansionView.apply {
            setImageResource(R.drawable.ic_process_expand_more)
            imageTintList = ColorStateList.valueOf(Ui.TextSecondary)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(expansionView, LayoutParams(context.dp(18), context.dp(18)).apply {
            marginStart = context.dp(8)
        })
        addView(header, fullWidth())
        stepsContainer.apply {
            orientation = VERTICAL
            setPadding(context.dp(4), context.dp(2), 0, 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(stepsContainer, fullWidth())
        collapseFooterView.apply {
            text = "收起过程"
            textSize = style.size(12f, 11f)
            setTextColor(Ui.TextSecondary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(style.size(34, 28)), 0, 0, 0)
            background = quietClickBackground()
            isFocusable = true
            contentDescription = "收起整个过程"
            setOnClickListener { setExpanded(false) }
        }
        addView(collapseFooterView, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(48)))
        bind(currentState)
    }

    fun bind(state: DemoChatProcessState) {
        currentState = state
        expandedStepIds.retainAll(state.steps.map { it.id }.toSet())
        val summary = state.summary?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: defaultSummary()
        if (summaryView.text.toString() != summary) summaryView.text = summary
        indicator.bind(state.stage, state.isRunning)
        setExpandedInternal(state.expanded, notifyListener = false)
    }

    fun update(
        stage: DemoChatProcessStage,
        toolName: CharSequence? = null,
        resultSummary: CharSequence? = null,
        steps: List<DemoChatProcessStep> = emptyList(),
        footerLeft: CharSequence? = null,
        footerRight: CharSequence? = null,
        expanded: Boolean = this.expanded
    ) = bind(DemoChatProcessState(stage, toolName, resultSummary, steps, footerLeft, footerRight, expanded))

    fun setExpanded(expanded: Boolean) = setExpandedInternal(expanded, notifyListener = true)
    fun isExpanded(): Boolean = expanded
    fun setOnExpandedChangeListener(listener: ((Boolean) -> Unit)?) { expandedChangeListener = listener }
    fun setOnStepExpandedChangeListener(listener: ((String, Boolean) -> Unit)?) { stepExpandedChangeListener = listener }

    private fun setExpandedInternal(value: Boolean, notifyListener: Boolean) {
        val changed = expanded != value
        expanded = value
        currentState = currentState.copy(expanded = value)
        if (!value) {
            expandedStepIds.clear()
            // Do not rebuild hidden steps on every streaming token.
            if (changed) {
                stepsContainer.removeAllViews()
                stepHolders.clear()
            }
        } else {
            renderSteps()
        }
        expansionView.rotation = if (value) 180f else 0f
        stepsContainer.visibility = if (value && currentState.steps.isNotEmpty()) VISIBLE else GONE
        collapseFooterView.visibility = stepsContainer.visibility
        val description = "过程记录，${summaryView.text}，${currentState.steps.size} 个步骤，" +
            if (value) "已展开，点击收起" else "已收起，点击展开"
        if (header.contentDescription != description) header.contentDescription = description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            header.stateDescription = if (value) "已展开" else "已收起"
        }
        if (notifyListener && changed) {
            expandedChangeListener?.invoke(value)
            header.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun defaultSummary(): String = when (currentState.stage) {
        DemoChatProcessStage.THINKING -> "正在思考你的请求"
        DemoChatProcessStage.TOOL_CALL -> "正在调用工具"
        DemoChatProcessStage.WAITING_CONFIRMATION -> "等待你的确认"
        DemoChatProcessStage.RESULT -> "正在整理工具结果"
        DemoChatProcessStage.COMPLETED -> "已完成 · ${currentState.steps.size} 个步骤"
        DemoChatProcessStage.ERROR -> if (currentState.isRunning) "工具遇到问题，正在继续处理" else "执行遇到问题 · 查看过程"
        DemoChatProcessStage.STOPPED -> "已停止 · 查看过程"
    }

    private fun renderSteps() {
        if (currentState.steps.map { it.id } != stepHolders.keys.toList()) {
            val previousHolders = stepHolders.toMap()
            stepsContainer.removeAllViews()
            stepHolders.clear()
            currentState.steps.forEachIndexed { index, step ->
                if (index > 0) {
                    stepsContainer.addView(View(context).apply {
                        setBackgroundColor(Ui.OutlineSubtle)
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LayoutParams(context.dp(1).coerceAtLeast(1), context.dp(style.size(10, 4))).apply {
                        marginStart = context.dp(8)
                    })
                }
                val holder = previousHolders[step.id] ?: buildStepRow(step.id)
                stepHolders[step.id] = holder
                stepsContainer.addView(holder.rowView, fullWidth())
            }
        }
        currentState.steps.forEach { step ->
            val holder = stepHolders.getValue(step.id)
            val detailParts = listOfNotNull(
                step.detail?.toString()?.takeIf { it.isNotBlank() },
                step.resultSummary?.toString()?.takeIf { it.isNotBlank() }
            )
            val hasDetails = detailParts.isNotEmpty()
            val showDetail = hasDetails && expandedStepIds.contains(step.id)
            val wasExpanded = holder.isExpanded
            holder.isExpanded = showDetail
            holder.titleView.text = step.title.toString().removePrefix("[动作] ")
            holder.compactDetailView.text = detailParts.firstOrNull().orEmpty()
            holder.compactDetailView.visibility = if (hasDetails && !showDetail) VISIBLE else GONE
            holder.disclosureView.visibility = if (hasDetails) VISIBLE else GONE
            holder.disclosureView.rotation = if (showDetail) 180f else 0f
            holder.detailScrollView.visibility = if (showDetail) VISIBLE else GONE
            if (showDetail) {
                val text = detailParts.joinToString("\n\n")
                if (holder.detailTextView.text.toString() != text) {
                    // Follow a stream only if the reader was already at its end.
                    val followEnd = !wasExpanded || !holder.detailScrollView.canScrollVertically(1)
                    holder.detailTextView.text = text
                    if (followEnd) holder.detailScrollView.scrollToBottom()
                }
            } else {
                holder.detailTextView.text = ""
            }
            holder.toggleView.isClickable = hasDetails
            holder.toggleView.contentDescription = "${step.title}，${stepStatusLabel(step.status)}，" +
                if (hasDetails) {
                    if (showDetail) "已展开，点击收起详情" else "已收起，点击展开详情"
                } else "暂无详情"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                holder.toggleView.stateDescription = if (showDetail) "已展开" else "已收起"
            }
            holder.indicatorView.setImageResource(when (step.status) {
                DemoChatProcessStepStatus.COMPLETE -> R.drawable.ic_process_check
                DemoChatProcessStepStatus.ERROR -> R.drawable.ic_process_error_outline
                DemoChatProcessStepStatus.WAITING, DemoChatProcessStepStatus.STOPPED -> R.drawable.ic_process_pause
                else -> R.drawable.ic_process_fiber_manual_record
            })
            holder.indicatorView.imageTintList = ColorStateList.valueOf(when (step.status) {
                DemoChatProcessStepStatus.ERROR -> Ui.Danger
                DemoChatProcessStepStatus.WAITING -> Ui.Warning
                else -> Ui.TextSecondary
            })
            val dotPadding = if (step.status == DemoChatProcessStepStatus.ACTIVE ||
                step.status == DemoChatProcessStepStatus.PENDING) context.dp(4) else 0
            holder.indicatorView.setPadding(dotPadding, dotPadding, dotPadding, dotPadding)
        }
    }

    private fun buildStepRow(id: String): StepRowHolder {
        val row = LinearLayout(context).apply { orientation = VERTICAL }
        val toggle = LinearLayout(context).apply {
            tag = "process-step:$id"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(48)
            background = quietClickBackground()
            isFocusable = true
            setOnClickListener {
                val next = !expandedStepIds.contains(id)
                if (next) expandedStepIds.add(id) else expandedStepIds.remove(id)
                renderSteps()
                stepExpandedChangeListener?.invoke(id, next)
                sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        }
        val stepIndicator = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        toggle.addView(stepIndicator, LayoutParams(context.dp(16), context.dp(16)).apply { marginEnd = context.dp(style.size(14, 8)) })
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, context.dp(style.size(5, 3)), 0, context.dp(style.size(5, 3)))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = TextView(context).apply {
            textSize = style.size(13f, 12f)
            setTextColor(Ui.TextSecondary)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(0f, 1.1f)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val compact = TextView(context).apply {
            textSize = style.size(12f, 11f)
            setTextColor(Ui.TextSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        texts.addView(title, fullWidth())
        texts.addView(compact, fullWidth())
        toggle.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val disclosure = ImageView(context).apply {
            setImageResource(R.drawable.ic_process_expand_more)
            imageTintList = ColorStateList.valueOf(Ui.TextSecondary)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        toggle.addView(disclosure, LayoutParams(context.dp(16), context.dp(16)).apply { marginStart = context.dp(8) })
        row.addView(toggle, fullWidth())
        val scroll = StepDetailScrollView(context).apply {
            tag = "process-detail:$id"
            if (style == DemoChatStyle.COMPACT) maxHeightPx = context.dp(144)
            background = Ui.rounded(context, Ui.SurfaceSoft, 8)
        }
        val detail = TextView(context).apply {
            textSize = style.size(12.5f, 12f)
            setTextColor(Ui.TextSecondary)
            setLineSpacing(0f, style.size(1.2f, 1.1f))
            setPadding(context.dp(style.size(12, 8)), context.dp(style.size(10, 6)), context.dp(style.size(12, 8)), context.dp(style.size(10, 6)))
            setTextIsSelectable(true)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        scroll.addView(detail, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val detailHeight = if (style == DemoChatStyle.COMPACT) LayoutParams.WRAP_CONTENT else context.dp(170)
        row.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, detailHeight).apply {
            marginStart = context.dp(style.size(30, 24))
            topMargin = context.dp(4)
            bottomMargin = context.dp(style.size(6, 3))
        })
        return StepRowHolder(row, toggle, stepIndicator, title, compact, scroll, detail, disclosure, false)
    }

    private fun stepStatusLabel(status: DemoChatProcessStepStatus): String = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> "已完成"
        DemoChatProcessStepStatus.ACTIVE -> "进行中"
        DemoChatProcessStepStatus.WAITING -> "等待确认"
        DemoChatProcessStepStatus.ERROR -> "执行失败"
        DemoChatProcessStepStatus.STOPPED -> "已停止"
        DemoChatProcessStepStatus.PENDING -> "待处理"
    }

    private fun fullWidth() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun quietClickBackground() = Ui.stateListDrawable(
        normal = Ui.rounded(context, Color.TRANSPARENT, 8),
        pressed = Ui.rounded(context, Ui.SurfaceSoft, 8),
        focused = Ui.rounded(context, Color.TRANSPARENT, 8, Ui.FocusRing)
    )
}
