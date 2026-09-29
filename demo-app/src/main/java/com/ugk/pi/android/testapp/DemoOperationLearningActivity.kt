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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Local demonstrations, reviewed operation versions, and explicitly confirmed runs. */
class DemoOperationLearningActivity : Activity() {
    private val process get() = DemoProcessScope.get(this)
    private val teaching get() = process.teachingController
    private var teachingRecords = emptyList<DemoTeachingRecord>()
    private var selectedTeachingId: String? = null
    private var compilingTeaching = false
    private var teachingCompilationJob: Job? = null
    private var teachingCompilationId: String? = null
    private var teachingCompilationCancellationRequested = false
    private var teachingCompilationDialog: DemoTeachingCompilationDialog? = null
    private var teachingConfirmationDialog: Dialog? = null
    private val recorder get() = DemoProcessScope.get(this).operationRecorder
    private val workflow get() = DemoProcessScope.get(this).workflowController
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
    private lateinit var workflowCard: LinearLayout
    private lateinit var workflowStatus: TextView
    private lateinit var workflowDetails: TextView
    private lateinit var workflowStop: TextView
    private val guardedViews = mutableListOf<View>()
    private var workflowEntries = emptyMap<String, OperationWorkflowEntry>()
    private var workflowRevision = -1L
    private var workflowDialog: Dialog? = null
    private var pendingGoal = ""
    private var pendingCriteria = ""
    private var pendingGoalDraftId: String? = null
    private var workflowFormKind: String? = null
    private var restoreWorkflowForm = false
    private var completionReviewDraftId: String? = null
    private var completionReviewConsumedId: String? = null
    private var compileAfterIntentSaveDraftId: String? = null
    private var activityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        selectedId = if (savedInstanceState != null) savedInstanceState.getString(EXTRA_DRAFT_ID)
            else intent.getStringExtra(EXTRA_DRAFT_ID)
        selectedTeachingId = if (savedInstanceState != null) savedInstanceState.getString(EXTRA_TEACHING_ID)
            else intent.getStringExtra(EXTRA_TEACHING_ID)
        pendingTitle = savedInstanceState?.getString("operation_title").orEmpty()
        pendingGoal = savedInstanceState?.getString("workflow_goal").orEmpty()
        pendingCriteria = savedInstanceState?.getString("workflow_criteria").orEmpty()
        pendingGoalDraftId = savedInstanceState?.getString("workflow_goal_draft")
        workflowFormKind = savedInstanceState?.getString("workflow_form")
        restoreWorkflowForm = workflowFormKind != null
        completionReviewConsumedId = savedInstanceState?.getString("workflow_review_consumed")
        completionReviewDraftId = if (savedInstanceState != null) savedInstanceState.getString("workflow_review_pending")
            else selectedId?.takeIf { intent.getBooleanExtra(EXTRA_REVIEW_COMPLETION, false) }
        compileAfterIntentSaveDraftId = savedInstanceState?.getString("workflow_compile_after_save")
        intent.removeExtra(EXTRA_REVIEW_COMPLETION)
        restoreStart = savedInstanceState?.getBoolean("operation_start_sheet")
            ?: intent.getBooleanExtra(EXTRA_START_RECORDING, false)
        buildPage()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        DemoProcessScope.get(this).setOperationUiVisible(listenerOwner, true)
        if (displayedDark != Ui.isDark) buildPage()
        teaching.attach(listenerOwner, onChanged = { state ->
            if (state.active) {
                activeCard.visibility = View.VISIBLE
                activeStatus.text = "教学中 · 已完成 ${state.completedSegments} 段"
                activeDetails.text = state.message
                pauseButton.text = "返回教学"
                pauseButton.setOnClickListener { process.overlayController.window.showExpanded(); moveTaskToBack(true) }
                finishButton.text = "结束教学"
                finishButton.setOnClickListener { teaching.finish() }
            } else if (recorder.snapshot().phase == DemoOperationPhase.IDLE) {
                activeCard.visibility = View.GONE
            }
            updateBusyControls()
        }, onEvent = { })
        recorder.attach(listenerOwner, ::renderSnapshot)
        workflow.attach(listenerOwner, ::renderWorkflowSnapshot)
        loadContent()
        if (restoreStart && !isBusy()) {
            restoreStart = false
            showStartSheet()
        }
    }

    override fun onPause() {
        activityResumed = false
        teaching.detach(listenerOwner)
        recorder.detach(listenerOwner)
        workflow.detach(listenerOwner)
        DemoProcessScope.get(this).setOperationUiVisible(listenerOwner, false)
        super.onPause()
    }

    override fun onDestroy() {
        DemoProcessScope.get(this).setOperationUiVisible(listenerOwner, false)
        startDialog?.dismiss()
        workflowDialog?.dismiss()
        teachingConfirmationDialog?.dismiss()
        teachingCompilationDialog?.dismiss()
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_TEACHING_ID, selectedTeachingId)
        outState.putString(EXTRA_DRAFT_ID, selectedId)
        outState.putString("operation_title", pendingTitle)
        outState.putString("workflow_goal", pendingGoal)
        outState.putString("workflow_criteria", pendingCriteria)
        outState.putString("workflow_goal_draft", pendingGoalDraftId)
        outState.putString("workflow_form", workflowFormKind)
        outState.putString("workflow_review_pending", completionReviewDraftId)
        outState.putString("workflow_review_consumed", completionReviewConsumedId)
        outState.putString("workflow_compile_after_save", compileAfterIntentSaveDraftId)
        outState.putBoolean("operation_start_sheet", startDialog?.isShowing == true || restoreStart)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedTeachingId = intent.getStringExtra(EXTRA_TEACHING_ID)
        val nextId = intent.getStringExtra(EXTRA_DRAFT_ID)
        if (nextId != selectedId) {
            workflowDialog?.dismiss()
            workflowFormKind = null
            restoreWorkflowForm = false
            completionReviewDraftId = null
            compileAfterIntentSaveDraftId = null
        }
        selectedId = nextId
        if (intent.getBooleanExtra(EXTRA_REVIEW_COMPLETION, false)) requestCompletionReview(nextId)
        intent.removeExtra(EXTRA_REVIEW_COMPLETION)
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
            if (selectedTeachingId != null || selectedId != null) { selectedTeachingId = null; selectedId = null; loadContent() } else finish()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        headerTitle = text("教我操作", 21f, bold = true).apply { tag = "operation_header_title" }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        headerOwl = TaskNoteUi.owl(this, 40)
        header.addView(headerOwl, LinearLayout.LayoutParams(dp(40), dp(40)))
        headerMore = detailIcon(R.drawable.ic_process_more_horiz, "更多操作", "operation_draft_more") {
            if (selectedTeachingId != null) showTeachingMenu() else showDraftMenu()
        }
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
        finishButton = TaskNoteUi.button(this, "结束并保存", true) {
            requestCompletionReview(recorder.snapshot().draftId)
            recorder.finish()
        }.apply { tag = "operation_page_finish" }
        actions.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        actions.addView(finishButton, LinearLayout.LayoutParams(0, -2, 1f))
        activeCard.addView(actions)
        root.addView(activeCard)
        workflowCard = column().apply {
            visibility = View.GONE
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(TaskNoteUi.Paper)
            tag = "workflow_active_card"
        }
        val workflowHeading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        workflowStatus = detailText("", 16f, TaskNoteUi.Ink, true).apply { tag = "workflow_active_status" }
        workflowHeading.addView(workflowStatus, LinearLayout.LayoutParams(0, -2, 1f))
        workflowStop = quiet("停止") { workflow.stop() }.apply {
            setTextColor(TaskNoteUi.Ink); gravity = Gravity.CENTER; tag = "workflow_page_stop"
        }
        workflowHeading.addView(workflowStop, LinearLayout.LayoutParams(dp(64), dp(48)))
        workflowCard.addView(workflowHeading)
        workflowDetails = detailText("", 13f, TaskNoteUi.Secondary).apply { maxLines = 3 }
        workflowCard.addView(workflowDetails, spaced(3))
        root.addView(workflowCard)
        content = column().apply { setPadding(dp(20), dp(16), dp(20), dp(28)) }
        contentScroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        displayedContentKey = null
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun renderSnapshot(state: DemoOperationSnapshot) {
        if (teaching.snapshot().active) return
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
        updateBusyControls()
    }

    private fun renderWorkflowSnapshot(state: DemoWorkflowSnapshot) {
        workflowCard.visibility = if (state.phase == DemoWorkflowPhase.IDLE) View.GONE else View.VISIBLE
        workflowStatus.text = when (state.phase) {
            DemoWorkflowPhase.COMPILING -> "正在整理操作"
            DemoWorkflowPhase.RUNNING -> "正在执行 · ${state.completedSteps}/${state.totalSteps}"
            DemoWorkflowPhase.JUDGING -> "AI 判断画面 · ${state.completedSteps}/${state.totalSteps}"
            DemoWorkflowPhase.SAVING -> "正在保存"
            DemoWorkflowPhase.IDLE -> ""
        }
        workflowDetails.text = listOf(state.message.takeIf { it.isNotBlank() },
            "${state.modelCalls} 次模型调用 · ${state.imagesSent} 张图片").filterNotNull().joinToString("\n")
        workflowStop.text = if (state.phase == DemoWorkflowPhase.COMPILING) "取消" else "停止"
        workflowStop.isEnabled = state.phase != DemoWorkflowPhase.SAVING
        workflowStop.alpha = if (workflowStop.isEnabled) 1f else .45f
        updateBusyControls()
        if (workflowRevision != state.revision) {
            workflowRevision = state.revision
            loadContent()
        }
    }

    private fun isBusy() = compilingTeaching || process.teachingCompiler.isCompiling || teaching.snapshot().active || recorder.snapshot().phase != DemoOperationPhase.IDLE ||
        workflow.snapshot().phase != DemoWorkflowPhase.IDLE

    private fun guard(view: View): View {
        guardedViews += view
        view.isEnabled = !isBusy()
        view.alpha = if (view.isEnabled) 1f else .45f
        return view
    }

    private fun updateBusyControls() {
        val idle = !isBusy()
        guardedViews.forEach { it.isEnabled = idle; it.alpha = if (idle) 1f else .45f }
        headerMore.isEnabled = idle && (displayedDraftId != null || selectedTeachingId != null)
        headerMore.alpha = if (headerMore.isEnabled) 1f else .4f
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
            val entries = linkedMapOf<String, OperationWorkflowEntry>()
            result.getOrNull()?.let { (draft, drafts) ->
                (drafts + listOfNotNull(draft)).distinctBy { it.id }.forEach { item ->
                    entries[item.id] = runCatching {
                        val plan = workflow.load(item.id)
                        OperationWorkflowEntry(plan = plan, records = if (plan != null) workflow.records(item.id) else emptyList(),
                            intent = workflow.readIntent(item.id))
                    }.getOrElse { OperationWorkflowEntry(error = it.message ?: "暂时无法读取操作版本") }
                }
            }
            if (generation != loadGeneration) return@launch
            teachingRecords = withContext(Dispatchers.IO) { runCatching { process.teachingStore.list() }.getOrDefault(emptyList()) }
            if (generation != loadGeneration) return@launch
            workflowEntries = entries
            guardedViews.clear()
            content.removeAllViews()
            if (recorder.snapshot().phase != DemoOperationPhase.IDLE) {
                content.addView(quiet("取消录制，保留已记录内容") { recorder.finish("用户取消录制") })
            }
            result.fold(onSuccess = { (draft, drafts) ->
                updateHeader(draft?.id)
                if (selectedTeachingId != null) showTeachingRecord(selectedTeachingId!!) else if (draft != null) showDraft(draft) else showList(drafts)
            }, onFailure = {
                updateHeader(null)
                content.addView(text("暂时无法读取本地草稿，请返回后重试。", 16f))
            })
            updateBusyControls()
            val key = selectedTeachingId ?: result.getOrNull()?.first?.id ?: "list"
            if (displayedContentKey != key) {
                displayedContentKey = key
                contentScroll.post { if (generation == loadGeneration) contentScroll.scrollTo(0, 0) }
            }
            result.getOrNull()?.first?.let(::restoreOrReviewCompletion)
        }
    }

    private fun showList(drafts: List<DemoOperationDraft>) {
        content.addView(text("教一次，下次帮你做", 25f, bold = true))
        content.addView(text("通过对话带我完成任务，做错了直接纠正，结束后整理成可复用经验。", 15f, Ui.TextSecondary))
        content.addView(guard(TaskNoteUi.button(this, "开始对话教学", true) { showStartSheet() }.apply {
            tag = "operation_start"
        }), spaced())
        content.addView(text("教学记录 · ${teachingRecords.size}", 14f, Ui.TextSecondary), spaced(24))
        teachingRecords.forEach { record ->
            content.addView(column().apply {
                background = Ui.rounded(this@DemoOperationLearningActivity, Ui.Surface, 18)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(text(record.title, 18f, bold = true))
                addView(text("${record.segments.size} 段对话 · ${teachingExperienceStatus(record)}", 13f, Ui.TextSecondary))
                addView(quiet("查看教学 →") { selectedTeachingId = record.id; loadContent() })
            }, spaced(10))
        }
        content.addView(text("旧版操作  ·  ${drafts.size}", 14f, Ui.TextSecondary), spaced(24))
        if (drafts.isEmpty()) {
            content.addView(text("还没有旧版演示草稿。新的任务可以从上方开始对话教学。", 16f, Ui.TextSecondary), spaced(12))
        }
        drafts.forEach { draft ->
            val entry = workflowEntries[draft.id]
            val plan = entry?.plan
            val needsCompilation = plan != null && needsCompilation(plan, entry?.intent)
            val verified = plan != null && !needsCompilation && DemoWorkflowUi.isVerified(plan, entry?.records.orEmpty())
            val card = column().apply {
                background = Ui.rounded(this@DemoOperationLearningActivity, Ui.Surface, 18)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(DemoWorkflowUi.pill(this@DemoOperationLearningActivity,
                    if (needsCompilation) "待重新整理" else if (verified) "可运行" else if (plan != null) "待试跑" else draftState(draft)), LinearLayout.LayoutParams(-2, -2))
                addView(text(plan?.title ?: draft.title, 18f, bold = true), spaced(6))
                addView(text(if (plan != null) "${plan.steps.size} 个步骤 · ${plan.steps.map { appLabel(it.packageName) }.distinct().joinToString("、")}" else
                    "${draft.events.size} 条事件 · ${draft.frames.size} 张画面", 13f, Ui.TextSecondary))
                addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.startedAt)), 12f, Ui.TextMuted))
                addView(quiet(if (plan != null) "审阅步骤 →" else "查看草稿 →") { selectedId = draft.id; loadContent() })
            }
            content.addView(card, spaced(10))
        }
    }

    private fun teachingRecordStatus(status: String) = when (status) {
        "active", "recording" -> "教学进行中"
        "completed", "finished" -> "教学已结束"
        "cancelled", "interrupted" -> "教学已中断"
        else -> "已保存"
    }

    private fun teachingExperienceStatus(record: DemoTeachingRecord): String {
        val compiled = when (record.compilationStatus) {
            "compiling" -> "整理中"
            "failed" -> "整理失败"
            "completed" -> "已整理"
            else -> "未整理"
        }
        if (record.guide == null) return compiled
        val availability = when (record.availability) {
            "available" -> "可用"
            "disabled" -> "已停用"
            "needs_revision" -> "需修订"
            else -> if (record.guide?.document?.isNotBlank() == true) "Agent 已核对" else "待验证"
        }
        return "$compiled · $availability · 版本 ${record.guideRevision}"
    }

    private fun showTeachingRecord(id: String) {
        val record = teachingRecords.firstOrNull { it.id == id }
        headerTitle.text = "教学记录"
        if (record == null) { content.addView(text("教学记录暂不可用", 16f)); return }
        headerOwl.visibility = View.GONE
        headerMore.visibility = View.VISIBLE
        headerMore.contentDescription = "教学记录更多操作"
        val summary = detailSurface(TaskNoteUi.Paper).apply {
            addView(TaskNoteUi.label(this@DemoOperationLearningActivity, "对话教学"), LinearLayout.LayoutParams(-2, -2))
            addView(detailText(record.title, 24f, TaskNoteUi.Ink, true), spaced(18))
            addView(detailText("${record.segments.size} 段对话  ·  ${record.segments.sumOf { it.actions.size }} 次操作",
                13f, TaskNoteUi.Secondary), spaced(12))
            addView(detailText(teachingRecordStatus(record.status), 12f, TaskNoteUi.Secondary), spaced(6))
        }
        content.addView(summary)
        val compilingThis = compilingTeaching && teachingCompilationId == id
        val status = when {
            compilingThis && teachingCompilationCancellationRequested -> "正在取消"
            compilingThis || record.compilationStatus == "compiling" -> "整理中"
            record.compilationStatus == "failed" -> "待重新整理"
            record.compilationStatus != "completed" -> "未整理"
            record.availability == "disabled" -> "已停用"
            record.availability == "needs_revision" -> "需修订"
            record.availability == "available" -> "可用"
            else -> if (record.guide?.document?.isNotBlank() == true) "Agent 已核对" else "待验证"
        }
        val practice = detailSurface()
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(detailText("最佳实践", 19f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(detailText(status, 12f, if (status == "待重新整理") Ui.Warning else Ui.Primary).apply {
            background = Ui.rounded(this@DemoOperationLearningActivity,
                if (status == "待重新整理") Ui.WarningSoft else Ui.PrimaryContainer, 8)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }, LinearLayout.LayoutParams(-2, -2))
        practice.addView(heading)
        practice.addView(detailText(when {
            compilingThis && teachingCompilationCancellationRequested -> "已请求取消，等待当前连接结束后可重新整理。"
            compilingThis -> "正在处理这份教学，原始记录会保留。"
            record.compilationStatus == "failed" -> "上次整理未完成，教学内容已保留，可以重新整理。"
            record.guide == null -> "把这次教学提炼成有效步骤、纠正经验和完成检查，方便下次使用。"
            record.compilationStatus != "completed" -> "下方是此前整理的经验。新增教学内容需要重新整理后才能推荐。"
            else -> "在普通对话中描述相近任务，即可查找并确认使用这份经验。"
        }, 14f, Ui.TextSecondary), spaced(12))
        record.guide?.let { guide ->
            val guideBody = column()
            if (guide.document.isNotBlank()) {
                guideBody.addView(text("", 15f).apply {
                    DemoMarkdownFormatter.setMarkdown(this, guide.document)
                    setTextIsSelectable(true)
                }, spaced(10))
            } else {
                guideBody.addView(detailText("目标", 12f, Ui.TextMuted), spaced(10))
                guideBody.addView(text(guide.goal, 16f, bold = true).apply { setTextIsSelectable(true) })
                guide.steps.forEachIndexed { index, step ->
                    guideBody.addView(text("${index + 1}. $step", 15f).apply { setTextIsSelectable(true) }, spaced(8))
                }
                fun section(title: String, items: List<String>) {
                    if (items.isEmpty()) return
                    guideBody.addView(detailText(title, 13f, Ui.Primary, true), spaced(18))
                    items.forEach { guideBody.addView(text("· $it", 14f).apply { setTextIsSelectable(true) }) }
                }
                section("准备条件", guide.prerequisites)
                section("纠正与注意事项", guide.corrections)
                section("完成检查", guide.completionChecks)
                section("待核实", guide.uncertainties)
                section("适用表达", guide.intentAliases)
                section("目标应用", guide.targetApps)
                section("不适用情况", guide.notApplicable)
            }
            practice.addView(collapsible("查看整理后的经验 · 版本 ${record.guideRevision}", "teaching_guide", guideBody), spaced(12))
        }
        practice.addView(guard(TaskNoteUi.button(this, when {
            compilingThis && teachingCompilationCancellationRequested -> "正在取消…"
            compilingThis || record.compilationStatus == "compiling" -> "正在整理…"
            record.guide == null -> "整理最佳实践"
            else -> "重新整理经验"
        }, true) {
            confirmTeachingCompilation(record)
        }), spaced(18))
        content.addView(practice, spaced(16))
        content.addView(guard(TaskNoteUi.button(this, "接着上次继续教学", false) {
            confirmTeachingResume(record)
        }), spaced(12))
        if (record.usageHistory.isNotEmpty()) {
            val history = column()
            record.usageHistory.takeLast(5).reversed().forEach { use ->
                val result = when (use.outcome) {
                    "success" -> "报告完成"
                    "needs_revision" -> "路径需修订"
                    "network_error" -> "网络异常"
                    "cancelled" -> "已取消"
                    else -> "未完成"
                }
                history.addView(text("版本 ${use.revision} · $result\n${use.summary}", 13f, Ui.TextSecondary), spaced(8))
            }
            content.addView(collapsible("最近使用记录", "teaching_usage", history), spaced(12))
        }
        content.addView(detailText("教学过程 · ${record.segments.size} 段", 18f, bold = true), spaced(28))
        content.addView(detailText("展开每一段，查看执行回复与画面证据。", 13f, Ui.TextSecondary), spaced(6))
        record.segments.forEachIndexed { index, segment ->
            val images = segment.actions.flatMap { listOfNotNull(it.beforeImage, it.afterImage) }.distinct()
            val state = when (segment.status) {
                "completed" -> "已完成"
                "running" -> "执行中"
                "cancelled", "interrupted" -> "已中断"
                "failed" -> "执行失败"
                else -> "已记录"
            }
            val card = column().apply {
                background = Ui.rounded(this@DemoOperationLearningActivity, Ui.Surface, 18)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(text("第 ${index + 1} 段 · $state", 16f, bold = true))
                addView(text(segment.instruction, 15f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, spaced(6))
                addView(text("${segment.actions.size} 次动作 · ${images.size} 张画面", 12f, Ui.TextSecondary))
            }
            val details = column().apply {
                addView(detailText("你的指令", 12f, Ui.TextMuted), spaced(8))
                addView(text(segment.instruction, 14f).apply { setTextIsSelectable(true) })
                if (segment.reply.isNotBlank()) {
                    addView(detailText("执行回复", 12f, Ui.TextMuted), spaced(12))
                    addView(text(segment.reply, 14f).apply { setTextIsSelectable(true) })
                }
                val gaps = segment.actions.flatMap { it.gaps }.distinct()
                if (gaps.isNotEmpty()) {
                    addView(detailText("画面与结果说明", 12f, Ui.Warning), spaced(12))
                    addView(text(gaps.joinToString("；"), 12f, Ui.TextSecondary))
                }
            }
            if (images.isNotEmpty()) {
                val evidence = column().apply { visibility = View.GONE }
                var loaded = false
                details.addView(quiet("查看本段画面") {
                    evidence.visibility = if (evidence.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    if (!loaded) {
                        loaded = true
                        images.forEachIndexed { imageIndex, name ->
                            val image = ImageView(this).apply {
                                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                                contentDescription = "第 ${index + 1} 段，第 ${imageIndex + 1} 张页面画面"
                            }
                            evidence.addView(image, spaced(8))
                            uiScope.launch {
                                val bitmap = withContext(Dispatchers.IO) {
                                    runCatching { process.teachingStore.imageFile(record.id, name)?.let { file ->
                                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                                        val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, bounds.outWidth / 900) }
                                        BitmapFactory.decodeFile(file.absolutePath, options)
                                    } }.getOrNull()
                                }
                                if (bitmap != null) image.setImageBitmap(bitmap)
                                else { image.visibility = View.GONE; evidence.addView(text("这张画面暂不可用", 12f, Ui.TextSecondary)) }
                            }
                        }
                    }
                })
                details.addView(evidence)
            }
            card.addView(collapsible("展开本段详情", "teaching_segment_$index", details), spaced(4))
            content.addView(card, spaced(12))
        }
    }

    private fun confirmTeachingResume(record: DemoTeachingRecord) {
        if (isBusy() || record.status == "active" || record.compilationStatus == "compiling") {
            notice("请先结束当前任务或整理"); return
        }
        val id = record.id
        lateinit var resumeDialog: Dialog
        resumeDialog = teachingExperienceChoiceDialog(this,
            com.ugk.pi.android.UserConfirmationDialogRequest(
                "继续上次教学？",
                "将恢复“${record.title}”的对话和纠正记录，新增内容继续保存在这份记录中。\n\n" +
                    (record.segments.lastOrNull()?.let { "上次指令：${it.instruction.take(240)}\n\n" } ?: "") +
                    "不会自动重放。返回手机界面后，请在悬浮窗说明从哪里继续；执行前会重新读取当前页面。" +
                    "已有整理经验会保留供查看，续教后需要重新整理才能再次推荐。",
                listOf(com.ugk.pi.android.UserConfirmationDialogButton("resume", "继续教学"),
                    com.ugk.pi.android.UserConfirmationDialogButton("cancel", "暂不继续"))
            )) { choice ->
            resumeDialog.dismiss()
            if (choice == "resume") process.resumeTeaching(id).fold(
                onSuccess = {
                    notice("已恢复教学，请在悬浮窗描述下一步")
                    moveTaskToBack(true)
                },
                onFailure = { notice(it.message ?: "暂时无法继续，原记录已保留") }
            )
        }
        teachingConfirmationDialog = resumeDialog
        resumeDialog.show()
    }

    private fun confirmTeachingCompilation(record: DemoTeachingRecord) {
        if (isBusy() || record.status == "active" || record.compilationStatus == "compiling") {
            notice("请先结束当前任务或等待整理结束"); return
        }
        lateinit var confirmation: Dialog
        confirmation = teachingExperienceChoiceDialog(this, com.ugk.pi.android.UserConfirmationDialogRequest(
            "整理成最佳实践？",
            "由 Agent 把这 ${record.segments.size} 段教学写成分步骤 SOP，并核对证据、修订后交付。\n\n" +
                "指令、执行结果和可用截图会发送给当前配置的模型。长教学会分批处理，可能需要多次请求和几分钟时间。\n\n" +
                "原始记录会保留，整理过程中可随时取消。",
            listOf(com.ugk.pi.android.UserConfirmationDialogButton("compile", "开始整理"),
                com.ugk.pi.android.UserConfirmationDialogButton("cancel", "暂不整理"))
        )) { choice ->
            confirmation.dismiss()
            if (choice == "compile") startTeachingCompilation(record)
        }
        teachingConfirmationDialog = confirmation
        confirmation.show()
    }

    private fun startTeachingCompilation(record: DemoTeachingRecord) {
        if (isBusy()) { notice("请先结束当前任务或等待整理结束"); return }
        val id = record.id
        compilingTeaching = true
        teachingCompilationCancellationRequested = false
        teachingCompilationId = id
        teachingCompilationDialog?.dismiss()
        val processing = DemoTeachingCompilationDialog(this, record.title) {
            teachingCompilationCancellationRequested = true
            teachingCompilationJob?.cancel()
            teachingCompilationDialog?.dismiss()
            loadContent()
            notice("已请求取消，原始记录保留")
        }
        teachingCompilationDialog = processing
        processing.show()
        loadContent()
        updateBusyControls()
        teachingCompilationJob = uiScope.launch {
            var guideSaved = false
            try {
                DemoTeachingCompilationClaim.withClaim(process.teachingStore, id) { current ->
                    val compilation = process.teachingCompiler.compileWithReport(current, onStage = { stage ->
                        withContext(Dispatchers.Main.immediate) { processing.render(stage) }
                    })
                    currentCoroutineContext().ensureActive()
                    processing.saving()
                    // Once atomic saving starts, cancellation cannot relabel the saved guide as failed.
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        process.teachingStore.saveGuide(id, compilation.guide)
                        guideSaved = true
                    }
                }
                processing.dismiss()
                if (!isDestroyed) notice("最佳实践已整理并保存")
            } catch (error: Exception) {
                if (!isDestroyed) when {
                    guideSaved -> { processing.dismiss(); notice("最佳实践已整理并保存") }
                    error is kotlinx.coroutines.CancellationException -> processing.dismiss()
                    else -> processing.failed(teachingCompilationFailure(error)) { startTeachingCompilation(record) }
                }
            } finally {
                compilingTeaching = false
                teachingCompilationJob = null
                teachingCompilationId = null
                teachingCompilationCancellationRequested = false
                if (!isDestroyed) loadContent()
            }
        }
    }

    private fun teachingCompilationFailure(error: Exception): String {
        if (error is DemoTeachingCompileException) return error.message
        val message = error.message.orEmpty()
        return when {
            error is java.net.SocketTimeoutException || message.contains("超时") || message.contains("timeout", true) ->
                "模型响应时间较长，本次等待已超时。\n请稍后重试，或在设置中切换响应更快的模型。"
            error is java.io.IOException -> "网络连接中断，暂时无法完成整理。请检查网络后重试。"
            else -> message.take(220).ifBlank { "暂时无法完成整理，请稍后重试。" }
        }
    }

    private fun showTeachingMenu() {
        val record = teachingRecords.firstOrNull { it.id == selectedTeachingId } ?: return
        if (isBusy()) return
        PopupMenu(this, headerMore).apply {
            if (record.guide != null) {
                menu.add(0, 1, 0, "复制最佳实践")
                menu.add(0, 2, 1, if (record.availability == "disabled") "恢复推荐（待验证）" else "停用这份经验")
                if (record.availability != "disabled") menu.add(0, 3, 2, "标记为需修订")
            }
            menu.add(0, 4, 3, "删除教学记录")
            setOnMenuItemClickListener { item ->
                if (!isBusy() && selectedTeachingId == record.id) when (item.itemId) {
                    1 -> {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText(record.title, record.guide!!.readableText()))
                        notice("已复制经验")
                    }
                    2, 3 -> runCatching {
                        check(record.status != "active" && record.compilationStatus != "compiling") { "请先结束教学或整理" }
                        process.teachingStore.setAvailability(record.id, if (item.itemId == 3) "needs_revision"
                            else if (record.availability == "disabled") "pending_validation" else "disabled")
                    }.onSuccess { loadContent() }.onFailure { notice(it.message ?: "经验状态更新失败") }
                    4 -> confirmTeachingDeletion(record)
                }
                true
            }
            show()
        }
    }

    private fun confirmTeachingDeletion(record: DemoTeachingRecord) {
        if (isBusy() || process.conversationRuntime.runCoordinator.isRunning()) {
            notice("请先停止正在进行的任务或等待整理结束"); return
        }
        val id = record.id
        lateinit var confirmation: Dialog
        confirmation = teachingExperienceChoiceDialog(this,
            com.ugk.pi.android.UserConfirmationDialogRequest(
                "删除这份教学记录？",
                "将删除“${record.title}”的教学对话、截图、整理经验和使用历史。删除后无法恢复，后续对话也不会再推荐这份经验。",
                listOf(com.ugk.pi.android.UserConfirmationDialogButton("delete", "删除记录"),
                    com.ugk.pi.android.UserConfirmationDialogButton("cancel", "保留记录"))
            )) { choice ->
            confirmation.dismiss()
            if (choice == "delete") uiScope.launch {
                if (isBusy() || process.conversationRuntime.runCoordinator.isRunning()) {
                    notice("请先停止正在进行的任务或等待整理结束")
                    return@launch
                }
                runCatching { withContext(Dispatchers.IO) { process.teachingStore.delete(id) } }
                    .onSuccess { selectedTeachingId = null; notice("教学记录已删除"); loadContent() }
                    .onFailure { notice(it.message ?: "删除失败，记录已保留") }
            }
        }
        // The common sheet does not own navigation or dismissal for its caller.
        teachingConfirmationDialog = confirmation
        confirmation.show()
    }

    private fun updateHeader(draftId: String?) {
        displayedDraftId = draftId
        headerTitle.text = if (draftId == null) "教我操作" else if (workflowEntries[draftId]?.plan != null) "已整理操作" else "演示草稿"
        headerOwl.visibility = if (draftId == null) View.VISIBLE else View.GONE
        headerMore.visibility = if (draftId == null) View.GONE else View.VISIBLE
        headerMore.isEnabled = displayedDraftId != null && !isBusy()
        headerMore.alpha = if (headerMore.isEnabled) 1f else .4f
    }

    private fun showDraft(draft: DemoOperationDraft) {
        val entry = workflowEntries[draft.id] ?: OperationWorkflowEntry()
        if (entry.plan != null) {
            showWorkflow(draft, entry.plan, entry.records, entry.intent)
            val raw = column()
            addRawDraft(draft, raw)
            content.addView(collapsible("原始记录 · ${draft.events.size} 条事件", "workflow_raw_recording", raw), spaced(18))
            return
        }
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
        summary.addView(detailText("演示已保存在本机", 12f, TaskNoteUi.Secondary), spaced(16))
        summary.addView(detailText("完成标准", 12f, TaskNoteUi.Secondary), spaced(14))
        summary.addView(detailText(entry.intent?.completionCriteria ?: "补一句：看到什么才算完成？", 15f, TaskNoteUi.Ink).apply {
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            tag = "workflow_draft_criteria"
        }, spaced(5))
        summary.addView(guard(quiet(if (entry.intent == null) "补充完成标准" else "修改完成标准") {
            showCompletionReview(draft)
        }.apply { tag = "workflow_review_completion" }))
        if (draft.events.isNotEmpty()) {
            summary.addView(guard(TaskNoteUi.button(this, "整理成操作", true) { confirmCompilation(draft) }.apply {
                tag = "workflow_compile"
            }), spaced(16))
        }
        content.addView(summary)
        entry.error?.let { content.addView(detailText(it, 13f, Ui.TextSecondary), spaced(10)) }
        val currentWorkflow = workflow.snapshot()
        if (currentWorkflow.phase == DemoWorkflowPhase.IDLE && currentWorkflow.draftId == draft.id &&
            currentWorkflow.message.isNotBlank()) {
            content.addView(detailText(currentWorkflow.message, 13f, Ui.TextSecondary), spaced(10))
        }
        addRawDraft(draft, content)
    }

    private fun addRawDraft(draft: DemoOperationDraft, parent: LinearLayout) {
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
            empty.addView(guard(TaskNoteUi.button(this, "重新教学", true) { showStartSheet() }.apply {
                tag = "operation_draft_record_again"
                contentDescription = "重新教学一份新演示，保留当前草稿"
            }), spaced(16))
            parent.addView(empty, spaced(16))
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
            parent.addView(timeline, spaced(16))
        }
        val linked = draft.events.flatMap { listOfNotNull(it.preFrameId, it.postFrameId) }.toSet()
        val otherFrames = draft.frames.filter { it.id !in linked }
        if (otherFrames.isNotEmpty()) {
            val framesCard = detailSurface().apply { tag = "operation_draft_other_frames" }
            addEvidence(framesCard, draft, otherFrames)
            parent.addView(framesCard, spaced(12))
        }
        if (draft.gaps.isNotEmpty()) addDraftNotes(draft, parent)
    }

    private fun addDraftNotes(draft: DemoOperationDraft, parent: LinearLayout = content) {
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
        parent.addView(card, spaced(12))
    }

    private fun showWorkflow(draft: DemoOperationDraft, plan: DemoWorkflowPlan, records: List<DemoWorkflowRunRecord>,
                             savedIntent: DemoWorkflowIntent?) {
        val needsCompilation = needsCompilation(plan, savedIntent)
        val verified = !needsCompilation && DemoWorkflowUi.isVerified(plan, records)
        val summary = detailSurface(TaskNoteUi.Paper, TaskNoteUi.Rule).apply { tag = "workflow_summary" }
        summary.addView(DemoWorkflowUi.pill(this, "${if (needsCompilation) "待重新整理" else if (verified) "可运行" else "待试跑"} · 第 ${plan.version} 版"),
            LinearLayout.LayoutParams(-2, -2))
        summary.addView(detailText(plan.title, 25f, TaskNoteUi.Ink, true), spaced(14))
        summary.addView(detailText(plan.goal, 15f, TaskNoteUi.Secondary), spaced(10))
        summary.addView(detailText("完成标准", 12f, TaskNoteUi.Secondary), spaced(14))
        summary.addView(detailText((if (needsCompilation) savedIntent?.completionCriteria else null)
            ?: plan.completionCriteria.ifBlank { "请先补充完成标准" }, 15f, TaskNoteUi.Ink).apply {
            tag = "workflow_plan_criteria"
        }, spaced(5))
        summary.addView(detailText("${plan.steps.size} 个步骤 · ${plan.steps.map { appLabel(it.packageName) }.distinct().joinToString("、")}",
            13f, TaskNoteUi.Secondary), spaced(14))
        summary.addView(guard(TaskNoteUi.button(this, if (needsCompilation) "重新整理操作" else if (verified) "再次运行" else "检查并试跑", true) {
            if (needsCompilation) confirmCompilation(draft) else confirmRun(plan, isTrial = !verified)
        }.apply { tag = if (needsCompilation) "workflow_recompile" else if (verified) "workflow_run_again" else "workflow_trial" }), spaced(18))
        summary.addView(guard(quiet("编辑名称与目标") {
            if (!canUsePlan(plan)) return@quiet
            workflowDialog?.dismiss()
            workflowDialog = DemoWorkflowUi.overviewEditor(this, plan, ::savePlan)
        }.apply { tag = "workflow_edit_overview" }))
        summary.addView(guard(quiet("修改标准并重新整理") {
            showCompletionReview(draft, recompile = true)
        }.apply { tag = "workflow_revise_completion" }))
        if (!verified) summary.addView(detailText(when {
            needsCompilation -> "完成标准已更新，重新整理后再试跑。"
            records.any { it.isTrial && it.status == "succeeded" } -> "内容已更新，需要重新试跑。"
            else -> "核对下面的步骤与完成条件，再开始试跑。"
        },
            12f, TaskNoteUi.Secondary), spaced(4))
        content.addView(summary)
        workflow.snapshot().takeIf { it.phase == DemoWorkflowPhase.IDLE && it.draftId == plan.draftId &&
            it.message.isNotBlank() }?.let {
            content.addView(detailText(it.message, 13f, Ui.TextSecondary), spaced(10))
        }

        if (plan.warnings.isNotEmpty()) {
            val warnings = column().apply {
                setPadding(dp(12), dp(4), dp(12), dp(12))
                plan.warnings.forEach { addView(detailText(it, 13f, Ui.TextSecondary), spaced(8)) }
            }
            content.addView(collapsible("需要核对 · ${plan.warnings.size} 项", "workflow_warnings", warnings), spaced(12))
        }
        content.addView(detailText(if (needsCompilation) "上次整理的步骤" else "操作步骤", 17f, bold = true), spaced(24))
        plan.steps.forEachIndexed { index, step ->
            val card = DemoWorkflowUi.stepCard(this, index, step, appLabel(step.packageName)) { anchor ->
                showStepMenu(plan, index, anchor)
            }
            card.findViewWithTag<View>("workflow_step_menu_${step.id}")?.let(::guard)
            content.addView(card, spaced(10))
        }
        val sortedRecords = records.sortedByDescending { it.startedAt }
        if (sortedRecords.isNotEmpty()) {
            val recordCard = detailSurface().apply { tag = "workflow_latest_result" }
            recordCard.addView(detailText("最近结果", 17f, bold = true))
            recordCard.addView(DemoWorkflowUi.runRecord(this, sortedRecords.first(), plan))
            content.addView(recordCard, spaced(18))
            if (sortedRecords.size > 1) {
                val history = column()
                sortedRecords.drop(1).forEach { history.addView(DemoWorkflowUi.runRecord(this, it, plan)) }
                content.addView(collapsible("更早的运行 · ${sortedRecords.size - 1} 次", "workflow_run_history", history), spaced(10))
            }
        }
        content.addView(detailText("本次整理 · ${plan.modelCalls} 次模型调用 · ${plan.imagesSent} 张图片", 12f, Ui.TextMuted), spaced(18))
    }

    private fun collapsible(label: String, viewTag: String, body: LinearLayout): LinearLayout = column().apply {
        tag = viewTag
        body.visibility = View.GONE
        val chevron = ImageView(this@DemoOperationLearningActivity).apply {
            setImageResource(R.drawable.ic_process_expand_more)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextSecondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val toggle = LinearLayout(this@DemoOperationLearningActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.clickableRounded(this@DemoOperationLearningActivity, Ui.Background, Ui.SurfaceSubtle, 12)
            isClickable = true; isFocusable = true
            tag = "${viewTag}_toggle"
            addView(detailText(label, 13f, Ui.TextSecondary), LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
            contentDescription = "$label，已收起，点击展开"
            setOnClickListener {
                val expanded = body.visibility != View.VISIBLE
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                chevron.rotation = if (expanded) 180f else 0f
                contentDescription = "$label，${if (expanded) "已展开，点击收起" else "已收起，点击展开"}"
            }
        }
        addView(toggle); addView(body)
    }

    private fun needsCompilation(plan: DemoWorkflowPlan, savedIntent: DemoWorkflowIntent?): Boolean =
        plan.completionCriteria.isBlank() || savedIntent?.let {
            it.completionCriteria.trim() != plan.completionCriteria.trim()
        } == true

    private fun requestCompletionReview(draftId: String?) {
        if (draftId != null && draftId != completionReviewConsumedId) completionReviewDraftId = draftId
    }

    /** Consume the finish request once; recreation restores the open form and its text separately. */
    private fun restoreOrReviewCompletion(draft: DemoOperationDraft) {
        if (!activityResumed || isBusy() || workflowDialog?.isShowing == true || startDialog?.isShowing == true) return
        if (restoreWorkflowForm) {
            restoreWorkflowForm = false
            val kind = workflowFormKind
            workflowFormKind = null
            if (pendingGoalDraftId == draft.id) {
                when (kind) {
                    "completion" -> showCompletionReview(draft)
                    "revise_completion" -> showCompletionReview(draft, recompile = true)
                    "compile" -> confirmCompilation(draft)
                }
                return
            }
        }
        if (compileAfterIntentSaveDraftId == draft.id) {
            compileAfterIntentSaveDraftId = null
            val stored = workflowEntries[draft.id]?.intent
            if (stored != null && stored.goal == pendingGoal.trim() && stored.completionCriteria == pendingCriteria.trim()) {
                confirmCompilation(draft)
                return
            }
        }
        if (completionReviewDraftId == draft.id) {
            completionReviewDraftId = null
            completionReviewConsumedId = draft.id
            val entry = workflowEntries[draft.id]
            if (entry?.plan == null && entry?.intent == null) showCompletionReview(draft)
        }
    }

    private fun prepareIntentFields(draft: DemoOperationDraft) {
        if (pendingGoalDraftId != draft.id) {
            val entry = workflowEntries[draft.id]
            pendingGoalDraftId = draft.id
            pendingGoal = entry?.intent?.goal ?: entry?.plan?.goal ?: draft.title
            pendingCriteria = entry?.intent?.completionCriteria ?: entry?.plan?.completionCriteria.orEmpty()
        }
    }

    private fun trackWorkflowForm(kind: String, dialog: Dialog) {
        workflowDialog = dialog
        workflowFormKind = kind
        restoreWorkflowForm = false
        dialog.setOnDismissListener {
            if (workflowDialog === dialog) {
                workflowDialog = null
                workflowFormKind = null
                restoreWorkflowForm = false
            }
        }
    }

    private fun showCompletionReview(draft: DemoOperationDraft, recompile: Boolean = false) {
        if (isBusy()) { notice("请先结束当前录制或操作"); return }
        prepareIntentFields(draft)
        completionReviewDraftId = null
        completionReviewConsumedId = draft.id
        workflowDialog?.dismiss()
        val dialog = DemoWorkflowUi.completionSheet(this, pendingGoal, pendingCriteria, recompile,
            { goal, criteria -> pendingGoal = goal; pendingCriteria = criteria }) { goal, criteria ->
            if (isBusy() || selectedId != draft.id) {
                notice("页面状态已变化，请重新打开说明")
                false
            } else workflow.saveIntent(draft.id, goal, criteria).fold(onSuccess = {
                pendingGoal = goal
                pendingCriteria = criteria
                if (recompile) compileAfterIntentSaveDraftId = draft.id
                true
            }, onFailure = {
                notice(it.message ?: "暂时无法保存，填写内容仍保留")
                false
            })
        }
        trackWorkflowForm(if (recompile) "revise_completion" else "completion", dialog)
    }

    private fun confirmCompilation(draft: DemoOperationDraft) {
        if (isBusy()) { notice("请先结束当前录制或操作"); return }
        if (draft.events.isEmpty()) { notice("先录下一段操作，再整理"); return }
        prepareIntentFields(draft)
        workflowDialog?.dismiss()
        val dialog = DemoWorkflowUi.compileSheet(this, draft, pendingGoal, pendingCriteria,
            { goal, criteria -> pendingGoal = goal; pendingCriteria = criteria }) { goal, criteria ->
            if (isBusy() || selectedId != draft.id) {
                notice("页面状态已变化，请重新打开整理卡")
                false
            } else workflow.compile(draft.id, goal, criteria).fold(onSuccess = { true }, onFailure = {
                notice(it.message ?: "暂时无法整理，演示仍保存在本机")
                false
            })
        }
        trackWorkflowForm("compile", dialog)
    }

    private fun showStepMenu(plan: DemoWorkflowPlan, index: Int, anchor: View) {
        if (!canUsePlan(plan)) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "编辑步骤与完成条件")
            menu.add(0, 2, 1, "删除这一步").isEnabled = plan.steps.size > 1
            setOnMenuItemClickListener { item ->
                if (!canUsePlan(plan)) return@setOnMenuItemClickListener true
                if (item.itemId == 1) {
                    workflowDialog?.dismiss()
                    workflowDialog = DemoWorkflowUi.stepEditor(this@DemoOperationLearningActivity, plan, index, ::savePlan)
                } else {
                    AlertDialog.Builder(this@DemoOperationLearningActivity, Ui.dialogTheme())
                        .setTitle("删除第 ${index + 1} 步？")
                        .setMessage("${plan.steps[index].title}\n保存后生成新版本，需要重新试跑。")
                        .setNegativeButton("保留", null)
                        .setPositiveButton("删除并保存") { _, _ ->
                            savePlan(plan.copy(steps = plan.steps.filterIndexed { i, _ -> i != index }))
                        }.show()
                }
                true
            }
            show()
        }
    }

    private fun savePlan(plan: DemoWorkflowPlan): Boolean {
        if (!canUsePlan(plan, compareDigest = false)) return false
        val current = workflowEntries[plan.draftId]?.plan ?: return false
        if (current.digest() == plan.digest()) return true
        return workflow.saveRevision(plan).fold(onSuccess = { true }, onFailure = {
            notice(it.message ?: "暂时无法保存，请保留当前修改后重试")
            false
        })
    }

    private fun canUsePlan(plan: DemoWorkflowPlan, compareDigest: Boolean = true): Boolean {
        val current = workflowEntries[plan.draftId]?.plan
        if (isBusy()) { notice("请先结束当前录制或操作"); return false }
        if (selectedId != plan.draftId || current == null || current.version != plan.version ||
            (compareDigest && current.digest() != plan.digest())) {
            notice("操作版本已变化，请重新打开")
            return false
        }
        return true
    }

    private fun confirmRun(plan: DemoWorkflowPlan, isTrial: Boolean) {
        if (!canUsePlan(plan)) return
        if (needsCompilation(plan, workflowEntries[plan.draftId]?.intent)) {
            notice("请先按当前完成标准重新整理操作")
            return
        }
        workflowDialog?.dismiss()
        workflowDialog = DemoWorkflowUi.runConfirmation(this, plan, isTrial, ::appLabel) {
            if (!canUsePlan(plan)) false
            else if (needsCompilation(plan, workflowEntries[plan.draftId]?.intent)) {
                notice("完成标准已更新，请先重新整理")
                false
            }
            else if (!isTrial && !DemoWorkflowUi.isVerified(plan, workflowEntries[plan.draftId]?.records.orEmpty())) {
                notice("这个版本还需要成功试跑一次")
                false
            } else workflow.start(plan, isTrial).fold(onSuccess = { true }, onFailure = {
                notice(it.message ?: "暂时无法开始，请检查设备状态")
                false
            })
        }
    }

    private fun showDraftMenu() {
        val id = displayedDraftId?.takeIf { it == selectedId } ?: return
        if (isBusy()) return
        PopupMenu(this, headerMore).apply {
            if (workflowEntries[id]?.plan != null) menu.add(0, 1, 0, "重新整理演示")
            menu.add(0, 2, 1, "删除演示与操作")
            setOnMenuItemClickListener { item ->
                if (displayedDraftId == id && selectedId == id && !isBusy()) {
                    if (item.itemId == 1) uiScope.launch {
                        val draft = withContext(Dispatchers.IO) { runCatching { recorder.readDraft(id) }.getOrNull() }
                        if (draft != null && selectedId == id) confirmCompilation(draft)
                    } else confirmDraftDeletion(id)
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
            if (isBusy()) { notice("请先结束当前录制或操作"); return@launch }
            AlertDialog.Builder(this@DemoOperationLearningActivity, Ui.dialogTheme())
                .setTitle("删除这份演示？")
                .setMessage("将删除“${current.title}”的演示素材、整理版本和运行记录。")
                .setNegativeButton("保留", null)
                .setPositiveButton("删除") { _, _ ->
                    if (selectedId != id || displayedDraftId != id || isBusy()) {
                        notice("草稿或录制状态已变化，请重新打开菜单")
                        return@setPositiveButton
                    }
                    uiScope.launch {
                        val result = workflow.delete(id)
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
        if (isBusy()) { notice("请先结束当前录制或操作"); return }
        startDialog?.dismiss()
        val root = column().apply {
            background = Ui.asymmetricRounded(this@DemoOperationLearningActivity, TaskNoteUi.Paper, 28, 28, 0, 0)
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }
        val body = column()
        body.addView(TaskNoteUi.label(this, "对话教学"), LinearLayout.LayoutParams(-2, -2))
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("这次想教我什么？", 25f, TaskNoteUi.Ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(TaskNoteUi.owl(this, 58), LinearLayout.LayoutParams(dp(58), dp(58)))
        body.addView(heading, spaced())
        body.addView(text("开始后回到桌面，在展开的悬浮对话窗里描述下一步。Agent 完成这一段就等你继续，做错了直接发消息纠正。结束后整理这次教学的有效步骤和经验。", 15f, TaskNoteUi.Secondary))
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
        val currentApi = ApiProviderSettingsStore(this).activeConfig()
        val apiReady = currentApi != null && currentApi.apiKey.isNotBlank() &&
            currentApi.model.isNotBlank() && currentApi.baseUrl.isNotBlank()
        body.addView(text(if (apiReady)
            "开始即同意：指令、页面截图、界面结构和纠正内容会发送给当前 API（${currentApi?.displayName()}），用于执行任务与整理经验。"
            else "请先配置模型。对话教学需要 AI 理解指令、执行操作并整理经验。", 14f, TaskNoteUi.Secondary), spaced())
        val preparation = column()
        body.addView(preparation, spaced())
        val footer = column()
        val primary = TaskNoteUi.button(this, "同意并开始教学", true) {
            if (isBusy()) { notice("请先结束当前录制或操作"); return@button }
            if (name.text.toString().trim().isBlank()) { name.error = "给这段操作起个名字"; return@button }
            process.startTeaching(name.text.toString().trim()).fold(onSuccess = {
                startDialog?.dismiss(); startDialog = null
                notice("教学已开始，请在悬浮窗描述下一步")
                runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
            }, onFailure = { notice(it.message ?: "暂时无法开始教学") })
        }.apply { tag = "operation_confirm_start" }
        footer.addView(primary)
        footer.addView(quiet("暂不开始") { startDialog?.dismiss(); startDialog = null })
        fun refreshPreparation() {
            preparation.removeAllViews()
            val serviceReady = AgentAccessibilityService.running && AgentAccessibilityService.instance != null
            val overlayReady = Settings.canDrawOverlays(this)
            val fullAuthorizationReady = AgentAuthorizationSettingsStore(this).isFullAuthorizationEnabled()
            primary.isEnabled = Build.VERSION.SDK_INT >= 30 && serviceReady && overlayReady && apiReady && fullAuthorizationReady && !isBusy()
            if (Build.VERSION.SDK_INT < 30) preparation.addView(text("页面关键画面需要 Android 11 或更新版本。此设备仍可查看已有草稿。", 14f, TaskNoteUi.Secondary))
            if (!apiReady) preparation.addView(quiet("去配置模型 →") {
                restoreStart = true; startDialog?.dismiss(); startDialog = null
                openPreparation(Intent(this, SettingsActivity::class.java))
            })
            if (!fullAuthorizationReady) {
                preparation.addView(text("请先开启全授权模式，教学中不再逐次弹出操作确认。", 13f, TaskNoteUi.Secondary))
                preparation.addView(quiet("去开启全授权模式 →") {
                    restoreStart = true; startDialog?.dismiss(); startDialog = null
                    openPreparation(Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_OPEN_AUTHORIZATION, true))
                })
            }
            if (!serviceReady) preparation.addView(quiet("开启无障碍 →") {
                restoreStart = true; startDialog?.dismiss(); startDialog = null
                openPreparation(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            if (!overlayReady) preparation.addView(quiet("开启悬浮对话窗 →") {
                restoreStart = true; startDialog?.dismiss(); startDialog = null
                openPreparation(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            if (primary.isEnabled) preparation.addView(text("准备好了。通过悬浮对话窗描述下一步；执行时收起为侧边思考图标，点击可展开。窗外可正常操作，每段结束后自动展开，可随时停止本段或结束教学。", 13f, TaskNoteUi.Secondary))
            else preparation.addView(text("完成准备后返回这里，再开始教学。", 13f, TaskNoteUi.Secondary))
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
        const val EXTRA_TEACHING_ID = "teaching_id"
        const val EXTRA_DRAFT_ID = "operation_draft_id"
        const val EXTRA_START_RECORDING = "operation_start_recording"
        const val EXTRA_REVIEW_COMPLETION = "operation_review_completion"
    }
}

private data class OperationWorkflowEntry(
    val plan: DemoWorkflowPlan? = null,
    val records: List<DemoWorkflowRunRecord> = emptyList(),
    val intent: DemoWorkflowIntent? = null,
    val error: String? = null
)
