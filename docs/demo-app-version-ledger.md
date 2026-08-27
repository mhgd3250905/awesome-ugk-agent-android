# demo-app 版本与变更台账

更新时间：2026-08-27
当前保存版本：`0.6.0`（`versionCode 7`）
版本范围：仅 `:demo-app`；SDK/AAR 模块版本继续独立维护。
当前阶段：`0.6.0` 文件型 skill 运行时与 agent-memory 记忆 skill 已完成 JVM 验证、真机安装与用户初步对话验收（捕获/回放）；本次提交将创建并同步 `demo-app-v0.6.0` 标签。

## 版本规则

- `versionCode` 只递增，不因重新打包或覆盖安装回退。
- `versionName` 使用面向测试交付的 SemVer 风格；聊天、会话和悬浮窗等一组可感知能力完成后提升 minor 版本。
- 稳定性修复、生命周期恢复和验收证据整理使用 patch 版本递增，不与新的用户可感知 UI 能力混用。
- 最近已保存版本 Git 标签为 `demo-app-v0.6.0`；`demo-app-v0.5.0`、`demo-app-v0.4.0` 和 `demo-app-v0.3.0` 保留为历史 Demo 交付标签，不代表 Terminal Runtime 已达到最终发布状态。
- Debug APK 允许本机从被 Git 忽略的配置读取 API 默认值，不能作为对外分发包；API Key 不进入源码、文档或提交。
- 真机迭代使用固定 Debug 签名和 `adb install -r -d`，不以卸载、清数据作为常规版本升级步骤。

## 0.6.0 · 2026-08-27 · 文件型 skill 运行时与 agent-memory 记忆 skill（已保存）

### 变更范围

- 新增独立模块 `pi-agent-skill-runtime-android`：SKILL.md 文件型 skill 规范（手写扁平 frontmatter 解析，标准字段 name/description + `x-ugk-load`/`x-ugk-embed-files`/`triggers` 扩展字段）、`SkillRepository` 实时扫盘、三级加载策略（always 全文常驻 / indexed 元数据桩 + `skill_read` 按需 / triggered 关键词）、`FileBackedSkillProvider` 合并式 Provider（含命名根实时嵌入，embed 内容每次 `skills()` 调用现读活数据）、`LoadPolicySkillResolver`（静态 plugin skills 行为零劣化）。
- 新增工具：`skill_list`、`skill_read`、`memory_list/read/write/delete`；记忆沙箱限定 `filesDir/agent-memory` 四分类白名单（user-profile/preferences/facts/rules），单文件 16KB 上限；`memory_delete` 默认 `UserConfirmationRequiredTool` 包装（全授权旁路沿用既有机制）。
- 第一个预制 skill `agent-memory`（always 策略）：捕获协议为"先在对话中征询同意 → memory_read → 合并不得丢条目 → overwrite 覆写 → 简短确认"，preferences/rules 经 `memory:` 命名根每轮实时嵌入常驻上下文，跨会话自动回放；幂等种子机制绝不覆盖已有目标。
- 核心最小改动：`AgentRuntime.Builder.skillProvider()` 从"立即拍平静态快照"改为持有 Provider 引用、每 run 拉取（公共 API 签名零变化，API surface 基线 575 不变；D-022 勘误二）。demo 前后台共用工厂一处接线。
- 文档：新增 `docs/android-agent-skills.md`（事实源）、`D-022` 及两条勘误、根 `AGENTS.md` 模块表更新。

### 当前证据与边界

