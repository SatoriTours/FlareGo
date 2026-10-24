# R2 预览生命周期与拒绝证据 独立功能验收报告

- 验收角色：功能验收（独立 fresh context，非实现者）
- 日期：2026-10-18 22:00–22:20 CST
- 仓库：/home/jimxl/projects/FlareGo；分支 feature/cloudflare-p3-r2；HEAD 3c7284b533ff081c9802b6ff12f36ab714e43dbb
- dirty 状态：23 项（已批准 R2-A 持续开发成果），验收期间未修改实现、未提交/推送、未派生、未清理

## 结论：通过（仅本轮范围）

必需验收项均有实际证据；范围外项见「未验证项」。

## 独立执行的命令与结果（非照抄自测数字）

1. 单元（JVM 三模块强制重跑）：
   - `./gradlew :shared-core:jvmTest --rerun :shared-ui:jvmTest --rerun :android-platform:testDebugUnitTest --rerun`
   - 退出码 0，BUILD SUCCESSFUL（6s，34 tasks，5 executed）
   - 独立统计 JUnit XML（/tmp/flarego-r2-retention-independent-evidence/unit-round/xml/，分模块保存避免同名覆盖）：
     - shared-core：38 个 XML，**tests=835，failures+errors=0，skipped=0**
     - shared-ui：2 个 XML，**tests=19，0 失败**
     - android-platform：7 个 XML，**tests=97，0 失败**
2. 设备（emulator-5554，flarego-r2-integration AVD，API 36，未启动/停止/改动设备）：
   - `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2ManagementUiTest,com.flarego.R2BucketEditorUiTest,com.flarego.R2DocumentPickerIntegrationTest,com.flarego.R2AuthorityOwnerIntegrationTest,com.flarego.DnsScanUiTest,com.flarego.DnsBatchPersistenceTest'`
   - 退出码 0，BUILD SUCCESSFUL（2m10s）
   - 独立读取设备 XML（/tmp/flarego-r2-retention-independent-evidence/native-round/xml/）：**tests=75，failures=0，errors=0，skipped=0（75/75）**
   - 该运行基于最终快照（含最后细微变更），补上了自测中"75 非最后变更后重跑"的缺口；此前旧预期（reconcile 后 UNKNOWN）失败未被随机重跑抹除——现断言已改为 FAILED（见下）。

## 契约逐项核对（源码 diff + 测试证据）

### 有界保留（64 条 opt-in、TTL 5 分钟、惰性回收）
- `ChangePlans` 新增可选 `retentionLimit: Int? = null`，默认 null 保留全部既有无界契约（`nextIdentity()` 在 null 时原样返回 id，DNS/通用路径行为不变）。R2WriteRuntime 以 `retentionLimit = 64` opt-in；TTL 沿用既有 `TTL_MS`。
- 满额拒绝新预览：`prepare` 先 `pruneExpiredLocked(trusted)` 再查容量，抛 CloudFailure(INVALID_INPUT)。测试 `aFullRetentionWindowRefusesNewPreviewsWithoutEvictingValidConfirmations`（64 满后拒绝，且全部仅 GET，confirm 仍成功、双击同一任务、单 POST）。
- 未过期/正在执行 claim 不驱逐：`pruneExpiredLocked` 只回收 `now >= expiresAtMs && issued[id]?.claim?.outcome?.isCompleted != false` 的条目；`expiryAndDiscardDoNotDetachAnInFlightAdmissionOrRecreateItsPersistedTask` 验证在途 confirm 期间 TTL 过期+另一预览 prepare 均不回收，完成后 SUCCEEDED、持久行仅 1 条。
- `retains()` 在 gate 争用时保守返回 true（不判死）。`pruneExpired()` 使用 trusted clock 且 `now >= 0` 才回收——坏时钟（now=-1）时保守保留，但撤销路径不依赖回收：`revocationDoesNotRequireAUsableClockOrAuthorizeNewWork` 验证 now=-1 可 discard、不可 prepare 新工作、恢复时钟后旧 planId 不可 confirm。
- 无后台轮询：仅 prepare/confirm/discard 入口惰性 `pruneRetained()`，无定时器/store 写。
- 满额拒绝仅限 R2 私有 issuer；通用 coordinator 未新增回收语义（`OperationCoordinator.kt` 仅新增 NotDispatched→FAILED 结清映射与 providerId=null）。

