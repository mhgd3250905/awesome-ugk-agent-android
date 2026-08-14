# 高影响操作确认票据契约

更新时间：2026-08-14

本文是 SDK-OPT-008 的协议设计结果。它先固化确认边界，再进入 Core、System、Terminal 和 Demo 的一次性实现；本文件本身不改变运行时行为。

## 1. 为什么不能继续只使用 selectedButtonId

当前确认结果只包含 `selectedButtonId`。它能够表达“用户按了哪个按钮”，但不能表达该选择授权了哪个 Tool、哪一组输入，也不能阻止同一确认结果被错误地套用到另一个高影响操作。

尤其是 Agent 的确认调用和目标 Tool 调用通常分属两个模型循环，确认发生时目标 Tool 还没有执行。因此不能依赖“下一次 Tool 是什么”来补绑定，目标必须进入确认请求本身。

## 2. 确认请求与票据结构

确认 Tool 的输入增加目标对象：

```json
{
  "title": "允许执行终端命令？",
  "message": "将执行用户指定的命令。",
  "buttons": [
    {"id": "confirm", "label": "允许"},
    {"id": "cancel", "label": "取消"}
  ],
  "target": {
    "toolName": "terminal_bash_execute",
    "input": {"script": "printf 'hello\\n'"}
  }
}
```

确认成功后的结果保留 `selectedButtonId`，并新增一次性票据：

```json
{
  "selectedButtonId": "confirm",
  "ticket": {
    "version": 1,
    "sessionId": "session-1",
    "toolName": "terminal_bash_execute",
    "inputFingerprint": "sha256:...",
    "nonce": "base64url-without-padding",
    "issuedAtEpochMillis": 0,
    "expiresAtEpochMillis": 120000
  }
}
```

票据不携带原始输入，只携带输入摘要；原始目标输入仍保留在确认请求中供宿主展示和生成摘要。

## 3. 绑定与摘要规则

- `sessionId` 必须等于执行目标 Tool 时的 `ToolExecutionContext.sessionId`。
- `toolName` 必须等于当前目标 `ToolCall.name`，按大小写敏感的完整字符串比较。
- `inputFingerprint` 为目标 `ToolCall.input` 的规范 JSON UTF-8 字节计算 SHA-256，表示为小写十六进制并加 `sha256:` 前缀。
- 规范 JSON 使用版本化规则 `canonical-json-v1`：对象键按 Unicode 码点升序排列；数组保持原顺序；字符串使用标准 JSON 转义；`true`、`false`、`null` 使用固定字面量；数字必须是有限 JSON number，并规范化 `-0`、前导零和指数表示。无法规范化的输入拒绝生成票据。
- 未来如果改变规范化算法，必须提升票据 `version`，不能静默改变同一输入的摘要。

确认 Tool 和受保护 Tool 必须使用同一套摘要实现，不能分别拼接字符串或依赖 Kotlin `JsonObject` 的当前迭代顺序。

## 4. 有效期、一次性和失败语义

- `nonce` 使用宿主运行环境的密码学安全随机源生成，至少 128 bit；它不是业务输入，也不能由模型指定。
- 默认票据有效期为 120 秒；`now >= expiresAtEpochMillis` 即过期。时钟由 Core 注入，便于测试。
- 受保护 Tool 只有在其 `priorMessages` 的最后一条 ToolResult 是本次确认结果、其后至多只有一个包含当前完整 ToolCall 的 Assistant(tool-call) 外壳、按钮属于允许集合、票据未过期且所有绑定字段匹配时才执行。该 Assistant 外壳是 Runtime 的消息封装，不代表新的执行；User/System 消息或任何其他 ToolResult 出现在确认之后都必须拒绝。
- 目标 Tool 执行成功、失败或被拒绝后，确认结果不再是下一次 Tool 的最近 ToolResult；下一次尝试必须重新确认。这是 v1 的“紧邻结果一次性”语义。
- 不匹配、缺字段、JSON 非法、过期、拒绝按钮、不同 Session 或重复使用均 fail-closed，不调用 delegate。
- v1 不宣称对宿主手工伪造的 `priorMessages` 提供持久化防重放能力；如果未来支持跨进程/排队确认，必须增加共享的 TicketStore，并把消费状态纳入新的协议版本。

## 5. 旁路与兼容策略

- `shouldBypassConfirmation` 仍表示宿主显式启用的 full authorization 策略；旁路不要求、不校验 ticket。若宿主仍调用确认 Tool，确认 Tool 可能按 target 返回普通 ticket，但受保护 Tool 的旁路路径不会读取它；生命周期和 UI 说明仍由宿主负责。
- 旧的仅返回 `selectedButtonId` 的确认结果可以继续被非受保护的确认调用读取，但受保护 Tool 默认拒绝无绑定票据的结果。
- 旧的 `UserConfirmationDialogRequest(title, message, buttons)` 源码调用可以保留迁移期兼容构造，但没有 `target` 时不能产生可执行的受保护票据。
- Demo 的旧 UI/API 不需要立即删除；迁移时必须在确认 UI 中展示目标 Tool 和目标输入摘要，并更新 Agent instructions 让模型在每次高影响操作前提交完整 target。

## 6. 最小实现范围与验收矩阵

下一步实现应只涉及：

- Core：票据模型、规范 JSON 摘要、确认 Tool 生成票据、受保护 Tool 校验票据。
- System/Terminal：创建共享的确认请求目标，并更新 instructions/Tool schema；不改变工具业务执行逻辑。
- Demo：把目标信息传给 Activity/overlay presenter，并保留 full authorization 旁路。

必须覆盖的测试：

- 同一目标 Tool 与同一输入成功；Tool 名称、输入字段、对象键顺序变化分别失败。
- 不同 Session、过期票据、拒绝按钮、缺少 ticket、非法 ticket 分别失败。
- 同一确认结果执行一次后再次尝试失败；不同目标不能复用。
- full authorization 显式开启时仍可执行，受保护 Tool 不要求或校验 ticket；是否调用确认 Tool 及其返回值由宿主实现决定。
- Activity 重建/悬浮窗切换期间确认仍能完成或安全取消。
- 真机上至少验证一次 Terminal 命令确认、一次 Screen 动作确认、一次取消和一次过期/重试路径。

本契约通过后才进入跨模块实现；实现期间若发现必须引入持久化 TicketStore、Runtime Coordinator 或新的 runId，需拆成独立决策，不在本步隐式扩大范围。
