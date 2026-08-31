package com.ugk.pi.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun scansValidSkillDirectory() {
        val root = tempFolder.newFolder("agent-skills")
        writeSkill(root, "my-skill", "description: Useful skill.\n", "Body instructions.")

        val scanned = SkillRepository(root).load().single()

        assertEquals(ScannedSkillStatus.VALID, scanned.status)
        assertEquals("my-skill", scanned.manifest?.name)
        assertEquals("Body instructions.", scanned.body.trim())
        assertEquals(null, scanned.error)
    }

    @Test
    fun reportsInvalidWhenNameDoesNotMatchDirectory() {
        val root = tempFolder.newFolder("agent-skills")
        writeSkill(
            root,
            directory = "directory-name",
            frontmatter = "name: other-name\ndescription: d.\n",
            body = "Body."
        )

        val scanned = SkillRepository(root).load().single()

        assertEquals(ScannedSkillStatus.INVALID, scanned.status)
        assertEquals(null, scanned.manifest)
        assertTrue(scanned.error!!.contains("does not match"))
        assertTrue(scanned.error.contains("directory-name"))
    }

    @Test
    fun reportsInvalidWhenSkillMdIsMissing() {
        val root = tempFolder.newFolder("agent-skills")
        File(root, "empty-skill").mkdirs()

        val scanned = SkillRepository(root).load().single()

        assertEquals(ScannedSkillStatus.INVALID, scanned.status)
        assertEquals("empty-skill", scanned.directoryName)
        assertTrue(scanned.error!!.contains("SKILL.md not found"))
    }

    @Test
    fun reportsInvalidWhenFileExceedsTotalLimit() {
        val root = tempFolder.newFolder("agent-skills")
        val dir = File(root, "big-skill").apply { mkdirs() }
        File(dir, "SKILL.md").writeText(
            "---\nname: big-skill\ndescription: d.\n---\n" + "a".repeat(128 * 1024 + 1)
        )

        val scanned = SkillRepository(root).load().single()

        assertEquals(ScannedSkillStatus.INVALID, scanned.status)
        assertTrue(scanned.error!!.contains("128 KB"))
    }

    @Test
    fun reportsInvalidForParseFailureInsteadOfDropping() {
        val root = tempFolder.newFolder("agent-skills")
        writeSkill(root, "broken-skill", "", "Body.")

        val scanned = SkillRepository(root).load().single()

        assertEquals(ScannedSkillStatus.INVALID, scanned.status)
        assertTrue(scanned.error!!.contains("description"))
    }

    @Test
    fun rescansDiskOnEveryLoad() {
        val root = tempFolder.newFolder("agent-skills")
        val repository = SkillRepository(root)
        assertEquals(0, repository.load().size)

        writeSkill(root, "late-skill", "description: d.\n", "Body.")
        assertEquals(1, repository.load().size)
    }

    @Test
    fun returnsEmptyListWhenRootIsMissing() {
        val repository = SkillRepository(File(tempFolder.root, "does-not-exist"))

        assertTrue(repository.load().isEmpty())
    }

    @Test
    fun rejectsSymlinkedSkillDirectoryEscapingRoot() {
        val root = tempFolder.newFolder("agent-skills")
        val outside = File(tempFolder.root, "outside").apply { mkdirs() }
        File(outside, "SKILL.md").writeText(
            "---\nname: evil\ndescription: Escapes the skill root.\n---\nInjected body."
        )
        val link = File(root, "evil")

        try {
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (error: Exception) {
                assumeNoException("Symbolic links are not available in this test environment.", error)
                return
            }
            assumeTrue(
                "The test runtime does not canonicalize symbolic-link targets.",
                File(link, "SKILL.md").canonicalFile == File(outside, "SKILL.md").canonicalFile
            )

            val scanned = SkillRepository(root).load()

            val evil = scanned.single { it.directoryName == "evil" }
            assertEquals(ScannedSkillStatus.INVALID, evil.status)
            assertTrue(evil.error!!.contains("skill repository"))
            assertTrue(FileBackedSkillProvider(SkillRepository(root)).skills().none { it.id == "evil" })
        } finally {
            link.delete()
        }
    }

    @Test
    fun rejectsSkillMdSymlinkEscapingItsDirectory() {
        val root = tempFolder.newFolder("agent-skills")
        val directory = File(root, "leaky").apply { mkdirs() }
        val outside = File(tempFolder.root, "outside").apply { mkdirs() }
        File(outside, "SKILL.md").writeText(
            "---\nname: leaky\ndescription: Escapes through SKILL.md.\n---\nInjected body."
        )
        val link = File(directory, "SKILL.md")

        try {
            try {
                Files.createSymbolicLink(link.toPath(), File(outside, "SKILL.md").toPath())
            } catch (error: Exception) {
                assumeNoException("Symbolic links are not available in this test environment.", error)
                return
            }
            assumeTrue(
                "The test runtime does not canonicalize symbolic-link targets.",
                link.canonicalFile == File(outside, "SKILL.md").canonicalFile
            )

            val scanned = SkillRepository(root).load()

            val leaky = scanned.single { it.directoryName == "leaky" }
            assertEquals(ScannedSkillStatus.INVALID, leaky.status)
            assertTrue(leaky.error!!.contains("SKILL.md"))
            assertTrue(FileBackedSkillProvider(SkillRepository(root)).skills().none { it.id == "leaky" })
        } finally {
            link.delete()
        }
    }

    @Test
    fun neverScansNestedDirectoriesAsSkills() {
        val root = tempFolder.newFolder("agent-skills")
        val inner = File(root, "nested/inner").apply { mkdirs() }
        File(inner, "SKILL.md").writeText("---\nname: inner\ndescription: d.\n---\nBody.")

        val scanned = SkillRepository(root).load()

        assertEquals(listOf("nested"), scanned.map { it.directoryName })
        assertEquals(ScannedSkillStatus.INVALID, scanned.single().status)
    }

    /**
     * Guards the packaged agent-memory asset: it must parse as a valid
     * always-skill whose embeds point at the live memory store through the
     * `memory:` named root (not at static seed files inside the skill
     * directory). Unit tests run with the module directory as working
     * directory.
     */
    @Test
    fun packagedAgentMemoryAssetParsesAsValidAlwaysSkill() {
        val assetFile = File("src/main/assets/agent-skills/agent-memory/SKILL.md")
        val parsed = SkillManifestParser.parse(assetFile.readText(Charsets.UTF_8))

        val valid = parsed as SkillManifestParseResult.Valid
        assertEquals("agent-memory", valid.manifest.name)
        assertEquals(SkillLoadPolicy.ALWAYS, valid.manifest.loadPolicy)
        assertEquals(
            listOf("memory:preferences.md", "memory:rules.md"),
            valid.manifest.embedFiles
        )
        assertTrue(valid.body.toByteArray(Charsets.UTF_8).size <= 3 * 1024)
    }

    @Test
    fun packagedAndroidSkillCreatorAssetParsesAsIndexedAuthoringSkill() {
        val assetFile = File("src/main/assets/agent-skills/android-skill-creator/SKILL.md")
        val parsed = SkillManifestParser.parse(assetFile.readText(Charsets.UTF_8))

        val valid = parsed as SkillManifestParseResult.Valid
        assertEquals("android-skill-creator", valid.manifest.name)
        assertEquals(SkillLoadPolicy.INDEXED, valid.manifest.loadPolicy)
        assertTrue(valid.body.contains("skill_save"))
        assertTrue(valid.body.contains("skill_list"))
        assertTrue(valid.body.contains("skill_read"))
        assertTrue(valid.body.contains("skill_delete"))
    }

    private fun writeSkill(root: File, directory: String, frontmatter: String, body: String) {
        val dir = File(root, directory).apply { mkdirs() }
        File(dir, "SKILL.md").writeText(
            if (frontmatter.contains("name:")) {
                "---\n$frontmatter---\n$body"
            } else {
                "---\nname: $directory\n$frontmatter---\n$body"
            }
        )
    }
}
