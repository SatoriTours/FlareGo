package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import com.flarego.ui.management.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.application.AppController
import com.flarego.core.application.inventory.*
import com.flarego.core.application.dns.*
import com.flarego.core.application.connection.ReadFeature
import com.flarego.core.application.connection.ReadTarget
import com.flarego.core.application.zones.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.updates.UpdateController
import com.flarego.ui.dns.DnsPage
import com.flarego.ui.zones.ZonePage
import com.flarego.ui.r2.backWithinWorkspace
import com.flarego.ui.kv.backWithinWorkspace
import com.flarego.ui.d1.backWithinWorkspace
import com.flarego.ui.workers.WorkerReleaseScreen
import com.flarego.ui.workers.toolbarTitle
import kotlinx.coroutines.launch

private sealed interface DialogState {
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

    /** Default add-account flow: verified API Token, never the OAuth handoff. */
    data object TokenConnect : DialogState

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlareGoApp(
    controller: AppController,
    disconnect: (Connection) -> Unit,
    openConsole: (Page) -> Unit,
    sensitiveScreen: (Boolean) -> Unit = {},
    updater: UpdateController? = null,
    connectionDraft: ConnectionDraft = remember { ConnectionDraft() },
    reconcileTask: (suspend (String) -> Unit)? = null,
    management: com.flarego.core.application.management.CloudflareManagement? = null,
    panelPorts: com.flarego.ui.management.ManagementPanelPorts? = null,
    panelContext: com.flarego.ui.management.ManagementPanelContext? = null,
    r2Browser: com.flarego.ui.r2.R2Browser? = null,
    kvBrowser: com.flarego.ui.kv.KvBrowser? = null,
    tokenConnection: TokenConnection? = null,
    d1Browser: com.flarego.ui.d1.D1Browser? = null,
    registrar: com.flarego.core.application.registrar.RegistrarController? = null,
    /** True for a legacy OAuth record: it keeps its row and must be reconfigured with a Token. */
    needsToken: (Connection) -> Boolean = { false },
    upgradeToken: ((Connection, Set<ReadTarget>) -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val account = state.selected?.let { AccountScope(it.id, it.accountId) }
    val workerBrowser = controller.workerReleases
    val workerRelease = workerBrowser?.state?.collectAsState()?.value?.takeIf {
        state.page == Page.RESOURCE && it.worker == state.resource && it.worker.resourceRef?.scope == account
    }
    val compactWorkerName = workerRelease != null &&
        (workerRelease.worker.name.length > 24 || LocalDensity.current.fontScale >= 1.5f)
    val managementDns = management?.dns?.state?.collectAsState()?.value
    var dnsBatchOpen by remember(account, state.page, state.domain?.id) { mutableStateOf(false) }
    var discardEdit by remember { mutableStateOf(false) }
    val managementZones = management?.zones?.state?.collectAsState()?.value
    // A late publisher from another account must not expose its content or error.
    val zonePageState = managementZones?.takeIf { it.account == account }
        ?.let { zones ->
            if (state.page == Page.DNS && zones.selected?.ref?.zoneId != state.domain?.id)
                zones.copy(selected = null)
            else zones
        } ?: ZonePageState(account = account)
    val managementZone = if (management != null && state.page in setOf(Page.DOMAINS, Page.DNS)) zonePageState.selected else null
    val actualPanelContext = panelContext?.takeIf {
        account!=null && managementZone?.ref==it.scope && it.scope.account==account &&
            it.currentEpoch==controller.operationEpoch
    }
    // Save only navigation choices and an independent live selection stamp, never leases, drafts or plans.
    val routeStamp=actualPanelContext?.let { listOf(it.scope.connectionId,it.scope.account.accountId,it.scope.zoneId,it.currentEpoch.toString(),state.page.name) }
    var savedPanelRoute by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val ownedPanelRoute = savedPanelRoute?.takeIf { it.size == 6 && it.take(5) == routeStamp }?.last()
    val panelModule = ManagementPanelModule.entries.firstOrNull { it.name == ownedPanelRoute?.substringBefore(':') }
    var panelLease by remember(account,managementZone?.ref,state.page,panelContext) { mutableStateOf<ManagementPanelLease?>(null) }
    var zoneTab by rememberSaveable(account,state.domain?.id ?: managementZone?.ref?.zoneId) {
        mutableStateOf(if (state.page == Page.DNS) ZoneManagementTab.DNS else ZoneManagementTab.DETAILS)
    }
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
    val pageStates = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var showAllResources by remember(account) { mutableStateOf(true) }
    var billingSourceMenu by remember(account, state.page) { mutableStateOf(false) }
    var homeMenu by remember(account, state.page) { mutableStateOf(false) }
    var syncDetails by remember(account, state.page) { mutableStateOf(false) }
    val feedback = remember { SnackbarHostState() }
    val jobDetails = remember(account, state.page) { mutableStateOf<String?>(null) }
    var homeBucket by rememberSaveable(account?.connectionId, account?.accountId) { mutableStateOf<String?>(null) }
    val r2Selected = state.page == Page.RESOURCES && state.resourceKind == ResourceKind.R2
    val r2State = r2Browser?.state?.collectAsState()?.value?.takeIf { r2Selected && it.context?.account == account }
    val r2Config = r2Browser?.configuration?.state?.collectAsState()?.value?.takeIf { r2State != null }
    val r2C = r2Browser?.c?.state?.collectAsState()?.value?.takeIf { r2State != null }
    val kvState = kvBrowser?.state?.collectAsState()?.value?.takeIf {
        state.page == Page.RESOURCES && state.resourceKind == ResourceKind.KV && it.context?.account == account
    }
    val d1State = d1Browser?.state?.collectAsState()?.value?.takeIf {
        state.page == Page.RESOURCES && state.resourceKind == ResourceKind.D1 && it.context?.account == account
    }
    var createTarget by remember(account, state.page) { mutableStateOf<ResourceKind?>(null) }
    LaunchedEffect(createTarget, d1State?.context, kvState?.context, r2State?.context) {
        when (createTarget) {
            ResourceKind.D1 -> if (d1State != null) {
                createTarget = null
                d1Browser?.begin(com.flarego.ui.d1.D1Editor.CREATE)
            }
            ResourceKind.KV -> if (kvState != null) {
                createTarget = null
                kvBrowser?.begin(com.flarego.ui.kv.KvEditor.CREATE)
            }
            else -> Unit
        }
    }
    val r2Object = r2State?.objects
    LaunchedEffect(r2Selected) { if (!r2Selected) homeBucket = null }
    LaunchedEffect(r2State?.context, r2Object?.bucket, account) {
        if (account != null && r2Object != null) controller.recordBucketOpened(account, r2Object.bucket)
    }
    val bucketRequested = homeBucket?.takeIf { r2Selected }
    LaunchedEffect(r2State?.context, bucketRequested, account) {
        if (r2State?.context != null && bucketRequested != null && account != null) {
            r2Browser?.openVisitedBucket(account, bucketRequested)
        }
    }
    var dialog by remember(connectionDraft) {
        mutableStateOf<DialogState?>(
            if (connectionDraft.isOpen) DialogState.TokenConnect else null,
        )
    }
    // The Registrar page owns its own search field; rebinding also drops any stale result.
    LaunchedEffect(registrar, account) { registrar?.bind(account) }
    val searchKey = state.page to state.resourceKind.takeIf { state.page == Page.RESOURCES }
    val searchFlags = remember(account) { mutableMapOf<Pair<Page, ResourceKind?>, MutableState<Boolean>>() }
    val searchQueries = remember(account) { mutableMapOf<Pair<Page, ResourceKind?>, MutableState<String>>() }
    var searchOpen by searchFlags.getOrPut(searchKey) { mutableStateOf(false) }
    var query by searchQueries.getOrPut(searchKey) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    fun openManagementPanel(module: ManagementPanelModule, universalSsl: Boolean = false) {
        if (actualPanelContext == null || panelPorts?.available(module) != true) return
        keyboard?.hide()
        clearPanel()
        savedPanelRoute = ArrayList(routeStamp.orEmpty() + (module.name + if (universalSsl) ":ssl" else ""))
    }

    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(management, account, state.page, state.domain?.id, controller.operationEpoch) {
        // Same independently stamped route after configuration recreation is still the owned selection.
        // All actual account/Zone/epoch/page changes fail the saved stamp and take the normal clearing path.
        if(activeModule!=null && actualPanelContext!=null) return@LaunchedEffect
        management?.let { pages ->
            pages.dns.onIntent(DnsIntent.Clear)
            pages.batch?.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Clear)
            if (pages.zones.state.value.account != account) pages.zones.onIntent(ZoneIntent.Clear)
            else pages.zones.onIntent(ZoneIntent.BackToList)
            if (account != null && state.page == Page.DOMAINS) pages.zones.onIntent(ZoneIntent.Ensure(account))
            if (account != null && state.page == Page.DNS) {
                if (pages.zones.state.value.account != account) pages.zones.onIntent(ZoneIntent.Ensure(account))
                state.domain?.takeIf { it.connectionId == account.connectionId }?.let {
                    pages.zones.onIntent(ZoneIntent.Open(ZoneScope(account, it.id)))
                }
            }
        }
    }
    // The connection flow never displays credentials. Other sensitive editors own their protection.
    DisposableEffect(Unit) {
        sensitiveScreen(false)
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
    val zoneEditing = management != null && state.page in setOf(Page.DOMAINS, Page.DNS) &&
        (zonePageState.editorMode != null || zonePageState.preparedChange != null)
    val nestedResource = (r2Selected && createTarget == ResourceKind.R2) || r2Object != null || bucketRequested != null || r2Config?.open == true || r2C?.open == true ||
        r2State?.preview != null || r2State?.buckets?.pendingDeleteName != null || r2State?.buckets?.editingBucketName != null ||
        kvState?.namespace != null || kvState?.editor != null || d1State?.database != null || d1State?.editor != null
    val detail = state.page in setOf(Page.DNS, Page.BUY, Page.RESOURCE, Page.JOBS) || managementZone != null || zoneEditing || nestedResource
    val designRoot = !detail && state.selected != null && state.page in setOf(Page.DOMAINS, Page.RESOURCES, Page.BILLING)
    val searchQuery = query.trim()
    val searchEnabled =
        activeModule == null && state.selected != null &&
            !(management != null && (state.page == Page.DNS || (state.page == Page.DOMAINS &&
                (managementZone != null || zonePageState.editorMode != null || zonePageState.preparedChange != null)))) &&
            !nestedResource &&
            state.page in setOf(Page.DOMAINS, Page.RESOURCES, Page.DNS, Page.JOBS)
    LaunchedEffect(searchOpen, searchEnabled) {
        if (searchOpen && searchEnabled) focus.requestFocus()
    }
    fun closeSearch() {
        searchOpen = false
        query = ""
        if (!d1State?.filter.isNullOrEmpty()) d1Browser?.filterName("")
        keyboard?.hide()
        focusManager.clearFocus()
    }
    fun navigateBack() {
        keyboard?.hide()
        focusManager.clearFocus()
        if (r2Selected && r2Browser != null && (r2State != null || bucketRequested != null)) {
            val leaveBucket = {
                homeBucket = null
                controller.navigate(Page.OVERVIEW)
            }
            if (r2State != null && r2Browser.backWithinWorkspace(leaveBucket.takeIf { bucketRequested != null })) return
            if (bucketRequested != null) { leaveBucket(); return }
            if (createTarget == ResourceKind.R2) { createTarget = null; return }
        }
        if (kvState != null && kvBrowser?.backWithinWorkspace() == true) return
        if (d1State != null && d1Browser?.backWithinWorkspace() == true) return
        if (state.page == Page.JOBS && jobDetails.value != null) jobDetails.value = null
        else if (activeModule != null) clearPanel()
        else if (dnsBatchOpen) {
            management?.batch?.onIntent(DnsBatchIntent.Cancel)
            dnsBatchOpen = false
        }
        else if (managementDns != null && managementDns.scope?.account == account && managementDns.scope == managementZone?.ref &&
            (state.page == Page.DNS || zoneTab == ZoneManagementTab.DNS) && management != null &&
            (managementDns.preparedChange != null || managementDns.mode != null || managementDns.detail != null)) {
            when {
                managementDns.preparedChange != null -> management.dns.onIntent(DnsIntent.BackToDraft)
                managementDns.mode != null -> discardEdit = true
                else -> management.dns.onIntent(DnsIntent.CloseDetail)
            }
        }
        else if (zoneEditing && management != null) {
            if (!zonePageState.loading) {
                if (zonePageState.preparedChange != null) management.zones.onIntent(ZoneIntent.BackToDraft)
                else discardEdit = true
            }
        }
        else if (state.page == Page.DOMAINS && managementZone != null && account != null && management != null) {
            management.dns.onIntent(DnsIntent.Clear)
            management.batch?.onIntent(DnsBatchIntent.Clear)
            management.zones.onIntent(ZoneIntent.BackToList)
        } else controller.back()
    }
    AppBackHandler(enabled = dialog == null && !discardEdit && (drawer.isOpen || detail || activeModule != null || state.page != Page.OVERVIEW || (searchOpen && searchEnabled))) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else if (state.page == Page.JOBS && jobDetails.value != null) navigateBack()
        else if (searchOpen && searchEnabled) closeSearch()
        else navigateBack()
    }
    val dismiss = {
        if (dialog is DialogState.TokenConnect) { tokenConnection?.cancel(); connectionDraft.clear() }
        dialog = null
    }
    val openConnection = {
        // API Token is the only supported connection flow.
        if (tokenConnection != null) {
            connectionDraft.isOpen = true
            dialog = DialogState.TokenConnect
        }
    }

    val selectedForUpgrade = state.selected?.takeUnless { it.demo || needsToken(it) }
    val openUpgrade: ((Set<ReadTarget>) -> Unit)? = if (selectedForUpgrade != null && upgradeToken != null && tokenConnection != null) {
        { targets ->
            upgradeToken(selectedForUpgrade, targets)
            openConnection()
        }
    } else null

    FlareGoTheme {
        if(discardEdit) AlertDialog(
            onDismissRequest = { discardEdit = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后将丢弃当前草稿，已保存的云端数据不会改变。") },
            confirmButton = { TextButton(onClick = {
                discardEdit = false
                if(zoneEditing) management?.zones?.onIntent(ZoneIntent.Cancel)
                else management?.dns?.onIntent(DnsIntent.Cancel)
            }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { discardEdit = false }) { Text("继续编辑") } },
        )
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
                                tint = MaterialTheme.colorScheme.primary,
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
                containerColor = if (designRoot) directoryBackground() else MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                snackbarHost = { SnackbarHost(feedback) },
                topBar = {
                    val jobsToolbar = state.page == Page.JOBS
                    val stackIdentity = (designRoot || jobsToolbar) && LocalDensity.current.fontScale >= 1.5f
                    val identity: @Composable () -> Unit = {
                        val canSwitch = state.connections.size > 1
                        val accentIdentity = canSwitch && state.page == Page.OVERVIEW
                        val identityColor = if (accentIdentity) homeAccentContentColor() else MaterialTheme.colorScheme.onSurfaceVariant
                        if (canSwitch || (state.selected != null && state.page != Page.ACCOUNTS)) Surface(
                            shape = CircleShape,
                            color = if (accentIdentity) homeAccentColor() else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .heightIn(min = if (canSwitch) 48.dp else 32.dp)
                                .then(if (canSwitch) Modifier.semantics { contentDescription = "切换云账号" }
                                    .clickable { keyboard?.hide(); scope.launch { drawer.open() } } else Modifier),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Cloud,
                                    null,
                                    modifier = Modifier.size(14.dp),
                                    tint = identityColor,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    state.selected?.name ?: "云账号",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = identityColor,
                                    modifier = Modifier.widthIn(max = if (stackIdentity) 200.dp else 104.dp),
                                )
                                if (canSwitch) {
                                    Spacer(Modifier.width(2.dp))
                                    Icon(Icons.Default.ExpandMore, null, Modifier.size(14.dp), tint = identityColor)
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.background(if (state.page == Page.OVERVIEW || designRoot) homeHeaderColor() else MaterialTheme.colorScheme.background)
                            .statusBarsPadding()
                    ) {
                        if (stackIdentity) {
                            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp),
                                contentAlignment = Alignment.CenterEnd) {
                                identity()
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (detail)
                                IconButton(
                                    onClick = ::navigateBack,
                                    modifier = Modifier.size(if (workerRelease != null || jobsToolbar) 48.dp else 44.dp),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", modifier = Modifier.size(20.dp))
                                }
                            Text(
                                when (state.page) {
                                    Page.DNS -> state.domain?.name ?: state.page.title
                                    Page.DOMAINS -> managementZone?.name ?: state.page.title
                                    Page.RESOURCE -> workerRelease?.toolbarTitle(compactWorkerName) ?: state.resource?.name ?: state.page.title
                                    Page.RESOURCES -> r2Object?.bucket ?: bucketRequested ?: kvState?.namespace?.title ?: d1State?.database?.name ?: "资源"
                                    Page.BILLING -> "账单"
                                    Page.ACCOUNTS -> "账号"
                                    else -> state.page.title
                                },
                                modifier = Modifier.weight(1f).padding(start = if (detail) 0.dp else 20.dp)
                                    .then(when {
                                        managementZone != null -> Modifier.testTag("zone-detail-name")
                                        designRoot -> Modifier.testTag("directory-page-title")
                                        else -> Modifier
                                    }),
                                style = when {
                                    detail -> MaterialTheme.typography.titleMedium
                                    state.page == Page.OVERVIEW || designRoot -> MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)
                                    else -> MaterialTheme.typography.headlineSmall
                                },
                                maxLines = if (workerRelease != null || jobsToolbar) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!stackIdentity) identity()
                            if (searchEnabled)
                                IconButton(
                                    onClick = {
                                        if (searchOpen) {
                                            closeSearch()
                                        } else searchOpen = true
                                    },
                                    modifier = Modifier.size(if (jobsToolbar) 48.dp else 44.dp),
                                ) {
                                    Icon(
                                        if (searchOpen) Icons.Default.Close
                                        else Icons.Default.Search,
                                        if (searchOpen) "取消搜索" else "搜索",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            if (state.selected == null) {
                                IconButton(onClick = openConnection, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Add, "连接账号", modifier = Modifier.size(20.dp))
                                }
                            } else if(activeModule==null) when (state.page) {
                                Page.DOMAINS ->
                                    IconButton(onClick = {
                                        if (management != null) management.zones.onIntent(ZoneIntent.BeginCreate)
                                        else controller.navigate(Page.BUY)
                                    }, modifier = Modifier.size(44.dp).testTag("zone-create")) {
                                        Icon(Icons.Default.Add, if (management != null) "创建 Cloudflare Zone" else "购买域名", modifier = Modifier.size(20.dp))
                                    }
                                Page.RESOURCES -> if (!nestedResource && state.resourceKind.product() in state.supportedProducts)
                                    IconButton(onClick = { dialog = DialogState.Create }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Default.Add, "创建资源", modifier = Modifier.size(20.dp))
                                    }
                                Page.DNS ->
                                    IconButton(
                                        onClick = {
                                            if (management != null) managementZone?.let { management.zones.onIntent(ZoneIntent.Open(it.ref)) }
                                            else state.domain?.let { dialog = DialogState.Edit(it, null) }
                                        }, modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(Icons.Default.Add, if (management != null) "刷新 Zone 详情" else "新增 DNS 记录", modifier = Modifier.size(20.dp))
                                    }
                                Page.OVERVIEW -> Box(Modifier.padding(start = 8.dp)) {
                                    FilledIconButton(onClick = { homeMenu = true }, modifier = Modifier.size(48.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )) { Icon(Icons.Default.MoreHoriz, "更多") }
                                    DropdownMenu(expanded = homeMenu, onDismissRequest = { homeMenu = false }) {
                                        DropdownMenuItem(text = { Text("刷新全部产品") }, enabled = !state.loading,
                                            modifier = Modifier.testTag("overview-refresh-all"),
                                            onClick = { homeMenu = false; controller.refreshAllProducts() })
                                        DropdownMenuItem(text = { Text("同步详情") },
                                            modifier = Modifier.testTag("overview-sync-details"),
                                            onClick = { homeMenu = false; syncDetails = true })
                                        DropdownMenuItem(text = { Text("操作记录") },
                                            onClick = { homeMenu = false; controller.navigate(Page.JOBS) })
                                    }
                                }
                                Page.ACCOUNTS ->
                                    IconButton(onClick = openConnection, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Default.Add, "连接账号", modifier = Modifier.size(20.dp))
                                    }
                                Page.BILLING -> {
                                    val canRead = ProductKey.BILLING in state.supportedProducts
                                    IconButton(onClick = controller::refresh, enabled = canRead && !state.loading,
                                        modifier = Modifier.size(48.dp).testTag("toolbar-refresh")) {
                                        Icon(Icons.Default.Refresh, "刷新", Modifier.size(20.dp))
                                    }
                                    if (state.productLoading && canRead) Box {
                                        IconButton(onClick = { billingSourceMenu = true }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.MoreHoriz, "账单来源")
                                        }
                                        DropdownMenu(billingSourceMenu, onDismissRequest = { billingSourceMenu = false }) {
                                            listOf(BillingSource.ACCOUNT_HISTORY to "账号账单",
                                                BillingSource.USER_HISTORY_LEGACY to "用户账单（旧接口）").forEach { (source, label) ->
                                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                                    billingSourceMenu = false
                                                    controller.selectBillingSource(source)
                                                })
                                            }
                                        }
                                    }
                                }
                                Page.RESOURCE -> if (workerRelease == null) {
                                    IconButton(onClick = { controller.refresh() }, enabled = !state.loading,
                                        modifier = Modifier.size(44.dp).testTag("toolbar-refresh")) {
                                        Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(20.dp))
                                    }
                                }
                                // The Registrar page owns its own search; a product refresh has no
                                // meaning here, so only back navigation and the account chip remain.
                                Page.BUY -> Unit
                                else ->
                                    IconButton(onClick = { controller.refresh() }, enabled = !state.loading && (state.page != Page.BILLING || ProductKey.BILLING in state.supportedProducts), modifier = Modifier.size(44.dp).testTag("toolbar-refresh")) {
                                        if (state.loading) CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp).semantics { contentDescription = "正在刷新" },
                                            strokeWidth = 2.dp,
                                        ) else Icon(
                                            Icons.Default.Refresh,
                                            "刷新",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                            }
                            if (updateState?.available != null)
                                IconButton(
                                    onClick = { controller.navigate(Page.ACCOUNTS) },
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Icon(
                                        Icons.Default.SystemUpdate,
                                        "发现应用更新",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                        }
                        val currentObservedAtMs = when (state.page) {
                            Page.DOMAINS -> if (management != null) zonePageState.observedAtMs else state.products[ProductKey.ZONES]?.observedAtMs
                            Page.RESOURCES -> state.products[state.resourceKind.product()]?.observedAtMs
                            Page.BILLING -> state.products[ProductKey.BILLING]?.observedAtMs
                            else -> null
                        }
                        val isCurrentFromCache = when (state.page) {
                            Page.DOMAINS -> if (management != null) zonePageState.fromCache else state.products[ProductKey.ZONES]?.fromCache == true
                            Page.RESOURCES -> state.products[state.resourceKind.product()]?.fromCache == true
                            Page.BILLING -> state.products[ProductKey.BILLING]?.fromCache == true
                            else -> false
                        }
                        val isCurrentPageLoading = when (state.page) {
                            Page.DOMAINS -> if (management != null) zonePageState.loading else state.products[ProductKey.ZONES]?.loading == true
                            Page.RESOURCES -> state.products[state.resourceKind.product()]?.loading == true
                            Page.BILLING -> state.products[ProductKey.BILLING]?.loading == true
                            else -> state.loading
                        }
                        if (!detail && state.selected != null &&
                            state.page in setOf(Page.DOMAINS, Page.RESOURCES, Page.BILLING) &&
                            !(state.page == Page.RESOURCES && (r2State != null || kvState != null || d1State != null))) {
                            ReadStatus(currentObservedAtMs, isCurrentFromCache, isCurrentPageLoading)
                        }
                        if (searchOpen && searchEnabled)
                            OutlinedTextField(
                                d1State?.filter ?: query,
                                { if (d1State != null) d1Browser?.filterName(it) else query = it },
                                placeholder = {
                                    Text(
                                        when (state.page) {
                                            Page.DOMAINS -> "搜索域名"
                                            Page.DNS -> "搜索记录名称或内容"
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
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                        }
                                    ),
                                trailingIcon = {
                                    Row {
                                        if ((d1State?.filter ?: query).isNotEmpty()) IconButton(onClick = {
                                            if (d1State != null) d1Browser?.filterName("") else query = ""
                                            focus.requestFocus()
                                        }) { Icon(Icons.Default.Close, "清空搜索") }
                                    }
                                },
                            )
                        if (state.loading && activeModule==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                },
                bottomBar = {
                    AppNavigation(navPage) { page ->
                        keyboard?.hide()
                        clearPanel()
                        management?.let { pages ->
                            pages.dns.onIntent(DnsIntent.Clear)
                            pages.batch?.onIntent(DnsBatchIntent.Clear)
                            pages.zones.onIntent(ZoneIntent.BackToList)
                            if (page == state.page && page == Page.DOMAINS && account != null) pages.zones.onIntent(ZoneIntent.Ensure(account))
                        }
                        controller.navigate(page)
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    state.error?.let { error ->
                        if(activeModule!=null) Text("账号提示：$error",Modifier.padding(horizontal=16.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
                        else Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Notice(error, controller::refresh)
                        }
                    }
                    val productOnPage = when (state.page) {
                        Page.DOMAINS, Page.DNS -> ProductKey.ZONES
                        Page.RESOURCES -> state.resourceKind.product()
                        else -> null
                    }
                    if (activeModule == null && productOnPage != null && openUpgrade != null &&
                        (state.products[productOnPage]?.error?.code == CloudFailureCode.PERMISSION_REQUIRED ||
                            productOnPage == ProductKey.ZONES && zonePageState.readPermissionDenied)
                    ) {
                        val target = productReadTarget(productOnPage, state.workersMode)
                        TextButton(onClick = { openUpgrade(setOf(target)) }, Modifier.testTag("product-permission-upgrade")) {
                            Text("申请补充${target.feature.label}读取权限")
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        val pageKey=listOf(state.selected?.id.orEmpty(),state.selected?.accountId.orEmpty(),state.page.name).joinToString { "${it.length}:$it" }
                        pageStates.SaveableStateProvider(pageKey) {
                        if (state.selected == null && state.page != Page.ACCOUNTS) {
                            ServiceSetupScreen(openConnection)
                        } else if(activeModule!=null && actualPanelContext!=null && panelPorts!=null) {
                            ManagementPanelHost(actualPanelContext,activeModule,panelPorts,
                                onBack={keyboard?.hide();clearPanel()},
                                modifier=Modifier.consumeWindowInsets(padding),onBound={panelLease=it},
                                onReadUpgrade=openUpgrade?.let { upgrade -> { target -> upgrade(setOf(target)) } },
                                openUniversalSsl = ownedPanelRoute?.endsWith(":ssl") == true)
                        } else when (state.page) {
                            Page.OVERVIEW ->
                                OverviewScreen(
                                    state,
                                    controller::navigate,
                                    { dialog = DialogState.Create },
                                    controller::refreshAllProducts,
                                    openResource = { kind -> showAllResources = false; controller.selectResourceKind(kind); controller.navigate(Page.RESOURCES) },
                                    upgrade = openUpgrade,
                                    openRecent = { visit ->
                                        val resource = (visit as? RecentVisit.Resource)?.resource
                                        if (resource?.kind == ResourceKind.R2 && r2Browser != null && visit in state.recent) {
                                            homeBucket = resource.name
                                            controller.selectResourceKind(ResourceKind.R2)
                                            controller.navigate(Page.RESOURCES)
                                        } else controller.openRecent(visit, managedZones = management != null)
                                    },
                                )
                            Page.DOMAINS -> if (management != null) {
                                val openPanel: (ManagementPanelModule) -> Unit = { openManagementPanel(it) }
                                val dnsContent:@Composable (ZoneDetails)->Unit = { zone ->
                                    val dnsState by management.dns.state.collectAsState()
                                    LaunchedEffect(management,zone.ref) { management.dns.onIntent(DnsIntent.Ensure(zone.ref)) }
                                    if(dnsState.scope==zone.ref && zone.ref.account==account) Column {
                                        if (dnsState.readPermissionDenied && openUpgrade != null) {
                                            TextButton(onClick = { openUpgrade(setOf(ReadTarget(ReadFeature.DNS, zone.ref.zoneId))) },
                                                Modifier.testTag("dns-permission-upgrade")) { Text("申请补充 DNS 读取权限") }
                                        }
                                        DnsPage(dnsState,management.dns::onIntent,batch=management.batch,
                                            managementActions=if(panelPorts!=null) ({ PanelEntries(listOf(ManagementPanelModule.FILES,ManagementPanelModule.SCAN,ManagementPanelModule.DNS_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) }) else null,
                                            externalBatchOpen = dnsBatchOpen, onBatchOpen = { dnsBatchOpen = it })
                                    } else Text("正在加载当前 Zone 的 DNS…",Modifier.padding(16.dp))
                                }
                                // A Zone list is not a registrar catalogue; this is the only entry to the
                                // separate read-only search page, and it never implies a purchase.
                                val openRegistrar = { controller.navigate(Page.BUY) }
                                val onZoneIntent: (ZoneIntent) -> Unit = { intent ->
                                    if (intent is ZoneIntent.Open) controller.openManagedZone(intent.scope, zonePageState)
                                    else management.zones.onIntent(intent)
                                }
                                if(panelPorts==null) ZonePage(zonePageState,onZoneIntent,Modifier.consumeWindowInsets(padding),searchQuery,dnsContent,onRegisterDomain=openRegistrar)
                                else ZonePage(zonePageState,onZoneIntent,ZoneManagementSlots(
                                    settings={ Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelEntries(listOf(ManagementPanelModule.ZONE_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) } },
                                    security = {
                                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PanelEntries(listOf(ManagementPanelModule.DNSSEC), panelPorts, actualPanelContext != null, openPanel)
                                            OutlinedButton(
                                                onClick = { openManagementPanel(ManagementPanelModule.ZONE_SETTINGS, universalSsl = true) },
                                                enabled = actualPanelContext != null && panelPorts.zoneSettings != null,
                                                modifier = Modifier.fillMaxWidth().testTag("management-open-UNIVERSAL_SSL"),
                                            ) { Text("Universal SSL") }
                                        }
                                    },
                                    selectedTab=zoneTab,onTabChanged={zoneTab=it}),Modifier.consumeWindowInsets(padding),searchQuery,dnsContent,onRegisterDomain=openRegistrar)
                            } else Column {
                                if (state.productLoading) Box(Modifier.padding(horizontal = 20.dp)) {
                                    DirectoryReadFeedback(state.products[ProductKey.ZONES], controller::refresh, { controller.loadMore(ProductKey.ZONES) })
                                }
                                DomainsScreen(state, searchQuery, controller::openDomain, onRegisterDomain = { controller.navigate(Page.BUY) })
                            }
                            Page.RESOURCES ->
                                ResourcesScreen(state, searchQuery, controller::openResource,
                                    { kind -> showAllResources = false; controller.selectResourceKind(kind) }, controller::refresh, {controller.loadMore(state.resourceKind.product())}, controller::selectWorkersMode,
                                    r2Content = r2Browser?.let { browser -> ({
                                        com.flarego.ui.r2.R2BrowserHost(browser, account, drawer.isClosed && dialog == null,
                                            ::navigateBack, searchQuery, openingRecent = bucketRequested != null,
                                            showBucketCreate = createTarget == ResourceKind.R2)
                                    }) },
                                    kvContent = kvBrowser?.let { browser -> ({
                                        com.flarego.ui.kv.KvBrowserHost(browser, account, drawer.isClosed && dialog == null, searchQuery)
                                    }) },
                                    d1Content = d1Browser?.let { browser -> ({
                                        com.flarego.ui.d1.D1BrowserHost(browser, account, drawer.isClosed && dialog == null, showDirectorySearch = false)
                                    }) },
                                    showDirectoryTabs = !nestedResource, showAllResources = showAllResources,
                                    selectAllResources = { showAllResources = true },
                                )
                            Page.DNS -> if (management != null && managementZone == null) {
                                val domain = state.domain
                                val target = if (account != null && domain?.connectionId == account.connectionId) ZoneScope(account, domain.id) else null
                                val error = zonePageState.error
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    if (zonePageState.loading || error == null) {
                                        Text("正在读取域名与 DNS…")
                                    } else {
                                        Notice(error)
                                        if (target != null) TextButton(onClick = { management.zones.onIntent(ZoneIntent.Open(target)) }) {
                                            Text("重试")
                                        }
                                    }
                                }
                            } else if (management != null) {
                                val openPanel: (ManagementPanelModule) -> Unit = { openManagementPanel(it) }
                                val dnsContent:@Composable (ZoneDetails)->Unit = { zone ->
                                    val dnsState by management.dns.state.collectAsState()
                                    LaunchedEffect(management,zone.ref) { management.dns.onIntent(DnsIntent.Ensure(zone.ref)) }
                                    if(dnsState.scope==zone.ref && zone.ref.account==account) Column {
                                        if (dnsState.readPermissionDenied && openUpgrade != null) {
                                            TextButton(onClick = { openUpgrade(setOf(ReadTarget(ReadFeature.DNS, zone.ref.zoneId))) },
                                                Modifier.testTag("dns-permission-upgrade")) { Text("申请补充 DNS 读取权限") }
                                        }
                                        DnsPage(dnsState,management.dns::onIntent,batch=management.batch,
                                            managementActions=if(panelPorts!=null) ({ PanelEntries(listOf(ManagementPanelModule.FILES,ManagementPanelModule.SCAN,ManagementPanelModule.DNS_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) }) else null,
                                            externalBatchOpen = dnsBatchOpen, onBatchOpen = { dnsBatchOpen = it })
                                    } else Text("正在加载当前 Zone 的 DNS…",Modifier.padding(16.dp))
                                }
                                if(panelPorts==null) ZonePage(zonePageState,management.zones::onIntent,
                                    Modifier.consumeWindowInsets(padding),searchQuery,dnsContent, initialDns = true)
                                else ZonePage(zonePageState,management.zones::onIntent,ZoneManagementSlots(
                                    settings={ Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PanelEntries(listOf(ManagementPanelModule.ZONE_SETTINGS),panelPorts,actualPanelContext!=null,openPanel) } },
                                    security = {
                                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PanelEntries(listOf(ManagementPanelModule.DNSSEC), panelPorts, actualPanelContext != null, openPanel)
                                            OutlinedButton(
                                                onClick = { openManagementPanel(ManagementPanelModule.ZONE_SETTINGS, universalSsl = true) },
                                                enabled = actualPanelContext != null && panelPorts.zoneSettings != null,
                                                modifier = Modifier.fillMaxWidth().testTag("management-open-UNIVERSAL_SSL"),
                                            ) { Text("Universal SSL") }
                                        }
                                    },
                                    selectedTab=zoneTab,onTabChanged={zoneTab=it}),Modifier.consumeWindowInsets(padding),searchQuery,dnsContent)
                            } else DnsScreen(state, searchQuery) {
                                    state.domain?.let { domain ->
                                        dialog = DialogState.Edit(domain, it)
                                    }
                                }
                            Page.RESOURCE -> if (workerRelease != null && workerBrowser != null) {
                                val workerPages = rememberSaveableStateHolder()
                                workerPages.SaveableStateProvider(workerRelease.worker.id) {
                                    WorkerReleaseScreen(workerRelease, workerBrowser, compactWorkerName)
                                }
                            } else ResourceScreen(state) { openConsole(Page.RESOURCE) }
                            Page.BILLING -> if (ProductKey.BILLING !in state.supportedProducts ||
                                state.products[ProductKey.BILLING]?.error?.code in
                                setOf(CloudFailureCode.PERMISSION_REQUIRED, CloudFailureCode.CREDENTIAL_EXPIRED)
                            ) {
                                // Two distinct causes share one card: the product was never granted, or
                                // this authorization (e.g. the base-scope fallback) did not include
                                // billing.read. The provider error stays visible, never swallowed.
                                val billingError = state.products[ProductKey.BILLING]?.error
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                                ) {
                                    DirectoryHeading("账单历史", Modifier.padding(bottom = 8.dp))
                                    DirectoryGroup(Modifier.testTag("billing-permission-card")) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            // An empty product set means this row is a legacy OAuth record:
                                            // OAuth is retired, so nothing is requested for it at all.
                                            val legacyAuth = state.supportedProducts.isEmpty()
                                            Text(
                                                if (legacyAuth) "该连接需要配置 API Token" else "无法读取账单历史",
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            if (billingError != null) {
                                                // A real provider refusal is reported as-is; only then does
                                                // reconnecting make sense.
                                                if (billingError.code == CloudFailureCode.PERMISSION_REQUIRED) {
                                                    Muted("当前 Token 没有账单历史读取权限，其他授权不受影响。")
                                                }
                                                billingError.publicMessage.takeIf { it.isNotBlank() }?.let { Muted(it) }
                                            } else if (legacyAuth) {
                                                Muted(
                                                    "旧版 OAuth 授权已停用，App 不会再刷新或请求它；请在该账号上重新连接并粘贴 API Token，" +
                                                        "验证成功后会替换旧凭据；如保存结果不确定，会停用该连接直到重新确认。",
                                                )
                                            } else {
                                                // A Token may read billing on demand; the token's own permission
                                                // decides success, so no permission name is asserted here.
                                                Muted(
                                                    "账单读取取决于该 API Token 的权限；若无权限请在官方页面为该 Token 勾选账单读取，" +
                                                        "或直接使用控制台。App 不会猜测未确认的授权范围。",
                                                )
                                            }
                                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (billingError?.code == CloudFailureCode.PERMISSION_REQUIRED && openUpgrade != null) Button(
                                                    onClick = { openUpgrade(setOf(ReadTarget(ReadFeature.BILLING))) },
                                                    modifier = Modifier.fillMaxWidth().testTag("billing-reauthorize"),
                                                ) {
                                                    Text("查看所需权限")
                                                }
                                                if (billingError?.code == CloudFailureCode.CREDENTIAL_EXPIRED) TextButton(onClick = openConnection) {
                                                    Text("Token 已失效，重新连接")
                                                }
                                                OutlinedButton(
                                                    onClick = { openConsole(Page.BILLING) },
                                                    modifier = Modifier.fillMaxWidth().testTag("billing-open-console"),
                                                ) {
                                                    Text("打开 Cloudflare 控制台")
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (state.productLoading && state.billingSource == BillingSource.ACCOUNT_HISTORY) {
                                val history = state.products[ProductKey.BILLING]
                                val selected = state.selected
                                if (selected != null && history?.observedAtMs != null) {
                                    ScopedBillingScreen(selected, history.scope,
                                        CloudPage((history.content as? ProductContent.Billing)?.items.orEmpty(), history.next, history.total),
                                        feedback = { DirectoryReadFeedback(history, controller::refresh, { controller.loadMore(ProductKey.BILLING) }) },
                                        openConsole = { openConsole(Page.BILLING) })
                                } else Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DirectoryHeading("账单历史")
                                    DirectoryReadFeedback(history, controller::refresh, { controller.loadMore(ProductKey.BILLING) })
                                    if (history?.loading != true && history?.error == null) Muted("尚未读取账单历史")
                                }
                            } else BillingScreen(state) { openConsole(Page.BILLING) }
                            Page.ACCOUNTS ->
                                AccountsScreen(
                                    state,
                                    openConnection,
                                    { dialog = DialogState.Disconnect(it) },
                                    {
                                        updater?.let {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Muted("设备")
                                                UpdatePanel(it)
                                            }
                                        }
                                    },
                                    needsToken = needsToken,
                                    upgrade = openUpgrade,
                                )
                            Page.JOBS -> JobsScreen(state, searchQuery, jobDetails, reconcileTask)
                            Page.BUY -> if (registrar != null) {
                                val registrarState by registrar.state.collectAsState()
                                com.flarego.ui.registrar.DomainRegisterPage(
                                    registrarState,
                                    registrar::onIntent,
                                    onOpenConsole = { openConsole(Page.BUY) },
                                    demo = state.selected?.demo == true,
                                )
                            } else Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                                // No registrar wired: say so instead of showing a purchase flow
                                // that has no search entry and cannot register anything.
                                EmptyState(
                                    title = "当前构建未接入域名搜索",
                                    subtitle = "域名注册搜索需要包含 Registrar 只读能力的版本；可先到 Cloudflare 控制台搜索与注册。",
                                    action = "打开 Cloudflare 控制台",
                                    onAction = { openConsole(Page.BUY) },
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
        if (syncDetails) SyncDetailsDialog(state, { syncDetails = false }, controller::refreshAllProducts,
            upgrade = openUpgrade?.let { request -> { targets -> syncDetails = false; request(targets) } })
        when (val modal = dialog) {
            DialogState.TokenConnect -> tokenConnection?.let { token ->
                TokenConnectionDialog(dismiss, token, onConnected = {
                    dismiss()
                    scope.launch { feedback.showSnackbar("账号已连接", duration = SnackbarDuration.Short) }
                })
            }
            DialogState.Create -> CreateResourceDialog(
                onDismiss = dismiss,
                native = buildSet {
                    if (r2Browser != null && ProductKey.R2 in state.supportedProducts) add(ResourceKind.R2)
                    if (kvBrowser != null && ProductKey.KV in state.supportedProducts) add(ResourceKind.KV)
                    if (d1Browser != null && ProductKey.D1 in state.supportedProducts) add(ResourceKind.D1)
                },
                openNative = { kind ->
                    dialog = null
                    closeSearch()
                    showAllResources = false
                    controller.selectResourceKind(kind)
                    createTarget = kind
                },
                openConsole = { dialog = null; openConsole(Page.RESOURCES) },
            )
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
