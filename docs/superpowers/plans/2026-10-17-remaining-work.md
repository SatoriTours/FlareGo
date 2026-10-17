# FlareGo 剩余任务规划（P1／P2 收尾与 P3 立项）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「计划里尚未完成的任务」拆成两层——已实现但按计划口径未结清的收尾项（可立即执行），和尚未立项的新产品批次（需先出规格）——并给出可验收的任务清单。

**Architecture:** 不新增产品源码。先让台账与实际代码一致（WS-A／WS-B／WS-C），再把 P3 交给 brainstorming→spec→plan 的既有流程（WS-E）。真实云资格（WS-D）与 P4–P9（WS-F）保持阻塞或路线状态，不因本次规划提前开工。

**Tech Stack:** 沿用 Kotlin／Compose／SQLDelight／OkHttp；Python 标准库审计工具 `tools/cloudflare_audit`；Gradle 测试命令见各任务。

**Spec:** `docs/cloudflare-coverage-audit.md` §3、§8、§9、§10；`docs/superpowers/plans/2026-10-07-cloudflare-p0-p2-index.md`；`docs/development-progress.md`。本规划不改变已批准边界。

## Global Constraints

- 核对基线：`main` @ `6823394`（含 P2 功能提交 `fbbb954`）。
- 不创建、移动、删除 Tag 或 Release；不推送、不合并，除非用户另行明确授权。
- 不修改 `/home/jimxl/projects/dreamtools`。
- 不创建 Paseo Workspace／worktree；本次工作全部在当前 workspace 内完成。
- 不把「计划复选框未勾选」写成「功能未实现」，也不反向把「代码存在」写成「已验收」。
- 真实云／付费资格计数保持 0，直到有授权环境 evidence；不缩短分母、不伪造排除。
- 提交只包含本任务明确列出的文件，不使用 `git add .`。

## Review Focus

1. 文档勾选（WS-A）与代码现状不符时，勾选必须附可核对的证据引用，否则保持未勾选并写明阻塞理由。
2. P1 Task5 Step3 的「生产接线」文本已过时（见 §一），必须先核验再决定勾选或补实现，不能直接勾选。
3. 设备测试 `Zone invalidCreateName` 曾失败 1 次、未复现（`p2-runtime-support-2026-10-17.json.final_regression.known_test_limit`）；不得因为「定向复验通过」就宣布稳定性修复。
4. 台账同步任务改的是审计文档，不得顺手改动 `endpoints.csv`／`review.json`／`pinned-openapi.json` 的固定快照值。
5. P3 立项必须先出规格（WS-E1），不得在无规格时直接派发适配器开发。

---

## 一、核对结论（2026-10-17）

### 1.1 五份计划的实际状态

| 计划 | 计划勾选 | 代码状态（本次核对） | 差距性质 |
| --- | --- | --- | --- |
| [P0 覆盖台账](2026-10-07-cloudflare-p0-coverage-ledger.md) | 25 勾选／0 未勾选 | 已交付 | 无 |
| [原生首版](2026-10-07-native-app.md) | 11／0 | 已交付并在主线 | 无 |
| [构建与应用更新](2026-10-07-release-updater.md) | 12／0 | 已交付并发布过 | 无 |
| [P1 公共基础](2026-10-07-cloudflare-p1-foundation.md) | 38／**7 未勾选** | T1–T9 已实现并合入 `f8840c2` | 台账同步债 |
| [P2 Zone／完整 DNS](2026-10-07-cloudflare-p2-zones-dns.md) | **0／50 未勾选** | T1–T10 已实现并合入 `fbbb954` | 台账同步债 |

### 1.2 支持 1.1 的代码级证据

