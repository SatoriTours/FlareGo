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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.OperationTask
import com.flarego.core.model.TaskState
import com.flarego.core.ports.DnsFilter
import com.flarego.ui.EmptyState
import com.flarego.ui.Notice
import com.flarego.ui.Pill
import com.flarego.ui.ReadStatus
import com.flarego.ui.SectionCard
import com.flarego.ui.taskLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DnsRecordsBody(
    state: DnsPageState,
    writable: Boolean,
    task: OperationTask?,
    onIntent: (DnsIntent) -> Unit,
    onBatch: (() -> Unit)? = null,
    managementActions: (@Composable () -> Unit)? = null,
) {
    var more by remember(state.scope) { mutableStateOf(false) }
    var filters by remember(state.scope) { mutableStateOf(false) }
    var search by rememberSaveable(state.scope) { mutableStateOf(false) }
    var name by rememberSaveable(state.scope, state.filter.name) { mutableStateOf(state.filter.name.orEmpty()) }
    var selectedId by remember(state.scope) { mutableStateOf(state.detail?.id) }
    val open: (String) -> Unit = { id -> selectedId = id; onIntent(DnsIntent.Open(id)) }
    val close: () -> Unit = { selectedId = null; onIntent(DnsIntent.CloseDetail) }
    val detail = selectedId?.let { id -> state.detail?.takeIf { it.id == id } ?: state.rows.firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DNS 记录",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { search = !search }, modifier = Modifier.testTag("dns-search-toggle")) {
                Icon(Icons.Outlined.Search, "搜索记录")
            }
            IconButton(onClick = { more = true }, modifier = Modifier.testTag("dns-more")) {
                Icon(Icons.Outlined.MoreHoriz, "更多 DNS 操作")
            }
        }
        if (search) Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                name,
                { name = it },
                singleLine = true,
                label = { Text("名称筛选") },
                modifier = Modifier.weight(1f).testTag("dns-search-name"),
            )
            TextButton(
                enabled = !state.loading,
                onClick = {
                    state.scope?.let { onIntent(DnsIntent.Load(it, state.filter.copy(name = name.takeIf(String::isNotBlank)))) }
                },
                modifier = Modifier.testTag("dns-search-apply"),
            ) {
                Text("搜索")
            }
        }
        if (state.filter != DnsFilter()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("筛选已启用", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(
                enabled = !state.loading,
                onClick = { name = ""; state.scope?.let { onIntent(DnsIntent.Load(it)) } },
                modifier = Modifier.testTag("dns-filter-clear"),
            ) {
                Text("清除筛选")
            }
        }
        if (detail == null) DnsTaskSummary(task, onIntent, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dns-list-loading"))
        key(state.scope) {
            LazyColumn(Modifier.fillMaxSize().testTag("dns-record-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.rows, key = { it.id }) { row ->
                    DnsCompactRecord(row, state.zoneName.orEmpty(), onOpen = { open(row.id) })
                }
                if (state.rows.isEmpty() && !state.loading) item {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        EmptyState(
                            title = if (state.filter != DnsFilter()) "没有匹配的 DNS 记录" else "暂无 DNS 记录",
                            subtitle = "当前筛选下没有 DNS 记录。",
                        )
                    }
                }
                if (state.next != null) item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onIntent(DnsIntent.LoadMore) },
                            enabled = !state.loading,
                            modifier = Modifier.testTag("dns-load-more"),
                        ) {
                            Text("加载更多记录")
                        }
                    }
                }
            }
        }
    }
    if (more) ModalBottomSheet(
        onDismissRequest = { more = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("DNS 操作", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = writable,
                onClick = { more = false; onIntent(DnsIntent.BeginCreate()) },
                modifier = Modifier.fillMaxWidth().testTag("dns-create"),
            ) {
                Text("添加记录")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { more = false; filters = true },
                    modifier = Modifier.testTag("dns-filter-open"),
                ) {
                    Text("筛选记录")
                }
                if (onBatch != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { more = false; onBatch() },
                        modifier = Modifier.testTag("dns-open-batch"),
                    ) {
                        Text("批量管理")
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = !state.loading,
                    onClick = {
                        more = false
                        state.scope?.let { onIntent(DnsIntent.Load(it, state.filter)) }
                    },
                    modifier = Modifier.testTag("dns-refresh"),
                ) {
                    Text("刷新记录")
                }
            }
            managementActions?.invoke()
            Text(
                "已加载 ${state.rows.size} 条${state.total?.let { " / 共 $it 条" }.orEmpty()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReadStatus(state.observedAtMs, state.fromCache, state.loading)
        }
    }
    if (filters) DnsFilterDialog(state, onIntent, onDismiss = { filters = false })
    if (detail != null) ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        DnsRecordDetails(state, detail, writable && state.detail?.id == detail.id, task, onIntent, close)
    }
}

