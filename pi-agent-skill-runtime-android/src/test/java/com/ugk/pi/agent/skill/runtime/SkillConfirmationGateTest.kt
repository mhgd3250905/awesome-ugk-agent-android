package com.ugk.pi.agent.skill.runtime

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import com.ugk.pi.android.UserConfirmationRequiredTool
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The confirmation gate of the file-backed skill runtime, one case per name the
 * plugin claims to protect.
 *
 * `AgentSkillRuntimePlugin`'s KDoc states that `skill_save`, `skill_delete`,
 * `memory_delete` and `memory_write` "are wrapped with
 * [UserConfirmationRequiredTool] by default". Before this file only `skill_save`
 * and `memory_delete` were observable: `skill_delete` was exercised exclusively
 * with `shouldBypassConfirmation = { true }`, which passes whether or not the
 * tool is wrapped, so renaming the protection entry `skill_delete` to something
 * that matches no tool left all 89 tests green (measured:
 * mut-m4-skill-delete-protection-dead.log; the same mutation on `skill_save`
 * does fail - mut-m4b - which is what localises the gap to this name rather than
 * to the wiring as a whole).
 *
 * Each case checks both directions, because a gate that refuses everything gets
 * deleted: the default plugin must refuse without a ticket and leave the state
 * untouched, and the same call must go through when the host has full
 * authorization.
 */
class SkillConfirmationGateTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun everyDeclaredMutationToolIsGatedByNameAndBehaviour() = runBlocking {
        val failures = mutableListOf<String>()

