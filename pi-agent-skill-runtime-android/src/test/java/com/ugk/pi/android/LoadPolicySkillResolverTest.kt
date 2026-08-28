package com.ugk.pi.android

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadPolicySkillResolverTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun alwaysAndIndexedSkillsResolveUnconditionally() {
        val root = skillRoot()
        writeSkill(root, "always-skill", "description: Always.\nx-ugk-load: always\n", "Body.")
        writeSkill(root, "indexed-skill", "description: Indexed.\nx-ugk-load: indexed\n", "Body.")
        val resolver = LoadPolicySkillResolver(SkillRepository(root))
        val skills = listOf(
            AndroidSkill(id = "always-skill", description = "Always.", instructions = "Body."),
            AndroidSkill(id = "indexed-skill", description = "Indexed.", instructions = "stub")
        )

        val resolved = resolver.resolve(
            "Tell me a joke about cats.",
            skills,
            emptySet(),
            AndroidSkillResolutionContext(fileBackedSkillIds = skills.map { it.id }.toSet())
        )

        assertEquals(listOf("always-skill", "indexed-skill"), resolved.map { it.id })
    }

    @Test
    fun triggeredSkillResolvesByTriggerIncludingChinese() {
        val root = skillRoot()
        writeSkill(root, "memory-helper", "description: Helper.\ntriggers: 记一下, remember\n", "Body.")
        val resolver = LoadPolicySkillResolver(SkillRepository(root))
        val skills = listOf(
            AndroidSkill(
                id = "memory-helper",
                description = "Helper.",
                instructions = "Body.",
                triggers = listOf("记一下", "remember")
            )
        )

        val fileContext = AndroidSkillResolutionContext(fileBackedSkillIds = setOf("memory-helper"))
        val hit = resolver.resolve("帮我记一下这个偏好", skills, emptySet(), fileContext)
        val miss = resolver.resolve("What is the weather?", skills, emptySet(), fileContext)

        assertEquals(listOf("memory-helper"), hit.map { it.id })
        assertTrue(miss.isEmpty())
    }

    @Test
    fun triggeredSkillFallsBackToDescriptionTokens() {
        val root = skillRoot()
        writeSkill(root, "camera-guide", "description: Guide for camera capture.\n", "Body.")
        val resolver = LoadPolicySkillResolver(SkillRepository(root))
        val skills = listOf(
            AndroidSkill(id = "camera-guide", description = "Guide for camera capture.", instructions = "Body.")
        )

        val resolved = resolver.resolve(
            "How does camera capture work?",
            skills,
            emptySet(),
            AndroidSkillResolutionContext(fileBackedSkillIds = setOf("camera-guide"))
        )

        assertEquals(listOf("camera-guide"), resolved.map { it.id })
    }

    @Test
    fun pluginDeclaredSkillsKeepKeywordResolverSemantics() {
        val resolver = LoadPolicySkillResolver(SkillRepository(skillRoot()))
        val pluginDeclaredSkill = AndroidSkill(
            id = "legacy-automation",
            description = "Legacy automation helper.",
            instructions = "Body.",
            methods = listOf(
                AndroidSkillMethod(
                    toolName = "legacy_tool",
                    purpose = "p",
                    whenToUse = "w",
                    resultSemantics = "r"
                )
            )
        )
        val skills = listOf(pluginDeclaredSkill)

        val byToolName = resolver.resolve("Please run legacy_tool now.", skills, setOf("legacy_tool"))
        val unrelated = resolver.resolve("Tell me a joke.", skills, setOf("legacy_tool"))

        assertEquals(listOf("legacy-automation"), byToolName.map { it.id })
        assertTrue(unrelated.isEmpty())
    }

    @Test
    fun pluginDeclaredSkillsStillResolveWhenFileSkillsExist() {
        val root = skillRoot()
        writeSkill(root, "file-skill", "description: File.\nx-ugk-load: triggered\n", "Body.")
        val resolver = LoadPolicySkillResolver(SkillRepository(root))
        val pluginDeclaredSkill = AndroidSkill(
            id = "notes",
            description = "Note taking.",
            instructions = "Body.",
            triggers = listOf("note", "笔记")
        )
        val fileSkill = AndroidSkill(id = "file-skill", description = "File.", instructions = "Body.")

        val resolved = resolver.resolve(
            "帮我看看笔记",
            listOf(fileSkill, pluginDeclaredSkill),
            emptySet(),
            AndroidSkillResolutionContext(fileBackedSkillIds = setOf("file-skill"))
        )

        assertEquals(listOf("notes"), resolved.map { it.id })
    }

    @Test
    fun customSkillMatchingRepositoryIdIsNotTreatedAsFileSkill() = runBlocking {
        val root = skillRoot()
        writeSkill(root, "shared-id", "description: File.\nx-ugk-load: always\n", "FILE_BODY")
        val repository = SkillRepository(root)
        val customSkill = AndroidSkill(
            id = "shared-id",
            description = "Custom skill.",
            instructions = "CUSTOM_BODY",
            triggers = listOf("never-match")
        )
        val llm = RecordingProvider()
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(LoadPolicySkillResolver(repository))
            .skillProvider(StaticAndroidSkillProvider(listOf(customSkill)))
            .build()

        runtime.run(AgentSession("custom-source"), "unrelated request").toList()

        assertTrue(llm.requests.single().messages.none { message ->
            message is AgentMessage.System && message.content.contains("CUSTOM_BODY")
        })
    }

    private fun skillRoot(): File = tempFolder.newFolder("agent-skills")

    private fun writeSkill(root: File, name: String, frontmatter: String, body: String) {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $name\n$frontmatter---\n$body")
    }

    private class RecordingProvider : LLMProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse("done")
        }
    }
}
