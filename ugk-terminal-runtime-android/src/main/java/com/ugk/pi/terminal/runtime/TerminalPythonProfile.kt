package com.ugk.pi.terminal.runtime

/**
 * Version facts of the packaged CPython profile - the single source the
 * skill tool texts, the model-facing AGENTS.md consistency test, and the
 * probe assertions all read from. Bump the constants here and every
 * derived string follows; the JVM test in the skill module turns a
 * forgotten AGENTS.md edit red.
 */
object TerminalPythonProfile {
    const val PYTHON_VERSION = "3.14"
    const val PYTHON_DISTRIBUTION_VERSION = "3.14.6"
    const val PYTHON_LIBRARY_FILE_NAME = "libpython3.14.so"
}
