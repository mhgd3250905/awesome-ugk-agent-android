# SDK 优化推进台账

更新时间：2026-08-14

## 版本与推进规则

- 本台账记录 SDK 架构优化步骤，不替代 `demo-app` 的产品版本台账。
- 每一步使用独立编号，必须经过：范围确认、实现、主线程审核、相关验证、文档记录和 Git 逻辑提交。
- 未形成正式发布物前，不因内部修复擅自提升 `demo-app` 版本或 Maven Artifact 版本。
- 每一步只修改目标范围，保留工作树中已有的用户改动。

当前基线：`demo-app 0.2.1` / `versionCode 3`；SDK 当前仍处于持续开发阶段。

## SDK-OPT-001：ToolRegistry 重复 Tool ID 治理

状态：已实现，主线程复核通过

目标：避免多个 Plugin 注册同名 Tool 时静默覆盖，降低能力组合错误的排查成本。

实现范围：

- `ToolRegistry.register()` 在写入前检查同名 Tool。
- 重复注册抛出 `IllegalArgumentException`，原有 Tool 保留，不发生隐式替换。
- 保持现有 `register(): ToolRegistry` 方法签名和唯一注册行为不变。
- 增加首次注册、同实例重复、不同实例重复、不同名称和 Plugin 注册路径测试。

验证：

```powershell
.\gradlew.bat :ugk-pi-android:testDebugUnitTest `
  --tests com.ugk.pi.android.ToolRegistryTest `
  --tests com.ugk.pi.android.AgentCapabilityPluginTest `
  --console=plain
```

结果：通过；`git diff --check` 通过。

兼容性影响：此前依赖“后注册覆盖先注册”的错误配置将改为在注册时失败；当前没有提供隐式覆盖行为。Demo 版本和 SDK Maven 版本均未提升，本步骤不构成正式发布版本。

下一步候选：统一 Plugin `close()` 和取消契约。只有在本步骤提交并确认工作树状态后，才进入下一步。
