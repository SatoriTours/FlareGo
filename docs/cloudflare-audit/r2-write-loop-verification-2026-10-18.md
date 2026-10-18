# R2 确认写入与只读恢复：阶段验证（2026-10-18）

## 范围

沿用 `feature/cloudflare-p3-r2`，HEAD 仍为 `50681de`，本轮和此前应用/权限接线均保留为未提交改动。没有合并、push、Tag、Release、真实云写入、付费请求或临时 S3 凭据。

**生产 UI 不再统一只读。** 已挂接的创建桶 POST、删除桶、上传、单删及批量删除，先生成预览，再显式确认，通过 `R2WriteRuntime → ChangePlans / OperationCoordinator → R2Controller → AuthorizedR2Session` 发送。没有 runtime 的独立展示组件仍保持只读；读取目录不代表写授权。

这不是 R2-A 全量完成：桶 PUT 创建/PATCH 设置暂仅有领域客户端/controller，UI 尚无对应编辑入口；完整 MainActivity + 系统 OpenDocument/CreateDocument 往返、Task7 十操作覆盖矩阵、全分支独立 review、真实云验收仍未完成。

## 实际实现

- 新增 `R2WriteRuntime`，实际上下文来自当前账号、凭据 revision、浏览器确认 epoch 和实际编辑命令，不由 plan 或持久化 task 回填。
- 修改输入、目标、选择、页面或凭据，旧预览不得继续发送。拒绝确认也恢复非 busy 状态，避免按钮永久阻塞。
- 各发送仍使用 Application 凭据 owner 的 `AuthorizedR2Session`，每次独立 Token 自检/账号级权限审查。模拟设备测试证明：允许时一次 DELETE；缺权时零变更请求。
- `OperationCoordinator.executeGrouped` 是内部受信任产品入口：锁用同桶 `r2_bucket`，但持久化 locator 保留原对象地址。默认 DNS/Zone 调用不改变。锁由 coordinator owner job 持有，取消 UI waiter 不提前释放。
- `R2UploadContent` 由 Android 私有暂存对象提供，路径不暴露。长度/SHA-256 来自原始文档的一次有界缓冲复制；上传打开的是该副本，不再打开原文档。仅展示 `R2StagedUploadInfo` 不能获得流。
- 副本最多打开一次；已释放或已打开后不能重用。运输层持有打开的 FD；释放 UI 副本不提前关闭运输层 FD。重新选择文件先释放旧暂存，不能在新 picker 等待时沿用旧文件。
- 确认结果显示真实 task state。`RUNNING / UNKNOWN / PARTIAL / ACTION_REQUIRED` 只提供核对，不提供写入重试。
- 恢复：同名对象存在不证明未知上传的内容已更新；单删的缺失候选 hash 包含 key；创建桶对账保留指定 storage class，但不把 locationHint 当数据中心保证。
- 批量列表在 prepare 时冻结；完整成功要回显精确、无重复的请求 key 集合，不再仅比较数量。部分/未知/需人工处理继续锁住同桶后续写入。

## RED / GREEN 证据

没有隐藏或搬走实现来制造历史 RED。

- `R2ControllerTest` 新增的旧 key 上传、单删 hash、指定 storage class 创建对账和 ACTION_REQUIRED 门禁：实际 4 个失败 → 修正后通过。日志 `/tmp/flarego-r2-loop-controller-{red,green}.log`。
- 外部可变批量列表、相同数量但错误/重复 key 回显：实际 2 个失败 → 通过。日志 `/tmp/flarego-r2-frozen-batch-{red,green}.log`。
- 浏览器两阶段提交、编辑撤销、仅文件元数据不能上传：实际 3 个失败 → 通过。日志 `/tmp/flarego-r2-browser-write-{red,green}.log`。
- 缺权确认遗留 busy：实际失败 → 修正通过，`/tmp/flarego-r2-browser-denied-red.log`。
- 替换文件仍保留旧暂存：实际失败 → 修正通过，`/tmp/flarego-r2-replacement-red.log` 和最终 Gradle 记录。
- 真实 VM + Compose 在未接线时等不到 preview，实际设备失败；接通 runtime/session/host 后，允许/拒绝两条路径通过。日志 `/tmp/flarego-r2-vm-loop-{red,green}.log`。
- 新 API 的 grouped/runtime/source 首轮 RED 是缺符号的编译失败，不是行为断言失败。暂存新增测试曾因最后一条 `assertFailsWith` 推断成非 Unit 而出现 JUnit 初始化错误；修正测试返回类型后真实执行通过。以上不混作既有行为回归 RED。

## 最新开发者自测

