# 【历史归档】CPython Runtime 切片实测结果

> 本文件是历史过程记录，不是当前事实源。当前 Python 能力和限制以 `../../terminal-runtime-baseline.md`、`../../terminal-runtime-validation.md` 和 `../../terminal-runtime-troubleshooting.md` 为准。

> 状态：已完成 x86_64 API 24/29/35 的 4 KB 与 API 36 的 16 KB 双 applicationId
> Debug 设备验证；API 35 另有 Release split 证据。arm64-v8a 只有产物静态校验，尚未获得
> 设备运行证据。  
> 验证日期：2026-08-12；Gate 1/2/3 复核：2026-08-13

## 已交付范围

Runtime 现在随宿主 APK/AAB 提供 CPython 3.14.6，并将 `python`、`python3` 注册为
Bash 内函数。受支持的基础模块已在设备上验证：

- `ssl`
- `sqlite3`
- `hashlib`
- `subprocess`

解释器、`libpython3.14.so`、CPython 依赖库和 54 个原生扩展模块按 ABI 放在
`nativeLibraryDir`；宿主仍必须配置 `packaging.jniLibs.useLegacyPackaging = true`。
这是为了让 Android Package Manager 在安装时提供可由进程加载的真实原生库文件。

标准库不是可执行载荷：它以 `stdlib.zip` 和逐文件 SHA-256 清单随 AAR 分发，首次调用
Python 时解压到宿主私有目录
`files/ugk-terminal-runtime/python/3.14.6/`。后续调用会重新校验 613 个文件；发现
损坏或残缺时会以 staging 目录重建并原子发布。

## Android 原生扩展处理

Android 的 APK 原生库提取规则会忽略不以 `lib` 开头的文件名，而 CPython 正常扩展名例如
`_sqlite3.cpython-314-…so`。构建准备脚本因此将物理文件改名为
`libugk_pyext_<原文件名>`，仍保留在 `nativeLibraryDir`。

归档中的 `sitecustomize.py` 为 CPython 安装一个只映射该固定扩展集的 finder，使
`import sqlite3` 仍按 CPython 模块名加载对应的已安装原生文件。它不会把宿主可写目录
加入这套由 Runtime 管理的原生扩展映射。

## 验证证据

| 场景 | 构建/安装方式 | 结果 |
|---|---|---|
| Demo A：`com.ugk.runtime.demo.a` | Release AAB 生成 x86_64 device split 并安装 | `OK (6 tests)` |
| Demo B：`com.example.runtime.demo.b` | 同一 Runtime AAR 的 Release x86_64 device split，Demo A 保持安装 | `OK (5 tests)` |
| Python 功能 | `python`、`python3`、`ssl/sqlite3/hashlib/subprocess` | 全部通过 |
| 重定位 | Demo B 的 `sys.prefix` | 指向 Demo B 自己的私有 `files` 路径，不依赖 Demo A 包名 |
| 自修复 | 篡改 `encodings/__init__.py` 后再次执行 Python | 清单校验触发重建，导入成功 |
| 原生负载 | 两个 ABI 的 ELF、哈希、动态依赖、16 KB `LOAD` alignment | 静态检查通过 |

除设备测试外，`scripts/terminal-runtime/verify-runtime.ps1` 会校验锁文件中 CPython
支持库、扩展树、标准库清单及 ZIP 的 SHA-256、ELF 架构、PIE、动态依赖和 16 KB 对齐。
输入 package、许可证和所有产物哈希记录在
`ugk-terminal-runtime-android/runtime-lock.json`。

## Gate 1 原生静态收口（2026-08-13）

上游 `python-3.14.6-*-linux-android.tar.gz` 是预编译 package；仓库中的
`prepare-python-runtime.ps1` 负责校验、解包和导入，并不重编 CPython。该 package 的
`prefix/lib/pkgconfig/sqlite3.pc` 使用 `prefix=/usr/local`，两个 ABI 的
`libsqlite3_python.so` 因此带有 `RUNPATH [/usr/local/lib]`。仓库的
`build-sqlite.sh` 只生成独立的 SQLite CLI，无法单独重产这个 CPython SQLite 共享库，
所以没有启动整套 CPython 重编。

范围内的净化由 `scripts/terminal-runtime/normalize-python-sqlite-rpath.ps1` 固化：使用
Debian 12 `patchelf 0.14.3-1+b1`，修改前严格要求 RUNPATH 恰为 `/usr/local/lib`，执行
标准 `patchelf --remove-rpath` 后，再用 `llvm-readelf` 和 `patchelf` 检查无 RPATH/RUNPATH、
`DT_NEEDED` 不变、ABI 不变、PT_LOAD 仍满足 16 KB。工具包、二进制哈希和固定 Docker
image digest 已记录在 `runtime-lock.json`。只处理 `arm64-v8a` 与 `x86_64` 两份
`libsqlite3_python.so`；更新后的 payload 哈希也已写回 lock。

Gate 1 的静态检查不等于 16 KB 真机通过：它覆盖 ELF/依赖/打包静态一致性，不能替代
arm64 16 KB 设备、完整 API 矩阵、低磁盘、升级迁移和安全边界验证。Gate 3 的生命周期与
取消用例已在 x86_64 API 24/36 通过，但不构成跨进程 Supervisor 或发布级证据。

## 当前边界

- 没有 `pip` 或 `ensurepip`，SDK 不提供运行时安装 Python 包的能力。
- 受支持的原生扩展只能随 APK/AAB 的固定 payload 发布；不把从网络下载或宿主可写目录中的
  原生扩展视为受支持能力。
- Python 和 Bash 都与宿主使用同一 Android UID，不构成安全沙箱。
- 当前 session + process group 机制已覆盖普通 `subprocess`；Tool 协程取消和同一
  `TerminalAgentPlugin` 实例内的显式 `cancel(callId)` / `cancelAll()` 会中断阻塞调用并
  触发进程组清理，单 Tool 默认并发上限为 2。跨进程 cancel-by-id、集中式 Supervisor、
  逃逸进程治理和进程重建仍未完成。
- arm64-v8a/16 KB 真机、完整 API 矩阵、升级迁移、低磁盘和性能预算仍是发布 Gate，不能由
  x86_64 模拟器结果替代。

## 相关文件

- `scripts/terminal-runtime/prepare-python-runtime.ps1`：验证官方 package 输入并准备可发布 payload。
- `scripts/terminal-runtime/python-sitecustomize.py`：原生扩展逻辑名到安装库文件的映射。
- `ugk-terminal-runtime-android/src/main/java/com/ugk/pi/terminal/runtime/PythonDistribution.kt`：
  标准库验证、解压、自修复和原子发布。
- `ugk-terminal-runtime-android/src/main/cpp/python_launcher.c`：通过 `Py_BytesMain` 启动
  内嵌 CPython 的原生 launcher。
