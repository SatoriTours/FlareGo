# P2 源码恢复与本地验收（2026-10-17）

P2 的 54 项候选端点及原生管理入口已恢复到独立分支，本地验证已完成。源码未提交、合并或推送；主仓库仍为开发前基线。本报告不声明真实 Cloudflare 或付费资格验收，也不声明单轮设备全绿。

## 工作区与恢复来源

- 原协调入口保留：workspace `wks_d1e3f3b31015789b`；旧执行 workspace `wks_f7c33b511b366d6f` 由用户手动归档移除，原目录和 XML 不再可用。
- 新隔离空间：`wks_9db7e60d3514d651`，`【自动】恢复站点与DNS管理`，路径 `/home/jimxl/.paseo/worktrees/2bpfajhp/cloudflare-p2-recovery`。
- 分支 `recovery/cloudflare-p2-zones-dns`，基线 `8371605815e5a20c95089e0c4cfe0bf7c8b401ee`；使用现存临时源码、公开 schema 和历史工具输入恢复，未重执行历史 shell／网络／Git 命令。
- 安全封闭解释器从实际工具输入重建候选；其20项自检通过。authority最终6源与原最终manifest逐项一致；C最终测试恢复到464行／19方法，保留风险重置及 fresh/stale 断言。历史 SHA 仅说明来源，不代替本次验证。
- 中间恢复发现并修正了漏注册4个 spread、误选早期 authority 拒绝桩、两个过期 controller 副本、C 路由／测试晚期差异。对应失败轮次保留；不是新功能 TDD 证据。
- 固定 OpenAPI commit `6b00f157496cd6a1792a613dc94654c51273aeb9`，SHA `06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461`；SCHEMA_FILE 优先且显式错误必须失败。Python 全审计所需 S3 公开资料按已固定 commit/SHA 恢复。

## 实际新验证

所有 Gradle／设备运行使用 `/tmp/flarego-p2-core-tests.lock` 串行。JDK17、离线依赖、本地固定公开 schema；没有 exclude、临时依赖注入或绕过原生 Confirm。证据目录为 `.superpowers/sdd/2026-10-07-cloudflare-p2-zones-dns/recovery-evidence/`。

| 阶段目录 | 实际结果 | 说明 |
| --- | --- | --- |
| core-first | 704／151 fail | 中间恢复漏 spread／早期 authority 桩；原样保留 |
| core-restored | 706／7 fail | 两个过期 controller 副本；原样保留 |
| core-final-restoration | 706／0 failure/error/skip；13.57s，6 tasks executed | 完整 core，含 coverage6；轮内源码未变 |
| platform-build-lint | 91／0；25.99s，129 executed | Debug assemble，app／platform lint 0 error，3／4 warning |
| app-lint-final | exit0；17.17s，5 executed | 最终 Android 测试变更后重新分析 androidTest；仍0 error／3 warnings |
| compatibility-restored-red | 5=2 PASS／3 FAIL | fresh owner/detail fixture、lazy列表可达性、Demo生命周期边界 |
| compatibility-candidate | 5=4 PASS／1 FAIL | Demo首次加载仍超时，不将 IME 假设当已证实原因 |
| demo-lifecycle-hypothesis | 3／3 PASS | 补真实公开 onManagementResume admission，仍由 native按钮发送唯一 Confirm |
| device-ui-full | 330=326 PASS／4 FAIL；678.88s | 全22类 UI／平台设备方法；旧失败 XML 保留 |
| fixture-boundary-targets | 9／9 PASS；73.14s | 包含原4失败＋相关 Management3／Batch3，不重复无关 leaf |
| device-app-font2-full | 24／24 PASS；375.64s | 2类真实 VM/runtime、AndroidDB、SAF/native；systemfont2 |
| Python审计／CI契约 | 349／10 PASS，0 skipped | 固定资料补齐后执行；之前349中1skip日志另保留 |

完整原始 command.json、gradle.log、新 XML、cases.json 与轮内 source-before／after 位于各阶段目录。最终归并 `accepted-case-results.json` 逐条引用实际 PASS XML：24 classes／354 unique 方法，无漏项或额外项。330／9／24 为互补及复验 cohort，不称单轮354或330全绿。

独立审查报告在 `recovery-evidence/independent-review/report.md`：所审范围无已证实 Critical／Important 阻断项，测试修正未弱化关键断言。其完成时24尚未结束，最终24由 root 另核对 XML、exit0、字体条件及源码一致性，不能倒称由审查者执行或验收。

## 兼容修正与失败边界

