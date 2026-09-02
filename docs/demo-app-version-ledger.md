# demo-app 版本与变更台账

更新时间：2026-09-02
当前保存版本：`1.0.4`（`versionCode 104`）
版本范围：仅 `:demo-app`；SDK/AAR 模块版本继续独立维护。
当前阶段：在 `1.0.2`（更名 `com.ugk.pi.agent` 并上线 Play 封闭测试，commit `6f88115`，标签 `demo-app-v1.0.2@6f88115`）之上交付悬浮窗软键盘避让（`1.0.3 / 103`，commit `032589c`）与 Play 应用内更新提示（`1.0.4 / 104`，功能 commit `11d9945`、版本 commit `4e4bbe4`）；`1.0.3`、`1.0.4` 均已发布到 Play 内部测试轨道。版本边界标签为 `demo-app-v1.0.4@4e4bbe4`；远端状态以 Git 实测为准。

> 文首元数据、版本规则和 `1.0.4` 记录描述当前保存点；其后的版本条目是不可改写的历史记录。
> 历史条目中的“当前”仅指该条目记录时点，不是今天的版本或验证状态。

## 版本规则

- `versionCode` 只递增，不因重新打包或覆盖安装回退。
- `versionName` 使用面向测试交付的 SemVer 风格；聊天、会话和悬浮窗等一组可感知能力完成后提升 minor 版本。
- 稳定性修复、生命周期恢复和验收证据整理使用 patch 版本递增，不与新的用户可感知 UI 能力混用。
- 本阶段版本边界标签名为 `demo-app-v1.0.4@4e4bbe4`；`demo-app-v1.0.3@032589c`、`demo-app-v1.0.2@6f88115`、`demo-app-v0.9.4`、`demo-app-v0.9.3`（指向 `1170268`）、`demo-app-v0.9.2`、`demo-app-v0.9.1`、`demo-app-v0.9.0`、`demo-app-v0.8.0`、`demo-app-v0.7.1`、`demo-app-v0.7.0`、`demo-app-v0.6.0`、`demo-app-v0.5.0`、`demo-app-v0.4.0` 和 `demo-app-v0.3.0` 保留为历史 Demo 交付标签，不代表 Terminal Runtime 已达到最终发布状态。
- Debug APK 允许本机从被 Git 忽略的配置读取 API 默认值，不能作为对外分发包；API Key 不进入源码、文档或提交。
- 真机迭代使用固定 Debug 签名和 `adb install -r -d`，不以卸载、清数据作为常规版本升级步骤；本阶段不操作真机。

## 1.0.4 · 2026-09-02 · Play 应用内更新提示（FLEXIBLE）

### 变更范围

- `demo-app` 集成 Google Play In-App Updates（`com.google.android.play:app-update:2.1.0` / `app-update-ktx:2.1.0`，另显式声明 `androidx.activity:activity:1.10.1`）：FLEXIBLE 流程，Play 官方更新对话框每进程最多自动发起一次（`InAppUpdateProcessScope` 进程级状态，Activity 配置变化重建不复弹）；下载完成后每次回前台以 snackbar 提示重启安装（`completeUpdate()`）；旁加载/无 Play 设备静默 no-op。
- `MainActivity` 基类由 `android.app.Activity` 切换为 `androidx.activity.ComponentActivity` 以承载 `startUpdateFlowForResult` launcher；`onNewIntent` / `onRequestPermissionsResult` 参数空ability相应收紧（JVM 签名不变，运行时行为不变）。
- Demo 版本由 `1.0.3 / versionCode 103` 提升到 `1.0.4 / versionCode 104`。不改变权限、Terminal v1 scope 或聊天 UI 基线。

### 验收证据与边界

- `:demo-app:testDebugUnitTest` 于 HEAD `4e4bbe4` 实测 `153/153`（0 failure/error/skipped），含 `InAppUpdateControllerTest` 10 例；`:demo-app:assembleDebug`、`:demo-app:bundleRelease` 通过，AAB 元数据 `versionCode 104 / versionName 1.0.4`。
- 独立 review 结论为有条件通过后修复 1 个 P1（防骚扰状态由 Activity 实例级提升为进程级）并补 launcher 存活守卫；两项 P3（`completeUpdate` 失败的用户可感知提示、纯决策类拆分文件）延后。
- 模拟器（`ugk_dev_api35_smooth`）冒烟：基类切换后主界面/设置页/HOME 重开/IME 输入零回归，logcat 无 FATAL；debug 直装包上 Play 可用性检查按预期静默失败（`ERROR_APP_NOT_OWNED`，仅日志无 UI）。
- 发布记录（外部观察）：AAB 上传 Play 内部测试轨道并于 2026-09-02 16:26 生效（截图 `playstore-internal-104-2026-09-02.png`），更新包 4.61 MB；版本说明 en-US 注明 in-app update prompt。
- 应用内更新弹窗对测试人员自下一版（`105+`）起实际可见——`104` 自身为轨道最新版时无更新可推。
- 版本边界标签为 `demo-app-v1.0.4@4e4bbe4`；远端状态以 Git 实测为准。

