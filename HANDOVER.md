# awesome-ugk-agent-android 项目交接文档 (Handover Document)

> **Demo 0.9.0 版本**：`versionCode 11`（文件型 skill 单文件 authoring MVP；保留 0.8.0 UI 基线）
> **0.9.0 版本边界**：标签 `demo-app-v0.9.0`（远端状态以 Git 实测为准）
> **上一保存版本**：`0.8.0 / versionCode 10`，本地标签 `demo-app-v0.8.0`
> **架构整改实现 checkpoint**：`9268bc2`
> **阶段 8 文档/验证 closeout**：`885c1e9`
> **微信式对话视觉实现 checkpoint**：`af5b0b7075dd8a201dbfd857987521f7b0d3470a`
> **首页顶栏收束 checkpoint**：`cde30bf9d12d262fce5141986a77230cbf6ff7b6`
> **分支**：`main`  
> **交接时间**：2026-08-30
> **工作区**：`E:\AII\ugk-android-new`
> **当前版本标签**：`demo-app-v0.9.0`（远端状态以 Git 实测为准）
> **目标真机**：小米设备 `QSG6Q8IFDMDELVGQ`、第二台小米 `e0b93f2f`（型号 `2304FPN6DC`）
> **注意**：设备列表中若有三星手机（`R5CRB11B2AW`），**严禁对其执行任何操作**，唯一下发与调试设备为小米手机。
>
> **设备记录说明**：`docs/demo-app-version-ledger.md` 的 v0.6.0 记录将 `QSG6Q8IFDMDELVGQ` 标为 REDMI Turbo 5 Max / Android 16；历史 0.2/0.3 记录曾写作小米 15 / `2602BRT18C` / Android 15。后续真机操作只按授权序列号选择，并在操作前重新核对型号，不按旧型号文字盲选。

---

## 0. 2026-08-29—30 架构与视觉收束（当前事实）

