# R2 应用接线执行记录

基线：`50681de`，分支 `feature/cloudflare-p3-r2`，沿用当前 checkout。

## Task 6b 分解

- [x] 6b.1 实际 App 的资源/R2 页挂载桶与对象浏览；账号/凭据/路由失效隔离，分页与错误状态。浏览器 7 项 + R2 设备 4 项通过。
- [x] 6b.2 SAF 上传文件选择、私有临时副本与单遍 SHA-256；选择结果绑定独立上下文，失效/取消清理。初轮暂存3项 + 实际SAF 5 MiB验证通过；后续真实picker已覆盖synthetic受控上传，见6b.5。
- [x] 6b.3 SAF 下载选择与流式写出；失败不发布半文件，未知状态不称成功。复用事务的 5 MiB 验证通过，完整 MainActivity 系统往返仍归 6b.5。
- [ ] 6b.4 R2 写入准入、确认与恢复：权限发行器之后，POST 建桶、删桶、对象上传/单删/批删的两阶段确认与受控 Session 已接线；同桶执行锁、单次私有 source、保守只读恢复已自测。最新 core807/UI13/platform97/设备定向51通过，见 [写入执行记录](2026-10-18-r2-write-loop-progress.md)。本轮独立功能/交互自动化验收已通过，勘误及分轮设备证据留存限制已归档；PUT/PATCH UI与独立全视觉检查仍未完成，系统picker最新限定范围独立复验已通过，因此6b.5另行记录；此总项仍因其余缺口暂不勾选。
- [x] 6b.5 当前API36/synthetic范围的MainActivity系统文件往返：8项真实OpenDocument/CreateDocument、5MiB传输、取消/失效/IO失败及Activity重建已验证；生产无测试hook。DNS取消重绑竞态和下载通知早于清理均已修正；限定范围独立复验14整轮+1单测通过，逐轮证据已归档，4图完成有限视觉核对。旧provider清理失败假设未证实，一般删除故障/可观测性仍有风险；API26-28/进程杀死恢复不在此证据内，不把此勾选扩为R2-A全完成。见 [picker执行记录](2026-10-18-r2-picker-progress.md) 与 [阶段证据](../../cloudflare-audit/r2-picker-verification-2026-10-18.md)。

## 核对与裁决

- Ruling: 本轮优先交付可实际访问的只读浏览与本地文件生命周期，写入按钮明确拒绝而不执行。原因：现有 TokenAuthorityIssuer/WriteAuthorityObservation 限定 ZoneScope，不支持 R2 账号写策略；不能以 UI 构造 granted 或绕过既有安全口径。代价：创建/修改/删除/上传暂不可执行，Task 6 不标完成；这是待开发的授权适配器而非要求提供真实账号才能写代码。
- Ruling: 已有 Task5c “列出同名 key 即证明上传成功”的结论不成立；同名旧对象也能命中。正式接通写入前，上传核对必须保持未知或核对可证明的内容，不把存在性当作内容证据。
- 不重复启动 UI worker：纯 UI 文件已由前端 profile 完成。本轮是主控负责的导航、凭据与平台集成，不修改主题或重设 UI。
- 不创建/归档 Workspace 或 worktree，不运行真实云请求，不推送/发布。未经新的提交授权，本轮保留工作树改动供检查。

## 验证

后续权限门禁批次结果：core796／UI7／platform95、设备定向49、审计349／CI10通过；详见 [权限准入验证](../../cloudflare-audit/r2-authority-verification-2026-10-18.md)。下面保留首次应用挂载批次数字。

首次挂载最终 core 770、shared-ui 7、platform 95 全通过；设备单轮定向 33/33，审计 349/349、CI 10/10，Debug/lint 通过（app/platform 0 error，10/8 warning）。详情见 [本轮验证](../../cloudflare-audit/r2-app-integration-2026-10-18.md)。

新增浏览器/暂存测试先因 API 未定义失败；两项真实回归（草稿 epoch 导致读 loading 卡住、下载接受 202/204/206）均实际 RED→GREEN。不删除/移动实现来补造历史 RED。

本轮没有提交、推送、合并或发布；工作树保留待验收。没有启动新子 Agent。
