package com.ugk.pi.android.testapp

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.android.ScannedSkillStatus
import com.ugk.pi.android.SkillRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device scan boundary regression for the review finding that load() and
 * scanDirectory() accepted skill directories that escape the repository root
 * through links while save/delete/skill_read already rejected them.
 */
@RunWith(AndroidJUnit4::class)
class MinApi24SkillScanSymlinkInstrumentedTest {
    @Test
    fun scanRejectsSkillDirectoryThatIsItselfASymlink() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-scan-symlink-dir")
        val outsideRoot = File(context.filesDir, "review-scan-symlink-outside")
        root.deleteRecursively()
        outsideRoot.deleteRecursively()

        val link = File(root, "linked-skill")
        try {
            outsideRoot.mkdirs()
            File(outsideRoot, "SKILL.md").writeText(
                "---\n" +
                    "name: linked-skill\n" +
                    "description: Escapes through a directory link.\n" +
                    "---\n" +
                    "Injected body."
            )
            root.mkdirs()
            createSymlinkOrSkip(outsideRoot, link)

            val scanned = SkillRepository(root).load()
                .first { it.directoryName == "linked-skill" }

            assertEquals(ScannedSkillStatus.INVALID, scanned.status)
            assertTrue(scanned.error.orEmpty().contains("link"))
        } finally {
            link.delete()
            outsideRoot.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun scanRejectsSkillMdThatSymlinksOutsideItsDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-scan-symlink-file")
        val outside = File(context.filesDir, "review-scan-symlink-file-outside.md")
        root.deleteRecursively()
        outside.delete()

        val link = File(root, "file-skill/SKILL.md")
        try {
            File(root, "file-skill").mkdirs()
            outside.writeText(
                "---\n" +
                    "name: file-skill\n" +
                    "description: Escapes through a file link.\n" +
                    "---\n" +
                    "Injected body."
            )
            createSymlinkOrSkip(outside, link)

            val scanned = SkillRepository(root).load()
                .first { it.directoryName == "file-skill" }

            assertEquals(ScannedSkillStatus.INVALID, scanned.status)
            assertTrue(scanned.error.orEmpty().contains("outside"))
        } finally {
            link.delete()
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun scanStillAcceptsPlainDirectChildSkills() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "review-scan-plain")
        root.deleteRecursively()

        try {
            val directory = File(root, "plain-skill")
            directory.mkdirs()
            File(directory, "SKILL.md").writeText(
                "---\n" +
                    "name: plain-skill\n" +
                    "description: A plain repository child.\n" +
                    "---\n" +
                    "Body."
            )

            val scanned = SkillRepository(root).load().single()

            assertEquals(ScannedSkillStatus.VALID, scanned.status)
        } finally {
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
}
