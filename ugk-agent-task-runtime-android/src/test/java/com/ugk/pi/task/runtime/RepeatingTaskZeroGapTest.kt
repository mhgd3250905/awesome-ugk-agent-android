package com.ugk.pi.task.runtime

import android.content.Context
import android.content.ContextWrapper
import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.android.AgentTaskClock
import com.ugk.pi.android.AgentTaskSchedule
import com.ugk.pi.android.AgentTaskScheduler
import com.ugk.pi.android.AgentTaskStatus
import com.ugk.pi.android.AgentTaskStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-5 P0 review regression: a repeating task whose execution runs longer
 * than its interval used to anchor the next occurrence at the execution
 * START time, so every write-back landed in the past and the platform
 * trigger fired again immediately — a zero-gap execution loop (plus one
 * ALWAYS_NOTIFY notification per loop) until endAtMillis.
 */
class RepeatingTaskZeroGapTest {

    private class MutableClock(var now: Long) : AgentTaskClock {
        override fun nowMillis(): Long = now
    }

    private fun dummyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private class FakeAgentTaskStore : AgentTaskStore {
        private val tasks = linkedMapOf<String, AgentTask>()
        override suspend fun upsert(task: AgentTask) {
            synchronized(tasks) { tasks[task.id] = task }
        }
        override suspend fun get(taskId: String): AgentTask? = synchronized(tasks) { tasks[taskId] }
        override suspend fun list(): List<AgentTask> = synchronized(tasks) { tasks.values.toList() }
    }

    private class RecordingAgentTaskScheduler : AgentTaskScheduler {
        val scheduled = mutableListOf<AgentTask>()
        override suspend fun schedule(task: AgentTask) {
            synchronized(scheduled) { scheduled += task }
        }
        override suspend fun cancel(taskId: String) = Unit
    }

    private fun repeatingPromptTask(startAt: Long): AgentTask = AgentTask(
        id = "task_long",
        sessionId = "session_1",
        title = "巡检",
        schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = startAt,
            intervalMillis = 60_000L,
            endAtMillis = startAt + 30 * 60_000L
        ),
        action = AgentTaskAction.RunAgentPrompt("巡检一次"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = startAt - 1_000L,
        updatedAtMillis = startAt - 1_000L,
        nextRunAtMillis = startAt
    )

    @Test
    fun `execution longer than the interval does not rearm a zero-gap next run`() = runBlocking {
        val start = 1_600_000_000_000L
        val clock = MutableClock(start)
        val store = FakeAgentTaskStore()
        store.upsert(repeatingPromptTask(start))
        val runtime = AndroidAgentTaskRuntime(
            context = dummyContext(),
            store = store,
            scheduler = RecordingAgentTaskScheduler(),
            notificationSink = { _, _, _ -> true },
            promptExecutor = { _ ->
                // One prompt execution easily outruns a 60s interval.
                clock.now += 10 * 60_000L
                AgentTaskActionExecutionResult(true, "done")
            },
            clock = clock,
            rearmExecutor = null
        )

        runtime.handle("task_long")

        val stored = store.get("task_long")!!
        val nextRun = stored.nextRunAtMillis
        assertTrue(
            "next occurrence must be anchored after the execution finished " +
                "(nextRun=$nextRun, now=${clock.now})",
            nextRun != null && nextRun > clock.now
        )
    }

    @Test
    fun `execution shorter than the interval keeps the fixed rate anchor`() = runBlocking {
        val start = 1_600_000_000_000L
        val clock = MutableClock(start)
        val store = FakeAgentTaskStore()
        store.upsert(repeatingPromptTask(start))
        val runtime = AndroidAgentTaskRuntime(
            context = dummyContext(),
            store = store,
            scheduler = RecordingAgentTaskScheduler(),
            notificationSink = { _, _, _ -> true },
            promptExecutor = { _ ->
                clock.now += 5_000L
                AgentTaskActionExecutionResult(true, "done")
            },
            clock = clock,
            rearmExecutor = null
        )

        runtime.handle("task_long")

        val stored = store.get("task_long")!!
        // Fixed-rate semantics: the next occurrence is still the original
        // start + interval grid slot, not completion + interval.
        assertEquals(start + 60_000L, stored.nextRunAtMillis)
    }
}
