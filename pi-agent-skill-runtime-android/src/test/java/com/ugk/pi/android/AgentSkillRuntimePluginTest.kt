package com.ugk.pi.android

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillRuntimePluginTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun exposesRawToolSetWithoutDeleteConfirmation() = runBlocking {
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot(),
            requireDeleteConfirmation = false
        )

        val toolNames = plugin.tools().map { it.name }

        assertEquals(
            listOf(
                "skill_list",
                "skill_read",
                "memory_list",
                "memory_read",
                "memory_write",
                "memory_delete"
            ),
            toolNames
        )
    }

    @Test
    fun wrapsMemoryDeleteWithUserConfirmationByDefault() = runBlocking {
        val memoryRoot = memoryRoot()
        File(memoryRoot, "facts.md").writeText("- fact")
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot
        )
        val deleteTool = plugin.tools().single { it.name == "memory_delete" }

        val blocked = deleteTool.execute(
            ToolCall(
                id = "call-1",
                name = "memory_delete",
                input = buildJsonObject { put("category", "facts") }
            ),
            ToolExecutionContext(sessionId = "test")
        )
        val bypassed = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot,
            requireDeleteConfirmation = true,
            shouldBypassConfirmation = { true }
        ).tools().single { it.name == "memory_delete" }.execute(
            ToolCall(
                id = "call-2",
                name = "memory_delete",
                input = buildJsonObject { put("category", "facts") }
            ),
            ToolExecutionContext(sessionId = "test")
        )

        assertTrue(blocked.isError)
        assertTrue(blocked.content.contains("show_user_confirmation_dialog"))
        assertFalse(bypassed.isError)
        assertFalse(File(memoryRoot, "facts.md").exists())
    }

    @Test
    fun contributesNoSkillsAndAGlobalInstruction() {
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot()
        )

        assertEquals("agent-skill-runtime", plugin.id)
        assertTrue(plugin.skills().isEmpty())
        val instructions = plugin.agentInstructions().single()
        assertTrue(instructions.contains("skill_read"))
        assertTrue(instructions.contains("consent"))
    }

    @Test
    fun contributesTheFileBackedProviderAsItsDynamicSkillSource() {
        val skillRoot = skillRoot()
        writeSkill(skillRoot, "file-skill", body = "File skill body.")
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot),
            memoryRoot = memoryRoot()
        )

        assertEquals(1, plugin.skillProviders().size)
        assertEquals(
            AndroidSkillProviderSource.FILE_BACKED,
            plugin.skillProviders().single().source
        )
        assertEquals(
            listOf("file-skill"),
            plugin.skillProviders().single().skills().map { it.id }
        )
    }

    @Test
    fun standaloneRegistrationAssemblesToolsInstructionsAndLiveFileSkills() = runBlocking {
        val skillRoot = skillRoot()
        val memoryRoot = memoryRoot()
        writeSkill(
            root = skillRoot,
            name = "file-skill",
            frontmatter = "x-ugk-load: always\nx-ugk-embed-files: memory:preferences.md\n",
            body = "FILE_SKILL_V1"
        )
        File(memoryRoot, "preferences.md").writeText("EMBED_V1")
        val repository = SkillRepository(skillRoot)
        val llm = RecordingProvider()
        val plugin = AgentSkillRuntimePlugin(
            repository = repository,
            memoryRoot = memoryRoot,
            embedRoots = mapOf("memory" to memoryRoot)
        )
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(LoadPolicySkillResolver(repository))
            .register(plugin)
            .build()

        runtime.run(AgentSession("file-skills-v1"), "hello").toList()

        writeSkill(
            root = skillRoot,
            name = "file-skill",
            frontmatter = "x-ugk-load: always\nx-ugk-embed-files: memory:preferences.md\n",
            body = "FILE_SKILL_V2"
        )
        File(memoryRoot, "preferences.md").writeText("EMBED_V2")

        runtime.run(AgentSession("file-skills-v2"), "hello").toList()

        assertEquals(
            listOf(
                "skill_list",
                "skill_read",
                "memory_list",
                "memory_read",
                "memory_write",
                "memory_delete"
            ),
            llm.requests.first().tools.map { it.name }
        )
        val firstSystem = llm.requests[0].messages.filterIsInstance<AgentMessage.System>()
        val secondSystem = llm.requests[1].messages.filterIsInstance<AgentMessage.System>()
        assertTrue(firstSystem.any { it.content.contains("skill_read") && it.content.contains("consent") })
        assertTrue(firstSystem.any { it.content.contains("FILE_SKILL_V1") && it.content.contains("EMBED_V1") })
        assertTrue(secondSystem.any { it.content.contains("FILE_SKILL_V2") && it.content.contains("EMBED_V2") })
        assertEquals(
            1,
            secondSystem.sumOf { system ->
                "## Android-Skill: file-skill".toRegex().findAll(system.content).count()
            }
        )
    }

    @Test
    fun fileBackedIdCollisionFailsBeforeLoadPolicyResolver() = runBlocking {
        val skillRoot = skillRoot()
        val memoryRoot = memoryRoot()
        writeSkill(
            root = skillRoot,
            name = "colliding-skill",
            frontmatter = "x-ugk-load: always\n",
            body = "FILE_COLLISION"
        )
        val repository = SkillRepository(skillRoot)
        var resolverCalls = 0
        val resolver = object : AndroidSkillResolver {
            private val delegate = LoadPolicySkillResolver(repository)

            override fun resolve(
                userMessage: String,
                skills: List<AndroidSkill>,
                availableToolNames: Set<String>
            ): List<AndroidSkill> {
                resolverCalls++
                return delegate.resolve(userMessage, skills, availableToolNames)
            }
        }
        val llm = RecordingProvider()
        val session = AgentSession("file-collision")
        val runtime = AgentRuntime.Builder()
            .llmProvider(llm)
            .skillResolver(resolver)
            .register(
                AgentSkillRuntimePlugin(
                    repository = repository,
                    memoryRoot = memoryRoot
                )
            )
            .skillProvider(
                StaticAndroidSkillProvider(
                    listOf(AndroidSkill("colliding-skill", "Custom", "CUSTOM_COLLISION"))
                )
            )
            .build()

        val events = runtime.run(session, "assemble").toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failure.message.contains("Duplicate skill id 'colliding-skill'"))
        assertTrue(failure.message.contains("agent-skill-runtime"))
        assertTrue(failure.message.contains("custom skillProvider()"))
        assertEquals(0, resolverCalls)
        assertTrue(llm.requests.isEmpty())
        assertEquals(listOf(AgentMessage.User("assemble")), session.messages)
    }

    @Test
    fun confirmationWrappedToolDescriptionChangesWithBypass() {
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot()
        )
        val bypassingPlugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot(),
            shouldBypassConfirmation = { true }
        )

        val description = plugin.tools().single { it.name == "memory_delete" }.description
        val bypassingDescription =
            bypassingPlugin.tools().single { it.name == "memory_delete" }.description

        assertTrue(description.contains("show_user_confirmation_dialog"))
        assertTrue(bypassingDescription.contains("full authorization"))
    }

    private fun skillRoot(): File = File(tempFolder.root, "agent-skills").apply { mkdirs() }

    private fun memoryRoot(): File = File(tempFolder.root, "agent-memory").apply { mkdirs() }

    private fun writeSkill(
        root: File,
        name: String,
        frontmatter: String = "",
        body: String
    ) {
        val directory = File(root, name).apply { mkdirs() }
        File(directory, "SKILL.md").writeText(
            "---\nname: $name\ndescription: File-backed skill.\n$frontmatter---\n$body"
        )
    }

    private class RecordingProvider : LLMProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun generate(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse("done")
        }
    }
}
