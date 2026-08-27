# Android Accessibility Screen Automation

## 目标

`pi-system-skill-android` 将 Android `AccessibilityService` 的屏幕能力整理为一套可复用的 SDK Skill：

- 读取有界 UI 结构树和当前屏幕尺寸；
- 按 `text`、`content_desc`、`view_id`、`type` 查找可见控件；
- 识别控件能力、可用 action、状态和屏幕位置；
- 对节点执行 click、long click、scroll、focus、clear focus、set text；
- 在 UI 树不足时执行有界 tap、long press、swipe；
- 触发 focused input 的 IME `enter` action；
- 执行 back、home、recents、notifications、quick settings 等全局动作。

Skill 只描述和编排能力，不会静默授予无障碍权限。权限必须由用户在系统设置中开启。

## 宿主接入

完整宿主向 `AndroidAutomationAgentPlugin` 提供 `ScreenAutomationBackend`。默认 Android 实现只需要当前
`AccessibilityService` 提供器和宿主自己的包名：

```kotlin
AndroidAutomationAgentPlugin(
    context = applicationContext,
    confirmationPresenter = confirmationPresenter,
    accessibilityServiceComponent = serviceComponent,
    accessibilityStateProvider = accessibilityStateProvider,
    screenAutomationBackend = AccessibilityScreenAutomationBackend(
        serviceProvider = AccessibilityServiceProvider { MyAccessibilityService.instance },
        ownPackageName = applicationContext.packageName
    )
)
```

不传 `screenAutomationBackend` 的轻量宿主仍只获得 App 查询、启动和无障碍状态工具，不会意外获得屏幕控制工具。

## Tool 分层

| Tool | 类型 | 用途 |
|---|---|---|
| `screen_read_ui_tree` | 只读 | 返回当前可见窗口、节点、bounds、状态、action 和 `snapshotId` |
| `screen_find_ui_element` | 只读 | 按 partial/exact text、content description、viewId 或 type 返回紧凑候选集和 `snapshotId` |
| `screen_perform_action` | 高影响 | 使用 `snapshotId + nodeId` 执行节点 action |
| `screen_gesture` | 高影响 | 按当前屏幕尺寸执行 tap、long press 或方向 swipe |
| `screen_press_key` | 高影响 | 在 API 30+ 对 focused input 触发 IME `enter` |
| `screen_global_action` | 高影响 | 执行系统 back、home、recents、通知栏等动作 |

高影响 Tool 由 `UserConfirmationRequiredTool` 包装；确认必须绑定下一次调用的完整 Tool 名称和 JSON input。

## Full authorization mode

When the host explicitly enables full authorization, Runtime registration omits
`show_user_confirmation_dialog`, protected Tool descriptions and active Skill
methods stop asking for a confirmation round, and the host injects an explicit
session instruction telling the Agent to call the protected Tool directly.
This only skips the confirmation gate; Accessibility readiness, snapshot/node
validation, input validation, action support checks, and result verification
remain mandatory. The default mode still requires exact-input confirmation.

## Host overlay isolation

The default backend receives interactive accessibility windows from the host
service and excludes the host package before building a screen snapshot. If
only the host overlay is available, it returns `WINDOW_UNAVAILABLE` instead of
returning the overlay as a usable screen. IME actions likewise resolve a
focused input only from a non-host window. Hosts should still avoid placing a
touchable overlay over a coordinate target when using coordinate gestures;
semantic node actions are preferred because they do not depend on overlay
coverage.

## Screen workflow recovery and passive overlay

The demo host enters a passive overlay mode when an Android launch or screen
automation tool starts. The expanded overlay is collapsed and its expanded
window is marked `FLAG_NOT_FOCUSABLE`, so the target app keeps input focus.
Confirmation cards may temporarily restore focusability so the user can press
their buttons; the overlay is collapsed again before the protected action
continues.

When a screen operation returns `success=false`, the result includes its
structured error code and a recovery hint. The next recovery step is a fresh
`screen_read_ui_tree` or `screen_find_ui_element` call. In the demo host,
`terminal_bash_execute` is blocked during the screen workflow and returns
immediately without starting Bash, preventing terminal exploration from
replacing screen recovery.

## Snapshot-first 协议

1. 调用 `get_android_accessibility_status`，只在 `readyForScreenAutomation=true` 时继续。
2. 已知 selector 时优先使用 `screen_find_ui_element`，需要完整层级时使用 `screen_read_ui_tree`。
3. 从同一次结果中选择唯一的 `snapshotId` 和 `nodeId`，并检查 `enabled`、`visibleToUser`、`actions`、
   `clickable`、`scrollable`、`editable`、文本和 bounds。
4. `screen_perform_action` 必须同时提交该次结果中的原样 `snapshotId` 和 `nodeId`，不得猜路径或复用旧节点。
5. 任意新的 read/find 都会替换该 session 的最新 snapshot；滚动、点击、输入后必须重新 read/find 验证。
6. 收到 `STALE_SNAPSHOT`、`SNAPSHOT_REQUIRED`、`NODE_NOT_FOUND`、`TARGET_NOT_INTERACTABLE` 或
   `ACTION_NOT_SUPPORTED` 时停止重试旧目标，重新读取并重新选择。

`nodeId` 是窗口索引和子节点索引组成的严格路径，例如 `0.1.2`。Backend 不跨 Tool 调用保留
`AccessibilityNodeInfo`，动作时重新解析当前树，并校验 package、type、viewId、bounds、text 和
content description，避免界面变化后误操作其他节点。

## 选择和滚动策略

- 优先唯一 `viewId`，其次唯一文本/内容描述，再使用 type 与邻近上下文消歧；多个候选时不得猜测。
- 目标不在当前结果中时，先找 `scrollable=true` 的最近容器，使用 `scroll_forward` / `scroll_backward`，
  每次滚动后重新读取。
- `truncated=true` 不是“目标不存在”的证明；可以缩小 selector、在上限内提高 `max_nodes`，或继续滚动。
- 只有 UI 树无法暴露可靠目标时才使用 `screen_gesture`。坐标必须来自最新屏幕尺寸和可见 bounds，不能假设
  `1080x2400` 或点击未经验证的位置。
- `set_text` 缺少 `text` 时 fail-closed，不会把省略参数解释为清空输入框；提交输入前再按目标语义调用
  `screen_press_key(key="enter")`。

## 结构化错误

常用错误码包括：

`ACCESSIBILITY_UNAVAILABLE`、`INVALID_INPUT`、`SNAPSHOT_REQUIRED`、`STALE_SNAPSHOT`、`NODE_NOT_FOUND`、
`TARGET_NOT_INTERACTABLE`、`ACTION_NOT_SUPPORTED`、`ACTION_FAILED`、`GESTURE_REJECTED`、
`GESTURE_TIMEOUT`、`KEY_UNSUPPORTED`、`KEY_FAILED`、`GLOBAL_ACTION_UNSUPPORTED`、`GLOBAL_ACTION_FAILED`。

Tool 返回 `success=false` 时，Agent 必须依据错误码恢复，不能仅凭计划中的 Tool call 宣称动作已经完成。

## 当前边界

- snapshot 只在当前进程内按 session 保留最新值，最多保留 16 个 session；没有跨进程 Coordinator、ticket store、
  `runId` 或屏幕录制。
- `screen_press_key` 在 Android API 30 以下不使用未经验证的坐标 fallback，而是返回 `KEY_UNSUPPORTED`。
- 无障碍服务、宿主 overlay 过滤和生命周期由宿主负责；SDK 只通过 `AccessibilityServiceProvider` 访问当前实例。
