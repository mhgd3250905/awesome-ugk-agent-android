# 【历史归档】Headless Terminal Runtime：进程组超时清理验证记录

> 本文件是历史过程记录，不是当前事实源。当前控制、取消和生命周期边界以 `../../terminal-runtime-architecture.md`、`../../terminal-runtime-validation.md` 和 `../../terminal-runtime-release-checklist.md` 为准。

日期：2026-08-13  
状态：同一 POSIX session 内的普通 Bash 子进程树超时清理、Tool 协程取消和单 Tool 并发
控制已完成 x86_64 API 24/29/35（4 KB）与 API 36（16 KB）回归；它不是跨 UID 安全隔离，
也不是完整的 Android Service Supervisor。

## 问题

原先 Java `Process.destroy()` 只能向直属 Bash 进程发出终止请求。Bash 脚本若启动后台任务，
该任务可能在 Bash 退出后继续运行；这对 Agent 的时间上限、资源控制和后续任务都不可接受。

## 实现

- `libugk_session_launcher.so` 是一个随 APK 安装、从 `nativeLibraryDir` 执行的第一方 PIE
  launcher。它调用 `setsid()`，将随后的 Bash 及普通子进程放进独立 POSIX session/process
  group，再 `execv()` 目标 ELF。
- launcher 在 `exec` 前通过一次性 app-private report file 报告 session leader PID，随后
  删除该环境变量，避免脚本获得该报告路径。
- `libugk_terminal_native.so` 是一个极小 JNI 控制库，调用 `kill(-pgid, SIGTERM)`；若 500 ms
  内未结束，再调用 `SIGKILL`。负 PID 是 POSIX 的“整个进程组”语义。
- report file 只用于提供进程组 ID，不包含可执行代码；session launcher、Bash、curl 等 ELF
  始终来自 APK 的 `nativeLibraryDir`。

## x86_64 API 24/29/35/36 实测

`terminal-probe-demo-a` 新增测试脚本：

```bash
(sleep 2; printf leaked > descendant-marker.txt) & sleep 30
```

该 Tool 调用的超时为 500 ms。Runtime 返回 `timedOut=true` 后等待 2.5 秒，并断言 marker
文件不存在。另有一个忽略 TERM 的 descendant 用例，验证 SIGKILL 升级；若后台子进程遗留，
marker 会被写出。API 24 4 KB 与 API 36 16 KB 的 Demo A 均为 `OK (10 tests)`，因此普通
后台子进程和 TERM-resistant descendant 均已与 Bash 一起被清理；API 29/35 保留 Gate 2
的基础超时回归证据。

`com.example.runtime.demo.b` 也在同一 session launcher 机制下通过 API 24/36 的全部 Probe、
Bash、SQLite、HTTPS 和 Python 测试（各 `OK (5 tests)`），证明 launcher 不依赖固定宿主
`applicationId`。

## 真实边界

- 脚本可主动调用 `setsid`、启动另一 App/Service 或利用未来新增的特权工具来逃离当前
  process group；该机制不能保证清理这类主动逃逸进程。
- `BashCommandTool` 现在将阻塞的 Runtime 调用放入 `runInterruptible(Dispatchers.IO)`，
  协程取消会中断执行线程并触发同一 process group 的清理；单个 Tool 实例默认最多并发
  2 个调用，宿主可配置但不能超过 4 个。Plugin 会复用同一个 Tool 实例，避免通过重复
  获取工具绕过上限。
- 目前已有同一 `TerminalAgentPlugin` 实例内的显式 `cancel(callId)` / `cancelAll()`；仍没有
  跨进程或集中式 Supervisor 级 cancel-by-id API、Binder Service、跨进程恢复或 Android
  进程被系统杀死后的完整生命周期管理。
- 该机制解决的是“受控 Runtime 在同一 session 内启动的普通子进程树”，不是安全沙箱；所有
  进程仍使用宿主 App UID。

CPython 已在同一 session 机制下完成 x86_64 API 24/29/35（4 KB）与 API 36（16 KB）双
applicationId 验证，其中包含 `subprocess` 用例；这不改变上述边界。集中式 Supervisor、
跨进程/生命周期级取消、逃逸进程治理，以及 Core Gate 之后的 jq、OpenSSH、Git 扩展专项
回归，仍需后续完成；Node 不属于 v1，未来若恢复必须在独立可选 AAR/扩展模块中单独验证。
