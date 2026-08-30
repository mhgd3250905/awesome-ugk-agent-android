package com.ugk.pi.android

import java.io.File

/**
 * Capability plugin for the file-backed skill runtime.
 *
 * This is the complete entry point for the file-backed skill runtime: it
 * contributes skill tools, global instructions, and the dynamic
 * [FileBackedSkillProvider]. File skills therefore remain in [skillProviders]
 * while [skills] stays empty to avoid double injection. `skill_save`,
 * `skill_delete`, and `memory_delete` are wrapped with
 * [UserConfirmationRequiredTool] by default because they mutate skill or
 * memory state; set [requireDeleteConfirmation] or
 * [requireSkillMutationConfirmation] to false for the respective raw tools.
 * [embedRoots] are the named roots that `x-ugk-embed-files` `alias:file.md`
 * entries resolve against; the same map is used by the tool and provider.
 */
class AgentSkillRuntimePlugin(
    private val repository: SkillRepository,
    private val memoryRoot: File,
    private val requireDeleteConfirmation: Boolean = true,
    private val shouldBypassConfirmation: () -> Boolean = { false },
    private val embedRoots: Map<String, File> = emptyMap(),
    private val requireSkillMutationConfirmation: Boolean = true
) : AgentCapabilityPlugin {
    override val id: String = "agent-skill-runtime"

    private val fileBackedSkillProvider = FileBackedSkillProvider(repository, embedRoots)

    override fun tools(): List<AgentTool> {
        return agentSkillRuntimeTools(repository, memoryRoot, embedRoots).map { tool ->
            val requiresConfirmation = when (tool.name) {
                "memory_delete" -> requireDeleteConfirmation
                "skill_save", "skill_delete" -> requireSkillMutationConfirmation
                else -> false
            }
            if (requiresConfirmation) {
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
            The indexed android-skill-creator skill defines the create, update, query,
            delete, and use SOP for file-backed skills. A skill is only complete after
            skill_list reports valid and skill_read verifies the saved manifest and body.
        """.trimIndent()
    )
}
