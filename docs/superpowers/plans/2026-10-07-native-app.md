# FlareGo 原生应用实施计划

**主线集成（2026-10-16）：**P1 九项已有实现，T6–9 集中提交 `f8840c2` 已本地合入 `main`，按用户授权推送 GitHub；恢复源码 core 285／平台 82／原生 49 项及 Debug／lint 通过。当前调用 16 项、真实云资格 0；P2 未开始。见 [主线集成验收](../../cloudflare-audit/p1-integration-verification-2026-10-16.md)。

> 历史计划：首版任务已完成并有历史验证。2026-10-11 的 `98cc0c9` 已将默认演示账号改为添加服务／密钥引导；下面的原始设计约束和勾选保留为首版记录，当前行为以 [原生实现规格](../../native-spec.md) 为准，最新实现及验证状态见 [开发进度](../../development-progress.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在 FlareGo 构建可安装的原生 Android 首版，保留已确认的紧凑导航设计并接入 Cloudflare。

**Architecture:** 核心领域与小型端口独立于厂商。Compose 共享界面通过应用状态调用用例；Android 提供传输、加密和数据库实现。

**Tech Stack:** Kotlin 2.3.20 / Compose Multiplatform 1.10.1 / AGP 9.0.1 / Gradle 9.1.0 / SQLDelight 2.3.2。

**Spec:** `docs/native-spec.md`，视觉参考 `docs/ui-design.md` 与 `design/mobile-*.png`。

## Global Constraints

- 不修改 `/home/jimxl/projects/dreamtools`。
- minSdk 26、compile / target SDK 36、JDK 17；固定依赖版本。
- 默认演示与真实连接分离，真实缺失数据不得填充示例。
- Token 不写入数据库/日志/备份；真实 DNS 写入必须经确认。
- 保留 Logo 抽屉、标题栏搜索、五个底部入口、紧凑资源行。

## Review Focus

- 切换账号期间的旧请求不得覆盖新账号。
- 部分服务无权限时仍显示有权限服务及逐项错误。
- 网络超时后的写入不得自动重试或声称失败已回滚。
- 无法读取金额时不得显示零费用或跨账号的总费用。
- 小屏幕、键盘和长资源名不得遮挡确认按钮与导航。

### Task 1: 核心应用与隔离

**Files:** `shared-core/build.gradle.kts`, `shared-core/src/commonMain/kotlin/com/flarego/core/{model,ports,application,demo}/*.kt`, `shared-core/src/commonTest/kotlin/com/flarego/core/AppControllerTest.kt`。

**Interfaces:** `CloudSession` 组合 `DnsPort` / `InventoryPort` / `BillingPort`；`AppController(scope, sessionFactory, store)` 提供 `StateFlow<AppState>`、`selectConnection`、`refresh`、`loadDns`、`saveDns`、`deleteDns`。

- [x] 配置四个 Gradle 模块和 JVM 测试目标。
- [x] 写测试：切换账号保留根页；慢旧请求不覆盖新账号；DNS 校验失败不写；写超时记录待核对且只发送一次；演示编辑重新读取可见。
- [x] 运行 `:shared-core:jvmTest`，确认缺失行为的失败，再实现模型、端口、用例和演示适配器。
- [x] 运行同一测试集确认通过。

### Task 2: Cloudflare 与平台存储

**Files:** `shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/*.kt`, `shared-core/src/commonMain/sqldelight/com/flarego/db/Local.sq`, `android-platform/src/main/kotlin/com/flarego/platform/*.kt`, `shared-core/src/commonTest/kotlin/com/flarego/core/CloudflareTest.kt`。

**Interfaces:** `HttpTransport.execute(HttpRequest): HttpResponse`；`CloudflareSession(accountId, transport)` 实现 Task 1 端口；`LocalStore` 存放连接元数据/操作记录；Android 工厂注入 Token 到固定 HTTPS transport。

- [x] 写测试：分页正确限制账号；403 输出权限提示；R2 对象形返回正确；部分库存失败隔离；DNS 仅修改指定记录且安全序列化；请求中 Token 不进入错误文本。
- [x] 运行测试观察失败，实现适配器及平台 HTTPS/Keystore/SQLDelight。
- [x] 运行完整 JVM 测试。

### Task 3: 原生界面与打包

**Files:** `shared-ui/src/commonMain/kotlin/com/flarego/ui/*.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/flarego/MainActivity.kt`, `app/src/androidTest/kotlin/com/flarego/AppSmokeTest.kt`, `README.md`。

**Interfaces:** `FlareGoApp(controller, connect, disconnect, openConsole)`；Android ViewModel 组合核心和平台并管理生命周期。

- [x] 写原生 smoke 测试：资源入口、折叠搜索、抽屉切换、域名详情与 DNS 编辑确认。
- [x] 实现主题/应用壳/列表/详情/表单；检查 320dp、长名称和滚动表单。
- [x] 运行 `:shared-core:jvmTest :app:assembleDebug :app:lintDebug`；本地模拟器可用时跑 UI 测试并截图。
- [x] 更新 README 和架构中的原生技术决定，提供 APK 路径与能力边界。

当前目录没有 Git 仓库，因此在用户指定目录直接开发，不创建 worktree、不提交或推送。批准的设计与开发指令已构成执行授权，继续实施，不重复要求审批。

**本轮回归（2026-10-15）：**隔离分支 P1 Task9 完成生产装配，core 285／平台 82／原生 49 项与 Debug 构建／lint 通过，发布契约 10 项通过；未重建签名 Release、未验证真实 APK 升级或触发发布。见 [Task9 验证](../../cloudflare-audit/p1-task9-verification-2026-10-15.md)。
