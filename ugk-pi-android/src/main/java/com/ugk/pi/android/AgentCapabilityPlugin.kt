package com.ugk.pi.android

interface AgentCapabilityPlugin {
    val id: String

    fun tools(): List<AgentTool>

    fun skills(): List<AndroidSkill>

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
