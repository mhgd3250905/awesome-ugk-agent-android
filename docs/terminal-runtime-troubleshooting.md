# Terminal Runtime 踩坑与修复

## 1. Node.js 范围膨胀

**现象**：Node.js 会扩大 V8、ABI、addon、体积、构建和安全维护面。

**处理**：从 v1 移除；脚本和 POC 仅保留为未来独立扩展资料。

**规则**：不得在 Core AAR/APK、runtime-lock、Tool 文案或发布 Gate 中间接带入 Node。

**当前易混淆点**：`runtime-lock.json` 的 `nativePayloadNormalization.tool.dockerImage` 当前是锁定的
`node@sha256:...` 构建镜像，仅用于在容器内运行 `patchelf`；它不进入 AAR/APK，也不提供 Android
运行时的 Node 命令。该构建依赖与 v1 Runtime 能力无关，但名称违反“无 Node 依赖”的直觉，发布前应
替换为只含必要工具的非 Node 基础镜像，并重新验证工具链和 lock。

## 2. CPython 的 `/usr/local/lib` RUNPATH

**现象**：两 ABI 的 `libsqlite3_python.so` 带 `RUNPATH [/usr/local/lib]`。

**根因**：上游 `sqlite3.pc` 使用 `prefix=/usr/local`。

**修复**：`normalize-python-sqlite-rpath.ps1` 用锁定 patchelf 在精确前置条件下 `--remove-rpath`，再验证 DT_NEEDED、ABI、PT_LOAD 和哈希。

## 3. API24 的 `Process.isAlive()` 崩溃

**现象**：主动取消出现 `NoSuchMethodError: Process.isAlive()Z`。

**根因**：该方法不在 minSdk24 API 面上。

**修复**：使用 `exitValue()`；正常返回表示已退出，`IllegalThreadStateException` 表示仍存活。

**结果**：API24 Gate3 通过。

## 4. API24 Python/execmem 退出码 139

**现象**：stderr 出现 `ANDROID_DATA not set`、`ANDROID_ROOT not set`，Python/execmem segfault。

**根因**：清空 `ProcessBuilder` 环境后旧版 Bionic 找不到时区根路径。

**修复**：Runtime 托管注入 `ANDROID_DATA=/data`、`ANDROID_ROOT=/system`，并禁止 Tool 覆写。

## 5. Bash API24 `fpurge` 无限自旋

**现象**：首条输出后占满 CPU，命令不结束。

**根因**：Autoconf 错误识别 Bionic 的 `fpurge`/`__fpurge`。

**修复**：构建时固定：

```text
ac_cv_func_fpurge=yes
ac_cv_func___fpurge=no
ac_cv_have_decl_fpurge=yes
```

## 6. 未压缩 `.so` 不能直接执行

**现象**：系统可以加载未压缩库，但 `nativeLibraryDir` 没有可供 `execve()` 的真实文件。

**修复**：保留 `jniLibs.useLegacyPackaging = true`，不要把 `dlopen` 成功当作命令执行成功。

## 7. 直属 Bash 结束但后台 descendant 存活

**现象**：Java `Process.destroy()` 只杀 Bash，后台进程继续写 marker。

**修复**：launcher `setsid()`，Runtime 按 pgid SIGTERM，500ms 后必要时 SIGKILL。

**边界**：主动 `setsid`、独立 Android Service、跨进程逃逸不在当前机制保证内。

## 8. AVD/大文件位置

大型 system image、AVD data、Python package、Docker/build cache 放 E 盘：`E:\Android\SDK`、`E:\Android\.android\avd` 或 `E:\AII\vendor`。只删除明确由本轮创建的精确临时 AVD；不要删除既有 AVD 或 `emulator.backup`。

## 9. API24 没有 `getconf`

用 `/proc/self/smaps` 的 `KernelPageSize` 交叉确认；API24 4KB，API36 16KB 环境必须实际读到 16384。

## 10. 静态验收缺少 NDK 参数

如果出现 `Set ANDROID_NDK_ROOT or pass -NdkRoot`，显式传入：

```powershell
.\scripts\terminal-runtime\verify-runtime.ps1 `
  -CheckPackages `
  -NdkRoot 'E:\Android\SDK\ndk\28.2.13676358'
```

