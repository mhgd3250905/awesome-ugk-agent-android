package com.ugk.pi.terminal.runtime

/**
 * Version facts of the packaged CPython profile - the single source the skill
 * tool texts, the model-facing AGENTS.md and the probe assertions read from.
 *
 * Bumping these constants carries every *string* derived from them. It does not
 * carry the payload: the standard library ships in a directory named after the
 * distribution version, and `libpython<version>.so` plus the
 * `cpython-<digits>` extension modules are file names under `jniLibs`.
 * `TerminalPythonProfilePayloadTest` is what turns a bump that stops at the
 * constants red on the host; `runtime-lock.json` and the packaging scripts record
 * the same version independently and are reconciled against the files on disk by
 * `scripts/terminal-runtime/verify-runtime.ps1 -CheckPackages` (a manual release
 * gate that never reads these constants). A model-facing text edit that invents a
 * version is caught by `TerminalAgentInstructionsAssetTest`.
 */
object TerminalPythonProfile {
    const val PYTHON_VERSION = "3.14"
    const val PYTHON_DISTRIBUTION_VERSION = "3.14.6"
    const val PYTHON_LIBRARY_FILE_NAME = "libpython3.14.so"
}