- P2 端口与客户端齐备：`core/ports/` 下 `ZoneManagementPort`、`DnsManagementPort`、`DnsFilesPort`、`DnsScanPort`、`DnssecPort`、`ZoneSettingsPort`、`DnsBatchEvidenceStore`；`core/cloudflare/dns/` 六个客户端、`core/cloudflare/zones/` 两个客户端；`core/cloudflare/generated/DnsSchemas.kt`（21 类）与 `ZoneSettingSchemas.kt`；`tools/cloudflare_audit/generate_dns.py`；`coreTest/P2CoverageTest.kt`。
- 运行时注册表实测 69 项：`p2-runtime-support-2026-10-17.json` → `runtime_registry_observed.count=69`（39 读／30 写），来源 `P2CoverageTest.actualRegistryHasExactly54CandidatesP1Union65AndFourSeparateAuxReads`；54 候选 + P1 16（重叠 5）= 65，加 4 个独立 authority 读 = 69。
- 本地回归：`final_regression` core 706／0 失败、platform 91／0 失败、Python 审计 349＋CI 契约 10、Debug 与 lint 通过。
- P1 Task5 生产接线已存在：`AppOperationRuntime.kt` 内联 `OperationCoordinator` 与 `DnsOperationLifecycle`；`app/src/main/kotlin/com/flarego/FlareGoViewModel.kt:100` 实例化 `AppOperationRuntime`；`prepareExtended` 校验 `credentialRevision` 与 `stillCurrent()`；`FlareGoViewModel.disconnect()` 调用 `extendedAdapter.invalidateConnection` → `controller.invalidateSession` → `retireCredential` → `operationRuntime.removeConnection`。
  → 因此 `2026-10-07-cloudflare-p1-foundation.md` Task5 中「生产接线及断开协调尚待应用集成」为**过时文本**，需核验后改写或勾选。

### 1.3 计划间互相矛盾的地方（本次要修的）

| 位置 | 现有文本 | 与代码冲突 |
| --- | --- | --- |
| `2026-10-07-cloudflare-p2-zones-dns.md` 头部「集成前状态（2026-10-15）」 | 「本计划 Task1–10 均尚未开始产品实现」 | 已全部实现 |
| 同上 | 50 个步骤全部未勾选 | 已实现并验证 |
| `2026-10-07-cloudflare-p0-p2-index.md` 开头 | 「P2 十项尚未开始产品实现」等多处历史叠加表述 | 已集成 |
| `docs/development-progress.md` 阶段表 | 「[P2]：10 项均未开始产品实现」 | 已集成 |
| `docs/development-progress.md`「P2 逐任务状态（历史：2026-10-16）」 | 十项「未实现」 | 已集成（该表标注为历史，但未指向新表） |

### 1.4 真正未完成的部分（本次规划的靶子）

| 编号 | 未完成内容 | 性质 | 可否现在开工 |
| --- | --- | --- | --- |
| WS-A | P1／P2 计划勾选、总览与进度文档同步 | 文档／台账 | 可以 |
| WS-B | P1 Task5 遗留步骤核验 + 全套回归证据重出 | 工程核验 | 可以（需设备） |
| WS-C | 设备测试 `Zone invalidCreateName` 单次失败定因 | 缺陷排查 | 可以（可能结论为「不可复现」） |
| WS-D | 真实云资格、付费套餐、环境授权验收 | 外部阻塞 | 不可以（缺授权环境） |
| WS-E | P3 高频开发者资源立项（规格 + 计划） | 新产品批次 | 可以（先规格） |
| WS-F | P4–P9 与独立协议（GraphQL／S3／realtime） | 路线 | 不可以（依赖 P3 校准与授权） |

**已批准但未验收的规模参考：** REST 台账 3,659 项中 `verified_available=0`、`capability_verified=0`、`blocked=3,001`、`future_batch_not_yet_approved=3,594`；批次分布 P3 254／P4 181／P5 552／P6 635／P7 496／P8 1,436；S3 73 项 0 verified；GraphQL dataset／field 未知；全部真实环境状态 `awaiting_authorized_environment`。

---

## 二、WS-A：台账与计划同步（可以立即执行）

**Files:**
- Modify: `docs/superpowers/plans/2026-10-07-cloudflare-p2-zones-dns.md`
- Modify: `docs/superpowers/plans/2026-10-07-cloudflare-p1-foundation.md`
- Modify: `docs/superpowers/plans/2026-10-07-cloudflare-p0-p2-index.md`
- Modify: `docs/development-progress.md`
- Test: 无自动化测试；验收为文档一致性 grep 与人工核对

**Interfaces:**
- Consumes: `docs/cloudflare-audit/p2-runtime-support-2026-10-17.json`、`p2-verification.md`、`p2-main-integration-verification-2026-10-17.md`、`p1-task6..9-verification-*.md`、`p1-integration-verification-2026-10-16.md`。
- Produces: 勾选后每个 Task 顶部新增一行 `**完成证据：** <文件#锚点>；<命令与结果>`，供 WS-E 计划沿用同一写法。

### Task A1：P2 计划结清（50 个步骤）

- [ ] **Step 1：为每个 P2 Task 加「完成证据」行。** 在 `## Task N：` 标题下、`**Files:**` 之前插入一行，引用实际证据文件与提交：

