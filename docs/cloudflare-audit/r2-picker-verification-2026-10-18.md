# R2 MainActivity 系统文件往返：阶段证据（2026-10-18）

## 结论边界

`feature/cloudflare-p3-r2`，HEAD `50681de` 未移动；保留全部未提交改动，没有 push、合并、Tag、Release、真实云写入或 S3 凭据签发。

本轮补齐**当前自有 API36 模拟器**上真实 MainActivity、ActivityResult registry、DocumentsUI 与 DocumentsProvider 的 R2 文件往返，并修复两个 R2 界面问题、DNS 生命周期竞态以及下载通知早于清理的时序。最新限定范围独立复验通过（14整轮+1单测，逐轮留存），不能扩大为 R2-A 全完成，也不保证所有provider删除故障都已解决。

## 新增的真实往返验证

`app/src/androidTest/kotlin/com/flarego/R2DocumentPickerIntegrationTest.kt` 共8项：

1. 原生 OpenDocument 取消回到同一 VM，可再次打开；零上传。
2. 原生选取5 MiB文档，私有暂存长度/SHA-256正确；预览前零写入，显式确认才向 synthetic transport 上传一次；对源字节再次检查未改变。
3. 原生 OpenDocument 下真实 MainActivity 重建；Android 保留原 VM 和结果交付，不重新注入 VM，不自动生成确认或上传。
4. 原生 CreateDocument 下真实 MainActivity 重建；下载精确字节且只调用一次。
5. 离开 R2 后再选回旧文档，不把文件暂存或发送到另一页面。
6. 原生 CreateDocument 取消，不发下载、不创建写任务。
7. 写入部分本地临时字节后模拟下载 IO 失败；最终删除本次拥有的新文档，释放新增持久权限，不发布半文件，不回显异常私有标记。
8. 原生 CreateDocument 保存5 MiB流，校验实际文档长度和SHA-256；新增权限释放，临时文件清理。

仅在 test 的 `Application.ActivityLifecycleCallbacks.onActivityPreCreated` 把已有可注入 transport 的 VM 放入真实 Activity 标准 ViewModelStore。生产 MainActivity/VM **没有增加测试 factory 或全局 hook**，没有伪造 ActivityResult、Document URI、授权 flags 或系统 picker。后续重建不再次注入 VM。测试限定 API29+；当前执行设备 AVD=`flarego-r2-integration`，serial=`emulator-5554`，只操作自有设备。

测试夹具最初出现两类等待错误，均只改测试：取消后必须等系统窗口实际返回，不能在清理时重复发 back；下载失败提示先于 NonCancellable IO finally，删除断言应等本次持久权限释放（abort之后），不是立即把提示当清理完成。这些不算生产 RED→GREEN。

## 实际生产缺陷及 RED→GREEN

- **上传成功仍显示“尚未上传”**：对象列表保留旧暂存 notice，和真实 SUCCEEDED 任务矛盾。common `R2BrowserWriteTest` 新断言实际失败，`R2Browser.confirmPreview` 结算后同步更新对象 notice，修复后13项通过。
- **预览首屏看不到对象 key**：目标列表在确认卡片之后，320×640下确认按钮出现时目标仍在下方屏外。真实设备 `目标：dir/new.bin` 的 `assertIsDisplayed` 实际失败；把目标列表移到卡片前。仍可滚动到完整确认按钮，8项真实往返通过。
- **DNS picker 取消结果在 dispose/rebind 间丢失**：原有 `actualMainActivityOpenFlightSurvivesRecreateWithoutRestoringConfirmation` 整组连续两轮等不到 CANCELLED，单独重跑可通过。确定性新增 `documentCancellationBetweenPresentationDisposeAndRebindIsNotLost` 实际失败：CANCELLED预期却为NONE。`ExtendedManagementAppAdapter` 原来仅通知 active presentation，且 bind 重置选择状态；现在以精确 `DnsDocumentRequestStamp` 保存非可执行取消观察，同 route/document revision 重绑时读取，真实 route exit、新选择和有效 selected 清理；不恢复文件权限、负载、计划或确认。新增用例覆盖 IMPORT/EXPORT、取消在 dispose 前后、route exit 不得继承旧取消。修复后该确定性用例和原生重建用例通过，后续大轮 DNS 5项通过。

日志：
- `/tmp/flarego-r2-picker-notice-red.log`
- `/tmp/flarego-r2-picker-target-red.log`
- `/tmp/flarego-r2-picker-ui-green.log`
- `/tmp/flarego-r2-picker-dns-cancel-red.log`、`...-green.log`
- 原64项失败轮：`/tmp/flarego-r2-picker-final-gradle.log`、`...-final-recheck.log`

新8项多数在现有生产实现上直接通过，属于补证，不撤走实现伪造历史失败。

## 最新回归结果（必须按实际轮次解释）

| 项目 | 实际结果 |
|---|---|
| core JVM | 807，0 failure/error/skip，强制重跑 |
| shared-ui JVM | 13，0 failure/error/skip，强制重跑 |
| Android platform unit | 97，0 failure/error/skip，强制重跑 |
| Debug APK/test APK | 构建通过 |
| App/platform lint | 0 errors；10/8 warnings，不称无警告 |
| pinned-schema Python | 349通过 |
| CI Python | 10通过 |
| 原生84项/font1 | **81通过、3失败**；3个旧 `EffectiveFont2` 用例因预期2.0/实际1.0失败 |
| 原失败3项/真实system font2 | 3/3通过；完成后font_scale恢复1.0 |
| 原生唯一方法合并 | 84项都有通过记录，残余0；**不是单轮84项通过，也不是项目全设备套件** |

