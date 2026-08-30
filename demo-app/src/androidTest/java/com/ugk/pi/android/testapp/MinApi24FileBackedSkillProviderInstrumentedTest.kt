package com.ugk.pi.android.testapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.FileBackedSkillProvider
import com.ugk.pi.android.SkillRepository
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinApi24FileBackedSkillProviderInstrumentedTest {
    @Test
    fun alwaysSkillCanEmbedNamedRootOnMinimumSupportedApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val skillRoot = File(context.filesDir, "review-min-api-file-backed-skills")
        val memoryRoot = File(context.filesDir, "review-min-api-file-backed-memory")
        skillRoot.deleteRecursively()
        memoryRoot.deleteRecursively()

        try {
            val skillDirectory = File(skillRoot, "agent-memory").apply { mkdirs() }
            File(skillDirectory, "SKILL.md").writeText(
                "---\n" +
                    "name: agent-memory\n" +
                    "description: Memory.\n" +
                    "x-ugk-load: always\n" +
                    "x-ugk-embed-files: memory:preferences.md\n" +
                    "---\n" +
                    "Memory body."
            )
            File(memoryRoot, "preferences.md").apply {
                parentFile?.mkdirs()
                writeText("- live preference")
            }

            val skill = FileBackedSkillProvider(
                repository = SkillRepository(skillRoot),
                embedRoots = mapOf("memory" to memoryRoot)
            ).skills().single { it.id == "agent-memory" }

            assertTrue(skill.instructions.contains("- live preference"))
        } finally {
            skillRoot.deleteRecursively()
            memoryRoot.deleteRecursively()
        }
    }
}
