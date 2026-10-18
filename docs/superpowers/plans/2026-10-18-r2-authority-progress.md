# R2 账号写权限准入：执行记录

继续已批准的 R2-A 与应用接线计划（Task 6b.4）。沿用 `feature/cloudflare-p3-r2` 当前工作树，不覆盖上一轮未提交改动、不新建工作空间。

## 本轮工作

- [x] 复用已严格验证的 Token 身份/条件/策略解析器，新增 R2 资源规则；原 Zone 行为不变，全套 core 含 P2 回归通过。
- [x] 独立 R2 证明发行器：账号、目标桶、操作、凭据 revision、session generation、有效期绑定；deny 优先，未知默认拒绝。
- [x] 受控 R2 session：所有写入口均经发行器，最终调用前同步重验；验证缺权和失效均为零写入。
- [x] Application owner 稳定持有 R2 发行器，凭据更新/断开同时撤销；生产浏览使用受控 session。
- [x] 完整回归与验证记录：core796（新增权限26）、UI7、platform95、设备定向49、审计349/CI10、Debug/lint通过。UI 确认流/可信上传 source/并发与恢复尚有独立缺口，不在仅实现证明发行器后把所有按钮放开。

详情见 [验证记录](../../cloudflare-audit/r2-authority-verification-2026-10-18.md)。所有数字来自实际 XML/命令，未运行项目全部设备测试。

## 语义与裁决

- Ruling: REST `api.cloudflare.com` 本批仅接受账号级 `Workers R2 Storage Write`；Object Read/Write（Bucket Item 类权限）不等于 REST 权限。桶限定 policy 不升级为账号管理授权。代价是保守拒绝未证实的资源形状，不静默降权到 S3 或改用全权密钥。
- 使用 pinned schema 的 operationId/method 和公开 x-api-token-group，不把内部 x-cfPermissionsRequired 当作公开权限组名。
- 只接受明确账号资源与账号下 R2 全桶资源。未知条件、资源、匹配的未知 deny、重复 catalog/JSON key、身份/目录漂移均不得正计授权。
- 证明只是 Token 策略准入，不是用户确认、套餐资格、付款许可或真实云验收。
- Ruling: UNKNOWN Token kind 探测不能把 transient verify 500/网络/解析失败当作排除该分支的证据；只忽略明确认证拒绝。该问题已通过真实行为 RED→GREEN 修正，代价是故障时保守保持只读。
- 不创建或提交真实 Token；本轮测试使用 synthetic provider；不提交/推送/合并/发布。

## 来源边界

固定 OpenAPI 缓存 `.superpowers/cloudflare/openapi.json` 用于操作/路径/权限元数据。
官方 R2 Authentication：https://developers.cloudflare.com/r2/api/tokens/；本轮 web_search（OpenAI）检索核对了 REST 需 Admin 类权限、bucket item 仅 S3 的区分，以及账号下 `com.cloudflare.edge.r2.bucket.*` 的资源形状。网页正文抓取被本机 fake-IP/SSRF 门禁拒绝（198.18.0.0/15）；未修改门禁、未绕过限制，也不把检索摘要当作所有未知字段的批准。未核实形状默认 UNKNOWN。