84项分解：R2 picker8 + R2 owner1 + 原owner15 + R2 UI6 + SAF17 + loading12 + 原DNS picker5 + extended adapter/runtime20。

3项字体条件用例为 `demoNativeScanApplyUserPreviewConfirmUsesActualRuntimeAndNoProduction`、`demoNativeDnsSettingsUserPreviewConfirmUsesActualRuntimeAndNoProduction`、`demoNativeZoneSettingsRiskClickPreviewConfirmUsesActualRuntimeAndNoProduction`；它们明确要求真实系统font2。原失败未删除，没有修改字体断言；仅自有模拟器临时设为2.0执行这3项，finally恢复1.0。

核心命令：

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :shared-core:jvmTest --rerun :shared-ui:jvmTest --rerun \
  :android-platform:testDebugUnitTest --rerun \
  :app:assembleDebug :app:lintDebug :android-platform:lintDebug \
  :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2DocumentPickerIntegrationTest,com.flarego.R2AuthorityOwnerIntegrationTest,com.flarego.ManagementPlatformOwnerIntegrationTest,com.flarego.R2ManagementUiTest,com.flarego.DnsFileIntegrationTest,com.flarego.ProductLoadingTest,com.flarego.DnsDocumentPickerIntegrationTest,com.flarego.ExtendedManagementAppIntegrationTest'
```

该大轮日志 `/tmp/flarego-r2-picker-final-green.log` 的文件名不能代表结果：**实际84项/3失败**。结果原样保存在 `/tmp/flarego-r2-picker-evidence/final-green/`。字体补轮实际日志 `/tmp/flarego-r2-picker-font2.log` 和 XML `.../font2-three/`；按 classname+method 合并84个唯一方法，不能相加为87个独立测试。

最终独立构建/lint命令日志 `/tmp/flarego-r2-picker-build-lint.log`；审计/CI日志 `/tmp/flarego-r2-picker-audit.log`、`...-ci.log`。

## 截图与有限视觉核对

修复后的4张原生320×640、system font1截图归档在 [evidence/r2-picker-2026-10-18](evidence/r2-picker-2026-10-18/)。只显示 synthetic 账号/桶/key/字节元数据，无 Token、真实对象内容或本机认证配置。

原协调会话实际通过图像读取检查了：对象列表、上传预览、滚动后的完整确认按钮、结算结果。目标 key 现在在确认卡片前；滚动后确认/取消按钮完整可见；成功后不再显示旧“尚未上传”提示。页面长内容仍通过滚动呈现，未把单张首屏都可见当所有控件同时可见。

这只是主控的有限视觉检查，**不是独立全视觉验收**；没有宣称R2 font2、横屏、多语言、API26-28视觉/交互通过。

## 独立验收与后续

已复用当前 Workspace 的功能验收 Agent `b3c879b6-a59f-44a8-b723-455b57f2aebe`（最新profile，pi/opencode-go/glm-5.3-flash/high）。首轮UI13通过、设备14项中13通过/1失败：下载IO失败后的空目标仍存在。其后同一项和完整14项重跑通过，逐轮证据在 `/tmp/flarego-r2-picker-independent-evidence/{round1,round2-retry-iofailure,round3-full}/`；[首份报告原文](r2-picker-independent-review-initial-2026-10-18.md)已归档。

**主控裁决：不能以重跑通过替代必需清理断言的修复，本轮暂不据此收尾。** 报告关于 `deleteDocument` 返回false的归因只有静态推断，未实测其返回值或 abort 异常，不能称为已证实根因，也未通过基线对照证明“非本轮回归”。

代码能直接确认的时序问题：`R2DocumentBridge` 原来在 catch/成功分支先 `documentFinished`，然后才执行IO finally里的 abort 和权限释放；provider也可能在删除调用中撤销权限，因此观察“失败提示/权限消失”并不是完整清理屏障。现已最小调整为记录 succeeded、在 NonCancellable finally 中完成 abort/权限释放后才回Main发布下载成功/失败终态。没有更改文件所有权、删除策略、权限策略，也没有自动重放任何云写入。

该修改后开发者14项通过，日志 `/tmp/flarego-r2-picker-cleanup-barrier.log`、XML `/tmp/flarego-r2-picker-evidence/cleanup-barrier/`。原验收端按固定范围复验：14项全轮、原失败项单独一轮均0失败/错误/skip；停止条件未触发，没有失败转绿式追加重跑。主控逐份读取验证这两轮XML和完整stdout日志确实留存于 `/tmp/flarego-r2-picker-independent-evidence/{cleanup-barrier-full,cleanup-barrier-single}/`，[复验报告已归档](r2-picker-cleanup-independent-review-2026-10-18.md)。最后源代码的Debug/lint再次通过，日志 `/tmp/flarego-r2-picker-cleanup-build-lint.log`。

**裁决：只接受当前顺序修复及有界往返范围通过。** 不采信报告中“grants恢复说明abort已返回，故旧失败必有真实删除失败”的推断，因为没有排除provider自行撤权。旧失败的provider级根因仍未证实。有限通过不能证明所有间歇失败都已消失；abort/grant异常被 runCatching 捕获仍是需要明确保留的系统故障与可观测性缺口。不能声称删除必成、底层故障已彻底解决或R2-A可发布。

验收端本轮已实际读取4张synthetic截图，完成同样320×640/font1范围的独立有限视觉检查；其余视觉项仍未验。

仍待：桶 PUT/PATCH 界面、Task7 十操作覆盖矩阵及全分支最终 review、未知任务安全结清、prepared缓存有界清理；进程杀死后的恢复、API26-28、R2独立全视觉与真实云均未在本轮完成。
