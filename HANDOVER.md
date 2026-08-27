# awesome-ugk-agent-android 项目交接文档 (Handover Document)

> **最新保存版本**：`0.5.0` (`versionCode 6`，通用 Android 定时任务)
> **最近已保存标签**：`demo-app-v0.5.0`（已同步 `origin`）
> **分支**：`main`  
> **交接时间**：2026-08-27  
> **目标真机**：小米 15（序列号 `QSG6Q8IFDMDELVGQ`，型号 `2602BRT18C`，Android 15）和第二台小米（序列号 `e0b93f2f`，型号 `2304FPN6DC`）
> **注意**：设备列表中若有三星手机（`R5CRB11B2AW`），**严禁对其执行任何操作**，唯一下发与调试设备为小米手机。

---

## 1. 项目概况与模块架构

本项目是专为 Android 设计的通用 AI Agent 运行时（Agent Runtime SDK）与原生自动化演示应用，基于纯代码构建 UI（无 XML layout），支持多轮大模型推理循环、工具调用（Tool Calls）、无障碍跨 App 屏幕自动化操控、终端 Runtime 基础设施与多模态视觉交互。

### 模块结构与依赖关系

```
:ugk-pi-android              — Agent Runtime 核心（AgentRuntime, AgentSession, AgentTool, LLMProvider, AndroidSkill）
:pi-file-skill-android       — 应用私有文件工具 skill
:pi-schedule-skill-android   — 定时任务 skill
:ugk-agent-task-runtime-android — Android 定时任务持久化、AlarmManager/JobScheduler 与通知运行时
:pi-system-skill-android     — 系统设置 / 权限 / Intent skill / 屏幕自动化工具下沉
:ugk-terminal-runtime-android — 无 UI 原生终端 Runtime 基础设施 (C++/NDK + Bash/curl/CPython)
:pi-terminal-skill-android  — Bash Agent Tool（默认逐次用户确认）
:demo-app                    — 无障碍屏幕操控与现代对话交互 App（包名 com.ugk.pi.android.testapp）
:terminal-probe-demo-a/b     — Runtime 可重定位验证 app（不同 applicationId）
```

**依赖流向**：`demo-app` -> `ugk-pi-android`, `pi-*`

---

## 2. 最近完成的核心优化与交付成果 (0.5.0)

### ① 多模态视觉识图交互体系
1. **双输入源接入**：
   - 拍照：动态权限申请（`CAMERA`）+ `FileProvider` 临时文件共享；
   - 相册：标准 `Intent.ACTION_GET_CONTENT`，免存储权限全版本兼容。
2. **极速低延迟图像处理 (`DemoImageUtils.kt`)**：
   - 原图自适应降采样（限制长边 1280px）+ `ExifInterface` 自动纠偏拍照旋转角度（90°/180°/270°）+ 优质 JPEG 压缩（~200KB），兼顾辨识准确率与毫秒级网络传输。
3. **沉浸式交互 UI**：
   - 输入框附件选单（拍照 / 相册 / 文件导入）；
   - 待发送缩略图卡片（右上角关闭按钮，联动点亮发送按钮，支持免文字直接发图）；
   - 沉浸式全屏大图查看器（点击气泡缩略图放大、全屏浏览、任意位置点击退出）。
4. **SDK 核心协议多模态升级**：
   - 扩展 `AgentImageContent`、`AgentMessage.User`、`AgentRunInput`；
   - 双端点协议全兼容：Anthropic 标准 Base64 `image` 块与 OpenAI 标准 `image_url` 块；在智谱 GLM-5.3-Flash 视觉端点实测通过。

### ② 原生卡片式 Markdown 表格组件 (`DemoTableView`) 与流式防抖
1. **根治跳动与闪烁根因**：
   - 查明 Markwon 官方 `TablePlugin` 基于 `TableRowSpan`（`ReplacementSpan`），因测量期未知宽度返回默认行高，绘制期异步触发 `view.post(setText)` 引起二次排版，在流式高频刷新中产生“高度0 -> 变高 -> post 重排”的恶性抖动死循环。
2. **架构重构升级**：
   - 提拔表格为顶层独立内容块 `DemoContentBlock.Table`（与独立代码块保持一致）；
   - 实现原生卡片表格视图 `DemoTableView`：
     - 外层 `HorizontalScrollView`（`isFillViewport = true`）：支持超宽多列表格横向平滑滑动，单元格文字不再换行过度堆叠；
     - 内部 `TableLayout`（`isStretchAllColumns = true`）：少列时自动等比拉伸平铺充满整张卡片；
     - 单元格采用原生 `TextView`（12sp 精致字体），支持行内 Markdown 加粗与行内代码；
     - 深度适配浅色（米白柔和底色 `#FAF9F6`）与深色（纯净深碳灰 `#232220`），搭配极细分割线与交替斑马纹；
   - **绝杀流式抖动**：表格是独立的 Android 原生 View，大模型在流式输出表格后续正文时，表格卡片**完全静止，零重绘、零测量震颤**。

