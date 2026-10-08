# Cloudflare P1：协议、作用域、任务与接口迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立安全且可扩展的产品接入基础，接入账号发现／验证，试点新版 Workers 目录与账号级账单，原有 DNS／演示／更新不回归。

**Architecture:** 在现有 KMP core 与 Android OkHttp 边界内拆分协议、分页、能力与持久化操作。新增产品目录和作用域端口，AppController 保留导航与旧接口兼容，按页面加载产品；新产品不得继续堆入 CloudflareSession。安全策略只由随版本发布的手写注册决定。

**Tech Stack:** Kotlin 2.3.20、Compose Multiplatform 1.10.1、SQLDelight 2.3.2、OkHttp 4.12.0、coroutines 1.10.2、kotlinx.serialization-json 1.8.1；JDK 17；不增加后端。

**Spec:** `docs/cloudflare-coverage-audit.md` §4.2／4.4、§5–§9；P0 台账计划为前置依赖。用户已随 P0–P2 总览批准连续实施；P0 工程出口已通过。当前禁用 Gradle／Android／native，纯 common/JVM 可用预检通过的缓存 Kotlin 编译器和 JUnit 验证；平台、迁移、原生与真实账号验收仍单列，不能据此冒称通过。

## Global Constraints

- Android minSdk 26、compile／target SDK 36、JVM 17；现有版本不升级。
- Cloudflare 管理凭据只能发送至 `https://api.cloudflare.com:443/client/v4`；不跟随重定向，不接受任意 URL／Authorization／Host 注入。
- API bounded-response 上限保持 4 MiB；文件通过独立流式端口，不把上限无限放大。
- 写请求无自动重放；POST 不自动等于写，必须通过手写语义策略分类；未知语义按写处理且默认不开放。
- P0 固定 Schema 不自动跟随 latest；当前 11 个操作仅 isolated_tests_only。
- 未知金额显示未知、十进制文本保存、账号账单与用户账单分开；不用 Alpha 用量伪造成本。
- Token、secret、SQL、DNS TXT 内容及对象内容不得进入任务持久化／日志；只存安全资源定位与指纹。
- 本阶段不做部署、R2 对象产品页、真实购买、后台常驻、S3 鉴权或 WebSocket；这些仍在后续批次，不声称已支持。
- 提交、合并、推送、Tag／Release 权限分别确认；本文提交步骤不在规划轮执行。

## Review Focus

1. HTTP header／path／Location 的恶意目标：拒绝越界，且重定向目标收到零次含 Token 请求（Task 2）。
2. 返回 202／204／304、非 JSON 或部分失败：状态保留，不把已接受与完成混淆（Task 3、5）。
3. 换 Token／断开／切账号时旧任务完成：可归档到原连接，但不能污染新账号 UI；失效凭据不再发送（Task 5、9）。
4. 旧数据库升级、进程死亡与未来未知状态：旧操作可读，未知写入只能核对，不自动重放（Task 4、5）。
5. 新接口 403／404／空数据及金额缺失：不静默回退用户账单、不用旧模型解释新 Worker ID、不以空列表掩盖错误（Task 7、8）。

---

## 开工与接入范围

P0 Task 2／4 完成后实施；工具全量覆盖不是运行时全量开放。

P1新增真实请求范围：`GET /accounts`、`GET /user/tokens/verify`、`GET /accounts/{account_id}/tokens/verify`、`GET /accounts/{account_id}/workers/workers`、`GET /accounts/{account_id}/billing/history`。既有 `/accounts/{account_id}` 验证及原 11 项可兼容。新老 Workers／Billing 策略显式选择，不在 403 后自动切到更宽作用域。

### 共同数据契约

以下名字在本计划及 P2 中固定；创建它们的 task 负责实现，调用 task 不另造同名变体。

