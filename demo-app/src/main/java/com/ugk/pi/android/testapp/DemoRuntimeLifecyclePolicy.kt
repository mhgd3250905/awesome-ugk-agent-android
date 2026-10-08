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

/**
 * Whether the built runtime's registered Tools no longer match the stored authorization
 * preference, and whether rebuilding now would interrupt anything.
 *
 * Split out of [MainActivity] because the decision is three booleans and only its effect
 * needs Android. The capability plugins add `show_user_confirmation_dialog` only when
 * confirmation is not bypassed, and that runs once inside `AgentRuntime`'s build, while the
 * protected Tools read the same stored preference live on every call. A runtime built while
 * the switch was on therefore holds protected Tools that answer with the missing-confirmation
 * wording, which names a Tool the model was never given, and no later resume corrected it.
 *
 * Rebuilding is not free: `rebuildRuntime` begins with `stopAgent(clearQueuedMessages =
 * true)`, which cancels pending urgent interactions, discards queued follow-up messages and
 * stops the in-flight turn. That is the user's own in-progress work and has nothing to do with
 * authorization, so a mismatch while something is running stays pending and is synced at the
 * next run start instead of destroying it.
 *
 * `applied == null` alongside an existing runtime means the mode was never recorded for it, so
 * it counts as a mismatch: rebuilding is the safe direction, keeping a runtime whose protected
 * Tools may be stranded is not.
 */
internal object DemoRuntimeAuthorizationSync {
    fun shouldRebuild(
        runtimeExists: Boolean,
        applied: Boolean?,
        current: Boolean,
        runInFlight: Boolean
    ): Boolean = runtimeExists && applied != current && !runInFlight
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
