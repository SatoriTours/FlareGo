# P1 Task 7：产品目录与 Workers 新模型

核对日期：2026-10-14。主线基线 `98cc0c9`，执行 workspace `wks_e60bb589a4c1ce32`，分支 `feature/cloudflare-foundation-completion`。Task 7 已完成目录端口实现和本地验证；本批次代码尚未提交或合并，原会话继续负责协调。

## 实现内容与接入边界

- 新增 `ProductDirectoryPort`、`CloudflareDirectoryClient`，每次只请求所选产品的一页，不自动遍历全部产品或全部页面。
- Workers 默认调用 `/workers/workers`：分别保存不可变 ID 和显示名称，保留可空部署时间，未提供用量或时间时保持未知。
- `WorkersDirectoryMode.LEGACY` 只能显式选择，调用未分页的 `/workers/scripts`，资源标注“旧接口”。403 不自动回退；旧 script 与新 Worker 使用不同资源引用 kind。
- R2 解析嵌套 buckets 和 cursor；D1 读取 uuid，KV 读取 title，Zone 请求带账号过滤并验证返回账号。所有请求先检查本地连接与账号 scope，资源保留完整 `ResourceRef`。
- `CloudResource` 追加有默认值的 source、legacy、resourceRef、deployedOn 字段，已有构造调用保持兼容。
- `DemoDirectory` 只接受显式 demo 连接，资源标注示例，使用内存分页；真实请求失败不会回填演示数据。
- 把 Task 6 的既有只读请求／错误脱敏边界移至公共 protocol helper，供 identity、capability 和 directory 复用，行为保持不变。

新端口的生产装配、按产品加载和兼容模式界面选择属于 Task 9。生产旧 `CloudflareSession.inventory()` 仍保留原流程；本轮不宣称 App 已切换到新 Workers 目录或具备完整 Workers 管理能力。当前只有隔离协议测试证据，按 L1 范围登记；真实云资格仍为 0。

## 固定 Schema 下的实施取舍

| 产品 | 分页与校验 |
| --- | --- |
| Zone | number；每页 5–50；带 account.id 过滤，并核对行内账号 |
| 新 Workers | number；每页 1–100，默认 50 |
| 旧 Workers scripts | 不加分页参数，拒绝 continuation token；未知总量保持未知 |
| R2 | cursor；每页 1–1000；拒绝 number token 和当前 cursor 回响 |
| D1 | number；每页 10–10000 |
| KV | number；每页 1–1000 |

超出端点边界的输入在发送前拒绝，不静默改变接口。资源引用区分 `worker`／`worker_script`，并绑定完整账号 scope；显示名称不承担身份。Demo 的 number 分页属于独立内存适配器，不作为真实 R2 cursor。

这些取舍若与将来的新固定 Schema 冲突，应调整端点校验或产品 DTO；不能猜测身份、权限或自动扩大凭据范围。公共只读 helper 的提取由原 221 项核心测试和新增目录测试共同验证。

## 实际验证

环境沿用 JDK 17、项目原有依赖及离线缓存，没有使用真实 Cloudflare 凭据。

| 检查 | 结果 |
| --- | --- |
| `CloudflareDirectoryTest` | 编译成功后 RED 22 项失败；实现后 22 项通过 |
| 全部 core Gradle 测试 | 243 项通过，0 失败／错误／跳过，包含原 Task 6 的 19 项 |
| Android 平台 JVM 测试 | 82 项通过，0 失败／错误／跳过 |
| Debug 构建 | 通过，新增模型与端口的 Android／Compose 使用方编译成功 |
| app／platform lint | 无 error，分别 3／4 项既有 warning |
| 覆盖审计／发布契约 | 333／10 项通过，无跳过；审计有既有 ResourceWarning |
| 设备原生测试 | 本轮未重跑；最近一次为 Task 6 的 30 项通过，不计为 Task 7 新设备证据 |

```sh
./gradlew :shared-core:jvmTest --tests 'com.flarego.core.CloudflareDirectoryTest' \
  --offline --no-daemon

./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest \
  :app:assembleDebug :app:lintDebug :android-platform:lintDebug \
  --offline --no-daemon

python3 -m unittest discover -s tools/cloudflare_audit/tests -q
python3 -m unittest discover -s tools/ci/tests -v
```

测试覆盖独立 Worker ID／名称、空部署时间、单页请求、403 不回退、显式 legacy、R2 cursor 编码和回响、D1／KV 映射、Zone 账号证明、所有产品的越界 scope 和分页拒绝、跨账号同名资源、格式错误、明确失败、取消、错误脱敏及 demo 隔离。

当前会话完成本地自审，有限范围内未发现待修复的 Critical／Important 问题；没有派发外部 provider。Task 6 源码哈希比对确认，除两个调用公共 helper 的客户端外，其余此前代码未改。原始日志暂存 `/tmp/flarego-task7-*.log`，长期结果以本文为准。

Task 8 账号账单、Task 9 生产集成及 P2 尚未实施；真实云账号、最新签名 Release 和 GitHub Actions 未验证。本轮未提交、合并、推送、创建 Tag 或发布 Release。
