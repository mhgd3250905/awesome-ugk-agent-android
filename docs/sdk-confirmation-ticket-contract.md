# 高影响操作确认票据契约

更新时间：2026-09-29

> **2026-10-07 追加（第十五轮）**：第 4 节列出的「三种状态」与「`withoutUserDecision` 由宿主声明」
> 已不再完备。`UserConfirmationDialogTool` 现在还会**自己**产出第四种回执：宿主返回的
> `selectedButtonId` 不在本次请求的按钮集合内、且本次确认存在绑定票据时，确认 Tool 返回
> `{withoutUserDecision:true, ticket}`（**没有** `selectedButtonId` 字段），受保护 Tool 按
> 「未得到用户决定」分派。这样改的理由是：弹窗已经展示、用户已经操作过一次，把它答成
> `isError` 会被受保护 Tool 读成「还没有确认，请先调用确认 Tool 再重试」，于是同一个问题再弹一次窗。
> 该回执不构成任何授权：授权判定与拒绝判定都要求一个非空且属于允许/拒绝集合的 `selectedButtonId`，
> 缺失即两侧都为假。没有票据时（旧的不带 `target` 的确认请求）这条无法绑定到某一组输入，
> 因此仍按 `isError` 响亮失败——即第 4 节原有措辞对那种形状仍然成立。
> 同节「宿主无法区分时保持 `false`，SDK 视同一次真实按钮选择」需按此收窄：SDK 会检查该 id
> 是否在本次请求的按钮集合内，不在则不视为真实选择。自查：
> `git grep -n "not present in the request" -- ugk-pi-android/src/main`。
> **第 4 节正文里那句「拒绝按钮集合默认 `cancel/deny/no/reject/decline/stop`」也已过期**：
> 第十五轮把按钮词汇收成唯一公开定义
> `USER_CONFIRMATION_DECLINED_BUTTON_IDS`（`ugk-pi-android/.../UserConfirmationRequiredTool.kt`，
> 实测 11 个 id：另含 `close/abort/dismiss/later/not_now`），因为 demo 正是把这些 id 画成 Cancel 按钮，
> 用户点「暂不」却被告知「请先调用确认 Tool 再重试」等于把刚做完的决定再弹一次窗。
> 放宽只发生在**拒绝**一侧，授权集合 `USER_CONFIRMATION_ACCEPTED_BUTTON_IDS` 未变。
> 仍未解决且如实登记：SDK 比较 id 大小写敏感，而 demo 的可视化分类与 Headless presenter 都先
> `lowercase()`，所以 `"Not_Now"` 这类混合大小写仍会被画成 Cancel 却读不成拒绝。
> 自查：`git grep -n "USER_CONFIRMATION_DECLINED_BUTTON_IDS" -- '*/src/main/*'`。

