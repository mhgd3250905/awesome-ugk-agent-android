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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AgentTaskRuntimeTest {
    @Test
    fun jsonCodecRoundTripsPolymorphicTask() {
        val task = sampleTask()

        val decoded = AgentTaskJsonCodec.decode(AgentTaskJsonCodec.encode(listOf(task))).single()

        assertEquals(task, decoded)
        assertTrue(decoded.action is AgentTaskAction.NotifyUser)
        assertTrue(decoded.schedule is AgentTaskSchedule.OneShot)
    }

    @Test
    fun jsonCodecRejectsCorruptPayloadWithoutBreakingStartup() {
        assertEquals(emptyList<AgentTask>(), AgentTaskJsonCodec.decode("not-json"))
    }

    @Test
    fun successfulOneShotExecutionBecomesCompleted() {
        val updated = sampleTask().afterExecution(now = 1_600_000_001_000L, success = true)

        assertEquals(AgentTaskStatus.COMPLETED, updated.status)
        assertEquals(1_600_000_001_000L, updated.lastRunAtMillis)
        assertEquals(1_600_000_001_000L, updated.completedAtMillis)
        assertNull(updated.nextRunAtMillis)
    }

    @Test
    fun successfulRepeatingExecutionSchedulesTheNextOccurrence() {
        val task = sampleTask().copy(
            schedule = AgentTaskSchedule.RepeatingUntil(
                startAtMillis = 1_600_000_000_000L,
                intervalMillis = 60_000L,
                endAtMillis = 1_600_000_180_000L
            ),
            nextRunAtMillis = 1_600_000_000_000L
        )

        val updated = task.afterExecution(now = 1_600_000_000_000L, success = true)

        assertEquals(AgentTaskStatus.SCHEDULED, updated.status)
        assertEquals(1_600_000_060_000L, updated.nextRunAtMillis)
        assertEquals(1_600_000_000_000L, updated.lastRunAtMillis)
        assertNull(updated.completedAtMillis)
    }

    @Test
    fun failedExecutionBecomesTerminalWithoutASecondAlarm() {
        val updated = sampleTask().afterExecution(now = 1_600_000_001_000L, success = false)

        assertEquals(AgentTaskStatus.FAILED, updated.status)
        assertNull(updated.nextRunAtMillis)
        assertEquals(1_600_000_001_000L, updated.lastRunAtMillis)
    }

    @Test
    fun oneShotDeliveryFailureStaysTerminalFailed() {
        val updated = sampleTask().afterDeliveryFailure(now = 1_600_000_001_000L)

        assertEquals(AgentTaskStatus.FAILED, updated.status)
        assertNull(updated.nextRunAtMillis)
        assertEquals(1_600_000_001_000L, updated.lastRunAtMillis)
    }

    @Test
    fun repeatingDeliveryFailureKeepsTheTaskScheduledAtTheNextOccurrence() {
        val updated = repeatingNotifyTask().afterDeliveryFailure(now = 1_600_000_000_000L)

        assertEquals(AgentTaskStatus.SCHEDULED, updated.status)
        assertEquals(1_600_000_060_000L, updated.nextRunAtMillis)
        assertEquals(1_600_000_000_000L, updated.lastRunAtMillis)
        assertNull(updated.completedAtMillis)
    }

    @Test
    fun repeatingDeliveryFailureAfterTheLastOccurrenceExpires() {
        val updated = repeatingNotifyTask().afterDeliveryFailure(now = 1_600_000_200_000L)

        assertEquals(AgentTaskStatus.EXPIRED, updated.status)
        assertNull(updated.nextRunAtMillis)
        assertEquals(1_600_000_200_000L, updated.completedAtMillis)
    }

    @Test
    fun receiverTaskSwallowsUnexpectedErrors() = runBlocking {
        var completed = false
        runReceiverTask { throw IllegalStateException("scheduler rejected the job") }
        completed = true

        assertTrue(completed)
    }

    @Test
    fun receiverTaskStillPropagatesCancellation() {
        val thrown = runCatching {
            runBlocking { runReceiverTask { throw CancellationException("Android stopped the broadcast.") } }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test
    fun promptTasksUseTheBackgroundAgentJobRoute() {
        val task = sampleTask().copy(
            action = AgentTaskAction.RunAgentPrompt("检查当前界面")
        )

        assertEquals(AgentTaskTriggerRoute.AGENT_JOB, task.triggerRoute())
    }

    @Test
    fun notificationTasksUseTheAlarmRoute() {
        assertEquals(AgentTaskTriggerRoute.NOTIFICATION_ALARM, sampleTask().triggerRoute())
    }

    @Test
    fun twoRuntimeInstancesExecuteTheSameDuePromptTaskOnlyOnce() = runBlocking {
        val store = FakeAgentTaskStore()
        store.upsert(sampleTask().copy(action = AgentTaskAction.RunAgentPrompt("检查当前界面")))
        val scheduler = RecordingAgentTaskScheduler()
        val executor = CountingPromptExecutor()
        val clock = FixedClock(1_600_000_000_000L)
        // Alarm and job deliveries each build their own runtime instance; the
        // shared store must keep the check-then-act transition exclusive.
        val runtimeA = AndroidAgentTaskRuntime(dummyContext(), store, scheduler, NoopAgentTaskNotificationSink, executor, clock, rearmExecutor = null)
        val runtimeB = AndroidAgentTaskRuntime(dummyContext(), store, scheduler, NoopAgentTaskNotificationSink, executor, clock, rearmExecutor = null)

        val first = launch(Dispatchers.IO) { runtimeA.handle("task_1") }
        val second = launch(Dispatchers.IO) { runtimeB.handle("task_1") }
        joinAll(first, second)

        assertEquals(1, executor.executions.get())
    }

    @Test
    fun undeliverableNotificationKeepsARepeatingTaskScheduled() = runBlocking {
        val store = FakeAgentTaskStore()
        store.upsert(repeatingNotifyTask())
        val scheduler = RecordingAgentTaskScheduler()
        val sink = RecordingAgentTaskNotificationSink(deliverySucceeds = false)
        val runtime = AndroidAgentTaskRuntime(dummyContext(), store, scheduler, sink, null, FixedClock(1_600_000_000_000L), rearmExecutor = null)

        val result = runtime.handle("task_1")

        assertFalse(result.success)
        val stored = store.get("task_1")!!
        assertEquals(AgentTaskStatus.SCHEDULED, stored.status)
        assertEquals(1_600_000_060_000L, stored.nextRunAtMillis)
        assertEquals(1_600_000_000_000L, stored.lastRunAtMillis)
        assertTrue(scheduler.scheduled.any { it.id == "task_1" })
    }

    @Test
    fun twoStoreInstancesDoNotLoseEachOthersCommittedTasks() {
        val backing = AtomicReference(AgentTaskJsonCodec.encode(listOf(sampleTask())))
        // Align both read-modify-write cycles on one stale snapshot so the
        // lost update is deterministic: both instances must observe [task_1]
        // before either of them commits its own task.
        val bothHaveRead = CountDownLatch(2)
        val readRaw: () -> String? = {
            bothHaveRead.countDown()
            bothHaveRead.await(2, TimeUnit.SECONDS)
            backing.get()
        }
        val storeA = TaskRecordStore(readRaw, { backing.set(it) })
        val storeB = TaskRecordStore(readRaw, { backing.set(it) })

        val writerA = Thread { storeA.upsert(sampleTask().copy(id = "task_2")) }
        val writerB = Thread { storeB.upsert(sampleTask().copy(id = "task_3")) }
        writerA.start()
        writerB.start()
        writerA.join()
        writerB.join()

        val ids = AgentTaskJsonCodec.decode(backing.get()).map { it.id }.toSet()
        assertEquals(setOf("task_1", "task_2", "task_3"), ids)
    }

    @Test
    fun interleavedUpsertsAcrossTwoStoreInstancesKeepAllTasks() {
        repeat(20) {
            val backing = AtomicReference<String?>(null)
            val storeA = TaskRecordStore({ backing.get() }, { backing.set(it) })
            val storeB = TaskRecordStore({ backing.get() }, { backing.set(it) })

            val writerA = Thread { repeat(50) { storeA.upsert(sampleTask().copy(id = "task_a")) } }
            val writerB = Thread { repeat(50) { storeB.upsert(sampleTask().copy(id = "task_b")) } }
            writerA.start()
            writerB.start()
            writerA.join()
            writerB.join()

            val ids = AgentTaskJsonCodec.decode(backing.get()).map { it.id }.toSet()
            assertEquals(setOf("task_a", "task_b"), ids)
        }
    }

    @Test
    fun upsertOverACorruptRecordBacksUpTheRawPayloadFirst() {
        val backing = AtomicReference("{ definitely not json")
        val backedUp = AtomicReference<String?>(null)
        val store = TaskRecordStore(
            readRaw = { backing.get() },
            writeRaw = { backing.set(it) },
            writeBackup = { backedUp.set(it) }
        )

        store.upsert(sampleTask())

        assertEquals("{ definitely not json", backedUp.get())
        assertEquals(listOf(sampleTask()), AgentTaskJsonCodec.decode(backing.get()))
    }

    private fun sampleTask(): AgentTask = AgentTask(
        id = "task_1",
        sessionId = "session_1",
        title = "提醒",
        schedule = AgentTaskSchedule.OneShot(1_600_000_000_000L),
        action = AgentTaskAction.NotifyUser("该休息了"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_599_999_000_000L,
        updatedAtMillis = 1_599_999_000_000L,
        nextRunAtMillis = 1_600_000_000_000L
    )

    private fun repeatingNotifyTask(): AgentTask = sampleTask().copy(
        title = "喝水提醒",
        schedule = AgentTaskSchedule.RepeatingUntil(
            startAtMillis = 1_600_000_000_000L,
            intervalMillis = 60_000L,
            endAtMillis = 1_600_000_180_000L
        ),
        action = AgentTaskAction.NotifyUser("该喝水了")
    )

    /**
     * The runtime only reads applicationContext, which plain JVM tests cannot
     * obtain from the framework stub, so a self-returning wrapper is enough.
     */
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
        val cancelled = mutableListOf<String>()

        override suspend fun schedule(task: AgentTask) {
            synchronized(scheduled) { scheduled += task }
        }

        override suspend fun cancel(taskId: String) {
            synchronized(cancelled) { cancelled += taskId }
        }
    }

    private class RecordingAgentTaskNotificationSink(
        private val deliverySucceeds: Boolean
    ) : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = deliverySucceeds
    }

    private object NoopAgentTaskNotificationSink : AgentTaskNotificationSink {
        override fun publish(context: Context, task: AgentTask, message: String): Boolean = true
    }

    private class CountingPromptExecutor : AgentTaskPromptExecutor {
        val executions = AtomicInteger()

        // Both deliveries must sit inside execute() at the same time so the
        // duplicated check-then-act transition is exposed deterministically:
        // the latch only opens once both broadcasts passed the status check.
        private val arrivals = CountDownLatch(2)

        override suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult {
            arrivals.countDown()
            arrivals.await(2, TimeUnit.SECONDS)
            executions.incrementAndGet()
            return AgentTaskActionExecutionResult(true, "执行完成")
        }
    }
}
