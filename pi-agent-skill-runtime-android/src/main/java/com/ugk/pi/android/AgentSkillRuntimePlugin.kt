package com.ugk.pi.android

import java.io.File

/**
 * Capability plugin for the file-backed skill runtime.
 *
 * This is the complete entry point for the file-backed skill runtime: it
 * contributes skill tools, global instructions, and the dynamic
 * [FileBackedSkillProvider]. File skills therefore remain in [skillProviders]
 * while [skills] stays empty to avoid double injection. `memory_delete` is
 * wrapped with [UserConfirmationRequiredTool] by default because it destroys
 * user data; set [requireDeleteConfirmation] to false for the raw tool set.
 * [embedRoots] are the named roots that `x-ugk-embed-files` `alias:file.md`
 * entries resolve against; the same map is used by the tool and provider.
 */
class AgentSkillRuntimePlugin(
    private val repository: SkillRepository,
    private val memoryRoot: File,
    private val requireDeleteConfirmation: Boolean = true,
    private val shouldBypassConfirmation: () -> Boolean = { false },
    private val embedRoots: Map<String, File> = emptyMap()
) : AgentCapabilityPlugin {
    override val id: String = "agent-skill-runtime"

    private val fileBackedSkillProvider = FileBackedSkillProvider(repository, embedRoots)

    override fun tools(): List<AgentTool> {
        return agentSkillRuntimeTools(repository, memoryRoot, embedRoots).map { tool ->
            if (requireDeleteConfirmation && tool.name == "memory_delete") {
                UserConfirmationRequiredTool(
                    tool,
                    shouldBypassConfirmation = shouldBypassConfirmation
                )
            } else {
                tool
            }
        }
    }

    override fun skills(): List<AndroidSkill> = emptyList()

    override fun skillProviders(): List<AndroidSkillProvider> = listOf(fileBackedSkillProvider)

    override fun agentInstructions(): List<String> = listOf(
        """
            File-backed skills may be present in this runtime. Relevant skill content may
            be injected into your context each turn without appearing in the persisted
            conversation. When you see a metadata-only (indexed) skill, call skill_read
            with its name to load the full instructions before relying on it. The memory
            capture protocol is defined by the injected agent-memory skill: never write
            or delete user memory without explicit user consent in the conversation.
        """.trimIndent()
    )
}
