package com.ugk.pi.task.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import com.ugk.pi.android.AgentTask
import com.ugk.pi.android.AgentTaskAction
import com.ugk.pi.android.AgentTaskClock
import com.ugk.pi.android.AgentTaskScheduler
import com.ugk.pi.android.AgentTaskStatus
import com.ugk.pi.android.AgentTaskStore
import com.ugk.pi.android.SystemAgentTaskClock
import com.ugk.pi.android.nextRunAtMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * A durable task store backed by one app-private SharedPreferences record.
 *
 * This is deliberately an adapter: the schedule skill only knows
 * [AgentTaskStore]. Hosts that need transactional queries or larger task
 * histories can replace it with SQLite/Room without changing the Tool API.
 */
class AndroidAgentTaskStore(context: Context) : AgentTaskStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val recordStore = TaskRecordStore(
        readRaw = { preferences.getString(KEY_TASKS, null) },
        writeRaw = { raw -> preferences.edit().putString(KEY_TASKS, raw).commit() },
        writeBackup = { raw -> preferences.edit().putString(KEY_TASKS_BACKUP, raw).commit() }
    )

    override suspend fun upsert(task: AgentTask) = recordStore.upsert(task)

    override suspend fun get(taskId: String): AgentTask? = recordStore.get(taskId)

    override suspend fun list(): List<AgentTask> = recordStore.list()

    private companion object {
        const val PREFERENCES_NAME = "ugk_agent_tasks"
        const val KEY_TASKS = "tasks"
        const val KEY_TASKS_BACKUP = "tasks_corrupt_backup"
    }
}

/**
 * Persistence core shared by every [AndroidAgentTaskStore] instance.
 *
 * The backing record is a single raw string, so the read-modify-write in
 * [upsert] must serialize across instances too: the alarm receiver, the job
 * service, and the host app each build their own store instance pointing at
 * the same SharedPreferences record, and per-instance locks would let one
 * instance overwrite another instance's committed snapshot.
 */
internal class TaskRecordStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit,
    private val writeBackup: (String) -> Unit = {}
) {
    fun upsert(task: AgentTask) {
        synchronized(lock) {
            val tasks = readForWrite().toMutableList()
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index >= 0) tasks[index] = task else tasks += task
            writeRaw(AgentTaskJsonCodec.encode(tasks))
        }
    }

    fun get(taskId: String): AgentTask? = synchronized(lock) {
        AgentTaskJsonCodec.decode(readRaw()).firstOrNull { it.id == taskId }
    }

    fun list(): List<AgentTask> = synchronized(lock) {
        AgentTaskJsonCodec.decode(readRaw())
    }

    private fun readForWrite(): List<AgentTask> {
        val raw = readRaw()
        if (raw.isNullOrBlank()) return emptyList()
        return AgentTaskJsonCodec.decodeOrNull(raw) ?: run {
            // One unreadable payload must not erase every stored task: keep
            // the raw record under a backup key before the next write
            // replaces it, so the data is still recoverable by hand.
            writeBackup(raw)
            emptyList()
        }
    }

    private companion object {
        val lock = Any()
    }
}

internal object AgentTaskJsonCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val serializer = ListSerializer(AgentTask.serializer())

    fun encode(tasks: List<AgentTask>): String = json.encodeToString(serializer, tasks)

    fun decode(value: String?): List<AgentTask> {
        if (value.isNullOrBlank()) return emptyList()
        return decodeOrNull(value) ?: emptyList()
    }

    /** Returns null only when the payload exists but cannot be decoded. */
    fun decodeOrNull(value: String): List<AgentTask>? =
        runCatching { json.decodeFromString(serializer, value) }.getOrNull()
}

internal enum class AgentTaskTriggerRoute {
    NOTIFICATION_ALARM,
    AGENT_JOB
}

internal fun AgentTask.triggerRoute(): AgentTaskTriggerRoute = when (action) {
    is AgentTaskAction.NotifyUser -> AgentTaskTriggerRoute.NOTIFICATION_ALARM
    is AgentTaskAction.RunAgentPrompt -> AgentTaskTriggerRoute.AGENT_JOB
}

