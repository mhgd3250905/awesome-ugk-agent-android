# 【历史归档】Terminal Runtime Probe 实测记录

> 本文件是历史过程记录，不是当前事实源。当前测试矩阵和 Gate 状态以 `../../terminal-runtime-validation.md` 与 `../../terminal-runtime-baseline.md` 为准。

日期：2026-08-12；Core Release Gate 2/3 复核：2026-08-13
状态：x86_64 API 24/29/35 的 4 KB 与 API 36 的 16 KB 双 applicationId v1 Core
回归通过；Gate 3 的控制/生命周期用例已在 API 24 4 KB 与 API 36 16 KB 通过；arm64
和其余 page-size/API 组合仍未覆盖

## 已验证

构建环境：

- `minSdk 24`、`targetSdk 36`
- AGP 8.11.1、Kotlin 2.2.21、NDK r28.2
- x86_64 API 24/29/35（4 KB）与 API 36（16 KB）Android Emulator

Probe 是一个 PIE ELF 可执行文件，但按 Android 原生库命名为
`libugk_runtime_probe.so`。它具备 `/system/bin/linker64` 或对应 ABI linker
解释器，ELF `LOAD` segment 是 16 KB 对齐。

以下两份 App 均使用同一 Runtime 模块并成功完成仪器测试：

| Host applicationId | 结果 |
|---|---|
| `com.ugk.runtime.demo.a` | 通过 |
| `com.example.runtime.demo.b` | 通过 |

测试断言了：

1. Probe 位于该宿主自己的 `ApplicationInfo.nativeLibraryDir`。
2. `ProcessBuilder` 可启动 Probe。
3. Probe 返回退出码 `0`，并把实际 `argv[0]` 路径输出回来。

这证明 Runtime 的 ELF 入口不依赖 `com.termux`、也不依赖固定宿主包名。

## Bash 垂直切片（API 35 x86_64，2026-08-12）

同一对应用随后运行了来自同一 Runtime 模块的 `libugk_bash.so`。测试验证：

1. Bash 从每个宿主自己的 `nativeLibraryDir` 启动。
2. 算术展开和标准输出正确。
3. `PWD` 与 `ProcessBuilder` 设置的 app-private workspace 一致。
4. Bash 可在该 workspace 写文件，并以退出码 `0` 结束。

期间发现 Android Bionic 的 `fpurge` 声明会被 Bash 的交叉 Autoconf 错认成 glibc
`__fpurge`，导致一个自跳转函数与 CPU 自旋。该问题已在构建脚本中用显式 cache 值
固定并通过两份 App 回归。完整记录见
[`terminal-runtime-bash-slice-results.md`](terminal-runtime-bash-slice-results.md)。

## SQLite 命令映射（API 35 x86_64，2026-08-12）

同一对应用还运行了来自同一 Runtime 模块的 `libugk_sqlite3.so`。因为可执行 ELF 必须
保持 Android 原生库命名并由安装器提取，Runtime 为非交互 Bash 生成受控 `BASH_ENV`，将
逻辑命令 `sqlite3` 映射到该宿主自身的 `nativeLibraryDir`。

两个 App 最初各自完成 3 个仪器测试（Probe、Bash、SQLite）。SQLite 测试确认：

1. `sqlite3` 在 Bash 中解析为 Runtime 注入的函数。
2. `sqlite3 :memory:` 能执行查询并返回 `42|1`。
3. 其中 `1` 来自 `sqlite_compileoption_used('OMIT_LOAD_EXTENSION')`，证明动态扩展加载
   已在编译期关闭。

SQLite 的两份 ELF 均为 16 KB `LOAD` 对齐的 PIE；其 API 24 动态依赖仅为 Android 系统
的 `libc.so`、`libdl.so`、`libm.so` 与 `libz.so`，并由
`scripts/terminal-runtime/verify-runtime.ps1` 锁定校验。

## HTTPS 网络 Profile（API 35 x86_64，2026-08-12）

同一对应用随后加入 `libugk_curl.so`、`libugk_openssl.so` 和锁定 CA asset 后，均完成
第四项仪器测试。Runtime 会把 CA asset 复制为 app-private 的非可执行数据文件、验证
SHA-256，并设置不可由 Tool 输入覆写的 `CURL_CA_BUNDLE`。

测试确认：

1. `curl` 与 `openssl` 在 Bash 中解析为 Runtime 注入函数，物理 ELF 位于各宿主自己的
   `nativeLibraryDir`。
