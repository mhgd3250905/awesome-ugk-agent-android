package com.ugk.pi.android.testapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 独立的通用设置页面，负责管理 API 供应商预设、模型规格参数、通信连通性检测、
 * 平台额度查询、全授权安全模式以及全局界面主题切换。
 */
class SettingsActivity : Activity() {

    private val apiStore by lazy { ApiProviderSettingsStore(this) }
    private val authorizationStore by lazy { AgentAuthorizationSettingsStore(this) }
    private val scope = CoroutineScope(Dispatchers.Main)

    private var selectedConfigId: String? = null
    private var selectedContextWindow: String = ContextProfile.DEFAULT_CONFIG
    private var selectedMaxOutputTokens: Int = 8192

    private val contextWindowOptions = ContextProfile.uiOrdered
    private val maxOutputOptions = listOf(
        4096 to "4K",
        8192 to "8K (通用)",
        16384 to "16K",
        32768 to "32K",
        65536 to "64K",
        131072 to "128K (超大)"
    )

    private var selectedAutoCompaction: Boolean = true
    private var selectedCompactionThreshold: Double = 0.70

    private val thresholdOptions = listOf(
        0.60 to "60%",
        0.65 to "65%",
        0.70 to "70% (推荐)",
        0.75 to "75%",
        0.80 to "80%"
    )

    // UI 组件引用
    private lateinit var rootLayout: LinearLayout
    private lateinit var scrollContainer: ScrollView
    private lateinit var headerBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var backButton: TextView

    private lateinit var themeCard: LinearLayout
    private lateinit var themeTitle: TextView
    private val themeButtons = mutableMapOf<AppThemeMode, TextView>()

    private lateinit var authCard: LinearLayout
    private lateinit var fullAuthSwitch: Switch
    private lateinit var authHintText: TextView

    private lateinit var presetCard: LinearLayout
    private lateinit var presetTitle: TextView
    private lateinit var chipRow: LinearLayout

    private lateinit var configCard: LinearLayout
    private lateinit var configCardTitle: TextView
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var keyInput: EditText

    private lateinit var contextCard: LinearLayout
    private lateinit var contextTitle: TextView
    private lateinit var contextRow: LinearLayout

    private lateinit var maxOutputCard: LinearLayout
    private lateinit var maxOutputTitle: TextView
    private lateinit var maxOutputRow: LinearLayout

    private lateinit var compactionCard: LinearLayout
    private lateinit var compactionSwitch: Switch
    private lateinit var compactionHintText: TextView
    private lateinit var compactionThresholdTitle: TextView
    private lateinit var thresholdRow: LinearLayout

    private lateinit var testCard: LinearLayout
    private lateinit var testButton: TextView
    private lateinit var testStatusCard: LinearLayout
    private lateinit var connectivityResultText: TextView
    private lateinit var balanceResultText: TextView
    private lateinit var errorText: TextView

    private lateinit var bottomActionBar: LinearLayout
    private lateinit var saveButton: TextView
    private lateinit var deleteButton: TextView

    private val themeListener: (Boolean) -> Unit = {
        runOnUiThread { applyTheme() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        ThemeManager.addListener(themeListener)

        // 沉浸式状态栏与边缘延展
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

        setContentView(buildUi())
        applyWindowInsets()
        applyTheme()

        // 加载当前激活的配置并初始化表单
        val active = apiStore.activeConfig()
        loadConfigToInputs(active)
        renderChips()
    }

    override fun onResume() {
        super.onResume()
        DemoActivityState.floatingWindow(this).hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(themeListener)
        scope.cancel()
    }

    private fun buildUi(): View {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@SettingsActivity, Ui.Surface, 0)
        }