- 全工程 JVM 单元测试共 258 个（基线 198 零回归 + 新模块 58 + 核心 2）、0 失败；`:demo-app:assembleDebug` 通过；APK 元数据 `versionCode 7 / versionName 0.6.0`。
- 核心公共 API surface 经 `scripts/sdk/inspect-core-api-surface.ps1` 复核仍为 575 个成员签名，与 D-018 基线一致。
- 真机 `QSG6Q8IFDMDELVGQ`（REDMI Turbo 5 Max，Android 16）安装启动正常、种子 SKILL.md 就位（always + memory: 嵌入配置）、logcat 无 crash；用户完成记忆捕获/回放初步对话验收并反馈可用。
- 边界：记忆捕获的"先征询后写"依赖模型对 skill 文案的遵从（模型偏差表现为未经同意写入，属行为问题而非运行时缺陷）；同一 run 内 provider 与 resolver 各扫盘一次属已接受设计；agent 自沉淀 skill（`skill_save`）列为 v2 展望；全授权模式下 `memory_delete` 不弹确认对话框（对话内复述确认仍由 skill 协议约束）。

## 0.5.0 · 2026-08-27 · 通用 Android 定时任务（已保存）

### 变更范围

- 新增独立模块 `ugk-agent-task-runtime-android`，把 `AgentTaskStore`、`AgentTaskScheduler` 适配为 Android 持久化 Store、通知任务的 `AlarmManager`、Prompt 任务的 `JobScheduler`、开机/升级恢复广播和通知 Sink。
- Demo 注册定时任务 Skill/Tool，并在 Android 13+ 启动时申请 `POST_NOTIFICATIONS`；`NOTIFY_USER` 用于提醒，`RUN_AGENT_PROMPT` 会通过 `AgentTaskJobService` 真正唤醒一轮 AgentRuntime。
- Demo 前后台共用 Runtime 工厂；后台按任务的 `sessionId` 恢复会话，使用 `AgentRunSource.SCHEDULED_TASK` 执行 Provider、无障碍屏幕、视觉、剪贴板和终端 Tool，并把任务输入与结果持久化回同一会话。
- 无交互确认窗口时，受保护动作默认安全拒绝；仅当用户显式开启全授权时，后台才允许执行这些动作。
- 增加重复任务状态迁移、失败收敛、Prompt 结果通知、时间算术溢出校验、会话重建和后台确认兜底测试。

### 当前证据与边界

- 全工程 JVM 单元测试共 198 个、0 个失败；`:demo-app:assembleDebug` 已通过；APK 元数据为 `versionCode 6 / versionName 0.5.0`，合并 Manifest 已包含 `AgentTaskJobService`。
- APK 已安装并启动于第二台授权小米 `e0b93f2f`（型号 `2304FPN6DC`）；用户已完成一次性 `RUN_AGENT_PROMPT` 后台唤醒/读屏体验验证并反馈可用。主目标小米 `QSG6Q8IFDMDELVGQ` 当时离线，三星设备 `R5CRB11B2AW` 未操作。
- 本版本标签为 `demo-app-v0.5.0`，随本次提交推送至 `origin`；`0.4.0` 和 `0.3.0` 标签一并补齐远端版本基线。
- 普通 `AlarmManager` 与 `JobScheduler` 都是系统尽力而为调度，可能受 Doze、网络状态和小米省电策略延迟；本版不是事件订阅或常驻监听服务。
- `RUN_AGENT_PROMPT` 是系统尽力而为的一次有限后台回合，不是微信事件订阅或常驻监听；执行时仍受网络、Doze、小米省电策略、无障碍连接和前台目标界面限制。

## 0.4.0 · 2026-08-27 · 视觉屏幕兜底与文本剪贴板

### 变更范围

- 视觉屏幕兜底：无障碍 UI 树无法暴露可靠目标时，通过 `screen_capture_visual` 提供短暂截图上下文，并以最新 `observationId` 和归一化区域驱动 `screen_visual_gesture`；截图和观察结果不进入持久化会话。
- Android 文本剪贴板：新增 `clipboard_read_text`、`clipboard_write_text`、`clipboard_clear`，由现有 Android 插件自动注册；第一版只处理纯文本，Android 10（API 29）以下明确返回不支持。
- 剪贴板读取原文只进入紧邻的下一次模型请求，持久化 Tool 结果、事件和 Demo 过程仅保留元数据；读/写/清空默认使用精确确认，写入默认标记敏感内容。
- `demo-app` 版本提升为 `versionCode 5` / `versionName 0.4.0`；本地 Git 标签为 `demo-app-v0.4.0`。

