# R2-A 本地工程出口 全分支最终审查（独立只读）

- 审查角色：功能验收（独立 fresh context，只读源码/测试/文档；不派生、不操作 Git/设备、不改实现）
- 日期：2026-10-18 22:10–22:40 CST
- 基线：branch feature/cloudflare-p3-r2，HEAD 3c7284b5；merge-base HEAD main **实测 6823394f0668bf2aae19ad2bc842cbec3a554342**（非假设值）
- 全分支源码差异：`git diff 6823394 -- shared-core/src shared-ui/src app/src android-platform/src` = **53 tracked 文件，+7707/−44**；另读 untracked `R2CoverageTest.kt`、`R2BucketEditorUiTest.kt`。本轮增量（retention/NotDispatched/取消）已在上一轮独立验收并复跑，不重复全文。

# 总结论：R2-A 本地工程出口 —— 通过

真实云/付费资格、发布资格：**未验证，declined-to-judge**（见末节），不宣告完整 R2-A 云能力或发布资格。规格头部"待用户评审"为旧元数据，原会话已有 A→B→C→D 批次顺序批准，本审查不据此误判需求未批准，也不自动批准 R2-E（临时凭签发仍 DEFERRED_PENDING_APPROVAL）。

## 一、逐 Task 证据覆盖（Task0–7）

