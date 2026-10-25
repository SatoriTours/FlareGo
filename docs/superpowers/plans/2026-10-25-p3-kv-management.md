# 完整标准 KV 实施计划

> 使用 executing-plans，由当前 Codex 连续实施。用户已批准，无逐步审批或提交步骤。

**状态：** 完整13项本地合成独立验收通过（2026-10-25）；真实云0，整体CF继续。

**Goal:** 原生管理标准 KV 的全部13项现行能力，保留弃用批删迁移记录，通过本地合成独立验收。
**Architecture:** typed KV port/client + literal policies；私有敏感内容 owner / AES-GCM 流式缓存；KV 独立授权 issuer；namespace 互斥协调和不可重放任务；原生浏览器、SAF 文件与三级确认。
**Tech Stack:** Kotlin Multiplatform、coroutines、kotlinx.serialization、Compose、Android SAF、JCA AES-GCM、JUnit / Compose instrumentation。
**Spec:** [已批准设计](../specs/2026-10-25-p3-kv-management.md)。Schema 固定 SHA `06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461`。

## Global Constraints

- root main 当前基线 f76465470fe0933c9f48faebed13b3e1594a0a72；复用当前空间，保留所有既有修改、R2-D原空间与历史证据。单一实现写端。
- 仅合成数据、本地测试；不触真实云或凭据，不提交、推送、Tag、Release、归档。
- 内容与metadata不进入公开state、任务、SavedState或日志；owner只发布随机句柄、长度和代次。缓存仅密文，内存密钥；认证预检完成后方可发送/导出同一封存内容。
- 写入issuer私有授权、最终撤销守卫、namespace锁、UNKNOWN门禁；七写零自动重试。ACK不代表全球传播。
- 证据统一 `~/.local/state/agent-artifacts/FlareGo/2026-10-25-kv/`，临时脚本放 /tmp；命令有界。功能未全部闭合前不声明完整KV完成。

## Review Focus

精确13项method/path及旧DELETE不发送；完整二进制/JSON语义与流式内存上界；缓存标签认证、不可变封存和撤销；授权不可借用/伪造；所有冻结keys可读及三阶段旧回调拒绝；部分结果/UNKNOWN不可重放；P1与历史R2覆盖门禁不被弱化；字号1/2原生视觉。

## Task 1：协议策略与 typed KV 客户端

**Files:** `shared-core/.../cloudflare/protocol/KvPolicies.kt`、`ActionPolicies.kt`；`model/KvModels.kt`、`ports/KvPort.kt`、`cloudflare/kv/CloudflareKvClient.kt`；对应 `KvPolicyTest` / `KvClientTest`。

- [x] RED：现有registry缺12项、POST读/删语义、旧DELETE不可发送；运行 `:shared-core:jvmTest --tests '*KvPolicyTest'`，保留失败断言。
- [x] GREEN：手写13项注册替换已有目录注册，exact keys包括带apostrophe的list-keys。无泛化请求入口。
- [x] RED/GREEN：typed namespace/key/value/bulk契约；单段编码、分页、有效UTF-8 byte上限、expiry、空值、二进制、multipart、完整批量回执。敏感大响应走TransferPort到owner sink；正文不转通用Text。
- [x] 核官方字符/字段/省略语义，逐操作wire测试；所有写429/取消未知不重发，读最多现有有界策略。运行受影响core测试。

## Task 2：敏感内容与加密文件

**Files:** core `ports/KvContentPort.kt` / `application/kv/KvValidation.kt`；platform `KvEncryptedContent.kt` / `KvDocumentFiles.kt`；core/platform对应测试。

**Interface:** owner mint随机handle；stage(input,maxBytes) / authenticated open / bounded private preview / release；公开safe descriptor无正文与路径。sealed ciphertext可二次顺序读取，上传为一次消费source；完整GCM预检先于任何明文交付。

