# P1 主线集成与提交验收（2026-10-16）

用户明确要求提交代码并推送 GitHub。本批次实现集中提交为 `f8840c22b4ecdde0858b7f7ae5c7af64dd2db01a`（`feat: integrate Cloudflare P1 products and task recovery`），已从功能分支快进合并到 `main`。目标远端为 `https://github.com/SatoriTours/FlareGo.git` 的 `main`；GitHub 推送结果由原会话在远端 HEAD 核对后交付，不创建 Tag／Release，不触发测试版发布。

## 恢复与保存

提交前发现旧执行目录和 worktree 登记不再存在，原因未确认；没有将旧目录视为仍可复用。新建 Paseo `wks_f1d5e4711d92060b`（【自动】Cloudflare成果恢复提交），分支 `feature/cloudflare-foundation-recovery`，基线 `98cc0c9`。从原会话保留记录中按顺序提取并审阅源码文件写入，恢复 T4 测试修正与 T6–9；没有重放历史网络、发布、模拟器或 Git 命令。恢复后完整重新构建并测试，不用旧测试结果代替。

原主目录 17 份文档与提交的功能分支副本逐字一致；先保存本地备份并提交，再清理主目录重复副本和快进合并。没有覆盖其他修改。`gradlew.bat` 原始字节与基线 Git blob 一致，其属性造成的行尾差异不包含在提交中。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| 恢复源码 core／platform | 285／82 项通过，无失败／跳过 |
| 临时 API 36 全套原生 | 49 项通过，无失败／跳过，包括生产 VM、任务恢复／核对与迁移 |
| Debug 应用及测试 APK | 构建通过 |
| app／platform lint | 0 error，分别 3／4 warning（GradleDependency／UseKtx） |
| 固定审计／发布契约 | 333／10 项通过，无跳过 |

合并后在主目录再次执行 core、platform、Debug 构建和 lint；源码与恢复验证的 SHA-256 清单核对，不把文档更新当作新行为。原生证据来自同一功能源码的恢复工作区；只清理一处文件尾空行，不更改功能。详细命令、完整源文件绑定与本地日志保存在原协调目录 `.superpowers/integration-20261016/`，源码提交提供永久保存。

当前运行时调用与 L1 证据见 [P1 主线调用清单](p1-runtime-support-2026-10-16.json)：原 11 + 新增 5 = 16。原 P0 `implemented.json`／CSV／review 保留固定历史快照，不能把其中的 11 当作现在 App 调用数。没有重新生成固定 Schema 台账、扩大运行时白名单或将 L1 提升为真实云资格。

## 仍然适用的边界

P1 九项都有实现并通过本地工程验证；P2 十项尚未开始。旧 DNS Unit receipt 保持待核对，不重发；PATCH 可读回哈希确认，创建／删除缺少完整 receipt 或缺席证明，仍需用户核对。真实 Cloudflare、S3／GraphQL／实时协议、其他 Android API、签名 Release／升级安装没有本轮验收；真实资格仍为 0。复核按用户明确选择由当前会话完成，未向外部 provider 发送源码。

[Task9 开发与复核记录](p1-task9-verification-2026-10-15.md)及更早报告保留为当时状态；它们提到的未提交、旧空间和旧基线均被本次集成记录更新。