### ③ 独立代码块横向滑动卡片 (`DemoCodeBlockView`)
- 独立卡片化展示，支持长行代码横向平滑滚动（不自动换行破坏代码排版结构）；
- 配备语言标签芯片与一键复制代码按钮，带复制成功微反馈。

### ④ 全方位美学与双主题重构
- **浅色主题**：米白暖灰质感基调（`#FBFBF9` / `#FAF9F6`），主色调选用热情鲜明的柿橙红，辅助以清爽淡绿状态点缀；
- **深色主题**：高级纯净深碳灰（`#1C1B1A` / `#232220`），彻底摒弃带有绿色的暗色调；
- **排版与细节**：全局精致清晰的 Medium 字体排版与舒适呼吸感间距，彻底修复系统通知栏、输入框、气泡、代码块等在深色模式下的反色与异常文字背景。

### ⑤ 64ms 节流流式调度器与 Markdown 语法容错
- 主界面采用 64ms 节流流式调度器，避免高频逐字更新引发的系统渲染过载；
- Markdown 未闭合语法（未闭合的反引号、粗斜体、列表等）流式自动稳定补全。

### ⑥ SDK 屏幕自动化下沉与统一策略
- 屏幕 UI 树读取、selector 查找、节点动作、手势与全局动作下沉到 `pi-system-skill-android` 的统一 Skill/Plugin；
- demo 通过 `AccessibilityScreenAutomationBackend` 注入当前无障碍服务；
- 统一全授权确认策略 `AgentConfirmationPolicy`。

### ⑦ 0.4.0：视觉屏幕兜底
- 当无障碍 UI 树无法暴露可靠目标时，新增 `screen_capture_visual` 截取当前屏幕，并通过 `ToolResult` 的短暂多模态附件传给紧邻的下一次模型请求；截图不写入持久化会话记录。
- 模型返回 `0..1` 归一化目标区域后，新增 `screen_visual_gesture` 使用最新 `observationId` 执行 tap、long press 或方向 swipe；后端校验 15 秒有效期、前台包名、屏幕尺寸、旋转和边界。
- 视觉截图和视觉手势均走精确输入确认；默认图片长边限制 1280、JPEG quality 80。Android API 30 以下返回不支持，受保护/DRM/动态画面仍可能不可用。
- demo 的无障碍服务配置已加入 `android:canTakeScreenshot="true"`。宿主若使用默认后端，也必须在自己的 service XML 中声明该能力。
- 视觉兜底已纳入 `0.4.0` 版本：`versionName=0.4.0`、`versionCode=5`，标签为 `demo-app-v0.4.0`；本次版本同步已补齐远端标签。

### ⑧ 0.4.0：Android 文本剪贴板 Tool/Skill
- `pi-system-skill-android` 新增 `clipboard_read_text`、`clipboard_write_text`、`clipboard_clear`，由现有 `AndroidSystemAgentPlugin` 和 `AndroidAutomationAgentPlugin` 自动注册，不新增独立插件。
- 按 Android 10（API 29）设计，模块 `minSdk` 仍为 24；API 28 及以下返回 `CLIPBOARD_UNSUPPORTED`。第一版仅暴露第一个纯文本剪贴板项，不处理图片和 URI。
- 读取原文只通过 `ToolResult.transientModelContent` 传给紧邻的下一次模型请求，持久化 `AgentSession`、`AgentEvent.ToolFinished` 和 demo 过程摘要只保留元数据；无焦点读取失败返回 `CLIPBOARD_READ_UNAVAILABLE`，不伪报为空。
- 读取、写入、清空默认均使用现有精确确认票据；写入默认设置 `sensitive=true`，写入/清空成功只表示 Android 接受请求，不代表目标 App 已粘贴或消费。
- 剪贴板能力已纳入 `0.4.0` 版本；与视觉兜底共同使用标签 `demo-app-v0.4.0`，本次版本同步已补齐远端标签。

### ⑨ 0.5.0 已保存：通用 Android 定时任务运行时

