# GitHub 发布与应用更新实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 发布签名的 FlareGo 开源 Android 应用，打通提交、Tag 与应用内更新闭环。

**Architecture:** 核心更新协议和控制器位于 shared-core；GitHub 下载/Android 安装验证位于 android-platform；账号页呈现更新状态，app ViewModel 持有控制器。CI 输出的元数据与 APK 是统一版本依据。

**Tech Stack:** 保持现有 Kotlin/KMP/Compose/OkHttp 工程；GitHub Actions、Python 3、gh CLI、Android 签名。

**Spec:** `docs/superpowers/specs/2026-10-07-release-updater.md`。

## Global Constraints

- dreamtools 只读；仓库 public SatoriTours/FlareGo，main，Apache-2.0。
- 私钥、密码不提交，不打印；签名通过 GitHub Secrets 注入。
- PR 不获得发布密钥；发布 APK 必须验证签名。
- 正式/提交通道共享签名，versionCode 单调；校验失败不能安装。

## Review Focus

- 旧 workflow 重跑不得覆盖较新 latest。
- 混合代际的 APK/metadata/checksum 必须拒绝。
- 下载残缺、大小不符、签名不符不得送安装器。
- 自动检查失败、网络中断不得阻断云管理界面。
- 切换通道及 Activity 重建不丢失下载/安装状态。

### Task 1: 签名 CI 与开源仓库

**Files:** `.github/workflows/android.yml`, `tools/ci/{build_metadata.py,prepare_signing.py,publish.py}`, `tools/ci/tests/test_metadata.py`, `app/build.gradle.kts`, `LICENSE`, `.gitignore`。
**Interfaces:** metadata(ref_type,ref_name,run_number,sha,highest) -> dict，APK/metadata/SHA256SUMS 发布契约。
- [x] 写元数据测试：合法 Tag、提交版本、单调序列、非法输入、拒绝旧发布，观察失败。
- [x] 实现脚本/签名属性/workflow，运行脚本测试。
- [x] 初始化 Git，准备可公开文件，生成并注入仓库 Secrets。

### Task 2: 核心更新协议与状态

**Files:** `shared-core/.../updates/{UpdateModels,ReleaseFormat,UpdateController}.kt`, `shared-core/src/commonTest/.../UpdateTest.kt`。
**Interfaces:** UpdateService.check(channel): AppUpdate / download(update,progress): String；UpdateStore 读取偏好/最后自动检查时间；UpdateController 的 StateFlow 和检查、通道切换、下载意图。
- [x] 写协议/状态测试：版本比较、checksum、缺失附件、通道约束、六小时节流、下载与重建，观察失败。
- [x] 实现核心协议和控制器，运行完整 JVM 测试。

### Task 3: GitHub 下载与原生安装/UI

**Files:** `android-platform/.../{GitHubUpdateService,AndroidUpdateInstaller}.kt`, `app/.../{FlareGoViewModel,MainActivity}.kt`, `shared-ui/.../UpdatePanel.kt`, Manifest/FileProvider 配置，平台/Android 测试。
**Interfaces:** installer.prepare(path,update): Intent，经 hash/包名/签名/版本校验；根 Activity 消费安装请求并处理授权。
- [x] 写下载完整性/可信 URL 与安装校验测试，观察失败。
- [x] 实现下载/进度、安装授权和账号页更新入口。
- [ ] 完成本地测试、Release 签名验证、lint 和界面验证。

### Task 4: GitHub 实际发布与验收

**Files:** README、发布/更新文档、验证记录。
- [ ] 完成只读独立审查并修复重要发现。
- [ ] 提交并推送 main，观察 Actions 构建与 latest 发布；创建并推送 v0.1.0，观察正式 Release 构建。
- [ ] 下载 CI APK 验证包名、版本与证书一致性，验证应用通过 GitHub 读取正式/提交更新契约。
- [ ] 提供仓库、Actions、Release 链接及实际通过的检查。

用户已明确授权公开、提交、签名与发布，直接连续执行；不要求重复设计审批。

## 实施记录

- 已创建公开仓库、Apache-2.0 许可证、四项签名 Secrets；同一密钥本地 Release 已通过 apksigner。
- 5 项 Python、26 项核心 JVM、6 项平台单元测试通过；Debug / Release 与 lint 通过。
- 独立审查修复：StateFlow.collect 不因消费安装请求取消验证；ViewModel 保留 pending 请求到系统安装器启动；Activity 重建及授权结果单次交接；Actions queue:max 保护排队 Tag 构建。
- 新增 Android 安装前拒绝旧版本、缓存篡改、缓存外路径的集成测试，验证持久化更新偏好。
