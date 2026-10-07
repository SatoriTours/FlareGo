# Cloudflare 协议验收环境与试点准入登记

本文件是 P0 的**验收环境登记**，不是运行时白名单，也不是任何产品的可用性认证。
仅登记环境标签、套餐类别、允许动作类别、负责人、权限类别、清理责任与状态；
不保存账号 ID、Zone ID、Token、S3 secret、付款资料、私钥或本机凭据路径。
所有真实环境都尚未获得授权，因此每项状态都是 `awaiting_authorized_environment`。

机器可验证的同一份数据在 [protocols.json](protocols.json)，由 `validate_protocols` 校验。

## 环境登记

| environment_id | 标签 | 套餐类别 | 允许动作类别 | 负责人 | 权限类别 | 清理责任 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `registrar_sandbox` | Cloudflare Registrar sandbox | sandbox | read_only_domain_search、availability_and_price_check、sandbox_registration_flow | 日常工作 | registrar_api_scoped_token | owner_role | `awaiting_authorized_environment` |
| `test_zone_dns` | isolated test zone and DNS records | free_or_pro | zone_read、dns_record_create_update_delete、cache_purge | 日常工作 | zone_dns_edit、zone_settings_read | owner_role | `awaiting_authorized_environment` |
| `enterprise_preview` | enterprise or preview entitlement checks | enterprise_or_preview | capability_read_only_check | 日常工作 | account_read、plan_entitlement_read | owner_role | `awaiting_authorized_environment` |
| `s3_multipart_cleanup` | R2 bucket for S3 multipart lifecycle | r2_developer_bucket | multipart_upload_create、multipart_abort_and_cleanup、object_read_back | 日常工作 | r2_s3_access_key_type_only | owner_role | `awaiting_authorized_environment` |
| `graphql_schema_capture` | authorized GraphQL schema capture | analytics_access | schema_introspection_capture、dataset_read_only_query | 日常工作 | analytics_read | owner_role | `awaiting_authorized_environment` |
| `k2_stream_host` | dedicated K2 stream endpoint | k2_beta_stream | stream_scoped_produce_consume、subscription_lease_ack_nack | 日常工作 | stream_scoped_credential_type_only | owner_role | `awaiting_authorized_environment` |
| `realtime_session_pilot` | realtime session pilot (tail, SFU, TURN, MoQ, live input) | product_specific_preview | session_create_and_consume、session_cancel_and_cleanup | 日常工作 | product_scoped_token_type_only | owner_role | `awaiting_authorized_environment` |

## 每项的具体阻塞与边界

### `registrar_sandbox`

- 阻塞：sandbox_access_not_provided（no authorized Registrar sandbox account is available）
- 边界：real domain registration, renewals and payment are never ordinary automated tests

### `test_zone_dns`

- 阻塞：test_zone_not_provided（no disposable zone or DNS record set is authorized）
- 边界：production DNS is out of scope for automated tests

### `enterprise_preview`

- 阻塞：enterprise_plan_evidence_absent（no enterprise or preview entitlement evidence）
- 边界：read-only capability checks only; no entitlement is assumed

### `s3_multipart_cleanup`

- 阻塞：s3_credentials_not_provided（dedicated S3 access key material is not authorized）
- 边界：aborted and incomplete multipart uploads must be cleaned up by the owning role

### `graphql_schema_capture`

- 阻塞：authorized_schema_capture_absent（no authorized introspection capture exists）
- 边界：the dataset and field universe stays unknown until an authorized capture is recorded

### `k2_stream_host`

- 阻塞：stream_host_credentials_absent（no stream-scoped credential or disposable stream is authorized）
- 边界：API-base credentials must not be reused on the stream host; lease and ACK/NACK mutate delivery state

### `realtime_session_pilot`

- 阻塞：realtime_protocol_evidence_absent（consumer protocol evidence for each realtime family is missing）
- 边界：connection protocols are enumerated separately from the REST session endpoints

## 协议专项准入前提

### Registrar sandbox

- 只在官方 sandbox 流程内验证搜索、可用性／价格检查与注册流程；真实域名注册、续费与付款永远不作为普通自动化测试。
- 支付资料、联系人隐私数据与注册协议准备不在本登记范围内。

### 测试 Zone／DNS

- 仅使用一次性测试 Zone 与可清理的 DNS 记录集合；生产 DNS 与真实域名变更不进入自动测试。

### 企业／预览／专用凭据

- 仅做只读能力核对，不假设任何套餐权益；缺少授权环境时保持阻塞，不缩小分母、不伪造排除批准。

### S3 multipart 清理

- 分片上传的 abort／残留清理由 `owner_role` 负责；恢复前核对 upload ID 与已上传分片，不盲目重传。
- 仅登记独立 SigV4 凭据**类型**与模板，不登记任何密钥值。

### GraphQL 授权 schema 捕获

- 在获得授权环境后执行一次 schema introspection 捕获，并记录环境与时间；在此之前 `review_status=awaiting_authorized_schema`，dataset／field 全集保持未知（不是 0）。
- 验收前提：部分 data + errors 同时处理、采样与时间窗口语义、dataset 访问与套餐限制，且 GraphQL 结果不得当作计费用量证据。

### K2 独立 stream host

- 数据面使用独立主机 `https://{stream_id}.k2.cloudflarestorage.com`（path-level servers），必须单独验收；API 基础主机凭据不得默认复用或跟随任意 server URL 授权。
- 8 项数据面操作已计入 REST 分母，不重复计入非 REST 统计；stream scope 保持 `unknown` 直到有独立证据。
- produce／consume 权限、lease 与 ACK/NACK/extend 会改变投递状态，POST consume 不得当作自动安全读取。

### 实时会话协议

- tail／SFU／TURN／MoQ／live input 等把「REST 会话创建与管理」与「实际连接协议」分开登记：会话端点引用精确 catalog ID，连接协议、鉴权／主机策略、生命周期与消费者证据分别阻塞。

## P1／P2 准入引用（仅供人工审查）

- P1 新增请求 5 项、P1 既有兼容 11 项、P2 候选 54 项。
- 其中 P1 兼容与 P2 候选重叠 5 项；去重后共 65 项，列表相加为 70 项。**不得把列表相加当成唯一总数**。
- 每项登记 `risk`、`confirmation_required`、`verification_boundary`、`auto_replay=false` 与 `readback_required`；这些是提议，不是已实现的运行时策略，也不是用户逐项排除批准。
- 其余 3,594 项 future 批次不因产品／tag 宽泛归属而提前获得准入。

## 未执行的真实验收

- 没有授权账号、没有授权 GraphQL schema 捕获、没有 stream-scoped 凭据、没有 sandbox 环境：`verified_available` 与 `capability_verified` 均为 0。
- 本登记不包含任何真实云请求、写入或付款授权。
