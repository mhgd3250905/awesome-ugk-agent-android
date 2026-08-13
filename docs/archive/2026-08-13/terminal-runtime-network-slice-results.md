# 【历史归档】Headless Terminal Runtime：HTTPS 网络 Profile 验证记录

> 本文件是历史过程记录，不是当前事实源。当前网络能力和发布边界以 `../../terminal-runtime-baseline.md`、`../../terminal-runtime-validation.md` 和 `../../terminal-runtime-release-checklist.md` 为准。

日期：2026-08-12  
状态：Bash + SQLite + curl + OpenSSL 的受控网络 Profile 已完成 x86_64 API 24/29/35 的
4 KB 与 API 36 的 16 KB 双 applicationId 设备验证；arm64-v8a 已完成同源构建与静态
ELF 验证，尚无 arm64 设备运行证据。

## 本批交付

- `libugk_openssl.so`：OpenSSL 3.6.3 静态 CLI，禁用 shared library、tests、modules、
  legacy provider、engine、DSO 和 SSL3；TLS 命令仍可用。
- `libugk_curl.so`：curl 8.21.0，静态链接上述 OpenSSL，仅保留 `file`、`http`、`https`。
- `cert.pem`：2026-07-16 Mozilla CA certificate bundle，以 APK asset 随包提供。
- `BashRuntime`：把 `curl`、`openssl`、`sqlite3` 映射为宿主自己的 `nativeLibraryDir`
  只读 ELF；每次启动时校验并自愈生成的 `BASH_ENV` profile，把 CA asset 校验后发布为
  app-private data，并托管 `CURL_CA_BUNDLE`。
- `TerminalAgentPlugin`：继续默认按次确认；描述明确网络外发能力和 CA 行为。

`android.permission.INTERNET` 会随 Runtime Manifest merge 到宿主。它是普通 Android
权限，但对 Agent 而言意味着真实数据外发能力；宿主不能把工作目录约束或独立进程当作
网络安全沙箱。

## 受控范围

| 命令 | 本批保证 | 明确不含 |
|---|---|---|
| `curl` | `file`、`http`、`https`，默认读取 Runtime 管理的 CA bundle | SSH/SCP/SFTP、FTP、LDAP、mail、HTTP/2、HTTP/3、HSTS、Alt-Svc、WebSocket、libpsl、brotli、zstd |
| `openssl` | `version`、`dgst`、`s_client` 等静态 CLI 功能；TLS 命令具有真实网络外发能力 | 动态 provider/module、legacy/engine 生态和完整 OpenSSL 配置承诺 |

本网络 Profile 的初始验证不包含 Python；CPython 已在后续切片交付，详见
[`terminal-runtime-python-slice-results.md`](terminal-runtime-python-slice-results.md)；
v1 不包含 Node.js、Git、OpenSSH、jq。Runtime 不允许通过 `apt`、`pkg` 或下载 ELF
补齐这些能力；Node 未来若恢复只能作为独立可选 AAR/扩展模块。

## 可复现构建

构建缓存位于 Git 工作树外的 `E:\AII\vendor`。Windows 使用 NDK r28.2 与 Git for
Windows Bash：

```powershell
$env:ANDROID_NDK_ROOT = 'E:\Android\SDK\ndk\28.2.13676358'
$env:UGK_TERMINAL_VENDOR_DIR = 'E:\AII\vendor'
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-network-runtime.sh arm64-v8a
& 'D:\Git\bin\bash.exe' scripts/terminal-runtime/build-network-runtime.sh x86_64
```

构建脚本会下载并验证以下锁定输入：OpenSSL 3.6.3、curl 8.21.0、CA bundle 2026-07-16。
Git for Windows 的 Perl 缺失 OpenSSL 配置所需的三个仅构建期模块；仓库中包含最小兼容
shim，且不会进入 Android APK。

## 产物证据

