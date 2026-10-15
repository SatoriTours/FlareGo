# Cloudflare P0：覆盖台账与范围门禁 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将一次性盘点转为可重复生成、可评审、没有遗漏归属且不会自动开放云写入的覆盖台账。

**Architecture:** Python 标准库读取固定官方 Schema，人工评审文件决定产品归属、风险和验收目标；生成 CSV 与报告，CI 检查唯一性和变更。生成目录不作为 App 请求白名单，不修改现有产品行为。

**Tech Stack:** Python 3 标准库、unittest、GitHub Actions；不新增产品依赖。

**Spec:** `docs/cloudflare-coverage-audit.md` §1、§3.3、§5、§8 P0、§9。用户已评审本计划并明确批准开始执行；实施边界见 P0–P2 总览。

## Global Constraints

- 源码基线 `bb1366de46c03e482d8d98ab25b7dde4abb93f15`；Schema 固定为 `6b00f157496cd6a1792a613dc94654c51273aeb9`，SHA-256 `06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461`。
- 初始 REST 计数为 3,659 操作／2,298 路径／582 标签；11 个 implemented_subset 不等于真实账号验收通过。
- R2 S3 官方兼容表 73 操作、26 可用、47 厂商不支持；与 REST 分母独立。
- 不读取本机凭据，不打印 Token，不执行真实云请求／扣款；公开 Schema 下载不携带云凭据。
- 不创建／移动 Tag，不发布 Release；提交步骤须在执行方式及源码提交授权确认后进行，不在规划轮执行。
- 用户当前会话是协调入口；编码需要隔离时通过 Paseo 创建并记录功能 worktree，不修改主 checkout 的其他任务。

## Review Focus

1. 同一路径多个方法以及一个操作多个 tag：METHOD＋path 唯一计数，tag 归属不能双算总数（Task 1）。
2. deprecated、SDK 状态、套餐与产品文档冲突：保留原始证据，不自动认定公开稳定可用（Task 2）。
3. 缺少权限元数据或陌生产品：必须有负责人和评审状态，不能默认允许执行或自动排除（Task 2）。
4. 官方 Schema 新增／移除／改名：生成审查差异，不自动覆盖已评审写策略或改变固定快照（Task 3）。
5. GraphQL／S3 与 REST 混合统计：单独分母；无真实账号自省时明确阻塞，不能虚构完整字段覆盖（Task 4）。

---

## 文件与责任边界

| 路径 | 责任 |
| --- | --- |
| `tools/cloudflare_audit/schema.py` | 本地 Schema 加载、SHA 校验、JSON Pointer 引用与操作枚举 |
| `tools/cloudflare_audit/catalog.py` | CSV／统计确定性生成，支持状态来自人工映射 |
| `tools/cloudflare_audit/review.py` | 人工台账校验、阶段目标与资格审查 |
| `tools/cloudflare_audit/diff.py` | 两个快照的 API／权限／请求／响应变化分析 |
| `tools/cloudflare_audit/cli.py`、`__init__.py` | 模块命令入口；包初始化并入 Task 1 |
| `tools/cloudflare_audit/tests/` | 小型离线 fixtures、目录与门禁测试 |
| `docs/cloudflare-audit/implemented.json` | 11 项源码支持映射与基线证据；不靠正则自动宣布支持 |
| `docs/cloudflare-audit/review.json` | 每个 operationId 的人工决策与依赖 |
| `docs/cloudflare-audit/protocols.json` | 非 REST 范围与验收台账 |
| `.github/workflows/cloudflare-audit.yml` | 独立只读 CI，不申请发布权限 |

## Task 1：可复核的固定快照生成

**Files:** Create `tools/cloudflare_audit/{__init__,schema,catalog,cli}.py`、`tools/cloudflare_audit/tests/test_catalog.py`、`tools/cloudflare_audit/tests/fixtures/schema.json`、`docs/cloudflare-audit/implemented.json`；Modify `docs/cloudflare-audit/{endpoints.csv,products.csv,snapshot.json}`。

**Interfaces:**
- `load_schema(path: Path, expected_sha256: str | None) -> dict`：校验失败抛 `ValueError`，不继续生成。
- `enumerate_operations(schema: dict) -> list[dict]`：每项包含 method/path/operation_id/tags 及原始 metadata；本地 `$ref` 按 JSON Pointer 解析，循环或无法解析时明确失败。
- `build_catalog(schema: dict, implemented: dict) -> dict`：返回 `endpoints`、`products`、`counts`；排序由 method＋path 固定，不依赖 dict 插入顺序。
- CLI：`python3 -m tools.cloudflare_audit.cli generate --schema PATH --manifest docs/cloudflare-audit/snapshot.json --implemented docs/cloudflare-audit/implemented.json --output DIR`。

