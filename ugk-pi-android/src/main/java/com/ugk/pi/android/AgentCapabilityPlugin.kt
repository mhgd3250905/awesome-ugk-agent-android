package com.ugk.pi.android

interface AgentCapabilityPlugin {
    val id: String

    fun tools(): List<AgentTool>

    /**
     * Skills declared by this plugin. The Runtime queries this on every run
     * so stateful confirmation policy can be reflected without rebuilding it.
     */
    fun skills(): List<AndroidSkill>

    /**
     * Dynamic skill providers owned by this plugin.
     *
     * The plugin is retained by [AgentRuntime.Builder]; its provider list and
     * each provider are queried on every run. The JVM default lets existing
     * plugin implementations omit this method; it is not a claim that the
     * complete AAR consumer ABI is unchanged.
     */
    fun skillProviders(): List<AndroidSkillProvider> = emptyList()

    /**
     * Global instructions for the runtime Agent.
     *
     * This is deliberately separate from the development-time root AGENTS.md.
     * A plugin may contribute a packaged runtime AGENTS.md (or an equivalent
     * system-level contract) that is visible on every model request while the
     * plugin is registered.
     */
    fun agentInstructions(): List<String> = emptyList()

    /**
     * Requests cancellation of all active or queued work owned by this plugin.
     *
     * The default is a no-op so existing plugins remain source and binary
     * compatible. Implementations should make repeated calls safe and return
     * the number of work items that accepted cancellation.
     */
    fun cancelAll(): Int = 0

    /**
     * Releases resources owned by this plugin.
     *
     * [AgentRuntime.close] invokes this at most once for a registered plugin.
     * Implementations should nevertheless keep direct calls idempotent.
     */
    fun close() = Unit
}
