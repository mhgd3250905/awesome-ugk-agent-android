package com.ugk.pi.terminal.runtime

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the rule that every process this Runtime spawns has its stdin shut
 * before the parent starts waiting on it.
 *
 * The child's stdin is a pipe, and a pipe the parent still holds open never
 * reports end-of-file, so a command that reads standard input would sit there
 * until the whole call timeout expires and come back with no output.
 *
 * The substitution here is the spawn itself, and only because
 * [NativeExecutableProcess.execute] reads `SystemClock` and execs an ELF from
 * nativeLibraryDir - neither exists on the host JVM. The fake models the one
 * property under test: a child that cannot be reaped while its stdin pipe
 * stays open. [untouchedChildIsNotReapable] is the control that keeps that
 * model from being a tautology - without the helper the same child never
 * becomes reapable.
 */
class NativeExecutableProcessStdinTest {
    @Test
    fun spawningClosesTheChildStdinBeforeTheParentWaits() {
        val child = StdinGatedProcess()

        val returned = NativeExecutableProcess.spawnWithStdinClosed(processBuilder()) { child }

        assertSame(child, returned)
        assertTrue("spawn helper returned without shutting the child's stdin", child.stdinClosed)
        // Only reachable because the helper shut the pipe: the same child still
        // reports "not exited" until its stdin sees end-of-file.
        assertEquals(0, child.exitValue())
        assertEquals(listOf(SINGLETON_EVENT), child.events)
    }

    @Test
    fun aChildLeftUntouchedIsStillNotReapable() {
        val child = StdinGatedProcess()

        val failure = assertThrows(IllegalThreadStateException::class.java) { child.exitValue() }

        assertTrue(failure.message!!.contains("stdin"))
        // Control for the test above: this is the state the Runtime would leave
        // every stdin-reading command in, and it is not something the fake can
        // reach by accident.
        assertFalse(child.stdinClosed)
    }

    /**
     * A guard that turns a successful spawn into a failure is worse than no
     * guard: the Bash path would crash the tool and the HTTP path would report
     * START_FAILED over a pipe Android may already have torn down.
     */
    @Test
    fun aStdinCloseFailureDoesNotFailTheSpawn() {
        val child = StdinGatedProcess(failOnClose = true)

        val returned = NativeExecutableProcess.spawnWithStdinClosed(processBuilder()) { child }

        assertSame(child, returned)
        assertTrue(child.events.contains("stdin-close-failed"))
    }

    private fun processBuilder(): ProcessBuilder =
        ProcessBuilder(listOf("definitely-not-invoked-by-the-substituted-starter"))

    /** A child that only exits once its stdin pipe reports end-of-file. */
    private class StdinGatedProcess(private val failOnClose: Boolean = false) : Process() {
        val events = mutableListOf<String>()
        var stdinClosed = false
            private set

        private val stdinStream = object : OutputStream() {
            override fun write(byte: Int) = throw IOException("stdin is not writable after spawn")

            override fun close() {
                if (failOnClose) {
                    record("stdin-close-failed")
                    throw IOException("pipe already torn down")
                }
                stdinClosed = true
                record(SINGLETON_EVENT)
            }
        }

        fun record(event: String) {
            events.add(event)
        }

        override fun getOutputStream(): OutputStream = stdinStream
        override fun getInputStream(): InputStream = ByteArray(0).inputStream()
        override fun getErrorStream(): InputStream = ByteArray(0).inputStream()

        override fun exitValue(): Int {
            if (!stdinClosed) throw IllegalThreadStateException("child is still blocked reading stdin")
            return 0
        }

        override fun waitFor(): Int = exitValue()
        override fun destroy() {}
    }

    companion object {
        private const val SINGLETON_EVENT = "stdin-closed"
    }
}
