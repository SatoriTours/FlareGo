package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.*
import com.flarego.core.ports.DnsFilter
import com.flarego.ui.SectionCard
import kotlinx.serialization.json.*

@Composable
fun DnsPage(state: DnsPageState, onIntent: (DnsIntent) -> Unit, modifier: Modifier = Modifier, batch: DnsBatchPagePort? = null, managementActions: (@Composable () -> Unit)? = null) {
    val scope = state.scope
    val batchState = batch?.state?.collectAsState()?.value
    var batchOpen by remember(scope) { mutableStateOf(false) }
    DisposableEffect(batch, scope) {
        batch?.onIntent(DnsBatchIntent.Clear)
        if (scope != null) batch?.onIntent(DnsBatchIntent.Bind(scope))
        onDispose { batch?.onIntent(DnsBatchIntent.Clear) }
    }
    val zoneName = state.zoneName
    val mode = state.mode
    val task = state.pendingTask?.takeIf { it.scope == scope }
    val unsettled = task?.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.ACCEPTED,
        TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING)
    val writable = scope != null && zoneName != null && !unsettled && !state.loading
    var draft by remember(scope, state.editorRevision, state.editingRecordId, state.mode) {
        val input = state.draft
        mutableStateOf(if (input != null) DnsFormDraft(input.type, input.name, input.fields)
            else DnsFormDraft(state.detail?.type ?: "A", state.detail?.name ?: "@", state.detail?.fields ?: JsonObject(emptyMap())))
    }
    Column(modifier.fillMaxSize()) {
        if (state.demo) Text("示例 · 未调用云 API", Modifier.padding(12.dp).testTag("dns-demo-notice"))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, Modifier.padding(12.dp).testTag("dns-page-error"), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            val prepared = state.preparedChange
            when {
                scope == null -> Text("请选择当前账号的 Zone 后加载 DNS。", Modifier.padding(16.dp))
                zoneName == null -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("等待供应商确认当前 Zone 完整名称；尚不能打开 DNS 表单。", Modifier.testTag("dns-zone-name-required"))
                    OutlinedButton(onClick = { onIntent(DnsIntent.Load(scope, state.filter)) }) { Text("重新加载 Zone 与 DNS") }
                }
                batchOpen && batch != null && batchState != null -> Column(Modifier.fillMaxSize()) {
                    OutlinedButton(onClick = { batch.onIntent(DnsBatchIntent.Cancel); batchOpen = false },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("dns-close-batch")) { Text("返回单条 DNS") }
                    DnsBatchPanel(scope, batchState, batch::onIntent, Modifier.weight(1f), recordsScope = scope, records = state.rows)
                }
                prepared != null -> {
                    val valid = prepared.target == scope && prepared.revision == state.revision &&
                        prepared.recordId == state.editingRecordId
                    if (valid && !unsettled) DnsChangePreview(
                        prepared.preview,
                        DnsPreviewTarget(scope.connectionId, scope.account.accountId, zoneName, prepared.recordId),
                        onConfirm = { if (writable) onIntent(DnsIntent.Confirm(prepared.id, prepared.revision)) },
                        onBack = { onIntent(DnsIntent.BackToDraft) }, onCancel = { onIntent(DnsIntent.Cancel) },
                        fields = previewFields(prepared.before, prepared.after),
                    ) else Column(Modifier.padding(16.dp)) {
                        Text("预览已失效或上一操作尚未核对，请返回后重新检查。")
                        OutlinedButton(onClick = { onIntent(DnsIntent.BackToDraft) }) { Text("返回编辑") }
                        DnsTaskSummary(task, onIntent)
                    }
                }
                mode != null && unsettled -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    DnsTaskSummary(task, onIntent)
                    OutlinedButton(onClick = { onIntent(DnsIntent.Cancel) }) { Text("取消编辑") }
                }
                mode == DnsWriteMode.DELETE -> Column(
                    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("删除 DNS 记录", style = MaterialTheme.typography.titleLarge)
                    Text("Zone：${state.zoneName}\n记录：${state.detail?.name.orEmpty()}\n记录 ID：${state.editingRecordId.orEmpty()}")
                    Text("删除可能中断依赖该记录的服务。先预览，再单独确认。", color = MaterialTheme.colorScheme.error)
                    Button(enabled = writable && state.editingRecordId != null,
                        onClick = { onIntent(DnsIntent.Preview(null, DnsWriteMode.DELETE, state.editingRecordId)) },
                        modifier = Modifier.fillMaxWidth().testTag("dns-delete-preview")) { Text("预览删除") }
                    OutlinedButton(onClick = { onIntent(DnsIntent.Cancel) }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
                mode != null -> DnsRecordForm(draft, zoneName,
                    onDraftChange = { value -> onIntent(DnsIntent.DraftInvalidated); draft = value },
                    onPreview = { input ->
                        if (writable) {
                            val actual = if (mode == DnsWriteMode.PATCH) input.copy(fields = editedFields(input.fields, draft.edits.keys)) else input
                            onIntent(DnsIntent.Preview(actual, mode, state.editingRecordId))
                        }
                    }, onCancel = { onIntent(DnsIntent.Cancel) },
                    method = when (mode) {
                        DnsWriteMode.PATCH -> DnsWriteMethod.PATCH
                        DnsWriteMode.REPLACE -> DnsWriteMethod.PUT
                        else -> DnsWriteMethod.POST
                    })
                else -> DnsRecordsBody(state, writable, task, onIntent, if (batch != null) ({ batchOpen = true }) else null, managementActions)
            }
        }
    }
}

