package com.ugk.pi.task.runtime

import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.android.AgentTaskSchedule
import com.ugk.pi.android.AgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskRuntimeTest {
    @Test
    fun jsonCodecRoundTripsPolymorphicTask() {
        val task = sampleTask()

        val decoded = AgentTaskJsonCodec.decode(AgentTaskJsonCodec.encode(listOf(task))).single()

        assertEquals(task, decoded)
        assertTrue(decoded.action is AgentTaskAction.NotifyUser)
        assertTrue(decoded.schedule is AgentTaskSchedule.OneShot)
    }

    @Test
    fun jsonCodecRejectsCorruptPayloadWithoutBreakingStartup() {
        assertEquals(emptyList<AgentTask>(), AgentTaskJsonCodec.decode("not-json"))
    }

    @Test
    fun successfulOneShotExecutionBecomesCompleted() {
        val updated = sampleTask().afterExecution(now = 1_600_000_001_000L, success = true)

        assertEquals(AgentTaskStatus.COMPLETED, updated.status)
        assertEquals(1_600_000_001_000L, updated.lastRunAtMillis)
        assertEquals(1_600_000_001_000L, updated.completedAtMillis)
        assertNull(updated.nextRunAtMillis)
    }

    @Test
    fun successfulRepeatingExecutionSchedulesTheNextOccurrence() {
        val task = sampleTask().copy(
            schedule = AgentTaskSchedule.RepeatingUntil(
                startAtMillis = 1_600_000_000_000L,
                intervalMillis = 60_000L,
                endAtMillis = 1_600_000_180_000L
            ),
            nextRunAtMillis = 1_600_000_000_000L
        )

        val updated = task.afterExecution(now = 1_600_000_000_000L, success = true)

        assertEquals(AgentTaskStatus.SCHEDULED, updated.status)
        assertEquals(1_600_000_060_000L, updated.nextRunAtMillis)
        assertEquals(1_600_000_000_000L, updated.lastRunAtMillis)
        assertNull(updated.completedAtMillis)
    }

    @Test
    fun failedExecutionBecomesTerminalWithoutASecondAlarm() {
        val updated = sampleTask().afterExecution(now = 1_600_000_001_000L, success = false)

        assertEquals(AgentTaskStatus.FAILED, updated.status)
        assertNull(updated.nextRunAtMillis)
        assertEquals(1_600_000_001_000L, updated.lastRunAtMillis)
    }

    @Test
    fun promptTasksUseTheBackgroundAgentJobRoute() {
        val task = sampleTask().copy(
            action = AgentTaskAction.RunAgentPrompt("检查当前界面")
        )

        assertEquals(AgentTaskTriggerRoute.AGENT_JOB, task.triggerRoute())
    }

    @Test
    fun notificationTasksUseTheAlarmRoute() {
        assertEquals(AgentTaskTriggerRoute.NOTIFICATION_ALARM, sampleTask().triggerRoute())
    }

    private fun sampleTask(): AgentTask = AgentTask(
        id = "task_1",
        sessionId = "session_1",
        title = "提醒",
        schedule = AgentTaskSchedule.OneShot(1_600_000_000_000L),
        action = AgentTaskAction.NotifyUser("该休息了"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_599_999_000_000L,
        updatedAtMillis = 1_599_999_000_000L,
        nextRunAtMillis = 1_600_000_000_000L
    )
}
