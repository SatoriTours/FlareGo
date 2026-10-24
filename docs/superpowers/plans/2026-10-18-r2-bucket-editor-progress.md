# R2 PUT/PATCH 界面补齐记录

承接已批准 R2-A Task6，基线 3c7284b、feature/cloudflare-p3-r2，沿用当前Workspace。本轮只开发/验证，不自动提交、推送、合并、Tag/Release或真实云写入。

既定设计落地：
- POST保留原创建流程；增加显式PUT模式，名称/已审核存储类型，经同一预览确认。PUT不支持位置提示，切换到PUT清除旧hint，不伪装JSON POST。
- 已存在桶单独“修改默认存储类型”：冻结桶名和原值，仅允许Standard/InfrequentAccess；只影响新上传对象，不迁移旧对象。未知/缺失当前类型只读，不能默认为Standard。
- 编辑器仅产出领域事件。实际发送继续R2WriteRuntime/Controller/AuthorizedR2Session，不从列表成功/展示状态授权，不放松确认、快照冲突与未知任务门禁。
- 原值/目标类型必须在预览可见；取消、编辑输入、模式/目标/凭据/页面变化使旧预览失效。

接口（原会话先完成models+browser，UI执行端随后只改BucketList及独立纯UI测试）：
- R2BucketCreateMode.POST/PUT；state.createMode。
- CreateModeChanged(mode)。PUT清除draftLocationHint，POST位置事件仅POST生效。
- RequestStorageClassEdit(name)、EditStorageClassChanged(value)、SubmitStorageClassEdit、CancelStorageClassEdit。
- state.editingBucketName:String?、editingOriginalStorageClass:String?、editingStorageClass:String。
- 模式tags rb-create-mode-post/put；PUT提示rb-create-put-hint，PUT隐藏位置chips。
- 行编辑rb-edit-$name；编辑card/target/original为rb-edit-card/target/original；选项rb-edit-class-Standard/InfrequentAccess；提交/取消rb-edit-submit/cancel。
- 既有rb-create-*、location、storage-class、delete/open tags保持兼容。

步骤：
- [x] 核心：4项实际断言RED→GREEN；未知/缺失原类型、非法目标类型拒绝，PUT/PATCH预览显示变体及前后默认类型。core811全过。日志 /tmp/flarego-r2-bucket-editor-core-{red,green}.log。
- [x] 浏览器：新API初轮缺符号（编译RED），补接口/no-op后5项实际断言RED，再实现handler后UI18全过；PUT/PATCH空体+header、hint隔离、取消/模式撤销、unknown/nonvisible禁入、最终快照变化零写入，沿用RUNNING未知门禁语义。平台97通过。日志 /tmp/flarego-r2-bucket-editor-browser-{api-red,behavior-red,green}.log。
- [x] UI角色按固定接口完成初版：Agent 3decc16e-92e0-41e1-a728-54e3c15c1c50（pi/cliproxy/gemini-3.8-flash-high）。纯UI3项RED→GREEN，XML/stdout留存 /tmp/flarego-r2-bucket-ui-evidence/round1与round3；无独立视觉声明。Paseo配置thinking=high/effective=off，原样保留，不擅改模型设置。
- [x] UI返修：PUT改为“按名称创建”，补原类型未知/null、loading/refreshing时的提交禁用与刷新时禁用入口。原UI作者仅改原两文件；followup-round1五项中2行为RED，round2五项全GREEN，逐轮XML/stdout留存。返修结束前主控未并行写入checkout。旧round2目标不在视口的失败按测试缺scrollTo解释，原失败未抹除。
- [x] 真实VM/Compose首轮：新增6项覆盖PUT/PATCH允许/拒绝、权威原类型变未知拒绝、取消后切POST无写入；连同原6项及纯UI3项，15/15通过。日志 /tmp/flarego-r2-bucket-editor-vm-first.log，XML /tmp/flarego-r2-bucket-editor-evidence/vm-first。视觉尚未执行，后续返修后继续验证。
- [x] Task7本地门禁与矩阵：新4项因缺必需matrix实际断言RED，补齐所有10项（含PUT）真实编译handler/具体测试并实际调用common行为后GREEN；51=10typed+40NOT_STARTED+1DEFERRED_PENDING_APPROVAL，活注册精确79（历史69+审核10），非subset。review仅52个增量观察，移除后与HEAD结构完全一致。实际日18文件替代计划示例17；不虚报完整R2-A完成。
- [x] 后置验证缺口：发现Completed直接SUCCEEDED，新增8项中7行为RED、1直接绿补证。同一coordinator桶锁内增加写后读；删桶须缺席，POST/PUT/PATCH显式class须匹配，失败/缺失/矛盾保持UNKNOWN，不重放。旧删桶“仍存在却成功”预期改UNKNOWN；旧UI fixture补真实新建桶GET状态而不削弱成功断言。保留失败日志和RED XML。
- [x] 最新开发者验证：core824/UI18/platform97全部通过；最终native单轮78/78（含VM14+纯UI5）及Debug/lint通过，app10/platform8既存warnings；Python audit349/CI10通过。五张真实VM synthetic有限截图主控实际逐张查看，不代替真实云或全系统窗/多设备视觉验收。
- [x] 本轮独立功能验收通过：按最新六profile notes复用b3c879b6-a59f-44a8-b723-455b57f2aebe，pi/opencode-go/glm-5.3-flash/high（配置与effective）；实际--rerun824/18/97、设备19/19及5图检查，通过仅本轮范围。主控实读diff/stdout/XML与截图、补存独立JVM当前build XML并标明非当轮即时留存；修正报告负例数量笔误8非7、旧UI round2只有stdout无XML，不抹除失败。实现与Gradle在验收期间未并行修改/执行。

