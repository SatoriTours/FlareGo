# 原生覆盖表 · 方案 A

基准：[当前可点稿](prototype/index.html)与[画册](prototype/atlas/index.html)。表按真实 App 来路分组；用画册 ID 或入口名称均可反查。按同一实现/产物验收：**已验**＝已有正式入口功能/视觉证据（具体状态见证据）；**合并**＝真实能力在行内、面板或系统流转，不另增页面；**现有**＝真实入口已接线，功能与视觉分别据实验证；**待补**＝已知 UI 缺口；**边界**＝当前能力、外部或安全限制；**规划**＝原稿已标规划。合成测试不证明实际 Cloudflare 可用；画册中示例文案、数值、演示跳转不是产品行为。

Android 复验：仅在隔离的指定 PVE 用 debug 和 androidTest APK。从表中的入口操作；可用 `adb -s 192.168.2.202:5556 shell am instrument -w -r -e class com.flarego.<类名或类名#方法> com.flarego.test/androidx.test.runner.AndroidJUnitRunner`。正式入口与组件用例有区别；只有实拍并目视对照才算视觉验收。最新测试和截图索引为交付的 `native/status.md`，不重复采已验主页。

| 画册 ID | MainActivity 来路 / 原生归属 | 状态与复验方法 |
| --- | --- | --- |
| `home`, `menu`, `drawer` | 总览／更多／多账号徽章 | 已验；WorkerReleasesUiTest，三条真实访问、账号隔离、顶栏与系统返回。 |
| `sync` | 总览 → 更多 → 同步详情 | 合并；WorkerReleasesUiTest#mainActivityOpensSyncDetailsWithoutInventingUnreadResults 验证真实读取／未读取、返回；补权仍以确切拒绝为前提。 |
| `setup`, `add`, `perms`, `token` | 无账号引导或账号 → 连接 Cloudflare；明细/已有 Token | 已验；AppSmokeTest、TokenConnectionUiTest。输入页不读取未主动粘贴的剪贴板。 |
| `discover`, `choose`, `connected` | Token 验证中 → 列账号或受限时手填 → 保存后回原位 | 合并；TokenConnectionUiTest、TokenConnectionImeUiTest；WorkerReleasesUiTest#mainActivityConnectsFromSetupAfterExplicitAccountChoice 从正式引导发现两个账号，显式选择并完成保存后才显示短暂反馈。 |
| `permission`, `upgrade`, `oldtoken` | 账单/产品拒绝 → 补权 → 验证原权限并替换 → 可选管理旧 Token | 已验；WorkerReleasesUiTest、BillingPermissionCardUiTest、TokenUpgradePersistenceTest；不自动撤销。 |
| `official` | Token 模板／旧 Token 管理／资源官网入口 | 边界，走系统浏览器；无 App 内伪造界面。 |
| `domains`, `empty`, `zone`, `dns` | 底栏域名 → 搜索/托管列表 → 域名的 DNS 页签、概况 | 已验；WorkerReleasesUiTest、ManagementIntegrationTest。 |
| `zonecreate`, `zoneedit`, `zonedelete`, `activation` | 域名顶栏新增或概况 → 创建/编辑/删除/激活检查 | 现有；ZoneManagementUiTest。独立按钮核对，删除须输入完整目标；激活检查非激活成功。 |
| `record`, `recordmx`, `dnsform`, `mxform`, `dnspreview` | DNS → 记录行 → 详情/按类型编辑 → 核对/确认 | 合并；WorkerReleasesUiTest 验证 A 记录正式入口，DnsManagementUiTest 覆盖各类型；MX 视觉未单独验收。 |
| `batch` | DNS 更多 → 批量操作 | 合并；DnsBatchUiTest、DnsBatchPersistenceTest；结果未知不重发。 |
| `files`, `importdns`, `exportdns`, `systemfile` | DNS 更多 → 文件 → Android 选择/创建；R2、KV、D1 也调用同类系统入口 | 合并/系统；DnsFilesUiTest、DnsDocumentPickerIntegrationTest、R2DocumentPickerIntegrationTest；核对完整文件及取消返回。 |
| `scan`, `reviewdns` | DNS 更多 → 扫描 → 审核发现项 | 合并；DnsScanUiTest、ExtendedManagementUiTest；逐项确认，不把扫描当写入成功。 |
| `dnssettings` | DNS 更多 → DNS 设置 | 现有；DnsScanUiTest（DNS 设置用例）、ExtendedManagementUiTest。 |
| `settings`, `settingedit`, `purge`, `ssl` | 域名 → 设置 → Zone 设置面板 → 通用/Universal SSL/缓存 | 合并；ZoneSettingsUiTest、ExtendedManagementUiTest。安全页也可直接打开同一 Universal SSL 面板。原权限与独立风险门禁不变。 |
| `security`, `dnssec` | 域名 → 安全 → DNSSEC | 合并；DnssecUiTest、ExtendedManagementUiTest；托管/待生效以供应商状态为准。 |
| `registrar` | 域名 → 搜索并注册域名 | 边界：目前只读搜索，实际注册去控制台；默认 Token 不预填 Registrar。AppSmokeTest。 |
| `resources`, `workers`, `legacy` | 底栏资源 → 类型 Workers → 当前账号／显式旧目录 | 已验；ProductLoadingTest、WorkerReleasesUiTest。 |
| `worker`, `deploys`, `deploy`, `pastdeploy`, `versions`, `version`, `version25`, `version26` | Worker → 当前流量与部署/版本页签；行内打开同一对象 | 已验/合并；WorkerReleasesUiTest、WorkerReleasesWireTest。多个版本 ID 是同一能力，不写流量。 |
| `create` | 资源顶栏新增 | 已接线；R2 打开原表单，KV／D1 打开各自有门禁的编辑器；Workers 保持外部。WorkerReleasesUiTest 的 mainActivityCreateMenu*。 |
| `r2`, `bucketcreate`, `bucketedit` | 资源 → R2 → 桶列表/创建/管理 | 现有；R2ManagementUiTest、R2BucketEditorUiTest；正式创建菜单的确认链路见下。 |
| `bucket`, `objects`, `object`, `upload` | R2 桶 → 对象/配置；以实际键前缀过滤/上传 | 合并；前缀仅由已加载对象推导，详情原位展开，类型若目录未提供不猜测；下载/删除沿原门禁。R2DocumentPickerIntegrationTest 核验上传/下载、原前缀和取消。 |
| `bucketcfg`, `cors`, `lifecycle`, `lock`, `localupload` | R2 桶 → 配置 → 对应目标页签 | 合并；R2ConfigurationUiTest、R2ConfigurationManagementUiTest；不伪造配置成功。 |
| `managed`, `custom`, `sippy` | R2 桶 → 配置 → 公共/自定义域名、按需迁移 | 合并；R2DomainConfigurationManagementUiTest、R2SippyFormUiTest、R2SippySecureWindowTest。不得录明文。 |
| `r2job`, `r2jobcreate`, `notify`, `metrics` | R2 目录 → 选择桶 → 后台作业、事件通知；指标为账号级可观察范围 | 合并；R2CManagementUiTest。桶指标画面不能把账号指标冒充单桶。 |
| `kv`, `kvcreate`, `namespace`, `kvmanage` | 资源 → KV → 创建/打开命名空间 → 管理 | 现有；KvManagementUiTest；正式创建菜单的确认链路见下，写许可、回执沿原门禁。 |
| `key`, `keyshow`, `keyedit`, `keyremove`, `kvbulk`, `kvread`, `kvwrite`, `kvdelete` | KV 命名空间 → 键展开、受保护值、批量操作 | 合并；KvManagementUiTest；显式显隐、后台撤销、正式文件流。 |
| `d1empty`, `d1`, `d1create`, `db`, `dbconfig` | 资源 → D1 → 空态/数据库/创建，进入后读详情或核对设置 | 现有；D1ManagementUiTest；正式创建菜单的合成成功和生产授权拒绝见下，不增加演示入口。 |
| `schema`, `columns`, `indexes`, `rows`, `sql`, `sqlpreview`, `sqlresult` | D1 数据浏览/SQL → 保护会话 → 原位结果与显式显示 | 合并；D1ManagementUiTest；任意 SQL 按原确认和私有结果保护，不能转成免确认读。 |
| `dimport`, `dexport`, `restore`, `undelete` | D1 管理动作及其保护与核对表单 | 边界：具备合成适配器功能证据，生产授权/协议证据不足的动作保持原门禁，不能宣布真实云执行。D1ManagementUiTest。 |
| `billing`, `accounts` | 底栏账单历史和账号卡 | 已验；WorkerReleasesUiTest、ScopedBillingScreenTest。 |
| `connection`, `disconnect` | 账号卡 → 本机连接、凭据说明、断开确认 | 连接详情在当前账号卡原位展开；仅展示实际拒绝项与本机保护说明，不显示凭据。已知读取拒绝才经安全补权。WorkerReleasesUiTest。 |
| `update`, `notes`, `download` | 账号 → 设备 → 应用更新、说明、下载/签名 → 系统安装 | 合并；正式入口长说明见下；UpdateIntegrationTest 仅覆盖偏好和安装校验，不证明说明可滚动；未实际下载或安装。 |
| `jobs`, `job`, `jobdone`, `unlock` | 总览更多 → 操作记录，或对应对象任务区 | 原位展开本机回执、安全目标及只读核对；返回先收起。320dp／2× 深色与常规浅色均定点核对顶栏完整标题。手动解除仅在原产品（例如 KV/D1 支持的任务）保留，不提供跨产品万能解除。WorkerReleasesUiTest、JobsExperienceUiTest。 |
| `preview`, `confirm`, `receipt` | 各实际写入/只读核对流程的预览、独立确认、结果 | 合并；相应产品测试。未知/部分成功不能当完成，禁止加泛化假确认页。 |
| `error`, `loading`, `offline` | 各目录、对象与操作读状态/重试 | 合并；ProductLoadingTest、ResourceExperienceUiTest、RefreshFeedbackUiTest。离线保留已观察值，数据未提供不填零。 |
| `pages`, `pageproject`, `pagehistory`, `pagepreview`, `pagedetail`, `analytics`, `period`, `release`, `traffic`, `events`, `rules`, `rule`, `cert`, `tunnel`, `audit` | 画册「后续规划 · 尚未上线」 | 规划，不创建生产入口。 |

