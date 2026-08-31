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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A task control-plane action taken while a background execution is in
 * flight (user cancels or reschedules from the foreground conversation)
 * must survive the execution's write-back.
 *
 * `handle()` reads the task record once, runs the prompt executor for
 * seconds to minutes, and then upserts a record derived from that stale
 * snapshot. A CANCELLED written by the cancel tool during the run used to be
 * overwritten back to SCHEDULED/COMPLETED and the platform re-armed it — a
 * cancelled task kept notifying forever.
 */
class AgentTaskRuntimeExecutionOverwriteTest {

    @Test
    fun `cancelling a task while its prompt executes keeps it cancelled`() = runBlocking {
        val store = FakeStore()
        store.upsert(repeatingPromptTask())
        val scheduler = RecordingScheduler()
        val executor = BlockingPromptExecutor()
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, executor,
            FixedClock(1_600_000_000_000L)
        )

        val execution = launch(Dispatchers.IO) { runtime.handle("task_1") }
        // Wait until the executor is inside execute() (i.e. past the status
        // check on the stale snapshot), then simulate the foreground cancel
        // tool: write CANCELLED and cancel the platform schedule.
        assertTrue(executor.started.await(5, TimeUnit.SECONDS))
        val cancelled = repeatingPromptTask().copy(status = AgentTaskStatus.CANCELLED, nextRunAtMillis = null)
        store.upsert(cancelled)
        scheduler.cancel("task_1")
        executor.release.complete(Unit)
        execution.join()

        val stored = store.get("task_1")!!
        assertEquals(
            "a concurrent CANCELLED must not be resurrected by the execution write-back, was ${stored.status}",
            AgentTaskStatus.CANCELLED,
            stored.status
        )
        assertTrue(
            "a cancelled task must not be re-armed by the platform scheduler, armed=${scheduler.scheduled}",
            scheduler.scheduled.none { it.id == "task_1" }
        )
    }

    @Test
    fun `rescheduling a task while its prompt executes keeps the new schedule`() = runBlocking {
        val store = FakeStore()
        store.upsert(repeatingPromptTask())
        val scheduler = RecordingScheduler()
        val executor = BlockingPromptExecutor()
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, executor,
            FixedClock(1_600_000_000_000L)
        )

        val execution = launch(Dispatchers.IO) { runtime.handle("task_1") }
        assertTrue(executor.started.await(5, TimeUnit.SECONDS))
        // Simulate agent_task_update: a new schedule window, still SCHEDULED.
        val updatedTask = repeatingPromptTask().copy(
            title = "改期后的任务",
            schedule = AgentTaskSchedule.RepeatingUntil(
                startAtMillis = 1_600_000_000_000L,
                intervalMillis = 600_000L,
                endAtMillis = 1_600_009_000_000L
            )
        )
        store.upsert(updatedTask)
        executor.release.complete(Unit)
        execution.join()

        val stored = store.get("task_1")!!
        assertEquals(
            "the concurrent update must survive the execution write-back",
            "改期后的任务",
            stored.title
        )
        assertEquals(
            "the concurrent schedule change must survive the execution write-back",
            600_000L,
            (stored.schedule as AgentTaskSchedule.RepeatingUntil).intervalMillis
        )
    }

    private fun repeatingPromptTask(): AgentTask = AgentTask(
        id = "task_1",
        sessionId = "session_1",
        title = "周期检查",
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

    private class RecordingScheduler : AgentTaskScheduler {
        val scheduled = mutableListOf<AgentTask>()
        val cancelled = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            synchronized(scheduled) { scheduled += task }
        }

        override suspend fun cancel(taskId: String) {
            synchronized(cancelled) { cancelled += taskId }
        }
    }

    private object NoopSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }

    private class BlockingPromptExecutor : AgentTaskPromptExecutor {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult {
            started.countDown()
            release.await()
            return AgentTaskActionExecutionResult(true, "执行完成")
        }
    }
}
