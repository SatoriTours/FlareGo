# R2 预览有界回收与未分派拒绝证据

## 当前状态与授权

承接已批准的 R2-A 收尾，仍在原 Workspace／`feature/cloudflare-p3-r2`，HEAD `3c7284b`。当前新增实现已独立验收通过（实际重跑835/19/97及最终75设备项），原会话已实读报告与XML；R2-A整体出口随后经两缺口修复与针对复验通过，见[最终核对](r2-a-local-exit-verification-2026-10-18.md)；局部通过本身不自动扩张。本轮未提交、推送、合并、Tag/Release或真实云写入；真实资格／付费／临时凭据均未验收。

原验收 Tab `b3c879b6…` 已返回 closed／archived，未擅自恢复。读取最新六个 saved profiles 的 notes 后，按同一功能验收配置 `pi/opencode-go/glm-5.3-flash`、thinking high，在已核实当前 Workspace `wks_d1e3f3b31015789b` 新建 fresh-context 只读验收端 `54584677-3cf5-4579-bd73-60f03b65f3fb`。不是备用模型切换，也不新建 Workspace/worktree。主控在验收期间暂停实现与 Gradle，仅归档文档和证据。

## 设计裁决与实现

### 1. 仅 R2 opt-in 的有界预览

- `ChangePlans` 默认 `retentionLimit=null`，原 DNS 等调用保留既有 ID、过期、确认及执行行为。R2 私有 issuer 设置64项；TTL仍为5分钟，不增加轮询器。
- 未过期条目不驱逐；已接纳但尚未结束的 claim，即使 TTL 已过也不回收。满额拒绝新预览，不能以静默驱逐代替一次确认一次结果的保证。
- 过期且没有运行中 claim 的 issuer prepared／issued 先回收，Runtime 原子表与 Controller 的 bucket／object payload 表据 issuer 是否仍保留清理。
- bounded issuer 给计划及确认加生命周期内不重复的序号后缀；即使 ID 源重复、旧条目已回收或时钟回退，旧字符串也不会指向新计划。九字段 ChangePlan 与持久任务结构不变。
- 取消撤销未接纳预览；已接纳的执行不能被 discard 分离，旧确认仍共享原结果。Browser 取消、输入／页面／目标变化及迟到预览丢弃都触发撤销；prepare 返回时已丢失真实上下文的条目也清理。
- 坏时钟不阻止撤销本地能力；惰性过期清理保守保留，prepare／confirm 仍按原规则拒绝坏时间。

**代价与边界：** 同一5分钟窗口最多64个需保留的预览／确认，合法旧确认不因容量被挤掉，极端高频操作可能需取消未接纳预览或等待过期。此处仅 prepared／issued／payload 的有界生命周期，不声称持久任务历史或通用稳定资源锁表也已回收。活跃 source／文件描述符由原 transport 所有者关闭，清理缓存不调用 source open/close、不删除任务行、不取消已发送作业。

### 2. 以可信发送边界结清，而非猜错误码

- 仅 R2 Controller 在可信 typed adapter 周围记录其必需最终 guard 是否成功通过。session／读取／权限等在此之前拒绝，可返回内部构造的 `NotDispatched` receipt，coordinator 以固定安全摘要结清 FAILED，provider request ID 为空。
- 一旦 guard 通过，任何之后的同错误码、最终权限证明拒绝或传输失败仍保守为可能已发送；不把真实 UNKNOWN／PARTIAL／ACCEPTED 转失败或成功，不自动重发。
- adapter 未调用 guard 却返回 Completed 是契约违反，保持 UNKNOWN，不能报成功。取消异常仍遵守原协调器恢复语义，不伪造未发送证明。
- FAILED 的只读核对在独立 live scope／账户／已审核 action／ref kind 校验后保持 FAILED，不读远端、更不恢复 payload；外国 scope 拒绝。新尝试须新计划、新显式确认、新权威快照与写证明；旧确认只返回原任务。
- R2 FAILED 文案改为中性“操作未成功…重新预览确认”，不把本地拒绝说成供应商拒绝。DNS Scan 只补新 sealed subtype 的穷尽渲染分支，不改变旧 DNS receipt 的解释或授权。

**不是人工强行结清：** 真正无法证明的 UNKNOWN／PARTIAL 继续阻断。没有新增“勾选已检查就标成功/解除”的入口，也不把断开连接当作供应商作业已结束的证明。

## 行为 RED／GREEN 与保留失败

