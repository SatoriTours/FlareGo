# P3 批次一：R2 深度管理 规格

**日期：** 2026-10-17
**状态：** 待用户评审
**上游：** `docs/cloudflare-coverage-audit.md` §3.1（R2 行）、§8（P3 批次）、§9（验收体系）
**候选范围：** `docs/cloudflare-audit/review.json` 中 `phase=P3` 且 `product_id=r2-objects-and-buckets`，共 **51** 个 REST 操作（20 读／26 写／5 未知，全部 `scope_kind=account`）。

## 1. 目标

把当前只做目录列举的 R2 产品扩展为**可操作的深度管理**：桶生命周期、对象 REST 读写、桶配置、后台任务与事件通知。目标等级：高频路径 L3，其余 L2。本规格只覆盖 P3 的 R2 部分；KV／D1／Workers／Pages 各自另出规格。

**成功判据：** 每类能力具备 创建→读取→修改→删除 闭环，且在不读入内存、不重放未知写入、不泄露凭据三条约束下通过 mock 契约／平台／原生三层验证。

## 2. 非目标（明确排除）

| 排除项 | 归属 | 理由 |
| --- | --- | --- |
| R2 S3 协议 73 项（ListBuckets／HeadObject／分片等） | P5 | 需 SigV4 与 stream-scoped 凭据，独立分母 |
| 大对象分片上传、>5 GiB 单对象 | P5 | S3 协议能力，REST 路径不提供 |
| `cf-r2-jurisdiction` 之外的 jurisdiction 专用主机与凭据 | P5／P8 | 需要独立 host 准入 |
| `r2-data-catalog*` 目录操作 | 未在 P3 候选内 | 保留台账归属 |
| Registrar、Zone、DNS 行为 | 已完成（P2） | 不回归 |

## 3. 关键决策

**D1 对象内容一律流式。** 上传／下载只用 P1 的 `ByteSource`／`ByteSink`／`TransferPort`；任何对象内容都不得进入 core 内存、不得走 `HttpBody.Text`。下载目标为 SAF 事务产生的临时 sink，校验响应头后提交；上传源由用户选择的 SAF 文档提供。

**D2 对象 key 按官方语义原样发送。** 固定 schema 明确要求 key 中的斜杠**字面发送、不得编码为 `%2F`**。写入校验：非空、UTF-8 字节数 ≤1024、不含 C0／C1 控制符与 `\`、不是 `.` 或 `..`、不以 `/` 开头；读取时保留 provider 返回的原样 key。

**D3 桶名校验取官方 pinned schema。** `r2_bucket_name`：`minLength=3`、`maxLength=64`、pattern `^[a-z0-9][a-z0-9-]*[a-z0-9]`。额外拒绝：IPv4 形式、`xn--` 前缀、`-s3alias` 后缀（S3 兼容常识，写入拒绝、读取保留展示）。

**D4 枚举只允许官方值写入，未知值读取保留。** `locationHint ∈ {apac, eeur, enam, weur, wnam, oc}`；`storageClass ∈ {Standard, InfrequentAccess}`；`jurisdiction ∈ {default, eu, us, fedramp, fedramp-high}`。provider 返回的未知枚举在文档模型中保留原值并只读展示。

**D5 风险分级与确认。** `DESTRUCTIVE` + 独立风险确认 + 冻结目标 + 写后回读：桶删除、批量对象删除、桶锁定配置删改、`r2.dev` 公共访问启停、自定义域删除。其余写为 `CHANGE`。沿用 P1 `ChangePlans`／`OperationCoordinator`，不新增确认机制。

**D6 批量删除上限 1000，超限拒绝。** `r2-delete-objects` 请求体为 JSON 字符串数组，`minItems=1`、`maxItems=1000`；UI 逐 key 展示后确认。已弃用的 `prefix` 参数不暴露给用户，只在台账保留弃用记录。

**D7 临时凭据签发推迟。** `r2-create-temp-access-credentials` 会签发可用的 S3 凭据，属凭据签发能力，需用户单独批准。候选台账保留归属，覆盖门禁中记 `DEFERRED_PENDING_APPROVAL`，**不计入已完成数**。

**D8 未知字段保留。** 所有 provider 文档模型带 `raw: JsonObject`，编辑已知字段不得删除未知内容；无法保证嵌套语义时阻止该编辑（与 P2 的 Review Focus 1 一致）。

**D9 复用既有 SAF 文件端口。** 直接复用 `DnsFileStore`／`DnsExportTransaction`／`DnsFileRef` 的形状与实现语义（临时 sink、提交前校验、失败清理、不覆盖用户源文件）。本次不做重命名重构；若之后命名成为障碍，另开独立任务。

**D10 真实云资格保持 0。** 本批实现止于 mock 契约／平台／原生三层；不做真实云写入、不申请付费资格、不改环境登记。

## 4. 分批与顺序

| 批次 | 内容 | 操作数 | 顺序依据 |
| --- | --- | ---: | --- |
| **R2-A** | 桶控制面（create／create-by-name／get／patch／delete）+ 对象 REST 闭环（list／get／put／delete／delete-objects） | 10 | 满足 §8「创建→读取→修改→删除闭环」，且复用已有桶目录 UI |
| R2-B | 配置面：CORS(3)、自定义域(5)、managed 公共域策略(2)、lifecycle(2)、local-uploads(2)、lock(3)、Sippy(3) | 20 | 依赖 R2-A 的桶目标模型 |
| R2-C | bucket jobs(3)、account metrics(1)、事件通知(4) | 8 | 只读为主，复用通知/任务模式 |
| R2-D | Super Slurper 迁移任务(10) + 连通性预检(2) | 12 | 长任务生命周期，需 R2-C 的任务模式 |
| R2-E | `r2-create-temp-access-credentials` | 1 | **待用户单独批准** |

R2-A 是本规格的交付目标；R2-B…E 在 R2-A 验收后各自出实施计划。

## 5. 接口草案（R2-A）

```kotlin
// shared-core/.../model/R2Models.kt
data class R2BucketDocument(val name: String, val scope: AccountScope, val raw: JsonObject)
data class R2BucketCreateInput(val name: String, val locationHint: String? = null, val storageClass: String? = null)
data class R2ObjectEntry(val key: String, val size: Long?, val etag: String?, val lastModified: String?, val raw: JsonObject)
data class R2ObjectTarget(val scope: AccountScope, val bucket: String, val key: String)
data class R2ObjectHead(val status: Int, val transferredBytes: Long, val etag: String?, val contentType: String?)

