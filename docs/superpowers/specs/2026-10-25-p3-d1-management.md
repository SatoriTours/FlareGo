# P3 完整 D1 数据库管理设计

日期：2026-10-25。状态：**用户已选择按完整13项方案继续，批准本地合成实现及验收**。独立设计校核已完成，工程契约冲突已闭合；执行见[实施计划](../plans/2026-10-25-p3-d1-management.md)。上游：全CF管理目标、[覆盖路线](../../cloudflare-coverage-audit.md)、[只读预研](../../cloudflare-audit/d1-preflight-2026-10-25.md)。KV完整13项已通过本地合成验收，不能以KV完成代替本批或整体目标。

## 1. 目标、现状与方案选择

把仅数据库UUID/name的D1目录扩展为原生数据库管理、SQL执行与结果、完整SQL文件导入导出、Time Travel及删除恢复。采用现有Kotlin/Compose、typed客户端、权限issuer、任务协调、私有内容owner及Android SAF；同一Codex负责实现、自测和普通修复，之后独立Codex验收。

推荐一个完整13操作切片：已有目录1项＋新增12项，底层按协议/隐私/协调/界面顺序实施，最终按完整范围验收。只先做数据库元数据会推迟SQL/备份恢复闭环；通用JSON调用页缺少SQL隐私、导入多阶段和危险恢复语义，不满足原生管理目标。这两种路径不作为完整D1交付。

用户已批准本设计的重要新增行为；授权仅限本地合成实现和验收，不允许执行真实SQL/生产恢复。复用 `/home/jimxl/projects/FlareGo`、`wks_777a025be3c6808e`、main及现有Tab；不创建Workspace/worktree/分支，不搬移R2-D成果。不真实云、凭据、E1、Git提交/推送/Tag/Release/归档。

## 2. 精确范围与版本化增量

固定项目Schema有12项D1；当前官方新增undelete。只读固定当前官方提交 `d04a1ddd718bb1c91f79d3220174e5a16ed1cacd` 的D1路径及递归引用，见[独立摘录](../../cloudflare-audit/d1-current-schema-2026-10-25.json)。原12操作非x-*内容与旧Schema逐项相同；新增接口单独版本化，不改历史Schema/3659分母或旧资格。

所有路径前缀为 `/accounts/{account_id}/d1/database`。

| operationId | 方法及后缀 | 本批完整入口与语义上界 |
| --- | --- | --- |
| d1-list-databases | GET base | 原目录深化，数字分页，READ |
| d1-create-database | POST base | 名称、jurisdiction、primary_location_hint、read_replication，WRITE |
| d1-get-database | GET /{database_id} | 数据库详情和已知/未知字段，READ |
| d1-update-database | PUT /{database_id} | 完整设置read_replication，WRITE |
| d1-update-partial-database | PATCH /{database_id} | 仅明确字段的部分设置，WRITE |
| d1-delete-database | DELETE /{database_id} | 删除数据库及数据，DESTRUCTIVE |
| d1-undelete-database | POST /{database_id}/undelete | 精确UUID恢复，可选new_name，WRITE/DESTRUCTIVE |
| d1-query-database | POST /{database_id}/query | 单查询/batch、string params、对象结果，潜在WRITE |
| d1-raw-database-query | POST /{database_id}/raw | 相同SQL输入，保留列/行原始结构，潜在WRITE |
| d1-export-database | POST /{database_id}/export | 启动/轮询、全部或指定表、schema/data、下载完整SQL，影响可用性 |
| d1-import-database | POST /{database_id}/import | init→可选签名上传→ingest→poll完整管线，DESTRUCTIVE |
| d1-time-travel-get-bookmark | GET /{database_id}/time_travel/bookmark | 当前或指定时间之前最近bookmark，READ |
| d1-time-travel-restore | POST /{database_id}/time_travel/restore | timestamp/bookmark恢复、previous_bookmark与明确再次恢复，DESTRUCTIVE |

13路由为3个纯读、10个可能修改数据/配置/可用性的操作；已有list不重复累计。当前root registry119，预计新增12后131＝62 READ/69 WRITE（query/raw的WRITE是最大效果，不表示每条SQL会修改）。完整候选及测试需锁literal身份，当前未实现不能先填typed通过。