- [x] **Step 1：写失败测试。** fixture 含 2 个路径、3 个操作、一个双 tag、一个含转义 JSON Pointer 的引用；新增断言：
  ```python
  def test_multitag_is_not_an_extra_operation(self):
      result = build_catalog(self.schema, self.implemented)
      self.assertEqual(3, result['counts']['operations'])
      self.assertEqual(4, result['counts']['tag_memberships'])
      self.assertEqual(1, result['counts']['implemented_subset'])
  ```
  同时测试重复 operationId、映射命中不存在操作、坏 SHA、未解引用 schema、重复生成字节完全一致。
- [x] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_catalog.py' -v`；新增测试因工具缺失而失败。
- [x] **Step 3：实现接口与生成器。** 把当前临时分析逻辑正式化；UTF-8 BOM CSV；保留 METHOD、operationId、原始 flags；初始 implemented.json 从已审查的 11 项手工映射建立，并记录 source file 与 baseline commit。当前源码变化不能被旧映射自动认定为新支持。
- [x] **Step 4：运行 GREEN 及固定快照检查。** 重跑测试；公开 Schema 校验后生成临时目录，确认 3,659／2,298／582／11／3,648／335／432 与 snapshot 一致。差异只能是批准新增的审查字段，不改原始基线。
- [x] **Step 5：提交独立成果。** 审阅本 task 路径后 `git add` 明确文件，`git commit -m "build: make Cloudflare coverage audit reproducible"`；不使用 `git add .`。

**Task 1 验收记录：**实现 `d2aac70`、引用解析修复 `3d5eecd` 已通过协调会话独立规格／质量审查。36 项工具测试、10 项已有 Python 测试通过；固定 Schema SHA、全部初始计数与三个产物字节复核一致。三个产物重新生成后无改动，满足复现要求；保留原有 path→method 确定性顺序。此成果仅为离线审计工具，不代表新增产品能力或真实账号验收。未运行 Gradle／Android／真实云端测试。

## Task 2：全量负责人、批次与资格决策

**Files:** Create `tools/cloudflare_audit/review.py`、`tools/cloudflare_audit/tests/test_review.py`、`docs/cloudflare-audit/review.json`；Modify `tools/cloudflare_audit/cli.py`、`docs/cloudflare-coverage-audit.md`。

**Interfaces:**
- `validate_review(operations: list[dict], review: dict, *, selected_ids: set[str] | None = None) -> list[str]`：返回可读错误，不修改台账。
- `summarize_review(operations: list[dict], review: dict) -> dict`：区分 registered／target／implemented／verified／legacy／blocked。
- review.json 按 operationId 索引，每项固定字段：`product_id`、`phase`（P1–P9）、`owner_role`、`scope_kind`、`protocol`、`semantics`（read/write/unknown）、`risk`、`pagination`、`availability_decision`、`deprecation_decision`、`target_level`、`test_prerequisites`、`needs_review`、`evidence`。
- `availability_decision`：documented_unverified／verified_available／restricted／provider_removed／console_only；未知不等于不可用。
- `target_level`：L0／L2／L3；任何只做 L0／排除项必须有原因与用户批准证据。运行时写许可仍属于 P1 手写策略，不来自该字段。

- [x] **Step 1：写失败测试。**
  ```python
  def test_unknown_permission_does_not_grant_availability(self):
      report = summarize_review(self.operations_without_permissions, self.review)
      self.assertEqual(0, report['verified'])
      self.assertEqual(1, report['blocked'])
  ```
  另测缺失 owner、未知 operationId、重复归属、SDK GA 与摘要 Alpha 冲突、仅 Forge hidden 不等于 removed、selected_ids 审查不影响其他产品。
- [x] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_review.py' -v`。
- [x] **Step 3：实现验证并人工归类。** 可按路径／SDK group 提议产品归属，但每项记录 review 状态。所有 3,659 项必须有 owner 与 phase；模糊标签分到显式 `unclassified-review` 工作包并在 P0 完成前人工消除。资格不明确的保留 needs_review 与具体证据缺口，不编造可用性。
- [x] **Step 4：运行 GREEN 与台账门禁。** `python3 -m tools.cloudflare_audit.cli check --review docs/cloudflare-audit/review.json --catalog docs/cloudflare-audit/endpoints.csv`；期望缺 owner／phase／产品归属为 0，并报告而非隐藏可用性阻塞数。P0 不要求普通账号验收数等于总数。
- [x] **Step 5：提交。** `git commit -m "docs: assign Cloudflare operation owners and acceptance targets"`，仅添加本 task 的文件。

