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
 * Chat-first 展示组件：消息保持轻量，Agent 过程以默认收起的单行入口承载。
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
    ERROR("执行失败", { Ui.Danger }),
    STOPPED("已停止", { Ui.TextSecondary });

    val accentColor: Int get() = accentColorProvider()
}

/** 一行可验证的 Agent 过程步骤，不包含模型隐性思维内容。 */
enum class DemoChatProcessStepStatus {
    COMPLETE,
    ACTIVE,
    WAITING,
    ERROR,
    STOPPED,
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
    val expanded: Boolean = false,
    val summary: CharSequence? = null,
    val isRunning: Boolean = stage == DemoChatProcessStage.THINKING ||
        stage == DemoChatProcessStage.TOOL_CALL || stage == DemoChatProcessStage.RESULT
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

    /** An overlay host may supply a preview window with its own WindowManager type. */
    var onImageClick: ((String) -> Unit)? = null

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

    private val userImagesContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
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
        addView(userImagesContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
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

    /** 绑定角色和消息正文，可选携带用户图片路径列表；不会触发任何业务回调。 */
    fun bind(role: DemoChatMessageRole, text: CharSequence, imagePaths: List<String> = emptyList()) {
        this.role = role
        messageText = text.toString()
        if (role == DemoChatMessageRole.USER) {
            userContainer.visibility = View.VISIBLE
            assistantContainer.visibility = View.GONE

            bindUserImages(imagePaths)

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
            userImagesContainer.visibility = View.GONE
            assistantBubble.background = asymmetricRoundedBackground(
                context = context,
                fillColor = DemoChatPalette.assistantBubble,
                strokeColor = DemoChatPalette.assistantStroke,
                topLeftDp = 4,
                topRightDp = 18,
                bottomRightDp = 18,
                bottomLeftDp = 18
            )
            renderAssistantContent(messageText)
            assistantCopyButton.background = copyButtonBackground(context)
            assistantCopyButton.setTextColor(DemoChatPalette.textSecondary)
        }
        contentDescription = buildString {
            append(role.accessibilityLabel)
            append("：")
            append(messageText.ifBlank { "空消息" })
        }
    }

    fun bind(role: DemoChatMessageRole, text: CharSequence, imagePath: String?) {
        bind(role, text, if (imagePath.isNullOrBlank()) emptyList() else listOf(imagePath))
    }

    private fun bindUserImages(imagePaths: List<String>) {
        userImagesContainer.removeAllViews()
        val validPaths = imagePaths.filter { it.isNotBlank() && java.io.File(it).exists() }
        if (validPaths.isEmpty()) {
            userImagesContainer.visibility = View.GONE
            return
        }
        userImagesContainer.visibility = View.VISIBLE
        when (validPaths.size) {
            1 -> {
                val path = validPaths[0]
                val bitmap = decodeSampledBitmap(
                    file = java.io.File(path),
                    targetMaxSidePx = 384
                )
                if (bitmap != null) {
                    val iv = createChatImageView(bitmap, path, 190, 190, 12)
                    userImagesContainer.addView(iv, LinearLayout.LayoutParams(
                        context.chatDp(190),
                        context.chatDp(190)
                    ).apply {
                        bottomMargin = context.chatDp(6)
                    })
                } else {
                    userImagesContainer.visibility = View.GONE
                }
            }
            2 -> {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                var count = 0
                validPaths.forEachIndexed { i, path ->
                    val bitmap = decodeSampledBitmap(
                        file = java.io.File(path),
                        targetMaxSidePx = 256
                    )
                    if (bitmap != null) {
                        val iv = createChatImageView(bitmap, path, 100, 100, 10)
                        row.addView(iv, LinearLayout.LayoutParams(
                            context.chatDp(100),
                            context.chatDp(100)
                        ).apply {
                            if (i < validPaths.size - 1) marginEnd = context.chatDp(6)
                        })
                        count++
                    }
                }
                if (count > 0) {
                    userImagesContainer.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = context.chatDp(6)
                    })
                } else {
                    userImagesContainer.visibility = View.GONE
                }
            }
            else -> {
                val row1 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                val itemsPerRow = 2
                var count = 0
                validPaths.forEachIndexed { i, path ->
                    val bitmap = decodeSampledBitmap(
                        file = java.io.File(path),
                        targetMaxSidePx = 256
                    )
                    if (bitmap != null) {
                        val targetRow = if (i < itemsPerRow) row1 else row2
                        val iv = createChatImageView(bitmap, path, 100, 100, 10)
                        targetRow.addView(iv, LinearLayout.LayoutParams(
                            context.chatDp(100),
                            context.chatDp(100)
                        ).apply {
                            if ((i % itemsPerRow) < itemsPerRow - 1) marginEnd = context.chatDp(6)
                        })
                        count++
                    }
                }
                var addedAny = false
                if (row1.childCount > 0) {
                    userImagesContainer.addView(row1, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = context.chatDp(6)
                    })
                    addedAny = true
                }
                if (row2.childCount > 0) {
                    userImagesContainer.addView(row2, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = context.chatDp(6)
                    })
                    addedAny = true
                }
                if (!addedAny) {
                    userImagesContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun createChatImageView(
        bitmap: android.graphics.Bitmap,
        imagePath: String,
        widthDp: Int,
        heightDp: Int,
        radiusDp: Int
    ): ImageView {
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, context.chatDp(radiusDp).toFloat())
                }
            }
            background = asymmetricRoundedBackground(
                context = context,
                fillColor = DemoChatPalette.cardSurface,
                strokeColor = DemoChatPalette.cardStroke,
                topLeftDp = radiusDp,
                topRightDp = if (radiusDp > 4) 4 else radiusDp,
                bottomRightDp = radiusDp,
                bottomLeftDp = radiusDp
            )
            setImageBitmap(bitmap)
            isClickable = true
            isFocusable = true
            contentDescription = "已发送图片，点击全屏预览"
            setOnClickListener {
                val open = onImageClick
                if (open != null) open(imagePath) else showFullImageDialog(context, imagePath)
            }
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

internal fun showFullImageDialog(context: Context, imagePath: String, overlay: Boolean = false): android.app.Dialog {
    val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    if (overlay) {
        dialog.window?.setType(if (Build.VERSION.SDK_INT >= 26) {
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            android.view.WindowManager.LayoutParams.TYPE_PHONE
        })
    }
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
    return dialog
}
