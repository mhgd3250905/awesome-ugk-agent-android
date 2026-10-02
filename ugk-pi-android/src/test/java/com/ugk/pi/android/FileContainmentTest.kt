package com.ugk.pi.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [isInsideRoot] itself. The predicate's only previous coverage came from
 * symbolic-link tests, and those `assumeNoException`/`assumeTrue` away on a
 * Windows host: with the whole predicate replaced by `return true`, every JVM
 * test stayed green. These cases need no link and no platform feature, so a
 * weakened containment rule turns them red on any host that runs the gate.
 */
class FileContainmentTest {
    @Test
    fun containmentTableHoldsForEveryShape() {
        assertEquals(emptyList<String>(), runTable())
    }

    /**
     * One row per shape, all reported together: a `for` loop that stopped at
     * the first mismatch would hide the sibling-prefix row behind the
     * traversal row.
     */
    private fun runTable(): List<String> {
        val root = newDirectory("ugk-root")
        val siblingWithSharedPrefix = newDirectory("${root.name}-evil")
        val foreign = newDirectory("ugk-foreign")
        try {
            File(root, "notes.md").writeText("inside")
            val deep = File(root, "sub/deep.md").also {
                it.parentFile.mkdirs()
                it.writeText("inside")
            }
            File(siblingWithSharedPrefix, "stolen.md").writeText("outside")

            val cases = listOf(
                ContainmentCase("the root itself is inside it", root, root, true),
                ContainmentCase("a direct child file", File(root, "notes.md"), root, true),
                ContainmentCase("a nested child file", deep, root, true),
                ContainmentCase("a child directory", File(root, "sub"), root, true),
                ContainmentCase(
                    "a sibling that shares the root's name prefix",
                    File(siblingWithSharedPrefix, "stolen.md"),
                    root,
                    false
                ),
                ContainmentCase(
                    "a parent-traversal path that canonicalizes into that sibling",
                    File(root, "../${siblingWithSharedPrefix.name}/stolen.md"),
                    root,
                    false
                ),
                ContainmentCase("an unrelated directory", File(foreign, "other.md"), root, false)
            )

            return cases.mapNotNull { case ->
                val actual = case.candidate.isInsideRoot(case.root)
                if (actual == case.expected) {
                    null
                } else {
                    "${case.name}: expected ${case.expected}, got $actual " +
                        "(candidate=${case.candidate.canonicalFile.path}, root=${case.root.canonicalFile.path})"
                }
            }
        } finally {
            deleteAll(root, siblingWithSharedPrefix, foreign)
        }
    }

    private data class ContainmentCase(
        val name: String,
        val candidate: File,
        val root: File,
        val expected: Boolean
    )

    private fun newDirectory(name: String): File = Files.createTempDirectory(name).toFile()

    private fun deleteAll(vararg directories: File) {
        directories.forEach { it.deleteRecursively() }
    }
}
