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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-7 review follow-up: making a failed durable write surface as an error
 * must not turn a cancellation into a logged-and-continued bookkeeping
 * failure. AgentTaskStore is public API and hosts are told they can swap in
 * Room/SQLite, so a canceled suspend upsert is a real shape; swallowing it
 * would let handle() re-arm, notify and report success for a run Android just
 * asked to retry.
 */
class HandleWriteBackCancellationTest {

    @Test
    fun cancellationFromTheExecutionWriteBackPropagatesInsteadOfBeingLogged() = runBlocking {
        val store = CancellationOnWriteStore(
            due = AgentTask(
                id = "task_1",
                sessionId = "session-1",
                title = "提醒",
                schedule = AgentTaskSchedule.OneShot(runAtMillis = 61_000L),
                action = AgentTaskAction.NotifyUser(message = "该走了"),
                status = AgentTaskStatus.SCHEDULED,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
                nextRunAtMillis = 61_000L
            )
        )
        val scheduler = RecordingScheduler()
        val runtime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, null, FixedClock(200_000L),
            rearmExecutor = null
        )

        try {
            runtime.handle("task_1", reschedule = true)
            fail("a canceled write-back must not be reported as a completed execution")
        } catch (expected: CancellationException) {
            assertTrue(
                "the cancellation must reach the caller, got: ${expected.message}",
                expected.message?.contains("disk") == true || expected.message != null
            )
        }
        assertEquals(0, scheduler.scheduledIds.size)
    }

    private fun dummyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private object NoopSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }

    private class CancellationOnWriteStore(private val due: AgentTask) : AgentTaskStore {
        private var current = due

        override suspend fun upsert(task: AgentTask) {
            if (task.status != AgentTaskStatus.SCHEDULED) {
                throw CancellationException("simulated canceled durable write")
            }
            current = task
        }

        override suspend fun get(taskId: String): AgentTask? = current

        override suspend fun list(): List<AgentTask> = listOf(current)
    }

    private class RecordingScheduler : AgentTaskScheduler {
        val scheduledIds = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            scheduledIds += task.id
        }

        override suspend fun cancel(taskId: String) {}
    }
}