### ID 防复用
- `nextIdentity()` 对 bounded issuer 追加 `:bounded:<serial>` 序号（serial 有界，Long.MAX_VALUE 拒绝），trusted ID 源重复值也不会识别到旧 plan/confirmation。测试：`expiredPreviewsFreeCapacityButReusedIdInputsCannotReviveTheOldPreview`（fresh.planId ≠ old.planId，旧 id confirm 抛 CloudFailure，零写入）、`discardedPreviewsCannotConfirmOrExhaustCapacityEvenWithTheSameLiveCommandAndIdInput`（96 轮 discard 后同 ID 源无重复 planId、confirm 失败、容量不耗尽、最终一次成功 POST）。

### 取消语义
- `R2WriteRuntime.discardPreview` → `plans.discardUnclaimed`：仅撤销未 admit 预览；claim 存在返回 false，不删持久行、不关 transport FD、不结算任务。`R2Browser.invalidateWrite` 取消时 discard 当前预览；迟到（epoch/上下文失效）的 prepare 结果也会 `discardPreview`（`latePreparationsWhichLoseTheirLiveContextDoNotExhaustCapacity`，96 次丢失上下文不耗尽容量）。
- UI 侧 `cancellingManyPreviewsRevokesThemAndDoesNotExhaustTheRetentionWindow`（96 次取消后仍可新预览，全程零写入）。

### 缓存与元数据回收
- `repeatedExpiryReclaimsBucketAndObjectPayloadTablesAsWellAsIssuerMetadata`：4 轮过期后 runtime.prepared=1、issuer.prepared=1、issuer.issued=0、controller.prepared=1、controller.preparedObjects=0，全程仅 GET——runtime/controller 两类 payload 缓存与 issuer 元数据均回收。
- controller `pruneRetiredPayloads` 用 `plans::retains` 过滤两张原子表，执行中 claim 保守保留。

### 正常重复确认仍至多一次 mutation
- `aFullRetentionWindow...` 与 discard 测试均断言重复 confirm 返回同一 task.id 且仅 1 次 POST。

### R2-only 可信发送边界（NotDispatched）
- `R2Controller.executeWithDispatchEvidence`（bucket 与 object 两条路径共用）：仅当 `beforeSend`（composed guard）完成前置 `mayHaveDispatched=true`；guard 前异常 → 内部构造 `ExecutionReceipt.NotDispatched()`（internal 构造，无法伪造）→ coordinator 结清 FAILED、providerId=null。guard 完成后同错误码/最终 proof 拒绝原样抛出 → 保守 RUNNING/UNKNOWN，不推断已发送与否。CancellationException 原样重抛。adapter 契约违反（跳过 guard 却返回 Completed）→ `Unknown()`，不报成功。
- 测试：`aProvenPreDispatchRefusalSettlesWithoutReplayAndOnlyAFreshPlanMayWrite`（sends=0、FAILED 结清、reconcile 只读仍 FAILED 且读数不变、新尝试=新 plan 新确认新证明新 id、store 中旧任务保持 FAILED）、`theSameErrorAfterTheSendBoundaryCannotBeReclassifiedAsNotDispatched`（RUNNING、后续 prepare 因未结清被拒、sends=0）、`objectRefusalsUseTheSameTrustedBoundaryAndCannotBeRevivedByReadOnlyReconciliation`（含跨 scope reconcile 拒绝）、`anAdapterThatReturnsCompletionWithoutTheFinalGuardCannotClaimSuccess`（UNKNOWN）。
- `acceptedAndUnknownReceiptsDoNotPretendToHaveACompletedPostcondition`：ACCEPTED/UNKNOWN 仍阻断。无人工勾选解除入口（R2Browser taskNotice 对非终态只提示只读核对）。

### FAILED 不回退 UNKNOWN；Default/DNS 不受影响
- `settledRefusal`：FAILED 任务在相同 live AccountScope、actionKey ∈ 合法集合、ref scope/kind 匹配、requireSameLive、ScopeGuard.requireOwned 通过时直接返回该任务，不再进入 coordinator.reconcile——只读核对保持 FAILED。scope/action/kind 不符抛 invalidInputFailure（不静默）。
- `OperationCoordinator`/DNS 现有执行路径未构造 NotDispatched；`DnsScanPanel.kt` 仅新增 sealed 穷尽分支文案（「本次操作未分派；请核对输入和权限后重新预览确认」），行为不变。

### UI 有限视觉验收（本ege轮新增范围）
- 我具备图像读取能力，实际查看本轮设备运行拉取的 `pre-dispatch-refusal.png`（副本存 /tmp/flarego-r2-retention-independent-evidence/native-round/）：提示为「操作未成功；请核对当前输入、权限和任务状态，再次操作须重新预览确认」，任务标签 `r2-patch-bucket: FAILED`，**无「供应商拒绝」误称**。R2Browser FAILED 文案改为不谎称 provider 拒绝。上轮五图与整体布局不属本轮全面视觉验收。

