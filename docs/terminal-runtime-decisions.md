# Terminal Runtime 决策记录

格式：背景 → 决策 → 原因 → 影响。

## D-001：SDK 内置 Headless Runtime，不依赖 Termux App

- 日期：2026-08-12
- 决策：Runtime 随宿主 App 安装，无终端 UI，不要求用户安装 Termux 或第二个 App。
- 原因：产品需要 Agent Tool 的命令执行能力，不需要人类交互终端；第二个 App 会引入安装、包名、权限、生命周期和 IPC 复杂度。
- 影响：自己负责原生载荷、重定位、许可证、兼容矩阵和进程治理。

## D-002：最低 API 提高到 24

- 日期：2026-08-12
- 决策：`minSdk = 24`，不处理更早版本。
- 原因：减少旧 Bionic 分支，明确可维护基线。
- 影响：API24 是必测边界，代码不能直接使用 API26+ 方法。

## D-003：v1 Core Profile 固定为 Bash/curl/OpenSSL/SQLite/CPython

- 日期：2026-08-13
- 决策：v1 只交付五类能力；Git、OpenSSH、jq、其他大型 Runtime 后置评估。
- 原因：先形成可验证的网络、脚本、数据和 Python 闭环，控制体积、许可证和兼容面。
- 影响：禁止通过 apt/pkg/网络下载 ELF 在运行时补齐能力。

## D-004：Node.js 排除 v1

- 日期：2026-08-13
- 决策：Node.js 不打包、不注册、不宣称；已有 POC 仅保留为未来可选扩展资料。
- 原因：V8、ABI、addon、体积、构建和安全维护面会显著扩大当前目标。
- 影响：任何恢复 Node 的工作必须独立 AAR/extension、独立 Gate、许可证和设备矩阵。

## D-005：原生载荷使用安装时提取模式

- 日期：2026-08-12
- 决策：宿主必须使用 `jniLibs.useLegacyPackaging = true`；Runtime 从 `nativeLibraryDir` 执行。
- 原因：未压缩 `.so` 可以加载但不保证有可供 `execve()` 的真实文件路径。
- 影响：AAR 不能替宿主可靠强制所有 DSL 行为，接入方必须显式配置并复测。

## D-006：默认立即用户确认

- 日期：2026-08-12
- 决策：`terminal_bash_execute` 默认使用 `UserConfirmationRequiredTool`。
- 原因：命令可访问文件、网络和进程能力，不能默认交给模型静默执行。
- 影响：关闭确认只允许由宿主明确配置可信会话，不能由模型请求自行关闭。

## D-007：Runtime 不是安全沙箱

- 日期：2026-08-12
- 决策：不承诺隔离宿主秘密或跨 UID 权限边界。
- 原因：Bash/Python 与宿主使用同一个 Android UID。
- 影响：确认策略、威胁模型和产品文案不能把 workspace 限制叫作权限隔离。

## D-008：session/process group 清理

- 日期：2026-08-13
- 决策：用 `setsid()` 建立独立 session，超时/取消使用 `kill(-pgid, SIGTERM)`，必要时 SIGKILL。
- 原因：Java `Process.destroy()` 只覆盖直属 Bash，无法可靠清理后台 descendant。
- 影响：覆盖正常 session 内的普通子进程树；主动 `setsid`、独立 Service、跨进程逃逸仍是边界。

## D-009：双 applicationId 是强制重定位测试

- 日期：2026-08-12
- 决策：Demo A `com.ugk.runtime.demo.a`、Demo B `com.example.runtime.demo.b` 共享同一 Runtime artifact。
- 原因：暴露固定包名、固定路径和 Termux 假设。
- 影响：新增 Runtime 组件必须检查 nativeLibraryDir、Python prefix、CA、BASH_ENV 和 workspace。

## D-010：预编译 CPython + 可审计 RUNPATH 净化