2. `printf abc | openssl dgst -sha256` 返回正确摘要。
3. `curl` 使用默认的 Runtime 管理 CA bundle 对 `https://example.com` 完成 DNS、TLS 与
   证书验证，并返回 `https=200`。
4. 这发生在 `com.ugk.runtime.demo.a` 与 `com.example.runtime.demo.b` 两个不同的
   `applicationId` 中，均为 `OK (4 tests)`。

`com.ugk.runtime.demo.a` 的 Release AAB 还使用 bundletool 1.18.3 按同一 x86_64 API 35
模拟器生成并安装 `base-master` + `base-x86_64` split APK；以与 instrumentation APK 一致
的项目 debug key 签名后，加入进程组清理后的五项测试再次为 `OK (5 tests)`。

curl 编译期只保留 `file/http/https`，OpenSSL 是无 modules/legacy/engine、禁用 SSL3 的
静态 CLI（TLS 命令仍在）；详情和 SHA-256 见
[`terminal-runtime-network-slice-results.md`](terminal-runtime-network-slice-results.md)。

## Core Release Gate 2：现有设备矩阵复核（2026-08-13）

### 补齐镜像前的本机环境与 API 35 结果

- Android SDK：`E:\Android\SDK`；补齐镜像前 Android Emulator 为 35.1.20；测试使用 SDK 内
  `platform-tools` 35.0.2。
- 补齐镜像前已安装的 system image 只有
  `system-images;android-35;google_apis_playstore;x86_64` revision 8，实占约 2.27 GB。
- 两个现有 AVD 的大文件均位于 `E:\Android\.android\avd`：`codex_api35` 与
  `pushup_trial_api35`。C 盘仅保留小型 AVD `.ini` 指针。
- 原有 `codex_api35` 在 ADB server 重启后保持 `offline`，未重启、未清数据、未关闭。
  本轮以隐藏窗口、固定端口 5556、`-no-snapshot-load` 启动现有
  `pushup_trial_api35`，未使用 `-wipe-data`。

本轮实际设备信息：

| 字段 | 值 |
|---|---|
| serial / AVD | `emulator-5556` / `pushup_trial_api35` |
| fingerprint | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.005/12228598:user/release-keys` |
| Android | API 35 / Android 15 |
| 主 ABI | `x86_64`（`abilist=x86_64,arm64-v8a` 不等于本轮执行了 arm64 payload） |
| page size | 4096 bytes（4 KB） |
| kernel | `6.6.30-android15-7-gbb616d66d8a9-ab11968886` x86_64 |
| 安装方式 | Gradle Debug target APK + Debug instrumentation APK；JNI 使用安装时提取 |
| 测试前 `/data` | 可用约 1.10 GiB，使用率 82% |

### 可重复命令与结果

```powershell
$env:JAVA_HOME = 'E:\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'E:\Android\SDK'
$env:ANDROID_SDK_ROOT = 'E:\Android\SDK'
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat `
  :terminal-probe-demo-a:connectedDebugAndroidTest `
  :terminal-probe-demo-b:connectedDebugAndroidTest `
  --console=plain
```

该单命令复跑为 `BUILD SUCCESSFUL`：

| applicationId | 测试数 | 结果 | 覆盖 |
|---|---:|---|---|
| `com.ugk.runtime.demo.a` | 7 | 7 passed，0 failed/skipped | nativeLibraryDir、Bash、SQLite、curl/HTTPS、OpenSSL、CPython、stdlib 自修复、超时进程树、execmem |
| `com.example.runtime.demo.b` | 5 | 5 passed，0 failed/skipped | 独立 nativeLibraryDir、Bash、SQLite、curl/HTTPS、OpenSSL、CPython 私有 prefix 与 subprocess |

两份网络测试均通过 `https://example.com` 的 DNS、TLS、证书校验并得到 HTTP 200，本轮没有
外部网络波动失败。A 的超时测试在 500 ms 超时后等待 2.5 秒，确认后台 descendant 未写出
marker。两个 App 的 Probe 均断言 `argv[0]` 来自各自
`ApplicationInfo.nativeLibraryDir`；B 另断言 `sys.prefix` 位于自己的
`files/ugk-terminal-runtime/python/3.14.6`，因此双 applicationId 重定位通过。

首次组合安装没有进入测试：AVD 中遗留的旧 probe APK 与当前 debug APK 证书不同，返回
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。Gradle 测试编排只清理了四个 in-scope
target/test package；在包均不存在后，同一命令稳定通过。这是测试环境签名冲突，不是
Runtime 或网络故障。