- [x] RED/GREEN：空/25MiB/100MB界限、认证标签破坏/截断/撤销/取消/磁盘失败不泄露前缀、不落明文、只清自己orphan。
- [x] RED/GREEN：流式JSON scanner完整验证bulk10000键、重复字段/键、base64与metadata1024bytes、每值25MiB、整包100MB；保留所有原文及完整keys。bulkget完整响应密文暂存，预览有界，不把100值整体读内存。
- [x] 明文SAF导出显式同意、事务失败清理；大文件不回读源URI；新owner不能解旧缓存。运行core/platform测试。

## Task 3：独立 KV 授权与协调

**Files:** `cloudflare/identity/CloudflareKvWriteAuthorityClient.kt` / `AuthorizedKvSession.kt`、`model/KvWriteAuthority.kt`、`application/kv/KvController.kt` / `KvTaskTargets.kt`，现有runtime/coordinator接线；对应单元测试。

**Interface:** issuer绑定connection/credential generation/account/action和namespace；私有proof非公共observation。复用审核过的token策略解析并核实Workers KV Storage Write，未知政策拒写。runtime准备返回随机handle及安全scope，最终guard以同一冻结draft再检查。

- [x] RED/GREEN：只读/deny/foreign account/撤销/旧proof/跨产品proof/未知策略0写；七写有私有proof。
- [x] RED/GREEN：同namespace互斥、旧dialog/编辑/背景/切scope失效；任务只安全locator，不存值/metadata/URI/命令；UNKNOWN保留写门禁，人工处置单独明确。
- [x] 验证批量部分失败完整failedkeys、非法回执UNKNOWN、ACK与读观察区分，禁止自动重放。运行受影响core/runtime测试。

## Task 4：原生完整入口与文件交互

**Files:** `shared-ui/.../kv/` owner/browser/host/screen、root route、`app/FlareGoViewModel.kt` / `MainActivity.kt` / KV SAF picker；UI/VM/native测试。

- [x] RED/GREEN：账号目录→namespace详情/分页keys→敏感值/meta/expiry；namespace create/get/rename/delete、value读写删、bulk读写删全有可执行入口。
- [x] 私有owner编辑与显式reveal/SecureWindow；64KiB编辑提示及完整文件替代；流式导入导出、明确覆盖/expiry/metadata策略。
- [x] 覆盖与删除独立risk→精确namespace ID→final，冻结全部keys分页；取消/错输入/旧回调0写，三阶段全scope。create/rename最终核对。
- [x] 原生合成路径断言真实wire/结果/任务；两字体实际滚动可读及标题/取消/确认完整。运行UI/VM/native受影响测试。

## Task 5：覆盖门禁与独立验收

**Files:** KV audit matrix/source pins、`KvCoverageTest.kt`、现有P2/R2当前门禁、进度/本计划；外部证据。

- [x] 当前矩阵覆盖全部13 literal handler/test/source；14固定分母保留弃用映射，新增12。历史A/B/C原矩阵/来源快照不追写；当前跨产品registry精确更新并有负向漂移测试。
- [x] 作者完整必要回归、debug / test APK、app/platform lint，记录真实XML计数与失败轨迹。根diff核secret与无关变更。
- [x] 冻结manifest后独立Codex验收关键协议/授权/缓存/原生与两字体视觉，缺陷回当前作者；不按文件拆Agent、不重复无关全套。
- [x] 原会话核实际报告/证据/diff及manifest；仅所用owned设备恢复/关闭。更新本地合成结论与真实云0资格、剩余全CF范围。禁止以局部完成结束本切片。

## 阶段记录

- 2026-10-25：用户批准完整标准KV；当前root复用main进入Task1。usage未知。

