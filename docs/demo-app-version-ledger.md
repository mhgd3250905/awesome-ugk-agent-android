# demo-app 版本与变更台账

更新时间：2026-10-01
当前本地测试版本：`1.13.0`（`versionCode 126`）
版本元数据范围：仅 `:demo-app`；本地收束也包含尚未发布的 `pi-system-skill-android` 源码调整，其模块/AAR 版本未改变。
当前阶段：2026-10-01 第九轮（教学整理链路）与第十轮（终端本地 HTTP 服务）两次 P0 审查分支经 AI 审核、修复并合并入 `main`，本阶段比较基线为 2026-09-30 收束点 `88b05e029996058300f4d7780782a7c0fbd8622d`，收束时 `HEAD` 为 `7a16065b1871dd082d1d5281ceedda2cf43451e7`。版本号保持 `1.13.0 / 126` 不变，下一发布版本仍自 `127` 起。`f3857e3` 起的 09-29—30 教学整理改进与 `demo-app-v1.13.0` 标签属于较早边界，历史验证不能代表本阶段最新实现。

发布边界：`1.0.3`、`1.0.4`、`1.0.5` 已发布到 Play 内部测试轨道（`1.0.5` 的发布证据为 2026-09-29 轨道页"发布时间：9月3日 00:19"，补齐此前缺失的外部观察）；封闭测试全球轨道此前停在 `1.0.2 (102)`；`1.1.0`–`1.12.0` 未提交 Play。`1.13.0 (126)` 已于 2026-09-29 10:25 发布到内部测试轨道，并提交封闭式测试轨道送审（Google 审核中，审核通过前封闭轨道用户仍见 1.0.2）。本地提交或标签不代表 Play 发布，已存在的 `demo-app-v1.0.5@11d764a` 版本边界保持不变。

## 2026-10-01 · 阶段收束：第九/十轮 P0 审查合并与真机验收环境重置

**合并内容。** `fix/p0-review-round9-20260930`（第九轮，教学整理链路 8 项修复）经 AI 审核后补修 2 项审核发现（合并无进展守卫的死条件改为轮前批次数比较并新增判别用例；单批次回查不再建议"换批次回查"），以 `9af8e40` 推送、合并为 `82699ed3`；`fix/p0-review-round10-20261001`（第十轮，本地 HTTP 遏制链/假绿门禁/生命周期真值）审核无新缺陷，与合并后 `main` 仅 `docs/terminal-runtime-validation.md` 尾部冲突（§34+§35 顺序保留），解决后合并为 `7a16065b`。两轮的发现、修复与未证实项以各自 PR 描述及验证文档 §34、§35 为事实源，本轮不复制其正文。

**验证证据。** 合并后 `main@7a16065b` 独占 `--rerun-tasks` 全量十模块 JVM 门禁 `858 tests / 0 failures / 0 errors / 3 skipped`（191 任务全执行；demo 332、terminal-runtime 50、terminal-skill 44）。设备通道另见验证文档 §36：2026-10-01 在日用 MIUI 真机上五轮尝试后定性为设备环境阻塞，UI 仪器套件的绿以 AVD 为准。

**真机与数据状态（重要）。** 2026-10-01 设备门禁尝试期间，一次 `connectedDebugAndroidTest` 对手机 `QSG6Q8IFDMDELVGQ` 上的应用做了卸载重装，应用私有数据被清空：2026-09-30 快照所列五份教学记录（SHA 清单 `.verify-shots/teaching-skill-before-data.txt`，PC 侧无内容副本）、用户其后新增教学与 `shared_prefs` API 配置均已丢失，未从任何备份恢复。当前手机安装同钥（`ugk.debug.keystore`）最新构建 `demo-app-debug.apk`（SHA-256 `0f7d5490fe2315eb313f2f2ced3489c5c857efd4f7f2f485e4059e01e911a052`），悬浮窗、无障碍授权已重授，数据为空、待用户重新配置并手测。**事故教训已固化：用户在用的包禁止推装/卸载；仪器门禁必须先 `run-as` 备份或使用 AVD。**

**验证范围与剩余工作。** 版本号未 bump（下版自 `127` 起）；独立台账仓 `awesome-ugk-agent-android-ledger` 的交叉订正仍由其台账 PR 处理，不在本仓范围。真实模型端到端的教学整理手测（短/长教学、取消/重试、指南读取）在数据清空后需以全新教学重新验证；第九轮修复的失败码（`EVIDENCE_MERGE_NO_PROGRESS`、`images_budget_dropped`、`compile_failed` 等）可经 `compilation-diagnostics.jsonl` 在真实手测中核对。

## 2026-09-30 · 阶段收束：教学整理与专用 Skill

**基线与范围。** 在原目录 `E:\AII\ugk-android-new`、`main` 分支整理从 `f3857e32b4e16b54c80393a01b84d46fe8643948` 起的教学相关改动；收束前 fetch 确认该基线与 `origin/main` 一致。版本仍为 `1.13.0 / 126`，本次保存源码及文档检查点，不创建版本标签或远端发行版。既有 Terminal/SDK 确认票据改动在基线中，不纳入本阶段功能变更。

| 处置范围 | 本次处理与事实来源 |
|---|---|
| 教学代码与 `teaching-sop-author` 资源 | 保存详情/进度弹窗、证据准备、分批与缓存、文字 SOP、审核交付、流式错误处理、专用 Skill；以当前源码和 APK 内资源为准 |
| README、文档入口、教学设计、UI 基线、Skill 规范及本台账 | 对齐当前流程、阶段加载与发布边界；区分内置整理 Skill 与为每份经验安装 Skill；保留每轮历史错误、修复与验证，不另建台账 |
| 代码注释与已有编译测试 | 注明缓存不代表最终审核、旧 JSON 解码仅供兼容；保持原有 8 个编译用例，将既有流程场景适配到自然语言笔记/草稿和工具交付，不新增用例。早期 8 个证据准备用例一并保存 |
| 本地探针、Play 材料、运行产物和配置 | 保留原位置且不纳入提交：3 个未跟踪 AndroidTest 探针、根目录 Play 截图、`playstore/`、被忽略的 `.verify-shots/`、APK、`local.properties` 与本地凭据 |

**检查结果。** `:demo-app:assembleDebug :demo-app:compileDebugUnitTestKotlin --console=plain` 成功，213 tasks、7 executed；日志 `.verify-shots/teaching-closeout-build.log`。这是 APK 构建和测试源码编译，没有执行测试用例。此前专用 Skill frontmatter 校验及四份 Markdown 打包字节核对通过，最新安装及五份教学 record/cache 哈希保留证据见下节。本轮 10 份 Markdown 的 102 个本地链接目标检查通过，阶段关键词已复核；首次暂存检查发现两个新增文件末尾有多余空行，移除后 `git diff --check` 和 `git diff --cached --check` 均通过。

**验证范围与剩余工作。** 遵循用户此前“由我操作测试、不做全量验证”的要求，本次不运行单元测试、全模块回归、仪器测试或真实模型请求；较早的 16 项、355 项等通过数量仅属于各自历史快照。当前需要用户手测短/长教学、取消后重试、旧经验读取，以及新 Skill 下最终交付质量和耗时。Terminal Gate、Release/Play 验证不适用：本阶段没有改变 Terminal/SDK 源码、打包配置或发布目标。旧快速审核失败的精确服务原因仍因历史日志缺失而未知，新的错误分类不能倒推出旧原因；Play 菜单点击问题也未在本阶段解决。

