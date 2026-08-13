# Design System

## Theme

浅色、克制、偏生产力工具。物理场景是：开发者在明亮桌面前用手机观察一次 Agent 任务，注意力集中在“下一步是否需要我确认”和“任务是否完成”，因此正文表面保持安静，状态色只承担语义。

## Color Strategy

Restrained。使用温和的暖灰表面、墨绿色文字和单一薄荷色主操作色。错误、警告和成功使用低饱和语义色，并同时显示文字标签。

## Color Tokens

| Token | Value | Usage |
|---|---|---|
| surface | `#F8FAF7` | 页面主背景 |
| surfaceElevated | `#FFFFFF` | 输入区、弹层、过程卡片 |
| surfaceSubtle | `#EEF3EF` | 会话抽屉、次级区域 |
| textPrimary | `#172322` | 正文、标题 |
| textSecondary | `#5E6D6A` | 元信息、辅助说明 |
| textMuted | `#87928F` | 占位、时间、弱提示 |
| accent | `#2FC38D` | 发送、选中、活动状态 |
| accentPressed | `#197E5D` | pressed/深色文本 |
| success | `#2C8B61` | 成功 |
| warning | `#A66A1F` | 等待确认、警告 |
| danger | `#B94A4A` | 错误、停止 |
| outline | `#D9E2DD` | 边框、分隔线 |

## Typography

- Android system sans-serif，优先使用系统字体以获得原生移动体验。
- Screen title: 20sp, medium/bold。
- Section label: 13sp, medium。
- Message body: 16sp，行高约 24sp。
- Supporting text: 13sp，行高约 18sp。
- Tool detail: 12sp，行高约 17sp。
- 不使用展示字体、渐变文字或全大写按钮。

## Spacing and Shape

- 基础间距：4、8、12、16、24dp。
- 消息流左右内边距：16dp；用户气泡最大宽度约 82%。
- 输入区最小高度：52dp；多行上限 5 行。
- 主要圆角：12dp；用户消息圆角 18dp；状态徽标 999dp。
- 触控目标：最小 44dp。
- 阴影少用，优先使用表面层次和 1dp outline。

## Information Hierarchy

1. 当前聊天内容和最新助手回答（主界面按聊天时间线展示；悬浮窗的最终回答位于过程和活动记录之后）。
2. 发送/停止以及当前 Agent 运行状态。
3. 工具过程卡片的摘要状态。
4. 会话标题、模型/API 源和权限状态。
5. 展开后的调试细节。

## Components

- App bar：会话入口、当前会话标题、设置入口；不放长状态文本。
- Conversation turn：用户消息右对齐，助手消息左对齐；助手回合可以附带过程卡片。
- Run progress card：默认一行摘要，展示状态图标、阶段名称、耗时；点击展开步骤和结果摘要。
- Floating overlay：后台显示状态胶囊；展开后按“用户消息（如有）→ Agent 过程 → 活动记录/排队状态 → 最终回答”的时间线排列。右下角使用加粗圆弧作为无图标缩放提示，缩放触控层不得遮挡输入和发送按钮。
- Composer：多行输入、可换行、发送/停止互斥；空输入时禁用发送。
- Session drawer/dialog：本地会话列表、新建、重命名、删除；删除需要二次确认。
- Status banner：只在缺少 API、无障碍或悬浮窗能力时显示，支持关闭或跳转设置。

## Motion

只使用 150–220ms 的状态过渡。过程卡片展开/收起和发送按钮状态切换属于反馈动画，不做页面入场编排。系统设置或外部 App 切换不依赖动画完成。

## Interaction States

每个主要控件必须覆盖默认、pressed、disabled、loading、error 状态。运行状态至少包含：就绪、思考中、调用工具、等待用户确认、工具完成、工具失败、已完成、已停止。
