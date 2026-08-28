package com.ugk.pi.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedSkillProviderTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun alwaysSkillEmbedsReferencedFilesBehindBody() {
        val root = skillRoot()
        writeSkill(
            root,
            "memory",
            frontmatter = "description: Memory skill.\nx-ugk-load: always\nx-ugk-embed-files: preferences.md, rules.md\n",
            body = "Memory body."
        )
        writeEmbed(root, "memory", "preferences.md", "- prefer Chinese")
        writeEmbed(root, "memory", "rules.md", "- no camera")

        val skill = provider(root).skills().single { it.id == "memory" }

        assertTrue(skill.instructions.contains("Memory body."))
        assertTrue(skill.instructions.contains("### Embedded: preferences.md"))
        assertTrue(skill.instructions.contains("- prefer Chinese"))
        assertTrue(skill.instructions.contains("### Embedded: rules.md"))
        assertTrue(skill.instructions.contains("- no camera"))
    }

    @Test
    fun alwaysSkillTruncatesOversizedEmbedWithNote() {
        val root = skillRoot()
        writeSkill(
            root,
            "memory",
            frontmatter = "description: Memory skill.\nx-ugk-load: always\nx-ugk-embed-files: big.md\n",
            body = "Body."
        )
        writeEmbed(root, "memory", "big.md", "b".repeat(20 * 1024))

        val skill = provider(root).skills().single { it.id == "memory" }

        assertTrue(skill.instructions.contains("### Embedded: big.md"))
        assertTrue(skill.instructions.contains("truncated at 16 KB"))
        assertFalse(skill.instructions.contains("b".repeat(20 * 1024)))
    }

    @Test
    fun alwaysSkillSkipsMissingEmbedWithNote() {
        val root = skillRoot()
        writeSkill(
            root,
            "memory",
            frontmatter = "description: Memory skill.\nx-ugk-load: always\nx-ugk-embed-files: missing.md\n",
            body = "Body."
        )

        val skill = provider(root).skills().single { it.id == "memory" }

        assertTrue(skill.instructions.contains("### Embedded: missing.md"))
        assertTrue(skill.instructions.contains("not found; skipped"))
    }

    @Test
    fun alwaysSkillEmbedsNamedRootFilesFromRegisteredRoot() {
        val root = skillRoot()
        val memoryRoot = tempFolder.newFolder("agent-memory")
        writeSkill(
            root,
            "agent-memory",
            frontmatter = "description: Memory.\nx-ugk-load: always\n" +
                "x-ugk-embed-files: local.md, memory:preferences.md\n",
            body = "Memory body."
        )
        writeEmbed(root, "agent-memory", "local.md", "- local seed")
        // A same-named file inside the skill directory must not shadow the
        // live named-root content.
        writeEmbed(root, "agent-memory", "preferences.md", "- stale template")
        File(memoryRoot, "preferences.md").writeText("- live preference")

        val provider = FileBackedSkillProvider(
            repository = SkillRepository(root),
            embedRoots = mapOf("memory" to memoryRoot)
        )
        val skill = provider.skills().single { it.id == "agent-memory" }

        assertTrue(skill.instructions.contains("### Embedded: memory:preferences.md"))
        assertTrue(skill.instructions.contains("- live preference"))
        assertFalse(skill.instructions.contains("- stale template"))
        assertTrue(skill.instructions.contains("### Embedded: local.md"))
        assertTrue(skill.instructions.contains("- local seed"))
    }

    @Test
    fun unregisteredEmbedRootIsSkippedWithNote() {
        val root = skillRoot()
        val memoryRoot = tempFolder.newFolder("agent-memory")
        File(memoryRoot, "preferences.md").writeText("- live preference")
        writeSkill(
            root,
            "agent-memory",
            frontmatter = "description: Memory.\nx-ugk-load: always\n" +
                "x-ugk-embed-files: memory:preferences.md\n",
            body = "Body."
        )

        // No embedRoots registered: the alias is unknown, so the entry is
        // skipped with a note instead of reading anything.
        val skill = provider(root).skills().single { it.id == "agent-memory" }

        assertTrue(skill.instructions.contains("### Embedded: memory:preferences.md"))
        assertTrue(skill.instructions.contains("unknown embed root 'memory'"))
        assertFalse(skill.instructions.contains("- live preference"))
    }

    @Test
    fun namedRootEmbedMissingFileSkipsWithNote() {
        val root = skillRoot()
        val memoryRoot = tempFolder.newFolder("agent-memory")
        writeSkill(
            root,
            "agent-memory",
            frontmatter = "description: Memory.\nx-ugk-load: always\n" +
                "x-ugk-embed-files: memory:rules.md\n",
            body = "Body."
        )

        val provider = FileBackedSkillProvider(
            repository = SkillRepository(root),
            embedRoots = mapOf("memory" to memoryRoot)
        )
        val skill = provider.skills().single { it.id == "agent-memory" }

        assertTrue(skill.instructions.contains("### Embedded: memory:rules.md"))
        assertTrue(skill.instructions.contains("not found; skipped"))
    }

    @Test
    fun namedRootEmbedsAreLiveAcrossSkillsCallsWithoutRebuildingProvider() {
        val root = skillRoot()
        val memoryRoot = tempFolder.newFolder("agent-memory")
        writeSkill(
            root,
            "agent-memory",
            frontmatter = "description: Memory.\nx-ugk-load: always\n" +
                "x-ugk-embed-files: memory:preferences.md\n",
            body = "Memory body."
        )
        val provider = FileBackedSkillProvider(
            repository = SkillRepository(root),
            embedRoots = mapOf("memory" to memoryRoot)
        )

        // Empty memory store first: the embed section shows the skip note.
        val before = provider.skills().single { it.id == "agent-memory" }
        assertTrue(before.instructions.contains("not found; skipped"))

        // memory_write lands new content on disk; the SAME provider instance
        // must pick it up on the next skills() call (live data, no rebuild).
        File(memoryRoot, "preferences.md").writeText("- [2026-08-27] 轻松语气 (user request)")
        val after = provider.skills().single { it.id == "agent-memory" }
        assertTrue(after.instructions.contains("- [2026-08-27] 轻松语气 (user request)"))
        assertFalse(after.instructions.contains("not found; skipped"))
    }

    @Test
    fun indexedSkillUsesStubAndKeepsDescription() {
        val root = skillRoot()
        writeSkill(
            root,
            "guide",
            frontmatter = "description: Big guide.\nx-ugk-load: indexed\n",
            body = "Full guide body."
        )

        val skill = provider(root).skills().single { it.id == "guide" }

        assertEquals("Big guide.", skill.description)
        assertEquals(FileBackedSkillProvider.INDEXED_STUB_INSTRUCTIONS, skill.instructions)
        assertFalse(skill.instructions.contains("Full guide body."))
    }

    @Test
    fun triggeredSkillUsesBodyAndPassesTriggersThrough() {
        val root = skillRoot()
        writeSkill(
            root,
            "helper",
            frontmatter = "description: Helper.\ntriggers: 记一下, remember\n",
            body = "Helper body."
        )

        val skill = provider(root).skills().single { it.id == "helper" }

        assertEquals("Helper body.", skill.instructions.trim())
        assertEquals(listOf("记一下", "remember"), skill.triggers)
    }

    @Test
    fun excludesInvalidSkills() {
        val root = skillRoot()
        writeSkill(root, "valid-skill", frontmatter = "description: Valid.\n", body = "Valid body.")
        writeSkill(root, "invalid-skill", frontmatter = "", body = "Body.")
        val provider = FileBackedSkillProvider(
            repository = SkillRepository(root)
        )

        val skills = provider.skills()

        assertEquals(listOf("valid-skill"), skills.map { it.id })
    }

    private fun provider(root: File): FileBackedSkillProvider {
        return FileBackedSkillProvider(repository = SkillRepository(root))
    }

    private fun skillRoot(): File = tempFolder.newFolder("agent-skills")

    private fun writeSkill(
        root: File,
        name: String,
        frontmatter: String,
        body: String
    ) {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $name\n$frontmatter---\n$body")
    }

    private fun writeEmbed(root: File, skillName: String, fileName: String, content: String) {
        File(File(root, skillName), fileName).writeText(content)
    }

}
