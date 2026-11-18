# Cloudflare 接入：API Token（OAuth 已停用，本文保留历史）

> **现状（2026-11-15，用户明确要求）**：App **只支持 API Token**。OAuth 不再是默认或高级入口：
> 构建已移除 OAuth Client ID／回调地址注入、App Link 注册、回调 `code/state` 处理与授权界面，
> 因此本文件以下内容全部是**历史记录**，不代表当前可用能力。任何"授权按钮被禁用""重新授权补齐权限""改用 OAuth 授权"的描述都已失效。
>
> **旧安装迁移**：旧版保存的 OAuth 记录带 `flarego-oauth-v1:` 前缀，升级后可被识别：
> - 账号名称与本地记录保留，账号页标记「需要 Token」，账单页说明"旧版 OAuth 授权已停用"。
> - App **不再刷新、不再请求**旧 OAuth 凭据；该格式也**不会被当作 Token 字符串发送**（存储层直接拒绝）。
> - 用户在同一账号上重新连接并只读验证新的 API Token 后，凭据被**补偿式替换**：加密凭据与本地账号行是两个存储，App 先写凭据（失败即回滚原值，新建账号则删除新密文），再写账号行；普通失败/取消保留原凭据与账号行。
> - **不承诺跨存储原子性**：两步之间进程被杀时，可能出现「新凭据已存、账号行仍旧」。恢复方式是重新连接（再次提交会同时写两处）；孤儿凭据没有对应账号行，不会被读取。回滚若本身失败，App 会报告该状态，不声称旧凭据已恢复。
> - **持久提交门禁**：每次凭据提交先在本地 SQLite 的 `credential_gate` 表内**用事务确认**写入 `PENDING`（携带唯一 operationId 与 accountId），确认成功才触碰 Keystore 里的凭据；凭据与账号行都写入后，仅当同一 operation 的 `release` 被事务确认删除，才发布 SAVED 并启用。任何时刻读取到 `PENDING`/`BLOCKED`/损坏/读错都 **fail-closed**：凭据不交出、产品不发请求、写证明链拿不到凭据（不只是 UI 提示）。进程在提交中被杀不会自动解除门禁，重新连接并完成一次确认提交才解锁；`release` 失败或结果未知时保持 blocked 并报 UNCERTAIN。旧安装没有门禁记录时按原有 Token 兼容（视为无门禁）；旧 OAuth 记录依旧需要 Token。
> - **保存结果三态**（`SAVED / NOT_SAVED / STATE_UNCERTAIN`）：`NOT_SAVED` 表示已恢复到连接前状态、可重试；`STATE_UNCERTAIN` 表示可能已写入部分内容，App 会**失败关闭**——丢弃本次 Token、不自动重试、该账号按「需要重新配置」处理并停止发起请求，直到重新连接确认。写入抛异常不会被当作"未写入"（SharedPreferences 先改内存后 commit），因此所有抛错路径都会先尝试受保护补偿。
> - 断开仍只删本机凭据；云端 Token 由用户自行在 Cloudflare 控制台吊销。
>
> **Token 能力边界**：按凭据类型决定。Token 可尝试全部已登记只读目录（含账号账单历史与 Registrar 只读搜索），单个产品 403 只报该产品；写入仍需既有 verify／Token details／permission-group／owner 证明链。发票 PDF／Alpha 受限用量不在范围。真实云验收仍未完成。

## 历史：一次只读授权（OAuth，已停用）

## 一次只读授权（当前方案）

权限 ID 来自维护者提供的 FlareGo OAuth 客户端范围截图，已包含准确 ID，运行时不再查询 `/oauth/scopes`，也不再执行基础授权后追加产品授权。

固定请求范围由 `CloudflareOAuthReadProfile` 唯一维护，共七项，全部来自客户端范围截图已确认的 ID；未经核实的 scope 一律不请求：

```text
account-settings.read
zone.read
dns.read
workers-scripts.metadata_read
workers-kv-storage.read
workers-r2.read
offline_access
```

