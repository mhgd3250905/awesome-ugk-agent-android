package com.ugk.pi.android.testapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Slice A: local demonstrations only. No model calls or execution promises. */
class DemoOperationLearningActivity : Activity() {
    private val recorder get() = DemoProcessScope.get(this).operationRecorder
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listenerOwner = Any()
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var headerTitle: TextView
    private lateinit var headerMore: ImageButton
    private lateinit var headerOwl: ImageView
    private var displayedDraftId: String? = null
    private var displayedContentKey: String? = null
    private lateinit var activeCard: LinearLayout
    private lateinit var activeStatus: TextView
    private lateinit var activeDetails: TextView
    private lateinit var pauseButton: TextView
    private lateinit var finishButton: TextView
    private var selectedId: String? = null
    private var loadGeneration = 0
    private var previousPhase = DemoOperationPhase.IDLE
    private var startDialog: Dialog? = null
    private var pendingTitle = ""
    private var restoreStart = false
    private var displayedDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        selectedId = savedInstanceState?.getString(EXTRA_DRAFT_ID) ?: intent.getStringExtra(EXTRA_DRAFT_ID)
        pendingTitle = savedInstanceState?.getString("operation_title").orEmpty()
        restoreStart = savedInstanceState?.getBoolean("operation_start_sheet")
            ?: intent.getBooleanExtra(EXTRA_START_RECORDING, false)
        buildPage()
    }

    override fun onResume() {
        super.onResume()
        DemoProcessScope.get(this).setOperationUiVisible(listenerOwner, true)
        if (displayedDark != Ui.isDark) buildPage()
        recorder.attach(listenerOwner, ::renderSnapshot)
        loadContent()
        if (restoreStart && recorder.snapshot().phase == DemoOperationPhase.IDLE) {
            restoreStart = false
            showStartSheet()
        }
    }

    override fun onPause() {
        recorder.detach(listenerOwner)
        DemoProcessScope.get(this).setOperationUiVisible(listenerOwner, false)
        super.onPause()
    }

    override fun onDestroy() {
        DemoProcessScope.get(this).setOperationUiVisible(listenerOwner, false)
        startDialog?.dismiss()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_DRAFT_ID, selectedId)
        outState.putString("operation_title", pendingTitle)
        outState.putBoolean("operation_start_sheet", startDialog?.isShowing == true || restoreStart)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedId = intent.getStringExtra(EXTRA_DRAFT_ID)
        loadContent()
        if (intent.getBooleanExtra(EXTRA_START_RECORDING, false)) showStartSheet()
    }

    private fun buildPage() {
        displayedDark = Ui.isDark
        val root = column().apply { setBackgroundColor(Ui.Background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Ui.Background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (Ui.isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(20), dp(8)) }
        header.addView(detailIcon(R.drawable.ic_arrow_back, "返回", "operation_header_back") {
            if (selectedId != null) { selectedId = null; loadContent() } else finish()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        headerTitle = text("教我操作", 21f, bold = true).apply { tag = "operation_header_title" }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        headerOwl = TaskNoteUi.owl(this, 40)
        header.addView(headerOwl, LinearLayout.LayoutParams(dp(40), dp(40)))
        headerMore = detailIcon(R.drawable.ic_process_more_horiz, "草稿更多操作", "operation_draft_more", ::showDraftMenu)
            .apply { visibility = View.GONE }
        header.addView(headerMore, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        activeCard = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(TaskNoteUi.Paper)
            visibility = View.GONE
        }
        activeStatus = text("", 17f, TaskNoteUi.Ink, true)
        activeDetails = text("", 13f, TaskNoteUi.Secondary)
        val statusContent = column().apply { addView(activeStatus); addView(activeDetails) }
        activeCard.addView(object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = minOf(dp(160), (resources.displayMetrics.heightPixels * .26f).toInt())
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(statusContent) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val actions = LinearLayout(this)
        pauseButton = TaskNoteUi.button(this, "暂停", false) {
            if (recorder.snapshot().phase == DemoOperationPhase.PAUSED) {
                recorder.resume().onFailure { notice(it.message ?: "暂时无法继续录制") }
            } else recorder.pause()
        }.apply { tag = "operation_page_pause" }
        finishButton = TaskNoteUi.button(this, "结束并保存", true) { recorder.finish() }.apply { tag = "operation_page_finish" }
        actions.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        actions.addView(finishButton, LinearLayout.LayoutParams(0, -2, 1f))
        activeCard.addView(actions)
        root.addView(activeCard)
        content = column().apply { setPadding(dp(20), dp(16), dp(20), dp(28)) }
        contentScroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        displayedContentKey = null
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun renderSnapshot(state: DemoOperationSnapshot) {
        if (state.phase != DemoOperationPhase.IDLE) {
            displayedDraftId = null
            headerMore.isEnabled = false
            headerMore.alpha = .4f
        }
        content.findViewWithTag<View>("operation_draft_record_again")?.apply {
            isEnabled = state.phase == DemoOperationPhase.IDLE
            alpha = if (isEnabled) 1f else .45f
        }
        activeCard.visibility = if (state.phase == DemoOperationPhase.IDLE) View.GONE else View.VISIBLE
        activeStatus.text = when (state.phase) {
            DemoOperationPhase.RECORDING -> "录制中 · ${operationDuration(state.elapsedMillis)}"
            DemoOperationPhase.PAUSED -> "已暂停 · ${operationDuration(state.elapsedMillis)}"
            DemoOperationPhase.SAVING -> "正在保存本地草稿"
            DemoOperationPhase.IDLE -> ""
        }
        activeDetails.text = listOfNotNull(state.title.takeIf { it.isNotBlank() },
            "${state.eventCount} 条事件 · ${state.frameCount} 张关键画面", state.message).joinToString("\n")
        pauseButton.text = if (state.phase == DemoOperationPhase.PAUSED) "继续录制" else "暂停"
        pauseButton.isEnabled = state.phase != DemoOperationPhase.SAVING
        finishButton.isEnabled = state.phase != DemoOperationPhase.SAVING
        if (previousPhase != DemoOperationPhase.IDLE && state.phase == DemoOperationPhase.IDLE) {
            state.message?.let(::notice)
            selectedId = state.draftId ?: selectedId
            loadContent()
        }
        if (previousPhase == DemoOperationPhase.IDLE && state.phase != DemoOperationPhase.IDLE) {
            selectedId = state.draftId
            loadContent()
        }
        previousPhase = state.phase
    }

    private fun loadContent() {
        val generation = ++loadGeneration
        val requestedId = selectedId
        displayedDraftId = null
        headerMore.isEnabled = false
        headerMore.alpha = .4f
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { requestedId?.let { recorder.readDraft(it) } to recorder.listDrafts() }
            }
            if (generation != loadGeneration) return@launch
            content.removeAllViews()
            if (recorder.snapshot().phase != DemoOperationPhase.IDLE) {
                content.addView(quiet("取消录制，保留已记录内容") { recorder.finish("用户取消录制") })
            }
            result.fold(onSuccess = { (draft, drafts) ->
                updateHeader(draft?.id)
                if (draft != null) showDraft(draft) else showList(drafts)
            }, onFailure = {
                updateHeader(null)
                content.addView(text("暂时无法读取本地草稿，请返回后重试。", 16f))
            })
            val key = result.getOrNull()?.first?.id ?: "list"
            if (displayedContentKey != key) {
                displayedContentKey = key
                contentScroll.post { if (generation == loadGeneration) contentScroll.scrollTo(0, 0) }
            }
        }
    }

    private fun showList(drafts: List<DemoOperationDraft>) {
        content.addView(text("把一次操作，先记下来", 25f, bold = true))
        content.addView(text("你来操作，助手记录事件和关键画面。当前只保存演示草稿，尚不进行 AI 整理或自动执行。", 15f, Ui.TextSecondary))
        content.addView(TaskNoteUi.button(this, "录制一次演示", true) { showStartSheet() }.apply {
            tag = "operation_start"; isEnabled = recorder.snapshot().phase == DemoOperationPhase.IDLE
            alpha = if (isEnabled) 1f else .45f
        }, spaced())
        content.addView(text("本地草稿  ·  ${drafts.size}", 14f, Ui.TextSecondary), spaced(24))
        if (drafts.isEmpty()) {
            content.addView(text("还没有演示\n从你熟悉的一小段操作开始。录完后可以查看事件和关键画面。", 16f, Ui.TextSecondary), spaced(12))
        }
        drafts.forEach { draft ->
            val card = column().apply {
                background = Ui.rounded(this@DemoOperationLearningActivity, Ui.Surface, 18)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(text(draft.title, 18f, bold = true))
                addView(text("${draftState(draft)} · ${draft.events.size} 条事件 · ${draft.frames.size} 张画面", 13f, Ui.TextSecondary))
                addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.startedAt)), 12f, Ui.TextMuted))
                addView(quiet("查看草稿 →") { selectedId = draft.id; loadContent() })
            }
            content.addView(card, spaced(10))
        }
    }

    private fun updateHeader(draftId: String?) {
        displayedDraftId = draftId.takeIf { recorder.snapshot().phase == DemoOperationPhase.IDLE }
        headerTitle.text = if (draftId == null) "教我操作" else "演示草稿"
        headerOwl.visibility = if (draftId == null) View.VISIBLE else View.GONE
        headerMore.visibility = if (draftId == null) View.GONE else View.VISIBLE
        headerMore.isEnabled = displayedDraftId != null
        headerMore.alpha = if (headerMore.isEnabled) 1f else .4f
    }

    private fun showDraft(draft: DemoOperationDraft) {
        val summary = detailSurface(TaskNoteUi.Paper, TaskNoteUi.Rule).apply { tag = "operation_draft_summary" }
        summary.addView(detailText(draftState(draft), 12f, TaskNoteUi.Secondary).apply {
            background = Ui.rounded(this@DemoOperationLearningActivity, TaskNoteUi.Sticker, 6)
            setTextColor(TaskNoteUi.StickerInk)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }, LinearLayout.LayoutParams(-2, -2))
        summary.addView(detailText(draft.title, 25f, TaskNoteUi.Ink, true).apply {
            tag = "operation_draft_title"
        }, spaced(12))
        summary.addView(detailText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(draft.startedAt)), 12f, TaskNoteUi.Secondary), spaced(6))
        val stats = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun stat(count: Int, label: String) = column().apply {
            contentDescription = "$label $count"
            addView(detailText(count.toString(), 25f, TaskNoteUi.Ink, true))
            addView(detailText(label, 13f, TaskNoteUi.Secondary), spaced(3))
        }
        stats.addView(stat(draft.events.size, "操作事件"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(View(this).apply { setBackgroundColor(TaskNoteUi.Rule) },
            LinearLayout.LayoutParams(dp(1), dp(38)).apply { leftMargin = dp(14); rightMargin = dp(14) })
        stats.addView(stat(draft.frames.size, "关键画面"), LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(stats, spaced(20))
        summary.addView(detailText("尚未整理、试跑", 12f, TaskNoteUi.Secondary), spaced(16))
        content.addView(summary)

        if (draft.events.isEmpty()) {
            val empty = detailSurface().apply { tag = "operation_draft_empty" }
            empty.addView(TaskNoteUi.owl(this, 56), LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            empty.addView(detailText("还没有录到操作", 19f, bold = true).apply { gravity = Gravity.CENTER }, spaced(10))
            empty.addView(detailText("开始后切换到目标 App，操作一小段再回来。", 14f, Ui.TextSecondary).apply {
                gravity = Gravity.CENTER
            }, spaced(8))
            if (draft.frames.isNotEmpty()) {
                empty.addView(detailText("已保留 ${draft.frames.size} 张关键画面，可在下方查看。", 13f, Ui.TextSecondary), spaced(8))
            }
            empty.addView(TaskNoteUi.button(this, "重新录制", true) { showStartSheet() }.apply {
                tag = "operation_draft_record_again"
                isEnabled = recorder.snapshot().phase == DemoOperationPhase.IDLE
                alpha = if (isEnabled) 1f else .45f
                contentDescription = "重新录制一份新演示，保留当前草稿"
            }, spaced(16))
            content.addView(empty, spaced(16))
        } else {
            val timeline = detailSurface().apply { tag = "operation_draft_timeline" }
            timeline.addView(detailText("操作记录", 17f, bold = true), spaced(0))
            draft.events.forEachIndexed { index, event ->
                val row = LinearLayout(this).apply { gravity = Gravity.TOP; setPadding(0, dp(16), 0, dp(12)) }
                row.addView(detailText((index + 1).toString().padStart(2, '0'), 12f, Ui.TextMuted).apply {
                    setPadding(0, dp(3), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(30), -2))
                val body = column()
                val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                heading.addView(detailText(eventLabel(event.type), 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(detailText(operationDuration(event.at - draft.startedAt), 12f, Ui.TextMuted).apply {
                    setPadding(dp(8), 0, 0, 0)
                })
                body.addView(heading)
                body.addView(detailText(appLabel(event.packageName), 13f, Ui.TextSecondary), spaced(5))
                event.label?.takeIf { it.isNotBlank() }?.let {
                    body.addView(detailText(it, 14f), spaced(7))
                }
                val frames = draft.frames.filter { it.id == event.preFrameId || it.id == event.postFrameId }
                if (frames.isNotEmpty()) addEvidence(body, draft, frames)
                row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
                timeline.addView(row)
                if (index < draft.events.lastIndex) timeline.addView(View(this).apply { setBackgroundColor(Ui.Divider) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(30) })
            }
            content.addView(timeline, spaced(16))
        }
        val linked = draft.events.flatMap { listOfNotNull(it.preFrameId, it.postFrameId) }.toSet()
        val otherFrames = draft.frames.filter { it.id !in linked }
        if (otherFrames.isNotEmpty()) {
            val framesCard = detailSurface().apply { tag = "operation_draft_other_frames" }
            addEvidence(framesCard, draft, otherFrames)
            content.addView(framesCard, spaced(12))
        }
        if (draft.gaps.isNotEmpty()) addDraftNotes(draft)
    }

    private fun addDraftNotes(draft: DemoOperationDraft) {
        val card = column().apply { tag = "operation_draft_notes" }
        val details = column().apply {
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), dp(12))
            addView(detailText("部分画面未记录，整理时需要补充确认。", 13f, Ui.TextSecondary), spaced(8))
            draft.gaps.forEach { addView(detailText(it, 13f, Ui.TextSecondary), spaced(10)) }
        }
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_process_expand_more)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextSecondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val toggle = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true; isFocusable = true
            tag = "operation_draft_notes_toggle"
            background = Ui.clickableRounded(this@DemoOperationLearningActivity, Ui.Background, Ui.SurfaceSubtle, 12)
            addView(detailText("记录说明 · ${draft.gaps.size} 项", 13f, Ui.TextSecondary), LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
            contentDescription = "记录说明，${draft.gaps.size} 项，已收起，点击展开"
            setOnClickListener {
                val expanded = details.visibility != View.VISIBLE
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                chevron.rotation = if (expanded) 180f else 0f
                contentDescription = "记录说明，${draft.gaps.size} 项，${if (expanded) "已展开，点击收起" else "已收起，点击展开"}"
            }
        }
        card.addView(toggle); card.addView(details)
        content.addView(card, spaced(12))
    }

    private fun showDraftMenu() {
        val id = displayedDraftId?.takeIf { it == selectedId } ?: return
        if (recorder.snapshot().phase != DemoOperationPhase.IDLE) return
        PopupMenu(this, headerMore).apply {
            menu.add("删除草稿与素材").apply { isEnabled = recorder.snapshot().phase == DemoOperationPhase.IDLE }
            setOnMenuItemClickListener {
                if (displayedDraftId == id && selectedId == id && recorder.snapshot().phase == DemoOperationPhase.IDLE) {
                    confirmDraftDeletion(id)
                }
                true
            }
            show()
        }
    }

    private fun confirmDraftDeletion(id: String) {
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { recorder.readDraft(id) } }
            if (selectedId != id || displayedDraftId != id) return@launch
            if (result.isFailure) { notice("暂时无法读取这份草稿，请稍后重试"); return@launch }
            val current = result.getOrNull()
            if (current == null) { loadContent(); notice("这份草稿已不可用"); return@launch }
            if (recorder.snapshot().phase != DemoOperationPhase.IDLE) { notice("请先结束当前录制"); return@launch }
            AlertDialog.Builder(this@DemoOperationLearningActivity, Ui.dialogTheme())
                .setTitle("删除这份演示？")
                .setMessage("将删除“${current.title}”及它的本地关键画面。")
                .setNegativeButton("保留", null)
                .setPositiveButton("删除") { _, _ ->
                    if (selectedId != id || displayedDraftId != id || recorder.snapshot().phase != DemoOperationPhase.IDLE) {
                        notice("草稿或录制状态已变化，请重新打开菜单")
                        return@setPositiveButton
                    }
                    uiScope.launch {
                        val result = withContext(Dispatchers.IO) { recorder.deleteDraft(id) }
                        result.onSuccess { if (selectedId == id) selectedId = null; loadContent() }
                            .onFailure { notice(it.message ?: "暂时无法删除草稿") }
                    }
                }.show()
        }
    }

    private fun detailSurface(color: Int = Ui.Surface, rule: Int = Color.TRANSPARENT) = column().apply {
        background = Ui.rounded(this@DemoOperationLearningActivity, color, 20, rule)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun detailText(value: String, size: Float, color: Int = Ui.TextPrimary, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    private fun detailIcon(resource: Int, label: String, viewTag: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource)
        imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
        contentDescription = label; tag = viewTag
        if (Build.VERSION.SDK_INT >= 26) tooltipText = label
        background = Ui.clickableRounded(this@DemoOperationLearningActivity, Ui.Background, Ui.SurfaceSubtle, 24)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { action() }
    }

    private fun addEvidence(parent: LinearLayout, draft: DemoOperationDraft, frames: List<DemoOperationFrame>) {
        val evidence = column().apply { visibility = View.GONE }
        var loaded = false
        val toggle = quiet("查看关键画面 · ${frames.size}") {}
        toggle.contentDescription = "${frames.size} 张关键画面，已收起，点击展开"
        toggle.setOnClickListener {
            val open = evidence.visibility != View.VISIBLE
            evidence.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "收起关键画面" else "查看关键画面 · ${frames.size}"
            toggle.contentDescription = "${frames.size} 张关键画面，${if (open) "已展开，点击收起" else "已收起，点击展开"}"
            if (open && !loaded) {
                loaded = true
                frames.forEach { frame ->
                    evidence.addView(detailText("${operationDuration(frame.at - draft.startedAt)} · ${appLabel(frame.packageName)}", 12f, Ui.TextSecondary), spaced(8))
                    val image = ImageView(this).apply {
                        adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "录制时的页面关键画面"
                    }
                    evidence.addView(image, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                    uiScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching { recorder.frameFile(draft.id, frame.fileName)?.let { file ->
                                val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, frame.width / 900) }
                                BitmapFactory.decodeFile(file.absolutePath, options)
                            } }.getOrNull()
                        }
                        if (bitmap != null) image.setImageBitmap(bitmap)
                        else {
                            image.visibility = View.GONE
                            evidence.addView(text("这张关键画面暂时无法读取。", 13f, Ui.TextSecondary))
                        }
                    }
                }
            }
        }
        parent.addView(toggle); parent.addView(evidence)
    }

    private fun showStartSheet() {
        if (recorder.snapshot().phase != DemoOperationPhase.IDLE) { notice("请先结束当前录制"); return }
        startDialog?.dismiss()
        val root = column().apply {
            background = Ui.asymmetricRounded(this@DemoOperationLearningActivity, TaskNoteUi.Paper, 28, 28, 0, 0)
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }
        val body = column()
        body.addView(TaskNoteUi.label(this, "录下一次演示"), LinearLayout.LayoutParams(-2, -2))
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("这次想教我什么？", 25f, TaskNoteUi.Ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(TaskNoteUi.owl(this, 58), LinearLayout.LayoutParams(dp(58), dp(58)))
        body.addView(heading, spaced())
        body.addView(text("操作事件和页面关键画面保存在本机。遇到不想记录的内容，先点暂停。录制无需配置模型，也不会调用模型。", 15f, TaskNoteUi.Secondary))
        val name = EditText(this).apply {
            hint = "例如：打开设置查看显示选项"; textSize = 16f
            setTextColor(TaskNoteUi.Ink); setHintTextColor(TaskNoteUi.Secondary)
            background = Ui.rounded(this@DemoOperationLearningActivity, Ui.Surface, 12, TaskNoteUi.Rule)
            setPadding(dp(14), dp(12), dp(14), dp(12)); minHeight = dp(54)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            filters = arrayOf(android.text.InputFilter.LengthFilter(120))
            setText(pendingTitle)
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(view.windowToken, 0)
                    view.clearFocus()
                    true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { pendingTitle = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        body.addView(name, spaced())
        val preparation = column()
        body.addView(preparation, spaced())
        val footer = column()
        val primary = TaskNoteUi.button(this, "开始录制", true) {
            if (name.text.toString().trim().isBlank()) { name.error = "给这段操作起个名字"; return@button }
            recorder.start(name.text.toString().trim()).fold(onSuccess = {
                startDialog?.dismiss(); startDialog = null
                notice("录制已开始，请切换到要演示的 App")
                runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
            }, onFailure = { notice(it.message ?: "暂时无法开始录制") })
        }.apply { tag = "operation_confirm_start" }
        footer.addView(primary)
        footer.addView(quiet("暂不录制") { startDialog?.dismiss(); startDialog = null })
        fun refreshPreparation() {
            preparation.removeAllViews()
            val serviceReady = AgentAccessibilityService.running && AgentAccessibilityService.instance != null
            val overlayReady = Settings.canDrawOverlays(this)
            primary.isEnabled = Build.VERSION.SDK_INT >= 30 && serviceReady && overlayReady
            if (Build.VERSION.SDK_INT < 30) preparation.addView(text("页面关键画面需要 Android 11 或更新版本。此设备仍可查看已有草稿。", 14f, TaskNoteUi.Secondary))
            if (!serviceReady) preparation.addView(quiet("开启无障碍 →") {
                restoreStart = true; startDialog?.dismiss(); startDialog = null
                openPreparation(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            if (!overlayReady) preparation.addView(quiet("开启悬浮控制条 →") {
                restoreStart = true; startDialog?.dismiss(); startDialog = null
                openPreparation(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            if (primary.isEnabled) preparation.addView(text("准备好了。开始后，用悬浮条暂停或结束。\n部分系统页会隐藏浮条，可回到本 App 暂停或结束。", 13f, TaskNoteUi.Secondary))
            else preparation.addView(text("完成准备后返回这里，再开始录制。", 13f, TaskNoteUi.Secondary))
        }
        refreshPreparation()
        val budget = (resources.displayMetrics.heightPixels * .85f).toInt()
        root.addView(object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                footer.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val visible = android.graphics.Rect().also { window.decorView.getWindowVisibleDisplayFrame(it) }
                val available = minOf(budget, visible.height().takeIf { it > 0 } ?: budget)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec((available - footer.measuredHeight - dp(34)).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(body) })
        root.addView(footer)
        startDialog = Dialog(this, Ui.dialogTheme()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
                setDimAmount(.44f); setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            show(); window?.setLayout(-1, -2)
        }
        if (Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
            root.startAnimation(AnimationUtils.loadAnimation(this, R.anim.demo_dialog_enter_bottom))
        }
    }

    private fun openPreparation(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { notice("无法打开此设置，请在系统设置中找到 UGK Agent") }
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun draftState(draft: DemoOperationDraft): String = when (draft.status) {
        "recording" -> "录制中"
        "paused" -> "录制已暂停"
        "interrupted" -> "录制已中断"
        else -> "本地草稿"
    }

    private fun eventLabel(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "点击"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "长按"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "滚动页面"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "输入变化（正文未记录）"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "切换页面"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "窗口变化"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "页面内容变化"
        else -> "界面事件"
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, color: Int = Ui.TextPrimary, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5)); setLineSpacing(0f, 1.16f)
    }
    private fun quiet(label: String, action: () -> Unit) = text(label, 14f, Ui.TextSecondary).apply {
        minHeight = dp(48); minWidth = dp(48); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
    private fun spaced(top: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun notice(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_DRAFT_ID = "operation_draft_id"
        const val EXTRA_START_RECORDING = "operation_start_recording"
    }
}
