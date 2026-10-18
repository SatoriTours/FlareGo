# R2 MainActivity 文件往返执行记录

续执行已批准的 R2-A / Task6b.5；沿用 feature/cloudflare-p3-r2 与现有未提交成果，不提交、合并、推送、建 Tag/Release 或另建工作空间。

## 范围与检查

- [x] 测试内在真实 MainActivity 创建前安装 synthetic-transport ViewModel，不向生产类增加测试钩子，不替换/伪造 ActivityResult 或系统 picker。
- [x] 真实 OpenDocument 取消：返回同一 VM，零写入，允许下一次选择。
- [x] 真实 OpenDocument → 私有暂存 → preview → 显式确认 → 流式上传；原文档不变，独立权限准入，暂存与本次新增持久权限清理。
- [x] 真实 CreateDocument → 流式下载 → 事务提交；精确字节/长度，取消/失败不伪报成功。
- [x] 真实 MainActivity 在系统 picker 下重建；Android保留原 VM，选择/下载各自仅交付一次，不自动预览或执行确认。
- [x] 当前 core807/UI13/platform97、Debug/lint、审计349/CI10通过；设备84项/font1先81PASS/3FAIL（旧font2前提不满足），3项真实font2补轮全过，84唯一方法均有通过证据。原始失败与补轮分别保存，不称单轮84通过。
- [x] 截图发现两项R2界面问题，均实际RED→GREEN：成功后旧暂存notice、确认目标被放在卡片之后。4张synthetic原生截图由主控实际读取，仅有限视觉核对。
- [x] 扩展回归暴露DNS取消在presentation dispose/rebind间丢失。原组测两次超时，新增确定性用例RED(CANCELLED vs NONE)→按精确document stamp保留非可执行观察→GREEN；覆盖IMPORT/EXPORT及route清理。
- [x] 独立验收记录闭环（当前API36/synthetic范围）：首轮清理失败未以重跑覆盖；已修正R2DocumentBridge在清理后发布下载终态。固定范围独立复验14整轮+1单测均0失败/错误/skip，XML/stdout逐轮留存，主控已核对并归档。只接受顺序修复与有限往返范围通过；旧失败provider级根因未证实，底层清理失败/可观测性仍有风险，不能承诺删除必成。视觉独立端实际读取4帧，仅320×640/font1范围。

Ruling: 测试通过 Application.ActivityLifecycleCallbacks.onActivityPreCreated 将现有可注入 transport 的 FlareGoViewModel 放入真实 MainActivity 的标准 ViewModelStore，生产 MainActivity/VM 不添加全局测试 factory。测试设备限定 API29+、自有 AVD flarego-r2-integration。API26-28 与进程杀死后恢复不由此证据覆盖。

Ruling: 新测试若直接通过，记为既有行为的补证，不撤走实现伪造 RED。只有实际发现行为失败后，才新增回归并做最小生产修复。