Ruling: 沿用既有规格D4“未知类型只读”，在controller对权威GET原值同样拒绝未知/缺失类型，不能只靠UI禁用。代价是对未证实的新供应商存储类型保守拒绝修改。

证据汇总：[本轮验证](../../cloudflare-audit/r2-batch-a-verification-2026-10-18.md)；机器矩阵 `docs/cloudflare-audit/r2-batch-a-runtime-support-2026-10-18.json`。本轮无commit/push。UNKNOWN/发送前拒绝任务的安全结算、有界prepared回收及完整R2-A最终审查仍待完成；不提前进入B/C/D或临时凭据。

## 继续开发：预览生命周期与拒绝证据（独立待验收）

Ruling: 只对R2私有issuer启用64项/5分钟有界保留。满额拒绝新预览，不驱逐有效或已接纳确认；过期且无运行claim才回收；有争用保守保留。共用ChangePlans默认路径与DNS行为不变。失误代价是过于保守的容量拒绝，不允许通过回收换来重放。

Ruling: 取消只撤销未接纳预览，不是任务结算；真正UNKNOWN/PARTIAL不加勾选即成功/强行解除入口。后续仅以受控发送边界证明的“未分派”证据安全结清，边界以后任何异常仍保守不确定。

- [x] 64容量、过期恢复容量/防ID复用、跨bucket/object/runtime/issuer全表回收：3行为RED→GREEN；日志/tmp/flarego-r2-retention-{red,green}.log及XML /tmp/flarego-r2-retention-evidence/{red,green}/。
- [x] Browser反复取消96次：新增用例在第65个预览触顶行为RED，接入discard后GREEN。日志/tmp/flarego-r2-retention-cancel-{red,green}.log，逐轮XML同名目录。
- [x] 另3项直接通过补证：相同活输入撤销/复用ID不复活、创建后丢失上下文不耗尽、TTL/取消不打断在途且双击单任务单POST。有效重复确认仍返回同一结果。
- [x] R2-only可信边界：2行为RED→GREEN（未分派应FAILED、漏guard不能成功）；边界后同错误码仍RUNNING为直接绿补证；终态拒绝只读核对不回UNKNOWN另见同一方法的第二RED阶段。对象前/后与外国scope补证通过。新NotDispatched内部receipt映射FAILED，默认普通coordinator异常语义不变，DNS Scan仅补sealed类型穷尽分支。
- [x] 坏时钟撤销：新1项行为RED后修复，prune保守保留、不阻止撤销，prepare/confirm仍拒绝负时间。
- [x] 最后开发者unit835/UI19/platform97通过；Debug/lint通过；设备75首轮74PASS/1旧后置UNKNOWN预期FAIL，修正为保持安全FAILED后75PASS。随后负时钟/后缀拼写微调及截图辅助更新，当前快照另跑unit835+native19PASS，不把75冒称最后微调后重跑。audit349/CI10通过。XML/stdout均按阶段保存，compile兼容错误有一轮完整stdout同名覆盖，仅会话摘要，不虚称完整留存。
- [x] 本增量独立通过：旧b3c879b6已closed/archived，未擅自恢复；最新profiles选同功能验收配置pi/opencode-go/glm-5.3-flash/high，在已核实当前Workspace新建54584677-3cf5-4579-bd73-60f03b65f3fb，未新建空间。fresh-context实际重跑835/19/97与最终75设备项，全通过；即时保存三模块47份XML及native XML/stdout，主控实读重算一致。拒绝图双方实际查看，仅本轮有限范围。
- [x] R2-A本地整体出口最终通过：同端整体审查先声称通过，主控核对后挑战D5 bulk正常路径缺写后读，以及Gradle未声明外部证据输入。复核确认两项，原整体通过撤回。保留规格补实现，修复第1轮针对复验54单测/2真VM及正常schema漂移负例/恢复均通过，原会话实读源码、日志/XML后确认；不把此结论扩张为真实云、付费或发布资格。

