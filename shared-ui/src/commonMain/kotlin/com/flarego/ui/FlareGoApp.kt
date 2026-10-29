package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import com.flarego.ui.management.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.application.AppController
import com.flarego.core.application.inventory.*
import com.flarego.core.application.dns.*
import com.flarego.core.application.zones.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.updates.UpdateController
import com.flarego.ui.dns.DnsPage
import com.flarego.ui.zones.ZonePage
import kotlinx.coroutines.launch

private sealed interface DialogState {
    data object Connect : DialogState

    data object Create : DialogState

    data class Edit(
        val domain: Domain,
        val record: DnsRecord?,
        val retainedDraft: DnsDraft? = null,
    ) : DialogState

    data class Confirm(
        val domain: Domain,
        val record: DnsRecord?,
        val draft: DnsDraft?,
        val returnDraft: DnsDraft? = draft,
    ) : DialogState

    data class Result(val title: String, val detail: String) : DialogState

    data class Disconnect(val connection: Connection) : DialogState

    data class Purchase(val name: String, val price: String) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlareGoApp(
    controller: AppController,
    connect: suspend (String, String, String) -> Unit,
    disconnect: (Connection) -> Unit,
    openConsole: (Page) -> Unit,
    sensitiveScreen: (Boolean) -> Unit = {},
    updater: UpdateController? = null,
    connectionDraft: ConnectionDraft = remember { ConnectionDraft() },
    discoverAccounts: (suspend (String, PageRequest) -> CloudPage<AccountSummary>)? = null,
    verifyToken: (suspend (String, TokenKind, String?) -> TokenVerification)? = null,
    reconcileTask: (suspend (String) -> Unit)? = null,
    management: com.flarego.core.application.management.CloudflareManagement? = null,
    panelPorts: com.flarego.ui.management.ManagementPanelPorts? = null,
    panelContext: com.flarego.ui.management.ManagementPanelContext? = null,
    r2Browser: com.flarego.ui.r2.R2Browser? = null,
    kvBrowser: com.flarego.ui.kv.KvBrowser? = null,
    oauthConnection: OAuthConnection? = null,
    d1Browser: com.flarego.ui.d1.D1Browser? = null,
) {
    val state by controller.state.collectAsState()
    val account = state.selected?.let { AccountScope(it.id, it.accountId) }
    val managementZones = management?.zones?.state?.collectAsState()?.value
    // A late publisher from another account must not expose its content or error.
    val zonePageState = managementZones?.takeIf { it.account == account }
        ?: ZonePageState(account = account)
    val managementZone = if (management != null && state.page in setOf(Page.DOMAINS, Page.DNS)) zonePageState.selected else null
    val actualPanelContext = panelContext?.takeIf {
        account!=null && managementZone?.ref==it.scope && it.scope.account==account &&
            it.currentEpoch==controller.operationEpoch
    }
    // Save only navigation choices and an independent live selection stamp, never leases, drafts or plans.
    val routeStamp=actualPanelContext?.let { listOf(it.scope.connectionId,it.scope.account.accountId,it.scope.zoneId,it.currentEpoch.toString(),state.page.name) }
    var savedPanelRoute by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val panelModule=savedPanelRoute?.takeIf { it.size==6 && it.take(5)==routeStamp }?.last()?.let { name -> ManagementPanelModule.entries.firstOrNull { it.name==name } }
    var panelLease by remember(account,managementZone?.ref,state.page,panelContext) { mutableStateOf<ManagementPanelLease?>(null) }
    var zoneTab by rememberSaveable(account,managementZone?.ref) { mutableStateOf(ZoneManagementTab.DETAILS) }
    val activeModule=panelModule?.takeIf { actualPanelContext!=null && panelPorts!=null }
    LaunchedEffect(routeStamp) {
        if(savedPanelRoute!=null && savedPanelRoute!!.take(5)!=routeStamp) savedPanelRoute=null
    }
    val focusManager=LocalFocusManager.current
    fun clearPanel() {
        val module=panelModule;val lease=panelLease
        if(module!=null && lease!=null && panelPorts!=null) {
            panelPorts.onRouteExit(lease)
            releasePanel(panelPorts,module,lease)
        }
        panelLease=null;savedPanelRoute=null
        focusManager.clearFocus()
    }
    val updateState = updater?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var dialog by remember(connectionDraft) {
        mutableStateOf<DialogState?>(if (connectionDraft.isOpen) DialogState.Connect else null)
    }
    var searchOpen by
        remember(state.page, state.selected?.id) {
            mutableStateOf(state.page == Page.BUY && state.selected?.demo == true)
        }
    var query by remember(state.page, state.selected?.id) { mutableStateOf("") }
    var searched by remember(state.page, state.selected?.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(management, account, state.page) {
        // Same independently stamped route after configuration recreation is still the owned selection.
        // All actual account/Zone/epoch/page changes fail the saved stamp and take the normal clearing path.
        if(activeModule!=null && actualPanelContext!=null) return@LaunchedEffect
        management?.let { pages ->
            pages.dns.onIntent(DnsIntent.Clear)
            pages.batch?.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Clear)
            pages.zones.onIntent(ZoneIntent.Clear)
            if (account != null && state.page in setOf(Page.DOMAINS, Page.DNS)) {
                pages.zones.onIntent(ZoneIntent.Load(account))
                if (state.page == Page.DNS) state.domain?.takeIf { it.connectionId == account.connectionId }?.let {
                    pages.zones.onIntent(ZoneIntent.Open(ZoneScope(account, it.id)))
                }
            }
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }
    // OAuth UI contains no credentials. Protect only the manual token form, not its entry page.
    val connectionShowsSecrets = dialog is DialogState.Connect &&
        (oauthConnection == null || connectionDraft.manualToken)
    DisposableEffect(connectionShowsSecrets) {
        sensitiveScreen(connectionShowsSecrets)
        onDispose { sensitiveScreen(false) }
    }
    val navPage =
        when (state.page) {
            Page.DNS,
            Page.BUY -> Page.DOMAINS
            Page.RESOURCE -> Page.RESOURCES
            Page.JOBS -> Page.OVERVIEW
            else -> state.page
        }
    val detail = state.page in setOf(Page.DNS, Page.BUY, Page.RESOURCE, Page.JOBS) || managementZone != null
    val searchEnabled =
        activeModule == null && state.selected != null &&
            !(r2Browser != null && state.page == Page.RESOURCES && state.resourceKind == ResourceKind.R2) &&
            !(kvBrowser != null && state.page == Page.RESOURCES && state.resourceKind == ResourceKind.KV) &&
            !(d1Browser != null && state.page == Page.RESOURCES && state.resourceKind == ResourceKind.D1) &&
            state.page in setOf(Page.DOMAINS, Page.RESOURCES, Page.DNS, Page.BUY, Page.JOBS)
    val dismiss = {
        if (dialog is DialogState.Connect) { oauthConnection?.cancel(); connectionDraft.clear() }
        dialog = null
    }
    val openConnection = {
        connectionDraft.isOpen = true
        dialog = DialogState.Connect
    }

    FlareGoTheme {
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(304.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                null,
                                tint = Forest,
                                modifier = Modifier.size(32.dp),
                            )
                            Text("云账号", style = MaterialTheme.typography.titleLarge)
                        }
                        Muted("切换账号后保留当前功能页")
                        HorizontalDivider()
                        Text("Cloudflare", style = MaterialTheme.typography.titleMedium)
                        state.connections.forEach { connection ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color =
                                    if (connection.id == state.selected?.id)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        keyboard?.hide()
                                        dismiss()
                                        management?.dns?.onIntent(DnsIntent.Clear)
                                        management?.batch?.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Clear)
                                        management?.zones?.onIntent(ZoneIntent.Clear)
                                        clearPanel(); controller.selectConnection(connection)
                                        scope.launch { drawer.close() }
                                    },
                            ) {
                                Row(
                                    Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        connection.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (connection.id == state.selected?.id)
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch { drawer.close() }
                                openConnection()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Add, null)
                            Text("连接 Cloudflare")
                        }
                        HorizontalDivider()
                        Muted("更多云厂商")
                        listOf("AWS", "Google Cloud", "Microsoft Azure", "阿里云", "腾讯云").forEach {
                            provider ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    provider,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Muted("规划中")
                            }
                        }
                        Muted("FlareGo · 本机管理你的云")
                    }
                }
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column(
                        Modifier.statusBarsPadding()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 58.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (detail)
                                IconButton(
                                    onClick = {
                                        keyboard?.hide()
                                        if (activeModule != null) clearPanel()
                                        else if (managementZone != null && account != null && management != null) {
                                            management.dns.onIntent(DnsIntent.Clear)
                                            management.batch?.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Clear)
                                            management.zones.onIntent(ZoneIntent.Clear)
                                            management.zones.onIntent(ZoneIntent.Load(account))
                                        } else controller.back()
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                }
                            IconButton(
                                onClick = {
                                    keyboard?.hide()
                                    scope.launch { drawer.open() }
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Default.Bolt,
                                    "切换云账号",
                                    tint = Lime,
                                    modifier =
                                        Modifier.size(30.dp)
                                            .background(Forest, RoundedCornerShape(8.dp))
                                            .padding(4.dp),
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 7.dp)) {
                                Text(
                                    when (state.page) {
                                        Page.DNS -> state.domain?.name ?: state.page.title
                                        Page.DOMAINS -> managementZone?.name ?: state.page.title
                                        Page.RESOURCE -> state.resource?.name ?: state.page.title
                                        else -> state.page.title
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if(activeModule==null) Text(
                                    state.selected?.let { "Cloudflare · ${it.name}" } ?: "添加服务 · 绑定密钥",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (updateState?.available != null)
                                IconButton(
                                    onClick = { controller.navigate(Page.ACCOUNTS) },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        Icons.Default.SystemUpdate,
                                        "发现应用更新",
                                        tint = Forest,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                            if (searchEnabled)
                                IconButton(
                                    onClick = {
                                        if (searchOpen) {
                                            searchOpen = false
                                            query = ""
                                            searched = false
                                            keyboard?.hide()
                                        } else searchOpen = true
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        if (searchOpen) Icons.Default.Close
                                        else Icons.Default.Search,
                                        if (searchOpen) "取消搜索" else "搜索",
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                            if (state.selected == null) {
                                IconButton(onClick = openConnection) {
                                    Icon(Icons.Default.Add, "连接账号")
                                }
                            } else if(activeModule==null) when (state.page) {
                                Page.DOMAINS ->
                                    IconButton(onClick = {
                                        if (management != null) management.zones.onIntent(ZoneIntent.BeginCreate)
                                        else controller.navigate(Page.BUY)
                                    }) {
                                        Icon(Icons.Default.Add, if (management != null) "创建 Cloudflare Zone" else "购买域名")
                                    }
                                Page.RESOURCES -> if (r2Browser == null || state.resourceKind != ResourceKind.R2)
                                    IconButton(onClick = { dialog = DialogState.Create }) {
                                        Icon(Icons.Default.Add, "创建资源")
                                    }
                                Page.DNS ->
                                    IconButton(
                                        onClick = {
                                            if (management != null) managementZone?.let { management.zones.onIntent(ZoneIntent.Open(it.ref)) }
                                            else state.domain?.let { dialog = DialogState.Edit(it, null) }
                                        }
                                    ) {
                                        Icon(Icons.Default.Add, if (management != null) "刷新 Zone 详情" else "新增 DNS 记录")
                                    }
                                Page.ACCOUNTS ->
                                    IconButton(onClick = openConnection) {
                                        Icon(Icons.Default.Add, "连接账号")
                                    }
                                else ->
                                    IconButton(onClick = { controller.refresh() }) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            "刷新",
                                            modifier = Modifier.size(21.dp),
                                        )
                                    }
                            }
                        }
                        if (searchOpen && activeModule==null)
                            OutlinedTextField(
                                query,
                                { query = it },
                                placeholder = {
                                    Text(
                                        when (state.page) {
                                            Page.DOMAINS -> "搜索域名"
                                            Page.DNS -> "搜索记录名称或内容"
                                            Page.BUY -> "输入想注册的域名"
                                            Page.JOBS -> "搜索操作"
                                            else -> "搜索资源名称"
                                        }
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .focusRequester(focus)
                                        .testTag("search-field"),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions =
                                    KeyboardActions(
                                        onSearch = {
                                            searchOpen = false
                                            searched = true
                                            keyboard?.hide()
                                        }
                                    ),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            searchOpen = false
                                            searched = true
                                            keyboard?.hide()
                                        }
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "提交搜索")
                                    }
                                },
                            )
                        if (state.loading && activeModule==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                        modifier = Modifier.heightIn(min = 68.dp),
                    ) {
                        listOf(
                                Triple(Page.OVERVIEW, Icons.Default.GridView, "overview"),
                                Triple(Page.DOMAINS, Icons.Default.Language, "domains"),
                                Triple(Page.RESOURCES, Icons.Default.Inventory2, "resources"),
                                Triple(Page.BILLING, Icons.Default.CreditCard, "billing"),
                                Triple(Page.ACCOUNTS, Icons.Default.CloudQueue, "accounts"),
                            )
                            .forEach { (page, icon, tag) ->
                                NavigationBarItem(
                                    selected = navPage == page,
                                    onClick = {
                                        keyboard?.hide()
                                        clearPanel()
                                        management?.let { pages ->
                                            pages.dns.onIntent(DnsIntent.Clear)
                                            pages.batch?.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Clear)
                                            pages.zones.onIntent(ZoneIntent.Clear)
                                            if (page == state.page && page == Page.DOMAINS && account != null) pages.zones.onIntent(ZoneIntent.Load(account))
                                        }
                                        controller.navigate(page)
                                    },
                                    icon = { Icon(icon, null, modifier = Modifier.size(21.dp)) },
                                    label = {
                                        Text(
                                            when (page) {
                                                Page.BILLING -> "账单"
                                                Page.RESOURCES -> "资源"
                                                Page.ACCOUNTS -> "账号"
                                                else -> page.title
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    modifier = Modifier.testTag("nav-$tag"),
                                )
                            }
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    state.error?.let { error ->
                        if(activeModule!=null) Text("账号提示：$error",Modifier.padding(horizontal=16.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        else Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Notice(error, controller::refresh)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (state.selected == null && state.page != Page.ACCOUNTS) {
                            ServiceSetupScreen(openConnection)
                        } else if(activeModule!=null && actualPanelContext!=null && panelPorts!=null) {
                            ManagementPanelHost(actualPanelContext,activeModule,panelPorts,
                                onBack={keyboard?.hide();clearPanel()},
                                modifier=Modifier.consumeWindowInsets(padding),onBound={panelLease=it})
                        } else when (state.page) {
                            Page.OVERVIEW ->
                                OverviewScreen(
                                    state,
                                    controller::navigate,
                                    { dialog = DialogState.Create },
                                    controller::refreshAllProducts,
                                )
                            Page.DOMAINS -> if (management != null) {
                                val openPanel:(ManagementPanelModule)->Unit = { module ->
                                    if(actualPanelContext!=null && panelPorts!=null && panelPorts.available(module)) {
                                        keyboard?.hide();clearPanel();savedPanelRoute=ArrayList(routeStamp.orEmpty()+module.name)
                                    }
                                }
                                val dnsContent:@Composable (ZoneDetails)->Unit = { zone ->
                                    val dnsState by management.dns.state.collectAsState()
                                    LaunchedEffect(management,zone.ref) { management.dns.onIntent(DnsIntent.Load(zone.ref)) }
                                    if(dnsState.scope==zone.ref && zone.ref.account==account) DnsPage(dnsState,management.dns::onIntent,batch=management.batch,
                                        managementActions=if(panelPorts!=null) ({ PanelEntries(listOf(ManagementPanelModule.FILES,ManagementPanelModule.SCAN,ManagementPanelModule.DNS_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) }) else null)
                                    else Text("正在加载当前 Zone 的 DNS…",Modifier.padding(16.dp))
                                }
                                if(panelPorts==null) ZonePage(zonePageState,management.zones::onIntent,Modifier.consumeWindowInsets(padding),dnsContent)
                                else ZonePage(zonePageState,management.zones::onIntent,ZoneManagementSlots(
                                    settings={ Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelEntries(listOf(ManagementPanelModule.ZONE_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) } },
                                    security={ Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelEntries(listOf(ManagementPanelModule.DNSSEC),panelPorts,actualPanelContext!=null,openPanel) } },
                                    selectedTab=zoneTab,onTabChanged={zoneTab=it}),Modifier.consumeWindowInsets(padding),dnsContent)
                            } else Column {
                                if (state.productLoading) ProductStatus(state.products[ProductKey.ZONES],controller::refresh,{controller.loadMore(ProductKey.ZONES)})
                                DomainsScreen(state, query, controller::openDomain)
                            }
                            Page.RESOURCES ->
                                ResourcesScreen(state, query, controller::openResource, controller::selectResourceKind, controller::refresh, {controller.loadMore(state.resourceKind.product())}, controller::selectWorkersMode,
                                    r2Content = r2Browser?.let { browser -> ({ com.flarego.ui.r2.R2BrowserHost(browser, account) }) },
                                    kvContent = kvBrowser?.let { browser -> ({ com.flarego.ui.kv.KvBrowserHost(browser, account) }) },
                                    d1Content = d1Browser?.let { browser -> ({ com.flarego.ui.d1.D1BrowserHost(browser, account) }) })
                            Page.DNS -> if (management != null) {
                                val openPanel:(ManagementPanelModule)->Unit = { module ->
                                    if(actualPanelContext!=null && panelPorts!=null && panelPorts.available(module)) {
                                        keyboard?.hide();clearPanel();savedPanelRoute=ArrayList(routeStamp.orEmpty()+module.name)
                                    }
                                }
                                val dnsContent:@Composable (ZoneDetails)->Unit = { zone ->
                                    val dnsState by management.dns.state.collectAsState()
                                    LaunchedEffect(management,zone.ref) { management.dns.onIntent(DnsIntent.Load(zone.ref)) }
                                    if(dnsState.scope==zone.ref && zone.ref.account==account) DnsPage(dnsState,management.dns::onIntent,batch=management.batch,
                                        managementActions=if(panelPorts!=null) ({ PanelEntries(listOf(ManagementPanelModule.FILES,ManagementPanelModule.SCAN,ManagementPanelModule.DNS_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) }) else null)
                                    else Text("正在加载当前 Zone 的 DNS…",Modifier.padding(16.dp))
                                }
                                if(panelPorts==null) ZonePage(zonePageState,management.zones::onIntent,Modifier.consumeWindowInsets(padding),dnsContent)
                                else ZonePage(zonePageState,management.zones::onIntent,ZoneManagementSlots(
                                    settings={ Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelEntries(listOf(ManagementPanelModule.ZONE_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) } },
                                    security={ Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelEntries(listOf(ManagementPanelModule.DNSSEC),panelPorts,actualPanelContext!=null,openPanel) } },
                                    selectedTab=zoneTab,onTabChanged={zoneTab=it}),Modifier.consumeWindowInsets(padding),dnsContent)
                            } else DnsScreen(state, query) {
                                    state.domain?.let { domain ->
                                        dialog = DialogState.Edit(domain, it)
                                    }
                                }
                            Page.RESOURCE -> ResourceScreen(state) { openConsole(Page.RESOURCE) }
                            Page.BILLING -> if (state.productLoading) Column {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { controller.selectBillingSource(BillingSource.ACCOUNT_HISTORY) }) { Text("账号账单") }
                                    TextButton(onClick = { controller.selectBillingSource(BillingSource.USER_HISTORY_LEGACY) }) { Text("用户账单（旧接口）") }
                                }
                                if (state.billingSource == BillingSource.USER_HISTORY_LEGACY) {
                                    BillingScreen(state) { openConsole(Page.BILLING) }
                                } else {
                                    ProductStatus(state.products[ProductKey.BILLING],controller::refresh,{controller.loadMore(ProductKey.BILLING)})
                                    val selected = state.selected
                                    val history = state.products[ProductKey.BILLING]
                                    if (selected != null && history?.observedAtMs != null)
                                        ScopedBillingScreen(selected,history.scope,CloudPage((history.content as? ProductContent.Billing)?.items.orEmpty(),history.next,history.total)) { openConsole(Page.BILLING) }
                                }
                            } else BillingScreen(state) { openConsole(Page.BILLING) }
                            Page.ACCOUNTS ->
                                AccountsScreen(
                                    state,
                                    openConnection,
                                    { dialog = DialogState.Disconnect(it) },
                                    { updater?.let { UpdatePanel(it) } },
                                )
                            Page.JOBS -> JobsScreen(state, query, reconcileTask)
                            Page.BUY ->
                                PurchaseScreen(
                                    query,
                                    searched,
                                    state.selected?.demo == true,
                                    { name, price -> dialog = DialogState.Purchase(name, price) },
                                    { openConsole(Page.BUY) },
                                )
                        }
                    }
                }
            }
        }
        when (val modal = dialog) {
            DialogState.Connect -> ConnectionDialog(dismiss, connect, connectionDraft, discoverAccounts, verifyToken, oauthConnection)
            DialogState.Create ->
                CreateResourceDialog(dismiss) {
                    dialog = null
                    openConsole(Page.RESOURCES)
                }
            is DialogState.Edit ->
                DnsEditor(
                    modal.domain,
                    modal.record,
                    dismiss,
                    { dialog = DialogState.Confirm(modal.domain, modal.record, it) },
                    { draft ->
                        dialog = DialogState.Confirm(modal.domain, modal.record, null, draft)
                    },
                    retainedDraft = modal.retainedDraft,
                )
            is DialogState.Confirm ->
                DnsConfirmation(
                    modal.domain,
                    modal.record,
                    modal.draft,
                    state.selected?.demo == true,
                    { dialog = DialogState.Edit(modal.domain, modal.record, modal.returnDraft) },
                    {
                        if (modal.draft == null) controller.deleteDns(modal.domain, modal.record!!)
                        else controller.saveDns(modal.domain, modal.record?.id, modal.draft)
                    },
                    { op -> dialog = DialogState.Result(op.status.label, op.detail) },
                )
            is DialogState.Result -> ResultDialog(modal.title, modal.detail, dismiss)
            is DialogState.Disconnect ->
                DisconnectDialog(modal.connection, dismiss) {
                    disconnect(modal.connection)
                    dialog = null
                }
            is DialogState.Purchase ->
                PurchaseDialog(modal.name, modal.price, dismiss) {
                    val op = controller.recordDemoPurchase(modal.name)
                    dialog = DialogState.Result(op.status.label, op.detail)
                }
            null -> Unit
        }
    }
}


private fun ManagementPanelPorts.available(module:ManagementPanelModule):Boolean = when(module) {
    ManagementPanelModule.FILES -> files!=null
    ManagementPanelModule.SCAN -> scan!=null
    ManagementPanelModule.DNS_SETTINGS -> dnsSettings!=null
    ManagementPanelModule.DNSSEC -> dnssec!=null
    ManagementPanelModule.ZONE_SETTINGS -> zoneSettings!=null
}

private fun releasePanel(ports:ManagementPanelPorts,module:ManagementPanelModule,lease:ManagementPanelLease) {
    when(module) {
        ManagementPanelModule.FILES -> ports.files?.clear(lease)
        ManagementPanelModule.SCAN -> ports.scan?.clear(lease)
        ManagementPanelModule.DNS_SETTINGS -> ports.dnsSettings?.clear(lease)
        ManagementPanelModule.DNSSEC -> ports.dnssec?.clear(lease)
        ManagementPanelModule.ZONE_SETTINGS -> ports.zoneSettings?.clear(lease)
    }
}

@Composable
private fun PanelEntries(modules:List<ManagementPanelModule>,ports:ManagementPanelPorts,contextReady:Boolean,onOpen:(ManagementPanelModule)->Unit) {
    modules.forEach { module ->
        val available=ports.available(module)
        OutlinedButton(onClick={onOpen(module)},enabled=contextReady && available,
            modifier=Modifier.fillMaxWidth().testTag("management-open-${module.name}")) { Text(module.title) }
        if(!available) Text("${module.title}尚待接入当前账号。")
        else if(!contextReady) Text("请等待当前账号与域名的读取，再进入此功能。")
    }
}
