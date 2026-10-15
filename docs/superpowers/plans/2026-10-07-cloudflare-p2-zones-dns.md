# Cloudflare P2：Zone 与完整 DNS 管理 Implementation Plan

**主线集成（2026-10-16）：**P1 九项已有实现，T6–9 集中提交 `f8840c2` 已本地合入 `main`，按用户授权推送 GitHub；恢复源码 core 285／平台 82／原生 49 项及 Debug／lint 通过。当前调用 16 项、真实云资格 0；P2 未开始。见 [主线集成验收](../../cloudflare-audit/p1-integration-verification-2026-10-16.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 Zone 基础生命周期、21 类 DNS 记录完整写模型、批量／文件／扫描／DNSSEC，以及审核过的 Zone 设置与基础缓存／SSL 管理闭环。

**Architecture:** 独立 Zone、DNS、Settings 产品端口使用 P1 scope／gateway／page／operation coordinator。产品 controller 持有内存草稿与变更计划，Compose 使用产品状态，不解释 Cloudflare 原始 DTO；少数复杂类型使用受控原生结构化字段／JSON 表单，不提供任意云请求编辑器。

**Tech Stack:** 沿用 Kotlin／Compose／SQLDelight／OkHttp 与 Android SDK 版本；Python 标准库用于固定 Schema 的局部定义生成，Android Storage Access Framework 用于文件选择。

**Spec:** `docs/cloudflare-coverage-audit.md` §2、§3.1、§5–§9；依赖 `2026-10-07-cloudflare-p1-foundation.md` 的共同契约。本计划已随 P0–P2 总览获批推进，P0 已完成工程风险审核；54 项仍是候选接入范围，不是已实现或已验收结果。

## 集成前状态（2026-10-15）

核对基线 `98cc0c9`：本计划 Task1–10 均尚未开始产品实现。已有旧 DNS 列表／五类 CRUD 属首版；P1 的流式传输、gateway 和 DNS 状态机包装属于公共基础，不能计为 P2 的 21 类模型、批量、文件、Zone 或 DNSSEC 产品完成。

P1 Task1–9 已有实现，迁移验证与 Task5 本地复核已补齐；Task6–9 隔离分支已完成产品／任务装配与本地工程验收，尚未提交合并或完成真实云验收。本计划仍未开始产品实现。任务逐项状态、缺失文件与验证边界见 [开发进度](../../development-progress.md)。下方保留实施要求，未勾选的步骤仍待执行。

## Global Constraints

- P1 的固定 API 主机、4 MiB bounded response、流式文件端口、语义策略、不重放写入、scope 隔离与凭据安全全部适用。
- 固定 Schema 的 DNS 类型为 A、AAAA、CAA、CERT、CNAME、DNSKEY、DS、HTTPS、LOC、MX、NAPTR、NS、OPENPGPKEY、PTR、SMIMEA、SRV、SSHFP、SVCB、TLSA、TXT、URI，共21种；未来新增类型只读展示，不自动开放编辑。
- DNS TTL 为1或通常60–86400；验证过的 Enterprise 约束允许最低30。套餐未知时保持60并解释原因；proxied仅对明确支持的类型开放，代理记录TTL按官方语义处理。
- Zone、DNS 与注册域名是不同模型；本批不进行域名购买、Registrar DS写入或第三方注册商修改。
- PATCH仅发送明确变更；PUT完整替换必须单独预览确认。服务器未提供CAS时“预提交回读”不是强原子并发保证。
- DNS batch在服务端单一数据库事务执行，任一子操作失败则整批不应用；全网传播非原子。Free单批最多200，已验证Pro／Business／Enterprise最多3,500，套餐未知按200。导入BIND文件最多262,144字节，API每用户每分钟3次；本机采用全App共享Cloudflare导入限流桶（滚动60秒最多3次），避免多个连接各自超额，但不能保证其他客户端没有消耗该用户额度。
- 21类可写模型／54个端点的技术接入不等于所有套餐参数已验证；无法取得授权环境的能力必须留下具体阻塞。
- 不修改dreamtools；不创建Tag／Release；提交／合并／推送权限单独确认。

## Review Focus

1. 未知字段／嵌套字段／未来记录类型：保留展示，不因编辑一个已知字段删除未知内容；无法保证嵌套更新语义时阻止该编辑（Task 1、2）。
2. TXT引号换行、SRV／LOC／HTTPS／SVCB结构、TTL30套餐差异：不套统一content字符串导致坏记录（Task 1、2）。
3. 外部并发变化、输入或账号变化、写超时：旧计划不能发送，unknown只能核对；回读不能消除厂商没有CAS的剩余竞争窗（Task 2、3）。
4. 批量原子提交但传播非原子、异常响应／导入部分成功、未知文件指令及空scan候选：不混淆提交与传播，也不宣称空结果＝扫描完成（Task 5、6、7）。
5. Zone删除、DNSSEC关闭、SSL降级、清全站缓存：目标和影响清楚，权限或editable=false时拒绝写入（Task 3、8、9）。

---

## 范围与接口约定

候选54项的精确方法／路径／operationId见 `docs/cloudflare-audit/p2-candidate-endpoints.csv`：

| 工作包 | 数量 | 本批目标 |
| --- | ---: | --- |
| Zone基础＋purge | 7 | list／create／get／edit／delete／activation_check／purge_cache |
| DNS Records | 13 | 全部无弃用信号的Records操作，包括详情、PATCH、PUT、batch、文件、scan新流程、usage |
| DNS Settings | 2 | GET／PATCH |
| DNSSEC | 4 | GET／PATCH／DELETE／zsk读取 |
| Zone Settings | 26 | 无弃用信号的专用及单setting接口；非editable或受限参数动态禁用 |
| Universal SSL Settings | 2 | GET／PATCH |

旧单步scan、旧全量Zone settings GET／PATCH留作迁移记录，不新接入；Cache Reserve、Tiered Cache、variants、Zone environments／invalidate等转P5／P8，台账保留归属。

### 产品契约（task各自创建）

- `ZoneDetails(ref: ZoneScope, name, status, paused, type, nameservers, raw: JsonObject)`；raw为只读供应商元数据，UI通过领域字段展示。
- `DnsRecordDocument(id: String, scope: ZoneScope, raw: JsonObject)`；`DnsRecordInput(type: String, name: String, fields: JsonObject)`。fields仅允许该已知类型官方可写字段，不含id／created_on／modified_on等只读值。
- `DnsConstraints(minTtl: Int = 60, maxTtl: Int = 86400, proxyTypes: Set<String>)`；minTtl30需要已验证证据，不能根据Token名字判断。
- `DnsFilter(name: String?, type: String?, content: String?, comment: String?, tags: List<String>)`；生成实际query参数时使用固定Schema字段与匹配语义，不自行发明过滤参数。
- `PreparedDnsChange(plan: ChangePlan, before: DnsRecordDocument?, input: DnsRecordInput?, mode: DnsWriteMode)`；mode=CREATE／PATCH／REPLACE／DELETE；原始payload只保存在产品controller内存。
- 所有scope／CloudPage／HttpBody／ExecutionReceipt／TaskState／ConfirmedPlan来自P1，不再定义另一套。

## Task 1：固定21种记录定义与局部校验器

**Files:** Create `tools/cloudflare_audit/generate_dns.py`、`tools/cloudflare_audit/tests/test_dns_generation.py`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/generated/DnsSchemas.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/model/DnsDocument.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/{DnsFieldValidation,DnsNormalization}.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/DnsDocumentTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/application/DnsValidation.kt`（旧接口转换为新规则兼容，原五类行为不回归）。

**Interfaces:**
- `generate_dns_definitions(schema: dict) -> dict[str, dict]`：解析官方post／patch／put的21个type分支及可写字段，局部defs保持引用和required；遇到无法处理的结构明确失败，不悄悄选第一个oneOf分支。
- `DnsSchemas.forType(type: String): JsonObject?`；分类型生成局部定义，不打包全27MB官方Schema。
- `validateDnsInput(zoneName: String, input: DnsRecordInput, constraints: DnsConstraints): DnsRecordInput`；规范名称及类型并验证字段，返回不可变结果。
- `validateDnsFields(schema: JsonObject, value: JsonObject): List<FieldViolation>`；支持本局部schema实际用到的type／enum／required／number bounds／pattern／arrays／objects／nullable／allOf／anyOf／oneOf；未支持关键字不得按验证成功处理。
- 保留现有Punycode输入规范；本批不宣称实现任意Unicode到IDNA的自动转换。TXT内容不trim，空值与缺失分别处理。

- [ ] **Step 1：写失败测试。** 生成器验证集合恰为上述21项且重复生成一致；core使用每类官方合法fixture做参数化校验。错误IPv4／IPv6、MX与SRV数值越界、LOC／CAA／TLSA缺字段、HTTPS／SVCB参数、TXT引号换行均有失败／保留断言；TTL30在min60拒绝、min30接受；读到future type仍保存raw但写入拒绝。
  ```kotlin
  assertEquals(21, generatedTypes.size)
  assertEquals("quoted \"value\"\nline", validated.fields["content"]!!.jsonPrimitive.content)
  assertFailsWith<CloudException> { validateDnsInput(zone, ttl30, standardConstraints) }
  ```
- [ ] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_dns_generation.py' -v`；`./gradlew :shared-core:jvmTest --tests 'com.flarego.core.DnsDocumentTest'`。
- [ ] **Step 3：实现生成与局部校验。** 校验源SHA；生成21份定义与readOnly剔除规则；ASCII名称边界、`@`、尾点、通配符与`_service`按DNS规则区分。不从content反推结构化data；未知readonly字段只在document保留。
- [ ] **Step 4：运行 GREEN。** 两组测试及原DnsValidationTest通过；官方fixtures未修改成迎合实现的假数据。生成代码可重复，无网络运行时依赖。
- [ ] **Step 5：提交。** `git commit -m "feat: model and validate all current Cloudflare DNS record types"`。

## Task 2：DNS分页、详情、差量写入与核对

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/DnsManagementPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/dns/CloudflareDnsClient.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/DnsChanges.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/CloudflareDnsManagementTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt`。

**Interfaces:**
- `DnsManagementPort.list(scope: ZoneScope, filter: DnsFilter, page: PageRequest): CloudPage<DnsRecordDocument>`；`get(scope, recordId): DnsRecordDocument`；`create(scope, input): DnsRecordDocument`；`patch(scope, recordId, fields: JsonObject): DnsRecordDocument`；`replace(scope, recordId, input): DnsRecordDocument`；`delete(scope, recordId): Unit`；均为suspend。
- `DnsChanges.prepare(scope, recordId: String?, input: DnsRecordInput?, mode): PreparedDnsChange`；读取当前详情，生成Task5 ChangePlan（使用P1 Task 5的OperationCoordinator契约）。
- `DnsChanges.submit(prepared: PreparedDnsChange, confirmed: ConfirmedPlan): OperationTask`；再次回读并比较beforeHash，冲突不发送；确认requestFingerprint一致；create未知结果通过scope＋目标字段查找，但没有唯一证据时保持UNKNOWN。
- raw未知顶层字段不随PATCH重发；嵌套settings／data只在已知语义且能保留当前所有字段时合并，不认识的nested写行为禁止。PUT无法安全保留未知可写字段时不开放完整替换。

- [ ] **Step 1：写失败测试。** SRV详情保留未知字段；编辑comment不发送type/content/data等未变更字段；nested未知保留或明确拒绝，不删；真正PUT与PATCH路径方法不同；服务器中途改内容返回CONFLICT且写次数0；超时只发送1次；get与list有scope隔离；usage不伪造记录数。
  ```kotlin
  assertEquals(setOf("comment"), patchBody.keys)
  assertEquals(0, writesAfterConflict)
  assertEquals(1, writesAfterTimeout)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.CloudflareDnsManagementTest'`。
- [ ] **Step 3：实现客户端与changes。** 注册本task对应6项单记录／列表operationIds，解析官方result shape；规范化hash只覆盖写语义字段，不用modified_on本身当可靠版本。新create返回真实record ID并回读，不将随机本机ID当云端资源。
- [ ] **Step 4：运行 GREEN。** 新测试与旧DNS JSON转义／unknown测试通过；记录无CAS的竞争窗并在confirmation文案说明。
- [ ] **Step 5：提交。** `git commit -m "feat: manage full DNS documents with conflict-aware changes"`。

## Task 3：Zone生命周期与安全确认

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/model/ZoneModels.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/ports/ZoneManagementPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/zones/CloudflareZoneClient.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/zones/ZoneController.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/ZoneManagementTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt`。

**Interfaces:**
- `ZoneManagementPort.list(account: AccountScope, page: PageRequest): CloudPage<ZoneDetails>`；`get(scope): ZoneDetails`；`create(account, input: ZoneCreateInput): ZoneDetails`；`edit(scope, input: ZoneEditInput): ZoneDetails`；`delete(scope): Unit`；`activationCheck(scope): Unit`；均suspend。
- ZoneCreateInput(name,type)与ZoneEditInput(paused: Boolean?,type: String?,vanityNameservers: List<String>?)字段按固定Schema；账号ID来自account scope，不接受草稿里另填account覆盖。
- `ZoneController.prepare(action: ZoneAction): ChangePlan`／`submit(action, confirmed): OperationTask`；ZoneAction密封类Create／Edit／Delete／ActivationCheck，均带冻结scope与输入。
- 删除与暂停须展示scope／完整域名／影响；删除额外要求输入完整域名匹配，不能将“断开账号”路由到delete。

- [ ] **Step 1：写失败测试。** create使用selectedaccount；域名注册状态与Zoneactive分开；nameserver及paused解析；delete目标不匹配写次数0；切账号后已准备plan拒绝；Zone不存在get404与权限403分别展示；activation只调用官方检查，不声明已传播完成。
  ```kotlin
  assertEquals(account.accountId, createBody["account"]!!.jsonObject["id"]!!.jsonPrimitive.content)
  assertEquals(0, deletesWhenTypedNameDoesNotMatch)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.ZoneManagementTest'`。
- [ ] **Step 3：实现6项Zone基础操作。** 复用coordinator，create超时查同scope名字但不自动重建；限制input type到官方值，不借Zone创建做Registrar购买。
- [ ] **Step 4：运行 GREEN。** 新测试及账号隔离测试通过；不伪造未来可能尚未就绪的nameservers。
- [ ] **Step 5：提交。** `git commit -m "feat: manage Cloudflare zones with explicit destructive confirmation"`。

## Task 4：专用Zone／DNS原生状态与21类型表单

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/DnsController.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/demo/DemoDnsManagement.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/dns/{DnsPage,DnsRecordForm,DnsChangePreview}.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/zones/{ZonePage,ZoneForm}.kt`、`app/src/androidTest/kotlin/com/flarego/DnsManagementUiTest.kt`；Modify `shared-ui/src/commonMain/kotlin/com/flarego/ui/{FlareGoApp,Forms,Screens}.kt`、`app/src/main/kotlin/com/flarego/FlareGoViewModel.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/AppController.kt`。

**Interfaces:**
- `DnsController`公开 `state: StateFlow<DnsPageState>`、`load(scope, filter, page)`、`prepare(input, mode, recordId)`、`submit(confirmed)`、`reconcile(taskId)`；内部草稿不可变，revision隔离旧请求。
- DnsPageState(scope, records: List<DnsRecordDocument>, next, loading, error, selected, preparedChange, pendingTask)。
- Compose `DnsPage(state, onIntent)`、`DnsRecordForm(type, schema, draft, onDraftChange, onPreview)`；schema来自受信任生成定义；typed UI＋复杂字段JSON编辑均经过Task1验证，不提供URL／method编辑。
- Zone页面和controller使用Task3接口；当前五底部导航保留，域名项进入Zone详情，可切DNS／设置／安全标签。

- [ ] **Step 1：写失败原生测试。** 五类快捷表单不回归；选SRV能编辑data.priority／weight／port／target并预览；CAA／HTTPS展示结构化字段；TXT换行保留；未知类型显示只读原因；修改type切换字段不会把前类型payload带入；返回确认后保留草稿；取消不提交。
  ```kotlin
  compose.onNodeWithTag("dns-type-SRV").performClick()
  compose.onNodeWithTag("dns-data-port").assertExists()
  assertEquals(0, fakeApi.writesBeforeConfirm)
  ```
  另测Zone删除完整域名确认、大字体、长字段可滚动、错误不挡其他账号；test tags由本task定义。
- [ ] **Step 2：运行 RED。** 设备可用后 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flarego.DnsManagementUiTest`。
- [ ] **Step 3：实现Compose及入口接线。** 将Forms.kt中的DNS表单迁移到专用包，不顺带重写ConnectionDialog／更新页；演示实现同端口但有示例标识。ViewModel注入P1共用transport/store，P2产品用例不直接读取TokenVault。
- [ ] **Step 4：运行 GREEN。** 新原生测试、旧AppSmokeTest／ConnectionHelpTest／核心账号切换通过；未知字段显示不写入日志或剪贴板。
- [ ] **Step 5：提交。** `git commit -m "feat: add native zone and advanced DNS management pages"`。

## Task 5：批量DNS与逐项核对

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/model/DnsBatch.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/dns/CloudflareDnsBatch.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/DnsBatchController.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/DnsBatchTest.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/dns/DnsBatchPanel.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/ports/DnsManagementPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt`。

**Interfaces:**
- `DnsBatchInput(deletes: List<String>, patches: List<DnsBatchPatch>, puts: List<DnsBatchPut>, posts: List<DnsRecordInput>)`；patch／put含id与对应fields／input。
- `DnsManagementPort.batch(scope, input): DnsBatchOutcome`；Outcome包含事务确认、逐项返回结果与待核对项目，不据HTTP200猜全部完成；正常子操作失败是整批未应用，不冒充普通部分成功模型。
- `DnsBatchController.prepare(scope,input)`／`submit(confirmed)`；每条先校验与回读；官方事务与操作顺序按固定描述，不以客户端自行排序替代；传播非原子明确说明。

- [ ] **Step 1：写失败测试。** 一个非法类型使发送0次；重复／相互冲突record ID输入拒绝；HTTP200但结果缺失或矛盾时保留UNKNOWN；官方明确子操作失败响应映射整批FAILED且不应用；超时不重放整批；核对仅查对应record ID／目标匹配，不把不相关记录算创建成功。
  ```kotlin
  assertEquals(0, requestsForInvalidBatch)
  assertEquals(TaskState.UNKNOWN, inconsistentBatchTask.state)
  assertEquals(1, requestsAfterTimeout)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.DnsBatchTest'`。
- [ ] **Step 3：实现批量与预览。** Free／未知套餐max200，验证过的Pro／Business／Enterprise max3500；超限拒绝或让用户显式拆批并各自确认，不默默分成多次不可原子操作。预览按新增／编辑／替换／删除分别计数与目标域名；缺项unknown不自动再次创建。
- [ ] **Step 4：运行 GREEN。** 新测试通过，原生batch confirmation测试补到DnsManagementUiTest；操作记录说明云端已确认与全球传播是两回事。
- [ ] **Step 5：提交。** `git commit -m "feat: plan and reconcile Cloudflare DNS batch changes"`。

## Task 6：DNS导入导出与文件完整性

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/DnsFilesPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/DnsFileController.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/dns/CloudflareDnsFiles.kt`、`android-platform/src/main/kotlin/com/flarego/platform/DnsDocumentFiles.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/dns/DnsFilePanel.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/DnsFilesTest.kt`、`app/src/androidTest/kotlin/com/flarego/DnsFileIntegrationTest.kt`；Modify `app/src/main/kotlin/com/flarego/MainActivity.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt`。

**Interfaces:**
- `DnsFilesPort.exportRecords(scope: ZoneScope, sink: ByteSink): HttpResponseHead`；`importRecords(scope: ZoneScope, source: ByteSource, length: Long, fingerprint: String, proxied: Boolean): DnsImportOutcome`；均suspend。使用multipart官方file字段与字符串true／false proxied，不将BIND文本包成JSON；避免将Kotlin关键字import作为函数名。
- `DnsFileRef(id: String, displayName, length, sha256)`是本机临时引用，不含Android Uri；平台服务由ref解析SAF文件，core不import Android。
- DnsFileController.prepareImport(scope, fileRef, proxied)生成计划；发送前重新读文件确认hash／长度；不持久化文件文本。导入preview展示文件、目标、选项和“官方执行可能部分导入”，没有真实dry-run API就不伪造逐记录云端预检。

- [ ] **Step 1：写失败测试。** multipart file与proxied按Schema；文件确认后变化写次数0；导出保留BIND原始bytes；错误JSON不能保存成“成功zone文件”；SAF无权限／磁盘不足／取消有安全错误并关闭源；262144字节接受、262145拒绝且发送0次；第4次/60秒请求不发送；未知BIND指令不猜合法记录总数；导入部分成功返回PARTIAL且不再导入；cf-proxied标签覆盖一般proxied选项时UI提示而不是改写文件。
  ```kotlin
  assertEquals(0, uploadsAfterHashChanged)
  assertContentEquals(originalBindBytes, exportedBytes)
  assertFalse(errorBodySavedAsSuccessfulExport)
  ```
- [ ] **Step 2：运行 RED。** core目标DnsFilesTest；设备目标DnsFileIntegrationTest。
- [ ] **Step 3：实现文件生命周期。** SAF CreateDocument／OpenDocument由MainActivity传文件引用；导出stream使用P1 TransferPort，临时文件在失败／取消后删除；只显示必要file元信息。客户端导入上限262144字节，Cloudflare导入限流桶全App共享滚动60秒最多3次；服务端429不自动重放导入。保留$ORIGIN／$TTL／$GENERATE与cf-标签，明确$INCLUDE不被厂商支持；没有完整逐记录解析就说明“记录数未知”，不能承诺默认proxied选项覆盖文件的cf-proxied标签。
- [ ] **Step 4：运行 GREEN。** core／原生测试通过，导入成功后回读记录页／usage核验，但不以总数相同证明每条语义相同。
- [ ] **Step 5：提交。** `git commit -m "feat: import and export DNS files with confirmed fingerprints"`。

## Task 7：新扫描审核流程、usage与DNS Settings

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/DnsScanPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/dns/CloudflareDnsScan.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/DnsScanController.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/DnsScanTest.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/dns/DnsScanPanel.kt`；Modify `DnsManagementPort.kt` 的完整路径同Task2、`CloudflareDnsClient.kt` 同Task2、`ActionPolicies.kt` 同Task2。

**Interfaces:**
- `DnsScanPort.trigger(scope): ExecutionReceipt`；`review(scope): List<DnsScanCandidate>`；`apply(scope, input: DnsScanReviewInput): ExecutionReceipt`。review官方没有page／cursor参数，不人为分页请求；UI可对返回的候选本地分组。DnsScanCandidate(id: String, record: DnsRecordDocument)来自实际候选记录。
- DnsScanReviewInput按官方accept／reject字段定义，候选ID不以列表位置代替；错误shape需明确拒绝，不从普通DNS列表构造假的扫描结果。
- `DnsManagementPort.usage(scope): DnsUsage`；`settings(scope): JsonObject`／`updateSettings(scope, changed: JsonObject): JsonObject`；字段由固定schema的审核注册确定，UI通过原生受控字段映射。

- [ ] **Step 1：写失败测试。** 只调用scan/trigger与scan/review新路径，不调用旧scan；trigger已接受不立即标SUCCEEDED；空review不自动断言扫描完成；apply只发送用户所选候选ID；取消不审批；usage仅以真实返回值显示；DNS setting写前校验权限／scope／差异。
  ```kotlin
  assertEquals(0, callsToDeprecatedScan)
  assertNotEquals(TaskState.SUCCEEDED, taskAfterTrigger.state)
  assertEquals(selectedIds, submittedReviewIds)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.DnsScanTest'`。
- [ ] **Step 3：实现4项scan／usage及2项DNS settings。** 用户主动刷新候选，无已证明status终态时不无限后台轮询；每次apply是独立确认写。P1恢复仅保留已接受任务与安全目标，不自动重触发扫描或apply。
- [ ] **Step 4：运行 GREEN。** 新测试与原生选择／确认测试通过；异步状态和可用性分别展示。
- [ ] **Step 5：提交。** `git commit -m "feat: review asynchronous DNS scans and zone DNS settings"`。

## Task 8：DNSSEC与注册商责任边界

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/DnssecPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/dns/CloudflareDnssecClient.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/dns/DnssecController.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/DnssecTest.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/dns/DnssecPanel.kt`；Modify `ActionPolicies.kt` 同Task2。

**Interfaces:**
- `DnssecPort.get(scope): DnssecDetails`／`update(scope,input: DnssecUpdateInput): DnssecDetails`／`delete(scope): Unit`／`zsks(scope): List<DnssecKey>`；suspend。zsk接口没有分页参数，不发送page／cursor。
- DnssecDetails(status,ds,digest,algorithm,keyTag,raw)按官方可空字段；DnssecUpdateInput字段与允许值来自schema，不以UI开关推断最终状态。
- controller启停／删除都生成计划，展示注册商DS记录责任；DS状态未知时强提示，不自动替用户更改注册商。

- [ ] **Step 1：写失败测试。** pending与active分开；DS缺失不显示示例；disable／delete明确两种action；不同zone不串DS；已有外部DS时警告停止解析风险且需二次确认；zsk字段按实际结果解析不泄露无关凭据。
  ```kotlin
  assertEquals("pending", details.status)
  assertNull(detailsWithoutDs.ds)
  assertEquals(0, disableRequestsBeforeRiskConfirmation)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.DnssecTest'`。
- [ ] **Step 3：实现4项操作和原生页。** 不宣称Provider PATCH成功即全网DNSSEC验证成功；外部注册商流程提供官方帮助，不计为自动API写入。
- [ ] **Step 4：运行 GREEN。** core与原生风险确认测试通过；不默认在生产Zone关闭DNSSEC验收。
- [ ] **Step 5：提交。** `git commit -m "feat: manage DNSSEC with registrar-aware safety checks"`。

## Task 9：Zone Settings、Universal SSL与基础清缓存

**Files:** Create `tools/cloudflare_audit/generate_zone_settings.py`、`tools/cloudflare_audit/tests/test_zone_settings_generation.py`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/generated/ZoneSettingSchemas.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/ports/ZoneSettingsPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/zones/CloudflareZoneSettings.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/application/zones/ZoneSettingsController.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/ZoneSettingsTest.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/zones/ZoneSettingsPanel.kt`；Modify `ActionPolicies.kt` 同Task2。

**Interfaces:**
- `ZoneSettingsPort.get(scope, setting: ZoneSettingKey): ZoneSettingValue`／`update(scope, setting, input: JsonObject): ZoneSettingValue`；ZoneSettingKey映射审核过的operationId／path／field spec，不是任意URL片段。
- `universalSsl(scope): UniversalSslSetting`／`updateUniversalSsl(scope, enabled): UniversalSslSetting`；`purge(scope, input: CachePurgeInput): ExecutionReceipt`。
- CachePurgeInput密封类All／Files／Hosts／Tags／Prefixes，字段组合、数量、套餐按固定schema／官方文档登记。浏览器原生URL校验不向url发送cloudtoken。
- 高频原生控件：ssl、always_use_https、min_tls_version、tls_1_3、http2、http3、development_mode、browser_cache_ttl；值由官方定义和账号editable确定，其余专用setting采用受控生成字段表单并先达到L2。

- [ ] **Step 1：写失败测试。** 26项setting操作＋2Universal＋1purge完整注册；旧全settingsGET／PATCH无调用；editable=false只读；未知setting拒绝请求；ssl降级与purgeAll须风险确认；不同zone独立；provider部分/未知状态不标成功；专用URL与genericsettingURL不混用。
  ```kotlin
  assertEquals(26, activeSettingOperationIds.size)
  assertEquals(0, writesWhenEditableFalse)
  assertEquals(0, purgeAllBeforeConfirm)
  ```
- [ ] **Step 2：运行 RED。** Python目标test_zone_settings_generation；core目标ZoneSettingsTest。
- [ ] **Step 3：生成局部定义并实现29项操作。** Generic get/update仅允许已审核setting keys，缺少可证明输入定义的setting保留needs_review并阻止写；P0正式准入前不得假定54项全部可用。设置读403单项失败不封锁Zone／DNS；更新后回读目标字段，不能拿generic.success=true冒充全站行为已经生效。
- [ ] **Step 4：运行 GREEN。** Python／core及原生风险测试通过；文件URL／tag／prefix清缓存不能隐式变purgeAll；Cache Reserve等不宣称在本批完成。
- [ ] **Step 5：提交。** `git commit -m "feat: control zone settings SSL and cache purges safely"`。

## Task 10：全端点归属、集成验收与文档

**Files:** Modify `README.md`、`docs/native-spec.md`、`docs/cloudflare-audit/review.json`（P0创建）、`docs/cloudflare-audit/p2-candidate-endpoints.csv`（只增加证据，不删除阻塞行）；Create `shared-core/src/commonTest/kotlin/com/flarego/core/P2CoverageTest.kt`、`docs/cloudflare-audit/p2-verification.md`；Modify `app/src/androidTest/kotlin/com/flarego/{AppSmokeTest,DnsManagementUiTest}.kt`。

**Interfaces:** `P2CoverageTest`根据固定54项candidate核对runtime注册＋产品handler＋测试证据键；支持状态与真实验收状态分别记录。阻塞项仍在目标列表，不因无权限从分母删除。

- [ ] **Step 1：写失败测试。** 有任何candidate缺handler／policy／test key则失败；手写registry多出未经审核写动作也失败；整个任务集合唯一覆盖54项（详情／增删改6＋Zone6＋batch1＋files2＋scanusage4＋DNSsettings2＋DNSSEC4＋sitesettings29）。
  ```kotlin
  assertEquals(candidateIds, implementedIds + explicitlyBlockedIds)
  assertTrue((implementedIds intersect explicitlyBlockedIds).isEmpty())
  ```
  blocked只能允许有具体外部准入阻塞与用户批准的条目，不能把未写代码改名blocked来通过实现验收。Blocked存在时明确本批尚未全量完成。
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.P2CoverageTest'`。
- [ ] **Step 3：补齐集成缺口与文档。** P2使用完整document端口后清理仅为迁移保留的DNS UI分支；旧core测试保留语义覆盖不为通过测试放松安全规则。记录已执行mock、emulator、授权测试Zone及未执行项；生产DNSSEC／Zone删除没有授权就不执行。
- [ ] **Step 4：完整验证。**
  ```sh
  ./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
    :app:assembleDebug :app:lintDebug :android-platform:lintDebug
  ./gradlew :app:connectedDebugAndroidTest
  python3 -m unittest discover -s tools/cloudflare_audit/tests -v
  python3 -m unittest discover -s tools/ci/tests -v
  ```
  隔离测试Zone的真实验收须单独获得具体动作授权；工具／账号／设备不可用时报告阻塞，不能算通过。
- [ ] **Step 5：提交。** `git commit -m "docs: verify Cloudflare zone and DNS coverage with evidence"`；不创建Release。

## 执行与评审边界

- Task1 → Task2 → Task4；Task3可在P1后独立推进；Task5／6／7／8依赖Task1／2及P1任务机制。
- Task9复用局部校验器，待Task1稳定后实施；Task10最后集成。
- DNS adapter包与Zone adapter包可并行；ActionPolicies.kt／端口聚合／ViewModel／导航统一由集成负责人修改；前端仅对应ui包，不直接替换backend接口。
- 暂不采用每小task新建workspace；一个P2产品批次复用一个功能worktree和上下文相关会话。
- 第一轮推荐P0→P1→P2顺序交付，P1通过后再按DNS／Zone独立文件范围并行；此计划不是授权启动P3–P9。

## 固定产品约束依据

以下补充文档取自官方cloudflare-docs提交 `e3a9cf758c28273178d56918399068c6d2eb8a1d`，本轮直接读取核对：

- [Batch官方说明](https://github.com/cloudflare/cloudflare-docs/blob/e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/docs/dns/manage-dns-records/how-to/batch-record-changes.mdx)：200／3500限制、数据库事务整批失败、deletes→patches→puts→posts顺序、传播非原子。
- [Import／Export官方说明](https://github.com/cloudflare/cloudflare-docs/blob/e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/docs/dns/manage-dns-records/how-to/import-and-export.mdx)：3次／分钟／用户、cf-proxied优先级、支持的BIND directives。
- [导入文件大小官方定义](https://github.com/cloudflare/cloudflare-docs/blob/e3a9cf758c28273178d56918399068c6d2eb8a1d/src/content/partials/dns/zone-file-size-limit.mdx)：256 KiB／262144字节。

**未执行说明（2026-10-12）：**此文件提供 P2 的逐任务、接口、RED／GREEN命令与验收门禁；P2 产品实现及对应云端验收均未开始。此结论不否认已存在的首版 DNS 或 P1 公共基础实现。