**`invalid_scope` 的处理**：若客户端不允许上述任一范围，Cloudflare 会以 `invalid_scope` 拒绝授权。连接页展示可执行说明（指明 App 只请求这七项、请在 OAuth 客户端核对后重新授权），不使用"缺少某项你找不到的权限"这类断言。"仅使用基础只读范围继续"的次要入口只在**存在更窄请求**（即本次比基础范围多请求了额外 scope）时出现；当前请求集等于基础集，因此该按钮不出现，避免重复同一个必然失败的授权。

2026-11-09 更正：此前本节写有"已确认 `billing.read`"与"用七项基础范围降级"的结论，均未获证实。用户在其 OAuth 客户端搜索 Billing 无任何结果，故 `billing.read` 已从默认请求中移除（见下节），账单产品也不再声明为可读。

首次连接和旧账号重新授权都只发起一次 Authorization Code + PKCE S256 浏览器授权。回调换取令牌后读取账号，完整列表只有一个账号时自动验证并保存，多账号时由用户选择。账号验证通过才替换原连接凭据；取消、拒绝、旧回调和验证失败不覆盖原连接。进程丢失 PKCE 会话时提示重新开始，不静默返回总览，不接受旧授权码。

令牌响应明确返回 scope 时，必须包含本次全部请求范围，否则拒绝保存；根据 OAuth 2.0 RFC 6749 §5.1，响应省略 scope 表示与请求范围一致。续期不会升级旧授权，旧版基础权限账号必须重新授权。请求范围和令牌响应检查不是实际产品 API 可读的证明，仍需真实云验证。

本方案不申请 D1、Workers 内容或写权限，**也不申请任何账单 scope**（见下节"账单读取（Billing）权限现状"）。D1 与账单入口显示当前未启用，不发送目录/后台加载请求，不计入刷新失败；演示连接仍按全部产品提供示例数据，已有连接与已有数据不受影响。总览正常刷新四类目录（域名、Workers、KV、R2），DNS 在域名内读取。

域名注册搜索已实现**只读 discovery**（代码与 UI 就绪，默认关闭）：`GET /accounts/{id}/registrar/domain-search`，入口在域名页底部「搜索并注册域名」，结果只是缓存建议，页面固定提示「搜索结果为缓存数据，注册前需在官方控制台确认可用性与价格」。因 Registrar 的 OAuth scope ID 未确认（见下节），真实账号走「域名搜索需要具备 Registrar 读取权限」分支且**不发请求**；演示连接返回三条示例候选。注册（`POST /registrar/registrations`）未实现，App 内不会提交注册、不会扣款。

Actions 只注入公开 Client ID 与回调 URL，不再注入 `flarego.oauthScopes`，历史 Gradle scope 配置不再控制请求。缺少客户端配置时仍禁用授权入口；不会回退到手动 Token。客户端七项范围截图不等于真实接口验收通过，实际浏览器授权、App Links、续期和支持产品读取仍需签名 APK 验证。

## 历史：账单读取（Billing）权限调查（OAuth 已停用，结论仅存参考）

- 官方 API 参考确认的账单读取接口路径与权限名（**这些是 API Token 的权限名，不等于 OAuth scope ID**）：
  - `GET /accounts/{account_id}/billing/history`（Accepted Permissions: `Billing Write` 或 `Billing Read`）— <https://developers.cloudflare.com/api/resources/billing/subresources/history/methods/list/>
  - `GET /accounts/{account_id}/billing/profile`、`POST /accounts/{account_id}/billing/profile` — <https://developers.cloudflare.com/api/resources/billing/subresources/profiles/methods/get/>
  - 发票：`PATCH /accounts/{account_id}/invoices`（Invoices 资源，未公开只读列表）— <https://developers.cloudflare.com/api/resources/accounts/subresources/invoices/>
  - 用量（Alpha/Restricted）：`GET /accounts/{account_id}/billable-usage/info`、`GET /accounts/{account_id}/billable-usage`、v2 `GET /accounts/{account_id}/billable/usage` — <https://developers.cloudflare.com/api/resources/billing/>
