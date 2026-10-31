package com.flarego.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.model.*
import com.flarego.core.application.inventory.*
import kotlinx.coroutines.launch

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)

@Composable
fun OverviewScreen(state: AppState, navigate: (Page) -> Unit, create: () -> Unit, refreshAll: () -> Unit = {}) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(
                color = Forest,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().clickable { navigate(Page.BILLING) },
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本月费用预估", color = Lime, style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (state.selected?.demo == true) "演示 · USD" else "尚无预估",
                            color = Lime,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        state.billing?.estimate?.let { "$$it" } ?: "—",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        if (state.selected?.demo == true) "示例数据 · 不产生真实扣款" else "历史账单可在费用页查看，暂无账号费用预估",
                        color = Lime.copy(alpha = .8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(Modifier.weight(1f).clickable { navigate(Page.RESOURCES) }) {
                    Muted("云资源")
                    Text(
                        if (state.productLoading) loadedLabel(state.products.filterKeys { it in resourceProducts }.values.toList()) else "${state.inventory.resources.size}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Muted("Workers / R2 / D1 / KV")
                }
                SectionCard(Modifier.weight(1f).clickable { navigate(Page.DOMAINS) }) {
                    Muted("我的域名")
                    Text(
                        if (state.productLoading) loadedLabel(listOfNotNull(state.products[ProductKey.ZONES])) else "${state.inventory.domains.size}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Muted(if (state.productLoading && state.products[ProductKey.ZONES]?.observedAtMs == null) "激活状态未加载"
                        else if (state.productLoading) "已加载域名中 ${state.inventory.domains.count { it.status == "active" }} 个已激活"
                        else "${state.inventory.domains.count { it.status == "active" }} 个已激活")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(
                        Triple(Icons.Default.AddCircleOutline, "购买域名", { navigate(Page.BUY) }),
                        Triple(Icons.Default.AddBox, "创建资源", create),
                        Triple(
                            Icons.AutoMirrored.Filled.ReceiptLong,
                            "费用明细",
                            { navigate(Page.BILLING) },
                        ),
                        Triple(Icons.Default.History, "操作记录", { navigate(Page.JOBS) }),
                    )
                    .forEach { (icon, title, click) ->
                        Column(
                            Modifier.weight(1f)
                                .clickable(onClick = click)
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                icon,
                                title,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                            Muted(title)
                        }
                    }
            }
        }
        if (state.selected?.demo == true)
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("请求趋势", style = MaterialTheme.typography.titleMedium)
                        Pill("示例 · 近 30 天")
                    }
                    DemoChart()
                    Muted("演示账号流量，仅用于展示界面")
                }
            }
        item {
            SectionCard {
                Text("最近操作", style = MaterialTheme.typography.titleMedium)
                val jobs = state.operations.filter { it.connectionId == state.selected?.id }.take(3)
                if (jobs.isEmpty()) Muted("暂无操作，所有变更可在操作记录追踪")
                else
                    jobs.forEach {
                        Text(it.title, style = MaterialTheme.typography.bodyMedium)
                        Muted(it.status.label)
                    }
            }
        }
        if (state.productLoading) item { OutlinedButton(onClick = refreshAll,modifier = Modifier.fillMaxWidth()) { Text("刷新全部产品") } }
        items(state.inventory.notices) { Notice(it) }
    }
}

