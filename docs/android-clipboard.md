# Android 文本剪贴板 Tool/Skill

## 当前决策

剪贴板属于 Android 系统能力，已经作为 `pi-system-skill-android` 的内置
`Tool + AndroidSkill` 提供，不单独创建新的插件。宿主注册
`AndroidSystemAgentPlugin` 或 `AndroidAutomationAgentPlugin` 后会自动获得以下三个工具：

| Tool | 能力 | 默认保护 |
|---|---|---|
| `clipboard_read_text` | 读取当前主剪贴板的第一个纯文本项 | 精确确认；原文只供下一次模型请求使用 |
| `clipboard_write_text` | 写入指定纯文本 | 精确确认；`sensitive` 默认 `true` |
| `clipboard_clear` | 清空当前主剪贴板 | 精确确认 |

第一版只处理文本，不暴露图片、URI 或其他 `ClipData` 类型。写入剪贴板不等于向目标 App 粘贴；若要粘贴，仍需配合目标 App 的 `screen_*` Tool 完成可见 UI 操作。

## Android 兼容和系统限制

- 能力门槛是 Android 10（API 29）。项目模块的 `minSdk` 仍保持 24；API 28 及以下返回 `CLIPBOARD_UNSUPPORTED`，不会调用新能力。
- Android 10 起，后台应用读取剪贴板受到系统焦点策略限制；`getPrimaryClip()` 返回 `null` 时，工具报告 `CLIPBOARD_READ_UNAVAILABLE`，不能据此断言剪贴板为空。宿主应让当前应用获得输入焦点，或由默认 IME 执行需要的读取。
- 读取只取第一个剪贴板项的文本，默认最多向模型暴露 8,000 个字符，安全上限为 20,000 个字符；超出部分会标记 `truncated=true`。
- 写入文本上限为 20,000 个字符，标签上限为 100 个字符。工具成功表示 Android 接受了写入/清空请求，不表示另一个应用已经消费了内容。

相关平台依据：[`ClipboardManager`](https://developer.android.com/reference/android/content/ClipboardManager)、[Android 安全增强中的剪贴板访问规则](https://source.android.com/docs/security/enhancements)、[Secure clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling?hl=zh-cn)。

## 敏感数据边界

`clipboard_read_text` 的 Tool 结果和 `AgentEvent.ToolFinished` 只包含状态、长度、MIME 类型和错误码；原文放在 `ToolResult.transientModelContent`，由 `AgentRuntime` 仅附加到紧邻的下一次 `ModelRequest`，然后丢弃。原文不会写入 `AgentSession` 的持久化消息，也不会进入 demo 的过程摘要。

`clipboard_write_text` 默认将 `ClipDescription` 标记为敏感内容。只有用户明确要求时才应传 `sensitive=false`，并且仍需经过正常确认。读取、写入和清空在默认模式都必须由 `show_user_confirmation_dialog` 为紧邻的完整 Tool 输入签发匹配票据；全授权宿主可以跳过 UI，但不能绕过输入校验和长度限制。

读取失败时必须依据结构化错误码恢复，不得把“无焦点导致不可读”解释成“空剪贴板”，也不得通过 `terminal_bash_execute` 或 Android Shell 绕过系统策略。

## 宿主接入

无需新增权限。现有插件注册即可：

```kotlin
val plugin = AndroidAutomationAgentPlugin(
    context = context,
    confirmationPresenter = presenter,
    accessibilityServiceComponent = serviceComponent,
    accessibilityStateProvider = stateProvider
)
```

如果宿主只需要系统能力，也可以注册 `AndroidSystemAgentPlugin`；不要同时注册两个插件，否则会重复注册相同的剪贴板 Tool 和确认 Tool。

## 当前状态

本能力已归档到本地 `0.4.0`（`versionCode 5`）版本基线，并与视觉兜底共同使用 Git 标签 `demo-app-v0.4.0`。该标签和提交仅保存在本地，尚未推送远端。