**Task 2 验收记录：**初始实现 `d14774d`，修复 `b2b8ba1`／`8fff278`／`831e51b`，经三轮限范围独立复审后通过规格／质量门禁。独立 128 项工具测试＋10 项既有 Python 测试通过；CLI 含固定 Schema 身份校验、原失败探针与组合资格探针、SHA／全部计数／三个产物／5,323,243 字节台账重放通过。registered／target 3,659、implemented subset 11、verified／capability_verified 0／0、legacy 432、blocked 3,001、needs_review 3,659、unclassified 0。23 项长尾已具体归属 P8，但资格阻塞保留；K2 数据面 8 项独立主机与 unknown 作用域、高级证书与网站规则 P5 归属已核实；P2 当前准入仍为 54 候选。自动提议不是逐项用户资格批准，没有任何 L0-only／排除项获批准。未运行 Gradle／Android／真实云验收。

## Task 3：Schema 差异与只读 CI

**Files:** Create `tools/cloudflare_audit/diff.py`、`tools/cloudflare_audit/tests/test_diff.py`、`.github/workflows/cloudflare-audit.yml`；Modify `tools/cloudflare_audit/cli.py`。

**Interfaces:**
- `diff_schemas(old: dict, new: dict) -> dict`：返回 added／removed／changed，changed 至少跟踪 path/method、权限、security、deprecated、availability、parameters、requestBody、responses 及其引用结构。
- CLI：`diff --old PATH --new PATH --output PATH`；`check` 只验证固定基线及已评审文件，不写回它们。
- 直接／间接引用的结构变化必须检测；使用规范化、循环安全的 schema graph fingerprint，不仅比较 `$ref` 字符串。

- [x] **Step 1：写失败测试。**
  ```python
  def test_referenced_body_change_is_reported(self):
      delta = diff_schemas(self.old_schema, self.schema_with_changed_component)
      self.assertIn('example-create', delta['changed'])
  ```
  另测改名导致新增／移除、权限扩张、非 JSON body、新方法、循环引用、仅 key 顺序改变无结构差异。
- [x] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_diff.py' -v`。
- [x] **Step 3：实现 diff 与 CI。** PR／分支执行离线单测与快照一致性检查；下载仅使用固定 raw GitHub URL，缓存以 SHA 为键。对新版本的比较是显式手动输入候选 commit，不自动改基线。CI 仅 `contents: read`，不引入 cloud secrets，不修改 android.yml 的发布语义。
- [x] **Step 4：运行 GREEN。** 全部离线测试；同版本 diff added／removed／changed 都为空；模拟 schema 变更产生报告且固定 manifest 无变化。检查工作流没有发布／Tag 写权限。
- [x] **Step 5：提交。** `git commit -m "ci: review Cloudflare schema changes without enabling actions"`。

**Task 3 验收记录：**实现 `30d0a29`、修复 `66f48c3` 已通过协调会话独立限范围规格／质量验收。207 项工具测试＋10 项既有 CI 测试通过；报告原子写入／完整基线保护、两侧所有操作的引用校验、多层参数 override、服务器与安全上下文回归通过。真实固定 Schema 同版本 diff 全空；合成深层引用变更影响 33 项、K2 host 变更仅影响 1 项；SHA／计数／catalog 与 5,323,243 字节 ledger 重放不变。当前修复 HEAD 的临时 checkout 执行实际 workflow shell blocks（仅下载替换为本地 pin／合成候选），全部 9 步骤通过、5 项固定 Schema 回归不跳过；真实写回与污染缓存负例被拒绝，既有 gradlew EOL 字节保留。CI 仅只读，缓存为同 job 的 commit＋SHA 复用，不声称持久跨运行缓存。远程 Actions、真实候选下载、Gradle／Android／真实云验收均未执行。

## Task 4：独立协议范围与试点准入

**Files:** Create `docs/cloudflare-audit/protocols.json`、`docs/cloudflare-audit/acceptance-environments.md`、`tools/cloudflare_audit/tests/test_protocols.py`；Modify `tools/cloudflare_audit/review.py`、`docs/cloudflare-audit/r2-s3-operations.csv`、`docs/cloudflare-coverage-audit.md`。

**Interfaces:**
- `validate_protocols(protocols: dict, s3_rows: list[dict]) -> list[str]`：验证独立分母和证据状态。
- GraphQL 台账包含 dataset／field／account_scope／plan／limits／schema_evidence／review_status；无自省证据时状态为 `awaiting_authorized_schema`，不捏造字段全集。
- S3 每操作记录支持参数子集与验证证据；厂商不支持项是 provider_unsupported，不是 FlareGo 的待实现写动作。
- 实时协议记录 session 创建接口、连接协议、鉴权／主机策略、生命周期与产品负责人。
- 验收环境登记仅保存环境标签、套餐、允许动作、负责人、可用权限类别、清理责任；不得存账号 Token、付款资料或本机凭据路径。