@Composable
fun DomainsScreen(state: AppState, query: String, open: (Domain) -> Unit) {
    val domains = state.inventory.domains.filter { it.name.contains(query, true) }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "全部域名",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Muted("${domains.size} 个 · ${if (state.selected?.demo == true) "演示账号" else "当前账号"}")
            }
        }
        if (domains.isEmpty())
            item {
                EmptyState(if (query.isBlank()) "暂无域名" else "没有匹配域名",
                    if (query.isBlank()) "可刷新列表或检查账号的 Zone Read 权限" else "尝试其他关键词或清空搜索")
            }
        items(domains, key = { it.id }) { domain ->
            Surface(
                Modifier.fillMaxWidth().clickable { open(domain) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            ) {
                Row(
                    Modifier.heightIn(min = 76.dp).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Language,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            domain.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Muted("Cloudflare DNS")
                    }
                    Pill(
                        if (domain.status == "active") "已激活" else domain.status,
                        domain.status != "active",
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        items(state.inventory.notices.filter { it.startsWith("域名") }) { Notice(it) }
    }
}

@Composable
fun ResourcesScreen(state: AppState, query: String, open: (CloudResource) -> Unit,
    selectKind: (ResourceKind) -> Unit = {}, refresh: () -> Unit = {}, more: () -> Unit = {}, selectWorkers: (com.flarego.core.ports.WorkersDirectoryMode) -> Unit = {},
    r2Content: (@Composable () -> Unit)? = null, kvContent: (@Composable () -> Unit)? = null, d1Content: (@Composable () -> Unit)? = null) {
    var filter by remember { mutableStateOf<ResourceKind?>(null) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (!state.productLoading) TextButton(onClick = { filter = null }, contentPadding = PaddingValues(6.dp)) {
                Text(
                    "全部",
                    color =
                        if (filter == null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ResourceKind.entries.forEach { kind ->
                TextButton(onClick = { if (state.productLoading) selectKind(kind) else filter = kind }, contentPadding = PaddingValues(6.dp)) {
                    Text(
                        when (kind) {
                            ResourceKind.WORKER -> if (state.productLoading) "Workers" else "边缘计算"
                            ResourceKind.R2 -> if (state.productLoading) "R2" else "存储"
                            ResourceKind.D1 -> "数据库"
                            ResourceKind.KV -> "KV"
                        },
                        color =
                            if ((if (state.productLoading) state.resourceKind else filter) == kind) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.resourceKind == ResourceKind.R2 && r2Content != null) {
            Box(Modifier.weight(1f)) { r2Content() }
            return@Column
        }
        if (state.resourceKind == ResourceKind.KV && kvContent != null) {
            Box(Modifier.weight(1f)) { kvContent() }
            return@Column
        }
        if (state.resourceKind == ResourceKind.D1 && d1Content != null) {
            Box(Modifier.weight(1f)) { d1Content() }
            return@Column
        }
        if (state.productLoading && state.resourceKind == ResourceKind.WORKER) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { selectWorkers(com.flarego.core.ports.WorkersDirectoryMode.MODERN) }) { Text("Workers 现代目录") }
            TextButton(onClick = { selectWorkers(com.flarego.core.ports.WorkersDirectoryMode.LEGACY) }) { Text("Workers 旧目录") }
        }
        val product = state.products[state.resourceKind.product()]
        if (state.productLoading) ProductStatus(product,refresh,more)
        val resources =
            state.inventory.resources.filter {
                (if (state.productLoading) it.kind == state.resourceKind else (filter == null || it.kind == filter)) && it.name.contains(query, true)
            }
        LazyColumn(
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (resources.isEmpty() && (!state.productLoading ||
                    (product?.observedAtMs != null && !product.loading && product.error == null)))
                item { EmptyState(when {
                    query.isNotBlank() -> "没有匹配资源"
                    state.productLoading && product?.allPagesLoaded == false -> "当前页面没有资源"
                    else -> "暂无云资源"
                }, when {
                        state.productLoading && product?.allPagesLoaded == false ->
                            "仅搜索已加载资源；可清空搜索或加载更多"
                        query.isNotBlank() -> "尝试其他关键词或清空搜索"
                        else -> "尝试其他类型或刷新列表"
                    }) }
            else if (resources.isEmpty() && state.productLoading && product?.loading != true && product?.error == null)
                item { EmptyState("尚未读取资源", "点击刷新当前产品读取列表") }
            items(resources, key = { "${it.kind}:${it.id}" }) {
                ResourceRow(it, state.selected?.demo == true) { open(it) }
            }
            items(state.inventory.notices.filterNot { it.startsWith("域名") }) { Notice(it) }
        }
    }
}

@Composable
fun DnsScreen(state: AppState, query: String, edit: (DnsRecord) -> Unit) {
    val records = state.dns.filter { "${it.name} ${it.content} ${it.type}".contains(query, true) }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("解析记录", style = MaterialTheme.typography.titleMedium)
                Pill(if (query.isBlank()) "${records.size} 条" else "${records.size} / ${state.dns.size} 条")
            }
        }
        if (state.dnsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (records.isEmpty() && !state.dnsLoading)
            item { EmptyState(if (query.isBlank()) "暂无解析记录" else "没有匹配的解析记录",
                if (query.isBlank()) "点击标题栏右侧 + 添加；读取失败可刷新重试" else "尝试其他关键词或清空搜索") }
        items(
            records,
            key = { it.id },
        ) { record ->
            SectionCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(record.type)
                    IconButton(onClick = { edit(record) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreHoriz, "编辑记录 ${record.id}")
                    }
                }
                Text(record.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    record.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (record.proxied) "☁ 已代理" else "仅 DNS",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (record.proxied) Orange
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Muted(
                        "TTL ${if (record.ttl == 1) "自动" else "${record.ttl}s"}${record.priority?.let { " · 优先级 $it" } ?: ""}"
                    )
                }
            }
        }
    }
}

@Composable
fun ResourceScreen(state: AppState, openConsole: () -> Unit) {
    val resource = state.resource ?: return
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(resource.kind.icon(), null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(resource.kind.label, style = MaterialTheme.typography.titleMedium)
                        Muted(resource.detail)
                    }
                }
                Text("ID: ${resource.id}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(Modifier.weight(1f)) {
                    Muted("${if (state.selected?.demo == true) "示例用量" else "用量"}")
                    Text(resource.usage ?: "—", style = MaterialTheme.typography.titleLarge)
                }
                SectionCard(Modifier.weight(1f)) {
                    Muted("数据状态")
                    Text(
                        if (state.selected?.demo == true) "演示" else "已读取",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
        if (state.selected?.demo == true && resource.kind == ResourceKind.WORKER)
            item {
                SectionCard {
                    Text("请求趋势", style = MaterialTheme.typography.titleMedium)
                    DemoChart()
                    Muted("示例指标 · 非实时监控")
                }
            }
        if (state.selected?.demo == true && resource.kind == ResourceKind.R2)
            item {
                SectionCard {
                    Text("对象目录 · 示例", style = MaterialTheme.typography.titleMedium)
                    listOf("images/", "videos/", "public/", "manifest.json").forEach {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(it)
                            Muted(if (it.endsWith('/')) "文件夹" else "1.2 KB")
                        }
                    }
                }
            }
        item {
            SectionCard {
                Text("管理资源", style = MaterialTheme.typography.titleMedium)
                Text("部署、对象操作与其他高级配置请在官方控制台完成。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = openConsole) { Text("打开 Cloudflare 控制台") }
            }
        }
    }
}

/** Typed history presentation; ownership and row scope are checked before any amounts are shown. */
@Composable
fun ScopedBillingScreen(connection: Connection, scope: CloudScope, history: CloudPage<BillingEntry>, openConsole: () -> Unit) {
    val source = when (scope) {
        is AccountScope -> BillingSource.ACCOUNT_HISTORY
        is UserScope -> BillingSource.USER_HISTORY_LEGACY
        else -> {
            Notice("不支持此账单范围，请选择账号或用户")
            return
        }
    }
    val owned = try {
        ScopeGuard.requireOwned(scope, connection)
        true
    } catch (_: CloudFailure) { false }
    if (!owned) {
        Notice("账单范围与当前连接不一致，请重新加载")
        return
    }
    if (history.items.any { it.scope != scope }) {
        Notice("账单数据与所选范围不一致，请重新加载")
        return
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (connection.demo) item { Notice("演示 · 示例数据，不产生真实扣款") }
        item { Text(source.title, style = MaterialTheme.typography.titleMedium) }
        if (source == BillingSource.USER_HISTORY_LEGACY) {
            item { Notice("用户级历史可能包含其他账号，不能作为当前账号费用。") }
        }
        item { Muted("已加载 ${history.items.size} 条历史记录，未提供费用预估") }
        if (history.items.isEmpty()) item { Notice("暂无账单历史") }
        items(history.items) { bill ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(bill.occurredAt?.substringBefore('T') ?: "时间未知", style = MaterialTheme.typography.titleMedium)
                    Text("${bill.amount ?: "未知"} ${bill.currency ?: "币种未知"}",
                        style = MaterialTheme.typography.titleMedium)
                }
                Text(bill.description ?: "未提供账单说明", style = MaterialTheme.typography.bodyMedium)
                Muted(bill.status?.let { "状态：$it" } ?: "状态未知")
                Muted(bill.id)
                bill.invoiceId?.let { Muted("发票：$it") }
                bill.receiptId?.let { Muted("收据：$it") }
            }
        }
        item {
            OutlinedButton(onClick = openConsole, modifier = Modifier.fillMaxWidth()) {
                Text("在官方控制台查看费用与发票")
            }
        }
    }
}

