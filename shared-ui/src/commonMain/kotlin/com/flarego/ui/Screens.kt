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

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)

@Composable
fun OverviewScreen(state: AppState, navigate: (Page) -> Unit, create: () -> Unit) {
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
                            if (state.selected.demo) "演示 · USD" else "尚无预估",
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
                        if (state.selected.demo) "示例数据 · 不产生真实扣款" else "历史账单可在费用页查看，暂无账号费用预估",
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
                        "${state.inventory.resources.size}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Muted("Workers / R2 / D1 / KV")
                }
                SectionCard(Modifier.weight(1f).clickable { navigate(Page.DOMAINS) }) {
                    Muted("我的域名")
                    Text(
                        "${state.inventory.domains.size}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Muted("${state.inventory.domains.count { it.status == "active" }} 个已激活")
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
        if (state.selected.demo)
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
                val jobs = state.operations.filter { it.connectionId == state.selected.id }.take(3)
                if (jobs.isEmpty()) Muted("暂无操作，所有变更可在操作记录追踪")
                else
                    jobs.forEach {
                        Text(it.title, style = MaterialTheme.typography.bodyMedium)
                        Muted(it.status.label)
                    }
            }
        }
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
                Muted("${domains.size} 个 · ${if (state.selected.demo) "演示账号" else "当前账号"}")
            }
        }
        if (domains.isEmpty())
            item {
                EmptyState(if (query.isBlank()) "暂无域名" else "没有匹配域名", "可刷新列表或检查账号的 Zone Read 权限")
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
fun ResourcesScreen(state: AppState, query: String, open: (CloudResource) -> Unit) {
    var filter by remember { mutableStateOf<ResourceKind?>(null) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { filter = null }, contentPadding = PaddingValues(6.dp)) {
                Text(
                    "全部",
                    color =
                        if (filter == null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ResourceKind.entries.forEach { kind ->
                TextButton(onClick = { filter = kind }, contentPadding = PaddingValues(6.dp)) {
                    Text(
                        when (kind) {
                            ResourceKind.WORKER -> "边缘计算"
                            ResourceKind.R2 -> "存储"
                            ResourceKind.D1 -> "数据库"
                            ResourceKind.KV -> "KV"
                        },
                        color =
                            if (filter == kind) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val resources =
            state.inventory.resources.filter {
                (filter == null || it.kind == filter) && it.name.contains(query, true)
            }
        LazyColumn(
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (resources.isEmpty())
                item { EmptyState(if (query.isBlank()) "暂无云资源" else "没有匹配资源", "尝试其他类型或刷新列表") }
            items(resources, key = { "${it.kind}:${it.id}" }) {
                ResourceRow(it, state.selected.demo) { open(it) }
            }
            items(state.inventory.notices.filterNot { it.startsWith("域名") }) { Notice(it) }
        }
    }
}

@Composable
fun DnsScreen(state: AppState, query: String, edit: (DnsRecord) -> Unit) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("解析记录", style = MaterialTheme.typography.titleMedium)
                Pill("${state.dns.size} 条")
            }
        }
        if (state.dnsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.dns.isEmpty() && !state.dnsLoading)
            item { EmptyState("暂无解析记录", "点击标题栏右侧 + 添加；读取失败可刷新重试") }
        items(
            state.dns.filter { "${it.name} ${it.content} ${it.type}".contains(query, true) },
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
                    Muted("${if (state.selected.demo) "示例用量" else "用量"}")
                    Text(resource.usage ?: "—", style = MaterialTheme.typography.titleLarge)
                }
                SectionCard(Modifier.weight(1f)) {
                    Muted("数据状态")
                    Text(
                        if (state.selected.demo) "演示" else "已读取",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
        if (state.selected.demo && resource.kind == ResourceKind.WORKER)
            item {
                SectionCard {
                    Text("请求趋势", style = MaterialTheme.typography.titleMedium)
                    DemoChart()
                    Muted("示例指标 · 非实时监控")
                }
            }
        if (state.selected.demo && resource.kind == ResourceKind.R2)
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

@Composable
fun BillingScreen(state: AppState, openConsole: () -> Unit) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Muted("本月预估${if (state.selected.demo) " · 演示 USD" else ""}")
                Text(
                    state.billing?.estimate?.let { "$$it" } ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Muted(
                    if (state.selected.demo) "预算示例 $100 · 已使用 48.6%"
                    else "Cloudflare API 未提供可靠的账号费用预估"
                )
            }
        }
        state.billing?.notice?.let { item { Notice(it) } }
        item {
            Text(
                if (state.selected.demo) "历史账单 · 示例" else "用户级账单历史",
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
                        "${bill.amount ?: "—"} ${bill.currency}",
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
        item { Notice("账号仅保存于本机。真实 Token 使用 Android Keystore 加密；演示账号不连接云服务。") }
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
        item {
            Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("连接 Cloudflare 账号")
            }
        }
    }
}

@Composable
fun JobsScreen(state: AppState, query: String) {
    val operations =
        state.operations.filter {
            it.connectionId == state.selected.id && it.title.contains(query, true)
        }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Muted("当前账号的最近 200 条本机操作") }
        if (operations.isEmpty()) item { EmptyState("暂无操作记录", "DNS 变更及模拟注册结果会显示在这里") }
        items(operations, key = { it.id }) { op ->
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
