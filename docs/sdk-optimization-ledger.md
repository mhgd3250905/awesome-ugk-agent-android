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

## SDK-OPT-005：AgentSession 并发运行门禁

状态：已实现并验证通过

目标：明确同一个 `AgentSession` 不允许多个 cold `Flow` 同时运行，避免并发读写
`session.messages`；取消或异常结束后必须释放运行占用，使下一次运行可以正常开始。

实现范围：

- `AgentSession` 增加非构造参数的内部 `Mutex`，不改变现有构造函数、`data class`
  equality 或 `messages` 数据结构。
- `AgentRuntime` 最深层 `run()` 在 Flow 收集时使用 `tryLock()`；同一 Session 已被占用时，
  使用现有 `AgentEvent.Failed` 返回明确结果，不新增 sealed event，不等待或排队第二次运行。
- 运行主体通过 `try/finally` 释放 Mutex，覆盖正常完成、Flow 取消、Provider/Tool 之外的异常路径。
- 未修改 `SessionStore`、Provider/Tool 协议、`AgentEvent` 定义、Demo UI 或
  `DemoAgentRunCoordinator`；Coordinator 仍保留自身的 UI 级 Job 门禁，Runtime 负责 SDK 级保护。

并发拒绝消息格式：

```text
AgentSession '<sessionId>' is already running.
```

验证：

```powershell
.\gradlew.bat :ugk-pi-android:testDebugUnitTest `
  --tests com.ugk.pi.android.AgentRuntimeTest `
  --console=plain

.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-file-skill-android:testDebugUnitTest `
  :pi-schedule-skill-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :ugk-terminal-runtime-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  :demo-app:testDebugUnitTest `
  --console=plain

.\gradlew.bat :demo-app:assembleDebug --console=plain
```

新增回归覆盖：

- 同一 Session 第二次并发运行返回 `AgentEvent.Failed`，且不追加第二条用户消息。
- 第一次运行取消后，同一 Session 可以再次完成运行。
- 运行主体发生未捕获异常后，同一 Session 可以再次完成运行。

结果：上述命令通过；`ugk-terminal-runtime-android:testDebugUnitTest` 仍为现有
`NO-SOURCE` 状态；`git diff --check` 通过。

兼容性影响：旧的唯一运行路径不变；新增的冲突路径从此前可能并发修改 Session，变为返回
`AgentEvent.Failed`。没有新增事件类型、没有改变公开构造函数、Demo 版本仍为
`0.2.1 / versionCode 3`，SDK Maven 版本未提升，本步骤不构成正式发布版本。

审核边界：本步只解决同一 `AgentSession` 的运行互斥和释放，不引入 `runId`、`RunHandle`、
SessionStore 重构、事件关联改造或跨 Runtime 的 Plugin 所有权治理。

## SDK-OPT-006：Core 外部消费者边界验收

状态：已实现，主线程审查通过并提交

目标：以最小、可复现的方式证明 `:ugk-pi-android` 可以独立生成 Release publication，并被一个只声明 Core artifact 的外部 Android consumer 编译和打包；不新增永久 consumer module，不改生产 Core API。

实现范围：
- 新增 `scripts/sdk/verify-core-consumer.ps1`，在 `build/sdk-core-consumer/<随机目录>` 下临时生成 Maven repository 和最小 Android library consumer，脚本结束后默认清理。
- 脚本执行 `generatePomFileForReleasePublication`、`bundleReleaseAar`、`publishReleasePublicationToMavenLocal`，但通过临时 `maven.repo.local` 隔离全局 `mavenLocal()`。
- 校验 publication 坐标、AAR 必要条目、无 native `.so`，以及 POM/Gradle Module Metadata 的完整依赖集合。
- 外部 consumer 仅声明 `com.ugk.pi:ugk-pi-android:0.1.0`，引用 `AgentRuntime`、`AgentSession` 和 `LLMProvider`，执行 `:consumer:assembleDebug`。
- 新增 `docs/sdk-core-consumer-contract.md`，记录当前坐标、依赖边界、验收命令和未覆盖风险。

