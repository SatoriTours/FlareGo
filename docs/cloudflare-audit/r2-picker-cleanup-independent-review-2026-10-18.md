> 原协调会话归档的有界复验报告：只采纳顺序修复对应范围通过。主控不把文中“grants恢复即说明abort已返回”的补充推断当作根因证据，因为provider也可能自行撤权；没有运行证据证明旧失败必为真实删除失败。一般provider/文件系统失败仍可能残留空目标，不能宣称已彻底消除。

# Task6b.5 清理屏障最小修复 — 复验与根因勘误报告

- 验收端：独立功能验收（同一会话续任，未派生）。实际执行模型：provider=pi（通道），model=opencode-go/glm-5.3-flash，thinking=high——依据本进程环境变量 `PI_PROVIDER/PI_MODEL/PI_REASONING_LEVEL` 与 Paseo agent 记录 `~/.paseo/agents/home-jimxl-projects-FlareGo/b3c879b6-a59f-44a8-b723-455b57f2aebe.json`（runtimeInfo 同值），非自我身份猜测；权重本体不可自证。
- 仓库：/home/jimxl/projects/FlareGo，branch=feature/cloudflare-p3-r2，HEAD=50681de；只读源码/测试/文档，未改仓库（旧报告 /tmp/flarego-r2-picker-independent-review.md 原样保留，其 §3 失败记录不覆盖）；未触碰真实云/付费/凭据/设备设置；未启停设备。
- 修复范围核实：`git status` 中 R2DocumentBridge.kt 为 untracked 增量；本轮修复窗口（主控 cleanup-barrier 轮之后）仅该文件变更（`find … -newermt '13:52'` 除 bridge 外为空；测试文件 mtime 13:24、DnsDocumentFiles.kt 09:18 均早于修复）。修复内容与主控描述一致：`succeeded` 记录（R2DocumentBridge.kt:53/80），NonCancellable finally 中先 IO 执行 `runCatching { tx?.abort() }` + grant 释放（:86-92），之后才回到 Main 调用 `documentFinished(request.id, succeeded)`（:94）；UI 不再把失败通知当作早于清理的完成屏障。stage/权限策略/删除所有权/文件事务实现未改。

## 1. 实际执行的有界复验（每轮立即留存）

设备核实（只查询）：emulator-5554 device；AVD flarego-r2-integration；font_scale=1.0。未启动/停止、未改设置。

| # | 完整实际命令（cwd=FlareGo） | 结果 | 留存 |
| --- | --- | --- | --- |
| 0（主控，复核） | 14 项三类选择器轮 | BUILD SUCCESSFUL；14/14，0 failed（XML 实读核实：R2 picker 8 + DNS picker 5 + extended 1） | /tmp/flarego-r2-picker-evidence/cleanup-barrier/、/tmp/flarego-r2-picker-cleanup-barrier.log |
| 1 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2DocumentPickerIntegrationTest,com.flarego.DnsDocumentPickerIntegrationTest,com.flarego.ExtendedManagementAppIntegrationTest#documentCancellationBetweenPresentationDisposeAndRebindIsNotLost'` | BUILD SUCCESSFUL in 46s；**14/14，0 failed/0 error/0 skip**（XML 实读：R2 8 + DNS 5 + extended 1） | /tmp/flarego-r2-picker-independent-evidence/cleanup-barrier-full/（cleanup-barrier-full-stdout.log + TEST-…xml，mtime 14:01） |
| 2 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2DocumentPickerIntegrationTest#nativeDownloadIoFailureDoesNotPublishOrKeepTheOwnedPartialDocument'` | BUILD SUCCESSFUL in 9s；**1/1 通过** | /tmp/flarego-r2-picker-independent-evidence/cleanup-barrier-single/（同上两类文件，mtime 14:02） |

任何失败即停的停止条件未触发；未出现失败转绿式重跑。

## 2. 根因置信度勘误（替代上一轮报告中"deleteDocument 返回 false 为根因"的表述）

上一轮报告把根因写成近乎确定的 `DocumentsContract.deleteDocument` 返回 false——**该表述证据不足，予以降级**。目前只有静态推断，未见 provider 返回值/abort 异常的运行证据（abort 失败被 `runCatching` 吞掉，无日志）。现清楚区分两类根因：

