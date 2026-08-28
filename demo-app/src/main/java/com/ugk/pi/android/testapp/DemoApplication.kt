package com.ugk.pi.android.testapp

import android.app.Application
import android.content.Context
import com.ugk.pi.task.runtime.AgentTaskRuntimeOwner
import com.ugk.pi.task.runtime.AlarmManagerAgentTaskScheduler
import com.ugk.pi.task.runtime.AndroidAgentTaskRuntime
import com.ugk.pi.task.runtime.AndroidAgentTaskStore

/** Host composition entry point used when JobScheduler starts the app process. */
class DemoApplication : Application(), AgentTaskRuntimeOwner {
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
