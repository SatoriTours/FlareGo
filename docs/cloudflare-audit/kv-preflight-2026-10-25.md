# KV 实施前核对（2026-10-25，历史预研）

本记录是后续设计输入，未批准 KV 实施，不修改准入、运行时注册或真实云资格。整体目标仍为全部可管理 Cloudflare 能力；R2-D 的独立返工复验在原功能工作树进行，本记录不属于其冻结快照。

当前状态补记：用户随后批准完整方案，13项已通过本地合成独立验收，真实云仍0；见[设计交付结论](../superpowers/specs/2026-10-25-p3-kv-management.md#10-本地合成交付结论2026-10-25)。下文保留实施前事实与原未批准历史，不表示当前仍未实施。

## 实施前仓库事实（历史快照）

- 固定 Schema `.superpowers/cloudflare/openapi.json` SHA256 为 `06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461`，未更新。
- KV 共14个固定 REST 操作：1项 P1 命名空间目录已接入，13项 P3 候选未实施，其中1项已弃用。完整现行能力为13项，新增12项；弃用操作继续保留在分母和迁移记录中，不伪装已实现。
- `CloudflareDirectoryClient` 仅读取 namespace id/title；不存在 KV 专用 port、typed client、变更协调或值编辑页面。演示资源和 inventory 详情不能算 KV 数据管理。
- 已有 `HttpBody.Stream`、multipart、`ByteSource`/`ByteSink`、`TransferPort` 可复用流式通道；它们本身不证明 KV 内容校验、私有内容生命周期或大 JSON 流式解析已完成。

| 固定 operationId | 方法及末段 | 当前归属 |
| --- | --- | --- |
| `workers-kv-namespace-list-namespaces` | GET namespaces | P1 目录，保留并深化分页 |
| `workers-kv-namespace-create-a-namespace` | POST namespaces | P3 新增 |
| `workers-kv-namespace-get-a-namespace` | GET namespaces/{namespace_id} | P3 新增 |
| `workers-kv-namespace-rename-a-namespace` | PUT namespaces/{namespace_id} | P3 新增 |
| `workers-kv-namespace-remove-a-namespace` | DELETE namespaces/{namespace_id} | P3 新增 |
| `workers-kv-namespace-list-a-namespace'-s-keys` | GET .../keys | P3 新增，保留固定 ID 中的单引号 |
| `workers-kv-namespace-read-the-metadata-for-a-key` | GET .../metadata/{key_name} | P3 新增 |
| `workers-kv-namespace-read-key-value-pair` | GET .../values/{key_name} | P3 新增，二进制 |
| `workers-kv-namespace-write-key-value-pair-with-metadata` | PUT .../values/{key_name} | P3 新增，覆盖语义 |
| `workers-kv-namespace-delete-key-value-pair` | DELETE .../values/{key_name} | P3 新增 |
| `workers-kv-namespace-get-multiple-key-value-pairs` | POST .../bulk/get | P3 新增，语义只读 |
| `workers-kv-namespace-write-multiple-key-value-pairs` | PUT .../bulk | P3 新增 |
| `workers-kv-namespace-delete-multiple-key-value-pairs` | POST .../bulk/delete | P3 新增，语义删除 |
| `workers-kv-namespace-delete-multiple-key-value-pairs-deprecated` | DELETE .../bulk | 已弃用，拟映射现行 POST 替代，待设计确认 |

所有路径前缀为 `/accounts/{account_id}/storage/kv/`。上表是固定 Schema 与 review 的只读交叉核对，不是执行白名单。

## 官方资料核对及设计影响

1. 标准 KV 的 key 上限512 bytes、metadata1024 bytes、value25 MiB，同 key 写入限额1次/秒。最新文档另外列出私测 KV Instant，限制不同；不能把 Instant 当作标准 KV 的同一契约。[标准与 Instant 限制](https://developers.cloudflare.com/kv/platform/limits/)
2. KV 最终一致；新值、修改和不存在的负缓存都可能在其他位置延迟60秒或更久。即时回读相等不能证明全球生效，短暂不相等也不能据此自动重复写入。协调器需分别表达 API 接纳、当前观察和传播未知。[一致性说明](https://developers.cloudflare.com/kv/concepts/how-kv-works/)
3. 单键 PUT 替换已有 value、expiration、metadata。可发 octet-stream 或 value 加可选 JSON metadata 的 multipart；未给过期参数表示不过期，同时给出时 TTL 优先。编辑页必须让使用者明确选择保留/替换相关字段，不能误将缺省当保留。[单键写入](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/subresources/values/methods/update/)
4. 批量写入最多10000项、整个请求不超过100 MB；现行响应可报告 successful_key_count 和 unsuccessful_keys。必须校验部分成功、缺计数、重复/外来失败键，不能只凭HTTP200宣称全部成功；不得自动重发整批覆盖新数据。[批量写入](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/methods/bulk_update/)
5. 批量读取 POST 一次最多100键，支持 text/json 和 withMetadata；应按语义登记只读，响应中的 value/metadata 仍需内容隐私保护。[批量读取](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/methods/bulk_get/)
6. 批量删除现行路径是 POST .../bulk/delete，权限页列 Workers KV Storage Write；不能沿用已弃用 DELETE .../bulk。[批量删除](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/methods/bulk_delete/)

注意：当前 API 目录在 `Namespaces Keys` 下还展示同路径的 deprecated SDK 别名，namespace 级现行方法页面未标弃用。设计需按 method + path + 固定 operationId 判定，不把 SDK 别名的弃用误套给现行 HTTP 能力。[API 目录](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/)

## 下一步需要锁定的产品决定

建议下一批完成标准 KV 的13项现行能力（复用1项、新增12项），为旧 DELETE 批删保留明确迁移记录。可分层实现，但验收终点仍是完整能力，不以只读目录代替深度管理。R2-E 临时凭据签发继续保持独立待批准，不夹带进 KV。

设计需一次确认：值与 metadata 的私有预览/编辑及显式文件导出；文本和二进制的完整读写；大批量输入如何冻结并流式校验而不把敏感正文塞入公开状态/任务；单键覆盖与批量覆盖的风险确认；命名空间删除及批量删除的完整作用域与多次确认。所有实现/验收仍只用合成数据，不读取真实内容或执行真实云写入。

待设计解决：大 JSON 的冻结存储与撤销方式、返回值的有界私有展示与完整导出、UTF-8字节级边界、分页游标失败保留和跨账号隔离、账号/namespace/key的互斥及外部并发边界。未解决前不声称可复用现有传输就已完成这些行为。

本轮只进行了仓库和官方资料读取；没有新增 KV 产品实现、测试、依赖或配置，没有 Git 写入、云调用、付费、凭据签发或发布。

### 实施时补核（2026-10-25）

- [REST 单值 PUT](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/subresources/values/methods/update/) 明确替换 value、expiration、metadata；省略两种到期参数表示不过期，省略metadata不提供保留承诺。键名按REST可打印非空白字符与512 UTF-8 bytes核对，保留大小写/Unicode/保留符号；编码仅解码一次的最后键参数，其他路径防护不放宽。
- [Token资源官方语法](https://developers.cloudflare.com/fundamentals/api/how-to/create-via-api/) 包含单账号及全部账号；KV evaluator仅接受这两种明确语法，仍绑定fresh账号GET与当前凭据代次。更细或未知资源语法拒写。
- [账号权限目录](https://developers.cloudflare.com/api/resources/accounts/subresources/tokens/subresources/permission_groups/methods/list/) 将 Workers KV Storage Write / Read 归属 account scope；ID必须由当前可信目录按精确名称及scope绑定，不把文档示例ID硬编码为授权。
