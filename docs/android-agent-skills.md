# Android Agent Skills（文件型 skill 运行时）

`pi-agent-skill-runtime-android` 让宿主把"skill"做成 App 私有目录里的 SKILL.md 文件：运行时扫描、
解析、按加载策略注入模型上下文，Agent 通过 `skill_list` / `skill_read` 工具自助发现与加载。
第一个预制 skill 是 `agent-memory`（用户记忆捕获与回放）。核心模块 `ugk-pi-android` 公共 API 签名
零变化（仅 `AgentRuntime.Builder` 内部改为持有 Provider 引用而非静态快照，见 D-022 勘误二）。

## SKILL.md 规范

目录约定：`<filesDir>/agent-skills/<skill-name>/SKILL.md`，目录名必须等于 frontmatter 的 `name`。

格式：首行 `---`，随后若干扁平 `key: value` 行，`---` 结束，之后是 markdown 正文 body。
frontmatter 为手写解析，只支持扁平键值，不支持嵌套 YAML。

标准字段：

| 字段 | 约束 |
|---|---|
| `name` | 必须，`[a-z0-9-]+`，且与目录名一致 |
| `description` | 必须，非空，≤1024 字符 |

扩展字段（`x-` 前缀；未知 key 忽略，保证前向兼容）：

| 字段 | 语义 |
|---|---|
| `x-ugk-load` | `always` / `indexed` / `triggered`，默认 `triggered` |
| `x-ugk-embed-files` | 逗号分隔条目，两种形式（见下节"命名根嵌入"）：相对路径（skill 目录内 `.md` 文件）或 `别名:相对路径.md`（解析到宿主注册的命名根），仅 `always` 策略生效 |
| `triggers` | 逗号分隔关键词，`triggered` 策略匹配用 |

校验上限：body 非空且 ≤64KB；SKILL.md 总文件 ≤128KB；单个 embed 文件读取 ≤16KB（超限截断并注明）。
解析或校验失败的 skill 不注入，但在 `skill_list` 结果里以 `invalid` 状态带原因列出，不静默丢弃。

## 命名根嵌入（embed roots）

`x-ugk-embed-files` 条目两种形式：

1. `preferences.md` —— 相对 skill 目录的 `.md` 文件，适合随 skill 打包的静态资产。
2. `memory:preferences.md` —— `别名:相对路径.md`，别名仅允许 `[a-z][a-z0-9-]*`，解析到宿主在
   `FileBackedSkillProvider(plugins, repository, embedRoots)` 注册的命名根目录（`Map<String, File>`）。

命名根是通用机制，不限于记忆：任意 skill 都可引用宿主注册的任意别名根，把 skill 目录之外的**活数据**
嵌入每轮注入。规则：

- 带别名条目的路径校验与 skill 目录条目一致：仅允许 `.md` 文件名、拒绝绝对路径与 `..`/分隔符，
  canonical 结果必须落在该根内（对齐 `pi-file-skill-android` AppPrivateFileTools 的归属校验）。
- 别名未注册 → 该条目按"缺文件跳过并注明"处理（`unknown embed root '别名'`），不影响其余条目与 skill 本身。
- 16KB 截断、缺文件跳过并注明等既有语义不变。
- **嵌入内容在每次 `skills()` 调用时实时读取**：宿主向命名根写入新内容后无需重建 Provider，
  下一次注入即包含最新内容。这是记忆常驻回放的基础。

`agent-memory` 预制 skill 即用此机制：frontmatter 写 `x-ugk-embed-files: memory:preferences.md,
memory:rules.md`，宿主注册 `memory → <filesDir>/agent-memory`（demo 的 `DemoAgentRuntimeFactory`
已接线），使 `memory_write` 写入的真实用户偏好/规则每轮自动注入上下文，模型无需调工具回放。
记忆为空时嵌入段显示"(embed file not found; skipped)"注记，skill body 已说明这表示该分类尚无记录。

## 三种加载策略

| 策略 | 注入内容 | 说明 |
|---|---|---|
| `always` | body + embed 文件（每节以 `### Embedded: <path>` 拼在 body 后） | 每轮无条件注入，适合小而关键的行为契约（如 agent-memory） |
| `indexed` | 固定桩文案 + 原 description（元数据便宜） | 模型需要时先 `skill_read(name)` 拉全文，适合大体积 skill |
| `triggered` | body | 由 Resolver 按关键词决定是否注入：优先 `triggers`，无 triggers 时回退 id+description 分词（词长 ≥4），行为对齐 `KeywordAndroidSkillResolver` |

## 运行时组成