| Task | 范围 | 本审查核实方式 | 结论 |
|---|---|---|---|
| 0 尾占位符路径匹配 | `ActionPolicies` trailing flag、`R2PathMatchingTest`（%2F 拒、%252F 收、单次解码） | 计划 Follow-up 两条保守边界已用测试钉住（见 §五） | 通过 |
| 1 领域模型与校验 | `R2Models.kt`、`R2Validation.kt`：桶名 3–64/pattern/IPv4/xn--/-s3alias；key ≤1024B、C0/C1/`\`/`.`/`..`/前导`/`拒绝；枚举白名单 | 实读源码；835 单测含 R2ValidationTest | 通过 |
| 2 手写策略注册 | `R2Policies.kt`：10 条（3 DELETE=DESTRUCTIVE，其余 CHANGE/READ），R2-E 无注册 | R2CoverageTest 反例：40 NOT_STARTED/1 DEFERRED 无策略、额外注册被拒、精确 79 | 通过 |
| 3 桶控制面端口/客户端 | `CloudflareR2Client.perform`：list 在 `result.buckets`+cursor；PUT/PATCH 无体+`cf-r2-storage-class` 头；delete 回执≠缺席证明 | 实读；R2BucketManagementTest 锚点经 coverage gate 实际调用 | 通过 |
| 4 对象列表/流式下载 | `downloadObject`：独立 getBucket 归属证明→单次流式 send→policy match 本地强制→非 200 拒写 sink；`CloudflareTransfers` 固定 buffer 流式、错误响应不落 sink、OwnedResources 独立清理（NonCancellable） | 实读 CloudflareTransfers.kt 全文；R2ObjectTest | 通过 |
| 5 上传/单删/批量删 | `performObject`：shape 先校验并 policy-match 后才开流；R2_MAX_BULK_DELETE=1000、重复 key 拒绝；单删回显 key 比对；bulk 结果 success 需全量回显一致，partial→Partial，失败→Unknown(uncertain)，只回读不重发 | 实读；R2ObjectWriteTest 在 835 集内 | 通过 |
| 5b/5c 控制器 | `R2Controller`：桶/对象同桶锁（`executeGrouped` + `ResourceRef(BUCKET_KIND,bucket)` 共组；`anyUnsettledForBucket` 跨桶/对象阻塞）、目标/凭据/路由冻结（freezeR2*Write + requireBound/Object + requireSameLive + isExpired）、取消/重建不重放（uncertain→Unknown，无重发路径）、写后核对（删桶后回读缺席；class 比对；失败/矛盾/缺失→UNKNOWN） | 实读 R2Controller.kt 全文+diff；R2GroupedExecutionTest（同桶串行+取消不释放） | 通过 |
| 6 Compose 页面 | R2Browser/R2BucketList/R2ObjectList/R2ChangePreviewCard：类型化删除确认（桶名/object key 逐字符匹配、批量确认卡含数量+非原子警告）、预览卡展示目标/原值、取消/模式变化撤销预览 | 实读 UI 源码；设备 75 中 14+5 项 UI 测试本轮独立复跑 | 通过 |
| 7 覆盖门禁与台账 | `R2CoverageTest`（untracked，已读全文）：51=10+40+1、注册精确 79、schema sha 全量校验、编译 handler/test 反射检查、负例（缺行/状态膨胀/rogue 注册/临时凭据偷跑）均拒绝、fresh fixture 实际运行映射行为、source_sha256 漂移检测 | 本轮 835 单测内通过；manifest 24 文件 sha 本轮重算 0 mismatch | 通过 |

## 二、关键决策 D1–D11 核对

- **D1 流式**：上传走 `R2UploadStaging`（私有副本 8192 buffer 同遍 SHA-256、单次 source、取消 cleanup、>5GiB 拒绝）、下载 `CloudflareTransfers` 固定 buffer；core 无对象内容入内存（`R2UploadContent` 契约绑定 digest/length/单次 source）✓
- **D2 key 原样**：`encodeR2ObjectKeyPath` 斜杠字面、其余保留字符百分号编码；读取保留原样 key ✓
- **D3 桶名**：官方 pattern + 附加拒绝 ✓
- **D4 枚举**：写白名单、读保留（`decodeBucket` raw 原样；未知 storage_class 只读，`UNSUPPORTED_CAPABILITY`）✓
- **D5 风险分级/确认/冻结/写后回读**：A 范围内 3 个 DESTRUCTIVE 逐操作满足——桶删除 typedName 精确匹配+冻结+删后回读缺席；对象删除 typedKey 精确匹配；批量删除确认卡（数量+非原子警告+已选列表可见）。桶锁配置/r2.dev/自定义域在 R2-B NOT_STARTED，不适用本批 ✓（非只查桶删除）
- **D6 批量 ≤1000**：controller+client 双层校验、重复 key 拒绝、prefix 参数未暴露 ✓；"逐 key 展示"以已选行列表+计数+警告实现，判断满足意图
- **D7 临时凭据**：无注册、无入口、门禁 DEFERRED_PENDING_APPROVAL 不计完成数 ✓
- **D8 raw 保留**：文档模型均带 raw；PATCH 对未知/缺失 storage_class 保守只读（规格 ruling D4 一致）✓
- **D9 SAF 复用**：`DnsDocumentFiles` Export 事务——临时 sink 字节上限、commit 前 stillEmpty 外部填充探测、本地/目的地指纹比对、abort 幂等独立清理、不覆盖用户源文件 ✓
- **D10 真实云 0**：无真实云写入路径；manifest 三 flag false；权限/凭据/云 Token 无日志（r2 core 路径 grep 0 处）✓
- **D11 头白名单**：`ALLOWED_BUSINESS_HEADERS` 仅新增 `cf-r2-storage-class` ✓

## 三、Review Focus 1–5 与发送边界

1. key 编码：合法原样到达（含斜杠/空格/+/非 ASCII 编码发送）、非法 0 发送（校验在 send 前）✓
2. 流式边界：>4MiB 不走内存 bounded 路径（transfers 无 in-memory bound，bound 在 CloudflareTransport）；取消后 sink/source 关闭（OwnedResources+R2OwnedSource+staging hook）✓
3. 批量：1001 拒 0 发送；缺失/矛盾→UNKNOWN 只回读不重发 ✓
4. 桶删除前置条件：provider 失败映射可解释错误，不重试不误报；成功回执仍需回读缺席 ✓
5. 公共访问/自定义域：R2-B 范围，未实现即无入口 ✓

**账户级新鲜证明与 final guard**（AuthorizedR2Session）：每次 mutation 强制 fresh observe（token kind 判别、ambiguous 拒绝、catalog 变更/主体变更撤销、账号 GET 归属绑定、TTL≤30s、时钟回拨防护）→ `proofFor`/`requireCurrent` 作为 final guard 在 product 回调后、gateway send 前；guard 后异常保守不推断。`executeWithDispatchEvidence` 仅在 guard 完成前异常时以内部 NotDispatched 结清（本增量已验收）。

## 四、已核实 vs 仅继承的证据

**本审查/本验收链实际重跑（当前快照有效）**：
- 单测三模块 --rerun：core 835 / ui 19 / platform 97，全绿（含 R2CoverageTest 门禁与 7+4+1 新用例）
- 设备 75/75 独立复跑（R2Management 14、BucketEditor 5、Picker 8、AuthorityOwner 1、DnsScan 41、DnsBatch 6），XML 独立读取
- 拒绝截图实读；manifest 24 sha 重算；注册 79 由 coverage gate 在 835 内断言

**仅继承（未在最终快照重跑，判低风险并说明）**：
- DnsFileIntegrationTest(17)、ManagementPlatformOwner(15)、ProductLoading(12)、DNS picker(5)、DNS 取消重绑(1)、ExtendedManagementApp 部分用例：最后完整设备轮为前段 78/78 轮（先于最终快照）。其后增量仅 DnsScanPanel 1 行 sealed 分支（DnsScanUiTest 41 项在我 75 内覆盖）与 retention/controller/browser（我 75 内 R2 项覆盖），未触碰 SAF/picker/owner 路径；故不构成阻塞，但最终快照上这些 owner 测试未重跑，如实记录。
- `:app:lintDebug`/`assembleDebug`：实现者自测通过；我的 connected 运行隐式重建 Debug APK，lint 未独立重跑。
- authority/write-loop/picker 各独立评审报告：引用其结论，未重放其 RED/Green 历史。
- 前 78/84 与当前 75 分属不同轮/范围，不相加为一次全量。

## 五、已知边界裁决

1. **literal % + 两 hex 误拒、encoded # 拒绝**（共享校验器）：安全侧失败（拒绝而非放行），已用 `documentsTheSharedValidatorLimit*` 测试钉住并列入计划 Follow-up。裁决：**可接受出口**；不得为能力补全放松通用重定向/注入防护，留待另开评审。
2. **第三方 provider 删除观测有限**：桶删除已有回读缺席；对象删除回显 key+列表穷举回读；批量部分失败→Partial/ActionRequired。裁决：不保证每个 provider 删除成功的观测量化如实保留，无误报成功路径，**满足 A**。
3. **真正 UNKNOWN/旧 RUNNING 无持久未分派证明**：不按错误码/超时迁移、无人工勾选成功入口（taskNotice 仅展示；reconcile 只读）。规格原要求"只读核对/未知不重放"完整满足；未发现新增手工解除功能。**无 A 必须项缺口**。
4. 通用稳定资源锁表与持久任务历史不在 prepared 有界回收承诺内——与本批承诺一致，不算缺口。

## 六、发现（非阻塞，交原开发者主控酌情处理）

1. 【文档·低】计划文档 Task1–7 复选框仍为 `- [ ]`，与进度/验证文档"已完成"矛盾；Task0 亦混用。建议完成后统一勾选或注明引用。
2. 【代码·极低】`CloudflareR2Client.performObject` 中 `HttpBody.Text("", type)` 为借构造副作用校验 media type 的死语句（值被丢弃）。功能正确（构造器校验），可读性欠佳。
3. 【文档·极低】spec 头部"状态：待用户评审"为陈旧元数据（主控已确认批次批准存在），建议后续更新避免误导。

均无安全或正确性影响，不构成不通过。

## 七、外部资格未验证 / declined-to-judge

- 真实 Cloudflare API 行为、付费能力、临时凭据：**未验证**（D10 明确本批不做；不以真实云缺证据作为本地出口的假失败，也不宣告真实环境资格通过）。
- API 26–35 多设备矩阵：未做，设备证据仅自有 AVD API36。
- R2-B(20)/C(8)/D(12)：NOT_STARTED，未审；R2-E(1)：待批，未审。
- 发布资格/全 R2-A 完整云验收：**declined-to-judge**——本地工程证据不外推至线上行为。

## 证据索引
- 本审查验证日志：/tmp/flarego-r2-a-final-review-evidence/verification.log
- 独立重跑证据：/tmp/flarego-r2-retention-independent-evidence/{unit-round,native-round}/（含 stdout+全部分模块 JUnit XML+拒绝截图）
- 上轮增量报告（含三项勘误附录）：/tmp/flarego-r2-retention-independent-review.md
- 分阶段文档：docs/cloudflare-audit/r2-{app-integration,authority-verification,write-loop-verification,picker-verification,batch-a-verification,bucket-editor-independent-review,retention-verification,picker-cleanup-independent-review}-2026-10-18.md

---

# 证据复核附录（第二轮，主控挑战两点；不覆写原报告，2026-10-18 23:00 CST）

主控两处挑战均**属实**。本节修正原报告相应表述；上轮 retention 835/19/97+75 独立验收仍有效，受影响的是 A 全出口结论与 coverage 触发可靠性。

## 复核 1：D5 批量对象删除"写后回读"缺口 —— 成立，A 整体结论修正为**不通过（仅此一项规格偏差）**

**事实（精确调用链与行号）：**
- 正常提交路径 `R2Controller.submitObject`（R2Controller.kt:316–338）：`api.writeObject(p.write)` 返回后**直接经 executeWithDispatchEvidence 结算**，无任何写后读。发送前那次 `api.getBucket`（L326）是归属证明，在 send 之前。
- `CloudflareR2Client.bulkDeleteResult`（CloudflareR2Client.kt:318–345）：`success=true` 且**全量回显 keys 与请求一致且 errors 为空** → `ExecutionReceipt.Completed()` → settle 为 SUCCEEDED。响应是 mutation 自身的 provider 回执，**不是独立读**。
- 唯一的批量缺席核对在 `R2Controller.reconcileObject` L364：`"r2-delete-objects" -> Reconciliation.ActionRequired`——是**手动触发的后续只读操作且明确不做自动回读**（注释：逐 key 回读不可廉价完成）。
- 套件自身证据：`R2ObjectWriteTest.aBulkDeleteSendsOneJsonKeyArrayAndReportsACompleteSuccess` L183 断言 `requests.last()` 即 DELETE——**证明正常成功流程之后没有任何读请求**（该测试在本轮 835 集内通过）。

**单删/上传的区分（避免混淆）：**
- 单删（`r2-delete-object`）：正常路径仅验证 provider 回显 key 一致（CloudflareR2Client.kt:301–307）+ 产品 typedKey 确认；自动化缺席证明（`objectPresent` 列表穷举，R2Controller.kt:615–619）只在手动 `reconcileObject` 时发生。规格 D5 回读清单为"桶删除、批量对象删除、桶锁定配置删改、r2.dev 公共访问启停、自定义域删除"——**单删不在清单内**，故回执验证不构成 D5 违规；但原报告未区分"正常回执验证"与"手动 reconcile"，在此澄清。
- 上传（`r2-put-object`）：同样仅回执回显，reconcile 恒 StillUnknown（注释：presence 可能是旧对象）。不在 D5 清单，不算缺口。
- 桶删除：`verifyBucketCompletion`（R2Controller.kt:196–215）在**正常路径**做写后回读缺席证明 ✓。原报告此项表述正确。

**裁决：**
- D5 缺口：**成立**。级别：**中**——正确性观测缺口（provider 回执与实际状态分歧时任务显示 SUCCEEDED 而无独立核对），非授权/重放/安全缺口；部分失败与矛盾响应已保守 Partial/UNKNOWN，回显一致性检查严格。已批准计划 Task5 的设计（L411–417）即以回执+异常时回读为完成语义，与规格 D5 原文存在未被明示的偏差——属规格与实现不符，不能由我以"回显=回读"放行。
- **A 本地工程出口整体结论由"通过"修正为：不通过（1 项）**。收敛路径二选一，由主控/用户裁决：
  1. 补实现：bulk 正常路径增加写后读（如逐 key/抽样/前缀列表核对或单次 list-objects 交叉核对），保持 UNKNOWN 不重放语义；或
  2. 规格修订：用户批准将批量删除的"写后回读"改为"provider 逐 key 回执 + 矛盾/缺失保守 UNKNOWN + 手动 reconcile 引导"，并把该偏差写入规格/台账后重审该项。
  修复或修订前，本端不维持 A 通过结论。

## 复核 2：覆盖门禁外部文件非 Gradle Test 输入 —— 成立，级别：中低（门禁可靠性）

**事实：**
- `shared-core/build.gradle.kts` 全文无 `inputs` 声明；根/`settings.gradle.kts` 无相关处理；R2CoverageTest 运行时读取：manifest `docs/cloudflare-audit/r2-batch-a-runtime-support-2026-10-18.json`、`review.json`、P1/P2 台账、`SCHEMA_FILE`（默认 `.superpowers/cloudflare/openapi.json`）、`source_sha256` 24 文件——其中 **7 个在 shared-core 之外**（app 3、shared-ui 5 中 4 个 commonMain、android-platform 1；明细 /tmp/flarego-r2-a-final-review-evidence/recheck-round2.log），均不在 shared-core test 源集/输入集。
- 主控实测 `/tmp/flarego-r2-retention-notice-final.log` L15：manifest/native 源变更后 `:shared-core:jvmTest UP-TO-DATE`——Gradle 对 Test 任务的 up-to-date 检查只覆盖其声明的输入（源集+classpath），**不含上述外部文件**。
- 同模式先例：P2CoverageTest（P2 既有），R2 门禁沿用并扩大读取面。

**影响与缓解：**
- 失效模式：仅 manifest/schema/UI/native 源变化时，推荐命令（spec §7 无 --rerun）会复用旧测试结果，门禁对"台账漂移/源码 hash 漂移"静默失察。我的独立验收用 `--rerun` 实跑 835，该快照证据有效；但**正常命令的触发可靠性缺口成立**。
- 边界：R2Policies 手写、运行时授权不加载台账——该门禁是证据门禁而非请求授权，故失效不直接产生越权发送；"CI 全新 checkout 兜底"不能替代本地正常命令的正确性。
- 修复建议（交主控，不写 repo）：在 `shared-core/build.gradle.kts` 为 Test 任务声明输入，例如 `tasks.withType<Test>().configureEach { inputs.files(...manifest/台账/SCHEMA_FILE/source_sha256 清单...) }`（可用 provider 延迟解析 manifest 内路径，兼容配置缓存）；或把证据门禁拆为独立校验任务并让 jvmTest 依赖其输出。P2 门禁同修。

## 原报告结论状态
- 原"通过"结论保留为第一轮记录，不覆写；自本附录起以"**不通过（D5 批量删除 1 项）+ coverage 触发可靠性缺口**"为当前有效结论。
- 其余 Task/决策/ReviewFocus 判定不变；复核日志：/tmp/flarego-r2-a-final-review-evidence/recheck-round2.log。

---

# 最终判定（D5/Gradle 输入两修复第 1 轮复验后，2026-10-18 23:15 CST）

**R2-A 本地工程出口：通过。**

判定轨迹完整保留：第一轮"通过"（后被挑战）→ 第二轮"不通过（D5 批量删除 1 项 + coverage 触发可靠性缺口）"→ 原开发者选择补实现（不改 D5 规格）第 1 轮修复 → 本端针对性只读复验**两项均修复成立**（详见 /tmp/flarego-r2-a-fix-independent-review.md）：
1. D5：bulk Completed 后在同一 coordinator 桶锁作业内做有界（≤5 页×1000、Unicode-safe 前缀、防重复 cursor）独立写后读；任何不确定保持 UNKNOWN+同桶 gate，不重发；Partial/Accepted/Unknown 不假装完成。独立单测 R2ControllerTest 44（RED 43 中 5 行为失败→GREEN 证据齐全）+ 设备 2/2（成功含 DELETE 后 GET /objects?、仍存在 UNKNOWN→ActionRequired、均恰 1 DELETE）。
2. Gradle inputs：jvmTest 声明 audit 台账/四模块 src/core build/SCHEMA_FILE Provider；端到端验证 drift fixture 作 SCHEMA_FILE 正常命令 EXIT=1 真实捕获，恢复默认实际执行且绿；UI/native 源与台账漂移不再被 UP-TO-DATE 掩盖。

**有效证据（当前快照）**：本端独立 --rerun 单测 44+4+6 全绿、设备 2/2、schema 漂移负例/恢复对；manifest 25 sha 0 mismatch；注册 79、51=10+40+1、flags false。
**继承证据（同快照，未由本端重跑）**：开发者 final unit 842/19/97、Native 21/21、audit349/CI10、Gradle 输入负例矩阵、单测/设备历史轮次 XML。
**范围与预算限制**：本轮为针对性复验——未全分支重读（修复局部化且已读）、未重跑无关全套（75/842 等完整回归以同快照既有证据为准）；真实云/付费/临时凭据/跨版本 API26–35 仍未验证，真实环境资格 **declined-to-judge**；R2-B/C/D 未动，R2-E 待批。本地工程出口通过 ≠ 真实云验收或发布资格。
**遗留观察**（非阻塞，原附录第六节三项继续有效）：bulk 回执+回执读取仍依赖 provider 对 list-objects 的一致性；>5000 项 prefix 只能保守 UNKNOWN 需人工核验（设计内代价，无强行解锁）。
