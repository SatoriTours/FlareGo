# Android 构建、签名与更新

公开仓库：[SatoriTours/FlareGo](https://github.com/SatoriTours/FlareGo)。许可证 Apache-2.0。

## 构建通道

| 触发 | 检查 | 产物 |
| --- | --- | --- |
| Pull request | 核心/平台单元测试、lint、Debug 编译 | 不使用签名 Secrets，不发布 |
| 任意分支提交 | 同上及签名 Release APK | Actions 的签名构建 artifact |
| main 提交、默认手动构建 | 同上 | 仅 Actions artifact，不创建/移动 Tag 或发布 Release |
| main 手动构建，明确勾选 `publish_snapshot` | 同上 | 滚动的 `latest` prerelease，`flarego-latest.apk` |
| `vMAJOR.MINOR.PATCH` Tag | 同上 | 同名正式 Release，`flarego-MAJOR.MINOR.PATCH.apk` |

普通提交只构建签名 APK，不创建或移动 Tag，也不发布 Release。普通构建任务只有仓库读取权限；独立发布任务仅在明确的 Tag push 或手动测试发布请求时获得写权限，发布脚本会再次检查该请求。

仅在维护者明确决定发布正式版本后，手动创建和推送 Tag。例如：

```sh
git tag v0.2.0
git push origin v0.2.0
```

测试发布需要在 Actions → Android → Run workflow 中选择 main，明确勾选 `publish_snapshot`；默认不勾选。此操作会创建或移动 `latest` Tag，并发布测试 Release。普通构建 artifact 可从对应运行页面下载，其中包含签名 APK、元数据和 SHA256SUMS；默认保留 30 天，下载需要登录 GitHub。

Tag 不允许前导零或预发布后缀，已经发布的正式 Release 不会覆盖。先保证对应提交通过检查，再创建新 Tag。main 的旧提交重跑仅保存 artifact，不替换当前 `latest`。流水线统一串行，且不取消正在发布的构建。`versionCode` 为 `max(10000 + workflow run number, highest recorded metadata versionCode + 1)`，草稿中的已分配元数据也参与版本下限，避免中断发布后重用较低版本号。所有通道共用序列；切换通道只安装高于本机版本的 APK。

发布附件：APK、`build-metadata.json`、`SHA256SUMS`。元数据包含通道、Tag、显示版本、整数版本号、Git commit SHA、APK 名称；校验文件绑定 APK 和元数据。发布期间滚动 Release 暂时转为 draft，上传全部附件后再发布。若网络故障造成草稿，请在该显式发布运行中选择 **Re-run all jobs**：重新分配更高版本号、重新签名并替换本次运行的 artifact，再完成草稿发布。不要仅重跑 publish job，它会拒绝复用草稿已占用的版本号。已公开的正式版本仍不可覆盖。

## 发布签名

仓库 Settings → Secrets and variables → Actions 保存以下四项 Repository Secrets：

- `ANDROID_KEYSTORE_BASE64`：FlareGo 独立 JKS 的 Base64。
- `ANDROID_KEYSTORE_PASSWORD`：Keystore 密码。
- `ANDROID_KEY_ALIAS`：签名别名。
- `ANDROID_KEY_PASSWORD`：私钥密码。

提交版和正式版始终共用同一签名。GitHub 对 Secrets 加密保存，runner 仅在临时目录以私有权限还原 JKS，任务结束删除。私钥及密码不进入源码或构建 artifact。不要替换现有签名密钥，否则已安装应用不能直接升级；Debug 签名也不能覆盖安装正式签名 APK。

没有配置签名 Secrets 的 fork 可以运行 PR 检查；分支发布构建会明确失败，不会悄悄发布未签名 APK。本地 Release 构建通过环境变量 `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` 配置，版本参数为 `-Pflarego.versionName=... -Pflarego.versionCode=...`。不要把密码写入 Gradle 文件。

## 应用内更新

「账号 → 应用更新」可选择正式版本或最新提交，默认正式版本。最新提交通道读取最后一次明确发布到 `latest` 的测试构建；普通提交的 Actions artifact 不会自动进入应用更新。自动检查间隔六小时，设置和上次尝试时间跨进程保存；手动检查不受此限制。发现新版本时标题栏出现更新入口。更新失败不影响云资源管理。

更新使用公开 GitHub Releases API，无需 GitHub Token，也不会发送云服务 Token。仅接受 GitHub 固定 HTTPS 来源及有限次可信重定向；发布描述和文本附件限制为 1 MB，APK 限制为 512 MB。先核对元数据 SHA-256，再流式下载 APK，大小及 SHA-256 一致后才将临时文件提交为完整缓存。

安装前再次验证缓存文件、应用包名 `com.flarego`、发布版本名称/版本号、Android 最低版本以及与当前应用相同的签名。禁止降级。下载状态由 ViewModel 持有，旋转屏幕不重新启动下载；未知来源授权返回后再次校验 APK。文件仅通过限制为私有 `cache/updates` 的 FileProvider 分享，用户在 Android 系统安装器确认安装。应用卸载或清理缓存会移除下载文件。