> **2026-10-08 追加（第十六轮）**：上一条末尾那句「仍未解决」已解决，且上面那条自查命令的命中集**变了**，
> 按原样跑它会误导读者，所以在这里更新而不是删改 2026-10-07 那段历史。
>
> 1. 比较规则现在只有一个定义：`userConfirmationButtonIntent(id[, accepted, declined])`
>    （`ugk-pi-android/.../UserConfirmationButtonVocabulary.kt`）做 trim + `Locale.ROOT` 折叠后再查集合，
>    accepted 先于 declined（保持受保护 Tool 一直有的优先级，重叠注入不会把授权读成拒绝）。
>    受保护 Tool 的两个判据、确认 Tool 的「按钮是否在请求里」判据、demo 的可视化分类、headless 兜底、
>    完整授权自动同意这五个落点全部走它；headless 与授权策略的两份手抄集合已删除。
>    新自查：`git grep -n "userConfirmationButtonIntent" -- '*/src/main/*'`。
>    旧自查命令 `git grep -n "USER_CONFIRMATION_DECLINED_BUTTON_IDS" -- '*/src/main/*'` 现在只剩 3 个命中
>    （集合定义、受保护 Tool 的注入默认值、词汇表的归一化别名），**demo 侧不再是它的读者**——
>    命中集从「SDK + demo 两处」变成「只有 SDK」，这正是本条要的效果，不是集合丢了。
>    本轮实测输出留在 `build/review-evidence/r16-doc-selfcheck.txt` 第 1 节。
> 2. 折叠两侧（授权与拒绝）是本轮的决定，理由与边界写清楚：授权仍必须同时满足「id 属于 accepted 词汇」
>    「非 `withoutUserDecision`」「票据结构有效且未过期」「票据的 sessionId/toolName/inputFingerprint 绑定本次调用」
>    「该 id 确实在本次请求的按钮集合里」，折叠大小写只让 `OK`/` Ok` 这类拼写进入既有门槛，
>    不新增任何一类「没人按键也能过」的路径。反面由两条用例钉住：`approve` 这类两个词表都不在的 id
>    既不授权也不报拒绝（`anIdOutsideBothVocabulariesIsNotReadAsTheUsersDecline`），
>    空白 id 归 UNRECOGNIZED（`aBlankIdIsNeitherApprovalNorRefusalAtTheClassifier`）。
> 3. 一条本轮打开又关掉的门，写进契约免得再被打开：demo 的分类除 id 词表外还有一条 **label 词表**，
>    因此 `{"id":"OK","label":"取消"}` 会被画成 Cancel 而 SDK 读成授权。现在的规则是
>    **id 读作授权时永不归入拒绝样式**（`ConfirmationVisualPolicy.isCancellation`）。
>    这条只在宿主可见的层面成立；第三方宿主若另有一套 label 词表仍可能画出矛盾，
>    SDK 看不见 label——要把它变成硬约束需要给确认结果加一等「视觉与词汇相冲突」语义，属接口扩面，
>    本轮登记不做。
> 4. 请求里两个按钮折叠后同 id（如 `["OK","ok "]`）现在**在问用户之前**就被拒绝：答案是 id，
>    折叠后无法归属到用户实际按下的那个按钮。上一条追加里「SDK 会检查该 id 是否在本次请求的按钮集合内」
>    那句由此获得新的边界：检查按归一化比较，因此不再能把大小写不同的回显当成「没提供过的 id」。
> 5. 本契约第 4 节默认「确认 Tool 是否注册」与「受保护 Tool 是否需要确认」一致，这一点要靠宿主保证：
>    能力插件只在 `AgentRuntime` 构建时按当时的偏好决定注册与否，而受保护 Tool 每次调用都读实时偏好。
>    宿主若在运行时切换该偏好，必须重建 runtime（或在没有回合在跑时重建）；否则受保护 Tool 会要求先调用
>    一个模型手里没有的 Tool。demo 侧的接线与不打断在跑回合的取舍见
>    `docs/terminal-runtime-validation.md` §43 的 F2。
> 6. 第十五轮追加说「headless 有真正取消按钮时保持一次普通拒绝而不是『没有人决定』」——该规则保留，
>    现在一致地适用于 published 拒绝词表的全部 11 个 id（此前只适用于其中 6 个）。
>    两条臂都不执行受保护 Tool；差别只在给模型的措辞。这仍是产品规则而非本轮的修复对象，
>    登记见 §43「被否掉的复核立案」第 2 条。

本文是 SDK-OPT-008 的协议设计结果。它先固化确认边界，再进入 Core、System、Terminal 和 Demo 的一次性实现；本文件本身不改变运行时行为。

## 1. 为什么不能继续只使用 selectedButtonId

当前确认结果只包含 `selectedButtonId`。它能够表达“用户按了哪个按钮”，但不能表达该选择授权了哪个 Tool、哪一组输入，也不能阻止同一确认结果被错误地套用到另一个高影响操作。

尤其是 Agent 的确认调用和目标 Tool 调用通常分属两个模型循环，确认发生时目标 Tool 还没有执行。因此不能依赖“下一次 Tool 是什么”来补绑定，目标必须进入确认请求本身。