- OAuth 侧是否提供对应 scope、以及其准确点号 ID，**尚未确认**：用户在其 OAuth 客户端的 scope 搜索中查 "Billing" 无任何结果；权威来源是 `GET /oauth/scopes`（<https://developers.cloudflare.com/api/resources/iam/subresources/oauth_scopes/methods/list/>），但它需要认证，本调查未使用任何凭据读取，因此该 ID 记为未知，不猜测。
- 因此 App **不请求** 账单 scope：`CloudflareOAuthReadProfile.products` 不含 `ProductKey.BILLING`，真实账号不发送账单请求；账单页显示「账单读取尚未启用」，说明只请求了七项已确认范围、准确范围尚未确认，并提供官方控制台入口。若某次读取真的被云端拒绝（`PERMISSION_REQUIRED`/`CREDENTIAL_EXPIRED`），卡片会**原样显示该错误**并提供「重新授权」；未确认能力的静态情况下不显示重新授权按钮，避免让用户循环授权。
- 未来若确认了准确 ID：只改 `CloudflareOAuthReadProfile` 的请求范围与 `products`，仍需真实账号验收，不能因为"改了常量"就宣称账单可用。

以下带日期的章节保留历史记录；与范围配置有关的当前行为以本节为准。

## 历史：Registrar（域名注册）权限调查（OAuth 已停用，结论仅存参考）

- Registrar 的 API 端点在官方参考中确认存在，例如只读搜索 `GET /accounts/{account_id}/registrar/domain-search` 与注册 `POST /accounts/{account_id}/registrar/registrations` — <https://developers.cloudflare.com/api/resources/registrar/>。官方「Registrar API」指南给出的前提是 **API Token** 具备 Registrar 权限，并需要账号已配置账单资料等 — <https://developers.cloudflare.com/registrar/registrar-api/>。
- 权限名侧：API Token 创建界面提供 `Registrar Domains: Admin` 与 `Registrar Sandbox Domains: Admin` 这类权限项（社区 issue 亦记录该界面现状与文档兼容性表不一致）— <https://github.com/cloudflare/cloudflare-docs/issues/32939>。**这只证明 Token 权限名存在，不等于 OAuth scope ID。**
- OAuth 侧：用户在 Manage Account → OAuth clients 的 scope 选择中确实能看到 Registrar Domains Admin/Read 与 Registrar Sandbox Domains Admin/Read 条目，但**准确的点号 scope ID 未确认**；OAuth 客户端会按 `GET /oauth/scopes` 校验 scope 字符串，猜错会被拒绝（<https://developers.cloudflare.com/api/resources/iam/subresources/oauth_scopes/methods/list/>，需认证，本调查未用凭据读取）。早先文档中"Registrar 不提供 OAuth"的说法同样未获证实，已删除。
- 当前结论：**App 不请求 Registrar scope**（`CloudflareOAuthReadProfile.registrarScope` 为 `null`），真实账号的域名搜索走「域名搜索需要具备 Registrar 读取权限」分支且不发任何请求（API Token 路径下由该 Token 的实际权限决定），只提供跳官方控制台；演示连接返回三条示例候选。这属于能力边界，不是已支持功能。
- 待确认项与后续：需要在 `GET /oauth/scopes` 或 OAuth 客户端里取得 Registrar 只读范围的准确点号 ID，填入 `registrarScope` 后重新授权，并用真实账号验证 domain-search 是否被授权（"填了常量"不等于可用）。若 OAuth 侧无法提供该范围，App 内真实搜索无法通过 OAuth 实现；是否另设仅限 Registrar 的 API Token 连接需单独评审，当前不做，也不新增 Global API Key 支持。

## 历史：仅跳转授权的连接流程（OAuth，2026-10-31，已停用）

用户已要求移除手动 Token 模式，账号名称和 Account ID 也不再由用户填写。
采用现有原生 OAuth 与账号接口，避免引入登录后端或额外权限。
不采用保留高级 Token 表单或要求每次确认单账号的方案，因为它们仍让用户承担接入细节。

