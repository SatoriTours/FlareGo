# P1 Task 8：账号账单与明确兼容范围

核对日期：2026-10-15。主线基线 `98cc0c9`，执行 workspace `wks_e60bb589a4c1ce32`，分支 `feature/cloudflare-foundation-completion`。账单客户端、类型模型和界面组件已实现并完成本地验证；本批次代码尚未提交或合并，原会话继续负责协调。

## 实现与接入边界

- 新增 `ScopedBillingPort`、`CloudflareBillingClient`，默认按 `AccountScope` 读取账号账单历史，每次返回所请求的一页。
- `BillingEntry` 保留完整 scope、金额原文、可空时间／币种／说明／状态，以及账号接口的 invoiceId／receiptId。来源由 scope 决定，不使用供应商的自由文本 source 字段推断归属。
- 用户级旧接口须在客户端构造时显式绑定 `UserScope`，请求范围必须精确匹配；账号接口 403 不触发用户接口回退。用户身份不得从 Account ID 或 Token ID 制造，局部绑定不等于真实云用户身份验收。
- 金额按 JSON number 的原始文本保存，不经过 Double；精度、尾随零、负数、科学计数法和真实零值保留。缺失／null 金额保持未知，不以 amount_to_pay 或默认 0 补齐；缺失币种也不默认 USD。
- 新增 `ScopedBillingScreen`：分别显示“当前账号账单历史”和“用户级账单历史 · 旧接口”，展示前检查连接和所有记录的 scope。范围错误时隐藏记录，缺失金额显示“未知”，没有费用预估。
- 现有用户级 `BillingScreen` 保留，并明确标注旧接口；缺失金额也改为“未知”。原有 demo 标签与示例预估保持原路径。
- hosted_invoice_url 不保存到该模型，也不请求、不生成携带 Token 的发票链接；界面只保留调用方提供的官方控制台动作。PDF、订阅、金额合计属于 P4。

账号级账单客户端及新组件的生产装配、按产品加载和范围选择属于 Task 9。当前生产 `CloudflareSession.billing()` 仍走用户级旧流程；本轮不能宣称 App 已启用账号账单，或具备真实费用全覆盖。真实云账号未验证。

## 固定 Schema 与实施取舍

固定 Schema 的 `bill-subs-api_account_billing_history` 含状态、发票、收据等字段，与用户级旧 DTO 分开解析。

| 范围 | 分页 | 未提供的数据 |
| --- | --- | --- |
| 账号历史 | number；per_page 最小 1，未声明最大值 | 可选元数据保留 null；无法确认记录 ID 时明确报错，不制造身份 |
| 用户级旧历史 | number；per_page 5–50，须显式绑定 UserScope | 不把账号接口的状态／发票／收据字段套入旧 DTO |

cursor 和不支持的 scope 在发送前拒绝。若后续固定 Schema 改变，调整相应解析和分页规则；不自动扩大凭据范围或制造金额。Task 9 需要向界面传入明确的账单 scope，空历史也不能靠第一条记录猜来源。

## 实际验证

JDK 17、项目原有版本、离线依赖缓存；本任务临时 API 36 Google APIs x86_64 模拟器，使用合成响应和合成凭据。

| 检查 | 结果 |
| --- | --- |
| `ScopedBillingTest` | 编译成功后 RED 17 项失败；实现后 17 项通过 |
| `ScopedBillingScreenTest` | 设备就绪后 RED 7 项失败；实现后 7 项通过 |
| 全部 core Gradle 测试 | 260 项通过，0 失败／错误／跳过，含旧 `billingHasNoInventedEstimate` |
| Android 平台 JVM 测试 | 82 项通过，0 失败／错误／跳过 |
| 全套 Android 设备测试 | 37 项通过，0 失败／错误／跳过，含原有连接、重建、迁移、持久化及更新回归 |
| Debug 应用／测试 APK | 构建通过 |
| app／platform lint | 无 error，分别 3／4 项既有 warning |
| 覆盖审计／发布契约 | 333／10 项通过，无跳过；审计有既有 ResourceWarning |

原生首跑时模拟器仍在启动，设备 OFFLINE，未执行任何测试，只记为环境 SETUP。确认 `sys.boot_completed=1` 后重新运行，才取得真实的 7 项 RED；没有把环境失败算作行为证明。后续全套 GREEN 无失败。

金额测试实际覆盖 `20.9900`、`9007199254740993.01`、`-20.99`、`0`、`1.2300e-7` 的原文保留，缺失／null、字符串金额、非数值、错误格式等负例。其他测试覆盖单页请求、账号与用户隔离、403 不回退、发票 URL 不跟随、错误脱敏和取消。原生测试验证两种标题、未知与零值、混合／外部 scope 隐藏、旧界面标签及官方控制台动作。

```sh
./gradlew :shared-core:jvmTest --tests 'com.flarego.core.ScopedBillingTest' \
  --offline --no-daemon

./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug :android-platform:lintDebug :app:connectedDebugAndroidTest \
  --offline --no-daemon -Pandroid.injected.device.serial=emulator-5582

python3 -m unittest discover -s tools/cloudflare_audit/tests -q
python3 -m unittest discover -s tools/ci/tests -v
```

设备命令同时设置 `ANDROID_SERIAL=emulator-5582`。原始日志暂存 `/tmp/flarego-task8-*.log`；长期结果以本文为准。

按用户选择，由当前会话完成本地自审；有限范围内未发现待修复的 Critical／Important 问题，没有派发外部 provider。Task 7 源码哈希比对确认，其记录中的源码仅 Models.kt 按计划追加了账单模型，其他此前成果保留。

Task 9 生产集成及 P2 尚未实施，P1 整体未验收。其他 Android 版本、真实云账号、最新签名 Release 和 GitHub Actions 未验证。本轮未提交、合并、推送、创建 Tag 或发布 Release。
