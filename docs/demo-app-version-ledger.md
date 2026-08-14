# demo-app 版本与变更台账

更新时间：2026-08-14
当前版本：`0.2.1`（`versionCode 3`）
版本范围：仅 `:demo-app`；SDK/AAR 模块版本继续独立维护。
当前阶段：稳定化测试期；本次 `sdk-stabilization-baseline-2026-08-14` 是工程 checkpoint，不是新的 Demo 发布版本。

## 版本规则

- `versionCode` 只递增，不因重新打包或覆盖安装回退。
- `versionName` 使用面向测试交付的 SemVer 风格；聊天、会话和悬浮窗等一组可感知能力完成后提升 minor 版本。
- 稳定性修复、生命周期恢复和验收证据整理使用 patch 版本递增，不与新的用户可感知 UI 能力混用。
- 本版本 Git 标签为 `demo-app-v0.2.1`；上一版 `demo-app-v0.2.0` 只代表 demo-app UI 版本，不代表 Terminal Runtime 已达到最终发布状态。
- Debug APK 允许本机从被 Git 忽略的配置读取 API 默认值，不能作为对外分发包；API Key 不进入源码、文档或提交。
- 真机迭代使用固定 Debug 签名和 `adb install -r -d`，不以卸载、清数据作为常规版本升级步骤。

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

### 稳定化测试期版本边界

- 本次保存不提升 `versionName` 或 `versionCode`；`0.2.1 / versionCode 3` 继续代表当前 Demo 测试交付物。
- `demo-app-v0.2.1` 保持不变；稳定化 checkpoint 使用独立标签，不能当作新的产品版本或正式发布标签。
- 后续若只是稳定性修复、测试和证据整理，先更新本台账和 SDK 稳定化文档；只有形成新的可安装交付物或用户可感知行为变化时才评估 patch/minor bump。

## 0.1.0 · 历史开发基线

`0.1.0` 是未打正式标签的早期 demo-app 基线。本版本不删除其历史提交，后续通过 `versionCode` 单调递增和版本标签追踪可安装交付物。
