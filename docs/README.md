# 项目文档入口

更新时间：2026-08-27
适用项目：`awesome-ugk-agent-android`
源码目录：`D:\AII\ugk-android`

独立持续开发台账：[awesome-ugk-agent-android-ledger](https://github.com/mhgd3250905/awesome-ugk-agent-android-ledger)

## 当前文档治理规则

本目录只保留一套当前事实源。过程性记录、已过期计划和历史测试明细进入 `archive/`，保留证据但不得被当作当前状态引用。

### 事实优先级

1. **源码、构建配置、`runtime-lock.json` 和实际测试结果**：最高优先级。
2. **本目录下的规范文档**：对源码事实进行解释、归纳和规划。
3. **模块 README 和 `AGENTS.md`**：面向接入者/新会话的入口，不能与规范文档冲突。
4. **`docs/archive/`**：历史证据，仅用于追溯。

如果文档与源码/测试结果冲突，先修正文档；如果源码与目标决策冲突，先暂停扩展并新增决策记录，不用文字掩盖实现偏差。

## 两个 `AGENTS.md` 的作用域

项目根目录的 `AGENTS.md` 是开发协作规范，不会打包进 SDK。SDK 内 Agent 使用另一份同名文件：
`pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md`。它由 `TerminalAgentPlugin` 注册时自动作为全局系统指令注入每次
`ModelRequest`，描述真实的 Headless Bash 环境、可用命令和替换规则。两者不能互相替代，也不能把开发规范当成模型运行时环境事实。

## 当前规范文档

| 文件 | 唯一职责 |
|---|---|
| [terminal-runtime-baseline.md](terminal-runtime-baseline.md) | 当前目标、v1 scope、已完成/未完成和“不可以宣称什么” |
| [terminal-runtime-architecture.md](terminal-runtime-architecture.md) | 模块、运行链路、数据目录、进程/环境/安全边界 |
| [terminal-runtime-development-plan.md](terminal-runtime-development-plan.md) | 当前阶段、里程碑、退出条件和行动顺序 |
| [terminal-runtime-validation.md](terminal-runtime-validation.md) | 验证命令、Gate、设备矩阵、结果和证据判读规则 |
| [terminal-runtime-troubleshooting.md](terminal-runtime-troubleshooting.md) | 已发生问题、根因、修复和复现/验证方法 |
| [terminal-runtime-decisions.md](terminal-runtime-decisions.md) | 影响范围或架构的正式决策记录 |
| [terminal-runtime-release-checklist.md](terminal-runtime-release-checklist.md) | 发布前必须逐项关闭的技术、许可证和接入检查 |
| [demo-app-ui-redesign.md](demo-app-ui-redesign.md) | demo-app 聊天、过程、输入和跨 App 悬浮窗的当前交互基线 |
| [demo-app-version-ledger.md](demo-app-version-ledger.md) | demo-app 版本、变更范围和验收台账 |
| [android-accessibility-screen-automation.md](android-accessibility-screen-automation.md) | Android Accessibility 屏幕自动化 Skill、Tool 协议、宿主接入和验证边界 |
| [android-clipboard.md](android-clipboard.md) | Android 文本剪贴板 Tool/Skill、确认策略、隐私和 API 限制 |
| [android-scheduled-tasks.md](android-scheduled-tasks.md) | Android Agent 定时任务控制面、持久化调度运行时、能力开关和验收边界 |
| [sdk-optimization-ledger.md](sdk-optimization-ledger.md) | SDK 架构优化步骤、验证结果和版本影响台账 |
| [sdk-core-consumer-contract.md](sdk-core-consumer-contract.md) | Core AAR 外部消费边界、版本策略和 API/ABI gate 触发条件 |
| [sdk-stabilization-baseline.md](sdk-stabilization-baseline.md) | SDK 稳定化测试期、版本封存点、验证快照和退出条件 |

## 快速阅读顺序

新会话先读：

1. `../AGENTS.md`；
2. `terminal-runtime-baseline.md`；
3. `terminal-runtime-development-plan.md`；
4. `terminal-runtime-validation.md`；
5. `terminal-runtime-troubleshooting.md`。

需要理解实现时，再读 `terminal-runtime-architecture.md`；需要改变方向时，先读并更新 `terminal-runtime-decisions.md`。

涉及 `demo-app` 的聊天或悬浮窗 UI 时，补读 `demo-app-ui-redesign.md` 和
`demo-app-version-ledger.md`；这两份文档只记录宿主 Demo 的产品 UI，不改变 Terminal Runtime 的 Gate 结论。

## 状态标签

- **已验证**：有明确命令、设备/环境和结果；可以对外描述，但要保留适用范围。
- **部分验证**：部分 ABI/API/page size/构建形态通过，不能泛化到未覆盖范围。
- **待验证**：已设计测试但没有可接受证据。
- **明确不支持**：当前 scope 明确排除，不得通过“系统恰好有命令”推断可用。
- **历史**：只存在于 `archive/`，不能用于更新当前状态。

## 维护规范

- 每次修改 v1 scope、打包方式、权限/安全边界或 Gate 退出条件，必须更新 `terminal-runtime-decisions.md`。
- 每次测试记录日期、源码 commit/工作树状态、API、ABI、page size、命令、结果和限制。
- 测试数量必须来自最新 XML/Gradle 输出，不手工沿用旧记录中的 `4 tests`、`7/7` 等数字。
- 新增大型 Runtime 前先记录体积、许可证、CVE、原生扩展和兼容矩阵，不直接加入 Core。
- `runtime-lock.json` 中的构建容器只能作为构建工具记录；不得把构建镜像中的 Node 误写成 v1 Runtime 能力。
- 旧文档不直接删除；如果不再是当前事实源，移动到 `archive/YYYY-MM-DD/` 并加归档声明。
- 修改文档后至少运行链接/关键词一致性检查和 `git diff --check`；涉及实现时运行对应 Gradle 验证。