## 设备测试断言核对
- `R2ManagementUiTest`：原 RUNNING/UNKNOWN 拒绝断言改为 FAILED（diff 确认两处 `-RUNNING/+FAILED`、`-UNKNOWN/+FAILED`）；`assertNoMutationWithRunningTask` 保留 `requests.all { it.method == "GET" }` 零写入断言，未弱化；新增拒绝截图捕获。line 213 的 UNKNOWN 属 unknown-class 用例，语义正确保留。

## 审计 manifest 抽核
- `docs/cloudflare-audit/r2-batch-a-runtime-support-2026-10-18.json`：source_sha256 24 个文件逐一重算，**0 mismatch/0 missing**；cross_cutting 引用存在；candidate 51 = batch_a 10 + not_started 40 + deferred 1；runtime_registry 79；authenticated_cloud_accepted / paid_eligibility_verified / release_qualified 均 false。

## TDD 证据存在性核对
- /tmp/flarego-r2-retention-evidence/ 含 red、cancel-red、dispatch-red、terminal-red、clock-red 及对应 green/final 轮目录与 pre-dispatch-refusal.png。本轮未逐轮重放全部历史 stdout（超出范围）；runtime 3 项、controller 2 项为直接绿补证一事与描述一致，未发现伪称 RED 的声明。

## 风险与观察（仅报告，不修改 repo）
1. **scope**：`settledRefusal` 依赖 resolver.current() 与 store.get；live scope 不匹配时抛 invalidInputFailure 而非返回 UNKNOWN——按契约属「保守拒绝」，非安全问题。
2. **race**：`R2WriteRuntime.prepare` 在 issuer.prepare 成功后、写入 runtime 原子表前丢失 live 上下文时会 discardUnclaimed 并抛 stale——CAS 循环无丢失更新；`discardPreview` 与 admit 的竞争由 issuer gate 内二次检查兜底，返回 false。未发现可重放/驱逐在途执行的窗口。
3. **容量保守性**：prepared 表计发生在 issuer，runtime 本地表仅视图；异常路径（如 stale）已即时回收，最坏情况是过于保守的容量拒绝，符合 ruling 代价取舍。
4. 非 Exception 的 Throwable（如 Error）不被 executeWithDispatchEvidence 捕获，任务保持保守状态，无错误分类风险。
5. 观察：`pruneExpired` 用 `gate.tryLock()`，争用时跳过回收——高并发下容量回收可能延迟，属设计内保守行为。

## 未验证项（范围外，如实声明）
- 真实 Cloudflare API/付费能力/临时凭据：未做（始终 false，不宣告真实云验收）。
- API 26–35 设备矩阵、全分支最终完整审查、R2-A 整体完成/发布资格：未做。
- `:app:lintDebug` 本轮未独立重跑（实现者自测通过；我的 connectedDebugAndroidTest 已隐式构建 Debug APK）。
- 上轮 PUT/PATCH 五张截图与 10/51 门禁细节：不属本轮，未重复开发/复验。
- 旧 835 以前历史报告范围未自动覆盖本轮新增。

## 证据索引
- /tmp/flarego-r2-retention-independent-evidence/unit-round/{stdout.log, xml/{shared-core,shared-ui,android-platform}/}
- /tmp/flarego-r2-retention-independent-evidence/native-round/{stdout.log, xml/, pre-dispatch-refusal.png}
- 实现者历史证据（未重放，仅存在性核对）：/tmp/flarego-r2-retention-evidence/

## 勘误（主控核对后更正口径，2026-10-18）
1. 【再勘误，推翻本条第一版】主控先前指示更正为 `assertNoMutationAfterConfirm` 不正确；主控复 rg 源码确认当前原名确为 `assertNoMutationWithRunningTask`（R2ManagementUiTest.kt:144，断言内容已改 FAILED 不变）。恢复真实原名，本条第一版系主控纠正自身误报，非原报告错误。
2. 保留 UNKNOWN 断言（R2ManagementUiTest line 213）的用例是「回执与权威读默认类型矛盾」，不是 unknown-class 编辑入口；unknown-class 截图用例为另一处。
3. `ExecutionReceipt.NotDispatched` 的 internal constructor 是可信 Core 模块/adapter 协议边界（模块封装），不是密码学不可伪造能力，不等同于 ConfirmedPlan 实例令牌；结论表述以此为准。
