package com.ugk.pi.android.testapp

import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private val accentColorProvider: () -> Int
) {
    THINKING("思考中", { Ui.PrimaryPressed }),
    TOOL_CALL("调用工具", { Ui.PrimaryPressed }),
    WAITING_CONFIRMATION("等待确认", { Ui.Warning }),
    RESULT("收到结果", { Ui.PrimaryPressed }),
    COMPLETED("已完成", { Ui.Success }),
    ERROR("执行失败", { Ui.Danger });

    val accentColor: Int get() = accentColorProvider()
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

    private val userBubble = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(DemoChatPalette.onUserBubble)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.18f)
        letterSpacing = 0.012f
        includeFontPadding = false
        minHeight = context.chatDp(40)
        setPadding(
            context.chatDp(16),
            context.chatDp(11),
            context.chatDp(16),
            context.chatDp(11)
        )
        background = asymmetricRoundedBackground(
            context = context,
            fillColor = DemoChatPalette.userBubble,
            strokeColor = DemoChatPalette.userStroke,
            topLeftDp = 18,
            topRightDp = 18,
            bottomRightDp = 4,
            bottomLeftDp = 18
        )
        setTextIsSelectable(true)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val userImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.chatDp(14).toFloat())
            }
        }
        background = asymmetricRoundedBackground(
            context = context,
            fillColor = DemoChatPalette.userAvatarSurface,
            strokeColor = DemoChatPalette.userStroke,
            topLeftDp = 18,
            topRightDp = 4,
            bottomRightDp = 18,
            bottomLeftDp = 18
        )
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** A quiet, neutral user identity marker keeps message ownership visible. */
    private val userAvatar = ImageView(context).apply {
        setImageResource(R.drawable.ic_person)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.chatDp(7), context.chatDp(7), context.chatDp(7), context.chatDp(7))
        imageTintList = android.content.res.ColorStateList.valueOf(DemoChatPalette.onUserAvatar)
        background = roundedBackground(
            context,
            DemoChatPalette.userAvatarSurface,
            0,
            9
        )
        contentDescription = "用户头像"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val assistantAvatar = ImageView(context).apply {
        setImageResource(R.drawable.brand_owl_avatar)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.chatDp(2), context.chatDp(2), context.chatDp(2), context.chatDp(2))
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, context.chatDp(9).toFloat())
            }
        }
        background = roundedBackground(
            context,
            DemoChatPalette.assistantAvatarSurface,
            0,
            15
        )
        contentDescription = "助手头像"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val assistantBubble = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = context.chatDp(40)
        setPadding(
            context.chatDp(16),
            context.chatDp(12),
            context.chatDp(16),
            context.chatDp(12)
        )
        background = asymmetricRoundedBackground(
            context = context,
            fillColor = DemoChatPalette.assistantBubble,
            strokeColor = DemoChatPalette.assistantStroke,
            topLeftDp = 4,
            topRightDp = 18,
            bottomRightDp = 18,
            bottomLeftDp = 18
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun createAssistantTextView(): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(DemoChatPalette.textPrimary)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.16f)
        letterSpacing = 0.012f
        includeFontPadding = false
        setTextIsSelectable(true)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var role: DemoChatMessageRole = DemoChatMessageRole.ASSISTANT
    private var messageText: String = ""

    private val userCopyButton = createCopyButton()
    private val userContentColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        addView(userImageView, LinearLayout.LayoutParams(
            context.chatDp(190),
            context.chatDp(190)
        ).apply {
            bottomMargin = context.chatDp(6)
        })
        addView(userBubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        addView(userCopyButton, copyButtonLayoutParams(Gravity.END))
    }

    private val userContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP or Gravity.END
        addView(userContentColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(userAvatar, LinearLayout.LayoutParams(context.chatDp(32), context.chatDp(32)).apply {
            marginStart = context.chatDp(8)
            topMargin = context.chatDp(2)
        })
    }

    private val assistantCopyButton = createCopyButton()
    private val assistantContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        val avatarParams = LinearLayout.LayoutParams(context.chatDp(30), context.chatDp(30)).apply {
            topMargin = context.chatDp(2)
            marginEnd = context.chatDp(8)
        }
        addView(assistantAvatar, avatarParams)

        val rightColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(assistantBubble, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(assistantCopyButton, copyButtonLayoutParams(Gravity.START))
        }
        addView(rightColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.86f))
    }

    init {
        clipChildren = false
        clipToPadding = false
        setPadding(
            context.chatDp(12),
            context.chatDp(6),
            context.chatDp(12),
            context.chatDp(6)
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            userContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
        )
        addView(
            assistantContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
            }
        )
        bind(role, messageText)
    }

    /** 绑定角色和消息正文，可选携带用户图片路径；不会触发任何业务回调。 */
    @JvmOverloads
    fun bind(role: DemoChatMessageRole, text: CharSequence, imagePath: String? = null) {
        this.role = role
        messageText = text.toString()
        if (role == DemoChatMessageRole.USER) {
            userContainer.visibility = View.VISIBLE
            assistantContainer.visibility = View.GONE

            if (!imagePath.isNullOrBlank() && java.io.File(imagePath).exists()) {
                val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(imagePath) }.getOrNull()
                if (bitmap != null) {
                    userImageView.setImageBitmap(bitmap)
                    userImageView.visibility = View.VISIBLE
                    userImageView.isClickable = true
                    userImageView.setOnClickListener {
                        showFullImageDialog(context, imagePath)
                    }
                } else {
                    userImageView.visibility = View.GONE
                }
            } else {
                userImageView.visibility = View.GONE
            }

            if (messageText.isNotBlank()) {
                userBubble.visibility = View.VISIBLE
                userBubble.setTextColor(DemoChatPalette.onUserBubble)
                userBubble.background = asymmetricRoundedBackground(
                    context = context,
                    fillColor = DemoChatPalette.userBubble,
                    strokeColor = DemoChatPalette.userStroke,
                    topLeftDp = 18,
                    topRightDp = 4,
                    bottomRightDp = 18,
                    bottomLeftDp = 18
                )
                userBubble.text = messageText
            } else {
                userBubble.visibility = View.GONE
            }
            userCopyButton.background = copyButtonBackground(context)
            userCopyButton.setTextColor(DemoChatPalette.textSecondary)
        } else {
            userContainer.visibility = View.GONE
            assistantContainer.visibility = View.VISIBLE
            assistantBubble.background = asymmetricRoundedBackground(
                context = context,
                fillColor = DemoChatPalette.assistantBubble,
                strokeColor = DemoChatPalette.assistantStroke,
                topLeftDp = 4,
                topRightDp = 18,
                bottomRightDp = 18,
                bottomLeftDp = 18
            )
            assistantCopyButton.background = copyButtonBackground(context)
            assistantCopyButton.setTextColor(DemoChatPalette.textSecondary)
            renderAssistantContent(messageText)
        }
        contentDescription = buildString {
            append(role.accessibilityLabel)
            append("：")
            append(messageText.ifBlank { "空消息" })
        }
    }

    /** 只更新消息正文，保留当前角色。 */
    fun updateText(text: CharSequence) {
        bind(role, text)
    }

    /**
     * 流式吐字过程中更新文本，使用复合分块渲染支持正文 Markdown 与横向滑动代码块。
     */
    fun updateStreamingText(text: CharSequence) {
        messageText = text.toString()
        if (role == DemoChatMessageRole.ASSISTANT) {
            assistantContainer.visibility = View.VISIBLE
            userContainer.visibility = View.GONE
            renderAssistantContent(messageText, isStreaming = true)
        } else {
            userContainer.visibility = View.VISIBLE
            assistantContainer.visibility = View.GONE
            userBubble.text = messageText
            userBubble.setTextColor(DemoChatPalette.onUserBubble)
        }
    }

    private fun renderAssistantContent(text: String, isStreaming: Boolean = false) {
        val blocks = DemoCodeBlockParser.splitBlocks(text)
        if (blocks.isEmpty()) {
            assistantBubble.removeAllViews()
            return
        }

        // 快速路径：单文本块（无代码块）
        if (blocks.size == 1 && blocks[0] is DemoContentBlock.Text) {
            val textContent = (blocks[0] as DemoContentBlock.Text).markdown
            val tv = if (assistantBubble.childCount == 1 && assistantBubble.getChildAt(0) is TextView) {
                (assistantBubble.getChildAt(0) as TextView).apply {
                    setTextColor(DemoChatPalette.textPrimary)
                }
            } else {
                assistantBubble.removeAllViews()
                createAssistantTextView().also { assistantBubble.addView(it) }
            }
            DemoMarkdownFormatter.setMarkdown(tv, textContent, isStreaming = isStreaming)
            return
        }

        // 复合块模式：包含代码块
        var childIndex = 0
        for (block in blocks) {
            when (block) {
                is DemoContentBlock.Text -> {
                    val existing = assistantBubble.getChildAt(childIndex)
                    val tv = if (existing is TextView) {
                        existing.apply { setTextColor(DemoChatPalette.textPrimary) }
                    } else {
                        val newTv = createAssistantTextView()
                        if (childIndex < assistantBubble.childCount) {
                            assistantBubble.removeViewAt(childIndex)
                        }
                        assistantBubble.addView(newTv, childIndex)
                        newTv
                    }
                    DemoMarkdownFormatter.setMarkdown(tv, block.markdown, isStreaming = isStreaming)
                    childIndex++
                }
                is DemoContentBlock.Code -> {
                    val existing = assistantBubble.getChildAt(childIndex) as? DemoCodeBlockView
                    val codeView = existing ?: DemoCodeBlockView(context).apply {
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = context.chatDp(6)
                            bottomMargin = context.chatDp(6)
                        }
                        layoutParams = lp
                    }
                    codeView.bind(block.language, block.code)
                    if (existing == null) {
                        if (childIndex < assistantBubble.childCount) {
                            assistantBubble.removeViewAt(childIndex)
                        }
                        assistantBubble.addView(codeView, childIndex)
                    }
                    childIndex++
                }
                is DemoContentBlock.Table -> {
                    val existing = assistantBubble.getChildAt(childIndex) as? DemoTableView
                    val tableView = existing ?: DemoTableView(context).apply {
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = context.chatDp(6)
                            bottomMargin = context.chatDp(6)
                        }
                        layoutParams = lp
                    }
                    tableView.bind(block.tableMarkdown)
                    if (existing == null) {
                        if (childIndex < assistantBubble.childCount) {
                            assistantBubble.removeViewAt(childIndex)
                        }
                        assistantBubble.addView(tableView, childIndex)
                    }
                    childIndex++
                }
            }
        }

        // 清理末尾多余视图
        while (assistantBubble.childCount > childIndex) {
            assistantBubble.removeViewAt(assistantBubble.childCount - 1)
        }
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
        val maxBubbleWidth = if (availableWidth > 0) {
            (availableWidth * MAX_BUBBLE_WIDTH_FRACTION).roundToInt()
        } else {
            context.chatDp(DEFAULT_BUBBLE_MAX_WIDTH_DP)
        }
        userBubble.maxWidth = maxBubbleWidth
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun createCopyButton(): TextView = TextView(context).apply {
        text = "复制"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(DemoChatPalette.textSecondary)
        gravity = Gravity.CENTER
        minWidth = context.chatDp(52)
        minHeight = context.chatDp(28)
        setPadding(context.chatDp(10), 0, context.chatDp(10), 0)
        background = copyButtonBackground(context)
        isClickable = true
        isFocusable = true
        contentDescription = "复制这条消息"
        setOnClickListener { copyVisibleMessageToClipboard() }
    }

    private fun copyButtonLayoutParams(gravity: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.gravity = gravity
            topMargin = context.chatDp(4)
        }

    private fun copyVisibleMessageToClipboard() {
        val text = messageText
        if (text.isBlank()) return

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("UGK Agent", text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val DEFAULT_BUBBLE_MAX_WIDTH_DP = 320
        const val MAX_BUBBLE_WIDTH_FRACTION = 0.80f
    }
}

