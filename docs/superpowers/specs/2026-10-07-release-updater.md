# GitHub 发布与应用更新

用户授权：在 SatoriTours 组织创建公开 FlareGo 仓库，提交现有代码，使用 GitHub Actions 构建提交版本及 Tag Release，签名保存在 GitHub，应用参考 dreamtools 的更新方式。dreamtools 仅只读参考，不修改。

- 公开仓库 `SatoriTours/FlareGo`，Apache-2.0；主分支 main。
- 一个 Android workflow：push 分支、vMAJOR.MINOR.PATCH 标签、PR、手动触发。PR 仅执行测试/检查和 Debug 构建；可信 push/dispatch 执行正式签名 Release 构建。
- 签名使用新生成的独立 RSA 密钥，四个 GitHub Secrets：ANDROID_KEYSTORE_BASE64、ANDROID_KEYSTORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD。私钥与密码不进入 Git、产物或日志。所有可下载版本使用同一签名。
- main 发布滚动 `latest` 预发布，Tag 发布对应正式 Release；其他分支只保留构建产物。每次发布包含 APK、build-metadata.json、SHA256SUMS。正式版本号取 Tag，提交版本显示 dev 构建号。
- 单一 workflow 的版本号序列加上已发布版本上限选择单调递增 versionCode；全局串行发布，旧构建不得降低 latest。重复发布正式 Tag 拒绝覆盖。
- 更新入口放在账号页“应用更新”。默认正式版本，可选择最新提交；默认启动时自动检查，跨进程六小时节流，手动检查不受节流限制。公开仓库不要求用户 Token。
- 先验证 Release、元数据和 checksum 一致性，再下载到私有缓存；验证长度、SHA-256、包名、版本、Android 兼容性与当前签名后才提供安装 URI。
- 下载使用固定 GitHub API 与可信 HTTPS 重定向白名单。安装必须经过系统未知来源授权与系统安装器确认，不静默安装。不支持降级。
- 首次发布 `v0.1.0` 验证 Tag 构建链；同时验证 main 的提交构建与 latest 附件。观察 Actions 真正完成，不能以仅提交 YAML 代替构建验收。

验证：CI 元数据脚本 unittest；核心更新协议/状态测试；平台下载和完整性测试；Android 签名包安装入口测试；现有 JVM/HTTP/UI 回归及 lint；线上两类 Actions 和签名产物检查。