@Composable
fun BillingScreen(state: AppState, openConsole: () -> Unit) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Muted("本月预估${if (state.selected?.demo == true) " · 演示 USD" else ""}")
                Text(
                    state.billing?.estimate?.let { "$$it" } ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Muted(
                    if (state.selected?.demo == true) "预算示例 $100 · 已使用 48.6%"
                    else "Cloudflare API 未提供可靠的账号费用预估"
                )
            }
        }
        state.billing?.notice?.let { item { Notice(it) } }
        item {
            Text(
                if (state.selected?.demo == true) "历史账单 · 示例" else BillingSource.USER_HISTORY_LEGACY.title,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (state.billing?.invoices.isNullOrEmpty())
            item {
                EmptyState(
                    "暂无可用账单",
                    "需要用户级 Billing Read 权限；不支持的 Token 可使用控制台查看",
                    "打开控制台",
                    openConsole,
                )
            }
        items(state.billing?.invoices.orEmpty(), key = { it.id }) { bill ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(bill.date, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${bill.amount ?: "未知"} ${bill.currency}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(bill.description, style = MaterialTheme.typography.bodyMedium)
                Muted(bill.id)
            }
        }
        item {
            OutlinedButton(onClick = openConsole, modifier = Modifier.fillMaxWidth()) {
                Text("在官方控制台查看费用与发票")
            }
        }
    }
}

