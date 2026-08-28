package com.ugk.pi.android.testapp

import android.content.Context
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

data class ApiProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val name: String? = null,
    val contextWindow: String? = ContextProfile.DEFAULT_CONFIG,
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
        val cw = ContextProfile.configValueOrDefault(contextWindow)
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
                val contextWindow = ContextProfile.configValueOrDefault(obj.stringValue("contextWindow"))
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
