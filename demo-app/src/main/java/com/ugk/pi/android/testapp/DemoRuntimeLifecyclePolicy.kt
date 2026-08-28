package com.ugk.pi.android.testapp

/**
 * The subset of provider settings that is captured when an AgentRuntime is
 * built. Session compaction settings are deliberately not part of this value:
 * they are synced independently when the Activity returns to the foreground.
 */
internal data class DemoRuntimeConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxOutputTokens: Int,
    val protocol: ProviderProtocol,
) {
    companion object {
        fun from(config: ApiProviderConfig?): DemoRuntimeConfig? = config?.let {
            DemoRuntimeConfig(
                baseUrl = it.baseUrl,
                apiKey = it.apiKey,
                model = it.model,
                maxOutputTokens = it.maxOutputTokens ?: 8192,
                protocol = it.protocol,
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