- `pi-schedule-skill-android` 继续只负责任务模型和 `agent_task_*` 控制 Tool；`ugk-agent-task-runtime-android` 负责 Android 持久化、通知任务的 `AlarmManager`、Prompt 任务的 `JobScheduler`、广播恢复和通知投递。
- Demo 已注册定时任务插件。`NOTIFY_USER` 仍用于纯提醒；`RUN_AGENT_PROMPT` 到点后由 `AgentTaskJobService` 创建无 UI 的宿主 Runtime，恢复 `sessionId` 对应会话，调用 `AgentRuntime` 完成一轮模型/Tool 执行，并把任务和结果追加回该会话。
- 前后台共用 `DemoAgentRuntimeFactory` 的 Provider、无障碍屏幕、视觉、剪贴板和终端能力图；后台使用无 UI 的确认 Presenter。无交互确认时，受保护动作只有显式开启全授权才会执行，默认安全失败。
- 使用普通非精确 `AlarmManager` 和系统 `JobScheduler`，不申请精确闹钟权限、不启动 Bash 常驻循环；Prompt 任务需要系统提供网络条件；Android 13+ 需要 `POST_NOTIFICATIONS`。
- 当前保存版本为 `versionCode 6 / versionName 0.5.0`；后台 Prompt 代码已完成 JVM/编译验证，并在第二台小米 `e0b93f2f`（`2304FPN6DC`）完成安装启动和一次性 `RUN_AGENT_PROMPT` 后台唤醒/读屏体验验收，用户反馈可用。
- 版本标签为 `demo-app-v0.5.0`，与 `main` 提交一并同步到 `origin`；主目标小米 `QSG6Q8IFDMDELVGQ` 本轮离线，三星设备 `R5CRB11B2AW` 未操作。

---

## 3. 关键文件索引与架构分布

| 模块 | 核心文件 | 职责说明 |
|---|---|---|
| `:demo-app` | `DemoTableView.kt` | 原生卡片式 Markdown 表格组件（横向滑动、自适应平铺、单元格富文本） |
| `:demo-app` | `DemoCodeBlockView.kt` | 原生代码块卡片（横向滚动、复制按钮）与 `DemoContentBlock` 分块解析器 |
| `:demo-app` | `DemoImageUtils.kt` | 拍照/相册图片采集、降采样压缩、Exif 旋转纠偏、Base64 转换 |
| `:demo-app` | `DemoChatViews.kt` | 消息气泡复合渲染（Text / Code / Table / Image 卡片挂载与大图查看器） |
| `:demo-app` | `DemoMarkdownFormatter.kt` | Markwon 构建配置、流式文本预处理与行内富文本解析 |
| `:demo-app` | `MainActivity.kt` | 聊天主界面、流式节流调度器（64ms）、附件菜单、主题切换与生命周期管理 |
| `:demo-app` | `ThemeManager.kt` / `ApiSettings.kt` | 双主题管理（米白暖灰 / 纯净深碳灰）与多 API 源配置 |
| `:ugk-pi-android` | `AgentImageContent.kt` | 多模态图片核心数据结构（Base64 + MimeType） |
| `:ugk-pi-android` | `AnthropicMessagesProvider.kt` | Anthropic 标准协议提供者（支持多模态 image 块与 baseUrl 自定义） |
| `:ugk-pi-android` | `OpenAiChatCompletionsProvider.kt` | OpenAI 兼容协议提供者（支持 image_url 与 baseUrl 自定义） |
| `:ugk-pi-android` | `AgentConfirmationPolicy.kt` | 全授权模式跳过确认策略与工具调用安全边界 |
| `:ugk-pi-android` | `Tool.kt` / `AgentRuntime.kt` | 临时敏感 Tool 文本只发送到下一次模型请求，不进入持久化会话 |
| `:pi-system-skill-android` | `ScreenAutomationTools.kt` | SDK 统一的屏幕读/查/动作/视觉观察/视觉手势/IME/全局 Tools |
| `:pi-system-skill-android` | `AccessibilityScreenAutomationBackend.kt` | 无障碍服务后端、快照校验、截图编码、视觉观察 freshness 与节点生命周期安全回收 |
| `:pi-system-skill-android` | `AndroidClipboardTools.kt` | Android 10+ 文本剪贴板后端与三个系统 Tool |
| `:pi-system-skill-android` | `AndroidSystemSkills.kt` | 剪贴板 Skill、确认和系统限制说明 |
| `:ugk-agent-task-runtime-android` | `AndroidAgentTaskRuntime.kt` | Android 定时任务 Store、AlarmManager/JobScheduler、JobService、广播恢复、通知 Sink 与宿主 Prompt 执行扩展点 |

---

## 4. 验证与质量基线

1. **自动化测试**：
   - 全工程 186 个单元测试全部通过：
     `.\gradlew.bat testDebugUnitTest --console=plain` （SUCCESS）
   - 覆盖多模态数据编解码、表格分块与结构化解析、屏幕自动化策略、文件导入与会话管理。
