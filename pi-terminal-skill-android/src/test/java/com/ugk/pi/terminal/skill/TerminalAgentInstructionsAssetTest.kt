package com.ugk.pi.terminal.skill

import com.ugk.pi.terminal.runtime.TerminalPythonProfile
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged AGENTS.md is the model-facing authority for the runtime
 * environment, and the runtime constants are the code-facing one. This pins
 * that the two never drift apart: a version bump that forgets the asset, or
 * an asset edit that invents a version, turns this red on the JVM without
 * needing a device.
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

        assertTrue(
            "AGENTS.md must mention CPython ${TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION} " +
                "to match the packaged distribution",
            text.contains("CPython ${TerminalPythonProfile.PYTHON_DISTRIBUTION_VERSION}")
        )
    }
}
