# Terminal Runtime 验证矩阵

更新时间：2026-08-30
验证源码：`E:\AII\ugk-android-new`
注意：最新 Terminal instrumentation/probe 的物理设备证据绑定到 source checkpoint `28bc352622458d29e090656ae42fd32f057e9196`；第 21 节保留 0.7.1 历史版本保存，第 22 节记录当前 Demo `0.9.0 / versionCode 11` skill authoring 版本保存与最终验证。后者不关闭 Terminal 设备矩阵、网络或发布 Gate。

> 第 6—14、18—19 节按日期保留历史验证快照；这些章节中的“当前”仅指当时的源码、APK 或设备上下文。
> 当前 Gate 结论和版本保存证据以文首总表、第 20 节、第 21 节和第 22 节为准；第 21 节是历史版本保存，第 22 节是当前 0.9.0 阶段。

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

结果（2026-08-29）：全部任务成功；`ugk-terminal-runtime-android:testDebugUnitTest` 当前为 `NO-SOURCE`。当前 XML 合计 `271` 个测试，`0` failure、`0` error、`0` skipped；分模块数量见第 20 节。Demo JVM 测试另以 `:demo-app:testDebugUnitTest` 验证 `104/104`。

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
