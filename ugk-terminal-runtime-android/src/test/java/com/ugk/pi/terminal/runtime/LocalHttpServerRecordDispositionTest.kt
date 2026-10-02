package com.ugk.pi.terminal.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * D-031: close() releases only what the closing instance itself started.
     * A side runtime (the demo app's teaching runtime) shares the process
     * with the main conversation runtime; before this rule its close()
     * rehydrated the shared disk records and killed the foreground server.
     */
    @Test
    fun closeReleasesOnlyRecordsThisInstanceStarted() {
        assertTrue(
            LocalHttpServerManager.closeReleasesRecord(recordOwnerId = 7L, closingOwnerId = 7L)
        )
    }

    @Test
    fun closeSparesRecordsRehydratedFromDisk() {
        assertFalse(
            "a rehydrated record has no live owner and must wait for an explicit stop/stopAll",
            LocalHttpServerManager.closeReleasesRecord(recordOwnerId = null, closingOwnerId = 7L)
        )
    }

    @Test
    fun closeSparesRecordsStartedByAnotherLiveInstance() {
        assertFalse(
            LocalHttpServerManager.closeReleasesRecord(recordOwnerId = 8L, closingOwnerId = 7L)
        )
    }

    /**
     * The D-031 rule as the lifecycle calls actually compose it.
     *
     * Before this row set, only `groupExistsForDisposition` was pinned: replacing
     * the `probeUsable` argument at `hasProcess()`/`groupProbe()` with a constant,
     * or reading a live handle plus an unusable probe as "gone", left all 55 tests
     * green (measured: mut-m1a/m1b logs). Every combination is reported together,
     * because a loop that stopped at the first mismatch would hide the D-031 rows
     * behind the handle-alive rows.
     */
    @Test
    fun processEvidenceCoversEveryObservationCombination() {
        val expected = mapOf(
            // A handle this instance still holds is direct evidence either way.
            Triple(true, true, true) to true,
            Triple(true, true, false) to true,
            Triple(true, false, true) to true,
            Triple(true, false, false) to true,
            // No handle: a usable probe decides.
            Triple(false, true, true) to true,
            Triple(false, true, false) to false,
            // No handle and no probe: "unknown" must not be read as "gone" (D-031).
            Triple(false, false, true) to true,
            Triple(false, false, false) to true
        )
        assertEquals(
            "handleAlive / probeUsable / probeAnswer rows that disagree",
            emptyList<String>(),
            expected.mapNotNull { (observations, want) ->
                val (handleAlive, probeUsable, probeAnswer) = observations
                val got = LocalHttpServerManager.processEvidencePresent(
                    handleAlive = handleAlive,
                    probeUsable = probeUsable,
                    probeAnswer = { probeAnswer }
                )
                if (got == want) {
                    null
                } else {
                    "handleAlive=$handleAlive probeUsable=$probeUsable probeAnswer=$probeAnswer " +
                        "expected=$want got=$got"
                }
            }
        )
    }

    /**
     * The group probe is a JNI `kill(-pgid, 0)`. `hasProcess()` short-circuited
     * around it on trunk, and an extraction that takes the observation eagerly
     * would pay one probe per live record per status() call - so the laziness is
     * part of the rule, not an implementation detail.
     */
    @Test
    fun processEvidenceOnlyAsksTheProbeWhenTheProbeIsTheAnswer() {
        var probes = 0
        val probeAnswer: () -> Boolean = {
            probes++
            true
        }

        LocalHttpServerManager.processEvidencePresent(
            handleAlive = true,
            probeUsable = true,
            probeAnswer = probeAnswer
        )
        LocalHttpServerManager.processEvidencePresent(
            handleAlive = false,
            probeUsable = false,
            probeAnswer = probeAnswer
        )
        assertEquals("a live handle or an unusable probe must not reach the JNI call", 0, probes)

        LocalHttpServerManager.processEvidencePresent(
            handleAlive = false,
            probeUsable = true,
            probeAnswer = probeAnswer
        )
        assertEquals("with no handle and a usable probe the answer comes from the probe", 1, probes)
    }

    /**
     * What one `stopRecord()` attempt may claim, over the whole observation
     * domain. The discriminating rows are the last two: with the probe unusable
     * and no handle the stop must fail loudly instead of reporting `stopped`,
     * and with the probe usable a group that answers "exists" must be signalled
     * rather than trusted.
     */
    @Test
    fun stopPlanCoversEveryObservationCombination() {
        val expected = mapOf(
            Triple(true, false, true) to GroupStopPlan.ALREADY_GONE,
            Triple(true, false, false) to GroupStopPlan.ALREADY_GONE,
            Triple(true, true, true) to GroupStopPlan.SIGNAL_AND_VERIFY,
            Triple(true, true, false) to GroupStopPlan.SIGNAL_AND_VERIFY,
            Triple(false, true, true) to GroupStopPlan.ALREADY_GONE,
            Triple(false, false, true) to GroupStopPlan.ALREADY_GONE,
            Triple(false, true, false) to GroupStopPlan.UNVERIFIABLE,
            Triple(false, false, false) to GroupStopPlan.UNVERIFIABLE
        )
        assertEquals(
            "probeUsable / groupExists / holdsProcessHandle rows that disagree",
            emptyList<String>(),
            expected.mapNotNull { (observations, want) ->
                val (probeUsable, groupExists, holdsProcessHandle) = observations
                val got = LocalHttpServerManager.groupStopPlan(
                    probeUsable = probeUsable,
                    groupExists = { groupExists },
                    holdsProcessHandle = holdsProcessHandle
                )
                if (got == want) {
                    null
                } else {
                    "probeUsable=$probeUsable groupExists=$groupExists " +
                        "holdsProcessHandle=$holdsProcessHandle expected=$want got=$got"
                }
            }
        )
    }

    /**
     * With no probe there is nothing to signal and nothing to observe, so asking
     * whether the group exists is pure cost - and `stopRecord()` used to do it.
     */
    @Test
    fun stopPlanDoesNotAskTheProbeWhenNothingCanSignalTheGroup() {
        var probes = 0
        val groupExists: () -> Boolean = {
            probes++
            true
        }

        LocalHttpServerManager.groupStopPlan(
            probeUsable = false,
            groupExists = groupExists,
            holdsProcessHandle = true
        )
        LocalHttpServerManager.groupStopPlan(
            probeUsable = false,
            groupExists = groupExists,
            holdsProcessHandle = false
        )
        assertEquals("an unusable probe must not be consulted", 0, probes)

        assertEquals(
            GroupStopPlan.SIGNAL_AND_VERIFY,
            LocalHttpServerManager.groupStopPlan(
                probeUsable = true,
                groupExists = groupExists,
                holdsProcessHandle = false
            )
        )
        assertEquals("a usable probe is consulted exactly once", 1, probes)
    }
}