## 2. 确认请求与票据结构

确认 Tool 的输入增加目标对象：

```json
{
  "title": "允许执行终端命令？",
  "message": "将执行用户指定的命令。",
  "buttons": [
    {"id": "confirm", "label": "允许"},
    {"id": "cancel", "label": "取消"}
  ],
  "target": {
    "toolName": "terminal_bash_execute",
    "input": {"script": "printf 'hello\\n'"}
  }
}
```

确认成功后的结果保留 `selectedButtonId`，并新增一次性票据：

```json
{
  "selectedButtonId": "confirm",
  "ticket": {
    "version": 1,
    "sessionId": "session-1",
    "toolName": "terminal_bash_execute",
    "inputFingerprint": "sha256:...",
    "nonce": "base64url-without-padding",
    "issuedAtEpochMillis": 0,
    "expiresAtEpochMillis": 120000
  }
}
```

票据不携带原始输入，只携带输入摘要；原始目标输入仍保留在确认请求中供宿主展示和生成摘要。

## 3. 绑定与摘要规则

- `sessionId` 必须等于执行目标 Tool 时的 `ToolExecutionContext.sessionId`。
- `toolName` 必须等于当前目标 `ToolCall.name`，按大小写敏感的完整字符串比较。
- `inputFingerprint` 为目标 `ToolCall.input` 的规范 JSON UTF-8 字节计算 SHA-256，表示为小写十六进制并加 `sha256:` 前缀。
- 规范 JSON 使用版本化规则 `canonical-json-v1`：对象键按 Unicode 码点升序排列；数组保持原顺序；字符串使用标准 JSON 转义；`true`、`false`、`null` 使用固定字面量；数字必须是有限 JSON number，并规范化 `-0`、前导零和指数表示。无法规范化的输入拒绝生成票据。
- 未来如果改变规范化算法，必须提升票据 `version`，不能静默改变同一输入的摘要。

确认 Tool 和受保护 Tool 必须使用同一套摘要实现，不能分别拼接字符串或依赖 Kotlin `JsonObject` 的当前迭代顺序。

## 4. 有效期、一次性和失败语义

- `nonce` 使用宿主运行环境的密码学安全随机源生成，至少 128 bit；它不是业务输入，也不能由模型指定。
- 默认票据有效期为 120 秒；`now >= expiresAtEpochMillis` 即过期。时钟由 Core 注入，便于测试。
- 受保护 Tool 只有在其 `priorMessages` 的最后一条 ToolResult 是本次确认结果、其后至多只有一个包含当前完整 ToolCall 的 Assistant(tool-call) 外壳、按钮属于允许集合、结果未声明 `withoutUserDecision=true`、票据未过期且所有绑定字段匹配时才执行。该 Assistant 外壳是 Runtime 的消息封装，不代表新的执行；User/System 消息或任何其他 ToolResult 出现在确认之后都必须拒绝。
- 目标 Tool 执行成功、失败或被拒绝后，确认结果不再是下一次 Tool 的最近 ToolResult；下一次尝试必须重新确认。这是 v1 的“紧邻结果一次性”语义。
- 不匹配、缺字段、JSON 非法、过期、拒绝按钮、不同 Session 或重复使用均 fail-closed，不调用 delegate。
- “用户拒绝”的判定条件（与授权判定共用同一条“紧邻上下文”规则，强度不得不对称）：最后一条 ToolResult 仍是本次 `show_user_confirmation_dialog` 的结果、其后至多只有包含当前完整 ToolCall 的 Assistant 外壳、`selectedButtonId` 属于**拒绝集合**（`declinedButtonIds`，默认 `cancel/deny/no/reject/decline/stop`）、结果不含 `withoutUserDecision=true`，且票据的 `sessionId` 与 `toolName` 绑定到当前受保护 Tool，且票据的 `inputFingerprint` 与当前调用输入按第 3 节的摘要规则匹配（拒绝与未触达两个状态都要求这层输入绑定：用户拒绝的是弹窗展示的那组输入，换一组输入重试不得复用旧的拒绝或未决结论）。任何一条不满足都不得宣称“用户已拒绝”。回执措辞按三种状态分派，互不覆盖：真实拒绝→“用户已拒绝，本轮不要再请求”；宿主声明未触达用户（`withoutUserDecision=true`）→“未得到用户决定，本轮不要再请求，并说明联系不上用户”；其余（含 `approve` 这类未被识别的肯定按钮、无票据、已过期、票据绑定的是另一组输入）→返回列出允许集合的“需要确认”提示，让模型可以自我纠正。把未识别按钮说成拒绝会阻断用户其实已经授权的动作；把未触达用户说成“请再弹一次窗”则会让无 UI 的后台回合在等不到答案的对话框上空转。
- `UserConfirmationDialogResult.withoutUserDecision`（默认 `false`）由宿主声明“该结果不是用户作出的决定”——窗口随宿主销毁、协程被取消、无 UI 的后台运行等。宿主无法区分时保持 `false`，SDK 视同一次真实按钮选择。该字段是**授权与拒绝两侧共同的硬条件**：为 `true` 时票据照常返回，但既不构成授权（否则宿主自己兜底选出的允许集合按钮就能让受保护 Tool 在无用户参与时执行），也不构成“用户已拒绝”。Demo 的 Activity presenter 在生命周期销毁路径上置为 `true`；Headless presenter 在按钮集合里没有可用拒绝按钮时置为 `true`。
- v1 不宣称对宿主手工伪造的 `priorMessages` 提供持久化防重放能力；如果未来支持跨进程/排队确认，必须增加共享的 TicketStore，并把消费状态纳入新的协议版本。