- 未连接：只展示官方授权入口、简短流程及隐私说明；构建未配置时说明需要维护者提供支持授权的版本。
- 返回 App：由 ViewModel 连续完成换取凭据、查询账号、验证账号及加密保存。仅当完整列表中只有一个账号时自动连接；多账号或分页未结束时按名称选择，点击即验证连接，不再另设确认按钮。同名账号用编号末六位区分，无需输入。
- 进行中：区分等待浏览器、读取账号、连接账号；连接保存期间禁用重复操作和关闭。连接成功自动关闭窗口，进入已连接账号。状态保留到 UI 消费，Activity 重建不会重复保存。
- 异常：空列表、网络失败、权限不足、过期分别说明；提供重新读取或重新授权。分页失败保留已发现的账号，重试失败页，不把部分列表误判为唯一账号。验证/保存失败保留账号以便再次点击重试。
- 安全：只接受当前发现列表中的账号；旧回调、取消后的响应不保存。错误信息不含原始响应或凭据。保留现有 PKCE、state、HTTPS 回调及续期约束。
- 兼容：保留已保存连接与旧凭据读取能力；新增/重新连接仅走 OAuth。同一账号重连沿用连接标识。删除 Token 输入表单、帮助入口和相关引导，不更改其他资源的凭据编辑器。
- 能力：授权成功仅表示账号连接成功，实际资源读写仍受构建请求范围与已有权限守卫限制；不新增 scope、不扩大云端权限。

实施顺序：先用 `AndroidOAuthConnectionTest` 固定自动连接、分页/空列表重试和取消边界，再修改连接状态与 `OAuthConnectionDialog`，删除旧表单及入口，更新 `OAuthConnectionUiTest`、实际入口烟测与文档。验收覆盖单/多/空账号、失败重试、重复回调、Activity 重建、无 Token/ID 输入及大字号可用性；真实 Cloudflare 登录/回跳独立记录，不以隔离测试冒充。

## 当前边界

代码提供 Android 的 Authorization Code + PKCE S256 授权、HTTPS App Link 回调、账号发现/验证、Keystore 加密保存及 refresh token 续期。已保存的 API Token 保持读取兼容；新增连接仅支持 OAuth，不提供手动凭据输入。维护者已提供 OAuth Client ID，Android Actions 工作流会在构建前注入公开客户端配置；真实云授权和设备回跳仍未验证。缺少配置的其他构建会禁用按钮，不模拟授权成功。

## 历史：GitHub Actions OAuth 构建配置（已删除，勿再注入）

`.github/workflows/android.yml` 的 `Configure public Cloudflare OAuth` 步骤在所有 Gradle 构建前追加以下公开配置到该次 CI checkout 的 `gradle.properties`，Debug 检查和签名 Release 构建均使用它们；无需修改本机 Gradle 用户配置，也不读取 Client Secret：

```properties
flarego.oauthClientId=9bd1b2bf245bd1b78506a7c0b6fbc11a
flarego.oauthRedirectUri=https://satori.tours/flarego/cloudflare/callback
flarego.oauthScopes=account-settings.read
```

关联文件已部署在 `https://satori.tours/.well-known/assetlinks.json`，包名为 `com.flarego`，仅包含 GitHub Actions 正式签名证书 SHA-256：
`C1:ED:85:DB:A0:F4:F7:40:82:A7:AA:48:D0:E8:3C:BD:87:C1:AB:32:65:C5:E6:38:1F:73:19:43:62:71:2A:B1`。

这是签名证书指纹，不是 APK 文件哈希；只要 Actions 使用同一签名密钥，常规提交和重新构建无需更新服务器文件。更换签名或分发渠道时需核实实际证书。调试包使用不同证书，不能用它证明正式包的 App Links 已通过。服务器文件就绪和构建注入也不代表真实授权已验收，仍须验证实际 Actions APK 的 manifest、设备域名关联、授权与续期。Cloudflare 客户端默认 private，仅所属账号成员可授权；对外开放仍须官方域名验证及 public 设置。

## 授权立即回跳并失败的排查

