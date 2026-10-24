# R2 PUT/PATCH 桶编辑 + 覆盖门禁 — 独立功能验收报告（本轮范围）

- 验收端：独立功能验收（同一会话续任，未派生）。实际执行模型：pi/opencode-go/glm-5.3-flash、thinking=high（配置 high，平台上次返回 effective high）——依据本进程环境变量 `PI_PROVIDER/PI_MODEL/PI_REASONING_LEVEL` 与 Paseo agent 记录 `~/.paseo/agents/home-jimxl-projects-FlareGo/b3c879b6-….json`，未自称其他模型；权重本体不可自证。
- 仓库：/home/jimxl/projects/FlareGo，branch=feature/cloudflare-p3-r2，HEAD=3c7284b；全程只读源码/测试/文档，未清理/提交/push/Tag/Release，未改模型/认证/权限，未派生，未创建/归档空间；未触碰真实 Cloudflare/凭据/网络写。
- 计划：docs/superpowers/plans/2026-10-18-r2-bucket-editor-progress.md（已读，范围一致）；既有 spec：2026-10-17-p3-r2-management.md。

## 1. 实际执行的有界验证（每轮立即留存）

设备核实（只查询）：emulator-5554 device；AVD flarego-r2-integration；API36/320x640/font1（font_scale=1.0 实测）。未启停、未改设置。

| # | 完整实际命令（cwd=FlareGo） | 结果 | 留存 |
| --- | --- | --- | --- |
| 1 | `./gradlew :shared-core:jvmTest --rerun :shared-ui:jvmTest --rerun :android-platform:testDebugUnitTest --rerun --console=plain` | BUILD SUCCESSFUL；**core 824 / ui 18 / platform 97，全 0 failure/error/skip**（XML 实读统计） | /tmp/flarego-r2-bucket-editor-independent-evidence/jvm/jvm-rerun-stdout.log |
| 2 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2ManagementUiTest,com.flarego.R2BucketEditorUiTest'` | BUILD SUCCESSFUL in 1m7s；**19/19，0 失败**（XML 实读：R2ManagementUiTest 14 + R2BucketEditorUiTest 5） | /tmp/flarego-r2-bucket-editor-independent-evidence/native-r1/（native-r1-stdout.log + TEST-…xml） |

主控证据复核（实读非照抄）：postcondition-final 78/78（XML 分类：R2BucketEditorUiTest 5、R2ManagementUiTest 14、R2DocumentPicker 8、R2AuthorityOwner 1、ManagementPlatformOwner 15、DnsFile 17、ProductLoading 12、DnsDocumentPicker 5、ExtendedManagementApp 1——确为指定组合，非全量设备测试）；lint 报告实测 `0 errors, 10 warnings`（app）/`0 errors, 8 warnings`（android-platform）；audit 日志 349 ok 行、CI 10 ok 行；UI 角色返修历史 XML 实读核对：bucket-ui round1 3/3 失败、followup-round1 5 项 2 失败（rowEditEntryDisabledWhenRefreshing、editFormCanSubmitDisabledWhenOriginalClassInvalidOrRefreshing）、followup-round2 5/5——与主控叙述一致，失败未抹除。

## 2. 逐项范围证据

