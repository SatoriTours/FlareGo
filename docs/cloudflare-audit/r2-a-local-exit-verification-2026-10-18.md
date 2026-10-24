# R2-A 本地工程出口：最终交付核对

## 结论与边界

**R2-A 本地工程出口通过。** 原会话已检查实际 diff、源码摘要、独立报告与原始 stdout／XML。此结论不是“全 R2 已完成”，也不代表真实 Cloudflare／付费／发布资格通过。

- 当前 `feature/cloudflare-p3-r2`，HEAD `3c7284b` 加本任务未提交改动；与 main 的 merge-base 实测 `6823394f0668bf2aae19ad2bc842cbec3a554342`。
- 用户前轮授权的提交／推送已止于 `3c7284b`；**本轮未 commit、push、合并、创建 Tag/Release 或真实云写入**。
- R2-A 10项 typed handler、手写策略、产品接线与本地三层证据；其余40项 NOT_STARTED，临时凭据1项 DEFERRED_PENDING_APPROVAL。当前精确注册79（42 READ／37 WRITE），历史 P1/P2/auxiliary 69保留。
- 真实云、付费、S3临时凭据、API26–35设备矩阵、全面系统窗／视觉矩阵未验收；相关机器资格始终 false。R2-B/C/D 后续逐批计划，未因本轮通过自动开始实现。

## 独立判定轨迹（不隐去撤回）

1. PUT/PATCH UI／真实VM／十操作映射／桶写后验证的有限范围通过，见[前轮证据](r2-batch-a-verification-2026-10-18.md)。
2. 本轮有界预览与可信未分派拒绝增量独立通过：实际835/19/97、最终75设备项及失败提示图。见[本增量](r2-retention-verification-2026-10-18.md)与[原始独立报告](r2-retention-independent-review-2026-10-18.md)。
3. 整体审查首次给“通过”，原会话不直接采纳，指出 D5 批量正常完成路径缺独立写后读、Gradle 未声明外部证据输入。原验收端复核确认，**撤回整体通过并判不通过**。
4. 原开发者修复第1轮，保留原规格而非放宽 D5；行为 RED→GREEN 及针对复验成立。验收端据真实证据最终恢复本地工程出口通过；完整轨迹见[整体报告（含撤回与修复附录）](r2-a-final-independent-review-2026-10-18.md)、[修复复验](r2-a-fix-independent-review-2026-10-18.md)。

## 完成项

| 任务 | 本地落实与主要证据 |
| --- | --- |
| 路径／模型／校验 | slash-tail 显式手写准入；桶名、key、枚举与未知字段规则；默认 Zone 路径防护不放松 |
| 桶控制面 | POST／PUT／GET／PATCH／DELETE；PUT/PATCH空体+storage-class头；未知原类型只读；确认前后快照／路由／凭据绑定；Completed后验证目标状态，删桶须缺席 |
| 对象读写 | cursor列表、prefix、SAF下载；私有单遍哈希快照和单次上传source；单删回显；批量完整回显、部分／矛盾／未知保守处理；同桶锁、不重放 |
| 授权与实际接线 | 账户级 Workers R2 Storage Write 新鲜证明及最终守卫；App owner撤销；真实VM／MainActivity与系统picker、重建、取消、输入失效、FD／授权清理 |
| 预览生命周期 | R2私有issuer opt-in64／5分钟，容量满拒新，不驱逐有效／在途claim；惰性过期回收、未接纳预览撤销、防ID复用与负时钟拒绝授权 |
| 未发送拒绝 | 可信R2 adapter guard完成前的未分派证据可FAILED；边界以后相同错误仍保守；不按旧记录错误码追溯结清；新尝试必须新计划新证明 |
| 最后D5修复 | 批量完整回执后，在同一桶锁／app-owned作业内独立列举；Unicode安全公共prefix，≤5页×1000；全部缺席且游标穷尽才成功，其他情况UNKNOWN，不再DELETE |
| 覆盖与缓存 | 10/51/79精确门禁；声明audit json/csv、四模块src、build及SCHEMA_FILE为Gradle输入；漂移实际执行并失败，恢复成功，不变仍UP-TO-DATE |

### D5／输入触发修复的实际证据