- `AccountScope(connectionId: String, accountId: String)`；`ZoneScope(account: AccountScope, zoneId: String)`。`UserScope`／`OrganizationScope` 同样包含 connectionId，不能通过 accountId 猜 User 归属。
- `ResourceRef(scope: CloudScope, kind: String, externalId: String)`；`CloudScope` 是上述四种密封类型；region／jurisdiction 是后续明确字段，不从 name 推断。
- `ActionPolicy(key: String, semantics: ActionSemantics, responseKind: ResponseKind, risk: RiskLevel, retry: ReadRetryPolicy)`；ActionSemantics=READ／WRITE；ResponseKind=ENVELOPE_JSON／RAW_JSON／TEXT／BINARY／EMPTY／NOT_MODIFIED；RiskLevel=READ_ONLY／CHANGE／DESTRUCTIVE／FINANCIAL。ReadRetryPolicy(enabled: Boolean = false, maxRetries: Int = 2, maxWaitMs: Long = 60_000)。未注册操作拒绝执行，Task 3创建这些类型。
- `PageRequest(limit: Int = 50, token: PageToken? = null)`；`PageToken.Number(value: Int)`／`Cursor(value: String)`；`CloudPage<T>(items: List<T>, next: PageToken?, total: Long?)`；其他分页策略后续加入，未知分页不擅自套 page。
- `OperationStore` 为独立端口，原 `LocalStore` 的 connections／operations 仍保持兼容；新 task summary 同步到旧操作列表。

## Task 1：作用域、能力与稳定错误模型

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/model/{CloudScope,Capabilities,CloudFailure,CloudPage}.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/ScopeCapabilityTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/application/AppController.kt` 的safeMessage映射，保留旧CloudException支持并识别CloudFailure。

**Interfaces:**
- 创建上述 scope／ref／page 类型；`ScopeGuard.requireOwned(scope: CloudScope, connection: Connection)`，不一致抛稳定错误。
- `ImplementationLevel`：NONE／REGISTERED／PROTOCOL／OPERABLE／DEEP。
- `CapabilityAvailability`：UNKNOWN／AVAILABLE／PERMISSION_REQUIRED／PLAN_REQUIRED／CREDENTIAL_REQUIRED／RESTRICTED／CONSOLE_ONLY／REMOVED／TEMPORARILY_UNAVAILABLE。
- `CapabilityStatus(key, scope, implementation, availability, reason, observedAtMs)`；实现与账号可用性正交。
- `class CloudFailure(code: CloudFailureCode, publicMessage: String, uncertain: Boolean = false, requestId: String? = null) : Exception(publicMessage)`；code 包含 SCOPE_MISMATCH／PERMISSION_REQUIRED／NOT_FOUND／CREDENTIAL_EXPIRED／RATE_LIMITED／UNAVAILABLE／INVALID_RESPONSE／CONFLICT／UNKNOWN_RESULT／INVALID_INPUT／UNSUPPORTED_CAPABILITY。适配为现有 CloudException，页面不收到供应商响应原文。

- [x] **Step 1：写失败测试。** `scopeWithSameExternalIdDoesNotCrossAccounts` 断言两个 AccountScope 下同 resource ID 不相等；`registeredDoesNotMeanAvailable` 断言 implementation=REGISTERED 且 availability=UNKNOWN；`zoneFromAnotherConnectionIsRejected` 断言 SCOPE_MISMATCH。
  ```kotlin
  assertNotEquals(ResourceRef(a, "worker", "same"), ResourceRef(b, "worker", "same"))
  assertEquals(CapabilityAvailability.UNKNOWN, status.availability)
  ```
- [x] **Step 2：运行 RED。** 名义 Gradle 命令未执行；采用已独立验证的缓存 Kotlin 2.3.20／JUnit JVM runner。新类型缺失和编译 typo 仅算 SETUP；编译通过后的 20 项目标测试出现 10 个实际断言失败，46 全套同样 10 失败；纯值对象控制例首次即通过，如实区分。
- [x] **Step 3：实现最小模型。** core 不 import Android；IDs 原样保留，显示名不得用于唯一性；枚举映射有未知安全值。
- [x] **Step 4：运行 GREEN。** 独立目标 20＋全套 46（原 26＋新 20）通过，实际 14 main＋5 test 源编译；5 个额外临时 oracle 测试通过（25／51），实际 BASE 控制器回放 3 个断言失败、当前 GREEN。333 审计＋10 CI 通过，固定 pin／catalog／5,323,243 字节 ledger／REST blobs／协议 CSV 未改。仅既有 9 个 UpdateTest opt-in 警告，未运行 Gradle／Android／native。
- [x] **Step 5：提交。** `debe2a426a08d345554e6c11a32f2d0ee340d9e7`，只 stage 六个许可路径。

**Task1 独立验收：**规格／质量通过（`4e526af→debe2a4`）。四 scope／完整资源身份／本地所有权与云权限分离、能力实现与可用性正交、未知枚举安全值、分页未知量、稳定错误兼容均验证。AppController 除 safeMessage 外仅增加 typed uncertain 判别，防止未知写入错误记为 FAILED，不改生命周期／导航。宿主 JDK21＋target/release17、compiler-only reflect2.2.10 是已披露的窄 JVM 验证，不是 Gradle/KMP/Android／迁移验收；无新 Cloudflare API 支持／真实 L2 宣称。延期非阻塞文案：枚举解析注释与 case/trim 实际行为不一致、通用数值提示未区分 total=0 合法边界。Task2 尚未实施，先检查纯 JVM HTTP/MockWebServer 可验证性。

## Task 2：多媒体请求、响应与安全传输

**Files:** Modify `shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/HttpTransport.kt`、`android-platform/src/main/kotlin/com/flarego/platform/{CloudflareClient,CloudflareTransport}.kt`；Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/TransferPort.kt`、`android-platform/src/main/kotlin/com/flarego/platform/CloudflareTransfers.kt`；Test `android-platform/src/test/kotlin/com/flarego/platform/{CloudflareClientTest,CloudflareProtocolTest,CloudflareTransferTest}.kt`。