设备只使用自有 `flarego-r2-integration`（API36，`emulator-5554`），开始前通过 `adb ... emu avd name` 核实。没有操作其他项目模拟器。

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :shared-core:jvmTest :shared-ui:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :android-platform:lintDebug \
  :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2AuthorityOwnerIntegrationTest,com.flarego.ManagementPlatformOwnerIntegrationTest,com.flarego.R2ManagementUiTest,com.flarego.DnsFileIntegrationTest,com.flarego.ProductLoadingTest'
SCHEMA_FILE="$PWD/.superpowers/cloudflare/openapi.json" python3 -m unittest discover -s tools/cloudflare_audit/tests -v
python3 -m unittest discover -s tools/ci/tests -v
```

| 检查 | 实际结果 |
|---|---|
| core JVM 全套 | 807；0 failure/error/skip |
| shared-ui JVM 全套 | 13；0 failure/error/skip |
| Android platform 单测全套 | 97；0 failure/error/skip |
| 设备指定 5 类 | 51；0 failure/error/skip（不是完整项目设备套件） |
| 设备分解 | R2 owner1 + 原 owner15 + R2 UI6 + SAF17 + loading12 |
| Debug APK / test APK | 构建通过 |
| App / platform lint | 0 errors，10 / 8 warnings；并非无 warning |
| pinned-schema Python 审计 | 349 通过 |
| CI 契约 Python | 10 通过 |
| whitespace | `git diff --check` 通过 |

日志 `/tmp/flarego-r2-loop-final-gradle.log`、`/tmp/flarego-r2-loop-audit.log`、`/tmp/flarego-r2-loop-ci.log`。数量从真实 JUnit XML 汇总。

设备交互验证了真实 VM/vault/database/Compose、预览确认、synthetic 账号权限与恢复；SAF 测试验证真实 provider 的 5 MiB 暂存与导出及打开 FD 的释放语义。但这些**不能替代 MainActivity 完整文件选择往返，也不代表真实云或视觉验收通过**。

## 独立验收

当前 Workspace 内独立验收 Agent `b3c879b6-a59f-44a8-b723-455b57f2aebe` 回报“通过（限本轮增量）”，勘误后报告已归档为 [独立验收记录](r2-write-loop-independent-review-2026-10-18.md)。主控复核实际 diff、当前 core807/UI13/platform97 的 0 failure/error/skip XML，并逐类核对其中14类 DNS/Zone/协调器回归合计447项通过；未把本轮通过扩为 R2-A 完成。

验收端独立重跑 core807/UI13/platform97，并报告分轮设备 R2 UI6、SAF17、owner1 通过。分轮设备 XML 被后轮覆盖，当前只保留最后 owner1；前两轮结果仅由验收会话当时读取 XML 的运行记录支持，没有独立保存的 XML/日志，不以当前 XML 充作24项总证据。主控此前统一51项的设备运行日志仍在 `/tmp/flarego-r2-loop-final-gradle.log`，但这是开发者自测，不冒充验收端独立留存证据。

首版错误自述模型为 Claude，并引用不存在的 R2CoverageTest，均已由原验收端更正；没有重跑测试、改变代码或更换模型。实际元数据为 provider=pi、model=opencode-go/glm-5.3-flash、thinking=high，主控已用 Paseo 元数据核对；权重本体不能由 Agent 自述证明。范围内功能/交互自动化验收通过，保留上述证据复核性限制；视觉仍未验证。自有模拟器已由原协调会话核实名称后停止，未操作其他设备。

独立发现经主控核对后列为后续工作：
- 保守的未知/预发送权限拒绝任务没有应用内安全结清路径；这是已记录的门禁后果，不以“断开连接清除记录”充当证明操作已完成的恢复方案。后续应设计可信未发送证据或明确人工结清流程。
- runtime 的 prepared map 依赖当前 ViewModel 主线程访问；目前生产调用符合该边界，但通用类的调度约束应明确，不能仅给 map 加锁便声称整套上下文线程安全。
- 过期/取消的 prepared 表未清理，批量 key payload 会被继续持有；需增加有界清理验证，不能因常规操作量小忽略大批量预览的内存成本。
- 视觉验收未执行；没有截图/图像理解证据。

## 剩余限制

- MainActivity 系统 picker 的完整选取/保存/重建往返仍缺 R2 专属证据；当前不是全量 E2E。
- 桶 PUT 创建、PATCH 默认存储等级的 UI 入口，以及 Task7 十操作 handler/test/runtime 支持矩阵未完成。
- 未知上传没有足够持久化内容证据可自动判成功；批量人工处理不能自动解锁。当前是保守门禁，而不是已完成的人工确认清障 UX。
- 缺权限在最后 guard 内被阻止时，沿用共享 coordinator 的 RUNNING 记录语义，不能伪报“确定失败、可重试”。
- 路径门禁仍保守拒绝部分 `%` 接十六进制、`#` key；未绕过门禁。
- 无真实凭据、付费资格、真实云写入、完整设备套件及全分支最终独立 review 证据。
