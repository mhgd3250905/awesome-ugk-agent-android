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

## 34. 第九轮 P0 审查修复（分支 `fix/p0-review-round9-20260930`，2026-09-30）

基线 `main@88b05e0`。本轮对象是 1.11–1.13 新增、前八轮 P0 审查从未触及的教学整理链路（`git log -- <file>` 提交数为 1 的 12 个文件）。本轮所有数字来自同一时点的独占全量运行：`build/reviewlogs/delivery-rerun.log`（`--rerun-tasks`，191 actionable tasks 全部执行，`BUILD SUCCESSFUL in 30s`）与 `build/reviewlogs/delivery-rerun-aggregate.txt`（JUnit XML 汇总），基线对照为 `build/reviewlogs/baseline-main.log` 与 `baseline-main-aggregate.txt`。

- 门禁：`88b05e0` = `821 tests / 3 skipped / 0 failure`（demo 318）；本轮分支 `768a56f` = `834 / 3 / 0`（demo 331，净增 13 项全部为 `DemoTeachingCompilationLimitsTest`；其余八个模块计数不变）。`pi-attention-skill-android` 仍 `NO-SOURCE`；AGENTS.md 原文把它写成“跑全部单元测试（十个）”的一环，本轮已改为明示“九个模块贡献用例、第十项永远不可能变红”的已登记缺口。
- 已实证并修复（每条都有主干可复现的红）：① `DemoTeachingCompiler` 合并循环不收敛——实测 378 次付费请求（6 次笔记 + 372 次合并，每轮翻倍）后才失败且无失败码；② 截图按原图字节预算而请求体是 Base64 文字——实测单次草稿请求携带 16,000,008 字符图像文字；③ 持久 `compiling` 声明只在 `catch (Exception)` 中释放——`OutOfMemoryError` 后记录停在“整理中”，重新整理/续教/删除全部被拒（`PROBE_P5`）；④ 容量守卫只数段数与动作数，实际由 4MB 编码上限先约束——实测 350 动作即失败而非宣称的 600，且报错文案是内部保存失败；⑤ 限额守卫抛无码 `IllegalStateException`，诊断里没有 `failureCode`。
- 本轮修复自身引入并被独立复核抓出的两处（已修）：把②改成“整次整理的编码字符预算”会把截图证据从 12MB 原图砍到约 2.9MB 并静默丢尾部；把④的阈值改为“64KB 余量的总大小”仍会把 `saveGuide`（最大约 400KB）和仅改变状态词的收尾写入一起拒掉。截至本轮采用的规则为：只对**证据增长**计费，并在硬上限下保留 512KB 给整理结果。
- 独立复核否掉/证伪的假设（附复跑命令，避免下一轮重复劳动）：`flow {}` 内在 `withTimeoutOrNull` 中 `emit` 会抛（实跑证明 1/2/3 正常送达、超时返回标记 `IllegalStateException`）；`STREAM_INCOMPLETE` 映射有缺陷（实测正常）；`compilationStatus="compiling"` 守卫是死的（`grep` 证明有写入与清除）；`AgentRuntime` 并发执行 toolCall 导致每轮证据预算竞争（源码为 `toolCalls.forEach` 顺序执行）；`fragmentContext` 可能收到非 JSON 证据（所有 part 都由 `buildJsonObject{}.toString()` 产生）；容量异常逃出工具会杀掉无关对话运行（`executeTool` 捕获 `Throwable` 转 `isError` 结果）。复跑（第十轮取证脚本在仓库外的一次性工作目录里，未入库，此处改为任何检出都能执行的命令）：全量 `./gradlew.bat :ugk-pi-android:testDebugUnitTest … :demo-app:testDebugUnitTest --console=plain --rerun-tasks --no-build-cache`（十项清单见根目录 `AGENTS.md`），定向 `./gradlew.bat :<模块>:testDebugUnitTest --tests "*<类名>*" --console=plain --rerun-tasks`。
- 设备通道未运行（未证实）：宿主 C 盘 100% 满、可用 1.9GB，按既定纪律不启动 AVD。合并前应在真机/AVD 上执行：`:demo-app:assembleDebug`；`:demo-app:connectedDebugAndroidTest`（先 `:app:installDebug` 再用 appops 授 `SYSTEM_ALERT_WINDOW`）；并人工复验短/长教学整理、取消后重试、旧经验读取，以及“记录达到容量后仍能结束教学并保存经验”。
- 本轮登记不修的遗留：`DemoWorkflowCompiler` 与 `DemoOperationStepReviewer` 仍是原图字节预算（单请求最坏约 16,777,216 字符），前者有明确的用户可读预算报错、后者上限为两帧；`loadImages` 会在整个整理期间保留至多 12MB 原图的 Base64 字符串（约 32MB UTF-16），改成按请求惰性编码可消除但未验证；`DemoTeachingHost` 的容量分支与 `Activity` 的整理协程只能由读码+夹具形状证明，Android 侧未实跑。

## 35. 第十轮 P0 审查修复（分支 `fix/p0-review-round10-20261001`，基线 `main@88b05e0`，2026-10-01）

验证日期：2026-10-01；宿主 Windows 10.0.26200 / Git Bash，JDK 17.0.11（`E:\Android\Android Studio\jbr`），Android SDK `E:\Android\SDK`，`GRADLE_USER_HOME=E:\DevCaches\gradle`，宿主 CPython 3.14.2。本轮不改变 Terminal v1 scope、原生载荷或权限边界（D-029 属安全边界收紧）。

### 门禁口径（只认日志 `EXIT=` 行与 JUnit XML 汇总）

独占 `--rerun-tasks` 全量实跑，代码状态 = 分支 HEAD `04eefe0`，`git status --porcelain` 为 0 项，运行窗口 `2026-09-30T20:18:32Z … 20:19:12Z`，全部 JUnit XML 的 `timestamp` 落在该窗口内（最晚 `2026-09-30T20:19:11.700Z`）：

```
EXIT=0
TOTAL tests=844 failures=0 errors=0 skipped=3
MODULE ugk-terminal-runtime-android   tests=50  failures=0 errors=0 skipped=0
MODULE pi-terminal-skill-android      tests=44  failures=0 errors=0 skipped=0
MODULE demo-app                       tests=318 failures=0 errors=0 skipped=0
MODULE ugk-pi-android                 tests=211 failures=0 errors=0 skipped=0
MODULE pi-agent-skill-runtime-android tests=89  failures=0 errors=0 skipped=2
MODULE ugk-agent-task-runtime-android tests=49  failures=0 errors=0 skipped=0
MODULE pi-system-skill-android        tests=50  failures=0 errors=0 skipped=0
MODULE pi-schedule-skill-android      tests=20  failures=0 errors=0 skipped=0
MODULE pi-file-skill-android          tests=13  failures=0 errors=0 skipped=1
```

基线对照：同一台机器、同一命令在 `main@88b05e0` 上独占实跑为 `821 / 3 skipped / 0 failure`。本轮净增 23 项，全部落在 `ugk-terminal-runtime-android`（27 → 50）：`LocalHttpServerHandlerContainmentTest` 8、`LocalHttpServerRecordDispositionTest` 11、`NativeExecutableProcessStdinTest` 3、`TerminalSpawnSiteTest` 1。`3 skipped` 的来源本轮不再唯一：新增 interpreter 用例在宿主无 python 或无链接权限时各自产生 skip（本机全部不 skip，所以仍为 3，且全部来自 agent-skill/file 两个模块）。`pi-attention-skill-android` 仍 `NO-SOURCE`（第九轮已在 `AGENTS.md` 明示为已登记缺口，本轮未变）。

证据文件为 gitignore 的 `build/reviewlogs/`（本机可复核，合并后看不到）：`main-baseline.log`（基线 821）、`delivery-final-rerun.log`（中途态 843）、`delivery-final-r10.log`（交付态 844，本节数字来源）、`mut-*.log`、`probe-matrix-round2.txt`、`probe-matrix-round3.txt`。关键输出原文已抄进本节与 D-029 / D-030。

另登记一次判废取数：`delivery-final-1`（`EXIT=0`、`tests=840`）未加 `--rerun-tasks`，聚合里混用了上一轮 targeted 跑留下的模块 XML 且分模块行缺一项，已作废，改用独占 `--rerun-tasks` 重跑。

### 实证缺陷与修复

1. **D-028 遏制可被硬链接绕过（P0 安全）**。`os.path.realpath` 对硬链接恒解析为服务树内自己的名字。宿主 CPython 实测：`ln <secret> site/index-copy.txt` 后经 token URL 返回 `200`，响应体即 `API_KEY=sk-should-never-be-served`。修复见 D-029。第一轮整改后复核又实测到两处同形缺口（`send_head()` 自行解析目录 `index.html`；`lstat` 对符号链接跳过链接数判定），均已在宿主复现为 `200` + 泄密正文后修掉。
2. **该安全边界唯一的"把关"是假绿（P0）**。`LocalHttpServerManagerTest` 只用 `script.contains(...)` 断言脚本文本。宿主实测：把遏制条件改成 `if False and ...`（保留全部被 grep 的子串）后该类 16 项测试全部绿色，而同期新增的 interpreter 驱动用例判红。同文件 py_compile 冒烟在"宿主无 python"时直接 `return`，把从未执行的检查计成通过。修复：新增 `LocalHttpServerHandlerContainmentTest`（写盘原样字节 → 起真服务 → 真 HTTP 问它答什么，拒绝一律断言显式 `404`），文本断言降级为结构冒烟并在注释里写明其可被语义破坏骗过，py_compile 改判 JUnit skip。
3. **子进程 stdin 从不关闭（P1）**。`NativeExecutableProcess` 与 `LocalHttpServerManager.start()` 各自 `start()` 后不触碰子进程 stdin；父进程持有的管道永不报 EOF，读 stdin 的命令（`read`/`cat`/`python -`/`openssl passwd -stdin`）会耗尽整段超时并返回空输出，与 `terminal_bash_execute` 自述"非交互脚本"矛盾。全仓 `src/main` 只有这两处 spawn，已收敛到一个 helper；新增源码扫描用例钉住"不许绕过"。
4. **`status()` 删除它只是没探到的记录，`stop()` 对未发信号的记录报 `stopped`（P1）**，且 `status()`/`stop()`/`stopAll()` 用两把尺子问同一件事。修复：一张归属判定表（口径统一为"本进程启动的进程是否还活着"）供四处读取；新增 `unattributable` 状态并同步五处对模型的契约文字。
5. **已核查不可达 / 已登记不修**：`LocalHttpServerManager` 内私有单参 `urlFor(port)`（返回无 token URL）全仓零调用点 → 直接删除；`stopDisposition` 归属存疑时不杀进程组，接受"可能泄漏一个我们自己的孤儿进程组"，这是两种失败里更便宜的一种，已在 KDoc 与 D-030 写明。

6. **本轮第一版整改又引入一条：`start()` 会销毁它随后拒绝接管的记录（P1）**。第一版把 `start()` 的复用条件接到 `queryDisposition` 之后，顺序变成「先 `discardRecord(existing)`（连带删 `.properties`）→ 再 `isPortListening` 判 PORT_IN_USE 并抛出」。于是一次抖动的 100 ms 探针就能让一个**在跑的服务**失去唯一记录：`status()`/`stop()` 从此报 `not_found`，端口永久不可复用——正是 F4 要消灭的那个状态，被本轮自己的修法在生产路径上复刻。现已改为「先确认端口不再应答，再删记录」，并把错误文案改成能读出成因的一支。**局限如实登记**：该顺序在宿主不可判红（`start()` 需要 Android Context 与真进程），`probe m4c` 亦为绿说明纯表用例看不见调用点装配；证据为代码路径推演 + 主仓仪器面复跑（见未证实项）。
7. **对外来监听者的错误归属（P1）**。`NativeProcessGroupControl.processGroupExists` 有意把 `EPERM` 当作"存在"，所以进程组被任何别的所有者复用后记录仍"活着"；`status()` 的正分支又只看一次裸 connect。结果 App 重启 + 端口被别的进程占用时，`status()` 会返回 `running` 并附上一个**没有任何人在服务**的 token URL，`start()` 还会直接复用该端口——而类里早就有为归因写的 `isTokenServed` 却没被这条路用。修复：无进程内句柄的记录一律用 `isTokenServed(port, token)` 判"这个端口是不是我们自己的服务在应答"，有句柄的仍用便宜的 connect（句柄即归属证据）。
8. **带 NUL 的 URL 不答话（P2）**。`realpath()`/`stat()` 对含 `U+0000` 的路径抛的是 `ValueError` 而非 `OSError`，它会逃出 `except ServedRootEscape`，连接被直接关闭、**没有任何响应**。变异 `m6-nul-guard-removed` 的实测原文即为证据：`expected:<[404]> but was:<[]>`（空状态）。这同时说明本轮此前所有"非 200 即拒绝"的断言形状是错的——连接中断、超时、文件不存在都能冒充拒绝，故本节新增/改写的所有拒绝断言一律要求显式 `404`。

### 独立复核两轮的实际结果与处置

第 1 轮专审本轮整改，抓出 2 条阻塞级（F1b 两处仍在出密、F4 的杀无辜 + 容量死锁），全部落地修复并配判别用例。第 2 轮专审第 1 轮的整改，再抓出 3 条（F6 `start()` 顺序复刻了 F4 的危害、F7 外来监听者被误归因为"我们的服务在跑"、F8 NUL 路径无响应）与 4 处**注释/契约文字比代码能做的说得多**：`stopDisposition` 的"只在能看见本进程启动的进程时才发信号"（代码还看宽限期）、`stopAll()` 返回计数的消费者（grep 无任何调用者消费该 Int）、`BashCommandTool` 的 stop 契约"绝不杀非托管进程"、`assets/ugk/AGENTS.md` 对硬链接规则"名字在服务树外"（代码是无条件 `st_nlink > 1`）。四条文字全部按代码实况改写。

第 2 轮另报「`spawnWithStdinClosed` 的 `starter` 缝隙 + 注释声称的把关不成立」，本轮据此把 `TerminalSpawnSiteTest` 的谓词从裸 `.start()` 收窄为"能创建子进程的调用"，并补 `m5c`（`worker.start()` 不得误红）；豁免行改为按内容匹配，避免无关重排把它变红。

第 2 轮复核者自报的正面结论也被本轮采信前复核过：31 条 URL × 2 种根目录形态（普通目录、`--directory` 本身是符号链接）在宿主 CPython 3.14.2 上 0 泄漏。

- 「`stop()` 应在句柄已死但进程组仍在时仍发信号，因为该组确由本进程创建」——第十轮第一版整改就是这样写的，复核后由本轮回滚：会话领导者被回收后 pgid 会回到内核池，可能已被同 UID 无关进程组复用，`kill(-pgid, 0)`/信号都可能打死无辜进程。复测（原写法指向第十轮一次性目录里的脚本，未入库）：`./gradlew.bat :ugk-terminal-runtime-android:testDebugUnitTest --tests "*LocalHttpServerRecordDispositionTest*" --console=plain --rerun-tasks`（`stopNeverSignalsAGroupWhoseOwnLeaderHasBeenReaped` 钉住现口径）。
- 「`lstat` 换成 `stat` 是遏制弱化」——变异实测 `m1c-judge-link-not-target` 显示功能面确有红，但方向是**变严**：`stat` 跟随链接后可能拒掉一个合法的根内符号链接。现口径为 `realpath` 定根 + 对解析结果 `stat()`，两者都不是靠 `lstat`。

### 未证实项（合并前应跑的命令）