**Interfaces:**
- 保留 `fun interface HttpTransport { suspend fun execute(request: HttpRequest): HttpResponse }`。
- `HttpRequest(method: String, path: String, payload: HttpBody = HttpBody.Empty, headers: Map<String, String> = emptyMap())`；保留旧 `(method, path, body: String?)` 构造及只读 `body: String?` 兼容 JSON 调用。
- `HttpBody.Empty`／`Text(value: String, contentType: String)`／`Bytes(value: ByteArray, contentType: String)`／`Stream(source: ByteSource, length: Long?, contentType: String)`／`Multipart(parts: List<MultipartPart>)`；MultipartPart 的 name、可选 filename、contentType 与 Text／Bytes／Stream body 明确，拒绝 CRLF 注入。
- `HttpResponse(status: Int, bytes: ByteArray, headers: Map<String, List<String>> = emptyMap())`；保留旧 `(status, body: String)` 构造与 UTF-8 `body` getter，仅文本调用使用。
- `ByteSource.read(buffer: ByteArray): Int`／`close()`；`ByteSink.write(buffer: ByteArray, offset: Int, count: Int)`／`close()` 是同步 IO 契约，仅平台 IO 线程调用；-1 表示 EOF。
- `TransferPort.download(request: HttpRequest, sink: ByteSink): HttpResponseHead` 为 suspend；HttpResponseHead 只有 status／headers／transferredBytes。
- body／source／sink 所有权：请求交给 transport 后，由 transport 在成功、失败、取消路径关闭；错误响应不写入下载文件，失败由调用方删除临时产物。

- [ ] **Step 1：写失败测试。** MockWebServer 验证二进制 `[0,255,128]` 原样返回、multipart 原样字段、35 秒现有 bounded call timeout 保持；4 MiB＋1 bounded response 明确失败；大于 4 MiB download 走 sink 而非内存。验证 POST 在连接中断与 503 Retry-After:0 时 requestCount=1；重定向后第二主机 requestCount=0；恶意 `//host`、scheme、`..`／编码 traversal、CRLF headers、Authorization／Host 注入全拒绝。
  ```kotlin
  assertContentEquals(byteArrayOf(0, -1, -128), response.bytes)
  assertEquals(1, server.requestCount)
  assertEquals(0, redirectTarget.requestCount)
  ```
  另测取消调用触发 Call.cancel、关闭 stream，阻塞 IO 不占用 UI 线程。
- [ ] **Step 2：运行 RED。** `./gradlew :android-platform:testDebugUnitTest --tests 'com.flarego.platform.CloudflareProtocolTest' --tests 'com.flarego.platform.CloudflareTransferTest'`。
- [ ] **Step 3：实现传输与兼容构造。** Bearer／Accept 由平台注入，业务 headers 允许表单 Content-Type、条件头等审核字段；固定 HTTPS 主机；OkHttp 禁止自动重试与认证 follow-up，写 body 一律 one-shot。取消桥接到 Call.cancel；download 和 upload source 使用固定大小 buffer；上传 payload 禁用内容日志。文件策略独立较长 timeout（总超时 10 分钟、read 60 秒），不改变普通请求 15／20／35 秒超时；这些 timeout 后仍可能 unknown。
- [ ] **Step 4：运行 GREEN。** 目标测试、既有 CloudflareClientTest 和 core CloudflareTest 均通过；取消后无继续读写。
- [ ] **Step 5：提交。** `git commit -m "feat: add safe Cloudflare binary and streaming transport"`。

