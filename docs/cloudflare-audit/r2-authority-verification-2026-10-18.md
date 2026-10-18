# R2 REST 写权限准入：实现与验证（2026-10-18）

> 本文记录权限批次的历史阶段。后续已接通部分 UI 写入预览确认、可信上传与恢复，不再统一只读；当前状态以 [写入闭环阶段证据](r2-write-loop-verification-2026-10-18.md) 为准。以下旧测试数/只读边界保留原轮含义。

## 状态

继续 `feature/cloudflare-p3-r2` 的未提交工作树。本轮不提交、合并、推送或发布，不新增子 Agent/Workspace/worktree，不读取用户真实 Token。

**本轮完成的是权限准入与受控 session，不是 UI 写入全闭环。** 页面仍只读；预览确认、可信上传 source、桶/对象并发锁与未知结果恢复等接线尚待完成，不能把测试中 synthetic 写入当作真实云授权。

## 实际改动

- 新增 `R2WriteTarget`、`R2WriteObservation`、issuer-owned `R2WriteProof`。证明绑定 connection/account、目标 bucket、默认 jurisdiction、operationId、credential revision、session generation、有效期。
- `R2TokenAuthorityIssuer` 由 Application 级 `ManagementPlatformOwner` 稳定持有。短 session/transport 重建不重置身份/权限目录绑定；凭据轮换或断开同时撤销旧 issuer。
- `CloudflareR2WriteAuthorityClient` 仅复用既有手写注册的 Token verify/self-details/permission-groups 和 account-details GET；没有新增 Token 创建、修改、吊销或 S3 临时凭据签发操作。
- `AuthorizedR2Session` 覆盖所有写入口：桶 create/create-by-name/patch/delete、对象 upload/delete/bulk-delete，以及桶/对象 guarded write。读操作不做权限升级；每次写入强制重新审查，原始 `CloudflareR2Client` 仅作为内部 typed transport。
- `CloudflareR2Client` 的两个写发送点加入最终同步 hook。应用的受控 session 用它校验 proof：在所有 suspending preflight、文件打开和产品 guard **之后**，真正 gateway send **之前**执行，避免已过期/撤销 proof 继续发送。
- 新增 exactly-once source 所有权 wrapper：最终 guard 拒绝、取消、IO 失败与 transport cleanup 都关闭同一个源，不能因为已打开文件却未真正发送而漏句柄。
- 生产 `FlareGoViewModel` 现在构造 `AuthorizedR2Session`，`R2Browser` 消费领域接口 `R2Session`，不再依赖原始具体客户端。当前 UI 仍不会发起写入，不把“受控 session 可写”当作“用户已经确认”。

## 准入语义

- 本批 REST 仅接受明确账号级 `Workers R2 Storage Write`。公开权限目录返回的动态 ID 才用于匹配策略；不从策略里的展示 name、Token 名称或内部 `x-cfPermissionsRequired` 推断权限。
- Object/Bucket Item Read/Write 不升级为 REST 管理权限。这些 S3 专用权限不是本批的 REST 写授权。
- 支持精确账号资源 `com.cloudflare.api.account.<account>` 的 `"*"` 值，以及该账号下 `{ "com.cloudflare.edge.r2.bucket.*": "*" }`。
- 桶限定资源被理解为 deny 范围，但不提升为 account-wide allow。未知资源、全账号 wildcard 等未审形状默认 UNKNOWN；这可能保守拒绝部分 Token，不静默扩大权限。
- 匹配 deny 优先；未知匹配 deny 默认拒绝。403/不可自检、未知 condition、重复 JSON key/catalog name、catalog scope 不匹配、Token/目录身份漂移、时间倒退、过期均不授 proof。
- Token 类型 UNKNOWN 时，只把 verify 的明确认证拒绝（401/403/404）当作该分支不可用；某一分支的 5xx/网络/解析失败不能被当作另一个分支成立的证据。两个分支都验证成功时 AMBIGUOUS_TOKEN_KIND。
- 真实账号 GET 必须回显当前 account ID；本机 ScopeGuard 不替代供应商绑定证据。
- proof 只是 Token 策略门禁：不等于套餐资格、不等于用户确认，也不保证厂商最终接受某次写入。

## 验证结果