1. **PUT 建桶/PATCH 编辑界面与真实 VM 线路** ✅ R2UiModels 新增 R2BucketCreateMode/5 个 intent/4 个 state 字段+storageClasses；R2Browser 处理 CreateModeChanged（PUT 清 draftLocationHint、POST 位置事件仅 POST 生效）、RequestStorageClassEdit（未知/缺失→只读错误提示，非 UI-only）；R2BucketList 编辑卡（rb-edit-card/target/original/class-*/submit/cancel）与创建模式 chips。真实 VM 线路由 R2ManagementUiTest（14 项，synthetic transport）与纯 UI 5 项覆盖。
2. **未知/缺失原类型只读** ✅ 双层拒绝：controller `validateTarget` 对权威 GET 原值非 R2_STORAGE_CLASSES 抛 UNSUPPORTED_CAPABILITY（不能只靠 UI 禁用，符合 Ruling）；UI 行按钮显示"存储类型只读"且 disabled；unknown-class.png 实证 FutureArchive 原样展示+只读。
3. **PUT 清除 Location Hint、零 body+header** ✅ 实测断言：`HttpBody.Empty` payload、`cf-r2-storage-class` 头、单次非 GET、确认后才发（actualVmPutBucketCreateUsesTheReviewedHeaderAndEmptyBodyOnlyAfterConfirmation）；UI 层 putModeClearsLocationHintAndSendsOnlyTheReviewedHeaderAfterConfirmation；put-mode.png/put-preview.png 实证 hint 被清且预览声明"此模式不发送位置参数或请求体"。
4. **显式二次确认** ✅ 沿用 prepare→preview→confirm 两阶段（既有 rp-confirm 机制），cancelOrChangingCreateModeRevokesThePatchPreview 钉住模式切换撤销；PUT/PATCH 均先 preview 再显式确认。
5. **独立账户写权限/状态漂移拒绝** ✅ missingAccountWriteGrantCannotPut/PatchAfterSuccessfulPreviewReads：preview 读全成功后 confirm → 全请求仍 GET + `/user/tokens/self` 自检发生 + 任务 RUNNING；unknownAuthoritativeClassAfterPreviewRejectsPatchDespiteGrantedWriteAuthority：preview 后把权威类型改为 FutureArchive → 零变更 + RUNNING；providerClassDriftAfterPatchPreviewCannotSendAChangedSnapshot（UI 层）同证。
6. **bucket completed 回执必须写后回读才 SUCCEEDED** ✅ R2Controller.verifyBucketCompletion（新增，diff +60 行）：在 coordinator 桶锁内、api.write 返回 Completed 后执行——删除必须缺席；POST/PUT/PATCH 显式类型必须在权威读中存在且相等（desired 含 storageClass 时不接受供应商默认）；显式类型匹配后候选哈希须等于 desiredHash；读失败/缺失/矛盾一律转 ExecutionReceipt.Unknown，**不重发、不把不可结论回读改标为干净失败、不释放门禁**（UNKNOWN 属 UNSETTLED_STATES）。12 项新 @Test 覆盖（=计划"核心4项"+"后置8项(7 RED+1 补证)"，计数核对一致）：aDeleteReceiptCannotClaimSuccessWhileTheBucketIsStillPresent、postAndPutReceiptsCannotClaimADifferentDefaultClassWasApplied、aPatchReceiptCannotClaimAnUnchangedUnknownOrMissingDefaultClassWasApplied、postReceiptReadFailureStaysUnknownAndNeverResendsTheMutation、acceptedAndUnknownReceiptsDoNotPretendToHaveACompletedPostcondition 等；设备端 actualVmPutReceiptCannotClaimAnUnchangedDefaultClassWasApplied 实证 1 次 PUT + 后随 GET + UNKNOWN。
7. **Task7 exact 10/51 与 79 活注册门禁** ✅ R2CoverageTest（新文件 167 行、4 项）独立实跑通过：
   - 正例：manifest（实际 18 日期文件，as_of=2026-10-18）schema_commit/sha 锚定 pinned schema；51 候选 = 10 TYPED_HANDLER + 40 NOT_STARTED + 1 DEFERRED_PENDING_APPROVAL（与 review.json R2/P3 候选 key 集合精确相等）；每个 typed 行含真实编译 handler（`Class.forName(...).methods`）与编译测试（declaredMethods）、registry policy 快照逐字段比对；`mappedCommonBehaviorMethodsActuallyRunWithFreshFixtures` 实际调用 ≥9 个行为测试方法。
   - **负例确实拒绝而非只查文本**：缺失行、空 tests、missing handler、"BLOCKED"改写、NOT_STARTED/DEFERRED 膨胀为 TYPED_HANDLER、移除注册、rogue 未审核注册——7 个 assertFailsWith 全为行为级断言且在实跑中执行。
   - 注册 79 = 历史 69（p1 ops + p2 ops + p2 auxiliary）+ 审核的 10；我用反射在编译产物上实测 **total=79, READ=42, WRITE=37**（/tmp/CountRegistry.java），与主控数字一致；`assertEquals(baseline + expected.keys, registry.keys)` 保证非 subset、无多余注册。
   - review.json 仅 52 处新增 runtime 观察（grep 实测 +52/-0，无 source_support/approval 历史改动），manifest 内 authenticated_cloud_accepted/paid_eligibility_verified/release_qualified 全 false。

## 3. 独立发现

- **【低/口径】计划"最新开发者验证"列 UI18，主控任务描述亦称 18；实测一致（18=UI13+5 新 WriteTest）**——无问题。
- **【信息】R2BucketManagementTest 新增 1 项（aBucketDeleteSendsOneOwnedEmptyBodyRequestAndAcceptsAProviderReceipt）** 为直接绿补证，与主控说法一致。
- **【低/残余风险】verifyBucketCompletion 的写后读失败会保持 UNKNOWN 门禁，而应用内无 UNKNOWN 人工结算路径**（前轮已知缺口，本轮 limitations 明确列入"Unknown-result manual settlement…remain pending"）；PUT 模式对已存在同名桶会走 CONFLICT→预置拒绝（requireAbsent），门禁语义不变。
- **【信息】PATCH 预览显示冻结前后两类型（patchPreviewShowsBothFrozenClassesAndLeavesExistingObjectsUnchanged），patch-preview.png 实证"Standard → InfrequentAccess"**。
- 未发现违反本轮准入边界的缺陷；未观察到 PUT/PATCH 绕过 AuthorizedR2Session 的路径。