```text
**完成证据：** `fbbb954`；本地 core 706／平台 91／Python 349+10；registry 69=`P2CoverageTest`；见 [主线集成](../../cloudflare-audit/p2-main-integration-verification-2026-10-17.md) 与 [P2 验证](../../cloudflare-audit/p2-verification.md)。
```

- [ ] **Step 2：勾选 Task1–Task9 的 Step1／Step3／Step4／Step5。** 这四项在恢复验证中有实际运行证据；勾选时把原命令结果写进行尾，例如 `（实测：core P2CoverageTest 通过）`。
- [ ] **Step 3：Task4 与 Task6 的 Step2 按真实执行方式记录。** 若为设备类测试，勾选并注明实际运行批次（`device-ui-full` 330 轮或 `device-app-font2-full` 24 轮），不写成单轮全绿。
- [ ] **Step 4：处理 Task10 Step4「完整验证」。** 勾选并注明 `single_all_green_run=false`；`known_test_limit` 原文保留，不改成「已通过稳定」。
- [ ] **Step 5：改写头部「集成前状态（2026-10-15）」。** 改为「集成后状态（2026-10-17）」并陈述：T1–T10 已实现并合入 `main`；本地验证已完成；真实云与付费资格未验收。保留原有 2026-10-15 段落为历史记录，不删除。

### Task A2：P1 计划 7 项遗留勾选

作业对象为 `2026-10-07-cloudflare-p1-foundation.md` 第 120、122、144、146、168、196、197 行。

- [ ] **Step 1：T2 Step2／Step4（第 120／122 行）。** 依据 `p1-foundation-verification-2026-10-13.md` 与实际 `:android-platform:testDebugUnitTest` 结果勾选，行尾注明 Client／Protocol／Transfer 76 项。
- [ ] **Step 2：T3 Step2／Step4（第 144／146 行）。** 勾选并注明 gateway 48 项；若沿用恢复后数字，注明来自主线 core 全套。
- [ ] **Step 3：T4 Step2（第 168 行）。** 勾选并注明 API 36 上 9 项迁移／事务／所有权测试通过；保留「初次 3 项失败系过时预期与夹具」的历史说明。
- [ ] **Step 4：T5 Step2（第 196 行）。** 勾选并注明 coordinator 54／controller 41。
- [ ] **Step 5：T5 Step3（第 197 行）先核验再决定。** 运行下列命令确认生产接线与断开协调存在：

```sh
grep -n "AppOperationRuntime\|ProductSessionFactory" app/src/main/kotlin/com/flarego/FlareGoViewModel.kt
grep -n "OperationCoordinator\|DnsOperationLifecycle\|credentialRevision\|stillCurrent" \
  shared-core/src/commonMain/kotlin/com/flarego/core/application/operations/AppOperationRuntime.kt | head
grep -n "invalidateConnection\|invalidateSession\|retireCredential\|removeConnection" \
  app/src/main/kotlin/com/flarego/FlareGoViewModel.kt
```

  - 三处都有命中 → 勾选，并把文本「生产接线及断开协调尚待应用集成」改为「生产接线与断开协调已由 Task9 集成完成（`FlareGoViewModel:100`、`disconnect()`）」。
  - 任一缺失 → 不勾选，转 WS-E 之前先补一个独立小任务，写清缺失点。

### Task A3：总览与进度文档去矛盾

- [ ] **Step 1：`2026-10-07-cloudflare-p0-p2-index.md`。** 在开头「主线集成更新（2026-10-17）」段落合并去重，删除「P2 未开始」类表述，只保留一条当前结论 + 一条历史记录指针。
- [ ] **Step 2：`docs/development-progress.md` 阶段表。** P2 行由「10 项均未开始产品实现」改为「T1–T10 已实现并合入 `main`；本地验证完成；真实云未验收」，并链接 `p2-main-integration-verification-2026-10-17.md`。
- [ ] **Step 3：保留 2026-10-16 的 P2「十项未实现」表但加显式历史标注**，指向 1.1 节同款的新表位置，不删除旧行。
- [ ] **Step 4：一致性验收。** 运行下列 grep，期望输出为空：

```sh
grep -rn "P2 十项尚未开始\|P2 未开始\|Task1–10 均尚未开始" docs/development-progress.md docs/superpowers/plans/*.md
```

- [ ] **Step 5：提交。**

```bash
git add docs/superpowers/plans/2026-10-07-cloudflare-p2-zones-dns.md \
        docs/superpowers/plans/2026-10-07-cloudflare-p1-foundation.md \
        docs/superpowers/plans/2026-10-07-cloudflare-p0-p2-index.md \
        docs/development-progress.md
git commit -m "docs: reconcile P1 and P2 plan checkboxes with integrated code"
```

