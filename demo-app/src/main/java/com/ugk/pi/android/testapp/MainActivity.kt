package com.ugk.pi.android.testapp

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.ugk.pi.android.AgentCapabilityPlugin
import com.ugk.pi.android.AgentEvent
import com.ugk.pi.android.AgentMessage
import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillMethod
import com.ugk.pi.android.AnthropicMessagesProvider
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val store by lazy { ApiProviderSettingsStore(this) }
    private var runtime: AgentRuntime? = null
    private var session = createSession()
    private var runJob: Job? = null

    private lateinit var messageContainer: LinearLayout
    private lateinit var messageScrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: View
    private lateinit var progressBar: ProgressBar
    private lateinit var providerLabel: TextView
    private lateinit var accessibilityIndicator: TextView
    private val floatingWindow by lazy {
        AgentFloatingWindow(applicationContext).apply {
            onSendMessage = { text -> runAgent(text) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        rebuildRuntime()
        checkAccessibility()
        checkOverlayPermission()
        if (Settings.canDrawOverlays(this)) {
            floatingWindow.show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityIndicator()
        updateOverlayIndicator()
        if (Settings.canDrawOverlays(this) && !floatingWindow.isShowing()) {
            floatingWindow.show()
        }
    }

    override fun onPause() {
        super.onPause()
        if (runJob?.isActive == true) {
            floatingWindow.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingWindow.hide()
    }

    private fun checkAccessibility() {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("本应用需要无障碍权限才能正常工作。\n\n点击「去设置」跳转到系统无障碍页面，找到「Agent Test」并开启。")
                .setCancelable(false)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("稍后") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
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

    private fun updateAccessibilityIndicator() {
        val enabled = isAccessibilityEnabled()
        val label = if (enabled) "无障碍: 已开启" else "无障碍: 未开启"
        accessibilityIndicator.text = label
        accessibilityIndicator.setTextColor(if (enabled) Ui.Mint else Ui.Danger)
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("Agent 操作其他应用时，需要悬浮窗显示操作进度。\n\n点击「去设置」开启「显示在其他应用上层」权限。")
                .setCancelable(false)
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("稍后") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun updateOverlayIndicator() {
        // could add an overlay indicator in header, but keeping it simple
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 245))
        }

        providerLabel = TextView(this).apply {
            setTextColor(Ui.TextSecondary)
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        val settingsButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Ui.TextSecondary)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { openSettings() }
        }

        accessibilityIndicator = TextView(this).apply {
            textSize = 11f
            setPadding(dp(16), 0, dp(16), dp(4))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            addView(providerLabel, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(settingsButton, dp(44), dp(44))
        }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(accessibilityIndicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        messageScrollView = ScrollView(this).apply {
            addView(messageContainer)
        }

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }

        inputField = EditText(this).apply {
            hint = "输入消息..."
            setHintTextColor(Ui.TextMuted)
            setTextColor(Ui.TextPrimary)
            textSize = 15f
            setSingleLine(true)
            background = null
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        sendButton = TextView(this).apply {
            text = "发送"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = Ui.rounded(this@MainActivity, Ui.Mint, 10)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { sendMessage() }
        }

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(inputField, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(sendButton, LinearLayout.LayoutParams(dp(72), dp(44)).apply {
                marginStart = dp(8)
            })
        }

        root.addView(messageScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(inputBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun openSettings() {
        ApiSettingsDialog(this, store) { config ->
            rebuildRuntime()
        }.show()
    }

    private fun rebuildRuntime() {
        val config = store.activeConfig()
        val provider: LLMProvider = if (config != null) {
            AnthropicMessagesProvider(
                apiKey = config.apiKey,
                model = config.model,
                baseUrl = config.baseUrl
            )
        } else {
            PlaceholderProvider
        }
        runtime = AgentRuntime.Builder()
            .llmProvider(provider)
            .register(object : AgentCapabilityPlugin {
                override val id = "accessibility-screen"
                override fun tools() = listOf(ScreenReadUiTreeTool(), ScreenPerformActionTool(), ScreenGlobalActionTool(), ScreenLaunchAppTool(), ScreenGestureTool(), ScreenPressKeyTool())
                override fun skills() = listOf(accessibilityScreenSkill)
            })
            .build()
        session = createSession()
        messageContainer.removeAllViews()
        providerLabel.text = config?.displayName() ?: "未配置 API 源"
        if (config == null) {
            addStatusMessage("请先点击右上角齿轮图标配置 API 源。")
        }
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isBlank()) return
        inputField.setText("")
        runAgent(text)
    }

    private fun runAgent(text: String) {
        val currentRuntime = runtime ?: return
        addBubble(text, isUser = true)

        runJob?.cancel()
        runJob = CoroutineScope(Dispatchers.Main).launch {
            progressBar.visibility = View.VISIBLE
            sendButton.isEnabled = false
            floatingWindow.setSending(true)

            var assistantBubble: TextView? = null

            floatingWindow.clear()
            floatingWindow.setStatus("思考中...")
            floatingWindow.addLog("用户: $text")

            currentRuntime.run(session, text)
                .catch { e ->
                    if (e !is CancellationException) {
                        addStatusMessage("错误: ${e.message}")
                        floatingWindow.setStatus("出错了")
                        floatingWindow.addLog("错误: ${e.message}")
                    }
                }
                .collect { event ->
                    when (event) {
                        is AgentEvent.ToolStarted -> {
                            val bubble = assistantBubble ?: addBubble("", isUser = false).also { assistantBubble = it }
                            bubble.append("\n🔧 调用工具: ${event.call.name}")
                            scrollToEnd()
                            floatingWindow.setStatus("调用工具")
                            floatingWindow.addLog("→ ${event.call.name}")
                        }
                        is AgentEvent.ToolFinished -> {
                            val preview = event.result.content.take(100)
                            addStatusMessage("  ↳ ${event.result.name}: $preview")
                            scrollToEnd()
                            val success = !event.result.isError
                            floatingWindow.setStatus(if (success) "工具完成" else "工具失败")
                            floatingWindow.addLog("✓ ${event.result.name}: ${if (success) "成功" else "失败"}")
                        }
                        is AgentEvent.Completed -> {
                            if (assistantBubble != null) {
                                assistantBubble!!.text = event.content
                            } else {
                                addBubble(event.content, isUser = false)
                            }
                            floatingWindow.setStatus("完成")
                            floatingWindow.addLog("回答: ${event.content.take(80)}")
                        }
                        is AgentEvent.Failed -> {
                            addStatusMessage("失败: ${event.message}")
                            floatingWindow.setStatus("失败")
                            floatingWindow.addLog("失败: ${event.message}")
                        }
                        else -> Unit
                    }
                }

            progressBar.visibility = View.GONE
            sendButton.isEnabled = true
            floatingWindow.setSending(false)
            floatingWindow.setStatus("就绪")
        }
    }

    private fun addBubble(text: String, isUser: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Ui.TextPrimary)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val bg = if (isUser) {
                Ui.rounded(this@MainActivity, Ui.Mint, 14)
            } else {
                Ui.rounded(this@MainActivity, Color.WHITE, 14, Color.rgb(220, 220, 220))
            }
            background = bg
            if (isUser) setTextColor(Color.WHITE)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        messageContainer.addView(tv, params)
        scrollToEnd()
        return tv
    }

    private fun addStatusMessage(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Ui.TextMuted)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
            gravity = Gravity.START
        }
        messageContainer.addView(tv, params)
        scrollToEnd()
    }

    private fun scrollToEnd() {
        messageScrollView.post {
            messageScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun createSession(): AgentSession = AgentSession(
        id = "test-session",
        messages = mutableListOf(
            AgentMessage.System(
                "你是一个有用的 AI 助手。直接回答用户问题，简洁明了。"
            )
        )
    )

    private object PlaceholderProvider : LLMProvider {
        override suspend fun generate(request: ModelRequest): ModelResponse {
            return ModelResponse(content = "请先在设置中配置 API 源（URL、模型名称、API Key）。")
        }
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
            You can see and interact with the Android screen via accessibility tools.

            **Launching apps:**
            - Use screen_launch_app with the package name to open an app directly. This is the PREFERRED way to open apps.
            - Common package names: com.tencent.mm (WeChat/微信), com.android.settings (Settings), com.android.chrome (Chrome), com.tencent.mobileqq (QQ), com.ss.android.ugc.aweme (抖音), com.android.dialer (Phone).
            - If you don't know the package name, you can read the home screen first to find the app icon.

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
            1. screen_launch_app to open the app.
            2. screen_read_ui_tree — if nodeCount is very low (0-20), the app blocks UI tree.
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
                toolName = "screen_launch_app",
                purpose = "Launches an app by package name. Use to open apps directly.",
                whenToUse = "When user wants to open an app (e.g. 'open WeChat', 'open Settings'). Use this instead of searching for app icons on screen.",
                resultSemantics = "success means the app launch intent was sent. Wait a moment then read the UI tree to verify."
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
