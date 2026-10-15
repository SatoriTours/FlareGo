# P1 Task 6：账号发现与 Token 验证

核对日期：2026-10-13。基线 `98cc0c9`，执行 workspace `wks_e60bb589a4c1ce32`，分支 `feature/cloudflare-foundation-completion`。实现与验证已完成，代码尚未提交或合并。原会话继续负责协调，文档同步主目录；以下新增能力属于隔离分支。

## 实现内容

- `IdentityPort`、`CloudflareIdentityClient`：显式发现账号，单页加载与继续分页，严格解码账号身份；指定账号验证必须返回匹配的账号 ID。
- 用户 Token 只调用 `/user/tokens/verify`；账号 Token 只调用 `/accounts/{account_id}/tokens/verify`。类型 UNKNOWN 不猜测验证接口；未知状态保持未知。
- 原生连接弹窗增加 Token 类型选择、可选状态验证、账号发现与分页选择。发现 403 后仍可手填账号；保存前必须单独验证目标账号，Token active 不作为账号授权证明。
- `ConnectionDraft` 只保存在 ViewModel 内存中，保留 Activity 重建规则；取消或连接成功后清空。Token 改动清除旧账号列表和状态；草稿版本检查阻止 Token A→B→A 后旧结果重新出现。
- `CapabilityPort`、`CloudflareCapabilityClient`：按 scope／action 缓存显式只读探测结果，独立 60 秒过期；新凭据使用新 client。403 只映射权限不足，401 映射凭据问题，404 保持未知，不猜测套餐或写权限。未知操作和写操作不探测。该端口的产品页装配仍属于 Task 9。
- ViewModel 增加传输注入边界用于真实连接逻辑的隔离测试；默认 Android 构造器与安全 Cloudflare transport 保留。

本轮新增实际调用入口为账号发现及两类 Token 验证，共 3 个端点。主线台账仍为 11 个 implemented，分支技术调用范围为 14 个；不修改真实资格为已通过，不宣称覆盖全部产品能力。

## 测试证据

环境沿用项目原版本、JDK 17、离线依赖缓存与本任务临时 API 36 x86_64 模拟器。使用合成响应与合成凭据，没有请求真实 Cloudflare，也没有发布。

| 验证 | 结果 |
| --- | --- |
| 新 `CloudflareIdentityTest` | RED 19 项失败于未实现方法；实现后 19 项通过 |
| 全部 core Gradle 测试 | 221 项通过，0 失败／错误／跳过 |
| Android 平台 JVM 测试 | 82 项通过，0 失败／错误／跳过 |
| 新 `AccountDiscoveryTest` | RED 7 项失败于缺少控件；最终 7 项通过 |
| 全套 Android 设备测试 | 30 项通过，0 失败／错误／跳过，含连接帮助、实际 Activity 重建、数据库迁移和更新回归 |
| Debug 应用和测试 APK | 构建通过 |
| app／platform lint | 0 error，分别 3／4 项 warning |
| 覆盖审计／发布契约 | 333／10 项通过，无跳过；审计有既有 ResourceWarning |

首轮原生实现验证为 5 通过／2 失败：实际 UI 已显示正确的权限错误，但 Compose `assertTextContains` 默认按整段匹配；修正为 substring 匹配后全套通过。本地复核又加强了拒绝连接场景，直接查询真实 SQLite，证明数据库中没有留下连接；加强后的全套 30 项再次通过。

```sh
./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug :android-platform:lintDebug :app:connectedDebugAndroidTest \
  --offline --no-daemon -Pandroid.injected.device.serial=emulator-5582

# 加强持久化断言后的最终全套设备验证
./gradlew :app:connectedDebugAndroidTest --offline --no-daemon \
  -Pandroid.injected.device.serial=emulator-5582

python3 -m unittest discover -s tools/cloudflare_audit/tests -q
python3 -m unittest discover -s tools/ci/tests -v
```

所有设备命令同时设置 `ANDROID_SERIAL=emulator-5582`。日志暂存 `/tmp/flarego-task6-*.log`；长期结果以本文为准。

## 本地复核与实施取舍

按用户选择，由当前会话实施并完成本地自审，没有派发外部 provider。已检查严格账号匹配、错误脱敏、手动连接回退、凭据草稿版本、缓存作用域、只读探测边界及实际持久化拒绝；有限范围内无待修复的 Critical／Important 问题。

1. 固定 Schema 的账号分页参数为 5–50；超出范围及 cursor 请求在本地拒绝。能力探测使用小型手写只读允许列表。若后续接口契约变化，须调整端点校验与探测列表，不自动扩大凭据范围。
2. 本轮保留未提交改动，计划的提交步骤仍未勾选。后续提交、合并和推送按实际授权执行；执行空间继续保留。

真实云账号、其他 Android 版本、签名 Release 与升级安装未验证。Task 7 新 Workers 目录、Task 8 账号账单、Task 9 按产品加载和生产任务系统装配仍待开发；P1 整体未验收。