## Task 3：策略注册、响应解码、分页与只读限流

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/{ActionPolicies,CloudflareGateway,ResponseDecoders,Pagination}.kt`、`android-platform/src/main/kotlin/com/flarego/platform/CloudflareHttpDates.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/CloudflareGatewayTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/CloudflareSession.kt`。

**Interfaces:**
- `ActionPolicies.policy(key: String): ActionPolicy?` 手写注册现有 11 项与 P1 新增 5 项；key 使用官方 operationId。
- `CloudflareGateway(transport, policies, sleeper: suspend (Long) -> Unit, nowMs: () -> Long, parseHttpDate: (String) -> Long?)`；`suspend fun send(actionKey: String, request: HttpRequest): HttpResponse`，未知 key 拒绝；根据 actionKey 的登记 method／path-template／scope 校验请求匹配，不只是检查 key 存在。
- `ResponseDecoders.envelope(response: HttpResponse, writing: Boolean): JsonObject`；`json`／`text` 分别解析；非 2xx 映射稳定错误且不输出原始响应。204 与 304 返回显式无内容／未变更状态，不调用 envelope。
- `Pagination.decode(root: JsonObject, strategy: PaginationStrategy): PageMetadata`；策略 Number／Cursor／None；`PageMetadata(next, total)`；空 cursor 终止，重复 cursor 失败。
- READ 仅在策略允许时对 429 最多重试 2 次；Retry-After 合法秒数或 HTTP-date，最多等待 60 秒；超出则返回限流和建议等待，不提早发送。缺头使用 1／2 秒退避；WRITE 不重试。5xx 本批不自动重试。HTTP-date由Android平台java.time RFC1123解析器注入，core不引入java.time；测试注入固定时钟／解析器。

- [ ] **Step 1：写失败测试。** `readOnlyPostIsNotMarkedUnknownWrite` 用测试注册的只读 POST 检查异常 uncertain=false；`write429DoesNotReplay` requestCount=1；`acceptedIsNotSuccessCompletion` 202 状态保留；`partialFailureRetainsProviderResult` 保留 safe partial metadata；`cursorLoopFails`；`headerCaseIsInsensitive`；恶意已注册 key 搭配不相干 path 被拒绝。
  ```kotlin
  assertFalse(readFailure.uncertain)
  assertEquals(1, writeRequests)
  assertEquals(202, accepted.status)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.CloudflareGatewayTest'`。
- [ ] **Step 3：实现公共协议。** 解码器不把全部非 JSON 当异常；只有各产品知道 partial 语义，公共层不将 HTTP 200／success=false 宣告“未执行”。旧 Session 转用 gateway，保留 API 字段转换供回归，分页改为显式策略而非全端点 per_page＋page。
- [ ] **Step 4：运行 GREEN。** 目标测试和 core 全部测试通过；每次请求只匹配一个手写策略；输入明文 SECRET 不进入异常输出。
- [ ] **Step 5：提交。** `git commit -m "feat: decode Cloudflare protocols with explicit action policies"`。