/**
 * Agent 过程卡片。
 *
 * 卡片默认收起，外层卡片和每个过程步骤拥有独立的展开状态。标题行始终展示阶段、
 * 工具名（如有）和外层展开状态；步骤默认只展示状态和一行摘要，详情由步骤自己的
 * 点击目标按需展开。
 */
/**
 * 步骤展开详情专用的固定高度内嵌滚动容器。
 *
 * 采用固定高度确保大段思考在流式增长时外部页面零抖动；
 * 显式禁用原生系统滚动条，从根本上消除流式刷新时滚动条因滑块重算而引发的上下跳动与闪烁。
 */
class StepDetailScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
    init {
        isNestedScrollingEnabled = true
        isVerticalScrollBarEnabled = false      // 彻底禁用原生滚动条，消除高频追加文本时的滑块闪烁与跳动
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER // 禁用边缘拉伸泛光
    }

    /**
     * 极简平滑沉底：直接计算目标底部 offset，避免 fullScroll() 触发的焦点抢占与平滑插值跳跃。
     */
    fun scrollToBottom() {
        post {
            val child = getChildAt(0) ?: return@post
            val targetY = child.bottom - (height - paddingBottom)
            if (targetY > 0) {
                scrollTo(0, targetY)
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (canScrollVertically(1) || canScrollVertically(-1)) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
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

    private class StepRowHolder(
        val rowView: View,
        val indicatorView: TextView,
        val titleView: TextView,
        val compactDetailView: TextView?,
        val detailScrollView: StepDetailScrollView?,
        val detailTextView: TextView?,
        val disclosureView: TextView,
        val isExpanded: Boolean
    )

    private val stepHolders = mutableMapOf<String, StepRowHolder>()

    private val header = LinearLayout(context)
    private val headerIcon = ImageView(context)
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
        background = processCardBackground(context)

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
            LayoutParams(context.chatDp(32), context.chatDp(32)).apply {
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
            setImageResource(R.drawable.brand_owl_avatar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(context.chatDp(2), context.chatDp(2), context.chatDp(2), context.chatDp(2))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, context.chatDp(8).toFloat())
                }
            }
            background = roundedBackground(
                context,
                DemoChatPalette.assistantAvatarSurface,
                0,
                14
            )
            contentDescription = "绿色猫头鹰助手"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureHeaderTitle() {
        headerTitle.apply {
            text = "Agent 过程"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(DemoChatPalette.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.012f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureHeaderMeta() {
        headerMeta.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(DemoChatPalette.textSecondary)
            letterSpacing = 0.01f
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureExpansionView() {
        expansionView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(DemoChatPalette.textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.015f
            maxLines = 1
            setPadding(context.chatDp(9), context.chatDp(3), context.chatDp(9), context.chatDp(3))
            background = roundedBackground(
                context,
                DemoChatPalette.surfaceSoft,
                DemoChatPalette.outlineSubtle,
                10
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureCollapsedSummary() {
        collapsedSummaryView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(DemoChatPalette.textSecondary)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.01f
            maxLines = 1
            setPadding(0, context.chatDp(6), 0, context.chatDp(2))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun configureStepsContainer() {
        stepsContainer.orientation = VERTICAL
        stepsContainer.setPadding(0, context.chatDp(6), 0, context.chatDp(2))
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
            setTextColor(DemoChatPalette.textSecondary)
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
        val currentIds = currentState.steps.map { it.id }
        val existingIds = stepHolders.keys.toList()

        // 检查能否进行轻量原位复用：步骤 ID 集合一致且各步骤展开状态保持不变
        val canReuse = currentIds == existingIds && currentState.steps.all { step ->
            val isExpanded = expandedStepIds.contains(step.id)
            stepHolders[step.id]?.isExpanded == isExpanded
        }

        if (canReuse) {
            currentState.steps.forEach { step ->
                val holder = stepHolders[step.id] ?: return@forEach
                holder.titleView.text = step.title
                holder.indicatorView.text = stepIndicator(step.status)
                holder.indicatorView.setTextColor(stepIndicatorTextColor(step.status))
                holder.indicatorView.background = roundedBackground(
                    context,
                    stepIndicatorFill(step.status),
                    stepIndicatorStroke(step.status),
                    12
                )

                val detailParts = listOfNotNull(
                    step.detail?.toString()?.takeIf { it.isNotBlank() },
                    step.resultSummary?.toString()?.takeIf { it.isNotBlank() }
                )
                val hasDetails = detailParts.isNotEmpty()
                holder.disclosureView.visibility = if (hasDetails) View.VISIBLE else View.GONE

                if (holder.isExpanded) {
                    val fullDetail = detailParts.joinToString("\n\n")
                    if (holder.detailTextView?.text?.toString() != fullDetail) {
                        holder.detailTextView?.text = fullDetail
                        // 内容流式更新时自动向下方滚动，最新思考保持可见
                        holder.detailScrollView?.scrollToBottom()
                    }
                } else {
                    val compactDetail = detailParts.firstOrNull() ?: if (hasDetails) {
                        "点击展开查看完整结果"
                    } else {
                        null
                    }
                    holder.compactDetailView?.text = compactDetail ?: ""
                }
            }
            return
        }

        // 结构变动或展开状态变化时重建
        stepsContainer.removeAllViews()
        stepHolders.clear()
        currentState.steps.forEachIndexed { index, step ->
            if (index > 0) {
                stepsContainer.addView(View(context).apply {
                    setBackgroundColor(DemoChatPalette.divider)
                }, LayoutParams(
                    context.chatDp(1),
                    context.chatDp(10)
                ).apply {
                    marginStart = context.chatDp(12)
                })
            }
            val holder = buildStepRow(step)
            stepHolders[step.id] = holder
            stepsContainer.addView(holder.rowView)
        }
    }

    private fun buildStepRow(step: DemoChatProcessStep): StepRowHolder {
        val detailParts = listOfNotNull(
            step.detail?.toString()?.takeIf { it.isNotBlank() },
            step.resultSummary?.toString()?.takeIf { it.isNotBlank() }
        )
        val hasDetails = detailParts.isNotEmpty()
        val isStepExpanded = expandedStepIds.contains(step.id)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            minimumHeight = context.chatDp(40)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(DemoChatPalette.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        textColumn.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        var compactDetailView: TextView? = null
        var detailScrollView: StepDetailScrollView? = null
        var detailTextView: TextView? = null

        if (isStepExpanded) {
            val fullDetail = detailParts.joinToString("\n\n")
            val scrollView = StepDetailScrollView(context).apply {
                background = roundedBackground(
                    context,
                    DemoChatPalette.surfaceSubtle,
                    DemoChatPalette.outlineSubtle,
                    8
                )
            }
            val detailTv = TextView(context).apply {
                text = fullDetail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(DemoChatPalette.textSecondary)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setLineSpacing(0f, 1.22f)
                letterSpacing = 0.01f
                setPadding(context.chatDp(12), context.chatDp(9), context.chatDp(12), context.chatDp(9))
                setTextIsSelectable(true)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            scrollView.addView(
                detailTv,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )

            // 固定高度 170dp：锁定详情高度，避免大段思考将卡片撑满，彻底杜绝外部页面重排抖动
            val fixedHeight = context.chatDp(170)
            textColumn.addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, fixedHeight).apply {
                topMargin = context.chatDp(6)
            })
            scrollView.scrollToBottom()
            detailScrollView = scrollView
            detailTextView = detailTv
        } else {
            val compactDetail = detailParts.firstOrNull() ?: if (hasDetails) {
                "点击展开查看完整结果"
            } else {
                null
            }
            if (compactDetail != null) {
                val tv = TextView(context).apply {
                    text = compactDetail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(DemoChatPalette.textSecondary)
                    setLineSpacing(0f, 1.15f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, context.chatDp(2), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                textColumn.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                compactDetailView = tv
            }
        }
        row.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val disclosure = TextView(context).apply {
            text = if (isStepExpanded) "收起" else "展开"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(DemoChatPalette.textSecondary)
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

        return StepRowHolder(
            rowView = row,
            indicatorView = indicator,
            titleView = title,
            compactDetailView = compactDetailView,
            detailScrollView = detailScrollView,
            detailTextView = detailTextView,
            disclosureView = disclosure,
            isExpanded = isStepExpanded
        )
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
        DemoChatProcessStepStatus.COMPLETE -> DemoChatPalette.primaryOnContainer
        DemoChatProcessStepStatus.ACTIVE -> DemoChatPalette.primaryOnContainer
        DemoChatProcessStepStatus.WAITING -> DemoChatPalette.amberOnContainer
        DemoChatProcessStepStatus.ERROR -> DemoChatPalette.dangerOnContainer
        DemoChatProcessStepStatus.PENDING -> DemoChatPalette.textMuted
    }

    private fun stepIndicatorFill(status: DemoChatProcessStepStatus): Int = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> DemoChatPalette.successSoft
        DemoChatProcessStepStatus.ACTIVE -> DemoChatPalette.primaryContainer
        DemoChatProcessStepStatus.WAITING -> DemoChatPalette.amberSoft
        DemoChatProcessStepStatus.ERROR -> DemoChatPalette.dangerSoft
        DemoChatProcessStepStatus.PENDING -> DemoChatPalette.surface
    }

    private fun stepIndicatorStroke(status: DemoChatProcessStepStatus): Int = when (status) {
        DemoChatProcessStepStatus.COMPLETE -> DemoChatPalette.success
        DemoChatProcessStepStatus.ACTIVE -> DemoChatPalette.primary
        DemoChatProcessStepStatus.WAITING -> DemoChatPalette.amber
        DemoChatProcessStepStatus.ERROR -> DemoChatPalette.danger
        DemoChatProcessStepStatus.PENDING -> DemoChatPalette.outlineSubtle
    }
}

private object DemoChatPalette {
    val surface get() = Ui.Surface
    val surfaceSubtle get() = Ui.SurfaceSubtle
    val surfaceSoft get() = Ui.SurfaceSoft
    val outlineSubtle get() = Ui.OutlineSubtle
    val divider get() = Ui.Divider
    val assistantBubble get() = Ui.AssistantBubble
    val assistantStroke get() = Ui.AssistantStroke
    val assistantAvatarSurface get() = Ui.AssistantAvatarSurface
    val userBubble get() = Ui.UserBubble
    val onUserBubble get() = Ui.OnUserBubble
    val userStroke get() = Ui.UserStroke
    val userAvatarSurface get() = Ui.UserAvatarSurface
    val onUserAvatar get() = Ui.OnUserAvatar
    val cardSurface get() = Ui.SurfaceElevated
    val cardPressed get() = Ui.SurfaceSoft
    val cardStroke get() = Ui.OutlineSubtle
    val primaryContainer get() = Ui.PrimaryContainer
    val primary get() = Ui.Primary
    val primaryPressed get() = Ui.PrimaryPressed
    val focusRing get() = Ui.FocusRing
    val primaryOnContainer get() = Ui.OnPrimaryContainer
    val success get() = Ui.Success
    val successSoft get() = Ui.SuccessSoft
    val amber get() = Ui.Warning
    val amberSoft get() = Ui.WarningSoft
    val amberOnContainer get() = Ui.WarningOnContainer
    val danger get() = Ui.Danger
    val dangerSoft get() = Ui.DangerSoft
    val dangerOnContainer get() = Ui.DangerOnContainer
    val outline get() = Ui.Outline
    val textMuted get() = Ui.TextMuted
    val textPrimary get() = Ui.TextPrimary
    val textSecondary get() = Ui.TextSecondary
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
    if (strokeColor != Color.TRANSPARENT) {
        setStroke(context.chatDp(1), strokeColor)
    }
}

private fun asymmetricRoundedBackground(
    context: Context,
    fillColor: Int,
    strokeColor: Int,
    topLeftDp: Int,
    topRightDp: Int,
    bottomRightDp: Int,
    bottomLeftDp: Int
): Drawable = GradientDrawable().apply {
    setColor(fillColor)
    val tl = context.chatDp(topLeftDp).toFloat()
    val tr = context.chatDp(topRightDp).toFloat()
    val br = context.chatDp(bottomRightDp).toFloat()
    val bl = context.chatDp(bottomLeftDp).toFloat()
    cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
    if (strokeColor != Color.TRANSPARENT) {
        setStroke(context.chatDp(1), strokeColor)
    }
}

private fun copyButtonBackground(context: Context): Drawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_pressed),
        roundedBackground(
            context = context,
            fillColor = DemoChatPalette.surfaceSoft,
            strokeColor = Color.TRANSPARENT,
            radiusDp = 8
        )
    )
    addState(
        intArrayOf(),
        roundedBackground(
            context = context,
            fillColor = Color.TRANSPARENT,
            strokeColor = Color.TRANSPARENT,
            radiusDp = 8
        )
    )
}

private fun processCardBackground(context: Context): Drawable = StateListDrawable().apply {
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

private fun showFullImageDialog(context: Context, imagePath: String) {
    val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val container = FrameLayout(context).apply {
        setBackgroundColor(Color.argb(235, 10, 11, 14))
        setOnClickListener { dialog.dismiss() }
    }
    val fullImageView = ImageView(context).apply {
        val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(imagePath) }.getOrNull()
        if (bitmap != null) {
            setImageBitmap(bitmap)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { dialog.dismiss() }
    }
    container.addView(
        fullImageView,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    dialog.setContentView(container)
    dialog.show()
}
