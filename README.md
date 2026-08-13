# awesome-ugk-agent-android

Android Agent Runtime SDK：提供通用 Agent 工具循环、Android Skill，以及一个无障碍屏幕操控 Demo。当前同时开发一个随宿主 App 安装的 Headless Terminal Runtime，让 Agent 在确认和资源策略约束下使用 Bash、curl、OpenSSL、SQLite 与 CPython。

## 当前状态

这是开发中的 SDK，不是最终发布版。

- Core Profile：Bash、curl、OpenSSL、SQLite、CPython 3.14.6；最低 `minSdk 24`。
- x86_64 API 24/29/35（4 KB）和 API 36（16 KB）已通过双 `applicationId` 回归。
- Runtime 控制 Gate 3 已在 x86_64 API 24/4 KB 与 API 36/16 KB 通过。
- arm64 运行、完整 page-size/API 矩阵、Release AAB、升级迁移、低资源、性能和许可证发布检查仍未完成。
- v1 不支持、不打包、不宣称 Node.js、Git、OpenSSH、jq。
- Runtime 无 UI，不要求安装 Termux 或第二个 App；它与宿主共享 Android UID，不是安全沙箱。
- `pi-system-skill-android` 提供白名单 Android 原生 Intent Tool；打开网页、相机、拨号、地图、分享等动作不通过终端执行。

## 模块

```text
:ugk-pi-android              Agent Runtime 核心
:ugk-terminal-runtime-android Headless 原生 Runtime（Bash/Python/网络/SQLite）
:pi-terminal-skill-android   terminal_bash_execute Agent Tool
:pi-file-skill-android       应用私有文件 Skill
:pi-schedule-skill-android   定时任务 Skill
:pi-system-skill-android     系统设置/权限/Intent Skill
:terminal-probe-demo-a/b     不同 applicationId 的 Runtime 验证 App
:demo-app                    无障碍屏幕操控示例（前后台切换保留测试会话/草稿）
```

## 构建与验证

Windows PowerShell：

```powershell
$env:JAVA_HOME = 'E:\Android\Android Studio\jbr'
.\gradlew.bat :demo-app:assembleDebug --console=plain
```

完整单元测试、双宿主仪器测试和 Runtime 静态验收见 [`AGENTS.md`](AGENTS.md) 与 [`docs/terminal-runtime-validation.md`](docs/terminal-runtime-validation.md)。

## 文档入口

从 [`docs/README.md`](docs/README.md) 开始。当前唯一事实源包括：

- [`docs/terminal-runtime-baseline.md`](docs/terminal-runtime-baseline.md)：目标、scope、状态和边界；
- [`docs/terminal-runtime-architecture.md`](docs/terminal-runtime-architecture.md)：架构与数据流；
- [`docs/terminal-runtime-development-plan.md`](docs/terminal-runtime-development-plan.md)：当前计划；
- [`docs/terminal-runtime-validation.md`](docs/terminal-runtime-validation.md)：验证矩阵和命令；
- [`docs/terminal-runtime-troubleshooting.md`](docs/terminal-runtime-troubleshooting.md)：踩坑与修复；
- [`docs/terminal-runtime-decisions.md`](docs/terminal-runtime-decisions.md)：正式决策；
- [`docs/terminal-runtime-release-checklist.md`](docs/terminal-runtime-release-checklist.md)：发布清单。

历史过程记录保存在 [`docs/archive/`](docs/archive/) 中，不作为当前状态依据。

持续开发台账：[awesome-ugk-agent-android-ledger](https://github.com/mhgd3250905/awesome-ugk-agent-android-ledger)。

## 终端 Runtime 接入概要

宿主 App 需要依赖：

```kotlin
dependencies {
    implementation(project(":ugk-terminal-runtime-android"))
    implementation(project(":pi-terminal-skill-android"))
    implementation(project(":pi-system-skill-android"))
}
```

并显式启用：

```kotlin
android {
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}
```

接入详情、确认策略、取消 API、权限和安全边界见 [`ugk-terminal-runtime-android/README.md`](ugk-terminal-runtime-android/README.md)。

Android 原生 Intent 和跨 App 自动化接入：轻量宿主可注册
`AndroidIntentAgentPlugin(context, confirmationPresenter)`，Agent 通过
`launch_android_app_intent` 使用白名单动作（例如 `open_url`）。完整的 Android 操作宿主应注册
`AndroidAutomationAgentPlugin`，并提供宿主无障碍服务的 `ComponentName` 和
`AndroidAccessibilityServiceStateProvider`，获得以下工具：

- `find_android_app`：通过 `PackageManager` 将用户说的 App 名称解析为候选包名；
- `launch_android_app`：通过包名的 launcher Activity 启动 App，不依赖无障碍；
- `get_android_accessibility_status`：检查无障碍是否由用户开启、服务是否已连接；
- `open_android_accessibility_settings`：打开系统设置，由用户手动授权；
- `launch_android_app_intent`：执行白名单的 URL、相机、拨号、地图和分享等 Intent。

跨 App 读屏和点击仍由宿主提供的 `screen_*` Tool 完成，Agent 必须先确认
`readyForScreenAutomation=true`，每次动作后重新读取屏幕验证。所有外部可见动作默认仍需先通过
`show_user_confirmation_dialog`。Intent Tool 以真实 `Context.startActivity()` 结果为准；预查询可能受
Android 11+ package visibility 影响，不能把 `resolveActivity()` 的空结果直接当成“设备没有浏览器”，也不能根据
终端里的 `am`/`pm` 失败推断 Android 应用状态。`AndroidSystemAgentPlugin` 与这两个插件应按能力选择，避免重复注册同名确认 Tool。

SDK 内 Agent 的运行时规范另有一份同名 `AGENTS.md`：
`pi-terminal-skill-android/src/main/assets/ugk/AGENTS.md`。它随 terminal skill 打包，并在注册
`TerminalAgentPlugin` 时自动注入每次模型请求；仓库根目录的 `AGENTS.md` 仅是开发协作规范，不能混用。

本机 Debug 开发使用被 Git 忽略的 `local.properties` 固定签名，并可通过 `ugk.api.config` 指向外部 API
配置文件。首次启动 Debug App 时会把配置写入应用私有设置；普通覆盖安装不会丢失。API key 不进入源码或 Git，
但会进入本机 Debug APK，Debug APK 不应分享给他人。