/**
 * Android platform adapter for the generic [AgentTaskScheduler] port.
 *
 * Notification tasks use a one-shot alarm. Prompt tasks use JobScheduler so
 * Android can give them a real background execution window instead of asking
 * a short-lived BroadcastReceiver to run an LLM/tool loop.
 */
class AlarmManagerAgentTaskScheduler(context: Context) : AgentTaskScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val jobScheduler = appContext.getSystemService(JobScheduler::class.java)

    override suspend fun schedule(task: AgentTask) {
        val nextRunAt = task.nextRunAtMillis
        if (task.status != AgentTaskStatus.SCHEDULED || nextRunAt == null) {
            cancel(task.id)
            return
        }

        // A task can be changed from one action type to the other. Clear the
        // other platform route first, but do not cancel a currently running
        // JobScheduler job just before it schedules its next repetition.
        when (task.triggerRoute()) {
            AgentTaskTriggerRoute.NOTIFICATION_ALARM -> {
                cancelAgentJob(task.id)
                cancelNotificationAlarm(task.id)
                scheduleNotificationAlarm(task.id, nextRunAt)
            }
            AgentTaskTriggerRoute.AGENT_JOB -> {
                cancelNotificationAlarm(task.id)
                // JobScheduler replaces a pending job with the same stable id.
                // Avoid canceling the currently executing job here; its
                // JobService will finish it after this state transition.
                scheduleAgentJob(task, nextRunAt)
            }
        }
    }

    override suspend fun cancel(taskId: String) {
        cancelNotificationAlarm(taskId)
        cancelAgentJob(taskId)
    }

    private fun cancelNotificationAlarm(taskId: String) {
        val trigger = pendingIntent(taskId)
        alarmManager.cancel(trigger)
        trigger.cancel()
    }

    private fun cancelAgentJob(taskId: String) {
        jobScheduler.cancel(stableJobId(taskId))
    }

    private fun scheduleNotificationAlarm(taskId: String, nextRunAt: Long) {
        val trigger = pendingIntent(taskId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // This is intentionally not an exact alarm. A reminder may be
            // delayed by Doze, while no special exact-alarm permission is
            // needed for the ordinary persistent-task path.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextRunAt,
                trigger
            )
        } else {
            @Suppress("DEPRECATION")
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextRunAt, trigger)
        }
    }

    private fun scheduleAgentJob(task: AgentTask, nextRunAt: Long) {
        val delayMillis = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val extras = PersistableBundle().apply {
            putString(AgentTaskJobService.EXTRA_TASK_ID, task.id)
        }
        val job = JobInfo.Builder(
            stableJobId(task.id),
            ComponentName(appContext, AgentTaskJobService::class.java)
        )
            .setMinimumLatency(delayMillis)
            // Prompt execution normally needs a model network request. The
            // job remains pending until any usable network is available.
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setExtras(extras)
            .build()
        check(jobScheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
            "Unable to schedule background Agent task ${task.id}."
        }
    }

    private fun pendingIntent(taskId: String): PendingIntent {
        val intent = AgentTaskAlarmReceiver.fireIntent(appContext, taskId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(
            appContext,
            stableRequestCode(taskId),
            intent,
            flags
        )
    }

    private fun stableRequestCode(taskId: String): Int =
        taskId.hashCode() and Int.MAX_VALUE

    private fun stableJobId(taskId: String): Int =
        taskId.hashCode() and Int.MAX_VALUE
}

fun interface AgentTaskNotificationSink {
    /** Returns false when Android rejected or cannot display the notification. */
    fun publish(context: Context, task: AgentTask, message: String): Boolean
}

object DefaultAgentTaskNotificationSink : AgentTaskNotificationSink {
    private const val CHANNEL_ID = "ugk_agent_task_reminders"
    private const val CHANNEL_NAME = "UGK Agent 定时提醒"

