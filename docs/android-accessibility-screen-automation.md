# Android Accessibility Screen Automation

## 目标

`pi-system-skill-android` 将 Android `AccessibilityService` 的屏幕能力整理为一套可复用的 SDK Skill：

- 读取有界 UI 结构树和当前屏幕尺寸；
- 按 `text`、`content_desc`、`view_id`、`type` 查找可见控件；
- 识别控件能力、可用 action、状态和屏幕位置；
- 对节点执行 click、long click、scroll、focus、clear focus、set text；
- 在视觉后端可用时以当前屏幕截图作为主要观察，并执行有界 tap、long press、swipe；
- 按需读取 UI 结构树，补充文本、节点能力、输入状态、滚动容器和目标消歧信息；
- 对没有视觉后端的宿主继续使用 UI 结构树工作流；
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

使用默认视觉后端时，宿主的无障碍服务 XML 还必须声明 Android 30+ 截图能力：

```xml
<accessibility-service
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true" />
```

截图能力由用户在系统无障碍设置中授权；SDK 不会静默开启该权限。

## Tool 分层

| Tool | 类型 | 用途 |
|---|---|---|
| `screen_read_ui_tree` | 只读 | 返回当前可见窗口、节点、bounds、状态、action 和 `snapshotId` |
| `screen_find_ui_element` | 只读 | 按 partial/exact text、content description、viewId 或 type 返回紧凑候选集和 `snapshotId` |
| `screen_perform_action` | 高影响 | 使用 `snapshotId + nodeId` 执行节点 action |
| `screen_gesture` | 高影响 | 按当前屏幕尺寸执行 tap、long press 或方向 swipe |
| `screen_capture_visual` | 高影响 | 截取当前外部屏幕并把图片附加到紧邻的下一次模型请求 |
| `screen_visual_gesture` | 高影响 | 使用最新视觉观察 ID 和 0..1 归一化目标区域执行手势 |
| `screen_press_key` | 高影响 | 在 API 30+ 对 focused input 触发 IME `enter` |
| `screen_global_action` | 高影响 | 执行系统 back、home、recents、通知栏等动作 |

高影响 Tool 由 `UserConfirmationRequiredTool` 包装；确认必须绑定下一次调用的完整 Tool 名称和 JSON input。视觉截图即使本身不改变屏幕，也会把跨应用画面发送给配置的模型，因此同样需要确认。

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

When a semantic screen operation returns `success=false`, the result includes its
structured error code and a recovery hint; the next recovery step is a fresh
`screen_read_ui_tree` or `screen_find_ui_element` call. For
`screen_capture_visual` or `screen_visual_gesture`, follow the visual error code
and capture a fresh observation when required. In the demo host,
`terminal_bash_execute` is blocked during the screen workflow and returns
immediately without starting Bash, preventing terminal exploration from
replacing screen recovery.

## 视觉优先协议

1. 对支持视觉截图的后端，每个新的屏幕观察周期先调用 `screen_capture_visual`；操作后再获取新截图验证界面状态。模型从截图判断当前界面和可见目标。
2. 截图成功后，模型会收到图片和 `observationId`、包名、屏幕尺寸、旋转角度等元数据；图片不会写入 `AgentSession` 的持久化消息，只附加到紧邻的下一次模型请求。截图会发送给配置的模型，默认确认模式下必须按完整输入确认流程授权。
3. 按需调用 `screen_find_ui_element` 或 `screen_read_ui_tree` 提供辅助证据，例如精确文字、可编辑状态、支持的节点操作、可滚动容器或目标消歧。每次读取都会生成新的 `snapshotId`，节点操作只能使用同一结果中的最新 `snapshotId` 和 `nodeId`。
4. 对视觉识别出的坐标目标，模型返回截图对应的 `left/top/right/bottom` 归一化区域（每个值在 `0..1`），再调用 `screen_visual_gesture` 并原样提交最新 `observationId`。语义文字输入和节点操作继续通过结构树与 `screen_perform_action` 完成。
5. 后端会校验观察 ID、15 秒有效期、前台包名、屏幕尺寸、旋转和目标区域；点击/长按使用区域中心，方向滑动从区域中心开始。执行成功只表示 Android 接受了触摸流，仍必须获取新截图验证。
6. 截图不支持时直接使用结构树工作流；截图失败或超时最多重试一次，之后改用结构树。不要在截图失败时反复重试。

视觉流程不是所有场景的通用突破：Android 30 以下不支持该截图 API；`FLAG_SECURE`、DRM、黑屏/受保护内容可能无法捕获；动画、弹窗或页面切换可能使观察过期；模型也必须支持图片输入。视觉坐标不能替代无障碍节点提供的可靠文本输入。涉及支付、认证、删除等不可逆操作时仍必须让用户确认具体目标。