- `SkillRepository(File rootDir)`：每次 `load()` 实时扫盘（不缓存），返回 `ScannedSkill(manifest, body, status, error?)`。
- `FileBackedSkillProvider(plugins, repository, embedRoots = emptyMap())`：合并式 `AndroidSkillProvider`。因为
  `AgentRuntime.Builder.skillProvider(x)` 会清空 `register(plugin)` 收集的静态 skills，宿主必须把已注册
  plugin 列表一并传入，保持原有 plugin skill 行为零变化。`embedRoots` 是命名根注册表（见"命名根嵌入"），
  建议与 `AgentSkillRuntimePlugin(embedRoots = ...)` 传同一份 map，让 `skill_read` 的 embed 清单按正确的
  根标注存在性。`AgentRuntime.Builder.skillProvider(x)` 持有 Provider 引用并在**每次 run 重新查询
  `skills()`**（2026-08-27 起的语义；此前 Builder 会拍平成静态快照），因此运行期新增的文件 skill、
  新写入的 embed 内容从下一次 run 起自动生效，无需重建 Runtime。Provider 实现须保证并发调用安全
  （多个 session 的 run 可能并发调用 `skills()`）。
- `LoadPolicySkillResolver(repository)`：always/indexed 无条件通过；triggered 与静态 plugin skills 走
  `KeywordAndroidSkillResolver` 兼容语义（含 method toolName 匹配）。假设文件 skill 与静态 plugin skill
  的 id 不碰撞；若某静态 skill 与 always/indexed 文件 skill 同 id，两者都会被无条件注入（当前无此情形）。
- `AgentSkillRuntimePlugin`（id=`agent-skill-runtime`）：注册工具与全局 instructions；`skills()` 返回空，
  文件 skills 由 Provider 统一供给，避免双重注入。构造参数 `embedRoots` 透传给 `skill_read`。

工具一览：

| 工具 | 说明 |
|---|---|
| `skill_list` | 列出所有 skill 的 name/description/loadPolicy/status（invalid 附 error） |
| `skill_read` | 按名字读 skill 全文 body + embed 清单标注；裸条目按 skill 目录、别名条目按命名根标注 `(missing)` / `(unknown root: 别名)`；未知名报 `SKILL_NOT_FOUND` |
| `memory_list` | 列出 agent-memory 目录各分类文件的 name/bytes/lastModified |
| `memory_read` | 读分类全文；非白名单报 `UNKNOWN_CATEGORY`，缺文件报 `NOT_FOUND` |
| `memory_write` | 整文件覆写（默认 overwrite=false，存在即报 `FILE_EXISTS`，模型应先读后合并再覆写）；单文件 16KB 上限 |
| `memory_delete` | 删除分类文件；高影响，默认由 `UserConfirmationRequiredTool` 包装，需先走 `show_user_confirmation_dialog` |

## 目录布局（demo-app 实例）

```
<filesDir>/agent-skills/<skill-name>/SKILL.md        # 文件型 skills（种子 + 用户自放）
<filesDir>/agent-memory/{user-profile,preferences,facts,rules}.md   # 记忆沙箱，即 "memory" 命名根
```

demo 通过 `DemoAgentRuntimeFactory` 把 `memory → <filesDir>/agent-memory` 注册进
`FileBackedSkillProvider` 与 `AgentSkillRuntimePlugin` 的 `embedRoots`。

## 记忆分类

- `preferences`：交互偏好（语气/语言/格式/简洁度）。
- `rules`：用户立下的操作规则（"永远/不要…"）。
- `user-profile`：称呼、身份、基本情况。
- `facts`：具体事实（设备、常用 app、账号尾号等）。

`preferences` 与 `rules` 通过命名根每轮实时嵌入（活数据常驻，无需模型调工具）；`user-profile` 与
`facts` 按需 `memory_read`。记忆协议由 `agent-memory` skill（always 注入）约束：捕获须先在对话中
提议、得到明确同意后 read → 合并（不得丢条目） → overwrite 覆写 → 简短确认；禁止未经同意写记忆、
禁止编造记忆。

## 种子机制

模块 assets 打包 `agent-skills/`，`AgentSkillSeeder.seed(context)` 把它复制到 `filesDir/agent-skills/`。
已存在的目标绝不覆盖（幂等，返回本次实际种子数量），用户改动与自建 skill 不会被升级覆盖。
`agent-memory` 只打包 SKILL.md，不带静态偏好/规则模板——真实记忆由 `memory:` 命名根实时嵌入，
空记忆以"文件不存在已跳过"注记呈现。注意：因种子绝不覆盖，从旧版（embed 指 skill 目录静态文件）
升级的安装会保留旧 SKILL.md 与旧模板，需要清掉该 skill 目录才会切换到命名根语义。

## v2 展望

- `skill_save`：Agent 在运行期把新 skill 写入 agent-skills（自沉淀），含 frontmatter 校验与
  覆盖确认，尚未实现。
