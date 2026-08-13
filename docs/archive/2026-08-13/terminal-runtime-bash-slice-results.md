# 【历史归档】Headless Bash + SQLite + HTTPS Runtime：最小可执行 Profile 记录

> 本文件是历史过程记录，不是当前事实源。当前状态、Gate 和未完成项以 `../../terminal-runtime-baseline.md`、`../../terminal-runtime-validation.md` 和 `../../terminal-runtime-development-plan.md` 为准。

日期：2026-08-12  
状态：本记录中的 Bash + SQLite + curl + OpenSSL 垂直切片已通过 API 35 x86_64 双
applicationId 验证；CPython 已在后续切片交付，完整 v1 Core Profile 已在 API 24/29/35
4 KB 与 API 36 16 KB 的 x86_64 双 applicationId 回归中通过；arm64 和发布级 Gate 仍未完成

## 已实现

- `ugk-terminal-runtime-android`：从宿主 `nativeLibraryDir` 启动随 APK 安装的 Bash ELF，
  设置私有 `HOME`、`PWD`、`TMPDIR`、`PATH` 和 `LD_LIBRARY_PATH`，并返回受限的
  stdout/stderr、退出码、耗时与超时状态。
- `pi-terminal-skill-android`：暴露 `terminal_bash_execute`，默认由
  `UserConfirmationRequiredTool` 包装；限制工作目录、超时、输出量和可注入环境变量。
- `terminal-probe-demo-a`、`terminal-probe-demo-b`：两个不同 `applicationId` 的
  Android 仪器测试 App。
- `scripts/terminal-runtime/build-bash.sh`：使用 NDK r28.2 交叉编译 GNU Bash 5.3.15，
  目标 API 24，输出 16 KB segment 对齐的 PIE ELF。
- `scripts/terminal-runtime/build-sqlite.sh`：使用同一 NDK 交叉编译 SQLite 3.53.4 CLI，
  目标 API 24，禁用动态扩展加载，输出 16 KB segment 对齐的 PIE ELF。
- `scripts/terminal-runtime/build-network-runtime.sh`：使用同一 NDK 交叉编译 OpenSSL
  3.6.3 与 curl 8.21.0，并锁定 Mozilla CA certificate bundle。curl 只保留
  `file/http/https`；CA asset 会由 Runtime 校验后复制到 app-private data。

## 关键实测结论

API 35 x86_64 Emulator 上，两个宿主均通过 Bash、SQLite、curl + OpenSSL 与原生 Probe
测试：

| applicationId | Bash | SQLite | curl + OpenSSL + HTTPS | 原生 Probe |
|---|---:|---:|---:|---:|
| `com.ugk.runtime.demo.a` | 通过 | 通过 | 通过 | 通过 |
| `com.example.runtime.demo.b` | 通过 | 通过 | 通过 | 通过 |

每个 Bash 测试均验证：算术展开、`PWD`、私有工作目录中的文件写入、退出码和输出采集。
每个 SQLite 测试均验证：Bash 通过受 Runtime 管理的 `BASH_ENV` 将 `sqlite3` 映射到
`nativeLibraryDir` 中的只读 ELF，`select 6 * 7` 返回 `42`，并确认
`SQLITE_OMIT_LOAD_EXTENSION` 编译选项已生效。

构建期间发现并修复一个 API 24 Bionic 交叉配置问题：Autoconf 将 Bionic 的
`fpurge` 重命名声明错误识别为 glibc 的 `__fpurge`。这会生成 `fpurge: jmp fpurge`
的无限自旋，表现为首条输出后占满一个 CPU 核。构建脚本现固定：

```text
ac_cv_func_fpurge=yes
ac_cv_func___fpurge=no
ac_cv_have_decl_fpurge=yes
```

重新构建后，Bash 命令在约 0.3 秒内完成，回归测试通过。

网络测试验证 `curl`、`openssl` 均由 `BASH_ENV` 中的受控函数解析到宿主自己的
`nativeLibraryDir`。Runtime 在下一次调用前会自愈被篡改的 `BASH_ENV`，并管理且禁止 Tool
覆写 `CURL_CA_BUNDLE`，从 APK asset 复制并 SHA-256 校验 CA bundle；两个 App 都在先篡改
这两个文件后，以恢复后的默认配置请求 `https://example.com` 并得到 `https=200`。OpenSSL
对 `abc` 的 SHA-256 返回
`ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`。

完整构建参数、二进制哈希、动态依赖和 arm64 静态检查详见
[`terminal-runtime-network-slice-results.md`](terminal-runtime-network-slice-results.md)。

## 尚未完成，不可对外承诺

- v1 Core 之外的 jq、OpenSSH、Git 及基础 Unix 工具；它们须在 Core Release Gate 之后逐项评估。
  Node.js 不属于 v1，未来如恢复只能作为独立可选 AAR/扩展模块。CPython 的后续验证见
  [`terminal-runtime-python-slice-results.md`](terminal-runtime-python-slice-results.md)。
- arm64 真机运行、API 34、API 35 x86_64 / 16 KB、API 36 x86_64 / 4 KB，以及其他未覆盖
  的 16 KB page-size 组合。
- 主动逃逸进程、跨进程/设备级显式取消、并发 Supervisor、Binder Service、低资源与升级迁移测试。
- 完整 SBOM、第三方许可证发布审计与 GPL 对应源码交付流程。

因此当前模块适合继续开发和受控内部验证，不应作为已完成的通用终端 Runtime 发布。
