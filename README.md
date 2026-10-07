# FlareGo

[下载正式版](https://github.com/SatoriTours/FlareGo/releases/latest) · [最新提交版](https://github.com/SatoriTours/FlareGo/releases/tag/latest) · [Android 构建](https://github.com/SatoriTours/FlareGo/actions/workflows/android.yml)

管理多云资源的原生 App，Cloudflare 首期。参考 dreamtools 的语言与框架，在本项目独立实现；没有修改 dreamtools。

## 原生 Android

使用 Kotlin 2.3.20、Kotlin Multiplatform、Compose Multiplatform 1.10.1、SQLDelight 2.3.2；HTTP 使用 OkHttp 4.12.0，明确关闭重试并使用一次性写请求体。Android 8.0 起，JDK 17 / Android SDK 36 / Gradle 9.1.0。

用 Android Studio 打开本目录，配置 Android SDK 后运行 `app`；或：

```sh
./gradlew :app:assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

默认进入演示账号。Logo 打开可左滑关闭的云账号抽屉；标题显示当前页面/资源名；搜索按需展开；底部五个功能入口。域名 / DNS / Workers / R2 / D1 / KV / 账单 / 账号 / 操作记录均有原生页面。

真实账号在「账号 → +」连接：填写 Account ID 和 API Token。需要 Account Read、Zone Read；DNS 变更需 DNS Write；Workers Scripts / Workers R2 Storage / D1 / Workers KV Storage 的 Read 权限按需提供。每项服务无权限时单独提示，不影响其他资源。详情参见 [Cloudflare 权限文档](https://developers.cloudflare.com/fundamentals/api/reference/permissions/)。

| 能力 | 当前实现 |
| --- | --- |
| 账号 | 验证、连接、切换、断开；Token 通过 Android Keystore 加密保存 |
| 域名 | 当前账号 Zone 列表与分页查询 |
| DNS | 查询、新增、编辑、删除；预览变更后确认提交；超时提示待核对 |
| 云资源 | Workers、R2、D1、KV 的真实目录与详情元数据 |
| 账单 | 用户级历史账单；需 Billing Read；无本月预估则显示未知 |
| 域名购买 | 演示搜索和购买确认；真实查询/购买跳转官方控制台 |
| 创建 / 部署 / R2 对象操作 | 官方控制台入口；App 内暂未接通这些写操作 |
| 其他厂商 | 规划状态，后续通过适配器扩展 |

演示域名、费用、请求趋势和购买均为示例，不连接真实云服务。真实连接不会用示例补齐缺失数据。用户级账单可能包含其他账号，界面不会将它汇总为当前账号费用。尚未提供真实账号凭据进行端到端 API 验证。

凭据不进入 SQL 或日志；账号和本机操作记录保存在 SQLDelight。Token 输入页禁止系统截图；备份及设备迁移禁用。请求固定使用 Cloudflare HTTPS API，不跟随重定向，不自动重试写操作。操作记录界面查询最近 200 条；关闭或卸载应用不会更改云资源。

## 签名构建与应用更新

每次提交通过 GitHub Actions 构建签名 APK，main 发布到 `latest` 测试通道；`vMAJOR.MINOR.PATCH` Tag 发布正式版。两个通道共用独立发布证书，私钥只保存在 GitHub Actions Secrets。账号页支持更新通道切换、自动/手动检查、下载校验与系统安装确认。详见 [发布与更新说明](docs/releases.md) 与 [实际验证记录](docs/release-verification.md)。

## 工程结构

```text
app/                 Android 入口、ViewModel、依赖组装
shared-ui/           Compose 主题、导航、页面和表单
shared-core/
  model/             领域模型
  ports/             DNS / 资源目录 / 账单 / 本地存储端口
  application/       用例、账号隔离、操作状态、DNS 校验
  cloudflare/        Cloudflare API 适配器
  demo/              独立演示适配器
android-platform/    HTTPS、Keystore、SQLDelight 平台实现
```

UI 不依赖 Cloudflare DTO；业务层不依赖 Android。新增云厂商实现相关小端口并在入口注册；不用把不同产品的写操作塞进万能资源接口。[原生规格](docs/native-spec.md) / [整体架构](docs/architecture.md) / [UI 设计](docs/ui-design.md)。

## 检查

```sh
./gradlew :shared-core:jvmTest :android-platform:testDebugUnitTest :app:assembleDebug :app:lintDebug :android-platform:lintDebug
# 启动 Android 模拟器后
./gradlew :app:connectedDebugAndroidTest
```

测试包含核心业务、更新协议与持久节流、HTTP 写请求边界、可信更新下载与损坏文件拒绝，以及 Android 原生交互、存储和安装前校验。JVM 测试涵盖账号切换、旧请求、写入不确定结果、分页、服务权限隔离、JSON 转义和 DNS 校验；HTTP 测试使用本地服务器验证断连与 503 时写请求不重放。原生交互测试涵盖资源搜索、账号抽屉与 DNS 修改确认。测试不需要云 Token，也不执行真实云写操作。

## 设计原型

原 HTML 可点击原型保留作为设计参考：

```sh
npm run dev
```

打开 http://localhost:4173 。`design-board.html` 是界面总览，`architecture.html` 是架构图；`design/mobile-*.png` 为设计稿，`design/native-*.png` 为原生模拟器截图。原型与原生应用分别运行，不共享状态。

## 许可证

[Apache License 2.0](LICENSE)。FlareGo 是独立项目，与 Cloudflare 或其他云厂商没有官方关联。