- 本阶段在 `demo-app-v0.8.0` / `79b0d31` 基线上完成文件型 skill 单文件 authoring MVP，版本元数据为 `0.9.0 / versionCode 11`；版本边界标签为 `demo-app-v0.9.0`，远端状态以 Git 实测为准。
- MVP 提供结构化 `skill_save`、`skill_delete`、完整 manifest `skill_read` 和内置 indexed `android-skill-creator` SOP，形成 create/update/delete/query/use 闭环；普通 `docs/*.md` 仍只是原料。完成声明必须同时有 `skill_save` 成功、`skill_list` 为 `valid` 和 `skill_read` manifest/body 核对。
- `skill_save` 固定写入 repository 直接子目录，默认拒绝覆盖；`skill_delete` 只接收合法 name；路径/格式/写后校验/回滚、用户确认和 `agent-memory`/`android-skill-creator` protected-skill 规则均保持 fail-closed。supporting resources、脚本/资产执行和 UI 管理不在本 MVP。
- 审查事实：前置独立 review 发现的 frontmatter 注入 P1 已修复，精确复查为 `CLOSED / PASS`；本阶段独立 closeout review 已 `PASS`。
- 最终门禁已通过：八个 SDK/Runtime 模块合计 `279/279`（Core 122、File 9、Schedule 9、Task Runtime 7、System 42、Agent Skill Runtime 70、Terminal Runtime 0 `NO-SOURCE`、Terminal Skill 20），Demo `107/107`，总计 `386/386`，0 failure/error/skipped；Demo assemble 与 AndroidTest Kotlin compile 通过。APK metadata 为 `versionCode 11 / versionName 0.9.0`，包含 `assets/agent-skills/android-skill-creator/SKILL.md`，`git diff --check` 通过。
- 设备事实勘误：功能代码的 `0.8.0 / versionCode 10` Debug APK 已通过 `adb install -r -d` 覆盖安装到授权小米 `QSG6Q8IFDMDELVGQ`（未卸载、未清理数据），当时只核对安装返回和 package metadata；`0.9.0 / versionCode 11` 本阶段不安装，不把设备安装成功描述为 skill authoring 行为验收。
- 尚未完成真实 Agent 的人工 create/update/delete/use end-to-end 场景，未调用真实 Provider/API；该边界必须随 0.9.0 版本记录保留。
- 快速迭代后的模块化审查已完成计划中的 7 个实现阶段，并形成 9 个本地 checkpoint：Runtime 生命周期、设置领域/UI、上下文档位、Provider profile、进程/悬浮窗 ownership、conversation runtime、Session transcript、capability assembly、Terminal/Screen host interlock 均已形成明确单一 owner。
- 架构整改实现 checkpoint 顺序为 `1409610`、`ccc76c9`、`20b2b60`、`f20bee7`、`1003cc1`、`74dd2ff`、`47964b5`、`9956116`、`9268bc2`，阶段 8 文档/验证收束为 `885c1e9`；随后按 patch 规则保存 `0.7.1 / versionCode 9`，该保存点不代表远程 PR 或 Release。
- 2026-08-29 本机收束：八模块 JVM `271/271`、Demo JVM `104/104`；Demo Debug、五个关键 Release AAR、两个 Probe Release APK、Terminal Runtime 静态/哈希/AAR/APK/zipalign 验收均通过。
- `verify-runtime.ps1` 修复了 `zh-CN` 下文化相关文件名排序造成的 CPython extension tree 哈希误报；改为 `StringComparer.Ordinal`，没有改动二进制或锁文件。
- 当前 Core AAR inventory 为 122 class、82 个审查口径 source-facing public type、800 个 `javap` public member。D-023/D-024 包含明确的 `0.x` source/semantic change；没有可信旧发布 AAR和升级 consumer，不能宣称完整 API/ABI 兼容。
- 阶段 8 的本机静态收束没有执行 ADB、instrumentation、真实网络或 Provider/API；其后的人工真机体验见下一条。arm64 16KB、完整设备矩阵、Release AAB/split、升级、低资源、性能和许可证仍是发布 Gate。
- 2026-08-29 已将 `885c1e9` 对应代码构建为 `0.7.0 / versionCode 8`，安装到 `QSG6Q8IFDMDELVGQ`（`2602BRT18C`）并启动；用户反馈该轮测试无明显问题。因旧设备上的 `0.6.0` 使用不同签名，本次按用户明确授权先卸载再安装，旧 App 本地数据已清除。`0.7.1` 只改变版本元数据和文档，本轮不重复设备验证。
- `0.7.1 / versionCode 9` 更新后全工程 JVM XML `375/375`、`:demo-app:assembleDebug` 与 APK metadata 检查通过；版本保存点和标签构成版本边界，不代表远程 Release。
- 2026-08-29 微信式对话视觉重构已在实现 checkpoint `af5b0b7075dd8a201dbfd857987521f7b0d3470a` 保存：用户消息右侧主题绿、AI 回复左侧 Light 白/Dark 深灰中性表面，顶栏/composer、过程证据、设置页、悬浮窗和组件状态统一使用 Light/Dark 语义 Token；launcher 保持原品牌猫头鹰，透明变体用于 App 内助手头像、空状态和悬浮窗入口。
- 该视觉 checkpoint 不升版本：当前实际安装并启动的 Debug APK 仍为 `0.7.1 / versionCode 9`，通过 `adb install -r -d` 覆盖安装至授权小米 `QSG6Q8IFDMDELVGQ`，未卸载、未清理数据，启动进程未见 `FATAL`。
- 当前视觉阶段验证：`:demo-app:compileDebugKotlin`、`:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 通过；`:demo-app:testDebugUnitTest` XML `107/107`，0 failure/error/skipped；`git diff --check` 通过。
- 主会话已查看并验收 Light/Dark 主聊天、Settings Dark、悬浮窗折叠/展开第二轮截图，确认用户绿、AI 中性、透明猫头鹰、无框顶栏与 composer、Markdown 和 disabled send 符合基线。截图在用户 Temp，未复制入仓库。
- 2026-08-29 首页顶栏收束 checkpoint `cde30bf9d12d262fce5141986a77230cbf6ff7b6` 已移除首页快捷主题切换，主题选择仍保留在设置页；异常设置图标已替换为标准实心齿轮。与最终生产代码一致、元数据仍为 `0.7.1 / versionCode 9` 的 APK 已覆盖安装至 `QSG6Q8IFDMDELVGQ` 并通过真机截图与设置入口验收，未卸载、未清理数据。
- 2026-08-30 将上述完整用户可感知视觉阶段保存为 `0.8.0 / versionCode 10`，本地标签为 `demo-app-v0.8.0`；版本元数据保存不重复安装、不 push、不创建 PR 或远程 Release。
- `0.8.0` 收束验证：`:demo-app:compileDebugKotlin`、`:demo-app:testDebugUnitTest`、`:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 通过，Demo JVM XML `107/107`，APK metadata 为 `versionCode 10 / versionName 0.8.0`，`git diff --check` 通过。
- 本阶段未运行 `connectedDebugAndroidTest`，因其可能安装/清理测试目标包并影响现有数据/API 设置；未调用真实 Provider/API。该边界不能被描述为自动化或真实网络通过。
- 当前文档入口与规范清单以 `docs/README.md` 为准；版本以 `docs/demo-app-version-ledger.md`，架构整改以 `docs/sdk-optimization-ledger.md`，Terminal Gate 以 `docs/terminal-runtime-baseline.md`、`docs/terminal-runtime-validation.md` 和 `docs/terminal-runtime-decisions.md` 为准。`sdk-stabilization-baseline.md` 是已 superseded 的历史快照。下文的功能和验证记录均为历史累计说明；遇到提交、测试数或路径冲突时，以本节和上述当前事实源为准。

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
:pi-agent-skill-runtime-android — 文件型 SKILL.md 运行时与 agent-memory 记忆 skill
:ugk-terminal-runtime-android — 无 UI 原生终端 Runtime 基础设施 (C++/NDK + Bash/curl/CPython)
:pi-terminal-skill-android  — Bash Agent Tool（默认逐次用户确认）
:demo-app                    — 无障碍屏幕操控与现代对话交互 App（包名 com.ugk.pi.android.testapp）
:terminal-probe-demo-a/b     — Runtime 可重定位验证 app（不同 applicationId）
```

**依赖流向**：`demo-app` -> `ugk-pi-android`, `pi-*`

---

## 2. 历史累计交付记录（截至 0.6.0）

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
- 当时保存版本为 `versionCode 6 / versionName 0.5.0`；后台 Prompt 代码已完成 JVM/编译验证，并在第二台小米 `e0b93f2f`（`2304FPN6DC`）完成安装启动和一次性 `RUN_AGENT_PROMPT` 后台唤醒/读屏体验验收，用户反馈可用。
- 版本标签为 `demo-app-v0.5.0`，与 `main` 提交一并同步到 `origin`；主目标小米 `QSG6Q8IFDMDELVGQ` 本轮离线，三星设备 `R5CRB11B2AW` 未操作。

### ⑩ 0.6.0 已保存：文件型 Skill Runtime 与 agent-memory

- 新增独立模块 `pi-agent-skill-runtime-android`：实时扫描 App 私有目录中的 `SKILL.md`，手写扁平 frontmatter 解析，支持 `always`、`indexed`、`triggered` 三级加载策略。
- 新增 `skill_list`、`skill_read` 和 `memory_list/read/write/delete`；Skill 支持 `x-ugk-embed-files` 命名根实时嵌入，记忆限定 `user-profile`、`preferences`、`facts`、`rules` 四类，单文件上限 16KB。
- 预制 `agent-memory` 使用 `always` 策略；`preferences`/`rules` 每次 Skill 注入时从 `memory:` 命名根实时读取，写入和删除遵循明确同意及现有确认策略。
- `AgentRuntime.Builder.skillProvider()` 改为持有 Provider 并在每次 run 拉取，允许同一 Runtime 在下一轮读取新 Skill/新记忆；Demo 前后台共用 `DemoAgentRuntimeFactory` 接线。
- 当时 `demo-app` 为 `versionCode 7 / versionName 0.6.0`，标签 `demo-app-v0.6.0` 已同步到 `origin`。该条记录中的远端状态仅适用于当时。

---

## 3. 关键文件索引与架构分布

| 模块 | 核心文件 | 职责说明 |
|---|---|---|
| `:demo-app` | `DemoTableView.kt` | 原生卡片式 Markdown 表格组件（横向滑动、自适应平铺、单元格富文本） |
| `:demo-app` | `DemoCodeBlockView.kt` | 原生代码块卡片（横向滚动、复制按钮）与 `DemoContentBlock` 分块解析器 |
| `:demo-app` | `DemoImageUtils.kt` | 拍照/相册图片采集、降采样压缩、Exif 旋转纠偏、Base64 转换 |
| `:demo-app` | `DemoChatViews.kt` | 消息气泡复合渲染（Text / Code / Table / Image 卡片挂载与大图查看器） |
| `:demo-app` | `DemoMarkdownFormatter.kt` | Markwon 构建配置、流式文本预处理与行内富文本解析 |
| `:demo-app` | `MainActivity.kt` | 聊天主界面、流式节流调度器（64ms）、附件菜单、主题同步与生命周期管理 |
| `:demo-app` | `ThemeManager.kt` / `Ui.kt` | 双主题管理（米白暖灰 / 纯净深碳灰）、动态主题色彩 Token 与 UI 样式辅助 |
| `:demo-app` | `ApiSettings.kt` | 多 API 源配置与 SharedPreferences 持久化 |
| `:ugk-pi-android` | `AgentImageContent.kt` | 多模态图片核心数据结构（Base64 + MimeType） |
| `:ugk-pi-android` | `AnthropicMessagesProvider.kt` | Anthropic 标准协议提供者（支持多模态 image 块与 baseUrl 自定义） |
| `:ugk-pi-android` | `OpenAiChatCompletionsProvider.kt` | OpenAI 兼容协议提供者（支持 image_url 与 baseUrl 自定义） |
| `:ugk-pi-android` | `AgentConfirmationPolicy.kt` | 全授权模式跳过确认策略与工具调用安全边界 |
| `:ugk-pi-android` | `Tool.kt` / `AgentRuntime.kt` | 临时敏感 Tool 文本只发送到下一次模型请求，不进入持久化会话 |
| `:pi-system-skill-android` | `ScreenAutomationTools.kt` | SDK 统一的屏幕读/查/动作/视觉观察/视觉手势/IME/全局 Tools |
| `:pi-system-skill-android` | `AccessibilityScreenAutomationBackend.kt` | 无障碍服务后端、快照校验、截图编码、视觉观察 freshness 与节点生命周期安全回收 |
| `:pi-system-skill-android` | `AndroidClipboardTools.kt` | Android 10+ 文本剪贴板后端与三个系统 Tool |
| `:pi-system-skill-android` | `AndroidSystemSkills.kt` | 剪贴板 Skill、确认和系统限制说明 |
| `:pi-agent-skill-runtime-android` | `AgentSkillRuntimePlugin.kt` / `AgentSkillTools.kt` | 文件型 Skill 与 agent-memory 工具、确认策略 |
| `:pi-agent-skill-runtime-android` | `SkillRepository.kt` / `SkillManifest.kt` / `FileBackedSkillProvider.kt` | SKILL.md 扫描解析、命名根嵌入与动态 Skill 注入 |
| `:ugk-agent-task-runtime-android` | `AndroidAgentTaskRuntime.kt` | Android 定时任务 Store、AlarmManager/JobScheduler、JobService、广播恢复、通知 Sink 与宿主 Prompt 执行扩展点 |

---

## 4. 历史验证记录索引（非当前状态）

> 本节保留 0.3.0—0.7.0 的累计验证记录；当前版本、测试数和 Gate 结论不从本节读取，以第 0 节和当前事实源为准。

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

3. **本次视觉兜底实现验证（未发布）**：
   - 全量 JVM 单元测试通过；`demo-app` Debug APK 构建通过；`demo-app` 仪器测试 Kotlin 源码编译通过。
   - Debug APK 已安装到两台授权小米设备 `QSG6Q8IFDMDELVGQ` 和 `e0b93f2f`；视觉兜底尚未完成真实跨应用截图、模型识别和坐标点击验收。后续 ADB 仍只能显式指定这两台小米设备，不能触碰三星设备。

4. **剪贴板 Tool/Skill 验证（0.4.0）**：
   - 全工程 JVM 单元测试通过，共 186 个测试、0 个失败；`demo-app` Debug APK 构建和仪器测试 Kotlin 源码编译通过。
   - 包含剪贴板能力的 Debug APK 已安装并启动于在线小米 `e0b93f2f`（`2304FPN6DC`），用户完成体验验证并反馈正常；主目标小米 `QSG6Q8IFDMDELVGQ` 当时不在线。`0.4.0` 标签已同步远端。

---

5. **定时任务已保存版本（0.5.0）**：
   - 后台 Prompt 执行链路已接入：`JobScheduler -> AgentTaskJobService -> DemoScheduledTaskPromptExecutor -> AgentRuntime`；前后台共用 Demo Runtime 工厂，会话结果持久化回同一会话。
   - 全工程 JVM 单元测试共 198 个、0 个失败；`:demo-app:assembleDebug` 已通过；APK 元数据为 `versionCode 6 / versionName 0.5.0`，合并 Manifest 已确认 `AgentTaskJobService`。
   - APK 已安装并启动到 `e0b93f2f`（`2304FPN6DC`），用户已完成 `RUN_AGENT_PROMPT` 后台唤醒/读屏体验测试并反馈可用；任务运行仍受网络、Doze、小米省电策略、无障碍连接和前台目标页面影响。
   - 后续 ADB 只允许显式指定两台小米设备 `QSG6Q8IFDMDELVGQ` 或 `e0b93f2f`，严禁操作三星 `R5CRB11B2AW`。

6. **文件型 Skill Runtime 已保存版本（0.6.0）**：
   - 远端 `6ce0d72` 完成实现，`3c61582` 完成阶段文档收尾；本地已 fast-forward 到 `3c61582`，`main` 与 `origin/main` 一致。
   - 当时接手复核：全工程 JVM 测试 XML 共 258 个，0 failures、0 errors；`:demo-app:assembleDebug` 成功；APK 元数据为 `versionCode 7 / versionName 0.6.0`。
   - `:ugk-pi-android:bundleReleaseAar` 成功；`scripts/sdk/inspect-core-api-surface.ps1` 当时输出 `707` 个 javap public member signatures。远端 v0.6 台账记录的 `575` 与该次实际输出不一致；最新 inventory 见本文第 0 节。
   - 远端 v0.6 台账已有 `QSG6Q8IFDMDELVGQ` 安装启动、Skill 种子和记忆捕获/回放初步验收记录；本次接手未对任何真机执行 ADB 操作。后续仍只允许显式指定两台小米序列号，严禁操作三星。

7. **悬浮窗收起极简优化、API 通信检测、多预设管理与平台额度查询（已在小米真机验证）**：
   - 悬浮窗收起状态精简为单行圆润胶囊（92dp × 38dp），移除非必要的双行长标题堆叠，左侧图标与文案联动（运行中橙红 `●`、待确认黄 `⚠`、完成绿 `✓`、失败红 `✕`、就绪灰 `✦`）。
   - 新增 `ApiQuotaAndConnectivityService`：支持 Anthropic 与 OpenAI 协议轻量探活与往返延迟测算；汲取开源 `cc-switch` 经验，自动根据 Base URL 嗅探匹配 DeepSeek、硅基流动、Moonshot/Kimi、OpenRouter 等平台，自动查询解析账户剩余额度与用量。
   - `ApiSettingsDialog` UI 升级：外层包裹 `ScrollView` 防止软键盘溢出；顶部增加横向滚动预设 Chip 条，支持多 API 方案切换、新建、别名重命名与删除；增加“⚡ 检测通信与平台额度”按钮与动态卡片。
   - 全工程 JVM 测试通过；Debug APK 成功安装并在小米真机 `QSG6Q8IFDMDELVGQ` 验证。

8. **API 上下文窗口与最大输出长度手动配置及全链路生效（已在小米真机验证）**：
   - `ApiProviderConfig` 新增 `contextWindow`（64K / 128K / 200K / 1M / 32K）与 `maxOutputTokens`（4K / 8K / 16K / 32K）字段与 `formatSpec()` 标签生成。
   - 设置弹窗新增两大项横向 Chip 选择区，支持点击秒切高亮与按配置持久化。
   - **生效链路**：`DemoAgentRuntimeFactory` 将 `maxOutputTokens` 下发至 `AnthropicMessagesProvider` 请求体，解决部分模型单次生成超限报 400 问题；`DemoActivityState` 联动 `budgetForContextWindow` 动态缩放历史消息与字符预算；`MainActivity` 顶部状态栏直观呈现 `(200K · 8K输出)`。
   - 全量 268 个 JVM 测试全部通过，APK 已部署并完成真机实测。

9. **独立专用设置页面（`SettingsActivity`）升级与真机验证（已在小米真机验证）**：
   - 全面由传统弹窗转变为独立全屏页面 `SettingsActivity`，包含 Edge-to-Edge 沉浸式导航栏、返回按钮、界面主题卡片、全授权模式卡片、API 预设栏、接口配置表单、上下文窗口 Chips、最大输出生成 Chips、通信检测与额度查询动态卡片，以及底部快捷操作栏。
   - 点击右上角设置按钮通过 `Intent` 切换至独立设置页，进入时自动收起悬浮窗避免遮挡；返回时 `MainActivity.onResume()` 自动同步更新主题与运行时配置。
   - 全工程 268 个 JVM 测试通过；Debug APK 成功安装并在小米真机 `QSG6Q8IFDMDELVGQ` 验证。

10. **适配 GLM-5.3 与 DeepSeek-V4 旗舰标准（新增 128K 超大输出与 2M 上下文）**：
   - 单次最大输出选项扩展：`4K`、`8K (通用)`、`16K`、`32K`、`64K`、`128K (超大)`。
   - 上下文总窗口选项扩展：`64K`、`128K`、`200K`、`1M`、`2M`、`32K`。
   - 底层 `DemoActivityState.budgetForContextWindow` 动态预算深度扩容：2M 匹配 800 轮/8万字符，1M 匹配 400 轮/5万字符，128K 匹配 160 轮/2万字符。
   - `ApiContextSettingsTest` 验证 `2M · 128K输出` 完整往返序列化与多档位预算测试全部通过。

11. **70% 阈值上下文自动压缩（Context Compaction）机制落地与真机部署**：
    - 新增核心压缩引擎 [`ContextCompactor.kt`](demo-app/src/main/java/com/ugk/pi/android/testapp/ContextCompactor.kt)：
      - **Token 估算**：中英及代码加权估算会话实时消耗；
      - **Level 1（工具输出剪枝）**：折叠历史大工具输出（首尾摘录），零 API 成本释放 40%~60% 空间；
      - **Level 2（结构化摘要提炼）**：将早期轮次转为《阶段压缩摘要》，保留最近 3~5 轮活跃对话；
      - **Level 3（原子边界校验）**：首消息保障为 `User`，杜绝孤儿 `Tool` 结果，符合各大模型协议规范。
    - [`SettingsActivity.kt`](demo-app/src/main/java/com/ugk/pi/android/testapp/SettingsActivity.kt) 新增“上下文自动压缩”卡片与 `60% / 65% / 70% (推荐) / 75% / 80%` 阈值 Chips。
    - 全套单元测试（含 `ContextCompactorTest` 6 个用例与 `ApiContextSettingsTest`）全量通过，Debug APK 成功编译并部署至小米真机 `QSG6Q8IFDMDELVGQ`。

12. **底部上下文占用率动态进度条与四阶色彩指示器（已在真机实测）**：
    - 移除底部传统文本提示（`Agent 会按需调用工具...`），换装为现代优雅的上下文监控胶囊条。
    - **细粒度四阶动态变色**：
      - `< 50%`：健康（Mint 翡翠绿）；
      - `50% ~ 70%`：适中（Sky Blue 天空蓝）；
      - `70% ~ 85%`：临界压缩区（Amber 琥珀橙）；
      - `≥ 85%`：高负荷警戒（Coral 赤红）。
    - **实时信息呈现**：展示当前已消耗 Token、总窗口容量及压缩阈值（例如 `● 上下文 18% (23.5K / 128K · 70%压缩)`），点击整栏可直接秒跳设置页调参。

## 5. 常用开发与调试命令速查

```powershell
# 1. 运行当前全工程单元测试
.\gradlew.bat `
  :ugk-pi-android:testDebugUnitTest `
  :pi-file-skill-android:testDebugUnitTest `
  :pi-schedule-skill-android:testDebugUnitTest `
  :ugk-agent-task-runtime-android:testDebugUnitTest `
  :pi-system-skill-android:testDebugUnitTest `
  :pi-agent-skill-runtime-android:testDebugUnitTest `
  :ugk-terminal-runtime-android:testDebugUnitTest `
  :pi-terminal-skill-android:testDebugUnitTest `
  :demo-app:testDebugUnitTest `
  --console=plain

