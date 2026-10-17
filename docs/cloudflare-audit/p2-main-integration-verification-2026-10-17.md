# P2 主线集成与本地验证（2026-10-17）

用户在明确询问“提交、合并到main并推送origin/main”后回复“继续”，本轮据此执行集成。功能提交 `fbbb954434191e19f8aaa5c94c9ce1ccbff18489` 已以fast-forward合入本地主线。GitHub目标为 `origin/main`（`SatoriTours/FlareGo`）；本报告记录本地验证，最终远端ref确认由推送后回执保存，不预填远端成功。

## 提交与差异

基线 `8371605815e5a20c95089e0c4cfe0bf7c8b401ee`。合并前 fetch --no-tags 取得的远端main仍等于本地基线，没有覆盖其他分支改动。140个文件先逐字节与独立备份核对，再精确暂存。

暂存检查发现新增`2.sqm`末尾多余空行，已仅移除空白，SQL语句未变；Windows wrapper按现有`.gitattributes`规范化Git索引行尾，忽略行尾的diff为空。记录保存为`integration-staging-check.json`。没有私钥、TokenVault、本机配置、构建产物或备份归档进入提交。

## 实际main验证

| 检查 | 结果 | 来源 |
| --- | --- | --- |
| 完整core | 706／0 failure/error/skip，16.41s | `recovery-evidence/integration-main-core`；6 tasks executed |
| 平台完整单测 | 91／0 failure/error/skip，构建组合59.07s | `recovery-evidence/integration-main-platform` |
| Debug assemble／lint | app／platform均0 error，3／4 warnings | 同上，129tasks executed |

均在 `/home/jimxl/projects/FlareGo` 的实际main运行，统一flock、JDK17、offline、固定SCHEMA_FILE，before/after源码一致。主线验证后只补日期化文档和机器台账集成状态，不改产品／永久测试；更新后coverage6实际通过（11.73s），Python审计349／CI契约10、CLI及DNS生成字节一致性也实际通过，结果保存在`integration-main-coverage`／`integration-main-python`。

设备验证沿用同一产品源码恢复批次的354个唯一方法分轮PASS证据。本轮没有重跑354项，不称单轮全绿：原330轮326PASS／4FAIL、后续9PASS覆盖原失败，另24项systemfont2真实VM／DB／SAF／native通过。后续原Zone13项与20次有界交互诊断通过，一次历史缺节点原因仍未确定，不能声称根因修复。迁移末尾空白调整不改变SQL逻辑。

## 保留的能力边界

54候选完整保留，实际registry69=候选54与P1历史16重叠5＋独立authority4READ；39READ／30WRITE。权限公开三态、本机风险选择与付费资格互不替代。UNKNOWN只读核对、不重放，不恢复payload／plan／risk；scan／purge受理不等于后台或全球完成。

本地synthetic provider与真实Android OS／数据库／SAF验证不是带凭据云验收。真实Cloudflare、付费资格、外部Registrar／全球DNS传播未验收。没有创建或移动Tag／Release，没有版本发布或真实云写入。

原恢复／后续验收文档保留当时的“未提交／未合并”状态，以本记录为后续更新，不重绑旧XML。P0/P1历史台账不改写。P3–P9仍只有路线，未在本轮开展。

## 保存与收尾

主仓库忽略目录 `.superpowers/recovery/p2-worktree-removed-2026-10-17/main-integration/` 保存merge／push回执、新日志XML与源码快照、APK和最终Git源码归档；原 `final-validated`／`final-followup-validated` 独立备份保留。执行worktree仅在提交完整进入main并已存在GitHub、无未提交/待保留成果且无活动任务后按Paseo归档，分支与原协调workspace保留。

## Android CI 干净环境补项

推送前检查发现旧Android workflow未准备新增core测试必需的固定Schema，本机忽略缓存会掩盖缺口。实际锁内暂存本机公开缓存并取消SCHEMA_FILE后，既有`P2CoverageTest.pinnedDenominatorAndSchemaKeepEveryOriginalCandidate`真实1/1失败（14.91s）；finally恢复公开缓存。仅新增下载固定commit公开Schema、SHA256校验及GITHUB_ENV传SCHEMA_FILE步骤后，提取并实际执行该workflow步骤，从公开来源下载、验证SHA再跑同一既有测试，实际1/1通过（14.03s）。

CI修复提交`927fdace0b1c3d4212b9ab4a7f225cc4d5be8eb3`。从新workflow移除新增步骤后逐字节等于旧文件，签名／权限／发布gate完全未改；10项CI发布契约再次通过。证据保存于独立`main-integration/android-schema-red`／`android-schema-green`与`ci-workflow-contract.log`。这是本地执行真实workflow loader，不冒称GitHub Actions已完成；远端Actions结果只能在推送后另确认。
