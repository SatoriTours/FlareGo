# P1 Task 9：应用集成与本地验收

核对日期：2026-10-15。基线 `98cc0c9059dc3c2a55a4a3cbf2473cd29c0ec9ad`，执行分支 `feature/cloudflare-foundation-completion`，Paseo workspace `wks_e60bb589a4c1ce32`。本批次 Task 6–9 与迁移测试修正均未提交、合并或推送；主线代码保持原基线。原协调会话继续负责交付。

## 结论和实际行为

Task 9 已在隔离分支完成实现与本地工程验证；P1 的九项开发任务已有实现，本地工程出口通过。复核由当前会话完成，遵循用户明确选择；没有再次向外部 provider 发送源码，不称为独立外部复审。主线集成、签名 Release 重建和真实云账号验收尚未完成，P2 产品任务未开始。

- 默认按页面加载：域名只读 Zone，资源只读当前 Workers／R2／D1／KV，账单默认只读当前账号历史。总览不自动查询全部产品，提供明确的“刷新全部产品”。每类独立保存数据、loading、错误、页位置、总数、凭据版本和观测时间。
- 切换账号取消旧读；凭据替换、A→B→A、同一 ID 的账号元数据替换、挂起的服务工厂和迟到响应均受 scope／revision／epoch 约束。外账号资源不能打开。
- 列表续页追加并去重，拒绝跳过首页和重复位置；首页刷新失败保留旧数据但清除旧续页。没有供应商总数时不宣称完整，总览不把未加载资源或激活数量显示为零，未知用量不补零。
- Workers 默认现代目录；旧 scripts 目录必须主动选择。账号账单失败不会自动改读用户账单；用户旧账单保留独立且明确标注的入口，可能跨账号。没有可信用户 ID 时不构造 UserScope，也不把旧用户记录包装成账号 BillingEntry。显式 demo 使用独立数据和示例标识，正式应用仍不预置 demo 连接。
- 生产 ViewModel 组装任务运行时、原有确认执行器和真实 task store；启动恢复只读写安全元数据，不恢复敏感 payload，不重发写入。运行中断转待核对，排队中断转待处理。删除连接共享 metadata latch，并异步执行本地事务，不阻塞 Main。
- DNS 确认命令的实际输入只保存在内存，通过独立当前 scope、凭据版本和 UI epoch 校验后发送；UI 取消等待不取消 app lifetime 的执行。旧 Unit DNS 接口无法保留完整 receipt，所以返回 UNKNOWN。只读核对可通过已有记录的完整目标哈希一致确认 PATCH；创建缺少 receipt ID、删除缺少完整缺席证明，重新读取后仍为 UNKNOWN，需要用户查看 DNS／官方控制台，绝不重发。
- 所有生产 gateway 注入实时时钟和 HTTP-date 解析器；任务列表刷新失败不覆盖已持久化的权威写入结果。旧聚合目录仅保留可选兼容构造路径，生产与 demo 目录使用新 loader。

## 实际验证

使用缓存 JDK 17、Android SDK、离线依赖及本任务专用临时 API 36 模拟器。先运行构建／单元任务，再独立运行设备任务，避免设备失败提前阻止 JVM 测试；最后修改后均完整重跑。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| core JVM | 285／285，0 跳过 | 既有 260 + ProductLoader／controller 16 + AppOperationRuntime 9 |
| Android 平台单测 | 82／82，0 跳过 | Client 10、Protocol 47、Transfer 19、Fingerprint 3、UpdateDownload 3 |
| 原生 API 36 | 49／49，0 跳过 | 原有 37 + ProductLoadingTest 12；包含 9 项迁移，实际 ViewModel／数据库恢复、现代及旧目录、两种账单、DNS 一次写入与只读核对、非阻塞断开 |
| Debug 应用／测试 APK | 构建通过 | 未重建签名 Release，未做 APK 升级安装验收 |
| app／platform lint | 0 error，分别 3／4 warning | GradleDependency／UseKtx；不是零警告 |
| 审计／发布契约 | 333／10 项通过，无跳过 | 固定公开 Schema 缓存，本地测试；无发布 |

```sh
./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  :android-platform:lintDebug --offline --no-daemon
./gradlew :app:connectedDebugAndroidTest --offline --no-daemon \
  -Pandroid.injected.device.serial=emulator-5582
python3 -m unittest discover -s tools/cloudflare_audit/tests -v
python3 -m unittest discover -s tools/ci/tests -v
```

