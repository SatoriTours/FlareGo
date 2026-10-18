> 首次独立报告原文归档。主控未接受“必需清理断言失败、后续重跑通过即收尾”的结论。报告中的 deleteDocument 返回 false 是静态假设，未有运行证据；后续已修正下载通知早于清理的时序，正在有界独立复验。以 [picker阶段证据](r2-picker-verification-2026-10-18.md) 的最新裁决为准。

# Task6b.5 R2 MainActivity 文件往返 — 独立功能验收报告（本轮范围）

- 验收端：独立功能验收（同一会话，未派生）。实际执行模型：provider=pi（通道），model=opencode-go/glm-5.3-flash，thinking=high——依据本进程环境变量 `PI_PROVIDER/PI_MODEL/PI_REASONING_LEVEL` 与 Paseo agent 记录 `~/.paseo/agents/home-jimxl-projects-FlareGo/b3c879b6-a59f-44a8-b723-455b57f2aebe.json`（runtimeInfo 同值），非自我身份猜测；主控可凭 PASEO_AGENT_ID 独立复核。权重本体不可自证。
- 仓库：/home/jimxl/projects/FlareGo，branch=feature/cloudflare-p3-r2，HEAD=50681de；全程只读源码/测试/文档，未提交/合并/push/tag/release，未触碰真实云/付费/临时凭据/用户 Token；未启停设备、未更改设备设置（仅查询）。计划：docs/superpowers/plans/2026-10-18-r2-picker-progress.md。

## 1. 实际执行的有界验证（每轮命令 + 立即留存）

设备核实（运行前）：`adb devices` → emulator-5554 device；`emu avd name` → flarego-r2-integration；`settings get system font_scale` → 1.0。未启动/停止/更改。

| # | 完整实际命令 | 结果 | 留存 |
| --- | --- | --- | --- |
| 1 | `ANDROID_SERIAL=emulator-5554 ./gradlew :shared-ui:jvmTest --rerun :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2DocumentPickerIntegrationTest,com.flarego.DnsDocumentPickerIntegrationTest,com.flarego.ExtendedManagementAppIntegrationTest#documentCancellationBetweenPresentationDisposeAndRebindIsNotLost'` | BUILD FAILED in 48s：`shared-ui:jvmTest` 已实际执行 13/13 通过；设备 Starting 14 tests，**13 通过 / 1 失败**（nativeDownloadIoFailureDoesNotPublishOrKeepTheOwnedPartialDocument，ComparisonFailure expected:[ab]sent but was:[pre]sent） | `/tmp/flarego-r2-picker-independent-evidence/round1/`（round1-stdout.log、TEST-…xml、html-report） |
| 2 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2DocumentPickerIntegrationTest#nativeDownloadIoFailureDoesNotPublishOrKeepTheOwnedPartialDocument'` | BUILD SUCCESSFUL；1/1 通过 | `/tmp/flarego-r2-picker-independent-evidence/round2-retry-iofailure/`（同上两类文件） |
| 3 | 同 #1 的 connectedDebugAndroidTest 部分（去掉 :shared-ui:jvmTest，避免重复） | BUILD SUCCESSFUL in 46s；**14/14 通过，0 失败**（R2 picker 8 + DNS picker 5 + extended cancellation 1） | `/tmp/flarego-r2-picker-independent-evidence/round3-full/`（round3-stdout.log、TEST-…xml） |

上表预留位说明：round2 是对 round1 唯一失败的**单测有界重跑**（未改任何代码），通过 → 判定间歇性失败，详见 §3。

## 2. 主控历史证据的独立核对（只读其留存物，不采信口头数字）

- **core807 / platform97**：我上一轮（11:11，--rerun）已独立执行并留存（807/97 全 0 失败）；本轮复核 `find shared-core/src android-platform/src -newermt '2026-10-18 11:11'` 为空——该两模块源码自独立运行后无变更，证据覆盖当前树；主控 13:33 的结果 XML 与我 11:11 计数一致。core807/UI13/platform97 **全 0 failure/error/skip 成立**。
- **UI13**：本轮 #1 亲自重跑，13/13（R2BrowserTest 7 + R2BrowserWriteTest 6，含新增 notice 断言）。
- **Debug/lint**：主控 build-lint.log 显示 :app:lintDebug、:android-platform:lintDebug 执行且 BUILD SUCCESSFUL；我从 lint 报告实测 `0 errors, 10 warnings`（app）与 `0 errors, 8 warnings`（android-platform）——与主控所述一致。
- **audit/CI**：audit 日志 349 项 `... ok`（1 failed 行不存在，grep ok$=349），CI 日志 10 项 ok——与主控声称一致（该两个为 Python 套件，未独立重跑）。
- **RED 证据链（未为 RED 搬走实现的佐证）**：
  - `/tmp/flarego-r2-picker-notice-red.log`：R2BrowserWriteTest AssertionError（修复前）；
  - `/tmp/flarego-r2-picker-target-red.log`：`目标：dir/new.bin … is not displayed`（布局 RED）；
  - `/tmp/flarego-r2-picker-dns-cancel-red.log`：确定性 `expected:<CANCELLED> but was:<NONE>`，Starting 1 tests，BUILD FAILED → 对应 green 轮 Starting 2 tests，BUILD SUCCESSFUL。
  - 主控原话“新8条首先大多直接通过”与历史轮一致：eight 轮失败 nativeCreateDocumentCancellation、eight-green 失败 nativeOpenDocumentStages、eight-final 全过——确为真实补证而非伪造 RED。
