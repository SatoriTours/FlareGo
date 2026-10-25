# 完整 D1 13 项实施计划

> **For agentic workers:** 使用 superpowers:executing-plans，由当前Codex连续实现、自测和普通修复，最后独立Codex验收。用户已于2026-10-25选择完整13项，不再审批文稿或执行步骤。

**Goal:** 将现有D1目录扩展为完整13项typed/native本地合成管理，包括SQL/结果、完整文件管线及恢复。

**Architecture:** D1独立领域类型、会话和权限issuer；复用固定API网关、协调器与加密缓存/SAF的底层机制，保持KV owner和资格隔离。任意用户SQL均是潜在写入；私有factory提供固定受控浏览。缺官方精确权限和签名host/path证据的生产入口关闭，合成fixture经内部注入。

**Tech Stack:** Kotlin Multiplatform、kotlinx.coroutines/serialization、Compose、现存OkHttp Android传输、AES-GCM和SAF，API floor26。

**Spec:** [已批准完整设计](../specs/2026-10-25-p3-d1-management.md)。批准前SHA `37341d76cbcb227fb9b01e0b566d73c9f1eadd0ed29f52b00964d47a2eaf9453`。

## Global Constraints

- 当前 `/home/jimxl/projects/FlareGo`、main、既有Workspace/Tab原地直做；不创建空间/worktree/分支，不整合其他树，不触dreamtools。
- 本轮仅本地合成；不调用真实云/凭据/付费/E1，不Git提交/推送/Tag/Release/归档。
- 原schema及历史KV/P2/R2矩阵字节固定；D1当前schema supplement独立版本化。13路由中已有list1项，新增12项后131＝62READ/69WRITE；审计JSON不作运行时注册。
- 任意SQL单条/多语句/batch需要新鲜D1写proof和三级确认；零自动重试，不用首词推断只读，不拆分号、不改SQL原文。
- 单statement100000bytes，每query100 string params；整个API含batch30秒。文件完整流5,368,709,120 bytes（Long），编辑器65536bytes，编辑器限制不缩小文件入口。
- SQL/params/结果/表与列/文件/签名URL私有，实际SecureWindow、遮蔽/显式reveal；公开状态和任务不存正文/URI/URL/MD5。云终态释放输入，独立结果会话留完整加密结果直到撤销；SAF任务独立封存副本。
- 首个潜在副作用前持久化RUNNING；同DB跨动作共享锁，UNKNOWN/PARTIAL/processing门禁持久化，不自动重试、不按标题认领。
- 字号1.0/2.0实际Main与真实SAF本地验证，独立原图语义检查。API26–28/真实云未测如实保留。

## Review Focus

1. init命中已有文件可直接开始导入；确认必须在init前，直接分支不能再次upload/ingest（Task4）。
2. SQL触发器/字符串含分号、多结果及内部失败；不截断、重写或把外层成功当全成功（Task2/3）。
3. 云终态立即释放输入同时结果可查看/导出；后台撤销后迟到结果不得恢复（Task5/6）。
4. 跨主机上传不带API token/cookie，拒绝redirect/不可信URL；生产缺证不能靠fixture放行（Task4）。
5. SQL未知结果跨动作/重建保持阻挡，取消与SAF发布失败不冒成功（Task5/6）。

## Task 1: 领域验证和13项策略

**Files:** 新增 `model/D1Models.kt`、`ports/D1Port.kt`、`application/d1/D1Validation.kt`、`cloudflare/protocol/D1Policies.kt`；修改 `ActionPolicies.kt` 由新注册替换已有list；新增 `D1ValidationTest.kt`、`D1PolicyTest.kt`。

**Interfaces:** `D1DatabaseRef(account: AccountScope, id: String)`；`D1CreateInput(name, jurisdiction?, primaryLocationHint?, replication?)`；`D1Validation.databaseId/name/create/exportOptions/params`；`D1Session`明确定义13方法，query/raw共享 `D1SqlInput`，受控浏览独立不可任意构造的 `D1BrowseRequest`。

