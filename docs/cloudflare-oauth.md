# Cloudflare OAuth 接入

## 当前边界

代码提供 Android 的 Authorization Code + PKCE S256 授权、HTTPS App Link 回调、账号发现/验证、Keystore 加密保存及 refresh token 续期。API Token 保持兼容，不接受 Global API Key。维护者已提供 OAuth Client ID，Android Actions 工作流会在构建前注入公开客户端配置；真实云授权和设备回跳仍未验证。缺少配置的其他构建会禁用按钮，不模拟授权成功。

## GitHub Actions 官方构建配置

`.github/workflows/android.yml` 的 `Configure public Cloudflare OAuth` 步骤在所有 Gradle 构建前追加以下公开配置到该次 CI checkout 的 `gradle.properties`，Debug 检查和签名 Release 构建均使用它们；无需修改本机 Gradle 用户配置，也不读取 Client Secret：

```properties
flarego.oauthClientId=9bd1b2bf245bd1b78506a7c0b6fbc11a
flarego.oauthRedirectUri=https://satori.tours/flarego/cloudflare/callback
flarego.oauthScopes=account.read
```

关联文件已部署在 `https://satori.tours/.well-known/assetlinks.json`，包名为 `com.flarego`，仅包含 GitHub Actions 正式签名证书 SHA-256：
`C1:ED:85:DB:A0:F4:F7:40:82:A7:AA:48:D0:E8:3C:BD:87:C1:AB:32:65:C5:E6:38:1F:73:19:43:62:71:2A:B1`。

这是签名证书指纹，不是 APK 文件哈希；只要 Actions 使用同一签名密钥，常规提交和重新构建无需更新服务器文件。更换签名或分发渠道时需核实实际证书。调试包使用不同证书，不能用它证明正式包的 App Links 已通过。服务器文件就绪和构建注入也不代表真实授权已验收，仍须验证实际 Actions APK 的 manifest、设备域名关联、授权与续期。Cloudflare 客户端默认 private，仅所属账号成员可授权；对外开放仍须官方域名验证及 public 设置。

## 维护者配置（不是每个 App 用户都要做）

1. 在 Cloudflare 的 Manage Account → OAuth clients 注册 FlareGo 客户端。选择 `authorization_code` 与 `refresh_token` grant、`code` response、`token_endpoint_auth_method=none`；移动端必须使用 PKCE S256，不创建或嵌入 Client Secret。
2. 登记维护者控制的精确 HTTPS 回调，例如 `https://login.example.org/cloudflare/callback`（仅示例，不能直接用于生产）。本实现不使用自定义 scheme 或本地回调服务。
3. 在该回调域名部署 `/.well-known/assetlinks.json`，声明包名 `com.flarego` 和实际 APK 签名证书 SHA-256 指纹。Debug 与发布证书不同，按实际测试包配置；指纹是公有元数据，不上传私钥。配置有效的 TLS 和回调落地说明页，不记录查询参数/授权码，不引入第三方脚本或转发授权码。
4. 客户端默认 private，仅所属账号成员可授权。面向其他用户开放须按官方流程补齐名称、Logo、应用网站和 DNS 域名验证；转为 public 是外部不可逆操作，本任务不自动执行。
5. 在本机 Gradle 用户配置或 CI 构建参数提供以下**公开配置**：

```properties
flarego.oauthClientId=<已注册的Client-ID>
flarego.oauthRedirectUri=https://<已验证域名>/cloudflare/callback
flarego.oauthScopes=account.read
```

默认只有 `account.read`，足够测试账号读取，不表示已授权资源管理。App 会另请求 `offline_access` 供续期；它不是控制台资源权限列表中的普通产品权限，不必在该列表中搜索勾选。客户端应允许 `refresh_token` grant，续期能否成功仍需实际授权验证。不同功能的 scope ID 应从 Cloudflare 官方 OAuth scopes 列表确认，不能把 API Token 展示名称或 Wrangler scope 直接当成第三方 OAuth scope。维护者按实际功能配置最小读取范围，勿一开始授予全部读写；本版尚无按功能增量授权 UI。

Client ID 不是密钥；不需要向用户索取 Client Secret 或 Global API Key。OAuth 配置随构建生效，Gradle 默认构建不读取云端凭据。缺省回调为不可用的 `.invalid` 域名。未配置的构建只显示配置说明及 Token 备用入口。

### Client ID 与回调地址的安全边界

