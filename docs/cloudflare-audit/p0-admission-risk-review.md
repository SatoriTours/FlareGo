# P0 出口：P1／P2 精确接口与工程风险审核

审核者：原协调会话（方案设计／架构角色）；依据：固定 Schema、既有 11 项源码映射、P1 已批准计划、P2 固定 54 项候选、`protocols.json`，实现验收 HEAD `971122434454f1f8d4917aaa72c27cbf28e2d7bc`。

## 决策与边界

- 工程实现范围审核通过：P1 新增 5／兼容 11／P2 候选 54，交集 5／并集 **65**；精确清单含 **35 只读／30 写**，余 **3,594** REST 操作仍属未来批次。各行 METHOD＋path 已与固定 catalog 核对，兼容集合与 implemented.json、P2 集合与候选 CSV 一致，不按宽 tag 扩范围。
- **此表不是运行时白名单、账号权限批准、真实危险动作授权或 L2 验收。** 原 `protocols.json` 的 `proposed=true`／`pending_manual_review` 是未经用户逐项云资格批准的提议状态，保留不改；本文记录独立的工程风险审核。资格台账 needs_review、未知权限／套餐／预览阻塞不清除。P1 注册必须手写并匹配 actionKey＋METHOD＋path-template＋scope，不读取此表自动授予执行。
- 每项读均要求最小权限、正确 connection／account／zone／user scope、分页与错误透明；读限流重试只在 P1 手写策略明确许可时开启，不因本表而启用。POST／PUT 等方法不是一般语义分类器，本次清单的 30 个写动作已按业务逐项评估。
- 每项写均要求 scope／凭据 revision／输入指纹冻结、预览和显式确认、同资源串行、超时／取消／202／部分失败状态保留、回读核对；**不自动重放**。破坏性／安全降级须二次确认；未知语义／未注册动作默认拒绝。DNS TXT、Token、正文、对象内容不入日志或任务表。
- `{setting_id}` 的精确路由准入**不等于任意 setting key 准入**：P2 Task9 必须逐键审核固定输入 schema／editable／套餐；没有可证明定义或资格的键阻塞，不能写入。专用 setting API 也须逐字段与账号套餐验证，缺账号不缩 54 分母或假称全可用。
- 真实域名购买／支付／注册、生产 DNS／DNSSEC／Zone 删除、订阅修改均不属于普通自动测试许可。Zone 创建不暗含域名注册、套餐购买或金额承诺；金融动作仍需独立许可。模拟／离线协议开发可继续，真实账号能力目前仍为 0。
- REST 3,659／S3 73 documented（26 vendor-supported、47 vendor-unsupported）仍独立；GraphQL／实时分母未知 null，不混合求和。47 厂商不支持项不是 FlareGo 漏做的写能力，不伪造用户 L0-only／排除批准。

## 逐项审核（并集 65）