## 1.0.3 · 2026-09-02 · 悬浮窗软键盘避让

### 变更范围

- `AgentFloatingWindow` 新增 IME 避让（新文件 `ImeAvoidance`）：跨 App overlay 不依赖系统 `ADJUST_RESIZE`/`ADJUST_PAN`（实测对悬浮窗均无效），三层检测——`onApplyWindowInsets` 缓存 + 150ms 防抖、`imeBottom > 0` 精确换算、IME 可见但无值时按屏高约 58% 估算兜底；键盘收起恢复 `preImeY` 锚点。不挂 `WindowInsetsAnimation.Callback`（会使 overlay 丢失 ime 数值）。
- Demo 版本由 `1.0.2 / versionCode 102` 提升到 `1.0.3 / versionCode 103`。不改变依赖、权限、Terminal v1 scope 或主界面聊天 UI 基线。

### 验收证据与边界

- `ImeAvoidanceTest` 12/12 通过；`:demo-app:assembleDebug` / `testDebugUnitTest` 通过。
- 五轮迭代 + 独立 review 终审通过（4 个 P1 全部修复）；模拟器（`ugk_dev_api35_smooth`）实测：贴底弹键盘上移至理论位 ±10px、收键盘复位 0 误差、三连开关零漂移、不重叠时不移动、收起再展开无跳变。
- 边界：估算兜底对第三方浮动键盘/横屏未验证（方向保守）；API 24-29 差值法无老设备实测；悬浮窗 resize 手柄热区过小为延后优化项。
- 发布记录（外部观察）：AAB 上传 Play 内部测试轨道并于 2026-09-02 15:16 生效（截图 `playstore-internal-103-2026-09-02.png`）。
- 版本边界标签为 `demo-app-v1.0.3@032589c`；远端状态以 Git 实测为准。

## 1.0.2 · 2026-09-01 · 更名 UGK Agent 并上线 Play 封闭测试

### 变更范围

- `applicationId` 由 `com.ugk.pi.android.testapp` 改为 `com.ugk.pi.agent`；`versionCode 15 -> 102`、`versionName 0.9.4 -> 1.0.2`；新增 release upload 签名配置（四项 keystore 属性均读自被 Git 忽略的 `local.properties`，不入库）；应用更名 UGK Agent（commit `6f88115`）。
- Play 上架过渡：`versionCode 100` 对应首个内部测试在线版本 `1.0.0`；`versionCode 101` 在上架准备期消耗（轨道与内容以 Play Console 发布历史为准）。`100-102` 均已消耗，后续版本从 `103` 起递增。

### 验收证据与边界

- 发布记录（外部观察）：`1.0.2 (102)` 于 2026-09-01 23:36 面向 177 国家/地区上线封闭测试；opt-in 链接 `https://play.google.com/apps/testing/com.ugk.pi.agent`。
- 版本边界标签为 `demo-app-v1.0.2@6f88115`；远端状态以 Git 实测为准。

## 0.9.4 · 2026-09-01 · 第三轮 P0 审查修复（SDK 协议/并发/性能 + 终端进程组契约）

### 变更范围

- `ugk-pi-android`：`terminalForTurn` 工具空白完成消息不再持久化空白 Assistant（此前经 Anthropic 序列化为空 content 数组导致会话每次请求 400、永久坏档）；Anthropic 序列化对存量空白 Assistant 补占位文本块（防御层）；空白 run 输入在入口拒绝、不入档不请求；两个 Provider 对截断/损坏的 tool 参数不再静默替换为空对象执行（丢弃该调用并保留 stop reason，交由既有 incomplete-response 重试）；`JavaNetHttpTransport.postStream` 取消收集器即断开底层连接（此前阻塞 readLine 占用 socket 与 IO 线程至 180s 读超时）并新增单行 maxResponseBytes 上限。
- `ugk-agent-task-runtime-android`：`handle()` 执行完成后写回前重读任务记录——执行期间并发的 cancel/update 不再被过期快照覆盖（取消任务复活、改期回滚两类缺陷），残余毫秒级窗口在代码注释中声明。
- `pi-schedule-skill-android`：`nextRunAtMillis` 对敌意/损坏持久化数据的算术溢出降级为 null，任何返回非负（此前可为负值触发 AlarmManager 立即到期热循环）。
- `pi-agent-skill-runtime-android` / `pi-file-skill-android`：`writeTextAtomically` 临时文件改唯一名（`File.createTempFile`）并删除“先删目标再拷贝”兜底（并发写者此前可把整个 memory/skill 文件删掉）；`AgentSkillSeeder` 种子临时文件唯一化、rename 前重查目标，并发 seed 不再可能留下永久损坏的种子 skill。
- `ugk-terminal-runtime-android`：bash 调用自然退出时清扫其进程组内残余后台进程（SIGTERM→SIGKILL），SDK runtime `AGENTS.md` 的“进程组绑定单次调用”契约从劝退变为强制——后台进程不能再绕过 `local_http_server_*` 的确认门禁与数量上限、砖化默认端口；`PythonDistribution` 首次全量校验后以 `.verified` 指纹标记短路（此前每次调用全量 SHA-256 校验 613 个文件共约 10.8MB 并持锁串行）；`LocalHttpServerManager` 对无 handle 的过期不监听记录惰性清理（pgid 回收复用导致的永久 running 假象与端口砖化），且不再对可能被复用的 pgid 盲目发信号。
- `demo-app`：`onSaveInstanceState` 不再用过期快照整会话覆盖保存（此前可抹掉后台定时任务追加的轮次，与 0.9.2 修复的不变量矛盾）；`saveAndFlush`/`appendMessagesAndFlush` 落盘改同步 `commit()`（原 `apply()` 与“进程杀死不丢结果”的声明不符）。
- Demo 版本由 `0.9.3 / versionCode 14` 提升到 `0.9.4 / versionCode 15`。不改变依赖、权限、Terminal v1 scope、聊天 UI 基线或 Release Gate。

