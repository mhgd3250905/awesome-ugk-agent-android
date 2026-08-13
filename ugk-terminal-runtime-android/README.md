# UGK Headless Bash + Python + SQLite + HTTPS Runtime（v1 Core Profile，当前开发版）

这个模块把一个无 UI 的 Bash ELF 随宿主 App 打包。它不是 Termux App，也不需要
用户额外安装第二个 App。

当前 v1 Core Profile 的目标和已验证范围是 `minSdk 24` 的 **Bash、CPython 3.14.6（`python` / `python3`）、
`sqlite3`、`curl`、`openssl`**、私有工作目录、结构化执行结果和 Android Agent Tool 接入。
Python 使用 CPython 官方 Android embeddable distribution：解释器和扩展模块留在
`nativeLibraryDir`，标准库作为已锁定的纯数据归档复制到 app-private data 并逐文件校验。
已在 x86_64 API 24/29/35（4 KB）与 API 36（16 KB）双 `applicationId` 设备回归中验证
`ssl`、`sqlite3`、`hashlib`、`subprocess`。v1 不支持、不打包、不宣称 Node.js，也不包含
Git、OpenSSH、jq；不能因为 Android 系统镜像中恰好存在某个命令而把它当作 SDK 能力。

Node.js 仅作为未来可能的独立可选 AAR/扩展模块保留。若恢复实现，必须独立记录体积、安全边界、
兼容矩阵、许可证和设备回归，仍随宿主 App 安装，不要求第二个 App；它不属于当前 v1 Gate。

当前目标、已完成/未完成项见
[`terminal-runtime-baseline.md`](../docs/terminal-runtime-baseline.md)；验证命令和矩阵见
[`terminal-runtime-validation.md`](../docs/terminal-runtime-validation.md)；发布前检查见
[`terminal-runtime-release-checklist.md`](../docs/terminal-runtime-release-checklist.md)。

`curl` 被裁剪为 `file`、`http`、`https`，并使用随 Runtime 锁定、校验后复制到
app-private data 的 CA bundle。

## 宿主接入

```kotlin
dependencies {
    implementation(project(":ugk-terminal-runtime-android"))
    implementation(project(":pi-terminal-skill-android"))
}

android {
    packaging {
        jniLibs {
            // Bash 必须作为真实文件出现在 nativeLibraryDir，才能由 ProcessBuilder 启动。
            useLegacyPackaging = true
        }
    }
}
```

`useLegacyPackaging = true` 是当前已在 x86_64 API 24/29/35（4 KB）与 API 36（16 KB）验证过的必要条件。AAR
不能可靠地替宿主 Gradle DSL 强制这一行为，因此宿主必须显式配置并在其目标设备上
复测。模块 Manifest 还声明了 `android:extractNativeLibs="true"`。

由于当前 Profile 包含真实网络客户端，模块还会通过 Manifest merger 向宿主声明普通的
`android.permission.INTERNET` 权限。接入方必须将其视为 Agent 的网络外发能力；
`TerminalAgentPlugin` 默认要求每次调用都获得用户确认。

直接使用 Runtime：

```kotlin
val runtime = BashRuntime(context)
val result = runtime.execute(
    BashCommandRequest(
        script = "python -c \"import hashlib; print(hashlib.sha256(b'abc').hexdigest())\""
    )
)
```

Agent 接入默认要求每次用户确认：

```kotlin
val terminalPlugin = TerminalAgentPlugin(context)
// 将 terminalPlugin 注册到宿主的 AgentRuntime。

// 宿主生命周期结束或用户主动停止任务时，可按 ToolCall id 取消运行中/排队调用：
terminalPlugin.cancel(callId)
```

注册 `TerminalAgentPlugin` 时，SDK 会同时读取并注入随 terminal skill 打包的运行时
`AGENTS.md`（`assets/ugk/AGENTS.md`）。它专门告诉 SDK 内 Agent：`terminal_bash_execute` 的
`script` 已经是 Bash source，不要再次调用 `bash`/`sh` 子进程，以及当前 v1 的可用/不可用命令。
这份文件与仓库根目录仅供开发者使用的 `AGENTS.md` 不是同一作用域，宿主不需要手工复制提示词。

该 API 只作用于当前 `TerminalAgentPlugin` 实例持有的运行中/排队调用；它不是跨进程或集中式
Supervisor 的 cancel-by-id 接口。`cancelAll()` 具有同样的实例范围。

只有宿主明确传入 `TerminalToolPolicy(requireUserConfirmation = false)` 时，工具才会
不经确认执行。该选项只适用于已经获得用户明确授权的可信会话。

### 本地网站预览

需要让浏览器访问一个工作区目录时，使用 `pi-terminal-skill-android` 随
`TerminalAgentPlugin` 注册的三个结构化 Tool：

- `local_http_server_start`：启动/复用 SDK 管理的 CPython HTTP server；默认绑定
  `127.0.0.1:8765`，需要用户确认；
- `local_http_server_status`：只读检查服务是否仍在监听，不需要确认；
- `local_http_server_stop`：按端口停止由 Runtime 记录的服务，需要用户确认。

这些 Tool 直接使用 Runtime 已验证的 Python launcher 和独立 process group，不要求 Agent
写 `nohup python3`、`disown` 或 Bash 后台脚本。返回的 `http://127.0.0.1:<port>/` 可以交给
Android 原生 `Intent` Tool 在同一设备的浏览器中打开，但不是局域网或公网地址。

## 安全与边界

- Bash 子进程与宿主 App 使用同一个 Android UID；它不是安全沙箱。
- 默认工作目录是 `files/ugk-terminal-workspace/`，目录限制不是权限边界。
- `curl` 可访问网络；默认 CA 路径由 Runtime 管理。Agent 仍可能主动传入 `-k`、代理或
  自定义证书参数，因此确认机制不能替代宿主自身的数据外发策略。