1. **时序竞态（代码可直接证明，确凿）**：旧版 bridge 在 catch 中调用 `browser.documentFinished(request.id, false)`，先于 finally 的 abort/grant 清理；测试在看到"未完成"通知后即开始断言清理产物，通知不能充当清理屏障。这是确定存在的设计缺陷，本轮最小修复已消除（documentFinished 移至 NonCancellable 清理之后、回到 Main 执行）。
2. **删除失败假设（仍可能，未实测到 provider 级证据）**：`abortNow → deleteNew → deleteDocument` 返回 false，或 `stillEmpty` 抛 fileIo/读到 0（n!=-1 且非 EOF），或 `stillEmpty` 返回 false（目标非空 → deleteNew 静默返回不删除）——均未被运行证据捕获。

**对旧失败轮次的补充推断（诚实地标注为推断，非证明）**：上一轮 round1 失败时，测试顺序为 notice("未完成") → downloadCalls → waitFor(grants==initial) → 文件 absent 检查。旧代码中 grant 释放在同一 IO 块内、且必然在 `tx.abort()` 返回之后执行；因此 grants 恢复到 initial 这一屏障意味着 abort 已返回，而文件仍 present——这指向 abort 内部清理**确实至少发生过一次真实失败**（上述 2 中某条路径），而非仅靠通知提前就能解释。据此：**旧失败的最可能解释是"真实清理失败 + 通知过早"共同作用**；但该推断仍依赖对调用顺序的静态分析，无 provider 返回值佐证，也未做旧/新代码基线对照，故不断言旧代码"非回归"或失败"必为 deleteDocument false"。

**修复的实际效力边界（重要）**：顺序修复保证"通知不再早于清理"，使未来的真实删除失败能被测试**确定性、可归因地暴露**（清理先返回，文件检查必然在 deleteDocument 尝试之后）；但它**不保证删除必成**——若 provider 再次返回 false/撤权，文件仍会残留且测试仍会失败。主控亦不应把该修复解读为已解决所有 provider/文件系统故障。

## 3. 复验结果与残余风险

- **复验结果：通过（限本轮顺序修复对应范围）**。修复窗口内仅 R2DocumentBridge.kt 变更；14 项全轮 + 该用例单轮全过；主控同选择器轮 14/14 的 XML/日志留存经实读核实。
- **残余风险（如实列出）**：
  1. **单次/有界成功不能排除所有间歇性失败**：round1 的失败本质（若为 2 中真实清理失败）在修复后仍可能复现——届时测试会失败，属正确的暴露而非回归；累计观测：同一签名失败 2 次（主控 six 轮 + 我 round1）、其余多轮通过，无频率稳定数据。
  2. **runCatching 静默吞掉 abort/grant 失败**（R2DocumentBridge.kt:89-92）：真实清理失败无任何日志/计数证据，只能靠测试暴露；一般系统级删除失败仍是潜在风险，建议后续为清理失败增加可观测记录（不属本轮范围）。
  3. `stillEmpty` 非空即静默跳过删除的路径（DnsDocumentFiles.kt:189）无 provider 证据核实。
  4. 根因推断基于静态调用顺序分析 + round1 屏障序列观察；未捕获 provider 返回值，未做新旧基线对照实验。

## 4. 视觉

沿用上一轮已实际读取的 4 张截图（/tmp/flarego-r2-picker-evidence/screenshots-final-green/）；UI 未变，无需重复；其余视觉未覆盖项不变（仅 4 帧、320x640/font1；其他分辨率/深色模式/长 key 换行未视觉检查）。

## 5. 未验证项与边界声明

- 未验证：真实云；R2-A 全完成/覆盖矩阵/全分支 review/安全结清/缓存清理仍待做（主控本次仅在顺序修复对应范围收尾）；API26-28/进程杀恢复；provider 级根因证据。
- 边界：全程只读仓库，仅写 /tmp 报告与 build 测试产物；未修改实现；未派生；未改模型/认证/权限；未发起真实 Cloudflare 请求；设备共 2 轮（1 全轮 + 1 单测），无失败、无覆盖式重跑。