- 日期：2026-08-13
- 决策：采用锁定 CPython 3.14.6 Android package；仅对两份 `libsqlite3_python.so` 用锁定 patchelf 移除 `/usr/local/lib` RUNPATH。
- 原因：上游 package 暴露绝对构建路径；完整重编 CPython 代价过高，问题可精确修复。
- 影响：每次升级必须重新检查绝对路径、DT_NEEDED、ABI、PT_LOAD 和 lock 哈希。

## D-011：构建容器中的 Node 不等于 Runtime Node

- 日期：2026-08-13
- 背景：`runtime-lock.json` 为 `patchelf` 归一化工具锁定了一个 `node@sha256:...` 容器镜像。
- 决策：该镜像只允许作为当前构建工具，不得进入 Android payload、AAR/APK、Tool 文案或能力声明；Node.js 仍明确不属于 v1。
- 原因：构建容器不是运行时依赖，但名称会造成 scope 误读；同时该容器对只执行 `patchelf` 来说不是最小语义依赖。
- 影响：发布前替换为非 Node、最小化且可锁定的基础镜像，并重新运行 Python RPATH 净化和 Gate 1；在替换完成前，维护者必须将它识别为已知技术债，不得声称“源码和构建链完全无 Node”。

## D-012：开发规范与 SDK runtime Agent 规范分离

- 日期：2026-08-13
- 背景：仓库已经有开发用根目录 `AGENTS.md`，但 SDK 内的 Agent 还需要知道自己运行在受控的 Headless Android Bash Runtime 中；两者若混用，模型会把仓库协作规则误当成终端环境事实。
- 决策：保留两份同名但不同作用域的 `AGENTS.md`。根目录文件只服务开发协作；`pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md` 随 SDK 打包，并由 `TerminalAgentPlugin.agentInstructions()` 自动注入 `AgentRuntime` 的每次模型请求。
- 原因：运行时 Agent 必须稳定获得环境契约，不依赖每个宿主 App 手工复制提示词；插件注册是终端能力进入 Agent 的唯一现有入口。
- 影响：终端环境能力、命令替换规则和“不要调用 `bash` 子进程”等模型行为约束，必须以 SDK runtime 文件为事实源，并用 `AgentRuntime` 单元测试和 APK asset 检查验证；根目录 `AGENTS.md` 不得复制进 APK。

## D-013：固定 demo Debug 签名与本机 API 初始化

- 日期：2026-08-13
- 背景：不同终端/Android Studio 环境会选择不同默认 `debug.keystore`，导致同一 `applicationId` 无法覆盖安装；卸载又会丢失本地 API 配置。
- 决策：`demo-app` 通过被 Git 忽略的 `local.properties` 固定本机 Debug keystore；Debug 构建从外部 API 配置文件读取默认值，首次启动仅在没有 SharedPreferences 状态时初始化。Release 资源保持为空。
- 原因：让日常构建保持同一签名、覆盖安装保留 API 设置，同时不把真实 key 写入源码、文档或 Git。
- 影响：固定 keystore 是本机开发配置，不是发布签名；Debug APK 内仍可提取 API key，不能分享。更换设备/工作机需重新配置本地路径；Release 发布必须使用独立、受保护且长期稳定的发布签名。

## D-014：Android App-facing 操作使用原生 Intent Tool

- 日期：2026-08-13
- 背景：真实 Agent 在创建天气网站后尝试通过终端执行 `am start`，但终端进程与宿主 App 使用普通应用 UID，不能代表 Android Shell；随后模型错误地把终端失败解释为设备没有浏览器。
- 决策：将常见 App-facing 操作做成 `launch_android_app_intent`，由 SDK 直接构造白名单 `Intent`，调用 `Context.startActivity()`，以真实派发结果为准。`open_url` 仅允许带 host 的 `http`/`https` URL；外部可见动作默认经过 `show_user_confirmation_dialog` 和 `UserConfirmationRequiredTool`。生产路径不先用 `PackageManager.resolveActivity()` 拦截，因为 Android 11+ package visibility 可能让预查询返回空；显式注入的 resolver 只用于确定性单元/仪器测试，`ActivityNotFoundException` 才映射为 `no_handler`。
- 原因：Android Intent 是系统原生的应用协作边界，不依赖 Termux、Bash、`am`、`pm` 或无障碍服务；直接派发避免把包可见性限制误判成设备事实。
- 影响：`pi-system-skill-android` 提供 `AndroidIntentAgentPlugin` 轻量入口；完整 Android 宿主使用 `AndroidAutomationAgentPlugin`，额外提供 App 查询、包名启动、无障碍状态和设置引导。`AndroidSystemAgentPlugin` 仍可按宿主需要使用；这些插件不能同时注册同名确认 Tool。Intent dispatch 的 `launched=true` 只表示 Android 接受并派发，不代表目标 App 已完成后续 UI 操作。