测试结束后，四个 target/test package 和 instrumentation 均由 Gradle 清理；对
`libugk_bash.so`、`libugk_python.so`、`libugk_curl.so`、`libugk_sqlite3.so`、
`libugk_openssl.so`、`libugk_runtime_probe.so`、`python`、`python3` 与 `sleep` 的
`pidof` 检查均为空，没有发现遗留测试进程。

完成记录后只对本轮启动的 `emulator-5556` 执行 `adb emu kill`；launcher PID 11812、
headless QEMU PID 61124 与 5556/5557 监听均已消失。原有 `codex_api35` QEMU PID 34276
及 5554/5555 监听保持存在，未修改其 AVD 数据。

### 补齐镜像前的要求矩阵与覆盖

| API | x86_64 / 4 KB | x86_64 / 16 KB | arm64 / 4 KB | arm64 / 16 KB |
|---:|---|---|---|---|
| 24 | 缺 image/AVD | 无现有 ps16k image | 缺设备/image | 无现有 ps16k image |
| 29 | 缺 image/AVD | 无现有 ps16k image | 缺设备/image | 无现有 ps16k image |
| 35 | **PASS：A 7/7、B 5/5** | package 可用但未安装 | package 可用但未执行 | package 可用但未安装/执行 |
| 36 | package 可用但未安装 | package 可用但未安装 | package 可用但未执行 | package 可用但未安装/执行 |

补齐镜像前确认 API 35/36 的 16 KB x86_64 package 名分别为
`system-images;android-35;google_apis_ps16k;x86_64` 与
`system-images;android-36;google_apis_ps16k;x86_64`；arm64 对应 ABI 为
`arm64-v8a`。API 24/29/36 的普通 x86_64 image 也在 sdkmanager catalog 中，
但本轮没有下载。

按现有本机数据估算，每个 system image 安装后约 2--3 GB；当前两个 AVD 各实占约
11.9--13.3 GB，因此每新增一个矩阵 AVD 应在 E 盘预留约 8--15 GB，下载量通常约
1--2 GB。当前 E 盘可用约 487 GB，C 盘仅约 24 GB；后续仍应保持
`ANDROID_SDK_ROOT=E:\Android\SDK`、`ANDROID_AVD_HOME=E:\Android\.android\avd`，
不把 image 或 AVD data 放到 C 盘。arm64 尤其是 16 KB 的发布结论应优先使用真实 arm64
设备；x86_64 AVD 即使 `abilist` 含 arm64 也不能替代 arm64 payload 验证。

### 最小镜像补齐与最终矩阵

本轮把 Android SDK 固定为 `E:\Android\SDK`，Android user home 与 AVD home 分别固定为
`E:\Android\.android`、`E:\Android\.android\avd`。临时 AVD 的 `.ini` 和数据目录均写入
E 盘；没有创建 C 盘 worktree、SDK 或 AVD data。下载前 E 盘可用
`487,308,935,168` bytes；删除三个临时 AVD 后可用 `475,003,015,168` bytes。

`sdkmanager --list` 先确认 package id，随后只安装下表三项。Android license 已接受，安装
过程没有出现新条款，也没有安装 API 35、arm64、Play Store 或 API 36 4 KB image。

| package id | revision | 安装后逐文件实占 | 保留位置 |
|---|---:|---:|---|
| `system-images;android-24;default;x86_64` | 8 | 3,273,614,437 bytes | `E:\Android\SDK\system-images\android-24\default\x86_64` |
| `system-images;android-29;default;x86_64` | 8 | 3,373,765,822 bytes | `E:\Android\SDK\system-images\android-29\default\x86_64` |
| `system-images;android-36;google_apis_ps16k;x86_64` | 7 | 4,571,069,638 bytes | `E:\Android\SDK\system-images\android-36\google_apis_ps16k\x86_64` |

安装 API 36 image 时，`sdkmanager` 同步把 Android Emulator 从 35.1.20 更新到 37.1.11；
当前目录实占 `1,082,790,346` bytes。旧版 35.1.20 被工具保留在 E 盘
`E:\Android\SDK\emulator.backup`，实占 `1,023,646,898` bytes，本轮没有删除该回滚副本。
因此 `avdmanager` 会报告 backup 的 package-location warning，但没有影响 AVD 创建或测试。