## Task 4：持久化任务与兼容数据库升级

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/model/OperationTask.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/ports/OperationStore.kt`、`shared-core/src/commonMain/sqldelight/com/flarego/db/Tasks.sq`、`shared-core/src/commonMain/sqldelight/com/flarego/db/1.sqm`、`android-platform/src/main/kotlin/com/flarego/platform/{LocalDatabase,AndroidOperationStore}.kt`；Modify `android-platform/src/main/kotlin/com/flarego/platform/AndroidLocalStore.kt`、`app/src/main/kotlin/com/flarego/FlareGoViewModel.kt`；Test `app/src/androidTest/kotlin/com/flarego/OperationMigrationTest.kt`。

**Interfaces:**
- `TaskState`：QUEUED／RUNNING／ACCEPTED／SUCCEEDED／PARTIAL／FAILED／UNKNOWN／RECONCILING／ACTION_REQUIRED。
- `OperationTask(id, connectionId, scope, actionKey, resourceRef, requestFingerprint, desiredHash, state, safeDetail, providerRequestId, pollPath, createdAtMs, updatedAtMs)`；nullable 字段显式声明，hash 是 SHA-256 十六进制串，pollPath 是已审核相对路径。
- `OperationStore.get(id): OperationTask?`、`list(connectionId): List<OperationTask>`、`put(task): Unit`、`removeConnection(connectionId): Unit`、`putWithSummary(task, summary: Operation): Unit`；最后一项必须单事务。
- LocalDatabase 统一持有一个 FlareGoDatabase，注入旧 LocalStore 和新 OperationStore；不为每个 store 重开独立连接。
- 旧 OperationStatus 三个值不改名；新任务通过 summary 映射 success／failed／unknown（partial／accepted 等 detail 明确，不谎称成功）。

- [ ] **Step 1：写失败测试。** 用 Android SQLite 建立精确旧 connection／operation 表并设置 `PRAGMA user_version=1`，存三种旧状态，再由新 driver 升到 v2；断言老账号和老操作不丢、tasks 表存在、事务故障不会只写 summary。未来未知状态字符串读为 UNKNOWN，不调用 valueOf 崩溃；断开连接清除对应 tasks 但保留其他连接。
  ```kotlin
  assertEquals(3, localStore.operations().size)
  assertEquals(TaskState.UNKNOWN, operationStore.get("future")!!.state)
  assertNull(operationStore.get("disconnected-task"))
  ```
- [ ] **Step 2：运行 RED。** 有模拟器后 `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.flarego.OperationMigrationTest`；没有设备记录阻塞，不以单测代替 migration 验收。
- [ ] **Step 3：实现 schema／1.sqm 与 store。** 新增表，不破坏旧列；1.sqm 与 fresh Tasks.sq 建表结构一致。新TaskState与旧OperationStatus的持久化读取都采用未知值回退UNKNOWN，禁止裸valueOf导致未来版本数据崩溃。拒绝 payload／credential 字段；store schema 仅安全定位和指纹。数据库退出时统一关闭，主线程不做大 IO。
- [ ] **Step 4：运行 GREEN。** 重跑 migration 测试和 LocalPersistenceTest；旧、新安装路径均通过，原旧操作记录仍可读。
- [ ] **Step 5：提交。** `git commit -m "feat: persist recoverable Cloudflare task metadata"`。

## Task 5：预览、确认、执行与恢复状态机

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/application/operations/{ChangePlans,OperationCoordinator,CanonicalJson}.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/ports/FingerprintPort.kt`、`android-platform/src/main/kotlin/com/flarego/platform/AndroidFingerprint.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/OperationCoordinatorTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/application/AppController.kt`（仅抽出操作生命周期，导航行为保持）。

**Interfaces:**
- `fun interface FingerprintPort { fun sha256(bytes: ByteArray): String }`；Android实现使用JDK SHA-256，core测试注入确定性实现。`CanonicalJson.encode(value: JsonElement): ByteArray`按对象key排序、保留数组顺序与数值文本、不删除null／unknown字段。Task 5创建；P2文件指纹与变更计划使用同一端口。
- `ChangePlan(id, scope, actionKey, resourceRef, beforeHash, desiredHash, requestFingerprint, credentialRevision, expiresAtMs)`；payload 仅留在当前产品 controller 内存，不入库。
- `ChangePlans.prepare(scope: CloudScope, actionKey: String, resourceRef: ResourceRef?, beforeHash: String?, desiredHash: String?, requestFingerprint: String, credentialRevision: Long, nowMs: Long): ChangePlan`，有效期5分钟。
- `confirm(planId: String, currentScope: CloudScope, credentialRevision: Long, requestFingerprint: String, nowMs: Long): ConfirmedPlan`；ConfirmedPlan(plan: ChangePlan, confirmationId: String)只能由ChangePlans内部创建并登记。未知／过期／输入或凭据变化拒绝；coordinator还须验证confirmationId，不信任UI自造对象。
- `ExecutionReceipt.Completed`／`Accepted(pollPath)`／`Partial(safeDetail)`；confirmed plan 在 coordinator 接受后标 consumed，同一 plan 重复提交返回同一 task。
- `suspend OperationCoordinator.execute(plan: ConfirmedPlan, write: suspend () -> ExecutionReceipt): OperationTask`；scope guard、凭据 revision、resource lock、task 落盘后才发送。闭包由受信任产品用例提供，不向 UI 暴露任意请求执行器。
- `suspend reconcile(taskId: String, read: suspend (OperationTask) -> Reconciliation): OperationTask`；结果 Matched／NotApplied／StillUnknown／ActionRequired。只回读，不重新调用 write。
- `recoverPending(): List<OperationTask>`：RUNNING→UNKNOWN，未发送 QUEUED→ACTION_REQUIRED；ACCEPTED 可查询，已确认写入不自动 replay。