当前已验证依赖：`kotlinx-coroutines-core:1.7.3`、`kotlinx-serialization-json:1.4.0`、`kotlin-stdlib:2.2.21`；未发现 Demo、Terminal 或其它 `pi-*` 模块依赖。

验证命令：
```powershell
.\scripts\sdk\verify-core-consumer.ps1
```

结果：通过。临时 publication、POM/Module Metadata/AAR 校验通过，外部 consumer `:consumer:assembleDebug` 通过；脚本修正 fixture 的 Java/Kotlin JVM target 后无生产代码变更。脚本执行期间首次发现并修复了 fixture 自身的 JVM target 配置问题，不能算 Core 缺陷。

边界与未覆盖项：
- 不建立永久消费者模块，不升 Demo 或 Maven 版本，不承诺 `0.1.0` 为正式发布版本。
- 未建立 API/ABI baseline、远程仓库发布、签名校验和多 AGP/Kotlin 兼容矩阵。
- 未验证真实第三方业务端到端运行；本步证明的是 artifact、依赖图和最小 Android 编译消费边界。

## SDK-OPT-007：Core API/ABI 稳定性与版本策略审查

状态：已审查，延期强制 baseline；轻量只读 inventory 已实现并验证通过

目标：确认 `:ugk-pi-android` 当前 Release AAR 的实际 public API/ABI 规模、publication/version 约束，
并判断当前是否适合引入强制 API/ABI compatibility gate。

事实审查结果：
- Release AAR 的 `classes.jar` 包含 74 个 class 文件、44 个顶层 class 文件。
- `javap -public` 发现 60 个 public type declarations；排除 `AgentRuntimeKt` 和 `*DefaultImpls` 后，
  当前源码消费者可见类型按审查口径为 57 个。
- `javap -public` 发现 519 个 public member signature；其中包含 Kotlin data class、默认参数、
  `componentN`、`copy$default` 和 `access$` 等编译器生成成员，不能直接当作人工稳定 API 数量。
- 主要公开面覆盖 Runtime/Session/Event/Message、Tool/Plugin、Provider/HTTP、AndroidSkill/SessionStore、
  以及 UserConfirmation 类型，尚未完成稳定 API 分层。
- `ugk-pi-android/build.gradle.kts` 当前只配置 release publication，坐标为
  `com.ugk.pi:ugk-pi-android:0.1.0`；没有 API/ABI 插件、baseline、远程发布或签名约束。

实现范围：
- 新增 `scripts/sdk/inspect-core-api-surface.ps1`，读取已有 Release AAR，临时提取 `classes.jar`，
  使用 JDK `jar`/`javap` 输出可复现的 class/type/member inventory。
- 脚本默认仅清理自己在 `build/sdk-api-surface/<随机目录>` 下创建的临时目录，可用 `-KeepWorkDir`
  保留现场；不写生产源码、publication 配置或永久 consumer module。
- 文档明确 `0.1.0` 是开发阶段验证坐标，不是正式发布承诺；Core 版本与 Demo `0.2.1 / versionCode 3`
  分开管理。

验证命令与结果：
```powershell
.\gradlew.bat :ugk-pi-android:bundleReleaseAar --console=plain
.\scripts\sdk\inspect-core-api-surface.ps1
```

结果：AAR 构建通过；inventory 脚本通过并输出上述统计；未引入 API/ABI 插件、baseline 文件、
永久 consumer、生产依赖或版本变化。

接收结论：接收本步骤的“事实审查 + 轻量只读检查”结果，不接收当前阶段强制 API/ABI baseline。
后续触发条件是出现正式外部 consumer、正式分发承诺、完成公共 API 分层，或需要验证一次跨版本
兼容性时，再把 baseline 提升为发布 gate。

## SDK-OPT-008：高影响操作确认票据协议设计