每个目标均一次只启动一个实例，使用固定空闲端口 5556、`-no-window`、`-no-audio`、
`-no-boot-anim`、`-no-snapshot`，不使用 `-wipe-data`。Debug target APK 与 Debug
instrumentation APK 由以下同一组合命令安装并运行，JNI 载荷使用安装时提取：

```powershell
$env:JAVA_HOME = 'E:\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'E:\Android\SDK'
$env:ANDROID_SDK_ROOT = 'E:\Android\SDK'
$env:ANDROID_USER_HOME = 'E:\Android\.android'
$env:ANDROID_AVD_HOME = 'E:\Android\.android\avd'
$env:ANDROID_EMULATOR_HOME = 'E:\Android\.android'
$env:ANDROID_PREFS_ROOT = 'E:\Android'
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat `
  :terminal-probe-demo-a:connectedDebugAndroidTest `
  :terminal-probe-demo-b:connectedDebugAndroidTest `
  --console=plain
```

三套新补齐目标均在首轮测试通过，没有 Runtime、网络或测试编排失败，也没有触发允许的
一次修复重试。API 35 在此前清理 in-scope 旧签名 probe 包后稳定复跑通过；该安装前冲突
没有进入测试，不计为 Runtime 失败。API 36 首次启动约 105 秒后才从 `offline` 变为
`device`，完整 boot 约 148 秒；这是同一实例的有界等待，不是重复启动。

| API / page size | image / AVD | fingerprint | 主 ABI | A / B 结果 | HTTPS |
|---|---|---|---|---|---|
| 24 / 4 KB | `android-24;default;x86_64` / `ugk_gate2_api24_x86_64_4k` | `Android/sdk_phone_x86_64/generic_x86_64:7.0/NYC/4174735:userdebug/test-keys` | `x86_64` | 7/7、5/5；0 fail/error/skipped | HTTP 200 |
| 29 / 4 KB | `android-29;default;x86_64` / `ugk_gate2_api29_x86_64_4k` | `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys` | `x86_64` | 7/7、5/5；0 fail/error/skipped | HTTP 200 |
| 35 / 4 KB | existing `pushup_trial_api35` | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.005/12228598:user/release-keys` | `x86_64` | 7/7、5/5；0 fail/error/skipped | HTTP 200 |
| 36 / 16 KB | `android-36;google_apis_ps16k;x86_64` / `ugk_gate2_api36_x86_64_16k` | `google/sdk_gphone16k_x86_64/emu64xa16k:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` | `x86_64` | 7/7、5/5；0 fail/error/skipped | HTTP 200 |

API 24 没有 `getconf`，其 4096-byte page size 由 `/proc/self/smaps` 的
`KernelPageSize: 4 kB` 交叉确认；API 29/35 返回 4096，API 36 返回 16384。API 36 的 kernel
为 `6.6.66-android15-8-gd0c43a640eab-ab13812146` x86_64。模拟器的 `abilist` 即使包含
`arm64-v8a`，本轮实际执行的仍是 x86_64 payload，不能作为 arm64 证据。

两份网络用例都对 `https://example.com` 完成 DNS、TLS、证书校验并断言 HTTP 200。A 还覆盖
CPython stdlib 自修复和超时进程树；B 覆盖独立 Python prefix。两个 App 都断言 Probe 来自
自身 `ApplicationInfo.nativeLibraryDir`，因此四个设备格上的双 `applicationId` 重定位通过。

每轮结束均确认四个 target/test package 不存在，并对 `libugk_bash.so`、
`libugk_python.so`、`libugk_session_launcher.so`、`libugk_curl.so`、
`libugk_sqlite3.so`、`libugk_openssl.so`、`libugk_runtime_probe.so`、`python`、`python3`
与 `sleep` 执行 `pidof`，结果均为空。三个新建临时 AVD 的测试期实占分别为
8,229,889,413、8,729,208,983、2,848,995,598 bytes；测试后只关闭并删除这三个精确名称的
AVD，三套 system image 均保留。最终 AVD 列表恢复为 `codex_api35` 与
`pushup_trial_api35`；原有 `codex_api35` QEMU PID 34276 和 5554/5555 监听保持存在，数据
未修改。

最终覆盖如下：

