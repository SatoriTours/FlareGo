# FlareGo 原生实现规格

状态核对：2026-10-15，源码 `98cc0c9`。本文描述当前 Android 入口；首版及后续扩展的实现／验收证据见 [开发进度](development-progress.md)。长期设计和模拟流程不自动计为已实现产品。

沿用 dreamtools 的语言与工程技术：Kotlin 2.3.20、Compose Multiplatform 1.10.1、AGP 9.0.1、Gradle 9.1.0、SQLDelight 2.3.2。只参考技术栈，不修改或复制 dreamtools 的业务代码。Android 8.0 / API 26 起，compile / target SDK 36，JDK 17。

## 当前实现范围

- 原生 Android APK；共享业务模块同时有 JVM 测试目标。
- 58dp 标题栏：Logo 打开左侧云账号抽屉，页面名称取代固定品牌名，右侧搜索按需展开。
- 总览、域名、DNS、域名购买、云资源、资源详情、账单、账号、操作记录；底部五个入口。资源采用紧凑列表。
- 首次安装显示添加服务及配置密钥引导，不预置演示账号；恢复已保存连接，移除最后一个连接后回到引导。演示适配器、DNS 模拟操作和购买确认只保留用于原型展示及隔离测试。
- 真实 Cloudflare：输入 Account ID 和 API Token，验证账号访问；查询该账号域名、Workers、R2、D1、KV；查询 DNS；经确认后新增、编辑、删除 DNS。连接和权限失败显示可恢复错误，不用演示数据补齐真实结果。
- 账单调用用户级历史接口；显式标注用户范围，未知费用保持未知。用户级 Token 无此权限时解释并提供官方控制台入口。
- 域名真实购买、创建资源、Worker 部署和 R2 对象操作首版导向官方控制台，不伪造完成状态。其余云厂商显示规划中。
- Token 使用 Android Keystore AES/GCM 加密存储，不写入 SQL、日志、截图或备份。SQLDelight 保存连接元数据、旧操作记录及新增安全任务元数据表；主线后者仅建 store；隔离分支 Task9 已接入生产 DNS 生命周期并验证。应用禁用系统备份。

## Cloudflare 扩展的接入状态

P1 已有完整 scope／page／稳定错误模型、安全多媒体与流式传输、16 项手写策略、响应解码和分页、任务模型／存储／数据库迁移源码，以及确认执行／恢复状态机。

主线 `98cc0c9` 的 `AppController.dnsOperations` 是可选且未启用；隔离分支 Task9 的生产 `FlareGoViewModel` 已组装任务生命周期、task store、启动恢复、异步断开与只读核对，配合按产品加载状态、分页和显式旧接口选择。最终 core 285／平台 82／API 36 原生 49 项通过，Debug 应用／测试 APK 和 lint 通过（0 error，分别 3／4 warning）。本批次尚未提交合并，真实云未验收；完整 Zone／21 类 DNS 管理属于尚未开始的 P2。

旧 DNS Unit receipt 不宣称成功，初始保持 UNKNOWN；PATCH 可通过读回完整哈希一致确认。创建没有 provider receipt ID、删除无法从旧列表证明完整缺席，读回后仍需人工核对。恢复不重发，凭据替换与断开阻止旧请求／元数据复活；实时时钟和日期解析已注入生产 gateway。详见 [Task 9 验证记录](cloudflare-audit/p1-task9-verification-2026-10-15.md)。

Task 6 分支新增显式账号发现和分页选择、两类 Token 的可选状态验证；发现失败保留手工 Account ID，保存仍要求指定账号可读。草稿只在 ViewModel 内存保留，Token 修改使旧查询失效。Task 6 当轮验证为 core 221／平台 82／设备 30 项通过，详见 [Task 6 验证记录](cloudflare-audit/p1-task6-verification-2026-10-13.md)。主线产品清单仍以 `98cc0c9` 为准。

Task 7 分支新增按产品分页目录、Worker 不可变 ID／名称映射与显式旧接口标识，scope 校验与 demo 分离；Task 7 当轮 core 243／平台 82 项、Debug 构建和 lint 通过，当轮未重跑设备测试。生产装配和显式旧目录选择已在隔离分支 Task9 完成，详见 [Task 7 验证记录](cloudflare-audit/p1-task7-verification-2026-10-14.md)。

Task 8 分支新增账号账单、显式用户旧范围、金额原文保留，以及带 scope 检查的账单组件；缺失金额显示未知，用户旧界面明确标注“旧接口”。Task8 当轮 core 260／平台 82／设备 37 项、Debug 应用／测试 APK 构建与 lint 通过；真实云账号未验证，生产装配现已在隔离分支 Task9 完成。见 [Task 8 验证记录](cloudflare-audit/p1-task8-verification-2026-10-15.md)。

## 结构

`shared-core` 的 domain/ports 不依赖 UI 或 Android；application 管理账号隔离、并发结果和写入操作；adapters 包含演示与 Cloudflare 实现。`shared-ui` 只消费应用状态和意图。`android-platform` 实现 HTTPS transport、Keystore 与 SQLDelight 持久化。`app` 作为 Android composition root。

真实请求仅发送到固定 Cloudflare API HTTPS 地址，不允许重定向携带 Token。HTTP 使用 OkHttp，禁用连接失败重试、重定向和认证跟进，写请求体标记一次性以禁止 503 等响应跟进。无自动重试写请求：超时等不确定结果标记待核对，并重新读取 DNS，避免重复创建。切换账号清空详情/搜索，保留底部功能，过时返回不可覆盖新账号。

## 验证边界

本轮正式 Gradle core 202 项、Android 平台 82 项、API 36 全套设备 23 项通过，后者包含 9 项数据库迁移／事务／所有权测试；Debug 构建与 lint 通过，lint 有 7 项 warning。设备测试的过时预期及跨账号路径夹具已在隔离分支修正，未合并；没有真实云验收。详见开发进度与本轮验证记录。
