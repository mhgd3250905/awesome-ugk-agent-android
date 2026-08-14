# Core SDK 外部消费者契约

更新时间：2026-08-14

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