- [ ] **Step 1：写失败测试。** 同一 plan 双击写次数=1；输入改变／过期／scope改变／credentialRevision改变写次数=0；同资源锁冲突阻止并发；task 先持久化后发送；UI取消不导致重复写；执行器取消／进程恢复只标 unknown；202→ACCEPTED；明确 partial→PARTIAL；核对读取不可恢复为 write。
  ```kotlin
  assertEquals(1, writes)
  assertEquals(TaskState.UNKNOWN, recoveredRunning.state)
  assertEquals(TaskState.ACCEPTED, accepted.state)
  ```
  特别覆盖“provider success=false 但可能已部分应用”，不得 FAILED＝未执行。
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.OperationCoordinatorTest'`。
- [ ] **Step 3：实现状态机。** 注入时钟／ID 工厂／安全 hash 函数便于测试；同资源串行而非全账号所有写一个锁。AppController 旧 DNS 写走 coordinator-compatible wrapper，原 UI 仍先确认；disconnect 使 revision 失效，未发送任务停止，已发请求的结果归原 connection。
- [ ] **Step 4：运行 GREEN。** 新测试和已有 AppControllerTest，尤其 cancelled UI、unknown 不重放、旧 factory 不重缓存，全通过。document 说明 fingerprint 不是厂商幂等键，也不提供跨进程 exactly-once。
- [ ] **Step 5：提交。** `git commit -m "feat: coordinate confirmed Cloudflare writes and reconciliation"`。

## Task 6：账号发现、Token 类型与能力探测

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/{IdentityPort,CapabilityPort}.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/CloudflareIdentityClient.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/CloudflareIdentityTest.kt`；Modify `app/src/main/kotlin/com/flarego/FlareGoViewModel.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/{ConnectionDraft,Forms}.kt`；Test `app/src/androidTest/kotlin/com/flarego/AccountDiscoveryTest.kt`。

**Interfaces:**
- `TokenKind`：USER_OWNED／ACCOUNT_OWNED／UNKNOWN；`TokenVerification(kind, active: Boolean?, notice: String?)`。
- `IdentityPort.discover(request: PageRequest): CloudPage<AccountSummary>`；`verifyAccount(accountId: String): AccountSummary`；`verifyToken(kind: TokenKind, accountId: String?): TokenVerification`；AccountSummary(id,name)。未知类型不猜权限；USER_OWNED 与 ACCOUNT_OWNED 使用各自 verify URL。
- `CapabilityPort.status(scope, actionKey): CapabilityStatus`；只读探测有缓存与独立过期，403 映射 PERMISSION_REQUIRED，不擅自推断具体缺失套餐。
- 连接 UI “发现账号”为显式只读动作；发现受限时仍支持原手工 Account ID。保存条件继续要求指定 account 可读取；可选 token verification 不取代账号验证。

- [ ] **Step 1：写失败测试。** accounts分页可选择账号；discover403仍可手动连接并成功验证；用户 token 不误用 account verify；账户 token 不强制通过 user verify；Token 本身有效但该账号不可读拒绝保存；确认后草稿清空、重建保留规则与旧 ConnectionHelpTest 一致。
  ```kotlin
  assertEquals("account-id", identity.verifyAccount("account-id").id)
  assertTrue(manualConnectAllowedAfterDiscovery403)
  assertEquals(0, savedConnectionsWhenAccountDenied)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.CloudflareIdentityTest'`；设备可用时目标 AccountDiscoveryTest 必须先失败。
- [ ] **Step 3：实现 discovery 和原生选择 UI。** 在凭据草稿生命周期内查询，不打印列表原始响应；选定 scope 后才保存连接。核心 Account ID 32 位校验保留；不引入 Global API Key 回退。生产 UI 不宣称 token verify 返回完整权限清单。
- [ ] **Step 4：运行 GREEN。** 新 core／原生测试，加 ConnectionHelpTest 和连接恢复测试；发现失败不会清空用户草稿或断开已有账号。
- [ ] **Step 5：提交。** `git commit -m "feat: discover Cloudflare accounts without requiring broad tokens"`。

