# P3 批次一：R2 深度管理 规格

**日期：** 2026-10-17
**状态：** R2-A 本地工程出口验收通过（2026-10-18）；R2-B §9完整20项已通过API36合成本地工程验收（2026-10-21，同一独立QA round2＋round3及主控补缺核定），见[B当前出口](../plans/2026-10-19-p3-r2-batch-b.md)。C8 §10完整8项已于2026-10-24通过API36合成本地工程验收（独立自动化与两字体实际视觉补验、父核定，见[批次C当前出口](../plans/2026-10-22-p3-r2-batch-c.md)）；D12尚未开始，R2-E仍待单独批准。实际API26–28、真实云／付费／发布资格未验收，无新增Git授权。A历史见[最终核对](../../cloudflare-audit/r2-a-local-exit-verification-2026-10-18.md)。
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

**D5 风险分级与确认。** `DESTRUCTIVE` + 独立风险确认 + 冻结目标 + 写后回读：桶删除、批量对象删除、桶锁定配置删改、`r2.dev` 公共访问启停、自定义域删除。其余写为 `CHANGE`；B按§9对公开、自动删除/终止multipart及保留策略采取更保守的独立风险确认。沿用 P1 `ChangePlans`／`OperationCoordinator`，不新增确认机制。

**D6 批量删除上限 1000，超限拒绝。** `r2-delete-objects` 请求体为 JSON 字符串数组，`minItems=1`、`maxItems=1000`；UI 逐 key 展示后确认。已弃用的 `prefix` 参数不暴露给用户，只在台账保留弃用记录。

**D7 临时凭据签发推迟。** `r2-create-temp-access-credentials` 会签发可用的 S3 凭据，属凭据签发能力，需用户单独批准。候选台账保留归属，覆盖门禁中记 `DEFERRED_PENDING_APPROVAL`，**不计入已完成数**。

**D8 未知字段保留。** provider 文档模型带 `raw: JsonObject`，编辑已知字段不得删除未知内容；无法保证嵌套语义时阻止该编辑（与 P2 的 Review Focus 1 一致）。B的Sippy敏感例外：raw仅是去敏安全快照，不缓存/回显原始secret，不以未知字段保留为由持久化秘密。

**D9 复用既有 SAF 文件端口。** 直接复用 `DnsFileStore`／`DnsExportTransaction`／`DnsFileRef` 的形状与实现语义（临时 sink、提交前校验、失败清理、不覆盖用户源文件）。本次不做重命名重构；若之后命名成为障碍，另开独立任务。

**D10 真实云资格保持 0。** 本批实现止于 mock 契约／平台／原生三层；不做真实云写入、不申请付费资格、不改环境登记。