- 首批落盘：13项手写策略（首轮3/2失败→3/0）、typed port/client十一项wire测试（首轮11/11失败→11/0）、字段4/0；gateway48/0、平台URL13/0。完整客户端仍待bulk流式校验接线/授权/原生验收，未声明切片完成。
- Task2提前按依赖实施：AES-256-GCM每64KiB独立记录，nonce=每文件随机8bytes前缀+记录序号，AAD绑定头/序号/长度，认证结束记录绑定总长度/块数；认证全文件后重新读取同一owner密文，无完整明文缓冲。缓存6/6 RED→6/0，30MiB生成流读写实际通过；仅作者结果。
- 已新增bulk流式JSON八项RED（8/8失败）并实施scanner，验证进行中。当前尚无授权issuer/原生入口/独立KV结论，usage未知。

- Task2：bulk 八项 GREEN；完整读响应验证后才发布、写正文/keys/expiry 发送前再验证，两项集成 RED→GREEN。当前核心 KV 28/0、缓存 7/0；SAF 与原生入口仍待实施。
- Task3：issuer 10/10 RED→10/0，首轮正向失败原因为测试夹具缺严格 envelope 字段（仅修测试）。正式 AuthorizedKvSession 七写刷新 proof/最后守卫，12/0。协调八项 8/8 RED→8/0，使用共享 OperationCoordinator、namespace 锁和持久化 UNKNOWN 门禁；人工解除只标记 ACTION_REQUIRED，云端结果未知。共享协调器回归进行中，尚无独立 KV 结论。

- Task4：核心 KV 54/0、平台 KV 14/0，UI owner/browser 12/0；正式 MainActivity 字号1.0 八项8/0、真实 SAF 两项2/0。首轮夹具缺policy id及精确文本、后续截图键盘焦点和Lazy列表未组合节点均保留失败历史后修正测试；取消前移交源关闭缺陷已由作者修复并回归。字号2.0、最终全回归和独立验收仍待完成。
- Task5：KV4/P2 6/R2 16门禁均0失败；当前registry119=60READ/59WRITE。新增KV当前跨产品来源快照，冻结P2和A/B/C原文件均不追写；有缺失、漂移、错handler及弃用混入负测。usage未知。

- 作者交验：首轮完整core1162仅两项旧计数失败（107→119），UI131/platform126/app8均0；精确计数及门禁相关57/0，原失败保留。新增SAF事务返回取消/拒绝清理和终态编辑清理回归后，文件7/0、全UI132/0、KV gate4/0，最新Debug/test APK与lint通过；Python349/0、CI10/0。
- 字号2.0末轮受影响原生八项8/0（56.888s），真实SAF两项在前一轮2/0；含实际密文缓存删除、原编辑清理、完整失败键和UNKNOWN门禁。先前10/4、4/1、3/1、10/1及单项/诊断通过均保留，未拼作一次全量绿。原生同步采用正确Lazy容器、真实窗口焦点与目标可见性条件，不用盲延迟。两字体关键original截图已由作者核看，独立验收待进行。
- 交验冻结：本轮只有当前Codex实现写端；源码/文档manifest与作者报告统一外部证据根，复用同空间现有Codex QA，不派Pi、不新建空间；交验期间原端停止源码、Gradle与设备操作。usage未知。

- 最终独立：core112/UI16/platform29/app8共165/0，实际Main两字体各10/0，独立合计185/0。original看53张，G1仅2.0 metadata遮蔽字段缺图；作者临时capture-only定向1/0后逐字节恢复，原QA实际看补图追加完整13项通过，未把补拍记独立重测。报告SHA `3ad6c00290fa3a4d277096b982be71c32a10e1ea07ec6e87ae0bb5b68a0276b0`。
- 父最终：复核报告/断言/图/210manifest/精确恢复及Git身份，产品和测试来源不变，文稿收尾差异另存final-validation.json；owned API36字体1.0后关闭，其他设备与adb server未动。真实云0/API26–28未测，不提交发布归档；整体CF active，D1已有只读预研，实施待完整方案一次确认。usage未知。
