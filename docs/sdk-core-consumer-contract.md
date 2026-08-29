# Core SDK 外部消费者契约

更新时间：2026-08-29

本文记录 `:ugk-pi-android` 当前阶段的最小外部消费边界。它是可重复验收契约，不代表已经完成最终发布体系或 API 稳定承诺。

## 当前坐标与发布方式

当前 Release publication 坐标为：

```text
com.ugk.pi:ugk-pi-android:0.1.0
```

发布来源是 `:ugk-pi-android` 的 `release` variant，产物包括 AAR、POM 和 Gradle Module Metadata。验收脚本将 publication 写入临时 Maven 仓库，不依赖或污染用户全局 `mavenLocal()`：

```powershell
.\scripts\sdk\verify-core-consumer.ps1
```

脚本会执行以下闭环：

1. 生成 Release AAR、POM 和 Module Metadata。
2. 校验 POM 与 Module Metadata 的坐标及依赖集合。
3. 校验 Core AAR 至少包含 `AndroidManifest.xml`、`classes.jar`，且不带 native `.so`。
4. 在临时目录生成一个只有 `:ugk-pi-android` 依赖的最小 Android library consumer。
5. 编译并打包 consumer，证明外部项目可以通过发布坐标使用 Core 的公开类型。

## 当前已验证的依赖边界

当前 publication 的直接依赖集合为：