## 结构树辅助与节点操作协议

1. 调用 `get_android_accessibility_status`，只在 `readyForScreenAutomation=true` 时继续。
2. 视觉后端可用时先观察截图；只有需要语义/节点证据时再读取结构树。纯结构树后端使用 `screen_find_ui_element` 或 `screen_read_ui_tree` 观察界面。
3. 已知 selector 时使用 `screen_find_ui_element`，需要完整层级时使用 `screen_read_ui_tree`。从同一次结果中选择唯一的 `snapshotId` 和 `nodeId`，并检查 `enabled`、`visibleToUser`、`actions`、
   `clickable`、`scrollable`、`editable`、文本和 bounds。
4. `screen_perform_action` 必须同时提交该次结果中的原样 `snapshotId` 和 `nodeId`，不得猜路径或复用旧节点。
5. 任意新的 read/find 都会替换该 session 的最新 snapshot；滚动、点击、输入后必须重新 read/find 验证。
6. 收到 `STALE_SNAPSHOT`、`SNAPSHOT_REQUIRED`、`NODE_NOT_FOUND`、`TARGET_NOT_INTERACTABLE` 或
   `ACTION_NOT_SUPPORTED` 时停止重试旧目标，重新读取并重新选择。

`nodeId` 是窗口索引和子节点索引组成的严格路径，例如 `0.1.2`。Backend 不跨 Tool 调用保留
`AccessibilityNodeInfo`，动作时重新解析当前树，并校验 package、type、viewId、bounds、text 和
content description，避免界面变化后误操作其他节点。

## 辅助定位和滚动策略

- 视觉后端优先根据截图识别目标。需要消歧时，用唯一 `viewId`、文本/内容描述或 type 与邻近上下文补充判断；多个候选时不得猜测。
- 需要滚动时，可从结构树获取最近的 `scrollable=true` 容器并使用 `scroll_forward` / `scroll_backward`；若树未暴露可靠滚动节点，可依据新截图执行视觉 swipe。
  每次滚动后都获取新观察，再决定下一步。
- `truncated=true` 不是“目标不存在”的证明；可以缩小 selector、在上限内提高 `max_nodes`，或继续滚动。
- 视觉后端对截图目标使用 `screen_visual_gesture`；纯结构树后端只有在节点 action 不可用且坐标能从当前屏幕尺寸和可见 bounds 可靠确定时才使用 `screen_gesture`。不能假设 `1080x2400` 或点击未经验证的位置。
- `set_text` 缺少 `text` 时 fail-closed，不会把省略参数解释为清空输入框；提交输入前再按目标语义调用
  `screen_press_key(key="enter")`。

## 结构化错误

常用错误码包括：

`ACCESSIBILITY_UNAVAILABLE`、`INVALID_INPUT`、`SNAPSHOT_REQUIRED`、`STALE_SNAPSHOT`、`NODE_NOT_FOUND`、
`TARGET_NOT_INTERACTABLE`、`ACTION_NOT_SUPPORTED`、`ACTION_FAILED`、`GESTURE_REJECTED`、
`GESTURE_TIMEOUT`、`KEY_UNSUPPORTED`、`KEY_FAILED`、`GLOBAL_ACTION_UNSUPPORTED`、`GLOBAL_ACTION_FAILED`、
`VISUAL_SCREENSHOT_UNSUPPORTED`、`VISUAL_SCREENSHOT_FAILED`、`VISUAL_SCREENSHOT_TIMEOUT`、
`VISUAL_OBSERVATION_REQUIRED`、`VISUAL_OBSERVATION_STALE`、`VISUAL_TARGET_INVALID`。

Tool 返回 `success=false` 时，Agent 必须依据错误码恢复，不能仅凭计划中的 Tool call 宣称动作已经完成。

## 当前边界

- snapshot 只在当前进程内按 session 保留最新值，最多保留 16 个 session；没有跨进程 Coordinator、ticket store、
  `runId` 或屏幕录制。
- 视觉观察只在当前进程内按 session 保留最新元数据，最多保留 16 个 session，图片本身不在后端缓存；默认图片长边
  限制为 1280、JPEG quality 为 80，观察有效期为 15 秒。
- `screen_press_key` 在 Android API 30 以下不使用未经验证的坐标 fallback，而是返回 `KEY_UNSUPPORTED`。
- 无障碍服务、宿主 overlay 过滤和生命周期由宿主负责；SDK 只通过 `AccessibilityServiceProvider` 访问当前实例。
