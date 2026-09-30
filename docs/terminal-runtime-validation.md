# Terminal Runtime 验证矩阵

更新时间：2026-09-01
验证源码：`E:\AII\ugk-android-new`
注意：最新 Terminal instrumentation/probe 的物理设备证据绑定到 source checkpoint `28bc352622458d29e090656ae42fd32f057e9196`；第 21—23 节保留历史版本保存，第 24 节记录 Demo `0.9.2 / versionCode 13` 第二轮 P0 修复保存与合并验收，第 25 节记录 PR #4 测试套件清理与防泄漏收束验收，第 26 节记录 Demo `0.9.3 / versionCode 14` 输入区/多图保存验收，第 27 节记录 Demo `0.9.4 / versionCode 15` 第三轮 P0 审查修复。后两者不关闭 Terminal 设备矩阵、网络或发布 Gate。

> 第 6—14、18—19 节按日期保留历史验证快照；这些章节中的“当前”仅指当时的源码、APK 或设备上下文。
> 当前 Terminal Gate 结论以文首总表与第 20 节为准；第 21—23 节为历史版本保存；Demo 0.9.2 保存以第 24 节为准；PR #4 测试 closeout 以第 25 节为准。

## 1. 环境变量

以下为当前机器的可执行环境；早期轮次小节中的旧布局（`E:\AndroidStudioKoalaFeat2024\jbr`、`E:\Application\Android\2020SDK\sdk`、`C:\Users\shengk\.android`）在本机已不存在，历史记录只保留当时的命令与结果。

```powershell
$env:JAVA_HOME = 'E:\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'E:\Android\SDK'
$env:ANDROID_USER_HOME = 'C:\Users\29485\.android'
```

当前 NDK：`E:\Android\SDK\ndk\28.2.13676358`。

## 2. Gate 总表

| Gate | 验证内容 | 当前结果 |
|---|---|---|
| Gate 1 | 两 ABI 原生静态/锁文件/AAR/APK/无 Node | 已通过 |
| Gate 2 | x86_64 API 24/29/35 4KB、API36 16KB、arm64 API34/4KB、A+B 重定位 | x86_64 已通过；arm64 API34/4KB 子集（受限证据）已通过 |
| Gate 3 | API24/4KB、API36/16KB 的确认/取消/超时/进程组/并发/输出/环境 | x86_64 子集已通过；arm64 本地控制子集（受限证据）已通过 |
| Host integration | `demo-app` 接入 Terminal Plugin、确认工具和真实 APK Runtime | API35 x86_64/4KB、API34 arm64/4KB 已通过 |
| Release Gate | arm64、完整 API/page size、Release AAB、升级、低盘、性能、许可证 | 未通过/未完成 |

> Gate 2/3 中的 arm64 “子集已通过”仅表示既有 4 KB、受限 Terminal 证据，不代表 `0.7.0` 当前完整 Terminal 体验，也不关闭 Release Gate；第 21 节的 Demo 人工体验不改变这一点。

## 3. 单元测试和静态验收

```powershell
.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-file-skill-android:testDebugUnitTest `
  :pi-schedule-skill-android:testDebugUnitTest `
  :ugk-agent-task-runtime-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :pi-agent-skill-runtime-android:testDebugUnitTest `
  :ugk-terminal-runtime-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  --console=plain
```

结果（2026-08-29）：全部任务成功；`ugk-terminal-runtime-android:testDebugUnitTest` 为 `NO-SOURCE`。截至 2026-08-29 的历史计数：XML 合计 `271` 个测试，`0` failure、`0` error、`0` skipped；分模块数量见第 20 节。Demo JVM 测试另以 `:demo-app:testDebugUnitTest` 验证 `104/104`。最新合计以第 28 节为准。

```powershell
.\scripts\terminal-runtime\verify-runtime.ps1 `
  -CheckPackages `
  -NdkRoot 'E:\Android\SDK\ndk\28.2.13676358'
```

结果：两 ABI 的 Bash/curl/OpenSSL/SQLite/Python 原生库、Python 扩展树/标准库、AAR、两个 Release APK、依赖闭包、哈希、zipalign 和无 Node Core 检查通过。

## 4. Gate 2 基础 Profile

| API | page size | ABI | Demo A | Demo B | HTTPS |
|---:|---:|---|---:|---:|---|
| 24 | 4 KB | x86_64 | 7/7 | 5/5 | HTTP 200 |
| 29 | 4 KB | x86_64 | 7/7 | 5/5 | HTTP 200 |
| 35 | 4 KB | x86_64 | 7/7 | 5/5 | HTTP 200 |
| 36 | 16 KB | x86_64 | 7/7 | 5/5 | HTTP 200 |

覆盖 Probe、nativeLibraryDir、Bash、SQLite、curl/HTTPS/OpenSSL、CPython、stdlib/CA 自修复、超时进程树和双 `applicationId` 路径。

## 5. Gate 3 控制 Profile

| API | page size | ABI | Demo A | Demo B | 主要额外覆盖 |
|---:|---:|---|---:|---:|---|
| 24 | 4 KB | x86_64 | 10/10 | 5/5 | API24 兼容、立即确认、主动取消、普通/TERM-resistant 超时、execmem |
| 36 | 16 KB | x86_64 | 10/10 | 5/5 | 16KB、主动取消、进程组 SIGKILL 升级、全 Core |

## 6. 宿主 `demo-app` 集成验证

验证日期：2026-08-13；源码状态：该轮工作树包含未提交改动。

设备：`ugk_dev_api35_smooth`，API 35、Google Play、x86_64、实际 page size `4096`，ADB `emulator-5556`。

```powershell
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat :demo-app:connectedDebugAndroidTest --console=plain
```

结果：`BUILD SUCCESSFUL`；demo-app 共 `13` 个测试，`failures=0`、`errors=0`、`skipped=0`。

覆盖内容：

- `demo-app` 显式接入 `:pi-terminal-skill-android`，并启用 `jniLibs.useLegacyPackaging = true`；
- `MainActivity` 注册 `show_user_confirmation_dialog` 和默认 `requireUserConfirmation=true` 的 `TerminalAgentPlugin`；
- 通过 fake confirmation presenter 产生 `confirm` 结果后，真实 APK 中的 `terminal_bash_execute` 在 `nativeLibraryDir` 执行 Bash/Python，返回 `exitCode=0`；
- `cancel` 结果会阻断终端调用，验证脚本没有执行；
- `LocalHttpServerManagerInstrumentedTest` 通过真实 APK Runtime 启动 loopback Python HTTP server，读取工作区页面，重新构造 Manager 后按 process group 停止，验证服务可恢复识别且不会杀死非托管端口；
- 安装后 `primaryCpuAbi=x86_64`，`nativeLibraryDir` 下的 Runtime ELF 为可执行文件；logcat 未发现 `FATAL EXCEPTION`、`UnsatisfiedLinkError` 或 `ugk_terminal` 异常。

限制：本次 instrumentation 不需要也不读取真实 API Key；它没有替代真实 LLM Provider，也没有自动点击真实 `AlertDialog`，因此真实模型工具循环和用户手动确认仍需后续手工验证。该结果不能替代 arm64 或 Release 证据。

## 7. 真实 Agent + Tool 单次低成本范围验证

验证日期：2026-08-13；设备仍为 `emulator-5556`（API 35、x86_64、4 KB）。用户明确授权使用已配置的付费 API，但要求避免重复调用；本次只发送一个本地、确定性、明确不联网的组件测试请求，没有新增 API Key，也没有执行 curl 网络请求。

结果：Agent 成功完成 `show_user_confirmation_dialog` → 用户点击继续 → `terminal_bash_execute` 的真实工具链；随后 Agent 生成的脚本调用了不存在于受控 PATH 的 `bash` 子进程，因 `set -e` 以 `exitCode=1` 提前结束。该调用同时证明 Bash 版本、CPython 3.14.6、Python `ssl`/`sqlite3`/`hashlib`、SQLite 内存查询均可返回；OpenSSL 只完成版本确认，证书操作未执行。第二次“修正脚本”确认被用户点击取消，因此没有再次消耗 API 或终端执行次数。

判定：真实 Agent→确认→Tool→结果回传链路已通过；模型环境规范在修复前不足，已新增 SDK runtime `AGENTS.md` 和明确的 shell 命令替换规则。该结果不能宣称 Agent 侧 OpenSSL 完整测试通过；Probe 的无模型 Runtime 测试仍是完整组件证据。

## 8. Runtime Agent contract 回归

本次代码变更后的无付费验证：

- 全部 JVM 单元测试通过，包含 `RuntimeAgentInstructionsTest`；
- `:pi-terminal-skill-android:bundleDebugAar` 和 `:demo-app:assembleDebug` 成功，AAR/APK 均含 `assets/ugk/AGENTS.md`；
- `:terminal-probe-demo-a:connectedDebugAndroidTest` 为 `10/10`、`:terminal-probe-demo-b:connectedDebugAndroidTest` 为 `5/5`，实际构造 `TerminalAgentPlugin` 和 Runtime 组件路径通过；
- 之后使用固定 Debug keystore 重新构建，`demo-app:connectedDebugAndroidTest` 最终 `13/13` 通过；测试结束后使用 `adb install -r -d` 覆盖安装固定签名 Debug APK，没有执行卸载或清空用户配置。
- 本轮没有重新调用真实 LLM，也没有读取或写入真实 API Key；模拟器回归使用 fake provider、确定性 Runtime 测试和本地 loopback HTTP 请求。

## 9. Debug 签名与本机默认配置

- 该轮 `demo-app` Debug APK 与固定的 `E:\Android\.android\debug.keystore` 签名一致；后续覆盖安装不应再出现默认 debug key 不一致。
- `E:\AII\deepseek-202608.txt` 只作为本机外部输入；首次启动 Debug App 后，应用私有 `SharedPreferences` 已确认写入 active provider、API host、model 和 API key（验证只记录存在性与长度，不输出 key）。
- `:demo-app:assembleRelease` 已通过，Release 侧默认 API 资源为空；Debug APK 含 key，不能用于分发。

## 10. Android 原生 Intent 与跨 App 自动化基础 Tool 集成

验证日期：2026-08-13；设备：`ugk_dev_api35_smooth`，API 35、x86_64、4 KB，ADB `emulator-5556`。

```powershell
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat :demo-app:connectedDebugAndroidTest '-Pandroid.injected.device.serial=emulator-5556' --console=plain
```

结果：`BUILD SUCCESSFUL`；该轮 Intent/自动化相关测试共 `12` 个，失败 `0`。该轮 demo-app 总测试数因新增本地 HTTP 服务回归为 `13/13`。其中 `AndroidIntentIntegrationInstrumentedTest`、
`AndroidAutomationToolsInstrumentedTest` 和 `AndroidAutomationAgentIntegrationInstrumentedTest` 覆盖：

- AgentRuntime 使用 fake provider 完成 `show_user_confirmation_dialog` → `launch_android_app_intent` 的工具循环；
- 有 URL 处理器时构造并派发 `Intent.ACTION_VIEW`，返回 `launched=true`、`resolvedPackage`；
 - 通过注入无处理器解析器确定性验证 `no_handler`，不把终端 `am`/`pm` 失败误判成设备事实；实际设备处理器分支按当时 AVD 安装状态验证；
- 用户取消确认时，外部 Intent 不被派发；
- `find_android_app` 通过 launcher 查询将宿主 App 名称解析为包名；
- `launch_android_app` 在没有无障碍连接时仍能通过原生 launcher Intent 启动 App；
 - `get_android_accessibility_status` 正确报告用户开关、服务连接、当时包名和可操作门禁；
- `open_android_accessibility_settings` 只打开系统设置，不伪造或静默授予权限；
- 无 `SYSTEM_ALERT_WINDOW` 权限时，Agent 运行期间通过原生 Intent 打开 Chrome 不再触发悬浮窗 `BadTokenException`；真实 URL `https://example.com` 成功显示 `Example Domain`，宿主 PID 保持存活；
- fake `LLMProvider` 实际跑通 `find_android_app` → `show_user_confirmation_dialog` → `launch_android_app` 的 AgentRuntime 工具循环；
- SDK runtime skill 明确要求 App-facing 动作使用原生 Intent，不使用 `terminal_bash_execute` 启动 Android 应用。

