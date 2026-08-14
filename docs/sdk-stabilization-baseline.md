# SDK 稳定化测试期与版本封存

更新时间：2026-08-14
阶段：稳定化测试期（非正式发布）

## 目的与结论

本文件把当前已经完成的架构整改、Demo 修复、Terminal Runtime 验证和消费边界检查封存为后续稳定性测试的起点。
它是绑定到指定 Git commit 的内部测试基线，不代表 SDK 或 Demo 产生了新的对外发布版本，不提供 API/ABI 兼容承诺，也不改变已有版本标签。

本阶段的核心策略是冻结架构边界、积累运行证据、只修复回归问题。暂不继续拆分模块、引入 Coordinator、扩张 Core 公共 API，或为了“看起来更完整”提前建立 API/ABI baseline。

## 版本与保存边界

| 对象 | 当前坐标 | 本次处理 |
|---|---|---|
| Core SDK publication | `com.ugk.pi:ugk-pi-android:0.1.0` | 保持开发期消费坐标，不 bump |
| Demo APK | `0.2.1` / `versionCode 3` | 保持当前可安装版本，不 bump |
| 既有产品标签 | `demo-app-v0.2.1` | 保留，不移动、不覆盖 |
| 稳定化保存点 | `sdk-stabilization-baseline-2026-08-14` | 独立 checkpoint 标签，不代表发布 |

保存前的父提交为完整 SHA `641847b0f1c4d1da5e62c395a50f632d5f29a2ba`，当时索引干净但工作树包含此前已经完成、尚未提交的改动；本次保存包含这些实现、测试、构建配置模板和验证记录，本步骤不新增生产功能。保存提交和验证提交的完整 SHA 记录在本文件的“提交绑定”小节。

以下内容明确不属于保存点：

- 被 Git 忽略的 `local.properties`，其中不读取、不提交 API key；
- Gradle/Kotlin 生成的 `.kotlin/` 临时缓存；
- 未经本阶段验证的 16 KB page size、真实 API 端到端和人工无障碍场景。

## 当前验证快照

验证日期：2026-08-14。设备：`R5CRB11B2AW` / `SM-A526U1`，Android 14/API 34，`arm64-v8a`，4 KB page size。

| 范围 | 结果 | 证据边界 |
|---|---|---|
| Core、各 skill、Demo 单元测试 | 通过 | `ugk-terminal-runtime-android:testDebugUnitTest` 当前为 `NO-SOURCE` |
| Demo Debug 构建 | 通过 | APK 已重新安装并启动 |
| Core Release AAR | 通过 | `:ugk-pi-android:bundleReleaseAar` |
| Core 外部消费检查 | 通过 | `scripts/sdk/verify-core-consumer.ps1` |
| Demo connected instrumentation | `14/14` 通过 | 当前真机；测试完成后已重新安装 APK |
| Runtime Probe | A `9/10`，B `4/5` | 唯一失败为设备 VPN/网络无上游导致 `example.com` DNS 失败，不归因于本地 Runtime 能力 |
| 启动日志 | 未见已知致命异常 | 未见 `FATAL EXCEPTION`、`UnsatisfiedLinkError`、`BadTokenException` |
| 工作树格式检查 | 通过 | `git diff --check` |

上述结果只证明列出的设备、构建形态和网络条件，不外推到其他 Android 版本、page size、Provider 或宿主生命周期。

## 提交绑定

- `641847b0f1c4d1da5e62c395a50f632d5f29a2ba`：本步骤开始前的父提交；不包含本次尚未封存的工作树改动。
- 稳定化 checkpoint：本文件所在的版本保存提交；提交完成后从该提交重新执行关键验证，再以验证记录提交绑定最终标签。
- `demo-app-v0.2.1` 指向历史 Demo 版本 `5e94c8adcdc6a8f5bb3a85037f8350ce7edb1e8c`，与当前开发线相差 13 个提交；不移动、不覆盖。

本记录的最终保存提交、验证提交和 `sdk-stabilization-baseline-2026-08-14` 标签指向，必须以 Git 实际输出为准；若后续提交改变生产代码，必须重新执行关键验证，不能沿用本记录。

## 稳定化测试窗口

### 冻结规则

- 只接受 P0/P1 缺陷、可复现回归、阻断验证的问题修复；每个修复必须补充对应测试或证据。
- 不在稳定化窗口内新增模块拆分、Runtime Coordinator、持久化 TicketStore、runId 或新的跨模块公共 API。
- 不因内部修复、文档、测试或架构整理自动提升 SDK/Demo 版本。
- 任何改变公共 API、确认语义、权限边界、Terminal Runtime scope 或 Gate 退出条件的工作，先新增独立台账条目并重新审查。

### 待完成验证

1. 重复执行当前 Demo connected instrumentation，形成至少三轮独立的 `14/14` 记录。
2. 覆盖确认成功、取消、过期、输入变化、重复使用，以及 AgentRuntime 取消/协程结束后的恢复。
3. 覆盖 Activity 后台/重建、悬浮窗切换、无悬浮窗权限和人工无障碍屏幕操作。
4. 在网络上游可用时执行真实 Provider API 端到端；网络不可用时保留失败原因，不把 DNS 失败记为本地能力回归。
5. 在 16 KB page size 的 arm64 环境补充构建和运行证据。

### 阶段退出条件

稳定化测试期完成前，不宣称“架构整改完成”或“全设备兼容”。至少满足以下条件后，才进入下一次架构评审：

- 没有未解释的 P0/P1 回归，且当前自动化真机回归达到三轮稳定结果；
- Activity 生命周期、取消、确认边界和人工无障碍操作有当前版本证据；
- 至少一次真实 Provider 端到端成功，或明确记录外部网络阻断及替代证据；
- 16 KB page size 的验证结论已单独记录；
- 出现真实长期外部 consumer 或正式分发承诺后，再按 `sdk-core-consumer-contract.md` 触发 API/ABI release gate。

在退出条件满足前，`0.1.0` 和 `0.2.1/versionCode 3` 继续作为当前开发/测试坐标。