    override fun publish(context: Context, task: AgentTask, message: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                task.id.hashCode() and Int.MAX_VALUE,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
            )
        }
        builder
            .setSmallIcon(appContext.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
            .setContentTitle(task.title.ifBlank { "UGK Agent 定时任务" })
            .setContentText(message.take(MAX_MESSAGE_CHARS))
            .setStyle(Notification.BigTextStyle().bigText(message.take(MAX_MESSAGE_CHARS)))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_DEFAULT)
        }
        contentIntent?.let(builder::setContentIntent)

        return runCatching {
            manager.notify(task.id.hashCode() and Int.MAX_VALUE, builder.build())
            true
        }.getOrDefault(false)
    }

    private const val MAX_MESSAGE_CHARS = 2_048
}

data class AgentTaskActionExecutionResult(
    val success: Boolean,
    val message: String
)

/**
 * Pure task state transition shared by Android execution and JVM tests.
 * A failed run is terminal in this first slice; retry policy belongs to a
 * future TaskRun/lease layer rather than the AlarmManager adapter.
 */
internal fun AgentTask.afterExecution(now: Long, success: Boolean): AgentTask {
    if (!success) {
        return copy(
            status = AgentTaskStatus.FAILED,
            updatedAtMillis = now,
            nextRunAtMillis = null,
            lastRunAtMillis = now
        )
    }

    val nextRun = schedule.nextRunAtMillis(now + 1L)
    return copy(
        status = when {
            schedule is com.ugk.pi.android.AgentTaskSchedule.OneShot -> AgentTaskStatus.COMPLETED
            nextRun == null -> AgentTaskStatus.EXPIRED
            else -> AgentTaskStatus.SCHEDULED
        },
        updatedAtMillis = now,
        nextRunAtMillis = nextRun,
        lastRunAtMillis = now,
        completedAtMillis = if (nextRun == null) now else null
    )
}

/**
 * State transition for delivery failures (missing notification permission,
 * rejected post): the run itself did not fail, so a repeating task stays
 * SCHEDULED at its next occurrence instead of dying terminally after one
 * denied notification. A one-shot task still ends FAILED because its single
 * reminder was lost, and the user can recreate it once permission is
 * granted. Like [afterExecution], a full retry policy belongs to a future
 * TaskRun/lease layer rather than this adapter.
 */
internal fun AgentTask.afterDeliveryFailure(now: Long): AgentTask {
    if (schedule is com.ugk.pi.android.AgentTaskSchedule.OneShot) {
        return copy(
            status = AgentTaskStatus.FAILED,
            updatedAtMillis = now,
            nextRunAtMillis = null,
            lastRunAtMillis = now
        )
    }

    val nextRun = schedule.nextRunAtMillis(now + 1L)
    return copy(
        status = when {
            nextRun == null -> AgentTaskStatus.EXPIRED
            else -> AgentTaskStatus.SCHEDULED
        },
        updatedAtMillis = now,
        nextRunAtMillis = nextRun,
        lastRunAtMillis = now,
        completedAtMillis = if (nextRun == null) now else null
    )
}

/**
 * Host hook for Agent prompt execution. The notification action is built in;
 * prompt execution is deliberately injected so the scheduler never depends
 * on an Activity or a particular LLM provider.
 */
fun interface AgentTaskPromptExecutor {
    suspend fun execute(task: AgentTask): AgentTaskActionExecutionResult
}

/**
 * Host composition hook. A host provides the Runtime-backed prompt executor
 * used by [AgentTaskJobService] without changing the task Tool module.
 */
fun interface AgentTaskRuntimeOwner {
    fun createAgentTaskRuntime(context: Context): AndroidAgentTaskRuntime
}

