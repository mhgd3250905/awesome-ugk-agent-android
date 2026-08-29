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
import android.widget.ImageButton
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
    private var selectedProtocol: ProviderProtocol = ProviderProtocol.AUTO
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

    private enum class TestStatusTone {
        NONE,
        SUCCESS,
        FAILURE
    }

    private enum class BalanceStatusTone {
        NONE,
        SUCCESS,
        ERROR,
        INFO
    }

    private var testStatusTone = TestStatusTone.NONE
    private var balanceStatusTone = BalanceStatusTone.NONE

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
    private lateinit var backButton: ImageButton

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
    private lateinit var protocolTitle: TextView
    private lateinit var protocolRow: LinearLayout
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
        (application as DemoApplication).processScope.overlayController.window.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        ThemeManager.removeListener(themeListener)
        scope.cancel()
    }

    private fun buildUi(): View {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@SettingsActivity, Ui.Background, 0)
        }

        // 1. 顶部标题栏 (Back Button + Title + Subtitle)
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        backButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = "返回上一页"
            isClickable = true
            isFocusable = true
            background = Ui.clickableRounded(this@SettingsActivity, Color.TRANSPARENT, Ui.SurfaceSoft, 12)
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

        headerBar.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
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
                text = mode.displayName
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
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
        protocolTitle = sectionTitle("API 协议").apply {
            textSize = 12.5f
            setPadding(0, dp(10), 0, 0)
        }
        val protocolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, 0)
        }
        protocolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        protocolScroll.addView(protocolRow)
        modelInput = settingsInput("模型名称 (例: deepseek-chat)")
        keyInput = settingsInput("API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        configCard.addView(configCardTitle)
        configCard.addView(nameInput, fieldLayoutParams())
        configCard.addView(urlInput, fieldLayoutParams())
        configCard.addView(protocolTitle)
        configCard.addView(protocolScroll)
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
            text = "检测通信与平台额度"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Ui.Primary)
            setTypeface(null, Typeface.BOLD)
            background = Ui.clickableRounded(this@SettingsActivity, Ui.SurfaceSoft, Ui.SurfaceSubtle, 12, Ui.OutlineFocus)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { runConnectivityAndQuotaTest() }
        }
        testStatusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
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
            setTextColor(Ui.OnPrimary)
            setTypeface(null, Typeface.BOLD)
            background = Ui.clickableRounded(this@SettingsActivity, Ui.Primary, Ui.PrimaryPressed, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { saveAndActivateCurrentConfig() }
        }
        deleteButton = TextView(this).apply {
            text = "删除此配置"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.stateColorList(
                normal = Ui.DangerOnContainer,
                pressed = Ui.DangerOnContainer
            ))
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
            selectedProtocol = config.protocol
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
            selectedProtocol = ProviderProtocol.AUTO
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
        renderProtocolChips()
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val state = apiStore.load()
        state.configs.forEach { cfg ->
            val isSelected = cfg.id == selectedConfigId
            val isCurrentActive = cfg.id == state.activeId
            val chip = TextView(this).apply {
                val prefix = if (isSelected) "✓ " else if (isCurrentActive) "● " else ""
                text = "$prefix${cfg.displayName()}"
                textSize = 12.5f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                if (isSelected) {
                    setTextColor(Ui.OnPrimaryContainer)
                    background = Ui.rounded(this@SettingsActivity, Ui.PrimaryContainer, 10)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
                }
                applyChoiceSemantics(
                    label = cfg.displayName(),
                    selected = isSelected,
                    stateLabel = when {
                        isSelected && isCurrentActive -> "已选中，当前启用"
                        isSelected -> "已选中"
                        isCurrentActive -> "当前启用"
                        else -> "未选中"
                    }
                )
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
            textSize = 12.5f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val isNew = selectedConfigId == null
            text = if (isNew) "✓ + 新增配置" else "+ 新增配置"
            if (isNew) {
                setTextColor(Ui.OnPrimaryContainer)
                background = Ui.rounded(this@SettingsActivity, Ui.PrimaryContainer, 10)
            } else {
                setTextColor(Ui.TextSecondary)
                background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
            }
            applyChoiceSemantics(
                label = "+ 新增配置",
                selected = isNew
            )
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
                text = if (isSelected) "✓ ${profile.displayLabel}" else profile.displayLabel
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                if (isSelected) {
                    setTextColor(Ui.OnPrimaryContainer)
                    background = Ui.rounded(this@SettingsActivity, Ui.PrimaryContainer, 10)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
                }
                applyChoiceSemantics(profile.displayLabel, isSelected)
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

    private fun renderProtocolChips() {
        if (!::protocolRow.isInitialized) return
        protocolRow.removeAllViews()
        ProviderProtocol.entries.forEach { protocol ->
            val isSelected = protocol == selectedProtocol
            val chip = TextView(this).apply {
                text = if (isSelected) "✓ ${protocol.displayLabel}" else protocol.displayLabel
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                if (isSelected) {
                    setTextColor(Ui.OnPrimaryContainer)
                    background = Ui.rounded(this@SettingsActivity, Ui.PrimaryContainer, 10)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
                }
                applyChoiceSemantics(protocol.displayLabel, isSelected)
                setOnClickListener {
                    selectedProtocol = protocol
                    renderProtocolChips()
                }
            }
            protocolRow.addView(chip, LinearLayout.LayoutParams(
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
                text = if (isSelected) "✓ $label" else label
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                if (isSelected) {
                    setTextColor(Ui.OnPrimaryContainer)
                    background = Ui.rounded(this@SettingsActivity, Ui.PrimaryContainer, 10)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
                }
                applyChoiceSemantics(label, isSelected)
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
                text = if (isSelected) "✓ $label" else label
                textSize = 12.5f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isEnabled = selectedAutoCompaction
                alpha = if (selectedAutoCompaction) 1.0f else 0.45f
                if (isSelected && selectedAutoCompaction) {
                    setTextColor(Ui.OnPrimaryContainer)
                    background = Ui.rounded(this@SettingsActivity, Ui.PrimaryContainer, 10)
                } else {
                    setTextColor(Ui.TextSecondary)
                    background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
                }
                applyChoiceSemantics(
                    label = label,
                    selected = isSelected,
                    stateLabel = when {
                        isSelected && selectedAutoCompaction -> "已选中"
                        isSelected -> "已选中，已停用"
                        !selectedAutoCompaction -> "未选中，已停用"
                        else -> "未选中"
                    }
                )
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
        testStatusTone = TestStatusTone.NONE
        balanceStatusTone = BalanceStatusTone.NONE
        testStatusCard.visibility = View.GONE

        scope.launch {
            try {
                val summary = ApiQuotaAndConnectivityService.testAndQuery(
                    ApiProviderConfig(
                        id = selectedConfigId ?: "connectivity-test",
                        baseUrl = url,
                        apiKey = apiKey,
                        model = model,
                        protocol = selectedProtocol
                    )
                )
                testButton.isEnabled = true
                testButton.text = "检测通信与平台额度"
                testStatusCard.visibility = View.VISIBLE

                if (summary.connectivity.success) {
                    testStatusTone = TestStatusTone.SUCCESS
                    connectivityResultText.text = "✓ ${summary.connectivity.message}"
                } else {
                    testStatusTone = TestStatusTone.FAILURE
                    connectivityResultText.text = "✕ ${summary.connectivity.message}"
                }
                val balance = summary.balance
                if (balance != null && balance.supported) {
                    balanceResultText.visibility = View.VISIBLE
                    if (!balance.balanceText.isNullOrBlank()) {
                        balanceStatusTone = BalanceStatusTone.SUCCESS
                        balanceResultText.text = "${balance.provider.displayName} 额度：${balance.balanceText}"
                    } else if (!balance.error.isNullOrBlank()) {
                        balanceStatusTone = BalanceStatusTone.ERROR
                        balanceResultText.text = "额度查询：${balance.error}"
                    }
                } else if (balance != null && !balance.supported) {
                    balanceResultText.visibility = View.VISIBLE
                    balanceStatusTone = BalanceStatusTone.INFO
                    balanceResultText.text = "提示：${balance.error ?: "该平台未开放公开额度接口"}"
                } else {
                    balanceStatusTone = BalanceStatusTone.NONE
                    balanceResultText.visibility = View.GONE
                }
                applyTestStatusTheme()
            } catch (e: Exception) {
                testButton.isEnabled = true
                testButton.text = "检测通信与平台额度"
                testStatusCard.visibility = View.VISIBLE
                testStatusTone = TestStatusTone.FAILURE
                balanceStatusTone = BalanceStatusTone.NONE
                connectivityResultText.text = "✕ 检测异常: ${e.message}"
                applyTestStatusTheme()
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
            compactionThreshold = selectedCompactionThreshold,
            protocol = selectedProtocol
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
        rootLayout.background = Ui.rounded(this, Ui.Background, 0)
        titleText.setTextColor(Ui.TextPrimary)
        subtitleText.setTextColor(Ui.TextSecondary)
        backButton.imageTintList = android.content.res.ColorStateList.valueOf(Ui.TextPrimary)
        backButton.contentDescription = "返回上一页"
        backButton.isClickable = true
        backButton.isFocusable = true
        backButton.background = Ui.clickableRounded(this, Color.TRANSPARENT, Ui.SurfaceSoft, 12)

        themeCard.background = Ui.rounded(this, Ui.Surface, 14)
        themeTitle.setTextColor(Ui.TextPrimary)
        themeButtons.forEach { (mode, btn) ->
            val isSelected = ThemeManager.currentMode == mode
            btn.text = if (isSelected) "✓ ${mode.displayName}" else mode.displayName
            btn.setTextColor(if (isSelected) Ui.OnPrimaryContainer else Ui.TextSecondary)
            btn.applyChoiceSemantics(mode.displayName, isSelected)
            btn.background = if (isSelected) {
                Ui.rounded(this, Ui.PrimaryContainer, 10)
            } else {
                Ui.rounded(this, Ui.SurfaceSoft, 10, Ui.OutlineSubtle)
            }
        }

        authCard.background = Ui.rounded(this, Ui.Surface, 14)
        fullAuthSwitch.setTextColor(Ui.TextPrimary)
        fullAuthSwitch.thumbTintList = Ui.switchThumbTint()
        fullAuthSwitch.trackTintList = Ui.switchTrackTint()
        authHintText.setTextColor(Ui.TextSecondary)

        presetCard.background = Ui.rounded(this, Ui.Surface, 14)
        presetTitle.setTextColor(Ui.TextPrimary)
        renderChips()

        configCard.background = Ui.rounded(this, Ui.Surface, 14)
        configCardTitle.setTextColor(Ui.TextPrimary)
        protocolTitle.setTextColor(Ui.TextPrimary)
        renderProtocolChips()
        listOf(nameInput, urlInput, modelInput, keyInput).forEach { input ->
            input.setTextColor(Ui.TextPrimary)
            input.setHintTextColor(Ui.TextMuted)
            input.background = Ui.rounded(this, Ui.SurfaceSoft, 12, Ui.OutlineSubtle)
        }

        contextCard.background = Ui.rounded(this, Ui.Surface, 14)
        contextTitle.setTextColor(Ui.TextPrimary)
        renderContextWindowChips()

        maxOutputCard.background = Ui.rounded(this, Ui.Surface, 14)
        maxOutputTitle.setTextColor(Ui.TextPrimary)
        renderMaxOutputChips()

        compactionCard.background = Ui.rounded(this, Ui.Surface, 14)
        compactionSwitch.setTextColor(Ui.TextPrimary)
        compactionSwitch.thumbTintList = Ui.switchThumbTint()
        compactionSwitch.trackTintList = Ui.switchTrackTint()
        compactionHintText.setTextColor(Ui.TextSecondary)
        compactionThresholdTitle.setTextColor(Ui.TextPrimary)
        renderThresholdChips()

        testCard.background = Ui.rounded(this, Ui.Surface, 14)
        applyTestButtonTheme()
        applyTestStatusTheme()

        saveButton.setTextColor(Ui.OnPrimary)
        saveButton.background = Ui.clickableRounded(this, Ui.Primary, Ui.PrimaryPressed, 14)
        deleteButton.setTextColor(Ui.stateColorList(
            normal = Ui.DangerOnContainer,
            pressed = Ui.DangerOnContainer
        ))
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

    private fun applyTestButtonTheme() {
        if (!::testButton.isInitialized) return
        testButton.setTextColor(
            Ui.stateColorList(
                normal = Ui.Primary,
                pressed = Ui.PrimaryPressed,
                disabled = Ui.DisabledContent
            )
        )
        testButton.background = Ui.clickableRounded(
            this,
            normalColor = Ui.SurfaceSoft,
            pressedColor = Ui.SurfaceSubtle,
            radiusDp = 12,
            strokeColor = Ui.OutlineFocus,
            disabledColor = Ui.DisabledContainer,
            disabledStrokeColor = Ui.DisabledContainer
        )
    }

    private fun applyTestStatusTheme() {
        if (!::testStatusCard.isInitialized) return
        val cardColor = when (testStatusTone) {
            TestStatusTone.SUCCESS -> Ui.SuccessSoft
            TestStatusTone.FAILURE -> Ui.DangerSoft
            TestStatusTone.NONE -> Ui.SurfaceSoft
        }
        val resultTextColor = when (testStatusTone) {
            TestStatusTone.SUCCESS -> Ui.OnPrimaryContainer
            TestStatusTone.FAILURE -> Ui.DangerOnContainer
            TestStatusTone.NONE -> Ui.TextPrimary
        }
        testStatusCard.background = Ui.rounded(this, cardColor, 10, Ui.OutlineSubtle)
        connectivityResultText.setTextColor(resultTextColor)
        balanceResultText.setTextColor(
            when (balanceStatusTone) {
                BalanceStatusTone.SUCCESS -> if (testStatusTone == TestStatusTone.SUCCESS) {
                    Ui.OnPrimaryContainer
                } else {
                    Ui.TextPrimary
                }
                BalanceStatusTone.ERROR -> Ui.TextSecondary
                BalanceStatusTone.INFO -> Ui.InfoOnContainer
                BalanceStatusTone.NONE -> Ui.TextSecondary
            }
        )
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
        background = Ui.rounded(this@SettingsActivity, Ui.Surface, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 12.5f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(Ui.TextSecondary)
    }

    private fun settingsInput(hint: String): EditText = EditText(this).apply {
        setSingleLine(true)
        this.hint = hint
        textSize = 14f
        setTextColor(Ui.TextPrimary)
        setHintTextColor(Ui.TextMuted)
        setPadding(dp(14), 0, dp(14), 0)
        background = Ui.rounded(this@SettingsActivity, Ui.SurfaceSoft, 12, Ui.OutlineSubtle)
    }

    private fun TextView.applyChoiceSemantics(
        label: String,
        selected: Boolean,
        stateLabel: String = if (selected) "已选中" else "未选中"
    ) {
        isSelected = selected
        isClickable = true
        isFocusable = true
        contentDescription = "$label，$stateLabel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = stateLabel
        }
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
