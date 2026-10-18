# R2 应用接线：本地验证与剩余边界（2026-10-18）

## 源码状态

基线 `50681de`（`feature/cloudflare-p3-r2`）。本轮新增代码保留为该分支未提交改动；没有合并、推送、Tag 或 Release。

本次属于 Task 6b 的部分交付，不是 R2-A 全量完成，也不是云端资格验收。

## 实际接通的能力

- `MainActivity` → `FlareGoApp` → 资源页 R2 选择器 → `R2BrowserHost`。
- `FlareGoViewModel.r2Browser` 使用实际选中连接、credential revision、`operationEpoch` 绑定生产 `CloudflareR2Client`；没有第二套 Token、任意主机或公开测试后门。
- 桶列表、对象列表、cursor 续页、前缀筛选、返回桶列表、错误展示；只展示默认 jurisdiction。
- 页面/账号/凭据变化使旧请求和迟到文件选择结果失效。当前可见 key 才可加入选择集，改变选择会撤销批量删除确认。
- 上传文件选择使用 OpenDocument；`R2DocumentBridge` 负责 URI/grant，`stageR2Upload` 将源文档流式复制到私有 cache，在同一次读取中计算长度和 SHA-256。不修改/删除源文档；取消/超限/异常清除临时副本。只显示“已暂存，尚未上传”。
- 下载使用 CreateDocument 与既有 `DnsDocumentFiles` 事务：先写私有临时文件，校验目标仍为空，再发布并回读校验，失败中止清理。R2 实例显式配置最大 5 GiB；DNS 原有 4 MiB 导出上限不变。没有新增全文件内存缓冲。
- R2 文档 launcher 追加在原 DNS launcher 之后，不改变原 ActivityResult 注册顺序；请求由 VM 保留，消费一次。

## 刻意未放行的能力

`TokenAuthorityIssuer`/`WriteAuthorityObservation` 当前是 ZoneScope 契约，并不支持 R2 account scope。**本轮没有扩权，也没有把 GET 成功当作写权限证明**。

因此 App 上的创建桶、删除桶、上传、单对象删除、批量删除均显示明确的只读原因，测试断言没有非 GET 请求。现有领域客户端与控制器仍保留，等待账号级权限准入与运行时集成。这个缺口是待开发功能，不是“必须提供真实账号才能写代码”。

完整写入接线前仍须解决：

1. R2 账号级权限/Token 策略证明及最终发送守卫，与平台凭据 revision 同步失效。
2. 上传对账不能只凭同名对象存在就判成功；可能是旧对象。应核对可证明的内容，或者保持 UNKNOWN/ACTION_REQUIRED。
3. 桶/对象控制器的共享执行锁与未结算任务门禁要能阻断预先准备的并发写，而不只是做一次列表检查。
4. 由暂存副本提供可信单次 source，绑定 content fingerprint；当前浏览器只保留暂存元信息与清理回调，尚不暴露上传流。
5. Task 7 十端点最终覆盖门禁/运行时支持矩阵；不能把本轮只读挂载登记为全部十项 UI 完成。

## 测试与实际结果

命令（最终源码）：

```sh
./gradlew :shared-core:jvmTest :shared-ui:jvmTest \
  :android-platform:testDebugUnitTest :app:assembleDebug \
  :app:lintDebug :android-platform:lintDebug :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2ManagementUiTest,com.flarego.DnsFileIntegrationTest,com.flarego.ProductLoadingTest'
SCHEMA_FILE="$PWD/.superpowers/cloudflare/openapi.json" \
  python3 -m unittest discover -s tools/cloudflare_audit/tests -v
python3 -m unittest discover -s tools/ci/tests -v
```

| 层次 | 实际结果 | 边界 |
| --- | --- | --- |
| core 全套 JVM | 770，0 failure/error/skip | 相对 769 增加下载状态负例 |
| shared-ui JVM | 7，0 failure/error/skip | 新浏览器行为测试，不是设备测试替代 |
| platform 全套单测 | 95，0 failure/error/skip | 相对 92 增加暂存三项 |
| R2 原生界面/VM | 4/4 | 真实 VM、数据库、Keystore、Compose；synthetic provider |
| SAF 文件集成 | 17/17 | 含 5 MiB R2 导出/暂存与 DNS 默认 4 MiB 回归；真实系统 DocumentsProvider/授权 |
| 原产品加载回归 | 12/12 | 其他产品入口及任务恢复回归 |
| 设备定向集合总计 | 33/33 | 单轮通过，未运行全部项目设备测试 |
| Debug APK/测试 APK | 通过 | 未重建签名 Release、未测试覆盖升级 |
| app lint | 0 error，10 warning | 依赖版本提示与既有 MainActivity UseKtx |
| platform lint | 0 error，8 warning | 依赖版本提示与既有存储 UseKtx |
| Python audit/CI | 349/349、10/10 | 固定 schema，无真实云凭据 |

原始结果位于 Gradle 标准 `build/test-results`、`app/build/outputs/androidTest-results/connected/debug` 与 lint XML；本轮命令日志保存于 `/tmp/flarego-r2-final-gradle.log`、`/tmp/flarego-r2-audit.log`、`/tmp/flarego-r2-ci.log`（临时日志，不作为永久归档保证）。

### RED/GREEN 与失败历史

- 首次编译 R2BrowserTest 与 R2UploadStagingTest 因新 API 未定义失败，之后实现；没有移动实现文件来补造历史 TDD。
- `editingUploadKeyDuringAReadDoesNotStrandTheLoadingState` 首次行为失败：草稿与列表共用 generation 导致有效读结果被丢弃、loading 永远为 true。分离 draft epoch/read epoch 后通过。
- `acceptedEmptyOrPartialDownloadsCannotClaimACompleteObject` 首次行为失败：客户端接受任意 2xx。完整 GET 仅允许 200，202/204/206 拒绝发布，随后通过。
- 第一轮 R2 原生 4 项有 1 失败：输入后 IME 改变视口，确认按钮未滚动到可点击位置。确认输入状态正确后对按钮 `performScrollTo/assertIsDisplayed/assertIsEnabled`，再跑 4/4 及最终集合 33/33；未改断言为放宽产品行为。

### 环境

原有 `dreamtools-performance` AVD 在本机因 config.ini 不完整、architecture=arm 而启动失败；没有修改该 AVD 或 dreamtools 项目。使用已安装 API 36/google_apis/x86_64，在 `/tmp/flarego-r2-avds` 创建独立测试 AVD `flarego-r2-integration`，不创建 Workspace/worktree，不安装新 SDK。测试结束只停止本轮启动的模拟器。

### 未执行与限制

- 没有真实 Cloudflare HTTP 请求、写入、付费资格、S3 凭据签发或扣费。
- R2 MainActivity 的完整“选择真实系统文档→返回→上传/下载”往返尚未单独 E2E 验证；本轮证明 VM 的请求/取消/过期拒绝，以及真实 SAF 底层事务，两者不能冒充完整端到端。
- 演示连接未实现 R2 深度管理适配器，明确展示不支持；不把演示目录视为生产云结果。
- 保留既有特殊 key 的共享路径校验限制；本轮未放宽主机/路径校验。
- 本轮只做主控自查，无新增独立子 Agent 审查；不声明全分支独立验收通过。
