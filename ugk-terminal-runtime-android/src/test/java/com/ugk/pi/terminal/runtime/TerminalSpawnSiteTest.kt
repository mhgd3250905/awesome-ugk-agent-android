package com.ugk.pi.terminal.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that this module spawns a native process in exactly one place.
 *
 * [NativeExecutableProcess.spawnWithStdinClosed] exists so the stdin rule
 * cannot be missed at a new spawn site, but a helper only helps if nobody
 * routes around it, and the stdin test cannot see the call sites (they sit
 * behind an Android Context). This scans the shipped sources instead.
 *
 * Comment lines are dropped before matching, so a KDoc that merely names
 * `ProcessBuilder.start()` cannot make this red and a commented-out spawn
 * cannot make it green.
 */
class TerminalSpawnSiteTest {
    @Test
    fun everyNativeSpawnGoesThroughTheStdinHelper() {
        val codeLines = File(SOURCE_DIRECTORY).walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .map(String::trim)
                    .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
                    .map { line -> file.name to line }
            }
            .toList()
        assertTrue(
            "expected to read the shipped sources from $SOURCE_DIRECTORY, found ${codeLines.size} lines; " +
                "this test is only meaningful with the module directory as the working directory",
            codeLines.size >= MAIN_SOURCE_FLOOR
        )

        val offenders = codeLines
            .filter { (_, line) -> line.contains(EXEC_CALL) || spawnsANativeProcess(line) }
            .filterNot { (file, line) -> file == HELPER_FILE && line.contains(HELPER_DEFAULT_MARKER) }
            .map { (file, line) -> "$file: $line" }

        assertEquals(
            "a new native spawn must go through NativeExecutableProcess.spawnWithStdinClosed so the " +
                "child's stdin is shut, and must not use Runtime.exec, which bypasses it entirely. " +
                "Offenders: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * Deliberately narrow: only a call that can create a child process counts.
     * Matching bare `.start()` would flag every `Thread.start()` and
     * `Timer.start()` in the module, and a guard that cries wolf gets deleted -
     * which is worse than no guard.
     */
    private fun spawnsANativeProcess(line: String): Boolean {
        val callsStart = line.contains(START_CALL) || line.contains(START_REFERENCE)
        return callsStart && (line.contains(PROCESS_BUILDER) || line.contains(BUILDER_REFERENCE))
    }

    companion object {
        private const val SOURCE_DIRECTORY = "src/main"
        private const val MAIN_SOURCE_FLOOR = 200
        private const val START_CALL = ".start()"
        private const val START_REFERENCE = "::start"
        private const val EXEC_CALL = "Runtime.getRuntime().exec"
        private const val PROCESS_BUILDER = "ProcessBuilder"
        private const val BUILDER_REFERENCE = "builder"
        private const val HELPER_FILE = "NativeExecutableProcess.kt"

        // Matched by content rather than by the whole trimmed line, so an
        // unrelated reformat of the default argument cannot turn this red.
        private const val HELPER_DEFAULT_MARKER = "starter: (ProcessBuilder) -> Process"
    }
}