## 4. 视觉检查（独立、已实际读图）

具备图像读取能力，已实际查看 5 张截图（/tmp/flarego-r2-bucket-editor-evidence/screenshots-postcondition/，均 320x640 PNG、font1 观感、synthetic 数据）：
1. **put-mode.png**：PUT chip 选中，位置提示区被 PUT 提示文本替换（"PUT 模式不支持位置提示…"），名称 put-bucket，存储类型 chips 可见；
2. **put-preview.png**：目标：put-bucket 位于预览卡上方；预览正文"按名称创建 R2 桶（PUT）；新上传对象默认类型：InfrequentAccess…不覆盖已存在的同名桶"；取消/确认执行均可见；
3. **patch-editor.png**：原存储类型：Standard、新类型 chips、取消/保存修改；
4. **patch-preview.png**："修改新上传对象的默认存储类型：Standard → InfrequentAccess。已有对象的存储等级不变"；
5. **unknown-class.png**：my-bucket 类型 FutureArchive 原样展示、"存储类型只读"禁用按钮、"进入存储桶"可用。
均只含合成数据（R2 Test fixture / my-bucket / put-bucket / FutureArchive），无账号 ID/Token。
**未覆盖**：非 320x640 分辨率、其他字号/深色模式、长桶名/key 截断、无障碍对比度、系统窗口级（多窗口/分屏）窗体表现——未做视觉检查；语义 assertIsDisplayed 属交互证据，不等同视觉。

## 5. 本轮范围结论：**通过**

本轮范围（PUT/PATCH 编辑界面与真实 VM 线路、未知/缺失原类型只读双层拒绝、PUT 零 body+header 与 hint 隔离、显式二次确认、独立写权限/状态漂移拒绝、completed 回执写后回读后置验证、Task7 exact 10/51 与 79 活注册门禁及负例拒绝）全部有证据支撑：独立 --rerun core 824/UI 18/platform 97 全过，设备 19/19，门禁负例为行为级断言，注册计数反射实测吻合，5 图视觉检查完成。

## 6. 未验证项（明确列出，不计入本轮通过）

1. **真实云/付费/临时凭据**：全部 synthetic transport + 本地往返，UNVERIFIED（manifest 各布尔门禁均 false，如实）。
2. **全分支总验收/完整 R2-A 完成**：不宣告。UNKNOWN 人工安全结算、有界 prepared 回收、B/C/D 批次、R2-E 审批仍未完成。
3. **API26-28 与进程杀恢复**：无新证据。
4. **更广视觉配置**：见 §4。
5. **主控 78/78 中其余类**（ManagementPlatformOwner/ProductLoading/DnsFile/DnsDocumentPicker/R2DocumentPicker/R2AuthorityOwner/ExtendedManagementApp）：本轮未独立重跑，仅实读其 postcondition-final XML 计数与构成；前几轮（Task6b.4/6b.5/清理屏障）已分别由本验收端独立验证过，未重复。
6. UI 角色 Agent（3decc16e…，gemini-3.8-flash-high，effective off）历史运行：其代码成果经本轮全量回归+设备测试覆盖，但对 UI 角色"实际执行质量"不做元数据外评价。

## 7. 边界遵守声明

- 全程只读仓库；写仅 /tmp 报告、/tmp/CountRegistry.java（仓库外）与 build 测试产物；未修改实现/测试/文档；未派生子 Agent；未改模型/设置；设备 1 轮设备测试 + 1 轮 JVM 重跑，无失败轮，无重跑转绿；未发起真实 Cloudflare 请求。

## 原会话归档核对附记（不改写独立端原文）

- 负例实际为 **8** 个 `assertFailsWith<AssertionError>`，§2 的“7个”为计数笔误；该方法已执行通过，不影响判定。
- 独立端 `jvm/` 实际只即时保存 stdout，未在该目录保存 XML。主控于结束通知后、未再运行 Gradle，从当前 build 测试结果补存 core38／UI2／platform7 个 XML，再实读核对824／18／97全部0失败／错误／跳过。归档在 `evidence/r2-bucket-editor-2026-10-18/independent-jvm/`。此为事后补存，不宣称即时XML留存。原生19项的 stdout/XML 当轮已留存并归档于 `independent-native/`。
- UI作者旧 round2 仅找到 `test.log`，没有该轮 XML；日志归档于 `regressions/ui-initial-round2.log`，不虚称该轮 XML 已保存。followup RED XML（5项2失败）及后置条件 RED XML（33项7失败）均已归档。
- 主控已核对真实diff、独立命令stdout、上述XML计数及5图。本轮有限范围通过；全R2-A／全分支／真实云／付费验收仍不通过此报告自动扩张。
