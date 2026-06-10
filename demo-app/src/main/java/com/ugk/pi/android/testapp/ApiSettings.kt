package com.ugk.pi.android.testapp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private val prefs = context.getSharedPreferences("api_provider_settings", Context.MODE_PRIVATE)

    fun load(): ApiProviderSettingsState {
        if (!prefs.contains(KEY)) return ApiProviderSettingsState.empty()
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

    private companion object {
        const val KEY = "state"
    }
}

object Ui {
    val Surface = Color.rgb(255, 255, 255)
    val SurfaceSoft = Color.rgb(245, 245, 245)
    val Mint = Color.rgb(47, 195, 141)
    val MintDark = Color.rgb(17, 126, 92)
    val TextPrimary = Color.rgb(22, 37, 38)
    val TextSecondary = Color.rgb(85, 108, 109)
    val TextMuted = Color.rgb(126, 147, 148)
    val Danger = Color.rgb(190, 45, 45)

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(context.dp(strokeDp), strokeColor)
        }

    fun styleSecondaryButton(button: Button) {
        button.setAllCaps(false)
        button.setTextColor(MintDark)
        button.background = rounded(button.context, Color.rgb(250, 255, 253), 12)
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

class ApiSettingsDialog(
    private val activity: android.app.Activity,
    private val store: ApiProviderSettingsStore,
    private val onChanged: (ApiProviderConfig?) -> Unit
) {
    private var selectedConfigId: String? = null

    fun show() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(4))
            background = Ui.rounded(activity, Ui.Surface, 12)
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
            setPadding(0, activity.dp(8), 0, 0)
        }

        root.addView(urlInput, fieldLayoutParams())
        root.addView(modelInput, fieldLayoutParams())
        root.addView(keyInput, fieldLayoutParams())
        root.addView(errorText)

        val state = store.load()
        val active = state.activeConfig()
        if (active != null) {
            selectedConfigId = active.id
            urlInput.setText(active.baseUrl)
            modelInput.setText(active.model)
            keyInput.setText(active.apiKey)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("API 源设置")
            .setView(root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("删除", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
                onChanged(config)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
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
        textSize = 15f
        setTextColor(Ui.TextPrimary)
        setHintTextColor(Ui.TextMuted)
        setPadding(activity.dp(14), 0, activity.dp(14), 0)
        background = Ui.rounded(activity, Ui.SurfaceSoft, 10)
    }

    private fun fieldLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(48)
    ).apply { topMargin = activity.dp(8) }
}