仅REST，无Worker/Sessions桥接、自动部署、GraphQL计费、SQLite本地模拟充当真实云或其他产品。read_replication设置成功不等于已接入Sessions或REST必经副本。PUT/PATCH支持复制配置，不虚构官方重命名接口。

## 3. 账号、权限与SQL安全边界

账号/数据库精确typed身份，UUID而非名称定位；路径及query单次编码，迟到请求不得换账号/数据库落地。新D1 issuer绑定credential revision/generation、account、operation、database和短租期，最后发送守卫强制fresh proof，不能借DNS/R2/KV写授权或“读取成功”。读列表/详情/bookmark保持可用；deny/未知条件/资源语法/凭据不明fail closed。

官方已有D1 Write明确映射的create/delete/PUT/PATCH及SQL使用动态permission catalog准确核对。export/import/restore/undelete在固定当前源仍缺x-api-token-group；实施时先查官方SDK/文档的精确证据，不能由命名类推。若仍不能核实，真实生产准入保持关闭且明确原因；用依赖注入的内部合成permit完成本地全链路，不增加UI开关或可被外部构造的通行证。该缺口仍计入整体待办，不伪装真实能力已可用。

**建议新增取舍：任意用户SQL一律按潜在写入执行。** 不用SELECT/PRAGMA/CTE首词、正则或本地试跑来证明远端无副作用。单条/多语句/batch皆要求D1写proof、完整保护预览和三级确认；参数逐项保留string类型与原字符。这样允许完整自定义SQL能力，并清楚说明普通D1 Read连接不能执行任意SQL。

只读连接仍提供数据库详情和受控浏览：固定内部生成的schema/table/index信息及有界分页SELECT；仅允许经过引用的单个标识符、参数化过滤值和固定枚举排序/limit，不接受自定义表达式/SQL片段。内部只读请求类型与任意SQL类型在私有factory分离，不能通过公开构造器放入SQL字符串绕过write。它们复用query路由但不增加REST分母；query的registry保持潜在WRITE、零重试。若无法证明内部某条语句只读，就走普通SQL确认而非降级放行。

全部潜在写零自动重试。首个可能有副作用的请求之前持久化RUNNING，发送后无可信结果一律UNKNOWN；provider错误文本不直接带SQL/结果进日志。读接口可采用既有有界429策略，SQL/poll/一次性流不自动重放。

## 4. SQL、参数、结果与私有内容

SQL、params、结果、表名/列名/表结构文本、SQL文件和临时签名URL均按潜在敏感内容处理；公开state只发布就绪、长度、页码、代次与随机句柄。任务不存正文、文件名/URI、URL、MD5、缓存路径、解密key或可重放SQL。

显示/编辑使用实际SecureWindow、默认遮蔽和显式reveal，不可用则拒绝内容；退出/后台/切账号/凭据更新撤销旧owner及确认。短编辑器采用既有64KiB上限，完整SQL或batch可由SAF文件导入私有缓存，不以编辑器上限缩小协议支持。不改写空格/换行、拆分分号、自动补事务/改变字面量，触发器和带分号字符串交远端正确处理。

请求支持typed单SQL+string params与typed batch两种结构，文件入口是该完整结构或SQL文本的明确选择，不通用任意HTTP。完整语法/类型扫描后封存；JSON数组/对象结果、raw列/行、null、数字原文、字符串、布尔、空结果与逐项success均完整保存。大结果有界流式加密到EOF，UI只分页/单项有界预览；超本地资源失败明确报告，不截断后声称成功，提供完整结果文件导出。

复用或抽取已验收的标准AES-GCM私有流式缓存/SAF事务能力，保持KV类型/owner隔离。每次发送/导出先完整认证，同一sealed文件重开读；密钥仅内存，各owner在自身会话取消/撤销/终态时释放其所有副本，启动仅删本App无owner密文。云操作终态与结果查看会话终态分开，具体交接见第7节。不得为了D1改弱KV完整性/生命周期。完整SQL/params需有可到达的保护分页预览，不能仅显示条数/大小。provider已损失的INT64精度不能被客户端恢复，保留原始数字也不承诺恢复精度。