## Task 7：新 Workers 目录与分产品目录端口

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/ProductDirectoryPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/inventory/CloudflareDirectoryClient.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/demo/DemoDirectory.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/CloudflareDirectoryTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/model/Models.kt`（CloudResource 增加可选来源与 legacy 标志，不破坏构造）。

**Interfaces:**
- `ProductDirectoryPort.zones(account: AccountScope, request: PageRequest): CloudPage<Domain>`。
- `resources(account: AccountScope, kind: ResourceKind, request: PageRequest): CloudPage<CloudResource>`。
- `WorkersDirectoryMode`：MODERN／LEGACY；默认 MODERN，403 不自动回退；兼容模式必须用户显式选择且页面标“旧接口”。
- 新 Worker id 为 immutable ID，name 为显示／script name；两者分别保存，不以旧脚本名称替代新 ID。新接口 per_page=50，不超过官方 max100。
- R2／D1／KV 分页各自策略，单次返回一页；legacy inventory() 可作为兼容门面，不用于新版每次页面刷新全产品。

- [ ] **Step 1：写失败测试。** worker名与id不同仍正确显示和定位；deployed_on=null无时间占位造假；新列表403无旧接口请求；显式LEGACY标来源；R2 cursor 无 page 参数；同名跨 scope 不混；kind=KV 不调用 Workers／R2／D1。
  ```kotlin
  assertEquals("immutable-id", worker.id)
  assertEquals("script-name", worker.name)
  assertEquals(0, callsToLegacyAfter403)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.CloudflareDirectoryTest'`。
- [ ] **Step 3：实现目录客户端与 DemoDirectory。** 使用 Task 3 gateway；解析固定 Schema 新 shape；分页失败保留明确错误不以空目录冒充。demo与真实分离，演示数据有示例标识。
- [ ] **Step 4：运行 GREEN。** 新测试与原目录分页隔离测试通过；新目录尚无真实账号证据时只登记 L1，不宣布完整 Workers 管理。
- [ ] **Step 5：提交。** `git commit -m "feat: page Cloudflare product directories and migrate Workers identity"`。

## Task 8：账号账单试点与明确兼容范围

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/ports/ScopedBillingPort.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/billing/CloudflareBillingClient.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/ScopedBillingTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/model/Models.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/Screens.kt`。

**Interfaces:**
- `ScopedBillingPort.history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry>`；本批支持 AccountScope 与显式 UserScope，其余scope拒绝。
- `BillingEntry(id, scope, occurredAt, amount: String?, currency, description, status, invoiceId: String?, receiptId: String?)`；JSON 数值使用 lexical text，不经过 Double 运算。
- `BillingSource`：ACCOUNT_HISTORY／USER_HISTORY_LEGACY；用户兼容模式不能映射到 account费用或自动回退。
- 页面明确显示“当前账号账单历史”或“用户级账单历史 · 旧接口”；无金额时“未知”，不显示 0，不建立不存在的 estimate。

- [ ] **Step 1：写失败测试。** account path正确、分页与scope保留；amount=20.99保存字符串；missing amount为null；account403不请求userhistory；用户模式标题不可变账号级；HTTP错误不以demo发票补齐。
  ```kotlin
  assertEquals("20.99", entry.amount)
  assertNull(entryWithoutAmount.amount)
  assertEquals(0, userHistoryCallsAfterAccount403)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.ScopedBillingTest'`。
- [ ] **Step 3：实现与 UI 标识。** 读取固定 Schema account_billing_history，不仅替换URL。保留旧用户范围入口作为显式选择；hosted_invoice_url 本批不自动请求或携Token打开，PDF／订阅／金额合计在P4。
- [ ] **Step 4：运行 GREEN。** 新测试及 billingHasNoInventedEstimate 通过；账单 scope 与 selected connection 匹配。
- [ ] **Step 5：提交。** `git commit -m "feat: read scoped Cloudflare account billing history"`。

## Task 9：按产品加载、状态隔离与应用集成

**Files:** Create `shared-core/src/commonMain/kotlin/com/flarego/core/application/inventory/ProductLoader.kt`、`shared-core/src/commonMain/kotlin/com/flarego/core/ports/ProductSessionFactory.kt`、`shared-core/src/commonTest/kotlin/com/flarego/core/ProductLoaderTest.kt`；Modify `shared-core/src/commonMain/kotlin/com/flarego/core/{application/AppController.kt,model/Models.kt}`、`app/src/main/kotlin/com/flarego/FlareGoViewModel.kt`、`shared-ui/src/commonMain/kotlin/com/flarego/ui/{FlareGoApp,Screens}.kt`；Create `app/src/androidTest/kotlin/com/flarego/ProductLoadingTest.kt`；Modify `README.md`、`docs/native-spec.md`。