## D-015：Android 专家 Agent 采用“解析—启动—无障碍门禁—读屏操作”工作流

- 日期：2026-08-13
- 背景：用户使用口语描述“打开某 App 并进入某界面点击某控件”时，Agent 需要知道自己位于普通 Android 宿主 App 内，而不是 Android Shell；启动 App、读取/操作其他 App 界面属于不同权限边界。
- 决策：新增 `AndroidAutomationAgentPlugin` 和 `android-app-automation` skill。Agent 先用 `find_android_app` 将名称解析为候选包名，再用 `launch_android_app` 通过 launcher Activity 启动；需要跨 App 读屏或点击时，先调用 `get_android_accessibility_status`，未就绪则调用 `open_android_accessibility_settings` 引导用户手动开启，服务连接后再使用宿主的 `screen_*` Tool。Agent 不得用终端 `am`/`pm`、猜包名或图标搜索代替这些工具。
- 原因：把 Android 系统事实、用户授权和无障碍操作分层，降低模型误判；无障碍授权必须由用户在系统设置中明确完成，SDK 不能静默授予。
- 影响：宿主必须注入自己的无障碍服务 `ComponentName` 和状态提供器；`<queries>` 仅声明 launcher/http(s) 查询范围，不等于获得无障碍或跨 App 控制权限。`launch_android_app` 不要求无障碍，后续读屏/点击才要求 `readyForScreenAutomation=true`。

## D-016：demo-app 前后台切换保持测试状态

- 日期：2026-08-13
- 背景：返回前台时可能发生 Activity 重建或启动器重复启动；原实现把 `AgentSession`、对话 UI 和权限提示状态全部放在 Activity 实例中，导致测试看起来像“重启”。
- 决策：`demo-app` 的 `MainActivity` 使用 `singleTask` 和 `alwaysRetainTaskState`；Agent 会话、运行协调器、悬浮窗和确认 presenter 放在不持有 Activity/View 引用的进程级状态中，Activity 重建时重新绑定 UI；对话草稿通过有界 `onSaveInstanceState` 恢复，权限提示在同一进程中只首次显示。Activity 真正结束时才取消正在运行的 Agent/确认对话，配置或权限导致的重建不打断运行。
- 原因：普通切后台/启动器返回应复用现有 task；Activity 重建时保留测试上下文；同时避免把完整、可能很大的 Tool 输出塞进 Binder saved-state。
- 影响：进程被系统彻底杀死且没有可用 saved state 时，正在运行的 Agent tool history 和 Job 仍不保证恢复；API 配置继续由 SharedPreferences 保留。该决策针对 demo 测试体验，不把 Activity 生命周期语义扩展为 SDK Runtime 的后台服务保证。

## D-017：长驻本地 HTTP 服务使用专用 Runtime Tool

- 日期：2026-08-13
- 背景：真实 Agent 为了让天气网站持续可访问而手写 `nohup python3`。在本 Runtime 中，
  `python3` 是 `BASH_ENV` 注入的 Bash 函数，不是 `nohup` 能通过 `exec` 找到的普通文件；即使改成
  `python3 ... &`，服务的 PID、端口冲突、停止和日志生命周期也仍由模型自行维护。
- 决策：`pi-terminal-skill-android` 注册 `local_http_server_start`、
  `local_http_server_status`、`local_http_server_stop`。Manager 使用已验证的 CPython launcher、
  现有 `setsid` session/process group 和 app-private metadata，服务固定绑定 `127.0.0.1`，目录
  只能位于 terminal workspace 下。start/stop 继续经过用户确认，status 为只读。