本地日志为 `/tmp/flarego-task9-final-build2.log`、`/tmp/flarego-task9-final-device2.log`、`/tmp/flarego-task9-audit.log`、`/tmp/flarego-task9-ci.log`；工作树源文件 SHA-256 与测试汇总保存于执行空间 `.superpowers/sdd/2026-10-07-cloudflare-p1-foundation/task-9-verification.json`。上述临时日志不是长期交付依赖，结论与边界保存在本文。

## RED 与复核修正

1. 加载器首轮 9 项测试 8 失败；加强唯一空跑的取消测试以断言请求已启动。加载器通过后，controller 3 项测试实际 RED，再完成接线。
2. 任务运行时首轮 8／8 实际 RED。原生首轮 7／7 实际 RED，生产 VM 明确复现一次请求全部目录和用户账单。首次接线后 5／7 通过，另外两项发现 Compose 启动与 navigate 重复请求，改用幂等 start。
3. 显式旧账单／Workers 入口新增原生 2 项 RED；旧账单及失败首页续页新增 core 2 项 RED。一次测试把 b 排除在已登记账号外，修正夹具后保留原有切换断言；未放宽行为要求。
4. 本地复核新增 core 2／native 2 项实际 RED：账号元数据替换后留旧 scope、迟到旧账单错误污染资源页、断开阻塞 UI（暂停 IO 实测 1203ms）、未加载域名显示假零。均在同一修复轮处理。
5. 写入后的 finally 列表刷新异常会覆盖权威 UNKNOWN 结果；新增运行时回归实际 RED（9 项中 1 失败），修复后从最终源码重跑全部测试。

当前有限本地复核没有未解决的 Critical／Important。旧 T5 的 NOT APPROVED 是历史结论，原缺口已有后续测试和本地复核；不改写历史为独立审核通过。

## 证据等级与剩余边界

| 能力 | 协议／隔离测试 | 原生装配证据 | 真实账号 |
| --- | --- | --- | --- |
| 账号发现／Token 类型 | T6 core 19 | T6 原生及本轮全套回归 | 未执行 |
| Zone／现代与旧 Workers／R2／D1／KV | T7 core 22 + 本轮 loader | 原生导航／分页及生产现代与旧 Workers | 未执行 |
| 账号账单／显式用户旧账单 | T8 core 17 + 本轮来源隔离 | scoped 组件及生产两种 API 装配 | 未执行 |
| 任务恢复／断开／只读核对 | T5 基础 + 本轮 runtime 9 | 真实数据库恢复、DNS PATCH 核对、暂停 IO 断开 | 未执行 |

主线冻结审计基线 `implemented.json` 仍登记原 11 项，真实 verified／capability_verified 均为 0。隔离分支生产装配覆盖原 11 + 新增 5 项技术调用，手写策略共 16；新增为账号发现、两类 Token verify、现代 Workers、账号账单。没有因代码接线就提升真实资格，也没有自行重生成或扩大固定 3,659 项台账白名单。集成到主线时应按实际提交更新源码基线与对应 L1 台账。

未执行真实 Cloudflare 操作、S3／GraphQL／实时协议验收、其他 Android API 版本、签名 Release／APK 升级、当前远端 Actions 查询。没有 Git 提交、合并、推送、Tag、Release 或 workspace 归档；工作区有待保留成果，继续复用。

## 实施裁决记录

以下保留本计划执行记录中的裁决，包括被后续证据替代的同步断开方案。当前有效行为以本文结论和最终源码为准。

- Task4: Ruling: 遵循 c98bc39 的固定安全文案与 pollPath 所属账号契约；修正测试预期和账号参数，保留整行元数据比对，不修改生产持久化逻辑。若判断错误，原始说明文字仍不会往返；该行为已有 common codec 测试及安全约束支撑。

- Task6: Ruling: fixed schema /accounts per_page is5..50; reject unsupported cursor and out-of-range limits instead of silently sending invalid paging. Add dedicated CloudflareCapabilityClient as CapabilityPort implementation, explicit readonly probes only, no token-verification-derived permissions; no probes of writes/unknown actions. Cost if wrong: adjust endpoint-specific validation/probe allowlist, never broaden token scope automatically.

