package com.ugk.pi.android.testapp

import android.app.Application
import android.content.Context
import com.ugk.pi.task.runtime.AgentTaskRuntimeOwner
import com.ugk.pi.task.runtime.AlarmManagerAgentTaskScheduler
import com.ugk.pi.task.runtime.AndroidAgentTaskRuntime
import com.ugk.pi.task.runtime.AndroidAgentTaskStore
import java.io.File

/** Host composition entry point used when JobScheduler starts the app process. */
class DemoApplication : Application(), AgentTaskRuntimeOwner {
    override fun onCreate() {
        super.onCreate()
        // Remove credentials and diagnostics persisted by the retired Jev trial.
        deleteSharedPreferences("jev_screen_settings")
        File(filesDir, "jev-live-probe.json").delete()
        File(filesDir, "jev-live-request.json").delete()
    }

    val processScope: DemoProcessScope by lazy {
        DemoProcessScope.get(this)
    }

    override fun createAgentTaskRuntime(context: Context): AndroidAgentTaskRuntime {
        val appContext = context.applicationContext
        return AndroidAgentTaskRuntime(
            context = appContext,
            store = AndroidAgentTaskStore(appContext),
            scheduler = AlarmManagerAgentTaskScheduler(appContext),
            promptExecutor = DemoScheduledTaskPromptExecutor(appContext, processScope)
        )
    }
}
