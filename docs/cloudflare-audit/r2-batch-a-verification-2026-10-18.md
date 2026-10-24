# R2 桶编辑与十操作覆盖：本地阶段证据

> 本文件为PUT/PATCH及覆盖门禁的历史阶段，所列“仍待”是该时点状态；之后新增有界回收/可信拒绝，并修复D5 bulk写后读与Gradle输入跟踪。当前R2-A本地出口通过，见[最终核对](r2-a-local-exit-verification-2026-10-18.md)，不扩张为真实云／付费／发布资格。

## 范围与结论

基线 `3c7284b`，工作分支 `feature/cloudflare-p3-r2`，本轮改动未提交。前轮该基线已获授权推送，Android run `39104310394` 与 audit run `39104310396` 成功；该授权不延伸到本轮。未合并 main、未创建 Tag/Release、未真实云写入或验证付费资格。

本轮补齐 PUT 建桶、PATCH 新上传默认存储类型的产品入口，并实现 Task7 的本地技术映射。**本轮有限范围已由独立功能验收通过，原会话核对证据后确认；不代表整个 R2-A 已完成。** UNKNOWN／发送前拒绝任务的安全人工结算、prepared 状态有界回收、全分支最终审查仍待完成，之后才进入 B→C→D。临时凭据仍待单独批准。

## 实现与安全边界

- POST 保持默认。PUT 明示“按名称创建”，清除／忽略 Location Hint，以路径名称、空请求体、`cf-r2-storage-class` 发送，不伪装 JSON POST。
- PATCH 冻结可见桶名及原类型，仅允许 Standard／InfrequentAccess。UI 和最终权威 GET 同时拒绝未知／缺失原值；加载／刷新时禁用编辑或提交。预览显示原值→目标、旧对象不迁移。
- UI 仅发送领域 intent，仍走同一 runtime、Controller、授权 Session；账户写证明、最终快照／凭据／路由检查、显式确认、同桶锁、未知结果不重放均保留。
- 排查发现旧 Completed 回执会直接成为 SUCCEEDED；底层“controller re-reads”注释原先未由正常提交路径落实。现于同一 coordinator-owned 桶锁内，对 Completed 增加只读验证：删除须读到缺席，创建／PATCH 须读到目标及显式请求的类型；未指定类型的创建不臆测供应商默认值。读失败、未知／缺失／不同类型或桶仍存在均为 UNKNOWN，不变成可安全重试的失败。Accepted／Unknown 不假装完成，不重发；保留 provider request ID。
- raw client 的 delete provider receipt 仍只是回执，真实成功结算由 Controller 的后置验证决定。

## RED／GREEN 与不夸大补证

| 层 | 实际证据 |
| --- | --- |
| Controller 编辑规则 | 新4项真实断言 RED→GREEN；当时 core811 |
| Browser 编辑意图 | 先有缺 API 的编译失败；补齐接口后另观察5项行为 RED→GREEN；UI18。编译错误不充作行为 RED |
| UI 作者首轮 | round1 3失败（缺 tags）；round2 1失败（小屏目标未滚入视口）；补 `performScrollTo()` 后 round3 3通过。保留所有轮次，不说无失败 |
| UI 作者返修 | followup-round1 5项中2行为失败；原类型未知／null及刷新禁用修复后 followup-round2 5/5。PUT 标签更正；配置 high、平台 effective off，如实记录，不自行切换模型 |
| VM 补证 | 最初6新增与原6、纯UI3，15/15直接通过，非新增行为 RED。后补未知原值和回执／读状态矛盾，VM共14项；纯UI共5项 |
| Coverage | 新4项因必需 matrix 缺失而真实断言失败，补矩阵／台账后通过；RED stdout 留存，未声称留有该轮 XML |
| 后置条件 | 新8项中7项真实行为失败（33项共7失败），1项 Accepted／Unknown 直接通过补证。修复后原删桶测试仍期待“桶存在却成功”失败；更正为 UNKNOWN。UI两个旧夹具未为新建桶提供正确 GET 导致失败；修正夹具已创建资源的读状态，未削弱成功断言。最终全套通过 |
| raw 删除 | 新增1项直接通过：拥有目标、空体、单次 DELETE、provider receipt；非新功能 RED |

日志 `/tmp/flarego-r2-bucket-editor-{core, browser}-*.log`（具体旧名见进度表），覆盖 `/tmp/flarego-r2-batch-a-coverage-{red,green}.log`，后置条件 `/tmp/flarego-r2-bucket-postcondition-{red,first-green,suite,green}.log`。UI 作者原始记录 `/tmp/flarego-r2-bucket-ui-evidence/`。后置条件 RED XML `/tmp/flarego-r2-bucket-editor-evidence/postcondition-red/`。

## Task7 机器边界

[`r2-batch-a-runtime-support-2026-10-18.json`](r2-batch-a-runtime-support-2026-10-18.json) 按实际执行日18命名，修正计划示例17。51候选精确分为10 typed handler、40 NOT_STARTED、1 DEFERRED_PENDING_APPROVAL；包括 Slurper，不以 `r2-*` 前缀遗漏候选。

