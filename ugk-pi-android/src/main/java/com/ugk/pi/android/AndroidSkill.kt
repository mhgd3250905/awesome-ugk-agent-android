package com.ugk.pi.android

data class AndroidSkill(
    val id: String,
    val description: String,
    val instructions: String,
    val methods: List<AndroidSkillMethod> = emptyList(),
    val triggers: List<String> = emptyList()
)

data class AndroidSkillMethod(
    val toolName: String,
    val purpose: String,
    val whenToUse: String,
    val resultSemantics: String
)

interface AndroidSkillProvider {
    fun skills(): List<AndroidSkill>
}

class StaticAndroidSkillProvider(
    private val skills: List<AndroidSkill>
) : AndroidSkillProvider {
    override fun skills(): List<AndroidSkill> = skills
}

object EmptyAndroidSkillProvider : AndroidSkillProvider {
    override fun skills(): List<AndroidSkill> = emptyList()
}

interface AndroidSkillResolver {
    fun resolve(
        userMessage: String,
        skills: List<AndroidSkill>,
        availableToolNames: Set<String>
    ): List<AndroidSkill>
}

class KeywordAndroidSkillResolver : AndroidSkillResolver {
    override fun resolve(
        userMessage: String,
        skills: List<AndroidSkill>,
        availableToolNames: Set<String>
    ): List<AndroidSkill> {
        val normalizedMessage = userMessage.lowercase()
        if (normalizedMessage.isBlank()) return emptyList()

        return skills.filter { skill ->
            skill.searchTerms().any { term ->
                normalizedMessage.contains(term.lowercase())
            } || skill.methods.any { method ->
                method.toolName in availableToolNames &&
                    normalizedMessage.contains(method.toolName.lowercase())
            }
        }
    }

    private fun AndroidSkill.searchTerms(): List<String> {
        if (triggers.isNotEmpty()) return triggers

        val terms = mutableListOf(id, description)
        terms += id.split('-', '_')
        terms += description
            .split(Regex("[^A-Za-z0-9_]+"))
            .filter { it.length >= 4 }
        return terms
    }
}

class AndroidSkillPromptBuilder {
    fun build(
        skills: List<AndroidSkill>,
        availableToolNames: Set<String>
    ): String {
        if (skills.isEmpty()) return ""

        return buildString {
            appendLine("Relevant Android-Skills are available for this turn.")
            appendLine("Use them as domain guidance for prebuilt host app methods. They do not grant new capabilities.")

            skills.forEach { skill ->
                appendLine()
                appendLine("## Android-Skill: ${skill.id}")
                appendLine("Description: ${skill.description}")
                appendLine()
                appendLine(skill.instructions.trim())

                val availableMethods = skill.methods.filter { it.toolName in availableToolNames }
                if (availableMethods.isNotEmpty()) {
                    appendLine()
                    appendLine("Available prebuilt methods:")
                    availableMethods.forEach { method ->
                        appendLine("- ${method.toolName}")
                        appendLine("  Purpose: ${method.purpose}")
                        appendLine("  When to use: ${method.whenToUse}")
                        appendLine("  Result semantics: ${method.resultSemantics}")
                    }
                }
            }
        }.trim()
    }
}
