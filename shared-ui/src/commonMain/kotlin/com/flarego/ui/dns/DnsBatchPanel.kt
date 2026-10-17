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
import com.flarego.ui.SectionCard
import kotlinx.serialization.json.*

/** Pure UI. Record views must be supplied from the matching provider-owned parent scope. */
@Composable
fun DnsBatchPanel(
    scope: ZoneScope,
    state: DnsBatchPageState,
    onIntent: (DnsBatchIntent) -> Unit,
    modifier: Modifier = Modifier,
    recordsScope: ZoneScope? = null,
    records: List<DnsRecordView> = emptyList(),
) {
    var input by remember(scope, state.scope, state.zoneName, state.editorRevision) {
        mutableStateOf(if (scope == state.scope) state.draft ?: DnsBatchInput() else DnsBatchInput())
    }
    var editor by remember(scope, state.scope, state.zoneName, state.editorRevision) { mutableStateOf<BatchEditor?>(null) }
    var requested by remember(scope, state.scope, state.zoneName, state.editorRevision) { mutableStateOf<DnsBatchInput?>(null) }
    var error by remember(scope, state.scope, state.zoneName, state.editorRevision) { mutableStateOf<String?>(null) }
    var visible by remember(scope, state.editorRevision) { mutableIntStateOf(20) }
    var previewVisible by remember(scope, state.preparedChange?.id) { mutableIntStateOf(20) }
    val ownedRecords = if (recordsScope == scope) records else emptyList()
    val task = state.pendingTask?.takeIf { it.scope == scope }
    val unsettled = task?.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.ACCEPTED, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING)
    val currentStateReady = state.outcome?.hasCurrentStateProof() == true && !unsettled
    val unresolved = state.outcome?.let { (it.transaction == DnsBatchTransaction.UNKNOWN && !currentStateReady) || it.pending.isNotEmpty() ||
        it.items.any { item -> item.verification in setOf(DnsBatchVerification.UNKNOWN, DnsBatchVerification.CONFLICT) } } == true
    val reason = when {
        state.scope != scope -> "当前账号或 Zone 已变化，等待当前 scope 的 batch 状态。"
        state.zoneName.isNullOrBlank() -> "等待 provider-owned Zone 完整名称；不能从记录名称或 Zone ID 猜 root。"
        state.error != null -> "当前批量操作尚未通过验证，请只读核对或重新绑定当前 Zone。"
        unsettled || unresolved -> "同 Zone DNS mutation family 的结果尚未核对；当前只读，不拆批、不重发或再次创建。"
        else -> null
    }
    val writable = reason == null && !state.loading
    val overLimit = input.count() > state.maxOperations || state.maxOperations <= 0
    val plan = state.preparedChange
    val validPlan = writable && !overLimit && plan != null && requested == input && state.draft == input &&
        plan.matches(scope, state.revision, input, state.zoneName!!)
    LaunchedEffect(reason, plan, validPlan) {
        if (reason != null || (plan != null && !validPlan)) requested = null
    }
    LaunchedEffect(recordsScope) {
        if (recordsScope != scope && editor?.record != null) { editor = null; requested = null }
    }
    fun invalidate() { requested = null; error = null; onIntent(DnsBatchIntent.DraftInvalidated) }
    fun changed(value: DnsBatchInput) { invalidate(); input = value; onIntent(DnsBatchIntent.DraftChanged(value)) }
    fun commit(value: DnsBatchInput) {
        try {
            validateBatchDraft(value, state.zoneName!!, state.maxOperations)
            changed(value); editor = null
        } catch (failure: Exception) { error = failure.message ?: "批量输入无效，未加入草稿" }
    }
    val active = editor
    if (state.scope == scope && writable && active?.record != null && active.kind != DnsBatchKind.DELETE) {
        Column(modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                error?.let { Text(it, Modifier.padding(12.dp).testTag("db-editor-error"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { invalidate(); editor = null }, modifier = Modifier.testTag("db-editor-cancel")) { Text("返回批量列表") }
            }
            DnsRecordForm(active.draft, state.zoneName!!, { next -> invalidate(); editor = active.copy(draft = next) }, { parsed ->
                try {
                    val record = active.record
                    val value = when (active.kind) {
                        DnsBatchKind.PATCH -> {
                            require(parsed.type == record.type) { "PATCH 修改类型必须改用独立 PUT 完整替换" }
                            val fields = patchDelta(record, active.patch, parsed, active.draft)
                            require(fields.isNotEmpty()) { "没有明确变更，请编辑字段后再加入" }
                            input.withPatch(DnsBatchPatch(record.id, fields), active.index)
                        }
                        DnsBatchKind.PUT -> input.withPut(DnsBatchPut(record.id, parsed), active.index)
                        else -> error("不支持的编辑操作")
                    }
                    commit(value)
                } catch (failure: Exception) { error = failure.message ?: "字段不安全，未加入草稿" }
            }, { invalidate(); editor = null }, modifier = Modifier.weight(1f),
                method = if (active.kind == DnsBatchKind.PATCH) DnsWriteMethod.PATCH else DnsWriteMethod.PUT,
                actionLabel = "加入批量草稿")
        }
        return
    }
    if (state.scope == scope && writable && active?.kind == DnsBatchKind.POST) {
        Column(modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                error?.let { Text(it, Modifier.padding(12.dp).testTag("db-editor-error"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { invalidate(); editor = null }, modifier = Modifier.testTag("db-editor-cancel")) { Text("返回批量列表") }
            }
            DnsRecordForm(active.draft, state.zoneName!!, { next -> invalidate(); editor = active.copy(draft = next) },
                { parsed -> commit(input.withPost(parsed, active.index)) }, { invalidate(); editor = null },
                modifier = Modifier.weight(1f), actionLabel = "加入批量草稿")
        }
        return
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.scope != scope) {
            Text(reason.orEmpty(), Modifier.testTag("db-readonly"))
            OutlinedButton(onClick = { onIntent(DnsBatchIntent.Bind(scope)) }) { Text("加载当前 Zone 批量状态") }
            return@Column
        }
        if (state.demo) Text("示例 · 未调用云 API", Modifier.testTag("db-demo"))
        Text("批量 DNS · ${state.zoneName ?: "等待供应商名称"}", style = MaterialTheme.typography.titleLarge)
        Text("连接：${scope.connectionId}\n账号：${scope.account.accountId}\nZone ID：${scope.zoneId}")
        Text(batchSemantics, Modifier.testTag(if (validPlan) "db-semantics" else "db-transaction-warning"))
        reason?.let { Text(it, Modifier.testTag(if (state.zoneName == null) "db-zone-name-required" else "db-readonly"), color = MaterialTheme.colorScheme.error) }
        state.error?.let { Text(it, Modifier.testTag("db-error"), color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("${input.count()} 项 / 可信上限 ${state.maxOperations}；超限拒绝，不自动拆批。", Modifier.testTag("db-limit"))
        if (validPlan) {
            Text("计划：${plan.id}\nrevision：${plan.revision}", Modifier.testTag("db-plan"))
            Text(countsText(plan.counts), Modifier.testTag("db-counts"))
            plan.warnings.forEach { Text(it) }
            plan.targets.take(previewVisible).forEach { item ->
                SectionCard {
                    Text("${item.kind} #${item.index + 1}\n${item.name}\nID：${item.recordId ?: "新建，无 provider ID"}\n类型：${item.type ?: "未提供"}\nbefore：${item.before.domainDisplay()}\nafter：${item.after.domainDisplay()}",
                        Modifier.testTag("db-target-${item.kind}-${item.index}"))
                }
            }
            if (plan.targets.size > previewVisible) TextButton(onClick = { previewVisible += 20 }) { Text("查看更多预览目标（仅展示，不拆批）") }
            Button(onClick = { if (validPlan) onIntent(DnsBatchIntent.Confirm(plan.id, plan.revision)) },
                modifier = Modifier.fillMaxWidth().testTag("db-confirm")) { Text("确认提交整批") }
            OutlinedButton(onClick = { onIntent(DnsBatchIntent.BackToDraft) }, modifier = Modifier.fillMaxWidth().testTag("db-back")) { Text("返回草稿") }
            TextButton(onClick = { requested = null; onIntent(DnsBatchIntent.Cancel) }, modifier = Modifier.fillMaxWidth().testTag("db-cancel")) { Text("取消") }
            return@Column
        }
        if (plan != null) Text("旧计划的草稿、scope、计数或 revision 已变化，旧确认不可使用。", color = MaterialTheme.colorScheme.error)
        BatchOutcome(state.outcome, currentStateReady)
        if (task != null) {
            Text("同 Zone DNS 操作：${task.actionKey}\n${task.state}\n${task.safeDetail}", Modifier.testTag("db-task"))
            if (unsettled) OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(DnsBatchIntent.Reconcile(task.id)) },
                modifier = Modifier.fillMaxWidth().testTag("db-reconcile")) { Text("只读核对结果") }
        }
        if (unresolved && task == null) Text("没有可安全核对的任务引用，保留未知结果；不能从结果重建 payload、确认或重发。")
        Text(countsText(input.counts()), Modifier.testTag("db-counts"))
        error?.let { Text(it, Modifier.testTag(if (active != null) "db-editor-error" else "db-list-error"), color = MaterialTheme.colorScheme.error) }
        if (active != null) {
            if (active.record != null && active.kind == DnsBatchKind.DELETE) {
                Text("加入 DELETE：${active.record.name}\nID：${active.record.id}；可能中断依赖此记录的服务。加入不发送，整批需预览确认。")
                Button(enabled = writable, onClick = { commit(input.copy(deletes = input.deletes + active.record.id)) },
                    modifier = Modifier.fillMaxWidth().testTag("db-delete-add")) { Text("加入删除草稿") }
            } else {
                Text("选择已加载的同 Zone provider 记录 · ${active.kind}")
                if (recordsScope != scope || ownedRecords.isEmpty()) Text("没有同 scope 的已加载领域记录；请先在 DNS 页加载，不能从裸 ID 猜类型或名称。", Modifier.testTag("db-records-reason"))
                ownedRecords.forEach { record ->
                    val readOnly = record.readOnlyReason ?: if (record.type !in DnsSchemas.types) "未来类型只读" else
                        if (active.kind == DnsBatchKind.PUT && record.unknownFields.isNotEmpty()) "存在未知字段，不能安全 PUT 替换" else null
                    OutlinedButton(enabled = writable && readOnly == null, onClick = {
                        invalidate(); editor = active.copy(record = record, draft = DnsFormDraft(record.type, record.name, record.fields))
                    }, modifier = Modifier.fillMaxWidth().testTag("db-select-${record.id}")) { Text("${record.type} · ${record.name}\nID：${record.id}") }
                    readOnly?.let { Text(it) }
                }
            }
            OutlinedButton(onClick = { invalidate(); editor = null }, modifier = Modifier.fillMaxWidth().testTag("db-editor-cancel")) { Text("返回批量列表") }
        } else {
            DnsBatchKind.entries.forEach { kind ->
                OutlinedButton(enabled = writable, onClick = {
                    invalidate(); editor = BatchEditor(kind)
                }, modifier = Modifier.fillMaxWidth().testTag("db-add-${kind.name}")) { Text("加入 ${kind.name} 操作") }
            }
        }
        var shown = 0
        DnsBatchKind.entries.forEach { kind ->
            input.groupSize(kind).let { size -> if (size > 0) Text("${kind.name} · $size 项", style = MaterialTheme.typography.titleMedium) }
            repeat(input.groupSize(kind)) { index ->
                if (shown++ < visible) {
                    val id = input.idAt(kind, index)
                    val row = id?.let { value -> ownedRecords.firstOrNull { it.id == value } }
                    val value = when (kind) { DnsBatchKind.POST -> input.posts[index]; DnsBatchKind.PUT -> input.puts[index].input; else -> row?.domainInput() }
                    SectionCard {
                        Text("${kind.name} #${index + 1}\n${value?.name ?: "名称未加载（不从 ID 推断）"}\nID：${id ?: "新建，无 provider ID"}\n${when(kind) { DnsBatchKind.PATCH -> input.patches[index].fields; DnsBatchKind.DELETE -> "删除可能中断依赖服务"; else -> value?.fields }}",
                            Modifier.testTag("db-item-$kind-$index"))
                        if (kind != DnsBatchKind.DELETE) TextButton(enabled = writable && (kind == DnsBatchKind.POST || row != null), onClick = {
                            invalidate()
                            val initial = when (kind) { DnsBatchKind.POST -> input.posts[index]; DnsBatchKind.PUT -> input.puts[index].input; else -> row!!.domainInput() }
                            val patch = if (kind == DnsBatchKind.PATCH) input.patches[index].fields else JsonObject(emptyMap())
                            editor = BatchEditor(kind, index, row, DnsFormDraft(initial.type,
                                patch["name"]?.jsonPrimitive?.content ?: initial.name, mergeDomainFields(initial.fields, JsonObject(patch - "name" - "type"))), patch)
                        }, modifier = Modifier.testTag("db-edit-$kind-$index")) { Text("编辑草稿项") }
                        TextButton(enabled = writable, onClick = { changed(input.removing(kind, index)) }, modifier = Modifier.testTag("db-remove-$kind-$index")) { Text("移除此项") }
                    }
                }
            }
        }
        if (shown > visible) TextButton(onClick = { visible += 20 }) { Text("查看更多草稿项（仅展示，不拆批）") }
        Button(enabled = writable && !overLimit && input.count() > 0, onClick = {
            try {
                validateBatchDraft(input, state.zoneName!!, state.maxOperations)
                requested = input; onIntent(DnsBatchIntent.Preview(input))
            } catch (failure: Exception) { error = failure.message ?: "批量草稿无效，未预览" }
        }, modifier = Modifier.fillMaxWidth().testTag("db-preview")) { Text("预览完整批量草稿") }
        TextButton(onClick = { requested = null; onIntent(DnsBatchIntent.Cancel) }, modifier = Modifier.fillMaxWidth().testTag("db-cancel")) { Text("取消") }
        OutlinedButton(enabled = !state.loading, onClick = { onIntent(DnsBatchIntent.Bind(scope)) },
            modifier = Modifier.fillMaxWidth().testTag("db-refresh")) { Text("只读刷新当前 Zone 与任务状态") }
    }
}

