# Cloudflare 接口缺口盘点与全覆盖开发方案（评审草案）

盘点日期：2026-10-07。当前用途：需求评审、范围决策与分批排期；**不是已批准执行的实施规格**，本轮不开发产品功能、不调用真实云写接口。

进度更新：用户已同意继续细化本方案；现已形成 [P0–P2实施计划总览](superpowers/plans/2026-10-07-cloudflare-p0-p2-index.md) 及三份分阶段计划，等待对这些新计划的评审确认。不得把这次同意解释为已批准全量产品实现或真实云写操作。

## 1. 目标、基线与结论

用户目标：列出 FlareGo 还未支持的 Cloudflare 接口／功能，并形成最终覆盖全部能力的开发方案和计划。

本草案的范围假设：Cloudflare 官方公开管理 API，包含 REST 与需要独立适配的 GraphQL、R2 S3 等协议。公开定义不等于所有账号都能调用；Enterprise、Alpha、Beta、已弃用、需要专用凭据以及没有公开 API 的功能单独管理。云服务本身的执行环境、隧道节点安装和第三方 IdP 运维，不因为接入管理 API 就变成 App 内可运行的能力。

### 1.1 可复核基线

- FlareGo 源码：`bb1366de46c03e482d8d98ab25b7dde4abb93f15`。
- Cloudflare 官方 OpenAPI：`cloudflare/api-schemas`，提交 `6b00f157496cd6a1792a613dc94654c51273aeb9`，提交时间 `2026-10-07T14:45:01Z`。
- [固定版本 Schema](https://raw.githubusercontent.com/cloudflare/api-schemas/6b00f157496cd6a1792a613dc94654c51273aeb9/openapi.json)。文件 SHA-256 与统计口径见 [snapshot.json](cloudflare-audit/snapshot.json)。
- 未使用真实账号、Token 或付款信息；源码检查与已有隔离测试不是云端端到端验收。

### 1.2 数量结论

| 项目 | 数量 | 含义 |
| --- | ---: | --- |
| REST 路径模板 | 2,298 | 一个路径可有多个 HTTP 方法 |
| REST 操作 | 3,659 | 按 `METHOD + path template` 唯一计数 |
| 官方标签 | 582 | API tag，不等于 582 个独立产品 |
| 当前已接入操作 | 11 | 8 个 GET，3 个写操作；多数只覆盖部分参数／字段 |
| 尚未接入操作 | 3,648 | 包含遗留、受限及尚待确认可用性的端点 |
| 显式 `deprecated=true` | 335 | 不应当作新功能优先接入 |
| 含任一弃用信号 | 432 | 合并 deprecated、SDK availability 与 x-cfDeprecation；其中 97 个没有显式 deprecated=true |

当前“命中端点”的比例约 **0.30%**。这不是产品完成度，也不能表示其余 3,648 个都是普通 Token 可立即调用的公开稳定接口。

官方元数据存在冲突：例如 Billable Usage 的摘要写 Alpha／Restricted，而 SDK availability 写 generally-available。因此保留原始证据，逐端点判定，不用单个字段自动宣布 GA。`x-forge-hidden`、`x-fern-ignore` 是工具链标记，也不能直接等同于内部接口或不可用。

6 个操作各有两个 tag，因此按标签汇总共有 3,665 次归属；标签行不能相加作为唯一端点总数。

### 1.3 交付清单

1. [endpoints.csv](cloudflare-audit/endpoints.csv)：**全部 3,659 个 REST 操作**，逐项列出方法、路径、operationId、产品标签、当前支持状态、支持限制、弃用信号、权限／套餐原始元数据、请求／响应媒体类型。
2. [products.csv](cloudflare-audit/products.csv)：**全部 582 个官方标签**，各自总数、部分接入数、未接入数与弃用数量。
3. [snapshot.json](cloudflare-audit/snapshot.json)：基线、Schema 提交、校验和、统计与限制。
4. [r2-s3-operations.csv](cloudflare-audit/r2-s3-operations.csv)：官方兼容表中的 73 个 S3 操作，其中 Cloudflare 支持 26 个、FlareGo 均未接入；另 47 个是 Cloudflare 本身不支持，不能列为 FlareGo 可实现能力。
5. 本文：面向功能的缺口列表、推荐架构、全覆盖路线、近期开工计划与验收标准。

CSV 的 `implemented_subset` 只表示源码已有调用路径；**没有任何行被标记为“真实账号全量验收通过”**。`availability_review` 和 `implementation_review` 目前均待评审；CSV 不是可直接执行的请求白名单。

## 2. 当前真实支持到什么程度

证据主要来自：

- `shared-core/.../cloudflare/CloudflareSession.kt`：实际请求端点与映射字段。
- `shared-core/.../application/DnsValidation.kt`：DNS 可写类型与约束。
- `shared-core/.../ports/Ports.kt`、`model/Models.kt`：当前仅目录、DNS、账单端口，资源类型仅 Workers／R2／D1／KV。
- `shared-ui/.../Screens.kt`、`Purchase.kt`、`Forms.kt`：详情、演示购买与控制台入口。
- `android-platform/.../CloudflareClient.kt`：固定主机、Bearer、JSON 单次写入、无自动重试。
- `shared-core/src/commonTest/.../CloudflareTest.kt`：已有隔离测试。

表中路径均省略 `https://api.cloudflare.com/client/v4`。

| 真实操作 | 已有能力 | 尚缺内容 |
| --- | --- | --- |
| `GET /accounts/{account_id}` | 连接时验证账号可读取 | 账号发现、完整详情、成员、角色、Token 生命周期 |
| `GET /zones` | 当前账号过滤、分页、列表 | Zone 详情／创建／修改／删除、激活、套餐与设置 |
| `GET /accounts/{account_id}/workers/scripts` | 脚本目录、修改时间 | 新 Workers 模型、源码、设置、版本、部署、路由等；此列表已有 SDK 弃用信号 |
| `GET /accounts/{account_id}/r2/buckets` | 桶目录、部分元数据 | 桶详情、jurisdiction、对象、策略、生命周期、迁移等 |
| `GET /accounts/{account_id}/d1/database` | 数据库目录 | 详情、SQL、导入导出、Time Travel、增删改 |
| `GET /accounts/{account_id}/storage/kv/namespaces` | 命名空间目录 | 空间管理、键与值、metadata、TTL、批量操作 |
| `GET /zones/{zone_id}/dns_records` | 基础记录列表 | 完整结构化字段、服务端筛选、单记录详情等 |
| `POST /zones/{zone_id}/dns_records` | 新增 A／AAAA／CNAME／TXT／MX | 其余官方记录类型及完整字段 |
| `PATCH /zones/{zone_id}/dns_records/{dns_record_id}` | 编辑上述五类的部分字段 | 结构化 data、comment、tags、settings；完整更新语义 |
| `DELETE /zones/{zone_id}/dns_records/{dns_record_id}` | 基础删除与确认流程 | 独立真实账号验收、变更后核对与更完整冲突处理 |
| `GET /user/billing/history` | 用户级历史账单 | 当前账号级账单、订阅、用量、发票；该接口显式弃用 |

**不计入真实 API 支持：**演示购买、演示 R2 对象目录、示例趋势、演示用量与预算；打开 Cloudflare 控制台；应用自己的更新与本机操作记录。

资源“详情”目前展示的是 inventory 目录返回的元数据，没有调用 Worker／R2／D1／KV 的详情接口。列表搜索主要发生在已下载数据上，不等于覆盖官方服务端搜索能力。

DNS 列表能容纳其他类型的基础信息，但写入校验只允许五类；因此不是“所有 DNS 类型已支持”。

## 3. 未支持功能列表（产品级）

以下是便于讨论和排期的功能分组。**穷举清单以 endpoints.csv 与 products.csv 为准**；本表不会把所有 582 个 tag 错当成独立产品，也不会遗漏冷门端点的待办归属。

### 3.1 已有模块的深度缺口

| 模块 | 未支持功能 | 主要风险／边界 | 批次 |
| --- | --- | --- | --- |
| 账号与凭据 | 可访问账号发现、Token 验证／轮换／吊销、账号／组织详情、成员、角色、用户组、资源共享、审计 | User／Account／Organization 作用域不同；列表权限不能推断写权限 | P1、P8 |
| Zones | 创建／删除／编辑、激活检查、暂停、Zone Holds、权限与套餐、完整设置 | Zone 不等于 Registrar 域名注册 | P2 |
| DNS | 官方全部记录类型、结构化字段、完整详情／替换、批量、导入／导出、异步扫描与审核、用量、DNSSEC、DNS 设置 | 保留未知字段；按最新 Schema 处理类型；批量传播不应被描述为全球原子事务 | P2 |
| 高级 DNS | Secondary DNS、TSIG、Peer、ACL、内部 DNS Views、DNS Firewall、自定义 Nameservers | 多为特殊套餐／产品范围，不默认展示可写按钮 | P8 |
| Workers | 新 Workers 列表与详情、源码导入导出、上传／部署、版本与流量分配、回滚、bindings、环境、secret、routes、custom domains、subdomain、Cron、builds、tail、profiling、observability | 上传有 multipart；secret 值通常不能读回；版本回滚不等于回滚数据库／资源 | P3 |
| R2 | 桶增删改、对象浏览／上传／下载／删除、metadata、CORS、生命周期、锁定、存储等级、公共域名／托管域、Sippy／Super Slurper、bucket jobs、catalog | REST 对象接口已存在；大文件／分片另用 S3；jurisdiction 必须进入 scope | P3、P5 |
| D1 | 数据库详情与增删改、参数化 SQL、raw query、结果分页、导入导出、Time Travel bookmark／restore | SQL 可以破坏数据；查询不应只按 POST 判断写入；导入导出是多步任务 | P3 |
| KV | 空间创建／改名／删除、keys、value、metadata、到期时间、批量读／写／删 | 最终一致性；二进制 value；批量部分失败 | P3 |
| Billing | 账号历史、发票／收据 PDF、余额、订阅／套餐／权益、计费资料、支付方式、用量与成本来源 | 用户账单不能归入单个账号；Alpha／Restricted 用量不保证返回金额；支付合规另行评审 | P1、P4、P8 |

### 3.2 尚未建立的产品模块

| 模块 | 未支持功能 | 批次 |
| --- | --- | --- |
| Registrar | 域名搜索、权威可用性／价格校验、扩展能力发现、注册、状态轮询、续费设置、锁定／隐私／联系人、转入及状态；官方 sandbox | P4 |
| Pages | 项目、部署、产物上传、部署状态／日志、回滚、域名、构建缓存 | P3 |
| Cache／CDN／Performance | 清缓存、缓存规则、Tiered Cache、Cache Reserve、Argo、Smart Shield、URL normalization、Origin Cloud Regions、Observatory、相关 Zone Settings | P2、P5 |
| SSL／TLS／Certificates | 加密模式、Universal／Advanced／Custom SSL、证书包、Origin CA、mTLS、Authenticated Origin Pull、Keyless、per-hostname TLS、DCV delegation | P2、P5 |
| Rules／WAF／Bots | Account／Zone Rulesets、托管／自定义 WAF、Rate Limiting、Transform／Redirect／Origin／Cache Rules、Lists、IP access、DDoS、Bot、泄露凭据、Security Events | P5 |
| API Shield／Client-side Security | 端点发现、Schema validation、token validation、客户端证书、内容扫描、浏览器端风险与相关设置 | P5、P8 |
| Email Routing／Sending／Security | 转发目的地址、规则、Catch-all、启停、邮件发送、子域、suppressions、Email Security／Settings | P5、P8 |
| Load Balancing／Health Checks | pools、monitors、groups、区域、负载均衡器、健康检查、事件 | P5 |
| Tunnel／Zero Trust Access | Tunnel 生命周期／配置／路由／虚拟网络、hostname routes、Access applications／policies／groups／IdP／service tokens／mTLS／SSH | P6 |
| Gateway／WARP／Devices | DNS／HTTP／网络策略、locations、lists、proxy、设备注册与 posture、managed networks、用户／seats／风险评分 | P6 |
| DLP／CASB／DEX／Infrastructure Access | DLP profiles／datasets／entries、集成、应用风险、设备体验测试与诊断、targets、基础设施访问 | P6 |
| Queues | 队列、producer／consumer、消息发送／拉取／确认、设置与状态 | P7 |
| Durable Objects | namespace／对象目录与可用元数据、指标；运行时数据操作仅按实际公开能力设计 | P7 |
| Workflows | workflow 配置、实例、状态、控制动作及用量 | P7 |
| Containers | 应用／镜像／registry／实例配置与状态、部署相关控制面 | P7 |
| Hyperdrive | 连接配置、凭据与管理；不把它当作通用 SQL 控制台 | P7 |
| Vectorize | 索引、向量 upsert／query／delete、metadata indexes、mutation 状态 | P7 |
| Workers AI／AI Gateway／AI Search | 模型／推理、finetune、gateway 配置／日志／成本／路由／datasets／evaluations、搜索实例／命名空间／索引任务；AutoRAG 与新命名兼容 | P7 |
| Pipelines／Secrets Store／Artifacts／Analytics Engine | pipelines 与关联数据服务、secret 生命周期、产物、SQL／分析；按公开控制面与数据面分别适配 | P7 |
| Images／Stream | 图片与变体、签名／密钥、上传／下载；视频、live inputs、字幕、音轨、剪辑、水印、签名、Webhook 与播放指标 | P7 |
| Browser Rendering／Realtime | browser API、截图／PDF／抓取等已公开接口；Realtime SFU／TURN／Kit、meetings／recordings／livestreams／MoQ 等管理与专用协议 | P7、P8 |
| Logs／Analytics／Notifications | GraphQL、HTTP／DNS／产品指标、Logpush／Logpull／Log Explorer／Instant Logs、订阅通知、webhooks、告警静默与历史 | P4、P5、P8 |
| Magic WAN／Transit／Spectrum | sites、connector、GRE／IPsec、路由、BGP、IPAM、网络监控、PCAP、interconnect／CNI、Spectrum 应用与分析 | P8 |
| Radar／Intel／Security Center／Cloudforce One | 公共互联网指标、威胁情报、URL Scanner、漏洞与资产发现、事件／调查／feed／Brand Protection | P8 |
| Cloudflare for Platforms／SaaS | Workers dispatch、租户、Custom Hostnames、fallback origin、resource sharing、组织管理 | P8 |
| Zaraz／Web Analytics／Waiting Room／Web3 | 第三方工具配置、站点分析、排队室与事件、相应公开主机接口；遗留功能先核验是否仍可用 | P8 |
| Monetization／AI Audit／Pay per Crawl | 按公开端点管理爬虫、定价、报表、订阅与相关配置；不扩展为未经授权支付能力 | P8 |
| 尾部标签与新产品 | catalog／table maintenance、abuse complaints、diagnostics、未能可靠归类的通用标签及后续新增端点 | P8、P9，禁止因名称模糊直接跳过 |

### 3.3 REST 数量不能代表的接口缺口

| 接口体系 | 当前缺口与目标 | 穷举程度／边界 |
| --- | --- | --- |
| GraphQL Analytics | `/client/v4/graphql` 尚未接入；HTTP／缓存／DNS／安全事件、Workers／存储／媒体／Zero Trust／网络等可访问 datasets 的查询、筛选、聚合、时间窗口与展示 | 本轮没有授权 schema introspection，未穷举全部字段；P0 单独冻结 dataset／field 矩阵。官方明确这不是计费用量依据 |
| R2 S3 桶操作 | ListBuckets、HeadBucket、CreateBucket、DeleteBucket、DeleteBucketCors、GetBucketCors、GetBucketLifecycleConfiguration、GetBucketLocation、GetBucketEncryption、PutBucketCors、PutBucketLifecycleConfiguration | 官方已支持的 11 个操作，App 全部未接入；每个操作内部仍有不支持参数 |
| R2 S3 对象操作 | HeadObject、ListObjects／V2、Get／Put／DeleteObject、DeleteObjects、CopyObject | 官方已支持的 8 个操作，App 全部未接入；优先 V2；不承诺 S3 Versioning／ACL／Tagging 等未支持能力 |
| R2 S3 分片 | ListMultipartUploads、Create／Complete／AbortMultipartUpload、UploadPart、UploadPartCopy、ListParts | 官方已支持的 7 个操作，App 全部未接入；进程恢复前核对 upload ID 和已上传 part，不盲目重传 |
| SQL／NDJSON／SCIM 等特殊媒体 | Analytics Engine SQL、Vectorize／Pipelines 等数据上传、SCIM 管理的媒体类型及错误处理 | 部分端点已在 REST 清单，不重复累计；协议支持仍是独立工作包 |
| WebSocket／SSE／实时协议 | Worker tail、实时会话、推理流等；建立会话的 REST 与实际连接／消费分开实现 | 本轮没有逐帧协议全量清单；按官方具体产品协议补盘，不以 GET／POST 接入代替实际流处理 |
| Workers binding／RPC 与外部 catalog | DO、Queues、R2、D1 等运行时绑定，Iceberg／catalog 等专用协议 | binding／RPC 不是可供手机客户端随意远程调用的统一管理 API；需要额外桥接时独立评审部署与授权 |

S3 操作清单取自官方文档仓库固定提交 `e3a9cf758c28273178d56918399068c6d2eb8a1d`；原始来源与 SHA-256 见 snapshot.json。REST 穷举与 S3 兼容表已经完成，GraphQL 全字段及专用实时协议穷举仍待授权与 P0 核验，不声称本轮已经列完一切非 REST 接口。

### 3.4 归属、批次与资格台账（P0 复核）

[endpoints.csv](cloudflare-audit/endpoints.csv) 只回答“官方有哪些操作”；[review.json](cloudflare-audit/review.json) 回答“谁负责、属于哪个批次、当前资格如何”。台账按 `operationId` 索引全部 3,659 个操作，每项固定 14 个字段：`product_id`、`phase`、`owner_role`、`scope_kind`、`protocol`、`semantics`、`risk`、`pagination`、`availability_decision`、`deprecation_decision`、`target_level`、`test_prerequisites`、`needs_review`、`evidence`。

- `scope_kind` 只描述 API 授权／资源作用域：`account`／`zone`／`user`／`organization`／`global`／`principal`／`unknown`；源码支持状态单独放在 `evidence.source_support`（`source_subset`／`unimplemented`）。无法确定作用域时记 `unknown` 并保留证据，不默认成 `global`。
- `phase` 是剩余产品工作的长期批次；`evidence.approvals` 才表示当前已批准的实现清单。两者分离，避免某个 tag 的默认归属被当成整个产品提前开工的许可。
- `semantics` 只由 HTTP 方法给出提示：POST 一律为 `unknown`，因为参数才决定读写；未审核的写入保持阻塞。
- 台账是审计数据，不是运行时白名单；`availability_decision` 不是写许可，运行时放行仍只来自 P1 手写策略。

当前 3,659 项全部是 `needs_review` 的机器提议（`proposed_not_approved`），没有用户逐项批准：

| 状态 | 数量 | 说明 |
| --- | ---: | --- |
| registered | 3,659 | 全部操作都有产品、批次、负责人、目标与证据 |
| target（L2／L3） | 3,659 | 未获批准排除，也没有 L0-only 项 |
| implemented（源码子集） | 11 | 仍是部分参数／字段支持，不等于完整支持 |
| verified_available | 0 | 无授权账号，无真实验收记录 |
| capability_verified（L2／L3） | 0 | 接口资格可用不等于 FlareGo 能力验收 |
| legacy／migration | 432 | 保留弃用证据与迁移决策，不自动缩成 L0 |
| blocked | 3,001 | 权限元数据缺失、Forge hidden 待审、写入风险待审、尾部长尾族资格待审等 |
| unclassified-review | 0 | 尾部家族已分到具体责任包；未来未知 tag 仍会落入该回退包 |

已批准的当前批次：P1 新增请求 5 项、P1 既有兼容 11 项、P2 候选 54 项（见 [p2-candidate-endpoints.csv](cloudflare-audit/p2-candidate-endpoints.csv)）；其余 3,594 项标记 `future_batch_not_yet_approved`。作用域分布：account 2,563／zone 693／global 281／user 54／unknown 32／principal 20／organization 16；批次分布：P1 16／P2 89／P3 254／P4 181／P5 552／P6 635／P7 496／P8 1,436。阻塞与验证数由 catalog 事实重新推导，不能靠删除 `evidence.blockers` 洗白；缺失或未清除的账号证据一律视为未知／阻塞，只有带引用与记录的显式清除才生效。

P8 尾部长尾族已按固定 schema 的 route family 分到具体责任包：`field-extractors-alpha`（3）、`account-reporting-control-plane`（6）、`k2-streams-beta-control-data`（14，其中 6 项 account 作用域控制面、8 项独立 stream 主机数据面保持 `unknown` 作用域并记录 host 证据）。这只是责任归属，不代表厂商资格、套餐或用户排除已批准。

## 4. 必须纠正的 API 边界

### 4.1 Registrar 不再只是“没有真实注册 API”

固定 Schema 已收录 `/registrar/domain-search`、`/domain-check`、`/extensions`、`/registrations` 以及 `/registrar-sandbox/...`；旧 `/registrar/domains` 已显式弃用。

推荐流程：搜索候选 → 实时检查可用性与价格 → 收集联系人／协议与支付准备状态 → 预览 → 用户明确确认 → 提交一次 → 处理 201／202 → 查询官方 workflow 状态。

- `domain-check` 使用 POST，但文档明确是只读操作，不应标记为扣款或域名预留。
- Schema 明确单账号域名数量限制、Premium 不支持、扩展能力需要动态发现、自动续费是显式授权。
- Schema 支持默认联系人回退，也明确提供缺少默认联系人时传入 contacts.registrant 的路径；产品 Beta 指南却仍将默认联系人列为前提。须验证这处版本差异，不把任何一方摘要硬编码成未经验证的规则。支付资料与注册协议准备仍需遵守官方要求。
- 接口目录与产品指南可能不同步；转入、联系人更新、续费等逐项用 sandbox／授权账号核验。
- 实际扣款注册不能作为普通自动化测试；报价变化后必须重新确认，不因超时自动重新下单。

### 4.2 账号级账单存在，但“实时费用全覆盖”仍不能承诺

`GET /accounts/{account_id}/billing/history` 已在 Schema 中，有 `#billing:read` 与套餐元数据。应优先试点该接口，逐步替代当前用户级历史，而不是简单修改页面标题。

账号历史、发票、订阅、用量、成本预测是不同能力与模型。Billable Usage V1 摘要写 Alpha；V2 摘要写 Alpha／Restricted，描述明确**成本与价格字段暂未填充**。即使 SDK 字段写 GA，也不能宣称能获得全部实时费用。

金额使用十进制字符串／Decimal；缺失金额显示未知，不按 0 处理，不用用量 × 想当然单价冒充实收。

### 4.3 R2 对象 REST 与 S3 都要规划

官方 REST 已有对象 list／get／put／delete／bulk delete，不必把所有对象功能都强制依赖新增 S3 凭据。

但固定 Schema 明确 REST 上传上限 **300 MB**，大多数工作负载推荐 S3 或 Workers binding。计划采用：

- 第一批：Bearer REST 对象浏览、小文件流式上传下载、基础删除。
- 第二批：独立加密保存 S3 Access Key／Secret，支持 multipart、copy、presigned 等 Cloudflare 实际支持的 S3 子集。
- R2 并非完全支持全部 Amazon S3 API；以官方兼容表逐操作验收。
- REST object key 中的 `/` 必须保留，不能直接套现有 `encodePath()` 全部转为 `%2F`。
- bulk delete 可在 HTTP 200、success=false 时已经删掉部分对象，且错误不一定标识失败键；必须逐项核对，不能整体宣布“未执行”。

### 4.4 Workers 需要新旧模型迁移

当前 scripts 列表只有 SDK availability 弃用信号，没有显式 deprecated=true；这属于需要核查的迁移项，不直接宣布已下线。

新增 `/workers/workers` 模型、worker ID、旧 script name 与 version／deployment 关系需明确映射。先验证新列表的真实权限与返回形状，必要时提供显式兼容路径；不能简单换 URL 后继续使用旧 DTO。

## 5. “全部支持”的可验收定义

不能把“全目录”“能发请求”“可用功能”“深度原生界面”混成一个指标。

| 等级 | 定义 | 能否对外宣称已支持功能 |
| --- | --- | --- |
| L0 已登记 | 目录中有端点、权限证据、官方状态与计划归属 | 否 |
| L1 协议接入 | 参数、body、响应、分页、错误、安全策略的契约测试通过 | 只能称技术接入，不能称真实账号验收 |
| L2 可操作能力 | 存在受控原生通用表单／结果页、操作计划、确认、核对与授权账号验证 | 可称该操作已支持，标注套餐／预览与测试范围 |
| L3 深度管理 | 专用产品 UI、跨端点业务流程、批量与文件／异步任务、完整产品验收 | 可称该产品模块深度支持 |

推荐最终目标：

1. 全部 REST 操作达到 L0，且无“未归类就算完成”的尾部黑洞。
2. 经确认仍公开可用的稳定／预览操作达到 L2；高频产品达到 L3。
3. Enterprise／专用凭据操作也有实际适配与受控 UI，但必须具备授权测试账号后才能完成对应验收；没有账号的保持阻塞，不能偷偷排除分母。
4. 弃用接口：提供必要兼容或迁移说明，不把已移除端点伪装成可支持功能。
5. 无公开 API 的 Dashboard 功能：明确 `console_only`，记录原因；控制台入口不计入 API 支持率。
6. GraphQL、S3、SCIM／Realtime 等独立协议另建覆盖矩阵，不能拿 REST 数量代表它们。
7. 如果要求每一个产品都具备 L3，继续拆成独立子项目；不是一个单体“全能资源详情页”可以交付的任务。

总体达标必须同时报告：原始目录数、经评审目标数、真实实现数、验证数、遗留数、外部阻塞数，以及所有排除理由。未经用户确认，不缩小“全部”的范围。

## 6. 可选路线与推荐方案

### A. 全手写专用 UI

每个产品都手写 DTO、调用、页面与流程。体验最好，但数千端点容易遗漏，维护官方变化的成本最高，首次覆盖时间最长。

### B. 仅做 Schema 驱动 API 浏览器

快速覆盖目录与参数编辑。但 multipart、二进制、WebSocket、异步工作流、危险写入和产品流程仍需要定制；仅能发请求不能满足“全部功能支持”。不推荐作为最终交付。

### C. 受控协议基础层 + 产品模块 + 通用原生能力页（推荐）

- 固定 Schema 生成端点目录、DTO 与契约基础，按产品分片，不把 27 MB Schema 直接打包为运行时解释器。
- 常用模块提供专用 Compose UI；长尾提供发布时注册、带权限／风险策略的通用原生表单与结果页。
- 单独实现 REST 非 JSON、GraphQL、S3、长连接与任务协议。
- 高风险动作必须经过手写策略与核对器审核；**生成端点不等于自动允许执行**。
- 不下载云端任意插件、不提供可携 Token 请求任意 URL 的“万能控制台”。

这延续现有 Ports & Adapters 架构，又解决只有目录、没有操作端口的问题。

## 7. 目标架构与改造范围

### 7.1 模块边界

```text
shared-core/
  model/                 Scope / ResourceRef / Capability / ChangePlan / Operation / Money
  ports/                 Identity / Zone / DNS / Registrar / Workers / ObjectStorage
                         KV / SQL / Rules / Certificates / Analytics / Billing ...
  application/           读取、计划、确认、执行、核对；按产品分控制器
  cloudflare/
    catalog/             固定端点与权限／风险元数据
    generated/           按产品分片的 DTO 与协议绑定
    identity/ zones/ dns/ registrar/ workers/ pages/ r2/ d1/ kv/ ...
    protocols/           REST / GraphQL / streaming contracts
android-platform/
  cloudflare/            OkHttp、上传下载、WebSocket／SSE、专用主机策略
  credentials/           Bearer、S3、服务密钥的隔离加密保存
  operations/            SQLDelight 任务持久化、WorkManager 生命周期适配
shared-ui/
  navigation/            能力目录与搜索，不把所有产品塞入底部导航
  products/              独立原生产品页
  generic/               审核过的表单、JSON／表格／文件结果页
```

目录是提议，不要求一次建立所有 Gradle 模块。先按包拆分，达到构建／依赖隔离需要时再拆模块。

### 7.2 必须先修的共性短板

1. **Scope 建模**：connection、user、account、organization、zone、region／jurisdiction 独立；资源 ID 加上 scope，不用名称决定全局唯一性。
2. **能力状态分开**：实现状态与账号可用状态正交。可用状态至少 unknown／available／permission_required／plan_required／credential_required／restricted／console_only／removed／temporarily_unavailable。
3. **权限发现**：静态权限信息 + 真实最小只读探测；部分端点权限元数据缺失时不默认授予。验证 Token 不表示列出了其全部权限。403 不直接断言“就是套餐不足”。
4. **Transport 改造**：请求 headers、合法主机／协议、body 类型、返回 headers／bytes／stream、204／304／202、超时与限流；继续保留凭据主机隔离、禁重定向泄漏与禁写重放。
5. **不强制统一响应信封**：JSON envelope、raw JSON、文本、文件、流分别解析；不是每个成功响应都有 success/result。
6. **分页策略独立**：page／cursor／offset／link／产品自定义游标，按需加载。当前 inventory 每次串行抓全部五类且上限 100 页，不适合扩到全产品。
7. **操作语义不能由 HTTP 方法决定**：GraphQL、SQL、domain-check、usage query 的 POST 可以是只读；批量的 2xx 可能部分成功。为每个操作定义风险与重试策略。
8. **持久化任务**：queued／running／accepted／succeeded／partial／failed／unknown／reconciling／action_required；保存 request ID、资源键与安全的核对信息，进程死亡后不重放未知写入。
9. **前台与后台职责**：短只读在前台；长上传／轮询使用 Android 可恢复任务。WorkManager 不保证精确实时执行；tail 日志默认前台连接，不靠后台常驻承诺全天在线。
10. **变更计划**：读最新状态、生成差异、显式确认、目标账号冻结、同资源串行、冲突提示、执行后回读；没有厂商幂等保证就不宣称 exactly-once。
11. **权限与敏感数据 UI**：secret／SQL／证书／联系人／对象内容的截图、剪贴板、导出和本地缓存策略；操作记录不保存明文敏感 payload。
12. **导航与状态**：保留现有五个底部入口，产品目录按分类进入；产品级 loading／error／pagination，切账号取消旧请求，旧结果不覆盖新 scope。

客户端直连仍是默认方案，不为本任务提前部署独立后端。确实需要无人值守全天任务、Webhook 接收、团队审批或服务端专用凭据时，另做可选执行器子项目；不能静默上传用户 Token。

## 8. 分批开发计划

P0–P9 是**功能批次，不是十次保证完成的短迭代**。每个产品走需求规格 → 用户评审 → 细化实施计划 → TDD／编码 → 测试验收。下面提供任务边界和可验收结果，不提前创建开发 workspace 或派发实现 Agent。

| 批次 | 工作包与交付 | 依赖 | 验收标准 |
| --- | --- | --- | --- |
| P0 范围冻结 | 评审全部端点、归类 tag／路径、核实弃用冲突、建立 GraphQL／S3／外部接口矩阵、确认账号与凭据试点、冻结目标与分母 | 本报告 | 每个 operationId 有所有者、状态、批次或明确阻塞；用户确认排除项 |
| P1 基础能力与迁移 | scope／capability／error／operation 模型；拆分 Session 与 AppController；transport、分页、按产品加载；账号发现；账号账单试点；Workers 新模型试点 | P0 | 原有账号／DNS 不回归；非 JSON、202、204、429、权限隔离与进程恢复测试通过；新旧 API 迁移可解释 |
| P2 站点管理闭环 | Zone 完整生命周期；完整 DNS schema、批量、文件导入导出、扫描审核、DNSSEC；高频 Zone Settings、基础缓存清理、SSL 模式 | P1 | 第三方注册域名与 Zone 分离；完整记录不丢字段；异步扫描可恢复；破坏性变更确认与云端核对 |
| P3 高频开发者资源 | Workers 专用控制台／版本／部署／绑定／路由／Cron；Pages；R2 REST 桶和对象；KV 数据；D1 SQL／导入导出／恢复 | P1；相关产品能力试点 | 每类实现创建→读取→修改→删除闭环；大文件不读入内存；发布失败／未知结果不重放；secret 不泄露 |
| P4 域名与观测费用 | Registrar 完整 sandbox 流程；授权后真实购买流程；GraphQL 指标与 Security Events；账号账单、收据、用量、订阅、到期通知 | P1；P2 资源关联；P0 付款／测试授权 | sandbox 可验收；真实购买须独立许可；202 可轮询；价格变化重新确认；费用／用量／未知清晰分开 |
| P5 网站安全与网络 | Rulesets／WAF／Rate Limiting／Transform 等；高级缓存与证书；API Shield；LB／Health Checks；Email Routing；R2 S3／迁移／高级桶配置 | P1；P2／P3 相关模块 | 规则顺序／版本／影响预览；证书／路由操作风险提示；S3 兼容矩阵与专用凭据隔离；部分失败可核对 |
| P6 Zero Trust | Access／Tunnel／Gateway／WARP／Devices／DLP／CASB／DEX／Infrastructure Access | P1、P5 共性安全流程 | 每类产品实际授权试点；策略变更有失联防护；不把管理 API 当作手机已安装连接器；浏览器 IdP 流程不收集第三方密码 |
| P7 开发者全平台 | Queues／DO／Workflows／Containers／Hyperdrive／Vectorize／AI／Pipelines／Secrets／Artifacts／媒体／Browser／Realtime | P1；P3、P4、P5 的协议与任务机制 | 每个产品的控制面与数据面单独验收；运行时 binding 与管理能力边界明确；长连接可取消且不泄露 session URL |
| P8 企业与长尾 | Magic 网络、IPAM／Spectrum、平台／组织、Radar／情报／Cloudforce One、品牌、Zaraz／Waiting Room、支付与计费写入等剩余标签；遗漏端点补齐 | P1；相应套餐／专用凭据／合规评审 | 全部目录有结论；Enterprise 无账号时明确外部阻塞；无公开 API 只标 console_only，不冒充实现 |
| P9 全量验收与持续跟进 | 全覆盖矩阵、Schema diff CI、迁移指南、性能／安全／无障碍／发布回归、文档与实际支持状态联动 | 各批交付 | 目标操作无未解释缺口；矩阵不能跳过受限账号；新增／移除端点自动提出变更待办，不自动发布写权限 |

### 8.1 可以并行与不可以并行的部分

- P1 公共契约稳定后，P2 DNS／Zone 与 P3 各资源适配器可并行；Registrar sandbox 与 GraphQL 查询可独立推进。
- Workers／Pages 复用上传与部署任务；R2 REST／S3 复用对象模型，但鉴权和请求主机隔离。
- P6–P8 可按产品平行开发，前提是具备授权环境且每组明确产品目录所有权。
- `Models.kt`、`Ports.kt`、Transport、数据库迁移、导航注册与 AppController 是共享接口，先由集成负责人稳定，不允许多个 Agent 无约束同时修改。
- 不把 P8 当作一个不再拆分的大任务：P0 为每个 tag／operation 指定子项目，批次结束必须有零遗漏核对。

### 8.2 近期排期建议与估算边界

建议双周迭代，但按完成验收工作包滚动排期，不承诺“几周做完几千接口”。

| 近期工作包 | 初步工程量 | 预期结果 |
| --- | --- | --- |
| 范围核验／规格与独立协议补盘 | 1–2 人周 | 冻结目标、发现阻塞、确定试点账号；全量估算输入完整 |
| 传输、scope、能力与任务基础 | 3–6 人周 | 具备接入非 JSON／异步／文件产品的基础；原有功能回归 |
| 账号账单与 Workers 迁移试点 | 1–3 人周 | 验证新版接口返回、权限与兼容策略，不替换为假定数据 |
| 完整 DNS／Zone 第一闭环 | 3–5 人周 | 首个具有 L3 验收条件的完整产品子项目 |
| R2 REST／KV／D1 第一闭环 | 4–8 人周 | 第一批真实数据管理；复杂上传与恢复单独测量 |

以上是粗估，不含等待权限／套餐／付款授权、完整 Workers／Pages 部署工具链与企业产品试点；可复用与并行会影响日历时间。**不把这些数字当作全量项目总工期。**

P0 完成后，逐工作包采用：端点数 × 实际复杂度（生成绑定／专用交互／跨端点流程／异步／新协议）＋测试与安全开销，给出 P3–P9 的滚动估算。先用 DNS、R2、Workers 三种不同复杂度产品校准生产率。

完整 L2 覆盖是多季度级候选项目；如果所有产品都要求 L3 或缺少 Enterprise 测试账号，总工期更长且可能存在外部阻塞。没有端点资格审查前给固定“全部完成日期”是不可靠的。

### 8.3 角色与协调

按用户当前 Paseo 分工规则：本会话负责协调与交付，也承担当前方案设计角色，因此本轮不重复创建同职责子 Agent。

后续执行时，先读取最新 profiles 及 notes：方案设计／架构角色负责规格与最终验收；Gemini 负责明确范围的 Compose 界面与交互；日常工作负责适配器、领域、平台与自测；高消耗工作只处理复杂协议／疑难问题。一个产品子项目复用一个执行 worktree，明确公共文件负责人，结果始终回到本会话。

合并、推送、Tag／Release 都不是本次规划请求的授权内容。

## 9. 测试与验收体系

### 9.1 单操作验收模板

每个 operationId 至少记录：

- 作用域、最小权限、套餐／预览限制、鉴权类型、官方版本证据。
- 全部受支持参数／字段与明确不支持字段，正确的路径编码和媒体类型。
- 正常、空结果、权限不足、资源不存在、限流、服务异常、结构异常。
- 分页结束、重复／失效游标、大目录按需加载、账号切换后旧结果隔离。
- 写入确认、并发冲突、超时未知、取消、进程死亡、回读核对、部分失败。
- 是否已通过 mock 契约／平台／原生 UI／sandbox／真实授权账号测试，证据分别列出。

若某参数只在企业套餐可用，基础功能通过不代表该参数已验收。通用原生表单也必须满足上述标准。

### 9.2 协议与安全专项

- HTTP 204、304、202；multipart、binary、text、NDJSON、PDF；请求头／响应头保留。
- GraphQL 部分 data + errors、dataset 访问限制、采样与时间窗口，不伪装为完整计数。
- R2 S3 SigV4、Cloudflare 实际兼容子集、jurisdiction、分片失败／取消与残留清理。
- tail／WebSocket／SSE 的连接取消、重连、限速、敏感 URL 与消息脱敏。
- 同账号不同 Token、同 Token 多账号、同名资源跨 scope、旧账号后台任务不串号。
- 重定向、host 注入、恶意 `links.self`／下载 URL／上传 URL 不携带 Token 越界。
- S3 secret、证书私钥、联系人、SQL 内容、对象内容与推理内容不进入日志／公开截图。
- 大文件流式 IO、内存峰值、磁盘不足、Activity 重建、后台任务中止。
- 不用“全权 Token”替代权限问题，不加入不受约束的 Global API Key 作为默认回退。

### 9.3 现有验证命令与新增门禁

```sh
./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :android-platform:lintDebug
./gradlew :app:connectedDebugAndroidTest
python3 -m unittest discover -s tools/ci/tests -v
python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_catalog.py' -v
python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_review.py' -v
python3 -m tools.cloudflare_audit.cli check --review docs/cloudflare-audit/review.json \
  --catalog docs/cloudflare-audit/endpoints.csv
```

`cli check` 只读，不修改 CSV、manifest、review 或产品行为；结构错误非零退出，可用性阻塞与待审数量照实报告而不隐藏。

执行阶段增加：固定 Schema 检查、生成代码可重复性、operationId 全归属、能力与风险策略完整性、各产品 mock／sandbox／授权账号测试任务。CI 不含生产 Token，不执行真实扣款或破坏性操作；受控真实账号验收是单独授权作业。

**完成定义：**适配器、权限策略、操作流程、UI、测试、端到端证据、支持矩阵和用户文档同步完成。仅添加函数或入口不算完成。

## 10. 当前决策与下一步

推荐采用 **C：协议基础层＋产品模块＋受控原生能力页**，目标为全部合格管理 API 到 L2、高频产品到 L3，保留向全部 L3 扩展的独立子项目路线。

下一步请评审三个关键边界：

1. 是否接受上述“全 L2＋高频 L3”作为第一轮全覆盖目标，还是每个产品都必须 L3？
2. Enterprise／专用凭据／预览能力是否纳入开发但等待授权环境验收？本草案默认纳入，不假装普通 Token 可用。
3. 是否批准先细化 P0／P1／P2 的独立规格，而不是直接启动全量并行开发？

用户已确认继续细化，逐文件／逐任务／逐测试的 [P0–P2计划](superpowers/plans/2026-10-07-cloudflare-p0-p2-index.md) 已另行写出；执行沿用用户的Paseo角色分工，待新计划评审后进入实施。此次仅新增盘点与规划资料，没有修改产品代码，也没有运行产品构建或真实云端测试。

## 11. 主要官方依据

- [Cloudflare API 目录](https://developers.cloudflare.com/api/)
- [固定提交的官方 OpenAPI](https://github.com/cloudflare/api-schemas/tree/6b00f157496cd6a1792a613dc94654c51273aeb9)
- [API Token 权限](https://developers.cloudflare.com/fundamentals/api/reference/permissions/)
- [Registrar API 指南](https://developers.cloudflare.com/registrar/registrar-api/) 与 [Registrar Reference](https://developers.cloudflare.com/api/resources/registrar/)
- [GraphQL Analytics](https://developers.cloudflare.com/analytics/graphql-api/)
- [R2 S3 兼容能力](https://developers.cloudflare.com/r2/api/s3/api/)
- [R2 Workers API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)

精确端点数量、状态、限制与字段证据取自已直接下载的固定 Schema；S3 兼容表、Registrar Beta 指南及 GraphQL 概览取自官方 cloudflare-docs 固定提交（snapshot.json 已记录）。网页检索摘要仅用于交叉核对，不用于覆盖 Schema 中的 Alpha／Restricted 或不提供价格字段等明确限制。