**Interfaces:**
- `ProductServices(directory: ProductDirectoryPort, billing: ScopedBillingPort, capabilities: CapabilityPort)`；`fun interface ProductSessionFactory { suspend fun create(connection: Connection): ProductServices }`。
- `ProductLoader`注入CoroutineScope、ProductSessionFactory、selected AccountScope StateFlow及凭据revision查询；公开 `states: StateFlow<Map<ProductKey, ProductLoadState>>`、`load(product: ProductKey, request: PageRequest = PageRequest())`／`invalidate(connectionId)`；ProductKey=ZONES／WORKERS／R2／D1／KV／BILLING。
- `ProductContent`密封类Zones(items: List<Domain>)／Resources(items: List<CloudResource>)／Billing(items: List<BillingEntry>)；`ProductLoadState(content: ProductContent, next: PageToken?, total: Long?, loading: Boolean, error: CloudFailure?, scope: AccountScope, credentialRevision: Long, observedAtMs: Long?, allPagesLoaded: Boolean)`。scope表示此页所属选择账号；BillingEntry自身的UserScope／AccountScope另行保留，不能把用户账单归入该页账号。不同产品有各自状态，不共用一个 loading／error；只有首请求完成且所有后续页读完才allPagesLoaded=true，不让 UI 解读供应商 JSON。
- AppController 构造增加可选 productFactory，原测试可继续使用旧 CloudSession；生产与demo都在此 task 使用新 loader。旧 inventory分支仅是迁移兼容，P2集成后评审去除，不扩展新产品。

- [ ] **Step 1：写失败测试。** 进域名只请求ZONES、不默认加载账单；选R2只加载R2；明示全产品刷新可分别加载，某个403不清空其他成功数据；换账号取消／丢弃旧结果；同connection换Token后旧结果不能写入；局部页加载不显示为“总数已完整”。
  ```kotlin
  assertEquals(listOf(ProductKey.ZONES), requestedProducts)
  assertEquals("new-account", state.scope.accountId)
  assertFalse(state.allPagesLoaded)
  ```
- [ ] **Step 2：运行 RED。** `./gradlew :shared-core:jvmTest --tests 'com.flarego.core.ProductLoaderTest'`；设备可用时目标 ProductLoadingTest。
- [ ] **Step 3：接线并完成最小原生界面。** 五底部入口不改；资源页增加四类产品选择与分页加载；Overview只展示已知结果与“未加载／已加载N”，不把第一页当全量或将未知用量补0；操作页展示新state的安全detail与核对入口。授权真实端点测试作为单独准入任务，凭据不进入仓库。
- [ ] **Step 4：全量 GREEN 与阶段验收。**
  ```sh
  ./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
    :app:assembleDebug :app:lintDebug :android-platform:lintDebug
  ./gradlew :app:connectedDebugAndroidTest
  python3 -m unittest discover -s tools/ci/tests -v
  python3 -m unittest discover -s tools/cloudflare_audit/tests -v
  ```
  emulator未运行或真实账号不可用必须报告未验收；新功能矩阵区分mock／emulator／真实账号证据。
- [ ] **Step 5：提交。** `git commit -m "feat: integrate lazy Cloudflare product loading and recovery"`。

## P1 出口与并行边界

- Task 1 → Task 2 → Task 3；Task 4 可在 Task 1 后独立推进，由统一数据库负责人改 ViewModel；Task 5 依赖 Task 3／4。
- Task 6／7／8 在 Task 3 后按各自目录并行；共同 Models.kt 修改由集成负责人执行，Forms.kt／Screens.kt 由前端任务复用现有 Gemini 会话。
- Task 9 最后集成，不能让多个 Agent 同时修改 AppController／ViewModel／导航。
- P0台账对应行从NONE变PROTOCOL需契约测试；OPERABLE须真实授权账号证据。基础通过不代表部署／对象操作已经实现。
- 验收需确认原11项、演示、账号草稿、凭据持久化、应用更新与签名规则不回归；本计划不发布版本。

**执行状态：**Task1 工程验收通过；Task2–9、迁移／平台／原生／真实账号验收尚未实施。P2 必须使用这里固定的 scope／page／gateway／operations 契约。
