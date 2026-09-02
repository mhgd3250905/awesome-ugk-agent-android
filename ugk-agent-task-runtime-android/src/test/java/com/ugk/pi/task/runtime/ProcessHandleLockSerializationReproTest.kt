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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Reproduces (JVM) the process-wide serialization of task delivery:
 * [AndroidAgentTaskRuntime.handle] holds PROCESS_HANDLE_LOCK
 * (AndroidAgentTaskRuntime.kt:439, lock declared at line 569 in the
 * companion object, shared by every runtime instance in the process) for the
 * WHOLE duration of the call, including the prompt executor run
 * (promptExecutor.execute at line 474). A notification task firing during a
 * slow prompt execution cannot pass `notificationSink.publish` (line 462)
 * until the prompt finishes — the reminder is delayed by the whole LLM/tool
 * loop.
 *
 * The assertions below pin the CURRENT (defective) behavior: while the
 * prompt executor is parked inside execute(), the notification handle makes
 * no progress; it only delivers after the prompt releases the lock.
 */
class ProcessHandleLockSerializationReproTest {

    @Test
    fun `notification delivery is blocked while a prompt execution holds the process lock`() = runBlocking {
        val store = FakeStore()
        store.upsert(slowPromptTask())
        store.upsert(notifyTask())
        val scheduler = RecordingScheduler()
        val sink = TimestampingNotificationSink()
        val executor = BlockingPromptExecutor()
        // Alarm and job deliveries each build their own runtime instance, but
        // PROCESS_HANDLE_LOCK lives in the companion object, so both instances
        // contend on the same process-wide mutex.
        val promptRuntime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, sink, executor, FixedClock(1_600_000_000_000L)
        )
        val notifyRuntime = AndroidAgentTaskRuntime(
            dummyContext(), store, scheduler, sink, null, FixedClock(1_600_000_000_000L)
        )

        val promptResult = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { promptResult.complete(promptRuntime.handle(SLOW_TASK_ID)) }
        // The prompt handle now sits inside execute() while holding the lock.
        assertTrue(executor.started.await(5, TimeUnit.SECONDS))

        val notifyCallNanos = System.nanoTime()
        val notifyResult = CompletableDeferred<AgentTaskActionExecutionResult>()
        launch(Dispatchers.IO) { notifyResult.complete(notifyRuntime.handle(NOTIFY_TASK_ID)) }

        // Defect evidence 1: with the prompt executor parked, the notification
        // is not delivered within the wait window — the notify handle is stuck
        // before notificationSink.publish (line 462), behind the lock.
        val notifiedWhilePromptParked = sink.notifyDelivery.await(500, TimeUnit.MILLISECONDS)
        assertFalse(
            "expected the notification to stay blocked while the prompt executor holds the process lock",
            notifiedWhilePromptParked
        )
        // Defect evidence 2: the notify handle() call itself has not returned.
        assertFalse(
            "expected handle(task_notify) to still be blocked (not completed) while the prompt runs",
            notifyResult.isCompleted
        )

        val releaseNanos = System.nanoTime()
        executor.release.complete(Unit)
        val promptOutcome = withTimeout(10_000) { promptResult.await() }
        val notifyOutcome = withTimeout(10_000) { notifyResult.await() }
        assertTrue(promptOutcome.success)
        assertTrue(notifyOutcome.success)

        // Defect evidence 3: the notification only goes out AFTER the prompt
        // released the lock (timestamp ordering, monotonic clock).
        val notifyPublishNanos = sink.firstNotifyPublishNanos.get()
        assertTrue(
            "notification publish must not have happened before the prompt release " +
                "(publish=$notifyPublishNanos, release=$releaseNanos)",
            notifyPublishNanos >= releaseNanos
        )
        assertTrue(
            "the notification task must have been delivered only after release",
            sink.publishedTaskIds.contains(NOTIFY_TASK_ID)
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

    /** Records delivery order and wall-clock (monotonic) timestamps per task. */
    private class TimestampingNotificationSink : com.ugk.pi.task.runtime.AgentTaskNotificationSink {
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
    private class BlockingPromptExecutor : com.ugk.pi.task.runtime.AgentTaskPromptExecutor {
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult {
            started.countDown()
            release.await()
            return AgentTaskActionExecutionResult(true, "执行完成")
        }
    }
}
