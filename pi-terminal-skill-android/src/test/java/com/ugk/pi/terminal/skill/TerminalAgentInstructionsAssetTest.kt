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
 * Every version mention is checked, not just the presence of one, and not only
 * under the `CPython` label: the first version of this test asserted
 * `text.contains("CPython <version>")`, and a second version of it folded only
 * `CPython x.y.z`, so an added line reading "Python 3.13 is the interpreter this
 * runtime ships" - which is what an asset edit that invents a version looks like
 * to the model - stayed green. A bare `Python x.y` mention is legitimate when it
 * names the interpreter series, so the rule is set membership over the two
 * derived facts rather than string equality.
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

        val claims = PYTHON_VERSION_CLAIM.findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(
            "AGENTS.md must state the packaged CPython version at least once; the model needs it " +
                "to decide what `python` is, and this assertion is also the pin against deleting " +
                "the only line that carries it",
            claims.contains(TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION)
        )
        assertEquals(
            "every Python version AGENTS.md names must be one of the packaged facts " +
                "(distribution ${TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION} or interpreter " +
                "series ${TerminalPythonProfile.PYTHON_VERSION}); anything else is a model-facing " +
                "lie about the interpreter",
            setOf(
                TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION,
                TerminalPythonProfile.PYTHON_VERSION
            ).intersect(claims),
            claims
        )
    }

    private companion object {
        val PYTHON_VERSION_CLAIM = Regex("""(?:CPython|Python|python)\s?(\d+\.\d+(?:\.\d+)?)""")
    }
}