详见[本增量证据](../../cloudflare-audit/r2-retention-verification-2026-10-18.md)。旧RUNNING/UNKNOWN没有持久化的未分派证明，不追溯按错误码变FAILED；真正未知/部分/异步结果继续阻断，未提供人工强制解除。

当前已改：ChangePlans opt-in保留参数/不可复用序号、R2WriteRuntime原子准备表与discard、R2Controller payload表清理、R2Browser取消/迟到预览丢弃。未改默认通用异常/恢复语义，未新增后台任务或真实云请求。

## R2-A出口复核修复第1轮（D5 + Gradle外部输入）

Ruling: 保留已批准D5原文，给正常bulk Completed补独立只读后验，不把provider回显当已回读；最多5页×1000条，按Unicode安全公共prefix收窄，不假设排序。命中目标、游标重复/未穷尽或读失败保持UNKNOWN，不重发；部分/受理/未知回执不假装完整。失误代价为大prefix下保守UNKNOWN，不是放宽不可重放边界。

- [x] Core新7个bulk用例：5行为RED→GREEN，2直接绿补证。覆盖仍存在/全缺席、多页和第二页目标、重复cursor/预算、读失败、Partial/Accepted/Unknown、UI waiter取消不取消app-owned读。44个controller测试通过。属性nextToken拼写编译错误另留日志，不充作RED。
- [x] 真实VM新2个bulk补证直接绿，fixture真实维护对象集合；21/21（VM16+纯UI5）。完整回显后仍存在必须UNKNOWN→ActionRequired、单DELETE；正常删除须DELETE后GET对象列表才SUCCEEDED。
- [x] Gradle增量行为RED：只把manifest的一个预期source_sha设为零，正常相同filter任务竟UP-TO-DATE成功；finally恢复。修复声明audit json/csv、4模块src、build脚本、SCHEMA_FILE输入后相同漂移4实际失败，恢复4绿，不变再次UP-TO-DATE；另Native源码注释漂移4失败、公开schema副本额外换行1失败，全部恢复。资格flags全程false，无API执行授权变化。日志/tmp/flarego-r2-audit-input-*.log及external-source/schema日志。
- [x] 最后完整unit842/UI19/platform97、audit349/CI10通过；Debug/lint及native21通过。Source hashes全部匹配，受控漂移探针已恢复。
- [x] 同验收端只读针对复验通过：Controller44/Coverage4/P2 gate6共54、新bulk真VM2/2，以及正常schema负例退出1/恢复退出0实际执行；25份源码摘要全匹配。未重读整分支或重跑无关所有测试。独立负例XML同名覆盖仅stdout为证，未伪称保留；开发者同类负例XML另存。

资料：/tmp/flarego-r2-bulk-evidence/，/tmp/flarego-r2-a-fix-independent-review.md。前轮不通过记录与首轮错误通过/复核撤回均保留。helper实际原名assertNoMutationWithRunningTask，主控此前更正为AfterConfirm是误报，已明确纠正自身而未改代码掩盖。

最终核对：[R2-A本地工程出口](../../cloudflare-audit/r2-a-local-exit-verification-2026-10-18.md)。原规格要求未知只读/不重放，不要求新增“人工勾选解锁”；真实UNKNOWN/旧RUNNING无证据不迁移，属于保留的安全边界，不为消灭待办引入未批准风险。至此A本地实现与工程验收完成，B/C/D未开始，E待单独批准；本轮仍无commit/push。当前审核链平台估价0.2807825美元（非账单），input505088（非缓存口径未知）、cached6142260、output41503；覆盖本fresh会话多阶段/勘误/修复复验，不当作仅最后一轮或端到端总成本。主控计数未知。