| operationId | METHOD + path | 阶段集合 | 工程风险 | 必须实现的边界 |
| --- | --- | --- | --- | --- |
| `account-api-tokens-verify-token` | `GET /accounts/{account_id}/tokens/verify` | p1_new | READ_ONLY | 用户/账号Token验证路由显式区分；有效Token不证明完整权限或账号可读 |
| `account-billing-history-get-billing-history` | `GET /accounts/{account_id}/billing/history` | p1_new | READ_ONLY | 账号/用户账单分离，403不宽scope回退；金额未知不补0，不自动下载凭据链接 |
| `accounts-account-details` | `GET /accounts/{account_id}` | p1_existing_compat | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `accounts-list-accounts` | `GET /accounts` | p1_new | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `d1-list-databases` | `GET /accounts/{account_id}/d1/database` | p1_existing_compat | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `dns-records-for-a-zone-apply-dns-scan-results` | `POST /zones/{zone_id}/dns_records/scan/review` | p2_candidates | CHANGE | 仅用户已选定扫描结果；冻结账号/旧值，逐记录回读 |
| `dns-records-for-a-zone-batch-dns-records` | `POST /zones/{zone_id}/dns_records/batch` | p2_candidates | CHANGE/DESTRUCTIVE | 删除项逐项确认；Free/未知200、已验证套餐3500；单DB事务/传播非原子，unknown不重放 |
| `dns-records-for-a-zone-create-dns-record` | `POST /zones/{zone_id}/dns_records` | p1_existing_compat, p2_candidates | CHANGE | 完整记录校验+预览；unknown检索核对而非再次创建 |
| `dns-records-for-a-zone-delete-dns-record` | `DELETE /zones/{zone_id}/dns_records/{dns_record_id}` | p1_existing_compat, p2_candidates | DESTRUCTIVE | 记录内容/ID确认；读记录核对删除，避免同名不同类型误删 |
| `dns-records-for-a-zone-dns-record-details` | `GET /zones/{zone_id}/dns_records/{dns_record_id}` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `dns-records-for-a-zone-export-dns-records` | `GET /zones/{zone_id}/dns_records/export` | p2_candidates | READ_ONLY | 独立流式下载；失败清临时文件；不保存TXT原文到日志/操作表 |
| `dns-records-for-a-zone-get-usage` | `GET /zones/{zone_id}/dns_records/usage` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `dns-records-for-a-zone-import-dns-records` | `POST /zones/{zone_id}/dns_records/import` | p2_candidates | CHANGE | 262144字节/3次每分钟每用户；BIND/cf-proxied预览；计数/部分失败回读，不重导 |
| `dns-records-for-a-zone-list-dns-records` | `GET /zones/{zone_id}/dns_records` | p1_existing_compat, p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `dns-records-for-a-zone-patch-dns-record` | `PATCH /zones/{zone_id}/dns_records/{dns_record_id}` | p1_existing_compat, p2_candidates | CHANGE | 未知字段保留/三态patch/旧值hash；冲突拒绝，回读完整记录 |
| `dns-records-for-a-zone-review-dns-scan` | `GET /zones/{zone_id}/dns_records/scan/review` | p2_candidates | READ_ONLY | 不臆造page参数/全集计数；官方数据缺页信息保留未知 |
| `dns-records-for-a-zone-trigger-dns-scan` | `POST /zones/{zone_id}/dns_records/scan/trigger` | p2_candidates | CHANGE | 显式扫描与影响提示；异步/unknown只核对，不重触发 |
| `dns-records-for-a-zone-update-dns-record` | `PUT /zones/{zone_id}/dns_records/{dns_record_id}` | p2_candidates | CHANGE | PUT完整替换预览，不把省略字段当patch；回读完整记录 |
| `dns-settings-for-a-zone-list-dns-settings` | `GET /zones/{zone_id}/dns_settings` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `dns-settings-for-a-zone-update-dns-settings` | `PATCH /zones/{zone_id}/dns_settings` | p2_candidates | CHANGE | 逐字段schema/diff/套餐检查；读Zone DNS设置核对，不变账号全局配置 |
| `dnssec-delete-dnssec-records` | `DELETE /zones/{zone_id}/dnssec` | p2_candidates | DESTRUCTIVE | DNSSEC/外部DS影响二次确认；不写Registrar，unknown只读核对 |
| `dnssec-dnssec-details` | `GET /zones/{zone_id}/dnssec` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `dnssec-edit-dnssec-status` | `PATCH /zones/{zone_id}/dnssec` | p2_candidates | CHANGE/DESTRUCTIVE | 启停/外部DS步骤明确；不声称DS已同步；回读状态 |
| `dnssec-list-dnssec-zsks` | `GET /zones/{zone_id}/dnssec/zsk` | p2_candidates | READ_ONLY | 不臆造page参数/全集计数；官方数据缺页信息保留未知 |
| `listWorkers` | `GET /accounts/{account_id}/workers/workers` | p1_new | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `put-zones-zone_id-activation_check` | `PUT /zones/{zone_id}/activation_check` | p2_candidates | CHANGE | 显式触发验证；202/unknown只读Zone状态，不重触发 |
| `r2-list-buckets` | `GET /accounts/{account_id}/r2/buckets` | p1_existing_compat | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `universal-ssl-settings-for-a-zone-edit-universal-ssl-settings` | `PATCH /zones/{zone_id}/ssl/universal/settings` | p2_candidates | CHANGE/DESTRUCTIVE | 停用失联风险二次确认；检查证书/设置，回读enabled非TLS端到端成功 |
| `universal-ssl-settings-for-a-zone-universal-ssl-settings-details` | `GET /zones/{zone_id}/ssl/universal/settings` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `user-api-tokens-verify-token` | `GET /user/tokens/verify` | p1_new | READ_ONLY | 用户/账号Token验证路由显式区分；有效Token不证明完整权限或账号可读 |
| `user-billing-history-(-deprecated)-billing-history-details` | `GET /user/billing/history` | p1_existing_compat | READ_ONLY | 账号/用户账单分离，403不宽scope回退；金额未知不补0，不自动下载凭据链接 |
| `worker-script-list-workers` | `GET /accounts/{account_id}/workers/scripts` | p1_existing_compat | READ_ONLY | 新版immutable ID/名称分开；旧接口显式选择，403不回退；目录非部署管理 |
| `workers-kv-namespace-list-namespaces` | `GET /accounts/{account_id}/storage/kv/namespaces` | p1_existing_compat | READ_ONLY | 新版immutable ID/名称分开；旧接口显式选择，403不回退；目录非部署管理 |
| `zone-cache-settings-change-aegis-setting` | `PATCH /zones/{zone_id}/settings/aegis` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-cache-settings-change-origin-h2-max-streams-setting` | `PATCH /zones/{zone_id}/settings/origin_h2_max_streams` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-cache-settings-change-origin-max-http-version-setting` | `PATCH /zones/{zone_id}/settings/origin_max_http_version` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-cache-settings-get-aegis-setting` | `GET /zones/{zone_id}/settings/aegis` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-cache-settings-get-origin-h2-max-streams-setting` | `GET /zones/{zone_id}/settings/origin_h2_max_streams` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-cache-settings-get-origin-max-http-version-setting` | `GET /zones/{zone_id}/settings/origin_max_http_version` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-purge` | `POST /zones/{zone_id}/purge_cache` | p2_candidates | CHANGE/DESTRUCTIVE | URL/tag/prefix/host与purge_everything分离；全清二次确认；不自动升级全清 |
| `zone-settings-change-automatic-platform-optimization-setting` | `PATCH /zones/{zone_id}/settings/automatic_platform_optimization` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-binary-ast-setting` | `PATCH /zones/{zone_id}/settings/binary_ast` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-fonts-setting` | `PATCH /zones/{zone_id}/settings/fonts` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-h2-prioritization-setting` | `PATCH /zones/{zone_id}/settings/h2_prioritization` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-image-resizing-setting` | `PATCH /zones/{zone_id}/settings/image_resizing` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-speed-brain-setting` | `PATCH /zones/{zone_id}/settings/speed_brain` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-transformations-allowed-origins-setting` | `PATCH /zones/{zone_id}/settings/transformations_allowed_origins` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-transformations-c2pa-setting` | `PATCH /zones/{zone_id}/settings/transformations_c2pa` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-change-transformations-config` | `PATCH /zones/{zone_id}/settings/transformations_config` | p2_candidates | CHANGE | 当前/目标diff，逐字段schema/套餐/editable检查；协议/来源变更提示影响，回读对应字段 |
| `zone-settings-edit-single-setting` | `PATCH /zones/{zone_id}/settings/{setting_id}` | p2_candidates | CHANGE/DESTRUCTIVE | setting_id逐键审核/可证明schema；未知key/editable=false拒绝；ssl降级独立确认 |
| `zone-settings-get-automatic-platform-optimization-setting` | `GET /zones/{zone_id}/settings/automatic_platform_optimization` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-binary-ast-setting` | `GET /zones/{zone_id}/settings/binary_ast` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-fonts-setting` | `GET /zones/{zone_id}/settings/fonts` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-h2-prioritization-setting` | `GET /zones/{zone_id}/settings/h2_prioritization` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-image-resizing-setting` | `GET /zones/{zone_id}/settings/image_resizing` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-single-setting` | `GET /zones/{zone_id}/settings/{setting_id}` | p2_candidates | READ_ONLY | setting_id逐键审核，未知key拒绝；路由准入不授予任意设置读取 |
| `zone-settings-get-speed-brain-setting` | `GET /zones/{zone_id}/settings/speed_brain` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-transformations-allowed-origins-setting` | `GET /zones/{zone_id}/settings/transformations_allowed_origins` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-transformations-c2pa-setting` | `GET /zones/{zone_id}/settings/transformations_c2pa` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zone-settings-get-transformations-config` | `GET /zones/{zone_id}/settings/transformations_config` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zones-0-delete` | `DELETE /zones/{zone_id}` | p2_candidates | DESTRUCTIVE | 名称+账号二次确认；读Zone核对删除/不存在，不声称注册域名已注销 |
| `zones-0-get` | `GET /zones/{zone_id}` | p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zones-0-patch` | `PATCH /zones/{zone_id}` | p2_candidates | CHANGE/DESTRUCTIVE | paused/type等字段逐项diff；暂停/转换提示业务影响并回读 |
| `zones-get` | `GET /zones` | p1_existing_compat, p2_candidates | READ_ONLY | 最小权限/明确scope；分页未知不报完整总量，错误不补演示数据 |
| `zones-post` | `POST /zones` | p2_candidates | CHANGE/费用边界 | 确认账号/domain/type；不注册域名/购买套餐，付费字段未独立许可拒绝 |