### 验收证据

- 全工程 JVM 单元测试：186 个通过，0 个失败。
- `:demo-app:assembleDebug`：通过。
- `:demo-app:compileDebugAndroidTestKotlin`：通过。
- 第二台小米 `e0b93f2f`（型号 `2304FPN6DC`）已安装并启动包含剪贴板功能的 Debug APK，用户完成剪贴板体验验证并反馈正常。
- 主目标小米 `QSG6Q8IFDMDELVGQ` 在本轮部署时不在线；三星设备 `R5CRB11B2AW` 未操作。

### 当前边界

- `0.4.0` 标签和提交已作为历史版本基线同步至远端；Release/AAB、API 配置和正式分发仍按发布清单单独验收。
- 剪贴板后台读取仍受 Android 焦点/默认 IME 策略约束；写入剪贴板不等于自动向其他 App 粘贴。

## 0.2.0 · 2026-08-13

### 变更范围

- 聊天优先的主界面：用户消息、Agent 过程和最终回答分层呈现。
- 过程卡片支持外层展开/收起、单步骤独立详情和底部收起入口；长结果保持完整可滚动阅读。
- 本地会话管理、草稿恢复、键盘自适应，以及发送/停止状态互斥。
- 获得悬浮窗权限后，App 退到后台即显示状态胶囊，不要求先发送任务；展开后可查看状态、过程、结果并发送排队消息。
- 悬浮窗支持标题区拖动、右下角拖动调整尺寸和最小尺寸限制；缩放圆角使用加粗圆弧提示，不额外放置图标。
- 缩放触控层置于内容层下方，确保右下角视觉提示不会遮挡输入框和发送按钮。
- 悬浮窗最终回答遵循与主界面一致的时间线：过程和活动记录之后显示，不再置于过程上方。
- 全授权模式和后台确认卡片用于受控测试设备；默认仍关闭高影响操作自动确认。

### 验收证据

- `:demo-app:testDebugUnitTest`：通过。
- `:demo-app:assembleDebug`：通过。
- 真机 `2602BRT18C`（型号 `2602BRT18C`）人工复测通过：悬浮窗过程/最终回答顺序正确，发送按钮可点击，拖动和缩放可用。
- 最近一次 UI 修复使用 `adb install -r -d` 增量安装成功；未卸载、未清理用户数据，本次文档/版本整理未调用付费 API。
- 详细的前置功能验收、测试边界和未覆盖矩阵见 [`demo-app-ui-redesign.md`](demo-app-ui-redesign.md)；Terminal Runtime 的验证证据仍以 `docs/terminal-runtime-validation.md` 为准。

### 已知边界

- 本版本未重新执行会安装测试 APK 的 connected instrumentation；最近 UI 变更以 JVM 测试、Debug 构建和真机人工复测为证据。
- Debug APK 不应分享给他人；Release 构建默认不嵌入 API 配置。
- 悬浮窗是可选的跨 App 观察入口，不等同于独立后台执行服务；进程被系统杀死后的 Agent 续跑仍不在本版本范围内。

## 0.2.1 · 2026-08-14 · 稳定性审查修复

### 变更范围

