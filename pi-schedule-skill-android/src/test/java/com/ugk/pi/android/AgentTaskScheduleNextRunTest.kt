package com.ugk.pi.android

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `nextRunAtMillis` also consumes records restored from persisted JSON, not
 * only records produced by the parse-validated tool input. Arithmetic on a
 * hostile or corrupted interval used to overflow into a negative value,
 * which AlarmManager treats as an immediately-due alarm — an instant
 * refiring loop for a repeating task. Overflow must degrade to "no next
 * run" instead.
 */
class AgentTaskScheduleNextRunTest {

    @Test
    fun `hostile persisted schedule never yields a negative next run`() {
        // startAt near Long.MAX_VALUE: the next-occurrence addition overflows
        // into a negative value, which takeIf { it <= endAtMillis } happily
        // accepts.
        val overflowingStart = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = Long.MAX_VALUE - 100L,
            intervalMillis = 1_000L,
            endAtMillis = Long.MAX_VALUE
        )
        // startAt deep in the past with a giant interval: the next occurrence
        // is arithmetically negative before endAtMillis is even consulted.
        val negativeOccurrence = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = -9_000_000_000_000_000_000L,
            intervalMillis = 5_500_000_000_000_000_000L,
            endAtMillis = Long.MAX_VALUE
        )

        listOf(
            overflowingStart to (Long.MAX_VALUE - 99L),
            negativeOccurrence to (-9_000_000_000_000_000_000L + 1_000L)
        ).forEach { (schedule, now) ->
            val next = schedule.nextRunAtMillis(nowMillis = now)
            assertTrue(
                "next run must be null or non-negative for $schedule, was $next",
                next == null || next >= 0L
            )
        }
    }

    @Test
    fun `regular repeating schedule still computes the next occurrence`() {
        val schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = 1_600_000_000_000L,
            intervalMillis = 60_000L,
            endAtMillis = 1_600_000_180_000L
        )

        val next = schedule.nextRunAtMillis(nowMillis = 1_600_000_061_000L)

        assertNotNull(next)
        assertTrue(next!! > 1_600_000_061_000L)
        assertTrue(next <= 1_600_000_180_000L)
    }

    @Test
    fun `window in the past yields no next run`() {
        val schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = 1_000L,
            intervalMillis = 60_000L,
            endAtMillis = 2_000L
        )

        assertNull(schedule.nextRunAtMillis(nowMillis = 5_000L))
    }
}