### 最终命令

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :shared-core:jvmTest :shared-ui:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :android-platform:lintDebug \
  :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2AuthorityOwnerIntegrationTest,com.flarego.ManagementPlatformOwnerIntegrationTest,com.flarego.R2ManagementUiTest,com.flarego.DnsFileIntegrationTest,com.flarego.ProductLoadingTest'
SCHEMA_FILE="$PWD/.superpowers/cloudflare/openapi.json" \
  python3 -m unittest discover -s tools/cloudflare_audit/tests -v
python3 -m unittest discover -s tools/ci/tests -v
```

| 检查 | 实测 |
| --- | --- |
| core 全套 JVM | 796，0 failure/error/skip（新增权限/受控 session 26 项） |
| shared-ui 全套 JVM | 7，0 failure/error/skip |
| platform 全套单测 | 95，0 failure/error/skip |
| 本轮设备单轮定向集合 | 49，0 failure/error/skip |
| 其中 R2 Application owner | 1：真实 owner，短 bundle 复用 issuer，轮换撤销旧 bundle/session，新 issuer 正常 |
| 其中原平台 owner 回归 | 15 |
| 其中 R2 VM/Compose 回归 | 4 |
| 其中 SAF 文件回归 | 17 |
| 其中产品加载回归 | 12 |
| Debug APK/测试 APK | 通过 |
| app/platform lint | 0 error（既有 warning 保留，不称零警告） |
| Python audit/CI | 349/349、10/10 |

26 项权限测试包括：真实 typed 客户端在有效管理员 proof 下逐项走通七种 REST 写，缺权时所有 convenience/guarded 入口均零写入，forged observation/proof 拒绝，目标/操作/账号绑定，deny 优先，目录/身份漂移，过期与时钟倒退，账户 Token endpoint 隔离，刷新失败/取消撤销已有 proof，以及产品 callback 使 proof 失效时已打开上传 source 恰好关闭一次。

测试把 provider transport 替换为完整 synthetic envelope/stream 行为；真实 gateway 注册、策略解析、发行器、受控 session、Application owner 和 Android 文件/数据库均未被成功布尔值 stub 代替。没有真实云请求。

### 失败与 RED/GREEN

- 新权限测试初次编译因 API 未定义失败，随后实现；未通过移走实现来补造历史 RED。
- 新受控 session 测试初次因 `AuthorizedR2Session` 未定义失败。
- 新 Android owner 测试初次因 bundle 无 `r2Issuer` 失败；接入后设备实测通过。
- `unknownKindDoesNotTreatTransientVerifyFailureAsProofOfTheOtherKind` 首次真实行为失败：旧逻辑把一支 verify 的 500 当作不可用，并从另一分支授 proof。改成只忽略明确认证拒绝后通过，未放宽预期。
- 本轮最终 JVM/设备定向集合无失败。

## 仍未完成

1. UI 写入预览/显式确认与最终结果展示，不能直接去掉当前 read-only 提示。
2. 可信暂存副本到单次上传 source 的安全连接；不得只相信外部提供的 SHA-256 标签。
3. R2Controller 桶/对象共享锁及未结算写的完整门禁。
4. 上传恢复不能凭“同名 key 存在”宣告内容已更新；未知结果只读核对、不重放。
5. 全部十端点的最终 handler/test/runtime 支持矩阵（Task 7）。
6. R2 MainActivity 完整系统文件选择往返仍待独立 E2E。分层 SAF 回归不替代它。

本轮没有独立子 Agent 审查，不宣称全分支独立验收通过。

## 来源与环境边界

固定 schema 与官方公开 R2 Authentication 页面入口见 [执行记录](../superpowers/plans/2026-10-18-r2-authority-progress.md)。网页正文抓取因本机 fake-IP 被工具 SSRF 门禁拒绝，未修改/绕过门禁。检索核对不是未知资源形状自动准入的授权。

设备始终限定本轮自建 `flarego-r2-integration`、serial `emulator-5554`，不使用或停止并行运行的其他项目模拟器。原始结果在 Gradle 标准 XML；命令日志 `/tmp/flarego-r2-authority-final-gradle.log`、`/tmp/flarego-r2-authority-audit.log`、`/tmp/flarego-r2-authority-ci.log` 为临时证据，不承诺永久归档。无签名构建/发布/真实付费资格验证。