| 命令 | ABI | 大小（bytes） | SHA-256 |
|---|---|---:|---|
| `openssl` | arm64-v8a | 7,002,952 | `61d3a59ea2517ba8a641bba33ac581626f8226a6e441f664b4e3bcd728d07bc6` |
| `openssl` | x86_64 | 7,319,328 | `356bc007643c33616cccf00370d478b21065bd877ac4ce5df7ded9f37bba8346` |
| `curl` | arm64-v8a | 6,477,280 | `edce5d66cc49d6b257f8d515528378c7f5042d749f7f3ef7ab278558f3160436` |
| `curl` | x86_64 | 6,783,616 | `f656d793bb8a67b55d862901e64e899efb5baab19eb388811cdbc59625220c75` |
| CA bundle | data asset | 186,446 | `3ff344e30b9b1ed2971044eabb438a08f2e2245ddb5f8ab1a3ad8b63ab4eaf91` |

所有新 ELF 都是 API 24 的 PIE `DYN` 文件，使用 `/system/bin/linker64`、16 KB `LOAD`
alignment。OpenSSL 的动态依赖仅为 `libc.so`、`libdl.so`；curl 的动态依赖仅为
`libc.so`、`libdl.so`、`libz.so`。`verify-runtime.ps1` 同时验证哈希、大小、ABI、PIE、
linker、依赖和禁止的构建路径/包名字符串。

## API 35 x86_64 实测

在 `emulator-5554` 上，先以 adb 从独立临时目录运行新 ELF，得到：

```text
OpenSSL 3.6.3 ... platform: android-x86_64
SHA2-256(stdin)= ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
curl 8.21.0 ... OpenSSL/3.6.3
Protocols: file http https
https=200
```

之后通过 SDK 本身重新打包并安装两份 Probe App；每份各执行四项 Android 仪器测试，均为
`OK (4 tests)`：

| applicationId | Probe | Bash | SQLite | curl + OpenSSL + managed CA + HTTPS |
|---|---:|---:|---:|---:|
| `com.ugk.runtime.demo.a` | 通过 | 通过 | 通过 | 通过 |
| `com.example.runtime.demo.b` | 通过 | 通过 | 通过 | 通过 |

网络用例断言 `curl`/`openssl` 被 Bash profile 解析为函数、`CURL_CA_BUNDLE` 可读、
OpenSSL SHA-256 正确，并用 HTTPS 请求 `https://example.com` 得到 `200`。这也验证 CA
asset 的复制、Manifest 权限合并和两个不同宿主 `applicationId` 的可重定位路径。每个网络
用例会先故意篡改当前 `BASH_ENV` 与 `CURL_CA_BUNDLE`，随后一次 Runtime 调用必须重建两者
并成功完成同一 HTTPS 请求。

## Release AAB split 验证

使用 bundletool 1.18.3，将 `terminal-probe-demo-a` 的 Release AAB 按当前 x86_64 API 35
模拟器生成 `base-master.apk` 与 `base-x86_64.apk`。为使已有 debug instrumentation APK 能
绑定到 Release target，APK set 使用项目实际的 `E:\Android\.android\debug.keystore`
签名；已用 `apksigner` 确认二者证书 SHA-256 一致。安装该 split APK set 后，Demo A 再次
返回 `OK (5 tests)`，包括篡改自愈后的 HTTPS 用例和进程组超时清理用例。

## 本记录之后仍未关闭的发布 Gate

- arm64-v8a 真机/模拟器、API 34、API 35 x86_64 / 16 KB、API 36 x86_64 / 4 KB，
  以及尚未覆盖的 16 KB page-size 组合。
- 主动逃逸进程、跨进程/设备级显式取消、并发 Supervisor、独立 Service、低资源与升级迁移测试。
- v1 Core 的 arm64/16 KB、完整 API 矩阵、低资源、升级迁移和发布级许可证 Gate；Gate 3
  已在 x86_64 API 24/36 覆盖控制、取消与超时，Core Gate 之后才评估 jq、OpenSSH、Git。
  Node 不属于当前发布阻塞项。
