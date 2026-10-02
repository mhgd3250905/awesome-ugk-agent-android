package com.ugk.pi.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every prose file in this repository is a fact source that a later review is
 * expected to search. A single control byte makes `git ls-files --eol` report
 * the file as `-text`, and ripgrep then answers "Binary file matches" instead
 * of the lines - so a round that records a defect in such a file leaves the
 * next round unable to find it. This happened twice: the round-10 record of a
 * NUL-in-URL finding wrote the real byte into `docs/terminal-runtime-validation.md`
 * (round 11 filed it as F5 and fixed one line), and round 11's own section 36
 * wrote a second one that the same fix did not reach.
 *
 * The gate walks the whole source tree below the repository root, skipping
 * `build/` and dot-directories, so it covers the packaged prose as well as the
 * handbooks: `pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md` and the
 * shipped `SKILL.md` files are the documents the model itself reads, and they
 * were outside an earlier version of this scan that looked only at the root,
 * `docs/` and the module top levels. The inventory assert exists because a
 * silently empty or partial scan would turn this into a check that never runs:
 * 46 Markdown files are tracked at the time of writing, so the floor is set to
 * 40 rather than to a count that any single directory's removal would satisfy.
 */
class DocumentationTextHygieneTest {
    @Test
    fun proseFilesContainNoControlBytes() {
        val repositoryRoot = File(".").canonicalFile.parentFile
        val documents = markdownFilesUnder(repositoryRoot)
        assertTrue(
            "Scanned ${documents.size} Markdown files under $repositoryRoot; the tree tracks 46. " +
                "A zero or partial inventory means this gate is not looking at anything.",
            documents.size >= MIN_DOCUMENTS
        )

        val offenders = documents.flatMap { document ->
            val bytes = document.readBytes()
            val lineOf = IntArray(bytes.size + 1)
            var line = 1
            for (index in bytes.indices) {
                lineOf[index] = line
                if (bytes[index].toInt() and 0xFF == NEWLINE) line++
            }
            bytes.indices.filter { index -> isControlByte(bytes[index]) }.map { index ->
                "${document.relativeTo(repositoryRoot).path}: line ${lineOf[index]} " +
                    "byte 0x%02X".format(bytes[index].toInt() and 0xFF)
            }
        }

        assertEquals(
            "Control bytes make a prose file unreadable to ripgrep and to `git grep` " +
                "as text. Write the escape as text instead (for example `U+0000`).",
            emptyList<String>(),
            offenders
        )
    }

    private fun markdownFilesUnder(repositoryRoot: File): List<File> {
        return repositoryRoot.walkTopDown()
            .onEnter { directory ->
                // `build/` holds generated reports that are not prose and not
                // tracked; dot-directories hold tool state.
                directory.name != "build" && !directory.name.startsWith(".")
            }
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .toList()
    }

    private fun isControlByte(byte: Byte): Boolean {
        val value = byte.toInt() and 0xFF
        return (value != TAB && value != NEWLINE && value != CARRIAGE_RETURN && value < 0x20) ||
            value == DELETE
    }

    private companion object {
        const val TAB = 0x09
        const val NEWLINE = 0x0A
        const val CARRIAGE_RETURN = 0x0D
        const val DELETE = 0x7F
        const val MIN_DOCUMENTS = 40
    }
}
