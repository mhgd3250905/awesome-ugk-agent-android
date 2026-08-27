# Android Agent 定时任务

当前实现是一条可持久化的“控制面 + 运行时适配面”链路：到点后既可以发送通知，也可以真正唤醒一次独立的 Agent 对话回合。

## 架构

```text
用户自然语言
    ↓
pi-schedule-skill-android
    ├─ AgentTask 模型 / 状态机
    ├─ agent_task_create/list/get/update/cancel
    └─ AgentTaskStore + AgentTaskScheduler 接口
    ↓
ugk-agent-task-runtime-android
    ├─ AndroidAgentTaskStore（持久化）
    ├─ AlarmManagerAgentTaskScheduler
    │   ├─ NOTIFY_USER → AlarmManager → AgentTaskAlarmReceiver
    │   └─ RUN_AGENT_PROMPT → JobScheduler → AgentTaskJobService
    └─ AndroidAgentTaskRuntime（执行、状态落盘、下一次调度）
    ↓
动作端口
    ├─ NOTIFY_USER → Android Notification
    └─ RUN_AGENT_PROMPT → 宿主注入 AgentTaskPromptExecutor → AgentRuntime
```

控制面不持有 `Context`，运行时适配面不依赖 `Activity` 或具体模型供应商。宿主在 `Application` 实现 `AgentTaskRuntimeOwner`，由 `AgentTaskJobService` 在后台创建自己的 Runtime；因此定时执行不需要依赖前台 Activity。后续可以替换 Store（例如 SQLite）或 Scheduler，而不改变 Agent Tool 协议。

## 当前实际支持范围

Demo 当前已开启 `NOTIFY_USER` 和 `RUN_AGENT_PROMPT`：

- “10 分钟后提醒我休息”会创建一个 `ONE_SHOT` 任务，到期后发送 Android 通知。
- “10 分钟后检查微信是否有新消息”应创建 `RUN_AGENT_PROMPT`；到点后系统启动 `AgentTaskJobService`，恢复任务关联的会话，使用 `AgentRunSource.SCHEDULED_TASK` 调用 AgentRuntime 的完整模型/Tool 循环，并把用户任务和最终结果写回同一会话。
- `REPEATING_UNTIL` 会在每次到点处理后重新计算下一次执行时间，不在进程里维持常驻循环。
- 设备重启或应用升级后，广播接收器从持久化 Store 恢复 `SCHEDULED` 任务；Prompt 任务重新交给 `JobScheduler`。
- Android 13（API 33）及以上需要用户授予通知权限；闹钟使用普通非精确调度，可能受到 Doze 和小米系统省电策略影响。
- Prompt 任务要求有可用网络；没有网络时由 `JobScheduler` 等待可用网络，而不是由应用进程自建轮询线程。

Demo 的后台执行器与前台使用同一套 Provider、Android Automation、视觉屏幕和剪贴板 Tool 注册图，但不创建 Activity。后台没有交互式确认窗口：只读观察可以执行；启动 App、点击、手势、视觉手势、剪贴板写入/清空和终端等受保护动作，只有用户显式开启“全授权”后才允许自动执行，否则任务会安全失败并通知用户。

## 为什么 Prompt 任务使用 JobScheduler

Prompt 任务不是轻量广播回调，必须有系统提供的后台执行窗口。当前项目没有新增生产依赖的授权，因此使用 Android 平台自带的 `JobScheduler`，并声明网络约束；没有把 `WorkManager` 耦合进 Core 或 Skill。通知任务仍使用普通 `AlarmManager`，两条路径都隐藏在 `AgentTaskScheduler` 端口后。

Android 官方明确区分了精确闹钟、普通闹钟和后台服务启动限制（见 [Alarms](https://developer.android.com/develop/background-work/services/alarms) 和 [后台启动 Foreground Service 限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)）：普通非精确闹钟不能作为可靠的后台 Agent 执行承载；如果未来需要更长执行时间、强重试或更精确的触发保证，应单独引入前台服务/WorkManager 方案并重新评估权限、配额和电量影响。

## 不属于当前承诺的能力

- 不是微信等 App 的事件订阅；本版没有外部事件监听器。
- 不是每秒精确计时器；系统或厂商可能延迟广播。
- 不是回合外持续运行的无限循环；每次闹钟只启动一次有限动作。
- `NOTIFY_USER` 是系统通知，不等同于向聊天窗口追加消息，也不等同于自动向第三方 App 发消息。
- `RUN_AGENT_PROMPT` 是一次有限的 Agent 回合，不是事件监听器；重复任务是多个独立回合。
- Android 后台启动第三方 Activity 仍受系统限制；如果目标 App 没有处于可读的前台界面，任务可能只能报告“无法观察”，不能把一次启动调用当作成功。

## v0.5.0 验收记录

- 全工程 JVM 单元测试：198 个通过，0 个失败；`:demo-app:assembleDebug` 通过，APK 元数据为 `versionCode 6 / versionName 0.5.0`。
- APK 已安装并启动于第二台授权小米 `e0b93f2f`（型号 `2304FPN6DC`）；用户已完成一次性 `RUN_AGENT_PROMPT` 后台唤醒/读屏体验验证并反馈可用。
- 主目标小米 `QSG6Q8IFDMDELVGQ` 本轮离线；三星设备 `R5CRB11B2AW` 未操作。真机调试命令必须显式指定授权小米序列号。
- 本记录验证的是有限后台 Agent 回合，不代表精确定时、第三方事件订阅、常驻监听或自动启动/解锁微信已得到保证。

## 验收建议

1. 首次启动 Demo 时允许通知权限。
2. 对 Agent 说：“10 分钟后检查当前微信界面有没有优积可的新消息，有就告诉我。”
3. 让应用退到后台，使用 `agent_task_list` 或 `agent_task_get` 确认状态为 `SCHEDULED`。
4. 确保无障碍服务仍连接；如任务需要点击、输入或视觉手势，先在设置中显式开启全授权。
5. 到期后检查 Android 通知、同一会话中的追加回合，并确认任务变为 `COMPLETED`。
6. 再验证重复任务的下一次时间、取消任务，以及重启/升级后的恢复。