| 范围 | 实际轮次 |
| --- | --- |
| 容量／过期／全缓存表／ID | 新3项实际行为 RED→GREEN，未使用缺 API 编译失败冒充 |
| Browser 取消96次 | 新1项在第65次触顶而 RED，接入撤销后 GREEN |
| 未分派拒绝／缺最终guard | 新2项行为 RED→GREEN；同错误码放在边界后仍 RUNNING 为直接通过补证 |
| 终态拒绝核对 | 同一新拒绝方法中增加“核对后仍FAILED且零额外GET”，观察原实现回 UNKNOWN 的 RED 后修复 |
| 坏时钟撤销 | 新1项因撤销被负时钟异常打断而 RED，修复后 GREEN，坏时间不因此能授权 |
| 补证 | 3项 Runtime（同输入撤销+重复ID、迟到上下文、在途/双击/TTL）及1项对象前后边界+外国scope直接通过；合计5项直接绿补证，不伪称RED |
| 兼容与旧预期 | 新 sealed receipt 最初编译指出 DNS Scan 缺穷尽分支，补齐。该次完整 stdout 被同名绿日志覆盖，仅会话摘录，不虚称已完整保留。旧 R2 快照拒绝 RUNNING 预期改为有证明的 FAILED；其余普通 coordinator 的 RUNNING 语义不变 |
| 设备首轮 | 75项74通过1失败：`missingWriteGrantDoesNotSendAndReadOnlyRecoveryDoesNotRetry` 的旧后置预期仍为UNKNOWN，实际安全终态FAILED；修正预期为保持FAILED，零写断言不弱化。随后75/75。不是随机失败重跑转绿 |

原始目录 `/tmp/flarego-r2-retention-evidence/` 的 `red`、`cancel-red`、`dispatch-red`、`terminal-red`、`clock-red` 均在覆盖结果前存 XML；`native/` 保存75项首轮真实失败，`final/native/` 保存修正轮。前轮 UI／系统 picker 的原失败仍保留，不被这里绿色抹除。

## 开发者实际验证

```sh
./gradlew :shared-core:jvmTest :shared-ui:jvmTest :android-platform:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:assembleDebug :app:lintDebug :android-platform:lintDebug \
  :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.flarego.R2ManagementUiTest,com.flarego.R2BucketEditorUiTest,com.flarego.R2DocumentPickerIntegrationTest,com.flarego.R2AuthorityOwnerIntegrationTest,com.flarego.DnsScanUiTest,com.flarego.DnsBatchPersistenceTest'
SCHEMA_FILE="$PWD/.superpowers/cloudflare/openapi.json" python3 -m unittest discover -s tools/cloudflare_audit/tests -v
python3 -m unittest discover -s tools/ci/tests -v
```

- 最后 unit **835 core／19 UI／97 platform**，全0失败／错误／跳过；Debug／lint通过。完整 XML `/tmp/flarego-r2-retention-evidence/clock-final/{core,ui,platform}/`，最后截图辅助更新后的普通unit命令为UP-TO-DATE，未当作该次重新执行证据；当前快照实际重跑由独立--rerun确认。
- 设备修正轮 **75/75**：R2 VM14+纯UI5+真实picker8+R2 owner1，以及 DNS Scan／batch persistence。随后补负时钟撤销及 bounded 后缀拼写，最终源码再跑 **19/19** R2 VM+纯UI；不把75和19相加当唯一覆盖，也不声称75已在最后微调后再次跑过。独立端本轮要求跑最终源码75项。
- 设备仅自有 `flarego-r2-integration`／`emulator-5554`，API36、320×640、dpi160、font1。未触碰其他设备。
- 审计 **349**／CI契约 **10** 通过，无远端 Actions 更新。
- 十操作／51候选／79注册不变；机器矩阵保留所有真实资格 false。前轮源码摘要保存在 `/tmp/flarego-r2-retention-evidence/previous-bucket-editor-runtime-support.json`；当前摘要明确加入本增量，不把之前验收绑定到新代码。

主控实际查看 `pre-dispatch-refusal.png`（synthetic VM fixture）：中性失败提示及“重新预览确认”可读；下方任务为 FAILED，不再谎称 provider 拒绝。仅此有限帧，不是全系统窗／多字号／对比度验收。无真实云、付费或临时凭据证据；API26–35、进程杀恢复与全分支最终审查未新验收。

## 独立验收

本增量通过：`/tmp/flarego-r2-retention-independent-review.md`，实际835/19/97重跑、最终75/75、拒绝图实看。stdout及47个unit XML按模块即时保存于 `/tmp/flarego-r2-retention-independent-evidence/unit-round/`，native stdout/XML在 `native-round/`；主控逐模块再次重算835/19/97，全0失败/错误/跳过。

后续依赖性R2-A整体出口审查复用此端，不重复未变快照整套测试。其最初给出整体通过，主控不直接采纳：正常批量删除是否真的有D5要求的写后回读（不能以回显/后续核对按钮代替），以及外部manifest/schema/source哈希是否为Gradle Test声明输入，两项证据疑点已交同一审查端复核。此处为首次复核检查点：原整体通过已撤回。随后补齐bulk有界读后验证和Gradle外部输入，修复第1轮独立54+2及schema负例/恢复通过，整体本地出口最终通过；真实云／付费仍未验，不因本地通过自动实现B。

## 角色与消耗口径

本轮开发由原会话延续，不重复派发已承担的实现；功能验收使用新建同角色配置，不是额度替补。新增需求为有界生命周期，缺陷为发送前拒绝不应长期占未知门禁及坏时钟撤销异常，设备旧预期更正与环境阻塞分列。当前未取得可归属本轮的非缓存／缓存／输出 token 与账单数据，均标未知；不把价格估计当实付或零成本。完成后只读取本验收端元数据，不扫历史库。
