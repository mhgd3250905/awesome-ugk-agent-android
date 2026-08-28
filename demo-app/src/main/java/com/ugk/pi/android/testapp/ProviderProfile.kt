package com.ugk.pi.android.testapp

import com.ugk.pi.android.AnthropicMessagesProvider
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.OpenAiChatCompletionsProvider

/** The model API protocol selected by a provider setting. */
enum class ProviderProtocol(
    val stableId: String,
    val displayLabel: String
) {
    AUTO("auto", "自动识别"),
    ANTHROPIC_MESSAGES("anthropic_messages", "Anthropic"),
    OPENAI_CHAT_COMPLETIONS("openai_chat_completions", "OpenAI");

    /** Alias useful to callers that call persistence keys simply an id. */
    val id: String
        get() = stableId

    companion object {
        fun fromPersistence(value: String?): ProviderProtocol {
            val normalized = value?.trim()?.lowercase() ?: return AUTO
            return entries.firstOrNull { it.stableId == normalized } ?: AUTO
        }

        fun resolve(selected: ProviderProtocol, baseUrl: String): ProviderProtocol {
            if (selected != AUTO) return selected
            val normalized = baseUrl.trimEnd('/').lowercase()
            return if (
                normalized.contains("anthropic") ||
                normalized.contains("api.anthropic.com") ||
                normalized.endsWith("/messages")
            ) {
                ANTHROPIC_MESSAGES
            } else {
                OPENAI_CHAT_COMPLETIONS
            }
        }
    }
}

/** API vendors with a known public balance endpoint. */
enum class QuotaProviderType(val displayName: String) {
    DEEPSEEK("DeepSeek"),
    SILICONFLOW("硅基流动"),
    MOONSHOT("Moonshot/Kimi"),
    OPENROUTER("OpenRouter"),
    UNKNOWN("通用/未知平台")
}

/**
 * The single provider mapping used by runtime creation, connectivity probes,
 * and quota lookup.
 */
data class ProviderProfile(
    val config: ApiProviderConfig,
    val resolvedProtocol: ProviderProtocol,
    val vendor: QuotaProviderType,
    val endpoint: String,
    val quotaEndpoint: String?,
    val quotaIsUsd: Boolean
) {
    val protocol: ProviderProtocol
        get() = resolvedProtocol

    val runtimeEndpoint: String
        get() = endpoint

    val probeEndpoint: String
        get() = endpoint

    /** Creates the SDK provider through the demo's injectable transport seam. */
    fun createRuntimeProvider(transport: DemoHttpTransport): LLMProvider {
        val coreTransport = DemoHttpTransportAdapter(transport)
        return when (resolvedProtocol) {
            ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider(
                apiKey = config.apiKey,
                model = config.model,
                // The SDK provider appends /v1/messages itself. The profile
                // endpoint is canonicalized to that suffix above.
                baseUrl = endpoint.removeSuffix(ANTHROPIC_MESSAGES_SUFFIX),
                transport = coreTransport,
                maxTokens = config.maxOutputTokens ?: 8192
            )

            ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsProvider(
                apiKey = config.apiKey,
                model = config.model,
                transport = coreTransport,
                endpoint = endpoint
            )

            ProviderProtocol.AUTO -> error("ProviderProfile must contain a resolved protocol")
        }
    }

    companion object {
        private const val ANTHROPIC_MESSAGES_SUFFIX = "/v1/messages"

        fun from(config: ApiProviderConfig): ProviderProfile {
            val resolvedProtocol = ProviderProtocol.resolve(config.protocol, config.baseUrl)
            val vendor = vendorFor(config.baseUrl)
            val endpoint = when (resolvedProtocol) {
                ProviderProtocol.ANTHROPIC_MESSAGES -> normalizeAnthropicEndpoint(config.baseUrl)
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> normalizeOpenAiEndpoint(config.baseUrl)
                ProviderProtocol.AUTO -> error("Provider protocol did not resolve")
            }
            val quota = quotaSpecFor(vendor, config.baseUrl)
            return ProviderProfile(
                config = config,
                resolvedProtocol = resolvedProtocol,
                vendor = vendor,
                endpoint = endpoint,
                quotaEndpoint = quota?.first,
                quotaIsUsd = quota?.second == true
            )
        }

        private fun vendorFor(baseUrl: String): QuotaProviderType {
            val lower = baseUrl.lowercase()
            return when {
                lower.contains("api.deepseek.com") -> QuotaProviderType.DEEPSEEK
                lower.contains("siliconflow.cn") || lower.contains("siliconflow.com") ->
                    QuotaProviderType.SILICONFLOW
                lower.contains("moonshot.cn") || lower.contains("moonshot.ai") ->
                    QuotaProviderType.MOONSHOT
                lower.contains("openrouter.ai") -> QuotaProviderType.OPENROUTER
                else -> QuotaProviderType.UNKNOWN
            }
        }

        private fun quotaSpecFor(
            vendor: QuotaProviderType,
            baseUrl: String
        ): Pair<String, Boolean>? {
            val lower = baseUrl.lowercase()
            return when (vendor) {
                QuotaProviderType.DEEPSEEK ->
                    "https://api.deepseek.com/user/balance" to false
                QuotaProviderType.SILICONFLOW ->
                    if (lower.contains("siliconflow.com")) {
                        "https://api.siliconflow.com/v1/user/info" to true
                    } else {
                        "https://api.siliconflow.cn/v1/user/info" to false
                    }
                QuotaProviderType.MOONSHOT ->
                    if (lower.contains("moonshot.ai")) {
                        "https://api.moonshot.ai/v1/users/me/balance" to true
                    } else {
                        "https://api.moonshot.cn/v1/users/me/balance" to false
                    }
                QuotaProviderType.OPENROUTER ->
                    "https://openrouter.ai/api/v1/auth/key" to false
                QuotaProviderType.UNKNOWN -> null
            }
        }

        private fun normalizeAnthropicEndpoint(baseUrl: String): String {
            val clean = baseUrl.trimEnd('/')
            val lower = clean.lowercase()
            return when {
                lower.endsWith("/v1/messages") ->
                    clean.dropLast(ANTHROPIC_MESSAGES_SUFFIX.length) + ANTHROPIC_MESSAGES_SUFFIX
                lower.endsWith("/messages") ->
                    clean.dropLast("/messages".length) + ANTHROPIC_MESSAGES_SUFFIX
                lower.endsWith("/v1") -> "$clean/messages"
                else -> "$clean$ANTHROPIC_MESSAGES_SUFFIX"
            }
        }

        private fun normalizeOpenAiEndpoint(baseUrl: String): String {
            val clean = baseUrl.trimEnd('/')
            val lower = clean.lowercase()
            return when {
                lower.endsWith("/chat/completions") ->
                    clean.dropLast("/chat/completions".length) + "/chat/completions"
                lower.endsWith("/v1") -> "$clean/chat/completions"
                else -> "$clean/v1/chat/completions"
            }
        }
    }
}
