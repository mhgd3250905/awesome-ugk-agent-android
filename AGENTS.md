# awesome-ugk-agent-android

Android Agent Runtime SDK — 通用 AI Agent 工具循环框架，附带无障碍屏幕操控 demo。

## 模块

```
:ugk-pi-android              — Agent Runtime 核心（AgentRuntime, AgentSession, AgentTool, LLMProvider, AndroidSkill）
:pi-file-skill-android       — 应用私有文件工具 skill
:pi-schedule-skill-android   — 定时任务 skill
:ugk-agent-task-runtime-android — Android 定时任务持久化、AlarmManager/JobScheduler 与通知运行时
:pi-system-skill-android     — 系统设置 / 权限 / Intent skill
:pi-agent-skill-runtime-android — 文件型 skill 运行时（SKILL.md 发现/解析/按加载策略注入）+ agent-memory 预制记忆 skill
:demo-app                    — 无障碍屏幕操控 demo（包名 com.ugk.pi.android.testapp）
:ugk-terminal-runtime-android — 无 UI 原生终端 Runtime 基础设施
:pi-terminal-skill-android  — Bash Agent Tool（默认逐次用户确认）
:terminal-probe-demo-a/b     — Runtime 可重定位验证 app（不同 applicationId）
```

依赖方向：demo-app -> ugk-pi-android, pi-*

## 文档事实源

Terminal Runtime 的当前目标、状态和验证以 `docs/README.md` 为入口，按以下顺序阅读：

1. `docs/terminal-runtime-baseline.md`
2. `docs/terminal-runtime-development-plan.md`
3. `docs/terminal-runtime-validation.md`
4. `docs/terminal-runtime-architecture.md`
5. `docs/terminal-runtime-decisions.md`

`docs/archive/` 只保存历史记录，不作为当前状态依据。需要改变 v1 scope、打包方式、权限/安全边界或 Gate 退出条件时，先更新 `docs/terminal-runtime-decisions.md`。

## 构建命令

`demo-app` 的 Debug 签名和默认 API 配置由被 Git 忽略的 `local.properties` 固定。首次构建请复制
`local.properties.example`，填写本机 SDK、稳定 debug keystore，以及可选的外部 API 配置文件路径。
API 内容不得复制进源码、文档或提交；Release 默认不嵌入 API 配置。

```powershell
# 构建原有无障碍 demo
.\gradlew.bat :demo-app:assembleDebug --console=plain

# 跑全部单元测试
.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-file-skill-android:testDebugUnitTest `
  :pi-schedule-skill-android:testDebugUnitTest `
  :ugk-agent-task-runtime-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :pi-agent-skill-runtime-android:testDebugUnitTest `
  :ugk-terminal-runtime-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  --console=plain

# 终端 Runtime 的双 applicationId 仪器测试（设备在线后执行）
.\gradlew.bat `
  :terminal-probe-demo-a:connectedDebugAndroidTest `
  :terminal-probe-demo-b:connectedDebugAndroidTest `
  --console=plain

# Runtime 静态/打包验收
.\scripts\terminal-runtime\verify-runtime.ps1 `
  -CheckPackages `
  -NdkRoot (Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358')

# 发布到本地 Maven（供外部项目消费）
.\gradlew.bat :ugk-pi-android:publishReleasePublicationToMavenLocal --console=plain
```

外部项目通过 mavenLocal 消费：
```kotlin
implementation("com.ugk.pi:ugk-pi-android:0.1.0")
```

## 技术栈

Kotlin 2.2.21, AGP 8.11.1, Java 17, compileSdk/targetSdk 36, minSdk 24, Gradle 8.13,
NDK 28.2.13676358
Kotlin Serialization（非 Gson/Moshi）
JUnit 4 + kotlinx-coroutines-test

v1 Terminal Core Profile：Bash、curl、OpenSSL、SQLite、CPython 3.14.6；Node.js、Git、OpenSSH、jq 不属于 v1。

## SDK 架构

- `AgentRuntime` 运行循环：用户消息 -> LLM -> ToolCall -> AgentTool -> ToolResult -> LLM -> 最终回答
- `AgentCapabilityPlugin` 是工具+技能+运行时全局指令的注册入口（`tools()` + `skills()` + `skillProviders()` + `agentInstructions()`）
- `AndroidSkill` 是只读上下文包，不创建工具、不授权；skill method 仅在同名 tool 已注册时才注入
- 文件型 skill：`pi-agent-skill-runtime-android` 把 SKILL.md 文件（frontmatter + 三级加载策略 always/indexed/triggered）经 `AgentSkillRuntimePlugin` 贡献的 `FileBackedSkillProvider` 动态注入上下文；`AgentRuntime.Builder` 每 run 合并 plugin dynamic/custom/plugin-declared skills。规范见 `docs/android-agent-skills.md`
- `AnthropicMessagesProvider` / `OpenAiChatCompletionsProvider` 支持 baseUrl 自定义
- 高影响工具默认通过 `UserConfirmationRequiredTool` 包装并先调用 `show_user_confirmation_dialog`；宿主显式开启 full authorization 时不注册确认 Tool，但仍保留工具自身校验

## 两类同名 `AGENTS.md`（必须区分）

- 根目录 `AGENTS.md`：开发协作规范，只给 Codex/仓库贡献者使用，不进入 APK/AAR，也不作为运行时 Agent 的环境事实。
- SDK runtime `AGENTS.md`：位于 `pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md`，随终端 skill 打包进宿主 APK；`TerminalAgentPlugin` 注册时通过 `agentInstructions()` 自动注入 `AgentRuntime` 的每次 `ModelRequest`，作为 SDK 内 Agent 的全局环境契约。
- 两个文件都必须保留文件名 `AGENTS.md`，但作用域、内容和生命周期完全不同。修改终端能力、命令映射或运行时限制时，必须同步检查 SDK runtime 文件及其验证记录；不能把根目录开发规范复制给模型。

## demo-app

无障碍屏幕操控 Agent，UI 全部代码构建（无 XML layout），功能：

| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | 主界面：对话 UI + AgentRuntime 构建 + skill instructions |
| `AgentAccessibilityService.kt` | 无障碍服务，静态 `instance` 给 Tool 使用 |
| `AgentFloatingWindow.kt` | 可拖动、缩放的跨 App 悬浮窗；按过程到最终回答的时间线展示 Agent 状态 |
| `pi-system-skill-android/src/main/.../ScreenAutomationTools.kt` | SDK 统一的 screen read/find/action/gesture/IME/global Tools |
| `pi-system-skill-android/src/main/.../AccessibilityScreenAutomationBackend.kt` | AccessibilityService 默认 backend、snapshot/target 校验和 fail-closed 恢复 |
| `pi-system-skill-android/src/main/.../ScreenAutomationSkills.kt` | Android Accessibility 屏幕自动化 Skill 与确认/验证策略 |
| `ApiSettings.kt` | API 源配置 + SharedPreferences 持久化 + Ui 色彩工具 |

需要权限：无障碍服务 + SYSTEM_ALERT_WINDOW（悬浮窗）
