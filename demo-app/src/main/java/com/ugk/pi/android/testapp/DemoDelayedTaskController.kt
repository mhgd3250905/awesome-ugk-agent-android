package com.ugk.pi.android.testapp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class DemoDelayedTask(
    val id: String,
    val conversationId: String,
    val sessionId: String,
    val instruction: String,
    val delaySeconds: Long,
    val repeating: Boolean = false
)

internal sealed interface DemoDelayedTaskState {
    data object Idle : DemoDelayedTaskState
    data class Proposed(val task: DemoDelayedTask) : DemoDelayedTaskState
    data class Waiting(
        val task: DemoDelayedTask,
        val deadlineElapsedMillis: Long,
        val deadlineWallMillis: Long,
        val completedRuns: Long = 0L,
        val latestResult: String? = null,
        val consecutiveFailedRounds: Int = 0
    ) : DemoDelayedTaskState
    data class Executing(
        val task: DemoDelayedTask,
        val completedRuns: Long = 0L,
        val consecutiveFailedRounds: Int = 0
    ) : DemoDelayedTaskState
}

/** How one round of a delayed task ended, as reported by the Agent turn owner. */
internal enum class DemoDelayedTaskRound {
    COMPLETED,
    FAILED
}

/** One process-owned delay slot. No platform alarm or independent Agent session is created. */
internal class DemoDelayedTaskController(
    context: Context,
    private val conversationRuntime: DemoConversationRuntime,
    private val onDue: (DemoDelayedTask) -> Unit
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var listenerOwner: Any? = null
    private var listener: ((DemoDelayedTaskState) -> Unit)? = null
    private var state: DemoDelayedTaskState = DemoDelayedTaskState.Idle

    init {
        // A previous process cannot continue its in-memory timer. Report the
        // interruption once; never turn this marker into a new scheduled run.
        val taskId = prefs.getString(KEY_TASK_ID, null)
        val conversationId = prefs.getString(KEY_CONVERSATION_ID, null)
        if (taskId != null && conversationId != null) {
            val instruction = prefs.getString(KEY_INSTRUCTION, "").orEmpty()
            val wasExecuting = prefs.getBoolean(KEY_EXECUTING, false)
            val repeating = prefs.getBoolean(KEY_REPEATING, false)
            val message = when {
                repeating && wasExecuting ->
                    "上次周期任务在应用进程结束时中断，本轮结果可能不完整；周期任务已停止：$instruction"
                repeating -> "上次周期任务因应用进程结束而中断，已停止后续执行：$instruction"
                wasExecuting -> "上次定时任务在应用进程结束时中断，执行结果可能不完整：$instruction"
                else -> "上次定时任务因应用进程结束而中断，尚未执行：$instruction"
            }
            runCatching {
                conversationRuntime.conversationStore.appendMessagesAndFlush(
                    conversationId,
                    listOf(DemoStoredMessage("assistant", message)),
                    activateConversation = false
                )
            }.onSuccess {
                clearMarker()
            }.onFailure {
                Log.w(TAG, "Unable to report interrupted delayed task", it)
            }
        }
    }

    fun snapshot(): DemoDelayedTaskState = state

    fun attach(owner: Any, onChanged: (DemoDelayedTaskState) -> Unit) {
        listenerOwner = owner
        listener = onChanged
        onChanged(state)
    }

    fun detach(owner: Any) {
        if (listenerOwner !== owner) return
        listenerOwner = null
        listener = null
    }

    suspend fun propose(
        sessionId: String,
        instruction: String,
        delaySeconds: Long,
        repeating: Boolean = false
    ): Result<DemoDelayedTask> = withContext(Dispatchers.Main.immediate) {
        val conversationId = conversationRuntime.activeConversationId
        when {
            delaySeconds !in 1L..86_400L || instruction.isBlank() || instruction.length > 2_000 ->
                Result.failure(IllegalArgumentException("定时任务时长或内容无效。"))
            state !is DemoDelayedTaskState.Idle ->
                Result.failure(IllegalStateException("已有一个待确认、等待中或执行中的定时任务，请先停止它。"))
            conversationId == null || conversationRuntime.session?.id != sessionId ->
                Result.failure(IllegalStateException("当前对话已切换，无法创建定时任务。"))
            else -> {
                val task = DemoDelayedTask(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    sessionId = sessionId,
                    instruction = instruction,
                    delaySeconds = delaySeconds,
                    repeating = repeating
                )
                publish(DemoDelayedTaskState.Proposed(task))
                Result.success(task)
            }
        }
    }

    /** The duration begins only after the user confirms the proposed task. */
    fun confirm(taskId: String): Boolean {
        val proposed = state as? DemoDelayedTaskState.Proposed ?: return false
        if (proposed.task.id != taskId) return false
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val deadlineElapsed = nowElapsed + proposed.task.delaySeconds * 1000L
        val deadlineWall = nowWall + proposed.task.delaySeconds * 1000L
        if (!prefs.edit()
                .putString(KEY_TASK_ID, proposed.task.id)
                .putString(KEY_CONVERSATION_ID, proposed.task.conversationId)
                .putString(KEY_INSTRUCTION, proposed.task.instruction)
                .putBoolean(KEY_REPEATING, proposed.task.repeating)
                .putBoolean(KEY_EXECUTING, false)
                .commit()
        ) return false
        val queued = conversationRuntime.runCoordinator.snapshot().queuedMessages
        if (queued > 0) {
            conversationRuntime.runCoordinator.clearQueue()
        }
        val task = proposed.task
        appendStatus(task, buildString {
            if (task.repeating) {
                append("已开启周期任务：首次在 ${task.delaySeconds} 秒后执行「${task.instruction}」，此后每轮结束再等待 ${task.delaySeconds} 秒，直到手动停止。")
            } else {
                append("已开启定时任务：${task.delaySeconds} 秒后执行「${task.instruction}」。")
            }
            if (queued > 0) append("原有 $queued 条排队消息已清空。")
        })
        val waiting = DemoDelayedTaskState.Waiting(task, deadlineElapsed, deadlineWall)
        publish(waiting)
        startTimer(waiting)
        return true
    }

    fun reject(taskId: String) {
        val proposed = state as? DemoDelayedTaskState.Proposed ?: return
        if (proposed.task.id != taskId) return
        appendStatus(proposed.task, "已取消定时任务：${proposed.task.instruction}")
        publish(DemoDelayedTaskState.Idle)
    }

    fun stop(): Boolean {
        val task = when (val current = state) {
            is DemoDelayedTaskState.Proposed -> current.task
            is DemoDelayedTaskState.Waiting -> current.task
            is DemoDelayedTaskState.Executing -> current.task
            DemoDelayedTaskState.Idle -> return false
        }
        timerJob?.cancel()
        timerJob = null
        if (state is DemoDelayedTaskState.Executing) {
            conversationRuntime.agentRuntime?.cancelAllPlugins()
            conversationRuntime.runCoordinator.stop()
        }
        clearMarker()
        appendStatus(task, "已停止定时任务：${task.instruction}")
        publish(DemoDelayedTaskState.Idle)
        return true
    }

    fun complete(
        taskId: String,
        latestResult: String? = null,
        round: DemoDelayedTaskRound = DemoDelayedTaskRound.COMPLETED
    ) {
        val executing = state as? DemoDelayedTaskState.Executing ?: return
        if (executing.task.id != taskId) return
        timerJob = null
        if (!executing.task.repeating) {
            clearMarker()
            publish(DemoDelayedTaskState.Idle)
            return
        }
        val failedRounds = if (round == DemoDelayedTaskRound.FAILED) {
            executing.consecutiveFailedRounds + 1
        } else {
            0
        }
        if (failedRounds >= MAX_CONSECUTIVE_FAILED_ROUNDS) {
            // A round that failed still costs a model request, and each failure
            // appends another "任务未完成" turn that pushes the reader's own
            // history out of the stored window. Repeating that forever turns a
            // broken key, an exhausted quota or a dead network into an endless
            // spend, so the loop stops on a bounded streak of failures and tells
            // the user why instead of silently restarting.
            appendStatus(
                executing.task,
                "周期任务已连续 $failedRounds 轮未完成，已停止：${executing.task.instruction}"
            )
            clearMarker()
            publish(DemoDelayedTaskState.Idle)
            return
        }
        if (!prefs.edit().putBoolean(KEY_EXECUTING, false).commit()) {
            appendStatus(executing.task, "周期任务状态保存失败，已停止后续执行：${executing.task.instruction}")
            clearMarker()
            publish(DemoDelayedTaskState.Idle)
            return
        }
        // Start the next full interval after this Agent turn has finished and
        // its result has been saved. Processing time never consumes it.
        val intervalMillis = executing.task.delaySeconds * 1_000L
        val nextElapsed = SystemClock.elapsedRealtime() + intervalMillis
        val nextWall = System.currentTimeMillis() + intervalMillis
        if (failedRounds > 0) {
            appendStatus(
                executing.task,
                "本轮周期任务未完成，第 $failedRounds/$MAX_CONSECUTIVE_FAILED_ROUNDS 次失败，" +
                    "${executing.task.delaySeconds} 秒后重试：${executing.task.instruction}"
            )
        }
        val waiting = DemoDelayedTaskState.Waiting(
            executing.task,
            nextElapsed,
            nextWall,
            executing.completedRuns + 1L,
            latestResult?.trim()?.takeIf { it.isNotEmpty() }?.let { result ->
                if (result.length > MAX_LATEST_RESULT_CHARS) {
                    result.take(MAX_LATEST_RESULT_CHARS) + "…（完整结果保存在对话中）"
                } else result
            },
            failedRounds
        )
        publish(waiting)
        startTimer(waiting)
    }

    fun fail(taskId: String, reason: String) {
        val executing = state as? DemoDelayedTaskState.Executing ?: return
        if (executing.task.id != taskId) return
        appendStatus(executing.task, reason)
        timerJob?.cancel()
        timerJob = null
        clearMarker()
        publish(DemoDelayedTaskState.Idle)
    }

    private fun startTimer(waiting: DemoDelayedTaskState.Waiting) {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay((waiting.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            // The proposal or previous timed turn must release the run gate.
            while (state == waiting && conversationRuntime.runCoordinator.isRunning()) {
                delay(50L)
            }
            if (state != waiting) return@launch
            if (!prefs.edit().putBoolean(KEY_EXECUTING, true).commit()) {
                appendStatus(waiting.task, "定时任务状态保存失败，已停止执行：${waiting.task.instruction}")
                clearMarker()
                publish(DemoDelayedTaskState.Idle)
                return@launch
            }
            publish(
                DemoDelayedTaskState.Executing(
                    waiting.task,
                    waiting.completedRuns,
                    waiting.consecutiveFailedRounds
                )
            )
            runCatching { onDue(waiting.task) }
                .onFailure {
                    fail(waiting.task.id, "定时任务启动失败：${it.message ?: "未知错误"}")
                }
        }
    }

    private fun publish(next: DemoDelayedTaskState) {
        state = next
        runCatching { listener?.invoke(next) }
    }

    private fun appendStatus(task: DemoDelayedTask, message: String) {
        runCatching {
            conversationRuntime.conversationStore.appendMessages(
                task.conversationId,
                listOf(DemoStoredMessage("assistant", message))
            )
        }.onFailure {
            Log.w(TAG, "Unable to write delayed task status ${task.id}", it)
        }
    }

    private fun clearMarker() {
        prefs.edit().clear().commit()
    }

    internal companion object {
        const val TAG = "DemoDelayedTask"
        const val PREFS_NAME = "demo_conversation_delay_task"
        const val KEY_TASK_ID = "task_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_INSTRUCTION = "instruction"
        const val KEY_REPEATING = "repeating"
        const val KEY_EXECUTING = "executing"
        const val MAX_LATEST_RESULT_CHARS = 2_000

        /** Consecutive failed rounds a repeating task tolerates before it stops. */
        const val MAX_CONSECUTIVE_FAILED_ROUNDS = 3
    }
}