@Composable
private fun DnsCompactRecord(row: DnsRecordView, zoneName: String, onOpen: () -> Unit) {
    val display = dnsRowPresentation(row, zoneName)
    val stackedValue = LocalDensity.current.fontScale >= 1.5f
    val ttlText = when (row.ttl) { null -> ""; 1 -> "自动"; else -> "${row.ttl}s" }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag("dns-record-${row.id}")
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    Modifier
                        .widthIn(min = 36.dp)
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        row.type,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    display.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    display.content,
                    modifier = Modifier.testTag("dns-record-content-${row.id}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stackedValue) {
                    // 大字号下中间列很窄，水平排会把 “TTL 360s” 拆成两行；改为纵向堆叠保证每一项完整。
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (row.proxied == true) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                display.status,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.proxied == true) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (ttlText.isNotEmpty()) {
                            Text(
                                "TTL $ttlText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!stackedValue) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (row.proxied == true) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            display.status,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.proxied == true) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ttlText.isNotEmpty()) {
                        Text(
                            "TTL $ttlText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(
                onClick = onOpen,
                modifier = Modifier.size(36.dp).testTag("dns-record-menu-${row.id}"),
            ) {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    "查看记录及操作",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun DnsRecordDetails(
    state: DnsPageState,
    detail: DnsRecordView,
    writable: Boolean,
    task: OperationTask?,
    onIntent: (DnsIntent) -> Unit,
    onClose: () -> Unit,
) {
    var rawVisible by remember(state.scope, detail.id) { mutableStateOf(false) }
    val editable = writable && detail.type in DnsSchemas.types && detail.readOnlyReason == null
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .testTag("dns-record-detail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${detail.type} · ${detail.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose, modifier = Modifier.testTag("dns-detail-close")) {
                Icon(Icons.Outlined.Close, "关闭记录详情")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Notice(it) }
        if (state.detail?.id != detail.id || state.fromCache) {
            Text("本地快照仅供查看；修改前需重新核实。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard {
            Text("记录信息", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                detail.content ?: "未读取内容或此类型使用结构化字段",
                modifier = Modifier.testTag("dns-detail-content"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TTL", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "TTL：${when (detail.ttl) { null -> "未提供"; 1 -> "自动"; else -> "${detail.ttl} 秒" }}",
                    modifier = Modifier.testTag("dns-detail-ttl"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("代理状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    dnsRowPresentation(detail, state.zoneName.orEmpty()).status,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            detail.priority?.let { priority ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("优先级", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "优先级：$priority",
                        modifier = Modifier.testTag("dns-detail-priority"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            detail.fields["data"]?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("结构化字段：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$it", style = MaterialTheme.typography.bodySmall)
                }
            }
            detail.readOnlyReason?.let {
                Text(it, modifier = Modifier.testTag("dns-detail-readonly"), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            if (detail.unknownFields.isNotEmpty()) {
                Text(
                    "保留的未知字段（仅查看）：${detail.unknownFields.sorted().joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DnsTaskSummary(task, onIntent)
        Button(
            enabled = editable,
            onClick = { onIntent(DnsIntent.BeginEdit(detail.id, DnsWriteMode.PATCH)) },
            modifier = Modifier.fillMaxWidth().testTag("dns-detail-patch"),
        ) {
            Text("修改记录（PATCH）")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = editable,
                onClick = { onIntent(DnsIntent.BeginEdit(detail.id, DnsWriteMode.REPLACE)) },
                modifier = Modifier.testTag("dns-detail-replace"),
            ) {
                Text("完整替换（PUT）")
            }
            Spacer(Modifier.width(16.dp))
            TextButton(
                onClick = { rawVisible = !rawVisible },
                modifier = Modifier.testTag("dns-raw-toggle"),
            ) {
                Text("供应商元数据（只读）")
            }
        }
        TextButton(
            enabled = editable,
            onClick = { onIntent(DnsIntent.BeginDelete(detail.id)) },
            modifier = Modifier.fillMaxWidth().testTag("dns-detail-delete"),
        ) {
            Text("删除记录", color = if (editable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
        }
        if (rawVisible) {
            SectionCard {
                Text("供应商元数据", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    state.selected?.takeIf { it.id == detail.id && it.scope == state.scope }?.raw?.toString()
                        ?: state.records.firstOrNull { it.id == detail.id && it.scope == state.scope }?.raw?.toString().orEmpty(),
                    modifier = Modifier.testTag("dns-raw-readonly"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun DnsTaskSummary(task: OperationTask?, onIntent: (DnsIntent) -> Unit, modifier: Modifier = Modifier) {
    task ?: return
    SectionCard(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DNS 任务", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Pill(taskLabel(task.state), warning = task.state != TaskState.SUCCEEDED)
        }
        Text("操作状态：${task.state}\n${task.safeDetail}", Modifier.testTag("dns-task-status"), style = MaterialTheme.typography.bodyMedium)
        if (task.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.ACCEPTED, TaskState.RECONCILING)) {
            Text("结果尚未确定；只读核对，不重复发送。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                enabled = task.state != TaskState.RECONCILING,
                onClick = { onIntent(DnsIntent.Reconcile(task.id)) },
                modifier = Modifier.fillMaxWidth().testTag("dns-task-reconcile"),
            ) {
                Text(if (task.state == TaskState.RECONCILING) "核对中" else "只读核对结果")
            }
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选 DNS 记录") },
        text = {
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
        },
        confirmButton = {
            TextButton(
                enabled = !state.loading,
                onClick = {
                    state.scope?.let {
                        onIntent(
                            DnsIntent.Load(
                                it,
                                DnsFilter(
                                    name.takeIf(String::isNotBlank),
                                    type.trim().uppercase().takeIf(String::isNotBlank),
                                    content.takeIf(String::isNotBlank),
                                    comment.takeIf(String::isNotBlank),
                                    tags.split(',').map(String::trim).filter(String::isNotEmpty),
                                ),
                            )
                        )
                    }
                    onDismiss()
                },
                modifier = Modifier.testTag("dns-filter-apply"),
            ) {
                Text("应用筛选")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