2. **真机部署与验证（小米 15 · `QSG6Q8IFDMDELVGQ`）**：
   - 运行态包信息验证：
     `adb -s QSG6Q8IFDMDELVGQ shell dumpsys package com.ugk.pi.android.testapp | Select-String "versionCode", "versionName"`
     -> **`versionCode=4 / versionName=0.3.0`**。
   - 真机多模态实测：拍照识图、相册选图、大图查看器、智谱 GLM-5.3-Flash 视觉结构化分析全链路畅通。
   - 真机表格实测：包含 8 行 3 列的水果营养表格流式输出全过程平稳顺滑，无跳动与闪烁，横向滑动正常，深浅色模式切换完美适配。

2. **本次视觉兜底实现验证（未发布）**：
   - 全量 JVM 单元测试通过；`demo-app` Debug APK 构建通过；`demo-app` 仪器测试 Kotlin 源码编译通过。
   - Debug APK 已安装到两台授权小米设备 `QSG6Q8IFDMDELVGQ` 和 `e0b93f2f`；视觉兜底尚未完成真实跨应用截图、模型识别和坐标点击验收。后续 ADB 仍只能显式指定这两台小米设备，不能触碰三星设备。

3. **剪贴板 Tool/Skill 验证（0.4.0）**：
   - 全工程 JVM 单元测试通过，共 186 个测试、0 个失败；`demo-app` Debug APK 构建和仪器测试 Kotlin 源码编译通过。
   - 包含剪贴板能力的 Debug APK 已安装并启动于在线小米 `e0b93f2f`（`2304FPN6DC`），用户完成体验验证并反馈正常；主目标小米 `QSG6Q8IFDMDELVGQ` 当时不在线。`0.4.0` 版本仅在本地保存，未推送远端。

---

4. **定时任务已保存版本（0.5.0）**：
   - 后台 Prompt 执行链路已接入：`JobScheduler -> AgentTaskJobService -> DemoScheduledTaskPromptExecutor -> AgentRuntime`；前后台共用 Demo Runtime 工厂，会话结果持久化回同一会话。
   - 全工程 JVM 单元测试共 198 个、0 个失败；`:demo-app:assembleDebug` 已通过；APK 元数据为 `versionCode 6 / versionName 0.5.0`，合并 Manifest 已确认 `AgentTaskJobService`。
   - APK 已安装并启动到 `e0b93f2f`（`2304FPN6DC`），用户已完成 `RUN_AGENT_PROMPT` 后台唤醒/读屏体验测试并反馈可用；任务运行仍受网络、Doze、小米省电策略、无障碍连接和前台目标页面影响。
   - 后续 ADB 只允许显式指定两台小米设备 `QSG6Q8IFDMDELVGQ` 或 `e0b93f2f`，严禁操作三星 `R5CRB11B2AW`。

## 5. 常用开发与调试命令速查

```powershell
# 1. 运行全工程单元测试
.\gradlew.bat testDebugUnitTest --console=plain

# 2. 构建 demo-app Debug APK
.\gradlew.bat :demo-app:assembleDebug --console=plain

# 3. 覆盖安装并启动应用至在线授权小米（示例使用第二台小米 e0b93f2f）
adb -s e0b93f2f install -r -d D:\AII\ugk-android\demo-app\build\outputs\apk\debug\demo-app-debug.apk
adb -s e0b93f2f shell am start -n com.ugk.pi.android.testapp/.MainActivity

# 4. 截取真机屏幕并拉取查看
adb -s QSG6Q8IFDMDELVGQ shell screencap -p /sdcard/screen_debug.png
adb -s QSG6Q8IFDMDELVGQ pull /sdcard/screen_debug.png C:\Users\shengk\.gemini\antigravity\brain\1a385ecb-7d18-468a-a8ee-3fd6a11f5328\screen_debug.png
```

---

## 6. 新会话接手注意事项

1. **语言规则**：严格使用**简体中文**进行技术解释、代码注释与对话交互。
2. **设备边界**：ADB 命令必须显式带上 `-s QSG6Q8IFDMDELVGQ` 或 `-s e0b93f2f`，严禁操作三星手机（`R5CRB11B2AW`）。
3. **两类 AGENTS.md 区分**：
   - 根目录 `AGENTS.md`：仅供开发者/助手协作，不打包进 APK。
   - 运行时 `pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md`：随 APK 打包，作为模型全局环境提示词。
4. **UI 构建原则**：项目纯代码构建界面（无 XML layout），增改视图时保持代码构建风格与 `ThemeManager` 动态调色规范。