@Composable
fun AccountsScreen(
    state: AppState,
    connect: () -> Unit,
    remove: (Connection) -> Unit,
    updates: @Composable () -> Unit = {},
) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.connections.isEmpty()) item { ServiceSetupCard(connect) }
        item { Notice("账号仅保存于本机。API Token 使用 Android Keystore 加密保存。") }
        item { updates() }
        items(state.connections, key = { it.id }) { connection ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(if (connection.demo) "演示" else "已连接")
                }
                Muted(
                    "Cloudflare${if (connection.demo) "" else " · ${connection.accountId.take(8)}…"}"
                )
                if (!connection.demo)
                    TextButton(onClick = { remove(connection) }) {
                        Text("断开连接", color = MaterialTheme.colorScheme.error)
                    }
            }
        }
        if (state.connections.isNotEmpty()) item {
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("连接 Cloudflare 账号")
            }
        }
    }
}

@Composable
fun JobsScreen(state: AppState, query: String, reconcile: (suspend (String) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var checking by remember(state.selected?.id) { mutableStateOf<String?>(null) }
    var checkError by remember(state.selected?.id) { mutableStateOf<String?>(null) }
    val search = query.trim()
    val accountTasks = state.tasks.filter { it.connectionId == state.selected?.id }
    val taskIds = accountTasks.map { it.id }.toSet()
    val summaries = state.operations.filter { it.connectionId == state.selected?.id }.associateBy { it.id }
    fun title(task: OperationTask) = summaries[task.id]?.title ?: "云资源任务"
    val tasks = accountTasks.filter { task ->
        listOf(title(task), task.actionKey, task.safeDetail, taskLabel(task.state), summaries[task.id]?.detail.orEmpty()).any { it.contains(search, true) }
    }
    // A task and its legacy summary are one visible result; filter after joining them.
    val operations = summaries.values.filter { op ->
        op.id !in taskIds && listOf(op.title, op.detail, op.status.label).any { it.contains(search, true) }
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Muted("当前账号的最近 200 条本机操作") }
        checkError?.let { item { Notice(it) } }
        items(tasks,key = { "task:${it.id}" }) { task -> SectionCard {
            Text(title(task),style = MaterialTheme.typography.titleMedium)
            Pill(taskLabel(task.state),task.state != TaskState.SUCCEEDED)
            Text(task.safeDetail,style = MaterialTheme.typography.bodyMedium)
            if (reconcile != null && task.state !in listOf(TaskState.SUCCEEDED,TaskState.FAILED))
                OutlinedButton(enabled = checking == null,onClick = {
                    checking = task.id;checkError = null
                    scope.launch {
                        try { reconcile(task.id) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { checkError = com.flarego.core.application.safeMessage(failure) }
                        finally { checking = null }
                    }
                }) { Text(if (checking == task.id) "核对中" else "只读核对") }
        } }
        if (operations.isEmpty() && tasks.isEmpty()) item { EmptyState(
            if (query.isBlank()) "暂无操作记录" else "没有匹配的操作记录",
            if (query.isBlank()) "当前账号的云资源操作结果会显示在这里" else "尝试其他关键词或清空搜索") }
        items(operations, key = { "operation:${it.id}" }) { op ->
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        op.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(op.status.label, op.status != OperationStatus.SUCCESS)
                }
                Text(op.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val resourceProducts = setOf(ProductKey.WORKERS,ProductKey.R2,ProductKey.D1,ProductKey.KV)
private fun loadedLabel(states: List<ProductLoadState>): String {
    val observed = states.filter { it.observedAtMs != null }
    return if (observed.isEmpty()) "未加载" else "已加载 ${observed.sumOf { it.content.count() }}"
}
@Composable
fun ProductStatus(state: ProductLoadState?, refresh: () -> Unit, more: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp,vertical = 6.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment = Alignment.CenterVertically) {
            Muted(if (state?.observedAtMs == null) {
                if (state?.loading == true) "正在读取…" else "未加载"
            } else "已加载 ${state.content.count()} · ${if (state.allPagesLoaded) "全部" else "尚未完整"}")
            TextButton(enabled = state?.loading != true,onClick = refresh) { Text("刷新当前产品") }
        }
        state?.error?.let { Notice(it.publicMessage,refresh) }
        if (state?.next != null) OutlinedButton(enabled = !state.loading,onClick = more) { Text("加载更多") }
    }
}
private fun taskLabel(state: TaskState): String = when(state) {
    TaskState.QUEUED -> "等待执行"
    TaskState.RUNNING -> "执行中"
    TaskState.ACCEPTED -> "已受理"
    TaskState.SUCCEEDED -> "已确认完成"
    TaskState.PARTIAL -> "部分完成"
    TaskState.FAILED -> "执行失败"
    TaskState.UNKNOWN -> "待核对"
    TaskState.RECONCILING -> "核对中"
    TaskState.ACTION_REQUIRED -> "需要处理"
}
