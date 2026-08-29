# Terminal Runtime 当前基线

更新时间：2026-08-29
架构整改实现基线：`main@9268bc2789c561f121fc992031fcd29466d0705e`
源码状态：阶段 1—7 已保存为独立本地 checkpoint；阶段 8 的完整静态、打包和 JVM 验收见 `terminal-runtime-validation.md`。该状态仍不是正式发布版本。

## 1. 产品目标

为 Android Agent SDK 提供一个随宿主 App 一起安装的 Headless Terminal Runtime：

- 没有终端 UI；
- 不要求用户安装 Termux 或第二个 App；
- Agent 通过 `AgentTool` 使用受策略约束的非交互 Bash；
- Agent 通过专用 Tool 管理需要跨一次调用持续存在的本地 HTTP 服务；
- Runtime 内置可验证的 Bash、curl、OpenSSL、SQLite 和 CPython 3.14.6；
- 每个宿主 App 使用自己的 `nativeLibraryDir`、私有工作区和私有运行时数据。

这是“SDK 内置原生执行能力”，不是把 Termux App 打包进 SDK，也不是 Linux 容器或安全沙箱。

## 2. v1 Core Profile

| 能力 | 当前状态 | 约束 |
|---|---|---|
| Bash 5.3.15 | Core | 非交互、私有工作区、超时、输出上限 |
| curl 8.21.0 | Core | 只保留 `file`/`http`/`https`，使用 Runtime 管理 CA |
| OpenSSL 3.6.3 | Core | 静态 CLI；无 shared library、module、legacy provider、engine、DSO、SSL3 |
| SQLite 3.53.4 | Core | CLI；`SQLITE_OMIT_LOAD_EXTENSION`，不加载可写目录原生扩展 |
| CPython 3.14.6 | Core | `python`/`python3`；验证 `ssl`、`sqlite3`、`hashlib`、`subprocess`；不含 `pip`/`ensurepip` |
| Node.js | 明确不支持 | 不打包、不注册 Tool、不进入 v1 Gate；未来只能独立扩展评估 |
| Git/OpenSSH/jq | 明确不支持 | 不在 Core Gate 前实现，不允许运行时下载补齐 |

最低支持版本：`minSdk 24`。当前构建基线：compile/target SDK 36、AGP 8.11.1、Kotlin 2.2.21、Java 17、Gradle 8.13、NDK 28.2.13676358。

## 3. 当前 Gate 状态

### Gate 1：双 ABI 静态验收——已验证

两 ABI 的 ELF、依赖闭包、GNU_STACK、TEXTREL、PT_LOAD 16 KB 对齐、锁文件哈希、AAR/APK 内容、zipalign 和无 Node Core 映射均通过 `verify-runtime.ps1 -CheckPackages`。

### Gate 2：设备矩阵——x86_64 子集与 arm64/API34/4KB 本地子集已验证

已验证：

- API 24 / x86_64 / 4 KB；
- API 29 / x86_64 / 4 KB；
- API 35 / x86_64 / 4 KB；
- API 36 / x86_64 / 16 KB。

每个设备均用 Demo A/B 验证双 `applicationId`：基础 Profile A `7/7`、B `5/5`，零 fail/error/skipped，HTTPS 为 HTTP 200。

此外，真实 arm64-v8a Android 14/API34/4KB 已完成 Demo 与 Runtime 本地能力回归；两项外网 Probe 因设备当时无可用上游网络而失败，不能据此关闭网络 Gate。整体 Gate 2 仍未完成：arm64 16KB、API35/16KB、API36/4KB 及完整 Release 安装矩阵尚未覆盖。

### Gate 3：Runtime 控制与生命周期——x86_64 子集已验证

- API 24 / x86_64 / 4 KB：Demo A `10/10`、Demo B `5/5`；
- API 36 / x86_64 / 16 KB：Demo A `10/10`、Demo B `5/5`；
- `BashCommandToolTest`：`10/10`。

覆盖默认确认、重复 call id、运行中/排队取消、`cancelAll()`、并发槽位复用、stdout/stderr 独立截断、普通超时、TERM-resistant descendant、SIGKILL 升级、Python、execmem 和双包名重定位。

## 4. 当前可以说什么

- 可以说：x86_64 API 24/29/35 4 KB 与 API 36 16 KB 的 Core Profile 已通过双宿主回归。
- 可以说：同一 `TerminalAgentPlugin` 实例内的 Tool 取消、超时、并发和同一 process group 清理已通过 x86_64 Gate 3。
- 可以说：安装后不需要第二个 App，也不依赖 `com.termux` 或固定宿主 `applicationId`。
- 可以说：根目录开发 `AGENTS.md` 与 SDK runtime `AGENTS.md` 已分离；终端插件会把后者自动注入 Agent 的每次模型请求，明确当前环境中的命令替换规则。
- 可以说：runtime contract 的 JVM 注入、AAR/APK asset 打包和 Probe A/B 设备回归已通过；`demo-app` 的同包名旧签名阻塞只影响本次重装验证，不影响已构建产物证据。
- 可以说：`demo-app` 已接入 `AndroidAutomationAgentPlugin`；API35 x86_64/4 KB 仪器测试覆盖了 App 名称查询、无需无障碍的包名启动、无障碍状态/设置引导，以及确认后的 `ACTION_VIEW` Intent 和取消不派发。
- 可以说：终端 skill 已把 `python`/`python3` 的 Bash 函数边界写入 SDK runtime `AGENTS.md`，并提供 `local_http_server_start/status/stop`；本地服务使用托管 CPython、127.0.0.1 绑定和可停止的独立 process group，不要求 Agent 手写 `nohup`。

## 5. 当前不能说什么

- 不能笼统说 arm64 已全部通过：当前只有 API34/4KB 真机本地能力证据，arm64 外网 Probe 和 16KB 运行证据仍未关闭。
- 不能说“所有 Android 版本和所有 page size 都支持”。
- 不能说 Runtime 是安全沙箱：命令与宿主共享 Android UID。
- 不能说已经是最终发布版：Release AAB 完整矩阵、升级迁移、低磁盘、进程被杀、性能、SBOM/许可证和接入消费测试尚未关闭。
- 不能说真实 LLM 已完成所有终端组件的 Agent 端到端验证；目前一次付费调用因 Agent 错误调用 `bash` 子进程而以 exit code 1 结束，OpenSSL 完整操作仍以 Probe 证据为主。
- 不能说终端可以代表 Android Shell 启动应用；Android App-facing 动作必须使用原生 Intent Tool，不能用 `am`/`pm` 失败推断浏览器或其他应用不存在。`Context.startActivity()` 是生产 Intent Tool 的事实结果；`resolveActivity()` 只保留给显式注入的确定性测试解析器，不能把 Android 11+ package visibility 下的空预查询当成设备事实。
- 不能说 Node.js、Git、OpenSSH、jq 可用。

## 6. 源码模块

| 模块 | 职责 |
|---|---|
| `:ugk-pi-android` | Agent Runtime、AgentTool、LLM Provider、Skill |
| `:ugk-terminal-runtime-android` | 原生载荷、BashRuntime、Python/CA 数据、session/process group |
| `:pi-terminal-skill-android` | `terminal_bash_execute`、本地 HTTP 服务 Tool、确认、策略、取消和结果映射 |
| `:terminal-probe-demo-a` | `com.ugk.runtime.demo.a` |
| `:terminal-probe-demo-b` | `com.example.runtime.demo.b` |
| `:demo-app` | 原有无障碍屏幕操控示例，不等于 Terminal Runtime 验证 App |