## 11. 旧 APK 签名冲突

仪器测试前可能存在同包名旧签名 Probe 包。只清理明确属于本轮 Probe 的 target/test package，再重跑；不要清理用户 App。安装编排失败不能直接归因于 Runtime。若是用户正在使用的 `demo-app`，先不要为测试擅自卸载，以免丢失 API 配置；可改用不冲突的 Probe 或等待用户明确授权重装。

## 12. Agent 重复调用 `bash` 导致命令失败

**现象**：真实 Agent 已经进入 `terminal_bash_execute`，但脚本中再次调用 `bash`，在 SDK 托管的最小 `PATH` 中得到 `command not found`；若脚本启用了 `set -e`，后续组件检查会提前结束。

**根因**：`terminal_bash_execute` 的 `script` 参数本身已经由 SDK 启动的 Bash 执行，不是 Android shell 命令包装器；模型没有获得足够明确的运行时环境契约。

**修复**：SDK 打包独立的 runtime `AGENTS.md`，由 `TerminalAgentPlugin` 注册时自动注入每次模型请求，并明确禁止 `bash`、`bash -c`、`sh`、`sh -c` 子进程调用；同时 terminal skill 文案保留该关键规则作为局部提醒。需要运行脚本时直接传 Bash source，需要 Python/SQLite 时分别使用 `python`/`python3` 和 `sqlite3`。

**验证边界**：该修复可用 JVM 单元测试验证注入顺序，并检查最终 APK 含 `assets/ugk/AGENTS.md`；真实 LLM 的新一轮付费请求不应为了重复证明而自动重跑。历史一次真实 Agent 调用已记录为“工具链通、脚本因调用不存在的 Bash 子进程退出 1”，不能宣称四组件 Agent 端到端全通过。

## 13. Debug APK 签名不一致导致覆盖安装失败

**现象**：`demo-app:connectedDebugAndroidTest` 在安装阶段报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，提示已有包的签名与新 APK 不同。

**根因**：不同开发环境使用了不同默认 `debug.keystore`；Android 把同一 `applicationId` 的不同证书视为不可覆盖更新的安装包。

**修复**：`demo-app/build.gradle.kts` 从被忽略的 `local.properties` 读取固定 Debug keystore 路径；当前本机固定为 `E:\Android\.android\debug.keystore`。这不是每次更新签名，而是让每次构建继续使用同一签名。

**配套**：API 配置从外部文件读取，只在 Debug 首次启动时写入 SharedPreferences；普通覆盖安装保留设置。不要为了测试擅自卸载带有付费 API 配置的 App；若确需换签名，先备份/重新初始化配置。

## 14. Agent 说“设备没有浏览器”但终端无法打开网页

**现象**：Agent 通过 `terminal_bash_execute` 执行 `am start` 或用 `pm` 查询失败后，声称 Android 设备没有浏览器。

**根因**：Headless Terminal 运行在宿主 App 的普通 Android UID 中，不是 `shell` UID；终端里的 `am`/`pm` 不能代表 Android Shell，也不能作为设备应用事实来源。

**修复**：使用 `launch_android_app_intent` 的 `open_url` 目标，由 SDK 原生调用 `Intent.ACTION_VIEW`，以
`Context.startActivity()` 的真实结果为准。有处理器时通常返回 `launched=true`；`ActivityNotFoundException` 返回
`no_handler`；其他派发异常返回 `launch_failed`。不要在生产路径先用 `PackageManager.resolveActivity()` 拦截：
Android 11+ package visibility 可能让预查询返回空。

**验证**：API35 x86_64/4 KB 的 `demo-app:connectedDebugAndroidTest` 共 `12/12` 通过，覆盖确认、取消、原生
`ACTION_VIEW`/确定性无处理器分支，以及 Android 自动化基础 Tool。该测试不调用真实 LLM/API。

## 15. Agent 不知道如何打开 App 或操作其他 App

**处理**：完整 Android 宿主注册 `AndroidAutomationAgentPlugin`。先用 `find_android_app` 查询用户口语中的 App 名称，
再用 `launch_android_app` 通过包名启动；不要猜包名，也不要通过终端 `am`/`pm` 启动。需要读屏、点击或输入时先用
`get_android_accessibility_status`，未就绪则打开 `open_android_accessibility_settings`，由用户手动授权并等待服务连接，
然后再调用宿主的 `screen_*` Tool。