- Task6: Ruling: user continuation authorizes development, but this session has not requested commits/merge/push; leave concrete changes reviewable and uncommitted. Existing plan commit checkbox stays pending. Cost: integration waits for explicit authorization.

- Task7: Ruling: fixed schema paging bounds are Zone5..50, modernWorkers1..100, R2cursor1..1000, D1number10..10000, KVnumber1..1000. Legacy scripts is unpaged: reject continuation tokens, send no paging query. Cost if wrong: update endpoint-specific rules against new pinned schema, never silently switch endpoints.

- Task7: Ruling: append optional source/legacy/resourceRef/deployedOn fields to CloudResource (default values preserve callers); use distinct worker vs worker_script reference kinds. Source action key plus account-bound ResourceRef preserves identity and prevents name-based cross-scope mixing. DemoDirectory only accepts explicit demo connections and uses in-memory number pagination. Cost if wrong: adapt future product DTOs before mutable resource actions, not guess identities.

- Task7: Ruling: extract existing unchanged typed-read/error-sanitization helper to protocol/RegisteredReads.kt, so directory does not depend on identity adapter. T6 client uses the same behavior through the shared helper. Cost if wrong: all221 prior core tests plus directory tests must catch regression before delivery.

- Task8: Ruling: pinned account billing uses number paging with min1 and no documented max; user legacy min5/max50. Amount must be JSON number lexical text (no Double), missing/null stays unknown; optional account metadata stays nullable, no alternate amount_to_pay/URL-based identity guesses. Cost if wrong: revise endpoint-specific parser against pinned schema, never fabricate a charge.

- Task8: Ruling: explicit legacy UserScope is bound in client constructor and must match history scope; default client supports account reads without enabling user fallback. User owner identity must be supplied explicitly, not derived from account or token ID. Existing legacy UI remains available; production typed service assembly belongs to Task9. Cost if wrong: provide a verified owner when integrating user mode; don't weaken account isolation.

- Task8: Ruling: add a scoped billing presentation component to Screens with scope/row guards, and retain existing legacy screen with explicit old-interface label. Add native presentation tests because UI labels/unknown amounts are part of this task. Cost if wrong: adjust Task9 composition API while preserving tested scope/label behavior.

- Task9: Ruling: loader takes an additional real-connection resolver and clock — AccountScope alone cannot supply demo/name or detect removed/replaced connection metadata; costs one internal assembly parameter. AllPagesLoaded requires a reported total equal to deduplicated loaded count and next=null; absent metadata remains unknown. Cost: providers omitting totals never receive a complete badge.

- Task9: Ruling: introduce DnsOperations interface and app-lifetime runtime around the existing lifecycle — existing lifecycle cannot bind independent UI input itself. Runtime hashes the in-memory actual confirmed command plus selected scope/revision/epoch, never plan fields; owns app-scope jobs and shares metadata deletion latch. Cost: more integration surface, covered by runtime cancellation/removal tests.

- Task9: Ruling: read-only reconciliation may confirm an exact patch record/hash match; create lacks provider receipt identity and legacy absence cannot prove delete, so these remain UNKNOWN after reading. No replay and no persisted payload. Cost: user must inspect creates/deletes in DNS/official console until receipt-aware P2 support.

- Task9: Ruling: preserve synchronous disconnect API using a short IO-dispatched local deletion transaction under the metadata latch; no cloud request occurs during deletion. Cost: UI can briefly wait for local storage. Caller-side late results cannot recreate deleted rows.

- Task9: Ruling superseding synchronous-disconnect ruling: disconnect must launch app-lifetime removal and return to UI immediately; keep metadata latch/IO deletion, await completion in native fixture cleanup. Prior synchronous design can deadlock while a writer awaits Main resumption. Cost: disconnect is asynchronous, errors are fixed safe UI text and account removal completes after the local transaction.

- Task9: Ruling: retain old user billing through an explicit separately labeled legacy screen/CloudSession read; the connection has no trusted user ID, so never fabricate UserScope or convert those rows to account BillingEntry. Modern account history alone uses ProductLoader. Explicit Workers legacy uses a separate optional directory service; no automatic fallback. Cost: compatibility paths remain until P2 migration, with guarded scope/revision/publication.
