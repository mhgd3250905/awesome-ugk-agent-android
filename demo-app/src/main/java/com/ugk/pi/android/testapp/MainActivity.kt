package com.ugk.pi.android.testapp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AgentRunSource
import com.ugk.pi.android.AgentToolInterlockErrorCodes
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import com.ugk.pi.android.AgentImageContent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val processScope by lazy { (application as DemoApplication).processScope }
    private val conversationRuntime: DemoConversationRuntime
        get() = processScope.conversationRuntime
    private val runCoordinator: DemoAgentRunCoordinator
        get() = conversationRuntime.runCoordinator
    private val apiStore by lazy { ApiProviderSettingsStore(this) }
    private val authorizationStore by lazy { AgentAuthorizationSettingsStore(this) }
    private val conversationStore: DemoConversationStore
        get() = conversationRuntime.conversationStore
    private val traceStore by lazy { DemoAgentTraceStore(applicationContext) }
    private val fileImportStore by lazy { DemoFileImportStore(applicationContext) }
    private val delayedTasks: DemoDelayedTaskController
        get() = processScope.delayedTasks
    private lateinit var activeConversation: DemoConversation
    private val session: AgentSession
        get() = checkNotNull(conversationRuntime.session) {
            "Active conversation session has not been initialized"
        }
    private var runState: DemoRunState = DemoRunState.initial()
    private var activityResumed = false
    private val capabilityInterlock: DemoCapabilityInterlock
        get() = conversationRuntime.capabilityInterlock
    private val activityToken = Any()
    private val confirmationPresenter by lazy {
        processScope.confirmationPresenter.also { presenter ->
            presenter.attach(
                activity = this,
                isResumed = { activityResumed },
                isFullAuthorizationEnabled = { authorizationStore.isFullAuthorizationEnabled() },
                overlayHost = floatingWindow
            )
        }
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var headerView: LinearLayout
    private lateinit var headerDividerView: View
    private lateinit var historyButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var composerLayout: LinearLayout
    private lateinit var inputShellLayout: LinearLayout
    private lateinit var appBarTitle: TextView
    private lateinit var messageContainer: LinearLayout
    private lateinit var messageScrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var importButton: ImportActionButton
    private lateinit var sendButton: SendActionButton
    private var pendingImportedFiles: List<DemoImportedFile> = emptyList()
    private var importingFile = false
    private var pendingImages: List<ProcessedImage> = emptyList()
    private var processingImages = false
    private var imageSelectionGeneration = 0L
    private var cameraPhotoFile: File? = null
    private lateinit var pendingImagePreviewContainer: LinearLayout
    private lateinit var pendingImagesNoticeText: TextView
    private lateinit var pendingImagesListContainer: LinearLayout
    private lateinit var pendingFilesPreviewContainer: LinearLayout
    private var suppressOverlayForInAppNavigation = false
    private val fileImportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var providerLabel: TextView
    private lateinit var runStatusLabel: TextView
    private lateinit var statusBanner: TextView
    private lateinit var contextUsageLayout: LinearLayout
    private lateinit var contextProgressBarTrack: LinearLayout
    private lateinit var contextProgressBarFill: View
    private lateinit var contextProgressBarEmpty: View
    private lateinit var contextUsageText: TextView
    private lateinit var contextHintRightText: TextView
    private var processCard: DemoChatProcessCardView? = null
    private var assistantMessageView: DemoChatMessageView? = null
    private var streamingAssistantText: StringBuilder? = null
    private var lastStreamingRenderTime = 0L
    private var hasPendingStreamingRender = false
    private val streamingRenderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val streamingRenderRunnable = Runnable { flushStreamingAssistantText() }
    private val delayedDialog by lazy { DemoDelayedTaskDialog(this) { delayedTasks.snapshot() } }
    private var lastImeInsetBottom = 0
    private val themeListener: (Boolean) -> Unit = { runOnUiThread { applyTheme() } }
    private val floatingWindow: AgentFloatingWindow
        get() = processScope.overlayController.window

    // Registered during construction so the launcher is ready long before
    // the Activity reaches STARTED, as the Activity Result API requires.
    private val inAppUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // The once-per-process flag was set before the flow launched, so a
        // user cancel or failure only needs to drop the download listener.
        if (result.resultCode != RESULT_OK) inAppUpdateController.onUpdateFlowAbandoned()
    }
    private val inAppUpdateController: InAppUpdateController by lazy {
        InAppUpdateController(this, inAppUpdateLauncher,
            canPresent = { permissionGuide.canShowUpdate() },
            onExternalFlow = { permissionGuide.markExternalFlow() })
    }

    private val permissionGuide: DemoPermissionGuideController = DemoPermissionGuideController(this,
        busy = { runState.isBusy || runCoordinator.isRunning() || DemoCapabilityInterlock.isRecordingOwned() ||
            delayedTasks.snapshot() !is DemoDelayedTaskState.Idle ||
            processScope.urgentInteractionDispatcher.hasPending() || importingFile || processingImages || backgroundGuidance.isActive },
        onFinished = { inAppUpdateController.checkOnResume() })
    private val runtimePermissionHistory by lazy { DemoRuntimePermissionHistory(this) }
    private val backgroundGuidance: DemoBackgroundGuidanceHost = DemoBackgroundGuidanceHost(this,
        beforeExternal = {
            suppressOverlayForInAppNavigation = true
            permissionGuide.markExternalFlow()
        },
        onClosed = {
            renderDelayedTaskState(delayedTasks.snapshot())
            permissionGuide.reconsider()
        })

    override fun startActivity(intent: Intent, options: Bundle?) {
        suppressOverlayForInAppNavigation = true
        permissionGuide.markExternalFlow()
        super.startActivity(intent, options)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        suppressOverlayForInAppNavigation = true
        permissionGuide.markExternalFlow()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionGuide.restore(savedInstanceState)
        backgroundGuidance.restore(savedInstanceState)
        ThemeManager.init(this)
        ThemeManager.addListener(themeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        activeConversation = conversationStore.get(
            conversationRuntime.activeConversationId ?: conversationStore.activeId()
        ) ?: conversationStore.ensureActive()
        conversationRuntime.activeConversationId = activeConversation.id
        conversationStore.setActive(activeConversation.id)
        conversationRuntime.session = conversationRuntime.sessionFor(activeConversation.id)
            ?: createDemoAgentSession(activeConversation).also {
                conversationRuntime.rememberSession(activeConversation.id, it)
            }
        setContentView(buildUi())
        processScope.overlayController.bindCommands(
            owner = activityToken,
            commands = DemoOverlayCommands(
                // AgentFloatingWindow invokes touch callbacks on the main looper;
                // return the enqueue result synchronously so a full queue keeps
                // the user's draft instead of clearing it optimistically.
                onSend = { text -> enqueueOverlayMessage(text) },
                onStop = { runOnUiThread {
                    if (DemoCapabilityInterlock.isRecordingOwned()) processScope.operationRecorder.finish("用户停止录制")
                    stopAgent(clearQueuedMessages = true)
                } },
                onOpenApp = {
                    runOnUiThread {
                        startActivity(
                            Intent(this@MainActivity, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                        )
                    }
                },
                onHide = { runOnUiThread { hideFloatingWindow() } },
                onDraftChanged = { draft -> conversationRuntime.draft = draft }
            )
        )
        restoreDraft(savedInstanceState)
        refreshRuntime()
        attachRunCoordinator()
        delayedTasks.attach(activityToken) { state ->
            runOnUiThread { renderDelayedTaskState(state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::activeConversation.isInitialized) refreshActiveConversationFromStore()
    }

    @Deprecated("Use the file picker callback when the Activity Result API is adopted.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_IMAGE || requestCode == REQUEST_TAKE_PHOTO || requestCode == REQUEST_IMPORT_FILE) {
            permissionGuide.onExternalResult()
        }
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_PICK_IMAGE -> {
                val uris = buildList {
                    val clipData = data?.clipData
                    if (clipData != null) {
                        for (i in 0 until clipData.itemCount) {
                            val itemUri = clipData.getItemAt(i).uri
                            if (itemUri != null) add(itemUri)
                        }
                    } else {
                        data?.data?.let { add(it) }
                    }
                }
                handleSelectedImageUris(uris)
            }
            REQUEST_TAKE_PHOTO -> {
                val file = cameraPhotoFile ?: return
                handleSelectedImageUris(listOf(Uri.fromFile(file)))
            }
            REQUEST_IMPORT_FILE -> {
                val uri = data?.data ?: return
                if (importingFile) return
                importingFile = true
                updateComposerState()
                fileImportScope.launch {
                    val result = withContext(Dispatchers.IO) { fileImportStore.importFile(uri) }
                    importingFile = false
                    when (result) {
                        is DemoFileImportResult.Success -> {
                            pendingImportedFiles = (pendingImportedFiles + result.file)
                                .takeLast(MAX_PENDING_IMPORTED_FILES)
                            showInlineNotice("已导入文件：${result.file.displayName}")
                            updatePendingFilesPreview()
                            updateComposerState()
                        }

                        is DemoFileImportResult.Failure -> {
                            showInlineNotice("文件导入失败：${result.message}")
                            updateComposerState()
                        }
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            permissionGuide.onExternalResult()
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            runtimePermissionHistory.recordResult(Manifest.permission.CAMERA, granted)
            when {
                granted -> openCamera()
                !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> showCameraPermissionSettings()
                else -> showInlineNotice("尚未获得相机权限，你仍可以从相册选择图片")
            }
        }
    }

    private fun showAttachmentMenu() {
        if (runState.isBusy || importingFile || processingImages || DemoCapabilityInterlock.isRecordingOwned()) return
        val options = arrayOf("拍照", "从相册选择图片", "导入文档/文件", "教我操作")
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle("添加与工具")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> openGalleryPicker()
                    2 -> openFilePicker()
                    3 -> openOperationLearning()
                }
            }
            .show()
    }

    private fun openGalleryPicker() {
        if (runState.isBusy || importingFile || processingImages) return
        if (pendingImages.size >= MAX_PENDING_IMAGES) {
            showInlineNotice("最多支持添加 $MAX_PENDING_IMAGES 张图片")
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGE)
    }

    private fun openCamera() {
        if (runState.isBusy || importingFile || processingImages) return
        if (pendingImages.size >= MAX_PENDING_IMAGES) {
            showInlineNotice("最多支持添加 $MAX_PENDING_IMAGES 张图片")
            return
        }
        when (runtimePermissionHistory.action(this, Manifest.permission.CAMERA)) {
            RuntimePermissionAction.SETTINGS -> { showCameraPermissionSettings(); return }
            RuntimePermissionAction.REQUEST -> {
                suppressOverlayForInAppNavigation = true
                permissionGuide.markExternalFlow()
                runtimePermissionHistory.markRequested(Manifest.permission.CAMERA)
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                return
            }
            RuntimePermissionAction.GRANTED -> Unit
        }
        val photoFile = DemoImageUtils.createCameraPhotoFile(this)
        cameraPhotoFile = photoFile
        val uri = DemoImageUtils.getFileProviderUri(this, photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_TAKE_PHOTO)
    }

    private fun showCameraPermissionSettings() {
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle("开启相机权限")
            .setMessage("当前未获得权限，请在系统设置中开启。你也可以从相册选择图片。")
            .setNegativeButton("暂时不用", null)
            .setPositiveButton("去系统设置") { _, _ ->
                try {
                    startActivity(runtimePermissionHistory.settingsIntent(Manifest.permission.CAMERA))
                } catch (_: Exception) {
                    showInlineNotice("暂时无法打开系统设置，请稍后再试")
                }
            }.show()
    }

    private fun handleSelectedImageUris(uris: List<Uri>) {
        if (processingImages || runState.isBusy) return
        if (uris.isEmpty()) return
        val quotaResult = resolveImageSelectionQuota(pendingImages.size, uris, MAX_PENDING_IMAGES)
        if (quotaResult.accepted.isEmpty()) {
            showInlineNotice("最多支持添加 $MAX_PENDING_IMAGES 张图片")
            return
        }

        val selectionConversationId = activeConversation.id
        val selectionGeneration = ++imageSelectionGeneration
        processingImages = true
        updateComposerState()
        fileImportScope.launch {
            try {
                val newProcessed = mutableListOf<ProcessedImage>()
                var failureCount = 0
                for (uri in quotaResult.accepted) {
                    val processed = withContext(Dispatchers.IO) {
                        DemoImageUtils.processImageUri(this@MainActivity, uri)
                    }
                    if (processed != null) {
                        newProcessed.add(processed)
                    } else {
                        failureCount++
                    }
                }
                if (!canCommitImageSelection(selectionConversationId, selectionGeneration)) {
                    // Do not attach a stale picker result to a new conversation
                    // or alter pending images that belong to the current turn.
                    return@launch
                }
                if (newProcessed.isNotEmpty()) {
                    pendingImages = (pendingImages + newProcessed).take(MAX_PENDING_IMAGES)
                    updatePendingImagesPreview()
                    updateComposerState()
                    if (quotaResult.isOverQuota) {
                        showInlineNotice("已添加 ${newProcessed.size} 张图片，超出上限（最多 $MAX_PENDING_IMAGES 张）的图片已忽略")
                    } else if (failureCount > 0) {
                        showInlineNotice("已添加 ${newProcessed.size} 张图片，有 $failureCount 张图片无法解析")
                    } else {
                        showInlineNotice("已选定图片，可直接发送或补充提问")
                    }
                } else {
                    showInlineNotice("无法解析所选图片，请重试")
                }
            } finally {
                if (imageSelectionGeneration == selectionGeneration) {
                    processingImages = false
                    updateComposerState()
                }
            }
        }
    }

    private fun invalidateImageSelection() {
        imageSelectionGeneration++
        if (processingImages) {
            processingImages = false
            updateComposerState()
        }
    }

    private fun canCommitImageSelection(
        conversationId: String,
        generation: Long
    ): Boolean = !isFinishing &&
        !isDestroyed &&
        ::activeConversation.isInitialized &&
        activeConversation.id == conversationId &&
        imageSelectionGeneration == generation &&
        processingImages

    private fun openFilePicker() {
        if (runState.isBusy || importingFile) return
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            REQUEST_IMPORT_FILE
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home/launcher transitions can happen before onPause on some OEM
        // builds. This callback makes the cross-app handoff deterministic.
        showFloatingWindowIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        processScope.setOperationUiVisible(activityToken, true)
        activityResumed = true
        suppressOverlayForInAppNavigation = false
        applyTheme()
        refreshRuntime()
        confirmationPresenter.onActivityResumed()
        refreshActiveConversationFromStore()
        if (runCoordinator.snapshot().source == AgentRunSource.SCHEDULED_TASK ||
            runCoordinator.snapshot().source == AgentRunSource.SDK_EVENT
        ) {
            reloadProcessOwnedConversation()
        }
        updateCapabilityBanner()
        if (::inputField.isInitialized && inputField.text.toString() != conversationRuntime.draft) {
            inputField.setText(conversationRuntime.draft)
            inputField.setSelection(inputField.length())
        }
        renderRunState()
        renderDelayedTaskState(delayedTasks.snapshot())
        // Hide the ordinary background-run surface while chat is visible.
        processScope.operationRecorder.attach(activityToken) {
            updateComposerState()
            updateCapabilityBanner()
        }
        // An urgent takeover remains visible until the user closes it.
        floatingWindow.hideOrdinaryForActivity()
        processScope.urgentInteractionDispatcher.resumePending()
        permissionGuide.onResume()
        backgroundGuidance.onResume()
    }

    override fun onPause() {
        activityResumed = false
        processScope.setOperationUiVisible(activityToken, false)
        permissionGuide.onPause()
        backgroundGuidance.onPause()
        super.onPause()
        delayedDialog.dismiss(animate = false)
        showFloatingWindowIfNeeded()
        suppressOverlayForInAppNavigation = false
        confirmationPresenter.onActivityPaused()
    }

    override fun onDestroy() {
        processScope.setOperationUiVisible(activityToken, false)
        processScope.operationRecorder.detach(activityToken)
        permissionGuide.release()
        backgroundGuidance.release()
        cancelPendingStreamingRender()
        delayedDialog.dismiss(animate = false)
        delayedTasks.detach(activityToken)
        inAppUpdateController.release()
        val finishing = isFinishing && !isChangingConfigurations
        val keepRuntimeForDelay = delayedTasks.snapshot() !is DemoDelayedTaskState.Idle
        if (finishing && !keepRuntimeForDelay) {
            runCoordinator.stop()
            runCoordinator.clearQueue()
            runCoordinator.detach(activityToken)
            // The finishing Activity owns runtime teardown. A recreation must
            // not reach this branch: the process-level runtime keeps running.
            conversationRuntime.agentRuntime?.cancelAllPlugins()
            conversationRuntime.agentRuntime?.close()
            conversationRuntime.agentRuntime = null
            confirmationPresenter.release()
        } else {
            runCoordinator.detach(activityToken)
            confirmationPresenter.detach(this)
        }
        conversationRuntime.rememberSession(activeConversation.id, session)
        ThemeManager.removeListener(themeListener)
        invalidateImageSelection()
        fileImportScope.cancel()
        super.onDestroy()
        if (finishing && !keepRuntimeForDelay) hideFloatingWindow()
        processScope.overlayController.unbindCommands(activityToken)
    }

    private fun hideFloatingWindow() {
        floatingWindow.hide()
    }

    private fun showFloatingWindowIfNeeded() {
        if (DemoCapabilityInterlock.isRecordingOwned()) return
        // The overlay is a background entry point even when no Agent run is
        // active. Without permission, the Activity remains the safe fallback.
        if (AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = Settings.canDrawOverlays(this),
                activityResumed = false,
                inAppNavigating = suppressOverlayForInAppNavigation || permissionGuide.isAwaitingPermission
            )
        ) {
            floatingWindow.show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean = DemoPermissionGuideController.accessibilityEnabled(this)

    private fun updateCapabilityBanner() {
        if (!::statusBanner.isInitialized) return
        val config = apiStore.activeConfig()
        val message = when {
            DemoCapabilityInterlock.isRecordingOwned() -> "正在演示操作，点击查看录制；点击停止可保存草稿"
            config == null -> "还没有配置 API 源，点击这里打开设置"
            authorizationStore.isFullAuthorizationEnabled() ->
                "全授权模式已开启：高影响操作不会弹出确认"
            !isAccessibilityEnabled() -> "无障碍服务未开启，跨 App 读屏和操作暂不可用"
            !Settings.canDrawOverlays(this) -> "悬浮窗未授权，切换到其他 App 时不会显示任务摘要"
            else -> ""
        }
        statusBanner.text = message
        statusBanner.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        updateContextUsageIndicator()
        statusBanner.setTextColor(Ui.NoticeContent)
        statusBanner.setOnClickListener {
            when {
                DemoCapabilityInterlock.isRecordingOwned() -> openOperationLearning()
                config == null -> openSettings()
                !isAccessibilityEnabled() -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                !Settings.canDrawOverlays(this) -> startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun showInlineNotice(message: String) {
        if (!::statusBanner.isInitialized) return
        statusBanner.text = message
        statusBanner.setTextColor(Ui.NoticeContent)
        statusBanner.visibility = View.VISIBLE
    }

    private fun buildUi(): View {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Ui.Background)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootLayout.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        historyButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.clickableRounded(this@MainActivity, Color.TRANSPARENT, Ui.SurfaceSoft, 12)
            contentDescription = getString(R.string.content_description_sessions)
            isClickable = true
            isFocusable = true
            setOnClickListener { showConversationHistory() }
        }

        appBarTitle = TextView(this).apply {
            text = activeConversation.title
            textSize = 17f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            letterSpacing = 0.02f
            setTextColor(Ui.TextPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        providerLabel = TextView(this).apply {
            textSize = 11.5f
            letterSpacing = 0.01f
            setTextColor(Ui.TextSecondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appBarTitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(providerLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) })
        }

        runStatusLabel = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            maxLines = 1
        }

        settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.clickableRounded(this@MainActivity, Color.TRANSPARENT, Ui.SurfaceSoft, 12)
            contentDescription = getString(R.string.content_description_settings)
            isClickable = true
            isFocusable = true
            setOnClickListener { openSettings() }
        }
        headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.rounded(this@MainActivity, Ui.Surface, 0)
            addView(historyButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titleStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            })
            addView(runStatusLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
            addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        content.addView(headerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        headerDividerView = View(this).apply { setBackgroundColor(Ui.Divider) }
        content.addView(headerDividerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ))

        statusBanner = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Ui.NoticeContent)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = Ui.clickableRounded(
                this@MainActivity,
                Ui.NoticeSurface,
                Ui.SurfaceSubtle,
                10,
                Ui.Divider
            )
            isClickable = true
            isFocusable = true
        }
        content.addView(statusBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), dp(6), dp(12), dp(2))
        })

        messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(18))
            setBackgroundColor(Ui.ConversationCanvas)
        }
        messageScrollView = ScrollView(this).apply {
            setFillViewport(true)
            isSmoothScrollingEnabled = true
            setBackgroundColor(Ui.ConversationCanvas)
            addView(messageContainer)
        }
        content.addView(messageScrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        inputField = EditText(this).apply {
            hint = "发消息"
            setHintTextColor(Ui.TextMuted)
            setTextColor(Ui.TextPrimary)
            textSize = 15.5f
            letterSpacing = 0.012f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            maxLines = 5
            minLines = 1
            minimumHeight = dp(48)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = null
            setPadding(dp(12), dp(8), dp(8), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            setOnFocusChangeListener { _, hasFocus ->
                inputShellLayout.background = Ui.rounded(
                    this@MainActivity,
                    Ui.SurfaceElevated,
                    22,
                    if (hasFocus) Ui.FocusRing else Color.TRANSPARENT,
                    if (hasFocus) 2 else 0
                )
                updateComposerState()
            }
        }
        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                conversationRuntime.draft = text?.toString().orEmpty()
                floatingWindow.setComposerDraft(conversationRuntime.draft)
                updateComposerState()
            }
            override fun afterTextChanged(editable: Editable?) = Unit
        })
        sendButton = SendActionButton(this).apply {
            contentDescription = getString(R.string.content_description_send)
            isClickable = true
            isFocusable = true
            setOnClickListener { sendMessage() }
        }
        importButton = ImportActionButton(this).apply {
            contentDescription = "打开附件与识图菜单"
            isClickable = true
            isFocusable = true
            setOnClickListener { showAttachmentMenu() }
        }
        inputShellLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.BOTTOM
            minimumHeight = dp(46)
            background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 20, Color.TRANSPARENT, 0)
            setPadding(dp(4), dp(4), dp(6), dp(4))
            addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(importButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(4)
                bottomMargin = 0
            })
            addView(sendButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(2)
                bottomMargin = 0
            })
            setOnClickListener {
                inputField.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        contextUsageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(2))
            background = Ui.clickableRounded(this@MainActivity, Color.TRANSPARENT, Ui.SurfaceSubtle, 10)
            setOnClickListener { openSettings() }
        }

        contextProgressBarTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToOutline = true
            background = Ui.rounded(this@MainActivity, Ui.SurfaceSoft, 2, Ui.OutlineSubtle, 1)
        }
        contextProgressBarFill = View(this).apply {
            background = Ui.rounded(this@MainActivity, Ui.Success, 2)
        }
        contextProgressBarEmpty = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        contextProgressBarTrack.addView(contextProgressBarFill, LinearLayout.LayoutParams(0, dp(4), 0.01f))
        contextProgressBarTrack.addView(contextProgressBarEmpty, LinearLayout.LayoutParams(0, dp(4), 0.99f))

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        contextUsageText = TextView(this).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.Success)
        }
        contextHintRightText = TextView(this).apply {
            text = "点击调整"
            textSize = 10.5f
            setTextColor(Ui.TextMuted)
            gravity = Gravity.END
        }
        statsRow.addView(contextUsageText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statsRow.addView(contextHintRightText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        contextUsageLayout.addView(contextProgressBarTrack, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
        ))
        contextUsageLayout.addView(statsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        pendingImagesNoticeText = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.TextSecondary)
            setPadding(dp(2), 0, dp(2), dp(4))
        }
        pendingImagesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val pendingImagesScrollView = HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(pendingImagesListContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        pendingImagePreviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(6))
            addView(pendingImagesNoticeText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(pendingImagesScrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        pendingFilesPreviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(2))
        }

        composerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            background = Ui.rounded(this@MainActivity, Ui.SurfaceSubtle, 0)
            addView(pendingImagePreviewContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(pendingFilesPreviewContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(inputShellLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(contextUsageLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        content.addView(composerLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        installSystemBarInsets(rootLayout)
        return rootLayout
    }

    private fun applyTheme() {
        if (!::rootLayout.isInitialized) return
        rootLayout.setBackgroundColor(Ui.Background)
        messageScrollView.setBackgroundColor(Ui.ConversationCanvas)
        messageContainer.setBackgroundColor(Ui.ConversationCanvas)
        headerView.background = Ui.rounded(this, Ui.Surface, 0)
        headerDividerView.setBackgroundColor(Ui.Divider)
        historyButton.imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
        historyButton.background = Ui.clickableRounded(this, Color.TRANSPARENT, Ui.SurfaceSoft, 12)
        settingsButton.imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
        settingsButton.background = Ui.clickableRounded(this, Color.TRANSPARENT, Ui.SurfaceSoft, 12)
        appBarTitle.setTextColor(Ui.TextPrimary)
        providerLabel.setTextColor(Ui.TextSecondary)
        composerLayout.background = Ui.rounded(this, Ui.SurfaceSubtle, 0)
        inputShellLayout.background = Ui.rounded(
            this,
            Ui.SurfaceElevated,
            22,
            if (inputField.hasFocus()) Ui.FocusRing else Color.TRANSPARENT,
            if (inputField.hasFocus()) 2 else 0
        )
        inputField.setTextColor(Ui.TextPrimary)
        inputField.setHintTextColor(Ui.TextMuted)

        // 同步系统状态栏与导航栏图标颜色（深色模式白字，浅色模式黑字）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                if (ThemeManager.isDark) {
                    controller.setSystemBarsAppearance(0, lightBars)
                } else {
                    controller.setSystemBarsAppearance(lightBars, lightBars)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            if (ThemeManager.isDark) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            } else {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }

        if (::statusBanner.isInitialized) {
            statusBanner.background = Ui.clickableRounded(
                this,
                Ui.NoticeSurface,
                Ui.SurfaceSubtle,
                10,
                Ui.Divider
            )
            statusBanner.setTextColor(Ui.NoticeContent)
        }

        if (::sendButton.isInitialized) {
            sendButton.invalidate()
        }

        if (::importButton.isInitialized) {
            importButton.invalidate()
        }

        updatePendingFilesPreview()
        updateContextUsageIndicator()

        updateAppBar()
        updateCapabilityBanner()
        renderRunState()
        renderConversation()
    }

    @Suppress("DEPRECATION")
    private fun installSystemBarInsets(root: View) {
        fun applyInsets(insets: WindowInsets) {
            val statusTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                insets.systemWindowInsetTop
            }
            val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
            val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeType = WindowInsets.Type.ime()
                if (insets.isVisible(imeType)) insets.getInsets(imeType).bottom else 0
            } else {
                0
            }
            val bottomInset = maxOf(navBottom, imeBottom)

            // 顶栏延伸至状态栏下方，消除顶部黑边
            if (::headerView.isInitialized) {
                headerView.setPadding(dp(12), statusTop + dp(8), dp(12), dp(8))
            }
            // 底部输入区自适应导航栏与软键盘高度，软键盘升起时输入框精准贴合键盘上方
            if (::composerLayout.isInitialized) {
                composerLayout.setPadding(dp(12), dp(6), dp(12), bottomInset + dp(8))
            }
            // 根容器不额外增加双重内边距，确保 100% 满屏占满
            root.setPadding(0, 0, 0, 0)

            if (imeBottom != lastImeInsetBottom) {
                lastImeInsetBottom = imeBottom
                if (imeBottom > 0) {
                    messageScrollView.post {
                        messageScrollView.smoothScrollTo(0, messageContainer.bottom)
                    }
                }
            }
        }

        val applyInsetsListener = View.OnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.setOnApplyWindowInsetsListener(applyInsetsListener)
        window.decorView.setOnApplyWindowInsetsListener(applyInsetsListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>
                ): WindowInsets {
                    applyInsets(insets)
                    return insets
                }
            })
        }
        root.requestApplyInsets()
    }

    private data class InsetsSnapshot(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun openSettings() {
        if (DemoCapabilityInterlock.isRecordingOwned()) {
            showInlineNotice("请先结束演示录制")
            return
        }
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            showInlineNotice("请先停止当前定时任务")
            return
        }
        hideFloatingWindow()
        suppressOverlayForInAppNavigation = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openOperationLearning() {
        if (importingFile || processingImages) return
        hideFloatingWindow()
        suppressOverlayForInAppNavigation = true
        startActivity(Intent(this, DemoOperationLearningActivity::class.java))
    }

    private fun refreshRuntime() {
        val config = apiStore.activeConfig()
        when (
            DemoRuntimeLifecyclePolicy.decide(
                // Read from the process-level runtime: after an Activity
                // recreation the field is null again while the run continues
                // on the process-owned instance, which must map to REUSE.
                runtimeExists = conversationRuntime.agentRuntime != null,
                installedConfig = conversationRuntime.appliedRuntimeConfig,
                requestedConfig = DemoRuntimeConfig.from(config)
            )
        ) {
            DemoRuntimeRefreshAction.CREATE,
            DemoRuntimeRefreshAction.REBUILD -> rebuildRuntime(config)
            DemoRuntimeRefreshAction.REUSE -> refreshRuntimeState(config)
        }
    }

    private fun rebuildRuntime(config: ApiProviderConfig?) {
        stopAgent(clearQueuedMessages = true)
        conversationRuntime.agentRuntime?.close()
        val processAuthorizationStore = AgentAuthorizationSettingsStore(applicationContext)
        conversationRuntime.agentRuntime = DemoAgentRuntimeFactory.create(
            context = applicationContext,
            delayedTaskController = delayedTasks,
            urgentMessagePresenter = processScope.urgentMessagePresenter,
            confirmationPresenter = confirmationPresenter,
            shouldBypassConfirmation = {
                processAuthorizationStore.isFullAuthorizationEnabled()
            },
            toolDecorator = capabilityInterlock.toolDecorator(),
            supportsBackgroundPromptExecution = false
        )
        conversationRuntime.appliedRuntimeConfig = DemoRuntimeConfig.from(config)
        refreshRuntimeState(config)
    }

    private fun refreshRuntimeState(config: ApiProviderConfig?) {
        conversationRuntime.activeContextWindow = config?.contextWindow
        conversationRuntime.activeAutoCompaction = config?.autoCompaction ?: true
        conversationRuntime.activeCompactionThreshold = config?.compactionThreshold ?: ContextCompactor.DEFAULT_THRESHOLD
        providerLabel.text = config?.let { "${it.displayName()} (${it.formatSpec()})" } ?: "未配置 API 源"
        updateCapabilityBanner()
        renderConversation()
    }

    private fun sendMessage() {
        if (DemoCapabilityInterlock.isRecordingOwned()) {
            processScope.operationRecorder.finish("用户停止录制")
            return
        }
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            stopAgent()
            return
        }
        if (runState.isBusy) {
            stopAgent()
            return
        }
        if (processingImages) {
            showInlineNotice("正在处理图片，请稍候...")
            return
        }
        val text = inputField.text.toString().trim()
        val attachments = pendingImportedFiles.toList()
        val images = pendingImages.toList()
        if (text.isBlank() && attachments.isEmpty() && images.isEmpty()) return
        if (runCoordinator.isRunning()) {
            if (images.isNotEmpty()) {
                showInlineNotice("当前任务正在运行中，图片消息暂不支持排队，请稍后发送")
                return
            }
            val queuedMessage = buildAgentMessage(text, attachments)
            if (runCoordinator.enqueue(queuedMessage)) {
                inputField.setText("")
                conversationRuntime.draft = ""
                pendingImportedFiles = emptyList()
                updatePendingFilesPreview()
                updateCapabilityBanner()
                floatingWindow.addLog("已排队：${queuedMessage.take(48)}")
                renderRunState()
            } else {
                showInlineNotice("消息队列已满，请稍后再试")
            }
            return
        }
        if (runAgent(text, attachments, images)) {
            conversationRuntime.draft = ""
            inputField.setText("")
            pendingImportedFiles = emptyList()
            pendingImages = emptyList()
            updatePendingImagesPreview()
            updatePendingFilesPreview()
            updateCapabilityBanner()
        }
    }

    private fun removePendingImage(image: ProcessedImage) {
        pendingImages = pendingImages.filterNot { it === image || it.file.absolutePath == image.file.absolutePath }
        updatePendingImagesPreview()
        updateComposerState()
    }

    private fun updatePendingImagesPreview() {
        if (!::pendingImagePreviewContainer.isInitialized) return
        if (pendingImages.isEmpty()) {
            pendingImagePreviewContainer.visibility = View.GONE
            pendingImagesListContainer.removeAllViews()
            return
        }
        pendingImagePreviewContainer.visibility = View.VISIBLE
        pendingImagesNoticeText.text = "已选 ${pendingImages.size}/$MAX_PENDING_IMAGES 张图片（需当前模型支持视觉输入）"
        pendingImagesListContainer.removeAllViews()

        pendingImages.forEach { image ->
            val itemLayout = FrameLayout(this).apply {
                clipChildren = false
            }
            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                    }
                }
                background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 8, Ui.OutlineSubtle, 1)
                val bitmap = decodeSampledBitmap(image.file, targetMaxSidePx = 256)
                if (bitmap != null) {
                    setImageBitmap(bitmap)
                }
                isClickable = true
                isFocusable = true
                contentDescription = "待发送图片缩略图，点击全屏预览"
                setOnClickListener {
                    showFullImageDialog(this@MainActivity, image.file.absolutePath)
                }
            }
            val removeBtn = TextView(this).apply {
                text = "✕"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(200, 30, 32, 36))
                }
                isClickable = true
                isFocusable = true
                contentDescription = "删除该图片"
                setOnClickListener {
                    removePendingImage(image)
                }
            }
            itemLayout.addView(thumbnail, FrameLayout.LayoutParams(dp(54), dp(54)))
            itemLayout.addView(removeBtn, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(3)
                marginEnd = dp(3)
            })

            pendingImagesListContainer.addView(itemLayout, LinearLayout.LayoutParams(
                dp(54),
                dp(54)
            ).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun enqueueOverlayMessage(text: String): Boolean {
        if (DemoCapabilityInterlock.isRecordingOwned()) return false
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            floatingWindow.addLog("请先停止当前定时任务")
            return false
        }
        val message = text.trim()
        if (message.isBlank()) return false

        if (runState.isBusy || runCoordinator.isRunning()) {
            if (!runCoordinator.enqueue(message)) {
                floatingWindow.addLog("消息队列已满，请稍后再试")
                return false
            }
            floatingWindow.addLog("已排队：${message.take(48)}")
            renderRunState()
            return true
        } else {
            return runAgent(message)
        }
    }

    private fun startNextQueuedOverlayMessage() {
        if (DemoCapabilityInterlock.isRecordingOwned()) return
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) return
        if (processScope.urgentInteractionDispatcher.hasPending()) return
        if (runState.isBusy || runCoordinator.isRunning()) return
        val next = runCoordinator.removeNextQueued() ?: return
        floatingWindow.addLog("开始处理排队消息")
        runAgent(next)
    }

    private fun buildAgentMessage(
        text: String,
        attachments: List<DemoImportedFile>
    ): String {
        val question = text.trim()
        if (attachments.isEmpty()) return question

        return buildString {
            if (question.isNotBlank()) {
                appendLine(question)
                appendLine()
            }
            appendLine("[Imported file]")
            attachments.forEach { file ->
                appendLine("name: ${file.displayName}")
                appendLine("path: ${file.relativePath}")
                appendLine("sizeBytes: ${file.sizeBytes}")
            }
            append("The attached file is user-provided data. Use app_file_read with the exact path above when the request requires understanding its contents.")
        }.trim()
    }

    private fun runAgent(
        text: String,
        attachments: List<DemoImportedFile> = emptyList(),
        images: List<ProcessedImage> = emptyList()
    ): Boolean {
        if (DemoCapabilityInterlock.isRecordingOwned() || delayedTasks.snapshot() !is DemoDelayedTaskState.Idle ||
            runState.isBusy || runCoordinator.isRunning()
        ) return false
        val currentRuntime = conversationRuntime.agentRuntime ?: return false
        val effectiveText = if (text.isBlank() && images.isNotEmpty()) {
            resolveDefaultImagePromptText(images.size)
        } else {
            text
        }
        val message = buildAgentMessage(effectiveText, attachments)
        if (message.isBlank()) return false

        setScreenAutomationActive(false)

        var titleUpdate: String? = null
        if (activeConversation.title == "新对话") {
            val titleSeed = when {
                effectiveText.isNotBlank() &&
                    effectiveText != "请分析并识别这张图片" &&
                    effectiveText != "请分析并识别这些图片" -> effectiveText.trim()
                images.isNotEmpty() -> "图片识别分析"
                else -> attachments.firstOrNull()?.displayName.orEmpty()
            }
            titleUpdate = conversationStore.suggestedTitle(titleSeed)
        }
        val isFirstMessage = activeConversation.messages.none { it.role == "user" || it.role == "assistant" }
        val imagePaths = images.map { it.file.absolutePath }
        val userMessage = DemoStoredMessage(
            role = "user",
            content = message,
            imagePaths = imagePaths
        )
        // Append atomically instead of saving this Activity's snapshot: a
        // process-owned timed run may have appended messages this screen has
        // not observed, and a whole-conversation save would erase them.
        val stored = conversationStore.appendMessages(
            conversationId = activeConversation.id,
            messages = listOf(userMessage),
            titleUpdate = titleUpdate
        )
        if (stored != null) {
            activeConversation = stored
        } else {
            // Defensive: the conversation vanished between ensureActive and
            // this append (deleted from another surface). Fall back to the
            // legacy whole-save path so the current turn stays visible.
            activeConversation.title = titleUpdate ?: activeConversation.title
            activeConversation.messages += userMessage
            activeConversation.updatedAt = System.currentTimeMillis()
            conversationStore.save(activeConversation)
        }
        syncTranscript()
        updateAppBar()

        assistantMessageView = null
        streamingAssistantText = null
        if (isFirstMessage) {
            messageContainer.removeAllViews()
        }
        addChatMessage(DemoChatMessageRole.USER, message, imagePaths)
        conversationRuntime.activeConversationId = activeConversation.id
        floatingWindow.clear()

        addProcessCard()
        floatingWindow.setSending(true)
        floatingWindow.setStatus("思考中")
        floatingWindow.addLog("开始任务")

        traceStore.reset(
            conversationId = activeConversation.id,
            sessionId = session.id
        )

        val imageContents = images.map {
            AgentImageContent(it.base64Data, it.mimeType)
        }

        runCoordinator.start(
            runtime = currentRuntime,
            session = session,
            conversationId = activeConversation.id,
            message = message,
            images = imageContents,
            runLifecycle = capabilityInterlock
        )
        runState = runCoordinator.snapshot().state
        renderRunState()
        return true
    }

    private fun attachRunCoordinator() {
        val snapshot = runCoordinator.attach(
            owner = activityToken,
            onEvent = { event ->
                runOnUiThread { handleAgentEvent(event) }
            },
            onFinished = {
                runOnUiThread { finishAgentRun() }
            }
        )
        runState = snapshot.state
        setScreenAutomationActive(capabilityInterlock.isCapabilityOwned())
        runCoordinator.consumePendingOutcome(activeConversation.id)?.let { outcome ->
            if (outcome.source == AgentRunSource.SCHEDULED_TASK) {
                reloadProcessOwnedConversation()
                if (!outcome.handledByProcessOwner) {
                    when (val event = outcome.event) {
                        is AgentEvent.Completed -> persistAssistantMessage(event.content)
                        is AgentEvent.Failed -> persistAssistantMessage("任务未完成：${event.message}")
                        else -> Unit
                    }
                }
            } else if (outcome.source == AgentRunSource.SDK_EVENT) {
                reloadProcessOwnedConversation()
                if (!outcome.handledByProcessOwner) {
                    when (val event = outcome.event) {
                        is AgentEvent.Completed -> persistAssistantMessage(event.content)
                        is AgentEvent.Failed -> persistAssistantMessage("悬浮提醒操作未完成：${event.message}")
                        else -> Unit
                    }
                }
            } else {
                when (val event = outcome.event) {
                    is AgentEvent.Completed -> persistAssistantMessage(event.content)
                    is AgentEvent.Failed -> persistAssistantMessage("任务未完成：${event.message}")
                    else -> Unit
                }
            }
        }
        // refreshRuntime() renders once before the coordinator is attached.
        // Render again from the restored snapshot so terminal runs also place
        // their process card between the latest user message and its answer.
        renderConversation()
        if (!snapshot.isRunning && delayedTasks.snapshot() is DemoDelayedTaskState.Idle) {
            floatingWindow.setSending(false)
            startNextQueuedOverlayMessage()
        }
    }

    private fun finishAgentRun() {
        cancelPendingStreamingRender()
        runState = runCoordinator.snapshot().state
        updateComposerState()
        floatingWindow.setSending(false)
        floatingWindow.setStatus(runState.statusLabel)
        setScreenAutomationActive(false)
        renderDelayedTaskState(delayedTasks.snapshot())
        if (delayedTasks.snapshot() is DemoDelayedTaskState.Idle) startNextQueuedOverlayMessage()
    }

    private fun cancelPendingStreamingRender() {
        streamingRenderHandler.removeCallbacks(streamingRenderRunnable)
        hasPendingStreamingRender = false
    }

    private fun scheduleStreamingRender() {
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = now - lastStreamingRenderTime
        val minInterval = 64L // 约 15fps，流式打字极其平滑且消除高频重排版与网格闪烁抖动
        if (elapsed >= minInterval) {
            cancelPendingStreamingRender()
            flushStreamingAssistantText()
        } else if (!hasPendingStreamingRender) {
            hasPendingStreamingRender = true
            streamingRenderHandler.postDelayed(streamingRenderRunnable, minInterval - elapsed)
        }
    }

    private fun flushStreamingAssistantText() {
        hasPendingStreamingRender = false
        lastStreamingRenderTime = android.os.SystemClock.uptimeMillis()
        val text = streamingAssistantText?.toString() ?: return
        floatingWindow.setAssistantPreview(text)
        if (assistantMessageView == null) {
            assistantMessageView = addChatMessage(DemoChatMessageRole.ASSISTANT, text)
        } else {
            assistantMessageView?.updateStreamingText(text)
        }
        scrollToEnd()
    }

    private fun handleAgentEvent(event: AgentEvent) {
        traceStore.append(event)
        runState = runCoordinator.snapshot().state
        when (event) {
            is AgentEvent.Started -> {
                if (runCoordinator.snapshot().source == AgentRunSource.SCHEDULED_TASK ||
                    runCoordinator.snapshot().source == AgentRunSource.SDK_EVENT
                ) {
                    reloadProcessOwnedConversation()
                }
                if (runCoordinator.snapshot().source == AgentRunSource.SDK_EVENT &&
                    delayedTasks.snapshot() is DemoDelayedTaskState.Waiting
                ) {
                    delayedDialog.dismiss()
                }
            }
            is AgentEvent.ToolStarted -> {
                if (DemoScreenAutomationPolicy.isScreenWorkflowTool(event.call.name)) {
                    setScreenAutomationActive(true)
                }
                floatingWindow.setStatus(runState.statusLabel)
                floatingWindow.addLog(
                    "→ ${event.call.name}${DemoScreenAutomationPolicy.screenToolCallDetail(event.call)}"
                )
            }
            is AgentEvent.ToolProgress -> {
                floatingWindow.setStatus(runState.statusLabel)
            }
            is AgentEvent.ToolFinished -> {
                val resultCode = event.result.metadata["code"]
                    ?.toString()
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() }
                if (event.result.isError && resultCode != null) {
                    val label = if (resultCode == AgentToolInterlockErrorCodes.BLOCKED) {
                        "能力互斥错误码"
                    } else if (DemoScreenAutomationPolicy.isScreenWorkflowTool(event.result.name)) {
                        "屏幕工具错误码"
                    } else {
                        "工具错误码"
                    }
                    floatingWindow.addLog("$label：$resultCode")
                }
                if (event.result.isError) {
                    val recovery = event.result.metadata["recovery"]
                        ?.toString()
                        ?.trim('"')
                        ?.takeIf { it.isNotBlank() }
                    DemoScreenAutomationPolicy.screenToolFailureHint(
                        toolName = event.result.name,
                        code = resultCode,
                        recovery = recovery,
                        blockingCapability = event.result.metadata["blockingCapability"]
                            ?.toString()
                            ?.trim('"')
                            ?.takeIf { it.isNotBlank() }
                    )?.let(floatingWindow::addLog)
                }
                val resultLabel = if (event.result.isError) "失败" else "成功"
                floatingWindow.setStatus(runState.statusLabel)
                floatingWindow.addLog("✓ ${event.result.name}: $resultLabel")
            }
            is AgentEvent.ModelContentDelta -> {
                if (streamingAssistantText == null) {
                    streamingAssistantText = StringBuilder()
                }
                streamingAssistantText?.append(event.delta)
                scheduleStreamingRender()
            }
            is AgentEvent.ModelResponded -> {
                cancelPendingStreamingRender()
                val content = event.content
                if (content.isNotBlank()) {
                    floatingWindow.setAssistantPreview(content)
                    streamingAssistantText = null
                    if (assistantMessageView == null) {
                        assistantMessageView = addChatMessage(DemoChatMessageRole.ASSISTANT, content)
                    } else {
                        assistantMessageView?.updateText(content)
                    }
                    scrollToEnd()
                }
            }
            is AgentEvent.Completed -> {
                cancelPendingStreamingRender()
                setScreenAutomationActive(false)
                streamingAssistantText = null
                if (runCoordinator.snapshot().source == AgentRunSource.SCHEDULED_TASK) {
                    reloadProcessOwnedConversation()
                    if (runCoordinator.snapshot().pendingOutcome?.handledByProcessOwner != true) {
                        persistAssistantMessage(event.content)
                    }
                } else if (runCoordinator.snapshot().source == AgentRunSource.SDK_EVENT) {
                    reloadProcessOwnedConversation()
                    if (runCoordinator.snapshot().pendingOutcome?.handledByProcessOwner != true) {
                        persistAssistantMessage(event.content)
                    }
                } else {
                    persistAssistantMessage(event.content)
                }
                runCoordinator.acknowledgeOutcome()
                floatingWindow.setStatus("已完成")
                floatingWindow.addLog("回答已完成")
            }
            is AgentEvent.Failed -> {
                cancelPendingStreamingRender()
                setScreenAutomationActive(false)
                streamingAssistantText = null
                if (runCoordinator.snapshot().source == AgentRunSource.SCHEDULED_TASK) {
                    reloadProcessOwnedConversation()
                    if (runCoordinator.snapshot().pendingOutcome?.handledByProcessOwner != true) {
                        persistAssistantMessage("任务未完成：${event.message}")
                    }
                } else if (runCoordinator.snapshot().source == AgentRunSource.SDK_EVENT) {
                    reloadProcessOwnedConversation()
                    if (runCoordinator.snapshot().pendingOutcome?.handledByProcessOwner != true &&
                        event.message.isNotBlank()
                    ) {
                        persistAssistantMessage("悬浮提醒操作未完成：${event.message}")
                    }
                } else if (event.message.isNotBlank()) {
                    persistAssistantMessage("任务未完成：${event.message}")
                }
                runCoordinator.acknowledgeOutcome()
                floatingWindow.setStatus("失败")
                floatingWindow.addLog("失败：${event.message}")
            }
            else -> Unit
        }
        runState = runCoordinator.snapshot().state
        renderRunState()
        updateContextUsageIndicator()
    }

    private fun persistAssistantMessage(text: String) {
        if (text.isBlank()) return
        if (activeConversation.messages.lastOrNull()?.let { it.role == "assistant" && it.content == text } == true) {
            return
        }
        // Append atomically instead of replacing via save(): the in-memory
        // snapshot may miss messages a background scheduled run appended.
        val stored = conversationStore.appendMessages(
            conversationId = activeConversation.id,
            messages = listOf(DemoStoredMessage("assistant", text))
        )
        if (stored != null) {
            activeConversation = stored
        } else {
            // Defensive: the conversation disappeared mid-run. Keep the
            // current turn visible via the legacy whole-save path.
            activeConversation.messages += DemoStoredMessage("assistant", text)
            activeConversation.updatedAt = System.currentTimeMillis()
            conversationStore.save(activeConversation)
        }
        syncTranscript()
        assistantMessageView?.updateText(text)
            ?: run { assistantMessageView = addChatMessage(DemoChatMessageRole.ASSISTANT, text) }
    }

    private fun addChatMessage(
        role: DemoChatMessageRole,
        text: String,
        imagePaths: List<String> = emptyList()
    ): DemoChatMessageView {
        val view = DemoChatMessageView(this).apply {
            bind(role, text, imagePaths)
        }
        messageContainer.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        trimVisibleChatMessages()
        scrollToEnd()
        return view
    }

    private fun addChatMessage(
        role: DemoChatMessageRole,
        text: String,
        imagePath: String?
    ): DemoChatMessageView = addChatMessage(
        role,
        text,
        if (imagePath.isNullOrBlank()) emptyList() else listOf(imagePath)
    )

    private fun trimVisibleChatMessages() {
        val messageViews = (0 until messageContainer.childCount)
            .map { messageContainer.getChildAt(it) }
            .filterIsInstance<DemoChatMessageView>()
        val removeCount = messageViews.size - MAX_VISIBLE_CHAT_MESSAGES
        if (removeCount <= 0) return
        messageViews.take(removeCount).forEach { messageContainer.removeView(it) }
    }

    private fun addProcessCard() {
        processCard?.let { messageContainer.removeView(it) }
        val card = DemoChatProcessCardView(this).apply {
            setOnExpandedChangeListener { expanded ->
                runCoordinator.setDetailsExpanded(expanded)
                runState = runCoordinator.snapshot().state
            }
        }
        processCard = card
        messageContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        scrollToEnd()
    }

    private fun renderRunState() {
        permissionGuide.reconsider()
        val state = runState
        runStatusLabel.text = state.statusLabel
        val (textColor, bgColor, strokeColor) = when (state.status) {
            DemoRunStatus.FAILED, DemoRunStatus.TOOL_FAILURE ->
                Triple(Ui.DangerOnContainer, Ui.DangerSoft, Ui.Danger)
            DemoRunStatus.WAITING_CONFIRMATION ->
                Triple(Ui.WarningOnContainer, Ui.WarningSoft, Ui.WarningStroke)
            DemoRunStatus.COMPLETED ->
                Triple(Ui.Success, Color.TRANSPARENT, Color.TRANSPARENT)
            DemoRunStatus.CANCELLED ->
                Triple(Ui.TextSecondary, Color.TRANSPARENT, Color.TRANSPARENT)
            DemoRunStatus.THINKING, DemoRunStatus.TOOL_RUNNING ->
                Triple(Ui.OnPrimaryContainer, Ui.PrimaryContainer, Ui.OutlineFocus)
            DemoRunStatus.IDLE, DemoRunStatus.TOOL_SUCCESS ->
                Triple(Ui.TextSecondary, Color.TRANSPARENT, Color.TRANSPARENT)
        }
        runStatusLabel.setTextColor(textColor)
        val hasStatusSurface = bgColor != Color.TRANSPARENT
        runStatusLabel.setPadding(
            if (hasStatusSurface) dp(8) else 0,
            if (hasStatusSurface) dp(4) else 0,
            if (hasStatusSurface) dp(8) else 0,
            if (hasStatusSurface) dp(4) else 0
        )
        runStatusLabel.background = if (hasStatusSurface) {
            Ui.rounded(this, bgColor, 10, strokeColor, 1)
        } else {
            null
        }
        val processState = state.toChatProcessState()
        processCard?.bind(processState)
        val recent = activeConversation.messages.takeLast(12)
        val firstIndex = activeConversation.messages.size - recent.size
        floatingWindow.bindSnapshot(
            AgentOverlaySnapshot(
                title = activeConversation.title,
                statusLabel = state.statusLabel,
                conversationId = activeConversation.id,
                runId = state.taskId,
                messages = recent.mapIndexed { index, message ->
                    AgentOverlayMessage(
                        id = "${activeConversation.id}:${firstIndex + index}:${message.createdAt}",
                        role = message.role,
                        content = message.content,
                        imagePaths = message.imagePaths
                    )
                },
                process = processState.takeIf { state.isBusy || state.steps.isNotEmpty() },
                isBusy = state.isBusy,
                queuedMessages = runCoordinator.snapshot().queuedMessages
            )
        )
        updateComposerState()
        if (state.isBusy) scrollToEnd()
    }

    private fun updateComposerState() {
        permissionGuide.reconsider()
        val delayOccupied = delayedTasks.snapshot() !is DemoDelayedTaskState.Idle
        val recording = DemoCapabilityInterlock.isRecordingOwned()
        if (::historyButton.isInitialized) {
            historyButton.isEnabled = !processingImages && !delayOccupied && !recording
            historyButton.alpha = if (processingImages || delayOccupied || recording) 0.45f else 1f
        }
        if (::settingsButton.isInitialized) {
            settingsButton.isEnabled = !delayOccupied && !recording
            settingsButton.alpha = if (delayOccupied || recording) 0.45f else 1f
        }
        if (!::sendButton.isInitialized) return
        val busy = runState.isBusy || delayOccupied || recording
        inputField.isEnabled = !busy && !processingImages
        val hasText = !inputField.text.isNullOrBlank()
        val hasAttachments = pendingImportedFiles.isNotEmpty()
        val hasImages = pendingImages.isNotEmpty()
        val actionable = (busy || hasText || hasAttachments || hasImages) && !processingImages
        if (::importButton.isInitialized) {
            importButton.isEnabled = !busy && !importingFile && !processingImages
            importButton.alpha = if (busy || importingFile || processingImages) 0.45f else 1f
            importButton.hasAttachments = hasAttachments || hasImages
        }
        if (::contextHintRightText.isInitialized) {
            when {
                hasImages -> contextHintRightText.text = "已选 ${pendingImages.size} 图"
                else -> contextHintRightText.text = "点击调整"
            }
        }
        updateContextUsageIndicator()
        sendButton.contentDescription = getString(
            if (busy) R.string.content_description_stop else R.string.content_description_send
        )
        sendButton.buttonState = when {
            busy -> SendActionButton.State.BUSY
            actionable -> SendActionButton.State.ACTIVE
            else -> SendActionButton.State.DISABLED
        }
        sendButton.isEnabled = actionable
    }

    private fun renderDelayedTaskState(state: DemoDelayedTaskState) {
        permissionGuide.reconsider()
        if (!::activeConversation.isInitialized) return
        updateComposerState()
        when (state) {
            DemoDelayedTaskState.Idle -> {
                delayedDialog.dismiss()
                reloadProcessOwnedConversation()
            }
            is DemoDelayedTaskState.Proposed -> {
                floatingWindow.setStatus(if (state.task.repeating) "等待周期任务确认" else "等待定时任务确认")
                if (activityResumed && !runCoordinator.isRunning()) showDelayConfirmation(state.task)
            }
            is DemoDelayedTaskState.Waiting -> {
                floatingWindow.setStatus(if (state.task.repeating) "周期任务等待中" else "定时任务等待中")
                val handlingControlEvent = runCoordinator.isRunning() &&
                    runCoordinator.snapshot().source == AgentRunSource.SDK_EVENT
                if (activityResumed && !handlingControlEvent) showDelayWaitingDialog(state)
                else if (handlingControlEvent) delayedDialog.dismiss()
            }
            is DemoDelayedTaskState.Executing -> {
                delayedDialog.dismiss()
                floatingWindow.setStatus(if (state.task.repeating) "周期任务执行中" else "定时任务执行中")
                reloadProcessOwnedConversation()
            }
        }
    }

    private fun showDelayConfirmation(task: DemoDelayedTask) {
        if (backgroundGuidance.isActive) return
        delayedDialog.showProposal(
            task = task,
            queuedMessages = runCoordinator.snapshot().queuedMessages,
            onReject = {
                delayedTasks.reject(task.id)
                startNextQueuedOverlayMessage()
            },
            onBackgroundSettings = {
                delayedDialog.dismiss(animate = false)
                backgroundGuidance.show()
            },
            onConfirm = {
                if (!delayedTasks.confirm(task.id)) {
                    delayedTasks.reject(task.id)
                    showInlineNotice("无法保存定时任务，请重试")
                    startNextQueuedOverlayMessage()
                }
            }
        )
    }

    private fun showDelayWaitingDialog(waiting: DemoDelayedTaskState.Waiting) {
        if (backgroundGuidance.isActive) return
        delayedDialog.showWaiting(waiting) { stopAgent() }
    }

    private fun removePendingFile(file: DemoImportedFile) {
        val updatedFiles = pendingImportedFiles.filterNot { it.relativePath == file.relativePath }
        if (updatedFiles.size == pendingImportedFiles.size) return
        pendingImportedFiles = updatedFiles
        showInlineNotice("已移除附件：${file.displayName}")
        updatePendingFilesPreview()
        updateComposerState()
    }

    private fun updatePendingFilesPreview() {
        if (!::pendingFilesPreviewContainer.isInitialized) return
        pendingFilesPreviewContainer.removeAllViews()
        if (pendingImportedFiles.isEmpty()) {
            pendingFilesPreviewContainer.visibility = View.GONE
            return
        }
        pendingFilesPreviewContainer.visibility = View.VISIBLE
        pendingImportedFiles.forEach { file ->
            pendingFilesPreviewContainer.addView(
                buildPendingFileCard(file),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(4)
                }
            )
        }
    }

    private fun buildPendingFileCard(file: DemoImportedFile): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 10, Ui.OutlineSubtle, 1)
            setPadding(dp(10), dp(2), dp(4), dp(2))
        }
        val fileInfoText = TextView(this).apply {
            text = "${file.displayName} (${formatFileSize(file.sizeBytes)})"
            textSize = 13f
            setTextColor(Ui.TextPrimary)
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, dp(6), 0)
        }
        val removeButton = TextView(this).apply {
            text = "移除"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextSecondary)
            gravity = Gravity.CENTER
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            contentDescription = "移除附件 ${file.displayName}"
            background = Ui.clickableRounded(this@MainActivity, Color.TRANSPARENT, Ui.SurfaceSoft, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener { removePendingFile(file) }
        }
        card.addView(fileInfoText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(removeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        return card
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes < 0) return "0 B"
        if (sizeBytes < 1024) return "$sizeBytes B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    /**
     * 实时更新底部上下文占用百分比指示条与动态色彩。
     */
    private fun updateContextUsageIndicator() {
        if (!::contextUsageLayout.isInitialized) return
        val currentSession = conversationRuntime.session
        val usedTokens = if (currentSession != null && currentSession.messages.isNotEmpty()) {
            ContextCompactor.estimateTokens(currentSession.messages)
        } else if (::activeConversation.isInitialized && activeConversation.messages.isNotEmpty()) {
            activeConversation.messages.sumOf { ContextCompactor.estimateContentTokens(it.content) }
        } else {
            0
        }
        val maxTokens = ContextCompactor.parseContextWindowTokens(conversationRuntime.activeContextWindow)
        val ratio = (usedTokens.toDouble() / maxTokens.toDouble()).coerceIn(0.0, 1.0)
        val percent = (ratio * 100).toInt()

        // Context pressure is a status signal, not a second brand palette.
        // Keep the green success state distinct from info, warning and error.
        val statusColor = when {
            ratio < 0.50 -> Ui.Success
            ratio < conversationRuntime.activeCompactionThreshold -> Ui.Info
            ratio < 0.85 -> Ui.Warning
            else -> Ui.Danger
        }

        val usedStr = ContextCompactor.formatTokenCount(usedTokens)
        val maxStr = ContextCompactor.formatTokenCount(maxTokens)
        val compactionStr = if (conversationRuntime.activeAutoCompaction) {
            val threshInt = (conversationRuntime.activeCompactionThreshold * 100).toInt()
            " · ${threshInt}%压缩"
        } else {
            ""
        }

        contextUsageText.text = "● 上下文 $percent% ($usedStr / $maxStr$compactionStr)"
        contextUsageText.setTextColor(statusColor)

        // 动态刷新进度条底色与填充宽度
        contextProgressBarTrack.background = Ui.rounded(this, Ui.SurfaceSoft, 2, Ui.OutlineSubtle, 1)
        contextProgressBarFill.background = Ui.rounded(this, statusColor, 2)

        val fillWeight = ratio.toFloat().coerceAtLeast(0.015f)
        val emptyWeight = (1.0f - fillWeight).coerceAtLeast(0f)

        val fillParams = contextProgressBarFill.layoutParams as? LinearLayout.LayoutParams
        val emptyParams = contextProgressBarEmpty.layoutParams as? LinearLayout.LayoutParams
        if (fillParams != null && emptyParams != null) {
            fillParams.weight = fillWeight
            emptyParams.weight = emptyWeight
            contextProgressBarTrack.requestLayout()
        }

        contextHintRightText.setTextColor(Ui.TextMuted)
    }

    private fun setScreenAutomationActive(active: Boolean) {
        floatingWindow.setExternalAutomationMode(active)
    }

    private fun stopAgent(clearQueuedMessages: Boolean = true) {
        if (clearQueuedMessages) processScope.urgentInteractionDispatcher.cancelPending()
        if (clearQueuedMessages && delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            runCoordinator.clearQueue()
        }
        if (delayedTasks.stop()) {
            confirmationPresenter.cancelPending()
            conversationRuntime.agentRuntime?.cancelAllPlugins()
            if (runCoordinator.isRunning()) runCoordinator.stop()
            runState = runCoordinator.snapshot().state
            renderRunState()
            floatingWindow.setSending(false)
            floatingWindow.setStatus("已停止")
            return
        }
        confirmationPresenter.cancelPending()
        if (!runCoordinator.isRunning() && !runState.isBusy) {
            setScreenAutomationActive(false)
            return
        }
        if (clearQueuedMessages) runCoordinator.clearQueue()
        conversationRuntime.agentRuntime?.cancelAllPlugins()
        runState = runCoordinator.stop().state
        renderRunState()
        floatingWindow.setSending(false)
        setScreenAutomationActive(false)
        floatingWindow.setStatus("已停止")
    }

    private fun renderConversation() {
        if (!::messageContainer.isInitialized) return
        messageContainer.removeAllViews()
        processCard = null
        assistantMessageView = null
        if (activeConversation.messages.none { it.role == "user" || it.role == "assistant" }) {
            renderEmptyState()
        } else {
            val processIndex = activeConversation.messages
                .indexOfLast { it.role == "user" }
            val hasProcess = runState.isBusy || runState.steps.isNotEmpty()
            activeConversation.messages.forEachIndexed { index, message ->
                when (message.role) {
                    "user" -> addChatMessage(DemoChatMessageRole.USER, message.content, message.imagePaths)
                    "assistant" -> addChatMessage(DemoChatMessageRole.ASSISTANT, message.content)
                }
                if (hasProcess && index == processIndex) addProcessCard()
            }
        }
        updateAppBar()
        if ((runState.isBusy || runState.steps.isNotEmpty()) && processCard == null) {
            addProcessCard()
        }
        renderRunState()
        updateContextUsageIndicator()
        scrollToEnd()
    }

    private fun renderEmptyState() {
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        val heroOuter = FrameLayout(this).apply {
            background = Ui.rounded(this@MainActivity, Ui.AssistantAvatarSurface, 999)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val heroInner = ImageView(this).apply {
            setImageResource(R.drawable.brand_owl_avatar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
            background = Ui.rounded(this@MainActivity, Ui.AssistantAvatarSurface, 20)
            contentDescription = "绿色猫头鹰助手"
        }
        heroOuter.addView(heroInner, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        empty.addView(heroOuter, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            bottomMargin = dp(16)
        })

        val title = TextView(this).apply {
            text = "Agent 就绪"
            textSize = 22f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            letterSpacing = 0.02f
            setTextColor(Ui.TextPrimary)
            gravity = Gravity.CENTER
        }
        val description = TextView(this).apply {
            text = "描述你想完成的任务，Agent 会在需要时调用工具并把过程整理给你。"
            textSize = 13.5f
            letterSpacing = 0.012f
            setTextColor(Ui.TextSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        empty.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        empty.addView(description, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val suggestions = listOf(
            Triple("检", "帮我检查当前设备状态", "查看设备型号、内存与系统环境"),
            Triple("网", "创建一个本地天气页面", "通过 Python/Bash 启动本地 HTTP 服务"),
            Triple("开", "打开一个已安装的 App", "通过包名或应用名称启动目标程序")
        )
        suggestions.forEach { (icon, prompt, desc) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Ui.clickableRounded(this@MainActivity, Ui.SurfaceElevated, Ui.SurfaceSubtle, 14, Ui.OutlineSubtle)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    inputField.setText(prompt)
                    inputField.requestFocus()
                    inputField.setSelection(inputField.length())
                }
            }
            val iconBadge = TextView(this).apply {
                text = icon
                textSize = 16f
                gravity = Gravity.CENTER
                background = Ui.rounded(this@MainActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle, 1)
            }
            card.addView(iconBadge, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(12)
            })

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val promptView = TextView(this).apply {
                text = prompt
                textSize = 14.5f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                letterSpacing = 0.012f
                setTextColor(Ui.TextPrimary)
            }
            val descView = TextView(this).apply {
                text = desc
                textSize = 12f
                letterSpacing = 0.01f
                setTextColor(Ui.TextSecondary)
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(promptView)
            textCol.addView(descView)
            card.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val arrowView = TextView(this).apply {
                text = "→"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Ui.PrimaryPressed)
                gravity = Gravity.CENTER
            }
            card.addView(arrowView, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginStart = dp(8)
            })

            empty.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            })
        }
        messageContainer.addView(empty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun scrollToEnd() {
        messageScrollView.post {
            messageScrollView.smoothScrollTo(0, messageContainer.bottom)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        permissionGuide.save(outState)
        backgroundGuidance.save(outState)
        conversationRuntime.rememberSession(activeConversation.id, session)
        // No whole-conversation save here: every conversation change already
        // persists at its point of change (appendMessages/save/rename/create),
        // while this in-memory snapshot can be stale — the refresh from the
        // store is skipped while a run is busy, so a background scheduled run
        // may have appended turns this snapshot has not observed, and a
        // whole-conversation save would erase them. See the invariant
        // documented on DemoConversationStore.appendStoredMessages.
        val draft = if (::inputField.isInitialized) inputField.text?.toString().orEmpty() else ""
        conversationRuntime.draft = draft
        outState.putString(KEY_DRAFT, draft)
        super.onSaveInstanceState(outState)
    }

    private fun restoreDraft(savedInstanceState: Bundle?) {
        val draft = savedInstanceState?.getString(KEY_DRAFT) ?: conversationRuntime.draft
        inputField.setText(draft)
        inputField.setSelection(inputField.length())
    }

    private fun syncTranscript() {
        conversationRuntime.transcript.clear()
        activeConversation.messages.forEach { message ->
            if (message.role == "user" || message.role == "assistant") {
                conversationRuntime.transcript += DemoTranscriptEntry(message.role, message.content)
            }
        }
    }

    /** Reload turns appended by the process-owned delayed or urgent dispatcher. */
    private fun refreshActiveConversationFromStore() {
        if (!::activeConversation.isInitialized) return
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            reloadProcessOwnedConversation()
            return
        }
        if (runState.isBusy || runCoordinator.isRunning()) return
        val latest = conversationStore.get(activeConversation.id) ?: return
        if (latest.updatedAt == activeConversation.updatedAt &&
            latest.messages == activeConversation.messages
        ) {
            return
        }
        activeConversation = latest
        conversationRuntime.session = createDemoAgentSession(latest)
        conversationRuntime.rememberSession(latest.id, session)
        syncTranscript()
        renderConversation()
    }

    /** Process-owned turns write through storage even while this Activity is detached. */
    private fun reloadProcessOwnedConversation() {
        if (!::activeConversation.isInitialized) return
        val latest = conversationStore.get(activeConversation.id) ?: return
        if (latest.updatedAt == activeConversation.updatedAt &&
            latest.messages == activeConversation.messages
        ) return
        activeConversation = latest
        syncTranscript()
        renderConversation()
    }

    private fun updateAppBar() {
        if (::appBarTitle.isInitialized) appBarTitle.text = activeConversation.title
    }

    private fun selectConversation(id: String) {
        if (DemoCapabilityInterlock.isRecordingOwned()) {
            showInlineNotice("请先结束演示录制")
            return
        }
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            showInlineNotice("请先停止当前定时任务")
            return
        }
        if (processingImages) {
            showInlineNotice("正在处理图片，请稍候...")
            return
        }
        val conversation = conversationStore.get(id) ?: return
        if (!::activeConversation.isInitialized || conversation.id != activeConversation.id) {
            invalidateImageSelection()
        }
        if (runState.isBusy || runCoordinator.isRunning()) stopAgent()
        activeConversation = conversation
        conversationStore.setActive(conversation.id)
        conversationRuntime.activeConversationId = conversation.id
        runCoordinator.resetForConversation(conversation.id)
        conversationRuntime.session = conversationRuntime.sessionFor(conversation.id)
            ?: createDemoAgentSession(conversation).also {
                conversationRuntime.rememberSession(conversation.id, it)
            }
        runState = runCoordinator.snapshot().state
        floatingWindow.clear()
        // floatingWindow.clear() resets the composer draft through
        // onDraftChanged; the field must follow, otherwise the visible text
        // of the previous conversation would silently become this
        // conversation's next message (sendMessage reads the field).
        if (::inputField.isInitialized) {
            inputField.setText("")
        }
        syncTranscript()
        renderConversation()
        updateCapabilityBanner()
    }

    private fun createNewConversation() {
        if (DemoCapabilityInterlock.isRecordingOwned()) {
            showInlineNotice("请先结束演示录制")
            return
        }
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            showInlineNotice("请先停止当前定时任务")
            return
        }
        if (processingImages) {
            showInlineNotice("正在处理图片，请稍候...")
            return
        }
        invalidateImageSelection()
        if (runState.isBusy || runCoordinator.isRunning()) stopAgent()
        val conversation = conversationStore.create()
        activeConversation = conversation
        conversationRuntime.activeConversationId = conversation.id
        runCoordinator.resetForConversation(conversation.id)
        conversationRuntime.session = createDemoAgentSession(conversation)
        conversationRuntime.rememberSession(conversation.id, session)
        runState = runCoordinator.snapshot().state
        floatingWindow.clear()
        if (::inputField.isInitialized) {
            inputField.setText("")
        }
        syncTranscript()
        renderConversation()
    }

    private fun showConversationHistory() {
        if (DemoCapabilityInterlock.isRecordingOwned()) {
            showInlineNotice("请先结束演示录制")
            return
        }
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            showInlineNotice("请先停止当前定时任务")
            return
        }
        if (processingImages) {
            showInlineNotice("正在处理图片，请稍候...")
            return
        }
        if (isFinishing || isDestroyed) return
        val dialog = BottomSheetDialog(this)
        val targetHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.asymmetricRounded(this@MainActivity, Ui.Surface, 20, 20, 0, 0)
        }

        val dragHandle = View(this).apply {
            background = Ui.rounded(this@MainActivity, Ui.OutlineSubtle, 2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(dragHandle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
            bottomMargin = dp(6)
        })

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(16), dp(10))
        }
        val titleView = TextView(this).apply {
            text = "会话历史"
            textSize = 17f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.TextPrimary)
        }
        val closeButton = TextView(this).apply {
            text = "关闭"
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.TextSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = Ui.clickableRounded(this@MainActivity, Color.TRANSPARENT, Ui.SurfaceSoft, 8)
            isClickable = true
            isFocusable = true
            contentDescription = "关闭"
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val newButton = TextView(this).apply {
            text = "+ 新建会话"
            textSize = 14.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.PrimaryPressed)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = Ui.clickableRounded(this@MainActivity, Ui.PrimaryContainer, Ui.SurfaceSubtle, 12, Ui.OutlineFocus)
            isClickable = true
            isFocusable = true
            contentDescription = "新建会话"
            setOnClickListener {
                createNewConversation()
                dialog.dismiss()
            }
        }
        root.addView(newButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(20)
            rightMargin = dp(20)
            bottomMargin = dp(10)
        })

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listScroll = NestedScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(20), 0, dp(20), dp(16))
            addView(listContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            listScroll.setPadding(dp(20), 0, dp(20), dp(16) + navBarInsets.bottom)
            insets
        }

        fun populate() {
            listContainer.removeAllViews()
            conversationStore.list().forEach { conversation ->
                val isActive = conversation.id == activeConversation.id
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(8), dp(10))
                    background = Ui.clickableRounded(
                        this@MainActivity,
                        if (isActive) Ui.PrimaryContainer else Ui.SurfaceElevated,
                        Ui.SurfaceSubtle,
                        12,
                        if (isActive) Ui.FocusRing else Ui.OutlineSubtle
                    )
                    isClickable = true
                    isFocusable = true
                    isSelected = isActive
                    contentDescription = "${conversation.title}，${if (isActive) "当前会话，已选中" else "未选中"}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        stateDescription = if (isActive) "当前会话，已选中" else "未选中"
                    }
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val title = TextView(this).apply {
                    text = if (isActive) "✓ ${conversation.title}" else conversation.title
                    textSize = 14.5f
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                    setTextColor(Ui.TextPrimary)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val meta = TextView(this).apply {
                    val countText = "${conversation.messages.count { it.role == "user" }} 条消息 · ${formatConversationTime(conversation.updatedAt)}"
                    text = if (isActive) "当前会话 · $countText" else countText
                    textSize = 11.5f
                    setTextColor(Ui.TextSecondary)
                    setPadding(0, dp(2), 0, 0)
                }
                info.addView(title)
                info.addView(meta)
                row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val actions = TextView(this).apply {
                    text = "⋯"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(Ui.TextSecondary)
                    contentDescription = "会话操作"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showConversationActions(conversation) { populate() }
                    }
                }
                row.addView(actions, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.setOnClickListener {
                    selectConversation(conversation.id)
                    dialog.dismiss()
                }
                listContainer.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }
        }

        dialog.setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            targetHeight
        ))

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.peekHeight = targetHeight
                behavior.maxHeight = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isHideable = true
                behavior.isDraggable = true
                val layoutParams = bottomSheet.layoutParams
                layoutParams.height = targetHeight
                bottomSheet.layoutParams = layoutParams
            }
            populate()
        }
        dialog.show()
    }

    private fun showConversationActions(
        conversation: DemoConversation,
        onChanged: () -> Unit
    ) {
        if (delayedTasks.snapshot() !is DemoDelayedTaskState.Idle) {
            showInlineNotice("请先停止当前定时任务")
            return
        }
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setItems(arrayOf("重命名", "删除")) { _, which ->
                if (which == 0) renameConversation(conversation, onChanged)
                else confirmDeleteConversation(conversation, onChanged)
            }
            .show()
    }

    private fun renameConversation(conversation: DemoConversation, onChanged: () -> Unit) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(conversation.title)
            setSelection(length())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(Ui.TextPrimary)
        }
        AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle("重命名会话")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                conversationStore.rename(conversation.id, input.text.toString())
                if (conversation.id == activeConversation.id) {
                    activeConversation = conversationStore.get(conversation.id) ?: activeConversation
                    updateAppBar()
                }
                onChanged()
            }
            .show()
    }

    private fun confirmDeleteConversation(conversation: DemoConversation, onChanged: () -> Unit) {
        val dialog = AlertDialog.Builder(this, Ui.dialogTheme())
            .setTitle("删除会话？")
            .setMessage("删除后无法在本地恢复这段会话。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val replacement = conversationStore.delete(conversation.id)
                if (conversation.id == activeConversation.id && replacement != null) {
                    selectConversation(replacement.id)
                }
                onChanged()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Ui.TextSecondary)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.Danger)
        }
        dialog.show()
    }

    private fun formatConversationTime(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

    private companion object {
        const val KEY_DRAFT = "demo_draft"
        const val MAX_VISIBLE_CHAT_MESSAGES = 100
        const val REQUEST_IMPORT_FILE = 4101
        const val REQUEST_TAKE_PHOTO = 4102
        const val REQUEST_PICK_IMAGE = 4103
        const val REQUEST_CAMERA_PERMISSION = 4104
        const val MAX_PENDING_IMPORTED_FILES = 3
    }


}