1. 设备侧 CPython 3.14.6 上 handler 的实际行为（宿主证据只覆盖同一份脚本字节在 3.14.2 上的判定；`st_nlink`、`index_pages`、目录重定向语义在 Android 上未实测）。合并前：`:demo-app:connectedDebugAndroidTest --tests "*LocalHttpServerManagerInstrumentedTest*"`（需先 `:app:installDebug`，端口 18765）。
2. `unattributable` 新状态在真机生命周期（重启后宽限期已过）下的实际出现路径：同上仪器类扩展一条断言。
3. `spawnWithStdinClosed` 两个调用点在真机上确实关闭了 stdin：`terminal_bash_execute` 跑 `read x; echo got-eof` 应在 1 秒级返回 `got-eof` 而不是耗尽超时。
4. `m4c-stop-wrapper-wiring` 变异为绿：纯表用例看不见调用点装配，句柄口径若在 wrapper 处被改错不会在宿主变红——已登记为本轮测试面的已知局限，只能靠第 1/2 条仪器用例补。
5. 本轮**未运行任何设备/仪器门禁**（宿主 C 盘曾长期满盘；本轮只在 JVM 面取数）。§33/§34 记录的设备数字不得当作当前门禁。

## 36. 第十轮设备通道尝试与 MIUI 环境定性（2026-10-01）

- 背景：§34、§35 均声明设备通道未运行。2026-10-01 经用户同意，在日用 MIUI 真机 `QSG6Q8IFDMDELVGQ` 上补跑 `:demo-app:connectedDebugAndroidTest` 与 `am instrument`，共五轮独立尝试（三次 gradle connected、两次直接 `am instrument`，其中一轮关闭全部系统动画校准）。
- 结果定性：设备环境阻塞，非本轮代码回归。① 首轮运行期间发生一次对在用应用的卸载重装，应用私有数据被清空（五份教学记录与 API 配置；事故与教训登记于版本台账 2026-10-01 节）；② 用户手动开启 MIUI「显示悬浮窗」后 `Settings.canDrawOverlays()` 守卫通过，证明该开关在 MIUI 上不受 AOSP `appops set SYSTEM_ALERT_WINDOW allow` 影响；③ `DemoDialogTestHostActivity` 45 秒无法达到 idle（事件队列零空闲），关闭 window/transition/animator 三个动画缩放后依旧复现，属设备系统 UI 级重绘，FloatingConversation、NoteStyle、PermissionGuide、DemoDelayedTaskDialog 等依赖该宿主 Activity 的用例系统性超时；④ `PermissionGuideInstrumentedTest.settingsEntry...SurvivesRecreation` 三轮挂起（§33 已登记该用例首跑偶发，本机为常态）。
- 影响边界：失败用例与本轮合并代码（教学编译器、终端本地 HTTP 运行时）无文件交集，不构成第九/十轮修复的回归或假绿证据；通过类与失败类的完整清单留在 `/tmp/am-instrument-run*.log`（未入库）。
- 结论与后续：UI 仪器套件的通过性验收只在 AVD 上执行（§33 惯例）；日用手机禁止直跑 connected/instrumented，确需时先 `run-as` tar 备份 `files/` 与 `shared_prefs/` 到 PC，跑完恢复并核对哈希。另登记两条操作教训：TaskStop gradle connected 会触发其清理阶段异步卸载主应用与 test APK（重装须用同钥 APK 并重授权限）；结果目录的孤儿 UTP JVM 进程不终止时，Gradle 因无法哈希 `.lck`/logcat 文件而拒绝执行 connected 任务。

## 37. D-031 close 归属与 native 探针 fail-closed 的验证（2026-10-02）

- 范围：`fix/terminal-lifecycle-d031-20261002` 分支四提交（D-031 决策先行、C8 fail-closed、A8 单表+ownerId、plugin close 接线与仪器回归）。决策与动机见 `docs/terminal-runtime-decisions.md` D-031；此处只记证据。
- JVM 门禁：十模块 `--rerun-tasks` 独占全量 **863/0/0/3**（= §36 前基线 858 + 新增 5：`LocalHttpServerRecordDispositionTest` 的「探针不可用按存在处理」×2 与「close 作用域」×3；skip 分布不变）。
- 仪器门禁（AVD `ugk_dev_api35_smooth`，emulator-5554，Android 35）：`:demo-app:installDebug` + `am instrument -e class com.ugk.pi.android.testapp.LocalHttpServerManagerInstrumentedTest` → **OK (2 tests)**：
  1. `managedServerServesWorkspaceAndStopsByProcessGroup`（既有用例，跨实例显式 stop 契约在单表下保持，D-030 追记第 5 条要求的复跑对象）；
  2. `closingASideManagerDoesNotStopTheServerItsOwnerStillServes`（新增 D-031 回归：side manager `close()` 后 owner 的站点仍 `running`，owner 显式 `stop` 仍 `stopped`）。
- 一次计划外的红/绿实证：首跑时模拟器上残留 09-28 旧主包（`installDebugAndroidTest` 只更新了测试包，主包 `lastUpdateTime=2026-09-28`），新用例对旧代码判红——`sideManager.close()` 走旧 `stopAll()` 语义杀掉 owner 站点、`owner.status(port)` 列表为空、`single()` 抛 `NoSuchElementException`；显式 `:demo-app:installDebug` 装入分支构建后同用例转绿。该失败与修复前缺陷逐点吻合，构成回归用例有效性的直接证据。教训登记：`installDebugAndroidTest` 不保证主包同步更新，`am instrument` 前须显式 `installDebug` 并核对 `lastUpdateTime`。
- 遗留（不假装已把关）：D-030 追记第 5 条的「外来监听者不得被报成 running」仪器断言仍未落地——构造该场景需要测试进程访问 manager 私有 `Process` 句柄或注入外来监听的测试缝，本批未引入；`unattributable` 真机路径与设备侧 CPython 3.14.6 handler 行为（§35 未证实项 1/2）继续挂账。
- 接线点局限补登（独立审核后追加）：`TerminalAgentPlugin.close()` 从 `stopAll()` 改为 `controller.close()` 的装配点无自动化判红——三个 JVM fake 对接口默认 `close()=stopAll()` 完全等价，仪器用例直接调 manager.close() 也不经 plugin；正确性由独立审核对杀伤链（`DemoTeachingController.completeClose` → `AgentRuntime.close` → `TerminalAgentPlugin.close` → `controller.close`）逐环人工核实支撑，与 D-030 追记第 5 条 m4c 教训同构。

## 38. 第十二轮 P0 审查（合并批次等价性核验 + 「有守卫」声称的可判红化，基线 `main@c5a13f5`，2026-10-03）

- 切割依据：第十一轮开分支（`710d567`）之后 `main` 又落 7 个提交（`710d567..c5a13f5`，29 文件）= D-031 生命周期批 + 跨模块收敛批次③。这两批是全仓最新且最未被审的表面，且提交信息自带「零行为变化 / 已被 JVM 用例钉住 / 每个派生字符串都会跟着变」类断言。PR #13 覆盖的文件（HttpTransport、两 provider、SseStreamFraming、OptionalArgumentReading 一族）本轮不重复修；与 PR #14（EOL 卫生 + close 接线用例）无文件交集。
- 宿主与通道：Windows 10.0.26200 / Git Bash；JDK 17.0.11（`E:\Android\Android Studio\jbr`，`java` 不在 PATH，须显式 `JAVA_HOME`）；Gradle 8.13（`./gradlew.bat --version` 实测 Launcher JVM 17.0.11）；`ANDROID_HOME=E:\Android\SDK`；AVD `ugk_dev_api35_smooth` 已在线（`adb devices` = `emulator-5554 device`，`getprop ro.build.version.sdk` = `35`），本轮**未跑仪器门禁**（结论不依赖设备）；仓库无 `.github/workflows`，所有门禁均为本地人工执行。
- 门禁口径（只认日志内 `EXIT=`、`MODULE_RESULT`、`XML_FRESHNESS` 行与 JUnit XML 时间戳窗口）：
  - 基线 `main@c5a13f5` 独占 `--rerun-tasks --no-build-cache`：**`867 tests / 3 skipped / 0 failure / 0 error`**，131 份 XML，窗口 `2026-10-02T19:28:45Z…19:29:22Z`（`build/review-evidence-r12/baseline-main-c5a13f5-rerun.log` + 同名 `-aggregate.txt`）。注：同日 19:06 的首次基线取数（`baseline-main-c5a13f5.log`，同口径 `867/3/0`）其后被一次定向 `--tests "*R12KotlinxProbeTest*" --rerun-tasks` 覆写掉 core 模块的结果目录，故交付前在纯净树上重取一次为准 —— 登记为过程失败。
  - 交付态 `6a76e01`（工作树 `git status --porcelain` 为 0 后独占运行）：**`890 tests / 3 skipped / 0 failure / 0 error`**，139 份 XML，窗口 `2026-10-02T20:43:16Z…20:43:53Z`（`build/review-evidence-r12/delivery-r12-final-gate3.log`，`EXIT=0`）。净增 23：Core 214→216、Terminal Runtime 55→60、File 13→17、Agent Skill Runtime 89→92、Schedule 20→21、Demo 332→340；Terminal Skill 45、System 50、Task Runtime 49 不变；`skipped=3` 来源不变（File 1 + Agent Skill Runtime 2 的 Windows symlink 用例）。
  - 中途两次取数不作交付口径：`886/3/0`（`eea6240`，`delivery-r12-final-gate.log`）是被后续代码提交推翻的中间态；`890/1/0`（`7653c9c`，`delivery-r12-final-gate2.log`）红在本轮新写用例自身的一处竞态（见「过程失败登记」），修复后在 `6a76e01` 重取。
  - `:pi-attention-skill-android:testDebugUnitTest` 在本基线上仍是 `NO-SOURCE`（该模块只有 `src/main`）；关闭它的是 OPEN 的 PR #13，本轮不重复。
  - 交付态数字取自 `eea6240`；其后若再有提交，只会是本文档与台账记录（不改代码），差值在提交信息中点名。
- 发现与处置（编号 / 严重度 / 位置按符号 / 证据 / 兄弟落点）：
  - **F1 P1（假绿守卫 + 声称失实）** `NativeProcessGroupControl.isAvailable` 的三处消费点（`hasProcess`、`groupProbe`、`stopRecord` 的 `server.process != null` 句柄替身）。8873de2 声称「query/stop disposition inputs and hasProcess() … companion pure function, pinned by JVM tests」——纯函数确实被钉住（把 `groupExistsForDisposition` 退回 `probeAnswer` 一条用例变红），但**装配点全部不可判红**：把 `probeUsable` 写成常量（mut-m1a）、把 `hasProcess` 改回裸 `processGroupExists`（mut-m1b）、把 `stopRecord` 整段退回 D-031 前形状（mut-m2）三次都 `55/0/0` 绿。处置：决策下沉为 `processEvidencePresent` / `groupStopPlan` 两条纯规则并全表钉死（`mut-mb2`、`mut-mb3` 各自只红对应那一行），行为逐行等价（plan 表复现原四分支）。登记局限：调用点传参仍无宿主判红，需 `:demo-app:connectedDebugAndroidTest --tests "*LocalHttpServerManagerInstrumentedTest*"` 在设备上补。
  - **F2 P1（假绿守卫）** `AppFileAgentPlugin` 与 `AgentSkillRuntimePlugin` 的 `protectedToolNames`。把 `"app_file_delete"` 写成不存在的名字 → file 模块 `13/0/0` 绿（mut-m3）；把 `add("skill_delete")` 写错 → skill 模块 `89/0/0` 绿（mut-m4），而同一个变异打在 `skill_save` 上会红（mut-m4b，红在 `AgentSkillRuntimePluginTest.wrapsSkillMutationsByDefaultAndBypassesTogether`）——缺口精确定位到那一个名字。skill text 明文向模型承诺「app_file_delete requires a prior user confirmation through show_user_confirmation_dialog」，所以名字写错等于教人以为有门禁。处置：两模块各加一张按名字 + 按行为的门禁用例（默认拒 + 状态不变 + 全授权放行；只读工具不得被包；三开关全关时整族解包且 skill 文案同步改口），复跑 mut-mc1/mut-mc2 现由对应用例判红。
  - **F3 P1（失实安全声称 + 无门禁）** `TerminalPythonProfile` KDoc「Bump the constants here and every derived string follows」。实测把 `PYTHON_DISTRIBUTION_VERSION` 改 3.14.7 并同步 asset 文案后，**全量 867 仍绿**（mut-m6-python-version-bump-fullgate.log），而 `PythonDistribution.PYTHON_ASSET_DIRECTORY` 是由该常量拼出来的 → 设备上 `assets.open(".../python/3.14.7/stdlib.zip")` 直接找不到载荷。`verify-runtime.ps1` 不补这个洞：它比对 `runtime-lock.json` 与磁盘文件，从不读这些常量（读码 + `git grep` 核实）。处置：新增 `TerminalPythonProfilePayloadTest` 把常量钉在资产目录名、manifest 的 `lib/python<version>/` 前缀与两 ABI 的 `libpython<version>.so`/`cpython-<digits>` 文件名上；`mut-mb1` 实测常量一改即在 runtime 模块与 skill 模块各红一条。KDoc 改为写明「字符串跟着变、载荷不跟着变，载荷由新用例把关，lock/打包脚本由人工 release 门禁把关」。
  - **F4 P2（永真式倾向的用例）** `TerminalAgentInstructionsAssetTest` 声称「an asset edit that invents a version, turns this red」，实现是 `contains("CPython <version>")`：再加一行假的 `CPython 3.99.9` 照样绿。处置：折叠到全部 `CPython x.y.z` 命中并要求唯一值，另加「一行都不许删」断言；`mut-mb4`（注入虚构版本行）实测判红。
  - **F5 P2（同族漏项，第十一轮 F5 的复发）** `docs/terminal-runtime-validation.md` §36 把真 U+0000 写进正文 → `git ls-files --eol` = `i/-text`，ripgrep 对整份文件返回 `binary file matches`（Grep 工具实测）。第十一轮按「修掉那一行」处置，没扫其余落点。处置：改为 `U+0000` 文本 + 常驻门禁 `DocumentationTextHygieneTest`（扫 root/docs/模块三级 Markdown，逐条报文件·行·字节，命中数不足 30 即红，防「扫了个空清单」）。**被证伪的假设一并登记**：原以为该文件会让 `git diff` 看不见 diff，实测 `git diff --numstat` 仍给出真实行数（`76 1`），危害限于检索面。
  - **F6 P1（用户可见卡死）** `DemoUrgentInteractionDispatcher` 的 `acceptedPresentationIds`：`drain()` 撞屏幕操控占用时走 `cancelPending()`，队列清空但 id 不释放 → 该悬浮屏之后的每次点击都在去重处被拒，无日志、无出路（要再发生 32 次事件才挤出那个 id）；第十一轮把这条挂在「未证实项」（出处见**台账** `REVIEW-2026-10-02-round11-framework-and-null-optionals.md` 第五节；本轮先误记为主仓 §37，已在 eea6240 提交信息订正——主仓 §37 是 D-031 记录，不含此项，`git grep DemoUrgentInteractionDispatcher -- docs` 实测只命中 `docs/android-agent-attention.md`）。处置：队列/记忆拆成 `DemoUrgentInteractionLedger`，规则一条 —— id 只在「排队中或已投递」期间被记住，事件被丢弃即释放。宿主可判红：mut-me1b（`discardAll` 退回 `pending.clear()`）红 `droppedActionsBecomeTappableAgain`，mut-me2b（`discard` 不释放）红 `queuedAndDeliveredIdsAreRefusedWhileDroppedOnesAreNot`。
  - **F7 P1（结果回调被吞后 UI 永不复位）** 同文件 `onOutcome`：`checkNotNull(store.appendMessagesAndFlush(...))` 之后才是 `setSending(false)/setStatus(...)`，而 `DemoAgentRunCoordinator.dispatch` 把 observer 包在 `runCatching { observer(event) }.isSuccess` 里 —— 会话被删时异常被吞、复位不执行，悬浮窗停在「正在处理悬浮操作」。处置：保存失败改为写可见日志，复位无条件执行（`runCatching` 只包住「保存」这一件真实副作用；`appendMessagesAndFlush` 非挂起，无取消异常可吞，已读签名确认）。
  - **F8 P2（文档指向不存在的把关者）** §35/§36 的复跑命令写作 `gate/run-jvm-gate.sh`、`gate/run-targeted.sh`、`gate/run-targeted-r10.sh`，而仓库里根本没有 `gate/`（`git grep "gate/run-"` 恰好命中这两处，`git ls-files` 只有 `DemoWorkflowActionGateway.kt`）。本轮已就地改为任何检出都能执行的 gradle 命令，并把「按特征值全仓扫」跑完（扫后剩余命中 0）。
