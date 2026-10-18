# R2 写入闭环执行记录

沿用已批准的 R2-A 与 Task6b.4；当前 `feature/cloudflare-p3-r2` 工作树上继续，不覆盖前轮成果，不提交/合并/推送/发布。

- [x] 修复 R2Controller 只凭同名 key 认定上传成功、单删 hash 缺 key、创建/设置对账字段及 ACTION_REQUIRED 门禁。
- [x] 保留对象 locator，同时给 coordinator 增加受信任的资源组执行锁；桶/对象同桶串行，锁在 app-job 内持有，不绑 UI 等待者。
- [x] R2WriteRuntime 复用 ChangePlans/OperationCoordinator；独立实际输入与 route epoch，不从 plan 反填授权上下文。
- [x] 平台暂存副本提供单次受控 source，取消/释放与运行中发送的文件所有权分离。
- [x] 将创建/删除/上传/批量删除接到两阶段预览和显式确认，状态与只读核对真实可见。
- [x] 真实 VM/Compose、平台文件和恢复开发者自测：core807/UI13/platform97/设备定向51，审计349/CI10、Debug/lint通过；真实云/付费/S3 临时凭据不在本轮。
- [x] 独立功能验收记录归档（仅本轮功能/交互自动化范围）：Agent `b3c879b6-a59f-44a8-b723-455b57f2aebe` 报告通过；模型/测试引用已勘误，主控核对现存 XML 和14类回归447项。独立设备前两轮未单独留存，视觉未验证；证据限制和后续问题见阶段证据“独立验收”。
- [ ] 后续：MainActivity picker当前8项真实往返、14+1限定范围独立复验与证据闭环已完成（底层provider异常仍有风险），见[picker记录](2026-10-18-r2-picker-progress.md)；桶 PUT/PATCH UI、Task7 十操作矩阵与全分支最终 review仍待完成。

裁决：
- 未知上传保持未知：列表中的同名对象不能证明本次内容写入。
- 批量部分失败或 ACTION_REQUIRED 不自动解锁同桶写入，不自动重放。没有可证明的读证据时保留门禁。
- 文件指纹来自 Application 文件暂存对象，不接受 UI 用字符串标签提供上传流。
- 对 coordinator 的扩展必须保持默认 DNS/Zone 调用语义不变，locator 只用于恢复，不被改写成 group locator。
