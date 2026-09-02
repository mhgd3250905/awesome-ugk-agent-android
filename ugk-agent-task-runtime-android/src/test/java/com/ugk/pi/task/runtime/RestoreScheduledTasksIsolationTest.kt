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
import org.junit.Test

/**
 * [AndroidAgentTaskRuntime.restoreScheduledTasks] must isolate per-task
 * scheduling failures: in production the prompt route's scheduleAgentJob
 * throws IllegalStateException when JobScheduler returns RESULT_FAILURE, and
 * that failure may only affect its own task — every SCHEDULED task stored
 * after the failing one must still be re-armed, and the failure must be
 * reported (result failures) instead of escaping the restore loop.
 */
class RestoreScheduledTasksIsolationTest {

    @Test
    fun `a failing re-arm is isolated and reported while later tasks are still re-armed`() = runBlocking {
        val store = FakeStore()
        // Insertion order defines store.list() order (LinkedHashMap): the
        // failing prompt task sits between two healthy notification tasks.
        store.upsert(notifyTask("task_a"))
        store.upsert(promptTask("task_b"))
        store.upsert(notifyTask("task_c"))
        val scheduler = FailingScheduler(failingTaskId = "task_b")
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )

        val result = runtime.restoreScheduledTasks()

        // The scheduler failure no longer escapes restoreScheduledTasks(),
        // and the loop does not stop at the failing task.
        assertEquals(listOf("task_a", "task_b", "task_c"), scheduler.attemptedIds.toList())
        assertEquals(
            "tasks stored after the failing one must still be re-armed",
            listOf("task_a", "task_c"),
            scheduler.scheduledIds.toList()
        )
        // The failed task id is reported with its reason and failure count.
        val failure = result.failures.single()
        assertEquals("task_b", failure.taskId)
        assertEquals("Unable to schedule background Agent task task_b.", failure.reason)
        assertEquals(1, result.failureCount)
        assertEquals(listOf("task_a", "task_c"), result.rearmedTaskIds)
        // The restore converges platform state only; it must not mutate any
        // persisted record.
        assertEquals(AgentTaskStatus.SCHEDULED, store.get("task_b")?.status)
        assertEquals(1_600_000_000_000L, store.get("task_b")?.nextRunAtMillis)
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
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )

        val result = runtime.restoreScheduledTasks()

        assertEquals(emptyList<AgentTaskRestoreFailure>(), result.failures)
        assertEquals(0, result.failureCount)
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
     * AlarmManagerAgentTaskScheduler.scheduleAgentJob.
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

    private object NoopSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }
}
