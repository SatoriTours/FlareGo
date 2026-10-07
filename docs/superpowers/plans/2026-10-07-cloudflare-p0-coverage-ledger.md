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

- [ ] **Step 1：写失败测试。**
  ```python
  def test_unknown_permission_does_not_grant_availability(self):
      report = summarize_review(self.operations_without_permissions, self.review)
      self.assertEqual(0, report['verified'])
      self.assertEqual(1, report['blocked'])
  ```
  另测缺失 owner、未知 operationId、重复归属、SDK GA 与摘要 Alpha 冲突、仅 Forge hidden 不等于 removed、selected_ids 审查不影响其他产品。
- [ ] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_review.py' -v`。
- [ ] **Step 3：实现验证并人工归类。** 可按路径／SDK group 提议产品归属，但每项记录 review 状态。所有 3,659 项必须有 owner 与 phase；模糊标签分到显式 `unclassified-review` 工作包并在 P0 完成前人工消除。资格不明确的保留 needs_review 与具体证据缺口，不编造可用性。
- [ ] **Step 4：运行 GREEN 与台账门禁。** `python3 -m tools.cloudflare_audit.cli check --review docs/cloudflare-audit/review.json --catalog docs/cloudflare-audit/endpoints.csv`；期望缺 owner／phase／产品归属为 0，并报告而非隐藏可用性阻塞数。P0 不要求普通账号验收数等于总数。
- [ ] **Step 5：提交。** `git commit -m "docs: assign Cloudflare operation owners and acceptance targets"`，仅添加本 task 的文件。

## Task 3：Schema 差异与只读 CI

**Files:** Create `tools/cloudflare_audit/diff.py`、`tools/cloudflare_audit/tests/test_diff.py`、`.github/workflows/cloudflare-audit.yml`；Modify `tools/cloudflare_audit/cli.py`。

**Interfaces:**
- `diff_schemas(old: dict, new: dict) -> dict`：返回 added／removed／changed，changed 至少跟踪 path/method、权限、security、deprecated、availability、parameters、requestBody、responses 及其引用结构。
- CLI：`diff --old PATH --new PATH --output PATH`；`check` 只验证固定基线及已评审文件，不写回它们。
- 直接／间接引用的结构变化必须检测；使用规范化、循环安全的 schema graph fingerprint，不仅比较 `$ref` 字符串。

- [ ] **Step 1：写失败测试。**
  ```python
  def test_referenced_body_change_is_reported(self):
      delta = diff_schemas(self.old_schema, self.schema_with_changed_component)
      self.assertIn('example-create', delta['changed'])
  ```
  另测改名导致新增／移除、权限扩张、非 JSON body、新方法、循环引用、仅 key 顺序改变无结构差异。
- [ ] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_diff.py' -v`。
- [ ] **Step 3：实现 diff 与 CI。** PR／分支执行离线单测与快照一致性检查；下载仅使用固定 raw GitHub URL，缓存以 SHA 为键。对新版本的比较是显式手动输入候选 commit，不自动改基线。CI 仅 `contents: read`，不引入 cloud secrets，不修改 android.yml 的发布语义。
- [ ] **Step 4：运行 GREEN。** 全部离线测试；同版本 diff added／removed／changed 都为空；模拟 schema 变更产生报告且固定 manifest 无变化。检查工作流没有发布／Tag 写权限。
- [ ] **Step 5：提交。** `git commit -m "ci: review Cloudflare schema changes without enabling actions"`。

## Task 4：独立协议范围与试点准入

**Files:** Create `docs/cloudflare-audit/protocols.json`、`docs/cloudflare-audit/acceptance-environments.md`、`tools/cloudflare_audit/tests/test_protocols.py`；Modify `tools/cloudflare_audit/review.py`、`docs/cloudflare-audit/r2-s3-operations.csv`、`docs/cloudflare-coverage-audit.md`。

**Interfaces:**
- `validate_protocols(protocols: dict, s3_rows: list[dict]) -> list[str]`：验证独立分母和证据状态。
- GraphQL 台账包含 dataset／field／account_scope／plan／limits／schema_evidence／review_status；无自省证据时状态为 `awaiting_authorized_schema`，不捏造字段全集。
- S3 每操作记录支持参数子集与验证证据；厂商不支持项是 provider_unsupported，不是 FlareGo 的待实现写动作。
- 实时协议记录 session 创建接口、连接协议、鉴权／主机策略、生命周期与产品负责人。
- 验收环境登记仅保存环境标签、套餐、允许动作、负责人、可用权限类别、清理责任；不得存账号 Token、付款资料或本机凭据路径。

- [ ] **Step 1：写失败测试。**
  ```python
  def test_provider_unsupported_s3_is_not_an_app_target(self):
      errors = validate_protocols(self.protocols_claiming_all_73_supported, self.s3_rows)
      self.assertTrue(any('provider_unsupported' in e for e in errors))
  ```
  另测没有 GraphQL schema 证据却标 verified、REST＋S3 相加伪造统一覆盖率、敏感字段出现在环境登记。
- [ ] **Step 2：运行 RED。** `python3 -m unittest discover -s tools/cloudflare_audit/tests -p 'test_protocols.py' -v`。
- [ ] **Step 3：完成协议台账与准入文档。** 26 项 S3 建立验收目标；GraphQL 与实时协议列负责人及具体证据阻塞；Registrar sandbox、测试 Zone／DNS、企业产品各有许可范围。真实域名注册、支付和生产 DNS 不列为普通自动测试动作。
- [ ] **Step 4：运行 GREEN 并评审 P0 出口。** 全部工具测试通过；3,659 项无归属缺口；非 REST 分母和阻塞透明；P1／P2 的 endpoint IDs 与风险策略获得人工审核。授权账号未提供时允许协议实现推进，但相应 L2 真实验收保持阻塞。
- [ ] **Step 5：提交。** `git commit -m "docs: define Cloudflare protocol and account acceptance gates"`。

## P0 验收与后续

- [ ] 工具可离线复核快照，下载仅是显式固定版本输入。
- [ ] 全量端点有产品、批次、负责人、目标与证据；弃用／受限／外部阻塞不被隐藏。
- [ ] 用户已确认目标分母和排除理由；没有“工具隐藏就不做”的自动排除。
- [ ] P1 接入清单及 P2 的 54 个活跃操作独立通过策略评审；其余产品不会因此自动启用。
- [ ] 本批不宣称新增 Cloudflare 功能；完成后进入 P1 基础能力计划。

**执行依赖：**P0 Task 1 → Task 2；Task 3 可在 Task 1 后并行，Task 4 与 Task 2 共用 review.py，必须由同一集成负责人合并。

**执行状态：**Task 1 已验收；Task 2–4 和 P0 出口仍待实施／审核。各项测试、CI 与提交只按对应步骤的实际证据记录，不将规划中的命令当作已执行结果。