`R2CoverageTest` 对所有10项（含 PUT）检查精确方法／路径、账户 scope、读写／风险、重试、二进制下载／slash-tail 标记、已编译 handler 和具体测试方法，并用新 fixture 实际调用映射的 common 行为测试。反例拒绝缺行、无 handler/test、非 A 状态膨胀、临时凭据偷跑、遗漏或额外注册。固定 schema SHA256 完整校验，坏 `SCHEMA_FILE` 不跳过；Android CI 已有校验下载及环境接线，无须新网络通道。

历史 P1／P2／auxiliary 69 保留，当前精确注册79（42 READ／37 WRITE）。review.json 只新增51个 per-operation runtime 观察和1个总指针；移除这52项后与 HEAD 版本结构完全相同，原 source_support／approval／implemented 历史没有被重标。矩阵不进入运行授权或发送路径。所有真实云／付费／发布资格仍 false。

## 最终开发者执行

```sh
./gradlew :shared-core:jvmTest :shared-ui:jvmTest :android-platform:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:assembleDebug :app:lintDebug :android-platform:lintDebug \
  :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2BucketEditorUiTest,com.flarego.R2ManagementUiTest,com.flarego.R2DocumentPickerIntegrationTest,com.flarego.R2AuthorityOwnerIntegrationTest,com.flarego.ManagementPlatformOwnerIntegrationTest,com.flarego.DnsFileIntegrationTest,com.flarego.ProductLoadingTest,com.flarego.DnsDocumentPickerIntegrationTest,com.flarego.ExtendedManagementAppIntegrationTest#documentCancellationBetweenPresentationDisposeAndRebindIsNotLost'
SCHEMA_FILE="$PWD/.superpowers/cloudflare/openapi.json" python3 -m unittest discover -s tools/cloudflare_audit/tests -v
python3 -m unittest discover -s tools/ci/tests -v
```

- core **824**／UI **18**／平台 **97**：0失败／错误／跳过。
- 设备最终单轮 **78/78**：R2真实VM14、纯UI5、真实picker8、R2凭据owner1、Management owner15、DNS文件17、产品加载12、DNS picker5、DNS取消重绑1。API36／320×640／dpi160／font1，仅自有 `flarego-r2-integration`、`emulator-5554`。不是全分支所有原生方法全量验收。
- 前一轮77/77及截图改善3/3独立留存，未与最终78相加计算唯一覆盖。前轮历史84方法分轮记录不被此78覆盖或重标。
- Debug／lint 成功：app0错误10 warnings、platform0错误8 warnings；既存 Kotlin／Gradle 警告未被抹除。
- Python audit **349**／CI契约 **10** 通过。此次没有 push，不能引用前轮 Actions 当作本轮远端构建结果。
- 最终日志 `/tmp/flarego-r2-bucket-editor-postcondition-final.log`、XML `/tmp/flarego-r2-bucket-editor-evidence/postcondition-final/`；前77轮 `final/`、截图3轮 `visual/`。独立端另保留实际命令结果，不以本表代验收。

## 有限视觉观察

主控实际查看以下五张最终 synthetic 截图：PUT 模式／提示／名称与选项，PUT 完整确认预览，PATCH 编辑选项，PATCH 完整确认预览，FutureArchive 桶只读入口。均为真实 VM 驱动的 Compose fixture；非真实云数据、非完整 MainActivity 系统窗验收。

- 确认点可同屏读到冻结目标、类型／前后差异、旧对象不迁移、无 CAS 风险及确认按钮；小屏文字正常换行。
- 模式图键盘已收起，可见 PUT 选中与无位置／不覆盖说明；未把被键盘覆盖的首图当最终证据。
- 编辑图因滚动仅覆盖原类型与选项／按钮，目标在单独完整确认图核对，不能声称编辑器全部信息同屏。
- 未知类型保留 FutureArchive 文本、只读按钮灰显，进入桶与删除仍为各自独立操作。
- 仅这一尺寸／字体／主题的有限帧；不包括 font2、深色、横屏、API26–28或所有系统提供者。fixture 状态栏图标样式不作为生产 MainActivity 系统栏验收。

五图已存于 [`evidence/r2-bucket-editor-2026-10-18/`](evidence/r2-bucket-editor-2026-10-18/)，独立端也实际逐张查看，仅这一有限范围通过，不由自动化绿色推导视觉通过。

## 独立验收与原会话交付核对

[独立报告及原会话附记](r2-bucket-editor-independent-review-2026-10-18.md)：实际 `--rerun` core824/UI18/platform97全过；实际设备VM14+纯UI5共19/19全过；编译产物反射计数79/42READ/37WRITE；真实检查5图。独立未再跑其余59设备项，仅核对主控最终78轮XML构成，不冒称独立全量78。

主控核对实际diff、独立stdout、XML与截图；通过范围仅本轮UI/VM/后置验证/本地覆盖门禁，剩余安全结算/有界回收及全分支审查仍未完成。独立端JVM仅即时留stdout，XML由主控在结束后从未再跑Gradle的当前build目录补存（38/2/7个suite，824/18/97）；原生19的XML/stdout已即时留存。旧UI round2仅stdout，无该轮XML；原失败及RED证据没有被后续绿色覆盖。

未提交、未推送、未远端构建本轮改动。使用后的自有AVD由主控确认归属后关闭，不处理其他设备或归档Workspace/Tab。
