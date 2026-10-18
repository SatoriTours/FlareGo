> 原协调会话归档的独立验收端勘误后报告。仅本轮增量的功能/交互自动化范围通过；视觉与完整 R2-A 未通过本报告验收。设备前两分轮未单独留存，证据限制见正文。

# R2 写入确认与只读恢复增量 — 独立功能验收报告（本轮范围）

- 验收端：独立功能验收（本会话），未修改任何源码/测试/文档，未提交/合并/push/tag/release，未触碰真实 Cloudflare 网络或付费操作。
- 仓库：/home/jimxl/projects/FlareGo，分支 feature/cloudflare-p3-r2，HEAD=50681de。
- 实际执行模型：provider=pi（通道），model=opencode-go/glm-5.3-flash，thinking=high。（勘误：初稿误写为 Claude，属自我身份臆断。经核实依据为可核实的元数据而非自述：① 本进程环境变量 `PI_PROVIDER=opencode-go`、`PI_MODEL=glm-5.3-flash`、`PI_REASONING_LEVEL=high`、`PI_SESSION_ID=01a14cfc-…`；② Paseo agent 记录 `/home/jimxl/.paseo/agents/home-jimxl-projects-FlareGo/b3c879b6-a59f-44a8-b723-455b57f2aebe.json` 的 runtimeInfo 同值。主控可用 PASEO_AGENT_ID=b3c879b6-a59f-44a8-b723-455b57f2aebe 独立复核该文件。模型本体可信度仍属通道声明，验收端无法自证权重。）
- 无子代理派生。
- 范围声明：本报告只验收「本轮 R2 写入确认与只读恢复增量」，**不是** 完整 R2-A 完成宣告。

## 1. 实际执行的验证（命令与结果）

全部为强制重跑（`--rerun`），不采信 UP-TO-DATE 结果。实际命令、结果与留存路径：

| # | 完整实际命令（cwd=/home/jimxl/projects/FlareGo） | 实际结果 | 留存证据路径 |
| --- | --- | --- | --- |
| 1 | `./gradlew :shared-core:jvmTest --rerun --console=plain` | BUILD SUCCESSFUL；807 tests，0 failed | `shared-core/build/test-results/jvmTest/TEST-*.xml`（目录 mtime 2026-10-18 11:11:40，覆盖本分支全量 commonTest，含全部 R2 与 DNS/Zone 用例） |
| 2 | `./gradlew :shared-ui:jvmTest --rerun --console=plain` | BUILD SUCCESSFUL；13 tests（R2BrowserTest 7 + R2BrowserWriteTest 6），0 failed | `shared-ui/build/test-results/jvmTest/TEST-*.xml`（mtime 11:11:46） |
| 3 | `./gradlew :android-platform:testDebugUnitTest --rerun --console=plain` | BUILD SUCCESSFUL；97 tests（含 R2UploadStagingTest 5），0 failed | `android-platform/build/test-results/testDebugUnitTest/TEST-*.xml`（mtime 11:11:52） |
| 4 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2ManagementUiTest'` | BUILD SUCCESSFUL in 28s；6/6 通过 | 本轮即时读取的 XML 已被第 5/6 轮覆盖（见下） |
| 5 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.DnsFileIntegrationTest'` | BUILD SUCCESSFUL；17/17 通过 | 同上 |
| 6 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2AuthorityOwnerIntegrationTest'` | BUILD SUCCESSFUL；1/1 通过 | `app/build/outputs/androidTest-results/connected/debug/TEST-flarego-r2-integration(AVD) - 16-_app-.xml`（mtime 11:19:15）与 `app/build/reports/androidTests/connected/debug/com.flarego.R2AuthorityOwnerIntegrationTest.html` |

**设备分轮证据说明（重要）**：Gradle 每轮 connected 测试会覆盖同一 XML/HTML 输出，当前磁盘 XML 只含第 6 轮（owner 集成 1 条），不能单独作为第 4/5 轮的留存证据。第 4/5 轮的 6/6 与 17/17 结果（含逐用例名单）是验收端在每轮 BUILD SUCCESSFUL 后**立即读取当时 freshly written XML** 所得，只存在于本会话运行记录中；磁盘无该两轮的独立留存。若主控需要可复核的第 4/5 轮证据，需重跑一次该两类（验收端本轮不重跑，遵不扩展验收的指令）。三组 JVM 结果目录均按模块覆盖留存且经 mtime 核对为本轮产物。AVD 身份核实：`adb -s emulator-5554 emu avd name` → flarego-r2-integration。

