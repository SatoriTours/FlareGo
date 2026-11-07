package com.flarego.ui.dns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.OperationTask
import com.flarego.core.ports.DnsFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DnsRecordsBody(state: DnsPageState, writable: Boolean, task: OperationTask?, onIntent: (DnsIntent) -> Unit,
    onBatch: (() -> Unit)? = null, managementActions: (@Composable () -> Unit)? = null) {
    var more by remember(state.scope) { mutableStateOf(false) }
    var filters by remember(state.scope) { mutableStateOf(false) }
    var search by rememberSaveable(state.scope) { mutableStateOf(false) }
    var name by rememberSaveable(state.scope, state.filter.name) { mutableStateOf(state.filter.name.orEmpty()) }
    var selectedId by remember(state.scope) { mutableStateOf(state.detail?.id) }
    val open: (String) -> Unit = { id -> selectedId = id; onIntent(DnsIntent.Open(id)) }
    val close: () -> Unit = { selectedId = null; onIntent(DnsIntent.CloseDetail) }
    val detail = selectedId?.let { id -> state.detail?.takeIf { it.id == id } ?: state.rows.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DNS 记录", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { search = !search }, modifier = Modifier.testTag("dns-search-toggle")) {
                Icon(Icons.Outlined.Search, "搜索记录")
            }
            IconButton(onClick = { more = true }, modifier = Modifier.testTag("dns-more")) {
                Icon(Icons.Outlined.MoreHoriz, "更多 DNS 操作")
            }
        }
        if (search) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称筛选") },
                modifier = Modifier.weight(1f).testTag("dns-search-name"))
            TextButton(enabled = !state.loading, onClick = {
                state.scope?.let { onIntent(DnsIntent.Load(it, state.filter.copy(name = name.takeIf(String::isNotBlank)))) }
            }, modifier = Modifier.testTag("dns-search-apply")) { Text("搜索") }
        }
        if (state.filter != DnsFilter()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("筛选已启用", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(enabled = !state.loading, onClick = { name = ""; state.scope?.let { onIntent(DnsIntent.Load(it)) } },
                modifier = Modifier.testTag("dns-filter-clear")) { Text("清除筛选") }
        }
        if (detail == null) DnsTaskSummary(task, onIntent)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dns-list-loading"))
        key(state.scope) {
            LazyColumn(Modifier.fillMaxSize().testTag("dns-record-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.rows, key = { it.id }) { row ->
                    DnsCompactRecord(row, state.zoneName.orEmpty(), onOpen = { open(row.id) })
                }
                if (state.rows.isEmpty() && !state.loading) item { Text("当前筛选下没有 DNS 记录。", Modifier.padding(vertical = 16.dp)) }
                if (state.next != null) item {
                    TextButton(onClick = { onIntent(DnsIntent.LoadMore) }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("dns-load-more")) { Text("加载更多记录") }
                }
            }
        }
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("DNS 操作", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = writable, onClick = { more = false; onIntent(DnsIntent.BeginCreate()) }, modifier = Modifier.fillMaxWidth().testTag("dns-create")) { Text("添加记录") }
            TextButton(onClick = { more = false; filters = true }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-open")) { Text("筛选记录") }
            if (onBatch != null) TextButton(onClick = { more = false; onBatch() }, modifier = Modifier.fillMaxWidth().testTag("dns-open-batch")) { Text("批量管理") }
            managementActions?.invoke()
            TextButton(enabled = !state.loading, onClick = { more = false; state.scope?.let { onIntent(DnsIntent.Load(it, state.filter)) } },
                modifier = Modifier.fillMaxWidth().testTag("dns-refresh")) { Text("刷新记录") }
            Text("已加载 ${state.rows.size} 条${state.total?.let { " / 共 $it 条" }.orEmpty()}", style = MaterialTheme.typography.labelMedium)
            com.flarego.ui.ReadStatus(state.observedAtMs, state.fromCache, state.loading)
        }
    }
    if (filters) DnsFilterDialog(state, onIntent, onDismiss = { filters = false })
    if (detail != null) ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DnsRecordDetails(state, detail, writable && state.detail?.id == detail.id, task, onIntent, close)
    }
}