- 本轮整改自引入 / 自述失实（专审整改轮与本作者冷读抓出，全部已修并各配「退回即红」用例；编号接上节）：
  - **F9 P1（97d453b 自引入的丢答案）** 把 `onOutcome` 的保存失败改为吞掉后，`DemoAgentRunCoordinator.dispatch` 里 `handled = observer != null && runCatching { observer(event) }.isSuccess` 恒真，而 `MainActivity` 的 SDK_EVENT / SCHEDULED_TASK 分支正是「`handledByProcessOwner != true` 才补写答案」——本轮把「转圈不停」换成了「答案哪儿都不落」。修法：无条件先复位 UI + 写可见日志，然后再重抛（重抛是报 `handled=false` 的唯一通道）。用例：`DemoAgentRunOutcomeHandoffTest` 两条钉住 coordinator 的交接契约；把 `handled` 简化成 `observer != null` 后，这两条**外加**既有 `DemoTeachingControllerTest.stoppingSegmentKeepsTeachingAndAllowsNextInstructionAfterCleanup` 一起红（`mut-mh1-handled-always-true.log`，failures=3）。已登记局限：dispatcher 自身的调用点在宿主仍不可构造。
  - **F10 P1（08847e3 的声称大于其覆盖面）** `DocumentationTextHygieneTest` 与提交信息都写「every prose file」，但扫描只走 root、`docs/`、模块顶层，8 个 tracked Markdown 在外面——含 `pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md` 与随包的 `SKILL.md`，即模型真正读的那批文档。复核线程在副本里往该 asset 注入一个 `0x01` 后门禁仍绿。修法：遍历改为根目录以下全树（排除 `build/` 与点目录，实测 46 个），下限从 30 提到 40 并写明理由。判别：向该 asset 注入真 NUL 现判红（`mut-mf2-hygiene-asset-outside-old-scan.log`）。
  - **F11 P2（168b497 的折叠窄于其声称）** `TerminalAgentInstructionsAssetTest` 只匹配 `CPython x.y.z`，复核线程注入「Python 3.13 is the interpreter this runtime ships」一行后仍绿。修法：折叠改为取所有 Python 版本号形状并要求落在 {distribution, interpreter series} 内，且 distribution 版本必须仍被提到。判别：同一条注入现判红（`mut-mf1-asset-claims-injected.log`）。
  - **F12 P2（168b497 自引入的急切求值 + 声称不精确）** 把决策抽成 `processEvidencePresent` / `groupStopPlan` 时，JNI `kill(-pgid,0)` 从被 `||` 短路的操作数变成**入参**，于是「句柄已证明活着」或「探针根本不可用」时也会去问一次；168b497 的「Behavior is unchanged」因此不精确，并与本文件自己写的「两个探针故意惰性」相矛盾。修法：两条规则改收惰性 lambda，调用点传闭包；判别：急切写法分别红 `processEvidenceOnlyAsksTheProbeWhenTheProbeIsTheAnswer`（修复前的实测原文就在 `pass5-ugk-terminal-runtime-android.log`）与 `stopPlanDoesNotAskTheProbeWhenNothingCanSignalTheGroup`（`mut-mhl2-stopplan-eager.log`）。
  - **F13 P3（`FileContainment` KDoc 的等价性描述不准）** 把 5 处手写比较都说成「和这条规则行为一致」，其中 `PythonDistribution` 两处守的是解包路径、`DemoWorkflowRepository` 一处只接受严格前缀——它们拒绝根自身，比本谓词更严，不是等价；也没必要把 `PythonDistribution` 归为「模型书写路径」。已按等价 / 更严分类重写。
  - **F14 P3（294c5d1 的证据句超出实测）** 「No test passed a case variant to any tool」不成立：主干的 `ToolJsonTest` 已经拿 `"True"/"FALSE"` 打过共享访问器本身。已收窄为「没有用例打到这个落点」（`activeOnly` 在各测试源集零命中那条实测保留）。
- 兄弟落点核对（本轮按特征值扫出的同族，处置逐一写明）：
  - 路径遏制谓词：共享 `isInsideRoot` 覆盖 `AppPrivateFileTool.resolvePath`/`AppFileListTool`、`resolveInsideRoot`、`SkillRepository.validateDeleteTree`；仍有 **5 处手写比较在 4 个文件**：`LocalHttpServerManager.resolveWorkspaceDirectory`、`PythonDistribution`（2 处）、`BashCommandTool.isInside`、`DemoWorkflowRepository`。terminal 那 3 处是结构性的（`:ugk-terminal-runtime-android` 对 core 无依赖边）；`BashCommandTool` 有 `api(project(":ugk-pi-android"))` 依赖边、本可收敛，但它落在 PR #13 已改的文件里，为避免与未合并审查抢同一文件而**登记不修**；四处手写比较都是「已规范化操作数 + 追 `File.separator`」，与共享谓词等价，`mut-m5` 证明收敛前它们不会被新用例判红。
  - 原子写：`AtomicFileWrites` 覆盖 file workspace / memory / skill 仓库与回滚 `replaceOnto`；`LocalHttpServerManager.persist()` 在 terminal 模块内另写一份，且原来还 `delete()` 目标再 rename —— 正是共享谓词明文拒绝的形状，本轮改为不删目标、失败即响亮报错（登记：该行无宿主判红，manager 不可在宿主构造）。
  - 版本事实：Kotlin 侧收敛（批次③）之外仍是独立字面量的有 `runtime-lock.json`、`src/main/assets/…/python/3.14.6/`、`scripts/terminal-runtime/prepare-python-runtime.ps1`、`verify-runtime.ps1` 里的 `cpython-314-*` glob，以及 README/baseline/release-checklist 等当前时态文档；本轮新用例覆盖「常量 ↔ 载荷 ↔ jniLibs 名」，lock/脚本 ↔ 磁盘由人工 release 门禁覆盖。
- 独立复核（截至本时点）：
  - 第一层两条只读线程对 `main@c5a13f5` 的本轮范围出具结论（逻辑/接线完整性 + 「有守卫」声称审计），立案逐条在冻结树复跑后处置；本轮 F1/F2/F3/F4 的立案直接来自它们（其中「`67dcff9` 声称 every replaced per-module copy rejected」一条由两条线程独立命中）。
  - 第二层「专审本轮整改」由一条只读线程在 `git archive` 副本里执行完成（先前登记的「后台线程未返回」已在 `1a5851f` 之后作废：该线程确实在 20:22 前返回，本段为订正）。它交出 2 条 BLOCKER + 7 条 NON-BLOCKING；本轮在主树逐条复跑后接受并修掉 6 条（F9–F14）、否掉 1 条、另有 2 条属我自己冷读先抓到的（`isReservable` 探针消耗有界记忆、KDoc/提交信息把 5 处手写比较写成「four sites」，在 `eea6240` 修）。**F9 是本轮修复自己引入的 P1**：若不做专审整改这一层就会带进主干。
  - 专审整改轮的立案处置逐条见「被否掉 / 判为不可达的立案」与上一节 F9–F14。
- 过程失败登记（判废的取数、被污染的树、走错的方向）：
  - 首次基线取数（19:06，同口径 `867/3/0`）被一次定向 `--tests "*R12KotlinxProbeTest*" --rerun-tasks` 覆写了 `ugk-pi-android` 的结果目录，聚合器随后只解析到 654 —— 因此在纯净树上重跑一次独占全量作为基线口径（19:28），旧聚合文件仅存档。
  - 交付前第二次全量取数（`7653c9c`，`890/1/0`）红在本轮新写的交接用例自身竞态上：它等的是观察者、读的是观察者之后才赋值的 `pendingOutcome`。单模块复跑两次绿、全量红，正是「touched 复跑掩盖负载相关缺陷」的形状；按用例缺陷处理（`6a76e01`）而不是按「环境抖动」登记。
  - 两次用 `git checkout -- <文件>` 回退变异时，把同一文件里**尚未提交**的真实订正一起抹掉（`FileContainment.kt` 的 KDoc、`ToolJson.kt` 的 KDoc），只能重写一遍。规则复述：变异前先把整改提交；未提交改动不得与 `git checkout` 同处一个工作树。
  - 一条「后台线程 transcript 恒 162 字节 = 通道启动即停」的判定是**误判**：被 TaskStop 的那条其实仍在跑，并在几分钟后完整交付（本节第一段即其结论）。今后不能只用 transcript 体积判存活——要么等到通知，要么按前台派发。
  - 变异归因上做过一次妥协：`discardAll` 与 `discard` 两条回退放在同一次运行里（`mut-me1b2b-ledger-reverts.log`，failures=2）。两条用例与两条回退一一对应，归因仍成立，但不如各跑一次干净；后续变异改回一条一跑（`mut-mhl2`、`mut-mh1` 即单条）。
- 被否掉 / 判为不可达的立案（附复测，勿在下一轮当新发现）：
  - 「`SkillRepository.validateDeleteTree` 在 `root` 非规范化时可能删掉仓库根」——`deleteSkill` 传入的 `root` 已 `rootDir.canonicalFile`，且 `name` 经 `[a-z0-9-]+` 校验、另有 `isDirectChildOfRoot` 前置，`File(rootDir, name)` 不可能规范化成根 → 已核查不可达。
  - 「`DemoUrgentInteractionLedger.remove()` 的唯一性前提依赖 `maxPending < maxRemembered`，界限反过来就能排队重复项」——不依赖：`reserve` 对已在 remembered 集里的 id 一律拒绝，与两个上限无关；钉住这条的用例是 `queuedAndDeliveredIdsAreRefusedWhileDroppedOnesAreNot`（复测：`./gradlew.bat :demo-app:testDebugUnitTest --tests "*DemoUrgentInteractionLedgerTest*" --console=plain --rerun-tasks`）。
  - 「随包 AGENTS.md 里已有一条未被检查的版本字样 `python3.14`（第 63 行）」——当前字节实测该文件只有一处版本形状：`grep -n "3\.14\|3\.99\|python3\." pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md` 只命中 `CPython 3.14.6` 一行 → 立案不成立，但由它引出的 F11 折叠加宽是真问题，已修。
  - 「`stopRecord()` 在探针不可用且有句柄时谎报 stopped」——句柄即本进程直接子进程（session launcher 已 exec 成 Python，见 `start()` 命令构造与 `SESSION_REPORT_ENVIRONMENT_VARIABLE` 注释），句柄死亡即服务器死亡；与探针可用时的 `DROP_CONFIRMED_DEAD` 结论同形 → 不修。
  - 「`withUserConfirmation` 收敛改变了保护集合」——逐字节比对 `710d567` 的两处 `when`/`if` 形状，名称集合与 bypass lambda 一致（两条独立复核均确认）→ 等价，不修。
  - 「`schedule` 的 `long()` 丢掉 `intOrNull` 兜底是回归」——`longOrNull` 与 `intOrNull` 对带引号数字串都返回值（探针实测 `strNum → 86400`），对布尔字面量都返回 null，兜底确为冗余 → 不修。
  - 「AGENTS.md 声称高影响工具默认都被包，而 `agent_task_create/update/cancel` 未包」——`docs/android-scheduled-tasks.md` 明写受保护动作由屏幕/终端互斥与全授权开关把关，创建任务本身不在该门禁范围；属有意的产品分层 → 登记不改（本轮未动 AGENTS.md，它与 PR #13 冲突面重叠）。
- 未证实项（合并前应跑）：
  1. `:demo-app:connectedDebugAndroidTest`（含 `LocalHttpServerManagerInstrumentedTest`、`TerminalAgentIntegrationInstrumentedTest`）本轮未跑；F1 的装配点与 F6/F7 的真实窗口链路只能靠它。步骤：`:demo-app:installDebug` → `appops set com.ugk.pi.agent SYSTEM_ALERT_WINDOW allow` → `am instrument -e class <类名>`，并核对主包 `lastUpdateTime`（§37 教训）。本机 AVD 已在线，非阻塞。
  2. `scripts/terminal-runtime/verify-runtime.ps1 -CheckPackages` 未跑（本轮不触碰原生载荷）；`:demo-app:assembleDebug` 见本节末的打包记录。
  3. 交付态数字只覆盖 JVM 面；`890/3/0` 不含任何仪器用例。
- 打包面：交付态 `6a76e01` 的 `:demo-app:assembleDebug` 为 `BUILD SUCCESSFUL`、`211 actionable tasks: 10 executed, 201 up-to-date`、`EXIT=0`（`build/review-evidence-r12/delivery-r12-assembleDebug-final.log`），`demo-app-debug.apk` 45,466,963 字节。`eea6240` 时点也跑过一次（`17 executed` / 复跑 `4 executed, 207 up-to-date`，APK 45,466,809 字节），该次日志文件后被同名 final 运行覆盖前的原文未保留，故此处只把它当中间态记录。本轮未跑 `verify-runtime.ps1 -CheckPackages`（不触碰原生载荷）。
- 本轮新增教训（每条都对应一个可执行动作，不写决心）：
  1. **吞异常前先问「谁在读这个失败」**。修「悬浮窗转圈不停」时把 `onOutcome` 的保存失败变成静默，而 `handled` 恰恰由这条异常计算，`MainActivity` 只在 `handled != true` 时补写答案——失败通道就是恢复通道。动作：给任何新加的 `runCatching`/`getOrDefault` 先 `git grep` 被包住调用的返回值与抛出条件的使用方；本轮由 `DemoAgentRunOutcomeHandoffTest` 常驻钉住。
  2. **新门禁必须注入一次「它声称的边界之外」的样本**。本轮两条 BLOCKER 都是同一形状：断言在自身覆盖范围内成立，KDoc 却把范围说成全体。动作：加门禁时除了注入应抓到的样本，还要注入一个位于**声称边界外**的样本，看它是被抓到还是被范围本身放过——抓到就说明声称过大，放过去就说明覆盖不足。
  3. **把决策抽成纯函数时，求值策略也是行为**。`a || f()` 与 `a || g(f())` 不等价，短路的 `f` 变成入参后每次都会跑；对 JNI / socket / 磁盘探针就是性能与副作用变化。动作：纯函数化时把探针类参数写成 `() -> Boolean`，并补一条计数用例（本轮两条 laziness 用例即此形状）。
  4. **用例要等它断言的那个状态**，不是等它前一个信号。`pendingOutcome` 在观察者之后才赋值，等观察者就在测竞态。动作：await 的目标对象与断言对象必须同一个。
  5. **变异之前先把整改提交**（本轮两次 `git checkout --` 抹掉未提交的 KDoc 订正）。
  6. **后台线程存活不能只按 transcript 体积判**：被判「启动即停」并 TaskStop 的那条实际完整交付了。动作：以通知或前台派发为准，体积只作弱信号。
  7. **文档里的复跑命令必须能在库里执行**。两条指向一次性外部脚本的「避免下一轮重复劳动」指针本轮被就地改掉；动作：落库前对命令里的每个路径跑一次存在性检查（本轮以 `git grep` 特征值扫过，剩余命中 0）。
## 39. 第十一轮 P0 审查修复（分支 `fix/p0-review-round11-20261002`，基线 `main@710d567`，2026-10-02）

> **入库注记（合并 PR #13 时追加）**：本节写作时点（2026-10-01/02）早于 §37（D-031）与 §38（第十二轮）；因该两节章节号在 PR #13 合并入库前已被 main 占用，本节按入库顺序编为 §39。合并前独立复核另查实一处缺陷并已在 PR 分支修复（OpenAI 流仅含 `[DONE]` 时仍以成功空回答收尾，判别用例 `openAiFailsLoudlyWhenTheStreamCarriedOnlyADoneMarker`，提交 `7b7911d`）。

