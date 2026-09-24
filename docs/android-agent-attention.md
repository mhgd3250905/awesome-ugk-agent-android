# Android Agent 消息与悬浮提醒

更新时间：2026-09-25

`pi-attention-skill-android` 是可选的 Android AAR。它让宿主 App 给 Agent 注册两种即时展示能力：系统通知，以及由宿主实现的重要消息展示。它不创建定时器或后台服务。延时任务到点后仍由原会话继续运行，Agent 可在该次运行中调用本模块的工具。

## 能力边界

| 工具 | 行为 | 成功状态 |
|---|---|---|
| `agent_send_notification` | 在宿主固定渠道立即发送一条通知 | `notification=posted` |
| `agent_show_urgent_message` | 先发送同一内容的通知，再请求宿主在屏幕上展示重要消息 | `notification=posted; overlay=shown` |

第二个工具仅在宿主提供 `UrgentMessagePresenter` 时注册；只有宿主实现 `InteractiveUrgentMessagePresenter` 标记接口时，工具才向 Agent 提供按钮与表单参数。两条路径分别返回状态；任一路径失败时 Tool 的 `isError=true`，但另一条路径仍可能成功。通知状态为 `posted` 只表示 Android 接受了投递，不保证横幅、声音或锁屏展示。悬浮展示状态为 `shown` 表示宿主窗口当时已成功显示，不保证进程死亡后仍存在。

通知失败状态包括 `permission_denied`、`disabled`、`channel_blocked`、`failed`；悬浮展示包括 `permission_denied`、`busy`、`unavailable`、`failed`。SDK 不会自行弹出权限申请界面，也不会把请求展示误报为已经展示。

## Agent 如何知道和选择

`AgentAttentionPlugin` 同时注册两个实际工具、描述使用场景的 `AndroidSkill`，以及每次模型请求都可见的简短能力说明。Agent 根据用户目的和当前情境决定是否通知：普通通知适合用户明确要求获知结果、或后台任务完成需要提醒；全屏重要提醒只用于用户合理预期被打断的时间敏感信息。重要悬浮工具已尝试发送同内容通知，不能为同一事件再调用普通通知工具。前台对话的普通答复通常不需要重复发通知。

`agent_show_urgent_message` 必填 `title`、`body`、`reason`。Agent 还可选 `accent`（`amber`、`green`、`blue`、`red`）和有序 `blocks`（最多 8 个 `heading`、`paragraph`、`callout`、`bullet` 元素，各元素为最多 240 字的纯文本）。`body` 用作通知摘要和无元素时的悬浮展示兜底；给出 `blocks` 时由宿主按顺序渲染这些元素。颜色与元素结构由 Agent 选择，实际 View、可滚动布局和固定关闭入口由宿主掌控，不接受任意 HTML、脚本、尺寸或屏幕坐标。

交互宿主还允许 `actions`（最多 3 个真实按钮，各有唯一的 ASCII `id` 和不超过 40 字的 `label`）以及一个可选 `form`（`id`、`label`、可选 `placeholder`、`submitLabel`）。Demo 的表单是单行纯文本，提交值最多 500 字；不用于密码或其他秘密。模型只能描述控件，不能指定直接执行的代码。展示工具立即返回 `presentationId` 和状态；用户点击或提交后，宿主校验该控件属于当前展示并只接受一次，再以 `SDK_EVENT` 新回合送回原会话。关闭按钮只关闭界面，不产生“完成”事件，也不让原 Tool 等待点击。

Skill 的触发词只控制详细说明何时补充到上下文，不在宿主端判断用户意图或直接执行通知；两个工具和简短能力说明始终提供给 Agent。工具立即执行，不负责创建未来定时任务；延时任务到点后，Agent 可按原请求决定是否使用提醒能力。实际投递与展示状态以工具结果为准。

## 外部 Android 宿主接入

当前本地开发 publication 坐标是 `com.ugk.pi:pi-attention-skill-android:0.3.0`，POM 声明了对 `com.ugk.pi:ugk-pi-android:0.1.0` 的依赖。`0.3.0` 增加可选交互控件、宿主 opt-in 接口与工具生成的展示绑定；不接交互的旧宿主继续使用 `UrgentMessagePresenter`。构建并发布到本机 Maven 仓库：

```powershell
.\gradlew.bat :ugk-pi-android:publishReleasePublicationToMavenLocal :pi-attention-skill-android:publishReleasePublicationToMavenLocal --console=plain
```

外部宿主在仓库中启用 `mavenLocal()`，然后声明：

```kotlin
dependencies {
    implementation("com.ugk.pi:pi-attention-skill-android:0.3.0")
}
```

