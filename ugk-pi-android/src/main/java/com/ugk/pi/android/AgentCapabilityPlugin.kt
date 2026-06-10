package com.ugk.pi.android

interface AgentCapabilityPlugin {
    val id: String

    fun tools(): List<AgentTool>

    fun skills(): List<AndroidSkill>
}