本轮对象不是终端 Runtime，而是 SDK 响应框架与「模型可控可选参数」这条横切规则：Terminal v1 scope、原生载荷与权限边界均未改动，因此不改变 D-029/D-030 的边界。

验证宿主：Windows 10.0.26200 / Git Bash；JDK 17.0.11 位于 `E:\Android\Android Studio\jbr`（PATH 上无 `java`、继承环境 `JAVA_HOME` 为空，必须显式指定——按 §33 之后立下的规矩，「探测不到」不等于「跑不了」）；Android SDK `E:\Android\SDK`（platforms 31/33/34/35/36、build-tools 30.0.3–36.0.0、NDK 26.3/27.0/28.2.13676358）；`GRADLE_USER_HOME=E:\DevCaches\gradle`（Gradle 8.13 发行包与 2.0 GB 依赖缓存已在位）；宿主 CPython 3.14.2；PowerShell 5.1.26100 可用（`scripts/terminal-runtime/verify-runtime.ps1` 与 `scripts/sdk/*.ps1` 本机可跑，本轮未跑：未改原生载荷/打包/Core API 面）。`adb devices` 为空；AVD `ugk_m3` 存在但本轮未启动。本仓**没有任何 CI**，AGENTS.md 的门禁全部靠人执行——这是仓库属性，不是本轮发现。

### 门禁口径（只认日志内 `EXIT=` 行与 JUnit XML 汇总 + 时间戳窗口）

- 基线 `main@710d567` 独占 `--rerun-tasks --no-build-cache`：**`858 tests / 3 skipped / 0 failures / 0 errors`**，`BUILD SUCCESSFUL in 1m 27s`，191 actionable tasks 全执行，全部 XML `timestamp` 落在 `2026-10-01T19:04:59Z … 19:05:52Z`。日志 `build/review-evidence/baseline-710d567.log`，汇总 `baseline-710d567-summary.txt`。
- 交付态代码提交 `c23ee71`（第 6 轮整改落地；工作树实测 `git status --porcelain` 为 0 后独占运行，运行时 HEAD 为只多一个文档提交的 `58e8c05`）：**`950 tests / 3 skipped / 0 failures / 0 errors`**，`BUILD SUCCESSFUL in 42s`，194 actionable tasks 全执行，136 份 XML 的 `timestamp` 全部落在 `2026-10-01T23:10:57Z … 23:11:34Z`，日志 `build/review-evidence/delivery-final5-r11.log`（内 `EXIT=0`），逐模块数值 `delivery-final5-r11-aggregate.txt`。
- 同一命令此前还跑过三次独占全量，全部保留作过程证据：`88c076c` 的 `delivery-final3-r11.log` 末尾 `EXIT=` 行取自一个未赋值的变量（写成空值），按本节口径**不作交付证据**；同代码态重跑的 `delivery-final4-r11.log` 得 `947/3/0`（窗口 `22:32:02Z … 22:32:42Z`），是第 5 轮整改落地前的交付态。
- 交付态打包：`:demo-app:assembleDebug` → `BUILD SUCCESSFUL`（211 actionable tasks，11 executed），APK `demo-app/build/outputs/apk/debug/demo-app-debug.apk` 44,661,210 字节；日志 `delivery-assembleDebug-r11.log`。
- 净增 92 项（对基线 858 的逐模块差值）：Core 211→238（+27，`StreamedResponseTransportContractTest` 现有 27 项）、`pi-attention-skill-android` 0→18、`pi-system-skill-android` 50→63（+13，F7/F10）、`pi-agent-skill-runtime-android` 89→98（+9）、`pi-terminal-skill-android` 44→50（+6，F8）、`pi-schedule-skill-android` 20→26（+6）、`demo-app` 332→345（+13）；`pi-file-skill-android` 13、`ugk-agent-task-runtime-android` 49、`ugk-terminal-runtime-android` 50 不变。`skipped=3` 来源不变（File Skill 1、Agent Skill Runtime 2 的 Windows symlink 既有用例，逐条名单见本轮取证）。
- 中途同口径独占运行序列（每份日志各自的 `EXIT=` 行与时间戳窗口为准）：`4a3da1c` 得 `912/3/0`（`delivery-final-r11.log`）、`87f9bbd` 得 `917/3/0`（`delivery-final2-r11.log`，134 份 XML，窗口 `21:46:47Z … 21:47:30Z`）、`88c076c` 得 `947/3/0`（`delivery-final4-r11.log`）、`c23ee71` 得 `950/3/0`（交付引用那一份）。差值依次来自第 4/5 轮复核的用例改名与拆分、F7/F8 两个补漏模块、以及第 6 轮的项级三态用例（`SystemSkillOptionalArgumentTest` 10→13）。
- **`:pi-attention-skill-android` 的 `NO-SOURCE` 缺口本轮关闭**（第九轮曾把它明示为「永远不可能变红的把关」）。同时订正两处「跑全量」清单：`AGENTS.md` 的模块数说明，以及 `HANDOVER.md` 第 5 节命令——该命令此前漏列本模块，照它执行不会跑到新用例。
- 变异取证合并写在 `build/review-evidence/mutation-matrix-final3.txt`（28 行、全部 OK，逐行只回退本轮写下去的那一处语义，并逐行核对 XML 时间戳不与上一行相同）；第 4/5 轮的改名与拆分让其中三行的期望集合指向了已不存在的用例名，因此按当前字节重跑为 `mutation-matrix-round5.txt`（15 行、全部 `FINAL_MUTATION_RC=0`，含恢复的 `z10`——它在 final3 里因锚点不再匹配而被删掉，等于本轮少了一条判别行）。基线复现另有 `r1-f1-baseline-red.log`（该类 6 项中 3 红、3 条对照绿）、`r1-f2-baseline-red.log`（attention 8 项中 5 红、3 条对照绿，取数脚本 `run-f2-baseline-repro.sh`）、`r5-baseline-skillred2.log` 与 `r5-baseline-schedule-red.log`（各 3 项中 1 红——这两条取自「一个用例覆盖多个落点」的拆分前形态）、`mutation-matrix-b1.txt`。
- 拆分之后按落点重取的基线红（脚本 `run-baseline-red-per-landing.sh`，两种回退模式：能编译的用 `git checkout 710d567 -- <file>` 取主干整份文件，用例引用了本轮新增符号的只把那一行退回主干语义）：`baseline-red-attention.log` 18 项中 10 红、8 绿；`baseline-red-skills.log` 9 中 4 红（四个可选键各自变红）；`baseline-red-schedule.log` 6 中 3 红；`baseline-red-demo-teaching.log` 17 中 2 红（`offset`、`includeImages` 各自一条）；`baseline-red-demo-delay.log` 10 中 2 红；`baseline-red-system.log` 10 中 2 红——**这一份不是主干语义**（它只把 null 判定退回原始读取，保留了分支写的 `as? JsonArray`），第 5 轮立案③据此重取为 `baseline-red-system-main-semantics.log`：13 项中 5 红、8 绿；`baseline-red-terminal.log` 6 中 2 红。每条日志的绿集合就是本轮要求的反向对照用例：它们在主干语义下不红（`theListReader…` 除外，它钉的是分支新写的三态读取口，本身不构成主干/分支判别）。
- 过程失败登记：① 两次把未提交的改动留在跑变异脚本的工作树里，被脚本收尾的 `git checkout --` 抹掉（一次丢修复、一次丢测试），此后规则固化为「先 commit 再派线程/跑变异」；② 一次 `z50` 行读到上一行的陈旧 XML（demo 主源码编译失败时测试任务不产出新 XML），靠逐行比对 `timestamp` 发现，脚本已把该检查写成硬条件；③ 一次 `true && <cond>` 被当成变异，它语义等价于不改，红集合为空才暴露——变异必须换掉那一行的语义；④ 变异取证脚本的判决段自己会把 `{行号}.ts` 时间戳标记写进当前目录（`run-mutations-final3.sh` 里 `open(f'{mid}.ts', 'w')`，运行时 cwd 就是仓库根），于是一次运行就把时间戳标记留在仓库根（本轮最后一次全绿矩阵跑完后实测 `git status --porcelain` 列出 27 个未跟踪 `.ts`）；已删除并核对 `git status --porcelain` 为 0，`run-mutations-round5.sh` 起不再写该标记。
⑤ **同族清点漏掉两个模块**：F3 的规则落到 attention/agent-skill-runtime/schedule/demo 之后就收手，`pi-system-skill-android` 与 `pi-terminal-skill-android` 一直带着同一个 `JsonNull` 事实（F7/F8），第 4 轮复核用 `grep -rnE '\?\.\s*json(Array|Object)' */src/main` 才捞回来。教训不是「再仔细一点」，而是清点必须以**特征值全仓扫描**开头、以「扫描结果逐条处置」结尾：本轮改完之后重跑同一特征值扫描，剩下的命中全部属于「读自己写的磁盘 JSON」或「已经是 `contentOrNull` 的宽容读法」，逐条写在下面「已核查不修」。
⑥ 交付证据自身写错一次：`delivery-final3-r11.log` 末尾的 `EXIT=` 行取了一个从未赋值的变量，写成空值。按本轮口径（只认日志内 `EXIT=` 行）这份日志不能充当完成信号，故同代码态重跑 `delivery-final4-r11.log`（`EXIT=0`）并引用它；final3 保留作过程记录。
⑦ 新写的文档上限用例在一次全类串行运行里报出「异常消息不是上限消息」的意外红（`mut-z28` 那一行同时红出第三条），单独重跑与全类重跑都不复现；根因按「先怀疑夹具」处理——该用例的响应体超出上限 65 KB，比环回套接字缓冲还大，客户端在阈值处停读之后服务端的剩余写入会失败并关连接，于是客户端可能先撞上连接错误。把超出量收到 ~7 KB 后重跑该类五次，每次先删掉上一轮的 `test-results` 目录（否则编译失败会留下旧 XML 被当成本轮结果）：`cap-race-repeat-1..5.log` 每份 `EXIT=0`，XML 计数依次为 `27 tests / 0 failures / 0 errors`，时间戳 `23:07:49Z`、`23:07:57Z`、`23:08:05Z`、`23:08:14Z`、`23:08:23Z` 互不相同；矩阵行 `z29` 也复跑为 OK。（第 5 轮还指出该句原先引用的 `/tmp/core-*.log` 三份日志里只有 `BUILD SUCCESSFUL`，没有计数，且不在仓库内不可复核——已按上述方式重取。）
⑧ **派线程与改代码没有隔离**：第 4 轮只读线程还在运行时，主线程继续在同一个工作树里编辑并提交，线程自己在报告里登记了「开工时工作树干净、运行中被改」，并指出第 5 轮派发 prompt 里写的 HEAD `406b237` 在本仓不存在（实际冻结字节是 `d8a404d`）。这条正是上一轮立过的「派发前先 commit 冻结」，本轮仍然违反了一次；线程的每条立案都已按 §5 在当前字节复跑后才处置，但它的取证时间点不再是单一基线。
⑨ **一笔 commit 的 message 覆盖了不属于它的改动**：`88c076c` 的正文同时写了「教学回查按落点拆成两条用例」与「文档上限夹具不再抢跑」，而它的 `git show --stat` 只有 `StreamedResponseTransportContractTest.kt` 一行（夹具超出量从 600 条字段改 320 条）；拆分那部分实际落在前一笔 `89707ff`。已提交历史不改写，用本笔登记订正。动作：commit 之后立刻 `git show --stat` 与 message 逐条对照，发现不匹配就补登记而不是 `--amend`。：`88c076c` 的正文同时写了「教学回查按落点拆成两条用例」与「文档上限夹具不再抢跑」，而它的 `git show --stat` 只有 `StreamedResponseTransportContractTest.kt` 一行（夹具超出量从 600 条字段改 320 条）；拆分那部分实际落在前一笔 `89707ff`。已提交历史不改写，用本笔登记订正。动作：commit 之后立刻 `git show --stat` 与 message 逐条对照，发现不匹配就补登记而不是 `--amend`。

### 实证缺陷与修复

1. **F1（P0 数据正确性 + P0 假绿门禁）非流式 JSON 文档被逐行切开后，回答被当成「成功的空回答」**。`JavaNetHttpTransport.postStream` 无条件逐行发射，而两 provider 的整段 JSON 容错分支要求「同一行以 `{` 开头且以 `}` 结尾」，于是**缩进排版**的非流式回答被切成碎片，每一片既不是独立文档也不是 `data:` 行 → 全部跳过 → 流尾兜底 `emit(Completed(""))`。真 socket 实测（环回、随机端口、`ServerSocket`）原文即 `expected:<[第一段内容]> but was:<[]>`，Anthropic/OpenAI/折叠契约各一条红，三条对照（紧凑单行文档、真 SSE 增量）在主干即绿。
   **为什么此前没人发现**：唯一断言该属性的 `ProviderStreamFramingTest.anthropicReadsAPrettyPrintedJsonDocumentFromAPostOnlyTransport` 驱动的是只实现 `post()` 的假件（走接口默认实现，`asSseLines` 能识别整段文档），而默认使用的 `JavaNetHttpTransport` 覆盖了这个方法——同一契约的两个实现行为相反，测试只绿的那一个不是产品用的那个。修法（根因层）：传输层按**响应媒体类型 + 首行 SSE 前缀**决定分帧；非事件流响应整体交出一个 emission（受 `maxResponseBytes` 约束，与 `post()` 同口径），并把契约折进**全部两个 `postStream` 实现**、都走真 socket 复验。
2. **F2（P1）200 响应中的 API error 文档被静默丢弃**。`{"error":{…}}` 缩进排版时同样被切碎，配额耗尽/过载变成一次空的完成——代码自己的注释写的是「mask the real failure」。两 provider 各加一条用例（含 `error.message` 为**对象**的代理形状，此时旧实现抛 `Element class … is not a JsonPrimitive`，用户看到的是序列化库内部消息而非端点原因，本轮一并按 `JsonObject.textOrNull` 修正，6 处落点）。
3. **F3（P1）可选参数写成 JSON null 时整个工具调用被拒**。跨 4 个模块的 5 个工具，共 14 个可选参数键：`agent_show_urgent_message` 的 `blocks/actions/form/placeholder/accent`；`skill_save` 的 `loadPolicy/triggers/embedFiles/overwrite`；`agent_task_update` 的 `schedule/action`；`read_teaching_evidence` 的 `offset/includeImages`；`demo_delay_propose` 的 `repeating`。根因是同一条：`JsonNull` **是** `JsonPrimitive` 也是**值**，所以 `this[key]`、`!= null`、`"k" in obj` 三种写法都把「网关没填」读成「模型要了」，再判整次调用非法。第七轮已在**响应侧**修过同一个 `JsonNull` 事实（`ProviderStreamNullFieldTest`），参数侧此前无人做。修法：每个模块一条 `optionalElement/declaresControl` 读取口（**不新增 Core 公开 API**，避免为两行惯用法扩大已发布 AAR 的 API 面）；null 一律等于文档化默认值，其中 `overwrite` 必须是 `false`（把 null 读成 true 会覆盖既有 skill），`accent`/非法类型仍判红（不得静默回落）。
4. **F4（P2）本轮第一版整改自带的三个新缺陷**（第 1/2 轮独立复核抓出，均已修并配判别用例）：① 守卫只认「出现过 `data:` 前缀」，于是 `data: [DONE]` 或 `data: 123` 仍旧空完成——改为要求负载被理解为事件对象；② 只按媒体类型分帧，使**误标 Content-Type 的真流式**被整段缓冲：丢掉增量送达、并把流落到 4 MB 文档上限，比修复前更差——加入首行 SSE 前缀作为第二信号，并断言 emission 形状（只看解析结果看不出这点）；③ 折叠契约的那条用例给 fallback 臂建了一个从不连接的 socket（假件死设置），且 `contains('\n')` 类断言对逐行读取器是永真式——改为两臂都走真 socket、并断言 emission 条数与响应体行数相等。
5. **F5（P2）文档事实源里有真 NUL 字节**。§35 在记录「带 U+0000 的 URL 不答话」时把那个字节直接写进了正文：Git 因此把该文件判为非文本（`git ls-files --eol` → `i/-text`），**ripgrep 对 §35/§36 的检索返回零命中**（修前实测 `No matches found`，修后命中 2 行），GNU grep 只回 `Binary file … matches` 而不给正文；`git grep` 不受影响（字节在第 84578 位，超出其 8 KB 采样窗）。本仓与审查纪律都要求「文档里声称的把关者必须 grep 核实」，而这份恰是 grep 不到的那一份。已改为字面转义写法。
6. **契约与代码不一致的两处（P2，同族第 6/7 轮反复出现）**：两个 attention 工具声明 `additionalProperties: false` 却在**顶层**完全不设防（嵌套 items 反而逐个筛键，同仓 `demo_delay_propose` 也答 `Unknown timer proposal field.`）——现两个工具都拒陌生顶层键，且「允许的键集」由 schema 自己核对；`agent_task_create/agent_task_update` 的 schema **没有任何 required 列表**，代码却拒缺失的 title/schedule/action/taskId（同参数名的 `agent_task_cancel` 反而声明了），现补齐并双向断言「schema 声明的必填 == 工具真正拒绝缺失的那些」。

