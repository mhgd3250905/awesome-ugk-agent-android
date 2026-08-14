package com.ugk.pi.android.testapp

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.app.Activity
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
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AndroidAutomationAgentPlugin
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillMethod
import com.ugk.pi.android.AnthropicMessagesProvider
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import com.ugk.pi.terminal.skill.TerminalAgentPlugin
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private val apiStore by lazy { ApiProviderSettingsStore(this) }
    private val authorizationStore by lazy { AgentAuthorizationSettingsStore(this) }
    private val conversationStore by lazy { DemoActivityState.conversationStore(applicationContext) }
    private var runtime: AgentRuntime? = null
    private lateinit var activeConversation: DemoConversation
    private lateinit var session: AgentSession
    private var runState: DemoRunState = DemoRunState.initial()
    private var activityResumed = false
    private val activityToken = Any()
    private var overlayPermissionDialog: AlertDialog? = null
    private val confirmationPresenter by lazy {
        DemoActivityState.confirmationPresenter.also { presenter ->
            presenter.attach(
                activity = this,
                isResumed = { activityResumed },
                isFullAuthorizationEnabled = { authorizationStore.isFullAuthorizationEnabled() },
                overlayHost = floatingWindow
            )
        }
    }

    private lateinit var appBarTitle: TextView
    private lateinit var messageContainer: LinearLayout
    private lateinit var messageScrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: TextView
    private lateinit var providerLabel: TextView
    private lateinit var runStatusLabel: TextView
    private lateinit var statusBanner: TextView
    private lateinit var composerHint: TextView
    private var processCard: DemoChatProcessCardView? = null
    private var assistantMessageView: DemoChatMessageView? = null
    private var lastImeInsetBottom = 0
    private val floatingWindow by lazy {
        DemoActivityState.floatingWindow(applicationContext).apply {
            onSendMessage = { text -> DemoActivityState.overlaySend?.invoke(text) == true }
            onStopAgent = { DemoActivityState.overlayStop?.invoke() }
            onOpenApp = { DemoActivityState.overlayOpenApp?.invoke() }
            onHide = { DemoActivityState.overlayHide?.invoke() }
            onDraftChanged = { draft -> DemoActivityState.draft = draft }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        activeConversation = conversationStore.get(
            DemoActivityState.activeConversationId ?: conversationStore.activeId()
        ) ?: conversationStore.ensureActive()
        DemoActivityState.activeConversationId = activeConversation.id
        conversationStore.setActive(activeConversation.id)
        session = DemoActivityState.sessionFor(activeConversation.id)
            ?: createSession(activeConversation).also {
                DemoActivityState.rememberSession(activeConversation.id, it)
            }
        setContentView(buildUi())
        DemoActivityState.bindOverlayCallbacks(
            owner = activityToken,
            // AgentFloatingWindow invokes touch callbacks on the main looper;
            // return the enqueue result synchronously so a full queue keeps
            // the user's draft instead of clearing it optimistically.
            onSend = { text -> enqueueOverlayMessage(text) },
            onStop = { runOnUiThread { stopAgent(clearQueuedMessages = true) } },
            onOpenApp = {
                runOnUiThread {
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                    )
                }
            },
            onHide = { runOnUiThread { hideFloatingWindow() } }
        )
        restoreDraft(savedInstanceState)
        rebuildRuntime()
        attachRunCoordinator()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home/launcher transitions can happen before onPause on some OEM
        // builds. This callback makes the cross-app handoff deterministic.
        showFloatingWindowIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        confirmationPresenter.onActivityResumed()
        updateCapabilityBanner()
        if (::inputField.isInitialized && inputField.text.toString() != DemoActivityState.draft) {
            inputField.setText(DemoActivityState.draft)
            inputField.setSelection(inputField.length())
        }
        renderRunState()
        // The main chat is the primary surface. The overlay is only a
        // background-run summary, so keep it hidden while this Activity is
        // visible to avoid competing with the conversation.
        floatingWindow.hide()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
        showFloatingWindowIfNeeded()
        confirmationPresenter.onActivityPaused()
    }

    override fun onDestroy() {
        val finishing = isFinishing && !isChangingConfigurations
        if (finishing) {
            DemoActivityState.runCoordinator.stop()
            DemoActivityState.runCoordinator.clearQueue()
            DemoActivityState.runCoordinator.detach(activityToken)
            runtime?.cancelAllPlugins()
            runtime?.close()
            confirmationPresenter.release()
        } else {
            DemoActivityState.runCoordinator.detach(activityToken)
            confirmationPresenter.detach(this)
        }
        DemoActivityState.rememberSession(activeConversation.id, session)
        super.onDestroy()
        DemoActivityState.clearOverlayCallbacks(activityToken)
        if (finishing) hideFloatingWindow()
    }

    private fun hideFloatingWindow() {
        floatingWindow.hide()
    }

    private fun showFloatingWindowIfNeeded() {
        // The overlay is a background entry point even when no Agent run is
        // active. Without permission, the Activity remains the safe fallback.
        if (AgentOverlayPolicy.shouldShowOnPause(
                overlayPermissionGranted = Settings.canDrawOverlays(this),
                activityResumed = false
            )
        ) {
            floatingWindow.show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AgentAccessibilityService.running) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains("$packageName/${packageName}.AgentAccessibilityService")
    }

    private fun updateCapabilityBanner() {
        if (!::statusBanner.isInitialized) return
        val config = apiStore.activeConfig()
        val message = when {
            config == null -> "还没有配置 API 源，点击这里打开设置"
            authorizationStore.isFullAuthorizationEnabled() ->
                "全授权模式已开启：高影响操作不会弹出确认"
            !isAccessibilityEnabled() -> "无障碍服务未开启，跨 App 读屏和操作暂不可用"
            !Settings.canDrawOverlays(this) -> "悬浮窗未授权，切换到其他 App 时不会显示任务摘要"
            else -> ""
        }
        statusBanner.text = message
        statusBanner.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (::composerHint.isInitialized) {
            composerHint.text = if (authorizationStore.isFullAuthorizationEnabled()) {
                "全授权模式已开启，高影响操作不会弹出确认"
            } else {
                "Agent 会按需调用工具，重要操作会先请求确认"
            }
        }
        statusBanner.setTextColor(
            if (config == null || !isAccessibilityEnabled()) Ui.Warning else Ui.TextSecondary
        )
        statusBanner.setOnClickListener {
            when {
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
        statusBanner.setTextColor(Ui.Warning)
        statusBanner.visibility = View.VISIBLE
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.Surface)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val historyButton = TextView(this).apply {
            text = "☰"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TextPrimary)
            contentDescription = getString(R.string.content_description_sessions)
            isClickable = true
            isFocusable = true
            setOnClickListener { showConversationHistory() }
        }

        appBarTitle = TextView(this).apply {
            text = activeConversation.title
            textSize = 18f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.TextPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        providerLabel = TextView(this).apply {
            textSize = 11f
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
            ).apply { topMargin = dp(2) })
        }

        runStatusLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.MintDark)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = Ui.rounded(this@MainActivity, Ui.SurfaceSoft, 999)
            maxLines = 1
        }
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TextPrimary)
            contentDescription = getString(R.string.content_description_settings)
            isClickable = true
            isFocusable = true
            setOnClickListener { openSettings() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 0)
            addView(historyButton, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(titleStack, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(4)
            })
            addView(runStatusLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(4) })
            addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        statusBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.Warning)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = Ui.rounded(this@MainActivity, Ui.SurfaceSoft, 12)
            isClickable = true
            isFocusable = true
        }
        content.addView(statusBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), dp(4), dp(12), dp(4))
        })

        messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(20))
        }
        messageScrollView = ScrollView(this).apply {
            setFillViewport(true)
            isSmoothScrollingEnabled = true
            addView(messageContainer)
        }
        content.addView(messageScrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        inputField = EditText(this).apply {
            hint = "给 Agent 发消息"
            setHintTextColor(Ui.TextMuted)
            setTextColor(Ui.TextPrimary)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            maxLines = 5
            minLines = 1
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(dp(12), dp(10), dp(8), dp(10))
            setOnFocusChangeListener { _, hasFocus ->
                updateComposerState()
                if (hasFocus) scrollToEnd()
            }
        }
        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                DemoActivityState.draft = text?.toString().orEmpty()
                floatingWindow.setComposerDraft(DemoActivityState.draft)
                updateComposerState()
            }
            override fun afterTextChanged(editable: Editable?) = Unit
        })
        sendButton = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Ui.SurfaceElevated)
            background = Ui.rounded(this@MainActivity, Ui.Mint, 22)
            contentDescription = getString(R.string.content_description_send)
            isClickable = true
            isFocusable = true
            setOnClickListener { sendMessage() }
        }
        val inputShell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 18, Ui.Outline)
            setPadding(dp(4), dp(4), dp(6), dp(4))
            addView(inputField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(4)
            })
        }
        composerHint = TextView(this).apply {
            text = "Agent 会按需调用工具，重要操作会先请求确认"
            textSize = 11f
            setTextColor(Ui.TextMuted)
            setPadding(dp(4), dp(5), dp(4), 0)
        }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 0)
            addView(inputShell, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(composerHint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        content.addView(composer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        installSystemBarInsets(root)
        return root
    }

    @Suppress("DEPRECATION")
    private fun installSystemBarInsets(root: View) {
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        fun applyInsets(insets: WindowInsets) {
            val systemInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val current = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                InsetsSnapshot(current.left, current.top, current.right, current.bottom)
            } else {
                InsetsSnapshot(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val imeType = WindowInsets.Type.ime()
                if (insets.isVisible(imeType)) insets.getInsets(imeType).bottom else 0
            } else {
                0
            }
            val bottomInset = maxOf(systemInsets.bottom, imeBottom)
            root.setPadding(
                initialLeft + systemInsets.left,
                initialTop + systemInsets.top,
                initialRight + systemInsets.right,
                initialBottom + bottomInset
            )
            if (imeBottom != lastImeInsetBottom) {
                lastImeInsetBottom = imeBottom
                if (imeBottom > 0) {
                    messageScrollView.post { messageScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        val applyInsetsListener = View.OnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.setOnApplyWindowInsetsListener(applyInsetsListener)
        // Android 15 edge-to-edge can deliver IME changes to the decor view
        // without relaying them to a content root that already applied bar
        // insets. Listen at both levels so the composer always stays above IME.
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
        ApiSettingsDialog(
            activity = this,
            store = apiStore,
            onChanged = { rebuildRuntime() },
            authorizationStore = authorizationStore
        ).show()
    }

    private fun rebuildRuntime() {
        stopAgent(clearQueuedMessages = true)
        runtime?.close()
        val config = apiStore.activeConfig()
        val provider: LLMProvider = if (config != null) {
            AnthropicMessagesProvider(
                apiKey = config.apiKey,
                model = config.model,
                baseUrl = config.baseUrl
            )
        } else {
            PlaceholderProvider
        }
        val nextTerminalPlugin = TerminalAgentPlugin(
            context = applicationContext,
            shouldBypassConfirmation = { authorizationStore.isFullAuthorizationEnabled() }
        )
        runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(
                AndroidAutomationAgentPlugin(
                    context = applicationContext,
                    confirmationPresenter = confirmationPresenter,
                    accessibilityServiceComponent = ComponentName(
                        this,
                        AgentAccessibilityService::class.java
                    ),
                    accessibilityStateProvider = AgentAccessibilityService.runtimeStateProvider,
                    shouldBypassConfirmation = {
                        authorizationStore.isFullAuthorizationEnabled()
                    }
                )
            )
            .register(nextTerminalPlugin)
            .register(object : AgentCapabilityPlugin {
                override val id = "accessibility-screen"
                override fun tools() = listOf(
                    ScreenReadUiTreeTool(),
                    ScreenPerformActionTool(),
                    ScreenGlobalActionTool(),
                    ScreenGestureTool(),
                    ScreenPressKeyTool()
                )
                override fun skills() = listOf(accessibilityScreenSkill)
            })
            .build()
        providerLabel.text = config?.displayName() ?: "未配置 API 源"
        updateCapabilityBanner()
        renderConversation()
    }

    private fun sendMessage() {
        if (runState.isBusy) {
            stopAgent()
            return
        }
        val text = inputField.text.toString().trim()
        if (text.isBlank()) return
        if (DemoActivityState.runCoordinator.isRunning()) {
            if (DemoActivityState.runCoordinator.enqueue(text)) {
                inputField.setText("")
                DemoActivityState.draft = ""
                floatingWindow.addLog("已排队：${text.take(48)}")
                renderRunState()
            } else {
                showInlineNotice("消息队列已满，请稍后再试")
            }
            return
        }
        // A missing API configuration is handled by the placeholder runtime;
        // it must not unexpectedly trigger a second, unrelated permission
        // prompt before the user has configured a runnable provider.
        if (apiStore.activeConfig() != null) {
            maybeOfferOverlayPermission()
        }
        if (runAgent(text)) {
            DemoActivityState.draft = ""
            inputField.setText("")
        }
    }

    private fun enqueueOverlayMessage(text: String): Boolean {
        val message = text.trim()
        if (message.isBlank()) return false

        if (runState.isBusy || DemoActivityState.runCoordinator.isRunning()) {
            if (!DemoActivityState.runCoordinator.enqueue(message)) {
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
        if (runState.isBusy || DemoActivityState.runCoordinator.isRunning()) return
        val next = DemoActivityState.runCoordinator.removeNextQueued() ?: return
        floatingWindow.addLog("开始处理排队消息")
        runAgent(next)
    }

    private fun maybeOfferOverlayPermission() {
        if (!AgentOverlayPolicy.shouldOfferPermission(
                permissionGranted = Settings.canDrawOverlays(this),
                hasActiveRun = true
            ) || DemoActivityState.overlayPromptShown || isFinishing
        ) {
            return
        }
        DemoActivityState.overlayPromptShown = true
        overlayPermissionDialog = AlertDialog.Builder(this)
            .setTitle("开启跨 App 悬浮窗")
            .setMessage("离开 Agent Test 后，悬浮小窗可以继续显示任务进度，并发送后续消息，不会打断当前 Agent 操作。")
            .setNegativeButton("暂不") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("去开启") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { overlayPermissionDialog = null }
                dialog.show()
            }
    }

    private fun runAgent(text: String): Boolean {
        if (runState.isBusy || DemoActivityState.runCoordinator.isRunning()) return false
        val currentRuntime = runtime ?: return false
        val message = text.trim()
        if (message.isBlank()) return false

        if (activeConversation.title == "新对话") {
            activeConversation.title = conversationStore.suggestedTitle(message)
        }
        activeConversation.messages += DemoStoredMessage("user", message)
        activeConversation.updatedAt = System.currentTimeMillis()
        conversationStore.save(activeConversation)
        syncTranscript()
        updateAppBar()

        assistantMessageView = null
        addChatMessage(DemoChatMessageRole.USER, message)
        addProcessCard()
        DemoActivityState.activeConversationId = activeConversation.id

        floatingWindow.clear()
        floatingWindow.setSending(true)
        floatingWindow.setStatus("思考中")
        floatingWindow.addLog("开始任务")

        DemoActivityState.runCoordinator.start(
            runtime = currentRuntime,
            session = session,
            conversationId = activeConversation.id,
            message = message
        )
        runState = DemoActivityState.runCoordinator.snapshot().state
        renderRunState()
        return true
    }

    private fun attachRunCoordinator() {
        val snapshot = DemoActivityState.runCoordinator.attach(
            owner = activityToken,
            onEvent = { event ->
                runOnUiThread { handleAgentEvent(event) }
            },
            onFinished = {
                runOnUiThread { finishAgentRun() }
            }
        )
        runState = snapshot.state
        DemoActivityState.runCoordinator
            .consumePendingOutcome(activeConversation.id)
            ?.event
            ?.let { event ->
                when (event) {
                    is AgentEvent.Completed -> persistAssistantMessage(event.content)
                    is AgentEvent.Failed -> persistAssistantMessage("任务未完成：${event.message}")
                    else -> Unit
                }
            }
        // rebuildRuntime() renders once before the coordinator is attached.
        // Render again from the restored snapshot so terminal runs also place
        // their process card between the latest user message and its answer.
        renderConversation()
        if (!snapshot.isRunning) {
            floatingWindow.setSending(false)
            startNextQueuedOverlayMessage()
        }
    }

    private fun finishAgentRun() {
        runState = DemoActivityState.runCoordinator.snapshot().state
        updateComposerState()
        floatingWindow.setSending(false)
        floatingWindow.setStatus(runState.statusLabel)
        startNextQueuedOverlayMessage()
    }

    private fun handleAgentEvent(event: AgentEvent) {
        runState = DemoActivityState.runCoordinator.snapshot().state
        when (event) {
            is AgentEvent.ToolStarted -> {
                floatingWindow.setStatus(runState.statusLabel)
                floatingWindow.addLog("→ ${event.call.name}")
            }
            is AgentEvent.ToolProgress -> {
                floatingWindow.setStatus(runState.statusLabel)
            }
            is AgentEvent.ToolFinished -> {
                val resultLabel = if (event.result.isError) "失败" else "成功"
                floatingWindow.setStatus(runState.statusLabel)
                floatingWindow.addLog("✓ ${event.result.name}: $resultLabel")
            }
            is AgentEvent.Completed -> {
                persistAssistantMessage(event.content)
                DemoActivityState.runCoordinator.acknowledgeOutcome()
                floatingWindow.setStatus("已完成")
                floatingWindow.addLog("回答已完成")
            }
            is AgentEvent.Failed -> {
                if (event.message.isNotBlank()) {
                    persistAssistantMessage("任务未完成：${event.message}")
                }
                DemoActivityState.runCoordinator.acknowledgeOutcome()
                floatingWindow.setStatus("失败")
                floatingWindow.addLog("失败：${event.message}")
            }
            else -> Unit
        }
        runState = DemoActivityState.runCoordinator.snapshot().state
        renderRunState()
    }

    private fun persistAssistantMessage(text: String) {
        if (text.isBlank()) return
        if (activeConversation.messages.lastOrNull()?.let { it.role == "assistant" && it.content == text } == true) {
            return
        }
        activeConversation.messages += DemoStoredMessage("assistant", text)
        activeConversation.updatedAt = System.currentTimeMillis()
        conversationStore.save(activeConversation)
        syncTranscript()
        assistantMessageView?.updateText(text)
            ?: run { assistantMessageView = addChatMessage(DemoChatMessageRole.ASSISTANT, text) }
    }

    private fun addChatMessage(role: DemoChatMessageRole, text: String): DemoChatMessageView {
        val view = DemoChatMessageView(this).apply {
            bind(role, text)
        }
        messageContainer.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        trimVisibleChatMessages()
        scrollToEnd()
        return view
    }

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
                DemoActivityState.runCoordinator.setDetailsExpanded(expanded)
                runState = DemoActivityState.runCoordinator.snapshot().state
            }
            setOnStepExpandedChangeListener { _, expanded ->
                if (expanded) scrollToEnd()
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
        val state = runState
        runStatusLabel.text = state.statusLabel
        runStatusLabel.setTextColor(
            when (state.status) {
                DemoRunStatus.FAILED -> Ui.Danger
                DemoRunStatus.WAITING_CONFIRMATION -> Ui.Warning
                DemoRunStatus.COMPLETED -> Ui.Success
                DemoRunStatus.CANCELLED -> Ui.TextSecondary
                else -> Ui.MintDark
            }
        )
        val latestStep = state.steps.lastOrNull()
        val stage = when (state.status) {
            DemoRunStatus.THINKING -> DemoChatProcessStage.THINKING
            DemoRunStatus.TOOL_RUNNING -> DemoChatProcessStage.TOOL_CALL
            DemoRunStatus.WAITING_CONFIRMATION -> DemoChatProcessStage.WAITING_CONFIRMATION
            DemoRunStatus.TOOL_SUCCESS -> DemoChatProcessStage.RESULT
            DemoRunStatus.COMPLETED -> DemoChatProcessStage.COMPLETED
            DemoRunStatus.TOOL_FAILURE, DemoRunStatus.FAILED, DemoRunStatus.CANCELLED -> DemoChatProcessStage.ERROR
            DemoRunStatus.IDLE -> DemoChatProcessStage.THINKING
        }
        if (processCard != null) {
            val processSteps = state.steps.map { step ->
                DemoChatProcessStep(
                    id = step.id,
                    title = step.title,
                    status = when (step.status) {
                        DemoRunStatus.COMPLETED, DemoRunStatus.TOOL_SUCCESS ->
                            DemoChatProcessStepStatus.COMPLETE
                        DemoRunStatus.THINKING, DemoRunStatus.TOOL_RUNNING ->
                            DemoChatProcessStepStatus.ACTIVE
                        DemoRunStatus.WAITING_CONFIRMATION ->
                            DemoChatProcessStepStatus.WAITING
                        DemoRunStatus.TOOL_FAILURE, DemoRunStatus.FAILED, DemoRunStatus.CANCELLED ->
                            DemoChatProcessStepStatus.ERROR
                        DemoRunStatus.IDLE -> DemoChatProcessStepStatus.PENDING
                    },
                    detail = step.detailLabel,
                    resultSummary = step.resultSummary
                )
            }
            floatingWindow.bindSnapshot(
                AgentOverlaySnapshot(
                    title = activeConversation.title,
                    statusLabel = state.statusLabel,
                    statusDetail = state.detailLabel,
                    latestMessage = activeConversation.messages.lastOrNull()?.content,
                    latestMessageRole = activeConversation.messages.lastOrNull()?.role,
                    steps = state.steps.map { step ->
                        AgentOverlayStep(
                            id = step.id,
                            title = step.title,
                            statusLabel = step.status.label,
                            detail = step.detailLabel,
                            resultSummary = step.resultSummary
                        )
                    },
                    isBusy = state.isBusy,
                    queuedMessages = DemoActivityState.runCoordinator.snapshot().queuedMessages
                )
            )
            processCard?.bind(
                DemoChatProcessState(
                    stage = stage,
                    toolName = latestStep?.takeIf { it.kind == DemoRunStepKind.TOOL }?.title,
                    resultSummary = state.resultSummary ?: state.detailLabel,
                    steps = processSteps,
                    footerLeft = if (processSteps.isEmpty()) null else "${processSteps.size} 个步骤",
                    footerRight = state.statusLabel,
                    expanded = state.detailsExpanded
                )
            )
        } else {
            floatingWindow.bindSnapshot(
                AgentOverlaySnapshot(
                    title = activeConversation.title,
                    statusLabel = state.statusLabel,
                    statusDetail = state.detailLabel,
                    latestMessage = activeConversation.messages.lastOrNull()?.content,
                    latestMessageRole = activeConversation.messages.lastOrNull()?.role,
                    isBusy = state.isBusy,
                    queuedMessages = DemoActivityState.runCoordinator.snapshot().queuedMessages
                )
            )
        }
        updateComposerState()
        floatingWindow.setStatus(state.statusLabel)
        if (state.isBusy) scrollToEnd()
    }

    private fun updateComposerState() {
        if (!::sendButton.isInitialized) return
        val busy = runState.isBusy
        inputField.isEnabled = !busy
        sendButton.text = if (busy) "■" else "↑"
        sendButton.contentDescription = getString(
            if (busy) R.string.content_description_stop else R.string.content_description_send
        )
        sendButton.background = Ui.rounded(
            this,
            if (busy) Ui.Danger else Ui.Mint,
            22
        )
        val hasText = !inputField.text.isNullOrBlank()
        // While busy this button is the stop action; while idle it is only
        // actionable when there is a non-blank message.
        // A cancelled Job may still be draining; text can already be queued
        // during that short window, so do not disable a valid send action.
        val actionable = busy || hasText
        sendButton.isEnabled = actionable
        sendButton.alpha = if (actionable) 1f else 0.55f
    }

    private fun stopAgent(clearQueuedMessages: Boolean = true) {
        confirmationPresenter.cancelPending()
        if (!DemoActivityState.runCoordinator.isRunning() && !runState.isBusy) return
        if (clearQueuedMessages) DemoActivityState.runCoordinator.clearQueue()
        runtime?.cancelAllPlugins()
        runState = DemoActivityState.runCoordinator.stop().state
        renderRunState()
        floatingWindow.setSending(false)
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
                    "user" -> addChatMessage(DemoChatMessageRole.USER, message.content)
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
        scrollToEnd()
    }

    private fun renderEmptyState() {
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(64), dp(28), dp(24))
        }
        val title = TextView(this).apply {
            text = "Agent 就绪"
            textSize = 24f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Ui.TextPrimary)
            gravity = Gravity.CENTER
        }
        val description = TextView(this).apply {
            text = "描述你想完成的任务，Agent 会在需要时调用工具并把过程整理给你。"
            textSize = 14f
            setTextColor(Ui.TextSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        }
        empty.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        empty.addView(description, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        listOf("帮我检查当前设备状态", "创建一个本地天气页面", "打开一个已安装的 App").forEach { suggestion ->
            val item = TextView(this).apply {
                text = suggestion
                textSize = 14f
                setTextColor(Ui.MintDark)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = Ui.rounded(this@MainActivity, Ui.SurfaceElevated, 14, Ui.Outline)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    inputField.setText(suggestion)
                    inputField.requestFocus()
                    inputField.setSelection(inputField.length())
                }
            }
            empty.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            })
        }
        messageContainer.addView(empty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun scrollToEnd() {
        messageScrollView.post {
            messageScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        DemoActivityState.rememberSession(activeConversation.id, session)
        conversationStore.save(activeConversation)
        val draft = if (::inputField.isInitialized) inputField.text?.toString().orEmpty() else ""
        DemoActivityState.draft = draft
        outState.putString(KEY_DRAFT, draft)
        super.onSaveInstanceState(outState)
    }

    private fun restoreDraft(savedInstanceState: Bundle?) {
        val draft = savedInstanceState?.getString(KEY_DRAFT) ?: DemoActivityState.draft
        inputField.setText(draft)
        inputField.setSelection(inputField.length())
    }

    private fun syncTranscript() {
        DemoActivityState.transcript.clear()
        activeConversation.messages.forEach { message ->
            if (message.role == "user" || message.role == "assistant") {
                DemoActivityState.transcript += DemoTranscriptEntry(message.role, message.content)
            }
        }
    }

    private fun updateAppBar() {
        if (::appBarTitle.isInitialized) appBarTitle.text = activeConversation.title
    }

    private fun selectConversation(id: String) {
        if (runState.isBusy || DemoActivityState.runCoordinator.isRunning()) stopAgent()
        val conversation = conversationStore.get(id) ?: return
        activeConversation = conversation
        conversationStore.setActive(conversation.id)
        DemoActivityState.activeConversationId = conversation.id
        DemoActivityState.runCoordinator.resetForConversation(conversation.id)
        session = DemoActivityState.sessionFor(conversation.id)
            ?: createSession(conversation).also {
                DemoActivityState.rememberSession(conversation.id, it)
            }
        runState = DemoActivityState.runCoordinator.snapshot().state
        floatingWindow.clear()
        syncTranscript()
        renderConversation()
        updateCapabilityBanner()
    }

    private fun createNewConversation() {
        if (runState.isBusy || DemoActivityState.runCoordinator.isRunning()) stopAgent()
        val conversation = conversationStore.create()
        activeConversation = conversation
        DemoActivityState.activeConversationId = conversation.id
        DemoActivityState.runCoordinator.resetForConversation(conversation.id)
        session = createSession(conversation)
        DemoActivityState.rememberSession(conversation.id, session)
        runState = DemoActivityState.runCoordinator.snapshot().state
        floatingWindow.clear()
        syncTranscript()
        renderConversation()
    }

    private fun showConversationHistory() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
        }
        val newButton = TextView(this).apply {
            text = "+  新建会话"
            textSize = 15f
            setTextColor(Ui.MintDark)
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val listScroll = ScrollView(this).apply {
            addView(listContainer)
        }
        root.addView(newButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(listScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(420)
        ))

        lateinit var dialog: AlertDialog
        fun populate() {
            listContainer.removeAllViews()
            conversationStore.list().forEach { conversation ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(8), 0, dp(8))
                    background = Ui.rounded(
                        this@MainActivity,
                        if (conversation.id == activeConversation.id) Ui.SurfaceSoft else Ui.SurfaceElevated,
                        12
                    )
                    isClickable = true
                    isFocusable = true
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val title = TextView(this).apply {
                    text = conversation.title
                    textSize = 15f
                    setTextColor(Ui.TextPrimary)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val meta = TextView(this).apply {
                    text = "${conversation.messages.count { it.role == "user" }} 条消息 · ${formatConversationTime(conversation.updatedAt)}"
                    textSize = 11f
                    setTextColor(Ui.TextMuted)
                    setPadding(0, dp(3), 0, 0)
                }
                info.addView(title)
                info.addView(meta)
                row.addView(info, LinearLayout.LayoutParams(0, dp(58), 1f))
                val actions = TextView(this).apply {
                    text = "⋯"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(Ui.TextSecondary)
                    contentDescription = "会话操作"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showConversationActions(conversation) { populate() }
                    }
                }
                row.addView(actions, LinearLayout.LayoutParams(dp(44), dp(58)))
                row.setOnClickListener {
                    selectConversation(conversation.id)
                    dialog.dismiss()
                }
                listContainer.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }
        newButton.setOnClickListener {
            createNewConversation()
            dialog.dismiss()
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("会话历史")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener { populate() }
        dialog.show()
    }

    private fun showConversationActions(
        conversation: DemoConversation,
        onChanged: () -> Unit
    ) {
        AlertDialog.Builder(this)
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
        }
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
            .show()
    }

    private fun formatConversationTime(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

    private fun createSession(conversation: DemoConversation): AgentSession {
        val messages = mutableListOf<AgentMessage>(
            AgentMessage.System(
                "你是一个有用的 AI 助手。直接回答用户问题，简洁明了；如果需要调用工具，先说明下一步并在工具完成后给出清晰结果。"
            )
        )
        conversation.messages.forEach { stored ->
            when (stored.role) {
                "user" -> messages += AgentMessage.User(stored.content)
                "assistant" -> messages += AgentMessage.Assistant(stored.content)
            }
        }
        return AgentSession(conversation.id, messages)
    }

    private object PlaceholderProvider : LLMProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse {
            return ModelResponse(content = "请先在设置中配置 API 源（URL、模型名称、API Key）。")
        }
    }

    private companion object {
        const val KEY_DRAFT = "demo_draft"
        const val MAX_VISIBLE_CHAT_MESSAGES = 100
    }

    private val accessibilityScreenSkill = AndroidSkill(
        id = "accessibility-screen-reader",
        description = "Screen reading and UI interaction via accessibility service. Use to read the current screen, find elements, click buttons, type text, scroll lists.",
        triggers = listOf(
            "screen", "ui", "click", "tap", "button", "scroll", "type", "input",
            "interface", "element", "view", "layout", "page", "app",
            "屏幕", "界面", "点击", "按钮", "滚动", "输入", "页面", "操作"
        ),
        instructions = """
            You are operating inside a normal Android host application. You can see and interact with other Android apps only through the host AccessibilityService and the registered Android tools.

            **Before cross-app UI work:**
            - Call get_android_accessibility_status first.
            - Continue to screen_read_ui_tree only when readyForScreenAutomation=true.
            - If enabledByUser=false, call open_android_accessibility_settings, tell the user to enable the service manually, and wait for a new status check.

            **Launching apps:**
            - Use find_android_app with the human app name first; do not guess package names or rely on a hardcoded package list.
            - If find_android_app returns multiple candidates, ask the user to disambiguate.
            - Use launch_android_app with the selected exact packageName. Opening an app does not require AccessibilityService; screen reading and clicking afterwards does.

            **Reading the screen:**
            - Use screen_read_ui_tree to get visible UI elements with nodeId, text, type, bounds.
            - Some apps (like WeChat) block UI tree reading — screen_read_ui_tree may return very few or 0 nodes.
            - When UI tree returns too few nodes, use screen_gesture to interact by coordinates instead.

            **Finding elements not on screen (IMPORTANT):**
            - Many screens (especially Settings, long lists) cannot show all items at once.
            - If screen_read_ui_tree does NOT contain the target element (by text, contentDesc, or viewId), you MUST scroll to find it. NEVER conclude "not found" without scrolling.
            - Scroll strategy for UI tree mode: find the nearest scrollable container (scrollable:true), use screen_perform_action with scroll_forward on that container's nodeId, then screen_read_ui_tree again to check.
            - Scroll strategy for gesture mode: use screen_gesture swipe_up at x=540, y=1200 to scroll down, then screen_read_ui_tree again.
            - Repeat scroll + read up to 10 times until you find the target or reach the bottom (content stops changing).
            - swipe_up scrolls content DOWN (you see items below). swipe_down scrolls UP (you see items above).

            **Interacting via UI tree (when available):**
            - Use screen_perform_action with nodeId for click, scroll, set_text.
            - Find scrollable containers (scrollable:true) and use scroll_forward/scroll_backward.

            **Interacting via gestures (for apps that block UI tree):**
            - screen_gesture provides coordinate-based actions: tap, long_press, swipe_up, swipe_down, swipe_left, swipe_right.
            - Typical screen size is about 1080x2400 (check bounds from UI tree for your device).
            - Common coordinates: top area y=200-400, middle y=800-1200, bottom y=1600-2000.
            - To scroll a list: use swipe_up at center-x, mid-screen-y to scroll down. swipe_down to scroll up.
            - To click a specific spot: use tap with estimated coordinates.

            **Strategy for apps like WeChat:**
            1. find_android_app, then launch_android_app to open the app.
            2. get_android_accessibility_status, then screen_read_ui_tree — if nodeCount is very low (0-20), the app may block UI tree.
            3. Switch to gesture mode: use swipe_up/swipe_down to scroll, tap to click by coordinates.
            4. After each gesture, screen_read_ui_tree again — systemui elements may give clues about current screen.
            5. Use swipe_up at x=540, y=1200 to scroll down the chat list or contacts list.
            6. Use tap at estimated coordinates to click on items.

            Global actions (screen_global_action, no nodeId needed):
            - back: press the Back button
            - home: press the Home button
            - recents: open recent apps
            - notifications: open notification shade
            - quick_settings: open quick settings

            Submitting input:
            - After using screen_perform_action with set_text on an input field, use screen_press_key with key="enter" to submit (search, send, go).
            - screen_press_key triggers the IME action on the currently focused input field. It works for search boxes, chat input, browser address bars, login forms, etc.
            - Typical flow: screen_read_ui_tree -> screen_perform_action(set_text) -> screen_press_key(enter) -> screen_read_ui_tree to verify.

            Important:
            - Do not use terminal_bash_execute, am, pm, or screen icon searching to launch another app.
            - Always try screen_read_ui_tree first. If it returns enough data, use screen_perform_action.
            - If screen_read_ui_tree returns very few nodes, switch to screen_gesture.
            - After any gesture or action, re-read the screen to check the result.
            - For scrolling, swipe_up scrolls content DOWN (see more below), swipe_down scrolls UP.
            - Do NOT give up after one attempt. Scroll multiple times if needed.
        """.trimIndent(),
        methods = listOf(
            AndroidSkillMethod(
                toolName = "screen_gesture",
                purpose = "Performs coordinate-based gestures (tap, swipe, long_press) on screen.",
                whenToUse = "When UI tree reading is blocked (e.g. WeChat returns 0 nodes) and you need to interact by coordinates. Also for scrolling lists in blocked apps.",
                resultSemantics = "success=true means gesture was dispatched. Check the screen afterwards."
            ),
            AndroidSkillMethod(
                toolName = "find_android_app",
                purpose = "Finds launchable app candidates by human-visible name or package name.",
                whenToUse = "Before launching an app when the user did not provide an exact package name.",
                resultSemantics = "count=0 means no match; ambiguous=true means ask the user to choose."
            ),
            AndroidSkillMethod(
                toolName = "launch_android_app",
                purpose = "Launches an installed app by exact package name through Android's launcher Intent.",
                whenToUse = "After find_android_app returns a selected packageName.",
                resultSemantics = "launched=true means Android accepted the request. Check get_android_accessibility_status and read the UI to verify the next step."
            ),
            AndroidSkillMethod(
                toolName = "get_android_accessibility_status",
                purpose = "Checks whether cross-app screen automation is currently ready.",
                whenToUse = "Before screen_read_ui_tree or any screen action in another app.",
                resultSemantics = "readyForScreenAutomation=true is required."
            ),
            AndroidSkillMethod(
                toolName = "open_android_accessibility_settings",
                purpose = "Opens Android Accessibility settings for the user to enable the host service.",
                whenToUse = "When get_android_accessibility_status reports enabledByUser=false.",
                resultSemantics = "userMustEnableManually=true means wait for the user and check status again."
            ),
            AndroidSkillMethod(
                toolName = "screen_read_ui_tree",
                purpose = "Reads the current screen UI tree and returns all visible elements with their properties.",
                whenToUse = "Use first to understand what is on screen before performing any action. Always call before screen_perform_action.",
                resultSemantics = "Returns a JSON array of UI elements. Each has nodeId, type, text, bounds, and capability flags. Use nodeId for subsequent actions."
            ),
            AndroidSkillMethod(
                toolName = "screen_perform_action",
                purpose = "Performs an action (click, scroll, type, etc.) on a UI element identified by nodeId.",
                whenToUse = "Use after screen_read_ui_tree to interact with a specific element. Requires nodeId from the UI tree.",
                resultSemantics = "success=true means the action was dispatched. The screen may change as a result. Re-read the UI tree to verify."
            ),
            AndroidSkillMethod(
                toolName = "screen_global_action",
                purpose = "Performs a global system action (back, home, recents, notifications, etc.) without targeting a specific UI element.",
                whenToUse = "Use for navigation and system-level actions. No nodeId needed.",
                resultSemantics = "success=true means the system accepted the action. The screen state will change accordingly."
            ),
            AndroidSkillMethod(
                toolName = "screen_press_key",
                purpose = "Presses a keyboard key. Currently supports 'enter' to submit the focused input field.",
                whenToUse = "After set_text on an input field, use screen_press_key with key='enter' to trigger search, send, go, or done.",
                resultSemantics = "success=true means the IME action was triggered. The screen may change (e.g. search results appear, message sent)."
            )
        )
    )
}

internal fun shouldShowFloatingWindowOnPause(
    overlayPermissionGranted: Boolean
): Boolean = AgentOverlayPolicy.shouldShowOnPause(
    overlayPermissionGranted = overlayPermissionGranted,
    activityResumed = false
)