class AndroidAgentTaskRuntime(
    context: Context,
    private val store: AgentTaskStore = AndroidAgentTaskStore(context),
    private val scheduler: AgentTaskScheduler = AlarmManagerAgentTaskScheduler(context),
    private val notificationSink: AgentTaskNotificationSink = DefaultAgentTaskNotificationSink,
    private val promptExecutor: AgentTaskPromptExecutor? = null,
    private val clock: AgentTaskClock = SystemAgentTaskClock
) {
    private val appContext = context.applicationContext

    suspend fun handle(
        taskId: String,
        reschedule: Boolean = true
    ): AgentTaskActionExecutionResult = PROCESS_HANDLE_LOCK.withLock {
        val task = store.get(taskId)
            ?: return@withLock AgentTaskActionExecutionResult(false, "任务不存在：$taskId")
        if (task.status != AgentTaskStatus.SCHEDULED) {
            return@withLock AgentTaskActionExecutionResult(false, "任务当前状态不可执行：${task.status}")
        }

        val now = clock.nowMillis()
        val nextRunAt = task.nextRunAtMillis
            ?: run {
                if (reschedule) scheduler.cancel(taskId)
                return@withLock AgentTaskActionExecutionResult(false, "任务没有下一次执行时间，已取消无效调度。")
            }
        if (nextRunAt > now + EARLY_ALARM_GRACE_MILLIS) {
            if (reschedule) scheduler.schedule(task)
            return@withLock AgentTaskActionExecutionResult(false, "任务尚未到期，已重新安排。")
        }

        var notifySuccessfulPrompt = false
        var deliveryFailed = false
        val result = try {
            when (val action = task.action) {
                is AgentTaskAction.NotifyUser -> {
                    if (notificationSink.publish(appContext, task, action.message)) {
                        AgentTaskActionExecutionResult(true, action.message)
                    } else {
                        deliveryFailed = true
                        AgentTaskActionExecutionResult(
                            false,
                            "通知未发送：请授予通知权限后重试。"
                        )
                    }
                }

                is AgentTaskAction.RunAgentPrompt -> {
                    val execution = promptExecutor?.execute(task)
                        ?: AgentTaskActionExecutionResult(
                            false,
                            "当前宿主尚未安装后台 Agent Prompt 执行器。"
                        )
                    notifySuccessfulPrompt = execution.success &&
                        action.notifyPolicy == com.ugk.pi.android.AgentTaskNotifyPolicy.ALWAYS_NOTIFY
                    execution
                }
            }
        } catch (error: CancellationException) {
            // JobService may be stopped by the OS. Keep the task scheduled so
            // JobScheduler can retry it instead of turning cancellation into a
            // terminal FAILED task.
            throw error
        } catch (error: Throwable) {
            AgentTaskActionExecutionResult(false, "定时任务执行失败，请稍后重试。")
        }

        // A denied notification is a delivery problem, not a task failure:
        // repeating tasks must survive it and advance to the next run.
        val updated = if (deliveryFailed) {
            task.afterDeliveryFailure(now)
        } else {
            task.afterExecution(now, result.success)
        }
        store.upsert(updated)
        if (reschedule) {
            if (updated.status == AgentTaskStatus.SCHEDULED) {
                scheduler.schedule(updated)
            } else if (task.action !is AgentTaskAction.RunAgentPrompt) {
                // A JobService's current job is consumed by jobFinished().
                // Canceling that same job from inside handle() can trigger
                // onStopJob while the result is being committed.
                scheduler.cancel(updated.id)
            }
        }
        if (!result.success || notifySuccessfulPrompt) {
            notificationSink.publish(appContext, task, result.message)
        }
        result
    }

    suspend fun restoreScheduledTasks() {
        // The boot restore may run while a firing alarm handles the same
        // task on another runtime instance; sharing the handle lock keeps the
        // restore from re-arming stale snapshots.
        PROCESS_HANDLE_LOCK.withLock {
            store.list()
                .filter { it.status == AgentTaskStatus.SCHEDULED && it.nextRunAtMillis != null }
                .forEach { scheduler.schedule(it) }
        }
    }

    private companion object {
        // Alarm broadcasts and JobService entries each build a fresh runtime
        // instance (see [taskRuntime]); mutual exclusion for the
        // check-then-act execution transition must therefore be process-wide
        // instead of per-instance.
        val PROCESS_HANDLE_LOCK = Mutex()
        const val EARLY_ALARM_GRACE_MILLIS = 5_000L
    }
}