7. **F7（P1）`pi-system-skill-android` 的三个可选参数读取把「网关没填」变成序列化库异常**。`AndroidPermissionTools.permissionsOrDefault`、`AppEnvironmentInfoTool.execute`、`AndroidAppIntentTool.execute` 用 `?.jsonArray` / `?.jsonObject` 读模型可控的可选参数；`JsonNull` 既不是 `JsonArray` 也不是 `JsonObject`，取属性直接抛异常，异常被运行时 Tool 捕获后变成一条**写着序列化类名的错误结果**，而它下一行就是文档化的默认权限集——`permissions: null` 不但没拿到默认值，连原因都读不懂。根因与 F3 同一条（`JsonNull` 既是值又是 `JsonPrimitive`），但本轮第 2 轮的同族清点没覆盖这个模块，是第 4 轮复核捞回来的（已登记为过程失败）。修法：模块内一条 `optionalElement` + `declaredStringList`（缺席或 `null` ⇒ 默认；声明了但不是列表 ⇒ 明确拒绝并在消息里点出参数名；`AppEnvironmentInfoTool` 不再抄第二份表达式）。用例 `SystemSkillOptionalArgumentTest` 13 项：按**主干语义**（把 null 判定、`?.jsonArray`、`?.jsonPrimitive`、`?.jsonObject` 四处一起退回主干写法，脚本 `run-baseline-red-system-main-semantics.sh`）实测 `baseline-red-system-main-semantics.log` = 13 项中 5 红、8 绿——红的是两条 `null*`（本条缺陷）与三条 `declared*`（主干对声明的非列表/非字符串项直接抛），绿的是 `absent*`、`declaredPermissionsAreHonoured`、`empty→默认`、`nullPermissionEntries…` 与 `theListReader…`（后者钉的是分支新写的三态读取口，本身不是主干/分支判别用例）。更早那份 `baseline-red-system.log`（10 项中 2 红）只回退了 null 判定、保留了分支的 `as? JsonArray` 安全转换，**不是主干语义**，第 5 轮据此订正本节口径。判别行 `z60/z61/z62/z81/z82/z83`。Tool 侧「把这个答案路由给权限请求/Intent 工厂」那一半需要 `Activity`/`Context`，宿主跑不了，成本写在测试类 KDoc。
8. **F8（P1）`terminal_bash_execute` 把两个未填的可选参数当成非法值**。`BashCommandTool` 的 `when (val rawTimeout = call.input["timeoutMillis"])` 有 `null -> 默认` 这一臂，但 `JsonNull` **是** `JsonPrimitive`，于是落到 `longOrNull` 失败分支 ⇒ `timeoutMillis: null` 被 `INVALID_TIMEOUT` 拒绝；`parseEnvironment(call.input["environment"])` 同理把 `environment: null` 判成 `INVALID_ENVIRONMENT`。这是 SDK 里被调用最多的 Tool，也是同一条 `JsonNull` 事实在本仓的第五个模块。修法与 F7 一致（模块内私有 `optionalElement`，声明但类型错误的值仍判红），补 6 项用例：`baseline-red-terminal.log` 6 项中 2 红、4 绿（两条 `declared*` 拒绝用例与 `absent*` 对照在主干即绿），判别行 `z70/z71/z72`。
9. **文档声称的把关者有一条不存在（P2，第 4 轮抓出）**。`isEventStreamContentType` 的 KDoc 写着「超过 `maxResponseBytes` 的正文响亮失败，这条边界由一条测试钉住」，但仓库里没有任何用例断言文档臂的上限抛错（修前 `grep -rn "exceeds maxResponseBytes" --include=*.kt */src/test` 零命中）。已补 `bufferedDocumentOverTheCapFailsLoudlyInsteadOfBeingHandedOverTruncated` 并把两个臂的用例名写进 KDoc，使这句声称可 grep 核对。同轮把 `textOrNull` 的实现改成与它自己的注释一致（`isNotBlank`；此前 `"   "` 被当成一条真实的错误原因，报出 `Anthropic API error:    `），补 `anthropicFallsBackToTheErrorTypeWhenTheMessageIsOnlySpaces`。

10. **F10（P1，本轮整改自引入，第 5 轮抓出）新的列表读取口把「声明了但不是名称」静默变成默认权限集**。`declaredStringList` 第一版逐项链 `mapNotNull { (it as? JsonPrimitive)?.contentOrNull }`，于是 `permissions: [{"name":"camera"}]` 被丢成空列表，而调用方把空列表读成「没人要求」⇒ 回落 `AndroidPermissionCatalog.defaultRuntimePermissions()`：一次运行时权限请求的清单由一个非法形状决定。主干在这条上是抛 `IllegalArgumentException`（`it.jsonPrimitive` 对对象抛），同样不按参数名拒绝——两种行为都不合格。修法：项级三态（`JsonNull` 项=未填 ⇒ 丢弃；字符串项 ⇒ 采纳；其余任何项 ⇒ `Unusable` ⇒ 按名字拒绝），并把 `Values` 的注释改成与实现一致。证据：改前红 `r6-system-red-before-fix.log`（13 项中 1 红，红名 `declaredNonStringPermissionItemsAreVisibleAsUnusable`，原文 `expected null`），修后 `r6-system-green-after-fix.log` 13/0/0；判别行 `z81`（把项级严格性退回丢弃）、`z82`（`null` 项不再被宽容）、`z83`（intent 参数的对象检查退化成强转）。同轮订正第 5 轮立案①：拒绝消息的用例原先被错误码满足，已改为断言 `message` 字段本体，判别行 `z80`（把 message 换成不相干的话 ⇒ 该用例红）。注意这是一次**相对主干的收紧**：`permissions: [5]` 在主干是被丢弃后走默认，现在按名字拒绝。

### 独立复核（本轮实跑 6 轮只读，已到委托人设的上限；第 1 轮两路并行，其后每轮专审上一轮写下去的整改；立案一律在当前字节复跑后才处置）

- 第 1 轮（两路并行，一路审修复面、一路审取证面）在整改里抓出 F4 的三个自引入缺陷、F2 的 `error.message` 对象形状、假件死设置、永真式断言、以及「折叠只覆盖了臂的一半」。
- 第 2 轮专审第 1 轮的整改，抓出 F3 在另外四个模块的 7 个同族落点（`overwrite`、`stringList`、`schedule/action`、`offset/includeImages`、`repeating`）、attention 顶层 `additionalProperties` 装饰化、`required` 列表缺失、以及「一个测试里跑 for 循环 → 第一处红遮住后面所有行」这一取证形态问题。
- 第 3 轮专审第 2 轮的整改（提交 `87f9bbd` 承接），抓出四条由整改自己写进去的问题：① 陌生键守卫按「键是否出现」判定，把 F3 在「非交互宿主 + `actions: null`」这条路径上重新破坏；② 「允许的键集」是手抄清单且已经漂移——通知工具拿紧急工具的键集校验，`accent` 照旧被接受；③ 首行嗅探未 `trimStart`，而 provider 侧读取会 `trim`，制表符开头的流被误判成文档；④ `error.message` 为空串时报出 `Anthropic API error: `（尾随空原因）。同轮把「for 循环遮蔽后续落点」这条形态问题落到 attention / skill / demo 三个文件。
- 第 4 轮专审第 3 轮的整改与本节的文字口径，抓出：① 两条**恒真断言**——`undeclaredArgumentNamesAreVisibleToBothTools` 拿生产 schema 派生的键集去比生产 schema 自己，生产是自己的 oracle，永远不可能失败（真正承载权重的是同文件里的字面量钉），已改为三张形状各自的字面量期望，并删掉只为喂这条恒真断言而存在的 `notificationSchema()`；② 本节把 `r1-f1-baseline-red.log` 的数字写错了（写作「6 红/8 对照绿」，该日志实为 6 项中 3 红、3 绿），并把 attention 的基线复现指向 `run-f2-baseline-repro.sh`（脚本本身不含数字，日志是 `r1-f2-baseline-red.log`）；③「每个落点都有主干红用例」在 skill/schedule/demo 上名不副实——现存日志取自拆分前的合并用例（各 3 项中 1 红），拆分后未按落点重取；④ 本节声称 §34–§36 按历史快照保留，但 §36 末行的句号在编辑时被挪到了文件末尾成为孤行（`。`），已把 §36 复原、孤行删除，现 §34–§36 与基线的唯一差异是 F5 那一处 NUL 字节改写（本轮唯一有意就地订正的历史行，理由见 F5）。
- 第 5 轮专审第 4 轮的整改（`bbfdcd3`/`e235b35`/`88c076c`/`77eef46` 与本节自身），抓出四条立案：① `unusablePermissionsAnswerWithARefusalThatNamesTheArgument` 断言 `contains("permissions")`，而错误码 `invalid_permissions` 单独就能满足它——把 message 换成不相干的话用例仍绿（假绿断言，按本轮口径等同 P0）；② 新增的共享列表读取口把「声明了但不是字符串的项」逐项丢弃，落到「空列表 ⇒ 用文档化默认」，于是 `permissions: [{"name":"camera"}]` 变成一套没人点过的运行时权限（主干在这条上是抛异常，两种行为都没有按参数名拒绝）；③ F7 所写「`declared*` 对照在主干即绿」不成立：那份 `baseline-red-system.log` 只回退了 null 判定，保留了分支新写的 `as? JsonArray` 安全转换，因此它不是主干语义；④ 「已核查不修」把 `?.jsonPrimitive?.contentOrNull` 一族整体判为无害，只对 `JsonNull` 这一臂成立——同一表达式遇到**声明了**的对象/数组仍抛序列化异常。另有两处口径问题（⑦ 引用的三次重跑日志里没有计数；派发只读线程的 prompt 写了不存在的 HEAD `406b237`，实际冻结字节是 `d8a404d`）。
- 第 6 轮专审第 5 轮的整改（`c23ee71` 与两份取证脚本），到委托人设的 6 轮上限为止。**第 6 轮自己的整改没有再经过一轮独立复核**，如实登记而不宣布收口：其证据是改前红（`r6-system-red-before-fix.log`：13 项中 1 红，红名即新增用例）、`mutation-matrix-round6.txt` 7 行全 OK、重跑后 `mutation-matrix-round5.txt` 15 行全 OK，以及下面那条矩阵运行器假绿的负向对照。下一轮应从「专审第 6 轮写下去的整改」开始。
- **本轮抓到自己的取证门禁假绿一次（等同 P0）**：`run-mutations-round5.sh`/`run-mutations-round6.sh` 的判决段写成 `python … | tee -a`，而 `row()` 读的是 `$?`＝`tee` 的退出码（恒 0），于是 `FINAL_MUTATION_RC` 与行级 MISMATCH 无关——第 5 轮那次「15 行、FINAL=0」就是这么来的。修复为读 `${PIPESTATUS[0]}`，并先做**负向对照**（`run-matrix-rc-negative-control.sh`：故意把一行期望写成不存在的用例名）→ 实测 `ROW z99-rc-negative-control MISMATCH` + `FINAL_MUTATION_RC=1`，证明这道门禁真的会红；随后重跑两套矩阵，`round5` 暴露 2 行 MISMATCH（`z60`/`z62` 的期望集合写于新增用例之前），按当前用例集订正后 15/15 OK，`round6` 7/7 OK。教训：用例改名或新增之后，旧矩阵行的期望集合本身就是过期断言，必须重跑，不能沿用旧结论。
- 第 4 轮被否掉的立案（附处置依据）：① 「可选键以 null 出现时不再被陌生键守卫拒绝」被指为「一个没人实现的参数看起来被 honoring」——这条正是 F3 的规则本身（网关把每个未填的可选键都发成 null），null 值键不构成声明，双向用例 `undeclaredArgumentNamesAreVisibleToBothTools` 已覆盖，判为按设计；② `textOrNull` 把空串读成缺失，使 provider 的原始负载兜底分支更容易被走到，而 `malformedSseEvent` 有意截到 200 字符——判为无后果：响应体长度已由传输层 `maxResponseBytes`/文档上限约束，且 6 处落点中只有 `fullBodyApiErrorMessageOrNull` 对空串敏感，其 null 分支仍会经 `parseResponse` 的 body 兜底抛出；③ `event:`/`id:`/`retry:` 前缀嗅探为事件流但永不置位 `sawUnderstoodEvent`，此类响应以「响亮失败」收场而非回落缓冲——本轮修复前该路径同样是失败，方向一致，登记不修。
- 被否掉的立案（附复测命令）：① `LocalHttpServerTools.runToolCall` 的 `catch (Exception)` 吞取消——`block` 非挂起、`controller.start()` 阻塞、`withContext` 的取消异常发生在 `try` 之外，宿主无法从这条路径抛 `CancellationException`，判为已核查不可达；② `LocalHttpServerStatusTool.description` 的「不 start/stop/signal」——`status()` 只 forget 确认已死的记录，与同文件 KDoc「never signals, stops, or rewrites a live service」一致，判为文字正确；③ §34/§35 里「`pi-attention-skill-android` 仍 NO-SOURCE」按「带轮次时间戳的历史快照」保留，不改写历史，只在本节记录关闭时点。复测命令均在 `build/review-evidence/` 脚本内。

### 已核查不修 / 遗留风险

