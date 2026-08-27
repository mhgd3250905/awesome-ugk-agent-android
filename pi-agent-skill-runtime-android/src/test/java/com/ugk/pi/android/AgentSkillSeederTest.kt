package com.ugk.pi.android

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillSeederTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun seedsPackagedSkillTreeIntoTargetRoot() {
        val source = FakeAssetSource(
            mapOf(
                "agent-skills/agent-memory/SKILL.md" to "---\nname: agent-memory\n".toByteArray(),
                "agent-skills/agent-memory/preferences.md" to "# preferences".toByteArray(),
                "agent-skills/agent-memory/rules.md" to "# rules".toByteArray()
            )
        )
        val targetRoot = File(tempFolder.root, "agent-skills")

        val seeded = AgentSkillSeeder.seed(source, targetRoot)

        assertEquals(3, seeded)
        assertEquals(
            "---\nname: agent-memory\n",
            File(targetRoot, "agent-memory/SKILL.md").readText()
        )
        assertEquals("# preferences", File(targetRoot, "agent-memory/preferences.md").readText())
        assertEquals("# rules", File(targetRoot, "agent-memory/rules.md").readText())
    }

    @Test
    fun seedingIsIdempotentAndNeverOverwritesExistingTargets() {
        val source = FakeAssetSource(
            mapOf(
                "agent-skills/agent-memory/SKILL.md" to "asset version".toByteArray()
            )
        )
        val targetRoot = File(tempFolder.root, "agent-skills")
        val target = File(targetRoot, "agent-memory/SKILL.md")
        target.parentFile!!.mkdirs()
        target.writeText("user edited version")

        val firstSeed = AgentSkillSeeder.seed(source, targetRoot)
        val secondSeed = AgentSkillSeeder.seed(source, targetRoot)

        assertEquals(0, firstSeed)
        assertEquals(0, secondSeed)
        assertEquals("user edited version", target.readText())
    }

    @Test
    fun seedsOnlyMissingFilesWhenTreeIsPartiallyPresent() {
        val source = FakeAssetSource(
            mapOf(
                "agent-skills/agent-memory/SKILL.md" to "skill".toByteArray(),
                "agent-skills/agent-memory/rules.md" to "rules".toByteArray()
            )
        )
        val targetRoot = File(tempFolder.root, "agent-skills")
        File(targetRoot, "agent-memory").mkdirs()
        File(targetRoot, "agent-memory/SKILL.md").writeText("already here")

        val seeded = AgentSkillSeeder.seed(source, targetRoot)

        assertEquals(1, seeded)
        assertEquals("already here", File(targetRoot, "agent-memory/SKILL.md").readText())
        assertEquals("rules", File(targetRoot, "agent-memory/rules.md").readText())
    }

    @Test
    fun missingAssetRootSeedsNothing() {
        val source = FakeAssetSource(emptyMap())
        val targetRoot = File(tempFolder.root, "agent-skills")

        val seeded = AgentSkillSeeder.seed(source, targetRoot)

        assertEquals(0, seeded)
        assertFalse(File(targetRoot, "SKILL.md").exists())
        assertTrue(targetRoot.isDirectory || !targetRoot.exists())
    }

    private class FakeAssetSource(private val files: Map<String, ByteArray>) : SkillAssetSource {
        override fun list(path: String): List<String> {
            val prefix = "$path/"
            val children = mutableSetOf<String>()
            files.keys.forEach { key ->
                if (key.startsWith(prefix)) {
                    children += key.removePrefix(prefix).substringBefore('/')
                }
            }
            return children.toList()
        }

        override fun open(path: String): InputStream {
            val content = files[path] ?: throw java.io.FileNotFoundException(path)
            return ByteArrayInputStream(content)
        }
    }
}