状态：协议设计已固化，主线程审查通过；尚未进入生产代码实现

目标：在不引入半成品跨模块代码的前提下，先解决当前确认机制只校验最近
`selectedButtonId`、无法绑定目标 Tool 和输入的问题，为下一步一次性实现提供明确契约。

设计结论：
- 确认请求必须携带 `target.toolName` 和完整 `target.input`；确认发生时目标 Tool 通常尚未执行，不能靠后续 Tool 调用推断授权对象。
- 确认结果保留 `selectedButtonId`，新增带 `version/sessionId/toolName/inputFingerprint/nonce/issuedAtEpochMillis/expiresAtEpochMillis` 的 ticket。
- `inputFingerprint` 使用版本化 canonical JSON + SHA-256；对象键排序、数组顺序、数字规范化和无法规范化时的拒绝语义已固定。
- 受保护 Tool 默认要求同一 Session、同一 Tool、同一输入摘要、未过期且紧邻的确认结果；允许 Runtime 在结果后附带一个包含当前 ToolCall 的 Assistant 外壳，但 User/System 消息或任何其他 ToolResult 会使确认失效；缺字段、拒绝、过期、错配和重复使用均 fail-closed。
- v1 的一次性语义依赖 Runtime 工具结果顺序；跨进程或排队确认若未来需要持久化防重放，必须另行引入共享 TicketStore 和协议版本。
- `shouldBypassConfirmation` 仍是宿主显式 full authorization 旁路，不要求或校验 ticket；若宿主仍调用确认 Tool，其返回的普通 ticket 在旁路路径不会被读取。

兼容边界：旧的 `selectedButtonId` 结果可以保留给非受保护确认调用；受保护 Tool 默认拒绝无 target/ticket 的旧结果。旧构造函数可在迁移期保留，但不能据此执行受保护操作。

本步只新增协议文档，不修改 Core、System、Terminal、Demo、runtime AGENTS 或版本配置。下一步实现必须覆盖 Core 单测、System/Terminal Tool 测试、Demo instrumentation 和真机确认/取消/过期/重试场景；如果实现需要 Runtime Coordinator、持久化 TicketStore 或新的 runId，必须先拆出独立决策。

## SDK-OPT-009A：Core-only 绑定确认票据

状态：已实现，主线程审查通过并提交

实现范围：
- Core 增加 `UserConfirmationTarget`、`UserConfirmationTicket` 和共享的 `canonical-json-v1` SHA-256 输入摘要实现。
- `UserConfirmationDialogTool` 从 `ToolExecutionContext.sessionId` 和确认请求 target 生成 120 秒、至少 128 bit nonce 的 ticket；旧请求缺少 target 时仍可返回普通选择结果，但不生成受保护 ticket。
- `UserConfirmationRequiredTool` 默认 fail-closed，校验最后一条 confirmation ToolResult、其后至多一个包含当前 ToolCall 名称和完整输入的 Assistant 外壳、允许按钮、ticket version/session/tool/input fingerprint、nonce 和时间窗口；显式 `shouldBypassConfirmation` 仍可旁路。
- 未引入 TicketStore、Coordinator、runId、System/Terminal/Demo 代码或版本变更；v1 一次性语义依赖 Runtime 的紧邻 ToolResult 顺序及有界 Assistant 外壳规则。

新增回归覆盖：同目标成功、对象键顺序稳定、数组顺序敏感、数字/布尔/null 规范化、Tool/Session/输入错配、过期、拒绝按钮、缺失/非法 ticket、目标缺失、一次性复用和 bypass。

验证结果：confirmation 定向测试、`:ugk-pi-android:testDebugUnitTest` 和 `git diff --check` 均通过。本步不执行真机测试，因为没有改变 Android 宿主或实际 Tool 实现；真机验收留给 009C。

## SDK-OPT-009B：System/Terminal confirmation instructions 迁移

状态：已实现，主线程审查通过并提交

