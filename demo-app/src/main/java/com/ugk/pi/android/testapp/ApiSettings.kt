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
    val model: String
) {
    fun displayName(): String {
        val host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()
        return if (host.isBlank()) model else "$model - $host"
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
                if (id.isBlank() || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) null
                else ApiProviderConfig(id, baseUrl, apiKey, model)
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

    val Surface: Int get() = if (isDark) Color.rgb(17, 22, 20) else Color.rgb(248, 250, 247)
    val SurfaceElevated: Int get() = if (isDark) Color.rgb(27, 36, 33) else Color.rgb(255, 255, 255)
    val SurfaceSoft: Int get() = if (isDark) Color.rgb(36, 48, 44) else Color.rgb(238, 243, 240)
    val SurfaceSubtle: Int get() = if (isDark) Color.rgb(22, 31, 28) else Color.rgb(243, 247, 244)
    val Mint: Int get() = if (isDark) Color.rgb(47, 211, 155) else Color.rgb(17, 126, 92)
    val MintLight: Int get() = if (isDark) Color.rgb(22, 58, 45) else Color.rgb(222, 245, 235)
    val MintDark: Int get() = if (isDark) Color.rgb(38, 189, 137) else Color.rgb(17, 107, 79)
    val MintStroke: Int get() = if (isDark) Color.rgb(36, 94, 73) else Color.rgb(176, 228, 206)
    val TextPrimary: Int get() = if (isDark) Color.rgb(230, 240, 235) else Color.rgb(17, 28, 26)
    val TextSecondary: Int get() = if (isDark) Color.rgb(158, 173, 167) else Color.rgb(83, 100, 96)
    val TextMuted: Int get() = if (isDark) Color.rgb(108, 125, 118) else Color.rgb(139, 158, 153)
    val Outline: Int get() = if (isDark) Color.rgb(44, 59, 53) else Color.rgb(224, 231, 226)
    val OutlineFocus: Int get() = if (isDark) Color.rgb(47, 211, 155) else Color.rgb(17, 126, 92)
    val UserBubble: Int get() = if (isDark) Color.rgb(26, 59, 47) else Color.rgb(222, 245, 235)
    val UserStroke: Int get() = if (isDark) Color.rgb(45, 93, 75) else Color.rgb(176, 228, 206)
    val AssistantBubble: Int get() = if (isDark) Color.rgb(27, 36, 33) else Color.rgb(255, 255, 255)
    val AssistantStroke: Int get() = if (isDark) Color.rgb(44, 59, 53) else Color.rgb(224, 231, 226)
    val CodeBg: Int get() = if (isDark) Color.rgb(22, 31, 28) else Color.rgb(236, 242, 239)
    val CodeText: Int get() = if (isDark) Color.rgb(74, 222, 128) else Color.rgb(17, 107, 79)
    val Success: Int get() = if (isDark) Color.rgb(52, 211, 153) else Color.rgb(38, 142, 98)
    val SuccessSoft: Int get() = if (isDark) Color.rgb(19, 54, 40) else Color.rgb(233, 248, 240)
    val Warning: Int get() = if (isDark) Color.rgb(251, 191, 36) else Color.rgb(180, 115, 20)
    val WarningSoft: Int get() = if (isDark) Color.rgb(51, 39, 17) else Color.rgb(254, 247, 233)
    val WarningStroke: Int get() = if (isDark) Color.rgb(94, 72, 29) else Color.rgb(245, 224, 180)
    val Danger: Int get() = if (isDark) Color.rgb(248, 113, 113) else Color.rgb(198, 54, 54)
    val DangerSoft: Int get() = if (isDark) Color.rgb(54, 25, 25) else Color.rgb(254, 238, 238)

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

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(8))
            background = Ui.rounded(activity, Ui.SurfaceElevated, 18)
        }

        val urlInput = settingsInput("URL (例: https://api.deepseek.com/anthropic)")
        val modelInput = settingsInput("模型名称 (例: deepseek-v4-flash)")
        val keyInput = settingsInput("API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val errorText = TextView(activity).apply {
            visibility = View.GONE
            setTextColor(Ui.Danger)
            textSize = 13f
            setPadding(activity.dp(4), activity.dp(8), activity.dp(4), 0)
        }

        // 主题模式选择卡片
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

        root.addView(themeContainer, authContainerLayoutParams())
        root.addView(urlInput, fieldLayoutParams())
        root.addView(modelInput, fieldLayoutParams())
        root.addView(keyInput, fieldLayoutParams())
        authContainer?.let { root.addView(it, authContainerLayoutParams()) }
        root.addView(errorText)

        val state = store.load()
        val active = state.activeConfig()
        if (active != null) {
            selectedConfigId = active.id
            urlInput.setText(active.baseUrl)
            modelInput.setText(active.model)
            keyInput.setText(active.apiKey)
        }

        val dialog = AlertDialog.Builder(activity, Ui.dialogTheme())
            .setTitle("设置与选项")
            .setView(root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("删除", null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            positiveButton?.setTextColor(Ui.Mint)
            negativeButton?.setTextColor(Ui.TextSecondary)
            neutralButton?.setTextColor(Ui.Danger)

            positiveButton?.setOnClickListener {
                val baseUrl = urlInput.text.toString().trim()
                val model = modelInput.text.toString().trim()
                val apiKey = keyInput.text.toString().trim()
                if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
                    errorText.text = "请填写所有字段"
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
                    model = model
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