**边界**：`launch_android_app` 不需要无障碍权限；它只能启动 launcher Activity。无障碍状态 Tool 只能报告用户设置和宿主
服务连接，不能代替系统授权；`<queries>` 只影响可查询的 App 范围。

## 16. `nohup python3` 无法启动本地服务

**现象**：`python3 script.py` 可以运行，但 `nohup python3 script.py` 返回
`nohup: exec python3: No such file or directory`；手写 `&` 可能暂时成功，但后续没有可靠的状态、停止和日志管理。

**根因**：Runtime 在每次 Bash 调用的 `BASH_ENV` 中把 `python`/`python3` 定义为调用
`$UGK_NATIVE_LIBRARY_DIR/libugk_python.so` 的 Bash 函数。当前 Shell 能解析函数，`nohup`、`env`、
`setsid`、`xargs` 等外部 exec 包装器只能寻找真实的 `python3` 文件，不能解析该函数。另一个根因是
`terminal_bash_execute` 的生命周期是一次 bounded call，不是 daemon supervisor。

**修复**：SDK runtime `AGENTS.md` 明确了直接调用规则；Agent 需要长驻本地 HTTP 服务时使用
`local_http_server_start`，用 `local_http_server_status` 做只读健康检查，用
`local_http_server_stop` 做清理。专用 Tool 直接使用已验证的 CPython launcher 和 Runtime
process-group 控制，服务只绑定 `127.0.0.1`。

**验证边界**：已添加 Tool 单元测试和 demo 真机/模拟器仪器测试；该能力仍与宿主共享 Android UID，
不提供公网/LAN 绑定，也不保证宿主进程被系统彻底杀死后的服务恢复。

## 16. demo-app 返回前台像“重启”

**根因**：如果启动器重新创建 `MainActivity`，仅放在 Activity 私有字段中的运行 Job、对话视图和权限提示状态会重新初始化；这不一定代表 Android 进程真的被杀死。

**修复**：当前 `MainActivity` 使用 `singleTask`/`alwaysRetainTaskState`，`DemoActivityState` 保留会话，`DemoAgentRunCoordinator` 保留 Job、运行代次和队列，确认 presenter/悬浮窗也在进程级复用；Activity saved state 只保留有界输入草稿；同一进程的权限提示只弹一次。通过启动器或 HOME 返回时应复用同一个 task/Activity，发生 Activity 重建时只重新绑定观察者。

**验证**：API35 x86_64/4 KB 模拟器上，HOME→`am start -W -n com.ugk.pi.android.testapp/.MainActivity` 后，ActivityRecord 和进程 PID 保持不变，输入草稿 `draft123` 保留；没有出现应用异常。若系统彻底杀死进程，当前只保证 API 配置恢复，完整运行中的 Agent Tool 任务不会后台续跑。

## 17. 无悬浮窗权限时打开外部 App 导致 demo-app 崩溃

**现象**：Agent 通过原生 `launch_android_app_intent` 打开 Chrome 后，Chrome 显示目标页面无法连接；此前 `127.0.0.1` 服务器已经返回过 HTTP 200。

**根因**：用户拒绝 `SYSTEM_ALERT_WINDOW` 后，`MainActivity.onPause()` 仍无条件调用 `AgentFloatingWindow.show()`，触发
`WindowManager.BadTokenException`（window type 2038），系统强制结束宿主进程；服务器子进程随宿主 UID 一起消失，导致浏览器后续看到 `ERR_CONNECTION_REFUSED`。

**修复**：`MainActivity.onPause()` 只有在 Agent 运行且 `Settings.canDrawOverlays()` 为真时才显示悬浮窗；`AgentFloatingWindow.show()` 和 `showExpanded()` 也各自检查权限，避免其他调用点绕过 Activity 防线。

**验证**：悬浮窗权限保持拒绝时，真实 Agent 请求 `Open the site https://example.com in the Chrome browser` 经确认后成功打开 Chrome 的 `Example Domain` 页面；App PID 保持存活，未出现 `FATAL EXCEPTION` 或 `BadTokenException`。本次 `demo-app:connectedDebugAndroidTest` 实际为 `12/12` 通过。
