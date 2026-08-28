package com.ugk.pi.android.testapp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.util.UUID

data class ApiProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val name: String? = null,
    val contextWindow: String? = "200K",
    val maxOutputTokens: Int? = 8192,
    val autoCompaction: Boolean? = true,
    val compactionThreshold: Double? = 0.70
) {
    fun displayName(): String {
        if (!name.isNullOrBlank()) return name
        val host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
        return if (host.isBlank()) model else "$model - $host"
    }

    fun formatSpec(): String {
        val cw = contextWindow?.ifBlank { "200K" } ?: "200K"
        val outVal = maxOutputTokens ?: 8192
        val outStr = if (outVal >= 1024) "${outVal / 1024}K" else "$outVal"
        val compStr = if (autoCompaction != false) " · ${((compactionThreshold ?: 0.70) * 100).toInt()}%压缩" else ""
        return "$cw · ${outStr}输出$compStr"
    }
}

data class ApiProviderSettingsState(
    val activeId: String?,
    val configs: List<ApiProviderConfig>
) {
    fun activeConfig(): ApiProviderConfig? = configs.firstOrNull { it.id == activeId }

    companion object {
        fun empty(): ApiProviderSettingsState = ApiProviderSettingsState(null, emptyList())
    }
}

object ApiProviderSettingsJson {
    fun encode(state: ApiProviderSettingsState): String = buildJsonObject {
        state.activeId?.let { put("activeId", it) }
        put("configs", buildJsonArray {
            state.configs.forEach { config ->
                add(buildJsonObject {
                    put("id", config.id)
                    put("baseUrl", config.baseUrl)
                    put("apiKey", config.apiKey)
                    put("model", config.model)
                    config.name?.let { put("name", it) }
                    config.contextWindow?.let { put("contextWindow", it) }
                    config.maxOutputTokens?.let { put("maxOutputTokens", it.toString()) }
                    config.autoCompaction?.let { put("autoCompaction", it.toString()) }
                    config.compactionThreshold?.let { put("compactionThreshold", it.toString()) }
                })
            }
        })
    }.toString()