本关键用例的设备结果（R2ManagementUiTest）：
- resourceR2TabMountsRealBrowserAndLongSlashKey、typedBucketDeletePreparesButNeverSendsWithoutTheFinalConfirmation、actualVmRequiresPreviewThenExplicitConfirmationAndFreshAccountAuthority、missingWriteGrantDoesNotSendAndReadOnlyRecoveryDoesNotRetry、uploadChooserCancellationPreservesDraftAndDoesNotUpload、switchingAwayWhileFilePickerIsPendingRejectsItsResult — 全部 ok。

关键本轮用例在 shared-core 计数核对（从测试结果 XML 读取）：R2ControllerTest 21（含 6 项新增：preexistingKeyNeverProves…、singleDeleteAbsenceMatchesTheCompleteFrozenTargetHash、callerOwnedBatchListCannotMutate…、actionRequiredAfterPartialBatchStillBlocksNewWrites、createdBucketCanReconcile…、reconcileKeepsAnUploadUnknownEvenWhenTheKeyIsListed）、R2GroupedExecutionTest 1、R2WriteRuntimeTest 4、R2ObjectWriteTest 12、R2WriteAuthorityTest 26。

## 2. 准入边界逐条核对（基于实际代码 + dirty diff + untracked）

1. **actual editor command 独立于 plan** ✅ `R2WriteRuntime`（R2WriteRuntime.kt:26 起）从 `actual()` 回调取 R2LiveSelection/R2Command；`prepared` 只存 ChangePlan；执行时使用 prepare 时冻结的 `p.write`（R2Controller.kt submit/submitObject），调用方提交的 write 对象不能注入新流。
2. **route/draft/credential 变化撤销旧确认** ✅ R2Browser 每个 intent 入口先 `invalidateWrite()`（epoch++、清 preview/command）；路由变化使 `actualR2Context()` 为 null → stale；draft 变化 `invalidateDraft()` 释放暂存内容；凭据变化由 `revision`/`ManagementPlatformOwner.requireCurrent` + `isLive` 双层拒绝。R2WriteRuntimeTest.changedInputOrRouteCannotBorrowAnEarlierConfirmation 钉住。
3. **所有生产 writes 走 AuthorizedR2Session** ✅ 唯一生产 session 工厂在 FlareGoViewModel.r2SessionFor → AuthorizedR2Session（demo 连接显式拒绝）。读委托不授权（AuthorizedR2Session.kt），每个写先 `authority.observe(forceRefresh=true)` + proof 绑定，短客户端 final guard `{ issuer.requireCurrent(proof,…) }` 在产品回调之后、gateway send 之前同步执行。R2WriteAuthorityTest 26 项（含 forged granted observation 不能铸造 proof、bucket-item/read 权限不授予 REST 管理、finalGuardRunsAfterProductGuard…）钉住。
4. **UI 先 prepare 再显式确认** ✅ prepareWrite → runtime.prepare → 预览卡片（rp-confirm/rp-cancel），confirmPreview 单次消费；设备测试 actualVmRequiresPreviewThenExplicitConfirmationAndFreshAccountAuthority 验证了 1 次 DELETE、SUCCEEDED、`/user/tokens/self` 自检路径真实发生。
5. **source 必须来自 Application 私有暂存对象** ✅ `R2UploadContent` 端口只由 platform `R2StagedUpload`（internal 构造，单次 openSource、released/opened 双拒）实现；metadata-only 入口（R2StagedUploadInfo）无法提供流，ConfirmUpload 检查 `uploadContent` 并给出"展示元数据不能提供上传内容"；R2BrowserWriteTest.displayMetadataAloneCannotProvideUploadBytesOrAWritePreview 钉住。
6. **coordinator 锁不因 UI waiter 取消释放；同桶 bucket/object 串行；locator 原值保留** ✅ `executeGrouped`（OperationCoordinator.kt:81-96）+ `resourceLock` key=kind:externalId:scope；对象写与桶写都以 BUCKET_KIND+桶名为 group。R2GroupedExecutionTest 证实：第一个操作持锁期间第二个不发、取消 UI waiter 不释放锁、完成后按序执行、task locator 保持 `my-bucket/a.txt`。
7. **unknown 上传不能以同名 key 存在认定成功** ✅ R2Controller.reconcileObject 对 `r2-put-object` 恒回 StillUnknown（注释明确"presence alone could be an older object"）；reconcileKeepsAnUploadUnknownEvenWhenTheKeyIsListed、preexistingKeyNeverProvesUnknownUploadContentWasApplied 钉住。
8. **单删缺失 hash 含 key** ✅ objectDesired(Delete) 含 bucket/key/deleted；reconcile 侧 matchCandidate 用同构 {"bucket","key","deleted":true}；对象存在性证明靠逐页穷尽该 key 前缀的 listing（≥5 页不完整即抛错，绝不以单页回答"不存在"）。
9. **批量响应比较实际 key 集合** ✅ bulkDeleteResult：success 时要求 confirmedKeys.toSet()==keys.toSet() 且无 errors 且数量一致，重复/不同 key 回声 → invalidResponse；部分失败 → PARTIAL + uncertain；不自动重放。R2ObjectWriteTest.aSameCountEchoOfDifferentOrDuplicateKeys…、aSuccessThatDoesNotEchoEveryKey… 钉住。
10. **UNKNOWN/PARTIAL/ACTION_REQUIRED 阻止同桶新写；恢复只读从不 replay** ✅ UNSETTLED_STATES 含全部七态，anyUnsettledForBucket 同时覆盖桶与对象任务；recoverPending 仅 RUNNING/RECONCILING→UNKNOWN、QUEUED→ACTION_REQUIRED，无任何发送路径；actionRequiredAfterPartialBatchStillBlocksNewWrites、unknownWriteBlocksNewPlansAndReadOnlyReconciliationNeverResends 钉住。
11. **传输关闭 source；UI release 不提前关闭正在读取的 FD** ✅ `R2OwnedSource`（AtomicReference exchange(null)，close 幂等，close 后 read 抛 objectIoFailure）；R2StagedUpload.release 只 unlink 文件，不 revoke 传输持有的 FD（Linux 语义），R2UploadStagingTest.claimedSourceReadsOnlyThePrivateSnapshotOnceAndSurvivesUiRelease 与 DnsFileIntegrationTest 5 MiB 用例双证。
12. **对 coordinator 的扩展不破坏 DNS/Zone 语义** ✅ executeGuarded 默认 resourceGroup=null 保留原 locator 锁；以第 1 轮实际执行的 807 项 shared-core jvmTest 中 DNS/Zone/协调器回归类为证据（从留存 XML 逐类读取，0 失败）：OperationCoordinatorTest 55、OperationTaskTest 21、AppOperationRuntimeTest 9、CloudflareGatewayTest 48、CloudflareDnsManagementTest 46、DnsBatchTest 47、DnsDocumentTest 16、DnsFilesTest 26、DnsScanTest 55、DnsTaskFamilyIntegrationTest 9、DnsValidationTest 4、DnssecTest 40、ZoneManagementTest 41、ZoneSettingsTest 30。（勘误：初稿引用的 R2CoverageTest 不存在，属错误引用，本条已改为上述实际执行类的留存结果。）