SQL单statement限制100000bytes，每query最多100 params，API整个调用（含batch）30秒；无可靠SQLite语法解析时不以简单分号切割当statement校验，也不将100000bytes误施加于整份多语句文件。客户端完整结构验证与服务端SQL校验区分；不把服务端失败推成整批零副作用。

负责人锁定本地完整导入流边界为5GiB＝5,368,709,120 bytes（Long）：D1导入指南明确5GiB，D1 Limits的5GB条目脚注指向R2单次上传，而R2明确5GiB的二进制定义。保留单位差异与真实云边界未验证的说明；边界及+1以有界生成流测试，不分配整份内存，不默默收窄为5,000,000,000或25MiB。来源见[预研补核](../../cloudflare-audit/d1-preflight-2026-10-25.md)。

## 5. 创建、设置、删除及恢复

创建完整显示名称、辖区、位置提示和复制模式；jurisdiction指定时location_hint被忽略，要在提交前说明，不暗中替用户改变选择。PUT明确完整替换配置，PATCH省略字段不当作关闭复制。设置前读详情冻结旧/新模式，不承诺跨客户端CAS；改变复制可能有延迟。

数据库删除明确全部数据、bindings/调用方影响、不自动拆除其他资源、不自动备份。删除恢复使用精确UUID及可选新名；不能声称普通list能列已删项。提供用户输入UUID和本App自有安全历史定位，不枚举猜测已删资源。官方7日窗口、账号限额、new_name冲突、恢复后Time Travel不能回到恢复时/之前均需明确说明；不能把7日当绝对可恢复承诺。

Time Travel允许bookmark或明确UTC timestamp二选一；输入时间先用GET解析真实bookmark并冻结，最终核对请求方式、原时间、解析bookmark与数据库完整身份。restore返回previous_bookmark时作为安全控制定位提供，不自动undo；再次恢复也经过新三阶段确认。缺字段/权限错误/404不当成恢复成功，运行中查询取消与数据回退影响明确告知。

## 6. 导入和导出完整管线

导入先SAF读取一次到加密封存，流式MD5仅owner内部及协议正文使用，长度以Long计，完整内容/格式确认在任何云init之前。官方Wrangler实现表明已上传有效文件命中时init可直接开始导入，不能把init作为无副作用预检。三级确认覆盖完整目标数据库、文件完整内容入口、长度、可能变更所有表、可用性影响及无客户端自动回滚。

init后分支严格解析：返回upload_url/filename则执行一次完整签名PUT并检查长度及ETag，之后ingest；直接返回处理中/完成则不重复上传或ingest，只继续绑定该任务的poll。签名URL只能来自本次可信账号/数据库/计划响应并由私有owner持有；HTTPS、host/path规则需按官方固定证据审核，禁止IP/用户输入URL/凭据/fragment/不明重定向，不向非API主机转发API token/cookies；上下载复用有界ByteSource/Sink与取消关闭契约。通用R2签名域名示例不足以证明D1内部存储账号等于选中用户账号；不能只放宽域名后缀就声称完成绑定。D1精确host/path证据不足时，生产签名传输准入保持关闭并说明原因，内部fixture完成全部管线；原固定Cloudflare API主机约束保持。

MD5不是授权证明或防碰撞安全签名；不能将同MD5的另一owner命令当本次已确认文件。导入上传、ingest、poll皆绑定同一输入句柄和任务；任一步未知不重跑整管线。init/ingest成功不冒充导入完成；最终success、status、final_bookmark及逐项meta需一致，否则UNKNOWN/部分结果待核对。单次文件不隐式拆分、改SQL或补空表，不依赖未验证的服务器回滚保证重试。

导出明确全库/完整表列表，以及schema/data/两者；空表列表等于全库，禁止把空选择当无操作。no_data和no_schema同时为true应拒绝无意义配置。启动前说明运行期间阻塞查询、需要持续轮询；用户停轮询/后台/断网不冒充服务器已取消或导出完成。仅明确已启动的任务携bookmark继续poll，不再次创建导出。

