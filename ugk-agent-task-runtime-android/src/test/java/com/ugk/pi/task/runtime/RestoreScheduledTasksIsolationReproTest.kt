package com.ugk.pi.task.runtime

import android.content.Context
import android.content.ContextWrapper
import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.android.AgentTaskSchedule
import com.ugk.pi.android.AgentTaskScheduler
import com.ugk.pi.android.AgentTaskStatus
import com.ugk.pi.android.AgentTaskStore
import com.ugk.pi.android.FixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces (JVM, current defective behavior) the missing per-task
 * isolation in [AndroidAgentTaskRuntime.restoreScheduledTasks]
 * (AndroidAgentTaskRuntime.kt:553-562): the SCHEDULED tasks are re-armed by a
 * plain `forEach { scheduler.schedule(it) }` (line 560). In production the
 * prompt route's scheduleAgentJob fails via
 * `check(jobScheduler.schedule(job) == RESULT_SUCCESS)` (lines 234-236) and
 * throws IllegalStateException. That exception escapes the forEach, so every
 * SCHEDULED task stored AFTER the failing one never gets its platform
 * trigger re-armed after boot/reboot.
 *
 * The stub scheduler mirrors that failure for one task id; the assertions
 * below pin the CURRENT behavior on purpose (a fix will need to flip them).
 */
class RestoreScheduledTasksIsolationReproTest {

    @Test
    fun `one failing schedule aborts restore of every later scheduled task`() = runBlocking {
        val store = FakeStore()
        // Insertion order defines store.list() order (LinkedHashMap): the
        // failing prompt task sits between two healthy notification tasks.
        store.upsert(notifyTask("task_a"))
        store.upsert(promptTask("task_b"))
        store.upsert(notifyTask("task_c"))
        val scheduler = FailingScheduler(failingTaskId = "task_b")
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L)
        )

        val failure = runCatching { runtime.restoreScheduledTasks() }.exceptionOrNull()

        // Defect evidence 1: the scheduler failure escapes restoreScheduledTasks()
        // (in production this IllegalStateException comes from
        // AlarmManagerAgentTaskScheduler.scheduleAgentJob's check(), lines 234-236;
        // the alarm receiver path would swallow it in runReceiverTask, but the
        // remaining tasks were already lost by then).
        assertTrue(
            "expected the scheduler failure to escape restoreScheduledTasks, was $failure",
            failure is IllegalStateException
        )
        assertEquals(
            "Unable to schedule background Agent task task_b.",
            failure?.message
        )
        // Defect evidence 2: the loop stopped at the failing task — task_a was
        // re-armed, task_c (stored after the failing one) was never attempted.
        assertEquals(
            "tasks stored after the failing one must not be re-scheduled under the current implementation",
            listOf("task_a"),
            scheduler.scheduledIds.toList()
        )
        assertEquals(
            "the failing task itself was attempted before the loop aborted",
            listOf("task_a", "task_b"),
            scheduler.attemptedIds.toList()
        )
    }

    /** Control group: with no failure every SCHEDULED task is re-armed in order. */
    @Test
    fun `without a failure restore re-arms every scheduled task`() = runBlocking {
        val store = FakeStore()
        store.upsert(notifyTask("task_a"))
        store.upsert(promptTask("task_b"))
        store.upsert(notifyTask("task_c"))
        val scheduler = FailingScheduler(failingTaskId = "none")
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L)
        )

        val failure = runCatching { runtime.restoreScheduledTasks() }.exceptionOrNull()

        assertEquals(null, failure)
        assertEquals(
            listOf("task_a", "task_b", "task_c"),
            scheduler.scheduledIds.toList()
        )
    }

    private fun notifyTask(id: String): AgentTask = AgentTask(
        id = id,
        sessionId = "session_1",
        title = "通知任务 $id",
        schedule = AgentTaskSchedule.OneShot(1_600_000_000_000L),
        action = AgentTaskAction.NotifyUser("该休息了"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_599_999_000_000L,
        updatedAtMillis = 1_599_999_000_000L,
        nextRunAtMillis = 1_600_000_000_000L
    )

    private fun promptTask(id: String): AgentTask = AgentTask(
        id = id,
        sessionId = "session_1",
        title = "周期检查 $id",
        schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = 1_600_000_000_000L,
            intervalMillis = 60_000L,
            endAtMillis = 1_600_000_180_000L
        ),
        action = AgentTaskAction.RunAgentPrompt("检查当前界面"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_599_999_000_000L,
        updatedAtMillis = 1_599_999_000_000L,
        nextRunAtMillis = 1_600_000_000_000L
    )

    private fun dummyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private class FakeStore : AgentTaskStore {
        private val tasks = linkedMapOf<String, AgentTask>()

        override suspend fun upsert(task: AgentTask) {
            synchronized(tasks) { tasks[task.id] = task }
        }

        override suspend fun get(taskId: String): AgentTask? = synchronized(tasks) { tasks[taskId] }

        override suspend fun list(): List<AgentTask> = synchronized(tasks) { tasks.values.toList() }
    }

    /**
     * Records every schedule() attempt and fails exactly one task id, mirroring
     * the JobScheduler RESULT_FAILURE -> IllegalStateException of
     * AlarmManagerAgentTaskScheduler.scheduleAgentJob (lines 234-236).
     */
    private class FailingScheduler(private val failingTaskId: String) : AgentTaskScheduler {
        val attemptedIds = mutableListOf<String>()
        val scheduledIds = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            synchronized(attemptedIds) { attemptedIds += task.id }
            if (task.id == failingTaskId) {
                throw IllegalStateException("Unable to schedule background Agent task ${task.id}.")
            }
            synchronized(scheduledIds) { scheduledIds += task.id }
        }

        override suspend fun cancel(taskId: String) = Unit
    }

    private object NoopSink : com.ugk.pi.task.runtime.AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }
}