实现范围：
- 更新 System 的 Intent/Automation/权限设置 instructions，要求受保护 Tool 在下一次调用前通过 `show_user_confirmation_dialog` 提供 `target.toolName` 与完整 `target.input`，并以相同名称和输入执行下一次 Tool。
- 更新 Terminal 的 `terminal_bash_execute`、`local_http_server_start`、`local_http_server_stop` 以及后续 `launch_android_app_intent` 的 instructions；保留 Bash、网络、loopback、服务管理和用户确认边界。
- 明确 `selectedButtonId` 仅表示按钮选择，不能单独构成授权；状态查询等只读 Tool 不需要确认票据。
- 未修改 Core、Demo、runtime `AGENTS.md`、Tool 执行逻辑、bypass、权限、Runtime、build.gradle、版本或既有用户未提交文件；未新增生产抽象。

验证结果：System/Terminal 定向单元测试、全 SDK 单元回归、`demo-app:assembleDebug` 和 `git diff --check` 均通过。Demo 真机确认迁移留给 009C。

## SDK-OPT-009C：Demo confirmation presenter/UI 迁移

状态：已实现，主线程审查通过；真机确认 UI 验收进行中

实现范围：
- `UserConfirmationDialogRequest.target` 映射到 Demo overlay confirmation snapshot，保留
  `toolName` 和有上限的 JSON 输入摘要；旧请求没有 target 时保持 `null`，不填充虚假目标。
- Activity 前台 `AlertDialog` 和跨 App `AgentFloatingWindow` 均展示目标 Tool 与输入摘要；输入摘要上限为
  `MAX_CONFIRMATION_INPUT_SUMMARY_CHARS = 512`，不改变 full authorization 的旁路和生命周期行为。
- 仅更新 `MainActivity` 中 screen action 的 instructions，要求确认 target 与下一次 Tool 的名称和完整输入完全一致。
- 更新 Android Automation、Android Intent、Terminal 三组 Demo instrumentation confirmation helper，覆盖成功、取消和 AgentRuntime 循环的目标绑定夹具。
- 未修改 Core、System、Terminal、runtime `AGENTS.md`、build.gradle、版本、权限、Activity 生命周期或既有 Screen Tool 实现。

验证结果：
```powershell
.\gradlew.bat :demo-app:testDebugUnitTest :demo-app:assembleDebug --console=plain
.\gradlew.bat :demo-app:compileDebugAndroidTestKotlin --console=plain
git diff --check
```

上述命令通过；首次 `connectedDebugAndroidTest` 在 14 个测试中 12 个通过，Android Automation 和 Android Intent 各 1 个因 Core confirmation boundary 问题失败；修复后需重新执行并在提交前重新安装 APK。

## SDK-OPT-009D：Runtime confirmation boundary integration fix

状态：修复中，已由 009C 真机验收发现，待主线程验证后提交

问题与修复范围：
- 真机暴露 Core 的旧判定只读取 `priorMessages.lastOrNull()`，而 `AgentRuntime` 在执行下一轮 ToolCall 前会先追加 Assistant(tool-calls) 外壳，导致确认后的 `launch_android_app` 和 `launch_android_app_intent` 被错误拒绝。
- 允许确认 ToolResult 后至多存在一个明确包含当前完整 ToolCall 的 Assistant 外壳；其余 User/System/ToolResult 或不匹配的 Assistant 均继续 fail-closed。
- 补充 Core 的 Assistant 外壳正向/错配测试，并让 Terminal Runtime instrumentation 断言受保护 ToolResult 确实成功，避免只检查事件名称而掩盖授权失败。
- 不引入 Coordinator、TicketStore、runId 或新的生产 API；不改变 ticket 字段、有效期、bypass 和 delegate 执行逻辑。

009C 真机首次结果：14 个测试中 12 个通过；Android Automation 和 Android Intent 各 1 个因上述 Core 边界问题失败，Terminal 三项通过但原循环夹具此前未断言执行结果。修复后需重新执行 Demo connected tests 并重新安装 APK。
