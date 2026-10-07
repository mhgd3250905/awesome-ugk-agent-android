package com.ugk.pi.android.testapp

/**
 * The provider and transcript-preparation settings captured when an
 * AgentRuntime is built.
 *
 * [fullAuthorizationEnabled] belongs here because it is a fact about the built runtime, not
 * only about the current settings: the plugins register `show_user_confirmation_dialog` only
 * when confirmation is *not* bypassed, while the protected Tools read the flag live on every
 * call. Leaving it out of this identity let a settings change map to REUSE, so a user who
 * turned full authorization off kept a runtime whose protected Tools demanded a dialog Tool
 * that had never been registered - every high-impact action answered "call
 * show_user_confirmation_dialog first" for a Tool the model was never given, until the
 * process restarted.
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
    val fullAuthorizationEnabled: Boolean,
) {
    companion object {
        fun from(config: ApiProviderConfig?, fullAuthorizationEnabled: Boolean): DemoRuntimeConfig? =
            config?.let {
                DemoRuntimeConfig(
                    baseUrl = it.baseUrl,
                    apiKey = it.apiKey,
                    model = it.model,
                    maxOutputTokens = it.maxOutputTokens ?: 8192,
                    protocol = it.protocol,
                    contextWindow = ContextProfile.configValueOrDefault(it.contextWindow),
                    autoCompaction = it.autoCompaction ?: true,
                    compactionThreshold = it.compactionThreshold ?: ContextCompactor.DEFAULT_THRESHOLD,
                    fullAuthorizationEnabled = fullAuthorizationEnabled,
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
