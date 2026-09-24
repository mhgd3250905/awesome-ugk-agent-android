# Android Agent 消息与悬浮提醒

更新时间：2026-09-25

`pi-attention-skill-android` 是可选的 Android AAR。它让宿主 App 给 Agent 注册两种即时展示能力：系统通知，以及由宿主实现的重要消息展示。它不创建定时器、后台服务或第二个悬浮窗。延时任务到点后仍由原会话继续运行，Agent 可在该次运行中调用本模块的工具。

## 能力边界

| 工具 | 行为 | 成功状态 |
|---|---|---|
| `agent_send_notification` | 在宿主固定渠道立即发送一条通知 | `notification=posted` |
| `agent_show_urgent_message` | 先发送同一内容的通知，再请求宿主在屏幕上展示重要消息 | `notification=posted; overlay=shown` |

第二个工具仅在宿主提供 `UrgentMessagePresenter` 时注册。两条路径分别返回状态；任一路径失败时 Tool 的 `isError=true`，但另一条路径仍可能成功。通知状态为 `posted` 只表示 Android 接受了投递，不保证横幅、声音或锁屏展示。悬浮展示状态为 `shown` 表示宿主窗口当时已成功显示，不保证进程死亡后仍存在。

通知失败状态包括 `permission_denied`、`disabled`、`channel_blocked`、`failed`；悬浮展示包括 `permission_denied`、`busy`、`unavailable`、`failed`。SDK 不会自行弹出权限申请界面，也不会把请求展示误报为已经展示。

## Agent 如何知道和选择

`AgentAttentionPlugin` 同时注册两个实际工具、描述使用场景的 `AndroidSkill`，以及每次模型请求都可见的简短能力说明。Agent 根据用户目的和当前情境决定是否通知：普通通知适合用户明确要求获知结果、或后台任务完成需要提醒；重要悬浮卡只用于用户合理预期被打断的紧急信息。重要悬浮工具已尝试发送同内容通知，不能为同一事件再调用普通通知工具。前台对话的普通答复通常不需要重复发通知。

Skill 的触发词只控制详细说明何时补充到上下文，不在宿主端判断用户意图或直接执行通知；两个工具和简短能力说明始终提供给 Agent。工具立即执行，不负责创建未来定时任务；延时任务到点后，Agent 可按原请求决定是否使用提醒能力。实际投递与展示状态以工具结果为准。

## 外部 Android 宿主接入

当前本地开发 publication 坐标是 `com.ugk.pi:pi-attention-skill-android:0.1.0`，POM 声明了对 `com.ugk.pi:ugk-pi-android:0.1.0` 的依赖。构建并发布到本机 Maven 仓库：

```powershell
.\gradlew.bat :ugk-pi-android:publishReleasePublicationToMavenLocal :pi-attention-skill-android:publishReleasePublicationToMavenLocal --console=plain
```

外部宿主在仓库中启用 `mavenLocal()`，然后声明：

```kotlin
dependencies {
    implementation("com.ugk.pi:pi-attention-skill-android:0.1.0")
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
        channelId = "my_agent_messages",
        channelName = "Agent 消息",
        smallIconResId = R.drawable.ic_stat_agent
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

宿主可省略 `presenter`，只注册普通通知工具。若宿主已有悬浮窗，应把重要消息卡片放进同一个窗口，避免两个窗口互相遮挡。`Demo` 的适配器位于 `DemoUrgentMessagePresenter.kt`，重要卡片在 `AgentFloatingWindow` 中渲染；屏幕自动化或用户确认占用窗口时返回 `busy`。宿主在前台和后台都可以尝试展示；进程被系统结束后不会继续运行或补发。

## Android 行为

- Android 8+ 的通知必须使用 channel；channel 首次创建后的重要度由用户控制，代码不能覆盖用户设置。模块使用宿主给定的固定渠道和默认重要度，不允许模型动态创建渠道。[Android 通知渠道](https://developer.android.com/develop/ui/compose/notifications/channels)
- Android 13+ 普通通知需要 `POST_NOTIFICATIONS` 运行时权限。[Android 通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- 跨 App 展示由宿主使用 `SYSTEM_ALERT_WINDOW` 和 `TYPE_APPLICATION_OVERLAY` 等 Android 接口完成；系统可能调整窗口可见性，不能保证覆盖锁屏或系统关键界面。[悬浮窗权限](https://developer.android.com/reference/android/Manifest.permission)、[窗口类型](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- 全屏 Intent 主要针对通话和闹钟，不作为通用 Agent 重要消息通道。[Android 14 全屏通知限制](https://developer.android.com/about/versions/14/behavior-changes-14)

本模块不依赖 `ugk-agent-task-runtime-android`，因此外部消费者不会因接入通知而得到它的 `JobService`、广播接收器或开机权限。