- 原因：把一次性脚本执行和跨调用服务生命周期分开；Agent 仍能使用 Bash/Python 完成功能，但不需要
  了解 launcher 路径、`nohup` 语义或进程组清理细节。
- 影响：Runtime `AGENTS.md` 必须明确 `python`/`python3` 只能在当前 Bash 中直接调用，禁止将其交给
  `nohup`、`env`、`setsid`、`xargs` 等外部 exec 包装器；本地 HTTP 服务不是 LAN/public server，
  也不承诺宿主进程被杀后的恢复。后续若需要 WebSocket、TLS、外网绑定或任意 daemon，应另立决策。

## D-018：稳定化窗口对确定性缺陷修复的准入边界

- 日期：2026-08-15
- 背景：PR #1（SDK-STAB-002）在稳定化窗口内同时修复 2 个 P0、2 个 P1 和 4 个 P2。冻结规则列出的准入类别是 P0/P1 缺陷、可复现回归和验证阻断修复；其中 nodeId 严格解析、`screen package` 活动窗口、`tel:`/`smsto:`/`mailto:` 输入校验和 `Thread.sleep` 替换按原始分级为 P2，需要明确准入依据，避免窗口被无限放宽。
- 决策：稳定化窗口内，低于 P1 的修复仅在同时满足以下条件时准入：缺陷是确定性的功能/数据正确性问题或输入校验缺口（不是重构、性能优化或新能力）；每项附带回归测试或有书面说明的验证缺口；不触碰公共 API、确认语义、权限边界、Terminal Runtime scope 或 Gate 退出条件；不引入 Coordinator、TicketStore、runId、模块拆分或新跨模块 API。本批 4 个 P2 全部满足上述条件，予以放行；其中 `Thread.sleep`→`delay` 一项是对 PR 自身第一版引入问题的修复。此决策是对冻结规则的细化，不改变其禁止项；后续 P2 级修复必须先在台账登记条目并引用本决策，才能合入。
- 原因：拒绝这批修复会使已证实的静默误操作（对无关节点执行动作并报告成功）和 URI 重构输入面继续存在；而放行条件不扩大架构面，与窗口“冻结边界、只修缺陷”的目的一致。
- 影响：Core API surface 的 public member signature 计数可因编译器合成成员（如 `access$` 桥）随 private helper 增减而波动（本批 574→575，唯一差异为 `access$appendCancelledToolResults` synthetic accessor）；inventory 总数差异须先经与基线的逐行 diff 区分合成成员与源码 API 变化，只有后者才按 SDK-OPT-010 的版本规则处理。

## D-019：Accessibility screen automation 下沉到 SDK Skill/Plugin

- 日期：2026-08-25
- 背景：屏幕读取、节点定位、点击/输入/滚动、坐标手势、IME action 和全局动作原先由 demo-app 私有注册，宿主无法复用同一套工具语义、目标校验和 Agent 指令。
- 决策：将 screen automation 的工具、Skill 和 Android 默认 backend 放入 `pi-system-skill-android`。`AndroidAutomationAgentPlugin` 通过可选的 `ScreenAutomationBackend` 注入屏幕能力；宿主只负责提供当前 `AccessibilityService` 和自身包名。未注入 backend 的轻量宿主不注册 screen tools。
- 目标协议：`screen_read_ui_tree` / `screen_find_ui_element` 生成有界值快照；每次快照有 `snapshotId`，每个节点有精确 `nodeId`；动作必须携带同一 session 的最新 snapshotId + nodeId。backend 会重新解析并校验 target fingerprint，发现 snapshot 过期、窗口/节点消失、目标不可交互或动作不支持时 fail-closed。
- 安全边界：读屏和查找为只读；节点动作、手势、IME action、全局动作继续由 `UserConfirmationRequiredTool` 包装。SDK 不静默授予无障碍权限，也不把 root、shell、终端命令作为屏幕能力替代。
- 资源边界：不跨 Tool 持有 `AccessibilityNodeInfo`，只保留 session 级 bounded snapshot；当前实现最多保留 16 个 session 的最新 snapshot。跨进程 Coordinator、ticket store、runId 和屏幕录制不属于本次 scope。
- 原因：统一工具名和 structured error code（例如 `STALE_SNAPSHOT`、`NODE_NOT_FOUND`、`TARGET_NOT_INTERACTABLE`）可降低模型猜节点、误操作和重复 read 的成本；把准确性策略放入 SDK，宿主接入只需实现 service provider。
- 影响：demo-app 改为注入 `AccessibilityScreenAutomationBackend`，删除 demo 私有 screen tool 实现；旧 screen tool 的单元测试迁移至 `pi-system-skill-android`。需要真机在用户手动开启无障碍后继续验证跨 App 读屏和操作；不能把无障碍未开启误报为 SDK 缺陷。
## D-020：Full authorization 不应触发模型确认回合

