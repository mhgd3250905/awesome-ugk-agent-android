package com.ugk.pi.android.testapp

import android.app.Application
import android.util.Log
import com.ugk.pi.android.AgentTaskStatus
import com.ugk.pi.task.runtime.AlarmManagerAgentTaskScheduler
import com.ugk.pi.task.runtime.AndroidAgentTaskStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process owner for the single-conversation Demo. */
class DemoApplication : Application() {
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Remove credentials and diagnostics persisted by the retired Jev trial.
        deleteSharedPreferences("jev_screen_settings")
        File(filesDir, "jev-live-probe.json").delete()
        File(filesDir, "jev-live-request.json").delete()
        migrationScope.launch { retireLegacyScheduledTasks() }
    }

    val processScope: DemoProcessScope by lazy {
        DemoProcessScope.get(this)
    }

    private suspend fun retireLegacyScheduledTasks() {
        val store = AndroidAgentTaskStore(this)
        try {
            val pending = store.list()
                .filter { it.status == AgentTaskStatus.SCHEDULED || it.status == AgentTaskStatus.RUNNING }
            if (pending.isEmpty()) return
            val scheduler = AlarmManagerAgentTaskScheduler(this)
            val conversations = processScope.conversationRuntime.conversationStore
            pending.forEach { task ->
                try {
                    scheduler.cancel(task.id)
                    store.upsert(task.copy(
                        status = AgentTaskStatus.CANCELLED,
                        nextRunAtMillis = null,
                        updatedAtMillis = System.currentTimeMillis()
                    ))
                    conversations.appendMessagesAndFlush(
                        task.sessionId,
                        listOf(DemoStoredMessage(
                            "assistant",
                            "旧版后台定时任务已停止，请在当前对话重新创建：${task.title}"
                        )),
                        activateConversation = false
                    )
                } catch (error: Exception) {
                    Log.w("DemoApplication", "Unable to retire legacy task ${task.id}", error)
                }
            }
        } catch (error: Exception) {
            Log.w("DemoApplication", "Unable to inspect legacy scheduled tasks", error)
        }
    }
}
