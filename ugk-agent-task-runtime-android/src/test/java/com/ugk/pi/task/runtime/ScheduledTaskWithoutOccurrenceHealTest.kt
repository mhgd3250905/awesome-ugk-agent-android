package com.ugk.pi.task.runtime

import android.content.Context
import android.content.ContextWrapper
import com.ugk.pi.schedule.skill.AgentTask
import com.ugk.pi.schedule.skill.AgentTaskAction
import com.ugk.pi.schedule.skill.AgentTaskSchedule
import com.ugk.pi.schedule.skill.AgentTaskScheduler
import com.ugk.pi.schedule.skill.AgentTaskStatus
import com.ugk.pi.schedule.skill.AgentTaskStore
import com.ugk.pi.schedule.skill.FixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-7 complement to the update-tool guard: records that were already
 * persisted as SCHEDULED with no occurrence - written by the pre-fix
 * agent_task_update, or by a corrupted/hostile decoded record - were skipped by
 * convergence (the filter required a non-null next run) while agent_task_list
 * kept reporting them as active. Restore must now repair or retire them.
 */
class ScheduledTaskWithoutOccurrenceHealTest {

    @Test
    fun overdueScheduledRecordWithoutOccurrenceIsRetiredNotLeftUndead() = runBlocking {
        val store = FakeStore()
        val scheduler = RecordingScheduler()
        // SCHEDULED, no occurrence, and the one-shot window is already past.
        store.upsert(
            task("task_dead", AgentTaskSchedule.OneShot(runAtMillis = 61_000L), nextRunAtMillis = null)
        )
        val runtime = runtime(store, scheduler, nowMillis = 200_000L)

        val result = runtime.restoreScheduledTasks()

        assertEquals(emptyList<String>(), result.rearmedTaskIds)
        assertEquals(emptyList<String>(), scheduler.scheduledIds)
        assertEquals(
            "an unarmable SCHEDULED record must stop pretending to be active",
            AgentTaskStatus.EXPIRED,
            store.get("task_dead")?.status
        )
    }

    @Test
    fun futureScheduledRecordWithoutOccurrenceIsRepairedAndArmed() = runBlocking {
        val store = FakeStore()
        val scheduler = RecordingScheduler()
        store.upsert(
            task("task_stale", AgentTaskSchedule.OneShot(runAtMillis = 90_000L), nextRunAtMillis = null)
        )
        val runtime = runtime(store, scheduler, nowMillis = 80_000L)

        val result = runtime.restoreScheduledTasks()

        assertEquals(listOf("task_stale"), result.rearmedTaskIds)
        assertEquals(listOf("task_stale"), scheduler.scheduledIds)
        assertEquals(90_000L, store.get("task_stale")?.nextRunAtMillis)
        assertEquals(AgentTaskStatus.SCHEDULED, store.get("task_stale")?.status)
    }

    @Test
    fun healthyScheduledRecordIsNotRewritten() = runBlocking {
        // Reverse check: convergence must not recompute a repeating task's next
        // occurrence and drag it off the grid the execution path owns.
        val store = FakeStore()
        val scheduler = RecordingScheduler()
        store.upsert(
            task(
                "task_ok",
                AgentTaskSchedule.RepeatingUntil(
                    startAtMillis = 10_000L,
                    intervalMillis = 60_000L,
                    endAtMillis = 10_000_000L
                ),
                nextRunAtMillis = 130_000L
            )
        )
        val runtime = runtime(store, scheduler, nowMillis = 80_000L)

        runtime.restoreScheduledTasks()

        assertEquals(listOf("task_ok"), scheduler.scheduledIds)
        assertEquals(130_000L, store.get("task_ok")?.nextRunAtMillis)
        assertTrue(store.get("task_ok")!!.updatedAtMillis == 1_000L)
    }

    private fun runtime(store: AgentTaskStore, scheduler: AgentTaskScheduler, nowMillis: Long) =
        AndroidAgentTaskRuntime(
            dummyContext(),
            store,
            scheduler,
            NoopSink,
            null,
            FixedClock(nowMillis),
            rearmExecutor = null
        )

    private fun task(
        id: String,
        schedule: AgentTaskSchedule,
        nextRunAtMillis: Long?
    ) = AgentTask(
        id = id,
        sessionId = "session-1",
        title = "提醒",
        schedule = schedule,
        action = AgentTaskAction.NotifyUser(message = "该走了"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        nextRunAtMillis = nextRunAtMillis
    )

    private fun dummyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private object NoopSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }

    private class FakeStore : AgentTaskStore {
        private val tasks = linkedMapOf<String, AgentTask>()

        override suspend fun upsert(task: AgentTask) {
            tasks[task.id] = task
        }

        override suspend fun get(taskId: String): AgentTask? = tasks[taskId]

        override suspend fun list(): List<AgentTask> = tasks.values.toList()
    }

    private class RecordingScheduler : AgentTaskScheduler {
        val scheduledIds = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            scheduledIds += task.id
        }

        override suspend fun cancel(taskId: String) {}
    }
}