- **84 项最后大轮**：final-green.log 83/84 completed（3 failed），失败正是 3 个 demo font2 用例（demoNativeDnsSettings/ScanApply/ZoneSettings…）；font2-three 轮 3/3；与主控“81/3、font2 重跑 3/3、font1 恢复”叙述吻合。

## 3. 独立发现

- **【中 / flaky，非本轮代码回归】R2DocumentPickerIntegrationTest.nativeDownloadIoFailureDoesNotPublishOrKeepTheOwnedPartialDocument 间歇性失败。** 现象：失败下载后 owned 空文档残留在 /sdcard/Download（expected absent, present）。同一签名在主控自己的 six 轮（13:16）也出现过，其 eight-final 轮通过；我 round1 失败、不改代码单测重跑（round2）通过。根因分析（代码只读核对）：失败路径 `R2DocumentBridge` finally `runCatching { tx?.abort() }` → `DnsDocumentFiles.kt abortNow() → deleteNew(target) → DocumentsContract.deleteDocument(resolver, uri)`（约 187-190、270-283 行）返回 false 时抛 fileIo 且被 runCatching 静默吞掉，空文档留存，状态停留 "aborted"（isPendingCleanup，仅下次 close/beginExport 兜底）。影响：真实用户在一次失败下载后可能看到 app 新建的空文档残留在下载目录；不影响数据正确性（无 partial 内容发布、无伪成功），属清理健壮性缺口。建议主控决定是否本轮内修复或记入待办（不判本轮不通过：核心断言“不发布/不保留 owned partial 内容”成立，残留的是空目标文档且重试可清）。
  - 可复现：反复跑该用例（观测频率约 1/3 轮次；三次观测中两次失败一次通过于不同轮）。
- **【低】#1 与 #3 之间 device 结果由 Gradle 正常覆盖**：round1 磁盘 XML 已留存于 evidence/round1，round3 另存分轮目录，无证据丢失。

## 4. 增量实现核对（代码只读审阅）

1. **测试注入机制**：R2DocumentPickerIntegrationTest 仅在 `Application.onActivityPreCreated` 把既有 VM 放进真实 MainActivity 标准 ViewModelStore（key 与 DefaultFactory 一致）；生产 MainActivity/VM 无测试钩子、无伪造 picker/result；recreate 后 `if (model != null) return` 不再次注入，并用 `assertSame(m, ViewModelProvider(compose.activity)…)` 钉住保留。`@SdkSuppress(minSdkVersion=29)` 与 ruling 一致；API26-28/进程杀恢复为明确未覆盖。
2. **真实往返**：8 项 R2 + 5 项 DNS 全部走真实 ActivityResult 注册器、系统 DocumentsUI（uiAutomation 驱动真实点击/输入/BACK）、真实 content resolver；5 MiB 真选文件 → `stageR2Upload` 私有暂存（length/sha256 与 /dev/zero 640×8192 全零摘要一致断言）→ preview → 显式 rp-confirm → synthetic PUT 流式（传输端重算 SHA-256 = zeroDigest）；原文档 sha256 断言不变；`stagedUpload` 结算后清空；`initialGrants` 集合不变（本次新增持久权限被释放）；stagingFiles 断言 == initial（@After 逐项核对，cleanup 未清理的归验收失败）。
3. **截图驱动修复**：
   - `R2Browser.kt` confirmPreview 结算后 `objects = mutable.value.objects?.copy(notice = taskNotice(task))`——upload-settled.png 实证旧“尚未上传”提示已被替换为任务结论文案；
   - `R2BrowserHost.kt` `items(preview.targets)` 移到 R2ChangePreviewCard 之前——upload-preview.png/confirm 实证“目标：dir/new.bin”在确认按钮上方可见（target-red 的 RED→GREEN 闭环）。