生产 Intent dispatch 以 `Context.startActivity()` 为准；测试中的显式 resolver 仅用于确定性 `no_handler` 分支，不能代表生产路径的 package visibility 预查询。该验证不调用真实 LLM/API；真实目标 App 的后续 UI 行为不由 `launched=true` 单独保证。仪器测试任务可能清理目标包，测试后需用固定签名 APK 覆盖安装恢复 demo；本次已恢复并确认默认 API 初始化。

## 11. demo-app 前后台生命周期回归

验证日期：2026-08-13；设备：`ugk_dev_api35_smooth`，API 35、x86_64、4 KB，ADB `emulator-5556`。

- `MainActivity` 使用 `singleTask`/`alwaysRetainTaskState`；HOME 后再执行 `am start -W -n com.ugk.pi.android.testapp/.MainActivity`，ActivityRecord 和进程 PID 保持不变；
- 输入草稿 `draft123` 在 HOME→返回后保留；切换启动器不会重复创建 Activity；
- 通过 `am kill` 模拟后台进程回收后，saved state 草稿 `killpersist` 恢复；API 配置仍显示为 `deepseek-v4-flash - api.deepseek.com`；
- 无悬浮窗权限时切换到 Chrome 的真实 Agent 回归未发现 `FATAL EXCEPTION`；该回归使用了用户已授权的一次真实 URL 请求，没有重复天气网站请求。

该回归保证 demo 测试体验，不保证被系统杀死后正在运行的 Agent Tool 在后台续跑；真正的后台执行需要独立 Service/WorkManager/进程恢复设计。

## 12. Runtime-managed 本地 HTTP 服务验证

验证日期：2026-08-13；设备：`ugk_dev_api35_smooth`，API 35、x86_64、4 KB。

- `local_http_server_start` 的底层 Manager 启动 CPython launcher，不经过 `nohup python3`；
- 服务只监听 `127.0.0.1:18765`，通过 raw HTTP 请求返回工作区 `index.html` 内容；
- 新建 Manager 能从 app-private metadata 恢复 process group，`local_http_server_stop` 成功停止服务；
- 结构化 Tool 输入、默认端口和只读 status 由单元测试覆盖；
- `:demo-app:connectedDebugAndroidTest` 最终 `13/13` 通过，测试结束后无残留 `18765` 服务。

该验证证明 Runtime-managed service 的启动、访问、重建识别和停止链路；不证明宿主进程被系统彻底杀死后服务能够恢复，也不开放 LAN/public bind。

## 13. 用户手工天气网站回归与模拟器 SystemUI ANR

验证日期：2026-08-13；设备：`emulator-5556`，API 35、x86_64、4 KB。

- 用户手工验证“创建天气网站 → 启动 Runtime-managed 本地 HTTP 服务 → 浏览器打开 loopback URL”通过；该结果确认本地网站功能链路可用。
- 随后一次测试中出现“App 无响应”现象。现场 `system_app_anr` 记录的进程是 `com.android.systemui`，不是 `com.ugk.pi.android.testapp`；原因是：`Input dispatching timed out ([Gesture Monitor] edge-swipe (server) is not responding. Waited 5008ms for MotionEvent)`。
- 同一时间窗口还记录了 `com.google.android.inputmethod.latin` 的输入事件超时；图形合成器、SystemUI、输入法和 demo 进程同时出现高 kernel CPU。SystemUI ANR 主线程堆栈停在 `HardwareRenderer.nSyncAndDrawFrame`，与图形合成管线卡住相符。
- `data_app_anr` 没有 demo 的 ANR 条目；没有发现 demo `FATAL EXCEPTION`。demo 进程仍存活，托管的 `libugk_python.so -m http.server 8765` 仍在运行。
- demo 的累计 `gfxinfo` 显示 `Janky frames=61.97%`、`GPU 90th/95th/99th percentile=4950ms`；这是本次模拟器图形压力的辅助证据，统计为进程累计值，不能单独作为每一帧的因果证明。

判定：本次现场故障应归类为模拟器的 SystemUI/图形/输入链路 ANR，不判定为本地 HTTP Runtime 启动失败。该轮仍需关注 demo UI 的渲染压力：它同时存在 Activity、悬浮窗和确认对话框渲染根，且 `MainActivity` 在 `Dispatchers.Main` 收集 Agent 事件；模型请求体构造和响应解析也可能在调用方上下文执行。后续应在不调用真实 API 的前提下补充 UI 性能回归，并考虑把序列化/解析与长 Agent 回路移出主线程。

## 14. 历史物理 arm64/API34 回归（2026-08-14）

验证日期：2026-08-14；设备：`SM-A526U1`，Android 14/API 34、arm64-v8a、4 KB page size；ADB 序列号：`R5CRB11B2AW`。

```powershell
$env:ANDROID_SERIAL = 'R5CRB11B2AW'
.\gradlew.bat :demo-app:connectedDebugAndroidTest --console=plain
.\gradlew.bat `
  :terminal-probe-demo-a:connectedDebugAndroidTest `
  :terminal-probe-demo-b:connectedDebugAndroidTest `
  --console=plain
```

- `:demo-app:connectedDebugAndroidTest`：`14/14` 通过；该轮 `demo-app` APK 为 `versionCode 3` / `versionName 0.2.1`。Instrumentation 执行期间由 Gradle 管理测试 APK 安装生命周期；结束后已使用 `adb install -r` 重新安装并启动 Demo，未把测试过程误记为用户数据保留。
- `terminal-probe-demo-a`：`9/10` 通过；`terminal-probe-demo-b`：`4/5` 通过。除联网用例外的 Runtime、本地 HTTP、进程控制、Python/SQLite/OpenSSL 等本地能力均通过。
- 两个未通过用例均为 `https://example.com` 联网测试：Probe A 为 `curl (6) Could not resolve host`，Probe B 为 `curl (28) Resolving timed out after 15000 milliseconds`。设备当时由 VPN 接管默认网络，`wlan0` 无 carrier、无可用外网路由，DNS/ICMP 均失败；这是测试环境阻塞，不判定为 Runtime 本地能力失败，双 Probe 网络 Gate 仍保持未通过。
- 该轮还修正了 probe A 对 `TerminalAgentPlugin.tools()` 使用 `.single()` 的过时假设，并同步刷新了 CPython manifest 的锁文件摘要与大小。

## 15. 未覆盖矩阵

| 维度 | 未覆盖 |
|---|---|
| ABI | arm64-v8a 16KB 运行 |
| API/page size | API35/16KB；API36/4KB；其他真实设备组合 |
| 构建形态 | API24/29/36 的 Release APK/AAB split 安装与升级 |
| 设备状态 | 低磁盘、低内存、进程被杀后的 Agent 运行恢复、升级迁移 |
| 架构 | 跨进程 cancel-by-id、独立 Service/Binder Supervisor、主动逃逸治理 |

## 16. 设备执行命令

设备通过 USB/ADB 在线并设置序列号后：

```powershell
$env:ANDROID_SERIAL = '设备序列号'
.\gradlew.bat `
  :terminal-probe-demo-a:connectedDebugAndroidTest `
  :terminal-probe-demo-b:connectedDebugAndroidTest `
  --console=plain
```

测试需要设备联网，因为包含 `https://example.com`。测试只使用两个 Probe App 的安装包、私有目录和进程；不需要 root，不应清理用户其他 App。

## 17. 证据判读

- API24 没有 `getconf` 时使用 `/proc/self/smaps` 的 `KernelPageSize`；
- API36 16KB 必须实际读到 `16384`；
- x86_64 模拟器即使 `abilist` 包含 arm64，也不能证明 arm64 payload 执行；
- Demo A/B 都通过才算双 `applicationId` 重定位；
- Debug instrumentation 通过不等于 Release APK/AAB 通过；
- 网络 HTTP 200 只证明该测试环境的 DNS/TLS/CA 路径正常，不代表所有网络环境。

## 18. 阶段 7 Terminal/Screen capability interlock JVM 验证

验证日期：2026-08-29；源码状态：`main` 分支 `9956116a96d8cebc7fd8aba778989397ee8a3a7e` 基线之上的未提交阶段 7 工作树。

TDD 证据：

- RED：新增 `AgentToolInterlockTest` 后，首次编译因 `AgentToolInterlock`、`AgentToolInterlockPolicy` 和 `AgentToolInterlockDecision` 尚未实现而失败；随后以最小通用 decorator/interlock 实现进入 GREEN。
- GREEN：核心 interlock focused test、Demo capability/policy focused tests、Demo coordinator end-to-end lifecycle focused test 均通过。

受影响验证命令：

```powershell
.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :demo-app:testDebugUnitTest `
  :demo-app:compileDebugKotlin `
  --console=plain
```

结果：`BUILD SUCCESSFUL`；测试报告为 `ugk-pi-android 122/122`、`pi-terminal-skill-android 16/16`、`pi-system-skill-android 42/42`、`demo-app 99/99`，均无 failures/errors/skipped。同时通过 `git diff --check`；目标静态门禁确认 `pi-terminal-skill-android` 不含 screen symbol，旧 screen 专用 guard、错误码和 callback 不存在，且仓库中没有 `startsWith` screen workflow 匹配。前台 `MainActivity` 与后台 `DemoScheduledTaskPromptExecutor` 均引用 `DemoScreenAutomationPolicy.isScreenWorkflowTool` 的同一精确、trim、`Locale.ROOT` 大小写不敏感 matcher。

本轮未运行 assemble、设备/instrumentation、network/API 或真实 Agent 请求；因此不新增 APK、设备、Provider 和外部服务证据。

## 19. 阶段 7 证据返修验证

验证日期：2026-08-29；范围：仅补强 Run-scoped interlock、前后台 executor 事件流、Terminal plugin 组合顺序和 exact matcher 的 JVM 证据。

TDD 证据：