@Composable
private fun DnsCompactRecord(row: DnsRecordView, zoneName: String, onOpen: () -> Unit) {
    val display = dnsRowPresentation(row, zoneName)
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("dns-record-${row.id}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp),
                modifier = Modifier.width(48.dp).padding(top = 1.dp)) {
                Text(row.type, Modifier.padding(horizontal = 3.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(display.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(4.dp),
                            color = if (row.proxied == true) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(display.status, Modifier.widthIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = if (row.proxied == true) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(display.content, Modifier.testTag("dns-record-content-${row.id}"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onOpen, modifier = Modifier.align(Alignment.TopEnd).offset(y = (-12).dp).size(48.dp).testTag("dns-record-menu-${row.id}")) {
                    Icon(Icons.Outlined.MoreHoriz, "查看记录及操作", Modifier.size(18.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

@Composable
private fun DnsRecordDetails(state: DnsPageState, detail: DnsRecordView, writable: Boolean, task: OperationTask?,
    onIntent: (DnsIntent) -> Unit, onClose: () -> Unit) {
    var rawVisible by remember(state.scope, detail.id) { mutableStateOf(false) }
    val editable = writable && detail.type in DnsSchemas.types && detail.readOnlyReason == null
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("dns-record-detail"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${detail.type} · ${detail.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose, modifier = Modifier.testTag("dns-detail-close")) { Icon(Icons.Outlined.Close, "关闭记录详情") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.detail?.id != detail.id || state.fromCache) Text("本地快照仅供查看；修改前需重新核实。", style = MaterialTheme.typography.labelMedium)
        Text(detail.content ?: "未读取内容或此类型使用结构化字段", Modifier.testTag("dns-detail-content"))
        Text("TTL：${when (detail.ttl) { null -> "未提供"; 1 -> "自动"; else -> "${detail.ttl} 秒" }}", Modifier.testTag("dns-detail-ttl"))
        Text("代理：${dnsRowPresentation(detail, state.zoneName.orEmpty()).status}")
        detail.priority?.let { Text("优先级：$it", Modifier.testTag("dns-detail-priority")) }
        detail.fields["data"]?.let { Text("结构化字段：\n$it") }
        detail.readOnlyReason?.let { Text(it, Modifier.testTag("dns-detail-readonly")) }
        if (detail.unknownFields.isNotEmpty()) Text("保留的未知字段（仅查看）：${detail.unknownFields.sorted().joinToString()}")
        DnsTaskSummary(task, onIntent)
        OutlinedButton(enabled = editable, onClick = { onIntent(DnsIntent.BeginEdit(detail.id, DnsWriteMode.PATCH)) }, modifier = Modifier.fillMaxWidth().testTag("dns-detail-patch")) { Text("修改记录（PATCH）") }
        OutlinedButton(enabled = editable, onClick = { onIntent(DnsIntent.BeginEdit(detail.id, DnsWriteMode.REPLACE)) }, modifier = Modifier.fillMaxWidth().testTag("dns-detail-replace")) { Text("完整替换（PUT）") }
        TextButton(enabled = editable, onClick = { onIntent(DnsIntent.BeginDelete(detail.id)) }, modifier = Modifier.fillMaxWidth().testTag("dns-detail-delete")) { Text("删除记录") }
        TextButton(onClick = { rawVisible = !rawVisible }, modifier = Modifier.testTag("dns-raw-toggle")) { Text("供应商元数据（只读）") }
        if (rawVisible) Text(state.selected?.takeIf { it.id == detail.id && it.scope == state.scope }?.raw?.toString()
            ?: state.records.firstOrNull { it.id == detail.id && it.scope == state.scope }?.raw?.toString().orEmpty(),
            Modifier.testTag("dns-raw-readonly"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DnsFilterDialog(state: DnsPageState, onIntent: (DnsIntent) -> Unit, onDismiss: () -> Unit) {
    var name by remember(state.scope, state.filter) { mutableStateOf(state.filter.name.orEmpty()) }
    var type by remember(state.scope, state.filter) { mutableStateOf(state.filter.type.orEmpty()) }
    var content by remember(state.scope, state.filter) { mutableStateOf(state.filter.content.orEmpty()) }
    var comment by remember(state.scope, state.filter) { mutableStateOf(state.filter.comment.orEmpty()) }
    var tags by remember(state.scope, state.filter) { mutableStateOf(state.filter.tags.joinToString(",")) }
    var advanced by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("筛选 DNS 记录") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-name"))
            OutlinedTextField(type, { type = it }, label = { Text("类型筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-type"))
            TextButton(onClick = { advanced = !advanced }) { Text("内容、备注与标签筛选") }
            if (advanced) {
                OutlinedTextField(content, { content = it }, label = { Text("内容筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-content"))
                OutlinedTextField(comment, { comment = it }, label = { Text("备注筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-comment"))
                OutlinedTextField(tags, { tags = it }, label = { Text("标签筛选（逗号分隔）") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-tags"))
            }
        }
    }, confirmButton = {
        TextButton(enabled = !state.loading, onClick = {
            state.scope?.let { onIntent(DnsIntent.Load(it, DnsFilter(name.takeIf(String::isNotBlank), type.trim().uppercase().takeIf(String::isNotBlank),
                content.takeIf(String::isNotBlank), comment.takeIf(String::isNotBlank), tags.split(',').map(String::trim).filter(String::isNotEmpty)))) }
            onDismiss()
        }, modifier = Modifier.testTag("dns-filter-apply")) { Text("应用筛选") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