- 日期：2026-08-26
- 背景：宿主已经通过 `shouldBypassConfirmation` 让高影响 Tool 在执行层直通，但模型仍能看到 `show_user_confirmation_dialog`、确认型 Tool description 和无条件确认 instructions，导致全授权模式额外消耗一个 LLM 回合。
- 决策：当宿主在 Runtime 注册时显式开启 full authorization，System/Terminal/File 相关 Plugin 不注册确认 Tool；受保护 Tool description、Skill methods 和 Agent instructions 同步声明“直接调用受保护 Tool”，但保留所有目标、权限、输入、动作能力和结果校验。普通模式保持现有确认票据和 fail-closed 语义。
- 兼容性：所有新增 Skill 参数默认保持确认；Demo 切换授权开关时重建 Runtime，使 Tool 注册表和模型上下文同步刷新。该模式只改变确认策略，不扩大 AccessibilityService 或 Terminal 能力边界。
- 验证要求：补充全授权 Tool 列表、Skill method 列表和 AgentRuntime 无确认回合测试，并继续运行普通确认链路回归。

## D-021：宿主悬浮窗不得成为屏幕自动化目标

- 日期：2026-08-26
- 背景：小米真机显示宿主 `TYPE_APPLICATION_OVERLAY` 窗口可能成为当前焦点窗口；虽然正常 `AccessibilityService.windows` 路径已按宿主包名过滤，但 `rootInActiveWindow` 回退和 IME action 仍可能把悬浮窗当成目标。
- 决策：SDK 的读屏回退、焦点输入解析和节点窗口解析统一拒绝宿主包名；仅有宿主窗口时返回 `WINDOW_UNAVAILABLE`，不得把空树或宿主树交给 Agent。Demo AccessibilityService 同时忽略自身包名事件，避免悬浮窗刷新污染外部活动包名。
- 兼容性：保留悬浮窗可输入、可显示的交互方式；语义节点 action 继续按非宿主窗口执行。坐标手势仍应避开悬浮窗覆盖区域。
- 验证要求：补充宿主事件过滤单测，运行 SDK/Demo 单测和 Debug APK 构建，并在小米设备复核悬浮窗权限、无障碍服务和外部目标窗口状态。

## D-022：文件型 skill 运行时（pi-agent-skill-runtime-android）