**D11 A阶段平台业务头白名单只加 `cf-r2-storage-class`。** 桶的 PUT／PATCH 变体没有请求体，storage class 只能走这个头；平台层原本只允许 content-type／条件头／range（`CloudflareClient.ALLOWED_BUSINESS_HEADERS`），必须显式扩一条。`cf-r2-jurisdiction` 留到 R2-B，不在本批扩白名单。

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
    /** POST 变体：JSON 体携带可选 locationHint 与 storageClass。 */
    suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument
    /** PUT 变体：**无请求体**，桶名在路径，可选 storageClass 走已审核头。 */
    suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument
    /** PATCH 变体：**无请求体**，必填 storageClass 走已审核头。这是桶唯一可变属性。 */
    suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument
    suspend fun deleteBucket(account: AccountScope, name: String)
    suspend fun listObjects(target: R2ObjectTarget, prefix: String?, delimiter: String?, page: PageRequest): CloudPage<R2ObjectEntry>
    suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead
    suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?): R2ObjectEntry
    suspend fun deleteObject(target: R2ObjectTarget)
    suspend fun deleteObjects(target: R2ObjectTarget, keys: List<String>): R2BulkDeleteOutcome
}
```

**桶列表的响应形状与 Zone 不同：**`r2-list-buckets` 的结果在 `result.buckets`（对象里的数组），不是 `result` 顶层数组，且用 cursor 分页。这一点必须按 pinned schema 实现，不能照搬 Zone 客户端。

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

## 9. R2-B 配置面设计（2026-10-19已批准实施）

用户回复“ok”批准本节完整20项推荐方案及新增风险/敏感内存处理边界；不包含实际云写入、使用真实凭据、Git或发布授权。A的代码与未提交成果保留；批准时B机器台账仍20项NOT_STARTED（历史时点，非当前状态）。当前B20本地工程通过，51候选=30typed/20NOT_STARTED/1DEFERRED；验收范围与限制以上述B计划当前出口和独立报告为准，不回写冻结历史资格。实施步骤及阶段账本见[批次B计划](../plans/2026-10-19-p3-r2-batch-b.md)，计划、实现、自测和独立验收按既有约定自行推进，不逐文稿/执行方式重复审批。

### 9.1 范围与顺序

| 切片 | 配置 | 操作数 | 主要效果 |
| --- | --- | ---: | --- |
| B1 | CORS、lifecycle、local uploads、bucket lock | 3+2+2+3=10 | 浏览器跨域规则、自动删除/迁移等级、近端写入复制、对象覆盖/删除保护 |
| B2 | 自定义域、managed r2.dev | 5+2=7 | 公开访问入口、域名关联、TLS设置及独立ownership/SSL状态 |
| B3 | Sippy | 3 | 读取/停用/启用按需迁移配置，源AWS/GCS/通用S3/Azure，目标R2 |

合计8读12写。完整落地后的精确注册预计99（现有79+20；READ50/WRITE49），A10与B20分别有handler/具体行为测试；C/D20项继续未开始，E1仍待批。只读、演示、官方控制台入口、真实API和未实现能力分列，不把已保存配置说成公网已可访问或对象已迁移完成。

### 9.2 目标、路由、授权与任务

- 桶列表进入“桶设置”，按所选面板懒加载，不一次拉取所有配置。结构化表单，不默认提供任意原始JSON写入。保留对象浏览和默认存储类型快捷路径，避免把所有配置堆进现有长列表。
- 新配置目标显式绑定account/connection、jurisdiction、bucket、配置类别及必要domain。已有`R2WriteTarget`已经包含jurisdiction，不能另造与其脱节的“地区”展示字段。
- 对schema声明支持的18项配置操作加入受控`cf-r2-jurisdiction`头；local uploads的GET/PUT不支持该头且只允许default，非default面板不得退回读取default。枚举保留官方5项；这不声明真实账号具备fedramp等资格，不增加专用主机或网络host准入。
- A现有对象操作仍是default；B非default目标须用对应header独立GET桶证明归属，不能把default列表同名桶当证据，也不静默把B设置目标切回A默认对象页。
- 复用同一R2 runtime的issuer/coordinator/任务store和有界保留；新`R2ConfigController`与客户端分离，避免继续堆大A控制器。default配置与A桶/对象共享桶锁及未结清门禁；不同jurisdiction身份不碰撞。统一可信locator解析/动作集合，旧任务只解释为default，不按任务恢复可执行命令。
- 每次写仍新鲜账户级REST写证明、最终守卫、冻结实际编辑器命令/路由/凭据/目标、预览及显式确认；新增12写动作显式审核，不接受桶级S3授权冒充REST管理。默认DNS/Zone语义不改。
- 所有配置写后独立GET验证；删除须证实缺席/禁用，整体PUT比对完整可观察目标。回读失败/异步受理/矛盾/分页未尽不得标成功，不重发。域名“已保存”“ownership状态”“SSL状态”分开，r2.dev关闭不宣称其他自定义域关闭。

### 9.3 写入形状与未知字段

- CORS、lifecycle、lock的PUT是整组规则替换，预览必须展示增删改及空数组清空影响；提交前重新读取原快照，不做隐式PATCH，也不按ID排序改变规则顺序。无法保证未知/嵌套语义无损时只读，不静默丢字段或猜默认值。
- 域名配置按各字段已证实的保留语义构建请求，不能泛化“所有PUT都整体替换”或“省略都保留”；语义未证实则阻止对应编辑。新域明确发送`enabled=false`、推荐`minTLS=1.2`，避免接口默认公开/旧TLS。已有配置按实值展示，不在打开页面时改动；弱TLS不作为新配置默认，降低安全级别需额外明确选择和确认。
- local uploads只控制新近端写入；关闭后现有复制可继续，不把enabled=false解释为全部复制结束。
- lifecycle年龄字段为`maxAge`秒，lock年龄为`maxAgeSeconds`秒，不混用；日期按明确UTC/RFC3339处理。空prefix影响全桶必须突出提示，重复规则ID、非法/溢出时长、未知枚举拒绝。
- 规则编辑采用客户端上限：完整配置UTF-8 JSON最多256 KiB、规则集合最多100条（原文与目标均受限）；超限保留只读，不截断重发。明确这是客户端安全编辑上限，不是供应商上限，避免64个预览各保留无限大原/目标文档。
- “未配置”、无读权限、解析失败必须区分；不能将任意404/失败视为空配置覆盖。仅按已证实的端点响应语义识别未配置，且桶归属先独立验证。
- 自定义域绑定独立核实zone属于当前账号及domain归属范围；不能只相信输入zoneId或列表成功。不额外调用DNS写接口，不把attach成功当证书/公网就绪；provider显式不可编辑或未知能力保守拒绝。

### 9.4 新增高风险边界（已批准本地实现与合成验证）

1. **公开/删除/保留策略：** 公网开启、域名变更/移除、含删除/终止multipart的lifecycle，以及锁规则变更（含无限期锁定）增加独立风险确认与精确桶名/域名，影响不自动生效于表单打开或默认值。保留锁可影响现有对象，不能承诺删除规则一定立即解除已有保护。此为B对原D5“其余CHANGE”的保守强化，不降低原有高风险要求。
2. **Sippy凭据：** 启用需要源存储凭据及现有R2目标Access Key ID/Secret Access Key；GCS含privateKey，Azure为accountKey/sasToken二选一。不能自动拿本机REST Bearer Token顶替，也不签发临时S3凭据；E仍未批准。产品未来将这些值提交Cloudflare；用户本次已批准该敏感处理功能的实现，未来实际用户仍须逐次最终确认，本任务不提供/使用真实值。
3. **推荐Sippy处理方式：** 每次输入仅内存暂存，预览遮盖，敏感页面防截图/近期任务泄露，不写入plan/task/DB/日志/剪贴板或错误文本，不自动保存便于下次复用。JSON传输/命令`toString`去敏；仅最终确认后单次使用，退场不破坏已接纳传输所有权。JVM不可变字符串等不承诺彻底物理擦除。
4. Sippy GET不回传secret，不能把脱敏值当可重发配置；修改/重启需重新输入。请求指纹用内存凭据句柄/随机binding绑定变化，不持久化可离线比对的secret摘要。未知结果不能以GET公开字段相同证明新凭据已应用，不能重放；正常保存也不宣称源连通性/对象迁移完成。
5. Sippy bucketURL只是提交Cloudflare的配置，App不直接探测任意外部URL，不扩网络host白名单；禁止在URL内夹userinfo/凭据，敏感字段不混入可见地址。四provider只用合成数据测试；当前不要求用户提供任何真实密钥，更不让用户将密钥发到聊天。

### 9.5 验收出口与备选

- 行为RED→GREEN覆盖整组替换/未知只读、false显式发送、目标/地区/凭据漂移零写、跨jurisdiction隔离与default同桶串行、权限未知拒写、风险确认、完整回读、异步/未知不重放和敏感数据去敏/生命周期。
- 平台验证仅新增受控jurisdiction头，无新host；真VM/Compose覆盖各配置面板与Sippy敏感输入，实际可执行的有限视觉与安全窗口检查按证据报告。假凭据不出日志/截图；不把mock当实际套餐/收费/连通性验收。
- 现有A842/UI19/platform97与最终证据先保留，B稳定后按影响范围回归；99精确注册与51=30已实现+20未开始+1待批的机器门禁仅在真正完成后更新，不提前计数。
- **推荐完整20项**，按B1→B2→B3交付并独立验收；真实云/付费/凭据签发仍0。若不批准Sippy凭据处理，可先做非Sippy17项，Sippy3项明确未完成，不能把B记全完成。

本节确认已完成，仅覆盖推荐方案及新增敏感/高风险功能的本地实现与验证；不重问A、批次顺序、文稿或谁来编码，不把此确认扩为真实云/付费/Git操作授权。

## 10. R2-C 作业、指标与事件通知设计（2026-10-24本地工程验收通过）

**状态：** B20已实际通过API36合成本地工程验收；本节推荐完整C8及异步删除/迁移/推送风险获用户“好的，继续”批准。用户追加“删除的操作要多次确认，避免误触”，按10.6落实本批三级确认，不再另问文稿/角色。实施计划见[批次C计划](../plans/2026-10-22-p3-r2-batch-c.md)。当前完整C8已通过API36合成本地验收：107=55READ/52WRITE，51候选=38typed/12NOT_STARTED/1DEFERRED；独立QA原生两字体各10/0及实际85图视觉证据，父已接受本地出口。当前与未测边界见该计划“当前出口”；冻结历史和开发技术矩阵不追写独立资格，以计划/独立报告为验收权威。沿用当前Workspace/branch/全部dirty成果，不Git或发布，不实际调用云。

### 10.1 精确范围与备选

| 模块 | operationId | 读/写 |
| --- | --- | --- |
| 桶作业 | `r2-list-bucket-jobs`、`r2-get-bucket-job`、`r2-create-bucket-job` | 2读1写 |
| 账号指标 | `r2-get-account-level-metrics` | 1读 |
| 事件通知 | `r2-get-event-notification-configs`、`r2-get-event-notification-config`、`r2-put-event-notification-config`、`r2-event-notification-delete-config` | 2读2写 |

推荐**完整C8本地实现与合成验收**，包括两类作业、现有Queue规则增删和指标展示；不是实际发起云端作业。备选为只读C5，或仅开放storageClassMigration而不开放prefixDelete；后者必须明确标出删除模式未开放，不冒充完整双模式作业能力。目标完成后才新增8注册：107=55READ/52WRITE，51候选=38typed/12NOT_STARTED/1DEFERRED；未落地前仍保留B的99/30/20/1。

### 10.2 复用与目标身份

- 新增独立的jobs/metrics/notifications模型、port、typed客户端与控制器，避免把所有新逻辑堆进A/B控制器。接入同一个R2WriteRuntime的issuer、64/TTL保留、coordinator、store、appScope、同桶组锁及未结清门禁；不得单建C锁池。
- 桶作业3项、通知4项均由pinned schema声明`cf-r2-jurisdiction`，显式绑定account/connection/jurisdiction/bucket以及必要jobId/queueId；沿用default/eu/us/fedramp/fedramp-high。指标仅account级，不带桶或jurisdiction头，不把账号汇总冒充当前桶/地区数据。
- 3写分别加入经审核的REST授权白名单，仍需新鲜账户级Workers R2 Storage Write证明、真实live editor/route/credential/target、冻结预览及最终守卫；S3桶级权限、locator、task和人工前置确认均不能代替执行授权。
- 新网络业务头仅限job创建中的受控`cf-r2-data-catalog-check`保护用途，不增host、不扩S3/Queues写API、不复活A弃用的DELETE-prefix路径。固定头值与作用按pinned契约验证，不接受用户任意header map；无法保证保护请求时拒发对应删除作业。

### 10.3 桶作业与不可逆风险

- 创建严格区分`prefixDelete`和`storageClassMigration`。前者非空prefix必须以`/`结尾；空prefix为单独的“清空整个桶”模式，不由空白表单默认推导。后者始终明确发送不同的source/destination（Standard/InfrequentAccess），不依赖省略双方后的默认反向迁移。
- 前缀删除/清桶需精确桶名、完整prefix（或明确全桶）、独立不可逆风险确认；转类需确认全桶范围及可能产生的存储/读取/最低保留期成本，不估算未获证实的价格。预览冻结的是匹配条件而非逐对象名单；作业启动前新增的匹配对象也可能受影响，启动后新增对象不在该作业范围。
- **Multipart边界：** pinned说明要求先中止活跃分片上传并避免作业期间写入。本批无S3 multipart列举/中止能力，不偷偷扩P5，也不自动删除通知来绕409。界面要求未来操作者明确确认已在外部处理中止/暂停写入；这是人工前置声明，**不是App已查明没有MPU的证据，更不是REST授权证明**。缺确认拒发；不能承诺桶一定保持为空、没有残留分片或不会有其他客户端写入。当前任务只模拟这些状态，不要求现场执行。
- 创建前独立GET桶、检查当前已知作业和相关通知配置；job列表每页最多1000、最多5页为客户端安全预算，不是provider总量承诺，带status过滤必须同时有jobType；分页未尽、重复cursor、未知jobType/status、归属不符、超客户端有界预算均不可当作“无在途作业”。本App同桶冲突写入统一串行/拒绝，不宣称能锁住其他客户端。通知冲突/429/409只呈现、保留真实拒绝或不确定证据，不自动去配置、不排队重发POST。
- 每个POST创建不同作业，因此**写请求零自动重试**，超时/取消/响应丢失不得再次提交。缺可信jobId时保持UNKNOWN，不按时间、prefix或列表里“最像的作业”关联或补发。
- 可信创建回执中的jobId只保存为范围受限的只读poll locator，不保存payload/确认或重建命令。已受理/运行中保留ACCEPTED，关联同一作业独立GET；主界面默认手动刷新，无永久后台轮询。退出页面不取消供应商作业，本批也没有伪造的远端“取消”按钮。
- 终态必须验证同account/jurisdiction/bucket/jobId、jobType及原目标参数；未知/矛盾/部分失败不能标全量成功，FAILED/CANCELLED不等于从未发生副作用，不重放或强解锁。COMPLETED只按充分可观察证据报告对应作业完成；迁移失败/跳过计数缺省不得擅填0。清桶作业完成不等于现在桶为空、MPU已清理或之后不会新增对象。指标或相似公开元数据不能证明未知写成功。

### 10.4 通知规则的真实语义

- 仅接既有同账号Queue，不创建Queue或消费者，不索取新Token。通过新鲜R2配置GET核对桶/Queue身份与完整规则；queueId不符、缺关键字段、未知actions/规则语义、404/403/失败均不当空配置。若服务不能返回可验证的既有Queue配置，拒写并说明限制，不靠输入ID或显示名称冒充归属证明。
- pinned的PUT是**追加新规则**，不是更新/替换既有规则。表单只发送新rules，至少一条、每条至少一个已知action且不重复；仅用PutObject/CopyObject/DeleteObject/CompleteMultipartUpload/LifecycleDeletion。prefix/suffix按原字面值，不trim、解码或改对象key；缺省/空过滤会覆盖全部对象，必须明确展示。
- 客户端沿用256KiB/100规则编辑预算（原配置、增量及预期结果均检查；不是供应商上限）。无法完整理解的配置只读，不截断重发、不为消除重叠自动删改旧规则。请求前再读before；成功后独立GET验证新规则及原规则未被意外丢失，不能只看HTTP200空结果或description相同就成功。
- 删除分开“删除选中的规则ID”与“清除此桶/此Queue全部规则”。选中删除必须有非空、去重且确在新鲜before中的ruleIds；不把空列表/序列化丢字段当清空。全清走明确无body的API语义，独立破坏性确认及精确桶/Queue目标，不静默退化。
- 新增通知必须确认对象事件元数据会推给Queue、可能触发既有消费者与费用；删除/清空确认可能中断现有事件处理。写后验证只证明配置增删，不证明消息送达、消费者执行或计费资格。

### 10.5 指标、界面与验收

- 账号指标按standard/infrequentAccess、uploaded/published及payloadSize/metadataSize/objects原字段展示，缺省/未知不补0；不擅自把浮点schema截成整数、溢出成无穷、相加为已证实总量或猜单位。没有数据时间戳就只标本次获取时间；明确provider统计可能延迟，不能作为删除/迁移结算或配额/账单证明。
- 桶入口分“后台作业”“事件通知”，账号入口提供账号指标；按需加载，不把B敏感页重做。复用预览/确认/任务详情/只读刷新，切目标/地区/凭据/路由撤销旧草稿确认，错误/UNKNOWN与旧结果不被reload吞掉。
- 先按pinned契约测试两作业分支、7个支持jurisdiction的端点（五种既有辖区）与账号级指标；行为RED覆盖POST零重试、已受理与真实完成区分、jobId丢失、旧任务不复活、跨A/B/C同桶门禁、追加不替换、选删/全清不同body、错误不当空、目标漂移零写。未知/超限状态保持只读或不确定。
- 稳定后按受影响范围做core/UI/platform/app/native回归及实际两字体关键交互/视觉检查，保留真实命令/环境/stdout/全部选中XML/退出码/源码hash；正常缓存漂移实验无强制rerun，外部输入全部声明，负例原件保留。独立验收返回通过/不通过/阻塞；历史B不回写，不把分轮通过拼成单轮总数。
- 保留API26–28实际版本未测、frame提交/物理擦除等B既有边界；C合成本地通过也不授予真实云、付费、Git、发布或E1权限。设计新增风险合并确认一次后，计划、角色、实现、自测、普通修复与独立验收由主控内部推进，不再逐文稿批准。

### 10.6 删除防误触（2026-10-22用户追加，直接执行）

本批prefixDelete（含空前缀清桶）、通知规则选删和全清统一**三级确认**，不是连续点同一个按钮三次：

1. 范围/风险阶段：明确account、jurisdiction、桶、完整prefix或显著“整桶”、Queue/选中规则ID，人工确认不可逆影响；清桶、通知全清都无默认选中。相关MPU外部处理声明仍独立于授权与删除确认。
2. 手动核对阶段：精确输入桶名，并展示前缀/Queue/规则身份；不自动填入确认文字，不trim或大小写容错，未匹配不能进入最终提交。预览冻结范围，回读before和最终授权守卫不因已输入桶名而省略。
3. 最终独立弹窗：再次显示精确范围与后果，有明确取消；最终按钮不默认聚焦/不由IME回车提交，与上一步危险按钮不沿用同一触点，连续点击不得穿透。仅此处单次接纳写任务，写入中禁重复动作；重复确认也不能重放。

任何草稿/选项/规则选择/目标/地区/route/credential变化、取消或离开页面都撤销本次三级确认；旧弹窗回调不得确认新草稿。确认阶段属于当前真实编辑器的内存状态，不从SavedState/任务/plan恢复，也不代替新鲜REST证明。真VM/Compose需证明只做前两步0write、输入错误0write、旧弹窗/凭据漂移0write、取消0write、连续点击至多1write；各删除模式在两字体下范围与取消/确认可见。既有A/B删除保护不能因共用预览/确认接线而倒退，不扩改无关产品的删除流程。
