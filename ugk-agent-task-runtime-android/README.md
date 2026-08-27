# ugk-agent-task-runtime-android

Android Agent 定时任务的运行时适配层。

## 职责边界

- `pi-schedule-skill-android` 负责任务模型、创建/查询/更新/取消 Tool 以及 Skill 指令；它不依赖 Android 生命周期。
- 本模块负责 Android 持久化、`AlarmManager`/`JobScheduler` 调度、广播恢复、后台 JobService 和通知投递；它不依赖 `Activity`、具体 LLM Provider 或 Bash 进程。
- `AgentTaskPromptExecutor` 是宿主注入点。只有宿主明确提供后台 Prompt 执行器并在 `ScheduleTaskAgentPlugin` 中开启能力后，才允许创建 `RUN_AGENT_PROMPT` 任务。

## 当前第一版能力

- `ONE_SHOT` 和 `REPEATING_UNTIL` 任务；重复任务每次执行后重新计算下一次调度。
- `SharedPreferences + Kotlin Serialization` 的轻量持久化 Store，应用重启、设备开机和应用升级后恢复待执行任务。
- 使用普通的 `AlarmManager.setAndAllowWhileIdle`，时间是尽力而为，不申请精确闹钟权限，也不在进程内启动无限循环。
- `NOTIFY_USER` 由运行时直接生成 Android 通知。
- `RUN_AGENT_PROMPT` 由 `JobScheduler -> AgentTaskJobService -> AgentTaskPromptExecutor` 触发；JobService 不依赖 Activity，允许宿主恢复自己的 AgentRuntime/会话。

## 宿主接入

```kotlin
dependencies {
    implementation(project(":pi-schedule-skill-android"))
    implementation(project(":ugk-agent-task-runtime-android"))
}
```

注册控制面插件时注入同一份 Store 和 Scheduler：

```kotlin
val store = AndroidAgentTaskStore(context)
val scheduler = AlarmManagerAgentTaskScheduler(context)

ScheduleTaskAgentPlugin(
    store = store,
    scheduler = scheduler,
    supportsBackgroundPromptExecution = true
)

class MyApplication : Application(), AgentTaskRuntimeOwner {
    override fun createAgentTaskRuntime(context: Context): AndroidAgentTaskRuntime {
        return AndroidAgentTaskRuntime(
            context = context,
            store = AndroidAgentTaskStore(context),
            scheduler = AlarmManagerAgentTaskScheduler(context),
            promptExecutor = MyBackgroundPromptExecutor(context)
        )
    }
}
```

模块 Manifest 会合并 `RECEIVE_BOOT_COMPLETED`、应用升级恢复广播、`AgentTaskJobService`、定时任务接收器和通知权限声明；Android 13+ 宿主仍需要在运行时申请 `POST_NOTIFICATIONS`。

## 当前限制

本模块提供的是持久化时钟触发，不是事件订阅系统，也不保证在 Doze、厂商省电策略或进程被杀时精确到秒。`JobScheduler` 是系统管理的尽力而为执行窗口，宿主的 `AgentTaskPromptExecutor` 必须是有限时任务；长时间、精确时间或强重试语义需要后续单独设计。后台是否能读屏、启动第三方 App 或执行高影响动作仍取决于宿主服务状态和确认策略。