- 运行协调器和确认 presenter 提升到进程级，Activity 重建时重新绑定 UI，不取消正在运行的 Agent；真正结束 Activity 时仍清理任务。
- 为每次运行增加代次校验，停止后立即发送不会被旧 Job 的 `finally` 覆盖；悬浮窗排队消息随运行协调器保存。
- 修复本地会话达到上限时的淘汰顺序；会话、运行时消息和悬浮窗过程条目均使用有界/稳定的状态模型。
- 空输入发送按钮真正禁用，运行中的主界面按钮仍保留停止语义；悬浮窗草稿跨收起/回前台保留。
- 失败详情保留完整正文，紧凑状态只用于摘要；悬浮窗切换会话时清除旧活动记录。
- 无悬浮窗权限时后台确认显式走取消兜底，不再留下不可见的 pending；删除无效的 saved-state transcript 字段。
- 无 API 配置时不再额外弹出悬浮窗授权提示；确认 presenter 在 Activity 真正结束时释放旧窗口和授权回调；会话持久化采用最新快照合并写入，避免快速连续保存造成无界后台队列。
- 屏幕 UI 树改用结构化 JSON 序列化；屏幕动作失败正确返回 `isError=true`，并补齐 Accessibility 节点回收。

### 验收证据

- `:demo-app:testDebugUnitTest`：通过（12 个测试，包含会话淘汰顺序、超长失败详情和运行协调器回归）。
- `:demo-app:assembleDebug`：通过；最终 APK 元数据为 `versionCode 3` / `versionName 0.2.1`。
- `:demo-app:connectedDebugAndroidTest`：在 source checkpoint `28bc352622458d29e090656ae42fd32f057e9196` 的 `SM-A526U1`（Android 14/API 34、arm64-v8a、4 KB）上 `14/14` 通过；测试结束后已重新安装 Debug APK 并启动，未见已知致命异常。
- 独立审查线程 `019ffc0d-ca14-77f3-83f7-beeaae65d310` 已完成最终代码复验：六维检查无 P1/P2 阻塞；建议后续补充 presenter release/detach、最新快照写入和无 API 发送路径的自动化测试。

### 当前未完成证据

- 本轮尚未重新执行人工悬浮窗、Activity 重建和键盘触控验收；连接的真机回归覆盖的是自动化 Demo/Runtime 测试。
- 未覆盖的人工场景仍需使用当前 APK、当前 tag 和设备序列号重新记录，不能用旧版本报告替代。

### 2026-08-25 屏幕自动化稳定性补强（不升版本）

- `screen_read_ui_tree` 增加屏幕尺寸、非自身窗口数、截断标记和有界 `max_nodes`；`screen_gesture` 改为按当前屏幕尺寸生成滑动终点并拒绝越界输入。
- `screen_perform_action` 对未知动作和缺失 `set_text.text` fail-closed；屏幕树和动作窗口路径补齐节点回收；手势回调等待改为可取消协程等待。
- 新增坐标边界、屏幕尺寸适配和输入拒绝 JVM 回归；当前 Debug APK 在 Pixel 8 / Android 17 Preview 与 SM-A526U1 / Android 14 上各完成 `14/14` connected instrumentation。
- 两台设备本轮均未启用 Demo AccessibilityService，因此跨 App 的真实微信/设置/浏览器无障碍操作仍未形成当前版本证据；本条不提升 `versionName` 或 `versionCode`。

### 稳定化测试期版本边界

- 本次保存不提升 `versionName` 或 `versionCode`；`0.2.1 / versionCode 3` 继续代表当前 Demo 测试交付物。
- `demo-app-v0.2.1` 保持不变；稳定化 checkpoint 使用独立标签，不能当作新的产品版本或正式发布标签。
- 后续若只是稳定性修复、测试和证据整理，先更新本台账和 SDK 稳定化文档；只有形成新的可安装交付物或用户可感知行为变化时才评估 patch/minor bump。

## 2026-08-25 Accessibility screen automation SDK migration

### 变更范围

- 屏幕 UI 树读取、selector 查找、节点 action、坐标手势、IME action 和 global action 下沉到
  `pi-system-skill-android` 的统一 Skill/Plugin。