- ProductLoadingTest：LazyColumn 从可滚动父容器定位“刷新全部产品”，保留全产品调用断言；旧 PATCH 核对 fixture 补真实同账号 Zone GET 与精确 DNS detail GET。保留 UNKNOWN→SUCCEEDED、仅1 PATCH，并新增只读核对全 GET／持久成功断言。
- ManagementIntegrationTest：真实演示连接生命周期 admission 先于 Load；收起 IME、检查按钮可见后真实点击，Confirm转发计数必须为1。未直接调用 Confirm、未扩大 viewport／超时；原日志不足以证明 IME 为历史根因。
- ManagementIntegrationTest 与 DnsBatchRuntimeIntegrationTest cleanup：只 disconnect 临时真实连接，Demo 不进入生产 credential retirement。Application tombstone／生产凭据代码未为测试改弱，仍等待真实连接移除并 clear VM owner。
- 专用 AVD 首次沿用旧中断状态，defaultDB 存1个 synthetic测试账号；只查询聚合计数，确认 AVD 名称和测试前缀后 clear 专用测试 App 数据。未读取 Token／私钥，没有清理用户主环境。诊断与 reset 在 fixture-preconditions，原330失败不删除。
- AppSmoke 两原失败与 Zone invalidCreateName 测试源码均未改；定向9全部通过。Zone一次缺错误节点的确定性原因未建立，只报告原断言下复验通过，不能宣称稳定性修复。
- C最终测试恢复到原最终字节，不以丢失 fresh/stale 风险断言的早期副本计通过。旧 P0/P1 ledger、候选54原8列／顺序保持；注册按实际运行导出69项（39 READ／30 WRITE），不是源码 regex 推算。
- 每轮225项源码快照轮内一致。core／platform快照之后变化仅3个 androidTest（C最终恢复及两个cleanup）；330之后仅两个cleanup。最终9／24运行时产品源码相同。台账状态与报告随后更新，最终清单另保存；不冒称所有较早快照等于最终每个文件。

## 功能及授权边界

21类型 DNS 表单与 schema／PATCH差量和PUT完整替换、Zone生命周期、批量安全持久manifest、BIND导入导出、scan review/trigger/usage、DNS Settings、DNSSEC/公开ZSK、20受控站点设置、Universal SSL与基础purge均已接入真实 VM 和原生入口。演示流程使用同类端口和实际按钮，生产请求由 synthetic transport验证，本地OS／数据库／SAF是真实能力。

每个 App runtime 内共用 plans／coordinator／store／write mutex／metadata latch；single、batch、import、apply、settings与legacy检查整Zone持久未结算gate。此结论不外推到任意独立生产 VM/runtime 并发共享同一 mutex。Application级导入3／60秒limiter的跨VM配额有单独实测，不能替代跨runtime写互斥证明。

权限公开三态不授写。内部 proof 绑定实际 token identity／scope／operation／credential generation，强刷新失败不复用旧许可；NSEC3／secondary／editable／套餐资格独立默认未知拒写。四辅助 GET自身可能缺 Token 自省权限，未知即只读。

UNKNOWN 只读核对、不重放，不恢复 executable payload、plan／risk／用户确认。Batch current-state proof 不等于历史事务COMMIT或本客户端归因。Import count只来自真实typed回执与已settled task；新VM无内存回执返回null。Export只有实际 text/plain完整字节commit成功才显示结果，list403不阻断有fresh owner的只读尝试。SAF撤权／取消／外部填充不会删除用户内容，但平台不存在文件CAS，进程丢失后的清理能力有限。

scan trigger／purge ACCEPTED不等于后台完成或全球传播；DNSSEC registrar DS／AXFR／全球生效未探测。无真实带凭据Cloudflare调用、套餐验证、注册商修改或云写入；没有创建Tag／Release、发布或推送。

## 保存与交付

源码／证据／Debug APK 的独立持久保存位置为主仓库忽略目录 `.superpowers/recovery/p2-worktree-removed-2026-10-17/final-validated/`，与执行worktree独立。备份含基线、逐文件SHA、源码归档及新运行证据；没有本机配置、TokenVault、私钥或旧session provider凭据。恢复分支保持未提交，不自动归档，等待用户后续提交／合并授权。

最终台账写入后，`coverage-doc-final` 正常 main／全部 commonTest 编译及 coverage6 实际通过（18.23s，6 executed）；`python-doc-final` 再次349／10通过，CLI check exit0，DNS生成与当前源码逐字节一致。最后仅追加本段和历史标题／说明，未再改产品、测试或机器映射。最终 Python 证据脚本首次因要求 OK 为末行而在349项实际OK后停止（缓冲的fixture输出随后出现），完整记录另存 harness-parse-stop；修正精确行解析后整组命令重新实际通过，非产品失败。