/**
 * Executes RUN_AGENT_PROMPT outside the Activity process lifecycle. The host
 * supplies the actual [AgentRuntime] through [AgentTaskRuntimeOwner], keeping
 * this SDK module independent from a concrete app, provider, or UI.
 */
class AgentTaskJobService : android.app.job.JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val stoppedJobIds = ConcurrentHashMap.newKeySet<Int>()

    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        val taskId = params.extras.getString(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: return false
        activeJobs[params.jobId]?.cancel()
        stoppedJobIds.remove(params.jobId)

        var runtime: AndroidAgentTaskRuntime? = null
        lateinit var job: Job
        job = serviceScope.launch {
            var shouldReschedule = false
            try {
                runtime = taskRuntime(applicationContext)
                // The current JobInfo must be finished before the repeating
                // task's next JobInfo is scheduled. Otherwise replacing the
                // same stable job id from inside handle() can stop itself.
                runtime?.handle(taskId, reschedule = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // A host composition error should not silently lose a durable
                // task. Leave its record scheduled and ask JobScheduler to try
                // again; normal task failures are converted to FAILED by the
                // runtime before reaching this branch.
                shouldReschedule = true
            } finally {
                activeJobs.remove(params.jobId, job)
                if (!stoppedJobIds.remove(params.jobId)) {
                    finishJob(params, shouldReschedule, runtime)
                }
            }
        }
        activeJobs[params.jobId] = job
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters): Boolean {
        stoppedJobIds += params.jobId
        val job = activeJobs.remove(params.jobId)
        if (job == null) {
            stoppedJobIds.remove(params.jobId)
            return false
        }
        job.cancel(CancellationException("Android stopped scheduled Agent task."))
        // The task remains SCHEDULED when cancellation propagates through
        // AndroidAgentTaskRuntime, so a true result safely requests a retry.
        return true
    }

    override fun onDestroy() {
        stoppedJobIds.addAll(activeJobs.keys)
        serviceScope.cancel()
        activeJobs.clear()
        super.onDestroy()
    }

    private fun finishJob(
        params: android.app.job.JobParameters,
        reschedule: Boolean,
        runtime: AndroidAgentTaskRuntime?
    ) {
        // Restore the next occurrence while JobService is still alive. Calling
        // jobFinished first lets Android tear down this service before a
        // repeating task can install its next JobInfo.
        serviceScope.launch {
            var shouldRetry = reschedule
            if (!reschedule && runtime != null) {
                runCatching { runtime.restoreScheduledTasks() }
                    .onFailure { shouldRetry = true }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                jobFinished(params, shouldRetry)
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}

class AgentTaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> runAsync {
                taskRuntime(appContext).restoreScheduledTasks()
            }

            ACTION_FIRE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
                runAsync {
                    taskRuntime(appContext).handle(taskId)
                }
            }
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runReceiverTask(block)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.ugk.pi.task.runtime.action.FIRE"
        const val EXTRA_TASK_ID = "taskId"

        fun fireIntent(context: Context, taskId: String): Intent = Intent(
            context,
            AgentTaskAlarmReceiver::class.java
        ).apply {
            action = ACTION_FIRE
            data = Uri.parse("ugk-agent-task://task/${Uri.encode(taskId)}")
            putExtra(EXTRA_TASK_ID, taskId)
        }
    }
}

internal fun taskRuntime(context: Context): AndroidAgentTaskRuntime {
    val owner = context.applicationContext as? AgentTaskRuntimeOwner
    return owner?.createAgentTaskRuntime(context) ?: AndroidAgentTaskRuntime(context)
}

/**
 * Broadcast coroutines must not let an unexpected error escape to the
 * process: an uncaught throwable inside goAsync work (for example the boot
 * restore failing JobScheduler's RESULT_SUCCESS check) crashes the whole
 * app. Structured cancellation still propagates so the receiver scope stays
 * cooperative.
 */
internal suspend fun runReceiverTask(block: suspend () -> Unit) {
    try {
        block()
    } catch (expected: CancellationException) {
        throw expected
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // Swallowed on purpose: a broadcast has no caller to report to, and
        // losing the process is worse than losing one delivery.
    }
}