- `DemoScheduledTaskPromptExecutorTest` 首次 focused 编译为 RED：测试所需的 runtime/conversation/provider 注入 seam 尚不存在；接入最小 seam 后，持久化路径又暴露 Android JVM `JSONObject.put` stub，改为测试 outcome writer 后 GREEN。
- `TerminalAgentPluginCompositionTest` 首次 focused 编译为 RED：既有 public Context 构造没有可注入的真实 `BashCommandTool` 组合 fixture；改用测试侧反射 private `Components`/primary constructor 后 GREEN，未增加新的 public/internal production constructor。
- coordinator 生命周期和 exact matcher 属于对已有实现的独立证据复核，首次 focused 运行即 GREEN；没有为制造 RED 而回退已满足契约的生产语义。

最终命令：

```powershell
.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :demo-app:testDebugUnitTest `
  --console=plain

.\gradlew.bat :demo-app:compileDebugKotlin --console=plain
```

结果：`BUILD SUCCESSFUL`；测试报告为 `ugk-pi-android 122/122`、`pi-terminal-skill-android 19/19`、`pi-system-skill-android 42/42`、`demo-app 104/104`，均无 failures/errors/skipped。`git diff --check` 通过；Terminal 模块及 runtime `AGENTS.md` 无 screen 专用符号，未发现 `startsWith("screen_")`，foreground/background 使用同一 Demo exact matcher/interlock seam。

本轮未运行 assemble、设备/instrumentation、network/API、真实 Provider 或真实 Native Bash；阶段 8 已补齐 assemble、Release package 和原生静态验收，但仍未新增设备/network/API 证据。

## 20. 架构整改阶段 8 全项目收束验证

验证日期：2026-08-29；实现基线：`main@9268bc2789c561f121fc992031fcd29466d0705e`。本节记录阶段 1—7 架构整改完成后的本机验证；不代表正式发布或设备矩阵关闭。

JVM 回归：

- 八个 SDK/Runtime 模块的 `testDebugUnitTest` 全部成功，XML 合计 `271` 个测试、`0` failure、`0` error、`0` skipped；其中 `ugk-terminal-runtime-android` 当前为 `NO-SOURCE`。
- 分模块为：Core `122`、File `9`、Schedule `9`、Task Runtime `7`、System `42`、Agent Skill Runtime `62`、Terminal Runtime `0`、Terminal Skill `20`。
- `demo-app:testDebugUnitTest` 另有 `104/104` 通过。

构建与消费：

- `:demo-app:assembleDebug` 成功。
- `:ugk-pi-android`、`:ugk-terminal-runtime-android`、`:pi-terminal-skill-android`、`:pi-system-skill-android`、`:pi-agent-skill-runtime-android` 的 Release AAR 均重新生成成功。
- `:terminal-probe-demo-a:assembleRelease` 与 `:terminal-probe-demo-b:assembleRelease` 成功。
- 补充检查曾运行 `scripts/sdk/verify-core-consumer.ps1`，并以 `-Dmaven.repo.local=<任务临时目录>` 隔离 publication 与 consumer 构建，没有写用户全局 Maven repository。但该脚本内部仍调用名为 `publishReleasePublicationToMavenLocal` 的 Gradle task，违反本阶段“不得调用 publishToMavenLocal”的字面约束，因此该结果只作为非 Gate 补充事实，不计入阶段 8 接收条件；阶段 8 的 Core 接收证据以 Release AAR inventory、`javap` 和 JVM tests 为准。

Terminal Runtime 静态/包验收：

- `verify-runtime.ps1 -CheckPackages` 验证两 ABI 的 62 个 native payload、54 个 CPython extension、613 个标准库条目、4 个 CMake bridge、Release AAR 与两个 Release Probe APK，最终输出 `Terminal Runtime payload verification passed.`。
- 验收首次在 `zh-CN` PowerShell 暴露 extension tree 哈希误报：脚本使用文化相关 `Sort-Object Name`，而锁文件按 ordinal 名称顺序生成。验证两 ABI 以 `StringComparer.Ordinal` 排序后精确还原锁值，脚本已改为显式 ordinal 排序；二进制和 `runtime-lock.json` 未变。
- 旧 Release AAR/APK 曾因阶段 7 后 CMake bridge 尚未重建而与当前源文件大小不符；重新生成 Runtime AAR 与两个 Probe Release APK 后包内容、SHA-256 和 `zipalign -P 16` 全部通过。旧产物不作为本轮证据。

Core API/JVM 边界：

- 当前 Release AAR inventory 为 `122` 个 class file、`64` 个顶层 class、`89` 个 `javap -public` type declaration、`82` 个审查口径 source-facing public type、`800` 个 public member signature。
- `AgentSession`/transcript policy、capability assembly provenance/resolver overload、通用 Tool decorator/interlock 均在当前 AAR 可见；`CompositeAndroidSkillProvider` 与 `RuntimeSkillAccumulator` 虽存在于 bytecode，但 class declaration 为 package-private，不是公共 consumer seam。
- 当前 Gradle 只显式固定 JVM target 17，没有显式 `jvmDefault` 配置；`javap` 同时可见 interface default method、`DefaultImpls` 与 `$jd` bridge。没有带可信版本元数据的旧发布 AAR和升级 consumer 测试，因此本轮只能报告当前表面，不能宣称对旧二进制或源码完全兼容。D-023/D-024 已记录有意的 `0.x` source/semantic change。

静态边界：`git diff --check` 通过；Terminal 生产源码与 runtime `AGENTS.md` 不含 screen capability 知识，仓库没有 `startsWith("screen_")` workflow matcher，前后台复用同一 Demo exact matcher/interlock seam。

未执行：设备/instrumentation、ADB、真实网络、真实 Provider/API、发布到远程仓库、tag/push/PR。物理设备与 Release AAB/升级/低资源/16KB 剩余项继续沿用本文件 Gate 总表和未覆盖矩阵，不能因本节静态通过而关闭。

## 21. Demo 0.7.1 架构稳定化版本保存

验证日期：2026-08-29；阶段基线：`885c1e9`；范围仅为 Demo patch 版本元数据、canonical 文档和本地版本边界，不改变 Terminal 原生载荷、Core publication、依赖、权限或运行时行为。

- `0.7.1 / versionCode 9` 更新后运行九个模块的 `testDebugUnitTest` 与 `:demo-app:assembleDebug`，`BUILD SUCCESSFUL`。
- XML 合计 `375/375`，0 failure/error/skipped：八个 SDK/Runtime 模块 `271`，Demo `104`；`ugk-terminal-runtime-android` 为 `NO-SOURCE`。
- `aapt2 dump badging` 确认 APK 为 `com.ugk.pi.android.testapp`、`versionCode='9'`、`versionName='0.7.1'`。
- 设备事实：同一架构整改代码已在版本元数据仍为 `0.7.0 / 8` 时安装到授权小米 `QSG6Q8IFDMDELVGQ`（`2602BRT18C`）并启动，用户完成体验测试并反馈无明显问题。`0.7.1` 只调整版本元数据与文档，本轮不重复安装或设备测试。
- 未运行 instrumentation、真实网络、真实 Provider/API、Terminal 原生 `-CheckPackages` 或 Release 包矩阵：本次没有触及对应代码/载荷，前四项不是 patch 元数据保存的必需 Gate；原生与 Release 证据继续使用第 20 节，但不得泛化为新设备矩阵。
- 不 push、不创建 PR、不发布远程 Release；本地 commit/tag 是本次唯一版本边界。

## 22. Demo 0.9.0 文件型 skill 单文件 authoring MVP 版本保存与最终验证

验证日期：2026-08-30；阶段基线：`demo-app-v0.8.0` / `79b0d31`；最终门禁与独立 closeout review 已通过。范围仅为文件型 skill authoring MVP、Demo 版本元数据和 canonical 文档对齐，不改变 Terminal 原生载荷、SDK publication `0.1.0`、依赖、权限、UI 基线或 Release Gate。版本边界标签为 `demo-app-v0.9.0`，远端状态以 Git 实测为准。

- 版本元数据为 `0.9.0 / versionCode 11`，版本边界标签为 `demo-app-v0.9.0`。
- 最终门禁命令覆盖八个 SDK/Runtime 模块的 `testDebugUnitTest`、`:demo-app:testDebugUnitTest`、`:demo-app:assembleDebug` 和 `:demo-app:compileDebugAndroidTestKotlin`，Gradle 输出 `BUILD SUCCESSFUL`，共 244 actionable tasks。
- XML 证据：八个 SDK/Runtime 模块合计 `279/279`（Core 122、File 9、Schedule 9、Task Runtime 7、System 42、Agent Skill Runtime 70、Terminal Runtime 0 `NO-SOURCE`、Terminal Skill 20）；Demo `107/107`；总计 `386/386`，均为 0 failure、0 error、0 skipped。
- `aapt2 dump badging` 确认 `com.ugk.pi.android.testapp`、`versionCode='11'`、`versionName='0.9.0'`；APK 包含 `assets/agent-skills/android-skill-creator/SKILL.md`；`git diff --check` 通过。
- 独立 closeout review 结果为 `PASS`。
- 前置设备事实：功能代码的 `0.8.0 / versionCode 10` APK 已以 `adb install -r -d` 覆盖安装到授权小米 `QSG6Q8IFDMDELVGQ`，未卸载、未清理数据；只核对安装与 package metadata。`0.9.0` APK 本阶段未安装。
- 尚未运行真实 Agent 人工 create/update/delete/use end-to-end、`connectedDebugAndroidTest`、真实 Provider/API、Terminal Runtime `-CheckPackages`；这些边界不等同于真实 skill 行为或 Terminal 发布 Gate 已关闭。远端同步状态以 Git 实测为准。

## 23. Demo 0.9.1 API 24 文件边界与 Intent 稳定性修复保存

验证日期：2026-08-30；阶段基线：`demo-app-v0.9.0` / `38a5603`；PR #2 head 为 `5bfc44f`，merge commit 为 `771fa4e`。范围仅为 API 24 文件路径边界、symlink 根目录逃逸、Intent data/type 保留、回归测试、Demo patch 版本元数据和 canonical 文档对齐；不改变 Terminal 原生载荷、SDK publication `0.1.0`、依赖、权限、UI 基线或 Release Gate。

- 版本元数据为 `0.9.1 / versionCode 12`，版本边界标签为 `demo-app-v0.9.1`。
- 八个 SDK/Runtime 模块与 Demo JVM 共发现 `388` 个测试：`387` passed、`1` skipped、0 failure/error；跳过项是 Windows 主机无法创建 symlink 的 `AppPrivateFileToolsTest.rejectsSymlinkToSimilarPrefixSibling`。
- `:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 与 `git diff --check` 通过；APK metadata 为 `com.ugk.pi.android.testapp` / `versionCode 12` / `versionName 0.9.1`，并包含 `assets/agent-skills/android-skill-creator/SKILL.md`。
- `pi-file-skill-android` 与 `pi-agent-skill-runtime-android` 的 `lintDebug` 为 0 error；`pi-system-skill-android` 的 8 个 Accessibility `NewApi` error 均在未改动的 `AccessibilityScreenAutomationBackend.kt`，本阶段改动的 `AndroidAppIntentTool.kt` 没有新增 lint finding。该既有 lint baseline 不作为本 patch 的新失败。
- PR 说明记录 API 24/API 35 的文件边界、skill embed 与 Intent data/type targeted dynamic evidence；closeout 未重复操作真机、未运行真实 Provider/API、Terminal `-CheckPackages` 或 Release 矩阵，不据此改变既有设备与发布 Gate 结论。