// shared-core/.../ports/R2ManagementPort.kt
interface R2ManagementPort {
    suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument>
    suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument
    suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument
    suspend fun patchBucket(account: AccountScope, name: String, fields: JsonObject): R2BucketDocument
    suspend fun deleteBucket(account: AccountScope, name: String)
    suspend fun listObjects(target: R2ObjectTarget, prefix: String?, delimiter: String?, page: PageRequest): CloudPage<R2ObjectEntry>
    suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead
    suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?): R2ObjectEntry
    suspend fun deleteObject(target: R2ObjectTarget)
    suspend fun deleteObjects(target: R2ObjectTarget, keys: List<String>): R2BulkDeleteOutcome
}
```

`R2ManagementSession` 沿用 P2 的 `ExecutionReceipt` + `beforeSend` 守卫形状；`write`／`writeBulkDelete` 是唯一发送点。

## 6. Review Focus（必须被测试钉住）

1. **对象 key 编码：** 含斜杠、空格、`+`、非 ASCII、前导 `/`、`.`／`..`、超 1024 字节。期望：合法 key 原样正确到达（斜杠不编码）；非法 key 写入拒绝，发送次数 0。
2. **流式边界：** 4 MiB 以上对象下载不得走内存 bounded 路径；上传大文件不得一次性读入内存；取消后 sink／source 均关闭且不产生半成品。
3. **批量删除：** 1001 个 key 拒绝且发送 0 次；响应缺失或矛盾时状态为 UNKNOWN 且只回读不重发。
4. **桶删除前置条件：** 非空桶／存在 multipart／存在通知规则时 provider 失败必须映射为可解释的前置条件错误，不重试、不误报成功。
5. **公共访问与自定义域：** `r2.dev` 启用、自定义域删除需明确确认；权限不足或 `editable=false` 时拒绝写入而非隐藏按钮后静默失败。

## 7. 验收标准

- R2-A 的 10 个 operationId 各有 typed handler、手写策略、测试 key，且 `R2CoverageTest` 可核对（参照 `P2CoverageTest` 的注册表导出方式）。
- R2-B…E 的 41 项在覆盖台账中显式记为未完成／待批，不用「blocked」伪装成已完成。
- `./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest :app:assembleDebug :app:lintDebug` 全通过；设备层按可用性单独记录。
- `docs/cloudflare-audit/` 新增 R2 运行时支持记录，逐项写 handler／test key／云验收状态（`UNVERIFIED`）。
- 凭据、S3 secret、对象内容不进入日志、剪贴板或公开截图。

## 8. 未决问题（需用户回答）

1. **R2-E 是否批准？** 临时凭据签发是安全敏感能力，默认推迟。
2. **批次顺序是否接受？** 建议 R2-A → R2-B → R2-C → R2-D，而不是先做 Slurper 或 jobs。
3. **上传文件大小上限如何表述？** 建议 UI 只对超过 5 GiB 的单对象明确拒绝并指向 P5，其余走流式。
