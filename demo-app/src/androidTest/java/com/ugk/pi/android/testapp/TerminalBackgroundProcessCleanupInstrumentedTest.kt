package com.ugk.pi.android.testapp

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugk.pi.terminal.runtime.BashCommandRequest
import com.ugk.pi.terminal.runtime.BashRuntime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SDK runtime contract ties one terminal_bash_execute call to one process
 * group: when the script returns, background children left in that group are
 * terminated instead of being reparented to init. These tests drive the real
 * packaged Bash through BashRuntime and inspect /proc (readable for the same
 * UID) to observe the group's survivors directly.
 */
@RunWith(AndroidJUnit4::class)
class TerminalBackgroundProcessCleanupInstrumentedTest {
    @Test
    fun naturalExitTerminatesBackgroundChildrenOfTheCall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BashRuntime(context)
        val marker = File(runtime.defaultWorkspace(), "cleanup-session-pgid.txt").apply {
            delete()
        }

        // "$$" is the non-interactive Bash's own PID. session_launcher calls
        // setsid before exec, so that PID is also the call's process-group id
        // and session id. The background Python outlives the script only when
        // cleanup is broken; with the sweep it must die with the group.
        val result = runtime.execute(
            BashCommandRequest(
                script = """
                    printf '%d' "${'$'}${'$'}" > cleanup-session-pgid.txt
                    "${'$'}UGK_NATIVE_LIBRARY_DIR/libugk_python.so" -c "import time; time.sleep(300)" &
                    echo done
                """.trimIndent(),
                timeoutMillis = 20_000
            )
        )

        assertEquals("cleanup script failed: ${result.stderr}", 0, result.exitCode)
        assertTrue("script did not run to completion", result.stdout.contains("done"))
        assertTrue("workspace marker was not written", marker.isFile)
        val processGroupId = marker.readText().trim().toInt()
        assertTrue("marker did not contain a process-group id", processGroupId > 0)

        // The sweep already ran inside execute(); the retry only absorbs a
        // briefly unreaped zombie transition.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var survivors = survivorsOfProcessGroup(processGroupId)
        while (survivors.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            survivors = survivorsOfProcessGroup(processGroupId)
        }
        assertTrue(
            "background process(es) survived the call in process group $processGroupId: $survivors",
            survivors.isEmpty()
        )
        marker.delete()
    }

    /**
     * Returns (pid, state) pairs of live processes whose process-group id
     * matches. /proc/<pid>/stat field 5 (pgrp) is the third field after the
     * comm field, which may itself contain spaces or parentheses, so parsing
     * resumes after the last ')'.
     */
    private fun survivorsOfProcessGroup(processGroupId: Int): List<Pair<Int, String>> {
        val processDirectories = File("/proc")
            .listFiles { file -> file.name.toIntOrNull() != null }
            .orEmpty()
        return processDirectories.mapNotNull { directory ->
            val pid = directory.name.toInt()
            runCatching {
                val stat = File(directory, "stat").readText()
                val fields = stat.substringAfterLast(")").trim().split(Regex("\\s+"))
                // fields[0] = state, fields[1] = ppid, fields[2] = pgrp
                if (fields[2].toInt() == processGroupId) pid to fields[0] else null
            }.getOrNull()
        }
    }
}