## 3. 独立发现

- **[中低 / 设计后果，需后续增量确认] 不确定任务在同桶无应用内解除路径。** 证据：OperationCoordinator.kt runConfirmed（约 156-162 行）写前持久化 RUNNING，任何未识别失败返回 running 行；权限缺失（pre-send PERMISSION_REQUIRED）场景设备测试 pin 在 RUNNING，reconcile（对象仍在/授权仍缺）→ UNKNOWN。UNKNOWN/ACTION_REQUIRED 属 UNSETTLED_STATES，`requireNoUnsettled` 永久阻止该桶后续全部写入；应用内无 per-task 取消/结清入口，只能断开重连连接（removeConnection 清行）。这与计划裁决"没有可证明的读证据时保留门禁"一致，且测试已显式 pin 该行为，故不判本轮不通过；但"门禁如何在授权补足后由用户主动结清"是遗留缺口，建议下轮计划明确（例如：基于可信 pre-send 拒绝证据的 FAILED 结清，或显式的人工确认结清流程）。
  - 可重现：非 granted token 上 previewObjectDelete → rp-confirm → 任务 RUNNING；reconcileTask → UNKNOWN；此后同桶任何 prepare 均报"该桶的上一操作尚未核对"。
- **[低] R2WriteRuntime.prepared 为无同步的 mutableMapOf。** prepare/confirm 目前经 viewModelScope 同一主线程访问（confirm 用 UNDISPATCHED 内联起步），未观察到实际并发；但这是 shared-core 通用类，依赖调用方调度器巧合，建议加调度约束或并发容器。文件：R2WriteRuntime.kt:35。
- **[低] prepared/preparedObjects 表在提交或取消后不清理**（memory-only，随预览次数线性增长，量级极小）。文件：R2Controller.kt entry/objectEntry。
- **[信息] 批量删除 success=false 且 errors 为空**（failed==0）映射 Unknown 而非 PARTIAL——保守且符合"未知保持未知"裁决。
- **[信息] prepare 中对桶的 getBucket 属只读预检**，符合"预览不发写"约束（设备测试断言 requests 全 GET）。

