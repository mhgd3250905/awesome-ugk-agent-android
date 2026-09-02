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
import java.util.concurrent.Executor

/**
 * Alarm and JobScheduler triggers are one-shot and only re-armed after
 * handle() commits; a process death mid-execution leaves the record
 * SCHEDULED with no armed trigger, and a pure-notification host has no
 * recovery entry besides device boot. Constructing a runtime must therefore
 * run an idempotent convergence pass over the persisted SCHEDULED tasks
 * (same alarm requestCode / JobScheduler job id means replace semantics).
 * The pass only re-arms platform state — it must never advance task state.
 */
class InitialRearmOnConstructionTest {

    @Test
    fun `constructing the runtime re-arms persisted scheduled tasks without advancing them`() = runBlocking {
        val store = FakeStore()
        store.upsert(notifyTask("task_a"))
        store.upsert(promptTask("task_b"))
        store.upsert(completedTask("task_c"))
        val scheduler = RecordingScheduler()
        val directExecutor = Executor { it.run() }

        AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L),
            rearmExecutor = directExecutor
        )

        // Both SCHEDULED tasks were re-armed; the COMPLETED one was skipped.
        assertEquals(listOf("task_a", "task_b"), scheduler.scheduledIds.toList())
        // Convergence must not advance or mutate any record.
        assertEquals(AgentTaskStatus.SCHEDULED, store.get("task_a")?.status)
        assertEquals(1_600_000_000_000L, store.get("task_a")?.nextRunAtMillis)
        assertEquals(1_599_999_000_000L, store.get("task_a")?.updatedAtMillis)
        assertEquals(1_600_000_000_000L, store.get("task_b")?.nextRunAtMillis)
        assertEquals(AgentTaskStatus.COMPLETED, store.get("task_c")?.status)
    }

    @Test
    fun `a null rearm executor disables the construction pass`() = runBlocking {
        val store = FakeStore()
        store.upsert(notifyTask("task_a"))
        val scheduler = RecordingScheduler()

        AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )

        assertTrue(scheduler.scheduledIds.isEmpty())
    }

    @Test
    fun `repeated constructions keep converging with stable task state`() = runBlocking {
        val store = FakeStore()
        store.upsert(notifyTask("task_a"))
        val scheduler = RecordingScheduler()
        val directExecutor = Executor { it.run() }

        repeat(3) {
            AndroidAgentTaskRuntime(
                dummyContext(), store, scheduler, NoopSink, null, FixedClock(1_600_000_000_000L),
                rearmExecutor = directExecutor
            )
        }

        // An injected executor runs the pass on every construction; the
        // platform replace semantics make that idempotent, and the store
        // state stays untouched.
        assertEquals(listOf("task_a", "task_a", "task_a"), scheduler.scheduledIds.toList())
        assertEquals(AgentTaskStatus.SCHEDULED, store.get("task_a")?.status)
        assertEquals(1_600_000_000_000L, store.get("task_a")?.nextRunAtMillis)
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

    private fun completedTask(id: String): AgentTask = notifyTask(id).copy(
        status = AgentTaskStatus.COMPLETED,
        nextRunAtMillis = null
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

    private class RecordingScheduler : AgentTaskScheduler {
        val scheduledIds = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            synchronized(scheduledIds) { scheduledIds += task.id }
        }

        override suspend fun cancel(taskId: String) = Unit
    }

    private object NoopSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }
}
