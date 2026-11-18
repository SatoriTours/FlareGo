package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.*
import com.flarego.core.model.*
import com.flarego.ui.Notice
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
    val writable = scope != null && zoneName != null && !unsettled && !state.loading && !state.fromCache && state.error == null
    var draft by remember(scope, state.editorRevision, state.editingRecordId, state.mode) {
        val input = state.draft
        mutableStateOf(if (input != null) DnsFormDraft(input.type, input.name, input.fields)
            else DnsFormDraft(state.detail?.type ?: "A", state.detail?.name ?: "@", state.detail?.fields ?: JsonObject(emptyMap())))
    }
    Column(modifier.fillMaxSize()) {
        if (state.demo) Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("dns-demo-notice")) {
            Notice("示例 · 未调用云 API")
        }
        if (state.fromCache && !state.loading) Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(
                "正在显示本地缓存，修改前需重新核实。",
                Modifier.testTag("dns-cache-notice"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.loading && state.observedAtMs == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("dns-page-error")) {
                Notice(it)
            }
        }
        Box(Modifier.weight(1f)) {
            val prepared = state.preparedChange
            when {
                scope == null -> Box(Modifier.padding(20.dp)) {
                    Text("请选择当前账号的 Zone 后加载 DNS。", style = MaterialTheme.typography.bodyMedium)
                }
                zoneName == null -> Column(
                    Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "等待供应商确认当前 Zone 完整名称；尚不能打开 DNS 表单。",
                        Modifier.testTag("dns-zone-name-required"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { onIntent(DnsIntent.Load(scope, state.filter)) }) {
                        Text("重新加载 Zone 与 DNS")
                    }
                }
                batchOpen && batch != null && batchState != null -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OutlinedButton(
                            onClick = { batch.onIntent(DnsBatchIntent.Cancel); batchOpen = false },
                            modifier = Modifier.testTag("dns-close-batch"),
                        ) {
                            Text("返回单条 DNS")
                        }
                    }
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
                    ) else Column(
                        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("预览已失效或上一操作尚未核对，请返回后重新检查。", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { onIntent(DnsIntent.BackToDraft) }) { Text("返回编辑") }
                        }
                        DnsTaskSummary(task, onIntent)
                    }
                }
                mode != null && unsettled -> Column(
                    Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DnsTaskSummary(task, onIntent)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { onIntent(DnsIntent.Cancel) }) { Text("取消编辑") }
                    }
                }
                mode == DnsWriteMode.DELETE -> Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("删除 DNS 记录", style = MaterialTheme.typography.titleLarge)
                    SectionCard {
                        Text("Zone：${state.zoneName}\n记录：${state.detail?.name.orEmpty()}\n记录 ID：${state.editingRecordId.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                        Text("删除可能中断依赖该记录的服务。先预览，再单独确认。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = writable && state.editingRecordId != null,
                        onClick = { onIntent(DnsIntent.Preview(null, DnsWriteMode.DELETE, state.editingRecordId)) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().testTag("dns-delete-preview"),
                    ) {
                        Text("预览删除")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { onIntent(DnsIntent.Cancel) }) { Text("取消") }
                    }
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