2026-10-29 对上述公开构建配置做无登录、无令牌的授权入口检查，Cloudflare 返回
`invalid_scope`，说明该 Client ID 当时不允许请求 `account.read`。
这证明请求在同意页之前已被服务端拒绝，不是 App Links 未回跳，也不能靠重试解决。

维护者随后提供的客户端范围截图确认，已允许的是 `account-settings.read`（Account Settings Read）及 `offline_access`，不是 `account.read`。已将 Actions 配置、本地默认值和测试样例同步为 `account-settings.read`，并修正本地 scope 校验遗漏连字符的问题；不需要为此增加云端权限。

不要猜 scope、移除全部 scope 碰运气或一次勾选全部读写。修正后的新构建仍需实际浏览器同意、账号发现及续期验收；旧 APK 不会自动更换 scope。
App 将已知错误码映射为明确提示，不显示原始 `error_description`、授权 URL 或凭据。

授权说明/账号选择页不显示密钥，允许截图；已移除 API Token 输入页，其他敏感资源编辑器的保护不受影响。

## 历史：OAuth 客户端维护者配置（不再需要）

1. 在 Cloudflare 的 Manage Account → OAuth clients 注册 FlareGo 客户端。选择 `authorization_code` 与 `refresh_token` grant、`code` response、`token_endpoint_auth_method=none`；移动端必须使用 PKCE S256，不创建或嵌入 Client Secret。
2. 登记维护者控制的精确 HTTPS 回调，例如 `https://login.example.org/cloudflare/callback`（仅示例，不能直接用于生产）。本实现不使用自定义 scheme 或本地回调服务。
3. 在该回调域名部署 `/.well-known/assetlinks.json`，声明包名 `com.flarego` 和实际 APK 签名证书 SHA-256 指纹。Debug 与发布证书不同，按实际测试包配置；指纹是公有元数据，不上传私钥。配置有效的 TLS 和回调落地说明页，不记录查询参数/授权码，不引入第三方脚本或转发授权码。
4. 客户端默认 private，仅所属账号成员可授权。面向其他用户开放须按官方流程补齐名称、Logo、应用网站和 DNS 域名验证；转为 public 是外部不可逆操作，本任务不自动执行。
5. 在本机 Gradle 用户配置或 CI 构建参数提供以下**公开配置**：

```properties
flarego.oauthClientId=<已注册的Client-ID>
flarego.oauthRedirectUri=https://<已验证域名>/cloudflare/callback
flarego.oauthScopes=account-settings.read
```

当前固定范围见顶部“一次只读授权”；构建不再提供引导 scope，也不再运行时解析目录。实际账号与资源能力仍须真实授权后核验。App 会另请求 `offline_access` 供续期；它不是控制台资源权限列表中的普通产品权限，不必在该列表中搜索勾选。客户端应允许 `refresh_token` grant，续期能否成功仍需实际授权验证。不同功能的 scope ID 应从 Cloudflare 官方 OAuth scopes 列表确认，不能把 API Token 展示名称或 Wrangler scope 直接当成第三方 OAuth scope。维护者按实际功能配置最小读取范围，勿一开始授予全部读写；本版尚无按功能增量授权 UI。

Client ID 不是密钥；不需要向用户索取 Client Secret 或 Global API Key。OAuth 配置随构建生效，Gradle 默认构建不读取云端凭据。缺省回调为不可用的 `.invalid` 域名。未配置的构建禁用授权入口并提示联系维护者，没有手动 Token 备用入口。

### Client ID 与回调地址的安全边界

Client ID 是应用的公开标识，可以包含在 APK 中。它本身不能代表用户访问云资源。移动客户端采用 `token_endpoint_auth_method=none`，以每次请求独立生成的 PKCE verifier 交换授权码；verifier 不放在授权 URL 中。需要保密的是 verifier、授权码、access token 与 refresh token，而不是 Client ID。`state` 校验用于将回调绑定到本次请求，不能用公开 Client ID 代替这些校验。