- 日期：2026-08-27
- 背景：SDK 此前的 skill 只能由 `AgentCapabilityPlugin` 在代码里静态注册；宿主与 Agent 无法在运行期以文件形式增补行为契约，也没有跨会话的用户记忆能力。改造 `ugk-pi-android` 注入管线会触碰受控的 API surface 基线。
- 决策：新建独立模块 `pi-agent-skill-runtime-android`，`ugk-pi-android` 核心零改动。skill 以 SKILL.md 文件放在 `<filesDir>/agent-skills/<skill-name>/`，frontmatter 用手写扁平解析（不引入 YAML 依赖），标准字段为 `name`/`description`，扩展语义走 `x-ugk-load`、`x-ugk-embed-files`、`triggers`，未知 key 忽略以保前向兼容。加载策略三级：`always`（body+embed 全文每轮注入）、`indexed`（仅元数据桩，模型用 `skill_read` 按需拉全文）、`triggered`（关键词匹配，语义对齐 `KeywordAndroidSkillResolver`）。因 `AgentRuntime.Builder.skillProvider(x)` 会清空已注册 plugin 的静态 skills，宿主必须用合并式 `FileBackedSkillProvider(plugins, repository)` 并配合 `LoadPolicySkillResolver` 接线；demo-app 4 个既有 plugin 的 skills 行为保持零变化。
- 记忆 = 第一个预制 skill：`agent-memory` 以 `always` 策略注入捕获/回放协议（先征询同意 → memory_read → 合并不得丢条目 → overwrite 覆写 → 简短确认），记忆沙箱限定 `filesDir/agent-memory` 下四个白名单分类（user-profile/preferences/facts/rules），单文件 16KB 上限；种子机制幂等、绝不覆盖已有目标。
- 高影响边界：`memory_delete` 销毁用户数据，默认由 `UserConfirmationRequiredTool` 包装，须先经 `show_user_confirmation_dialog` 确认；full authorization 模式沿用宿主既有 bypass 开关。解析/校验失败的 skill 不注入但以 invalid 状态带原因出现在 `skill_list`，不静默丢弃。
- 勘误（2026-08-27）：D-022 初版把 `x-ugk-embed-files` 描述为"skill 目录静态文件"，导致记忆回放链路断裂——静态种子 `preferences.md`/`rules.md` 作为嵌入对象，而 `memory_*` 工具把真实用户记忆写到另一个根目录，用户偏好不会常驻生效。embed 语义修正为"支持命名根实时嵌入"：条目支持 `别名:相对路径.md` 形式（别名 `[a-z][a-z0-9-]*`），解析到宿主在 `FileBackedSkillProvider(embedRoots)` 注册的命名根，路径校验与 skill 目录条目一致（仅 .md、拒绝绝对路径与 `..`、canonical 必须落在该根内），嵌入内容每次 `skills()` 调用实时读取。`agent-memory` 改用 `memory:preferences.md` / `memory:rules.md` 并删除静态种子模板；命名根机制为通用能力，不限于记忆。事实源见 `docs/android-agent-skills.md` 的"命名根嵌入"。
- 勘误二（2026-08-27）：验收发现 `AgentRuntime.Builder.skillProvider(x)` 原实现把 `x.skills()` 立即拍平进静态快照，Runtime 每 run 查询的只是快照——同进程内新写入的记忆/新放入的文件 skill 要等宿主重建 Runtime 才生效，跨会话回放主链路实际断裂。核心做最小行为修正（公共 API 签名零变化，API surface 基线不受影响）：Builder 持有 Provider 引用，`build()` 有自定义 Provider 时直接传入，Runtime 维持既有"每 run 调 `skills()`"的拉取语义；`skillProvider()` 清空 plugin 静态 skills 的既有语义保留。生效粒度由此变为"下一次 run"，且 Provider 实现须并发调用安全（`AgentRuntimeBuilderLiveSkillProviderTest` 锁定该行为）。行为差异记录：旧实现下 `skillProvider(x)` 之后再 `register(plugin)` 会把 plugin 静态 skills 追加进快照；新实现下自定义 Provider 全权接管，其后 `register` 的静态 skills 不再进入注入列表（宿主应先 register 再 skillProvider，并把 plugin 列表传给合并式 Provider——demo 即如此接线）。
- 影响：`docs/android-agent-skills.md` 为该运行时的事实源；`skill_save`（Agent 自沉淀 skill）列为 v2 展望，不在本期 scope。

## D-023：AgentSession 取得 transcript ownership，并在请求前统一准备

