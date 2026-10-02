package com.ugk.pi.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for what a lifecycle call may conclude about one managed record.
 *
 * Both decisions need no Android Context and no live process, while every
 * caller of them needs both - that is why they are functions of four
 * observations instead of inline branches. Two of the observations arrive
 * lazily because they are a JNI `kill(-pgid, 0)` and a TCP connect: an
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
    fun queryForgetsARecordWhoseProcessGroupIsGone() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = false,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.FORGET_CONFIRMED_DEAD, disposition)
        assertEquals("a deaf port tells us nothing once the group is gone", 0, probes.portListening)
    }

    @Test
    fun queryNeverProbesTheGroupWhileItsOwnProcessIsStillAlive() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            processHandleAlive = true,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.REPORT, disposition)
        assertEquals("a live process already answers liveness", 0, probes.groupExists)
        assertEquals("a live process must not be demoted by one deaf probe", 0, probes.portListening)
    }

    @Test
    fun queryReportsARehydratedRecordThatHasNotAgedWithoutProbingThePort() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
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
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(QueryDisposition.REPORT_UNATTRIBUTABLE, disposition)
        assertEquals(1, probes.portListening)
    }

    @Test
    fun queryTreatsAReapedSessionLeaderTheSameAsARehydratedRecord() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.queryDisposition(
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(
            "a reaped handle says nothing more about the group than no handle does",
            QueryDisposition.REPORT_UNATTRIBUTABLE,
            disposition
        )
    }

    @Test
    fun stopSignalsAGroupThisInstanceIsStillRunningIn() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            processHandleAlive = true,
            processGroupExists = probes.exists(answer = false),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.SIGNAL_PROCESS_GROUP, disposition)
        assertEquals("attribution does not need a second opinion", 0, probes.groupExists)
    }

    /**
     * The direct child was our session leader; once it has been reaped the
     * group id can be recycled, so a deaf aged record must be dropped rather
     * than signalled - killing whatever group now holds that id would hit an
     * unrelated same-UID process. What the call may no longer do is report that
     * as `stopped`.
     */
    @Test
    fun stopNeverSignalsAGroupWhoseOwnLeaderHasBeenReaped() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.DROP_UNATTRIBUTABLE, disposition)
    }

    @Test
    fun stopSignalsARehydratedRecordThatIsStillListening() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = true,
            portListening = probes.listening(answer = true)
        )

        assertEquals(StopDisposition.SIGNAL_PROCESS_GROUP, disposition)
        assertEquals("a listening group must be signalled, no probe needed", 0, probes.groupExists)
    }

    @Test
    fun stopReportsConfirmedDeadWhenTheGroupIsProvablyGone() {
        val probes = ProbeCounter()

        val disposition = LocalHttpServerManager.stopDisposition(
            processHandleAlive = false,
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
            processHandleAlive = false,
            processGroupExists = probes.exists(answer = true),
            pastStaleGrace = false,
            portListening = probes.listening(answer = false)
        )

        assertEquals(StopDisposition.SIGNAL_PROCESS_GROUP, disposition)
    }

    /**
     * D-031: a native probe that cannot run says nothing about the group.
     * Reading its universal `false` as "gone" let status() FORGET a live
     * record - deleting the only copy of the issued token - and let stop()
     * claim `stopped` for a server still running.
     */
    @Test
    fun groupExistenceIsTreatedAsTrueWhenTheProbeCannotRun() {
        assertEquals(
            "an unobservable group is not a dead group",
            true,
            LocalHttpServerManager.groupExistsForDisposition(probeUsable = false, probeAnswer = false)
        )
        assertEquals(
            true,
            LocalHttpServerManager.groupExistsForDisposition(probeUsable = false, probeAnswer = true)
        )
    }

    @Test
    fun groupExistenceFollowsTheProbeWhenItRuns() {
        assertEquals(false, LocalHttpServerManager.groupExistsForDisposition(probeUsable = true, probeAnswer = false))
        assertEquals(true, LocalHttpServerManager.groupExistsForDisposition(probeUsable = true, probeAnswer = true))
    }
}
