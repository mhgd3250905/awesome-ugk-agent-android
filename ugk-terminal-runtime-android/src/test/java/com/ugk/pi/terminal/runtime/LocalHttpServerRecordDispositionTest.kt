package com.ugk.pi.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for what a lifecycle call may conclude about one managed record.
 *
 * Both decisions need no Android Context and no live process, while every
 * caller of them needs both - that is why they are functions of three
 * observations instead of inline branches. The observations arrive lazily
 * because two of them are a JNI `kill(-pgid, 0)` and a TCP connect: an
 * unconditional version would pay a socket probe per record per status() call.
 */
class LocalHttpServerRecordDispositionTest {
    private class ProbeCounter {
        var groupExists = 0
        var portListening = 0

        fun exists(answer: Boolean): () -> Boolean = { groupExists++; answer }

        fun listening(answer: Boolean): () -> Boolean = { portListening++; answer }
    }

    @Test
    fun queryForgetRecordsAConfirmedDeadProcessAndFreesItsPort() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            hasInProcessHandle = false,
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = false,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.FORGET_CONFIRMED_DEAD, disposition)
        assertEquals("a deaf port tells us nothing once the group is gone", 0, probes.portListening)
    }

    @Test
    fun queryNeverSignalsAndNeverProbesTheGroupWhileItsOwnHandleIsAlive() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            hasInProcessHandle = true,
            processHandleAlive = true,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.REPORT, disposition)
        assertEquals("a live handle already answers liveness", 0, probes.groupExists)
        assertEquals("a live handle must not be demoted by one deaf probe", 0, probes.portListening)
    }

    @Test
    fun queryReportsARehydratedRecordThatHasNotAgedWithoutProbingThePort() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            hasInProcessHandle = false,
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = false,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.REPORT, disposition)
        assertEquals("grace has not elapsed, so deafness cannot be concluded", 0, probes.portListening)
    }

    @Test
    fun queryReportsARehydratedRecordThatIsStillListening() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            hasInProcessHandle = false,
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = true)
        )

        assertEquals(QueryDisposition.REPORT, disposition)
    }

    /**
     * The one row that used to be `FORGET_CONFIRMED_DEAD`. A query that forgets
     * such a record also deletes its persisted metadata, which is the only
     * place the issued token still exists - the caller is left with a server it
     * can neither reach nor stop, and start() then reports the port occupied.
     * Restoring the old answer here must turn this test red.
     */
    @Test
    fun queryReportsAnUnattributableRecordInsteadOfForgettingIt() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            hasInProcessHandle = false,
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.REPORT_UNATTRIBUTABLE, disposition)
        assertEquals(1, probes.portListening)
    }

    @Test
    fun queryForgetsADeadHandleWhoseProcessGroupIsGone() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            hasInProcessHandle = true,
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.FORGET_CONFIRMED_DEAD, disposition)
    }

    /**
     * Attribution survives a dead direct child as long as this process still
     * holds the handle: the process-group id came from our own session
     * launcher. The old ruler asked "is the handle alive", so stop() abandoned
     * a group it had created, without ever signalling it, and reported it as
     * stopped.
     */
    @Test
    fun stopSignalsAGroupThisProcessStartedEvenAfterItsDirectChildDied() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            hasInProcessHandle = true,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.SIGNAL_PROCESS_GROUP, disposition)
    }

    @Test
    fun stopSignalsARehydratedRecordThatIsStillListening() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            hasInProcessHandle = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = true)
        )

        assertEquals(StopDisposition.SIGNAL_PROCESS_GROUP, disposition)
        assertEquals("a listening group must be signalled, no probe needed", 0, probes.groupExists)
    }

    @Test
    fun stopDropsAnUnattributableRecordWithoutSignalling() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            hasInProcessHandle = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.DROP_UNATTRIBUTABLE, disposition)
    }

    @Test
    fun stopReportsConfirmedDeadWhenTheGroupIsProvablyGone() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            hasInProcessHandle = false,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.DROP_CONFIRMED_DEAD, disposition)
    }

    @Test
    fun stopSignalsARehydratedRecordThatHasNotAged() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            hasInProcessHandle = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = false,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.SIGNAL_PROCESS_GROUP, disposition)
    }
}