仅在原生上存在而画册未单列的反查：账单旧用户来源 → `billing`；Token 浏览器失败与受限 Account ID → `discover` / `error`；R2 对象批量选择/删除 → `objects` / `confirm`；DNS 高级记录类型和过滤 → `dnsform` / `dns`；DNS 文件、R2/KV/D1 选择器各自复用 `systemfile`。边界场景只有当前明确可证的能力，不用测试夹具为产品增加入口。

## 本轮复验看哪里

- 真入口：`WorkerReleasesUiTest` 的 `mainActivityConnectsFromSetupAfterExplicitAccountChoice`、`mainActivityOpensSyncDetailsWithoutInventingUnreadResults`、`mainActivityConnectionDetailsExplainLocalCredentialWithoutExposingIt`、`mainActivityCreateMenuOpensExistingNativeD1Form`、`mainActivityCreateMenuOpensR2AndKvWithoutSending`、`mainActivityOperationsExpandAndCollapseTheCurrentReceipt`、`mainActivityHomeReopensOnlyVisitedObjectsAndReturnsHome`。可加 `-e captureM1 true`；私有内容不在截图中。
- 创建确认（正式 MainActivity、合成传输）：`R2DocumentPickerIntegrationTest#nativeCreateR2FromResourceMenuChecksTargetCancelsAndConfirmsOnce`、`KvManagementUiTest#actualCreateKvFromResourceMenuChecksTargetCancelsAndConfirmsOnce`、`D1ManagementUiTest#nativeCreateD1FromMenuCancelThenConfirmSyntheticCreate`：从资源创建菜单进入各自表单，核对目标，取消不写，最终确认只发送一次。R2／D1 合成结果成功；KV 仅为 API 接纳。`D1ManagementUiTest#nativeCreateD1FromMenuWithProductionGuardAndMissingGrantNeverWrites` 走生产授权检查，缺写许可则失败且不发送写入。
- 更新说明：`WorkerReleasesUiTest#mainActivityUpdateNotesScrollToVisibleTruncationBoundaryAndClose` 从账号 → 设备 → 应用更新检查合成版本，打开说明并滚至正文末行，核对 12000 字符边界、截断提示、关闭及未下载；320dp／2× 深色和常规浅色均通过实现者自测。
- 安全与文件：`ExtendedManagementUiTest#securityShortcutUsesOwnedUniversalSslReadAndReturnsToSecurity` 验证同一作用域和无写入；`R2DocumentPickerIntegrationTest` 的正式 Activity 用例核对真实系统文件选择与原前缀。DNS 扫描／设置、DNSSEC、Zone 清缓存、KV 生命周期、D1 私有结果、更新签名边界有对应功能用例，未逐状态重拍。
- 范围边界：画册的权限、活动、历史来源、下载和安全状态是状态标签，不是新页面或写能力承诺。未逐张截图验收 134 项；尤其类型未提供、D1 部分生产协议、账号级 R2 指标、官方注册和真实云回执不能由合成夹具认定通过。
