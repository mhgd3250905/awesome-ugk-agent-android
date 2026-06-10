# awesome-ugk-agent-android

Android Agent Runtime SDK — 通用 AI Agent 工具循环框架，附带无障碍屏幕操控 demo。

## 模块

```
:ugk-pi-android              — Agent Runtime 核心（AgentRuntime, AgentSession, AgentTool, LLMProvider, AndroidSkill）
:pi-file-skill-android       — 应用私有文件工具 skill
:pi-schedule-skill-android   — 定时任务 skill
:pi-system-skill-android     — 系统设置 / 权限 / Intent skill
:demo-app                    — 无障碍屏幕操控 demo（包名 com.ugk.pi.android.testapp）
```

依赖方向：demo-app -> ugk-pi-android, pi-*

## 构建命令

```bash
# 构建 demo
./gradlew :demo-app:assembleDebug --console=plain

# 跑全部单元测试
./gradlew :ugk-pi-android:testDebugUnitTest :pi-file-skill-android:testDebugUnitTest :pi-schedule-skill-android:testDebugUnitTest :pi-system-skill-android:testDebugUnitTest --console=plain

# 发布到本地 Maven（供外部项目消费）
./gradlew :ugk-pi-android:publishReleasePublicationToMavenLocal
```

外部项目通过 mavenLocal 消费：
```kotlin
implementation("com.ugk.pi:ugk-pi-android:0.1.0")
```

## 技术栈

Kotlin 1.8.22, AGP 8.3.2, Java 17, compileSdk 34, minSdk 23, Gradle 8.4
Kotlin Serialization（非 Gson/Moshi）
JUnit 4 + kotlinx-coroutines-test

## SDK 架构

- `AgentRuntime` 运行循环：用户消息 -> LLM -> ToolCall -> AgentTool -> ToolResult -> LLM -> 最终回答
- `AgentCapabilityPlugin` 是工具+技能的注册入口（`tools()` + `skills()`）
- `AndroidSkill` 是只读上下文包，不创建工具、不授权；skill method 仅在同名 tool 已注册时才注入
- `AnthropicMessagesProvider` / `OpenAiChatCompletionsProvider` 支持 baseUrl 自定义
- 高影响工具通过 `UserConfirmationRequiredTool` 包装，必须先调用 `show_user_confirmation_dialog`

## demo-app

无障碍屏幕操控 Agent，UI 全部代码构建（无 XML layout），功能：

| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | 主界面：对话 UI + AgentRuntime 构建 + skill instructions |
| `AgentAccessibilityService.kt` | 无障碍服务，静态 `instance` 给 Tool 使用 |
| `AgentFloatingWindow.kt` | 可拖动悬浮窗，Agent 运行时叠加在任意 app 上 |
| `ScreenReadUiTreeTool.kt` | 读 UI 树（跳过自身 overlay，最多 200 node） |
| `ScreenPerformActionTool.kt` | nodeId 粒度操作：click/scroll/set_text |
| `ScreenLaunchAppTool.kt` | 按 package name 启动 app |
| `ScreenGlobalActionTool.kt` | 系统动作：back/home/recents/notifications |
| `ScreenGestureTool.kt` | 坐标手势：tap/long_press/swipe（UI 树被屏蔽时用） |
| `ScreenPressKeyTool.kt` | 触发 IME action（回车/搜索/发送），API 30+ 用 ACTION_IME_ACTION |
| `ApiSettings.kt` | API 源配置 + SharedPreferences 持久化 + Ui 色彩工具 |

需要权限：无障碍服务 + SYSTEM_ALERT_WINDOW（悬浮窗）
