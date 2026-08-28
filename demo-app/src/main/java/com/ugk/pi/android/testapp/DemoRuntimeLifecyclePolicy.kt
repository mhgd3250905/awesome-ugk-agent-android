package com.ugk.pi.android.testapp

/**
 * The provider and transcript-preparation settings captured when an
 * AgentRuntime is built.
 */
internal data class DemoRuntimeConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxOutputTokens: Int,
    val protocol: ProviderProtocol,
    val contextWindow: String,
    val autoCompaction: Boolean,
    val compactionThreshold: Double,
) {
    companion object {
        fun from(config: ApiProviderConfig?): DemoRuntimeConfig? = config?.let {
            DemoRuntimeConfig(
                baseUrl = it.baseUrl,
                apiKey = it.apiKey,
                model = it.model,
                maxOutputTokens = it.maxOutputTokens ?: 8192,
                protocol = it.protocol,
                contextWindow = ContextProfile.configValueOrDefault(it.contextWindow),
                autoCompaction = it.autoCompaction ?: true,
                compactionThreshold = it.compactionThreshold ?: ContextCompactor.DEFAULT_THRESHOLD,
            )
        }
    }
}

internal enum class DemoRuntimeRefreshAction {
    CREATE,
    REUSE,
    REBUILD,
}

internal object DemoRuntimeLifecyclePolicy {
    fun decide(
        runtimeExists: Boolean,
        installedConfig: DemoRuntimeConfig?,
        requestedConfig: DemoRuntimeConfig?,
    ): DemoRuntimeRefreshAction = when {
        !runtimeExists -> DemoRuntimeRefreshAction.CREATE
        installedConfig == requestedConfig -> DemoRuntimeRefreshAction.REUSE
        else -> DemoRuntimeRefreshAction.REBUILD
    }
}