- F7/F8 之后用同一条特征值再扫一遍剩下的命中，判为不修并留下依据：① `?.jsonArray` / `?.jsonObject` 在本仓的其余命中全部在**读自己写出去的磁盘 JSON**（`ApiSettings`、`DemoOperationDraftStore`、`DemoTeachingStore`、`DemoWorkflowRepository`）或第三方余额/模型端点（`ApiQuotaAndConnectivityService`）——那里 `null` 意味着文件被写坏或上游给了非法形状，响亮失败是这两条路径本来要的语义，把它降级成「当缺失处理」会掩盖损坏；② `?.jsonPrimitive?.contentOrNull` / `stringValue` 一族**只对 `JsonNull` 这一臂**已经等于「缺失走默认或按必填拒绝」（`AndroidClipboardTools` 的 `text/label/sensitive`、`AndroidAppCatalogTool.query`、`AndroidSystemPageTool.target`、`ScreenAutomationTools.text`、`DemoScreenAutomationPolicy` 的诊断串）：同一表达式遇到**声明了**的对象/数组仍抛序列化异常（`?.jsonPrimitive` 对非原始值抛），第 5 轮据此把「不是缺陷」的措辞收窄为只对 null 臂成立，并把这五处登记为下一轮候选——修它要给每处补「声明但形状错误 ⇒ 按参数名拒绝」的语义，机械替换成 `as? JsonPrimitive` 会把非法值静默变成默认，违反本轮刚立的不变量；③ `{"error":"一个字符串"}` 这种把 error 发成标量的端点仍会被读成一条空的**成功**回答（两个 provider 都只识别 `error` 对象），该形状在 `main@710d567` 就存在、本轮没触碰判定它的代码，登记为下一轮的候选而不是顺手改。
- `LocalHttpServerManager.start()` 容量清理会 `removeRecord` 掉 `REPORT_UNATTRIBUTABLE` 行（删的正是 token 的唯一存留处，端口可能仍在服务）；`ensureMetadataLoaded` 对任何解析失败 `.onFailure { metadataFile.delete() }`。两处都在代码注释里**自我声明为刻意取舍**（前者解「上限被过期记录永久占满」，后者配 stage+rename 保证「撕裂写不会被当成有效」）。按「把有意设计当缺陷」的既有教训本轮不动，登记为遗留风险；若要改，方向是「退役而非删除」（改名 `.retired` 保留 token）并先在仪器面复现端口仍活。
- `DemoUrgentInteractionDispatcher`（零用例）两处**未证实**：① `onOutcome` 的 `checkNotNull(store.appendMessagesAndFlush(...))` 在会话运行中被删除时抛到 `DemoAgentRunCoordinator.dispatch` 的 `runCatching` 里，其后的 `setSending(false)/setStatus` 不再执行，悬浮窗停在「正在处理悬浮操作」，只能靠悬浮窗「停止」复位；② `submit()` 已返回 true 后若 `drain()` 撞上 `isScreenOperationOwned()` 会 `cancelPending()` 静默丢弃，且 `presentationId` 留在 `acceptedPresentationIds`，同一屏幕再点被永久拒。合并前应跑：`:demo-app:connectedDebugAndroidTest`（先 `:app:installDebug`，再用 appops 授 `SYSTEM_ALERT_WINDOW`），或在 AVD 上定向跑 `DemoUrgent*`/悬浮相关用例。
- 本轮**未运行任何设备/仪器门禁**；打包面跑了 `:demo-app:assembleDebug`（数值见「门禁口径」节的交付态打包一条），`verify-runtime.ps1 -CheckPackages` 未跑（本轮未触碰原生载荷）。§33–§36 的设备数字仍是历史时点。合并前应跑：`:demo-app:connectedDebugAndroidTest`、`:terminal-probe-demo-a:connectedDebugAndroidTest`、`:terminal-probe-demo-b:connectedDebugAndroidTest`、`scripts\terminal-runtime\verify-runtime.ps1 -CheckPackages -NdkRoot (Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358')`；`-CheckPackages` 未跑的原因是本轮未触碰载荷与打包，而非不可跑（PowerShell 与 NDK 均已实测在位）。

### 本轮新增教训（建议固化为动作）

- **契约测试必须折叠在该契约的全部实现上，并且都走真边**：接口有默认实现 + 子类覆盖时，只测默认实现等于没测。动作：新增/修改任何 `interface + override` 的验收时，把实现列表写进一条 `listOf(...)` 折行用例，逐臂打印臂名与失败原因（本轮 `z04` 行一次红 10 项即为此形态）。
- **一个测试方法里的 `for` 循环 = 第一处红遮住其余落点**：兄弟落点要么拆成独立 `@Test`，要么在循环里收集失败后一次性断言。本轮 `n5/n7` 两行都只报出同一个索引，就是这种形态的伪装。
- **每落地一遍整改就再开一轮只审这一遍**：本轮 F4 的三个缺陷全部来自第一版整改，且都是「本轮正在打的那类形状」的复发（恒真式断言、死设置、单信号判断）。
- **变异必须换掉那一行的语义**，加一个 `true &&` 前缀不是变异；取证脚本必须自带「时间戳与上一行不同」的硬条件，否则编译失败会静默留下上一行的 XML。
- **未提交的改动绝不能和 `git checkout` 类脚本同处一个工作树**：本轮两次被自己的还原逻辑抹掉成果。动作：任何线程/脚本派发前先 commit 冻结。
- **文档里的把关者要 grep 核实，而 grep 本身要验能被检索**：本轮第一次 `grep -rn docs/` 就因一个 NUL 字节漏掉了整份事实源。动作：接手文档密集的仓库先跑一次「tracked 文本文件含控制字节」扫描（`git ls-files --eol` 里的 `-text` 即线索）。
- **断言不许把生产当自己的 oracle**：本轮抓到两条「用 schema 派生的键集去比同一个 schema」的用例，它们永远为真，权重全在旁边的字面量钉上——而这两条恰好是上一轮为了修「手抄清单漂移」写下去的。动作：新增断言时问一句「生产代码改坏，这条会红吗」，不会就删掉或换成字面量。
- **取证脚本自己那道「全绿」信号必须做负向对照**：本轮两套矩阵脚本的 `FINAL_MUTATION_RC` 取自 `tee` 的退出码（`python … | tee` 之后 `$?` 恒 0），于是它可以带着 MISMATCH 写 0——和它要防的假绿一模一样。动作：任何「汇总行说全绿」的脚本，先故意造一行不成立的期望跑一次，看到它真的红，才允许引用它的绿色结论。
- **三态读取口的每一条臂都要有自己的用例**：`缺席 ⇒ 默认`、`null ⇒ 默认`、`声明但不可用 ⇒ 拒绝` 三臂里，本轮两次栽在第三臂（项级形状被丢弃后落到「空=默认」；断言被错误码满足而不看消息本体）。动作：新增这类读取口时按臂数写用例数，并在用例名里点出是哪一臂。
- **改名或拆分用例，必须同批改取证脚本的期望集合**：矩阵行指向已不存在的用例名，等于把该行变成既不红也不验证任何事的空跑。动作：改完用例名跑一次 `grep -rn "<旧用例名>" build/review-evidence/*.sh`，命中的行按当前字节重跑。
- **同族清点的边界是「特征值扫描的命中数 == 逐条处置数」**，不是「我改了几个文件」：本轮 F3 的规则落完四个模块就收手，剩下两个模块（F7/F8）靠复核捞回。动作：规则落地后立刻跑一次全仓特征值扫描（本轮为 `grep -rnE '\?\.\s*json(Array|Object)' --include=*.kt */src/main` 与 `grep -rnE 'call\.input\["[a-zA-Z]+"\]' --include=*.kt */src/main`），每条命中要么改、要么写进「已核查不修」并附依据。
- **交付日志里的 `EXIT=` 必须来自真实退出码**：本轮一次把未赋值的变量写进 `EXIT=`，日志按自己的口径不作完成信号，只能重跑。动作：`cmd > log 2>&1` 后单独一行 `RC=$?`，再 `echo "EXIT=$RC" >> log`，写完立刻 `tail -2 log` 核对。
- **行尾/属性卫生**：`git ls-files --eol` 显示 3 个 Kotlin 文件各含 1 行 CRLF（`DemoWorkflowActionGateway.kt`、`DemoWorkflowRunner.kt`、`DemoWorkflowRunnerTest.kt`），且无 `.gitattributes`。本轮未改（无可证后果，属同一陷阱的潜伏形态），建议下一轮以「加 `.gitattributes` 并把 `*.kt text eol=lf` 与 `*.bat text eol=crlf` 钉住」一次性收口。

## 40. 第十三轮 P0 审查（网关错误形状 / 截断原因集合 / 「声明但不可用」的参数形状，基线 `main@13bf709`，2026-10-04）

- 切割依据：本轮对象是「模型或网关给出的值，本客户端读不出意义时，被读成成功或默认」这一族。§38/§39 已进主干，其覆盖面（`HttpTransport` 分帧、`SseStreamFraming` 的行切分、`OptionalArgumentReading` 一族）本轮只在**新的落点或新的形状**上继续，不重复修。全仓 597 tracked 文件 / 332 个 Kotlin / 14,850 行，一轮做不完：terminal 原生载荷与打包、权限与无障碍后端、demo UI 布线本轮不动。
- 宿主与通道：Windows 10.0.26200.9457 / Git Bash 5.2.37；Node v24.15.0、Python 3.14.2、Git 2.50.0；JDK 17.0.11 在 `E:\Android\Android Studio\jbr`（PATH 无 `java`、继承 `JAVA_HOME` 为空，gradle 调用必须显式指定）；`GRADLE_USER_HOME=E:\DevCaches\gradle`（Gradle 8.13 在位）；Android SDK `E:\Android\SDK`。仓库仍无 `.github/workflows`，AGENTS.md 的十个 `testDebugUnitTest` 是唯一门禁且全部人工执行。
- 门禁口径（只认日志内 `EXIT=`、`TOTAL`、`MODULE_RESULT` 行与 JUnit XML `timestamp` 窗口）：
  - 基线 `main@13bf709` 独占 `--rerun-tasks`：**`984 tests / 3 skipped / 0 failures / 0 errors`**，146 份 XML，窗口 `2026-10-03T19:03:43.306Z…19:05:31.801Z`；日志 `build/review-evidence/r13-baseline-fullgate.log`（内 `EXIT=0`、`STATUS_BEFORE=0`、`STATUS_AFTER=0`），逐模块 `r13-baseline-aggregate.txt`。这同时**证实** AGENTS.md 当时写的 `984` 可复核——那条不是假绿。
  - 交付态：代码状态 `ef415d1`（`git status --porcelain` 实测为 0 后独占运行，日志内 `EXIT=0`、`STATUS_AFTER=0`）：**`1033 tests / 3 skipped / 0 failures / 0 errors`**，154 份 XML，窗口 `2026-10-03T20:55:30.293Z…2026-10-03T20:56:27.602Z`；日志 `build/review-evidence/r13-delivery-fullgate.log`，逐模块 `r13-delivery-aggregate.txt`。**取数之后落库的只有文档：本节、AGENTS.md 的数字重述，以及本节的三处文字订正；不含任何 `src/` 改动**，该差值在此点名，不要让读者以为数字随分支 HEAD 自动有效。
  - 交付态打包：`:demo-app:assembleDebug` 为 `BUILD SUCCESSFUL in 12s`、`211 actionable tasks: 15 executed, 196 up-to-date`，`EXIT=0`（`build/review-evidence/r13-delivery-assembleDebug.log`，2026-10-04 04:56:28），`demo-app/build/outputs/apk/debug/demo-app-debug.apk` 实测 45468897 字节。
  - 逐模块差值（基线 → 交付）：净增 49 项（对基线 984 的逐模块差值）：demo-app 353->374, pi-schedule-skill-android 27->34, ugk-pi-android 244->265。未变模块：pi-agent-skill-runtime-android 101, pi-attention-skill-android 18, pi-file-skill-android 17, pi-system-skill-android 63, pi-terminal-skill-android 52, ugk-agent-task-runtime-android 49, ugk-terminal-runtime-android 60。`skipped=3` 来源不变（`pi-file-skill-android` 1 项 + `pi-agent-skill-runtime-android` 2 项的 Windows symlink 既有用例）。
- 发现与处置：
  - **F1（P1，静默成功 + 同族折叠缺失）** `AnthropicMessagesProvider.parseResponse`、`AnthropicMessagesProvider.fullBodyApiErrorMessageOrNull`、`OpenAiChatCompletionsProvider.parseResponse`、`OpenAiChatCompletionsProvider.fullBodyApiErrorMessageOrNull` 只认 `error` 的对象形状。`{"error":"Overloaded"}`（代理/旧网关写法）在 Anthropic 侧完成成一次空回答并把 `completedEmitted` 置真——正是绕过 §39 分帧守卫的那条形；在 OpenAI 文档路径被报成 `OpenAI response missing choices[0]`，把调查方向从端点原因引到响应字段。另抓到 `{"type":"error"}`（协议自带标记、无 `error` 字段）在主干同样是静默空答案。证据：`build/review-evidence/r13-f1-baseline-red.log`（`StreamedResponseTransportContractTest` 37 项 7 红，红句原文 `expected java.lang.Exception to be thrown, but nothing was thrown` 与 `expected the endpoint's own reason, got: OpenAI response missing choices[0]`；两条 `"error":null` 对照同运行绿）。根因：同一判定被抄六遍，每遍只认一种形状；同文件的中流事件臂早已按 presence 处理，说明省略是缺陷不是设计。修法：`SseStreamFraming` 抽成两条规则并折叠全部六个落点——文档路径 `apiErrorReasonOrNull(root, fallback)`（缺席 / `JsonNull` / 空序列化哨兵 `"" false 0 [] {}` ⇒ 未报告；顶层 `type=="error"` ⇒ 报告），事件路径 `streamErrorReasonOrNull(error, payload)`（presence ⇒ 报告，`JsonNull` ⇒ 缺席）；reason 与 echo 分别有界（`MAX_API_ERROR_REASON_CHARS`=1000 / `MAX_API_ERROR_ECHO_CHARS`=200）。取舍：两条 presence 规则**故意不同**——文档体里可能有需要保留的完整答案，事件信封里没有；合成一条会把中流 `{"error":{}}` 变成静默续流。兄弟落点：`grep -rn '\["error"\]' */src/main` 命中 7 处 / 3 文件，demo 两处见 F8，`DemoWorkflowCompiler` 的 `output["error"]?.let` 见 F8；`pi-terminal-skill-android` 的 JSON-RPC 读侧无 `error` 键读取（扫描零命中）。
  - **F2（P1，截断答案被当完整答案）** `AgentRuntime.isIncompleteFinalResponse` 认两个原因且比较原始串；`max_output_tokens`（Gemini 兼容端点的拼写）因此让半句话以 `AgentEvent.Completed` 落进 transcript。证据：`r13-f2-baseline-red.log`（5 项 2 红，红句 `expected the run to refuse the truncated answer, got: Completed(content=半句话说到一半)`；`length`/`end_turn`/`refusal` 三条对照绿）。兄弟落点：demo 六个消费者——两个教学读取器会归一，`DemoOperationStepReviewer`/`DemoWorkflowCompiler`/`DemoWorkflowRunner` 比较原始串，`DemoModelIntentRouter` 既比较原始串又只认两个原因；本轮把六处收进 `DemoModelStopReasons` 一个对象（`DemoModelStopReasonsTest` 逐成员 + 逐拼写折叠），core 侧保留私有集合，`StopReasonCompletenessContractTest` 逐成员拒绝。两侧不能合并：`internal` 出不了已发布 AAR，为两个常量扩公开 API 面不值。明确不扩：`content_filter`/`sensitive`/`refusal` 不进重试集合——拒绝是模型说的话，重放三次既花钱又把它藏进通用失败，由 `safetyStopReasonCompletesInsteadOfRetrying` 钉住。登记局限：`DemoTeachingCompiler.knownReasons` 只给 trace 字段打标签，不是完整性判定，故意不入集合。
  - **F3（P1，数据正确性）** `AgentTaskListTool` 的 `status` 用 `runCatching { AgentTaskStatus.valueOf(it) }.getOrNull()`，null 即「不过滤」：拼错、小写、数字都返回全表（含 CANCELLED/FAILED）且 `ok=true`。证据：`r13-f34-baseline-red2.log`（逐臂红，原文 `lowercase name: expected a refusal naming status, got 3 rows`）；「键缺席」对照绿。
  - **F4（P1，且是对上一轮登记的改判）** 同工具 `activeOnly`：严格布尔读法 + `?: false` 使 `"True"`/`1`/`""` 读成不过滤。§39 与 `ToolJson` KDoc 曾以「本模块还没有三态读取口，另写一份会造成两套规则」登记不修；PR #13 合并后该模块的 `optionalElement` 就在同一文件里被 `agent_task_update` 使用，前提过期 → 本轮改判、把 `agent_task_list` 两个过滤参数接进既有规则，并订正 `ToolJson` 指向（原句指向不存在的「shared optional-argument reader」）与 §39 那条登记的理由。schema 此前只写「Optional AgentTaskStatus name」而代码要求精确名 → 补 literal `enum`，由 `AgentTaskListFilterSchemaTest` 与 `AgentTaskStatus.entries` 按集合对折（字面量清单是独立 oracle，避免生产自证）。**相对主干是收紧**：不可用的声明值按名字拒绝，而不是悄悄放宽结果集。
  - **F5（P2，失败读不出是哪个参数）** `DemoTeachingExperiencePlugin.ToolCall.text` 用 `jsonPrimitive.content`，而 `JsonNull` 是 `JsonPrimitive`、其 `content` 是字符串 `"null"`：`{"usageId":null}` 被当成真 id 去查，回执「没有已确认的经验使用」；声明成对象/数组则抛序列化库消息；`require(outcome in setOf(...))` 无消息 → 落进本文件的通用兜底；`{"revision":"abc"}` 报 `For input string`。证据：`r13-f5-baseline-red.log`（7 项 5 红，红句原文含 `Element class ... is not a JsonPrimitive`、`Failed requirement.`、`For input string: "abc"`）；键缺席与正常上报两条对照绿。
  - **F6（P1，本轮修复自己引入，两条独立复核分别命中）** F1 第一版按 presence 判定，使 `{"error":"","choices":[…完整答案…]}` 以及 `false`/`0`/`[]`/`{}` 这类「结构体整字段序列化」的正常 200 回答被硬失败——把一个还能用的集成改成全量失败，比它要修的空答案更坏。修法即上面「两条 presence 规则故意不同」。证据是反向的：`anthropicAnswersTheDocumentWhenAnUnsetErrorFieldRidesAlongACompleteAnswer` / `openAi…` 两张形状表（各 5 臂）在第一版代码下红、在当前代码绿；其中 `{"error":{}}` 那一臂在**主干**也红（旧代码遇到空对象同样抛），所以这一组同时钉住新旧两种错法。变异行 `d1`。
  - **F7（P2，同上：边界只做了一半）** 第一版只给 echo 有界，`readable`（端点自己给的 message）无界 → 数 MB 的端点字符串可以进异常消息、被 host 记日志并写进 transcript；demo 侧两处新拒绝文案也把模型给的值全量内插。现 reason / echo / 参数回显各自有界并各有一条用例（`anthropicBoundsTheEndpointErrorReasonItQuotes`、`anthropicBoundsTheUnreadableErrorPayloadItEchoes`、`refusalEchoesOnlyABoundedPrefixOfTheRejectedStatus`、`outcomeRefusalDoesNotEchoTheWholeRejectedValue`），变异行 `d4`/`d5`/`d20`/`d24`。
  - **F8（P2，同族没堵完）** 同一个「只认对象形状 / 只认键在场」的事实还立在 demo 两处：`DemoTeachingModelProvider.checkServiceResponse` 判 `get("error") is JsonObject`（标量错误因此不进 `DemoTeachingRequestFailure.http` 的按码归因，只剩 `REQUEST_FAILED` 通用码），以及 `DemoWorkflowCompiler` 的计划门禁 `output["error"]?.let { throw … }`——后者把模型给出的完整计划里 `"error": null` 读成「缺少完成目标所需的证据」，让人回去补录素材。两处并入 `DemoApiErrorSignal`。证据：行为用例 `aNullErrorFieldInModelOutputDoesNotRefuseThePlanAsMissingEvidence`（红在主干语义，见变异行 `d18`）+ 对照 `anErrorTheModelActuallyStatesStillRefusesThePlan`（主干即绿）+ 形状表 `DemoApiErrorSignalTest`。
