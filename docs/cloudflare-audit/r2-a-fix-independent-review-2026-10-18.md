# R2-A 两缺口修复（第1轮）独立复验报告

- 验收端：同一只读验收端，针对性复验；未全分支重读、未重跑无关全套
- 日期：2026-10-18 22:50–23:15 CST
- 状态：branch feature/cloudflare-p3-r2 @ 3c7284b5（不变），dirty 25 项（含修复）
- 修复选择：补实现（不改 D5 规格原文）

## 结论：两缺口均修复成立 —— R2-A 本地工程出口最终判定：**通过**（详见 A 报告附录最终节）

## 缺口 1：D5 批量删除写后回读（补实现）—— 复验通过

**实现核实**（R2Controller.kt:316–368）：
- `submitObject` 在 `api.writeObject` 完整 receipt 后、**同一 executeWithDispatchEvidence/coordinator 桶锁作业内**调用 `verifyBulkDeleteCompletion`（L334）——不在锁外、不另起事务。
- 仅处理 `BulkDelete + Completed`；Partial/Accepted/Unknown 原样返回，不探测、不假装完成（`bulkPartialAcceptedAndUnknownReceiptsDoNotPretendToHaveACompleteReadback` 断言 0 次读）。
- 冻结 key 集合（`p.write` 来自冻结 PreparedObject）；`commonPrefixWith` fold 收窄 prefix——surrogate 对安全（测试用 `甲😀/甲😁` 断言 prefix=`甲`）；不假设排序。
- 最多 `MAX_RECONCILE_PAGES=5` 页、每页 limit=1000；逐页检查目标 key 是否仍存在；重复 cursor→UNKNOWN；页预算耗尽（repeat 正常结束）→UNKNOWN；读异常→UNKNOWN（CancellationException 重抛）；游标穷尽且全部目标缺席才保留 Completed。全程无第二次 DELETE。
- 大 prefix >5000 项无法证毕即 UNKNOWN/人工核验，无强行解锁入口 ✓（与既有 UNKNOWN 语义一致）。

**TDD 证据核对**（独立读取，非转述）：
- RED XML（/tmp/flarego-r2-bulk-evidence/red/）：tests=43，**failures=5**，正是 5 项行为用例（缺席存在性、穷尽读、读失败不重放、cursor/预算/Unicode 前缀、UI 等待者取消）。
- GREEN XML：43/0。green-compile-failure.log（nextToken 拼写编译失败→修为 CloudPage.next）如实留存，未伪称行为 RED。
- 2 项直接绿补证（partial/accepted/unknown 不读、多页完成后页仍存在）与描述一致。

**本端独立执行**：
- `./gradlew :shared-core:jvmTest --rerun --tests 'com.flarego.core.R2ControllerTest' --tests 'com.flarego.core.R2CoverageTest' --tests 'com.flarego.core.P2CoverageTest'` → EXIT=0；独立 XML：R2ControllerTest **44**（37+7 新 bulk，含 9 个 bulk 相关用例）/ R2CoverageTest **4** / P2CoverageTest **6**，全绿。
- 设备（emulator-5554，2 方法 filter）→ EXIT=0，XML 独立读取 **2/2**：成功用例断言 DELETE 后存在 `GET /objects?`、恰 1 次 DELETE、SUCCEEDED；仍存在用例断言 UNKNOWN→reconcile→ACTION_REQUIRED、恰 1 次 DELETE（不重放）。旧零写入断言未弱化（`requests.all{GET}` 多处保留）。
- 单删/上传语义未动（源码核实：reconcileObject 单删列表穷举、put 仍 StillUnknown；D5 清单不含单删）✓。

## 缺口 2：coverage 门禁 Gradle inputs —— 复验通过

**实现核实**（shared-core/build.gradle.kts 追加块）：`tasks.named("jvmTest")` 声明
- `docs/cloudflare-audit/*.json,*.csv`（顶层 fileTree，RELATIVE）；
- 四模块 `src` 目录（shared-core/shared-ui/app/android-platform——UI/native 源变更即失效）；
- core `build.gradle.kts`；
- `SCHEMA_FILE` Provider（env 缺省 fallback pinned 本地 openapi.json，PathSensitivity.NONE=按内容哈希）。
- 无任意 JSON 路径解析、无 Groovy 依赖、未禁用 UP-TO-DATE、未改运行时授权 ✓。

**本端独立端到端验证**（仅环境变量，不改 repo；正常命令无 --rerun）：
1. `SCHEMA_FILE=/tmp/flarego-r2-bulk-evidence/schema-drift.json ./gradlew :shared-core:jvmTest --tests R2CoverageTest` → **EXIT=1**，`Task :shared-core:jvmTest FAILED`（schema SHA 失配被真实执行捕获）——证明 schema 文件已是有效输入且内容被校验。
2. 恢复默认 `./gradlew :shared-core:jvmTest --tests R2CoverageTest` → EXIT=0，task 行为**实际执行**（非 UP-TO-DATE），BUILD SUCCESSFUL。
- 说明：负例轮 XML 被恢复轮同名覆盖，负例以 stdout（/tmp/flarego-r2-a-fix-independent-evidence/schema-negative-stdout.log）+ 退出码 1 为证，如实记录。
- 主控既有负例矩阵（matrix source_sha 漂移旧配置 UP-TO-DATE=行为 RED、新配置 4 失败、外部 Native 注释 4 失败、不变 UP-TO-DATE、flags 恒 false）目录/XML 均在 /tmp/flarego-r2-bulk-evidence/，本端抽核存在性与 RED 日志行，未重跑（同快照）。

## manifest 与台账
- `r2-batch-a-runtime-support-2026-10-18.json`：25 个 source_sha256 重算 **0 mismatch**；51=10+40+1、注册 79、三 flag false——修复后 hash 已同步更新，无漂移。

## 证据索引
- 本端：/tmp/flarego-r2-a-fix-independent-evidence/{unit/{stdout.log,xml/}, native/{stdout.log,xml/}, schema-negative-stdout.log, schema-restore-stdout.log}
- 开发者（继承引用，同快照）：final unit 842/19/97 XML /tmp/flarego-r2-bulk-evidence/final-unit/；Native 21/21 /tmp/flarego-r2-bulk-evidence/selftest/native/ 与 /tmp/flarego-r2-a-fix-selftest.log；audit349/CI10 通过；RED/Green /tmp/flarego-r2-bulk-postread-{red,green}.log
- 范围外未验证（不变）：真实云/付费/临时凭据/跨版本 API26–35、发布资格——仍 0，declined-to-judge。
