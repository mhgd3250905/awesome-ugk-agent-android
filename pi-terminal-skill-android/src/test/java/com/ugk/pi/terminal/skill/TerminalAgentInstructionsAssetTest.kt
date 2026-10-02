package com.ugk.pi.terminal.skill

import com.ugk.pi.terminal.runtime.TerminalPythonProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged AGENTS.md is the model-facing authority for the runtime
 * environment, and the runtime constants are the code-facing one. This pins
 * that the two never drift apart: a version bump that forgets the asset, or an
 * asset edit that invents a version, turns this red on the JVM without needing
 * a device.
 *
 * Every mention is checked, not just the presence of one: the first version of
 * this test asserted `text.contains("CPython <version>")`, so adding a second
 * line claiming a different interpreter - which is exactly what an asset edit
 * that invents a version looks like - left it green.
 *
 * Reads the asset from the source tree because unit tests do not see
 * src/main/assets on their classpath; Gradle runs JVM tests with the module
 * directory as the working directory.
 */
class TerminalAgentInstructionsAssetTest {
    @Test
    fun agentsMdStatesThePackagedPythonVersion() {
        val agentsMd = File("src/main/assets/ugk/AGENTS.md")
        check(agentsMd.isFile) { "SDK runtime AGENTS.md not found at ${agentsMd.absolutePath}" }
        val text = agentsMd.readText()

        val claims = CPYTHON_VERSION_CLAIM.findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(
            "AGENTS.md must state the packaged CPython version at least once; the model needs it " +
                "to decide what `python` is, and this assertion is also the pin against deleting " +
                "the only line that carries it",
            claims.isNotEmpty()
        )
        assertEquals(
            "every CPython version AGENTS.md names must be the packaged distribution version " +
                "(a second, invented version is a model-facing lie about the interpreter)",
            listOf(TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION),
            claims.distinct().sorted()
        )
    }

    private companion object {
        val CPYTHON_VERSION_CLAIM = Regex("""CPython\s+(\d+\.\d+(?:\.\d+)?)""")
    }
}