| API | x86_64 / 4 KB | x86_64 / 16 KB | arm64 / 4 KB | arm64 / 16 KB |
|---:|---|---|---|---|
| 24 | **PASS：A 7/7、B 5/5** | catalog 无本轮适用 ps16k image | 未覆盖 | 未覆盖 |
| 29 | **PASS：A 7/7、B 5/5** | catalog 无本轮适用 ps16k image | 未覆盖 | 未覆盖 |
| 35 | **PASS：A 7/7、B 5/5** | 未覆盖；非本轮下载范围 | 未覆盖 | 未覆盖 |
| 36 | 未覆盖；按最小策略未下载 4 KB image | **PASS：A 7/7、B 5/5** | 未覆盖 | 未覆盖 |

Gate 2 因 arm64 与剩余 page-size 组合仍为部分完成。下一批设备环境至少需要一台可由 ADB
独占测试的 arm64 4 KB 设备，以及一台实际返回 16384 page size 的 arm64 Android 15/16
设备；严格平台矩阵还需要 API 36 x86_64 / 4 KB、API 35 x86_64 / 16 KB 和 API 34 格。
system image 与 AVD 若需补齐仍应分别放在 `E:\Android\SDK` 和
`E:\Android\.android\avd`，并继续使用同一双 Gradle task 命令。

## Core Release Gate 3：Runtime 控制与生命周期（2026-08-13）

Gate 3 验证的是“命令能否被可靠地约束、取消和收口”，不是 Android 权限沙箱。结果如下：

| 设备 | Demo A | Demo B | 结果 |
|---|---:|---:|---|
| API 24 / x86_64 / 4 KB | 10/10 | 5/5 | 0 fail/error/skipped |
| API 36 / x86_64 / 16 KB | 10/10 | 5/5 | 0 fail/error/skipped |

Demo A 的 10 个用例覆盖默认立即确认、主动取消运行中的调用、普通超时、TERM-resistant
后台 descendant 的 SIGKILL 升级、Bash/SQLite/curl/HTTPS/OpenSSL/CPython/execmem 和
双 applicationId 资源定位；Demo B 的 5 个用例覆盖独立宿主的 Bash、SQLite、curl/HTTPS、
Python 与 nativeLibraryDir 重定位。`BashCommandToolTest` 当前为 10/10，通过重复 call id、
运行中/排队取消、`cancelAll()`、并发槽位复用、stdout/stderr 独立截断和环境变量约束。

本轮修复了两类真实 API 24 问题：

- `Process.isAlive()` 不在 minSdk 24 API 面上，改用 `exitValue()` +
  `IllegalThreadStateException` 判断存活状态。
- 清空 `ProcessBuilder` 环境后，旧版 Bionic 的时区路径解析需要显式保留
  `ANDROID_DATA=/data` 与 `ANDROID_ROOT=/system`；两者现在由 Runtime 注入并禁止 Tool 覆写。

当前 Gate 3 的控制范围是同一 `TerminalAgentPlugin` 实例内的协程、排队项和 process group；
跨进程/独立 Service/Binder Supervisor、Android 进程被杀后的恢复、主动逃逸进程治理、低磁盘
与升级迁移仍未关闭。因此 Gate 3 是 x86_64 Runtime 控制通过，不等于完整发布 Gate 或安全沙箱。

## 已证伪的打包形式

在同一设备上，把 App 改为未压缩原生库打包
（`useLegacyPackaging = false` / `extractNativeLibs = false`）后，测试失败：

```text
Probe payload is missing: <nativeLibraryDir>/lib/x86_64/libugk_runtime_probe.so
```

此时库可以被 Android 从 APK 直接映射/加载，但不会在 `nativeLibraryDir`
提供可供 `execve()` 的实际文件。因此，不能把“未压缩 `.so` 能被 dlopen”
误当成“它能作为 Bash/Python 进程启动”。

## 当前结论

Phase 1 保留安装时提取的原生载荷模式：

```kotlin
packaging {
    jniLibs.useLegacyPackaging = true
}
```

该选择让可执行文件获得真实路径，是目前 Probe 已验证的交付机制。

## 尚未关闭的风险

- arm64 4 KB/16 KB 真机或模拟器上，提取模式与 v1 Core 依赖闭包的兼容性。
- API 34、API 35 x86_64 / 16 KB、API 36 x86_64 / 4 KB，以及 API 24/29/36 真机覆盖。
- API 24/29/36 当前只有 Debug instrumentation 证据；Release APK/AAB split 仍需设备回归。
- Python stdlib、CA bundle 等 v1 Core 数据目录的可重定位行为；未来扩展必须自行建立数据目录回归。
- 这不是权限隔离机制；Runtime 仍与宿主共享 UID。