- demo 通过 `AccessibilityScreenAutomationBackend` 注入当前无障碍服务，不再维护私有 Screen Tool 和屏幕 Skill。
- 节点动作改为 `snapshotId + nodeId` 精确绑定；新的 read/find 会使旧 snapshot 失效，backend 对目标 fingerprint
  和可交互状态做 fail-closed 校验。
- 高影响屏幕操作继续使用 `UserConfirmationRequiredTool`；无障碍授权仍由用户手动开启。

### 版本边界

本次是 SDK 能力归位、测试迁移和文档修订，不改变 demo 用户可感知 UI，因此保持 `0.2.1 / versionCode 3`。

## 0.3.0 · 多模态识图、原生卡片组件与全方位美学重构 (versionCode 4)

### 变更背景与交付范围
1. **多模态识图体系**：
   - 支持拍照（动态申请 Camera 权限 + FileProvider 共享）与系统相册选取；
   - 智能采样压缩（长边限制 1280px + 200KB 优质 JPEG）与 ExifInterface 自动纠偏旋转角度；
   - 沉浸式缩略图卡片、删除与联动点亮发送按钮；
   - 沉浸式全屏大图查看器（点击放大、全屏沉浸浏览、轻松关闭退出）；
   - SDK 核心层全面升级多模态协议（兼容 Anthropic 标准 Base64 image 块与 OpenAI 标准 image_url 块，已在智谱 GLM-5.3-Flash 视觉端点实测通过）。
2. **全方位美学与双主题重构**：
   - 浅色模式采用米白暖灰质感基调，主色调选用热情高辨识度的柿橙红；深色模式采用沉稳高级的深碳灰（彻底去除带绿色的暗色调）；
   - 全局精致清晰字体排版（`sans-serif-medium`），行距与内边距呼吸感大幅提升；
   - 全面修复深色模式下的反色、异常背景与不可见文字异常。
3. **独立代码块与横向滑动卡片**：
   - 提取代码块为独立卡片视图 `DemoCodeBlockView`，支持不换行横向平滑滚动；
   - 配备语言标签芯片与一键复制代码按钮，带复制成功微反馈。
4. **原生卡片式 Markdown 表格组件 (`DemoTableView`) 与流式防抖**：
   - 彻底废除在单一 TextView 内使用 Markwon `TableRowSpan`（`ReplacementSpan`）异步 `post(setText)` 导致的行高疯狂震颤跳跃与网格重叠死循环；
   - 采用独立原生卡片视图，内嵌 `HorizontalScrollView`（`isFillViewport = true`，支持超宽表格横向平滑滚动，单元格不换行堆叠）与 `TableLayout`（`isStretchAllColumns = true`，少列等比拉伸）；
   - 单元格字号调优为 12sp，支持加粗与代码等富文本；
   - 表格独立于正文视图，后续正文流式输出时，表格卡片零重绘、零震颤。
5. **流式调度器与格式容错**：
   - 采用 64ms 节流流式调度器，配合 Markdown 未闭合格式自动补全闭合（反引号、粗斜体、列表等）。

### 验收证据
- `:demo-app:testDebugUnitTest`：全工程 163 个单元测试全部通过（包含新增的多模态提供者单测、表格提取解析单测）。
- 小米 15 目标设备（`QSG6Q8IFDMDELVGQ`）真机实测验证：
  - 拍照与相册选取、缩略图展开与全屏大图查看体验丝滑；
  - 智谱 GLM-5.3-Flash 实测视觉分析极度精准；
  - 包含 8 行 3 列的水果表格在流式生成与完成态下无丝毫跳动与闪烁，横向滑动正常；
  - 浅色米白模式与深色纯净深碳灰模式切换平滑完整。
- 版本标签：`demo-app-v0.3.0`。

## 0.1.0 · 历史开发基线

`0.1.0` 是未打正式标签的早期 demo-app 基线。本版本不删除其历史提交，后续通过 `versionCode` 单调递增和版本标签追踪可安装交付物。