### 验收证据与边界

- 九个模块 JVM 单元测试合计 `916` 个（`138` 个测试类）：`910` passed、`6` skipped、0 failure/error；skip 均为 Windows 主机 symlink 限制的既有用例。本轮新增 7 个 JVM 测试类共 17 个用例 + 1 个仪器用例，其中 10 个为缺陷复现用例（修复前确认失败、修复后转绿，取证见 PR 说明），其余为防御边界锁定用例。
- API 35 x86_64 模拟器（`codex_api35`）`:demo-app:connectedDebugAndroidTest` `28/28` 通过（0 failure/0 skipped），含新增 `TerminalBackgroundProcessCleanupInstrumentedTest.naturalExitTerminatesBackgroundChildrenOfTheCall`——对真实原生运行时验证后台子进程随调用结束被清扫。本轮未操作任何真机。
- `:demo-app:assembleDebug` 通过，APK metadata 为 `versionCode 15 / versionName 0.9.4`。
- 每个缺陷项均带先红后绿的复现测试；`LocalHttpServerManager` 惰性清理为行为加固，未带专用仪器用例（既有 `LocalHttpServerManagerInstrumentedTest` 全绿）。遗留未修复项（主线程位图解码、相册 URI 权限过期、arm64 16KB、`handle` 残余毫秒级竞态窗口等）记录于 PR 说明，不宣称已解决。
- 版本边界标签为 `demo-app-v0.9.4`；远端状态以 Git 实测为准。

## 0.9.3 · 2026-08-31 · 输入区与附件体验优化 + 多图方案 A

### 变更范围

- 输入区与附件体验（在 `0.9.2` 基线上，隔离 clone `codex/fix-input-composer-ui` 分支完成）：
  - 输入框文字改为正确的垂直对齐；去除输入框上方贴边横线。
  - 附件（文件导入）信息移动到输入框上方展示，可单独移除；删除附件后不再保留错误的"已导入"提示。
  - 进入设置页时不再闪现悬浮球；会话历史入口改为底部 Bottom Sheet（新增 `com.google.android.material:material:1.13.0` 依赖）。
- 多图方案 A：
  - 相册支持批量选择、相机支持追加拍摄，最多 4 张；待发送图片以横向缩略图条展示在输入框上方，支持单张删除。
  - 点击缩略图进入全屏预览；多图按添加顺序随消息发送。
  - 会话历史持久化保存多图数据，并兼容读取旧版本单图数据。
- 独立审查（Luna）关闭三类问题：图片异步处理跨会话竞态、历史缩略图 Bitmap 内存峰值、毫秒级文件名碰撞；最终审查结论 `PASS`。
- Demo 版本由 `0.9.2 / versionCode 13` 提升到 `0.9.3 / versionCode 14`。不改变 SDK publication `0.1.0`、权限、Terminal v1 scope 或 Release Gate。

### 验收证据与边界