internal fun shouldShowFloatingWindowOnPause(
    overlayPermissionGranted: Boolean,
    inAppNavigating: Boolean = false,
): Boolean = AgentOverlayPolicy.shouldShowOnPause(
    overlayPermissionGranted = overlayPermissionGranted,
    activityResumed = false,
    inAppNavigating = inAppNavigating,
)

private class ImportActionButton(context: android.content.Context) : View(context) {
    var hasAttachments: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = Path()

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(cx, cy) - context.dp(6).toFloat()).coerceAtLeast(0f)

        // 背景圆（与 SendActionButton 相同，100% 圆形，完全受自身约束，绝不发生边缘裁剪）
        bgPaint.color = when {
            isPressed -> if (hasAttachments) Ui.OutlineFocus else Ui.SurfaceSubtle
            hasAttachments -> Ui.PrimaryContainer
            else -> Ui.SurfaceSoft
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // 图标颜色：有附件时使用品牌绿高亮，无附件时使用次级文字色
        iconPaint.color = when {
            hasAttachments -> Ui.PrimaryPressed
            else -> Ui.TextSecondary
        }
        iconPaint.strokeWidth = radius * 0.13f

        // 绘制矢量回形针图标（逆时针旋转 45° 呈现经典斜角）
        canvas.save()
        canvas.rotate(-45f, cx, cy)

        clipPath.reset()
        val s = radius * 0.22f
        val halfH = radius * 0.38f

        // 内圈起点与下行线
        val startY = cy - halfH * 0.15f
        val innerBottomY = cy + halfH * 0.55f
        clipPath.moveTo(cx + 0.5f * s, startY)
        clipPath.lineTo(cx + 0.5f * s, innerBottomY)

        // 底部内半圆（向左，直径 s）
        val innerBottomRect = RectF(
            cx - 0.5f * s,
            innerBottomY - 0.5f * s,
            cx + 0.5f * s,
            innerBottomY + 0.5f * s
        )
        clipPath.arcTo(innerBottomRect, 0f, 180f, false)

        // 向上沿中导轨到顶部
        val topY = cy - halfH * 0.70f
        clipPath.lineTo(cx - 0.5f * s, topY)

        // 顶部大半圆（顺时针向右，直径 2s）
        val topRect = RectF(
            cx - 0.5f * s,
            topY - s,
            cx + 1.5f * s,
            topY + s
        )
        clipPath.arcTo(topRect, 180f, 180f, false)

        // 向下沿右外导轨到底部
        val outerBottomY = cy + halfH * 0.70f
        clipPath.lineTo(cx + 1.5f * s, outerBottomY)

        // 底部外半圆（顺时针向左，直径 3s）
        val outerBottomRect = RectF(
            cx - 1.5f * s,
            outerBottomY - 1.5f * s,
            cx + 1.5f * s,
            outerBottomY + 1.5f * s
        )
        clipPath.arcTo(outerBottomRect, 0f, 180f, false)

        // 向上沿最左外导轨
        val endY = cy - halfH * 0.20f
        clipPath.lineTo(cx - 1.5f * s, endY)

        canvas.drawPath(clipPath, iconPaint)
        canvas.restore()

        // 附件就绪指示徽标（右上角品牌绿圆点）
        if (hasAttachments) {
            badgePaint.color = Ui.BrandPrimary
            val badgeRadius = radius * 0.20f
            val badgeCx = cx + radius * 0.52f
            val badgeCy = cy - radius * 0.52f
            canvas.drawCircle(badgeCx, badgeCy, badgeRadius, badgePaint)
        }
    }
}