### Task A4：加一道防复发检查（可选，但强烈建议）

- [ ] **Step 1：写一个只读一致性脚本。** 新建 `tools/ci/tests/test_plan_ledger_consistency.py`：断言每份计划若在 `docs/development-progress.md` 被标为「已完成」则其未勾选步骤数必须为 0，否则失败并打印文件与行号。
- [ ] **Step 2：运行验证。**

```sh
python3 -m unittest discover -s tools/ci/tests -p 'test_plan_ledger_consistency.py' -v
```

- [ ] **Step 3：接入既有工作流。** 在 `.github/workflows/cloudflare-audit.yml` 的「Run repository CI unit tests」步骤中纳入该模式；不新增 job、不增加 Secret。
- [ ] **Step 4：提交。**

```bash
git add tools/ci/tests/test_plan_ledger_consistency.py .github/workflows/cloudflare-audit.yml
git commit -m "test: fail CI when a completed plan still has unticked steps"
```

---

## 三、WS-B：遗留工程核验（可以立即执行）

### Task B1：主线全套回归并落盘新证据

- [ ] **Step 1：运行完整门禁。**

```sh
./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :android-platform:lintDebug
python3 -m unittest discover -s tools/ci/tests -v
python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_catalog.py' -v
python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_review.py' -v
python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_protocols.py' -v
```

- [ ] **Step 2：确认 P2 覆盖率门禁仍成立。**

```sh
./gradlew :shared-core:jvmTest --tests 'com.flarego.core.P2CoverageTest'
```

  期望：通过，且实际注册表仍为 69（39 读／30 写）。

- [ ] **Step 3：把结果写入验收记录。** 新建 `docs/cloudflare-audit/post-integration-regression-2026-10-17.md`，逐项记录命令、实际数字、跳过项；若数字与 706／91 不同，按实际写并说明原因，不改旧记录。
- [ ] **Step 4：提交。**

```bash
git add docs/cloudflare-audit/post-integration-regression-2026-10-17.md
git commit -m "docs: record post-integration regression with source evidence"
```

**依赖：** 可在 WS-A 之前或之后执行；两者文件不重叠。

---

## 四、WS-C：设备测试单次失败定因

**背景（逐字保留）：** `known_test_limit: "Zone invalidCreateName failed once in330 and passed unchanged in targeted9; deterministic failure cause unestablished, no stability fix claimed."`

- [ ] **Step 1：按 systematic-debugging 流程先复现，不先改代码。** 连设备后重复运行目标用例多轮：

```sh
for i in 1 2 3 4 5; do
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.flarego.ZoneManagementUiTest \
    || echo "ROUND $i FAILED"
done
```

- [ ] **Step 2：判定结论并落盘。** 二选一，都必须写进 `p2-verification.md` 的已知限制段：
  - 复现成功 → 记录最小失败序列、缺失节点、时序依赖，转 WS-E 之前的修复任务。
  - 无法复现（N 轮全绿）→ 写「N 轮未复现；原因未证实；不声称稳定性修复」。
- [ ] **Step 3：提交（仅文档）。**

```bash
git add docs/cloudflare-audit/p2-verification.md
git commit -m "docs: record zone invalidCreateName reproduction rounds"
```

---

## 五、WS-D：真实云与付费资格验收（阻塞，不可现在开工）

**阻塞事实：** `authenticated_cloud_accepted=false`、`paid_eligibility_verified=false`、`verified_available=0`、`capability_verified=0`、环境登记全部 `awaiting_authorized_environment`。

需要用户先提供四项中的任一项，才能解除对应子任务；本规划不代替用户决定提供哪一项：

| 子任务 | 前置 | 验收产物 |
| --- | --- | --- |
| D1 P1 16 项 L1 真实验收 | 授权账号 + Token 权限类别 | 每项 scope／权限／返回值证据记录 |
| D2 P2 54 项候选真实验收 | 同上 + 可清理测试 Zone | 每端点 §9.1 模板记录 |
| D3 付费套餐参数验收 | 已授权 Pro／Business／Enterprise 环境 | batch 3,500、TTL 30、NSEC3 等逐项证据 |
| D4 GraphQL schema 捕获 | `schema_introspection_capture` 授权 | dataset／field 矩阵，状态从 `awaiting_authorized_schema` 前进 |
| D5 S3 26 项厂商支持操作 | stream-scoped 凭据（独立 host） | SigV4 与分片证据，凭据与 REST Token 隔离 |