- [x] 先写缺功能RED：UUID/名称非法拒绝、params101拒绝/100保留、全库空tables明确、no_data/no_schema双true拒绝、13literal方法/风险/零重试及旧list不重复。
- [x] 实现不可变类型/验证/受控只读factory及注册；危险值的toString脱敏。
- [x] `timeout 600s ./gradlew :shared-core:jvmTest --tests '*D1ValidationTest' --tests '*D1PolicyTest'` →0失败；登记RED/GREEN。

## Task 2: 完整私有SQL输入及结果流

**Files:** 新增 `ports/D1ContentPort.kt`、`application/d1/D1SqlJson.kt`、`D1ResultJson.kt`、`D1Content.kt`；必要时抽取KV已验收缓存底层，不改变KV产品API；新增D1SqlJsonTest/D1ResultJsonTest，平台D1EncryptedContentTest。

**Interfaces:** `D1Content`只提供随机descriptor、`openVerified(): ByteSource`与close；`D1SqlInput`由完整验证的SQL或typed JSON文件产生；`D1ResultDocument`持有完整加密响应与安全摘要；预览 `page(index,size)`按有界段返回，仅私有owner消费。

- [x] RED：params只string、single/batch原文含注释/CTE/PRAGMA/触发器不改写；大于编辑器仍文件完整；畸形UTF8/JSON/重复字段/截断拒绝；query对象/raw列行所有类型和数字原文、单字符串多结果/内部false保留。
- [x] 实现有界完整扫描、加密封存、认证后同ownedsealed文件重读；不建立全份明文树；大结果分页有可达完整文件出口。
- [x] 测试完整5GiB及+1生成流、25MiB以上、不进展流、取消关闭/完整性篡改；流式JSON边界测试与平台缓存回归通过。

## Task 3: typed固定API客户端

**Files:** 新增 `cloudflare/d1/CloudflareD1Client.kt`、`D1Wire.kt`；测试D1ClientTest、平台D1WireTest。

**Interfaces:** `CloudflareD1Client(connection,gateway,transfers,contents,beforeMutation): D1Session`；list/get/bookmark只读；create/PUT/PATCH/delete/undelete/query/raw/import/export/restore发送前最后守卫；大SQL请求和结果走流，不经HttpResponse整份bytes。

- [x] RED：13路径/方法/编码/可选字段literal wire、UUID与账号错配、404/202/缺字段/逐项失败、未知字段保留、provider正文不入错误；query路由不自动重试。
- [x] 实现schema精确字段/分页、原文stream JSON编码、独立受控浏览、timestamp解析bookmark和previous_bookmark；不承诺SQL事务回滚。
- [x] core客户端与MockWebServer wire定向验证通过，源/响应关闭每路径均有断言。

## Task 4: 完整导入/导出管线及跨主机传输

**Files:** 新增 `application/d1/D1FilePipeline.kt`、`ports/D1SignedTransferPort.kt`、平台 `D1SignedTransfers.kt`；测试D1FilePipelineTest、D1SignedTransfersTest。

**Interfaces:** pipeline `startImport(ref,confirmedInput)`、`startExport(ref,confirmedOptions)`、`continuePoll(locator)`；locator只绑定任务/账号/DB/动作/受审bookmark，不含命令；signed port私有工厂绑定API响应及输入owner。

- [x] RED：init直接处理/完成零upload/ingest；upload分支MD5/ETag/长度一致后只一次ingest；各阶段unknown不重启；每次签名传输无API Authorization/cookies、拒绝IP/用户URL/redirect、错误/取消关闭source/sink。
- [x] 实现有界前台轮询（30次或120秒先到为止，单次有timeout），用户取消/后台停止后诚实unknown；显式继续只poll旧locator，禁止重复启动。生产四操作与签名证据门禁先于发送。
- [x] 导出完整下载加密后交结果owner，1小时过期不重启；空表范围/末页、virtual-table官方错误、不改SQL/删表；定向合成管线与平台测试通过。

