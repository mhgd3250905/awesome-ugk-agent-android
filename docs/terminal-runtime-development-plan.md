# Terminal Runtime 开发计划（当前版）

更新时间：2026-08-29
当前阶段：快速迭代后的模块化架构整改与本机静态/打包回归已收束；继续补齐 arm64 16KB、Release 安装、低资源、升级和正式发布证据。
关联基线：[terminal-runtime-baseline.md](terminal-runtime-baseline.md)

## 1. 目标和停止条件

### 目标

交付一个可被 Android Agent SDK 消费的 Headless Terminal Runtime：宿主 App 只接入 SDK，不安装第二个 App；Agent 可以在确认/策略约束下执行非交互 Bash，并组合 Bash、curl、OpenSSL、SQLite 和 CPython 3.14.6。

### v1 停止条件

v1 不因“还可以再装更多命令”无限膨胀。以下能力是 Core Profile：

- Bash 5.3.15；
- curl 8.21.0（`file/http/https`）；
- OpenSSL 3.6.3 静态 CLI；
- SQLite 3.53.4 CLI，禁用动态扩展加载；
- CPython 3.14.6（`python`/`python3`，验证 `ssl/sqlite3/hashlib/subprocess`）。

Node.js、Git、OpenSSH、jq 不属于 v1。Node 相关脚本只能作为未来独立扩展资料，不能被 v1 构建、锁文件、Tool 文案或发布检查间接带入。

## 2. 当前完成度

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | `minSdk 24`、compile/target 36、AGP/Kotlin/JDK/NDK 基线 | 已完成 |
| Phase 1 | 原生 ELF Probe、`nativeLibraryDir` 执行、提取式打包 | x86_64 已完成；arm64 API34/4KB 本地子集已验证，16KB 未完成 |
| Phase 2 | Bash、SQLite、curl、OpenSSL、CA、CPython Core | x86_64 API 24/29/35 4KB + API36 16KB 已验证 |
| Phase 3 | Agent Tool、确认、路径/环境/输出/并发、超时/取消/process group、运行时 `AGENTS.md` 注入、`demo-app` 宿主接入、Android 原生 Intent 与跨 App 自动化基础 Tool | x86_64 API24/36 Gate3 已验证；API35 宿主集成 13/13（含 Runtime-managed 本地 HTTP 服务回归）已验证；runtime Agent contract 已打包/单测验证 |
| Phase 4 | 两 ABI 静态 Gate、设备矩阵、双 applicationId | 两 ABI 静态 Gate 完成；x86_64 子集与 arm64 API34/4KB 本地子集完成，整体未完成 |
| Phase 5 | 发布、安全、低资源、升级、Maven 消费 | 未完成 |

## 3. 近期行动顺序

### P0：arm64 运行验证

真机或可独占的 arm64 环境到位后：

1. 运行 Demo A/B 的 `connectedDebugAndroidTest`；
2. 记录 API、fingerprint、ABI、实际 page size、网络条件和测试 XML；
3. 先关闭 arm64/4KB，再关闭 arm64/16KB；不能用 x86_64 AVD 的 `abilist` 代替 arm64 证据；
4. 重点检查 nativeLibraryDir 执行、Python 扩展、CA/TLS、SQLite、主动取消和 TERM-resistant descendant。

### P1：没有真机时继续

- 完善 Runtime/Tool JVM 单元测试和错误映射；
- 保持 SDK runtime `AGENTS.md` 与根目录开发 `AGENTS.md` 分离；后续新增命令、路径或权限规则先更新 runtime 文件和注入测试；真实付费 Agent 回归只在明确需要时做单次验证；
- 完成 Release APK/AAB、bundletool split、签名和可安装性验证；
- 设计低磁盘、安装中断、标准库/CA 自修复和升级迁移测试；
- 生成 NOTICE/SBOM，核对 GPL 对应源码、补丁和许可证发布材料；
- 用独立消费 App 做 Maven 发布/接入测试；
- 评估是否需要独立 Service/Binder Supervisor，先写决策，不直接增加架构复杂度。

### P2：发布前

- 完整 API 34/35/36 与 4KB/16KB 矩阵；
- arm64 4KB/16KB 设备证据；
- 进程被杀、后台切换、低内存、低磁盘、覆盖升级、卸载重装和 Runtime 数据迁移；
- 性能、安装体积、内存、启动耗时和并发预算；
- Play/发行渠道关于网络外发、原生代码和 GPL/第三方材料的复核。

## 4. 每个阶段的验收规则

### 构建和静态规则

- 所有输入、工具和二进制产物的版本/哈希必须进入 `runtime-lock.json`；
- 两 ABI 通过 ELF class/machine/type、PIE、DT_NEEDED 闭包、GNU_STACK、TEXTREL 和 PT_LOAD 16KB 对齐；
- 不出现绝对构建路径、固定 `applicationId`、`com.termux` 运行时依赖或 Node Core 映射；
- AAR/APK 的原生库必须按当前提取式执行模型打包，并通过 zipalign/内容检查；
- 新增原生组件必须补充许可证、源码、构建脚本、体积和安全边界记录。

### Agent Tool 规则

- 高影响终端能力默认需要立即用户确认；
- 工具只能进入 app-private workspace；
- 托管环境、CA、Python 路径和 session report 不得由 Tool 覆写；
- 每次调用有超时、输出上限和并发上限；
- 取消、超时和异常必须最终清理正常 session 内的 process group；
- 文案必须明确 Runtime 不是安全沙箱，不能保护宿主 UID 可访问的秘密。

## 5. 变更管理

以下变更必须先更新 [terminal-runtime-decisions.md](terminal-runtime-decisions.md)：

- 加入/删除 Runtime 包或改变 v1 scope；
- 改变 `minSdk`、ABI、page-size 或原生打包方式；
- 改变确认策略、权限、网络外发和安全边界；
- 引入 Service/Binder/跨进程 Supervisor；
- 让运行时可以下载、安装或加载新的原生代码；
- 改变 Gate 退出条件或把部分验证泛化为完整支持。