    fun decode(value: String?): ApiProviderSettingsState {
        if (value.isNullOrBlank()) return ApiProviderSettingsState.empty()
        return runCatching {
            val root = Json.parseToJsonElement(value).jsonObject
            val configs = root["configs"]?.jsonArray?.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj.stringValue("id")
                val baseUrl = obj.stringValue("baseUrl")
                val apiKey = obj.stringValue("apiKey")
                val model = obj.stringValue("model")
                val name = obj.stringValue("name").ifBlank { null }
                val contextWindow = obj.stringValue("contextWindow").ifBlank { "200K" }
                val maxOutputTokens = obj.stringValue("maxOutputTokens").toIntOrNull() ?: 8192
                val autoCompaction = obj["autoCompaction"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val compactionThreshold = obj["compactionThreshold"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.70
                if (id.isBlank() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) null
                else ApiProviderConfig(
                    id = id,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    name = name,
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    autoCompaction = autoCompaction,
                    compactionThreshold = compactionThreshold
                )
            } ?: emptyList()
            val activeId = root["activeId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { active -> configs.any { it.id == active } }
            ApiProviderSettingsState(activeId ?: configs.firstOrNull()?.id, configs)
        }.getOrElse { ApiProviderSettingsState.empty() }
    }

    private fun JsonObject.stringValue(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
}

class ApiProviderSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("api_provider_settings", Context.MODE_PRIVATE)

    fun load(): ApiProviderSettingsState {
        if (!prefs.contains(KEY)) {
            val defaults = loadDebugDefaults()
            if (defaults != null) {
                save(defaults)
                return defaults
            }
            return ApiProviderSettingsState.empty()
        }
        return ApiProviderSettingsJson.decode(prefs.getString(KEY, null))
    }

    fun activeConfig(): ApiProviderConfig? = load().activeConfig()

    fun upsertAndActivate(config: ApiProviderConfig): ApiProviderSettingsState {
        val current = load()
        val configs = current.configs.filterNot { it.id == config.id } + config
        val next = ApiProviderSettingsState(config.id, configs)
        save(next)
        return next
    }

    fun activate(configId: String): ApiProviderSettingsState {
        val current = load()
        if (current.configs.none { it.id == configId }) return current
        val next = ApiProviderSettingsState(configId, current.configs)
        save(next)
        return next
    }

    fun delete(configId: String): ApiProviderSettingsState {
        val current = load()
        val configs = current.configs.filterNot { it.id == configId }
        val activeId = if (current.activeId == configId) configs.firstOrNull()?.id else current.activeId
        val next = ApiProviderSettingsState(activeId, configs)
        save(next)
        return next
    }

    private fun save(state: ApiProviderSettingsState) {
        prefs.edit().putString(KEY, ApiProviderSettingsJson.encode(state)).apply()
    }

    private fun loadDebugDefaults(): ApiProviderSettingsState? {
        val id = appContext.getString(R.string.ugk_default_api_provider_id).trim()
        val baseUrl = appContext.getString(R.string.ugk_default_api_base_url).trim()
        val apiKey = appContext.getString(R.string.ugk_default_api_key).trim()
        val model = appContext.getString(R.string.ugk_default_api_model).trim()
        if (id.isBlank() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        val config = ApiProviderConfig(
            id = id,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model
        )
        return ApiProviderSettingsState(activeId = id, configs = listOf(config))
    }

    private companion object {
        const val KEY = "state"
    }
}

object Ui {
    // Dynamic theme state
    val isDark: Boolean get() = ThemeManager.isDark

    val Surface: Int get() = if (isDark) Color.rgb(18, 19, 22) else Color.rgb(251, 249, 245)
    val SurfaceElevated: Int get() = if (isDark) Color.rgb(26, 27, 31) else Color.rgb(255, 255, 255)
    val SurfaceSoft: Int get() = if (isDark) Color.rgb(36, 38, 43) else Color.rgb(240, 236, 229)
    val SurfaceSubtle: Int get() = if (isDark) Color.rgb(30, 32, 36) else Color.rgb(245, 242, 236)
    
    // 主品牌与核心强调色：活力温暖橙红
    val Mint: Int get() = if (isDark) Color.rgb(255, 110, 74) else Color.rgb(234, 84, 52)
    val MintLight: Int get() = if (isDark) Color.rgb(58, 36, 30) else Color.rgb(253, 238, 233)
    val MintDark: Int get() = if (isDark) Color.rgb(255, 131, 98) else Color.rgb(206, 62, 31)
    val MintStroke: Int get() = if (isDark) Color.rgb(104, 58, 47) else Color.rgb(247, 195, 182)
    
    // 文字体系：浅色暖炭黑，深色通透灰白（绝不发绿）
    val TextPrimary: Int get() = if (isDark) Color.rgb(240, 242, 245) else Color.rgb(28, 26, 23)
    val TextSecondary: Int get() = if (isDark) Color.rgb(156, 161, 174) else Color.rgb(107, 102, 94)
    val TextMuted: Int get() = if (isDark) Color.rgb(101, 106, 118) else Color.rgb(158, 152, 142)
    
    // 描边体系：浅色米灰，深色冷灰
    val Outline: Int get() = if (isDark) Color.rgb(47, 50, 56) else Color.rgb(229, 224, 216)
    val OutlineFocus: Int get() = if (isDark) Color.rgb(255, 110, 74) else Color.rgb(234, 84, 52)
    
    // 气泡色彩：用户暖橙粉底，助手纯白/纯炭黑底
    val UserBubble: Int get() = if (isDark) Color.rgb(46, 34, 30) else Color.rgb(253, 238, 233)
    val UserStroke: Int get() = if (isDark) Color.rgb(78, 52, 43) else Color.rgb(247, 195, 182)
    val AssistantBubble: Int get() = if (isDark) Color.rgb(26, 27, 31) else Color.rgb(255, 255, 255)
    val AssistantStroke: Int get() = if (isDark) Color.rgb(47, 50, 56) else Color.rgb(234, 229, 220)
    
    // 代码卡片与文本：深浅双模精致配色
    val CodeBg: Int get() = if (isDark) Color.rgb(21, 22, 25) else Color.rgb(245, 242, 236)
    val CodeText: Int get() = if (isDark) Color.rgb(110, 231, 183) else Color.rgb(45, 106, 79)
    
    // 清爽点缀色：淡青绿/草木绿
    val Success: Int get() = if (isDark) Color.rgb(110, 231, 183) else Color.rgb(46, 125, 94)
    val SuccessSoft: Int get() = if (isDark) Color.rgb(22, 46, 36) else Color.rgb(232, 245, 238)
    
    // 警告与危险提示色
    val Warning: Int get() = if (isDark) Color.rgb(251, 191, 36) else Color.rgb(196, 126, 24)
    val WarningSoft: Int get() = if (isDark) Color.rgb(51, 39, 17) else Color.rgb(254, 247, 233)
    val WarningStroke: Int get() = if (isDark) Color.rgb(94, 72, 29) else Color.rgb(245, 224, 180)
    val Danger: Int get() = if (isDark) Color.rgb(248, 113, 113) else Color.rgb(209, 57, 57)
    val DangerSoft: Int get() = if (isDark) Color.rgb(54, 25, 25) else Color.rgb(254, 238, 238)

    // 新增语义化别名 Tokens
    val Accent: Int get() = Mint
    val AccentLight: Int get() = MintLight
    val AccentDark: Int get() = MintDark
    val AccentStroke: Int get() = MintStroke
    val Sage: Int get() = Success
    val SageSoft: Int get() = SuccessSoft

    fun dialogTheme(): Int = if (ThemeManager.isDark) {
        android.R.style.Theme_DeviceDefault_Dialog_Alert
    } else {
        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(context.dp(strokeDp), strokeColor)
        }

    fun asymmetricRounded(
        context: Context,
        color: Int,
        topLeftDp: Int,
        topRightDp: Int,
        bottomRightDp: Int,
        bottomLeftDp: Int,
        strokeColor: Int = 0,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val tl = context.dp(topLeftDp).toFloat()
        val tr = context.dp(topRightDp).toFloat()
        val br = context.dp(bottomRightDp).toFloat()
        val bl = context.dp(bottomLeftDp).toFloat()
        cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        if (strokeColor != 0) setStroke(context.dp(strokeDp), strokeColor)
    }

    fun stateListDrawable(
        normal: Drawable,
        pressed: Drawable? = null,
        focused: Drawable? = null,
        disabled: Drawable? = null
    ): StateListDrawable = StateListDrawable().apply {
        if (disabled != null) addState(intArrayOf(-android.R.attr.state_enabled), disabled)
        if (pressed != null) addState(intArrayOf(android.R.attr.state_pressed), pressed)
        if (focused != null) addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }

    fun clickableRounded(
        context: Context,
        normalColor: Int,
        pressedColor: Int,
        radiusDp: Int,
        strokeColor: Int = 0,
        strokeDp: Int = 1
    ): StateListDrawable {
        val normal = rounded(context, normalColor, radiusDp, strokeColor, strokeDp)
        val pressed = rounded(context, pressedColor, radiusDp, strokeColor, strokeDp)
        return stateListDrawable(normal = normal, pressed = pressed)
    }

    fun styleSecondaryButton(button: Button) {
        button.setAllCaps(false)
        button.setTextColor(MintDark)
        button.background = clickableRounded(button.context, SurfaceElevated, SurfaceSoft, 12, Outline)
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

class ApiSettingsDialog(
    private val activity: android.app.Activity,
    private val store: ApiProviderSettingsStore,
    private val onChanged: (ApiProviderConfig?) -> Unit,
    private val authorizationStore: AgentAuthorizationSettingsStore? = null
) {
    private var selectedConfigId: String? = null
    private var selectedContextWindow: String = "200K"
    private var selectedMaxOutputTokens: Int = 8192
    private val scope = CoroutineScope(Dispatchers.Main)

    private val contextWindowOptions = listOf("64K", "128K", "200K", "1M", "2M", "32K")
    private val maxOutputOptions = listOf(
        4096 to "4K",
        8192 to "8K (通用)",
        16384 to "16K",
        32768 to "32K",
        65536 to "64K",
        131072 to "128K (超大)"
    )

    fun show() {
        val scrollRoot = ScrollView(activity).apply {
            isFillViewport = true
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(14))
            background = Ui.rounded(activity, Ui.SurfaceElevated, 18)
        }
        scrollRoot.addView(root)

        // 1. 主题模式选择卡片
        val themeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.SurfaceSubtle, 12, Ui.Outline)
            setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        }
        val themeTitle = TextView(activity).apply {
            text = "界面主题"
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        val themeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, activity.dp(8), 0, 0)
        }

        val themeButtons = mutableMapOf<AppThemeMode, TextView>()
        AppThemeMode.values().forEach { mode ->
            val btn = TextView(activity).apply {
                text = "${mode.icon} ${mode.displayName}"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                val isSelected = ThemeManager.currentMode == mode
                setTextColor(if (isSelected) Ui.SurfaceElevated else Ui.TextSecondary)
                background = if (isSelected) {
                    Ui.rounded(activity, Ui.Mint, 10)
                } else {
                    Ui.rounded(activity, Ui.SurfaceSoft, 10, Ui.Outline)
                }
                setOnClickListener {
                    ThemeManager.setMode(activity, mode)
                    themeButtons.forEach { (m, b) ->
                        val sel = m == mode
                        b.setTextColor(if (sel) Ui.SurfaceElevated else Ui.TextSecondary)
                        b.background = if (sel) {
                            Ui.rounded(activity, Ui.Mint, 10)
                        } else {
                            Ui.rounded(activity, Ui.SurfaceSoft, 10, Ui.Outline)
                        }
                    }
                    onChanged(store.load().activeConfig())
                }
            }
            themeButtons[mode] = btn
            themeRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (mode != AppThemeMode.LIGHT) marginStart = activity.dp(6)
            })
        }
        themeContainer.addView(themeTitle)
        themeContainer.addView(themeRow)