**接手入口。** 先读本节及 [教学设计](demo-operation-learning-design.md)，再按需读 [Skill 装配边界](android-agent-skills.md#demo-教学整理专用-skill) 和 `teaching-sop-author/SKILL.md`。下一步以用户手测反馈迭代整理方法或实际失败环节；保留原始教学、缓存和已整理经验，不重置用户改动，不把本地构建或 Agent 文字核对写成真机任务成功，也不触碰上述排除材料。

以下各轮记录保留其发生时的提交/验证状态；其中早期“未提交”“旧检查点可复用”等描述按对应轮次理解，不替代本节与当前设计规范。

## 2026-09-30 · 本地未发布：教学整理专用 Skill

新增 `assets/teaching-skills/teaching-sop-author/SKILL.md` 和提炼/合并、编写、审核三份阶段参考，沉淀纠正优先、证据判断、去噪取舍、分步骤写作及定向回查交付方法。`DemoTeachingSopSkill` 在整理的 IO 路径一次读取 APK 资源快照；提炼、合并与编写通过 `AndroidSkillPromptBuilder` 注入对应阶段，审核 Runtime 注册专属 provider 和必选 resolver，后续工具回合与交付提醒继续携带同一 Skill。普通聊天的 Skill 仓库和种子机制不变。

移除 Kotlin 中重复的业务提示，仅保留工具协议与运行边界。Agent 自主组织、修订并明确交付自然语言 SOP，不新增固定标题、字段或步骤数门槛。实际注入提示进入原有缓存指纹，Skill 更新后相关笔记和草稿重新生成，原始记录和已有经验保留；后续同内容重试仍可复用。现有六处测试构造调用仅机械补入同源资源 reader，未新增用例或执行自动化测试。

已完成独立代码复核、Skill frontmatter 静态校验、`git diff --check` 及 `:demo-app:assembleDebug --console=plain`，构建成功（211 tasks，10 executed），日志 `.verify-shots/teaching-skill-build.log`。核对 APK 中四份 Markdown 与源文件字节一致，且不存在通用 `agent-skills/teaching-sop-author` 目录。未请求真实模型，整理质量与耗时由用户手测，不能将构建成功记作模型验收。

安装前确认四份教学均未录制/整理中。`adb install -r` 至真机 `QSG6Q8IFDMDELVGQ` 返回 Success，更新时间 `2026-09-30 01:12:54`。设备与本地 APK SHA-256 均为 `9b614c9a041c5a32ed1bc02d0d3bd68d108f21ae0cbf6a00b403a4acbb418fe1`；安装前后五份 record/cache 文件哈希一致，清单为 `.verify-shots/teaching-skill-before-data.txt`、`.verify-shots/teaching-skill-after-data.txt`。版本仍为 `1.13.0 / 126`，未发布。

## 2026-09-29 · 本地未发布：审核请求失败原因保留与流式回查

用户报告点击重新整理后0秒显示“Agent 核对尚未完成”。设备检查确认8份步骤笔记与4,092字符的 SOP 草稿已经缓存；重试直接请求最后审核（37,424字符、无图），最近几次在约0.4～1.6秒出现 REQUEST_FAILED。原实现未保留 HTTP 状态/具体网络异常，无法事后确认这些请求是鉴权、限流、额度还是连接错误。更早的审核曾在167,134ms返回3次回查工具，下一请求累积121,731字符及4图后180秒 SOCKET_TIMEOUT；两类失败不能混为同一原因。

修复普通异常经 Runtime 后被泛化吞掉的问题：教学 Provider、Compiler 和审核 Agent 使用 DemoTeachingRequestFailure，保留安全的 HTTP 状态、正文提供的服务错误码/请求编号及网络类别，不显示或记录原始错误正文。教学链路全程透传 SDK 流式响应，取消可主动断开连接。审核回查每页12k、每轮合计24k字符，返回 total/nextOffset；默认无图，显式取图每轮最多一批。原已完成检查点的键和正文不变，仍可直接复用。

按用户要求未执行全量或自动化测试、未代发真实模型请求；本轮只做代码复核、Debug 构建和安装核对。`:demo-app:assembleDebug --console=plain` 成功，日志 `.verify-shots/teaching-review-request-build.log`。APK SHA-256 为 `0758369fc6d79ea546214d0c62c655eeaeb31ae51fc90c8313390ae58fd9e960`；`adb install -r` 至 `QSG6Q8IFDMDELVGQ` 返回 Success，更新时间 `2026-09-29 23:28:07`，设备 APK 哈希匹配。目标 record.json 和 compilation-summaries.json 安装前后分别保持 `1ad698a21aaadcfa79914518b52bb2ee19415f4b052e6fe530077bfa16d8c1ac`、`89b0b88a3ad234e63a37cb9cd871ac4c3ffe5b2ab0edbc6474067b9823bb6dce`。最终审核交付仍待用户手测，不能将本次构建结果记作真实模型验收通过。

## 2026-09-29 · 本地未发布：Agent 编写、核对并交付 SOP

用户再次手测后，设备诊断明确为首批输入34,468字符/3张图，68,325ms后收到2,286字符正文及9,689字符推理，stopReason=end_turn，随后 JSON_SYNTAX。该次失败发生在本地正文解析阶段，没有报告超时或输出截断。核对当前配置的路径后确认实际是 GLM 的 Anthropic 兼容接口；上一轮仅凭域名/模型推断为 OpenAI 接口有误，原 Anthropic SDK 已传 max_tokens，OpenAI 专用参数补丁没有覆盖实际路径。

按用户最新要求，改为自然语言步骤笔记与 SOP 草稿；最终使用独立 AgentRuntime 审核会话，由 Agent 自主回查原批次/截图、修订并通过 submit_reviewed_sop 明确交付。内容质量由 Agent 核对，不再要求模型正文满足固定 JSON 字段或标题结构。保留请求完整性、取消及容量边界；审核最多12次请求，交付工具成功立即结束。已完成笔记及草稿持久复用，审核未交付不能标为整理完成。

新 document 正文完整保存并用于 Markdown 展示、复制和获准复用，标题/目标等仅作可选检索信息；旧结构化经验继续读取。进度弹窗增加“Agent 审核交付”，已交付正文显示“Agent 已核对”。Agent 文字核对与后续真实设备使用验证保持各自含义。

未新增或运行自动化测试，未调用真实模型代替用户手测。`:demo-app:assembleDebug --console=plain` 成功，日志 `.verify-shots/teaching-sop-agent-build.log`。APK SHA-256 `1a585b273db6059baa9ae2ff766a6b35bf0d18c4214881c46c38882de4869d81`；`adb install -r` 安装到 `QSG6Q8IFDMDELVGQ` 返回 Success，设备更新时间 `2026-09-29 22:20:46`，设备 base.apk 哈希匹配。目标原始 record.json 安装前后均为 `2e55875d0bbfd1e4118f2af8168146e3834f0250cf6beb3269d68c5cee40e661`。版本保持 `1.13.0 / 126`，本轮未发布。

## 2026-09-29 · 本地未发布：整理响应兼容、分批复用与诊断

用户在界面优化包中整理9段、62次操作的记录，约3分10秒后收到“模型返回的内容不完整”。旧包把格式/长度错误混为同一提示，且没有保存本次整理响应的诊断信息，现有日志不足以确认这次失败的具体触发条件。当时新增教学专用 OpenAI 协议适配，传 max_tokens、reasoning_effort=low、thinking=enabled，普通聊天及 SDK 不修改；当时误判用户配置走 OpenAI，后续核实为 Anthropic 兼容路径，纠正与最新诊断见上条。协议依据见[教学设计文档](demo-operation-learning-design.md)。

响应解析改为先解开完整 JSON 围栏/单层字符串包装，再按规范化后的内容长度校验；兼容仅含 text 字段的文字数组项，截断、缺字段和类型不符仍拒绝。失败显示批次与明确原因，并在应用私有目录记录有界元数据；不保存模型正文、推理或凭据。成功且校验通过的步骤摘要按模型、提示、材料和图片指纹缓存，用户重试可复用；升级前未保存的批次不能恢复。

按用户要求未新增或运行测试、未调用真实模型复测。`:demo-app:assembleDebug --console=plain` 成功（日志 `.verify-shots/teaching-response-build.log`）；当前 APK SHA-256 为 `4c8747341dcbfa21d1a01c3f9a23377d602d2cdebcb639d2a7f0caf636d7c599`。`adb install -r` 覆盖安装至 `QSG6Q8IFDMDELVGQ` 返回 `Success`，更新时间 `2026-09-29 22:00:33`，设备 base.apk 哈希与本地相同。目标教学 record.json 安装前后 SHA-256 均为 `e74f9d646306ddd790eb2e7b8555a1be8ad584aa6b4c8b6733c624220fc85bfa`。版本保持 `1.13.0 / 126`，真实长教学整理效果待用户手动验收。

## 2026-09-29 · 本地未发布：教学详情与整理弹窗优化

按用户真机反馈，教学详情改为记录概况、最佳实践与可展开的逐段过程；整理使用主按钮，继续教学为次按钮，删除/复制/经验状态操作移入右上角更多菜单。整理确认复用现有教学经验纸张弹窗。新增 `DemoTeachingCompilationDialog`，处理时阻塞页面交互，展示当前阶段、实际完成批次、用时及取消入口；跳过阶段不显示成已执行，失败后保留原因并可明确重试，成功后返回记录。

针对长任务等待问题，提炼/合并材料从160,000降至48,000字符，每批最多6张实际附图；请求协程时限从90调整为210秒，底层读取180秒，不隐式重试。主动取消后关闭弹窗，尚未退出的阻塞请求由页面提示正在取消，收尾前不允许重新发起。

按用户要求仅做必要构建及安装核对，未新增或运行自动化测试，长教学真实模型与界面交互交由用户手动操作。`:demo-app:assembleDebug --console=plain` 成功，日志 `.verify-shots/teaching-ui-build.log`。APK SHA-256：`f4a032d6080c84524205a6cee64bd52f67def40b0ab9a1be2a248c17ca716288`；通过 `adb install -r` 覆盖安装至 `QSG6Q8IFDMDELVGQ`，返回 `Success`，设备更新时间 `2026-09-29 21:38:02`。版本仍为 `1.13.0 / 126`，原有数据保留；此改动未发布到 Play。

## 2026-09-29 · 本地未发布：长教学按步骤清理与分批整理

当前工作树把“整理最佳实践”的整份记录长度拒绝改为“本地按步骤清理 → 长记录分批提炼 → 最终汇总”。已知工具只清理重复或非语义字段，保留用户指令、纠正、动作及错误、页面状态和完成证据；历史保存时已截断的结果标记为可能不完整。单段拆批保留段背景，超大单项分片不删除中间文字；截图跟随对应步骤提炼，摘要保留实际附图来源。原始记录和截图不修改。

整理入口显示阶段与批次并可取消，确认说明长记录可能产生多次模型请求；进程内排他及原子记录状态领取阻止重复启动，原子保存完成后不会被取消分支改成失败。模型生成的摘要仍需最终全局核对，后段纠正可以撤销前段目标。

实际检查及范围：

- 2026-09-29 20:52（本地时间），`DemoTeachingCompilerTest` 8 项、`DemoTeachingEvidencePreparationTest` 8 项通过，失败/错误/跳过均为 0；使用模拟模型验证长记录分批、原始材料保留、后段纠正、层级汇总及失败/取消。报告为 `demo-app/build/test-results/testDebugUnitTest/TEST-com.ugk.pi.android.testapp.DemoTeachingCompilerTest.xml` 与对应的 `DemoTeachingEvidencePreparationTest.xml`。
- 此后补齐跨批段背景、实际附图来源及取消保存竞态保护；这些收尾改动做了静态复核和 `:demo-app:assembleDebug --console=plain` 构建，未重新运行单元测试。最终构建成功，仅有现存 Android 弃用警告。
- 首个分批整理 APK：`demo-app/build/outputs/apk/debug/demo-app-debug.apk`，SHA-256 `a47c237dca957e93d9c3633c0a5ca112072db2408162a7bdd9efb91d97fd58dc`。2026-09-29 按用户要求通过 `adb install -r` 覆盖安装至小米真机 `QSG6Q8IFDMDELVGQ`，返回 `Success`；设备安装更新时间为 `2026-09-29 21:14:00`，安装后的 `base.apk` SHA-256 与本地一致，应用数据目录 inode 及首次安装时间保持不变。未用真实模型验收长教学整理质量；后续 APK 见上方界面优化记录。

源码版本仍为 `1.13.0 / 126`；此改动未提交、未打标签、未发布，不改变下方已发布版本的事实。

## 1.13.0 · 2026-09-28 · 分段对话教学与悬浮/视觉链路收敛

### 当前行为

- “教我操作”要求已配置可用模型、用户已开启全授权、无障碍服务已连接、悬浮窗权限可用且设备处于解锁交互状态。模型未配置时不能开始，不提供无模型录制路径。
- 教学开始后回到手机主页，使用与普通对话相同的悬浮聊天窗分段指导 Agent；当前段结束后等待用户继续或纠正。停止只中断当前段，结束才保存本地教学记录；整理必须由用户明确发起。
- 普通对话可检索整理后的教学经验，用户明确选择后才读取步骤；模型报告实际结果，用户核对完成条件后才能将经验标为可用。经验不按旧坐标重放，也不另装专属 Skill。旧录制草稿和旧工作流继续兼容，和新教学记录分开存放。
- 经验使用回执按当前用户请求绑定，不依赖会被 transcript 压缩改变的历史消息数量；续教或整理中的记录会禁用经验状态按钮，竞态写入也会显示提示，不让 Activity 崩溃。
- 悬浮窗只做串行横向位移：源对象完全离场后目标对象从同侧进场。展开时气泡离场180ms、面板进场240ms；收起时面板离场200ms、气泡进场180ms。没有透明度或缩放过渡。动画期间由各自根 View 消费窗口内触摸，不设置 FLAG_NOT_TOUCHABLE，避免系统把覆盖窗透明度强制降到0.8。
- 普通对话与教学共用屏幕工具收起/恢复逻辑。截图和手势会移除悬浮窗后执行；启动其他 App 的 launch_android_app 与 launch_android_app_intent 则保留可见窗口，因为真机日志证明移除窗口可能导致 Android 静默拒绝后台启动。
- 视觉手势不因模型耗时或固定15秒期限拒绝，也没有点击前像素帧差门禁。仍校验观察ID、目标App、屏幕几何/旋转和目标坐标范围，并限制观察单次使用。点击后画面比较只作诊断，不证明按钮响应或目标任务完成；画面变化不用于判定其原因。

### 本轮验证与已知问题

- 真机悬浮窗纯位移动效已在小米 QSG6Q8IFDMDELVGQ 上验证。修复系统强制透明后，七项定向窗口探针通过，系统窗口 alpha 告警为0，前后录屏确认进出场不再透底。证据保留于 .verify-shots/overlay-system-opacity-before.mp4、overlay-system-opacity-after.mp4、overlay-system-opacity-device.log、overlay-system-opacity-after.log、overlay-opaque-slide-device.log。
- App 启动回归已用真机探针确认：保留悬浮窗时，从后台请求打开 Google Play 能到达前台。对应系统日志包含 BAL_ALLOW_NON_APP_VISIBLE_WINDOW/result2；证据为 .verify-shots/launch-visible-overlay-build.log 与 launch-visible-overlay-device.log。此结论只覆盖启动工具，不覆盖 Play 内部按钮点击。
- 慢视觉模型探针把决策延迟模拟为40秒；目标按钮仍收到点击，背景动画变化不再拦截手势。Android 16 的敏感 View 测试可以复现“派发回调成功但 click listener 未触发”，但尚未证明 Google Play 目标按钮采用了相同保护。证据为 .verify-shots/visual-revalidation-build-final.log 与 visual-revalidation-device-3.log。
- Play“管理应用和设备”仍有未解决的点击问题：真实无障碍手势回调成功但页面未跳转；用户的另一款 App 点击正常，ADB 同坐标可进入管理页。当前证据缩小了范围，不能确认根因；不要记作已修复。相关探针和设备日志见 .verify-shots/gesture-diagnostics-protected-device-final.log、gesture-diagnostics-device-actions.log、no-pixel-gate-device.log。
- 真实模型教学闭环曾在 API 35 模拟器完成三段“打开时钟 → 计时器 → 按用户纠正进入秒表”，记录 2e456015-c0b9-4369-a1d5-168e72b8ed6f：3段、16次工具调用、3次屏幕动作、3张操作后截图，并整理出3项经验。该样本验证教学记录流程，不代表第三方 App 稳定性或 Play 点击已解决。设备报告为 .verify-shots/teaching-device-3.log。

2026-09-29 清理后执行：

```powershell
.\gradlew.bat :demo-app:testDebugUnitTest :pi-system-skill-android:testDebugUnitTest :demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --console=plain
```

**审查前基线复验：353 项 JVM 测试通过**（Demo 303、System skill 50；零失败、错误或跳过），Debug APK 和 AndroidTest APK 均构建成功。本次没有运行仪器测试或全模块/Terminal Gate，也没有把该基线 APK 安装到设备；上方设备结果是先前的定向验证。`.verify-shots/` 内容仅为本机诊断证据，不作为唯一源码或版本事实源。该时点的基础源码由本地 checkpoint 保存；未打标签、推送或发布。

### 2026-09-29 深度审查与修复

- 教学经验回执存在已复现的数据一致性竞态：用户在“验证经验可用”确认框打开期间把经验改为“需修订”，旧实现先写入成功使用记录，再因状态已变化拒绝第二次状态更新，留下工具报错但历史记为成功的部分提交。回归测试 `stateChangeDuringValidationDoesNotLeaveAPartialUsageWrite` 在修复前失败、修复后通过；现在使用一次原子存储更新重验版本/状态并同时写入使用记录与可用状态。
- API 24/25 兼容性经 `:demo-app:lintDebug` 复核：此前直接调用 API 26 的 `java.nio.file` 与 `java.util.Base64`，而应用 `minSdk=24`，涉及草稿/教学/工作流原子文件写入和带图模型请求。已统一通过 API 21 的 Android `Os.rename` 原子移动，桌面 JVM 测试用隔离反射 NIO 路径；Base64 改为 Kotlin 标准库并新增 RFC 4648 编码用例。悬浮窗 API 30 Insets 调用补上调用边界标注。API 24/25 真机未连接，未做旧系统设备运行复现。
- 同轮修正 Lint 指出的可分发/主题问题：相机硬件声明改为可选、导航栏明暗属性限定在 `values-v27`，自定义进度图标改用 AppCompat ImageView。
- 修复后 `:demo-app:testDebugUnitTest` 与 `:pi-system-skill-android:testDebugUnitTest` 结果合计 **355 项通过**（Demo 305、System 50；零失败、错误或跳过）。随后用 `:demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --rerun-tasks` 强制重建两个 APK；Debug 与 AndroidTest 产物时间均晚于最终修复源码。`:demo-app:lintDebug` 成功，Lint XML 为 104 warnings、1 hint、0 errors；警告仍含依赖版本、弃用 API、方向建议等非阻断项。设备清单为空，未运行 connected AndroidTest 或安装 APK。
- 交叉核对发现原设计文档将 `efacf1b` 基线写成“尚未提交”，与实际已提交 HEAD 不符；现已改为准确区分已提交基础实现和本次未提交审查修复。版本号仍为 `1.13.0 / 126`，此次未升版本或创建新 checkpoint。
- 独立只读审查最终结论为 **PASS**，需求完整性、逻辑、边界、代码质量、测试覆盖和实际运行结果六维均通过；其唯一阻塞为 AndroidTest APK 时间戳过旧，已强制重建并复核时间后关闭。保留一项未证实的 P1 风险：长模型等待期间若同一应用内部页面变化但包名、屏幕尺寸和旋转不变，视觉坐标手势如何处理尚无设备探针覆盖；不得把该场景描述成已验证安全。

交付边界：本次审查修改尚在本地工作树；未触碰现有未跟踪设备探针与 Play 材料，未清理用户文件、推送、打标签、发布或安装。独立只读审查结论与后续修正记录见本节追加内容。

### 2026-09-29 Play 发布推进（1.13.0 / 126）

- 用户决定以 `1.13.0 / 126` 推进 Play 更新：内部测试与封闭测试轨道都发布；商店 listing 与截图保持 1.0.2 时代现状，只更新版本说明。权限与数据安全表单无需变更（1.0.2 以来无新增 Android 权限，本轮仅将相机硬件声明改为可选特征）。
- 审查修复以 `b8db716` 提交进 main（16 个文件：经验回执原子状态更新与回归测试、`DemoAtomicFileOps`/`DemoBase64` 恢复 minSdk 24 兼容、values-v27 主题资源等）；未跟踪设备探针与 Play 材料保持原状未入库。
- release 构建于同一提交源码执行：`./gradlew.bat :demo-app:bundleRelease :demo-app:assembleRelease` 通过（含 `lintVitalRelease`）。产物：AAB `demo-app/build/outputs/bundle/release/demo-app-release.aab` 39,369,556 字节，SHA-256 `1dadb215e3081fdd9d1f0676c1466be9470dd7125ccfa34aa7a2e1adf355c879`；同源 Release APK 39,945,609 字节，SHA-256 `14543942e70d17c24e6b6a884a87a0a0733aaff31280d58d9dea95b5fedb8df2`。
- `apksigner`/`keytool` 确认 AAB 与 APK 为同一 upload 证书（`CN=UGK Agent Upload`，SHA-256 `a7818fd48fb80e1a747f4bc3d2565b83c42ceff074d0a84cc5fceadbd6c693a0`，与 1.0.2–1.0.5 同一 keystore）；APK 元数据 `com.ugk.pi.agent / versionCode 126 / versionName 1.13.0 / minSdk 24`。
- 版本说明重写为 1.13.0 口径：封闭测试轨道中英文更新（en 495/500、zh 248/500 字符），内部测试轨道新增同文案中英文文件；均为 `playstore/` 本地材料，不入库。
- Agent 在 `round5_api35` 模拟器完成 release APK 安装与主界面启动初检后，最终 release 包验证由用户自行完成并确认通过；具体设备与路径未记录，不作 Agent 独立复核结论。
- Play 上传执行记录（Agent 浏览器自动化，开发者账号 Bboy Ug1yk）：同一 AAB（126）上传内部测试轨道并"保存并发布"，2026-09-29 10:25 面向内部测试人员发布；版本说明为 en-US/zh-CN 双语言。封闭式测试 - Alpha 轨道（178 个国家/地区，原版本 1.0.2 (102)）经"从内容库添加"引入同一 126 (1.13.0)、同样双语言版本说明，发布概览中"126 (1.13.0) 开始全面发布"已于 2026-09-29 约 10:30 送交 Google 审核（审核通常 7 天内完成；送审前有 Play 自动快速检查）。两轨道均仅 1 个非阻断警告：设备支持范围变化为净增 288 台（手机 49、平板 244、车载 10、Chromebook 35）、0 台减少，与相机硬件声明改为可选特征一致。证据截图：根目录 `playstore-internal-126-2026-09-29.png`、`playstore-closed-126-submitted-2026-09-29.png`（本机材料，不入库）。封闭轨道审核结果以 Play Console 后续实测为准，不得预填"已生效"。

### 已替代的方案

早期曾采用逐点确认录制、全屏触摸遮罩、贴边小球、透明度/缩放交叉渐变、固定15秒观察失效及手势前像素比对。这些方案已分别被分段对话、普通悬浮窗、纯位移动效和单次观察诊断替代，不属于当前行为。更早的 APK、失败探针与修复记录留在本机证据目录；以下 1.12.0 及更早条目仍按各自版本保存原始历史边界。

### 历史 APK 校验索引（均非当前构建）

| 阶段 | APK SHA-256 | 边界与证据 |
| --- | --- | --- |
| 首轮对话教学主流程 | `3e6dda82d66aac6199a038616e5c76d8d99ebc1640bf167150e84eef04c3938b` | API 35 时钟教学闭环；`.verify-shots/teaching-build-7.log`。后续悬浮窗和视觉操作修订不包含在此 APK。 |
| 教学侧边图标动效 | `026f26e5235b40606b53aa1fdcd99f677267ff1af24d3753e4073e21fe89a6c8` | 已安装的中间样式版本，后被统一悬浮窗替代；`.verify-shots/teaching-motion-final-build.log`。 |
| 全屏遮罩移除候选 | `8de85417f74a4775a167e13d1b3433a8d813cca17be10e406c5245ffa986881d` | 当时因保留设备中未结束教学而未安装；后续被普通悬浮窗流程替代；`.verify-shots/teaching-bubble-build.log`。 |
| 普通对话经验检索 | `a2629b0bbc99458615105d02e10316613c72c4d56a321807eefe654f960d70b9` | 经验确认/反馈探针版本；`.verify-shots/teaching-experience-build.log` 与 `teaching-experience-device-final.log`。 |

## 1.12.0 · 2026-09-28 · 手动学习与复用闭环（本机定向验收）

源码版本已设为 `1.12.0 / 125`，只提升 Demo 版本。入口仍是“添加与工具 → 教我操作”。录制结束先询问“怎样算完成”：目标与完成标准独立填写，可仅保存在本机或稍后补充；最后截图不作为成功结论。目标、标准和录制素材只有明确确认后才交当前 API 整理。审阅页显示整项标准、步骤、逐步完成条件与必要 AI 判断，允许编辑名称/目标/步骤标题/逐步完成条件及删除多余步骤。保存生成新版本，旧版本的试跑记录不能使新版本显示“可运行”；整项标准变更须再次确认整理，最新说明与计划标准不一致时禁止试跑/再次运行。“再次运行”要求当前 `version + digest` 有成功试跑记录。

新增 `DemoWorkflowCompiler`、独立版本/运行仓库、本地 `DemoWorkflowRunner`、受限动作网关及进程控制器。执行使用新鲜结构树进行唯一语义定位和结果检查，必要时才调用当前配置的视觉模型；不把每一步交回完整 AgentRuntime。运行前的原生确认绑定具体版本、目标 App、动作范围、画面分析、最多一次返回恢复及五分钟上限。跨 App 显示约 232×52dp 的状态/步数/停止浮条；已有录制浮条继续沿用 208×52dp。

采集与编译加强动作因果证据校验：缺少对应前后素材的旧草稿保留可查看，不能借其他事件的画面推测补齐；需重新录制。只接受声明过的语义动作与受限视觉判断，不接受模型生成的坐标、任意脚本、任意工具或跳步。停止、服务/锁屏变化及进程恢复均不会自动重放；已有 Agent、录制、非 Idle 计时和交互提醒受共享忙闲边界约束。

整理与执行沿用主 API 当前配置并固定本轮配置；GLM 是可配置的视觉服务之一，未硬编码为专用模型。模型请求数、发送图片数和耗时来自实际记录；本链路禁用隐式 HTTP 重试，不展示估算 tokens 或费用节省百分比。没有接入自然语言自动召回、聊天 `workflow_run`、定时执行或周期复用；本轮不改变既有计时语义或 Terminal Runtime Gate。

最终构建与 JVM 验证命令：

```powershell
.\gradlew.bat :demo-app:testDebugUnitTest :pi-system-skill-android:testDebugUnitTest :demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --console=plain
```

结果为 **312 项 JVM 通过**（Demo 268、System 44，零失败/错误/跳过），最终日志 `build-17.log`。覆盖编译证据、事件/目标候选、重复控件、版本摘要/完成标准、存储恢复、执行边界、取消、不重放、返回恢复、请求预算及传输不重试。未重跑无关 Terminal 全量矩阵。原生使用 `ugk_dev_api35_smooth` / `emulator-5580` / Android 15 API 35 / x86_64；未操作另一个 NuttX `emulator-5554`。

真实 Accessibility 录制为“设置 → 电池 → 电池用量”，9 条事件、4 帧，两次点击均关联自身前后画面。完成标准独立要求电量图表和屏幕使用时间区域可见，末尾以真实 `glm-5.3-flash` 视觉判断；未伪造整理结果或试跑记录。最终编译生成第 2 版的三个步骤，同版本及摘要完成试跑和再次运行：

| 环节 | 模型请求 / 图片 | 耗时 | 结果 |
| --- | --- | --- | --- |
| 最终整理（compile-6） | 1 / 3 | 65.598 秒 | 生成 v2，排除偶然电量、分钟数、日期条件 |
| 最终试跑（trial-4） | 1 / 1 | 8.948 秒 | 3/3 步完成 |
| 同版再次运行（rerun-1） | 2 / 2 | 11.002 秒 | 3/3 步完成 |

表内执行耗时来自运行记录，不包含仪器等待无障碍重连的约 10 秒。三个最终设备探针均各 `OK (1 test)`；这是限定样本，不代表成功率或对全视觉方案的节省比例。调试时存在被拒绝的编译和失败试跑，均保留在本机报告：已修正协议选择、动作证据提示及候选、偶然数值条件、把结果存在性误作唯一定位，以及页面切换时截图过快的问题；未放宽动作唯一定位或通过失败结果伪造“可运行”。

原生 UI 检查包含录后自动“怎样算完成”、本地保存、独立上传确认、标准变更后取消整理仍保存并禁止运行旧计划、浅/深色、360×640dp 短屏、1.3 倍字号、键盘避让和配置变化后字段恢复。最终审阅页显示“可运行 · 第 2 版”与“再次运行”，确认卡显示目标 App、标准及动作范围；最后一次查看确认卡后取消，未继续执行。

Debug APK 已以同签名 `install -r` 安装，路径 `demo-app/build/outputs/apk/debug/demo-app-debug.apk`，44,868,858 字节，SHA-256 **`a9f200396a711141c544cddb68ea5f69ef57d758c4d5af72ead0362afbff3303`**。32 个本轮源码/构建/测试文件校验记录于 `source-hashes.json`。本机材料：`.verify-shots/operation-workflow-20260928/REPORT.md`、`unit-results.json`、`probe-*.json/.log`、`completion-*.png`、`criteria-*.png`、`final-*.png`；本机材料不自动加入版本提交。

用户原草稿与五张图片共六个文件的 SHA-256 前后相同，原 API 偏好完整恢复，临时设备 API 文件已移除。期间模拟器意外退出后 APK 代码目录缺失，但原 userdata 保留；通过同签名覆盖安装恢复并做素材备份，未卸载、清数据或重置镜像。退出和代码目录缺失的根因未确认。最终恢复标准显示配置、连接无障碍服务，App 留在可运行示例详情。没有用户真机或任意第三方 App 的完整验收结论。

### 本阶段文档收束与交接

比较基线为 `0fa220879c34c43ea5b23482963c830d55c08114`，收束前 HEAD 仍为该提交，暂存区为空；阶段差异全部来自工作树。目标是保存已验收的手动学习闭环，并让当前规范、入口和验收台账一致。本次文档收束不改执行逻辑、不另升版本。

| 路径 / 类别 | 处置与事实依据 / 权威来源 |
| --- | --- |
| `demo-app` 本轮 32 个源码、构建及测试文件 | 保存既有实现；以源码及 `source-hashes.json` 对应的最终构建、JVM 和设备探针为证据 |
| `README.md`、`docs/README.md` / 当前入口 | 更新版本、能力与阅读入口；产品契约以演示学习规范为准 |
| `docs/demo-operation-learning-design.md` / 当前规范 | 对齐实现与范围；补 200 条运行记录上限，纠正本轮新增的逐节点两次预算及整理耗时持久化误述。基线仅规定检查点独立汇总预算，未规定逐节点硬上限；不改变用户契约来迁就实现 |
| `docs/demo-app-ui-redesign.md` / UI 基线 | 更新完成标准、上传确认、重新整理门禁与运行浮条；证据以实际原生检查为限 |
| `docs/demo-delayed-conversation.md` / 互斥契约 | 补充试跑/再次运行共享屏幕占用；计时执行仍是普通 Agent，不自动复用路径 |
| 本台账 / 当前及历史记录 | 汇总最终证据、限制与保存边界；1.11.0 及更早条目原样保留各自时点 |
| `HANDOVER.md`、Terminal 文档、两类 `AGENTS.md`、权限规范、既有代码注释 | 保留；历史交接已有 superseded 标记，未改变 Terminal/Core/权限契约，未发现本阶段需改的注释 |
| 本机截图、`.verify-shots/`、`.playwright-mcp/`、`playstore/`、构建产物和私密配置 | 保留并排除提交；未删除、迁移或新增第二套文档体系，生成物不作为唯一事实源 |

收束先核对 32 个文件 SHA-256 全部一致；暂存检查发现 Compiler/Repository 文件末行残留 CR，随后仅统一这两份文件换行，逐字符确认去除 CR 后与验收源码相同，无逻辑修改。最终 APK 哈希与上文一致；重算实际 JVM XML 为 268 + 44 = 312，零失败/错误/跳过。最终 compile-6、trial-4、rerun-1 的 JSON 均成功、配置恢复为 true，日志各 `OK (1 test)`。相关 Gradle 命令已在同一源码上执行通过（见上文 build-17）；本次仅文档修订，复用该证据，不重复构建、设备执行或付费 API 调用。中间失败已修复并由最终验证替代，完整过程保留本机报告。

文档相对链接存在性、关键词一致性、`git diff --check` 及暂存范围检查通过。其他模块 JVM、Terminal 双宿主/打包 Gate、完整 ABI/真机矩阵与 Release 构建未运行：本阶段未改变这些模块或发布形态，属于本地 Demo 定向收束，不把这些结果列作通过。无待决契约冲突；模拟器意外退出根因仍未知，第三方 App 的泛化可靠性、原生故障注入全矩阵和费用节省比例仍未验证。

下一会话先读 `docs/README.md` → `demo-operation-learning-design.md` → 本台账 → `demo-app-ui-redesign.md`。后续先由用户选择实际目标 App/完成标准并补充定向验收，再讨论聊天召回或定时复用；不能把后续设计当作已实现。保留原草稿、API 配置、本机证据和 Play 材料，不操作其他项目的模拟器，不自动重新运行原草稿。

以下 `1.11.0` 及更早条目保留当时事实；其“尚未实现”“下一步”等措辞只描述对应阶段。

## 1.11.0 · 2026-09-28 · 本地版本保存

基线为 `main@d7a7bad8162e5d29302cdfafe40011498b5c7fee`。用户在浮条验收后认可详情布局并要求“版本保存”。本条随本地 checkpoint 提交保存，提交主题为 `feat(demo): save v1.11.0 operation recording and draft UI`；版本仍为 `1.11.0 / 124`。没有 push、新 tag、Release 或 Play 发布。

| 范围 | 本次处置与事实来源 |
| --- | --- |
| `demo-app` 录制器、采集、存储、界面、服务桥及宿主互斥/派发/测试 | 保存已验证的 Slice A 与两轮 UI 改动；实际采集及恢复边界见演示学习规范 |
| Demo 计时意图路由、`pi-system-skill-android` 屏幕观察指令与测试 | 保存纯文字路由与按需截图协议；执行授权、目标时效及失败重试上限沿用既有校验 |
| `README.md`、文档入口、UI/版本/演示/计时/自动化规范 | 对齐 1.11.0、用户反馈和本地保存边界；修正根 README 的 1.10.0 与视觉优先过期描述 |
| 历史台账、`HANDOVER.md`、Terminal Runtime 文档与 SDK runtime `AGENTS.md` | 保留既有历史与契约；本次未改变终端功能、Core 公共 API 或发布 Gate |
| `.verify-shots/`、`.playwright-mcp/`、根目录截图、`playstore/`、构建产物和本机配置 | 保留本机内容，排除提交；不清理或覆盖 |

收束前核对了三轮实际验证记录的 **30 个源码/配置/测试文件** SHA-256，全部一致；随后仅更新文档，并移除 Activity 一处空行的多余回车字符，确认其余字符相同，没有逻辑变更。已运行命令与结果为：

```powershell
# Slice A 最终功能验证
.\gradlew.bat :demo-app:testDebugUnitTest :pi-system-skill-android:testDebugUnitTest :demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --max-workers=1 --console=plain
# 紧凑浮条更新后
.\gradlew.bat :demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --max-workers=1 --console=plain
adb -s emulator-5554 shell am instrument -w -r -e class com.ugk.pi.android.testapp.OperationRecordingControlsInstrumentedTest com.ugk.pi.agent.test/androidx.test.runner.AndroidJUnitRunner
# 草稿详情最终更新后
.\gradlew.bat :demo-app:assembleDebug --max-workers=1 --console=plain
```

各次构建均通过；现有 JVM XML 重算 Demo **206** + System skill **44** = **250**，0 failure/error/skipped，更新后的控件仪器测试 **1/1**。UI 与采集实测范围见下方三轮记录。最新 APK SHA-256 仍为 `F05E01B51BC7093D52AFAE7A57D0922EF6DF7996D673970D6BE7535CC20CDD0A`；详情验证后原有 7 份草稿哈希一致，临时设备设置已恢复。保存时检查差异、文档链接和显式暂存范围；本机收束证据位于 `.verify-shots/operation-learning-closeout-20260928/`。

没有重跑其他未改动模块的 JVM、Terminal 双宿主/打包 Gate、完整设备/ABI 矩阵或 Release：本阶段只涉及 Demo 与 System skill 观察文案，没有 Core/Terminal 接口或二进制改动，已有相关结果和文件校验足以支撑本地 checkpoint。没有重做模型付费实验或宣称节省比例；模型整理/执行尚未实现，不能作为验收通过项。

下一步先读 `demo-operation-learning-design.md` 与本台账，再推进 Slice B 模型整理、审阅和试跑。保留本机配置、既有用户数据、历史截图与 Play 材料。以下各轮的“未提交”“最终 APK”等表述仅指各自验证时点，当前保存边界以本条为准。

## 1.11.0 · 2026-09-28 · 草稿详情布局补充

草稿详情从文字堆叠改为暖纸摘要卡、双列计数及中性内容卡。空草稿提供小猫头鹰状态与“重新录制”；已有事件用单块时间线呈现。说明和关键画面按需展开，删除移到右上角更多菜单并保留确认；菜单读取失败给出提示，操作前重核草稿与录制状态。仅修改 `DemoOperationLearningActivity.kt`，沿用 `1.11.0 / 124`。

`:demo-app:assembleDebug --max-workers=1 --console=plain` 通过。API 35 AVD 验证浅色正常字号、深色 320dp/1.5 倍字号的空草稿及 7 事件/3 帧草稿，完成导航、说明折叠、关键画面展开、重新录制打开/取消、删除确认后保留的检查。原有 7 份草稿 JSON 哈希均未变化，临时字体、屏幕尺寸、主题及无障碍设置已恢复。此布局调整没有新增或重跑单元/仪器测试，没有调用模型 API。

最新 Debug APK 已保留数据安装至 `emulator-5554`，SHA-256：`F05E01B51BC7093D52AFAE7A57D0922EF6DF7996D673970D6BE7535CC20CDD0A`。截图与验证记录见[草稿详情报告](../.verify-shots/operation-draft-layout-20260928/REPORT.md)。用户随后回复“OK”并要求版本保存，设备未注明；认可及保存边界见上节。以下浮条和初版 Slice A 条目保留各阶段证据。

## 1.11.0 · 2026-09-28 · 紧凑录制浮条补充

根据用户反馈，录制浮条改为约 208×52dp 单行胶囊：左侧呼吸录制点与计时，右侧暂停/继续、结束两个图标，各保留 48dp 点击区域。暂停时指示点静止、时间冻结；动画遵循系统开关并随隐藏/移除停止，保留拖动和截图隐藏。沿用本轮 `1.11.0 / 124`，下节初版记录保留。

`:demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --max-workers=1 --console=plain` 通过；更新后的录制控件仪器测试 1 项通过，覆盖浅深色、1.5 倍字号、208×52dp 尺寸及按钮状态/回调。API 35 AVD 实测呼吸动画、暂停冻结、继续、拖动和结束保存；用户反馈“可以测试通过”，记为浮条调整验收，未指定设备。此 UI 调整未重跑下节 250 项 JVM 测试，也未调用模型 API。

最新 Debug APK 保留数据覆盖安装至 `emulator-5554`，SHA-256：`E30A1ED27979C0EDDA736446FF5A7EEC49344CFEA22BCDCB4D865B31237CCCD6`。证据见[紧凑浮条报告](../.verify-shots/operation-recording-compact-20260928/REPORT.md)。模拟器临时无障碍设置已恢复，既有草稿及新测试草稿保留；未提交、打标签或发布。

## 1.11.0 · 2026-09-28 · 演示录制 Slice A（初版记录）

新增主聊天附件菜单“添加与工具 → 教我操作”：原生暖纸开始卡、API 30+/无障碍/悬浮窗准备入口、可拖动录制条、暂停/继续/结束、本地草稿列表及事件/关键帧回看、明确确认后的删除。名称输入限 120 字符，键盘完成不自动开始。记录说明默认折叠，保留完整证据；从列表打开草稿时从标题开始显示。当前产物是原始事件、页面结构与画面，不称为已学习步骤，不提供 AI 整理或执行按钮。

进程级录制与普通 Agent 共用屏幕 owner；思考中任务、非 Idle 计时、排队消息和交互提醒阻止开始，录制期间禁止另起 Agent/SDK_EVENT、切会话/新建及设置。暂停冻结实际录制时长，失效在途采集；串行检查点和活动标记支持下次启动标为中断，不自动恢复。具体限制与当前代码见[演示学习规范](demo-operation-learning-design.md)。没有启用旧调度器或把 skill 保存当成本地执行。

阶段证据见[本机报告](../.verify-shots/operation-learning-integration-20260928/REPORT.md)：API 35 AVD `SettingsDemo` 草稿为 7 事件、3 帧、85 节点；暂停期间操作 Settings，草稿字节不变；完成自动打开真实草稿。输入页自动暂停且测试输入不入草稿；强制结束进程后保留 3 事件/1 帧并标为中断；关闭无障碍或熄屏会停止并保存。原生控件浅深色、280dp、1.5 倍字号检查通过；1080×1600 短屏下控制可达；App 内隐藏浮条、桌面恢复显示已实测。

部分 Settings 子页由系统隐藏普通 overlay，需回桌面或本 App 暂停/结束，不绕过系统限制。UIAutomator dump 会干扰该 AVD 的录制服务，采集中采用 screencap/自有草稿观察。尚无本版用户真机验收或 Play 发布。本轮 Slice A 未调用外部 API；模拟器临时字号、主题、屏幕尺寸、输入法标记和无障碍状态已恢复，安装和本轮草稿保留。

最终构建命令为 `:demo-app:testDebugUnitTest :pi-system-skill-android:testDebugUnitTest :demo-app:assembleDebug :demo-app:assembleDebugAndroidTest --max-workers=1 --console=plain`，`BUILD SUCCESSFUL`。JVM 测试 Demo **206** + System skill **44** = **250**，0 failure/error/skipped；另有 `OperationRecordingControlsInstrumentedTest` **1 项通过**。新增录制存储/证据关联测试覆盖恢复竞争、缺失/陈旧活动标记、损坏文件保留、受限临时图片回收及跨段/跨包拒绝关联；互斥测试覆盖普通 Agent 思考期及录制期间工具/运行拒绝。

最终 Debug APK 已保留数据覆盖安装到 `emulator-5554`，SHA-256：`CF3A755E19F20F5374187D6290D60D4E95A2766123CE97BF12987A9B74BD5A4C`。代码与测试清单校验值保存在本机报告旁的 `verification.json`；本阶段未提交、推送、打标签或修改真机安装。

## 2026-09-28 · 视觉调用开销优化（先行阶段记录，当时无新版本）

基于 `d7a7bad8162e5d29302cdfafe40011498b5c7fee` 的后续工作树。普通 Agent 改用按需观察协议：新鲜结构树足以定位或验证时可以不截图，未知/纯视觉内容和信息不足时仍使用视觉。结果验证、截图时效、目标校验、确认与失败重试上限保持原边界。截图附带的说明也明确区分视觉坐标手势与结构树语义动作，避免误导模型把所有动作都转为坐标点击。

Demo 的计时意图判断只发文字和附件存在标记，消除分类阶段重复上传图片；主 Agent 的原始请求和图片保持不变。附件内容决定动作或时间时交主 Agent 理解，仍保留计时提案工具。新测试验证请求传递、同步/流式分支和原有重试上限，不代表真实模型的分类准确率已实测。

验证命令（同一工作树，本机既有 Android Studio JBR）：

```powershell
.\gradlew.bat :demo-app:testDebugUnitTest :demo-app:assembleDebug --max-workers=1 --console=plain
.\gradlew.bat :pi-system-skill-android:testDebugUnitTest :demo-app:assembleDebug --max-workers=1 --console=plain
```

均 `BUILD SUCCESSFUL`。最新 XML：Demo `191`、System skill `44`，合计 `235` tests，0 failure/error/skipped；其中新路由器测试 6 项。最终第二次构建包含截图说明的补充修正。`git diff --check` 与文档链接检查通过。没有新增 API 调用、安装、设备操作、提交、标签或发布；现有真机版本保持原状态。本次不修改 Core 公共接口，不宣称已实现本地路径 runner 或已测得时间/费用节省百分比。

这次先行优化时演示页面尚未实现；后续 Slice A 已进入本版录制实现，见上节与[演示学习规范](demo-operation-learning-design.md)。本段测试数和未安装状态仅属于当时的优化阶段，不覆盖后续新增功能。

## 2026-09-28 · 第五/六轮 P0 审查修复合并（源码基线推进，无新版本）

demo-app 源码基线从 `c10773e`（`demo-app-v1.10.0` 标签）推进到 `main@c5e78fa`：PR #8（第六轮 5 项：SSE 流边界装配、任务收敛 tryLock、周期任务失败预算、确认拒绝语义、interval 守卫）2026-09-27 合并 `7d00ff0`；PR #7（第五轮 20 项：流式错误 surfaced、tool_calls 分片归并、重复任务零间隙锚定、通知投递真实性、skill_save 保留 id、memory_write 确认闸、终端元数据原子写、死记录清扫、token 200 判定、会话 store 互斥等）解决 4 处冲突后 2026-09-28 合并 `c5e78fa`。逐项清单与证据见 [`terminal-runtime-validation.md`](terminal-runtime-validation.md) §31。

这两轮均为缺陷修复，无可感新能力，按版本规则不提升 `versionName`/`versionCode`，不创建新标签；`1.10.0 / 123` 的 APK 校验值与真机验收记录仍对应 `2f5a56c` 时点构建。合并后门禁：全模块 JVM `630` tests / `3` skipped / 0 failure（`--rerun-tasks` 实跑）；API 35 模拟器 `:demo-app:connectedDebugAndroidTest` `62/62` 通过（含第五轮新增会话 store 并发压测）。合并前 PR #8 描述中"验证通过但第五轮修复未进主干"的易误读状态已解除。验证证据存本地 `.verify-shots/pr8-round6-merge-gate-20260927/`、`.verify-shots/pr7-round5-merge-gate-20260928/`（不纳入源码提交）。

## 2026-09-27 · 1.10.0 补充阶段收束

基线：`demo-app-v1.10.0` / `c10773ecf4341ff3bce1904694012e0372054913`。它保存此前已验收的悬浮对话统一阶段；本次在其后保存补充改动，不改写原标签条目、APK 校验值或 `1.8.0 / 1.9.0` 的历史交付记录。

| 范围 | 当前结果 | 事实源 |
| --- | --- | --- |
| 悬浮对话紧凑排版 | 小窗独立字号、气泡和列表间距、Markdown 与详情高度；主聊天保持默认样式。12:19 安装后用户反馈“好多了 验证通过” | [UI 规范](demo-app-ui-redesign.md)、[设计验收](../design-qa.md) |
| 进入 App 的权限引导 | 每次真实前台进入检查无障碍、悬浮窗、相机和通知，均可跳过；补齐实际提醒分类开关，选图/导入使用系统选择器 | [权限与后台引导规范](demo-app-permission-guide.md) |
| 已授权后关闭的恢复 | 共用请求/授权历史与实时查询；系统不再询问时打开对应设置，旧历史缺失时保留一次设置兜底 | [权限与后台引导规范](demo-app-permission-guide.md) |
| 可选后台运行建议 | 后台耗电、自启动独立入口和任务卡片锁定提示；确认只记录阅读版本，设置页与计时确认可再次打开 | [权限与后台引导规范](demo-app-permission-guide.md)、[计时边界](demo-delayed-conversation.md) |

最新完整包于 2026-09-27 13:55（Asia/Shanghai）保留数据覆盖安装到小米 `QSG6Q8IFDMDELVGQ` / `2602BRT18C`，版本 `1.10.0 / 123`，启动 `Status: ok`、进程存在。Debug APK SHA-256：`C704690C4AB18403B4AE4FC18053A7E81152F8A2DBE3D74090B9C4F233F6F7D9`。用户随后明确反馈“可以了 测试通过”，作为最新完整包的用户验收；具体点击路径未另行记录。

最终源码验证于同日最后一轮实现调整后、13:55 安装前完成：

```powershell
.\gradlew.bat :demo-app:assembleDebug :demo-app:assembleDebugAndroidTest :demo-app:testDebugUnitTest --console=plain
adb -s emulator-5580 shell am instrument -w -r -e class com.ugk.pi.android.testapp.PermissionGuideInstrumentedTest,com.ugk.pi.android.testapp.DemoDelayedTaskDialogInstrumentedTest com.ugk.pi.agent.test/androidx.test.runner.AndroidJUnitRunner
```

构建通过，179 项 JVM 测试为 0 失败/错误，API 35 定向仪器测试 12/12 通过。证据对应基线加本次提交中 21 个 Kotlin 源码/测试文件的工作树状态；最终调整包括精简建议文案、“我已了解，不再提醒”按钮和设置入口即时换色，均已纳入最终测试。验证后未再修改实现或测试，本次收束只对齐文档；历史各轮测试数量和 APK 不冒充最终包证据。实际设置往返、进程重建和视觉检查范围见权限规范；本地证据目录为 `.verify-shots/background-guidance-20260927/`。

用户验收不扩写为 Agent 已操作全部 OEM 开关、跨品牌验证或长期后台常驻。真实模型计时、重启/进程死亡后的续跑也不在本轮验收范围；既有进程内计时边界保持不变。本次只保存本地提交，无远端推送、标签或发布动作；本地验证材料、浏览器记录、根目录截图及 Play 材料保留，不纳入源码提交。

> 文首元数据、补充阶段收束和版本规则描述当前本地测试实现；其后的版本条目是不可改写的历史记录。
> 历史条目中的“当前”仅指该条目记录时点，不是今天的版本或验证状态。

## 版本规则

- `versionCode` 只递增，不因重新打包或覆盖安装回退。
- `versionName` 使用面向测试交付的 SemVer 风格；聊天、会话和悬浮窗等一组可感知能力完成后提升 minor 版本。
- 稳定性修复、生命周期恢复和验收证据整理使用 patch 版本递增，不与新的用户可感知 UI 能力混用。
- 当前本地版本保存标签为 `demo-app-v1.10.0`；上一个保存标签为 `demo-app-v1.7.3`。`1.8.0 / 121` 与 `1.9.0 / 122` 曾分别构建和安装，但未形成独立 Git 保存标签。此前 `1.1.0 / 106` 至 `1.7.2 / 119` 的本地测试版本也未创建标签。`demo-app-v1.0.5@11d764a`、`demo-app-v1.0.4@4e4bbe4`、`demo-app-v1.0.3@032589c`、`demo-app-v1.0.2@6f88115`、`demo-app-v0.9.4`、`demo-app-v0.9.3`（指向 `1170268`）、`demo-app-v0.9.2`、`demo-app-v0.9.1`、`demo-app-v0.9.0`、`demo-app-v0.8.0`、`demo-app-v0.7.1`、`demo-app-v0.7.0`、`demo-app-v0.6.0`、`demo-app-v0.5.0`、`demo-app-v0.4.0` 和 `demo-app-v0.3.0` 保留为历史 Demo 交付标签，不代表 Terminal Runtime 已达到最终发布状态。
- Debug APK 允许本机从被 Git 忽略的配置读取 API 默认值，不能作为对外分发包；API Key 不进入源码、文档或提交。
- 真机迭代使用固定 Debug 签名和 `adb install -r -d`，不以卸载、清数据作为常规版本升级步骤；用户反馈已在真机测试 1.0.5/105 Debug 包，设备和日志信息未记录，Agent 未独立操作设备或复核该结果。

## 1.10.0 · 2026-09-25 · 悬浮对话与主界面统一

### 变更范围

- 悬浮面板改用紧凑单行标题、次级状态和三个图标入口。最近 12 条消息复用主聊天消息组件，支持相同的 Markdown、代码、表格、图片预览和复制；流式回复同步展示并在持久化回复出现时去重。
- 过程复用主界面一行动态摘要与轻量两级记录，活动日志默认收起。按 ID 复用消息和步骤，状态、日志与输入草稿刷新保持展开选择；读者离开底部后不强制追随新消息。
- 输入区复用发送/停止组件，保留忙时发送排队、拒绝发送保留草稿及收起后恢复草稿。拒绝原因只采用本次新日志，避免误显示历史成功信息。长标题和代码语言在窄窗口保持单行省略；图片预览使用正确悬浮窗口类型。
- 标题拖动、右下角缩放、确认回调、IME 避让和外部自动化焦点契约沿用既有行为。没有新增依赖、权限或修改 SDK/AAR 接口。
- 此保存点还纳入独立的长会话修复：存储仍保留最新有界消息；如果截断后的首条是助手消息，重建模型会话从第一个完整用户回合开始，避免向模型发送不完整的首回合。对应 `DemoAgentSessionFactoryTest` 覆盖截断和仅有助手提示两种情况；该修复与视觉变更分别说明。

### 验收证据与边界

- `:demo-app:assembleDebug`、`:demo-app:assembleDebugAndroidTest`、`:demo-app:testDebugUnitTest` 通过，169 项单元测试无失败；最终 API 35 仪器测试 9/9 通过，包含主聊天两级展开回归、悬浮过程展开保持、流式去重、发送与草稿、折叠日志、浅深色 280dp 窄窗和 1.5 倍字体、真实拖动防误触、图片预览及停靠键盘避让/复位。
- 7 张最终真实原生截图及原版对比见 `.verify-shots/overlay-ui-final/`、`overlay-reference-comparison.png`、`overlay-states-overview.png`，根目录 `design-qa.md` 最终验收 `passed`。测试数据由 fixture 提供；首次键盘检查误用了 Gboard 透明手写区域的窗口边界，切换为停靠键盘后通过，未为此修改生产避让算法。临时 `stylus_handwriting_enabled` 设置已恢复为原先未设置状态。手写/浮动键盘仍在既有估算兜底边界内。
- 小米 `QSG6Q8IFDMDELVGQ`（`2602BRT18C`，Android 16 / API 36）安装前确认无活动计时任务；`adb install -r` 成功并启动，设备版本 `123 / 1.10.0`，进程存在，原会话、API 和主题偏好文件仍在。用户随后回复“很好 测试通过”，具体测试路径未另行记录；真实模型端到端和人工 TalkBack 未由 Agent 独立验收。
- 阶段收束重新执行 Demo 构建、AndroidTest APK 构建及根目录列出的八个 SDK 单元测试模块，Gradle `BUILD SUCCESSFUL`；九模块 XML 合计 `575` 项，0 failure、0 error、3 skipped（Windows symlink 条件受限）；Demo 为 `169/169`。本阶段已执行的定向仪器测试仍以先前记录的 `9/9`、`6/6 + 1/1` 和 `4/4 + 1/1` 为证据；Terminal 双宿主仪器、Runtime 打包 Gate 与真实 Provider 端到端不属于本次 Demo UI 范围。本机日志 `.verify-shots/stage-closeout-build.log` 和各阶段记录供本地追溯。
- Debug APK：`demo-app/build/outputs/apk/debug/demo-app-debug.apk`，SHA-256：`E4EBBB0A8425B492C355B3F386DBEA3334CED04BD2D6F2D0040FE98DCD3C9FBD`。本地提交与标签仅保存源码和文档，不代表 Play 发布；截图/日志为本机证据，未放入 Git。

### 本地检查点与接手范围

- 阶段基线是 `demo-app-v1.7.3` / `dcf48e2`；本地 `demo-app-v1.10.0` 保存随后完成的便签式确认、主聊天轻量过程和悬浮对话统一，以及上文单列的长会话修复。中间 `1.8.0`、`1.9.0` 的测试包与真机记录仍作为历史证据，不追补为精确 Git 标签。
- 检查点只纳入 `demo-app` 的对应源码、Debug 合成会话测试宿主、自动化测试、原生资源和许可，以及根 `README.md`、`docs/demo-app-ui-redesign.md`、`docs/demo-app-version-ledger.md`、`docs/demo-delayed-conversation.md`、`docs/README.md`、`design-qa.md`。`.verify-shots/`、`.playwright-mcp/`、根目录 a11y/Play 截图及 `playstore/` 是本机或其他阶段内容，继续留在工作树，不属于该标签。被忽略的本地 API 配置也未纳入。
- 下一次接手先读根 `AGENTS.md`、本台账、`docs/demo-app-ui-redesign.md` 和 `design-qa.md`，再查看 `MainActivity.kt`、`AgentFloatingWindow.kt`、`DemoChatProcessCardView.kt`、`TaskNoteUi.kt`。后续真实模型体验或用户反馈按新任务处理；Terminal Runtime、Play 发布与远程仓库操作不属于本次检查点。

## 1.9.0 · 2026-09-25 · 一行过程摘要与轻量展开记录

### 变更范围

- 用户提供简洁过程参考及当前展开截图，要求等待阶段减少占用、使用有趣的小动效，并降低展开内容与最终回答的视觉竞争。主聊天收起态改为 48dp 一行：动态三点、短摘要、小箭头；去除头像、外框、白色大卡和重复状态/步数。
- 展开后使用次文字、13sp 常规标题、小图标和细连接线；每个步骤仍可独立展开完整思考、输入参数及结果。170dp 局部滚动保留完整内容，新增步骤复用同 ID View，读者滚离底部时不会被流更新拉走。关闭外层重置各步骤展开状态。
- 收起摘要只从运行阶段与工具名称得到；原始参数、模型思考和工具输出留在单项详情。工具失败但 Runtime 仍忙碌时继续三点动效；完成、终态失败、停止、等待确认及隐藏/离窗停止动画，遵从系统关闭动画。手动停止在主摘要和子步骤均显示为中性状态。
- `DemoChatProcessCardView` 从聊天组件文件提取，新增 `DemoProcessPresentation`；图标使用官方 Material Icons outlined 原资源并沿用已打包的 Apache 2.0 许可。模型请求、工具执行、会话持久化与 SDK/AAR 接口保持原语义。

### 验收证据与边界

- `:demo-app:assembleDebug`、`:demo-app:assembleDebugAndroidTest`、`:demo-app:testDebugUnitTest` 通过，169 项单元测试无失败。API 35 模拟器上原展开回归和新过程测试共 6/6 通过；实际关闭系统动画后的补充检查 1/1 通过，已恢复原设置。
- 8 张真实原生截图覆盖浅深色、收起、展开、完整详情、错误和 1.5 倍字体。参考图/旧版与实现已并排对比，最终设计验收 `passed`，见根目录 `design-qa.md` 的 `1.9.0` 记录；本地证据位于 `.verify-shots/process-*`。不通过真实模型生成截图内容。
- 小米 `QSG6Q8IFDMDELVGQ`（`2602BRT18C`，Android 16 / API 36）安装前无活动延时任务；`adb install -r` 成功并启动。设备确认 `versionCode 122 / versionName 1.9.0`，原会话、API、主题等偏好文件仍在，进程存在。用户真机观感与真实模型端到端尚待反馈，人工 TalkBack 未在本轮执行。
- 本地 Debug APK：`demo-app/build/outputs/apk/debug/demo-app-debug.apk`，SHA-256：`81BCAFB2222637190F40A09D48CD0301BE6A18ABD53E08FEFFAF669786DBC138`。本轮没有创建提交、标签或 Play 发布。

## 1.8.0 · 2026-09-25 · 便签式确认、计时与整屏提醒

### 变更范围

- 用户选定暖纸色、墨绿色大字、琥珀色便签和猫头鹰插画的第二套设计，并要求将同一风格延伸至顶部倒计时与接管悬浮层后的整屏提醒。新增 `TaskNoteUi` 统一浅深色 Token、按钮、标签、虚线、生成插画与下划线；交互内容仍由 Android 原生 View 渲染。
- 底部确认层突出完整任务、自然时长、单次/周期属性以及“取消 / 确认并开始”；保留待清队列提醒与后台设置入口。顶部等待层突出倒计时、预计时间及任务摘要，停止操作固定可见。短窗口或大字体下，正文滚动区域为操作区预留实际高度。
- 整屏提醒沿用便签风格，保留有序内容、原因、真实按钮与输入表单。40 字按钮可完整换行；关闭、打开对话、按钮点击和表单提交继续采用各自既有语义。计时状态机、显式入退场动画与 SDK/AAR 接口未因视觉调整改变。
- 猫头鹰与装饰下划线为生成图像资源；时钟和关闭图标使用 Google Material Icons 原始资源，Apache 2.0 许可随 Demo 打包。资源来源及提示词记录见 `docs/demo-app-ui-redesign.md`。

### 验收证据与边界

- `:demo-app:assembleDebug`、`:demo-app:assembleDebugAndroidTest` 和 `:demo-app:testDebugUnitTest` 通过；169 项单元测试无失败。API 35 模拟器上的入退场与便签 UI 定向仪器测试最终 4/4 通过；1080×960 短窗口、1.5 倍字体的追加检查 1/1 通过，检查后已恢复窗口尺寸。
- 已实查确认/等待/整屏提醒的浅深色截图，长任务滚动、固定操作区、表单校验及操作回调。选稿与实现同宽裁切对比、实际截图和修正记录见根目录 `design-qa.md`，最终设计验收为 `passed`。截图与构建日志存放于本地 `.verify-shots/`。
- 小米 `QSG6Q8IFDMDELVGQ`（`2602BRT18C`，Android 16 / API 36）安装前无活动延时任务；以 `adb install -r` 保留数据覆盖安装并启动。`dumpsys package` 确认 `versionCode 121 / versionName 1.8.0`，原会话、API 与主题等偏好文件仍在，应用进程存在。
- 本轮未通过真实模型触发完整提醒链路，未进行实际输入法弹出的悬浮表单端到端检查；这些仍需结合真机体验。本地 Debug APK：`demo-app/build/outputs/apk/debug/demo-app-debug.apk`，SHA-256：`E9B99D14B8EB262578325376FDDAA0ECE5A8FF45CBC78396C889140396425D24`。本轮没有创建提交、标签或 Play 发布。
- 用户随后明确回复“我已经测试通过了”，本轮真机体验验收通过；具体测试路径未另行记录。该反馈与上述 Agent 独立验证范围分别保留。

## 1.7.3 · 2026-09-25 · 修正顶部确认层窗口边界与重复留白

### 变更范围

- 用户在 `1.7.2 / 119` 真机截图确认：状态栏仍是灰色，确认卡片顶部增加了一整段空白。根因是浮动 Dialog 仍从系统状态栏下方开始，而补偿用的状态栏高度又叠加在卡片内部。
- 顶部确认层改为非浮动、全屏透明 Dialog，由贴顶卡片覆盖状态栏背后；卡片内容只避让一次状态栏高度，并将自身顶部留白由 22dp 缩到 16dp。向下入场动画作用于卡片本身。底部等待层维持原窗口和布局。版本由 `1.7.2 / 119` 升至 `1.7.3 / 120`。

### 验收证据与边界

- `:demo-app:assembleDebug` 和 `git diff --check` 通过；`aapt dump badging` 确认 APK 为 `com.ugk.pi.agent / 1.7.3 / 120`。授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`，Android 16 / API 36）安装前任务标记为空，以 `adb install -r` 保留数据覆盖安装并启动；`dumpsys package` 确认设备版本为 `120 / 1.7.3`，原 API、会话与主题等偏好文件仍在，应用进程存在。用户随后针对顶部灰条和大块空白的复测明确回复“测试通过”。
- 本地版本保存：实现提交 `0d4ce13`，台账对齐后以 `demo-app-v1.7.3` 标记保存点。对应本机 Debug APK 位于 `demo-app/build/outputs/apk/debug/demo-app-debug.apk`（被 Git 忽略），SHA-256 为 `9304DED7BB697E1143A0F09F6E50AC3FC76162FF1BA48CECD966266B2273DB87`；不代表对外分发或 Play 发布。

## 1.7.2 · 2026-09-25 · 顶部确认层状态栏修补尝试

### 变更范围

- 用户真机截图指出顶部确认层已贴顶，但系统状态栏仍显示灰色压暗背景，与白色弹窗形成断层。本版尝试让浮动 Dialog 覆盖状态栏区域，并给确认内容增加状态栏高度的顶部留白、设置状态栏图标明暗；该尝试未改变 Dialog 的浮动窗口边界。
- 仅调整顶部确认层的窗口布局和文档；底部等待层、计时语义、Agent 会话与 SDK/AAR 未改。本地测试版本由 `1.7.1 / 118` 升至 `1.7.2 / 119`。

### 验收证据与边界

- `:demo-app:assembleDebug` 和 `git diff --check` 通过；`aapt dump badging` 确认 APK 为 `com.ugk.pi.agent / 1.7.2 / 119`。授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`，Android 16 / API 36）安装前计时任务标记为空，以 `adb install -r` 保留数据覆盖安装并启动；`dumpsys package` 确认设备版本为 `119 / 1.7.2`，原 API、会话与主题等偏好文件仍在，任务标记仍为空，应用进程存在。用户随后真机截图显示灰色状态栏仍在，卡片顶部多出一段空白；本版视觉目标未达成。

## 1.7.1 · 2026-09-25 · 计时弹窗半屏覆盖与内容收束

### 变更范围

- 用户在 `1.7.0 / 117` 真机界面指出：顶部确认层仍像悬空卡片；底部等待层的任务内容被滚动区与固定停止按钮挤住，需要滚动才能看完，并提供截图。前者改为贴顶全宽、仅下缘圆角的上半屏覆盖层，保留向下入场；后者保留贴底全宽与向上入场。
- 等待层将“距离任务执行”并入计时面板，缩小留白与计时字号，任务由大卡片改为紧凑摘要；不再在等待层重复展示上轮结果，其完整内容仍在主对话。等待层移除滚动容器，让倒计时、进度、预计时间、任务摘要和停止按钮直接可见；极长任务摘要最多两行并显示省略号，原文仍可在对话中查看。确认层继续允许滚动查看完整任务，开始/取消按钮固定可见。
- 仅改 Demo 原生界面与文档，不改计时或 Agent 运行语义；本地测试版本由 `1.7.0 / 117` 升至 `1.7.1 / 118`。

### 验收证据与边界

- `:demo-app:assembleDebug` 和 `git diff --check` 通过；`aapt dump badging` 确认 APK 为 `com.ugk.pi.agent / 1.7.1 / 118`。授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`）安装前计时任务标记为空，随后以 `adb install -r` 保留数据覆盖安装并启动；`dumpsys package` 确认 `versionCode 118 / versionName 1.7.1`，原 API、会话等偏好文件仍在，任务标记仍为空，应用进程存在。实际上下覆盖位置与无滚动布局仍需用户真机体验。

## 1.7.0 · 2026-09-25 · 计时确认与等待弹窗视觉分层

### 变更范围

- 用户真机确认 `1.6.1 / 116` 周期计时修正生效，同时指出确认和等待弹窗同为居中大卡片，文案重复、层级松散且状态难以区分；提供了两张当前界面截图作为改造依据。
- 计时提案改为从顶部向下进入、位于上半区的蓝色决策卡，突出间隔、任务内容及并排的取消/开始按钮。等待态改为从底部向上进入、占据下半区的绿色计时面板，突出剩余时间、进度、预计执行时间与停止入口。说明文案压缩，长内容在独立内容区滚动，按钮固定可见；保留 Light/Dark 主题色、后台设置入口和任务本身的确认/取消/停止语义。
- 本版仅改 `:demo-app` 的原生界面和动画资源，不改模型意图路由、计时起点、Agent 会话或 SDK/AAR。测试交付版本由 `1.6.1 / 116` 升至 `1.7.0 / 117`。

### 验收证据与边界

- `:demo-app:assembleDebug` 和 `git diff --check` 通过；`aapt dump badging` 确认 APK 为 `com.ugk.pi.agent / 1.7.0 / 117`。初次检查授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`）时，上一轮“每轮提醒喝水”的周期任务仍在等待，因此暂缓安装。用户随后明确要求安装；再次检查任务标记为空后，使用 `adb install -r` 保留数据覆盖安装并启动。`dumpsys package` 确认 `versionCode 117 / versionName 1.7.0`，应用进程存在，原 API、会话等偏好文件仍在，任务标记仍为空。弹窗的实际动效、长任务滚动与浅/深色观感需用户真机体验。

## 1.6.1 · 2026-09-25 · 周期任务按上轮完成时间重新计时

### 变更范围

- 用户在真机运行每 30 秒提醒任务后发现，Agent 本轮处理耗时十余秒，下一轮倒计时一出现就只剩十余秒。原因是 `1.6.0` 以首次截止时间为节拍，Agent 运行时间也消耗了下一轮间隔。
- 首轮仍从用户确认后等待完整间隔。此后在本轮 Agent 结束、结果写回原对话并释放运行槽位后，再开始下一轮完整间隔；不并发、不补跑。前台/后台等待、单次任务、手动停止和进程死亡后中断策略保持原有边界。Activity 的结果保存兜底也等到运行结束才重新计时。
- 同步调整模型路由、Agent 工具说明、确认/倒计时卡片和当前计时文档，明确“每轮完成后重新等待”。本地测试版本由 `1.6.0 / 115` 升至 `1.6.1 / 116`。

### 验收证据与边界

- `:demo-app:assembleDebug` 和 `git diff --check` 通过；`aapt dump badging` 确认 APK 为 `com.ugk.pi.agent / 1.6.1 / 116`。安装前核对授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`）的计时任务标记为空；随后用 `adb install -r` 保留数据覆盖安装并启动。安装后 `dumpsys package` 确认版本和应用进程，原 API、会话等偏好文件仍在，任务标记仍为空。未运行自动化测试或真实模型多轮计时；新倒计时起点与后台行为待用户真机复测。

## 1.6.0 · 2026-09-25 · 单对话固定间隔周期任务

### 变更范围

- 用户真机测试指出“从现在开始，每 5 分钟做一件事”无法创建任务。此前 `DemoModelIntentRouter` 明确把重复任务判为普通对话，`demo_delay_propose` 和 `DemoDelayedTaskController` 也只支持单次到点。本版让模型将明确的无限期固定间隔请求路由为周期提案；宿主只验证结构化间隔和任务内容，不对用户文字做关键词、正则或格式匹配。
- 沿用唯一计时槽位、用户确认和原会话续跑。确认后第一个间隔到点执行；之后以第一次截止时间为节拍，上一轮结束后只安排下一个未来时点，跳过错过的时点，不并发、不补跑。每轮仍使用 `SCHEDULED_TASK` 和原 `AgentSession`，结果写入当前对话；同一会话的整屏提醒按钮/表单事件可在周期等待期间串行进入 `SDK_EVENT`，普通聊天仍被阻塞。周期任务只由用户手动停止；Cron、有限次数和自动结束条件尚未提供。
- 确认与等待卡片区分单次/周期，展示执行间隔、下一次倒计时、已完成轮次和上轮结果节选；完整结果仍保存在主对话。进程结束后不自动续跑周期；下次启动写入中断说明。无新增 Android 权限、后台组件或独立 Agent 会话。
- 本地测试版本由 `1.5.0 / 114` 升至 `1.6.0 / 115`。

### 验收证据与边界

- `:demo-app:assembleDebug` 和 `git diff --check` 通过；`aapt dump badging` 确认 APK 为 `com.ugk.pi.agent / 1.6.0 / 115`。授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`）已用 `adb install -r` 保留数据覆盖安装并启动，`dumpsys package` 确认版本、应用进程存在，原 API、会话等偏好文件仍在；安装前后计时任务标记均为空。真实模型意图、跨多轮计时与后台存活仍需用户真机体验。

## 1.5.0 · 2026-09-25 · 可交互的整屏提醒

### 变更范围

- `agent_show_urgent_message` 在宿主实现 `InteractiveUrgentMessagePresenter` 时开放最多 3 个真实按钮和一个单行表单；Agent 负责提供控件 ID、标签与提示，App 负责安全边界和实际 View。旧宿主仍可只实现 `UrgentMessagePresenter`，不会得到无法回传的控件参数。`pi-attention-skill-android` 本地 publication 升至 `0.3.0`。
- SDK 为每次展示生成 `presentationId` 并绑定原 `sessionId`，不接受模型自填绑定。Demo 只接受当前会话的控件事件，单次展示只提交一次；忙时进入有界进程队列，原回合结束后通过 `SDK_EVENT` 在同一会话启动新回合，停止当前任务会清除尚未运行的控件事件。用户操作与 Agent 后续回答进入主对话；右上角关闭和“打开对话”不伪装为点击控件。
- 表单只接收非空、最多 500 字的单行文本；带表单的悬浮窗才获得输入焦点。没有新增 Android 权限或后台执行组件；进程结束后尚未运行的内存队列不会续跑。
- 本地测试版本由 `1.4.0 / 113` 升至 `1.5.0 / 114`。

### 验收证据与边界

- `:demo-app:assembleDebug` 与 `:pi-attention-skill-android:publishReleasePublicationToMavenLocal` 通过，`0.3.0` AAR 已落到本机 Maven 仓库；`aapt dump badging` 确认 APK 是 `com.ugk.pi.agent / 1.5.0 / 114`，`git diff --check` 通过。授权小米 `QSG6Q8IFDMDELVGQ`（实测型号 `2602BRT18C`）已通过 `adb install -r` 保留数据覆盖安装并启动，`dumpsys package` 确认 `1.5.0 / 114`、应用进程存在，原 API、会话与其他偏好文件仍在；安装前后延时任务标记均为空。真实模型选择控件、按钮回传以及不同输入法布局仍需用户真机体验反馈。

## 1.4.0 · 2026-09-25 · 醒目通知与整屏重要提醒

### 变更范围

- 用户真机反馈此前通知只有隐蔽的低优先级条目，重要提醒只显示在悬浮对话框内部，无法形成真正的屏幕提醒。Demo 为后续 Agent 通知使用新的 `ugk_agent_alerts_high_v1` 渠道，首次创建时请求 `HIGH` 重要度；旧 `ugk_agent_messages` 渠道保留。SDK 的 `AgentNotificationConfig` 支持宿主选择重要度，Android 8 以下映射通知优先级。
- `AgentFloatingWindow` 在展示重要提醒时暂时撤下普通悬浮球/对话框，切换为占满可用屏幕的 `UrgentTakeoverView`。右上角始终有 48dp 关闭入口，底部可打开主对话；关闭后恢复原悬浮状态。主界面恢复前台时不撤下尚未关闭的重要提醒；开始屏幕自动化或需要用户确认时，撤下提醒以便操作，通知仍可回看。
- Agent 可为 `agent_show_urgent_message` 选固定配色和最多 8 个有序纯文本元素：标题、段落、强调块、列表项。App 负责安全边界、滚动和关闭入口，不让模型提交任意 View、HTML 或屏幕坐标。通知正文仍是简要摘要和无元素时的悬浮兜底。SDK 外部宿主可按自身 UI 渲染或忽略这些可选元素；`pi-attention-skill-android` 的本地 publication 版本升至 `0.2.0`。
- 本地测试版本由 `1.3.3 / 112` 升至 `1.4.0 / 113`；没有新增权限或后台组件。

### 验收证据与边界

- `:demo-app:assembleDebug` 与 `git diff --check` 通过；`1.4.0 / 113` Debug APK 已在授权小米 `QSG6Q8IFDMDELVGQ` 上以 `adb install -r` 保留数据覆盖安装，安装后 `dumpsys package` 确认版本，原 API、会话和其他偏好文件仍存在。安装前后延时任务标记均为空。
- 新通知渠道在首次实际发通知时创建；本轮没有触发真实 Agent 通知或整屏提醒。横幅是否弹出还取决于用户渠道设置、勿扰模式与系统策略，`posted` 只说明 Android 接受投递。整屏悬浮窗不能覆盖系统关键界面。真机视觉、关闭恢复和 Agent 生成结构化元素的效果仍需用户体验。

## 1.3.3 · 2026-09-25 · Agent 提醒能力可发现性

### 变更范围

- 用户真机反馈 `1.3.2 / 111` 的通知功能“好像没有什么问题”。本版保留既有通知和悬浮展示实现，将 `agent_send_notification`、`agent_show_urgent_message` 的用途、选择边界与返回状态写入每次模型请求可见的插件说明；详细 `AndroidSkill` 继续提供操作语义，触发词不负责决定是否执行通知。
- 明确普通前台聊天无需重复通知，重要悬浮工具已附带通知，不为同一事件双重调用；延时提醒先由定时任务能力处理，期限到达后再按任务目的提醒。
- 本地测试版本由 `1.3.2 / 111` 提升至 `1.3.3 / 112`；无新 Android 权限、渠道或后台组件。

### 验收证据与边界

- `:demo-app:assembleDebug` 通过；`1.3.3 / 112` Debug APK 已在授权小米 `QSG6Q8IFDMDELVGQ` 上保留数据覆盖安装，安装后 `dumpsys package` 确认版本且原偏好文件仍在。安装前延时任务标记为空。
- 用户对旧版通知的体验反馈不等于新版 Agent 选工具验收；新版能力说明如何影响实际模型选工具，尚需真机对话观察。

## 1.3.2 · 2026-09-25 · 模型驱动的延时意图路由

### 变更范围

- 根据用户明确要求，移除 `DemoRelativeDelayParser` 和输入/助手文本匹配兜底。延时意图与任务内容由当前配置的大模型对每条直接用户消息作结构化 JSON 判断；模型判为延时后才调用现有提案工具，App 只校验时长、内容和槽位并请求用户确认。
- Z.AI 官方端点的路由请求使用 `response_format: json_object`；其他端以提示词要求 JSON。无效结果最多重试一次，仍无效就明确失败，不依据文本猜测。到点续跑沿用原 Agent 循环，不重新进入路由。
- 本地测试版本由 `1.3.1 / 110` 提升至 `1.3.2 / 111`。每条用户消息增加一次模型请求，正常任务的首字延迟与模型费用会相应增加。

### 验收证据与边界

- `:demo-app:compileDebugKotlin` 与 `:demo-app:assembleDebug` 已通过；`1.3.2 / 111` Debug APK 在授权小米 `QSG6Q8IFDMDELVGQ` 上通过 `adb install -r` 保留数据覆盖安装，安装后 `dumpsys package` 确认版本，原有 API 与会话偏好文件仍在。安装前延时任务标记为空。
- 模型路由的实际对话、确认卡和不同 API 兼容端的 JSON 输出仍需用户真机体验；构建和安装通过不等于模型分类永远正确。

## 1.3.1 · 2026-09-25 · 延时任务确认弹窗兜底

### 变更范围

- 真机 `1.2.1 / 108` 上，用户发送“一分钟以后跟我发一句你好”后仅看到 Agent 文本“请确认”，没有确认卡。应用私有诊断记录显示该回合模型 `toolCallCount=0`，`DemoDelayedTaskState` 未进入 `Proposed`；“全授权”不是原因。
- 明确的相对延时指令由 Demo 输入层保守解析，直接提交到现有单任务控制器并显示确认卡。复杂表达继续走 Agent；模型只输出与用户时长一致的提案文本时，宿主补建提案。若模型声称“请确认”但任务仍未创建，显示明确失败说明。
- 本地测试版本由 `1.3.0 / 109` 提升至 `1.3.1 / 110`。仍需用户点击确认才开始计时，后台进程与到点续跑语义不变。

### 验收证据与边界

- `:demo-app:compileDebugKotlin`、`:demo-app:assembleDebug` 通过；`1.3.1 / 110` Debug APK 已在连接的测试机 `QSG6Q8IFDMDELVGQ` 上通过 `adb install -r` 保留数据覆盖安装，安装后 `dumpsys package` 确认 `versionCode=110`、`versionName=1.3.1`。实际界面、确认/拒绝及一分钟到点续跑仍需真机复验。

## 1.3.0 · 2026-09-24 · Agent 即时通知与重要悬浮提醒

### 变更范围

- 新增可独立发布的 `pi-attention-skill-android` AAR。Agent 可调用 `agent_send_notification` 发宿主通知；宿主提供展示适配器时可调用 `agent_show_urgent_message`，先发通知再尝试屏幕展示，分别返回实际状态。
- Demo 在现有 `AgentFloatingWindow` 顶部增加重要提醒卡，避免第二个悬浮窗。用户可关闭提醒或打开主界面；屏幕自动化和待确认界面占用悬浮窗时不抢占。
- 本地测试版本由 `1.2.1 / 108` 提升至 `1.3.0 / 109`。权限声明维持原有 `POST_NOTIFICATIONS` 与 `SYSTEM_ALERT_WINDOW`；没有新增后台服务或持久化调度。

### 验收证据与边界

- `:pi-attention-skill-android:assembleRelease`、`:demo-app:assembleDebug` 通过；Release publication 的 POM/Module Metadata 已生成，POM 仅含 Core SDK 与 Kotlin 标准库直接依赖。
- 本轮尚未覆盖安装到真机，未实测通知渠道设置、权限拒绝或重要卡片交互；构建成功不等同于各 Android 厂商上的实际展示保证。外部接入、Tool 返回状态和平台限制见 [`android-agent-attention.md`](android-agent-attention.md)。

## 1.2.1 · 2026-09-24 · 延时任务弹窗视觉收口

### 变更范围

- 将系统默认确认弹窗和无限旋转等待框改为主题化原生卡片。确认态突出等待时长、完整任务与“开始等待”；等待态突出大号倒计时、确定进度、预计开始时间与“停止任务”。
- 卡片沿用 `Ui` 的暖白/深灰、绿色主操作和圆角层级；长任务及字体放大时支持内部滚动。计时、独占、后台和进程死亡语义保持 `1.2.0` 的设计。
- 本地测试包版本由 `1.2.0 / 107` 升至 `1.2.1 / 108`；未提交 Play，未创建发布标签。

### 验收证据与边界

- `:demo-app:compileDebugKotlin` 与 `:demo-app:assembleDebug` 通过；确认与等待卡片已在本地 Android 模拟器的浅色、深色模式分别渲染并检查。临时预览 Activity 已从正式构建移除。
- `1.2.1 / 108` 已在连接的小米测试机上以 `adb install -r -d` 保留数据覆盖安装；用户已反馈 `1.2.0 / 107` 延时功能可用，但原弹窗与倒计时简陋。新版真实对话流程与主观视觉体验仍待用户确认。

## 1.2.0 · 2026-09-24 · 单对话延时任务首版

### 变更范围

- `demo_delay_propose` 将相对延时意图交给宿主确认；确认从用户点击“开启”开始计时，不受“全授权”设置影响。主对话展示阻塞倒计时和停止入口，其他 Agent 消息与会话切换在等待期间不可用。
- 到点后进程级分发器向同一会话追加用户消息，再经现有 `DemoAgentRunCoordinator` 与 `AgentRuntime` 执行。退后台和锁屏不主动取消；进程死亡不自动补跑，下次启动提示中断。
- Demo 退役旧版后台 `JobScheduler`/`AlarmManager` 组件入口，升级时取消旧活动任务并在关联对话说明。SDK 通用调度模块仍保留。协议与边界见 [`demo-delayed-conversation.md`](demo-delayed-conversation.md)。
- Demo 测试交付元数据由 `1.1.0 / 106` 升至 `1.2.0 / 107`；未提交 Play，未创建发布标签。

### 验收证据与边界

- `:demo-app:compileDebugKotlin` 和 `:demo-app:assembleDebug` 已通过；合并 Manifest 不含旧定时 JobService、Receiver 和开机广播权限。
- 本轮未运行单元测试或真机定时流程；用户确认、后台到点、旧任务升级迁移和不同设备电池策略仍需真机验收，不能据构建成功宣称按时执行可靠。

## 1.1.0 · 2026-09-24 · 视觉优先屏幕自动化

### 变更范围

- 对支持截图的无障碍后端，屏幕自动化以当前截图作为主要观察，操作后重新截图验证；结构树用于语义证据、精确文本、节点能力和目标消歧。截图不支持时使用结构树；截图失败最多重试一次，之后回退结构树。实现与限制见 [`android-accessibility-screen-automation.md`](android-accessibility-screen-automation.md)，源码提交 `2d01a70`。
- `DemoApplication` 启动时删除已退役 Jev 试验留下的偏好和两个数据文件（源码提交 `e4c422d`）。
- Demo 测试交付元数据由 `1.0.5 / versionCode 105` 升至 `1.1.0 / versionCode 106`。此版本未提交 Play，也未创建发布标签。

### 验收证据与边界

- `:demo-app:assembleDebug`、8 个 SDK 模块及 `:demo-app:testDebugUnitTest`、`:demo-app:compileDebugAndroidTestKotlin` 在源码 HEAD `e4c422da81d675f623558a0b29dcb5efd640a12e` 对应的提交内容上通过；9 个模块共 `573` 项（0 failure、0 error、3 skipped）。该测试结果在版本元数据递增前执行；本次收口另行验证 `1.1.0 / 106` Debug APK 构建。
- 用户反馈此前安装的 `1.0.5 / 105` Debug APK 真机测试效果不错。该反馈对应视觉优先实现的同一源码；设备型号、系统版本和独立日志未记录，不作为 Agent 独立复核结果。`1.1.0 / 106` 是新的本地测试元数据，尚无此版本真机验证。
- Play 发布状态：本次未上传；`1.1.0` 不是 Play 发布。旧版 `1.0.5` 的 Play 轨道状态仍以 Play Console 实测为准。

## 1.0.6 · 2026-09-08 · 第五轮 P0 审查修复（SDK 协议/并发/技能边界/demo 数据完整性）

### 变更范围

- 本版本属于第五轮 P0 审查修复批次（分支 `fix/p0-review-round5-20260908`，基于 `main@b0f1859`）。demo-app 侧改动三处：
  - `DemoConversationStore`：`create` / `delete` / `rename` 纳入 store 监视器，`rename` 改为单临界区读改写（原先 `get()`→`save()` 之间的后台定时结果可被陈旧快照整会话覆盖丢失）；`appendMessagesAndFlush` / `saveAndFlush` 的同步落盘等待移出 store 监视器（原先前台 save/append 会阻塞在后台 flush 的磁盘 commit 上）。
  - `MainActivity`：切换/新建会话时输入框与 `floatingWindow.clear()` 触发的 `runtime.draft` 清空保持一致（原先把 A 会话草稿发进 B 会话的数据不一致路径）。
  - `DemoAgentRuntimeFactory`：把静态插件 skill id 集合传给 `SkillRepository` 的新 `reservedSkillIds` 参数，`skill_save` 撞名时返回 `SKILL_NAME_RESERVED` 结构化错误（原先一次确认过的 `skill_save` 可让之后每次 run 在技能组装阶段永久失败且无法自愈）。
- Demo 版本由 `1.0.5 / versionCode 105` 提升到 `1.0.6 / versionCode 106`。不改变依赖、权限、Terminal v1 scope 或聊天 UI 基线。

### 验收证据与边界

- 全模块 JVM 门禁（`--rerun-tasks`，含独立审查条件修复后的最终重跑）：`585` tests / `3` skipped（既有 Windows symlink 限制用例）/ 0 failure / 0 error，较基线 `560` 净增 25 例（含先红后绿回归用例）。
- `:demo-app:connectedDebugAndroidTest`（AVD `round5_api35`，API 35 x86_64，4 KB）：`30/30` 通过、0 failure（基线 `28` + 新增 `DemoConversationStoreConcurrencyInstrumentedTest` 2 例）。
- 本条目验收以分支 HEAD 实测为准；未操作真机、未调用真实 Provider/API、未跑 Release 矩阵，不关闭任何 Gate。

## 1.0.5 · 2026-09-03 · 悬浮窗贴顶钳位下的 IME 高度压缩

> 本条目为 2026-09-03 补记：`c703f91` / `11d764a` 提交时未同步台账，条目内容以 commit message、Git diff 与补记当日实测测试结果为据。

### 变更范围

- `AgentFloatingWindow` IME 避让增强（commit `c703f91`）：展开态悬浮窗已贴住 48dp 顶部钳位且窗口较高时，旧避让逻辑静默接受残余重叠、底部输入行被键盘遮挡。`avoidanceDecision` 改为返回 `(y, height)`：先按原逻辑平移；平移被钳位且仍有真实重叠时压缩高度，使窗口底部落在 IME 上方 8dp（受最小/最大高度约束、绝不增高、minHeight 兜底）。键盘关闭精确恢复用户原始高度与位置；拖动保持高度基线（手指只拥有位置），resize 采纳新高度。涉及 `AgentFloatingWindow.kt`、`ImeAvoidance.kt`，`ImeAvoidanceTest` 新增用例。
- Demo 版本由 `1.0.4 / versionCode 104` 提升到 `1.0.5 / versionCode 105`（commit `11d764a`）；自本版起，运行 `1.0.4` 的测试人员可见 Play 应用内更新弹窗（`1.0.4` 之后有新版本可推）。不改变依赖、权限、Terminal v1 scope 或主界面聊天 UI 基线。

### 验收证据与边界

- `:demo-app:testDebugUnitTest` 于 HEAD `11d764a` 补记当日实测 `160/160`（0 failure / 0 error / 0 skipped，`--max-workers=1`）；较 `1.0.4` 的 `153` 新增 7 例 IME 用例。
- commit message 自述真机（Android 16）+ 模拟器验证：贴顶高窗压缩（2403→1302 / 1913→1234，顶保持钳位）、键盘关闭精确恢复、矮窗仍仅平移、IME 打开时 resize 采纳新高度、收起再展开保留用户高度、三连开关零漂移、0 crash；`ImeAvoidanceTest` 19/19。该真机验证未随提交记录台账，本条目不将其作为独立复核过的证据。
- 发布记录：`11d764a` 标题为面向内部测试发布的版本提交，但本次补记无 Play Console 外部观察证据；发布状态以 Play Console 实测为准。
- 版本边界标签为 `demo-app-v1.0.5@11d764a`；远端状态以 Git 实测为准。

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
  - > **2026-10-02 更正（追加，原文保留于上）**：上面这行 `916 个 / 138 类` 与 `docs/terminal-runtime-validation.md` §27 同一数字，已被该节 2026-09-28 的更正按 `@Test` 逐提交实测判为**失实记录**（`main@1170268` 实测 `441` tests / `63` 类）。此前只在验证文档做了更正，本台账仍带着无注的旧数字，故在此补指针；当前门禁口径以验证文档 §37 的 `--rerun-tasks` 独占实跑为准。
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