## 24. Demo 0.9.2 第二轮 P0 审查修复保存与合并验收

验证日期：2026-08-31；阶段基线：`demo-app-v0.9.1` / `9340d6f`；PR #3 head 为 `741649d`（`c1b4ce7` 代码 + `741649d` 验证措辞修正），merge commit 为 `b91f1a8`。范围仅为 14 项修复（Anthropic 协议 role 交替与 thinking 块、工具循环异常恢复、会话/任务存储并发与数据一致性、skill 扫描边界与 frontmatter 重复 key、原子写、demo 进程级 runtime 所有权与会话追加、`stopAll` 记录保留）与 Demo `0.9.2 / versionCode 13` 版本元数据；不改变 Terminal 原生载荷、SDK publication `0.1.0`、依赖、权限、UI 基线或 Release Gate。

- 版本元数据为 `0.9.2 / versionCode 13`，版本边界标签为 `demo-app-v0.9.2`；逐项修复说明与 PR 自报证据见 `docs/demo-app-version-ledger.md` 0.9.2 条目。
- 合并验收复核（2026-08-31）：merge-base 恰为 `9340d6f`、恰含声明的 2 个提交、`git diff --check` 干净；在临时 worktree（PR head）复跑九模块 `testDebugUnitTest` 与 `:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 为 `BUILD SUCCESSFUL`（244 actionable tasks）；62 个结果 XML 重算合计 `427` 个测试：`424` passed、`3` skipped、0 failure/error（3 个 skip 均为 Windows symlink 限制，其中 skill 扫描 2 项由仪器用例覆盖）。
- APK metadata 复核：`com.ugk.pi.android.testapp` / `versionCode 13` / `versionName 0.9.2`、`minSdk 24`、`targetSdk 36`，并包含 `assets/agent-skills/android-skill-creator/SKILL.md`。
- 独立 reviewer 六维度审查（需求完整性/逻辑正确性/边界/代码质量/测试覆盖/实际运行与文档一致性）：`PASS`，0 BLOCKING、0 MAJOR、4 MINOR、6 NOTE；33 个改动文件全部落在声明范围内，无 scope creep，未触碰 Terminal v1 scope、打包、权限边界或 Gate 退出条件。MINOR 项（前台 fallback 复活已删会话的理论路径、原子写固定 tmp 名并发交互、损坏备份单槽、catch Throwable 波及 Error）与 PR 自报遗留项一并记录，不阻塞本保存。
- PR 声明的 API 35 x86_64 模拟器 `connectedDebugAndroidTest 28/28`（含 skill 扫描 symlink 红绿闭环取证）本机无该模拟器未复跑，以 PR 说明与版本台账记录为准；demo #12 Activity 重建端到端行为仍待真机/模拟器人工验收。
- 本 closeout 未操作真机、未运行真实 Provider/API、Terminal `-CheckPackages` 或 Release 矩阵，不据此改变既有设备与发布 Gate 结论；`0.9.2` APK 本阶段未安装到设备。

## 25. PR #4 测试套件清理与防测试 Workspace 泄漏收束验收

验证日期：2026-08-31；阶段基线：`66d2abfdffd06fb9207b630b98994f2756dce1bb`；PR #4 head 为 `ac2c3f929b85df6d66244d35a306a905ae6cfa13`（含 `d14901d` 与 `ac2c3f9`），merge commit 为 `7dc1b7c17e3503a9aa528cc3088b27b518d58b8a`。范围仅为测试套件冗余清理、防测试 workspace 泄漏、被删测试契约覆盖恢复与测试编码修复；不改变生产代码行为、依赖、Gradle/Android 配置、Terminal 原生载荷、Demo 版本元数据（仍为 `0.9.2 / versionCode 13`）或 Release Gate。

- 测试清理与覆盖恢复内容：
  - `demo-app`: 删除冗余的 `MainActivityLifecyclePolicyInstrumentedTest`（JVM `AgentOverlayPolicyTest` 已有等价覆盖）；删除冗余的 `ContextCompactionBoundedTranscriptTest`，将边界裁剪关键用例合入 `ContextCompactorTest`（测试数 116→112）。
  - `demo-app`: 修复 `AndroidAutomationAgentIntegrationInstrumentedTest` 中的 UTF-8 编码乱码（`"鎵撳紑杩欎釜搴旂敤"` -> `"打开这个应用"`）。
  - `ugk-pi-android`: 删除与 `AgentRuntimeCapabilityAssemblyTest` 重复的 `AgentRuntimeBuilderLiveSkillProviderTest`，并在 `AgentRuntimeCapabilityAssemblyTest` 中保留多 provider 与 dynamic provider 每 run 查询验证（测试数 128→127）。
  - `pi-terminal-skill-android`: 为 `BashCommandToolTest` 与 `TerminalAgentPluginCompositionTest` 增加 `@After cleanUpWorkspaces()`，尝试清理测试记录的所有临时 workspace，若删除失败则令测试失败以防静默泄漏。
  - `pi-agent-skill-runtime-android`: `AgentSkillSeederTest` 解耦具体 `agent-memory` 路径，改用通用 `sample-skill` 夹具。
- 门禁验收（2026-08-31）：
  - 运行九模块 `testDebugUnitTest` 与 `:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin`（`--rerun-tasks --console=plain`），Gradle 输出 `BUILD SUCCESSFUL`（244 actionable tasks: 244 executed）。
  - JUnit XML 汇总：九模块合计 `422` 个测试：`419` passed、`3` skipped、`0` failure、`0` error。
    - `demo-app`: 112 passed / 0 skipped / 0 failed / 0 error
    - `pi-agent-skill-runtime-android`: 78 passed / 2 skipped（Windows symlink 限制） / 0 failed / 0 error
    - `pi-file-skill-android`: 12 passed / 1 skipped（Windows symlink 限制） / 0 failed / 0 error
    - `pi-schedule-skill-android`: 11 passed / 0 skipped / 0 failed / 0 error
    - `pi-system-skill-android`: 42 passed / 0 skipped / 0 failed / 0 error
    - `pi-terminal-skill-android`: 20 passed / 0 skipped / 0 failed / 0 error
    - `ugk-agent-task-runtime-android`: 17 passed / 0 skipped / 0 failed / 0 error
    - `ugk-pi-android`: 127 passed / 0 skipped / 0 failed / 0 error
    - `ugk-terminal-runtime-android`: `NO-SOURCE`
  - Workspace 泄漏检查：本次门禁前后差集 `LEAKED_COUNT=0`，比对确认测试前后没有新增匹配的 `ugk-terminal-*` 临时目录（此项仅证明当次运行无新增泄漏，不代表 TEMP 目录无历史残留）。
  - `git diff --check` 通过。
- 边界与未执行：本阶段为纯测试套件清理与防泄漏治理，未触碰生产代码；未操作真机、未运行真实 Provider/API、Terminal `-CheckPackages` 或 Release 矩阵，不改变既有设备与发布 Gate 结论。

## 26. Demo 0.9.3 输入区/附件体验与多图方案 A 保存验收

验证日期：2026-09-01；阶段基线：PR #4 收束后的 `66d2abf`（远端 `5120709` docs closeout 之上）；版本保存 commit 为 `1170268`（`feat(demo-app): enhance composer and multi-image flow`），已快进推送到远端 `main`。范围仅为 `:demo-app` 输入区与附件体验优化（输入垂直对齐、去除贴边横线、附件信息上移可移除、"已导入"提示修正、进入设置不闪现悬浮球、会话历史底部 Bottom Sheet，新增 `material 1.13.0` 依赖）与多图方案 A（相册批量选择/相机追加、最多 4 张、横向待发送缩略图、单张删除、全屏预览、按顺序发送、历史持久化兼容旧单图数据），以及独立审查关闭的图片异步跨会话竞态、历史缩略图 Bitmap 内存峰值、毫秒文件名碰撞三类问题；不改变 Terminal 原生载荷、SDK publication `0.1.0`、权限或 Release Gate。

- 版本元数据为 `0.9.3 / versionCode 14`，applicationId 仍为 `com.ugk.pi.android.testapp`；版本边界标签 `demo-app-v0.9.3` 已创建，指向 `1170268`。
- 门禁：九模块 JVM 62 个结果 XML 合计 `441` 个测试：`438` passed、`3` skipped、0 failure/error（3 个 skip 与 PR #4 收束基线相同，均为 Windows 主机 symlink 限制）；`:demo-app:testDebugUnitTest`、`:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 使用 `--max-workers=1` 通过（Windows 并行测试 JVM 曾触发 `errno=1455` 虚拟内存不足，属环境限制）；`git diff --check` 通过。
- APK metadata：`com.ugk.pi.android.testapp` / `versionCode 14` / `versionName 0.9.3` / `minSdk 24`。
- 真机验收：`0.9.3` Debug APK 覆盖安装并启动到授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`，Android 16 / API 36），用户完成界面/功能人工测试并明确回复"测试通过"；本轮真机验收为用户手动操作，无自动外部 API 请求。
- 独立审查（Luna）对多图与附件流程的最终结论为 `PASS`；三类修复（图片异步跨会话竞态、历史缩略图 Bitmap 内存峰值、毫秒文件名碰撞）均已关闭。后续第三轮审查的独立补审亦为 `PASS`（见第 27 节）。
- 边界与未执行：本轮没有 connected AndroidTest 结果目录（AndroidTest 仅完成 Kotlin 编译）；未运行 Terminal `-CheckPackages` 或 Release 矩阵，不改变既有设备与发布 Gate 结论。

## 27. Demo 0.9.4 第三轮 P0 审查修复保存（SDK 协议/并发/性能 + 终端进程组契约）

验证日期：2026-09-01；审查与修复范围：`main@1170268`（正式保存的 `0.9.3 / versionCode 14`，标签 `demo-app-v0.9.3`，保存验收见第 26 节）之上的第三轮 P0 审查。本轮不改变 Terminal v1 scope、原生载荷、打包方式或权限边界；Release Gate 状态不变。

- 修复项（每项均有先红后绿的复现用例；本轮新增 7 个 JVM 测试类共 17 个用例 + 1 个仪器用例，其中 10 个为缺陷复现用例，修复前失败取证见 PR 说明）：
  - `ugk-pi-android`：`terminalForTurn` 空白完成不再持久化空白 Assistant（Anthropic 空 content 数组 400 永久坏档，双层防御）；空白 run 输入入口拒绝；两 Provider 截断/损坏 tool 参数丢弃而非伪造 `{}` 执行；`postStream` 取消即断连（原阻塞至 180s 读超时）+ 单行 `maxResponseBytes` 上限。
  - `ugk-agent-task-runtime-android`：`handle()` 写回前重读记录，执行期间并发 cancel/update 不再被过期快照覆盖（取消任务复活、改期回滚）；残余毫秒级窗口在代码注释中声明。
  - `pi-schedule-skill-android`：`nextRunAtMillis` 溢出降级 null、返回值非负（防 AlarmManager 负值立即触发热循环，敌意持久化数据路径）。
  - `pi-agent-skill-runtime-android` / `pi-file-skill-android`：`writeTextAtomically` 唯一临时名 + 删除“先删目标再拷贝”兜底（并发写可删除整个文件的缺陷，Windows 上确定性复现）；`AgentSkillSeeder` 种子临时文件唯一化（Linux 交错下可损坏种子 skill 的加固，本机未运行时复现）。
  - `ugk-terminal-runtime-android`：bash 调用自然退出清扫进程组残余后台进程（SDK runtime `AGENTS.md` 契约强制，堵住绕过 `local_http_server_*` 确认门禁/数量上限与默认端口砖化）；`PythonDistribution` 全量校验后 `.verified` 指纹短路（原每次调用 SHA-256 校验 613 文件约 10.8MB 且持锁串行）；`LocalHttpServerManager` 无 handle 过期不监听记录惰性清理、不对可能被复用 pgid 盲目发信号（加固，无专用新用例，既有仪器用例全绿）。
  - `demo-app`：`onSaveInstanceState` 移除过期快照整会话覆盖（抹后台轮次）；`saveAndFlush`/`appendMessagesAndFlush` 同步 `commit()` 落盘（原 `apply()` 与防丢声明不符）。
- 门禁验收（2026-09-01）：
  - `test`（全模块 JVM）：`BUILD SUCCESSFUL`，JUnit XML 汇总 `138` 个测试类、`916` tests：`910` passed、`6` skipped、0 failure/error；skip 均为 Windows symlink 限制的既有用例。基线（`main@1170268`）为 `124` 类 / `882` tests / 0 failure，本轮净增 34 个测试全部通过（含 10 个先红后绿的缺陷复现用例）。
  - > **2026-09-28 更正（追加，原文保留于上）**：上一行的 `138 类 / 916 tests` 与基线 `124 类 / 882 tests` 经按 `@Test` 逐提交实测核对为**失实记录**——`main@1170268` 实测 `441` tests / `63` 类，第三轮分支头 `eb750a8` 实测 `458` tests / `70` 类；在一个只增不减的套件上先 `916` 后（第四轮）`560` 不可能成立，疑为当时 XML 汇总跨模块重复累计。当轮的合并内容、缺陷修复与设备验收不受影响；自 §28 起的分模块实测口径（§31 起 `--rerun-tasks` 全量实跑）为准。
  - `:demo-app:connectedDebugAndroidTest`（AVD `codex_api35`，API 35 x86_64，page size 4 KB）：`28/28` 通过、0 failure、0 skipped，含新增 `TerminalBackgroundProcessCleanupInstrumentedTest.naturalExitTerminatesBackgroundChildrenOfTheCall`——真实原生运行时上验证 bash 后台子进程随调用结束被清扫。
  - `:demo-app:assembleDebug` 通过；APK metadata `versionCode 15 / versionName 0.9.4`；`git diff --check` 通过。
- 边界与未执行：本轮未操作真机、未调用真实 Provider/API、未跑 `-CheckPackages` 与 Release 矩阵；arm64（尤其 16 KB）Gate 状态不变。遗留项（demo 主线程位图解码、相册 URI 权限过期、Activity 重建丢失待发图片与拍照文件、`handle` 残余毫秒级竞态、`local_http_server` 跨实例元数据共享、非 SSE 后端 pretty-print JSON 容错等）记录于 PR 说明与版本台账 0.9.4 条目，不宣称已解决。

## 28. Terminal Tool 错误路径 JVM 测试与错误映射完善（开发计划 P1 第 1 项）

验证日期：2026-09-03；源码状态：`cockpit/work/4b5fdb3d2d0de585` 分支工作区未提交改动（含本轮改动）。本轮为 JVM 单元测试与错误映射完善，不改变 Terminal v1 scope、原生载荷、打包方式或权限边界；设备矩阵、网络与 Release Gate 状态不变。

- 行为改动（LLM 可见）：
  - `terminal_bash_execute` 与 `local_http_server_*` 的错误结果统一为纯文本 content（`"<CODE>: <message>"`），metadata 恒为 `{code, message}`；此前 local HTTP 工具的错误 content 为 JSON `{"error": ..., "message": ...}` 且 metadata 只有 `code`。
  - 负 exit code（信号终止）时，`terminal_bash_execute` 的 content 在 9 字段 JSON payload 后追加一行解释（如 `exitCode=-9 ... signal 9 (SIGKILL)`，并说明 Runtime 自身的超时/取消/调用结束清扫也会产生该类终止）；payload 仍为恰好 9 字段，不新增字段。
  - SDK runtime `AGENTS.md` 的 "Reporting failures" 段补充负 exit code 信号终止含义（见 D-027）。
  - `local_http_server_start/status/stop` 的 tool description 补充纯文本错误格式说明。
- 测试：`pi-terminal-skill-android` 新增 19 个 JVM 测试（`BashCommandToolTest` 12→26、`LocalHttpServerToolTest` 4→9），覆盖：timedOut payload、exitCode=null 且未超时、负 exit code 信号解释与 9 字段 payload 锁定、MISSING_SCRIPT（缺失/空/空白）、INVALID_TIMEOUT（0/负数/超 policy 上限拒绝，1 与上限接受）、INVALID_WORKSPACE_PATH（绝对路径/反斜杠/`.` 段/`..`，均断言 metadata code）、INVALID_ENVIRONMENT（33 项、非法名字；32 项与 4096 字节边界接受）、IOException/IllegalArgumentException/IllegalStateException 映射（含无 message 回退类名）、local HTTP 的 PORT_IN_USE/TOO_MANY_SERVERS/START_FAILED/STOP_FAILED/INVALID_INPUT/LOCAL_HTTP_SERVER_FAILED 兜底（含无 message 回退类名）、既有取消测试补 CANCELLED code 断言。
- 命令与结果（均加 `--max-workers=1`，`JAVA_HOME=E:\Android\Android Studio\jbr`）：
  - `.\gradlew.bat :pi-terminal-skill-android:testDebugUnitTest --console=plain --max-workers=1`：`39/39` 通过，0 failure/error/skipped（`BashCommandToolTest` 26、`LocalHttpServerToolTest` 9、`TerminalAgentPluginCompositionTest` 4）。
  - 全模块 JVM（第 3 节八个模块命令 + `:demo-app:testDebugUnitTest`）：`BUILD SUCCESSFUL`，合计 `506` tests、`3` skipped（既有 Windows symlink 限制用例，分布于 File Skill 1、Agent Skill Runtime 2）、0 failure、0 error；分模块：Core 136、File 13、Schedule 14、Task Runtime 19、System 42、Agent Skill Runtime 83、Terminal Runtime 0（`NO-SOURCE`）、Terminal Skill 39、Demo 160。
- 边界与未执行：未运行设备/connected 测试、`-CheckPackages`、Release 矩阵或真实 Provider/API；本轮不关闭任何 Gate。

## 29. 第四轮 P0 审查修复（分支 fix/p0-review-round4-20260903）

验证日期：2026-09-03；源码状态：分支 `fix/p0-review-round4-20260903`（基于 `cockpit/work/4b5fdb3d2d0de585` = origin/main + D-027 两提交）。全部缺陷先由独立验证工以复现测试实证（含突变敏感性验证），修复后复现测试翻转为回归测试；本轮不改变 Terminal v1 scope、原生载荷或权限边界（`local_http_server` 的 token 门禁见 D-028，属安全边界收紧而非放宽）。

- 修复清单与证据（缺陷在 HEAD `a2e451b` 均先被复现测试证实）：
  - `ugk-pi-android`：第三方工具回显错误 `toolCallId` 时 Runtime 归一化信封 id（原会永久砖化会话）；SSE 流式累计字节封顶 `maxStreamedBytes` 默认 8 MiB（原流式路径无总量上限，已实测 8MB 无截断）；OpenAI 非流式非对象 `arguments` 改为丢弃（对齐流式"丢弃不伪造"，原伪造 `{"value":...}`）；OpenAI 兼容网关 content-parts 数组容错拼接（原抛 `IllegalArgumentException` 杀 run）；incomplete-response 重试重新挂载原始多模态输入附件（推翻旧"一次性"设计：重试提示词要求基于原始输入复现答案，与不可见图片矛盾）。
  - `ugk-agent-task-runtime-android`：恢复循环逐任务隔离（原单个 JobScheduler 失败中止全部后续任务重排，已实证）；`handle()` 全局锁改 per-task 锁（原通知投递被分钟级 prompt 执行串行阻塞，已实证）；构造路径幂等 re-arm（进程内首次初始化收敛 SCHEDULED 任务的平台 armed 状态，自愈"alarm 消费后进程被杀"的断链孤儿）。
  - `ugk-terminal-runtime-android` / `pi-terminal-skill-android`：`local_http_server` 改为 token 门禁 handler（随机 128-bit URL 路径段 + `realpath` 符号链接遏制 + 复用不同目录报 `PORT_IN_USE`，见 D-028；handler 脚本已在本机 CPython 3.14 实测 404/200/逃逸阻断）；`OutputCollector` UTF-8 截断回退到码点边界（原截断尾部产生 U+FFFD）。
  - `demo-app`：trace 写入移出主线程（原每个流式 delta 主线程 open/write/close+stat，常态触发 ANR 风险）；`DemoCapabilityInterlock` ownership 提升为进程级（原前后台各持实例私有状态，D-025 要求的互斥跨实例失效；`AndroidAutomationAgentPlugin` 新增与 Terminal 相同的 D-025 装饰缝）。
  - 文档：README 版本行 1.0.5、根 AGENTS.md 包名表述、HANDOVER 历史快照声明、validation 第 3 节历史措辞、troubleshooting 重复编号修正。
- 命令与结果（`JAVA_HOME=E:\Android\Android Studio\jbr`，`--rerun-tasks` 强制重跑）：
  - 全模块 JVM（第 3 节八模块命令 + `:demo-app:testDebugUnitTest`）：`BUILD SUCCESSFUL`，合计 `560` tests、`3` skipped（既有 Windows symlink 限制用例）、0 failure、0 error；分模块：Core 157、File 13、Schedule 14、Task Runtime 27、System 42、Agent Skill Runtime 83、Terminal Runtime 17（新增 JVM 测试源集）、Terminal Skill 40、Demo 167。基线（`a2e451b`）为 `506/3/0`。含独立审查（六维）两轮通过后按 P2/F 清单修复的 7 项加固：冷启动 re-arm 让位与跳过在途任务、自定义 transport+maxStreamedBytes 快速失败（含四组合直接单测）、interlock 决策点原子获取、`reasoning_content` 数组容错、`TerminalAgentPlugin.TOOL_NAMES` 单一事实源（含 tools() 一致性断言）。
  - `:demo-app:compileDebugKotlin`、`:demo-app:compileDebugAndroidTestKotlin`：通过（`local_http_server` token URL 的仪器测试断言已同步更新，无 token 请求断言 404）。
  - `:demo-app:connectedDebugAndroidTest`（AVD `codex_api35`，API 35 x86_64，page size 4 KB）：两次通过（P2 加固前与最终代码状态各一次），均 `28/28`、0 failure、0 skipped，含更新后的 `LocalHttpServerManagerInstrumentedTest`（token 路径 200、无 token 404、进程组停止）与全部终端/无障碍集成用例。
- 边界与未执行：probe A/B 仪器用例未跑（无 local_http 用法，不受 token 影响）；未跑 `-CheckPackages`、Release 矩阵或真实 Provider/API；不关闭任何 Gate。负 exitCode 设备侧验证（第 28 节遗留）状态不变。

## 30. 第五轮 P0 审查修复（分支 fix/p0-review-round5-20260908）

验证日期：2026-09-08；源码基线：`main@b0f1859`（第四轮收束状态，本机复现门禁 `560/3/0`）。本轮不改变 Terminal v1 scope、原生载荷或权限边界；不关闭任何 Gate。

- 修复清单（每项均有先红后绿复现证据；标注"设备实测"者为 connected 测试取证）：
  - `ugk-pi-android`：OpenAI 流式 `data: {"error":...}` 中途错误事件改为抛出（原先被静默丢弃，截断答案以正常完成收尾并写入会话）；流式 `tool_calls` 省略 `index` 的续传分片并入最近活动 draft（原先制造幻影 draft，真实参数被丢弃、首个工具以伪造 `{}` 执行）；`HttpTransport.postStream` 默认回退实现按行切分（原先整包单发导致两个 Provider 全部事件被丢弃）；两 Provider `parseResponse` 对 200 + 错误 JSON 抛出含 message 的异常（原先解析成空白成功响应）；`maxIterations<=0` 与 `pendingUserMessages` 宿主回调抛错收敛为 `AgentEvent.Failed`（原先裸异常逃逸 flow 破坏失败契约）；`lifecyclePlugins` 加 `@Volatile`；Anthropic 序列化合并相邻 Assistant 消息（Messages API 拒绝连续 assistant 角色，宿主构造的此类会话原先每轮 400 永久损坏）。
  - `ugk-agent-task-runtime-android`：重复任务下次执行锚点改为 max(执行开始, 执行结束)（原先锚定执行开始，执行超间隔时零间隙连转+通知风暴；短执行保持固定频率网格）；`DefaultAgentTaskNotificationSink` 增加 `areNotificationsEnabled()` 与 channel importance 检查（原先 API<33 总开关关闭/渠道禁用时 `notify()` 静默无效仍报成功）。
  - `pi-schedule-skill-android`：`schedule`/`action` 非对象入参返回结构化 `INVALID_SCHEDULE`/`INVALID_ACTION`（原先 `jsonObject` 抛 IllegalArgumentException 逃过错误码通道）。
  - `pi-agent-skill-runtime-android`：`SkillRepository` 新增 `reservedSkillIds`，`skill_save` 撞宿主插件 skill id 返回 `SKILL_NAME_RESERVED`（原先撞名使之后每次 run 技能组装永久失败且 agent 无法自愈）；`memory_write` 默认纳入确认票据硬闸（原工具描述承诺 consent 但无强制，`overwrite=true` 可静默销毁用户记忆；宿主可用 `requireMemoryWriteConfirmation=false` 显式关闭）；frontmatter 解析剥离 UTF-8 BOM（原先 Windows 编辑器产物永久 invalid）。
  - `ugk-terminal-runtime-android`：`LocalHttpServerManager` 元数据 `persist()` 改 staged+rename 原子写（原先进程半写截断即永久端口砖化）；start 达到上限时清扫进程组已死的记录（原先重启后死记录确定性触发 `TOO_MANY_SERVERS`）；start 成功判定从"端口可连"升级为"本服务 token 路径 HTTP 200"（10s 服务预算；raw socket 探测；堵住 bind 竞态下把外来监听者当自己服务器的误报；原先 `waitForPort` 只证明内核层可连）；`close/stopAll/start/status/stop` 改进程级锁（多实例共享服务目录/脚本文件名/端口空间）；失败消息附日志尾部（诊断）。
  - `pi-terminal-skill-android`：`timeoutMillis` 非数值/非原始类型返回 `INVALID_TIMEOUT`（原先静默回落默认超时）。
  - `demo-app`：见 `docs/demo-app-version-ledger.md` 1.0.6 条目（会话 store 互斥、flush 出锁、草稿一致性、`reservedSkillIds` 接线）。
- 已知取证边界：会话 store `delete`/`append` 竞态属结构性数据竞争（代码级实证），设备端 200 轮交错压测在未修复代码上未能确定性复现复活（窗口微秒级），修复以互斥正确性论证 + 新增交错回归用例守护。
- 门禁验收（2026-09-08，`JAVA_HOME=E:\Android\Android Studio\jbr`）：
  - 全模块 JVM（`--rerun-tasks` 强制重跑）：`BUILD SUCCESSFUL`，合计 `585` tests / `3` skipped（既有 Windows symlink 用例）/ 0 failure / 0 error；分模块：Core 169、File 13、Schedule 16、Task Runtime 29、System 42、Agent Skill Runtime 89、Terminal Runtime 19、Terminal Skill 41、Demo 167。基线 `560/3/0`，净增 25 例。
  - `:demo-app:connectedDebugAndroidTest`（AVD `round5_api35`，API 35 x86_64，page size 4 KB）：`30/30` 通过、0 failure（基线 28 + 新增 2）。
  - `:demo-app:assembleDebug` 通过；APK 元数据 `versionCode 106 / versionName 1.0.6`（见版本台账 1.0.6 条目）。
- 独立审查（六维度，只读）结论：有条件 PASS（0 BLOCKING / 0 MAJOR / 3 MINOR / 5 NOTE）。按清单修复后复验：
  - `agent_task_create` 的 `action` 非对象入参统一返回 `INVALID_ACTION`（原先被 `as? JsonObject` 折叠成 `MISSING_ACTION`，与 update 路径不一致），补先红后绿用例；
  - 两个 Provider 的流式"非 SSE 容错分支"对完整 error JSON body 直接抛出 API error（原先被 `runCatching` 吞掉后降级为空白 Completed，经 3 次 incomplete 重试以笼统失败收场并重打 3 次 API），补先红后绿用例；
  - 文档分模块计数修正（即本节数字）；
  - 同批落实审查建议：BOM 处理改为 `\uFEFF` 可见转义；`isTokenServed` 补 `-`/`_` 开头 token 用例；补 OpenAI 侧 `postStream` 默认回退用例；`toAnthropicMessage` 的 Assistant 分支标注"通常由合并路径先消费"（Kotlin when 穷尽性要求保留）。
  - 审查确认不修的已知项：无 `index` 交错续传为已声明的固有限制（注释声明）；自家 server 极慢冷启动超 10s 时 `PORT_IN_USE` 措辞可能误导（行为正确）。
- 边界与未执行：未操作真机、未调用真实 Provider/API、未跑 `-CheckPackages` 与 Release 矩阵；arm64（尤其 16 KB）Gate 状态不变。

## 31. 第六轮 P0 审查修复与第五/六轮合并验收（PR #8、PR #7）

第六轮验证日期：2026-09-27（分支 `fix/p0-review-round6-20260927`，基于 `main@c10773e`，2 提交）；合并验收日期：2026-09-27/28。本轮两 PR 均不改变 Terminal v1 scope、原生载荷或权限边界；不关闭任何 Gate。

- 第六轮修复 5 项（先红后绿 + 突变自检，详见 PR #8 描述）：
  - `ugk-agent-task-runtime-android`：任务收敛一律 `tryLock()`（删除 `skipBusyTasks`）；原先忙兄弟任务的 in-flight prompt 执行会让排后任务拿不到触发器、`jobFinished()` 永不提交，Android 在 deadline 强停服务并丢失 running 预留。
  - `ugk-pi-android`：新增 `SseStreamFraming.kt`（`asSseLines()` 支持跨行发射装配、CRLF 优先、整段 JSON 文档原样保留；单行优先解析 + 缓冲事件上限 1,000,000 字符 fail-closed），两 Provider 的 SSE `data:` 解析改单行优先、多行缓冲拼接；解析失败的行不再静默丢弃（原先截断回答伪装成成功完成并写入会话）。
  - `demo-app`：周期任务引入 `DemoDelayedTaskRound`/`consecutiveFailedRounds`，连续 3 轮未完成即停止并说明；原先失败后无限自我重挂（真机实证 12 秒 11 轮），空内容的 `Completed` 亦计为未完成轮次。
  - `ugk-pi-android` 确认工具：拒绝判定复用"紧邻上下文"规则并新增 `withoutUserDecision`；未识别按钮与宿主生命周期兜底结果不再被误报为"用户已拒绝"。
  - `pi-schedule-skill-android`：`nextRunAtMillis` 对反序列化后的 `intervalMillis <= 0` 返回 null（原先向上取整截断成过去时间，被平台当立即到期紧密重触发）。
- 第六轮分支门禁（2026-09-27 实跑）：全模块 JVM `--rerun-tasks` `595` tests / `3` skipped / 0 failure（Core 171、Demo 172、Task Runtime 40、Schedule 15、System 44、Agent Skill 83、Terminal Runtime 17、Terminal Skill 40、File 13）；设备定向 `DemoDelayedTaskFailureLoopInstrumentedTest` 2/2、`AgentOverlayTranscriptRenderInstrumentedTest` 2/2。
- PR #8 合并验收（2026-09-27，merge `7d00ff0`）：合并前在 `round5_api35`（API 35 x86_64 / 4 KB）独占复跑——分支全量 JVM `595/0/3` 与声称一致；定向 `ProcessPresentationInstrumentedTest` 5/5 无崩溃挂起（前次异常未复现）；全量 `:demo-app:connectedDebugAndroidTest` 44 项中 36 通过 + 8 项 `FloatingConversationInstrumentedTest` 因 AGP 重装 APK 重置悬浮窗 appops 的前置断言失败，重授权后 `adb shell am instrument` 复跑 8/8 通过。合并后 main JVM `605/0/3`（实跑）。
- PR #7（第五轮）合并验收（2026-09-28，merge `c5e78fa`）：`fix/p0-review-round5-20260908`（9 提交，2026-09-08 开）与演进后 main 的 4 处冲突解决后合并——`HttpTransport.postStream` 取第六轮 `asSseLines()` 实现（第五轮"按行切分"语义的超集）；demo 工厂 `reservedSkillIds` 接线扩展覆盖 attention/delay 插件；会话 store 互斥与 flush 出锁移植到 `writeExecutor` 结构（`rename`/`delete` 纳入 store 监视器单临界区，`appendMessagesAndFlush`/`saveAndFlush` 落盘等待移出监视器）；demo `versionCode` 保持 123 不回退。合并分支 JVM `630` tests / `3` skipped / 0 failure（`--rerun-tasks` 实跑，净增 25 与第五轮声称一致；第五/六轮流式测试套件共存全过）；设备 `:demo-app:connectedDebugAndroidTest` `62/62` 一次通过（含第五轮新增 `DemoConversationStoreConcurrencyInstrumentedTest`）。合并后 main 全量 JVM 复跑 `630/0/3`。
- 两 PR 合并后，第五轮的"验证通过但修复未进主干"状态解除；`demo-app` APK 元数据保持 `1.10.0 / versionCode 123`（缺陷修复无新增可感能力，不 bump），见版本台账。
- 边界与未执行：未调用真实 Provider/API、未跑 `-CheckPackages` 与 Release 矩阵；arm64（尤其 16 KB）Gate 状态不变。本地证据：`.verify-shots/pr8-round6-merge-gate-20260927/`、`.verify-shots/pr7-round5-merge-gate-20260928/`（未提交）。

## 32. 第七轮 P0 审查修复（分支 fix/p0-review-round7-20260928）

- 起点：main@`6ac77d4`（PR #7、PR #8 均已合并）。基线全量 JVM 实跑 `630` tests / `3` skipped / `0` failure（Core 183、Demo 182、Agent Skill 89、Task Runtime 42、System 44、Terminal Skill 41、Schedule 17、Terminal Runtime 19、File 13；`pi-attention-skill-android` 仍 `NO-SOURCE`，其 `testDebugUnitTest` 目标不贡献用例）。
- 方法：每条结论先写复现用例，要求在未改主干源码时**红**、修复后**绿**，且每组缺陷都配一条反向对照用例（在主干上即绿，证明断言能区分而不是恒假）。收口前对第七轮新增的 5 个文件级修复再做一次「只回退该文件 → 定向复跑」的红/绿复核，结果与首轮一致。
- 确认票据（安全边界，两处互锁，缺一即可绕过）：
  - `ugk-pi-android` `UserConfirmationRequiredTool`：授权侧从不读 `withoutUserDecision`，拒绝侧却读。宿主自己兜底选出允许集合按钮（`confirm`）并声明「不是用户决定」时，票据结构、session、toolName、input 指纹全部合法，受保护 Tool 照常执行；与 `docs/sdk-confirmation-ticket-contract.md`「票据照常返回但依旧不可执行」及「强度不得不对称」直接矛盾。现两侧同读该标志，并新增第三种回执措辞：对话框未能触达用户时不再回「请调用确认框后重试」（该措辞会把无 UI 的后台回合推回同一个永远等不到答案的对话框，即第六轮拒绝环的同形问题）。
  - `demo-app` `HeadlessConfirmationDialogPresenter`：无 UI 时以 `buttons.last()` 兜底「拒绝」，而按钮集合由模型自己书写；只给一个 `{id:"confirm"}` 按钮就返回允许集合内的 id 并签发真票据。走 `DemoScheduledTaskPromptExecutor`（唯一使用该 presenter 的生产路径）的后台定时任务因此可在无人参与下执行任意受保护 Tool。现无拒绝按钮可读时如实置 `withoutUserDecision=true`；含取消按钮的常规集合行为不变（保留第六轮「不要重复弹窗」措辞）。端到端用例同时依赖上述两处修复，单独回退任一处仍会执行 delegate。
- 定时任务（数据正确性）：
  - `pi-schedule-skill-android` `AgentTaskUpdateTool`：仅改标题时 `nextRunAtMillis = schedule.nextRunAtMillis(now)` 可为 null，而状态仍是 `SCHEDULED`；`AndroidAgentTaskRuntime.schedule` 对该形态的回答是 `cancel(task.id)`，于是**到点未投的 one-shot 被一次编辑静默销毁并返回成功**（Doze 对齐延后触发器是已记录的正常形态）。现拒绝该编辑且不写库、不动已挂机的触发器；create 侧同样补上「无未来发生」的前置断言（用户可设 1970 前 RTC，`runAt` 变负即落入同一形态）。
  - `ugk-agent-task-runtime-android` `AndroidAgentTaskStore`：丢弃 `commit()` 返回值，落盘失败仍上报成功；同模块的 `SharedPreferencesTaskJobIdAssignmentStore` 早已 `check(...)` 并写明同一风险。现主记录写入按同契约失败即抛，工具侧如实报错。`handle()` 的执行回写刻意保留容错：该处触发器已被消费，因记账失败而跳过重挂会杀死周期任务，故记日志后继续。
  - 同模块 `convergeScheduledTasks`：原过滤条件要求 `nextRunAtMillis != null`，使历史遗留（以及被改写/损坏的记录）的 `SCHEDULED`+null 永远不被收敛，却被 `agent_task_list activeOnly=true` 报为存活——用户等不到它，它却一直被算作活跃任务。现收敛对该形态自愈：schedule 仍有未来发生则补回并重挂，否则落 `EXPIRED`；`SCHEDULED`+有效发生的记录一律原样处理，不改写 `updatedAtMillis`。
- Provider 协议健壮性（核心可用性）：
  - `OpenAiChatCompletionsProvider`：流式与非流式对协议可选字段使用抛异常的 `jsonObject`/`jsonArray`。`"delta":null`、`"choices":null`、`"tool_calls":null`、`"error":null` 分别产生 `IllegalArgumentException`、以及把整段 chunk 当错误抛出的 `IllegalStateException`；POJO/Jackson 序列化的网关在**正常成功回答**上就会输出 `"tool_calls":null`，因此一条健康端点即可让回合失败。现统一按安全转换读，null 视为缺席。
  - `AnthropicMessagesProvider`：空串 `tool_use` id/name 被接受并写入 transcript，此后每次请求都因 min-length 校验被真实 API 拒绝，**会话永久不可用**（同模块 OpenAI 侧 `buildFinalToolCalls` 早已显式丢弃空 id，注释亦称同一策略）；非流式 `content:null` 同样抛异常。现两个 provider 的流式/非流式四条路径统一：空 id/名丢弃，`input` 缺席仍是合法零参调用、类型不符则丢弃而不是伪造成 `{}`。
- 技能装配（核心可用性）：`AgentRuntime` 的 `RuntimeSkillAccumulator` 对重复 skill id 直接抛异常。第五轮只在 `skill_save` 侧堵住，并在其回归注释里写明该形态会「brick 之后每一轮，agent 再也用不了」——因为抛异常发生在 tool 循环之前，`skill_delete` 无法被调用。而写入 `agent-skills/` 的通道不止 `skill_save`（终端 bash、私有文件工具、导入包、恢复备份）。现按来源裁决：`FILE_BACKED` 一律让位并保留胜者的原有位置（注入顺序不再取决于谁恰好是文件来源），两个宿主来源冲突仍显式失败。同步修订 `docs/android-agent-skills.md` 两处「任何重复都由 assembly 立即失败」的旧契约与 `CompositeAndroidSkillProvider` KDoc。
- 终端 Tool（确认完整性）：`BashCommandTool` 对 environment 值筛 NUL，却不筛 `script`（成为 `bash -c` 的 argv，C 字符串在 NUL 处截断）与 `workingDirectory`（同属票据指纹覆盖的输入，且其分段检查是精确比较：`"sub"` + NUL + `"../outside"` 拼成的值切分后不存在裸 `..` 段，可绕过分段检查（NUL 截断只会落在 workspace 内的前缀上，不构成越界，但用户确认过的路径与实际使用的路径不再一致）。现两处均拒绝并给出 `INVALID_SCRIPT` / 路径无效回执，且断言命令未达执行器。
- 独立复核（两轮，均为只读、不改代码线程）：
  - 第一轮从「需求完整性/逻辑/边界/反向失败/质量/覆盖」六维审本轮修复，接受的整改：OpenAI 非流式空 id、Anthropic 非流式伪造 `input`、`withoutUserDecision` 的再弹窗措辞环、`workingDirectory` NUL、create 侧同形态、收敛自愈、替换后注入位置漂移，以及一条**在其目标行为上恒真的假覆盖用例**（`[confirm, review_later]` 在主干上即返回 `review_later`）已改为按标志断言。
  - 第二轮专审**第一轮整改本身**（整改代码是本轮风险最高的代码），抓出两处由本轮修复引入的阻塞缺陷并全部整改：① `handle()` 执行回写改用 `runCatching` 容错后会把 `CancellationException` 一并吞掉——`AgentTaskStore` 是公开 API（文档鼓励宿主换 Room/SQLite），被取消的挂起写入被当作记账失败继续重挂与通知，`jobFinished` 还会以 `shouldRetry=false` 交差，与本文件既有约定（`catch (CancellationException) { throw it }`）相反；改为显式重抛取消，并补 `HandleWriteBackCancellationTest`（把重抛改回吞掉即红，证明用例不是摆设）。② 新增的 NUL 筛查只落在 `BashCommandTool`，同模块的孪生输入 `local_http_server_start.directory`（同为模型书写、同为子进程 argv 与服务根目录）未落，`reports` + NUL + `private` 会让子进程实际服务被截断的前缀；已在 `LocalHttpServerManager` 补筛并把纯输入筛查拆成可 JVM 测的函数（`LocalHttpServerDirectoryScreenTest`）。
  - 第二轮同时接受的次级项：`AgentTaskUpdateTool` 回滚顺序（恢复写入抛异常会跳过后继 best-effort 重挂，改为两步各自容错）、退役 `EXPIRED` 时同步撤销平台触发器、`SkillRepository`/`DemoAgentRuntimeFactory` 中仍以「会 brick 后续每一轮」论证 `reservedSkillIds` 的注释（该前提已被本轮装配裁决改动取消，保留守卫但更正理由，避免后来者据旧注释删掉守卫）、`INVALID_WORKSPACE_PATH` 文案补 NUL（否则模型会无限重试同一路径）。
  - 第二轮还订正了本轮自己写进文档的三处失实：「遗留僵尸记录删不掉」（`agent_task_cancel` 一直可用）、`workingDirectory` 绕过示例（`"a` + NUL + `"../../etc"` 切分后有裸 `..` 段，主干也会拒；真实可达示例是 `"sub"` + NUL + `"../outside"`，且截断只落在 workspace 内前缀，不构成越界）、契约文档中「任何拒绝条件不满足都回到需要确认提示」被新增第三种措辞推翻的旧句。
  - 遗留未修（本轮不声称已证实）：冲突文件 skill 被静默丢弃无回执；`reservedSkillIds` 与装配裁决的重叠面；修复 1/2 后 `[Cancel]` 这类大小写不一致按钮仍走「需要确认」提示。
