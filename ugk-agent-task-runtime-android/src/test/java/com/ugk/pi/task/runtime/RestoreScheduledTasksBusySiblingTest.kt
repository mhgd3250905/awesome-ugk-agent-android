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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A prompt execution holds its own task's handle lock for minutes, so the
 * convergence pass must never wait behind it.
 *
 * [AgentTaskJobService.finishJob] runs [AndroidAgentTaskRuntime.restoreScheduledTasks]
 * when a prompt job exits, while a sibling prompt job may still be executing.
 * The pass converges every persisted task in one loop, and the same coroutine is
 * the only place that posts `jobFinished()`: one iteration that waits for a busy
 * sibling leaves every later task un-armed and makes Android see the finished job
 * as still running until it force-stops the JobService.
 *
 * The runtime's own contract forbids this ("a prompt execution that runs for
 * minutes must not delay another task's notification delivery"), so the blocking
 * variant is a contract violation rather than an accepted trade-off.
 */
class RestoreScheduledTasksBusySiblingTest {

    @Test
    fun `convergence completes while a sibling task is executing and still re-arms idle tasks`() = runBlocking {
        val store = FakeStore()
        // Insertion order is store.list() order: the busy prompt task comes first.
        store.upsert(busyPromptTask("task_running"))
        store.upsert(notifyTask("task_later"))
        val promptStarted = CompletableDeferred<Unit>()
        val promptRelease = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler()
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, SucceedingSink,
            promptExecutor = {
                promptStarted.complete(Unit)
                promptRelease.await()
                AgentTaskActionExecutionResult(true, "完成")
            },
            clock = FixedClock(NOW_MILLIS),
            rearmExecutor = null
        )

        // Mirrors the JobService route: handle(reschedule = false), so this
        // delivery never re-arms itself and the convergence pass is the only
        // place that can install the next occurrence.
        val running = launch { runtime.handle("task_running", reschedule = false) }
        promptStarted.await()

        val converged = async { runtime.restoreScheduledTasks() }
        val result = withTimeoutOrNull(300L) { converged.await() }
        assertNotNull(
            "convergence must not wait behind an in-flight prompt execution",
            result
        )
        val scheduledWhileBusy = scheduler.scheduledIds.toList()

        promptRelease.complete(Unit)
        running.join()
        assertEquals(listOf("task_later"), result?.rearmedTaskIds)
        assertEquals(emptyList<AgentTaskRestoreFailure>(), result?.failures)
        // The busy task is left to its own delivery; the idle one is re-armed.
        assertEquals(listOf("task_later"), scheduledWhileBusy)
    }

    @Test
    fun `convergence re-arms every idle task`() = runBlocking {
        val store = FakeStore()
        store.upsert(notifyTask("task_a"))
        store.upsert(notifyTask("task_b"))
        val scheduler = RecordingScheduler()
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, SucceedingSink, null,
            FixedClock(NOW_MILLIS), rearmExecutor = null
        )

        val result = withTimeoutOrNull(300L) { runtime.restoreScheduledTasks() }

        assertEquals(listOf("task_a", "task_b"), result?.rearmedTaskIds)
        assertEquals(emptyList<AgentTaskRestoreFailure>(), result?.failures)
    }

    private fun notifyTask(id: String): AgentTask = AgentTask(
        id = id,
        sessionId = "session_1",
        title = "通知任务 $id",
        schedule = AgentTaskSchedule.OneShot(NOW_MILLIS),
        action = AgentTaskAction.NotifyUser("该休息了"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = NOW_MILLIS - 60_000L,
        updatedAtMillis = NOW_MILLIS - 60_000L,
        nextRunAtMillis = NOW_MILLIS
    )

    private fun busyPromptTask(id: String): AgentTask = AgentTask(
        id = id,
        sessionId = "session_1",
        title = "周期检查 $id",
        schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = NOW_MILLIS,
            intervalMillis = 60_000L,
            endAtMillis = NOW_MILLIS + 600_000L
        ),
        action = AgentTaskAction.RunAgentPrompt("检查当前界面"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = NOW_MILLIS - 60_000L,
        updatedAtMillis = NOW_MILLIS - 60_000L,
        nextRunAtMillis = NOW_MILLIS
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

    private object SucceedingSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }

    private companion object {
        const val NOW_MILLIS = 1_600_000_000_000L
    }
}
