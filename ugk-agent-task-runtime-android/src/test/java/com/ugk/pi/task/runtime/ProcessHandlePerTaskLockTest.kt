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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * handle() must run under a per-task lock instead of a process-wide one: a
 * prompt execution (an LLM/tool loop that can take minutes) may not delay an
 * unrelated task's notification delivery, while two deliveries of the SAME
 * task (alarm plus job, or a double fire) still serialize their check-then-act
 * execution transition.
 */
class ProcessHandlePerTaskLockTest {

    @Test
    fun `notification delivery completes while an unrelated prompt execution is parked`() = runBlocking {
        val store = FakeStore()
        store.upsert(slowPromptTask())
        store.upsert(notifyTask())
        val scheduler = RecordingScheduler()
        val sink = TimestampingNotificationSink()
        val executor = BlockingPromptExecutor()
        // Alarm and job deliveries each build their own runtime instance; the
        // per-task locks live in the companion object, so both instances
        // still contend on the same lock for one task id.
        val promptRuntime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, sink, executor, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )
        val notifyRuntime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, sink, null, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )

        val promptResult = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { promptResult.complete(promptRuntime.handle(SLOW_TASK_ID)) }
        // The prompt handle now sits inside execute() while holding only its
        // own task's lock.
        assertTrue(executor.started.await(5, TimeUnit.SECONDS))

        val notifyResult = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { notifyResult.complete(notifyRuntime.handle(NOTIFY_TASK_ID)) }

        // Fixed behavior 1: the notification is delivered WHILE the prompt
        // executor is still parked, and the notify handle() returns without
        // waiting for the prompt to release anything.
        assertTrue(
            "expected the notification to be delivered while the prompt executor is parked",
            sink.notifyDelivery.await(5, TimeUnit.SECONDS)
        )
        val notifyOutcome = withTimeout(10_000) { notifyResult.await() }
        assertTrue(notifyOutcome.success)

        val releaseNanos = System.nanoTime()
        executor.release.complete(Unit)
        val promptOutcome = withTimeout(10_000) { promptResult.await() }
        assertTrue(promptOutcome.success)

        // Fixed behavior 2: the notification went out strictly BEFORE the
        // prompt was released (monotonic clock), not after it.
        val notifyPublishNanos = sink.firstNotifyPublishNanos.get()
        assertTrue(
            "notification publish must happen before the prompt release " +
                "(publish=$notifyPublishNanos, release=$releaseNanos)",
            notifyPublishNanos in 0 until releaseNanos
        )
        assertTrue(sink.publishedTaskIds.contains(NOTIFY_TASK_ID))
        // The two concurrent handles committed their own records without
        // interfering with each other.
        assertEquals(AgentTaskStatus.COMPLETED, store.get(NOTIFY_TASK_ID)?.status)
        assertEquals(AgentTaskStatus.SCHEDULED, store.get(SLOW_TASK_ID)?.status)
        assertEquals(1_600_000_060_000L, store.get(SLOW_TASK_ID)?.nextRunAtMillis)
    }

    @Test
    fun `two deliveries of the same task still serialize their execution`() = runBlocking {
        val store = FakeStore()
        store.upsert(slowPromptTask())
        val scheduler = RecordingScheduler()
        val executor = BlockingPromptExecutor()
        val runtimeA = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, executor, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )
        val runtimeB = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, executor, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )

        val first = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { first.complete(runtimeA.handle(SLOW_TASK_ID)) }
        assertTrue(executor.started.await(5, TimeUnit.SECONDS))

        val second = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { second.complete(runtimeB.handle(SLOW_TASK_ID)) }
        // While the first delivery is parked inside execute(), the second
        // delivery of the SAME task must stay blocked on that task's lock —
        // it cannot pass the status check and must not start a second run.
        assertFalse(
            "expected the second delivery of the same task to stay blocked while the first runs",
            second.isCompleted
        )

        executor.release.complete(Unit)
        val firstOutcome = withTimeout(10_000) { first.await() }
        val secondOutcome = withTimeout(10_000) { second.await() }
        assertTrue(firstOutcome.success)
        // The repeating task advanced to its next occurrence, so the second
        // delivery finds it not due yet and re-arms instead of executing.
        assertFalse(secondOutcome.success)
        assertEquals(
            "the same task occurrence must be executed exactly once",
            1,
            executor.executions.get()
        )
    }

    @Test
    fun `construction-time convergence skips a task whose handle is in flight`() = runBlocking {
        val store = FakeStore()
        store.upsert(slowPromptTask())
        store.upsert(notifyTask())
        val scheduler = RecordingScheduler()
        val executor = BlockingPromptExecutor()
        val handlingRuntime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, executor, FixedClock(1_600_000_000_000L),
            rearmExecutor = null
        )
        val promptResult = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { promptResult.complete(handlingRuntime.handle(SLOW_TASK_ID)) }
        assertTrue(executor.started.await(5, TimeUnit.SECONDS))

        // A cold-start convergence (direct executor = immediate pass) runs
        // while the prompt handle is parked inside execute(): it must skip
        // the busy task instead of re-arming a RUNNING job under it, and
        // still re-arm the unrelated notification task.
        AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, NoopSink, executor, FixedClock(1_600_000_000_000L),
            rearmExecutor = java.util.concurrent.Executor { it.run() }
        )

        executor.release.complete(Unit)
        val promptOutcome = withTimeout(10_000) { promptResult.await() }
        assertTrue(promptOutcome.success)

        val rearmedIds = synchronized(scheduler.scheduled) { scheduler.scheduled.map { it.id } }
        assertTrue(
            "expected the convergence to re-arm the unrelated notification task",
            NOTIFY_TASK_ID in rearmedIds
        )
        assertEquals(
            "the busy prompt task must be armed exactly once (by its own write-back), never by the convergence",
            1,
            rearmedIds.count { it == SLOW_TASK_ID }
        )
    }

    private companion object {
        const val SLOW_TASK_ID = "task_slow_prompt"
        const val NOTIFY_TASK_ID = "task_notify"
    }

    private fun slowPromptTask(): AgentTask = AgentTask(
        id = SLOW_TASK_ID,
        sessionId = "session_1",
        title = "慢速后台检查",
        schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = 1_600_000_000_000L,
            intervalMillis = 60_000L,
            endAtMillis = 1_600_000_180_000L
        ),
        action = AgentTaskAction.RunAgentPrompt("耗时很长的检查"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_599_999_000_000L,
        updatedAtMillis = 1_599_999_000_000L,
        nextRunAtMillis = 1_600_000_000_000L
    )

    private fun notifyTask(): AgentTask = AgentTask(
        id = NOTIFY_TASK_ID,
        sessionId = "session_1",
        title = "喝水提醒",
        schedule = AgentTaskSchedule.OneShot(1_600_000_000_000L),
        action = AgentTaskAction.NotifyUser("该喝水了"),
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

    /** Records delivery order and wall-clock (monotonic) timestamps per task. */
    private class TimestampingNotificationSink : AgentTaskNotificationSink {
        val publishedTaskIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val firstNotifyPublishNanos = AtomicLong(-1)
        val notifyDelivery = CountDownLatch(1)

        override fun publish(context: Context, task: AgentTask, message: String): Boolean {
            publishedTaskIds += task.id
            if (task.id == NOTIFY_TASK_ID) {
                firstNotifyPublishNanos.compareAndSet(-1, System.nanoTime())
                notifyDelivery.countDown()
            }
            return true
        }
    }

    /** Parks inside execute() until [release] completes, like a slow LLM/tool loop. */
    private class BlockingPromptExecutor : AgentTaskPromptExecutor {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger()

        override suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult {
            started.countDown()
            release.await()
            executions.incrementAndGet()
            return AgentTaskActionExecutionResult(true, "执行完成")
        }
    }
}
