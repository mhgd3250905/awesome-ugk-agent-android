package com.ugk.pi.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-7 review follow-up: the NUL screen added to terminal_bash_execute had a
 * twin hazard in local_http_server_start, whose `directory` is also
 * model-authored and also becomes an argv element of a child process (plus the
 * root that child serves). The manager's lifecycle needs a Context and a native
 * process, so the pure screen is pinned here.
 */
class LocalHttpServerDirectoryScreenTest {
    private val nul = 0.toChar().toString()
    private val backslash = 92.toChar().toString()

    @Test
    fun rejectsDirectoryContainingNul() {
        val reason = LocalHttpServerManager.directoryScreeningError("reports" + nul + "private")

        assertEquals("directory must not contain a NUL character", reason)
    }

    @Test
    fun stillRejectsBlankAndBackslashPaths() {
        // The refactor must not weaken the pre-existing screens. An absolute
        // POSIX path is not asserted here: java.io.File decides absoluteness
        // with host filesystem semantics, so "/etc" is only unambiguous on
        // device, where this actually runs.
        assertEquals(
            "directory must not be blank",
            LocalHttpServerManager.directoryScreeningError("   ")
        )
        val backslashed = LocalHttpServerManager.directoryScreeningError(".." + backslash + "escape")
        assertTrue(
            "a Windows-style path must still be refused, got: $backslashed",
            backslashed?.contains("relative path") == true
        )
    }

    @Test
    fun acceptsAnOrdinaryRelativeDirectory() {
        assertNull(LocalHttpServerManager.directoryScreeningError("reports/2026"))
        assertNull(LocalHttpServerManager.directoryScreeningError("."))
    }
}
