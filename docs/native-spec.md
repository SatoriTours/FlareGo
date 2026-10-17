# FlareGo 原生实现规格

> 2026-10-17 恢复批次：P2 已恢复到独立未提交分支并完成本地验证。706 core、91 平台项通过；354 个设备方法分轮均有 PASS，原 330 项轮次的 4 个失败及后续复验原样保留。真实云与付费资格未验收，主线尚未合入。见 [恢复验收报告](cloudflare-audit/p2-recovery-verification-2026-10-17.md)。


**历史主线集成（2026-10-16）：**P1 九项已有实现，T6–9 集中提交 `f8840c2` 已本地合入 `main`，按用户授权推送 GitHub；恢复源码 core 285／平台 82／原生 49 项及 Debug／lint 通过。当前调用 16 项、真实云资格 0；P2 未开始。见 [主线集成验收](cloudflare-audit/p1-integration-verification-2026-10-16.md)。
**当前 P2 工作区（2026-10-17，未提交）：**54 候选端点已有 typed 产品 handler、独立预览确认与恢复路径，并通过五 typed ports 接入原唯一 AppOperationRuntime。P1 union 为65；独立 authority aux4 后实际 registry 为69（39 READ／30 WRITE），由运行中的 registry 核对，不以源码行数统计。当前覆盖证据见 [P2 验证](cloudflare-audit/p2-verification.md) 与 [机器映射](cloudflare-audit/p2-runtime-support-2026-10-17.json)。恢复批次 core 706／平台 91、Debug、lint、Python 349＋10 已通过；354 设备方法分轮通过。源码尚未提交，真实云与付费未验收。下方有明确日期的 P1／首版结论保留其历史含义。


首版基线：2026-10-15，源码 `98cc0c9`；当前 P1 集成状态以上方 2026-10-16 记录为准。本文描述当前 Android 入口；首版及后续扩展的实现／验收证据见 [开发进度](development-progress.md)。长期设计和模拟流程不自动计为已实现产品。

沿用 dreamtools 的语言与工程技术：Kotlin 2.3.20、Compose Multiplatform 1.10.1、AGP 9.0.1、Gradle 9.1.0、SQLDelight 2.3.2。只参考技术栈，不修改或复制 dreamtools 的业务代码。Android 8.0 / API 26 起，compile / target SDK 36，JDK 17。

## P2 当前功能分支（2026-10-17）

当前未提交的功能分支已接入 Zone 生命周期、21 类 DNS 表单与差量写入、批量操作、BIND 导入导出、扫描审核与 DNS Settings、DNSSEC、20 项受控站点设置、Universal SSL 和基础清缓存。54 项候选端点均保留在覆盖分母中；生产手写注册表含与 P1 合集的 65 项及独立 4 项权限自省读取。详情见 [P2 验证记录](cloudflare-audit/p2-verification.md)。

真实权限只使用当前凭据与供应商信息验证；未知权限或资格保持只读。演示功能使用同一任务流程并隔离真实凭据和云请求。导入结果未知时禁止重发；扫描与缓存请求受理不表示全球完成。DNSSEC 注册商 DS 配置与付费资格未做真实云验收。当前 Debug 构建与 lint 通过，706 core／91 平台项通过；354 个设备方法分轮均有 PASS，不能称单轮全绿。下文带日期的 P0/P1 状态为历史记录，主线未因本批次自动更新。

## 首版与 P1 已有范围（历史，见日期）

- 原生 Android APK；共享业务模块同时有 JVM 测试目标。
- 58dp 标题栏：Logo 打开左侧云账号抽屉，页面名称取代固定品牌名，右侧搜索按需展开。
- 总览、域名、DNS、域名购买、云资源、资源详情、账单、账号、操作记录；底部五个入口。资源采用紧凑列表。
- 首次安装显示添加服务及配置密钥引导，不预置演示账号；恢复已保存连接，移除最后一个连接后回到引导。演示适配器、DNS 模拟操作和购买确认只保留用于原型展示及隔离测试。
- 真实 Cloudflare：输入 Account ID 和 API Token，验证账号访问；查询该账号域名、Workers、R2、D1、KV；查询 DNS；经确认后新增、编辑、删除 DNS。连接和权限失败显示可恢复错误，不用演示数据补齐真实结果。
- 账单默认调用账号历史接口；用户级旧接口由显式入口选择，未知费用保持未知。用户级 Token 无此权限时解释并提供官方控制台入口。
- 域名真实购买、创建资源、Worker 部署和 R2 对象操作首版导向官方控制台，不伪造完成状态。其余云厂商显示规划中。
- Token 使用 Android Keystore AES/GCM 加密存储，不写入 SQL、日志、截图或备份。SQLDelight 保存连接元数据、旧操作记录及新增安全任务元数据表；后者已接入主线 DNS 生命周期、启动恢复与核对并完成本地验证。应用禁用系统备份。

## Cloudflare 扩展的集成前状态（2026-10-15）

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

## P2 当前工作区的原生契约

- Zone 是供应商 Zone，并非 Registrar。创建绑定所选账号，删除需要 fresh owned provider fullname 的精确输入；activation pending 和 nameservers 不宣称注册或传播完成。
- DNS21类型采用固定 Schema 局部定义；raw 未知字段保留展示，PATCH 仅明确差量，PUT 独立完整替换与确认，嵌套未知语义不能证明时拒绝。IPv6 等价仅本地128位规范化，无网络解析。
- Batch 同一数据库事务按 deletes→patches→puts→posts；传播非原子。未知套餐保守200，不静默拆批；3500仅可信 scope/credential/时效证据接口，生产付费资格未验。安全 per-item manifest 与 task/summary 同事务持久化，不保存正文；新 runtime 只读证明当前状态，不归因历史执行者。
- BIND 导入上限262144 bytes、全 Application/连接共享滚动60秒三次；实际系统 Open/Create flags/persisted grant，job claim独立于 presentation clear/recreate，真实 route exit 撤旧 picker。只读导出不依赖 import 写权限或 records list 成功，保原字节；Partial/UNKNOWN 不从记录总数推成功或自动重导。
- Scan触发仅受理；空候选不证明完成，审核明确接受／拒绝；DNS Settings 权限与字段资格分开，false/omitted保留区别。DNSSEC风险按实际操作确认，DS/Registrar及外部预签名责任不代办；NSEC3/paid未知不开放。
- Zone settings只固定审核目标与同次prepare snapshot；editable/字段资格未知则只读。SSL降级、关闭Universal SSL、清全站缓存独立风险确认；purge受理不等于全网缓存已清空。
- Authority稳定issuer属于credential generation，跨短gateway/session重建保留catalog ID binding/tombstone；只内部identity proof可消费，公开三态不是授权。资源wildcard/deny/expiry等按已审子集求值，IP/未知conditions保持unknown；不读取deprecated Zone.plan猜套餐。
- App adapter 的===lease、actual fullscope/epoch/credential/revision与原子IO只读guard独立于plan。真实输入变化撤旧确认；handoff后appjob保实际能力，结束finally撤本draft guard。新VM恢复metadata但不恢复file/body/confirm/风险receipt；import显示仅真实typed outcome，export只显示同context/route/target实际commit结果。Demo明确本机示例，零生产acquisition/task写，与真实云验收分开。

恢复批次已核对 24 classes／354 methods：330 项轮次原为 326 PASS／4 FAIL；后续 9 项定向通过包含全部原失败，另 24 项真实 VM/SAF/DB 集成在 system font2 通过。旧失败不倒写为通过；各轮源码快照和条件见恢复报告。Zone invalidName 的一次失败原因未确定，不宣称已修复稳定性。