本实现使用 Android HTTPS App Links，不能用 `http://localhost` 或 `http://127.0.0.1` 作为回调。需要维护者能够管理且设备可访问的 HTTPS 域名，并发布包含包名、实际签名证书指纹的 `/.well-known/assetlinks.json`。这不要求搭建保存令牌的后端：授权码交换和加密保存都由手机完成，站点只提供域名关联文件及无敏感日志的回调落地页。其他 OAuth 客户端的 loopback 方案不代表本 App 已支持，也不代表 Cloudflare 已接受相应回调注册。

未注册客户端时，本地测试用隔离 HTTP 服务替代令牌端点、用测试回调触发真实校验逻辑；这些测试不能证明 Cloudflare 同意页、域名关联或浏览器回跳已经可用。

## 安全与兼容

- 只在系统浏览器打开固定 Cloudflare 授权入口，不使用嵌入式 WebView。
- verifier、state、未保存的授权只驻留 ViewModel 内存。Activity 重建可继续；进程退出后必须重新授权，不从陌生回调创建连接。
- 精确校验 HTTPS 主机、端口和路径，无 fragment/用户名/密码；state 单值匹配，授权码单值，十分钟有效，一次消费。拒绝/错误正文不直接显示，令牌对象不生成含密钥的字符串。
- 等待期间重新打开授权页会复用仍有效的 state 与 PKCE 请求；超过十分钟重新生成，取消则立即失效。旧授权或不匹配 state 的回调不会消耗当前请求，匹配的拒绝/错误回调仍为一次性处理。
- 账号发现失败或为空后可在原授权下重试，也可重新授权；翻页失败保留已有列表及待重试页。主动重新授权会清除旧的账号选择。取消或重新开始后，迟到的续期响应不会恢复旧凭据或继续验证旧账号。
- 令牌端点固定 `https://dash.cloudflare.com/oauth2/token`，禁止重定向/自动重试，响应最多 64 KiB。只有令牌请求重签；业务 401、写入未知结果都不自动重放。
- access/refresh token 和过期时间一起存入原有 TokenVault 密文。旧 API Token 格式不变，无自动迁移；显式重新连接同一 Account ID 才替换凭据。
- 续期、替换和删除由应用级逐连接锁串行处理；缓存会话逐请求读取当前令牌，并检查原有凭据代次，避免恢复过期 token 或已断开的连接。
- OAuth 授权成功不是写权限证明。现有 Token verify/details/catalog 与 Zone owner 守卫不绕过；这些辅助接口不兼容 OAuth 或权限不足时仍拒绝受保护的写操作。OAuth 只能执行实际 scopes 和既有权限守卫允许的操作；不再引导用户手工添加 API Token 来补足能力。
- App 内断开仅删除本机连接及凭据，不撤销云端 grant。用户可在 Cloudflare 的 OAuth authorizations 管理页撤销；重新连接后需重新授权。刷新令牌也具有敏感性，泄漏后应撤销授权。

## 验证

本地自动化覆盖 PKCE 请求、伪造/重放/过期回调、拒绝授权、异常/超大响应、重定向拒绝、单飞续期及轮换保存、旧 Token 兼容、资源关闭与业务请求不重放。构建使用：

```sh
ANDROID_HOME=<SDK路径> SCHEMA_FILE=<固定公开OpenAPI路径> ./gradlew :android-platform:testDebugUnitTest :shared-core:jvmTest :shared-ui:jvmTest :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug
```

必须补充的真实验收：部署 App Links → 验证签名/包名 → 浏览器同意/拒绝 → 回跳 → 单账号自动连接／多账号按名称选择 → 读取已授权资源 → 重启恢复 → 过期续期 → 控制台撤销 → App 明确报错 → 已有账号兼容回归。没有设备或真实客户端时不能把这些项目判为通过。

官方资料：
- https://developers.cloudflare.com/fundamentals/oauth/
- https://developers.cloudflare.com/fundamentals/oauth/create-an-oauth-client/
- https://developers.cloudflare.com/fundamentals/oauth/integrate-with-cloudflare/
- https://developers.cloudflare.com/api/resources/iam/subresources/oauth_scopes/methods/list/
- https://developer.android.com/training/app-links/configure-assetlinks
