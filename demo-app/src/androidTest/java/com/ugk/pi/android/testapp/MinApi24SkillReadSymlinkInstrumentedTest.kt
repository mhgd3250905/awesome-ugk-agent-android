package com.ugk.pi.android.testapp

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.SkillReadTool
import com.ugk.pi.android.SkillRepository
import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolExecutionContext
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinApi24SkillReadSymlinkInstrumentedTest {
    @Test
    fun skillReadMarksBareSymlinkEmbedMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-min-api-skill-read-bare")
        val outside = File(context.filesDir, "review-min-api-skill-read-bare-outside.md")
        val link = File(root, "bare-skill/outside.md")
        root.deleteRecursively()
        outside.delete()

        try {
            File(root, "bare-skill").mkdirs()
            File(root, "bare-skill/SKILL.md").writeText(
                "---\n" +
                    "name: bare-skill\n" +
                    "description: Bare skill.\n" +
                    "x-ugk-embed-files: outside.md\n" +
                    "---\n" +
                    "Body."
            )
            outside.writeText("outside content")
            createSymlinkOrSkip(outside, link)

            val result = SkillReadTool(SkillRepository(root)).execute(
                call("skill_read", "name" to "bare-skill"),
                ToolExecutionContext(sessionId = "min-api-skill-read-bare")
            )

            assertFalse("skill_read failed: $result", result.isError)
            assertTrue(result.content.contains("- outside.md (missing)"))
        } finally {
            link.delete()
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun skillReadMarksNamedRootSymlinkEmbedMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-min-api-skill-read-named")
        val memoryRoot = File(context.filesDir, "review-min-api-skill-read-memory")
        val outside = File(context.filesDir, "review-min-api-skill-read-named-outside.md")
        val link = File(memoryRoot, "preferences.md")
        root.deleteRecursively()
        memoryRoot.deleteRecursively()
        outside.delete()

        try {
            File(root, "named-skill").mkdirs()
            File(root, "named-skill/SKILL.md").writeText(
                "---\n" +
                    "name: named-skill\n" +
                    "description: Named skill.\n" +
                    "x-ugk-embed-files: memory:preferences.md\n" +
                    "---\n" +
                    "Body."
            )
            memoryRoot.mkdirs()
            outside.writeText("outside content")
            createSymlinkOrSkip(outside, link)

            val result = SkillReadTool(
                SkillRepository(root),
                embedRoots = mapOf("memory" to memoryRoot)
            ).execute(
                call("skill_read", "name" to "named-skill"),
                ToolExecutionContext(sessionId = "min-api-skill-read-named")
            )

            assertFalse("skill_read failed: $result", result.isError)
            assertTrue(result.content.contains("- memory:preferences.md (missing)"))
        } finally {
            link.delete()
            outside.delete()
            memoryRoot.deleteRecursively()
            root.deleteRecursively()
        }
    }

    private fun createSymlinkOrSkip(target: File, link: File) {
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (error: Exception) {
            assumeNoException("Symbolic links are not available in this test environment.", error)
        }
    }

    private fun call(name: String, vararg pairs: Pair<String, String>): ToolCall {
        return ToolCall(
            id = "call-$name",
            name = name,
            input = buildJsonObject {
                pairs.forEach { (key, value) -> put(key, value) }
            }
        )
    }
}
