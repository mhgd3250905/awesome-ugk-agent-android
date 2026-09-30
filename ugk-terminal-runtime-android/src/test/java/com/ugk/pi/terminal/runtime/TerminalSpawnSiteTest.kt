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
            .filter { (_, line) -> line.contains(SPAWN_CALL) || line.contains(EXEC_CALL) }
            .filterNot { (file, line) -> file == HELPER_FILE && line == HELPER_DEFAULT }
            .map { (file, line) -> "$file: $line" }

        assertEquals(
            "a new native spawn must go through NativeExecutableProcess.spawnWithStdinClosed so the " +
                "child's stdin is shut, and must not use Runtime.exec, which bypasses it entirely. " +
                "Offenders: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    companion object {
        private const val SOURCE_DIRECTORY = "src/main"
        private const val MAIN_SOURCE_FLOOR = 200
        private const val SPAWN_CALL = ".start()"
        private const val EXEC_CALL = "Runtime.getRuntime().exec"
        private const val HELPER_FILE = "NativeExecutableProcess.kt"
        private const val HELPER_DEFAULT = "starter: (ProcessBuilder) -> Process = { it.start() }"
    }
}
