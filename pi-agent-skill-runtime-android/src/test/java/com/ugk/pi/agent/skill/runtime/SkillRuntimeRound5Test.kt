package com.ugk.pi.agent.skill.runtime
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.UserConfirmationRequiredTool

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-5 P0 review regressions for the file-backed skill runtime:
 *
 * - S1: `skill_save` must refuse names that collide with host-provided
 *   plugin skills — such a save bricks every later run with
 *   "Duplicate skill id" at skill-assembly time and cannot be undone by the
 *   agent (skill tools never run again).
 * - S2: `memory_write` claims "Requires prior user consent" but was the only
 *   memory/skill mutation tool without a hard confirmation gate, so a model
 *   could silently destroy user memory with overwrite=true.
 * - S5: a UTF-8 BOM in front of the frontmatter made a valid SKILL.md
 *   permanently invalid (Windows-editor authored files).
 */
class SkillRuntimeRound5Test {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun skillRoot(): File = File(tempFolder.root, "agent-skills").apply { mkdirs() }

    private fun memoryRoot(): File = File(tempFolder.root, "agent-memory").apply { mkdirs() }

    // ------------------------------------------------------------------
    // S1: reserved host skill ids
    // ------------------------------------------------------------------

    @Test
    fun saveSkillRejectsReservedHostSkillIds() {
        val repository = SkillRepository(
            rootDir = skillRoot(),
            reservedSkillIds = setOf("agent-scheduled-tasks", "imported-files")
        )

        val outcome = repository.saveSkill(
            SkillSaveRequest(
                name = "agent-scheduled-tasks",
                description = "Impersonates a host skill id.",
                body = "Body"
            )
        )

        val failure = outcome as SkillSaveOutcome.Failed
        assertEquals("SKILL_NAME_RESERVED", failure.code)
        assertTrue(failure.message.contains("agent-scheduled-tasks"))
        // Nothing was written to disk.
        assertFalse(File(skillRoot(), "agent-scheduled-tasks").exists())
    }

    @Test
    fun saveSkillStillAcceptsFreeNamesWhenReservedIdsAreConfigured() {
        val repository = SkillRepository(
            rootDir = skillRoot(),
            reservedSkillIds = setOf("agent-scheduled-tasks")
        )

        val outcome = repository.saveSkill(
            SkillSaveRequest(name = "my-own-skill", description = "Fine", body = "Body")
        )

        assertTrue(outcome is SkillSaveOutcome.Saved)
    }

    // ------------------------------------------------------------------
    // S2: memory_write confirmation gate
    // ------------------------------------------------------------------

    @Test
    fun memoryWriteIsWrappedWithUserConfirmationByDefault() = runBlocking {
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot()
        )
        val writeTool = plugin.tools().single { it.name == "memory_write" }
        assertTrue(writeTool is UserConfirmationRequiredTool)

        val blocked = writeTool.execute(
            ToolCall(
                id = "call-1",
                name = "memory_write",
                input = buildJsonObject {
                    put("category", JsonPrimitive("rules"))
                    put("content", JsonPrimitive("injected rule"))
                    put("overwrite", JsonPrimitive(true))
                }
            ),
            ToolExecutionContext(sessionId = "s1")
        )

        assertTrue(blocked.isError)
        assertTrue(blocked.content.contains("User confirmation required"))
    }

    @Test
    fun memoryWriteConfirmationCanBeDisabledByHostOptOut() = runBlocking {
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot()),
            memoryRoot = memoryRoot(),
            requireMemoryWriteConfirmation = false
        )
        val writeTool = plugin.tools().single { it.name == "memory_write" }

        val result = writeTool.execute(
            ToolCall(
                id = "call-2",
                name = "memory_write",
                input = buildJsonObject {
                    put("category", JsonPrimitive("facts"))
                    put("content", JsonPrimitive("- fact"))
                }
            ),
            ToolExecutionContext(sessionId = "s1")
        )

        assertFalse(result.isError)
    }

    // ------------------------------------------------------------------
    // S5: UTF-8 BOM in front of the frontmatter
    // ------------------------------------------------------------------

    @Test
    fun parserAcceptsUtf8ByteOrderMarkBeforeFrontmatter() {
        val text = "\uFEFF---\nname: bom-skill\ndescription: Written by a Windows editor.\n---\nBody text"

        val parsed = SkillManifestParser.parse(text)

        val valid = parsed as SkillManifestParseResult.Valid
        assertEquals("bom-skill", valid.manifest.name)
        assertEquals("Body text", valid.body)
    }

    @Test
    fun repositoryScansBomAuthoredSkillAsValid() {
        val root = skillRoot()
        val directory = File(root, "bom-skill").apply { mkdirs() }
        File(directory, "SKILL.md").writeText(
            "\uFEFF---\nname: bom-skill\ndescription: Bom authored.\n---\nBody",
            Charsets.UTF_8
        )
        val repository = SkillRepository(root)

        val scanned = repository.load().single { it.directoryName == "bom-skill" }

        assertEquals(ScannedSkillStatus.VALID, scanned.status)
    }
}