- 日期：2026-08-29
- 状态：已授权实施，独立审查 P1/P2 修复已完成并通过补充验证
- 背景：架构审查发现 `AgentSession.messages` 仍暴露 `MutableList`，外部 Demo/Compactor 可以绕过 `runGate` 直接清空、追加或切断工具消息组；`AgentRuntime` 也在多个位置直接修改转录。压缩只在 Demo `DemoAgentRunCoordinator` 的 `finally` 中执行，无法保护同一 Run 的下一次模型请求，也无法保证最终回答后没有下一次请求时仍受持久化上限约束。
- 决策：本轮有意调整 Core 公共形状：`AgentSession` 改为以 `List<AgentMessage>` 接收历史并由自身持有 copy-on-write transcript，公开 `messages` 只返回由 `transcriptLock` 保护的不可变快照；追加、工具批次取消补齐、替换、快照和请求前准备集中在 Session 内部接口，既有 `runGate` 保留并继续覆盖每次 Run。Core 新增小型 `TranscriptPreparationPolicy` seam，默认 no-op；`AgentRuntime` 在每个 `ModelRequest` 构建前、以及最终完成边界，均在 `runGate` 内让 Session 以 immutable snapshot 应用 policy，再把实际 prepared request snapshot 交给 Provider。policy 异常或非法转录序列拒绝替换、保留原转录，并使 Runtime 发出 `AgentEvent.Failed`（取消仍按取消语义重抛）；当前响应内重复 tool-call ID 在 envelope append 与 tool 执行前失败。
- 决策（Demo）：`ContextCompactor` 改为只接收消息快照并返回纯 compaction result，不再写入 Session；`DemoAgentRuntimeFactory` 按当前 Provider 的 `contextWindow`、`autoCompaction` 和 `compactionThreshold` 注入 policy，生命周期配置快照也纳入这三项以避免复用旧 policy。删除 `DemoConversationRuntime.boundSession` 及其隐藏的 coordinator 后置压缩；`DemoAgentRunCoordinator` 的通用 `sessionFinalizer` seam 保留给其他用途。最终持久化上限只能在明确的 transcript/session policy 时机执行，不能恢复全局 `finally` 方案。
- 不变式：Session 不持久化用户/工具的瞬态图片、敏感模型文本或图片上下文；输入及工具附件只进入其紧邻的一个 ModelRequest；首个非 system 消息必须是 user；assistant `tool_use` 与其连续 `tool_result` 保持原子；取消时为未完成调用补齐 error `tool_result`。policy 结果若违反这些规则，必须拒绝且不得污染原转录。
- 兼容性影响：构造函数继续接受 `List<AgentMessage>`（现有位置参数和 `messages =` 命名调用保持可用），只读 `messages` 的索引/遍历/断言调用保持可用；直接依赖 `MutableList` 的 `add`/`clear`/`+=` 会在源码层面停止编译。`AgentSession` 不再提供 data class 自动生成的 `copy`、`componentN` 和基于可变转录的 value equality，调用方必须改用显式 Session factory 或 snapshot 比较。Demo 的 `ContextCompactor.compactIfNeeded` 也从 `AgentSession -> CompactionSummary` 改为 `List<AgentMessage> -> ContextCompactionResult`，原 `boundSession` 入口删除。本仓库处于 `0.x` 且本轮已明确授权全量架构整改，接受该公共 API/source compatibility 变化；不改变 Provider、Tool、Event 协议。
- 版本与发布边界：不修改版本号、publication 坐标、tag、push、PR 或发布产物；本决策及实现不构成发布。补充验证为 `:ugk-pi-android:testDebugUnitTest`、`:demo-app:testDebugUnitTest`、`:demo-app:compileDebugKotlin`、`git diff --check` 和生产代码 `session.messages` mutation 检索，均通过；不做 assemble、device、network 或 API 调用。
- 回退边界：在本轮交付前如需回退，只撤销本决策对应的 Core AgentSession/AgentRuntime policy、Demo compaction/factory/coordinator 接线及其必要测试/文档改动，恢复到干净 `74dd2ff`；不触碰 capability assembly、Terminal/Screen policy、UI 重构、依赖和版本。若发现超出上述边界的变更，先停止并处理边界，不以兼容层或文字记录掩盖。
