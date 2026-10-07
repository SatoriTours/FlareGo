# 2026-10-07 发布验证

- 公共源码仓库：SatoriTours/FlareGo，Apache-2.0。
- 提交签名构建：[37591325603](https://github.com/SatoriTours/FlareGo/actions/runs/37591325603)，成功。
- v0.1.0 Tag 签名构建：[37592194269](https://github.com/SatoriTours/FlareGo/actions/runs/37592194269)，成功。
- 已下载两个通道的发布 APK，验证 APK 签名、包名、版本及 APK/元数据 SHA-256。
- Android 签名证书 SHA-256：`c1ed85dba0f4f74082a7aa48d0e83cbd87c1ab3265c5e6381f73194362712ab1`。这是公开证书指纹，私钥和密码仅保存在 GitHub Secrets，本地临时签名文件已删除。
- 正式版本：0.1.0 / versionCode 10003；提交版本验证样本：0.1.0-dev.10002 / versionCode 10002。
- 7 项发布契约 Python 测试、26 项核心 JVM 测试、6 项平台单元测试、6 项 Android 原生测试通过；本地 Debug / Release 构建及 app / platform lint 通过。
- 原生测试覆盖搜索、账号切换、DNS 与购买确认、加密凭据、SQL 持久化、更新设置及拒绝旧版本/修改后的缓存/缓存外安装文件。
- 模拟器 API 36 已通过 App 从真实 GitHub Releases 下载并覆盖安装提交版（10000 → 10002）。下载及未知来源授权期间旋转屏幕后，待安装请求仍能进入系统安装器；只打开一次确认页。
- 已从签名提交版切换到正式通道，通过 App 下载并覆盖安装 v0.1.0（10002 → 10003），系统版本号已确认。
- 更新界面和系统安装确认截图：`design/native-updates-available.png`、`design/native-update-installer.png`。

以上真实更新验证只在隔离模拟器的演示账号运行，无真实云 Token 或云资源写操作。