- 第七轮分支门禁（2026-09-28 实跑，收口后最后一次全量）：全模块 JVM `--rerun-tasks` `668` tests / `3` skipped / `0` failure（Core 202、Demo 185、Agent Skill 89、Task Runtime 49、System 44、Terminal Skill 44、Terminal Runtime 22、Schedule 20、File 13），新增 `38` 条用例分布于 12 个新测试类，另有 1 个第五轮用例（`AgentSkillRuntimePluginTest`）按新装配契约改写（净增 0 条）。跳过项仍是 Windows 主机无法建符号链接的 3 条既有用例。
- 边界与未执行：本轮未跑仪器套件——验收期间宿主 C 盘可用空间由 8.7 GB 降至 1.5 GB（已核对非本工作区写入：`~/.android` 最新写入早于本轮构建，Gradle/Kotlin 产物均在 E: 盘缓存），为避免模拟器在满盘上写坏 AVD 而放弃设备通道；上述修复的行为面全部有 JVM 证据，设备面按「未证实回归」登记，合并前建议独占重跑 `:demo-app:connectedDebugAndroidTest`（第六轮口径 62 项）与 `terminal-probe-demo-a/b`。未调用真实 Provider/API；`-CheckPackages`、Release 矩阵、arm64/16 KB 状态不变。
- 遗留风险（本轮未修，已在 PR 说明取舍）：workspace 内**硬链接**可绕过 D-028 的符号链接收敛，把同 UID 任意文件（`shared_prefs` 里的 API key、agent-memory 库）放进本地 HTTP 服务目录——`realpath` 与 canonical 前缀判定对硬链接恒为真，需 `st_nlink>1` 拒绝或改为只读副本，属设备侧验证项；`PythonDistribution`/`LocalHttpServerManager` 的实例级锁在双 `AgentRuntime`（前台 + 后台定时任务）下保护同一进程级路径；`NativeExecutableProcess` 不关闭子进程 stdin，读 stdin 的命令会耗尽整个超时；HTTP 管理器的超时/新鲜度判定使用挂钟；技能 embed 先 `readBytes()` 再比 16 KB 上限（超大文件在分配前即失败）；always/indexed 注入无每轮总量预算；`pi-attention-skill-android` 仍无任何 JVM 用例。

