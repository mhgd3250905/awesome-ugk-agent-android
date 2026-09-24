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
    val delaySeconds: Long
)

internal sealed interface DemoDelayedTaskState {
    data object Idle : DemoDelayedTaskState
    data class Proposed(val task: DemoDelayedTask) : DemoDelayedTaskState
    data class Waiting(
        val task: DemoDelayedTask,
        val deadlineElapsedMillis: Long,
        val deadlineWallMillis: Long
    ) : DemoDelayedTaskState
    data class Executing(val task: DemoDelayedTask) : DemoDelayedTaskState
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
            val message = if (wasExecuting) {
                "上次定时任务在应用进程结束时中断，执行结果可能不完整：$instruction"
            } else {
                "上次定时任务因应用进程结束而中断，尚未执行：$instruction"
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
        delaySeconds: Long
    ): Result<DemoDelayedTask> = withContext(Dispatchers.Main.immediate) {
        val conversationId = conversationRuntime.activeConversationId
        when {
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
                    delaySeconds = delaySeconds
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
                .putBoolean(KEY_EXECUTING, false)
                .commit()
        ) return false
        val queued = conversationRuntime.runCoordinator.snapshot().queuedMessages
        if (queued > 0) {
            conversationRuntime.runCoordinator.clearQueue()
        }
        appendStatus(
            proposed.task,
            "已开启定时任务：${proposed.task.delaySeconds} 秒后执行「${proposed.task.instruction}」。" +
                if (queued > 0) "原有 $queued 条排队消息已清空。" else ""
        )
        publish(DemoDelayedTaskState.Waiting(proposed.task, deadlineElapsed, deadlineWall))
        timerJob = scope.launch {
            delay((deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            // The proposal tool ends the first Agent turn. Do not start the
            // timed turn until that session's run gate has been released.
            while (isWaitingFor(taskId) && conversationRuntime.runCoordinator.isRunning()) {
                delay(50L)
            }
            if (!isWaitingFor(taskId)) return@launch
            prefs.edit().putBoolean(KEY_EXECUTING, true).commit()
            publish(DemoDelayedTaskState.Executing(proposed.task))
            runCatching { onDue(proposed.task) }
                .onFailure { fail(taskId, "定时任务启动失败：${it.message ?: "未知错误"}") }
        }
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

    fun complete(taskId: String) {
        val executing = state as? DemoDelayedTaskState.Executing ?: return
        if (executing.task.id != taskId) return
        timerJob = null
        clearMarker()
        publish(DemoDelayedTaskState.Idle)
    }

    fun fail(taskId: String, reason: String) {
        val executing = state as? DemoDelayedTaskState.Executing ?: return
        if (executing.task.id != taskId) return
        appendStatus(executing.task, reason)
        complete(taskId)
    }

    private fun isWaitingFor(taskId: String): Boolean =
        (state as? DemoDelayedTaskState.Waiting)?.task?.id == taskId

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

    private companion object {
        const val TAG = "DemoDelayedTask"
        const val PREFS_NAME = "demo_conversation_delay_task"
        const val KEY_TASK_ID = "task_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_INSTRUCTION = "instruction"
        const val KEY_EXECUTING = "executing"
    }
}