## 5. 旁路与兼容策略

- `shouldBypassConfirmation` 仍表示宿主显式启用的 full authorization 策略；旁路不要求、不校验 ticket。若宿主仍调用确认 Tool，确认 Tool 可能按 target 返回普通 ticket，但受保护 Tool 的旁路路径不会读取它；生命周期和 UI 说明仍由宿主负责。
- 旧的仅返回 `selectedButtonId` 的确认结果可以继续被非受保护的确认调用读取，但受保护 Tool 默认拒绝无绑定票据的结果。
- 旧的 `UserConfirmationDialogRequest(title, message, buttons)` 源码调用可以保留迁移期兼容构造，但没有 `target` 时不能产生可执行的受保护票据。
- Demo 的旧 UI/API 不需要立即删除；迁移时必须在确认 UI 中展示目标 Tool 和目标输入摘要，并更新 Agent instructions 让模型在每次高影响操作前提交完整 target。

## 6. 最小实现范围与验收矩阵

下一步实现应只涉及：

- Core：票据模型、规范 JSON 摘要、确认 Tool 生成票据、受保护 Tool 校验票据。
- System/Terminal：创建共享的确认请求目标，并更新 instructions/Tool schema；不改变工具业务执行逻辑。
- Demo：把目标信息传给 Activity/overlay presenter，并保留 full authorization 旁路。

必须覆盖的测试：

- 同一目标 Tool 与同一输入成功；Tool 名称、输入字段、对象键顺序变化分别失败。
- 不同 Session、过期票据、拒绝按钮、缺少 ticket、非法 ticket 分别失败。
- 同一确认结果执行一次后再次尝试失败；不同目标不能复用。
- full authorization 显式开启时仍可执行，受保护 Tool 不要求或校验 ticket；是否调用确认 Tool 及其返回值由宿主实现决定。
- Activity 重建/悬浮窗切换期间确认仍能完成或安全取消。
- 真机上至少验证一次 Terminal 命令确认、一次 Screen 动作确认、一次取消和一次过期/重试路径。

本契约通过后才进入跨模块实现；实现期间若发现必须引入持久化 TicketStore、Runtime Coordinator 或新的 runId，需拆成独立决策，不在本步隐式扩大范围。