## Task 5: 权限issuer、确认和持久化协调

**Files:** 新增 `model/D1WriteAuthority.kt`、`cloudflare/identity/{D1TokenPolicyEvaluation,CloudflareD1WriteAuthorityClient,AuthorizedD1Session}.kt`、`application/d1/{D1Mutation,D1WriteRuntime}.kt`；修改OperationCoordinator支持D1定位；新增D1WriteAuthorityTest/D1WriteRuntimeTest。

**Interfaces:** issuer绑定credential revision/generation/account/op/DB与短租期；runtime `prepare → risk → typedUuid → final → submit`句柄只内存。result owner移交独立于云task终态。生产missing映射无公有合成通行证。

- [x] RED：全部10个潜在副作用权限及最后guard、deny/过期/跨产品/跨DB拒绝；4缺证生产零send；SQL自定义不能借readonlyfactory，readonly固定查询可发。
- [x] RED：字段变化/后台/重复确认零重复发送；SQL未知跨动作/重建阻挡、人工关门禁仅ACTION_REQUIRED；create缺UUID不按名称认领。
- [x] 实现同DB共享锁、持久化状态、受审locator，仅用户显式继续poll；安全任务摘要不含正文，定向authority/runtime与受影响协调回归通过。

## Task 6: 原生私有SQL、结果、文件与管理入口

**Files:** 新增 `shared-ui/.../d1/{D1ContentOwner,D1Browser,D1BrowserHost}.kt`、app `D1DocumentBridge.kt`；接入FlareGoViewModel/MainActivity/ManagementPlatformOwner/FlareGoApp/Screens/SecureWindowPurpose；测试D1ContentOwnerTest/D1BrowserTest、app D1ManagementUiTest。

**Interfaces:** publicstate仅安全资源身份/长度/句柄/状态；privateowner只在SecureWindow内提供文本页；DocumentBridge复用底层SAF，不直接把Kv产品类型带入D1界面。

- [x] RED：Resources实际入口、list分页/详情、create/复制old-new、undelete UUID/new_name、query/raw/single/batch、只读schema/table/index浏览、TimeTravel双方式/previous bookmark新确认、完整import/export。
- [x] RED：云终态输入清掉但完整结果仍可分页/SAF；离页/后台/切账号/迟到拒绝，所有terminal文件任务清理；默认遮蔽/reveal与保护窗失败拒绝。
- [x] 实现固定标题/取消/确认及可滚动完整范围（SQL/params/表列表末页），无私有正文进入普通StateFlow或SavedState；受影响JVM验证与actualMain合成关键链路/SAF字体1.0、2.0通过并实际阅图。

## Task 7: 覆盖门禁、回归和独立验收

**Files:** 新增D1CoverageTest、`docs/cloudflare-audit/d1-runtime-support-2026-10-25.json`；修改当前P2/R2/KV严格门禁的D1来源overlay；交付文档development-progress与本计划/设计。外部证据统一 `~/.local/state/agent-artifacts/FlareGo/2026-10-25-d1/`。

- [x] literal13与131精确计数、固定schema12＋新增undelete独立supplement、正常缓存源码漂移拒绝/恢复；历史矩阵字节不追写，不把sourcehash当云授权。
- [x] 作者适当全模块JVM/Debug/testAPK/lint、Python审计/CI契约及实际Main/SAF两字体，完整记录失败/修复，必要项未过不交验。
- [x] 冻结manifest/HEAD/index后复用已批准独立Codex，仅一写端/设备端；独立关键JVM/native/视觉与必要回归，问题回作者修复并窄复验，父核diff/证据后才写结论。
- [x] 明确本地合成/真实云0/API26–28未实测/缺证入口关闭；收尾只停owned设备，保留全部现存混合成果，不Git提交或归档。完整13项交付后回全CF主线。

## 执行台账