轮询是用户明确启动管线的一部分，仅当前前台会话有界进行，有最大时长/次数与可取消进度；到限说明可能取消/未知，允许用户对同一安全任务定位显式继续检查，不自动重放启动。签名下载链接按官方一小时有效期处理，过期不静默启动新导出。全SQL先完整下载加密认证，再由用户选SAF目录仅新建自有pending→完整关闭→rename；不直接将半个网络流交为完成文件，不覆盖用户文件。含virtual tables的数据库官方不支持导出时，显示官方限制，不自动删表绕过。

## 7. 三阶段确认、协调与任务结果

数据库删除/恢复/undelete、任意SQL及导入、影响数据库可用性的导出均使用：风险说明→输入精确database UUID→独立最终确认。创建/复制设置采用完整旧新值核对和独立最终确认；任一字段/源/账号/凭据变化、取消/离开/后台均使旧句柄失效。

最终固定标题/取消/确认，正文可滚动，完整connection/account/database UUID/name、动作、配置、时间/bookmark、SQL/params或全部导出表范围可到达；列表分页不能只显示size。敏感预览由私有owner提供且保护窗口，不流入通用任务/公开确认DTO。

同数据库的全部潜在副作用共享锁，跨动作UNKNOWN/PARTIAL/处理中门禁持久化，刷新/重启不清除。create及已删UUID恢复的账号级交互另核查冲突，不按相同名称认领创建成功。任务只安全动作/定位/状态；过程bookmark是否可持久化须作为只读任务locator单独审核，签名URL/正文不可持久化。若无安全locator则诚实未知、人工核对，不存整条命令求恢复。

结果区分本地拒绝/未发送、接纳处理中、可信完成、逐项失败或部分成功、结果未知。batch逐条核对请求/结果位置及success；单SQL字符串可能返回多个statement结果，不能按一个请求只取一个结果。外层success不能覆盖内部失败；不把changes=0等同无副作用。只读观察不能推定未知SQL已完成，人工解除仅标ACTION_REQUIRED，不改云结果、不自动重试。

**负责人闭合的生命周期契约：云操作终态不等于私有结果会话终态。** 云操作结束立即释放已不再需要的SQL/params、原始输入和发送冻结副本，禁止通过保留命令实现重放；完整query/raw结果交给独立私有结果owner，绑定原connection/account/database、凭据代次与会话epoch。在当前前台SecureWindow会话内，用户可分页查看及明确发起完整导出；关闭结果、离页、后台、切账号或凭据变动时撤销并释放，迟到回调不能恢复。需要再次执行必须重新导入/准备/确认。

用户明确发起SAF文件任务时，结果owner交接独立加密封存副本；文件任务按已批准范围和自身取消/失败/完成释放副本，不能因此延长页面owner或通用云任务的敏感正文存活。导出SQL同样区分网络完成、受保护下载结果会话和SAF完成：完整网络文件可在当前结果会话等待用户选择目录，SAF完成或用户关闭后释放相应owner；网络完成不能提前宣称文件已保存。撤销时不得新发云请求或改变目的地，已开始的本地文件任务遵循既有SAF事务取消/清理契约。

## 8. 原生交互与必要验收

Resources→D1目录/分页→详情→复制设置/删除/恢复、受控schema/表浏览、私有SQL query/raw+batch和结果、SQL文件导入导出、Time Travel。当前缺官方权限映射的生产入口明确显示暂不可用原因；演示/fixture成功不混入用户真实连接或云资格。

验收按完整13literal operation/policy/handler/compiled测试/native入口与来源hash校对；12项旧+1项增量分别核原schema及固定当前supplement，历史KV/P2/R2矩阵原字节保持并作严格当前来源叠加，不把审计JSON变动态运行时注册。共享来源/119→131计数门禁要一并更新，不能放宽为>=。