- 九模块 JVM 单元测试 62 个结果 XML 合计 `441` 个测试：`438` passed、`3` skipped、0 failure/error（3 个 skip 与 PR #4 收束基线相同，均为 Windows 主机 symlink 限制）。
- `:demo-app:testDebugUnitTest`、`:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 使用 `--max-workers=1` 通过（Windows 并行启动多个测试 JVM 曾触发 `errno=1455` 虚拟内存不足，属环境限制，非断言失败）；`git diff --check` 通过。
- APK metadata：`com.ugk.pi.android.testapp` / `versionCode 14` / `versionName 0.9.3` / `minSdk 24`。
- 真机验收：`0.9.3 / versionCode 14` Debug APK 覆盖安装并启动到授权小米 `QSG6Q8IFDMDELVGQ`（型号 `2602BRT18C`，Android 16 / API 36），用户完成界面/功能测试并明确回复"测试通过"。
- 本轮没有 connected AndroidTest 结果目录；AndroidTest 仅完成 Kotlin 编译。
- 保存 commit `1170268`（`feat(demo-app): enhance composer and multi-image flow`）已快进推送到远端 `main`；版本边界标签 `demo-app-v0.9.3` 已创建，指向 `1170268`；远端状态以 Git 实测为准。
- 后续第三轮 P0 审查对该提交代码的独立补审结论为 `PASS`（附 2 项非阻断 MAJOR 建议：主线程批量解码缩略图、busy 时静默丢图）；其发现的问题（`onSaveInstanceState` 覆盖残留、flush 落盘语义等）已由 0.9.4 条目修复。

## 0.9.2 · 2026-08-31 · 第二轮 P0 审查修复（SDK 协议/并发 + demo 生命周期/数据正确性）

### 变更范围

- `ugk-pi-android`：`AnthropicMessagesProvider` 把连续 Tool/User 消息合并为单条 user 消息，恢复 Anthropic Messages 的严格 user/assistant 交替（工具返回图片或运行中投递消息不再触发 400）；请求不再回传无 signature 的 thinking 块；`AgentRuntime` 工具循环对任意异常先补全 tool_result 信封再重抛，畸形 tool 元数据不再把会话置为永久 Failed；`InMemorySessionStore` 改为 `ConcurrentHashMap.computeIfAbsent`，并发 `getOrCreate` 不再产生双实例绕过 `runGate`。
- `ugk-agent-task-runtime-android`：`handle()`/`restoreScheduledTasks()` 的互斥锁升级为进程级（alarm/job 每次投递新建实例导致实例锁失效）；任务存储抽出 `TaskRecordStore` 并使用进程级锁，跨实例读-改-写不再丢更新；损坏 JSON 在覆写前先备份到 `tasks_corrupt_backup`；广播协程兜底防 BOOT 崩溃；通知投递失败（权限缺失）不再把重复任务置为终态 FAILED。
- `pi-schedule-skill-android`：create/update 调度失败时回滚任务记录并返回 `SCHEDULER_ERROR`，不再留下“已调度”虚挂任务。
- `pi-agent-skill-runtime-android` / `pi-file-skill-android`：skill 目录扫描补齐 canonical/symlink 边界（目录链接与 SKILL.md 外链均拒绝）；frontmatter 重复 key 解析失败；memory_write/app_file_write/播种改为 temp+rename 原子写。
- `demo-app`：`AgentRuntime` 所有权移至进程级 `DemoConversationRuntime`，Activity 重建（配置未变）不再终止运行中的 Agent 或清空排队消息；会话存储新增原子 `appendMessages`，前台保存与后台定时任务结果不再互相覆盖，已删除会话不再被后台结果复活。
- `ugk-terminal-runtime-android`：`stopAll()` 仅在进程组真正终止后移除记录，失败组保持可查询、可停止。
- Demo 版本由 `0.9.1 / versionCode 12` 提升到 `0.9.2 / versionCode 13`。不改变依赖、权限、Terminal v1 scope、UI 基线或 Release Gate。

### 验收证据与边界

- 九模块 JVM 单元测试合计 `427` 个：`424` passed、`3` skipped、0 failure/error；跳过项均为 Windows 主机无法创建/解析 symlink 的用例，其中 skill 扫描 symlink 边界已由新增仪器用例覆盖（下条）。
- API 35 x86_64 模拟器（`ugk_dev_api35_smooth`，实际 page size 4096）`:demo-app:connectedDebugAndroidTest` `28/28` 通过，含新增 `MinApi24SkillScanSymlinkInstrumentedTest`（临时禁用修复时 2 个 symlink 用例如期失败，恢复后全绿——红绿闭环取证）；本轮未操作任何真机。
- `:demo-app:assembleDebug` 通过，APK metadata 为 `versionCode 13 / versionName 0.9.2`。
- 除 `LocalHttpServerManager.stopAll` 外的每个 JVM 修复项均带先红后绿的复现测试；`stopAll` 的记录保留行为沿用同文件 `stop()` 的既有契约（失败组保留记录、可查询可停止），该模块当前无 JVM 测试基础设施，未带新测试。遗留未修复项（流式主线程节流、会话重建工具证据、图片解码内存、bash 正常退出孤儿进程组、mailto/本地 HTTP 鉴权等）记录于 PR 说明与本台账边界，不宣称已解决。
- 版本边界标签为 `demo-app-v0.9.2`；远端状态以 Git 实测为准。

## 0.9.1 · 2026-08-30 · API 24 文件边界与 Intent 稳定性修复

### 变更范围

- PR #2（head `5bfc44f`，merge commit `771fa4e`）修复 `File.toPath()` 在 API 24 的运行时崩溃，改用 API 24 可用的 canonical `File` 边界判断；路径比较带目录分隔符边界，避免相似前缀目录误判。
- `pi-file-skill-android`、`FileBackedSkillProvider` 与 `skill_read` 统一拒绝 canonical/symlink 越出注册根目录；文件列表不再暴露指向 workspace 外部的 symlink 条目。
- `AndroidAppIntentSpec.toIntent()` 在 URI 与 MIME type 同时存在时使用 `setDataAndType()`，避免设置 type 时清除已有 data URI；四种 data/type 组合均有 Android 仪器回归用例。
- Demo 版本由 `0.9.0 / versionCode 11` 提升到 `0.9.1 / versionCode 12`。不改变 SDK publication `0.1.0`、依赖、权限、Terminal v1 scope、UI 基线或 Release Gate。

### 验收证据与边界

- 八个 SDK/Runtime 模块与 Demo JVM 共发现 `388` 个测试：`387` passed、`1` skipped、0 failure/error；跳过项是 Windows 主机无法创建 symlink 的 `rejectsSymlinkToSimilarPrefixSibling`，同类 Android symlink 用例已编译。
- `:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 和 `git diff --check` 通过；APK metadata 为 `versionCode 12 / versionName 0.9.1`。
- PR 说明记录了 API 24/API 35 的文件边界、skill embed 与 Intent data/type targeted dynamic evidence；本次 closeout 未重复操作真机、未调用真实 Provider/API，也不改变 Terminal 发布矩阵结论。
- 版本边界标签为 `demo-app-v0.9.1`；远端状态以 Git 实测为准。