批准已收到。原设计独立校核重要项已由负责人闭合，无新增产品决定。执行记录/命令完整输出在外部证据 `2026-10-25-d1/progress.md`。本计划自检：设计1–3→Task1/3/5，4→Task2/6，5→Task3/5/6，6→Task4/6，7→Task5/6，8→Task7；接口按领域类型→内容→客户端→管线→runtime→UI顺序，单写端。用户不提交优先于技能commit步骤，复用当前空间优先于技能worktree步骤，必要独立验收优先于技能禁止复验默认。

### 作者交验快照（2026-10-25）

Tasks1–6作者实现/自测完成，Task7独立验收待执行，不将作者通过写成独立通过。完整JVM1222 core＋143 UI＋142 platform＋8 app均0失败/错误/跳过；Debug/testAPK、app/platform lint通过，Python审计349/0、CI10/0。D1定向core60/UI11/platform14（具体由独立XML复核）；注册131＝62R/69W，D1为13＝3R/10W。普通缓存实际源码注释漂移使D1Coverage 4/1失败，逐字节还原后4/0，未使用强制重跑。

实际Main/真实SAF：当前1.0完整10/0（93.649s），2.0完整10/0（111.821s）；最后仅新增恢复显式显示截图/断言，1.0恢复定向1/0（11.071s）。全部使用合成连接/传输，无真实云或凭据。原失败、环境/命令错误、普通返工保留外部progress，不累计重叠用例。作者已实际阅必要原图（完整SQL/params、表列表末页、恢复时间/bookmark、完整回执、SAF完成），独立须据当前快照自行执行关键路径及语义视觉。

生产import/export/restore/undelete及签名传输仍因官方精确权限/host-path证据不足关闭，合成DI仅内部测试。5GiB及+1为有界生成流边界，不冒充实际5GiB加密落盘或真实云；API26–28未实测，整体CF未完成。完整作者报告和冻结manifest在外部证据根，所有权串行交原已批准独立Codex。

### 独立首验与 I1/I2 返工（历史阶段）

首验JVM203/0、原生两字体各10/0，actual original38路径，257来源前后不变；仍因两处Important不通过，独立报告原文保留（SHA `0e4b333c333525535c519e1020a6fa1cbc30ab8e49642ebcb057286e5d878939`）。I1确认视图冻结connection label、使用冻结account.connectionId作为稳定ID，在风险/typed/FINAL均显示；同账号改名/换连接使旧确认失效，新连接另准备。I2目录使用medium圆角矩形与16dp留白，完整行自适应字号。

新增实际Main断言先真实1/1 RED（找不到连接名称）→修后受影响core44/UI11均0，runtime含新同账号多连接冻结/失效断言14/0；Debug/testAPK和app lint通过。当前两字体各定向3/0（53.681s/51.594s），覆盖目录/CRUD、query完整最终确认和导入/完整表末页导出；作者单张original核I1/I2和表末页可读。未重复首验223/全量1515；最终结论待原独立Codex窄复验。证据 `2026-10-25-d1/rework-*`，新manifest与旧257基线增量绑定，不覆盖原冻结材料。

### 最终独立结论：完整13项本地合成通过

原独立Codex实际复验core44/UI11=55/0，Main query与目录CRUD在1.0/2.0各2/0（25.905s/33.034s），本轮共59次，failure/error/skipped均0；单张original实际阅5张新图，I1冻结连接身份、I2目录裁字均闭合，无新增Important。257项验前后0差异，精确9delta/248不变，原schema/历史矩阵/HEAD/index/APK均匹配。父已核报告、快照和原生日志，并实际看两张2.0关键原图；不重复跑全套。

最终[复验报告](/home/jimxl/.local/state/agent-artifacts/FlareGo/2026-10-25-d1/independent/recheck-report.md) SHA256 `8ce7716cecf108ffabb25617a53386ab1d00cc02af6bf660f605da233c5545a5`，原不通过报告及失败轨迹保留。源码/测试验后不改，收尾仅三份文稿，最终核验见外部 `final-validation.json`。真实云0、生产四项及signed准入关闭、API26–28未测、5GiB生成流限定继续保留；整体CF未完成，无Git提交/发布/归档。