- [ ] **Step 1：请用户逐项确认可提供哪一类环境。** 收到确认前，D1–D5 保持未开始，不创建环境登记里没有的条目。
- [ ] **Step 2：收到确认后，才把对应行从 `awaiting_authorized_environment` 改为实际状态**，并在 `acceptance-environments.md` 记录标签／套餐类别／允许动作类别，不写账号 ID、Token、私钥或付款资料。

---

## 六、WS-E：P3 高频开发者资源立项（下一个开发批次）

**范围（来自 `docs/cloudflare-coverage-audit.md` §8）：** Workers 控制台／版本／部署／绑定／路由／Cron；Pages；R2 REST 桶与对象；KV 数据；D1 SQL／导入导出／恢复。台账 `review.json` 中 `phase=P3` 共 254 个操作。

- [ ] **Step 1：先做规格，不写代码。** 用 `superpowers:brainstorming` 与用户确认：P3 是整体一批，还是先只做 R2 REST + KV + D1（数据面）而把 Workers 部署链单独一批。产出 `docs/superpowers/specs/2026-10-XX-p3-developer-resources.md`。
- [ ] **Step 2：规格必须锁定三类高风险决定。** D1 的 SQL 写入如何与 `OperationCoordinator` 的确认／回读契约对齐；R2 大对象是否走 SAF + `TransferPort` 流式而不入内存；secret／value 是否可读回、不可读回时 UI 如何表述。
- [ ] **Step 3：规格获批后写实施计划。** 用 `superpowers:writing-plans` 拆成每产品一个 Task，文件落到 `docs/superpowers/plans/2026-10-XX-p3-*.md`；沿用 P2 的 `**完成证据：**` 行写法与 `P2CoverageTest` 式覆盖门禁（改为 `P3CoverageTest`）。
- [ ] **Step 4：加覆盖门禁骨架。** 计划中必须包含一个 Task：P3 候选端点逐项有 handler／policy／test key，缺一失败。
- [ ] **Step 5：用实际生产率回填估算。** 以 P2 的 54 端点实际投入校准 P3 254 项的滚动估算，替换 `docs/cloudflare-coverage-audit.md` §8.2 的「4–8 人周」粗估为有依据的区间。

**依赖：** WS-A 必须先行，否则新计划会继承同一套失真的勾选口径。

---

## 七、WS-F：P4–P9 与独立协议（不启动）

- 保持 `docs/cloudflare-coverage-audit.md` §8 的路线与 §3.5 的独立分母，不合并 REST／S3／GraphQL／realtime 计数。
- P8 的 1,436 项在 P3 完成前不拆分；GraphQL／S3／realtime 等独立协议在获得授权环境前保持 `awaiting_authorized_*` 阻塞。
- 不因某产品「看起来简单」而提前准入；准入仍走 P0 的 14 字段台账与 65 项手写策略门禁。

---

## 八、执行顺序与验收

```text
WS-A1/A2/A3（台账同步，可并行于 WS-B）
        ↓
WS-A4（防复发检查）── WS-B1（回归证据）── WS-C1（flake 定因）
        ↓
WS-E1 规格 → 用户评审 → WS-E2 计划 → 按计划实现
        ↓
WS-D（用户提供授权环境后）逐项解除阻塞
```

**总体验收标准：**

- §二 Step4 的 grep 输出为空，且 `test_plan_ledger_consistency.py` 通过。
- P1／P2 计划中每个勾选项都能指到一份实际证据文件，无「因为看起来做了」的勾选。
- `docs/development-progress.md` 与 `cloudflare-audit/*` 不再出现与代码冲突的「未开始」表述。
- WS-C 有明确结论字符串（复现或 N 轮未复现），不遗留「疑似」。
- WS-D 在用户提供环境前保持 0 计数，不被文档同步任务顺手改写。

**明确不做：**

- 不创建 Tag／Release，不推送，不合并（除非用户另行授权）。
- 不创建 Workspace／worktree／子 Agent；不归档任何空间。
- 不修改 `endpoints.csv`、`review.json`、`pinned-openapi.json`、`snapshot.json` 的固定值。
- 不启用 `protocols.json` 中任何 synthetic／bypass 标志，不把审计台账当运行时白名单。
- 不在没有规格的情况下开始 P3 编码，不把 3,594 项 `future_batch_not_yet_approved` 当作已批准工作量。
