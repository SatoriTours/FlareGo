package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.model.*
import com.flarego.core.application.inventory.*
import com.flarego.core.application.connection.ReadFeature
import com.flarego.core.application.connection.ReadTarget
import com.flarego.core.ports.WorkersDirectoryMode
import kotlinx.coroutines.launch

private val ListPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    state: AppState,
    navigate: (Page) -> Unit,
    create: () -> Unit,
    refreshAll: () -> Unit = {},
    openResource: (ResourceKind) -> Unit = { navigate(Page.RESOURCES) },
    upgrade: ((Set<ReadTarget>) -> Unit)? = null,
    openRecent: (RecentVisit) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val readable = state.products.filterKeys { it in state.supportedProducts }
    val loading = state.loading || readable.values.any { it.loading }
    val failures = readable.filterValues { it.error != null }
    val entries = listOf(null, ResourceKind.WORKER, ResourceKind.R2, ResourceKind.KV, ResourceKind.D1)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val columns = if (maxWidth < 340.dp || largeText) 2 else 3
        val compactViewport = maxHeight < 500.dp && !largeText
        PullToRefreshBox(isRefreshing = loading, onRefresh = { if (!loading) refreshAll() }) {
            LazyColumn(Modifier.fillMaxSize().background(homeResourcesColor()).testTag("overview-list")) {
                item {
                    Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp)
                        .padding(top = if (compactViewport) 8.dp else 14.dp, bottom = if (compactViewport) 6.dp else 14.dp)) {
                        HomeSectionTitle("最近访问")
                        Spacer(Modifier.height(if (compactViewport) 2.dp else 8.dp))
                        if (state.recent.isEmpty()) Muted("打开域名、Worker 或 R2 桶后，会显示在这里。",
                            Modifier.padding(vertical = 16.dp))
                        state.recent.forEachIndexed { i, recent ->
                            val domain = (recent as? RecentVisit.Zone)?.domain
                            val resource = (recent as? RecentVisit.Resource)?.resource
                            val name = domain?.name ?: resource?.name.orEmpty()
                            val label = if (domain != null) "域名" else if (resource?.kind == ResourceKind.WORKER) "Worker" else "R2 桶"
                            val tag = if (domain != null) "recent-zone-${domain.id}" else "recent-resource-${resource?.kind?.name}-${resource?.id}"
                            Row(Modifier.fillMaxWidth().heightIn(min = if (compactViewport) 48.dp else 60.dp)
                                .clickable { openRecent(recent) }.testTag(tag),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(31.dp).background(homeAccentColor(), RoundedCornerShape(9.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(resource?.kind?.icon() ?: Icons.Outlined.Language, null, Modifier.size(21.dp),
                                        tint = homeAccentContentColor())
                                }
                                Text(name, Modifier.weight(1f).padding(horizontal = 14.dp),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Muted(label)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 10.dp).size(18.dp),
                                    tint = colors.onSurfaceVariant)
                            }
                            if (i != state.recent.lastIndex) HorizontalDivider(Modifier.padding(start = 45.dp))
                        }
                    }
                    HorizontalDivider()
                }
                item {
                    HomeSectionTitle("我的资源", Modifier.padding(horizontal = 20.dp,
                        vertical = if (compactViewport) 8.dp else 14.dp))
                }
                items(entries.chunked(columns)) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = if (compactViewport) 6.dp else 8.dp)
                        .height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { kind ->
                            val tag = kind?.name?.lowercase() ?: "domains"
                            val label = when (kind) {
                                null -> "域名"
                                ResourceKind.WORKER -> "Workers"
                                ResourceKind.R2 -> "R2 存储"
                                ResourceKind.KV -> "KV 存储"
                                ResourceKind.D1 -> "D1 数据库"
                            }
                            Surface(
                                onClick = { if (kind == null) navigate(Page.DOMAINS) else openResource(kind) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .background(colors.outlineVariant, RoundedCornerShape(16.dp))
                                    .padding(bottom = 2.dp).testTag("overview-$tag"),
                                shape = RoundedCornerShape(16.dp), color = colors.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
                            ) {
                                Column(Modifier.heightIn(min = if (compactViewport) 96.dp else 114.dp)
                                    .padding(horizontal = 5.dp, vertical = if (compactViewport) 5.dp else 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(if (compactViewport) 4.dp else 5.dp, Alignment.CenterVertically)) {
                                    Icon(kind?.icon() ?: Icons.Outlined.Language, null,
                                        Modifier.size(if (compactViewport) 25.dp else 31.dp), tint = homeAccentColor())
                                    Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                                    val count = overviewLoadedCount(state, kind?.product() ?: ProductKey.ZONES)
                                    Text(count, textAlign = TextAlign.Center,
                                        style = if (count.all { it.isDigit() || it == '+' })
                                            MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)
                                        else MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (loading && readable.isNotEmpty()) item {
                    Muted("已完成 ${readable.values.count { !it.loading }}/${readable.size} 项读取", Modifier.padding(16.dp))
                }
                if (failures.isNotEmpty()) item {
                    val succeeded = readable.values.count { !it.loading && it.error == null && it.observedAtMs != null }
                    Muted(if (loading) "${failures.size} 项未能读取" else "读取完成：$succeeded 项成功，${failures.size} 项失败",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    TextButton(onClick = refreshAll, enabled = !loading, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重试读取") }
                }
                failures.forEach { (product, loaded) ->
                    item(key = "error:$product") { Box(Modifier.padding(horizontal = 16.dp)) {
                        Notice("${product.overviewLabel()}：${loaded.error!!.publicMessage}")
                    } }
                }
                val refused = failures.filterValues { it.error?.code == CloudFailureCode.PERMISSION_REQUIRED }.keys
                if (refused.isNotEmpty() && upgrade != null) item {
                    TextButton(onClick = { upgrade(refused.map { productReadTarget(it, state.workersMode) }.toSet()) },
                        modifier = Modifier.fillMaxWidth().testTag("overview-permission-upgrade")) { Text("申请补充读取权限") }
                }
                val tasks = state.tasks.filter { it.connectionId == state.selected?.id }
                val pending = tasks.count { it.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.ACCEPTED,
                    TaskState.ACTION_REQUIRED, TaskState.RECONCILING) } + state.operations.count { operation ->
                    operation.connectionId == state.selected?.id && operation.status == OperationStatus.UNKNOWN &&
                        tasks.none { it.id == operation.id }
                }
                if (pending > 0) item {
                    OutlinedButton(onClick = { navigate(Page.JOBS) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { Text("$pending 项操作待核对") }
                }
                items(state.inventory.notices) { Box(Modifier.padding(horizontal = 16.dp)) { Notice(it) } }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun HomeSectionTitle(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
internal fun homeHeaderColor() = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFE1ECE3)
    else MaterialTheme.colorScheme.surfaceContainer

@Composable
private fun homeResourcesColor() = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFF4F4EF)
    else MaterialTheme.colorScheme.background

@Composable
internal fun homeAccentColor() = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFF007D59)
    else MaterialTheme.colorScheme.primary

@Composable
internal fun homeAccentContentColor() = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color.White
    else MaterialTheme.colorScheme.onPrimary

internal fun productReadTarget(product: ProductKey, workersMode: WorkersDirectoryMode) =
    ReadTarget(if (product == ProductKey.WORKERS && workersMode == WorkersDirectoryMode.LEGACY) ReadFeature.WORKERS_LEGACY
        else ReadFeature.entries.first { it.product == product })

internal fun ProductKey.overviewLabel(): String = when (this) {
    ProductKey.ZONES -> "域名"
    ProductKey.WORKERS -> "Workers"
    ProductKey.R2 -> "R2"
    ProductKey.KV -> "KV"
    ProductKey.D1 -> "D1"
    ProductKey.BILLING -> "账单"
}

private fun overviewLoadedCount(state: AppState, product: ProductKey): String {
    if (state.productLoading) {
        if (product !in state.supportedProducts) return "当前授权方案未支持"
        val loaded = state.products[product] ?: return "未加载"
        if (loaded.observedAtMs == null) return when {
            loaded.loading -> "读取中"
            loaded.error != null -> "读取失败"
            else -> "未加载"
        }
        val count = "${loaded.content.count()}${if (loaded.allPagesLoaded) "" else "+"}"
        return when {
            loaded.loading -> "$count · 更新中"
            loaded.error != null -> "$count · 更新失败"
            else -> count
        }
    }
    return if (product == ProductKey.ZONES) state.inventory.domains.size.toString()
    else state.inventory.resources.count { it.kind.product() == product }.toString()
}

@Composable
fun DomainsScreen(state: AppState, query: String, open: (Domain) -> Unit, onRegisterDomain: (() -> Unit)? = null) {
    val domains = state.inventory.domains.filter { it.name.contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item { DirectoryHeading("已托管", Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        if (domains.isEmpty())
            item {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    EmptyState(if (query.isBlank()) "暂无域名" else "没有匹配域名",
                        if (query.isBlank()) "可刷新列表或检查账号的 Zone Read 权限" else "尝试其他关键词或清空搜索")
                }
            }
        itemsIndexed(domains, key = { _, domain -> domain.id }) { index, domain ->
            DirectoryItem(index, domains.size, Modifier.padding(horizontal = 16.dp)) {
                CompactListRow(domain.name, value = zoneStatusLabel(domain.status), onClick = { open(domain) },
                    showDivider = false, wrapTitle = true)
            }
        }
        items(state.inventory.notices.filter { it.startsWith("域名") }) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { Notice(it) }
        }
        if (onRegisterDomain != null)
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onRegisterDomain, modifier = Modifier.testTag("zone-open-registrar")) {
                        Text("搜索并注册域名")
                    }
                }
            }
    }
}

@Composable
fun ResourcesScreen(state: AppState, query: String, open: (CloudResource) -> Unit,
    selectKind: (ResourceKind) -> Unit = {}, refresh: () -> Unit = {}, more: () -> Unit = {}, selectWorkers: (com.flarego.core.ports.WorkersDirectoryMode) -> Unit = {},
    r2Content: (@Composable () -> Unit)? = null, kvContent: (@Composable () -> Unit)? = null, d1Content: (@Composable () -> Unit)? = null,
    showDirectoryTabs: Boolean = true, showAllResources: Boolean = !state.productLoading,
    selectAllResources: () -> Unit = {}) {
    Column {
        if (showDirectoryTabs) DirectoryPicker(
            label = "资源类型",
            options = (if (state.productLoading) emptyList() else listOf("all" to "全部")) +
                listOf("WORKER" to "Workers", "R2" to "R2", "KV" to "KV", "D1" to "D1"),
            selected = if (!state.productLoading && showAllResources) "all" else state.resourceKind.name,
            onSelect = { key ->
                if (key == "all") selectAllResources()
                else ResourceKind.entries.firstOrNull { it.name == key }?.let(selectKind)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            tag = "resource-type-selector",
            optionPrefix = "resource-tab",
        )
        if (showDirectoryTabs) DirectoryHeading("当前账号", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (state.productLoading && state.resourceKind.product() !in state.supportedProducts) {
            Notice("当前授权方案未支持 ${state.resourceKind.name} ${state.resourceKind.label}")
            return@Column
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
        val product = state.products[state.resourceKind.product()]
        val resources =
            state.inventory.resources.filter {
                (if (state.productLoading || !showAllResources) it.kind == state.resourceKind else true) && it.name.contains(query, true)
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
                item { EmptyState("尚未读取资源", "刷新当前产品读取列表", "刷新当前产品", refresh) }
            if (state.productLoading && (product?.error != null || product?.loading == true))
                item { DirectoryReadFeedback(product, refresh, more, showPagination = false) }
            itemsIndexed(resources, key = { _, resource -> "${resource.kind}:${resource.id}" }) { index, resource ->
                DirectoryItem(index, resources.size) {
                    val kind = when (resource.kind) {
                        ResourceKind.WORKER -> if (resource.legacy) "旧 Worker" else "Worker"
                        ResourceKind.R2 -> "R2 桶"
                        ResourceKind.KV -> "KV"
                        ResourceKind.D1 -> "D1"
                    }
                    CompactListRow(resource.name, value = resource.usage ?: kind, onClick = { open(resource) },
                        showDivider = false, wrapTitle = true)
                }
            }
            if (state.productLoading) item { DirectoryReadFeedback(product, refresh, more, showReadState = false) }
            if (state.productLoading && state.resourceKind == ResourceKind.WORKER) item {
                DirectoryPicker("Workers 目录",
                    listOf("modern" to "现代目录", "legacy" to "旧目录"), state.workersMode.name.lowercase(),
                    onSelect = { selectWorkers(if (it == "legacy") WorkersDirectoryMode.LEGACY else WorkersDirectoryMode.MODERN) },
                    tag = "worker-directory-selector", optionPrefix = "worker-dir-tab")
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
fun ScopedBillingScreen(
    connection: Connection, scope: CloudScope, history: CloudPage<BillingEntry>,
    openConsole: () -> Unit,
    feedback: @Composable () -> Unit = {},
) {
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
    LazyColumn(contentPadding = ListPadding) {
        if (connection.demo) item { Notice("演示 · 示例数据，不产生真实扣款") }
        item { DirectoryHeading(if (source == BillingSource.ACCOUNT_HISTORY) "账单历史" else source.title, Modifier.padding(bottom = 8.dp)) }
        if (source == BillingSource.USER_HISTORY_LEGACY) {
            item { Notice("用户级历史可能包含其他账号，不能作为当前账号费用。") }
        }
        if (history.items.isEmpty()) item { DirectoryGroup { CompactListRow("暂无账单历史", showDivider = false) } }
        itemsIndexed(history.items) { index, bill ->
            DirectoryItem(index, history.items.size) {
                BillingHistoryRow(
                    bill.occurredAt?.substringBefore('T') ?: "时间未知",
                    "${bill.amount ?: "未知"} ${bill.currency ?: "币种未知"}",
                    listOfNotNull(bill.description ?: "未提供账单说明",
                        bill.status?.let { "状态：$it" } ?: "状态未知", bill.id,
                        bill.invoiceId?.let { "发票：$it" }, bill.receiptId?.let { "收据：$it" }),
                )
            }
        }
        item { feedback() }
        item {
            OutlinedButton(onClick = openConsole, modifier = Modifier.fillMaxWidth()) {
                Text("在官方控制台查看费用与发票")
            }
        }
    }
}

@Composable
fun BillingScreen(state: AppState, openConsole: () -> Unit) {
    val billing = state.billing
    val bills = billing?.invoices.orEmpty()
    LazyColumn(contentPadding = ListPadding) {
        item {
            DirectoryHeading(if (state.selected?.demo == true) "账单历史 · 示例" else BillingSource.USER_HISTORY_LEGACY.title)
        }
        billing?.notice?.let { item { Notice(it) } }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (bills.isEmpty() && !state.loading) item {
            DirectoryGroup { CompactListRow("暂无可用账单", showDivider = false) }
            DirectoryHeading("用户级旧接口需要额外的用户级只读权限。", Modifier.padding(top = 8.dp))
        }
        itemsIndexed(bills, key = { _, bill -> bill.id }) { index, bill ->
            DirectoryItem(index, bills.size) {
                BillingHistoryRow(bill.date, "${bill.amount ?: "未知"} ${bill.currency}", listOf(bill.description, bill.id))
            }
        }
        billing?.estimate?.let { estimate ->
            item { Muted("本月预估：$$estimate", Modifier.padding(top = 12.dp)) }
        }
        item {
            TextButton(onClick = openConsole, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("在官方控制台查看费用与发票")
            }
        }
    }
}

@Composable
private fun BillingHistoryRow(date: String, amount: String, details: List<String>) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(date, amount, details.lastOrNull()) { mutableStateOf(false) }
    Column {
        if (LocalDensity.current.fontScale >= 1.5f) {
            CompactListRow(date, subtitle = amount, showDivider = false, onClick = { expanded = !expanded })
        } else {
            CompactListRow(date, value = amount, showDivider = false, onClick = { expanded = !expanded })
        }
        if (expanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            details.forEach { Muted(it) }
        }
    }
}

@Composable
fun AccountsScreen(
    state: AppState,
    connect: () -> Unit,
    remove: (Connection) -> Unit,
    updates: @Composable () -> Unit = {},
    /** Legacy OAuth records keep their row but must be reconfigured with an API Token. */
    needsToken: (Connection) -> Boolean = { false },
    upgrade: ((Set<ReadTarget>) -> Unit)? = null,
) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.connections.isEmpty()) item { ServiceSetupCard(connect) }
        items(state.connections, key = { it.id }) { connection ->
            var detailsOpen by androidx.compose.runtime.saveable.rememberSaveable(connection.id, connection.accountId) { mutableStateOf(false) }
            val legacy = !connection.demo && needsToken(connection)
            SectionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        connection.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Pill(
                        when {
                            connection.demo -> "演示"
                            legacy -> "需要 Token"
                            else -> "已连接"
                        },
                        legacy,
                    )
                }
                Text(
                    "Cloudflare${if (connection.demo) "" else " · ${connection.accountId.take(8)}…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (legacy) Text(
                    "旧版 OAuth 授权已停用，App 不会再刷新或请求它；请重新连接并粘贴 API Token。账号名称与本地记录保留。",
                    Modifier.testTag("account-needs-token-${connection.id}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { detailsOpen = !detailsOpen },
                    modifier = Modifier.testTag("connection-details-toggle")) {
                    Text(if (detailsOpen) "收起连接详情" else "连接详情")
                }
                if (detailsOpen) {
                    Muted(if (connection.demo) "演示账号没有云端凭据" else if (legacy)
                        "旧授权停用，当前连接需要配置 API Token" else "凭据已在本机加密保存")
                    Muted("FlareGo 只按实际读取结果判断权限；未读取不代表已授权。")
                    val scoped = AccountScope(connection.id, connection.accountId)
                    val denied = if (state.selected == connection) {
                        state.products.filterValues {
                            it.scope == scoped && it.error?.code == CloudFailureCode.PERMISSION_REQUIRED
                        }.keys
                    } else emptySet()
                    if (denied.isNotEmpty()) {
                        Muted("当前缺少读取权限：${denied.joinToString("、") { it.overviewLabel() }}")
                        if (upgrade != null) TextButton(onClick = {
                            upgrade(denied.map { productReadTarget(it, state.workersMode) }.toSet())
                        }) { Text("核对并补充读取权限") }
                    }
                    if (!connection.demo && !legacy) Muted("替换时核验可保留的读取范围；写权限不会自动迁移。")
                }
                if (!connection.demo) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { remove(connection) }) {
                            Text("断开连接", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (state.connections.isNotEmpty()) item {
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("连接 Cloudflare 账号")
            }
        }
        item { Notice("账号仅保存于本机，授权凭据使用 Android Keystore 加密保存。") }
        item { updates() }
    }
}

@Composable
fun JobsScreen(
    state: AppState,
    query: String,
    expanded: MutableState<String?> = remember(state.selected?.id, state.selected?.accountId) { mutableStateOf(null) },
    reconcile: (suspend (String) -> Unit)? = null,
) {
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
        // 受限宽度下必须自己占满一行；否则字形比排版宽度略宽时末尾字会被自身边界裁掉。
        item { Muted("当前账号的最近 200 条本机操作", Modifier.fillMaxWidth()) }
        checkError?.let { item { Notice(it) } }
        items(tasks, key = { "task:${it.id}" }) { task ->
            val key = "task:${task.id}"
            val open = expanded.value == key
            val pending = task.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.ACCEPTED, TaskState.ACTION_REQUIRED, TaskState.RECONCILING)
            DirectoryGroup {
                CompactListRow(title(task), value = taskLabel(task.state),
                    modifier = Modifier.testTag("job-row-${task.id}"), showDivider = open, wrapTitle = true,
                    onClick = { expanded.value = key.takeUnless { open } })
                if (open) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("job-detail-${task.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.safeDetail, style = MaterialTheme.typography.bodyMedium)
                    task.resourceRef?.let { Muted("目标：${it.externalId}") }
                    if (task.scope is ZoneScope && task.resourceRef == null) Muted("Zone：${(task.scope as ZoneScope).zoneId}")
                    Muted("来源：此设备")
                    if (task.updatedAtMs > 0) Muted("更新于 ${observationTime(task.updatedAtMs)}")
                    if (pending) {
                        Muted("请求可能已经送达；不会自动再提交。若需人工处置，请在对应对象的任务区核对原目标。")
                    }
                    if (reconcile != null && task.state !in listOf(TaskState.SUCCEEDED, TaskState.FAILED)) {
                        TextButton(
                            enabled = checking == null,
                            onClick = {
                                checking = task.id
                                checkError = null
                                scope.launch {
                                    try { reconcile(task.id) }
                                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (failure: Exception) { checkError = com.flarego.core.application.safeMessage(failure) }
                                    finally { checking = null }
                                }
                            },
                        ) { Text(if (checking == task.id) "核对中" else "只读核对") }
                    }
                }
            }
        }
        if (operations.isEmpty() && tasks.isEmpty()) item {
            EmptyState(
                if (query.isBlank()) "暂无操作记录" else "没有匹配的操作记录",
                if (query.isBlank()) "当前账号的云资源操作结果会显示在这里" else "尝试其他关键词或清空搜索",
            )
        }
        items(operations, key = { "operation:${it.id}" }) { op ->
            val key = "operation:${op.id}"
            val open = expanded.value == key
            DirectoryGroup {
                CompactListRow(op.title, value = op.status.label, modifier = Modifier.testTag("job-row-${op.id}"),
                    showDivider = open, wrapTitle = true, onClick = { expanded.value = key.takeUnless { open } })
                if (open) Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(op.detail, style = MaterialTheme.typography.bodyMedium)
                    Muted("来源：此设备")
                }
            }
        }
    }
}

internal fun taskLabel(state: TaskState): String = when(state) {
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