## 0.9.0 · 2026-08-30 · 文件型 skill 单文件 authoring MVP

### 变更范围

- Demo 版本由 `0.8.0 / versionCode 10` 提升到 `0.9.0 / versionCode 11`，保留 0.8.0 的聊天/UI 基线；SDK/AAR publication 坐标继续为开发期 `0.1.0`，不改变依赖、权限、Terminal v1 scope 或 Release Gate。
- `pi-agent-skill-runtime-android` 新增内置 `android-skill-creator`（`indexed`）SOP，以及结构化 `skill_save`、`skill_delete` 和返回完整 manifest 的 `skill_read`，形成 create/update/delete/query/use 闭环。
- skill 只有在 `skill_save` 成功、`skill_list` 报告 `valid`、`skill_read` 核对 manifest/body 后才算创建或更新完成；普通 `docs/*.md` 仍是原料，不等于已安装 skill。
- `skill_save` 固定写入 repository 直接子目录，默认不覆盖；`skill_delete` 仅接收合法 name；路径校验、解析/写后校验、回滚、用户确认和 `agent-memory`/`android-skill-creator` protected-skill 规则均保持 fail-closed。
- 前置独立 review 发现的 frontmatter 注入 P1 已修复，并经精确复查为 `CLOSED / PASS`；本阶段独立 closeout review 已 `PASS`。

### 验收证据与边界