- 复核抓出并被就地订正的**声称失实**（本轮自己写下的句子）：`ToolJson` 与两处 schedule 用例 KDoc 说 `agent_task_update` 在 `agent_task_list`「之上/just above」——实测 `AgentTaskUpdateTool` 声明在其**下方**；`AgentRuntime` 集合 KDoc 与 `StopReasonCompletenessContractTest` KDoc 说 demo 侧「都按大小写不敏感读」「每半边都有钉」——实测六个里有四个比较原始串、`DemoModelIntentRouter` 根本没进任何声称列表；两个 provider 的旧注释说「stop reason 为 max_tokens/length 会重试」——集合已变，改为不点名成员的措辞以免再次漂移；`kotlinx booleanOrNull` 接受 `"True"/"FALSE"` 这一被两处 KDoc 引用的库事实此前无人验过，现由 `ToolJsonTest.kotlinxBooleanOrNullIsWhatTheConsolidationStoryClaimsItIs` 直接断言。
- 已核查不修（附依据）：`LocalHttpServerTools` 端口族——复核称异常被塌成 `LOCAL_HTTP_SERVER_FAILED`，读码否证：`runToolCall` 有显式 `catch (error: IllegalArgumentException) -> "INVALID_INPUT"`，残留只是消息按类型而非按参数名描述，登记为下一轮候选；`DemoDelayAgentPlugin.description`「the app asks the user to confirm」由 `DemoDelayedTaskController.confirm(taskId)` 与其上注释支撑，声称属实；`AgentTaskCreateTool` 的嵌套 `schedule`/`action` 已在 `parseSchedule` 内按名字结构化拒绝非对象声明，不需再接 `optionalElement`，而 `update` 的 `title` 在 `null` 时「保持原值」是设计；`REPEATING_UNTIL` 缺 `startAfterSeconds` 默认 `0L` 与 `ONE_SHOT` 必填的不对称属有意语义；`ToolJson.boolean("overwrite") ?: false` 两处默认方向 fail-closed；`?.jsonPrimitive`/`?.jsonArray` 在读自身磁盘 JSON 与第三方端点上的命中维持 §39 判定；`AndroidAppCatalogTool`、`ScreenAutomationTools` 的 `stringValue/intValue/doubleValue`、`DemoScreenAutomationPolicy`、`DemoTeachingSopAgent.intOrNull` 属 §39 已登记的下一轮候选。特征值扫描存档 `build/review-evidence/r13-sibling-scans.txt`（error 键 7 命中/3 文件、`?.json*` 126/23、`call.input[` 17/9、`ToolJson` 访问器 31/4、`stopReason` 27/13、原因字面量 22/11）。
- 独立复核（截至本时点）：第 1 轮两路只读线程（一路审修复面，一路审声称与兄弟落点）+ 第 2 轮一路**前台**只读线程专审本轮整改，另加本作者冷读；立案全部在当前字节复跑后才处置——F6/F7/F8 与四条 KDoc 失实全部来自这一层，即「整改带的是新缺陷」这条规律第七次被实证。F1–F8 的落地未经再过一轮独立复核，如实登记而不宣布收口；下一轮应从「专审本轮 §40 落地下来的整改」开始。取证：`build/review-evidence/mutation-matrix-r13-final.txt`（首跑 `ROWS=24 NOT_OK=6 NEGATIVE_CONTROL_CAUGHT=True`——那 6 行全部是**期望与变异范围**写错，不是代码不红；逐条订正后复跑 `build/review-evidence/mutation-matrix-r13-corrected.txt` = `ROWS=6 NOT_OK=0 NEGATIVE_CONTROL_CAUGHT=True`，即 24 个决定点在最终字节上全部「退回即判红」，且这条汇总门禁自己被抓红过一次）。
- 过程失败登记：① 首轮属性普查跑在上一轮分支 tip `eb8b246` 上，于是把「没有 `.gitattributes`」「3 个 Kotlin 文件混合行尾」写进计划；checkout 到基线后实测 `.gitattributes` 已存在（`ccf0a6e`）且那三个文件已是 LF——§39 结尾的交接项实际早已由 PR #14 关闭，本条为登记不是重开。② 变异矩阵前两版把 `m1c`/`m1d` 的期望写成「四个 provider 站点各自独立可判红」，实测 MISMATCH：折叠后 `fullBody` 站点被 `parseResponse` 兜住，而 `type` 候选臂真正的判别用例是既有的 `anthropicFallsBackToTheErrorTypeWhenTheMessageIsBlank`/`...OnlySpaces`（`...MessageFieldIsAnObject` 两条由有界 body 回显满足，并不钉 `type` 臂）。③ 第三版（当前在跑的）矩阵里 `d5` 的替换把 reason 与 echo 两条边界一起去掉、`d6`/`d7` 的期望少列了三条同样被该回退打破的用例——都是**期望与变异范围**写错，不是代码问题；订正后按行重跑。④ 一次「提前全量」把日志重定向写错（外层 `> /dev/null`），该次运行无可引用 `EXIT=`，按未完成处理；`run-full-gate.sh` 已改为脚本内部自落盘。⑤ 取证脚本第一次调用即 `FileNotFoundError`：Git Bash 的 PATH 首位是 MSYS 包装 `git`，Windows `CreateProcess` 不能执行，Python 子进程必须用 `D:\Git\cmd\git.exe`；该次运行未改动任何文件（未提交的测试改动因此未被 `git checkout --` 抹掉），随后把那两处测试改动先提交再跑。⑥ 新写用例六次编译期自误（`Result<Int>` 与 `Int` 比较、`assertTrue` 参数顺序、`suspend` 标记残留、`padEnd` 传 String、缺 `JsonArray`/`booleanOrNull`/`ToolResult` import），均由编译器当场抓到。
- 未证实项（合并前应跑）：① 设备/仪器面未跑。`adb devices` 空、空闲物理内存 3.5 GB、C: 剩 11 GB（探测原文 `build/review-evidence/r13-device-channel-probe.txt`，含 `gradlew :demo-app:tasks --all` 证明 `connectedDebugAndroidTest` 在任务图内），按既有教训不在本机启动模拟器。复跑：`:demo-app:installDebug` → `adb shell appops set com.ugk.pi.agent SYSTEM_ALERT_WINDOW allow` → `./gradlew :demo-app:connectedDebugAndroidTest`（核对主包 `lastUpdateTime`，§37 教训）+ `:terminal-probe-demo-a:connectedDebugAndroidTest` + `:terminal-probe-demo-b:connectedDebugAndroidTest`。F1/F2 的端到端可见性与 F5/F8 的真机链路只能靠它。② `{"error":"字符串"}` 是否由某个在架生产网关实际发出：本轮只证明「一旦发出即被读成空答案或错因」，无端点样本。③ 文档里 `am start -n com.ugk.pi.android.testapp/.MainActivity` 的实际失败输出（无设备）；依据是 `applicationId = "com.ugk.pi.agent"` 与 `6f88115`（2026-09-02 起）。④ `scripts/terminal-runtime/verify-runtime.ps1 -CheckPackages` 未跑（未触碰原生载荷）。
- 文档订正（本轮就地，均不改写已提交历史）：`docs/terminal-runtime-troubleshooting.md` §17 的复跑组件形式改为 `com.ugk.pi.agent/com.ugk.pi.android.testapp.MainActivity`（观测结论原样保留）；`docs/sdk-optimization-ledger.md` 的两处「当前」加时点限定（`ugk-terminal-runtime-android` 早已不是 `NO-SOURCE`，demo 当前为 `1.13.0/126`）；`HANDOVER.md` 文首横幅收窄为「§1–§4 与 §6 属历史快照，§5 的命令清单仍是现时指导」（§5 步骤 1 于 2026-10-02 更新过，与横幅的「整篇 2026-09-01 快照」互相矛盾）；AGENTS.md 的门禁数字改为交付态并指向本节。§3 的 `BashCommandToolTest 26/26` 与 §11 的 `am start` 属带日期的历史验证快照（文件顶部已声明第 6—14 节的「当前」仅指当时），**不订正**，只在此登记：该测试类当前 JVM 面实测 27 项。§39 F14 那句「没有用例打到 `activeOnly` 这个落点」是 2026-10-03 时点的实测陈述，本轮起该落点已有用例（`AgentTaskListActiveOnlyArgumentTest`），同样不改写 §39，只在此登记。台账侧的过期项与自查命令见台账 `REVIEW-2026-10-04-round13-error-shapes-and-truncated-answers.md`。
- 本轮新增教训（每条挂一个动作）：
  1. **把「presence 即失败」当普适规则，会在同一轮里制造新的硬失败。** F1 的第一版把空哨兵值判成错误，比它要修的空答案更坏。动作：任何「X 出现即失败」的守卫，先按调用上下文枚举「X 出现但旁边有可用答案」的落点，文档路径与事件路径分开立法并各写形状表。
  2. **有界回显要逐臂检查，别只给「我们读不懂」那一臂。** 端点自己写的 message 恰恰是最长、最不可信的那一段。动作：新增拒绝文案时，把「这段文本谁提供、会进哪里」写进注释并加一条长度断言（本轮四条）。
  3. **折叠会让单个站点的回退不再可判红。** 这不是缺口，但必须显式登记，否则下一轮会把它当新发现。动作：矩阵里为「不单独判红」的站点保留空期望行并注明由谁兜住（`d8`/`d9`）。
  4. **改判上一轮的「不修」要先看前提还在不在。** F4 的理由在 PR #13 合并那一刻失效。动作：每轮开工把上一轮「已核查不修」表逐条问一遍前提，并把复测命令写回。
  5. **仓库属性探测必须在 checkout 之后**（`.gitattributes`、行尾普查本轮又踩一次）。动作：开工序列固定 fetch → checkout baseline → 属性普查。
  6. **MSYS 的 `git` 不能被子进程执行。** 取证脚本用绝对 `D:\Git\cmd\git.exe`；且在跑会 `git checkout --` 复原的脚本之前必须先提交（本轮两处测试改动当时未提交，若脚本没在第一步就崩，就会被自己的还原逻辑抹掉）。动作：脚本 `assert_clean` + 派发/变异前先 commit（这条第七次记，仍在发生）。


## 42. 第十五轮 P0 审查（确认门禁烧掉答案；不可读的整集合快照被当成「没有数据」，基线 `main@de7102f`，2026-10-07）

宿主实测：Windows 10.0.26200.9457、Git Bash、Node v24.15.0、Python 3.14.2、git 2.50.0；
JDK 17.0.11 在 `E:/Android/Android Studio/jbr`（`java` 不在 PATH）、`GRADLE_USER_HOME=E:/DevCaches/gradle`、
SDK `E:/Android/SDK`（NDK 28.2.13676358 在位）。磁盘 `C: 200G/183G/17G 92%`、`E: 269G` 可用；
会话开始空闲物理内存 9037 MB / 32 GB。`adb devices -l` 为空（adb server 在 127.0.0.1:5037 监听），
无 qemu 进程；按既有纪律不启动新 AVD，设备面整轮未跑。
正式工作副本 `E:/AII/ugk-android-new` 与 `E:/AII/ugk-cockpit` 服务（pid 30164，
`--data-directory E:\AII\ugk-cockpit\.data\service`）、`E:\AII_Gemini\ugk-medtrum` 的 cdp-proxy（pid 16624）
全程未进入、未 kill。

### 门禁口径（每个数字都指向一个日志文件）

- 基线独占全量（`de7102f`，`--rerun-tasks`）：`1034 tests / 3 skipped / 0 failure`，154 份 XML，
  时间戳全部在 `2026-10-07T03:03:48+0800 … 03:05:51+0800` 窗口内，`EXIT=0`、`AGG_EXIT=0`、
  `STALE_TIMESTAMP_SUITES=0`。原文 `build/review-evidence/r15-baseline-fullgate.log`。
  这条实测**证实**了 `AGENTS.md` 现写的 `1034`；第十四轮的 `1077` 只属于未合并分支（PR #18 OPEN）。
- 交付态独占全量（代码状态 `0e89d17`，同命令同窗口校验）：`1054 tests / 3 skipped / 0 failure`，
  157 份 XML，`EXIT=0`、`AGG_EXIT=0`、`STALE_TIMESTAMP_SUITES=0`。
  原文 `build/review-evidence/r15-delivery-fullgate.log`。
  逐模块（基线 → 交付）：ugk-pi 266→273（+7）、file 17→17、schedule 34→34、attention 18→18、
  task-runtime 49→49、system-skill 63→63、agent-skill-runtime 101→101、terminal-runtime 60→60、
  terminal-skill 52→52、demo-app 374→387（+13）。合计 +20 = 本轮新增用例数（核心 7 + demo 13）。
  **差值声明**：该数字取自 `0e89d17`；本节（`docs/`）与台账追加是其后仅有的文档改动，不含 `src/`。
