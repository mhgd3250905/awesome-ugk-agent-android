package com.ugk.pi.android

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `AgentSkillSeeder.seed` runs from both the foreground chat and background
 * scheduled tasks, so two seeds can race on the same files. Each writer
 * stages into its own unique temporary file and renames only while the
 * target is still absent, so whatever the interleaving, the target ends up
 * holding exactly the packaged asset bytes. No interleaving is injected
 * here: the assertions must hold for every schedule.
 */
class AgentSkillSeederConcurrencyTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `concurrent seeds leave the complete asset at the target`() {
        // Varied bytes so a truncated or interleaved copy cannot pass for
        // the original.
        val asset = ByteArray(64 * 1024) { index -> ((index * 31 + 7) % 251).toByte() }
        val source = FakeAssetSource(
            mapOf(
                "agent-skills/pack/SKILL.md" to asset,
                "agent-skills/pack/rules.md" to "rules".toByteArray()
            )
        )
        val targetRoot = File(tempFolder.root, "agent-skills")

        val start = CountDownLatch(1)
        val seeds = (1..4).map { index ->
            thread(name = "seed-$index") {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                AgentSkillSeeder.seed(source, targetRoot)
            }
        }
        start.countDown()
        seeds.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        val skill = File(targetRoot, "pack/SKILL.md")
        assertTrue("target must exist after concurrent seeds", skill.isFile)
        assertTrue(
            "target must hold exactly the packaged asset",
            skill.readBytes().contentEquals(asset)
        )
        assertEquals("rules", File(targetRoot, "pack/rules.md").readText())
        assertTrue(
            "no temporary residue may survive concurrent seeds",
            targetRoot.walkTopDown().none { it.name.endsWith(".tmp") }
        )
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