        // 1. 顶部标题栏 (Back Button + Title + Subtitle)
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        backButton = TextView(this).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TextPrimary)
            background = Ui.clickableRounded(this@SettingsActivity, Ui.SurfaceSoft, Ui.SurfaceSubtle, 12, Ui.Outline)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleText = TextView(this).apply {
            text = "设置与选项"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        subtitleText = TextView(this).apply {
            text = "管理 API 源、模型参数规格与偏好"
            textSize = 12f
            setTextColor(Ui.TextSecondary)
            setPadding(0, dp(2), 0, 0)
        }
        titleContainer.addView(titleText)
        titleContainer.addView(subtitleText)

        headerBar.addView(backButton, LinearLayout.LayoutParams(dp(44), dp(40)))
        headerBar.addView(titleContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(headerBar)

        // 2. 页面主滚动区
        scrollContainer = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        scrollContainer.addView(contentLayout)

        // 2.1 主题模式卡片
        themeCard = sectionCard()
        themeTitle = sectionTitle("界面主题")
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        AppThemeMode.values().forEach { mode ->
            val btn = TextView(this).apply {
                text = "${mode.icon} ${mode.displayName}"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    ThemeManager.setMode(this@SettingsActivity, mode)
                }
            }
            themeButtons[mode] = btn
            themeRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (mode != AppThemeMode.LIGHT) marginStart = dp(6)
            })
        }
        themeCard.addView(themeTitle)
        themeCard.addView(themeRow)
        contentLayout.addView(themeCard, cardLayoutParams())

        // 2.2 全授权模式卡片
        authCard = sectionCard()
        fullAuthSwitch = Switch(this).apply {
            text = "全授权模式"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
            isChecked = authorizationStore.isFullAuthorizationEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                authorizationStore.setFullAuthorizationEnabled(isChecked)
            }
        }
        authHintText = TextView(this).apply {
            text = "开启后，Agent 的高影响操作不再弹出确认。仅建议在受控测试设备使用。"
            textSize = 12f
            setTextColor(Ui.TextSecondary)
            setPadding(0, dp(4), 0, 0)
        }
        authCard.addView(fullAuthSwitch)
        authCard.addView(authHintText)
        contentLayout.addView(authCard, cardLayoutParams())

        // 2.3 API 预设选择栏
        presetCard = sectionCard()
        presetTitle = sectionTitle("API 配置预设")
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipScroll.addView(chipRow)
        presetCard.addView(presetTitle)
        presetCard.addView(chipScroll)
        contentLayout.addView(presetCard, cardLayoutParams())

        // 2.4 API 表单字段卡片
        configCard = sectionCard()
        configCardTitle = sectionTitle("API 接口配置")
        nameInput = settingsInput("配置备注名称 (可选，例: DeepSeek 官方)")
        urlInput = settingsInput("URL 端点 (例: https://api.deepseek.com/anthropic)")
        modelInput = settingsInput("模型名称 (例: deepseek-chat)")
        keyInput = settingsInput("API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        configCard.addView(configCardTitle)
        configCard.addView(nameInput, fieldLayoutParams())
        configCard.addView(urlInput, fieldLayoutParams())
        configCard.addView(modelInput, fieldLayoutParams())
        configCard.addView(keyInput, fieldLayoutParams())
        contentLayout.addView(configCard, cardLayoutParams())

        // 2.5 上下文窗口选择卡片
        contextCard = sectionCard()
        contextTitle = sectionTitle("上下文总窗口 (Context Window)")
        val contextScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        contextRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contextScroll.addView(contextRow)
        contextCard.addView(contextTitle)
        contextCard.addView(contextScroll)
        contentLayout.addView(contextCard, cardLayoutParams())

        // 2.6 单次最大输出生成卡片
        maxOutputCard = sectionCard()
        maxOutputTitle = sectionTitle("单次最大输出生成 (Max Output Tokens)")
        val maxOutputScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        maxOutputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        maxOutputScroll.addView(maxOutputRow)
        maxOutputCard.addView(maxOutputTitle)
        maxOutputCard.addView(maxOutputScroll)
        contentLayout.addView(maxOutputCard, cardLayoutParams())

        // 2.7 上下文自动压缩卡片
        compactionCard = sectionCard()
        compactionSwitch = Switch(this).apply {
            text = "上下文自动压缩 (Context Compaction)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
            isChecked = selectedAutoCompaction
            setOnCheckedChangeListener { _, isChecked ->
                selectedAutoCompaction = isChecked
                renderThresholdChips()
            }
        }
        compactionHintText = TextView(this).apply {
            text = "当上下文使用率达到设定阈值时，自动折叠历史大工具输出并提炼阶段摘要，保障长对话不中断。"
            textSize = 12f
            setTextColor(Ui.TextSecondary)
            setPadding(0, dp(4), 0, dp(8))
        }
        compactionThresholdTitle = sectionTitle("触发压缩阈值 (Trigger Threshold)").apply {
            textSize = 12.5f
        }
        val thresholdScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, 0)
        }
        thresholdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thresholdScroll.addView(thresholdRow)

        compactionCard.addView(compactionSwitch)
        compactionCard.addView(compactionHintText)
        compactionCard.addView(compactionThresholdTitle)
        compactionCard.addView(thresholdScroll)
        contentLayout.addView(compactionCard, cardLayoutParams())

        // 2.8 通信检测与额度卡片
        testCard = sectionCard()
        testButton = TextView(this).apply {
            text = "⚡ 检测通信与平台额度"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Ui.Mint)
            setTypeface(null, Typeface.BOLD)
            background = Ui.clickableRounded(this@SettingsActivity, Ui.SurfaceSoft, Ui.SurfaceSubtle, 12, Ui.MintStroke)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { runConnectivityAndQuotaTest() }
        }
        testStatusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.Outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        connectivityResultText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        balanceResultText = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, dp(4), 0, 0)
        }
        testStatusCard.addView(connectivityResultText)
        testStatusCard.addView(balanceResultText)

        testCard.addView(testButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        testCard.addView(testStatusCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        contentLayout.addView(testCard, cardLayoutParams())

        // 2.8 错误提示
        errorText = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Ui.Danger)
            textSize = 13f
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        contentLayout.addView(errorText)

        // 2.9 底部操作按钮栏
        bottomActionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        saveButton = TextView(this).apply {
            text = "保存并启用此配置"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ui.SurfaceElevated)
            setTypeface(null, Typeface.BOLD)
            background = Ui.clickableRounded(this@SettingsActivity, Ui.Mint, Ui.MintDark, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { saveAndActivateCurrentConfig() }
        }
        deleteButton = TextView(this).apply {
            text = "删除此配置"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.Danger)
            background = Ui.clickableRounded(this@SettingsActivity, Ui.DangerSoft, Ui.SurfaceSubtle, 12, Ui.Danger)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { deleteCurrentConfig() }
        }
        bottomActionBar.addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bottomActionBar.addView(deleteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        contentLayout.addView(bottomActionBar)

        rootLayout.addView(scrollContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return rootLayout
    }

    private fun loadConfigToInputs(config: ApiProviderConfig?) {
        if (config != null) {
            selectedConfigId = config.id
            nameInput.setText(config.name.orEmpty())
            urlInput.setText(config.baseUrl)
            modelInput.setText(config.model)
            keyInput.setText(config.apiKey)
            selectedContextWindow = ContextProfile.configValueOrDefault(config.contextWindow)
            selectedMaxOutputTokens = config.maxOutputTokens ?: 8192
            selectedAutoCompaction = config.autoCompaction ?: true
            selectedCompactionThreshold = config.compactionThreshold ?: 0.70
            deleteButton.visibility = View.VISIBLE
        } else {
            selectedConfigId = null
            nameInput.setText("")
            urlInput.setText("")
            modelInput.setText("")
            keyInput.setText("")
            selectedContextWindow = ContextProfile.DEFAULT_CONFIG
            selectedMaxOutputTokens = 8192
            selectedAutoCompaction = true
            selectedCompactionThreshold = 0.70
            deleteButton.visibility = View.GONE
        }
        if (::compactionSwitch.isInitialized) {
            compactionSwitch.isChecked = selectedAutoCompaction
        }
        errorText.visibility = View.GONE
        testStatusCard.visibility = View.GONE
        renderContextWindowChips()
        renderMaxOutputChips()
        renderThresholdChips()
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val state = apiStore.load()
        state.configs.forEach { cfg ->
            val isSelected = cfg.id == selectedConfigId
            val isCurrentActive = cfg.id == state.activeId
            val chip = TextView(this).apply {
                val prefix = if (isCurrentActive) "● " else ""
                text = "$prefix${cfg.displayName()}"
                textSize = 12.5f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                if (isSelected) {
                    setTextColor(Ui.SurfaceElevated)
                    background = Ui.rounded(this@SettingsActivity, Ui.Mint, 14)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 14, Ui.Outline)
                }
                setOnClickListener {
                    loadConfigToInputs(cfg)
                    renderChips()
                }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }

        val newChip = TextView(this).apply {
            text = "+ 新增配置"
            textSize = 12.5f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val isNew = selectedConfigId == null
            if (isNew) {
                setTextColor(Ui.SurfaceElevated)
                background = Ui.rounded(this@SettingsActivity, Ui.Mint, 14)
            } else {
                setTextColor(Ui.Mint)
                background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 14, Ui.MintStroke)
            }
            setOnClickListener {
                loadConfigToInputs(null)
                renderChips()
            }
        }
        chipRow.addView(newChip)
    }

    private fun renderContextWindowChips() {
        contextRow.removeAllViews()
        contextWindowOptions.forEach { profile ->
            val isSelected = profile.stableId.equals(selectedContextWindow, ignoreCase = true)
            val chip = TextView(this).apply {
                text = profile.displayLabel
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                if (isSelected) {
                    setTextColor(Ui.SurfaceElevated)
                    background = Ui.rounded(this@SettingsActivity, Ui.Mint, 14)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 14, Ui.Outline)
                }
                setOnClickListener {
                    selectedContextWindow = profile.stableId
                    renderContextWindowChips()
                }
            }
            contextRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun renderMaxOutputChips() {
        maxOutputRow.removeAllViews()
        maxOutputOptions.forEach { (tokens, label) ->
            val isSelected = tokens == selectedMaxOutputTokens
            val chip = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                if (isSelected) {
                    setTextColor(Ui.SurfaceElevated)
                    background = Ui.rounded(this@SettingsActivity, Ui.Mint, 14)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 14, Ui.Outline)
                }
                setOnClickListener {
                    selectedMaxOutputTokens = tokens
                    renderMaxOutputChips()
                }
            }
            maxOutputRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun renderThresholdChips() {
        if (!::thresholdRow.isInitialized) return
        thresholdRow.removeAllViews()
        thresholdOptions.forEach { (ratio, label) ->
            val isSelected = Math.abs(ratio - selectedCompactionThreshold) < 0.001
            val chip = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isEnabled = selectedAutoCompaction
                alpha = if (selectedAutoCompaction) 1.0f else 0.45f
                if (isSelected && selectedAutoCompaction) {
                    setTextColor(Ui.SurfaceElevated)
                    background = Ui.rounded(this@SettingsActivity, Ui.Mint, 14)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 14, Ui.Outline)
                }
                setOnClickListener {
                    if (selectedAutoCompaction) {
                        selectedCompactionThreshold = ratio
                        renderThresholdChips()
                    }
                }
            }
            thresholdRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun runConnectivityAndQuotaTest() {
        val url = urlInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        val apiKey = keyInput.text.toString().trim()
        if (url.isBlank() || model.isBlank() || apiKey.isBlank()) {
            errorText.text = "请先填写 URL、模型名称和 API Key"
            errorText.visibility = View.VISIBLE
            return
        }
        errorText.visibility = View.GONE
        testButton.isEnabled = false
        testButton.text = "正在检测通信与额度..."
        testStatusCard.visibility = View.GONE

        scope.launch {
            try {
                val summary = ApiQuotaAndConnectivityService.testAndQuery(url, apiKey, model)
                testButton.isEnabled = true
                testButton.text = "⚡ 检测通信与平台额度"
                testStatusCard.visibility = View.VISIBLE

                if (summary.connectivity.success) {
                    connectivityResultText.setTextColor(Ui.Success)
                    connectivityResultText.text = "✓ ${summary.connectivity.message}"
                } else {
                    connectivityResultText.setTextColor(Ui.Danger)
                    connectivityResultText.text = "✕ ${summary.connectivity.message}"
                }

                val balance = summary.balance
                if (balance != null && balance.supported) {
                    balanceResultText.visibility = View.VISIBLE
                    if (!balance.balanceText.isNullOrBlank()) {
                        balanceResultText.setTextColor(Ui.MintDark)
                        balanceResultText.text = "💰 ${balance.provider.displayName} 额度: ${balance.balanceText}"
                    } else if (!balance.error.isNullOrBlank()) {
                        balanceResultText.setTextColor(Ui.TextSecondary)
                        balanceResultText.text = "💰 额度查询: ${balance.error}"
                    }
                } else if (balance != null && !balance.supported) {
                    balanceResultText.visibility = View.VISIBLE
                    balanceResultText.setTextColor(Ui.TextMuted)
                    balanceResultText.text = "ℹ️ ${balance.error ?: "该平台未开放公开额度接口"}"
                } else {
                    balanceResultText.visibility = View.GONE
                }
            } catch (e: Exception) {
                testButton.isEnabled = true
                testButton.text = "⚡ 检测通信与平台额度"
                testStatusCard.visibility = View.VISIBLE
                connectivityResultText.setTextColor(Ui.Danger)
                connectivityResultText.text = "✕ 检测异常: ${e.message}"
                balanceResultText.visibility = View.GONE
            }
        }
    }

    private fun saveAndActivateCurrentConfig() {
        val baseUrl = urlInput.text.toString().trim()
        val model = modelInput.text.toString().trim()
        val apiKey = keyInput.text.toString().trim()
        val name = nameInput.text.toString().trim().ifBlank { null }

        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            errorText.text = "请填写所有必填字段（URL、模型名称和 API Key）"
            errorText.visibility = View.VISIBLE
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            errorText.text = "URL 需以 http:// 或 https:// 开头"
            errorText.visibility = View.VISIBLE
            return
        }

        val config = ApiProviderConfig(
            id = selectedConfigId ?: UUID.randomUUID().toString(),
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            name = name,
            contextWindow = selectedContextWindow,
            maxOutputTokens = selectedMaxOutputTokens,
            autoCompaction = selectedAutoCompaction,
            compactionThreshold = selectedCompactionThreshold
        )
        apiStore.upsertAndActivate(config)
        Toast.makeText(this, "配置已保存并启用", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun deleteCurrentConfig() {
        val id = selectedConfigId ?: return
        val nextState = apiStore.delete(id)
        Toast.makeText(this, "配置已删除", Toast.LENGTH_SHORT).show()
        loadConfigToInputs(nextState.activeConfig())
        renderChips()
    }

    private fun applyTheme() {
        rootLayout.background = Ui.rounded(this, Ui.Surface, 0)
        titleText.setTextColor(Ui.TextPrimary)
        subtitleText.setTextColor(Ui.TextSecondary)
        backButton.setTextColor(Ui.TextPrimary)
        backButton.background = Ui.clickableRounded(this, Ui.SurfaceSoft, Ui.SurfaceSubtle, 12, Ui.Outline)

        themeCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        themeTitle.setTextColor(Ui.TextPrimary)
        themeButtons.forEach { (mode, btn) ->
            val isSelected = ThemeManager.currentMode == mode
            btn.setTextColor(if (isSelected) Ui.SurfaceElevated else Ui.TextSecondary)
            btn.background = if (isSelected) {
                Ui.rounded(this, Ui.Mint, 10)
            } else {
                Ui.rounded(this, Ui.SurfaceSoft, 10, Ui.Outline)
            }
        }

        authCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        fullAuthSwitch.setTextColor(Ui.TextPrimary)
        authHintText.setTextColor(Ui.TextSecondary)

        presetCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        presetTitle.setTextColor(Ui.TextPrimary)
        renderChips()

        configCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        configCardTitle.setTextColor(Ui.TextPrimary)
        listOf(nameInput, urlInput, modelInput, keyInput).forEach { input ->
            input.setTextColor(Ui.TextPrimary)
            input.setHintTextColor(Ui.TextMuted)
            input.background = Ui.rounded(this, Ui.SurfaceSoft, 12, Ui.Outline)
        }

        contextCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        contextTitle.setTextColor(Ui.TextPrimary)
        renderContextWindowChips()

        maxOutputCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        maxOutputTitle.setTextColor(Ui.TextPrimary)
        renderMaxOutputChips()

        compactionCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        compactionSwitch.setTextColor(Ui.TextPrimary)
        compactionHintText.setTextColor(Ui.TextSecondary)
        compactionThresholdTitle.setTextColor(Ui.TextPrimary)
        renderThresholdChips()

        testCard.background = Ui.rounded(this, Ui.SurfaceSubtle, 14, Ui.Outline)
        testButton.setTextColor(Ui.Mint)
        testButton.background = Ui.clickableRounded(this, Ui.SurfaceSoft, Ui.SurfaceSubtle, 12, Ui.MintStroke)
        testStatusCard.background = Ui.rounded(this, Ui.SurfaceSoft, 10, Ui.Outline)

        saveButton.setTextColor(Ui.SurfaceElevated)
        saveButton.background = Ui.clickableRounded(this, Ui.Mint, Ui.MintDark, 14)
        deleteButton.setTextColor(Ui.Danger)
        deleteButton.background = Ui.clickableRounded(this, Ui.DangerSoft, Ui.SurfaceSubtle, 12, Ui.Danger)

        // 系统状态栏图标颜色
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
    }

    private fun applyWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootLayout.setOnApplyWindowInsetsListener { _, insets ->
                val statusBar = insets.getInsets(WindowInsets.Type.statusBars())
                val navBar = insets.getInsets(WindowInsets.Type.navigationBars())
                headerBar.setPadding(dp(16), statusBar.top + dp(8), dp(16), dp(8))
                scrollContainer.setPadding(0, 0, 0, navBar.bottom)
                insets
            }
        } else {
            headerBar.setPadding(dp(16), dp(36), dp(16), dp(8))
        }
    }

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSubtle, 14, Ui.Outline)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Ui.TextPrimary)
    }

    private fun settingsInput(hint: String): EditText = EditText(this).apply {
        setSingleLine(true)
        this.hint = hint
        textSize = 14f
        setTextColor(Ui.TextPrimary)
        setHintTextColor(Ui.TextMuted)
        setPadding(dp(14), 0, dp(14), 0)
        background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 12, Ui.Outline)
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }

    private fun fieldLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(48)
    ).apply { topMargin = dp(8) }
}