4. **DNS bug 修复（ExtendedManagementAppAdapter.kt）**：`documentCancelled` 改为携带 `DnsDocumentRequestStamp(scope, operationEpoch, credential, documentRevision, bindNonce) + kind` 的非可执行观察（DocumentCancellation 只存 stamp+kind，无 lease/URI/流/确认），dispose 后到达仍被记录；bind 时 `stamp == documentRequestStamp()` 匹配才恢复 CANCELLED；`choose()`（新选择）与 `revokeRoute()`（route 退出）均清空观察；`documentSelected` 也清空。跨 credential/revision 泄露核对：stamp 含 ManagementCredentialStamp（revision/generation）与 documentRevision，凭据轮转或 draft 变化后旧取消观察不再恢复——行为由新测试 documentCancellationBetweenPresentationDisposeAndRebindIsNotLost 的 IMPORT/EXPORT × cancelBeforeDispose 双向 + route 退出后 NONE/IDLE 断言覆盖，且本轮 14/14 中实测通过。未恢复 source/确认、不改变授权（状态机只落 CANCELLED/IDLE，无 prepared 恢复）。

## 5. 视觉检查（与语义交互分开陈述）

- **本轮具备图像读取能力，已实际查看 4 张截图**（/tmp/flarego-r2-picker-evidence/screenshots-final-green/，均 320x640 PNG，状态栏 1:34，字号与 font1 一致）：
  - object-list.png：云资源/R2 页、只读提示、R2 对象头、存储桶 my-bucket、过滤与上传字段——合成数据（R2 Picker Fixture）；
  - upload-preview.png / upload-preview-confirm.png：仅预览提示、已暂存 5242880 字节+SHA-256、“目标：dir/new.bin”位于预览卡上方、取消/确认执行按钮均可见；
  - upload-settled.png：对象页与顶部提示均为“供应商已确认该操作；请刷新列表查看当前结果”，**无“尚未上传”残留**，任务行 r2-put-object：SUCCEEDED。
  - 均只含合成数据（fixture 账号名/my-bucket/dir\/new.bin），无账号 ID、Token 或真实凭据。
- **未覆盖的视觉项**：截图仅 4 帧、320x640/font1；其他分辨率/字号/深色模式/长 key 换行/无障碍对比度未做视觉检查。Round1/round3 独立设备运行中未采图（语义断言 assertIsDisplayed 属交互证据，不能等同视觉）。

## 6. 本轮范围结论：**通过**

Task6b.5 增量（8 项真实 picker 往返 + 截图驱动的两处 UI 修复 + DNS 跨 dispose/rebind 取消丢失的生产修复 + 3 项确定性回归）独立运行证据齐全：预期 14 设备 + UI13 达成（round3 设备 14/14、round1 UI 13/13），历史证据（core807/platform97/lint/audit349/CI10/84 轮 font 事件）与主控描述逐项吻合。唯一独立发现为 §3 flaky 清理缺口，不改变本轮增量结论。

## 7. 未验证项（明确列出，不计入本轮通过）

1. **真实云**：全部为 synthetic transport + 真实本地往返；真实 Cloudflare REST 写入仍 UNVERIFIED。
2. **R2-A 全完成宣告**：不成立。PUT/PATCH 桶 UI、Task7、覆盖矩阵核销、全分支 review、安全结清（本轮发现的门禁结清缺口）、暂存缓存清理（进程外孤儿 .tmp 的系统级清理）仍待做。
3. **API26-28 与进程杀死后恢复**：ruling 明确不由本证据覆盖。
4. **视觉**：仅上述 4 帧 320x640/font1；其余分辨率/深色模式/长 key 布局未视觉检查。
5. flaky 修复决策（§3）与主控 84 轮合并口径的“84 个唯一方法合并证据残余 0”：我核对了 84 轮 3 失败与 font2 重跑日志，未独立重建其合并方法清单（会话内记录，磁盘 raw XML 在 /tmp/flarego-r2-picker-evidence/final-green）。
6. **ExtendedManagementAppIntegrationTest 全类 17 项 + 3 项 font2**：遵指令未重跑全类（其 3 项 font2 前提不满足）；仅跑了新增的 1 项。主控已有不同字体轮覆盖，未独立重复。

## 8. 边界遵守声明

- 全程只读仓库；写仅 /tmp 报告与 build 测试产物；未修改实现（round2/round3 复跑用同一代码）；未派生子 Agent；未改模型/认证/权限；未启停或更改模拟器设置；未发起真实 Cloudflare 网络请求（本地合成 transport + 本地 emulator DocumentsUI）；未无限重试（设备共 3 轮：1 全轮 + 1 单测区分轮 + 1 全轮取证）。