- 最终门禁：八个 SDK/Runtime 模块合计 `279/279`（Core 122、File 9、Schedule 9、Task Runtime 7、System 42、Agent Skill Runtime 70、Terminal Runtime 0 `NO-SOURCE`、Terminal Skill 20），Demo `107/107`，总计 `386/386`，0 failure/error/skipped；Gradle 输出 `BUILD SUCCESSFUL`，共 244 actionable tasks。
- `:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 通过；`aapt2 dump badging` 确认 `com.ugk.pi.android.testapp`、`versionCode='11'`、`versionName='0.9.0'`；APK 包含 `assets/agent-skills/android-skill-creator/SKILL.md`；`git diff --check` 通过。
- 前置设备事实：功能代码的 `0.8.0 / versionCode 10` Debug APK 已通过 `adb install -r -d` 覆盖安装到授权小米 `QSG6Q8IFDMDELVGQ`，未卸载、未清理数据；只验证安装与 package metadata，不等同于 skill authoring 行为验收。`0.9.0 / versionCode 11` 本阶段未安装。
- 尚未完成真实 Agent 的人工 create/update/delete/use end-to-end 场景；未调用真实 Provider/API。该版本保存不改变 supporting resources、脚本/资产执行、UI 管理或发布矩阵边界。
- 版本边界标签为 `demo-app-v0.9.0`；远端状态以 Git 实测为准，本条目不预判 push 或远程 Release 状态。

## 0.8.0 · 2026-08-30 · 微信式对话视觉与顶栏收束

### 变更范围

- 以成熟 IM 的交互秩序统一主聊天：用户消息右侧主题绿，AI 回复左侧 Light 白色/Dark 深灰中性表面；过程、工具证据、代码和表格使用中性层级，不与对话气泡争夺视觉焦点。
- 统一 Light/Dark 语义 Token、品牌猫头鹰、组件状态、设置页和悬浮窗折叠/展开视觉；悬浮窗最终回答支持 Markdown，空输入发送保持禁用语义。
- 首页顶栏只保留会话入口、运行状态和设置入口；主题切换集中在设置页；设置入口由异常描边资源改为标准实心齿轮矢量图标。
- 视觉实现 checkpoint 为 `af5b0b7075dd8a201dbfd857987521f7b0d3470a`，顶栏收束 checkpoint 为 `cde30bf9d12d262fce5141986a77230cbf6ff7b6`。Demo 版本由 `0.7.1 / versionCode 9` 提升到 `0.8.0 / versionCode 10`；SDK/AAR 坐标、依赖、权限和 API 配置边界不变。

### 验收证据与边界

- `:demo-app:compileDebugKotlin`、`:demo-app:testDebugUnitTest`、`:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin` 通过；Demo JVM XML `107/107`，0 failure/error/skipped；`git diff --check` 通过。
- 最终 APK 元数据确认为 `versionCode 10 / versionName 0.8.0`。
- 与最终生产代码一致、版本元数据仍为 `0.7.1 / versionCode 9` 的 Debug APK 已通过 `adb install -r -d` 覆盖安装至授权小米 `QSG6Q8IFDMDELVGQ`，未卸载、未清理数据；Light/Dark 主聊天、Settings Dark、悬浮窗折叠/展开和顶栏修复均已完成真机人工验收。`0.8.0` 元数据保存不重复安装。
- 未运行 `connectedDebugAndroidTest`：该测试可能安装/清理测试目标包并影响现有数据/API 设置；未调用真实 Provider/API。该边界不代表自动化或真实网络通过。
- 本地版本 checkpoint 与 `demo-app-v0.8.0` 标签不 push、不创建 PR、不发布远程 Release。

## 0.7.1 · 2026-08-29 · 模块化架构稳定化保存

### 变更范围（架构保存）

- 不新增用户功能；保存 `0.7.0` 之后完成的 Runtime 生命周期、API 设置、上下文档位、Provider profile、进程/悬浮窗 ownership、conversation runtime、Session transcript、capability assembly 与 Terminal/Screen host interlock 收敛。
- Demo 版本由 `0.7.0 / versionCode 8` 提升到 `0.7.1 / versionCode 9`；Core SDK publication 继续保持开发期坐标 `0.1.0`，没有依赖升级或权限变化。
- Canonical 文档、SDK 优化台账、验证矩阵、UI 版本元数据和交接信息对齐到当前保存点。

### 当前证据与边界（架构保存）

- `885c1e9` 对应代码已构建为 `0.7.0`，安装到 `QSG6Q8IFDMDELVGQ`（`2602BRT18C`）并启动；用户随后反馈该轮测试无明显问题。旧设备 App 为不同签名的 `0.6.0`，按用户明确授权卸载后重新安装，因此旧 App 本地数据已清除。
- `0.7.1` 元数据更新后，全工程 JVM XML 合计 `375/375` 通过、0 failure/error/skipped；其中八个 SDK/Runtime 模块为 `271`，Demo 为 `104`，`ugk-terminal-runtime-android` 当前为 `NO-SOURCE`。
- `:demo-app:assembleDebug` 成功；APK 元数据确认为 `versionCode 9 / versionName 0.7.1`。
- 上述架构保存本身未重复安装、未运行 instrumentation、真实网络或 Provider/API；随后同版本视觉 checkpoint 的实现、真机安装和验收见下节，未改变 SDK、权限、依赖或 API 配置边界。
- 本地保存 commit/tag 不 push、不创建 PR、不发布远程 Release。

### 同版本微信式对话视觉 checkpoint（不升版本）

#### 变更范围

- 实现 checkpoint：`af5b0b7075dd8a201dbfd857987521f7b0d3470a`（`feat(demo-app): refine conversation visual hierarchy`）。
- 用户消息统一为右侧主题绿气泡；AI 回复统一为左侧 Light 白色/Dark 深灰中性气泡；顶栏、composer、设置页、过程/工具证据和悬浮窗共享同一套 Light/Dark 语义 Token。
- 品牌资产保持同一猫头鹰语汇：launcher 沿用原品牌图，透明变体用于 App 内助手头像、空状态和悬浮窗入口；组件状态区分进行中、等待确认、成功、失败、危险、禁用和按压态，绿色预算不扩展到 AI 或证据层。
- 不涉及 SDK/Terminal、依赖、权限、API key 内容、版本号、远程 push/PR/release、tag 或外部台账。

#### 验收证据与边界

- `:demo-app:compileDebugKotlin`、`:demo-app:assembleDebug`、`:demo-app:compileDebugAndroidTestKotlin`：通过。
- `:demo-app:testDebugUnitTest`：测试 XML `107/107`，0 failure、0 error、0 skipped；`git diff --check`：通过。
- APK 元数据仍为 `0.7.1 / versionCode 9`；已对授权小米 `QSG6Q8IFDMDELVGQ` 执行 `adb install -r -d` 覆盖安装，未卸载、未清理数据，启动进程未见 `FATAL`。
- 主会话已查看并验收 Light/Dark 主聊天、Settings Dark、悬浮窗折叠/展开第二轮截图，确认用户绿、AI 中性、透明猫头鹰、无框顶栏与 composer、Markdown 和 disabled send 符合基线。截图保留在用户 Temp，不入库。
- 未运行 `connectedDebugAndroidTest`：该测试可能安装/清理测试目标包并影响现有数据/API 设置；未调用真实 Provider/API。该跳过项属于本阶段明确边界，不代表自动化或真实网络通过。

## 0.7.0 · 2026-08-28 · 独立设置页、旗舰大模型参数适配、70% 智能上下文压缩与动态监控

### 变更范围

- **全新独立 API 与模型高级设置页面（`SettingsActivity`）**：
  - 将原有受限的底部弹窗彻底重构为独立沉浸式设置界面，包含基础 API 源管理（支持 OpenAI/Anthropic/DeepSeek/GLM 等自定义协议与 BaseURL）、连通性测试与余额一键刷新、模型单次最大输出与上下文总窗口配置、上下文自动压缩卡片；
  - 与主界面 `MainActivity` 建立双向通信与状态同步，保存后即时无缝生效。
- **旗舰大模型标准与上下文参数体系（GLM-5.3 & DeepSeek-V4）**：
  - 单次最大输出选项扩展：`4K`、`8K (通用)`、`16K`、`32K`、`64K`、`128K (超大)`；
  - 上下文总窗口选项扩展：`32K`、`64K`、`128K`、`200K`、`1M`、`2M`；
  - 底层 `DemoActivityState.budgetForContextWindow` 动态会话预算扩容（2M 支持 800 轮/8万字符）。
- **70% 阈值三级阶梯智能上下文压缩引擎（`ContextCompactor`）**：
  - **Token 精确估算**：中英文（中文约 1.2 字符/Token、代码英文约 3.5 字符/Token）及 JSON 结构多语言加权估算；
  - **Level 1（工具输出剪枝）**：扫描非最近 2 轮中的超长工具输出，首尾紧凑折叠（`[历史输出已折叠: 原 N 字符...]`），零 API 成本释放 40%~60% 容量；
  - **Level 2（结构化摘要提炼）**：剪枝后仍超标时，将早期 50% 对话提炼为结构化阶段摘要节点，保留最近 3~5 轮完整活跃交互；
  - **Level 3（原子边界校验）**：首消息强校验为 `User`、`tool_use` 与 `tool_result` 成对存在，杜绝孤儿节点，严格符合各大模型接口协议。
- **底部上下文占用率动态进度条与四阶色彩指示器**：
  - 移除旧版静态提示语（`Agent 会按需调用工具...`），换装为现代优雅的上下文监控胶囊条；
  - **四阶动态变色**：`< 50%` 清新翡翠绿（`Ui.Success`）、`50%~70%` 天空蓝、`70%~85%` 琥珀橙、`≥ 85%` 警戒红；
  - **实时呈现与点击直达**：展示如 `● 上下文 18% (23.5K / 128K · 70%压缩)`，点击可秒级跳转至设置页调参。

### 当时证据与边界

- 全工程 JVM 单元测试共 274 个全部 GREEN 通过（包含 `ContextCompactorTest` 6 个用例、`ApiContextSettingsTest` 序列化与预算测试、`ApiQuotaAndConnectivityServiceTest`）；
- `:demo-app:assembleDebug` 编译通过，APK 元数据为 `versionCode 8 / versionName 0.7.0`；
- 小米真机 `QSG6Q8IFDMDELVGQ` 部署成功，实测独立设置页调参、连通性探测、超长会话 70% 阈值压缩以及翡翠绿实时指示条均运行完美。

## 0.6.0 · 2026-08-27 · 文件型 skill 运行时与 agent-memory 记忆 skill（已保存）

### 变更范围

- 新增独立模块 `pi-agent-skill-runtime-android`：SKILL.md 文件型 skill 规范（手写扁平 frontmatter 解析，标准字段 name/description + `x-ugk-load`/`x-ugk-embed-files`/`triggers` 扩展字段）、`SkillRepository` 实时扫盘、三级加载策略（always 全文常驻 / indexed 元数据桩 + `skill_read` 按需 / triggered 关键词）、`FileBackedSkillProvider` 合并式 Provider（含命名根实时嵌入，embed 内容每次 `skills()` 调用现读活数据）、`LoadPolicySkillResolver`（静态 plugin skills 行为零劣化）。
- 新增工具：`skill_list`、`skill_read`、`memory_list/read/write/delete`；记忆沙箱限定 `filesDir/agent-memory` 四分类白名单（user-profile/preferences/facts/rules），单文件 16KB 上限；`memory_delete` 默认 `UserConfirmationRequiredTool` 包装（全授权旁路沿用既有机制）。
- 第一个预制 skill `agent-memory`（always 策略）：捕获协议为"先在对话中征询同意 → memory_read → 合并不得丢条目 → overwrite 覆写 → 简短确认"，preferences/rules 经 `memory:` 命名根每轮实时嵌入常驻上下文，跨会话自动回放；幂等种子机制绝不覆盖已有目标。
- 核心最小改动：`AgentRuntime.Builder.skillProvider()` 从"立即拍平静态快照"改为持有 Provider 引用、每 run 拉取（源码级公共 API 设计无新增；当时接手环境的 inventory 脚本输出与历史台账口径仍需复核，见下方证据）。demo 前后台共用工厂一处接线。
- 文档：新增 `docs/android-agent-skills.md`（事实源）、`D-022` 及两条勘误、根 `AGENTS.md` 模块表更新。

### 当时证据与边界

- 全工程 JVM 单元测试共 258 个（基线 198 零回归 + 新模块 58 + 核心 2）、0 失败；`:demo-app:assembleDebug` 通过；APK 元数据 `versionCode 7 / versionName 0.6.0`。
- 核心公共 API surface：本次接手在生成的 Release AAR 上运行 `scripts/sdk/inspect-core-api-surface.ps1`，输出 `707` 个 javap public member signatures；远端 v0.6 台账记录的 `575` 与实际输出不一致，需下一阶段确认 Kotlin 生成成员/基线和脚本统计口径，暂不把“零变化”作为该次已验证结论。
- 真机 `QSG6Q8IFDMDELVGQ`（REDMI Turbo 5 Max，Android 16）安装启动正常、种子 SKILL.md 就位（always + memory: 嵌入配置）、logcat 无 crash；用户完成记忆捕获/回放初步对话验收并反馈可用。
- 边界：记忆捕获的"先征询后写"依赖模型对 skill 文案的遵从（模型偏差表现为未经同意写入，属行为问题而非运行时缺陷）；同一 run 内 provider 与 resolver 各扫盘一次属已接受设计；agent 自沉淀 skill（`skill_save`）列为 v2 展望；全授权模式下 `memory_delete` 不弹确认对话框（对话内复述确认仍由 skill 协议约束）。

## 0.5.0 · 2026-08-27 · 通用 Android 定时任务（已保存）

### 变更范围

- 新增独立模块 `ugk-agent-task-runtime-android`，把 `AgentTaskStore`、`AgentTaskScheduler` 适配为 Android 持久化 Store、通知任务的 `AlarmManager`、Prompt 任务的 `JobScheduler`、开机/升级恢复广播和通知 Sink。
- Demo 注册定时任务 Skill/Tool，并在 Android 13+ 启动时申请 `POST_NOTIFICATIONS`；`NOTIFY_USER` 用于提醒，`RUN_AGENT_PROMPT` 会通过 `AgentTaskJobService` 真正唤醒一轮 AgentRuntime。
- Demo 前后台共用 Runtime 工厂；后台按任务的 `sessionId` 恢复会话，使用 `AgentRunSource.SCHEDULED_TASK` 执行 Provider、无障碍屏幕、视觉、剪贴板和终端 Tool，并把任务输入与结果持久化回同一会话。
- 无交互确认窗口时，受保护动作默认安全拒绝；仅当用户显式开启全授权时，后台才允许执行这些动作。
- 增加重复任务状态迁移、失败收敛、Prompt 结果通知、时间算术溢出校验、会话重建和后台确认兜底测试。

### 当时证据与边界

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

### 当时边界

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

### 当时未完成证据

- 本轮尚未重新执行人工悬浮窗、Activity 重建和键盘触控验收；连接的真机回归覆盖的是自动化 Demo/Runtime 测试。
- 未覆盖的人工场景仍需使用该版本 APK、该版本 tag 和设备序列号重新记录，不能用旧版本报告替代。

### 2026-08-25 屏幕自动化稳定性补强（不升版本）

- `screen_read_ui_tree` 增加屏幕尺寸、非自身窗口数、截断标记和有界 `max_nodes`；`screen_gesture` 改为按当前屏幕尺寸生成滑动终点并拒绝越界输入。
- `screen_perform_action` 对未知动作和缺失 `set_text.text` fail-closed；屏幕树和动作窗口路径补齐节点回收；手势回调等待改为可取消协程等待。
- 新增坐标边界、屏幕尺寸适配和输入拒绝 JVM 回归；当时 Debug APK 在 Pixel 8 / Android 17 Preview 与 SM-A526U1 / Android 14 上各完成 `14/14` connected instrumentation。
- 两台设备本轮均未启用 Demo AccessibilityService，因此跨 App 的真实微信/设置/浏览器无障碍操作仍未形成当时版本证据；本条不提升 `versionName` 或 `versionCode`。

### 稳定化测试期版本边界

- 在当时的稳定化测试期，本次保存不提升 `versionName` 或 `versionCode`；`0.2.1 / versionCode 3` 继续代表该阶段的 Demo 测试交付物。
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