        // 2. 全授权模式卡片
        val authContainer = authorizationStore?.let { authorization ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rounded(activity, Ui.SurfaceSubtle, 12, Ui.Outline)
                setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
            }
        }

        val fullAuthorizationSwitch = authorizationStore?.let { authorization ->
            Switch(activity).apply {
                text = "全授权模式"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Ui.TextPrimary)
                isChecked = authorization.isFullAuthorizationEnabled()
                contentDescription = "开启后跳过 Agent 高影响操作确认"
            }
        }
        val authorizationHint = authorizationStore?.let {
            TextView(activity).apply {
                text = "开启后，Agent 的高影响操作不再弹出确认。仅建议在受控测试设备使用。"
                textSize = 12f
                setTextColor(Ui.TextSecondary)
                setPadding(0, activity.dp(4), 0, 0)
            }
        }

        authContainer?.let { container ->
            fullAuthorizationSwitch?.let { container.addView(it) }
            authorizationHint?.let { container.addView(it) }
        }

        // 3. 已保存 API 配置 (预设选择与切换)
        val presetContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.SurfaceSubtle, 12, Ui.Outline)
            setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(12))
        }
        val presetHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val presetTitle = TextView(activity).apply {
            text = "API 配置预设"
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        presetHeader.addView(presetTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        presetContainer.addView(presetHeader)

        val chipScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, activity.dp(8), 0, 0)
        }
        val chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipScroll.addView(chipRow)
        presetContainer.addView(chipScroll)

        // 4. 输入框字段
        val nameInput = settingsInput("配置备注名称 (可选，例: DeepSeek 官方)")
        val urlInput = settingsInput("URL (例: https://api.deepseek.com/anthropic)")
        val modelInput = settingsInput("模型名称 (例: deepseek-chat)")
        val keyInput = settingsInput("API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // 5. 上下文总窗口卡片
        val contextWindowContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.SurfaceSubtle, 12, Ui.Outline)
            setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(12))
        }
        val contextWindowTitle = TextView(activity).apply {
            text = "上下文窗口 (Context Window)"
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        val contextWindowScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, activity.dp(8), 0, 0)
        }
        val contextWindowRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contextWindowScroll.addView(contextWindowRow)
        contextWindowContainer.addView(contextWindowTitle)
        contextWindowContainer.addView(contextWindowScroll)

        // 6. 单次最大输出卡片
        val maxOutputContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.SurfaceSubtle, 12, Ui.Outline)
            setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(12))
        }
        val maxOutputTitle = TextView(activity).apply {
            text = "单次最大生成 (Max Output Tokens)"
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.TextPrimary)
        }
        val maxOutputScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, activity.dp(8), 0, 0)
        }
        val maxOutputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        maxOutputScroll.addView(maxOutputRow)
        maxOutputContainer.addView(maxOutputTitle)
        maxOutputContainer.addView(maxOutputScroll)

        val errorText = TextView(activity).apply {
            visibility = View.GONE
            setTextColor(Ui.Danger)
            textSize = 13f
            setPadding(activity.dp(4), activity.dp(8), activity.dp(4), 0)
        }

        // 7. 通信检测与额度卡片
        val testStatusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(activity, Ui.SurfaceSubtle, 12, Ui.Outline)
            setPadding(activity.dp(14), activity.dp(10), activity.dp(14), activity.dp(10))
            visibility = View.GONE
        }
        val connectivityResultText = TextView(activity).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val balanceResultText = TextView(activity).apply {
            textSize = 12.5f
            setPadding(0, activity.dp(4), 0, 0)
        }
        testStatusCard.addView(connectivityResultText)
        testStatusCard.addView(balanceResultText)

        // 测试按钮
        val testButton = TextView(activity).apply {
            text = "⚡ 检测通信与平台额度"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Ui.Mint)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = Ui.clickableRounded(activity, Ui.SurfaceSoft, Ui.SurfaceSubtle, 10, Ui.MintStroke)
            setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10))
        }

        // 挂载到 root
        root.addView(themeContainer, authContainerLayoutParams())
        authContainer?.let { root.addView(it, authContainerLayoutParams()) }
        root.addView(presetContainer, authContainerLayoutParams())
        root.addView(nameInput, fieldLayoutParams())
        root.addView(urlInput, fieldLayoutParams())
        root.addView(modelInput, fieldLayoutParams())
        root.addView(keyInput, fieldLayoutParams())
        root.addView(contextWindowContainer, authContainerLayoutParams())
        root.addView(maxOutputContainer, authContainerLayoutParams())
        root.addView(testButton, fieldLayoutParams())
        root.addView(testStatusCard, authContainerLayoutParams())
        root.addView(errorText)

        var neutralButtonRef: android.widget.Button? = null

        fun updateNeutralButtonVisibility() {
            neutralButtonRef?.visibility = if (selectedConfigId != null) View.VISIBLE else View.GONE
        }

        fun renderContextWindowChips() {
            contextWindowRow.removeAllViews()
            contextWindowOptions.forEach { opt ->
                val isSelected = opt.equals(selectedContextWindow, ignoreCase = true)
                val chip = TextView(activity).apply {
                    text = opt
                    textSize = 12.5f
                    setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
                    if (isSelected) {
                        setTextColor(Ui.SurfaceElevated)
                        background = Ui.rounded(activity, Ui.Mint, 14)
                    } else {
                        setTextColor(Ui.TextSecondary)
                        background = Ui.rounded(activity, Ui.SurfaceSoft, 14, Ui.Outline)
                    }
                    setOnClickListener {
                        selectedContextWindow = opt
                        renderContextWindowChips()
                    }
                }
                contextWindowRow.addView(chip, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = activity.dp(8) })
            }
        }

        fun renderMaxOutputChips() {
            maxOutputRow.removeAllViews()
            maxOutputOptions.forEach { (tokens, label) ->
                val isSelected = tokens == selectedMaxOutputTokens
                val chip = TextView(activity).apply {
                    text = label
                    textSize = 12.5f
                    setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setPadding(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(6))
                    if (isSelected) {
                        setTextColor(Ui.SurfaceElevated)
                        background = Ui.rounded(activity, Ui.Mint, 14)
                    } else {
                        setTextColor(Ui.TextSecondary)
                        background = Ui.rounded(activity, Ui.SurfaceSoft, 14, Ui.Outline)
                    }
                    setOnClickListener {
                        selectedMaxOutputTokens = tokens
                        renderMaxOutputChips()
                    }
                }
                maxOutputRow.addView(chip, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = activity.dp(8) })
            }
        }

        fun loadConfigToInputs(config: ApiProviderConfig?) {
            if (config != null) {
                selectedConfigId = config.id
                nameInput.setText(config.name.orEmpty())
                urlInput.setText(config.baseUrl)
                modelInput.setText(config.model)
                keyInput.setText(config.apiKey)
                selectedContextWindow = config.contextWindow?.ifBlank { "200K" } ?: "200K"
                selectedMaxOutputTokens = config.maxOutputTokens ?: 8192
            } else {
                selectedConfigId = null
                nameInput.setText("")
                urlInput.setText("")
                modelInput.setText("")
                keyInput.setText("")
                selectedContextWindow = "200K"
                selectedMaxOutputTokens = 8192
            }
            errorText.visibility = View.GONE
            testStatusCard.visibility = View.GONE
            renderContextWindowChips()
            renderMaxOutputChips()
            updateNeutralButtonVisibility()
        }

        fun renderChips() {
            chipRow.removeAllViews()
            val state = store.load()
            state.configs.forEach { cfg ->
                val isSelected = cfg.id == selectedConfigId
                val isCurrentActive = cfg.id == state.activeId
                val chip = TextView(activity).apply {
                    val prefix = if (isCurrentActive) "● " else ""
                    text = "$prefix${cfg.displayName()}"
                    textSize = 12.5f
                    setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
                    if (isSelected) {
                        setTextColor(Ui.SurfaceElevated)
                        background = Ui.rounded(activity, Ui.Mint, 14)
                    } else {
                        setTextColor(Ui.TextSecondary)
                        background = Ui.rounded(activity, Ui.SurfaceSoft, 14, Ui.Outline)
                    }
                    setOnClickListener {
                        loadConfigToInputs(cfg)
                        renderChips()
                    }
                }
                chipRow.addView(chip, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = activity.dp(8) })
            }

            val newChip = TextView(activity).apply {
                text = "+ 新增配置"
                textSize = 12.5f
                setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
                val isNew = selectedConfigId == null
                if (isNew) {
                    setTextColor(Ui.SurfaceElevated)
                    background = Ui.rounded(activity, Ui.Mint, 14)
                } else {
                    setTextColor(Ui.Mint)
                    background = Ui.rounded(activity, Ui.SurfaceSoft, 14, Ui.MintStroke)
                }
                setOnClickListener {
                    loadConfigToInputs(null)
                    renderChips()
                }
            }
            chipRow.addView(newChip)
        }

        // 初始化数据
        val state = store.load()
        val active = state.activeConfig()
        loadConfigToInputs(active)
        renderChips()

        testButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            val model = modelInput.text.toString().trim()
            val apiKey = keyInput.text.toString().trim()
            if (url.isBlank() || model.isBlank() || apiKey.isBlank()) {
                errorText.text = "请先填写 URL、模型名称和 API Key"
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            testButton.isEnabled = false
            testButton.text = "正在检测连通性与额度..."
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

        val dialog = AlertDialog.Builder(activity, Ui.dialogTheme())
            .setTitle("设置与选项")
            .setView(scrollRoot)
            .setPositiveButton("保存并启用", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("删除", null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutralButtonRef = neutralButton
            updateNeutralButtonVisibility()

            positiveButton?.setTextColor(Ui.Mint)
            negativeButton?.setTextColor(Ui.TextSecondary)
            neutralButton?.setTextColor(Ui.Danger)

            positiveButton?.setOnClickListener {
                val baseUrl = urlInput.text.toString().trim()
                val model = modelInput.text.toString().trim()
                val apiKey = keyInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifBlank { null }
                if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                    errorText.text = "请填写所有必填字段（URL、模型名称和 API Key）"
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    errorText.text = "URL 需以 http:// 或 https:// 开头"
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val config = ApiProviderConfig(
                    id = selectedConfigId ?: UUID.randomUUID().toString(),
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    name = name,
                    contextWindow = selectedContextWindow,
                    maxOutputTokens = selectedMaxOutputTokens
                )
                store.upsertAndActivate(config)
                authorizationStore?.setFullAuthorizationEnabled(
                    fullAuthorizationSwitch?.isChecked == true
                )
                onChanged(config)
                dialog.dismiss()
            }
            neutralButton?.setOnClickListener {
                val id = selectedConfigId ?: return@setOnClickListener
                val nextState = store.delete(id)
                onChanged(nextState.activeConfig())
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun settingsInput(hint: String): EditText = EditText(activity).apply {
        setSingleLine(true)
        this.hint = hint
        textSize = 14f
        setTextColor(Ui.TextPrimary)
        setHintTextColor(Ui.TextMuted)
        setPadding(activity.dp(14), 0, activity.dp(14), 0)
        background = Ui.rounded(activity, Ui.SurfaceSoft, 12, Ui.Outline)
    }

    private fun fieldLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(48)
    ).apply { topMargin = activity.dp(8) }

    private fun authContainerLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = activity.dp(12) }
}