- 新7个 core bulk用例：5项行为RED（43项中5失败）后GREEN；2项（非完整receipt不读、多页及后页仍存在）为直接绿补证。44个Controller方法当前通过。
- 真VM新增2项直接绿补证：完整回显后独立GET列表才SUCCEEDED；回显却仍存在则UNKNOWN→ACTION_REQUIRED且单次DELETE。
- 原配置：只改manifest预期SHA，正常任务错误地UP-TO-DATE成功，已观测行为RED。
- 新配置：同一漂移4项实际失败；恢复4过，不变再次UP-TO-DATE。外部Native源注释漂移4失败；独立公开schema副本追加换行，SHA漂移1失败；均精确恢复，资格flags从未改成true。
- 独立端复验正常命令的schema漂移→退出1，恢复→退出0且实际执行，非强制`--rerun`掩盖输入缺口。

## 最终有效验证（不相加混算）

| 来源／范围 | 实际数字 |
| --- | --- |
| 开发者当前完整unit | **core842／UI19／platform97**，全0失败／错误／跳过 |
| 开发者当前构建 | Debug、app/platform lint通过；既存warnings仍保留，不称零警告 |
| 开发者当前R2设备 | **21/21**（真VM16+纯UI5） |
| 开发者Python | audit349／CI契约10通过 |
| 独立修复前完整回归 | **835／19／97 + native75/75**，对当时快照有效；75含DNS Scan41、batch persistence6及R2相关28 |
| 独立修复后受影响复验 | **Controller44 + R2Coverage4 + P2Coverage6 = 54** 全过；新bulk真VM **2/2**；正常Gradle schema负例与恢复实跑 |

完整842与独立54不是两套同范围全量；21、2、75、前78／84均不同范围／快照，未相加为一次全设备验证。修复只涉及 bulk后读、相应测试和test输入声明，其他原始授权／流式／owner证据按未变源码继承；未为未变快照反复重跑全套。前轮Download清理失败及本轮旧预期失败／受控负例均保留。

归档入口：[evidence/r2-a-exit-2026-10-18/](evidence/r2-a-exit-2026-10-18/)。独立schema负例XML曾被恢复轮同名覆盖，负例仅以实际stdout/退出1为证；归档不把恢复XML伪装成失败XML。开发者同类负例另有原始失败XML。

## 必须保留的限制与裁决

1. **URI特殊key子集**：共享防护仍保守拒绝某些 literal `%`+hex／encoded `#`；明确不承诺任意保留字符都可操作，当前安全侧拒绝，不绕过通用防护，另行评审才扩展。
2. **bulk有界回读**：大prefix超过5页仍不能证毕会保持UNKNOWN；不是无限扫描，不宣称可自动解决所有未知结果。Partial／Accepted及旧UNKNOWN无人工强解；断开连接不等于供应商任务已停止。
3. **provider清理**：顺序、FD和真实系统picker受测；第三方provider删除失败的观测仍有限，不保证所有provider失败时必定无外部空文档／半成品。没有误报下载成功的授权。
4. **只读核对只证明当前观察**：同key存在不能证明未知上传内容，回读／发送间无CAS；不宣称并发原子隔离或历史执行者证明。
5. **视觉有限**：双方实际检查前段5张PUT/PATCH/只读图与本轮中性FAILED图，全部synthetic；不是全主题、字号、系统窗或所有交互逐帧的视觉验收。
6. 计划checkbox为模板（含未获授权commit步骤），当前状态以执行账本及本最终记录为准；不为了全勾选而执行Git动作。`HttpBody.Text("", type)` 是借构造器做media-type校验，不删除该校验性语句。

这些是本地出口的显式适用范围，不隐去未完成40项或未验真实资格，不把本地工程通过冒称线上可用承诺。

## 角色与估价

功能验收端 `54584677…` 的平台元数据确认 `pi/opencode-go/glm-5.3-flash`，configured/effective thinking high。新会话覆盖本轮增量、整体审查、挑战复核、事实勘误与修复第1轮，不能把累计数误作最后54+2的单轮消耗：平台 `inputTokens=505088`（是否含缓存口径未进一步核实，非缓存输入单独记未知）、`cachedInputTokens=6142260`、`outputTokens=41503`，`totalCostUsd=0.2807825` 为平台估价而非实付账单。主控本轮token/估价不可完整归属，记未知，不作免费推断；未作额度替补或模型切换。