        protectedCases().forEach { case ->
            val skillRoot = File(tempFolder.root, "skills-${case.name}").apply { mkdirs() }
            val memoryRoot = File(tempFolder.root, "memory-${case.name}").apply { mkdirs() }
            case.prepare(skillRoot, memoryRoot)

            val gated = AgentSkillRuntimePlugin(
                repository = SkillRepository(skillRoot),
                memoryRoot = memoryRoot
            ).tools().singleOrNull { it.name == case.name }

            if (gated == null) {
                failures += "${case.name}: the plugin exposes no tool with this name, so the " +
                    "protection entry cannot match anything"
            } else if (gated !is UserConfirmationRequiredTool) {
                failures += "${case.name}: not wrapped in UserConfirmationRequiredTool - the " +
                    "protection set no longer names this tool, so the documented consent gate " +
                    "is silently gone"
            } else {
                val blocked = gated.execute(
                    ToolCall("blocked-${case.name}", case.name, case.input),
                    ToolExecutionContext(sessionId = "test")
                )
                if (!blocked.isError || !blocked.content.contains("show_user_confirmation_dialog")) {
                    failures += "${case.name}: executed with no confirmation ticket " +
                        "(isError=${blocked.isError}, content=${blocked.content.take(140)})"
                }
                case.stateAfterBlock(skillRoot, memoryRoot)?.let { failures += it }

                val bypassed = AgentSkillRuntimePlugin(
                    repository = SkillRepository(skillRoot),
                    memoryRoot = memoryRoot,
                    shouldBypassConfirmation = { true }
                ).tools().single { it.name == case.name }.execute(
                    ToolCall("bypass-${case.name}", case.name, case.input),
                    ToolExecutionContext(sessionId = "test")
                )
                if (bypassed.isError) {
                    failures += "${case.name}: the full-authorization bypass refused as well " +
                        "(content=${bypassed.content.take(140)})"
                }
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    /** The other direction: a read-only tool inside the protection set would be a gate nobody can pass. */
    @Test
    fun readOnlyToolsAreNeverWrapped() {
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(File(tempFolder.root, "skills-readonly").apply { mkdirs() }),
            memoryRoot = File(tempFolder.root, "memory-readonly").apply { mkdirs() }
        )
        val readOnly = setOf("skill_list", "skill_read", "memory_list", "memory_read")
        val present = plugin.tools().filter { it.name in readOnly }
        assertEquals(
            "this assertion is only meaningful if it is looking at all four read-only tools",
            4,
            present.size
        )
        assertEquals(
            emptyList<String>(),
            present.filterIsInstance<UserConfirmationRequiredTool>()
                .map { "${it.name}: a read-only tool is inside the protection set" }
        )
    }

    @Test
    fun turningEveryConfirmationFlagOffUnwrapsTheWholeSet() = runBlocking {
        val skillRoot = File(tempFolder.root, "skills-off").apply { mkdirs() }
        val memoryRoot = File(tempFolder.root, "memory-off").apply { mkdirs() }
        writeSkill(skillRoot, "gate-guide")
        val plugin = AgentSkillRuntimePlugin(
            repository = SkillRepository(skillRoot),
            memoryRoot = memoryRoot,
            requireDeleteConfirmation = false,
            requireMemoryWriteConfirmation = false,
            requireSkillMutationConfirmation = false
        )

        assertEquals(
            "with all three flags false nothing may still demand a ticket",
            emptyList<String>(),
            plugin.tools().filterIsInstance<UserConfirmationRequiredTool>().map { it.name }
        )
        val deleted = plugin.tools().single { it.name == "skill_delete" }.execute(
            ToolCall("call-1", "skill_delete", buildJsonObject { put("name", "gate-guide") }),
            ToolExecutionContext(sessionId = "test")
        )
        assertFalse(deleted.isError)
        assertFalse(File(skillRoot, "gate-guide").exists())
    }

    private class ProtectedCase(
        val name: String,
        val input: JsonObject,
        val prepare: (skillRoot: File, memoryRoot: File) -> Unit = { _, _ -> },
        val stateAfterBlock: (skillRoot: File, memoryRoot: File) -> String? = { _, _ -> null }
    )

    private fun protectedCases(): List<ProtectedCase> = listOf(
        ProtectedCase(
            name = "skill_save",
            input = buildJsonObject {
                put("name", "gate-guide")
                put("description", "Gate guide.")
                put("body", "Gate body.")
            },
            stateAfterBlock = { skillRoot, _ ->
                if (File(skillRoot, "gate-guide").exists()) {
                    "skill_save created the skill without a ticket"
                } else {
                    null
                }
            }
        ),
        ProtectedCase(
            name = "skill_delete",
            input = buildJsonObject { put("name", "gate-guide") },
            prepare = { skillRoot, _ -> writeSkill(skillRoot, "gate-guide") },
            stateAfterBlock = { skillRoot, _ ->
                if (File(skillRoot, "gate-guide").exists()) {
                    null
                } else {
                    "skill_delete removed an existing skill without a ticket"
                }
            }
        ),
        ProtectedCase(
            name = "memory_write",
            input = buildJsonObject {
                put("category", "facts")
                put("content", "- overwritten by an ungated call")
                put("overwrite", true)
            },
            prepare = { _, memoryRoot -> File(memoryRoot, "facts.md").writeText("- original fact\n") },
            stateAfterBlock = { _, memoryRoot ->
                val content = File(memoryRoot, "facts.md").readText()
                if (content == "- original fact\n") {
                    null
                } else {
                    "memory_write replaced the category file without a ticket (content=$content)"
                }
            }
        ),
        ProtectedCase(
            name = "memory_delete",
            input = buildJsonObject { put("category", "facts") },
            prepare = { _, memoryRoot -> File(memoryRoot, "facts.md").writeText("- fact") },
            stateAfterBlock = { _, memoryRoot ->
                if (File(memoryRoot, "facts.md").exists()) {
                    null
                } else {
                    "memory_delete removed the category file without a ticket"
                }
            }
        )
    )

    private fun writeSkill(root: File, name: String) {
        val directory = File(root, name).apply { mkdirs() }
        File(directory, "SKILL.md").writeText(
            "---\nname: $name\ndescription: File-backed skill.\n---\nbody\n"
        )
    }
}