覆盖SQL注释/CTE/PRAGMA/多语句/触发器与params不改写、只读factory不能带自定义SQL、账号/凭据/权限拒绝/最后守卫、真实wire/上传外主机无凭据和redirect拒绝、init直接导入分支、25MiB以上与5GiB边界生成流、结果大JSON有界/完整保存、源取消关闭/缓存认证/SAF失败事务、未知跨动作门禁、重建与旧回调、previous_bookmark及undelete窗口/冲突。

作者实现与自测后，独立Codex跑关键JVM/实际Main API36、系统SAF、字体1.0/2.0必要视觉。必须实际看完整范围、私有遮蔽/reveal、SQL/表列表末页、风险typedfinal和固定操作；新缺陷回作者，修复后只复验受影响范围。API26–28与真实云资格分别保留未验证，不用本地synthetic声称生产全部完成。

## 9. 请求一次确认的内容

推荐完整13项D1方案：包含未来使用者明确执行任意SQL（含batch/query/raw），私有SQL/params/结果与完整SQL文件，可能阻塞数据库的导入导出、精确UUID的删除/Time Travel/undelete及三级确认；任意SQL按潜在写入，普通只读连接使用受控浏览。官方四项缺精确权限时生产仍关闭，本轮仅内部合成全链路。

依据Paseo核心“重要产品/架构取舍合并一次确认”，只确认这些新增重要行为；获批后不再逐文稿或执行步骤审批，连续推进完整本地切片、自测、普通修复与独立验收。本轮不真实云/凭据/付费/E1/Git提交发布归档；整体全部CF能力目标继续，不自行缩成D1完成。

## 10. 独立设计校核收口

独立Codex完成只读builder check，报告见[builder-review.md](/home/jimxl/.local/state/agent-artifacts/FlareGo/2026-10-25-d1-preflight/builder-review.md)，SHA256 `f0b2863d244cb00c464eeabd8aa8bbbfb6309e59ee944a6efab0c32b0518cac4`。唯一Important为执行终态释放结果与完整查看/导出冲突，负责人据既有KV owner/文件任务契约在第4/7节明确双生命周期，未新增用户决策或产品范围。5GiB精确边界与缺证生产门禁已纳入第4/6节。该校核只证明设计可进入一次确认，不代表产品已实现或功能验收通过。

## 10. 作者实现交验状态（2026-10-25）

已完成13项typed/native本地合成实现及作者自测；独立验收待执行，当前不宣称最终通过。完整JVM1222/143/142/8均0，Debug/testAPK和app/platform lint通过；实际Main/真实SAF两字体各10/0，恢复显式显示另字体1.0定向1/0。当前覆盖131＝62R/69W，历史schema/矩阵冻结，当前D1补充证据不构成云授权。外部详细证据及失败轨迹见实施计划与 `~/.local/state/agent-artifacts/FlareGo/2026-10-25-d1/`。

本地完成不改变第3/6节的生产缺证默认关闭、真实云0、API26–28未实测及整体CF未完成边界；本轮不提交或发布。

独立首验发现I1确认缺连接身份、I2大字号目录胶囊裁字，自动化223/0仍判不通过。两项已由原负责人修复，受影响core44/UI11、两字体定向各3项通过；当前待原独立Codex定向复验，本节历史作者结果不替代最终结论。连接名称与稳定ID来自准备时冻结身份；目录布局不缩小用户字号。

## 12. 最终本地合成验收结论

完整D1 13项本地合成切片已通过独立验收。首验完整范围证据加返工受影响独立复验（JVM55/0、两字体实际Main各2/0，共59次零失败）闭合I1/I2；独立实际核5张新原图，父核当前来源、日志与关键原图，无剩余Important。原不通过历史保留，前节“待复验”为该阶段记录；最终[复验报告](/home/jimxl/.local/state/agent-artifacts/FlareGo/2026-10-25-d1/independent/recheck-report.md) SHA256 `8ce7716cecf108ffabb25617a53386ab1d00cc02af6bf660f605da233c5545a5`。

本结论不扩大第3/6/8节授权：真实云0，生产import/export/restore/undelete与signed host/path准入仍关闭，API26–28未实测，5GiB仅生成流边界；整体CF未完成，本轮未提交或发布。