宿主提供适用于通知状态栏的小图标；需要系统通知时在应用 Manifest 声明 `POST_NOTIFICATIONS`，并在 Android 13+ 自行引导用户授予运行时权限。需要跨 App 悬浮展示时，宿主另外声明并引导用户授予 `SYSTEM_ALERT_WINDOW`。只接通知时无需悬浮窗权限。

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

在宿主的 Runtime 组合根注册插件：

```kotlin
val publisher = AndroidNotificationPublisher(
    applicationContext,
    AgentNotificationConfig(
        channelId = "my_agent_alerts_high_v1",
        channelName = "Agent 醒目提醒",
        smallIconResId = R.drawable.ic_stat_agent,
        importance = NotificationManager.IMPORTANCE_HIGH
    )
)
val presenter = UrgentMessagePresenter { message ->
    // 宿主在主线程显示自己的界面，检查悬浮权限和实际 addView 结果。
    // 不能显示时返回 PERMISSION_DENIED / BUSY / UNAVAILABLE / FAILED。
    UrgentPresentation(UrgentPresentationStatus.UNAVAILABLE)
}
val runtime = AgentRuntime.Builder()
    .llmProvider(provider)
    .register(AgentAttentionPlugin(publisher, presenter))
    .build()
```

宿主可省略 `presenter`，只注册普通通知工具。要开放交互参数，宿主实现 `InteractiveUrgentMessagePresenter`，并在其 UI 中把点击/提交事件送回自己的 Agent 会话。SDK 在 `UrgentMessage.binding` 提供不能由模型指定的 `presentationId`、原 `sessionId`；宿主应校验绑定、控件 ID、一次性提交和当前会话归属，当前回合仍运行时先排队，之后以 `AgentRunInput(source = SDK_EVENT, taskId = presentationId)` 启动新回合。仅实现普通 `UrgentMessagePresenter` 时不应渲染无法回传的假按钮。

`Demo` 的适配器位于 `DemoUrgentMessagePresenter.kt`：`AgentFloatingWindow` 暂时撤下原悬浮球/对话框，使用同一个进程级控制器展示 `UrgentTakeoverView` 的整屏画布；右上角关闭和底部“打开对话”始终由 App 提供。`DemoUrgentInteractionDispatcher` 把已接受的操作绑定回原会话，忙时放进有界进程内队列，到空闲后顺序启动 `SDK_EVENT` 回合；事件与结果写入主对话。用户关闭后按所在前后台状态恢复此前的普通悬浮表面。已有整屏提醒、屏幕自动化或用户确认占用窗口时返回 `busy`；后两者开始时会撤下整屏提醒，通知仍留在系统通知栏。宿主在前台和后台都可以尝试展示；进程被系统结束后，未处理的内存队列不会自动补发。

## Android 行为

- Android 8+ 的通知必须使用 channel；channel 首次创建后的重要度由用户控制，代码不能覆盖用户设置。模块使用宿主给定的固定渠道，默认重要度是 `DEFAULT`；宿主可通过 `AgentNotificationConfig.importance` 选择重要度。Demo 改用新的 `ugk_agent_alerts_high_v1` 渠道和 `HIGH` 重要度，以提高横幅出现的机会；既有 `ugk_agent_messages` 渠道不修改、不清理。是否显示横幅仍由系统、用户渠道设置、勿扰模式等决定，`posted` 不表示横幅已显示。[Android 通知渠道](https://developer.android.com/develop/ui/compose/notifications/channels)
- Android 13+ 普通通知需要 `POST_NOTIFICATIONS` 运行时权限。[Android 通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- 跨 App 展示由宿主使用 `SYSTEM_ALERT_WINDOW` 和 `TYPE_APPLICATION_OVERLAY` 等 Android 接口完成；系统可能调整窗口可见性，不能保证覆盖锁屏或系统关键界面。[悬浮窗权限](https://developer.android.com/reference/android/Manifest.permission)、[窗口类型](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- 表单需要可获得输入法焦点的悬浮窗。Demo 只在带表单时移除 `FLAG_NOT_FOCUSABLE`，并随输入法 inset 调整内容；Android 和厂商仍可能改变键盘与悬浮窗的布局。[窗口焦点与输入法](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)、[输入法可见性](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility)
- 全屏 Intent 主要针对通话和闹钟，不作为通用 Agent 重要消息通道。[Android 14 全屏通知限制](https://developer.android.com/about/versions/14/behavior-changes-14)

本模块不依赖 `ugk-agent-task-runtime-android`，因此外部消费者不会因接入通知而得到它的 `JobService`、广播接收器或开机权限。
