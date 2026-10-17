# P2 Zone / DNS：技术支持与验证边界（2026-10-17）

P2 功能已恢复并提交，本地合入main；当前集成状态见[主线集成验证](p2-main-integration-verification-2026-10-17.md)。以下表格保留恢复阶段的分轮结果。旧执行工作区及原 XML 被用户归档移除，本次所有通过结论均来自新的实际运行；来源与完整限制见 [恢复验收报告](p2-recovery-verification-2026-10-17.md)。真实 Cloudflare 云／付费资格未执行。本文和 CSV 不是执行 allowlist、Token 写权限证明或发布记录；P0 implemented snapshot 与 P1 ledger 保持历史原值。

## 固定来源与分母

- OpenAPI commit `6b00f157496cd6a1792a613dc94654c51273aeb9`，SHA256 `06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461`；来源 [固定公开 Schema](https://raw.githubusercontent.com/cloudflare/api-schemas/6b00f157496cd6a1792a613dc94654c51273aeb9/openapi.json)。
- [candidate CSV](p2-candidate-endpoints.csv) 保留54行及原八列值/顺序；canonical八列digest `3f20a3ea404a5692725c685eb664beab0a8e3ccb11ef1c4202e92fdeb1b534be`。没有把未写代码改名blocked或删除无法验云的项。
- 实际 `ActionPolicies.registry.registrations` 导出69项：候选54（24 READ/30 WRITE）与P1历史16重叠5→union65，四独立authority READ→69（39 READ/30 WRITE）。operationId、method、path、scope、semantics、response、risk与retry完整比较，不按源码regex/数字猜覆盖。
- Schema测试优先 `SCHEMA_FILE`；设置了错误路径或错误SHA必须失败，不fallback/skip。未设置时仅本地`.superpowers/cloudflare/openapi.json`缓存回退。CI应由现下载固定schema并SHA核对的步骤提供环境变量。

## 恢复批次实际验证

| 层次 | 新运行结果 | 证据与边界 |
| --- | --- | --- |
| shared-core 全套 | 706／0 failure/error/skip；13.57s，6 tasks executed | `recovery-evidence/core-final-restoration`；含 P2CoverageTest 6 项与实际 registry69 |
| 平台、Debug、lint | 平台 91／0；25.99s，129 tasks executed；lint 0 errors，app3／platform4 warnings | `platform-build-lint`；最终 Android 测试变化后再跑 `app-lint-final` 通过 |
| 设备 UI 全集合 | 330=326 PASS／4 FAIL | `device-ui-full`，原失败 XML 保留，不称全绿 |
| 必要定向复验 | 9／9 PASS，包含全部原4失败及受影响类 | `fixture-boundary-targets`；两个 cleanup 测试修正，旧 Smoke／Zone 用例未改 |
| 真实 VM／DB／SAF／native | 24／24 PASS；system font2、320×640／dpi160 | `device-app-font2-full`；synthetic provider，没有真实云请求 |
| 方法集合 | 24 classes／354 unique methods 各有实际 PASS | `accepted-case-results.json`；分轮覆盖，不是单轮354全绿 |
| Python／CLI | 审计349／CI契约10，无 skip；CLI check 与 DNS生成字节一致 | 固定公开 Schema／S3 缓存，`SCHEMA_FILE` 明确指定 |
| 独立只读审查 | 所审范围无已证实 Critical／Important 阻断项 | 审查时最后24待定；root在其完成后另核对实际24 PASS |
| 真实云／paid | 未执行 | 默认未知拒写，不用 mock／Token名／deprecated Zone.plan 授资格 |

每轮都有准确命令、实际新 XML／日志和轮内源码 before／after。core／platform 后只有3个 Android 测试文件改变，330 后仅两个 cleanup 测试文件改变；最终9与24轮源码一致。Zone invalidName 曾缺节点、后在原断言下通过，其确定性原因未证实，不声称稳定性修复。全套设备的已知条件和失败历史必须与分轮 PASS 一同阅读。

coverage不仅验证登记：逐项检查compiled typed handler与具体test method，并实际调用所引用的真实synthetic typed行为测试。Settings的20个key循环执行GET/PATCH/readback，覆盖24 dedicated+2 generic operation；再实际执行Universal2与purge1→29，不把8个generic key算16新operation。Mutation否定覆盖删分母/缺handler或test/blocked伪装/新增非法write/少registry/错scope或response/写retry。registry数据只用于审计，产品手写注册表未加载它。

## 安全与能力限制

21DNS类型局部schema忠实；PATCH差量与PUT完整替换分开；raw未知保留只读/未知nested拒绝。当前状态语义hash与provider读不构成强CAS，modified_on不是强CAS。Zone所属账号需provider证据，不把Zone当Registrar。

Batch按官方deletes→patches→puts→posts单数据库事务，传播非原子；200保守quota，生产3500资格未证明，不能静默拆批。safe manifest与task/summary原子落库，重建只能只读当前状态证明，不恢复payload/confirm/历史归因。single/batch/import/apply/settings同完整Zone持久未结算gate相互阻断，accepted scan trigger语义单列，其他Zone/read export不误封。

BIND最大262144 bytes、Application全连接共享滚动60秒三次；实际SAF flags/persisted grant及job capability不由UI假造。Import Partial/UNKNOWN不靠inventory count推成功、不重放；新VM无内存receipt显示不可用。只读export可在records403/写authority未知时尝试，只有真实text/plain字节安全commit才显示完成，不能推云权限保证。

DNSSEC DS/预签名/Registrar与全球传播不自动处理。NSEC3 Enterprise、secondary/presigned、settings editable/套餐与其他功能资格分别验证；未知保持拒写，Token policy grant不代替paid资格。SSL降级/Universal禁用/purge all等风险token只由同runtime按用户enum颁发；本机确认不授远端写权。

## 54候选逐端点映射

完整method/path/policy/handler/test/schema权限label在[机器映射](p2-runtime-support-2026-10-17.json)；下表每项都有明确代码与行为证据，云验收均UNVERIFIED。

| operationId | 方法与路径 | typed handler | 实际行为测试键 |
| --- | --- | --- | --- |
| `zones-get` | `GET /zones` | `CloudflareZoneClient#list` | `ZoneManagementTest#listIsAccountFilteredOneRequestedPageAndPreservesZoneState` |
| `zones-post` | `POST /zones` | `CloudflareZoneClient#create` | `ZoneManagementTest#createUsesSelectedAccountAndOnlySchemaFields` |
| `zones-0-delete` | `DELETE /zones/{zone_id}` | `CloudflareZoneClient#delete` | `ZoneManagementTest#deleteUsesExactZoneAndValidatesReturnedIdentity` |
| `zones-0-get` | `GET /zones/{zone_id}` | `CloudflareZoneClient#get` | `ZoneManagementTest#missingNameserversStayEmptyAndZoneActiveDoesNotClaimDomainRegistration` |
| `zones-0-patch` | `PATCH /zones/{zone_id}` | `CloudflareZoneClient#edit` | `ZoneManagementTest#editSendsExactlyOneChangedPropertyAndRetainsUnknownMetadata` |
| `put-zones-zone_id-activation_check` | `PUT /zones/{zone_id}/activation_check` | `CloudflareZoneClient#activationCheck` | `ZoneManagementTest#activationUsesOnlyOfficialPutAndNoBody` |
| `dns-records-for-a-zone-list-dns-records` | `GET /zones/{zone_id}/dns_records` | `CloudflareDnsClient#list` | `CloudflareDnsManagementTest#listingUsesSchemaQueryNamesExactMatchingAndProviderPagination` |
| `dns-records-for-a-zone-create-dns-record` | `POST /zones/{zone_id}/dns_records` | `CloudflareDnsClient#create` | `CloudflareDnsManagementTest#createUsesRealProviderIdAndReadbackNeverLocalIds` |
| `dns-records-for-a-zone-batch-dns-records` | `POST /zones/{zone_id}/dns_records/batch` | `CloudflareDnsClient#batch` | `DnsBatchTest#directProductionBatchUsesFixedGroupsDeltaAndRealIds` |
| `dns-records-for-a-zone-export-dns-records` | `GET /zones/{zone_id}/dns_records/export` | `CloudflareDnsFiles#exportRecords` | `DnsFilesTest#exportPreservesExactBindBytesAndCommitsOnlyOfficialTextPlainSuccess` |
| `dns-records-for-a-zone-import-dns-records` | `POST /zones/{zone_id}/dns_records/import` | `CloudflareDnsFiles#writeImport` | `DnsFilesTest#multipartUsesFileBytesAndStringBooleanWithoutRewritingDirectivesOrTags` |
| `dns-records-for-a-zone-review-dns-scan` | `GET /zones/{zone_id}/dns_records/scan/review` | `CloudflareDnsScan#review` | `DnsScanTest#reviewUsesOnlyRealScanRouteWithoutPagingAndPreservesFutureRaw` |
| `dns-records-for-a-zone-apply-dns-scan-results` | `POST /zones/{zone_id}/dns_records/scan/review` | `CloudflareDnsScan#apply` | `DnsScanTest#acceptsAreRecordObjectsWithoutIdsAndRejectRequestObjectsDifferFromResponseStrings` |
| `dns-records-for-a-zone-trigger-dns-scan` | `POST /zones/{zone_id}/dns_records/scan/trigger` | `CloudflareDnsScan#trigger` | `DnsScanTest#trigger200WithoutResultIsOnlyAcceptedAndDoesNotInventPollPath` |
| `dns-records-for-a-zone-get-usage` | `GET /zones/{zone_id}/dns_records/usage` | `CloudflareDnsZoneSettings#usage` | `DnsScanTest#usageQuotaNullAndZeroRemainDifferentWithoutPlanInference` |
| `dns-records-for-a-zone-delete-dns-record` | `DELETE /zones/{zone_id}/dns_records/{dns_record_id}` | `CloudflareDnsClient#delete` | `CloudflareDnsManagementTest#deleteNeedsExactProviderIdAndRestoredReconciliationIsReadOnly` |
| `dns-records-for-a-zone-dns-record-details` | `GET /zones/{zone_id}/dns_records/{dns_record_id}` | `CloudflareDnsClient#get` | `CloudflareDnsManagementTest#srvAndFutureDetailsPreserveRawFields` |
| `dns-records-for-a-zone-patch-dns-record` | `PATCH /zones/{zone_id}/dns_records/{dns_record_id}` | `CloudflareDnsClient#patch` | `CloudflareDnsManagementTest#patchSendsOnlyChangedCommentAndPreservesUnknownRaw` |
| `dns-records-for-a-zone-update-dns-record` | `PUT /zones/{zone_id}/dns_records/{dns_record_id}` | `CloudflareDnsClient#replace` | `CloudflareDnsManagementTest#putIsActualReplacementWithDistinctConfirmationAndUnsafeUnknownsBlockIt` |
| `dns-settings-for-a-zone-list-dns-settings` | `GET /zones/{zone_id}/dns_settings` | `CloudflareDnsZoneSettings#settings` | `DnsScanTest#readonlyReviewUsageSettingsStillWorkDuringMutationUnknownAndPermissionIsLocal` |
| `dns-settings-for-a-zone-update-dns-settings` | `PATCH /zones/{zone_id}/dns_settings` | `CloudflareDnsZoneSettings#updateSettings` | `DnsScanTest#scalarPatchSendsOnlyActualDeltaAndFalseIsNotOmitted` |
| `dnssec-delete-dnssec-records` | `DELETE /zones/{zone_id}/dnssec` | `CloudflareDnssecClient#delete` | `DnssecTest#deleteRequiresOfficialStringReceiptAndDisabledReadbackInsteadOfGuessingFrom404` |
| `dnssec-dnssec-details` | `GET /zones/{zone_id}/dnssec` | `CloudflareDnssecClient#get` | `DnssecTest#nullableDsMaterialAndUnknownMetadataAreReadWithoutExamplesOrCrossZoneMixing` |
| `dnssec-edit-dnssec-status` | `PATCH /zones/{zone_id}/dnssec` | `CloudflareDnssecClient#update` | `DnssecTest#enableCompletesOnlyProviderConfigurationAndPreviewStatesRegistrarResponsibility` |
| `dnssec-list-dnssec-zsks` | `GET /zones/{zone_id}/dnssec/zsk` | `CloudflareDnssecClient#zsks` | `DnssecTest#zskUsesActualNestedCapitalizedShapeAndNeverRetainsPrivateSigningMaterialOrPagination` |
| `zone-purge` | `POST /zones/{zone_id}/purge_cache` | `CloudflareZoneSettings#purge` | `ZoneSettingsTest#controlledPurgeVariantsNeverFallBackToAllAndUrlsOnlyEnterPayload` |
| `zone-cache-settings-get-aegis-setting` | `GET /zones/{zone_id}/settings/aegis` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-cache-settings-change-aegis-setting` | `PATCH /zones/{zone_id}/settings/aegis` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-automatic-platform-optimization-setting` | `GET /zones/{zone_id}/settings/automatic_platform_optimization` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-automatic-platform-optimization-setting` | `PATCH /zones/{zone_id}/settings/automatic_platform_optimization` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-binary-ast-setting` | `GET /zones/{zone_id}/settings/binary_ast` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-binary-ast-setting` | `PATCH /zones/{zone_id}/settings/binary_ast` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-fonts-setting` | `GET /zones/{zone_id}/settings/fonts` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-fonts-setting` | `PATCH /zones/{zone_id}/settings/fonts` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-h2-prioritization-setting` | `GET /zones/{zone_id}/settings/h2_prioritization` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-h2-prioritization-setting` | `PATCH /zones/{zone_id}/settings/h2_prioritization` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-image-resizing-setting` | `GET /zones/{zone_id}/settings/image_resizing` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-image-resizing-setting` | `PATCH /zones/{zone_id}/settings/image_resizing` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-cache-settings-get-origin-h2-max-streams-setting` | `GET /zones/{zone_id}/settings/origin_h2_max_streams` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-cache-settings-change-origin-h2-max-streams-setting` | `PATCH /zones/{zone_id}/settings/origin_h2_max_streams` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-cache-settings-get-origin-max-http-version-setting` | `GET /zones/{zone_id}/settings/origin_max_http_version` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-cache-settings-change-origin-max-http-version-setting` | `PATCH /zones/{zone_id}/settings/origin_max_http_version` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-speed-brain-setting` | `GET /zones/{zone_id}/settings/speed_brain` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-speed-brain-setting` | `PATCH /zones/{zone_id}/settings/speed_brain` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-transformations-allowed-origins-setting` | `GET /zones/{zone_id}/settings/transformations_allowed_origins` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-transformations-allowed-origins-setting` | `PATCH /zones/{zone_id}/settings/transformations_allowed_origins` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-transformations-c2pa-setting` | `GET /zones/{zone_id}/settings/transformations_c2pa` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-transformations-c2pa-setting` | `PATCH /zones/{zone_id}/settings/transformations_c2pa` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-transformations-config` | `GET /zones/{zone_id}/settings/transformations_config` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-change-transformations-config` | `PATCH /zones/{zone_id}/settings/transformations_config` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-get-single-setting` | `GET /zones/{zone_id}/settings/{setting_id}` | `CloudflareZoneSettings#get` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `zone-settings-edit-single-setting` | `PATCH /zones/{zone_id}/settings/{setting_id}` | `CloudflareZoneSettings#update` | `ZoneSettingsTest#everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget` |
| `universal-ssl-settings-for-a-zone-universal-ssl-settings-details` | `GET /zones/{zone_id}/ssl/universal/settings` | `CloudflareZoneSettings#universalSsl` | `ZoneSettingsTest#universalSslUsesDedicatedEndpointAndVerifiesReadbackWithoutClaimingCertificateIssuance` |
| `universal-ssl-settings-for-a-zone-edit-universal-ssl-settings` | `PATCH /zones/{zone_id}/ssl/universal/settings` | `CloudflareZoneSettings#updateUniversalSsl` | `ZoneSettingsTest#universalSslUsesDedicatedEndpointAndVerifiesReadbackWithoutClaimingCertificateIssuance` |

## 四项独立 authority auxiliary READ

这四项不计入54，不包含Token管理写操作。已有P1 verify两项不是新增aux分母。真实路径的details/catalog读取需要固定schema所列额外Token introspection权限；只有同凭据完整catalog精确accepted label+Zone scope唯一bootstrap，issuer/generation冻结ID，再仅ID求policy。缺权限、IP/未知conditions、目录漂移/expiry/credential变化保持UNKNOWN/撤proof，不从成功READ或公开三态转true。

| operationId | GET path | 固定schema accepted permissions |
| --- | --- | --- |
| `user-api-tokens-token-details` | `/user/tokens/{token_id}` | API Tokens Write, API Tokens Read |
| `account-api-tokens-token-details` | `/accounts/{account_id}/tokens/{token_id}` | Account API Tokens Write, Account API Tokens Read |
| `permission-groups-list-permission-groups` | `/user/tokens/permission_groups` | API Tokens Write, API Tokens Read |
| `account-api-tokens-list-permission-groups` | `/accounts/{account_id}/tokens/permission_groups` | Account API Tokens Write, Account API Tokens Read |

## 可复核本地交付记录

原工作记录位于`.superpowers/sdd/2026-10-07-cloudflare-p2-zones-dns/`：Task1–9报告、A/B1/platform/UI/App契约与SHA、各轮RED/GREEN和root集成证据均保留。B2b源清单digest `d67ec7c9e785036b7f0c8ec5313b18e080095096ff4e0b47d589d640666e30b8`；其24case索引对应实际XML，不把count相加为单轮。Task10最终报告/新SHA另存，不覆盖任何历史。

无提交／merge／push／Tag／Release、无真实云写入。本次本地验证结果见恢复报告；旧失败和历史 artifact 原样保留。台账更新后再次正常执行 coverage6、Python审计349／CI契约10、CLI check及生成字节一致性检查，均通过。
