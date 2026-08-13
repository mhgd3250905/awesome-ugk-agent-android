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
}