- 冲突面：本轮 10 个改动文件与 OPEN 的 PR #18 的 22 个文件**交集为 0**，
  `git merge-tree --write-tree 38eeee1 HEAD` 退出 0、无冲突标记
  （`build/review-evidence/r15-pr18-overlap.txt`）。

### 发现与修复

**F1 P1** `UserConfirmationDialogTool.execute` — 弹窗先展示、票据后计算。所有与用户回答无关的失败点
（`require(sessionId.isNotBlank())`——`AgentSession` 不校验 id、空串合法；`require(isValidNonce)`；
`sha256(target.input)` 拒绝 `{"count":1e100000}` 这种合法 JSON；`Math.addExact` 时钟溢出）都在弹窗之后。
`isError` 的确认结果被 `UserConfirmationRequiredTool.immediateDialogResult` 读成「没有确认」，
受保护 Tool 因此回答「先调用 show_user_confirmation_dialog 再重试」，已经答过一次的用户被再弹一次窗。
修复：`requireIssuable` 收纳 `issue()` 全部的失败点并由 `issue()` 调用（两侧不会分叉），
确认 Tool 在调用 presenter 之前跑它、并只抽一次 nonce；时钟仍在答复时读（TTL 从决定起算）。
红证 `build/review-evidence/r15-fa-baseline-red2.log`（4 红 / 2 对照绿）。

**F2 P0** `DemoConversationStore.decodeStoredConversations` — 整个数组解析失败返回 `emptyList()`，
而该文件注释写着「One bad element must not discard otherwise valid conversations」，那句只在数组
本身解析成功时成立。`readAll()` 缓存空结果，随后任一普通动作（首启 `ensureActive()`、`create()`）
把整份用户对话覆盖成一条。修复：`loadStoredSnapshot` 把「没有存过 / 是空的 / 读不出来 / 读出来但一条可用都没有」
分开；`salvageStoredConversationArray` 在真解析器裁决下按完整记录回退（最多 16 次前缀，不用正则猜文本），
完整记录回到应用而不只是回到文件；`DemoUnreadableSnapshotArchive` 在任何写入之前按内容哈希把原始字节
存进 `filesDir/snapshot-recovery/`（temp+rename、每源限 3 份、写不成则 `Log.w` 报量不静默）。
该文件自身的 KDoc 早已承诺「treats malformed preferences as recoverable data」——本轮是让代码回到它声称的样子。

**F3 P1** `ApiProviderSettingsJson` — 同一形状的读-改-写：坏值 → `empty()` → 一次「保存」抹掉其余
provider 与 API key，回执还说「已保存并启用」。同一规则落地；结构修复**故意不做**（容器是对象，
撕裂尾部无法在不猜嵌套的前提下闭合），登记为剩余缺口。
兄弟落点普查（第三个整集合 prefs 存储）：`AndroidAgentTaskRuntime` 的 `KEY_TASKS` 早已有
`writeBackup(raw)` 在 `writeRaw` 之前落盘，规则一致，仅备份槽单份可被下次损坏覆盖（登记不修）。

**F4 P1** `UserConfirmationRequiredTool` 的拒绝集合与 demo `ConfirmationVisualPolicy` 的取消按钮词汇
是两份手抄清单：`not_now/close/abort/dismiss/later` 被画成 Cancel 按钮却不在 SDK 的 `declinedButtonIds` 里，
用户点「暂不」得到的是「请先调用确认 Tool 再重试」→ 同一个弹窗回到他们刚做完的决定之上。
修复：`USER_CONFIRMATION_ACCEPTED_BUTTON_IDS` / `USER_CONFIRMATION_DECLINED_BUTTON_IDS` 成为唯一公开定义，
demo 的分类派生自它。没有放宽授权面：新增 id 只进拒绝集合，且 `approve` 那条「未知按钮不得说成用户已拒绝」
的既有用例仍绿（双向都测，§10）。红证 `build/review-evidence/r15-fb1-red3.log`，
失败落在循环里第一个非规范 id 上而同一条用例的 `cancel` 通过，一次运行同时证明假件与可达性。

**F5–F8 本轮修复自己引入的缺陷**（由「专审整改」那一轮在当前树复跑后成立，修见 `2f2128b`、`0e89d17`）：
- F5 `salvageStoredConversationArray` 的边界搜索用 `lastIndexOf('}', end - 1)`，而 Java/Kotlin 的
  `fromIndex` **含**该位；前缀末位正好是 `}` 时返回同一个位置、`end` 不变，16 次预算全耗在同一个候选上后返回 null。
  「写入停在闭合括号之后」恰是最可能的撕裂偏移。改 `end - 2` 保证边界严格递减。
  改前在工程 JDK 上实证：`lastIndexOf(e2-1)=51 e2=52`（不前进）对比 `lastIndexOf(e2-2)=26`（回到上一条记录）。
- F6 「不可读」被定义成「解析不过」，集合错了：`[{"id":""}]`、`{"configs":[{无 id 记录}]}` 解析通过、
  一条可用都没有，仍会被下次保存抹掉。改为两条落点共用一条规则。
- F7 provider 存储 `load()` 两次解析（`activeConfig()` 有 9 个生产调用点，每个都新建 store）。合一次。
- F8 对话归档跑在 `synchronized(cacheLock)` 里，而该文件自己的注释说这把锁不该等磁盘。改为锁内决定、锁外写。

### 变异矩阵（每条整改都要「退回旧实现即判红」）

`build/review-evidence/r15-mutation-matrix-final3.txt`：11 行全部 CONTROL 先绿、注入确实落地（回读断言）、
指定那一条用例变红、`git checkout --` 复原后 `git status --porcelain` 为空。
未落地的一次性自检：m5–m10 第一轮整批被控制闸挡下，原因是新加的 provider 用例少了一个 `assertTrue` 导入
让 demo-app 测试编译失败——**闸门挡住了一次会被读成「注入没抓住」的假结果**，而不是放它过去。

### 已核查但不修（附证据）

1. `ActivityUserConfirmationDialogPresenter` 的 `?: CANCEL_BUTTON_ID` 造一个请求里没有的按钮 id，
   是 F1 那条「宿主返回未提供 id」在本仓的真实生产者。本轮不修生产者：SDK 侧已把危害压掉
   （伪造 id 变成绑定票据的「没有用户决定」，不再触发第二次弹窗）；要修得给
   `UserConfirmationDialogResult` 加一等「用户主动放弃」语义，属公开接口扩面 + 宿主迁移。
2. `HeadlessConfirmationDialogPresenter` 与 `AgentAuthorizationPolicy` 各自仍留一份按钮词汇表副本
   （前者 `close` 在、后者 `not_now` 不在）。改它们会移动 `withoutUserDecision` 的语义边界
   （无 UI 的后台回合会不会被说成「用户拒绝了」），与 F4 的修复方向冲突，登记给下一轮连同用例一起判。
3. `AndroidAgentTaskRuntime` 的 `KEY_TASKS_BACKUP` 单槽、可被下次损坏覆盖——同族规则已满足（备份先于覆盖写），
   且没有任何 main 读回它，KDoc 明写「recoverable by hand」，是诚实陈述不是假门禁。
4. `DemoAgentTraceStore.compact()` 两阈值不自洽：`MAX_BYTES = 512*1024`、`MAX_RETAINED_LINES = 1_500`
   → 保留行平均超过 349.5 B 时压缩不可能把文件降回阈值以下，此后每次 append 整文件重读重写。
   算术即证（`git grep -nE "MAX_BYTES|MAX_RETAINED_LINES" -- demo-app/src/main`）。
   该文件在 OPEN PR #18 的 22 文件清单内，本轮避让。
5. 一次性 Waiting 任务到点时 `DemoUrgentInteractionDispatcher.timerAllowsInteraction` 返回 false，
   用户对重要提醒的真实点击被丢弃。第十一轮已把该族拆成 `DemoUrgentInteractionLedger` 并钉住释放规则，
   本轮复跑其用例仍绿，判为已核查的既定产品门禁而非缺陷。
6. `UserConfirmationDialogTool(override val name = …)` 允许宿主改名，而受保护 Tool 按常量比对名字，
   全仓无任何地方检测这种错配；`AGENTS.md`/契约文档要求「引用常量」，实测 main 源码里仍有 5 处字面量
   `"show_user_confirmation_dialog"`（`DemoRunState.kt` 3 处、`AppPrivateFileTools.kt`、
   `AndroidAutomationAgentPlugin.kt`、`BashCommandTool.kt` 2 处）。改这些字面量要动的文件里
   `DemoRunState.kt`、`BashCommandTool.kt` 属 PR #18 清单，本轮不改；后果是 fail-closed（一切都拒绝），
   不是泄权。

### 被否掉的复核立案（附复测命令）

1. 「教学可在定时任务 Waiting 时启动，于是 `DemoDelayedMessageDispatcher.dispatch()` 的
   `check(!isScreenOperationOwned())` 会把周期任务永久打死」——**否**。
   `DemoTeachingHost` 起手里就有 `check(process.delayedTasks.snapshot() is DemoDelayedTaskState.Idle)`；
   录制、工作流、教学、`MainActivity.runAgent` 四条启动路径都卡非 Idle 定时任务。
   复测：`git grep -n "delayedTasks.snapshot()" -- demo-app/src/main`。
2. 「`DemoDelayedTaskController` 的 init 在会话被删时清不掉标记，于是每次启动重复播报中断」——**否**。
   `appendMessagesAndFlush` 对未知 id 返回 null 而非抛异常，`runCatching{}.onSuccess{ clearMarker() }` 成立。
3. 「`UserConfirmationRequiredTool` / `show_user_confirmation_dialog` 在 main 源码里不存在」——**否，且方法是错的**：
   `git grep … -- '*/src/main'` 这条 pathspec 连已知存在的 `class AgentRuntime` 都是零命中。
   正确姿势：不带 pathspec 再 `| grep "/src/main/"`。见「过程失败登记」第 1 条。
4. 整改审那一轮说「对话归档那条用例的前提不成立、fixture 其实可救」——**否**。
   按文件里真实字节重放循环：`{` 位置是相邻的 55/56（`messages` 数组根本没闭合），任何切点都不成数组，
   归类为不可读是对的、用例为对的原因绿。但 fixture 不真实，已另加 F6 那两条真臂（`m10` 反证了旧的那条
   钉的是另一条臂）。
5. 同一轮说「票据 `issue()` 在弹窗之后仍可能抛（注入时钟在两次读取之间变负或饱和）」——**事实成立，本轮不改**。
   需要注入时钟或设备时钟落到 `Long.MAX_VALUE` 前两分钟之内；替代方案（把时间戳钉在提问时刻）会让
   读两分钟才答的用户拿到过期票据并被引导再弹一次窗，正是本轮要消除的形状。改为把残余如实写进 KDoc。
6. 同一轮说「归档目录可能无界增长」——**否**，`pruneOldest` 每前缀限 3 份（约 6 个文件）。

### 未证实项（合并前应跑）

1. 设备/仪器面整轮未跑：`adb devices -l` 空、C: 剩 17 GB。复跑：`:demo-app:installDebug` →
   `adb shell appops set com.ugk.pi.agent SYSTEM_ALERT_WINDOW allow` →
   `./gradlew :demo-app:connectedDebugAndroidTest`（26 文件 / 76 个 `@Test`）+
   `:terminal-probe-demo-a:connectedDebugAndroidTest` + `:terminal-probe-demo-b:connectedDebugAndroidTest`。
2. `AgentFloatingWindow.clear()` 与 `hideConfirmation()` 都是 `confirmationResult = null` 而不调用它
   （`selectConfirmation` 才调用），调用点在 `MainActivity` 三处。若悬浮窗确认正挂在
   `suspendCancellableCoroutine` 上，这条路径是否让宿主回合永久等待**本轮无法在宿主判红**（需要真窗口与 Looper）。
   合并前应跑悬浮/确认相关仪器用例，或补一条「clear 期间未决确认必须被以 `withoutUserDecision` 结算」的断言。
3. 「torn SharedPreferences 写」在真机上是否就是 F2/F3 的成因：本轮证明的是「值一旦不可读，下一次保存必然覆盖」
   这条链条本身，不主张 Android 层的具体撕裂形态。
4. `scripts/terminal-runtime/verify-runtime.ps1 -CheckPackages` 未跑（本轮未触碰原生载荷）。

### 门禁自身的状态（本轮实测）

- 本仓无 `.github/workflows`（`ls -a .github` → No such file or directory），唯一自动门禁是 `AGENTS.md`
  的十个 `testDebugUnitTest`；本轮基线与交付两次运行里十个任务全部产出用例，无 `NO-SOURCE` 假绿项。
- 文档门禁清单**不含** `:demo-app:connectedDebugAndroidTest`，而 JVM 门禁也不编译 `src/androidTest`。
  本轮实测该源集目前仍可编译（`:demo-app:compileDebugAndroidTestKotlin` `BUILD SUCCESSFUL`、`EXIT=0`，
  `build/review-evidence/r15-androidtest-compile.log`）——这是「现在还没坏」，不是「有人在把关」。
  本轮不改 `AGENTS.md`（在 PR #18 清单内），把这一行交给下一次台账收口。
- 受保护工具名字表活性仍由第十三轮 §40 F2 的用例把住，本轮独占复跑绿：
  `build/review-evidence/r15-name-liveness-rerun.log`（file 17 / agent-skill-runtime 101，0 failure）。

### 过程失败登记

1. **pathspec 假零**：`git grep -n "<符号>" -- '*/src/main'` 对确实存在的符号返回空（连
   `class AgentRuntime` 都零命中），一度让我把「确认门禁类不存在」当成发现。此后：先跑一条
   已知存在符号的对照探针，命中数不符就判定是工具用法而不是事实。
2. **取证脚本自己的 CRLF 让整轮矩阵作废一次**：Python 在 Windows 上 `print` 走文本模式，
   `ids` 清单每行尾带 `\r`，`--tests` 过滤器因此匹配 0 个用例，7 行全部「CONTROL 不绿」。
   矩阵的控制闸把它们全判废而不是给出假绿——修法是喂给 `tr -d '\r'`。
3. **`bash -c` 里套 Python 字面量再次静默吃掉转义**：补丁写成 `tr -d "<CR 实字符>"`（功能上凑巧正确但不可读），
   改用行号/字面量脚本 + `cat -A` 回读才定形。同一条教训第八次复发。
4. **一次「红了但没归因」的取数被自己否掉**：`anUnreadableConversationSnapshot…` 首轮红在
   `JsonDecodingException`（我的断言先解析了错误文本），是假件问题不是缺陷；调整断言顺序后重跑，
   红落在 `expected:<0> but was:<1>` 的缺陷语义上（`r15-fa-baseline-red.log` 保留为判废记录）。
5. **旧矩阵行的锚点在改名/改形之后过期**：m2/m5/m7 三行 `ANCHOR_NOT_UNIQUE`，说明取证脚本的期望集合
   就是过期断言；按当前字节重钉后全部落地（第十次记，仍在发生）。

### 本轮新增教训（以及该固化成什么动作）

1. **「解析得过」不等于「读得出」**。凡是「读侧容错 + 写侧整体覆盖」的组合，判据必须是
   *产出了多少可用数据*，而不是*文本是否合法*。动作：给这类落点固定两条控制用例——
   显式空容器不得触发保留、合法文本里装不可用记录必须触发保留；少一条就是假覆盖。
2. **边界递减必须由被搜索位排除上一次命中来保证**。`lastIndexOf(ch, i)` 含 `i`，所以「从 `end-1` 往前找」
   在后缀本身就是目标字符时不前进。动作：凡循环里写「找上一个 X 然后收缩边界」，把
   「从 `end-2` 起找」和一条「尾部正好停在 X 上」的用例一起写，并优先在宿主 JDK 上跑一次位置断言。
3. **矩阵的 CONTROL 闸是唯一能区分「注入没被抓住」与「我的取数坏了」的东西**：本轮两次整批作废
   （CRLF、缺导入）都被它挡住。动作：任何行不绿就丢弃该行的结论，且必须去读控制日志本身，
   不许把丢弃记成「这条回退不判红」。
