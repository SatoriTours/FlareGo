# D1 下一批事实预研（未批准实现）

2026-10-25。KV 独立验收期间仅只读核对并留存外部证据；未改当前交验210文件、产品/测试、schema/旧台账、Git或设备。整体CF目标仍active；此文件不构成下一批实现批准。

## 现存覆盖与官方增量

固定schema有12个D1操作：list/create/delete/get/PATCH/PUT/export/import/query/raw/bookmark/restore。root手写ActionPolicies目前只注册list（d1-list-databases），原生Resources也是数据库目录，未发现D1深度管理专属入口。review将其余11项放P3。

当前官方API目录另列POST /accounts/{account_id}/d1/database/{database_id}/undelete，固定schema没有此路由。因此不能把固定12项称当前全部；下一批候选应逐项核对13路由，新增undelete另作版本化supplement，不改历史分母/冻结schema、不猜operationId或权限。

官方说明恢复删除仅限7日，支持可选new_name；恢复后不能Time Travel到恢复时或之前。恢复会占账号限额。没有证据表明普通list可列已删数据库，界面需要明确数据库UUID输入或自有安全删除历史，不虚构自动发现。

来源：[D1 API目录](https://developers.cloudflare.com/api/resources/d1/subresources/database/)、[Undelete](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/undelete/)。

## SQL、复制、内容隐私

query/raw支持单查询和batch，SQL本身可含分号分隔多语句；固定params为string数组。不能仅看POST当写、或首词SELECT当无副作用，SQL读写分级及readonly凭据行为需在完整设计中明确。API接受D1 Read或D1 Write，但这不证明任意SQL可安全按读放行；后端逐语句写限制尚未实测。

PUT/PATCH的固定schema可修改read_replication(mode auto/disabled)，并非任意数据库重命名接口。create还含name、jurisdiction、primary_location_hint；jurisdiction存在时location hint被忽略。启用复制与客户端REST自动享用副本不同，不应借设置成功宣传Sessions/binding已接入。

SQL、params、结果、dump、临时签名URL需按私有敏感内容处理；复用KV缓存/SAF前必须核命名及owner边界，不把KV domain类型直接暴露为D1产品API。自定义SQL的确认、数据库锁与unknown副作用、结果完整保存策略属于待一次合并确认的重要取舍。

来源：[Query](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/query/)、[Read replication](https://developers.cloudflare.com/d1/best-practices/read-replication/)。

## 导入、导出和恢复

导入不是发送SQL到import单步：init(etag=MD5)→临时URL PUT→ingest(etag,filename)→poll(current_bookmark)。官方Wrangler实现指出命中已有有效文件时init可能直接开始导入，不能把init当纯预检或把破坏性确认推迟到ingest。上传不带Cloudflare API Authorization；signed host/redirect、完整流长度/摘要和取消未知状态仍需独立设计。

导出包含启动和持续轮询；停止轮询可能取消，运行期间数据库不能服务查询。dump_options包含no_data/no_schema/tables；空tables等于所有表，需要完整范围确认；signed_url仅一小时。不能只实现获取URL就称完整导出。

恢复接受bookmark或timestamp，返回新bookmark和previous_bookmark；运行中查询会取消。保留previous_bookmark只能提供另一次明确恢复入口，不自动undo或自动重试未知恢复。

来源：[Import](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/import/)、[Wrangler官方实现](https://github.com/cloudflare/workers-sdk/blob/main/packages/wrangler/src/d1/execute.ts)、[Export](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/)、[Restore](https://developers.cloudflare.com/api/resources/d1/subresources/database/subresources/time_travel/methods/restore/)、[Time Travel](https://developers.cloudflare.com/d1/reference/time-travel/)。

## 当前限制待实现时锁定

Limits页面记单SQL100000bytes、每查询100绑定参数、30秒查询、导入文件5GB；import-export指南写5GiB，因此单位边界尚有文档差异，设计应记录与锁定权威执行边界，不能默默取小值当完整支持。导出不支持含virtual tables的数据库，不可自动删表绕过；INT64结果可能已有服务端数值精度损失，客户端保留原始JSON数值也不能声称恢复精度。

来源：[Limits](https://developers.cloudflare.com/d1/platform/limits/)、[Import/export指南](https://developers.cloudflare.com/d1/best-practices/import-export-data/)。

下一步：先闭合正在运行的KV独立验收，再补当前官方undelete精确schema/权限证据与SQL安全设计，形成可审阅完整D1方案并只合并询问新增重要行为一次。本预研无测试结果，不是D1功能交付。usage未知。


## 版本化增量核对（同轮补充）

已从官方公开GitHub只读获取api-schemas当前main并固定到提交`d04a1ddd718bb1c91f79d3220174e5a16ed1cacd`；整份源SHA256 `5b3120b85bad2c16a63b6ed760e885ffcedbd3771c3cedb4658defcab59641ac`。仅D1 paths及递归引用components摘录留`current-d1-schema-extract.json`（SHA256 `d207fe8955d0fd016652902b72cfee5f690e0a4da7ac59849f0e644198a54310`），不覆盖项目固定schema。

12项既有D1操作在忽略x-*扩展后逐操作对象完全相同；新增唯一D1路由operationId已核实为`d1-undelete-database`，POST上述undelete路径。new_name可选、body非required、additionalProperties=false、UUID路径和200标准数据库详情、4XX标准错误均有固定源证据。

权限缺口不能省略：新schema中export/import/time-travel-restore/undelete的x-api-token-group仍缺失，不能因为其他D1写操作列D1 Write就声称四项官方精确权限已解决。未做真实token试探。后续完整方案必须包含核实路线、独立内部合成permit与生产准入边界，不能让metadata空值默认为可写。

源码补充核对：现有TransferPort仅download，D1上传需明确有界ByteSource请求体流与临时签名URL不同主机、无API鉴权转发的传输契约；不可把现有download成功等同上传已实现。KV交验源码/设备未动。

上游固定链接：https://github.com/cloudflare/api-schemas/blob/d04a1ddd718bb1c91f79d3220174e5a16ed1cacd/openapi.json 。


## 导入字节边界补核（设计校核期间，仅事实补充）

本轮再次逐项读取官方文档：D1 Limits 表写5GB，但其脚注5明确将导入限制指向R2上传；导入指南明确写5GiB，R2 Limits表明确单次请求5GiB，并将GiB定义为2^30 bytes。因此后续设计的本地完整流支持边界应锁定为5 × 1024^3 = 5,368,709,120 bytes（Long），同时保留D1表格单位不一致的记录，不宣称真实云已验证该精确边界。测试需覆盖该边界及+1的生成流，不能实际分配5GiB内存或将大文件测试缩成25MiB。

单SQL statement限制为100,000 bytes，不等同整个含多语句SQL文本／导入文件只能100,000 bytes；未具备SQLite兼容语法解析器时，不用分号切割触发器／字符串来施加伪准确本地限制，保留完整文本并区分服务端语法／statement限制错误和客户端JSON／params结构校验。

来源：[D1 Limits及脚注5](https://developers.cloudflare.com/d1/platform/limits/)、[D1导入指南Known limitations](https://developers.cloudflare.com/d1/best-practices/import-export-data/)、[R2 Limits字节定义与单次上传](https://developers.cloudflare.com/r2/platform/limits/)。

签名地址补核：D1 import模型明确upload_url是R2 presigned URL；R2官方文档说明签名地址自身承担授权、不需额外鉴权头，支持account域名路径式和bucket.account域名式，不能使用自定义域名。此证据支持隔离的无API凭据传输器，但不能由通用R2示例推断D1内部存储账号必须等于用户选中账号，也不能把任意*.cloudflarestorage.com后缀当完整D1身份绑定。D1精确host/path模板未公开的部分仍作为生产准入待核项；完整本地合成管线通过内部fixture校验，不降低固定Cloudflare API主机约束。

来源：[D1 Import模型](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/import/)、[R2 Presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)。本节无产品实现、测试、云或Git写入。

## 一次确认待回复期间的权限取证结果

已直接打开当前官方export/import/time_travel restore/undelete四个方法页，并逐页查找“Accepted Permissions”；本次四页均未出现该字段。对照官方create和get_bookmark页明确提供D1 Write或D1 Read/Write，说明不能把本次缺字段解释为自动拥有权限。定向官方域名检索也未找到四项各自精确映射的新证据；这属于证据未闭合，不是证明服务端不支持D1 Write。保持设计中的生产准入待核边界，不继续同词搜索，不试探真实token，也不改变待确认的13项本地实现范围。

直接核查页：[export](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/)、[import](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/import/)、[restore](https://developers.cloudflare.com/api/resources/d1/subresources/database/subresources/time_travel/methods/restore/)、[undelete](https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/undelete/)。此取证不代表D1实现已批准；一次合并确认仍待用户回复。