未发现违反准入边界的正确性缺陷。

## 4. UI 交互与视觉检查（分开陈述）

- **交互（自动化语义层）**：设备端 Compose 语义测试 6 项覆盖了 tab 挂载、typed 删除两阶段、preview 后显式确认、权限缺失不发、picker 取消/切换拒绝。属于交互自动化证据。
- **视觉检查：未验证。** 本验收端无截图采集与图像理解执行，未做任何真实设备截图/布局/颜色/间距/换行的人工或模型视觉核对。`r2-read-only-notice`、`r2-staged-file`、`r2-write-result`、`r2-tasks` 等仅以语义树存在性验证。不得将上述自动化结果解读为视觉验收通过。

## 5. 本轮范围结论：**通过**

本轮增量（写入两阶段确认、AuthorizedR2Session 账号级 proof 门禁、平台私有暂存 source、executeGrouped 同桶串行锁、UNKNOWN/PARTIAL/ACTION_REQUIRED 只读恢复不重放、批量 key 集合比对、单删含 key hash、单删 typedKey 确认）的代码与测试证据一致，全部有界测试实际执行且 0 失败，未发现边界违反。结论为**通过**（限本轮增量范围）。

## 6. 未验证项（明确列出，不计入本轮通过）

1. **系统 MainActivity SAF 全轮转**：真实 Activity 重建（旋转/进程回收）期间 documentRequests 流、pending grant 与 ActivityResult 交付的端到端行为未在真实旋转中验证（测试以直接调用 onR2DocumentResult/切换页面模拟，非真实旋转）。
2. **最终十操作 coverage 矩阵 / 全分支 review**：R2-A 全部 10 个 operationId 的覆盖台账核销与本轮 diff 之外的全分支审查未执行（P3 51 项中 41 项仍属 R2-B…E）。
3. **真实云验收**：所有 R2 写入/授权路径止于 synthetic transport 与 mock 契约；真实 Cloudflare REST 写入、真实 Token 策略判定、真实桶/对象数据、S3 临时凭据（R2-E）均未验证，保持 UNVERIFIED。
4. **视觉验收**：见 §4，无截图/图像理解，未执行。
5. 疑点 §3 第一条的"门禁结清"产品路径：本轮裁决允许保留门禁，但结清 UX 属未验证/未设计项。

## 6b. 勘误记录（2026-10-18，仅澄清证据，未重跑测试、未改仓库、未改验收范围）

1. **模型身份**：初稿“实际执行模型：Claude”为验收端自我臆断误写。已改为按可核实元数据如实记录：PI_* 环境变量与 Paseo agent JSON 双重印证 provider=pi / model=opencode-go/glm-5.3-flash / thinking=high。验收端无法自证模型权重本体，主控可凭 PASEO_AGENT_ID 独立复核。
2. **R2CoverageTest 引用删除**：该文件不存在，原引用错误。已改为留存 XML 中实际执行的 14 个 DNS/Zone/协调器回归类（合计 447 项，0 失败）作为 §2.12 证据。
3. **设备分轮证据诚实化**：原表将三轮设备结果并列于同一路径下，易被误读为均可由当前磁盘 XML 复核。已在 §1 表中逐轮列出完整命令，并明确：当前磁盘 XML 只含最后一轮；第 4/5 轮（R2ManagementUiTest 6/6、DnsFileIntegrationTest 17/17）是每轮结束后即时读取当时 XML 的运行记录观察，磁盘已被后续轮覆盖，无独立留存；如需可复核证据需重跑。

**通过结论影响评估**：
- 勘误 1 只影响报告身份行，不影响任何测试/代码证据，通过结论不受影响。
- 勘误 2 是引用错误而非证据缺失：§2.12 的支撑原本就是第 1 轮 807 项全量结果，替换为逐类实际证据后结论不变。
- 勘误 3 使设备证据的表述更准确：第 4/5 轮从“可由磁盘 XML 复核”降级为“会话运行记录观察”，属于证据可复核性降级而非结果改变。综合评估：**本轮通过结论不受两处错误影响，维持不变**；设备轮次证据的独立可复核性缺口已如实标注。

## 7. 边界遵守声明

- 全程只读源码与文档；仅写 /tmp 本报告与 build 测试产物；未启动/停止模拟器（仅查询既存 emulator-5554）；未触碰认证、模型通道、workspace/worktree/git/tag/release；未发起任何真实 Cloudflare 网络请求（本地合成 transport + 本地 emulator）。
