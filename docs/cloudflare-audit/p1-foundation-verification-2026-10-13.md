# P1 基础层：工程验证与有限复核

核对日期：2026-10-13。生产源码基线 `98cc0c9059dc3c2a55a4a3cbf2473cd29c0ec9ad`。
执行空间 `wks_e60bb589a4c1ce32`，分支 `feature/cloudflare-foundation-completion`，名称 `【自动】Cloudflare基础完善`。本轮只修正设备测试、补齐验证和整理文档，没有增加产品功能，也没有提交、合并或推送。

## 环境与实际结果

JDK 17，项目原有 Gradle 9.1.0、Kotlin 2.3.20、Android SDK 36，依赖离线缓存。使用新建的临时 API 36 Google APIs x86_64 模拟器 `emulator-5582`，没有使用用户已有 AVD 或真实云凭据。

```sh
./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug :android-platform:lintDebug --offline --no-daemon

ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest \
  --offline --no-daemon -Pandroid.injected.device.serial=emulator-5582

python3 -m unittest discover -s tools/cloudflare_audit/tests -q
python3 -m unittest discover -s tools/ci/tests -v
```

| 检查 | 实际结果 |
| --- | --- |
| 正式 Gradle core 测试 | 202 项，0 失败／错误／跳过 |
| Android 平台 JVM 测试 | 82 项，0 失败／错误／跳过 |
| Debug 应用与 instrumentation APK | 构建成功；SQLDelight 生成、Android 与 Compose 编译成功 |
| app／platform lint | 0 error，分别 3／4 warning（GradleDependency／UseKtx） |
| 全套 Android 设备测试 | 修正后 23 项，0 失败／错误／跳过 |
| 覆盖审计 | 333 项通过，无跳过；有现存 ResourceWarning |
| 发布契约 | 10 项通过；未触发发布 |

设备测试包含 `OperationMigrationTest` 9 项、`AppSmokeTest` 5 项、`ConnectionHelpTest` 5 项、`LocalPersistenceTest` 2 项、`UpdateIntegrationTest` 2 项。迁移涵盖精确旧 v1 数据库升级、全新安装、未知状态读取、共享 owner、summary 错配拒绝、真实 SQLite trigger 故障回滚、安全文案、跨连接删除与重开。

审计在新 worktree 初次运行时有 7 项因缺少忽略的公开来源缓存而跳过；从主目录复制已有固定 OpenAPI 和 S3 兼容表后，全套 333 项实际执行通过。未下载新来源或变更固定 pin。

## 设备测试失败与修正

初次全套设备执行为 20 通过、3 失败，不能视为全绿：

1. `legacyV1DatabaseUpgradesWithoutLosingAccountsOrOperations`：旧断言期待原始 `safeDetail` 往返，但 `c98bc39` 明确要求按任务状态归一为安全文案。
2. `bothStoresShareOneOwnerAndOneTransaction`：同一过时预期；SUCCEEDED 应读回“已确认完成”。
3. `removingAConnectionClearsOnlyItsOwnTasks`：第二账号的任务夹具仍使用第一个账号的 `pollPath`，构造时被所有权校验拒绝，未运行到删除断言。

修正仅涉及 [OperationMigrationTest.kt](../../app/src/androidTest/kotlin/com/flarego/OperationMigrationTest.kt)：使用独立字面值期待安全文案、让查询路径使用夹具账号参数、保留完整行比较并加强另一账号行完整保留断言。没有放宽生产校验，没有修改 schema 或迁移实现。修正后全套 23 项通过。

该测试文件 SHA-256：`8abde7ae47cd7292398264577e137029b606972f208a6762ad3a56a9c7f5acc8`。测试修正仍在隔离分支，主目录源码未同步此改动。

## Task 5：旧证明缺口的当前会话复核

复核范围为旧质量记录的 `tracked-contract-tests-proof-gap`，检查 `277e50c..4542b55` 后续测试以及当前依赖的状态机与控制器源码；不启动新一轮无边界测试矩阵。当前会话只读复核结论：后续提交已提供下列受版本管理的证明，未发现该有限范围内需要继续修复的问题。

| 原证明要求 | 当前对应证据 |
| --- | --- |
| 应用执行 job 与 UI caller 分离并受清理管理 | [控制器测试资源管理](../../shared-core/src/commonTest/kotlin/com/flarego/core/AppControllerTest.kt#L1390)；独立 app／caller job、gate、scheduler drain 统一登记 |
| 任一阶段失败仍释放资源，清理错误不能覆盖原异常 | [factory 阶段故障测试](../../shared-core/src/commonTest/kotlin/com/flarego/core/AppControllerTest.kt#L1293)覆盖 9 个阶段；[coordinator 故障测试](../../shared-core/src/commonTest/kotlin/com/flarego/core/OperationCoordinatorTest.kt#L93)覆盖创建至最终断言；检查取消、多个 suppressed exception、真实时间有界 join |
| 发送后归档保留完整原始身份，不污染当前 UI | [发送后测试组](../../shared-core/src/commonTest/kotlin/com/flarego/core/AppControllerTest.kt#L985)在真实 typed-send 内停车；检查完整 task、字面 canonical summary、完整冻结 UI 和 sentinel 旧操作记录 |
| 旧 factory 必须在真实重新获取边界停车 | [ManagedFactory 与契约断言](../../shared-core/src/commonTest/kotlin/com/flarego/core/AppControllerTest.kt#L1514)记录初始与新 session 身份、实际 create 次数及参数；五个 cell 覆盖不变、revision、fingerprint、撤销和仅 UI epoch 变化 |
| 不能只做自比较或删除旧保护测试 | 独立检查 scope、action、resource ref、hash、revision、时间与 provider/poll 字段；coordinator 原 51 项保留并增至 54，controller 原 32 项保留并增至 41 |

正式 Gradle 中 coordinator 54 项和 controller 41 项均通过。`LegacyDnsReceiptSource` 的 Unit 接口仍只能报告 UNKNOWN；这不构成明确完成证明，测试也保持 UNKNOWN。

独立子 Agent 复审**未执行**：自动审批拒绝向 Paseo 的 `pi/openai-codex/gpt-6.1-sol` provider 发送源码与复审上下文，理由是缺少对该目的地的明确授权。没有改用其他 provider 绕过。用户随后明确选择“由当前会话完成本地复核”，因此本轮采用当前会话的有限复核结论，外部独立复审不再列为本轮待办。本记录不声称独立审查通过；旧 NOT APPROVED 保留为历史结论，后续证明缺口已有本轮本地复核结果。

## 验收边界与后续事项

- Task 4 的本轮 Debug／API 36 迁移验证已补齐；没有证明所有 Android 版本或签名升级路径。
- Task 5 的有限证明缺口已有本地核对结果，用户已选择当前会话本地复核作为本轮方式，不再等待外部复审。生产 `FlareGoViewModel` 没有组装 coordinator／`dnsOperations`，应用任务恢复、核对、断开协调仍属 Task 9。
- Task 6–9 及 P2 产品功能未实现；本轮不计入新增 Cloudflare API 能力，真实资格验证仍为 0。
- 最新签名 Release、实时 GitHub Actions／远端分支、真实 Cloudflare 账号和其他专用协议环境未验证。

原始日志暂存 `/tmp/flarego-p1-completion-*.log`，首次失败另存 `/tmp/flarego-p1-completion/native-before-fix.log`；以上长期结论不依赖临时文件存在。