- [x] **Step 1：写失败测试。**
  ```python
  def test_provider_unsupported_s3_is_not_an_app_target(self):
      errors = validate_protocols(self.protocols_claiming_all_73_supported, self.s3_rows)
      self.assertTrue(any('provider_unsupported' in e for e in errors))
  ```
  另测没有 GraphQL schema 证据却标 verified、REST＋S3 相加伪造统一覆盖率、敏感字段出现在环境登记。
- [x] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_protocols.py' -v`。
- [x] **Step 3：完成协议台账与准入文档。** 26 项 S3 建立验收目标；GraphQL 与实时协议列负责人及具体证据阻塞；Registrar sandbox、测试 Zone／DNS、企业产品各有许可范围。真实域名注册、支付和生产 DNS 不列为普通自动测试动作。
- [x] **Step 4：运行 GREEN 并评审 P0 出口。** 全部工具测试通过；3,659 项无归属缺口；非 REST 分母和阻塞透明；P1／P2 的 endpoint IDs 与风险策略获得人工审核。授权账号未提供时允许协议实现推进，但相应 L2 真实验收保持阻塞。
- [x] **Step 5：提交。** `git commit -m "docs: define Cloudflare protocol and account acceptance gates"`。

**Task 4 验收记录：**实现 `e331e02`、修复 `4089289`／`07a2f0b`／`985d71e`／`9711224`，前三轮未通过后完成根因讨论，用户批准 B 闭合事实流水线；第四轮限定复审通过规格／质量门禁。独立 333 项工具＋10 项既有 CI 测试通过，实际 BASE `985d71e` 的 12 项新回归测试产生 11 个断言失败（无 import/error 噪声），当前 GREEN；原 21 负例、新合同 44 例与资格／context／隐私扩展反例、合法完整链／无套餐证据的 schema-only 正例通过。固定 SHA／所有计数／3 个产物字节／5,323,243 字节 ledger／6 REST blobs／S3 原七列 73 身份顺序 26/47 未变。独立公开 MD 核对全 73 身份／支持／特性／组／摘要，实际 `9711224` 无 ignored 来源临时 checkout 的 126 项协议测试通过，强制全集门禁执行；仅额外原 MD 比较跳过 1。该本地测试不是远程 Actions；不运行 Gradle／Android／native／真实云验收。真实 GraphQL、S3 资格保持 0，7 环境 awaiting。

## P0 验收与后续

- [x] 工具可离线复核快照，下载仅是显式固定版本输入。
- [x] 全量端点有产品、批次、负责人、目标与证据；弃用／受限／外部阻塞不被隐藏。
- [x] 沿用用户批准的目标分母；用户批准的 L0-only／排除清单为空，不自动缩分母；47 厂商不支持 S3 项具体登记，不冒充 FlareGo 实现遗漏。
- [x] P1 接入清单及 P2 的 54 个活跃操作独立通过工程风险评审；其余产品不会因此自动启用。
- [x] 本批不宣称新增 Cloudflare 功能；完成后进入 P1 基础能力计划。

**P0 工程出口：**原协调／架构角色逐项审核 65 个唯一 operationId 与绑定、风险和确认／回读／不重放边界，详见 [准入风险审核](../../cloudflare-audit/p0-admission-risk-review.md)。5 新增／11 兼容／54 P2 候选，交集 5、并集 65，35 读／30 写；余 3,594 属未来批次。审核仅准许按已批计划开展工程实现，不是用户云资格／真实写入许可；原提议 JSON 与真实能力／外部阻塞保持不改。所有 P1 运行时动作仍由手写策略及产品确认机制决定；generic setting 路由不授予任意 key。P1 的 Gradle／设备测试命令受当前禁令限制：先检查现有缓存的独立 JVM 测试可行性，不绕过限制，也不把未执行测试算通过。

**执行依赖：**P0 Task 1 → Task 2；Task 3 可在 Task 1 后并行，Task 4 与 Task 2 共用 review.py，必须由同一集成负责人合并。

**执行状态（2026-10-12）：**Task 1–4 及 P0 工程出口的独立验收记录保留；本次 333 项审计测试、10 项发布契约测试及固定 Schema 台账校验通过。重新生成的 endpoints／products 与 checkout 仅 CSV 行尾不同，统一 LF 后一致，不声称当前原始字节完全一致。真实账号资格／危险动作验收仍阻塞；P1 已推进到 Task5，Task6–9 及 P2 尚未实现。后续以 [开发进度](../../development-progress.md) 和 P1 当前状态为准，不重复进行已完成的 Task1 预检。

**后续进度（2026-10-15）：**P1 Task1–9 已有实现；隔离分支 Task6–9 本地工程验收通过，尚未提交合并。原生迁移与产品／任务装配已验证，真实云资格保持 0，P2 产品任务尚未开始。上方 2026-10-12 执行状态保留为历史记录，最新证据见 [开发进度](../../development-progress.md)。