@Composable
private fun DnsRecordsBody(state: DnsPageState, writable: Boolean, task: OperationTask?, onIntent: (DnsIntent) -> Unit, onBatch: (() -> Unit)? = null, managementActions: (@Composable () -> Unit)? = null) {
    var filterName by remember(state.scope, state.filter) { mutableStateOf(state.filter.name.orEmpty()) }
    var filterType by remember(state.scope, state.filter) { mutableStateOf(state.filter.type.orEmpty()) }
    var filterContent by remember(state.scope, state.filter) { mutableStateOf(state.filter.content.orEmpty()) }
    var filterComment by remember(state.scope, state.filter) { mutableStateOf(state.filter.comment.orEmpty()) }
    var filterTags by remember(state.scope, state.filter) { mutableStateOf(state.filter.tags.joinToString(",")) }
    var advanced by remember(state.scope) { mutableStateOf(false) }
    var rawVisible by remember(state.scope, state.detail?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DNS 记录 · ${state.zoneName}", style = MaterialTheme.typography.titleLarge)
        managementActions?.invoke()
        DnsTaskSummary(task, onIntent)
        if (onBatch != null) OutlinedButton(onClick = onBatch, modifier = Modifier.fillMaxWidth().testTag("dns-open-batch")) { Text("批量 DNS 草稿与只读核对") }
        Button(onClick = { onIntent(DnsIntent.BeginCreate()) }, enabled = writable,
            modifier = Modifier.fillMaxWidth().testTag("dns-create")) { Text("新增 DNS 记录") }
        val detail = state.detail
        if (detail != null) {
            val editable = writable && detail.type in DnsSchemas.types && detail.readOnlyReason == null
            SectionCard {
                Text("${detail.type} · ${detail.name}", style = MaterialTheme.typography.titleMedium)
                detail.content?.let { Text(it) }
                Text("TTL：${detail.ttl ?: "未提供"}\n代理：${detail.proxied ?: "未提供"}")
                detail.fields["data"]?.let { Text("结构化字段：\n$it") }
                detail.readOnlyReason?.let { Text(it, Modifier.testTag("dns-detail-readonly")) }
                if (detail.unknownFields.isNotEmpty()) Text("保留的未知字段（仅查看）：${detail.unknownFields.sorted().joinToString()}")
                OutlinedButton(enabled = editable, onClick = { onIntent(DnsIntent.BeginEdit(detail.id, DnsWriteMode.PATCH)) },
                    modifier = Modifier.fillMaxWidth().testTag("dns-detail-patch")) { Text("PATCH 修改明确字段") }
                OutlinedButton(enabled = editable, onClick = { onIntent(DnsIntent.BeginEdit(detail.id, DnsWriteMode.REPLACE)) },
                    modifier = Modifier.fillMaxWidth().testTag("dns-detail-replace")) { Text("PUT 完整替换记录") }
                TextButton(enabled = editable, onClick = { onIntent(DnsIntent.BeginDelete(detail.id)) },
                    modifier = Modifier.fillMaxWidth().testTag("dns-detail-delete")) { Text("删除记录") }
                TextButton(onClick = { rawVisible = !rawVisible }, modifier = Modifier.testTag("dns-raw-toggle")) { Text("供应商元数据（只读）") }
                if (rawVisible) Text(state.selected?.takeIf { it.id == detail.id && it.scope == state.scope }?.raw?.toString()
                    ?: state.records.firstOrNull { it.id == detail.id && it.scope == state.scope }?.raw?.toString().orEmpty(),
                    Modifier.testTag("dns-raw-readonly"), style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionCard {
            Text("筛选 DNS 记录", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(filterName, { filterName = it }, label = { Text("名称筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-name"))
            OutlinedTextField(filterType, { filterType = it }, label = { Text("类型筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-type"))
            TextButton(onClick = { advanced = !advanced }) { Text("内容、备注与标签筛选") }
            if (advanced) {
                OutlinedTextField(filterContent, { filterContent = it }, label = { Text("内容筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-content"))
                OutlinedTextField(filterComment, { filterComment = it }, label = { Text("备注筛选") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-comment"))
                OutlinedTextField(filterTags, { filterTags = it }, label = { Text("标签筛选（逗号分隔）") }, modifier = Modifier.fillMaxWidth().testTag("dns-filter-tags"))
            }
            Button(onClick = {
                state.scope?.let { onIntent(DnsIntent.Load(it, DnsFilter(filterName.takeIf(String::isNotBlank),
                    filterType.trim().uppercase().takeIf(String::isNotBlank), filterContent.takeIf(String::isNotBlank),
                    filterComment.takeIf(String::isNotBlank), filterTags.split(',').map(String::trim).filter(String::isNotEmpty)))) }
            }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().testTag("dns-filter-apply")) { Text("应用筛选") }
        }
        Text("已加载 ${state.rows.size} 条${state.total?.let { " / 共 $it 条" } ?: ""}")
        state.rows.forEach { row ->
            OutlinedButton(onClick = { onIntent(DnsIntent.Open(row.id)) }, modifier = Modifier.fillMaxWidth().testTag("dns-record-${row.id}")) {
                Column(Modifier.fillMaxWidth()) { Text("${row.type} · ${row.name}"); row.content?.let { Text(it) } }
            }
        }
        if (state.rows.isEmpty()) Text("当前筛选下没有 DNS 记录。")
        if (state.next != null) OutlinedButton(onClick = { onIntent(DnsIntent.LoadMore) }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().testTag("dns-load-more")) { Text("加载更多记录") }
        OutlinedButton(onClick = { state.scope?.let { onIntent(DnsIntent.Load(it, state.filter)) } }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().testTag("dns-refresh")) { Text("刷新 DNS") }
    }
}

@Composable
private fun DnsTaskSummary(task: OperationTask?, onIntent: (DnsIntent) -> Unit) {
    task ?: return
    Text("操作状态：${task.state}\n${task.safeDetail}", Modifier.testTag("dns-task-status"))
    if (task.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.ACCEPTED, TaskState.RECONCILING)) {
        Text("结果尚未确定；只读核对，不重复发送。")
        OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(DnsIntent.Reconcile(task.id)) },
            modifier = Modifier.fillMaxWidth().testTag("dns-task-reconcile")) { Text("只读核对结果") }
    }
}

/** Only actual edited paths enter PATCH. The complete projection remains display-only. */
private fun editedFields(validated: JsonObject, paths: Set<String>): JsonObject {
    fun add(result: JsonObject, tokens: List<String>, value: JsonElement): JsonObject {
        val key = tokens.first()
        return JsonObject(result + (key to if (tokens.size == 1) value else
            add((result[key] as? JsonObject) ?: JsonObject(emptyMap()), tokens.drop(1), value)))
    }
    var result = JsonObject(emptyMap())
    paths.forEach { path -> validated.at(path)?.let { result = add(result, path.split('.'), it) } }
    return result
}

private fun previewFields(before: DnsRecordInput?, after: DnsRecordInput?): List<DnsPreviewField> {
    fun flatten(input: DnsRecordInput?): Map<String, String> {
        input ?: return emptyMap()
        val result = linkedMapOf("type" to input.type, "name" to input.name)
        fun visit(value: JsonObject, prefix: String) {
            value.forEach { (key, item) ->
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                if (item is JsonObject) visit(item, path)
                else result[path] = if (item is JsonPrimitive && item.isString) item.content else item.toString()
            }
        }
        visit(input.fields, "")
        return result
    }
    val a = flatten(before); val b = flatten(after)
    return (a.keys + b.keys).filter { a[it] != b[it] }.map { DnsPreviewField(it, a[it], b[it]) }
}