| 坐标 | 版本 | 结论 |
|---|---:|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | `1.7.3` | 允许的 Core 运行时依赖 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.4.0` | 允许的 Core 运行时依赖 |
| `org.jetbrains.kotlin:kotlin-stdlib` | `2.2.21` | Kotlin 编译插件产生的运行时依赖 |

验收同时拒绝其它 publication 依赖，特别是 `demo-app`、`ugk-terminal-runtime-android`、`pi-file-skill-android`、`pi-schedule-skill-android`、`pi-system-skill-android` 和 `pi-terminal-skill-android` 的模块依赖。

Core 源码当前也没有对这些模块或 Android framework API 的源码引用；Core 作为 Android library 仍保留空 `AndroidManifest.xml`，因此外部消费者必须是 Android 工程，而不是普通 JVM 工程。

## 这一步证明了什么

- `:ugk-pi-android` 可以单独生成 Release AAR。
- publication 的 POM/Module Metadata 没有把 Demo、Terminal 或其它 `pi-*` 模块带入依赖图。
- 一个只声明 Core artifact 的最小 Android consumer 可以编译并打包。
- 本步不需要新增永久 Gradle consumer module，验证 fixture 由脚本临时生成并在结束后删除。

## 尚未证明的内容

- 尚未承诺 `0.1.0` 是正式发布版本，也没有升 Demo 版本或 SDK 版本。
- 尚未建立 API/ABI compatibility baseline、签名校验、远程 Maven 仓库发布和版本兼容策略。
- 尚未证明任意第三方网络环境、不同 AGP/Kotlin 版本或不同 Android page size 下的完整运行时行为。
- 尚未验证 Core 与外部 Provider、Tool、SessionStore 的真实端到端业务流程；本步只验证编译和 artifact 边界。
- Core 的当前公开 API 仍把 `AndroidSkill` 等 Android 语义类型放在同一 artifact 中；是否进一步拆分应等待真实消费者需求，不在本步扩大范围。

## API/ABI 与版本策略审查（SDK-OPT-007）

本步骤只建立可重复的 API surface inventory，不建立或强制 API/ABI compatibility baseline。

### Release AAR 的当前事实

在执行 `:ugk-pi-android:bundleReleaseAar` 后，使用以下只读检查脚本可以从实际 Release AAR 的
`classes.jar` 生成统计：

```powershell
.\scripts\sdk\inspect-core-api-surface.ps1
```

当前构建结果：

| 指标 | 数量 | 说明 |
|---|---:|---|
| `classes.jar` 中的 class 文件 | 122 | 包含 Kotlin 编译器生成的协程状态机、lambda 和辅助类 |
| 顶层 class 文件 | 64 | 包含 Kotlin 文件 facade |
| `javap -public` 类型声明 | 89 | 包含 `DefaultImpls` 等编译器生成类型 |
| 面向源码消费者的 public 类型 | 82 | 排除 Kotlin facade 与 `*DefaultImpls` 后的审查口径 |
| `javap -public` public member signatures | 800 | 包含 data class 的 getter、`componentN`、`copy`、默认参数和 access bridge |

主要类型按职责分组如下：

- Runtime/运行模型：`AgentRuntime`、`AgentSession`、`AgentEvent`、`AgentMessage`、`AgentRunInput`、`AgentRunSource`。
- Tool/Plugin：`AgentTool`、`AgentToolDefinition`、`ToolRegistry`、`ToolCall`、`ToolResult`、`ToolProgress`、`ToolExecutionContext`、`AgentCapabilityPlugin`。
- Provider/传输：`LLMProvider`、`ModelRequest`、`ModelResponse`、`AnthropicMessagesProvider`、`OpenAiChatCompletionsProvider`、`HttpTransport`。
- Skill/Session/时间上下文：`AndroidSkill*`、`SessionStore`、`InMemorySessionStore`、`AgentTimeContext*`。
- 确认协议：`UserConfirmationDialog*`、`UserConfirmationRequiredTool`。

这些数量是 2026-08-29 阶段 8 当前 Release artifact 的观察结果，不是 API 稳定性承诺。尤其是 800 个 binary member
signature 不能直接作为人工维护的稳定 API 数量；Kotlin 编译器生成的成员会随源码形态和编译器
版本变化。

阶段 5—7 新增或调整了 `AgentSession` transcript ownership、`TranscriptPreparationPolicy`、
capability assembly 的 provider source/resolution context，以及通用 `AgentToolDecorator`/
`AgentToolInterlock` seam。`CompositeAndroidSkillProvider` 和 `RuntimeSkillAccumulator` 虽进入
`classes.jar`，其 JVM class declaration 为 package-private，不属于 public consumer seam。

当前 Gradle 仅显式配置 JVM target 17，没有显式固定 `jvmDefault` mode；当前 `javap` 同时可见
interface default method、`DefaultImpls` 与 `$jd` bridge。仓库没有带可信发布坐标/commit 元数据的旧
AAR，也没有旧 consumer 升级矩阵，因此本次 inventory 只能描述当前表面，不能证明相对旧版本的
源码或二进制兼容。D-023/D-024 中记录的 `0.x` 有意 source/semantic change 需要消费者按迁移说明处理。

### 当前 publication 与版本约束

- publication 只配置 `release` variant，坐标固定为 `com.ugk.pi:ugk-pi-android:0.1.0`。
- 版本写在 `ugk-pi-android/build.gradle.kts` 中，当前没有 API/ABI 检查插件、兼容性基线、远程发布、签名或多版本矩阵。
- `0.1.0` 是当前开发阶段用于本地/临时 consumer 验证的坐标，不是正式发布承诺；不能据此推断 API、ABI、运行时行为或升级兼容性。
- Core SDK 版本与 Demo 版本分开管理；当前 Demo 为 `0.7.0 / versionCode 8`，本步骤不因 inventory 结果提升任何版本。

### 轻量版本策略（当前持续开发阶段）

- SDK 版本唯一事实源是 `ugk-pi-android/build.gradle.kts` 的 `MavenPublication.version`；当前 `0.1.0` 只是开发期消费坐标。
- `scripts/sdk/verify-core-consumer.ps1` 中的 `ArtifactVersion` 是验收参数，不是第二个版本源；未来准备候选版本时，必须在同一提交同步更新 publication、脚本默认值和文档。
- 内部修复、测试、文档和架构整理不 bump；准备对外候选版本时，向后兼容的稳定性/安全修复使用 patch，新增公开能力或 `0.x` 阶段的有意破坏性调整使用 minor，并提供迁移说明。
- 只有完成稳定公共 API 分层、正式分发和兼容性承诺后，才考虑 `1.0.0`；`1.x` 之后的破坏性 API 调整才使用 major。
- Demo 只维护自己的 `demo-app/build.gradle.kts` 中的 `versionCode/versionName`：SDK 内部修复不自动触发 Demo bump，只有 Demo 可安装交付物或用户可感知行为变化时才提升 Demo 版本。
- `ugk-terminal-runtime` 的原生依赖/CMake 版本和 Demo 版本均不属于 Core SDK publication 版本。

### 正式发布的触发条件与当前边界

当前只要求 Release AAR、POM/Module Metadata、临时隔离 Maven consumer、Core 单元测试和必要的 API surface inventory 通过；不建立永久 consumer、远程仓库、签名体系或强制 API/ABI baseline。

API/ABI gate 分成“必要前置条件”和“触发事件”，避免把两者混成一个模糊条件：

- 必要前置条件（全部满足）：完成稳定的 Core 公共 API 分层；确定正式版本策略、发布渠道和兼容性承诺；具备至少一个旧版本升级回归场景。
- 触发事件（任一发生）：出现需要长期维护的真实外部 consumer 或 `0.x` 坐标开始正式分发；发生一次需要证明“无意 API/ABI 破坏”的跨版本变更。

只有必要前置条件全部满足且发生任一触发事件时，才把 API/ABI baseline 提升为 release-candidate gate。该 gate 只阻断候选发布，不阻断日常 Debug 开发；Core API gate 也不混入 Terminal Runtime 的 ELF/page-size gate。

### 基线决策

当前阶段采用“只记录、暂不强制”的 API/ABI 基线策略。原因是 Core 的公开面仍在快速收敛，且目前只有
最小外部编译消费证据，没有正式第三方消费者、远程发布承诺或稳定的 API 分层；此时引入完整兼容性
插件会提前冻结设计并增加维护成本。

必要前置条件全部满足且发生任一触发事件后，再建立并强制 baseline：

1. 必要前置条件：明确哪些 Provider、AndroidSkill、确认类型属于稳定承诺。
2. 必要前置条件：确定正式版本策略、发布渠道、兼容性承诺，并准备至少一个升级回归场景。
3. 触发事件：出现需要长期维护的真实外部 consumer，或 `0.x` 坐标开始对外正式分发。
4. 触发事件：发生一次需要证明“无意 API/ABI 破坏”的跨版本变更。

触发后，baseline 应先作为发布 gate 运行，不应直接阻塞所有日常开发构建。本步骤不引入插件、永久
consumer module、生产依赖或版本变更。