## 33. Python 标准库自愈修复（设备门禁复跑发现，2026-09-28）

第七轮合并门禁的设备复跑（§32 建议项的执行）在 `round5_api35`（API 35 x86_64 / 4 KB）上暴露：`terminal-probe-demo-a` 的 `invokesEmbeddedPythonWithNativeExtensionsAndRepairsItsStandardLibrary` 与 `permitsExecutableMemoryRequiredByEmbeddedJavaScriptEngines` 失败，`main@079b1d1` 基线同样失败（对照实验确认非第七轮回归）。

- 根因：`PythonDistribution` 的 `.verified` 指纹标记（PR #5 第三轮引入的热路径优化，`a4772de`）在标记有效时跳过逐文件 SHA-256 校验；此后任何"文件损坏但标记未失效"的意外（部分写入、位腐、测试篡改）都不会再触发重建——`terminal_bash_execute` 的 Python 永远带着损坏的 `encodings/__init__.py` 启动即崩，`Fatal Python error: Failed to import encodings module`。该缺陷自 2026-09-01 起潜伏：probe 双宿主设备门禁在第四轮后未再执行，直至本轮复跑才首次暴露。类 KDoc「tampered tree is rebuilt on the next invocation」的承诺自该优化起不再成立。
- 修复（`main` 直接提交，2 文件）：`PythonDistribution.invalidateVerifiedMarker()` 删除指纹标记（下次 `home()` 走全量校验/重建，该路径既有）；`BashRuntime.execute` 在且仅在命中「解释器启动即崩」特征——`stderr` 含 `Fatal Python error: Failed to import encodings module` 且非超时——时失效标记、重建受管环境（触发修复）并重试一次。该 abort 发生在任何脚本语句执行之前，重试无用户副作用；普通脚本失败（traceback/非零退出）不匹配、原样返回。
- 复验（`round5_api35` 实跑）：`terminal-probe-demo-a` `10/10`（修复前 `8/10`）、`terminal-probe-demo-b` `5/5`、`:demo-app:connectedDebugAndroidTest` `62/62`（首跑 1 项 UI 断言失败且运行中断，重跑全绿，判定为该 AVD 既有偶发，与本修复无关——同形态曾见于 §31 第六轮验收记录）；全模块 JVM `--rerun-tasks` `668/0/3` 不变。证据：`.verify-shots/pr9-round7-merge-gate-20260928/` 与 `fix-probe-a.log`、`fix-demo-full2.log`（本地）。
- 边界：自愈判据只覆盖实测到的 encodings 导入崩溃形态，`init_sys_streams` 等其他启动期 fatal error 出现时再扩；修复重建路径的实例级锁问题维持 §32 遗留不变。