private data class BatchEditor(val kind: DnsBatchKind, val index: Int? = null, val record: DnsRecordView? = null,
    val draft: DnsFormDraft = DnsFormDraft(), val patch: JsonObject = JsonObject(emptyMap()))
private const val batchSemantics = "操作顺序 deletes → patches → puts → posts，组内保持用户顺序。数据库单一事务：明确子项失败时整批未应用；全球传播非原子，不能承诺全网完成。DELETE 可能中断依赖服务。PUT 为独立完整替换，遗漏字段回默认，未知内容可能丢失。服务端无 CAS，预提交回读仍有竞争窗口。"
private fun DnsRecordView.domainInput() = DnsRecordInput(type, name, fields)
private fun DnsRecordInput?.domainDisplay() = if (this == null) "无领域值（新建前/删除后）" else "$type $name\n$fields"
private fun DnsBatchInput.counts() = mapOf(DnsBatchKind.DELETE to deletes.size, DnsBatchKind.PATCH to patches.size, DnsBatchKind.PUT to puts.size, DnsBatchKind.POST to posts.size)
private fun countsText(counts: Map<DnsBatchKind, Int>) = DnsBatchKind.entries.joinToString(" · ") { "${it.name} ${counts[it] ?: 0}" }
private fun DnsBatchInput.count() = deletes.size + patches.size + puts.size + posts.size
private fun DnsBatchInput.groupSize(kind: DnsBatchKind) = counts().getValue(kind)
private fun DnsBatchInput.idAt(kind: DnsBatchKind, index: Int): String? = when (kind) { DnsBatchKind.DELETE -> deletes[index]; DnsBatchKind.PATCH -> patches[index].id; DnsBatchKind.PUT -> puts[index].id; DnsBatchKind.POST -> null }
private fun <T> List<T>.withItem(value: T, index: Int?): List<T> = if (index == null) this + value else mapIndexed { i, old -> if (i == index) value else old }
private fun DnsBatchInput.withPatch(value: DnsBatchPatch, index: Int?) = copy(patches = patches.withItem(value, index))
private fun DnsBatchInput.withPut(value: DnsBatchPut, index: Int?) = copy(puts = puts.withItem(value, index))
private fun DnsBatchInput.withPost(value: DnsRecordInput, index: Int?) = copy(posts = posts.withItem(value, index))
private fun DnsBatchInput.removing(kind: DnsBatchKind, index: Int): DnsBatchInput = when (kind) {
    DnsBatchKind.DELETE -> copy(deletes = deletes.filterIndexed { i, _ -> i != index })
    DnsBatchKind.PATCH -> copy(patches = patches.filterIndexed { i, _ -> i != index })
    DnsBatchKind.PUT -> copy(puts = puts.filterIndexed { i, _ -> i != index })
    DnsBatchKind.POST -> copy(posts = posts.filterIndexed { i, _ -> i != index })
}
private fun validateBatchDraft(input: DnsBatchInput, zoneName: String, maximum: Int) {
    require(input.count() in 1..maximum) { "批量数量超限或为空；可信上限 $maximum，不会自动拆批" }
    val ids = input.deletes + input.patches.map { it.id } + input.puts.map { it.id }
    require(ids.none(String::isBlank) && ids.distinct().size == ids.size) { "同组或跨组重复 provider ID，不能加入" }
    input.puts.forEach { validateDnsInput(zoneName, it.input, DnsConstraints(), DnsWriteMethod.PUT) }
    val posts = input.posts.map { validateDnsInput(zoneName, it, DnsConstraints(), DnsWriteMethod.POST) }.map(::dnsBatchPostSemantic)
    require(posts.distinct().size == posts.size) { "重复语义 POST，不能重复创建" }
}
private fun mergeDomainFields(before: JsonObject, delta: JsonObject): JsonObject = JsonObject(before + delta.mapValues { (key, value) ->
    if (value is JsonObject && before[key] is JsonObject) mergeDomainFields(before[key]!!.jsonObject, value) else value
})
private fun patchDelta(record: DnsRecordView, previous: JsonObject, parsed: DnsRecordInput, draft: DnsFormDraft): JsonObject {
    fun add(base: JsonObject, path: List<String>, value: JsonElement?): JsonObject {
        val key = path.first()
        val next = if (path.size == 1) value else add((base[key] as? JsonObject) ?: JsonObject(emptyMap()), path.drop(1), value).takeIf { it.isNotEmpty() }
        return JsonObject(if (next == null) base - key else base + (key to next))
    }
    var delta = JsonObject(previous - "name" - "type")
    draft.edits.keys.forEach { path ->
        if (path.startsWith("data.") || path.startsWith("settings.")) require(record.unknownFields.none { it.startsWith(path.substringBefore('.') + ".") }) {
            "嵌套字段含未知内容，无法安全合并；请使用官方控制台"
        }
        delta = add(delta, path.split('.'), parsed.fields.at(path))
    }
    fun changed(candidate: JsonObject, old: JsonObject): JsonObject = JsonObject(candidate.mapNotNull { (key, value) ->
        val next = if (value is JsonObject) changed(value, (old[key] as? JsonObject) ?: JsonObject(emptyMap())).takeIf { it.isNotEmpty() } else value.takeIf { it != old[key] }
        next?.let { key to it }
    }.toMap())
    delta = changed(delta, record.fields)
    if (parsed.name != record.name) delta = JsonObject(delta + ("name" to JsonPrimitive(parsed.name)))
    return delta
}
private fun DnsBatchPreview.matches(scope: ZoneScope, actualRevision: Long, input: DnsBatchInput, zoneName: String): Boolean {
    if (id.isBlank() || this.scope != scope || revision != actualRevision || counts != input.counts() || targets.size != input.count()) return false
    val expected = DnsBatchKind.entries.flatMap { kind -> (0 until input.groupSize(kind)).map { kind to it } }
    return targets.zip(expected).all { (item, pair) ->
        item.kind == pair.first && item.index == pair.second && item.recordId == input.idAt(pair.first, pair.second) &&
            item.name.isNotBlank() && when (item.kind) {
                DnsBatchKind.DELETE -> item.before != null && item.after == null
                DnsBatchKind.POST -> item.before == null && item.after == input.posts[item.index] && item.name == input.posts[item.index].name
                DnsBatchKind.PUT -> item.before != null && item.after == input.puts[item.index].input && item.name == input.puts[item.index].input.name
                DnsBatchKind.PATCH -> patchProjectionMatches(item, input.patches[item.index], zoneName)
            }
    }
}
/** Display binding only. The shared resolver/controller independently guards actual sending. */
private fun patchProjectionMatches(item: DnsBatchPreviewItem, patch: DnsBatchPatch, zoneName: String): Boolean {
    val before = item.before ?: return false
    val after = item.after ?: return false
    if (item.name != after.name || item.type != after.type) return false
    return runCatching {
        fun text(key: String, fallback: String): String = patch.fields[key]?.let {
            require(it is JsonPrimitive && it.isString)
            it.content
        } ?: fallback
        val expected = DnsRecordInput(text("type", before.type), text("name", before.name),
            mergeDomainFields(before.fields, JsonObject(patch.fields - "name" - "type")))
        val wanted = validateDnsInput(zoneName, expected, DnsConstraints(), DnsWriteMethod.POST)
        val displayed = validateDnsInput(zoneName, after, DnsConstraints(), DnsWriteMethod.POST)
        dnsBatchPostSemantic(wanted) == dnsBatchPostSemantic(displayed)
    }.getOrDefault(false)
}
@Composable
private fun BatchOutcome(outcome: DnsBatchOutcome?, currentStateReady: Boolean) {
    outcome ?: return
    var visible by remember(outcome) { mutableIntStateOf(20) }
    Text(when (outcome.transaction) {
        DnsBatchTransaction.CONFIRMED_COMMIT -> "数据库事务：CONFIRMED_COMMIT，已确认供应商提交；逐项当前核对与全球传播另行判断。"
        DnsBatchTransaction.CONFIRMED_REJECT -> "数据库事务：CONFIRMED_REJECT，明确拒绝，整批未应用。"
        DnsBatchTransaction.UNKNOWN -> if (currentStateReady)
            "数据库事务：UNKNOWN。当前状态已匹配，历史事务仍未知，不归因本客户端。仅允许新的显式草稿→预览→确认，不自动重放；此标记不是发送授权。"
        else "数据库事务：UNKNOWN；当前逐项 MATCHED 不证明历史提交或归因本客户端，也不是数据库部分成功。"
    }, Modifier.testTag("db-outcome"))
    outcome.items.take(visible).forEach { item ->
        Text("${item.ref.kind} #${item.ref.index + 1} · ${item.ref.createName ?: item.ref.recordId ?: "目标未提供"}\nprovider ID：${item.providerId ?: "未提供"}\n当前核对：${item.verification}",
            Modifier.testTag("db-result-${item.ref.kind}-${item.ref.index}"))
    }
    if (outcome.pending.isNotEmpty()) {
        Text("待核对 ${outcome.pending.size} 项；只读核对，不能从 metadata 构造重发。")
        outcome.pending.take(visible).forEach { ref ->
            Text("待核对 UNKNOWN：${ref.kind} #${ref.index + 1}\n目标：${ref.createName ?: ref.recordId ?: "未提供"}\n类型：${ref.createType ?: "未提供"}",
                Modifier.testTag("db-pending-${ref.kind}-${ref.index}"))
        }
    }
    if (outcome.items.size > visible || outcome.pending.size > visible) TextButton(onClick = { visible += 20 }) { Text("查看更多核对项（只读，不重发）") }
}

/** Consume backend-owned complete read evidence; never infer it from matches, payload or hashes. */
private fun DnsBatchOutcome.hasCurrentStateProof() = currentStateVerified && items.isNotEmpty() && pending.isEmpty() &&
    items.all { it.verification == DnsBatchVerification.MATCHED }
