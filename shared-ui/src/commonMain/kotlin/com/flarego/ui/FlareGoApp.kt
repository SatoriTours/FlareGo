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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.application.AppController
import com.flarego.core.model.*
import com.flarego.core.updates.UpdateController
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
) {
    val state by controller.state.collectAsState()
    val updateState = updater?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var dialog by remember(connectionDraft) {
        mutableStateOf<DialogState?>(if (connectionDraft.isOpen) DialogState.Connect else null)
    }
    var searchOpen by
        remember(state.page, state.selected.id) {
            mutableStateOf(state.page == Page.BUY && state.selected.demo)
        }
    var query by remember(state.page, state.selected.id) { mutableStateOf("") }
    var searched by remember(state.page, state.selected.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }
    DisposableEffect(dialog is DialogState.Connect) {
        sensitiveScreen(dialog is DialogState.Connect)
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
    val detail = state.page in setOf(Page.DNS, Page.BUY, Page.RESOURCE, Page.JOBS)
    val searchEnabled =
        state.page in setOf(Page.DOMAINS, Page.RESOURCES, Page.DNS, Page.BUY, Page.JOBS)
    val dismiss = {
        if (dialog is DialogState.Connect) connectionDraft.clear()
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
                                    if (connection.id == state.selected.id)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        keyboard?.hide()
                                        dismiss()
                                        controller.selectConnection(connection)
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
                                    if (connection.id == state.selected.id)
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
                                        controller.back()
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
                                        Page.RESOURCE -> state.resource?.name ?: state.page.title
                                        else -> state.page.title
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Cloudflare · ${state.selected.name}",
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
                            when (state.page) {
                                Page.DOMAINS ->
                                    IconButton(onClick = { controller.navigate(Page.BUY) }) {
                                        Icon(Icons.Default.Add, "购买域名")
                                    }
                                Page.RESOURCES ->
                                    IconButton(onClick = { dialog = DialogState.Create }) {
                                        Icon(Icons.Default.Add, "创建资源")
                                    }
                                Page.DNS ->
                                    IconButton(
                                        onClick = {
                                            state.domain?.let {
                                                dialog = DialogState.Edit(it, null)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Add, "新增 DNS 记录")
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
                        if (searchOpen)
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
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Notice(error, controller::refresh)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (state.page) {
                            Page.OVERVIEW ->
                                OverviewScreen(
                                    state,
                                    controller::navigate,
                                    { dialog = DialogState.Create },
                                )
                            Page.DOMAINS -> DomainsScreen(state, query, controller::openDomain)
                            Page.RESOURCES ->
                                ResourcesScreen(state, query, controller::openResource)
                            Page.DNS ->
                                DnsScreen(state, query) {
                                    state.domain?.let { domain ->
                                        dialog = DialogState.Edit(domain, it)
                                    }
                                }
                            Page.RESOURCE -> ResourceScreen(state) { openConsole(Page.RESOURCE) }
                            Page.BILLING -> BillingScreen(state) { openConsole(Page.BILLING) }
                            Page.ACCOUNTS ->
                                AccountsScreen(
                                    state,
                                    openConnection,
                                    { dialog = DialogState.Disconnect(it) },
                                    { updater?.let { UpdatePanel(it) } },
                                )
                            Page.JOBS -> JobsScreen(state, query)
                            Page.BUY ->
                                PurchaseScreen(
                                    query,
                                    searched,
                                    state.selected.demo,
                                    { name, price -> dialog = DialogState.Purchase(name, price) },
                                    { openConsole(Page.BUY) },
                                )
                        }
                    }
                }
            }
        }
        when (val modal = dialog) {
            DialogState.Connect -> ConnectionDialog(dismiss, connect, connectionDraft)
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
                    state.selected.demo,
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