# 2. 构建 demo-app Debug APK
.\gradlew.bat :demo-app:assembleDebug --console=plain

# 3. 构建 Core Release AAR 并检查 API surface
.\gradlew.bat :ugk-pi-android:bundleReleaseAar --console=plain
.\scripts\sdk\inspect-core-api-surface.ps1

# 4. 覆盖安装并启动应用至在线授权小米（示例使用第二台小米 e0b93f2f）
adb -s e0b93f2f install -r -d E:\AII\ugk-android-new\demo-app\build\outputs\apk\debug\demo-app-debug.apk
adb -s e0b93f2f shell am start -n com.ugk.pi.android.testapp/.MainActivity

# 5. 截取真机屏幕并拉取查看；执行前必须确认目标是授权小米
adb -s QSG6Q8IFDMDELVGQ shell screencap -p /sdcard/screen_debug.png
adb -s QSG6Q8IFDMDELVGQ pull /sdcard/screen_debug.png $env:TEMP\screen_debug.png
```

---

## 6. 新会话接手注意事项

1. **语言规则**：严格使用**简体中文**进行技术解释、代码注释与对话交互。
2. **设备边界**：ADB 命令必须显式带上 `-s QSG6Q8IFDMDELVGQ` 或 `-s e0b93f2f`，严禁操作三星手机（`R5CRB11B2AW`）。本次接手未执行 ADB。
3. **两类 AGENTS.md 区分**：
   - 根目录 `AGENTS.md`：仅供开发者/助手协作，不打包进 APK。
   - 运行时 `pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md`：随 APK 打包，作为模型全局环境提示词。
4. **UI 构建原则**：项目纯代码构建界面（无 XML layout），增改视图时保持代码构建风格与 `ThemeManager` 动态调色规范。
5. **当前视觉基线**：用户消息右侧主题绿，AI 回复左侧中性表面；绿色预算只用于用户气泡、主要动作、已选控件和成功状态，等待确认/危险/失败使用独立语义色。实现事实先读 `docs/demo-app-ui-redesign.md` 的当前视觉章节，再读 `docs/demo-app-version-ledger.md` 的同版本 checkpoint；不要把旧的橙红美学历史条目当作当前基线。