## 35. 第十轮 P0 审查修复（分支 `fix/p0-review-round10-20261001`，基线 `main@88b05e0`，2026-10-01）

验证日期：2026-10-01；宿主 Windows 10.0.26200 / Git Bash，JDK 17.0.11（`E:\Android\Android Studio\jbr`），Android SDK `E:\Android\SDK`，`GRADLE_USER_HOME=E:\DevCaches\gradle`，宿主 CPython 3.14.2。本轮不改变 Terminal v1 scope、原生载荷或权限边界（D-029 属安全边界收紧）。

### 门禁口径（只认日志 `EXIT=` 行与 JUnit XML 汇总）

独占 `--rerun-tasks` 全量实跑，代码状态 = 分支 HEAD `3977b77`，`git status --porcelain` 为 0 项，运行窗口 `2026-09-30T20:05:32Z … 20:06:22Z`，128 份 JUnit XML 的 `timestamp` 全部落在该窗口内（最早 `2026-09-30T20:05:40.265Z`、最晚 `2026-09-30T20:06:21.180Z`）：

```
EXIT=0
TOTAL tests=843 failures=0 errors=0 skipped=3
MODULE ugk-terminal-runtime-android   tests=49  failures=0 errors=0 skipped=0
MODULE pi-terminal-skill-android      tests=44  failures=0 errors=0 skipped=0
MODULE demo-app                       tests=318 failures=0 errors=0 skipped=0
MODULE ugk-pi-android                 tests=211 failures=0 errors=0 skipped=0
MODULE pi-agent-skill-runtime-android tests=89  failures=0 errors=0 skipped=2
MODULE ugk-agent-task-runtime-android tests=49  failures=0 errors=0 skipped=0
MODULE pi-system-skill-android        tests=50  failures=0 errors=0 skipped=0
MODULE pi-schedule-skill-android      tests=20  failures=0 errors=0 skipped=0
MODULE pi-file-skill-android          tests=13  failures=0 errors=0 skipped=1
```