Client ID 是应用的公开标识，可以包含在 APK 中。它本身不能代表用户访问云资源。移动客户端采用 `token_endpoint_auth_method=none`，以每次请求独立生成的 PKCE verifier 交换授权码；verifier 不放在授权 URL 中。需要保密的是 verifier、授权码、access token 与 refresh token，而不是 Client ID。`state` 校验用于将回调绑定到本次请求，不能用公开 Client ID 代替这些校验。

本实现使用 Android HTTPS App Links，不能用 `http://localhost` 或 `http://127.0.0.1` 作为回调。需要维护者能够管理且设备可访问的 HTTPS 域名，并发布包含包名、实际签名证书指纹的 `/.well-known/assetlinks.json`。这不要求搭建保存令牌的后端：授权码交换和加密保存都由手机完成，站点只提供域名关联文件及无敏感日志的回调落地页。其他 OAuth 客户端的 loopback 方案不代表本 App 已支持，也不代表 Cloudflare 已接受相应回调注册。

未注册客户端时，本地测试用隔离 HTTP 服务替代令牌端点、用测试回调触发真实校验逻辑；这些测试不能证明 Cloudflare 同意页、域名关联或浏览器回跳已经可用。

## 安全与兼容

- 只在系统浏览器打开固定 Cloudflare 授权入口，不使用嵌入式 WebView。
- verifier、state、未保存的授权只驻留 ViewModel 内存。Activity 重建可继续；进程退出后必须重新授权，不从陌生回调创建连接。
- 精确校验 HTTPS 主机、端口和路径，无 fragment/用户名/密码；state 单值匹配，授权码单值，十分钟有效，一次消费。拒绝/错误正文不直接显示，令牌对象不生成含密钥的字符串。
- 等待期间重新打开授权页会复用仍有效的 state 与 PKCE 请求；超过十分钟重新生成，取消则立即失效。旧授权或不匹配 state 的回调不会消耗当前请求，匹配的拒绝/错误回调仍为一次性处理。
- 账号发现失败后可在原授权下重试，也可手填 Account ID 验证；翻页失败保留已有列表及待重试页。主动重新授权会清除旧的账号选择。取消或重新开始后，迟到的续期响应不会恢复旧凭据或继续验证旧账号。
- 令牌端点固定 `https://dash.cloudflare.com/oauth2/token`，禁止重定向/自动重试，响应最多 64 KiB。只有令牌请求重签；业务 401、写入未知结果都不自动重放。
- access/refresh token 和过期时间一起存入原有 TokenVault 密文。旧 API Token 格式不变，无自动迁移；显式重新连接同一 Account ID 才替换凭据。
- 续期、替换和删除由应用级逐连接锁串行处理；缓存会话逐请求读取当前令牌，并检查原有凭据代次，避免恢复过期 token 或已断开的连接。
- OAuth 授权成功不是写权限证明。现有 Token verify/details/catalog 与 Zone owner 守卫不绕过；这些辅助接口不兼容 OAuth 或权限不足时仍拒绝受保护的写操作。当前只应把 OAuth 用作按实际 scopes 允许的读取接入，写入仍可用最小权限 API Token。
- App 内断开仅删除本机连接及凭据，不撤销云端 grant。用户可在 Cloudflare 的 OAuth authorizations 管理页撤销；重新连接后需重新授权。刷新令牌也具有敏感性，泄漏后应撤销授权。

## 验证

本地自动化覆盖 PKCE 请求、伪造/重放/过期回调、拒绝授权、异常/超大响应、重定向拒绝、单飞续期及轮换保存、旧 Token 兼容、资源关闭与业务请求不重放。构建使用：

```sh
ANDROID_HOME=<SDK路径> SCHEMA_FILE=<固定公开OpenAPI路径> ./gradlew :android-platform:testDebugUnitTest :shared-core:jvmTest :shared-ui:jvmTest :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug
```

必须补充的真实验收：部署 App Links → 验证签名/包名 → 浏览器同意/拒绝 → 回跳 → 选择账号 → 读取已授权资源 → 重启恢复 → 过期续期 → 控制台撤销 → App 明确报错 → Token 备用及已有账号回归。没有设备或真实客户端时不能把这些项目判为通过。

官方资料：
- https://developers.cloudflare.com/fundamentals/oauth/
- https://developers.cloudflare.com/fundamentals/oauth/create-an-oauth-client/
- https://developers.cloudflare.com/fundamentals/oauth/integrate-with-cloudflare/
- https://developers.cloudflare.com/api/resources/iam/subresources/oauth_scopes/methods/list/
- https://developer.android.com/training/app-links/configure-assetlinks
