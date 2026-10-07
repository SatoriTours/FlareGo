# FlareGo 原生首版

沿用 dreamtools 的语言与工程技术：Kotlin 2.3.20、Compose Multiplatform 1.10.1、AGP 9.0.1、Gradle 9.1.0、SQLDelight 2.3.2。只参考技术栈，不修改或复制 dreamtools 的业务代码。Android 8.0 / API 26 起，compile / target SDK 36，JDK 17。

## 首版交付

- 原生 Android APK；共享业务模块同时有 JVM 测试目标。
- 58dp 标题栏：Logo 打开左侧云账号抽屉，页面名称取代固定品牌名，右侧搜索按需展开。
- 总览、域名、DNS、域名购买、云资源、资源详情、账单、账号、操作记录；底部五个入口。资源采用紧凑列表。
- 默认演示账号，可完整体验导航、DNS 新增/编辑/删除及购买确认。所有演示金额和模拟操作明确标注。
- 真实 Cloudflare：输入 Account ID 和 API Token，验证账号访问；查询该账号域名、Workers、R2、D1、KV；查询 DNS；经确认后新增、编辑、删除 DNS。连接和权限失败显示可恢复错误，不用演示数据补齐真实结果。
- 账单调用用户级历史接口；显式标注用户范围，未知费用保持未知。用户级 Token 无此权限时解释并提供官方控制台入口。
- 域名真实购买、创建资源、Worker 部署和 R2 对象操作首版导向官方控制台，不伪造完成状态。其余云厂商显示规划中。
- Token 使用 Android Keystore AES/GCM 加密存储，不写入 SQL、日志、截图或备份。SQLDelight 只保存连接元数据和操作记录。应用禁用系统备份。

## 结构

`shared-core` 的 domain/ports 不依赖 UI 或 Android；application 管理账号隔离、并发结果和写入操作；adapters 包含演示与 Cloudflare 实现。`shared-ui` 只消费应用状态和意图。`android-platform` 实现 HTTPS transport、Keystore 与 SQLDelight 持久化。`app` 作为 Android composition root。

真实请求仅发送到固定 Cloudflare API HTTPS 地址，不允许重定向携带 Token。HTTP 使用 OkHttp，禁用连接失败重试、重定向和认证跟进，写请求体标记一次性以禁止 503 等响应跟进。无自动重试写请求：超时等不确定结果标记待核对，并重新读取 DNS，避免重复创建。切换账号清空详情/搜索，保留底部功能，过时返回不可覆盖新账号。

## 验证

JVM 测试覆盖账号切换、过期请求、DNS 确认/校验、未知操作结果、分页、权限错误、部分资源不可用、脱敏。构建 Debug APK并执行 Android lint；能启动本地模拟器时记录原生截图和主要交互验证。未提供用户凭据，不执行真实云写操作。