基线对照：同一台机器、同一命令在 `main@88b05e0` 上独占实跑为 `821 / 3 skipped / 0 failure`。本轮净增 22 项，全部落在 `ugk-terminal-runtime-android`（27 → 49）：`LocalHttpServerHandlerContainmentTest` 7、`LocalHttpServerRecordDispositionTest` 11、`NativeExecutableProcessStdinTest` 3、`TerminalSpawnSiteTest` 1。`pi-attention-skill-android` 仍 `NO-SOURCE`（第九轮已在 `AGENTS.md` 明示为已登记缺口，本轮未变）。

证据文件为 gitignore 的 `build/reviewlogs/`（本机可复核，合并后看不到）：`main-baseline.log`、`delivery-final-rerun.log`、`mut-*.log`、`probe-matrix-round2.txt`。关键输出原文已抄进本节与 D-029 / D-030。

### 实证缺陷与修复

1. **D-028 遏制可被硬链接绕过（P0 安全）**。`os.path.realpath` 对硬链接恒解析为服务树内自己的名字。宿主 CPython 实测：`ln <secret> site/index-copy.txt` 后经 token URL 返回 `200`，响应体即 `API_KEY=sk-should-never-be-served`。修复见 D-029。第一轮整改后复核又实测到两处同形缺口（`send_head()` 自行解析目录 `index.html`；`lstat` 对符号链接跳过链接数判定），均已在宿主复现为 `200` + 泄密正文后修掉。
2. **该安全边界唯一的"把关"是假绿（P0）**。`LocalHttpServerManagerTest` 只用 `script.contains(...)` 断言脚本文本。宿主实测：把遏制条件改成 `if False and ...`（保留全部被 grep 的子串）后该类 16 项测试全部绿色，而同期新增的 interpreter 驱动用例判红。同文件 py_compile 冒烟在"宿主无 python"时直接 `return`，把从未执行的检查计成通过。修复：新增 `LocalHttpServerHandlerContainmentTest`（写盘原样字节 → 起真服务 → 真 HTTP 问它答什么，拒绝一律断言显式 `404`），文本断言降级为结构冒烟并在注释里写明其可被语义破坏骗过，py_compile 改判 JUnit skip。
3. **子进程 stdin 从不关闭（P1）**。`NativeExecutableProcess` 与 `LocalHttpServerManager.start()` 各自 `start()` 后不触碰子进程 stdin；父进程持有的管道永不报 EOF，读 stdin 的命令（`read`/`cat`/`python -`/`openssl passwd -stdin`）会耗尽整段超时并返回空输出，与 `terminal_bash_execute` 自述"非交互脚本"矛盾。全仓 `src/main` 只有这两处 spawn，已收敛到一个 helper；新增源码扫描用例钉住"不许绕过"。
4. **`status()` 删除它只是没探到的记录，`stop()` 对未发信号的记录报 `stopped`（P1）**，且 `status()`/`stop()`/`stopAll()` 用两把尺子问同一件事。修复：一张归属判定表（口径统一为"本进程启动的进程是否还活着"）供四处读取；新增 `unattributable` 状态并同步五处对模型的契约文字。
5. **已核查不可达 / 已登记不修**：`LocalHttpServerManager` 内私有单参 `urlFor(port)`（返回无 token URL）全仓零调用点 → 直接删除；`stopDisposition` 归属存疑时不杀进程组，接受"可能泄漏一个我们自己的孤儿进程组"，这是两种失败里更便宜的一种，已在 KDoc 与 D-030 写明。

### 本轮被否掉的复核立案（附复测命令）

- 「`stop()` 应在句柄已死但进程组仍在时仍发信号，因为该组确由本进程创建」——第十轮第一版整改就是这样写的，复核后由本轮回滚：会话领导者被回收后 pgid 会回到内核池，可能已被同 UID 无关进程组复用，`kill(-pgid, 0)`/信号都可能打死无辜进程。复测：`bash gate/run-targeted-r10.sh m ugk-terminal-runtime-android --tests "*LocalHttpServerRecordDispositionTest"`（`stopNeverSignalsAGroupWhoseOwnLeaderHasBeenReaped` 钉住现口径）。
- 「`lstat` 换成 `stat` 是遏制弱化」——变异实测 `m1c-judge-link-not-target` 显示功能面确有红，但方向是**变严**：`stat` 跟随链接后可能拒掉一个合法的根内符号链接。现口径为 `realpath` 定根 + 对解析结果 `stat()`，两者都不是靠 `lstat`。

### 未证实项（合并前应跑的命令）

1. 设备侧 CPython 3.14.6 上 handler 的实际行为（宿主证据只覆盖同一份脚本字节在 3.14.2 上的判定；`st_nlink`、`index_pages`、目录重定向语义在 Android 上未实测）。合并前：`:demo-app:connectedDebugAndroidTest --tests "*LocalHttpServerManagerInstrumentedTest*"`（需先 `:app:installDebug`，端口 18765）。
2. `unattributable` 新状态在真机生命周期（重启后宽限期已过）下的实际出现路径：同上仪器类扩展一条断言。
3. `spawnWithStdinClosed` 两个调用点在真机上确实关闭了 stdin：`terminal_bash_execute` 跑 `read x; echo got-eof` 应在 1 秒级返回 `got-eof` 而不是耗尽超时。
4. `m4c-stop-wrapper-wiring` 变异为绿：纯表用例看不见调用点装配，句柄口径若在 wrapper 处被改错不会在宿主变红——已登记为本轮测试面的已知局限，只能靠第 1/2 条仪器用例补。
5. 本轮**未运行任何设备/仪器门禁**（宿主 C 盘曾长期满盘；本轮只在 JVM 面取数）。§33/§34 记录的设备数字不得当作当前门禁。
