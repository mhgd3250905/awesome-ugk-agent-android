# SDK 优化推进台账

更新时间：2026-08-14

## 版本与推进规则

- 本台账记录 SDK 架构优化步骤，不替代 `demo-app` 的产品版本台账。
- 每一步使用独立编号，必须经过：范围确认、实现、主线程审核、相关验证、文档记录和 Git 逻辑提交。
- 未形成正式发布物前，不因内部修复擅自提升 `demo-app` 版本或 Maven Artifact 版本。
- 每一步只修改目标范围，保留工作树中已有的用户改动。

当前基线：`demo-app 0.2.1` / `versionCode 3`；SDK 当前仍处于持续开发阶段。

## SDK-OPT-001：ToolRegistry 重复 Tool ID 治理

状态：已实现，主线程复核通过

目标：避免多个 Plugin 注册同名 Tool 时静默覆盖，降低能力组合错误的排查成本。

实现范围：

- `ToolRegistry.register()` 在写入前检查同名 Tool。
- 重复注册抛出 `IllegalArgumentException`，原有 Tool 保留，不发生隐式替换。
- 保持现有 `register(): ToolRegistry` 方法签名和唯一注册行为不变。
- 增加首次注册、同实例重复、不同实例重复、不同名称和 Plugin 注册路径测试。

验证：

```powershell
.\gradlew.bat :ugk-pi-android:testDebugUnitTest `
  --tests com.ugk.pi.android.ToolRegistryTest `
  --tests com.ugk.pi.android.AgentCapabilityPluginTest `
  --console=plain
```

结果：通过；`git diff --check` 通过。

兼容性影响：此前依赖“后注册覆盖先注册”的错误配置将改为在注册时失败；当前没有提供隐式覆盖行为。Demo 版本和 SDK Maven 版本均未提升，本步骤不构成正式发布版本。

后续步骤：统一 Plugin `close()` 和取消契约，已由下方 `SDK-OPT-002` 承接；本条记录仍保留
`SDK-OPT-001` 的独立提交边界。

## SDK-OPT-002：统一 Plugin 取消与资源释放契约

状态：已实现并验证通过

目标：让宿主通过 `AgentRuntime` 统一取消和释放已注册 Plugin，避免宿主必须特殊识别
`TerminalAgentPlugin`。本步只处理生命周期转发，不引入 `AgentHostController`、`RuntimeLease`、
模块拆分或后台服务。

实现范围：

- `AgentCapabilityPlugin` 增加默认 no-op 的 `cancelAll(): Int` 和 `close()`，旧 Plugin 不需要立即实现新方法。
- `AgentRuntime.Builder` 记录通过 Builder 注册的 Plugin；直接构造 `AgentRuntime` 的旧路径保持不变。
- 增加 `AgentRuntime.cancelAllPlugins()`：Runtime 未关闭时转发到所有注册 Plugin；关闭后返回 `0`。
- 增加幂等的 `AgentRuntime.close()`：每个 Runtime 最多调用一次 Plugin `close()`，按逆注册顺序释放。
- `TerminalAgentPlugin` 显式实现统一契约，同时保留 `cancel(callId)`、`stopAllLocalHttpServers()` 等既有专用 API。
- Demo 的停止、Runtime 重建和 Activity 销毁改为操作 `AgentRuntime`，不再持有 Terminal Plugin 生命周期字段；重建前先停止当前任务。

生命周期语义：

- `cancelAllPlugins()` 可重复调用；每次只返回当次仍接受取消的工作数量。
- `close()` 在同一个 Runtime 内幂等；关闭后不再转发取消请求。
- Plugin 实现应让直接调用自身 `close()` 也保持幂等。
- `AgentRuntime` 不会自动取消正在运行的 Flow；宿主应先调用 `cancelAllPlugins()`，再调用 `close()`。

验证：

```powershell
.\gradlew.bat :ugk-pi-android:testDebugUnitTest `
  --tests com.ugk.pi.android.AgentCapabilityPluginTest `
  --tests com.ugk.pi.android.AgentRuntimeTest `
  --console=plain

.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-file-skill-android:testDebugUnitTest `
  :pi-schedule-skill-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :ugk-terminal-runtime-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  --console=plain
```

结果：两组命令均通过；`ugk-terminal-runtime-android:testDebugUnitTest` 当前为 `NO-SOURCE`；
`git diff --check` 通过。默认接口方法实际编译为 JVM default method。

兼容性影响：新增方法均提供默认实现，现有 Plugin 源码和已有唯一注册路径无需修改；新增
`AgentRuntime` 生命周期 API 不改变旧构造函数和既有 Tool/Plugin 名称。Demo 版本仍为
`0.2.1 / versionCode 3`，SDK Maven 版本未提升，本步不构成正式发布版本。

审核边界：本步没有解决同一 Plugin 实例被多个 Runtime 共享时的所有权治理，没有把 Runtime
关闭自动绑定到每个 `Flow` 的取消，也没有解决跨 Activity 重建时进程级 Coordinator 与旧 Runtime
的所有权迁移；这些保留为后续架构议题，不在当前稳定迭代扩大范围。

## SDK-OPT-003：ScreenAutomationBackend 演进接缝

状态：已审查，延后实施；无代码提交

审查结论：该方向架构上成立，但当前不适合直接落地。

延后原因：

- `ScreenReadUiTreeTool` 和 `ScreenPerformActionTool` 已包含工作树中尚未提交的稳定性修复。
- 当前最小 Backend 接缝仍需要同时改动两个 Tool 和 `MainActivity`，无法在本轮可靠地区分新改动与既有用户改动。
- 当前只有一个 Demo 宿主，尚未出现必须独立消费 Screen 能力的第二个真实宿主。
- 子线程试做的新增接口、Demo adapter、测试和接入 hunk 已全部撤销，未进入提交或版本发布。

后续触发条件：Screen Tool 当前行为先稳定，或出现第二个真实宿主/外部独立消费需求后，再以“先接口、后逐个 Tool 迁移”的顺序重新实施。重新启动前必须先建立可恢复的差异边界，并单独验证 UI 树 JSON、节点回收、动作错误和确认流程。

## SDK-OPT-004：高影响 Tool 确认票据绑定

状态：已审查，延后实施；无代码提交

审查结论：现有确认协议可以演进为绑定 `sessionId + toolName + inputFingerprint + nonce + expiresAt` 的一次性票据，但这不是完全透明的内部修复，而是一次增量协议变更。

延后原因：

- 需要同时改动 Core 确认 Tool、System/Terminal 注册说明、Demo presenter 调用夹具和相关仪器测试。
- 旧的“只看最近一次 `selectedButtonId`”行为不能继续作为受保护 Tool 的授权依据，否则绑定没有实际安全效果。
- 当前实现尚未完成全量 Core/Demo 回归、真机确认流程验证和完整文档记录，不适合在本轮半完成合入。
- 本轮子线程产生的票据实现、测试、提示文本和接入 hunk 已全部撤销，未进入提交或版本发布。

重新启动条件：先固定票据字段和 fingerprint 规范，再一次性完成 Core 单测、Demo 单测、确认取消/过期/重复使用测试和真机高影响操作验证；届时接受“旧 UI/API 保持、旧授权语义收紧”的兼容性边界。
