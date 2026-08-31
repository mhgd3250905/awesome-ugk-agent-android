# Terminal Runtime 发布检查清单

状态：未完成。以下项目全部关闭后，才可以称为发布候选；当前 x86_64 Debug Gate 通过不等于本清单通过。

## A. Scope 与功能

- [x] v1 Core 固定为 Bash、curl、OpenSSL、SQLite、CPython 3.14.6。
- [x] Node.js、Git、OpenSSH、jq 明确排除并不进入 Core payload。
- [x] `terminal_bash_execute`、确认策略、超时、并发、输出截断和取消 API 有文档。
- [x] Runtime 明确说明无 UI、无需 Termux/第二个 App。
- [ ] 宿主接入文档由独立消费 App 验证，不依赖源码仓库中的未发布文件。

## B. 原生与打包

- [x] 两 ABI ELF/DT_NEEDED/GNU_STACK/TEXTREL/PT_LOAD/哈希静态验收。
- [x] `nativeLibraryDir` 提取式执行路径已通过 x86_64 双宿主。
- [x] 未压缩 `.so` 失败路径已记录，宿主配置要求已写入 README。
- [ ] arm64 4KB 运行通过。（注：已有 arm64-v8a / Android 14 / API 34 / 4 KB 真机受限运行证据，见 `terminal-runtime-validation.md` §14 与 Gate 总表；按 README “不能宣称 arm64 完整通过”的口径，本发布项仍未关闭）
- [ ] arm64 16KB 运行通过。
- [ ] API34、API35/16KB、API36/4KB 等剩余组合通过。
- [ ] Release APK/AAB split 在目标 ABI/API 安装并执行。

## C. 生命周期与资源

- [x] 普通 session descendant 超时清理。
- [x] TERM-resistant descendant 的 SIGKILL 升级。
- [x] 同一 Plugin 实例主动取消、排队取消、`cancelAll()`。
- [x] 并发槽位和输出上限单元测试。
- [ ] Android 进程被杀后的恢复/清理行为。
- [ ] 低磁盘、低内存、安装中断和 Runtime 数据迁移。
- [ ] 长时间/高并发性能和内存预算。
- [ ] 是否需要跨进程 Service/Binder Supervisor 的正式决策。

## D. 安全与权限

- [x] 默认用户确认。
- [x] 托管环境、CA、Python 路径不能被 Tool 覆写。
- [x] 文档明确 Runtime 与宿主共享 UID，不是安全沙箱。
- [ ] 提示注入/恶意脚本/网络外发威胁模型。
- [ ] 宿主秘密、workspace、日志和输出的泄露审计。
- [ ] Google Play/发行渠道对网络外发、原生代码和后台运行的政策复核。

## E. 许可证与供应链

- [x] `runtime-lock.json` 记录输入、工具和产物哈希。
- [x] `THIRD_PARTY_NOTICES.md`、GPL-3.0 文本和来源链接存在。
- [ ] 将仅用于 `patchelf` 的 `node@sha256` 构建镜像替换为非 Node 基础镜像，并重新锁定/验证构建工具。
- [ ] 生成最终 SBOM。
- [ ] 准备 Bash 对应源码、补丁和构建脚本的发布材料。
- [ ] 做一次依赖 CVE/安全更新审计，并记录响应方式。

## F. 发布判定

只有 A-E 全部完成且结果绑定到一个干净 commit，才能：

1. 生成版本化 AAR/AAB；
2. 由独立消费 App 验证；
3. 发布 Maven/内部仓库候选版本；
4. 对外宣传支持范围。
