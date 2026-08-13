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