- `openssl` 当前是静态、无动态 provider/module、无 legacy/engine、禁用 SSL3 的 CLI；
  TLS 命令（包括 `s_client`）仍可用，也意味着它本身同样具有网络外发能力。不要承诺
  完整 OpenSSL 配置生态或把它当作受限网络沙箱。
- Python 与 Bash 一样使用宿主 App 的 Android UID；它不是隔离解释器。`PYTHONHOME`、
  `PYTHONPATH`、扩展目录和证书路径由 Runtime 管理，调用者不能通过 tool 的 `environment`
  覆盖它们。标准库是私有目录中的数据而非可执行 ELF，发现损坏会在下次调用自动重建。
- Python 不包含 `pip` / `ensurepip`，Runtime 只把随 APK/AAB 发布的固定解释器与原生扩展
  作为受支持 payload。不要把这一配置当成安全边界：Python 与 Bash 同 UID，仍可执行调用者
  提供的代码并访问其有权访问的数据。
- Runtime 不能更新或执行从网络下载的原生 ELF；原生负载只能随新的 APK/AAB 发布。
- 每次执行都会进入独立 POSIX session。超时或 `TerminalAgentPlugin.cancel(callId)`
  先向整个 process group 发送 `SIGTERM`，再在 500 ms 后发送 `SIGKILL`；已验证普通 Bash
  后台子进程和 TERM-resistant descendant 会一同结束。Tool 默认最多并发 2 个调用，宿主可配置到 4 个；协程取消也会
  触发同样的清理。主动调用 `setsid` 或借由其他 Android 组件逃逸的进程不在此保证内；
  Binder Service、跨进程恢复和进程重建仍未完成，因此当前实现仍不适合不受信任或长时间
  后台任务。

## 重建 Runtime 负载

构建缓存必须位于 Git 工作树之外。例如在 Windows：

```powershell
$env:ANDROID_NDK_ROOT = 'E:\Android\SDK\ndk\28.2.13676358'
$env:UGK_TERMINAL_VENDOR_DIR = 'E:\AII\vendor'
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-bash.sh arm64-v8a
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-bash.sh x86_64
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-sqlite.sh arm64-v8a
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-sqlite.sh x86_64
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-network-runtime.sh arm64-v8a
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-network-runtime.sh x86_64

# Python 官方 Android package 应下载到 Git 工作树以外的 E 盘目录，并先按 runtime-lock.json
# 中的 SHA-256 校验。该脚本只从已校验 package 准备 Runtime payload。
./scripts/terminal-runtime/prepare-python-runtime.ps1 `
  -X86Package 'E:\AII\vendor\sources\python-3.14.6-x86_64-linux-android.tar.gz' `
  -Arm64Package 'E:\AII\vendor\sources\python-3.14.6-aarch64-linux-android.tar.gz' `
  -PatchelfTool 'E:\AII\vendor\tools\patchelf-0.14.3-debian12-amd64' `
  -NdkRoot 'E:\Android\SDK\ndk\28.2.13676358' `
  -WorkDirectory 'E:\AII\vendor\build\python-3.14.6-runtime' `
  -ReplaceExisting
```

脚本锁定 GNU Bash 5.3.15、SQLite 3.53.4、OpenSSL 3.6.3、curl 8.21.0、CPython 3.14.6
官方 Android package 和 CA bundle 的输入/产物 SHA-256，并为 API 24 Bionic 固定必要的
交叉编译探测值。SQLite CLI 禁止动态加载扩展（`SQLITE_OMIT_LOAD_EXTENSION`）；curl 只构建
`file/http/https`，OpenSSL 使用静态内建 provider。Python preparation 排除 CPython test
extensions、`test`、IDLE、Tk、`ensurepip` 和 pydoc data，并用 `sitecustomize.py` 将 Android
可提取的 `libugk_pyext_*` 文件映射回 CPython 模块名。它不会下载或使用 Termux App；Termux
package recipe 仅作为构建兼容性参考。

上游 CPython Android package 的 `libsqlite3_python.so` 带有构建机 RUNPATH
`/usr/local/lib`。准备脚本在发布前调用
`scripts/terminal-runtime/normalize-python-sqlite-rpath.ps1`，仅对两个 ABI 的该文件
执行标准 `patchelf --remove-rpath`；脚本会锁定工具 SHA-256，并在修改前后断言 RUNPATH、
`DT_NEEDED`、ELF ABI 和 PT_LOAD 16 KB 对齐。它不会手工改写 ELF，也不会净化其他 payload。

GNU Bash 采用 GPL-3.0-or-later。发布包含本模块的产品前，必须完成对应源码、补丁、
构建脚本、许可证文本和 SBOM 的分发义务；详见
[`terminal-runtime-release-checklist.md`](../docs/terminal-runtime-release-checklist.md)。
SQLite 为 Public Domain；OpenSSL 为 Apache-2.0；curl 使用 curl license（MIT-like）；
CPython 使用 PSF-2.0；Mozilla CA certificate bundle 使用 MPL-2.0。它们的源码、构建参数、
数据及二进制哈希均锁定在 `runtime-lock.json`，详见 `THIRD_PARTY_NOTICES.md`。

可以在构建后运行以下检查，校验 Runtime lock、架构、PIE、linker、16 KB `LOAD`
alignment 和动态依赖：

```powershell
./scripts/terminal-runtime/verify-runtime.ps1 -CheckPackages -NdkRoot 'E:\Android\SDK\ndk\28.2.13676358'
```
