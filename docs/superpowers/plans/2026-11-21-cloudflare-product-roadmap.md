# FlareGo 对照 Cloudflare：移动运维开发路线（待评审）

基线：main 9ab8952 及当前工作区。对照公开管理能力和 Android 原生入口，不把官网链接、演示数据、协议模型或其他工作区的实现计作当前主线交付。不是对所有官方接口的实时重抓，也不是已发布 APK 或生产账号验收报告。

## 产品差距

| 用户要办的事 | 当前主线 | 相对官方的缺口和取舍 |
|---|---|---|
| 管账号、域名和解析 | Token 发现账号、按能力补权；Zone 生命周期、21 类 DNS、批量、BIND、扫描、DNSSEC；20 项受控站点设置、Universal SSL、清缓存 | 不重做记录编辑。高级 DNS、证书生命周期、规则集和策略变更是不同工作流；按套餐分开 |
| 看边缘服务上线情况 | Workers 现代目录、显式旧目录、资源元数据 | 缺 Worker 版本、部署和流量关系；Pages 缺项目、环境和部署状态。官网打开不算 App 已部署 |
| 管数据 | R2 桶/对象及配置、任务、通知、指标已有入口；KV 有键值及批量；D1 有 SQL、导入导出和恢复代码 | 核实生产准入而非重做 CRUD。D1 导入、导出、恢复和恢复已删库在既有证据中缺云端资格；R2 S3 分片、迁移等要另核协议和凭据，独立树 Slurper 不算 main |
| 看站点健康和安全 | 有站点配置和产品级 R2 指标；不是全站流量和安全事件面板 | GraphQL 站点请求、带宽、错误趋势；安全事件、WAF/Cache Rules 及其优先级。先观察，后改变线上行为 |
| 看账单、注册域名 | 账号账单历史；Registrar 原生只读搜索，官方完成购买 | 非实时费用预测、非精确注册报价；发票、订阅、续费和购买不是同一个权限。支付操作暂留官网 |
| 管其他产品与团队 | 当前产品目录不是 Cloudflare 全平台 | Zero Trust Access/Tunnel、负载均衡、Queues、Durable Objects、Workflows、AI、媒体、成员与审计、企业网络等按需分包。运行连接器、运行时代码不等于手机管理 API |

## 分期与交付门槛

1. **随首期校准能力清单**：更新 README 中“存储只读目录”等过时描述；按“有原生入口／生产受限／仅官网／演示／未接入”列出能力和证据。历史固定端点 CSV 不回写、不混算覆盖率。针对本期候选用当前官方契约核对标识、读权限和套餐。
2. **Workers 只读发布详情（首个独立版本）**：从现有资源列表进入 Worker，回答“最近部署是哪次、涉及哪些版本、各占多少流量”。候选：脚本名下 deployments、versions 列表与详情；现代 worker_id 与 script_name 不可互代，先验证映射，不能可靠定位就明示不可用。复用 ProductLoader、账号作用域与资源导航。区分最新创建版本与已部署版本；展示时间、来源，缺字段不猜。验收：单版本、分流、无部署、翻页、403、限流、快速切换账号不串数据；只读路径零写请求、不默认追加权限。
3. **Pages 只读部署（第二个独立版本）**：单独接入项目→生产/预览→部署详情；显示成功、失败、进行中及最后更新时间，不把预览当生产。空项目和无权限可区分；暂不重试构建、不自动拉取可能含敏感正文的日志。
4. **站点分析（第三个版本）**：以选中 Zone 为范围，用 GraphQL 展示近 24 小时及 7 天请求数、带宽和错误趋势。验收：时间范围与时区明确、数据为空与真的为零可区分，采样及保留期受限有说明；无权限不影响 DNS。随后可接只读安全事件与缓存命中指标。
5. **受控写入与存储补证**：先允许从已验证的 Worker 版本选择部署目标，展示账号、环境和流量影响，再单独设计回滚。以授权的非生产资源验证、结果不确定不重放，明确回滚不还原 D1/KV。并按实际授权对 R2/KV/D1 已有能力做最小云端试点；未获资格的操作继续关闭。不以新功能上线为由扩大默认 Token。
6. **长期分包**：优先移动排障场景（规则只读、证书状态、Tunnel 健康、审计查询）；之后才考虑规则写入、团队管理、按需开发者产品。支付、企业网络、专用凭据或套餐未具备时标外部限制；完整长尾沿用既有 P5–P9 台账，不以堆接口数量排期。

各期设备验收只在指定 PVE Android 环境执行，覆盖实际首页入口、错误态、窄屏和大字体；既有 Actions 不变。测试替身和 PVE 验证不代替真实 Cloudflare 资格；真实账号、付费资源、生产写入另需授权。下一步只建议批准第 1–2 项，其余按使用反馈滚动调整。

## 核对依据

- 仓库：docs/cloudflare-coverage-audit.md、docs/cloudflare-audit/endpoints.csv（冻结官方 schema）、docs/development-progress.md（分期证据和限制）。
- 实际接线：shared-ui/src/commonMain/kotlin/com/flarego/ui/FlareGoApp.kt；同目录 r2/R2BrowserHost.kt、kv/KvBrowserHost.kt、d1/D1BrowserHost.kt；shared-core 对应 cloudflare 客户端与 ZoneSettingsModels.kt。
- 官方：[Workers 版本和部署](https://developers.cloudflare.com/workers/configuration/versions-and-deployments/)、[Pages API](https://developers.cloudflare.com/api/resources/pages/)、[GraphQL Analytics](https://developers.cloudflare.com/analytics/graphql-api/)、[缓存清理](https://developers.cloudflare.com/cache/how-to/purge-cache/)、[R2 S3 兼容范围](https://developers.cloudflare.com/r2/api/s3/api/)、[官方 Schema](https://github.com/cloudflare/api-schemas)。
