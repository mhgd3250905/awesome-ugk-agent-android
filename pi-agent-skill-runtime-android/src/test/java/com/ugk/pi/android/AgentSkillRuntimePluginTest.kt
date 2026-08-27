package com.ugk.pi.android

import java.io.File
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
}
