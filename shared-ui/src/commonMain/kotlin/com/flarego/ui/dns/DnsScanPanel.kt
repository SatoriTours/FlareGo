package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.ExecutionReceipt
import kotlinx.serialization.json.*
import androidx.compose.ui.Modifier
import com.flarego.core.model.*

enum class DnsTask7Section { SCAN_TRIGGER, SCAN_REVIEW, SCAN_APPLY, USAGE, SETTINGS }
data class DnsTask7Error(val scope: ZoneScope, val epoch: Long, val section: DnsTask7Section, val message: String)
data class DnsScanPanelPreview(val planId: String, val revision: Long, val epoch: Long,
    val action: DnsScanAction, val preview: DnsScanPreview, val ref: ResourceRef)
data class DnsScanUsage(val epoch: Long, val value: DnsUsage)
data class DnsScanPanelOutcome(val scope: ZoneScope, val epoch: Long, val value: DnsScanApplyOutcome)
data class DnsScanPanelState(
    val scope: ZoneScope? = null, val epoch: Long = 0,
    val snapshot: DnsScanSnapshot? = null, val constraints: DnsConstraints = DnsConstraints(),
    val reviewLoaded: Boolean = false,
    val selection: DnsScanReviewInput = DnsScanReviewInput(emptyList(), emptyList(), emptyMap()),
    val usage: DnsScanUsage? = null, val prepared: DnsScanPanelPreview? = null,
    val pendingTasks: List<OperationTask> = emptyList(), val outcome: DnsScanPanelOutcome? = null,
    val errors: List<DnsTask7Error> = emptyList(), val loading: Set<DnsTask7Section> = emptySet(),
    val revision: Long = 0, val editorRevision: Long = 0,
)
sealed interface DnsScanPanelIntent {
    data class RefreshReview(val scope: ZoneScope) : DnsScanPanelIntent
    data class RefreshUsage(val scope: ZoneScope) : DnsScanPanelIntent
    data class DraftInvalidated(val scope: ZoneScope) : DnsScanPanelIntent
    data class SelectionChanged(val scope: ZoneScope, val input: DnsScanReviewInput) : DnsScanPanelIntent
    data class Preview(val action: DnsScanAction) : DnsScanPanelIntent
    data class Confirm(val planId: String, val revision: Long) : DnsScanPanelIntent
    data class BackToDraft(val scope: ZoneScope) : DnsScanPanelIntent
    data class Cancel(val scope: ZoneScope) : DnsScanPanelIntent
    data class Reconcile(val scope: ZoneScope, val taskId: String) : DnsScanPanelIntent
    /** Clears only transient UI; never deletes durable tasks or clears mutation gates. */
    data class Clear(val scope: ZoneScope) : DnsScanPanelIntent
}
@Composable
fun DnsScanPanel(scope: ZoneScope, currentEpoch: Long, state: DnsScanPanelState,
    onIntent: (DnsScanPanelIntent) -> Unit, modifier: Modifier = Modifier) {
    var requested by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf<DnsScanAction?>(null) }
    val context = state.scope == scope && state.epoch == currentEpoch
    val snapshot = state.snapshot
    val owner = snapshot?.owner
    val owned = context && owner?.task7Matches(scope) == true
    val errors = state.errors.filter { owned && it.scope == scope && it.epoch == currentEpoch }
    fun failed(section: DnsTask7Section) = errors.any { it.section == section }
    val tasks = state.pendingTasks.filter { owned && it.scope == scope }
    val mutationBlocked = tasks.any { it.actionKey in DNS_MUTATION_KEYS && it.state in DNS_MUTATION_UNSETTLED }
    val triggerBlocked = tasks.any { it.actionKey == DNS_SCAN_TRIGGER_OPERATION && it.state in DNS_MUTATION_UNSETTLED && it.state != TaskState.ACCEPTED }
    val outcome = state.outcome?.takeIf { owned && it.scope == scope && it.epoch == currentEpoch }
    val unknownApply = outcome?.value?.receipt.let { it is ExecutionReceipt.Unknown || it is ExecutionReceipt.Partial }
    val rowsResult = runCatching { snapshot?.candidates.orEmpty().map { dnsScanCandidateView(it, owner!!.name, state.constraints) } }
    val rows = rowsResult.getOrDefault(emptyList())
    val validRows = rowsResult.isSuccess && rows.all { it.scope == scope && it.view.id == it.id } && rows.map { it.id }.distinct().size == rows.size
    val triggerAllowed = owned && snapshot?.writeAllowed == true && !triggerBlocked && !failed(DnsTask7Section.SCAN_TRIGGER)
    val applyAllowed = owned && snapshot?.writeAllowed == true && state.reviewLoaded && validRows && !mutationBlocked && !unknownApply &&
        !failed(DnsTask7Section.SCAN_REVIEW) && !failed(DnsTask7Section.SCAN_APPLY)
    // The app also uses SCAN_REVIEW for prepare. Usage/settings loading is unrelated to this write.
    val writeBusy = state.loading.any { it == DnsTask7Section.SCAN_TRIGGER || it == DnsTask7Section.SCAN_REVIEW || it == DnsTask7Section.SCAN_APPLY }
    val canTrigger = triggerAllowed && !writeBusy
    val canApply = applyAllowed && !writeBusy
    val selection = state.selection
    val selectedIds = selection.acceptIds + selection.rejectIds
    val selectionReady = selectedIds.isNotEmpty() && selectedIds.distinct().size == selectedIds.size &&
        selection.expectedCandidateHashes.keys == selectedIds.toSet() && selectedIds.all { id ->
            rows.singleOrNull { it.id == id }?.let { row -> row.selectionHash == selection.expectedCandidateHashes[id] &&
                (id !in selection.acceptIds || (row.acceptInput != null && row.acceptUnavailableReason == null)) } == true
        }
    val plan = state.prepared
    val actual = when (requested) {
        is DnsScanAction.Trigger -> DnsScanAction.Trigger(scope)
        is DnsScanAction.Apply -> DnsScanAction.Apply(scope, selection)
        else -> null
    }
    val enabled = if (actual is DnsScanAction.Trigger) triggerAllowed else applyAllowed && selectionReady
    val validPlan = plan != null && enabled && actual != null && requested == actual && plan.action == actual &&
        plan.planId.isNotBlank() && plan.epoch == currentEpoch && plan.revision == state.revision &&
        plan.ref == ResourceRef(scope, if (actual is DnsScanAction.Trigger) "dns_scan_trigger" else "dns_scan_review", scope.zoneId) &&
        dnsScanPreviewMatches(actual, plan.preview, rows, owner!!.name)
    LaunchedEffect(context, owned, enabled, selection, rows, plan, state.revision) {
        if (!owned || !enabled || requested != actual || (plan != null && !validPlan)) requested = null
    }
    fun choose(id: String, accept: Boolean) {
        requested = null
        val old = if (accept) selection.acceptIds else selection.rejectIds
        val added = if (id in old) old - id else old + id
        val accepts = if (accept) added else selection.acceptIds - id
        val rejects = if (accept) selection.rejectIds - id else added
        val hashes = (accepts + rejects).associateWith { key -> rows.single { it.id == key }.selectionHash }
        onIntent(DnsScanPanelIntent.DraftInvalidated(scope))
        onIntent(DnsScanPanelIntent.SelectionChanged(scope, DnsScanReviewInput(accepts, rejects, hashes)))
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("扫描候选记录与用量", Modifier.testTag("ds-header"), style = MaterialTheme.typography.titleLarge)
        if (!context || !owned) {
            Text(if (!context) "账号或域名已变化，请重新读取" else "尚无可信的域名归属信息，请重新读取", Modifier.testTag(if (!context) "ds-context-mismatch" else "ds-owner-mismatch"))
            OutlinedButton(onClick = { onIntent(DnsScanPanelIntent.RefreshReview(scope)) }, modifier = Modifier.testTag("ds-refresh-review")) { Text("读取当前域名") }
            return@Column
        }
        Text("域名：${owner!!.name}")
        Text("记录用量", style = MaterialTheme.typography.titleMedium)
        state.usage?.takeIf { it.epoch == currentEpoch && it.value.scope == scope }?.let {
            Text("记录用量：${it.value.recordUsage}；" + (it.value.recordQuota?.let { q -> "限额：$q" } ?: "限额未提供"), Modifier.testTag("ds-usage"))
        }
        errors.filter { it.section == DnsTask7Section.USAGE }.forEach { Text(it.message, Modifier.testTag("ds-error-USAGE")) }
        OutlinedButton(enabled = DnsTask7Section.USAGE !in state.loading, onClick = { onIntent(DnsScanPanelIntent.RefreshUsage(scope)) }, modifier = Modifier.testTag("ds-refresh-usage")) { Text("刷新记录用量") }
        Text("扫描只发现候选，不自动添加DNS记录。请求受理不代表扫描结束；请主动查看当前发现的记录。")
        errors.filter { it.section != DnsTask7Section.USAGE && it.section != DnsTask7Section.SETTINGS }.forEach { Text(it.message, Modifier.testTag("ds-error-${it.section}")) }
        if (snapshot!!.writeAllowed != true) Text("当前域名的写权限未知或不可用；仍可只读查看候选和用量。", Modifier.testTag("ds-readonly"))
        tasks.forEach { task ->
            val acceptedTrigger = task.actionKey == DNS_SCAN_TRIGGER_OPERATION && task.state == TaskState.ACCEPTED
            Text(if (acceptedTrigger) "扫描请求已受理；尚无扫描结束证明" else "${task.state}：${task.safeDetail}；仅按已确认事实展示，不保证全网传播完成。", Modifier.testTag("ds-task-${task.id}"))
            if (task.state in DNS_MUTATION_UNSETTLED) OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(DnsScanPanelIntent.Reconcile(scope, task.id)) }, modifier = Modifier.testTag("ds-reconcile-${task.id}")) { Text("只读核对") }
        }
        outcome?.let { observed ->
            val receipt = observed.value.receipt
            Text(when (receipt) {
                is ExecutionReceipt.Unknown -> "UNKNOWN：缺少可信审核回执；候选消失或记录数量不能证明此前操作成功，不要重复提交。"
                is ExecutionReceipt.Partial -> "PARTIAL：仅有部分审核回执；只读核对，不保证整批原子应用，不重放。"
                is ExecutionReceipt.Accepted -> "审核请求已受理，等待供应商状态；不保证全网传播完成。"
                is ExecutionReceipt.Completed -> "供应商已返回审核回执；这是回执与当前观察，不证明全网传播完成或历史执行者。"
                is ExecutionReceipt.Rejected -> "供应商已明确拒绝本次审核。"
            }, Modifier.testTag("ds-outcome"))
            observed.value.accepted.filter { it.scope == scope }.forEach { doc -> runCatching { dnsScanRecordView(doc) }.getOrNull()?.let { Text(task7RecordText(it)) } }
            if (observed.value.rejected.isNotEmpty()) Text("回执拒绝候选：${observed.value.rejected.joinToString()}")
        }
        Button(enabled = canTrigger, onClick = { val action = DnsScanAction.Trigger(scope); requested = action; onIntent(DnsScanPanelIntent.Preview(action)) }, modifier = Modifier.testTag("ds-preview-trigger")) { Text("预览扫描请求") }
        OutlinedButton(enabled = DnsTask7Section.SCAN_REVIEW !in state.loading, onClick = { onIntent(DnsScanPanelIntent.RefreshReview(scope)) }, modifier = Modifier.testTag("ds-refresh-review")) { Text("查看当前候选记录") }
        if (!validRows) Text("候选身份或内容无法安全识别，请重新读取；当前不能审核。", Modifier.testTag("ds-candidate-error"))
        if (state.reviewLoaded && rows.isEmpty() && validRows) Text("目前未发现候选记录；这不代表扫描结束。", Modifier.testTag("ds-empty"))
        rows.forEach { row ->
            Text(task7RecordText(row.view), Modifier.testTag("ds-row-${row.id}"))
            row.acceptUnavailableReason?.let { Text(it.replace("needs_review", "需要进一步审核"), Modifier.testTag("ds-reason-${row.id}")) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = row.id in selection.acceptIds, enabled = canApply && row.acceptInput != null && row.acceptUnavailableReason == null,
                    onCheckedChange = { choose(row.id, true) }, modifier = Modifier.testTag("ds-accept-${row.id}")); Text("接受此记录")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = row.id in selection.rejectIds, enabled = canApply, onCheckedChange = { choose(row.id, false) }, modifier = Modifier.testTag("ds-reject-${row.id}")); Text("拒绝此候选")
            }
        }
        Text("已选接受 ${selection.acceptIds.size}；拒绝 ${selection.rejectIds.size}。审核可能永久接受/拒绝候选；无CAS，仍有外部并发窗口。")
        Button(enabled = canApply && selectionReady, onClick = { val action = DnsScanAction.Apply(scope, selection); requested = action; onIntent(DnsScanPanelIntent.Preview(action)) }, modifier = Modifier.testTag("ds-preview-apply")) { Text("预览候选审核") }
        if (validPlan) {
            val displayed = plan!!
            Text("${displayed.preview.domain}\n" + (if (displayed.action is DnsScanAction.Trigger) "启动扫描，不自动接受记录" else "接受：${displayed.preview.acceptIds.joinToString()}\n拒绝：${displayed.preview.rejectIds.joinToString()}") +
                "\n" + displayed.preview.warnings.joinToString("\n"), Modifier.testTag("ds-preview"))
            displayed.preview.records.forEach { doc -> Text(task7RecordText(dnsScanRecordView(doc))) }
            Button(enabled = !writeBusy, onClick = { requested = null; onIntent(DnsScanPanelIntent.Confirm(displayed.planId, displayed.revision)) }, modifier = Modifier.testTag("ds-confirm")) { Text("独立确认本次操作") }
            OutlinedButton(onClick = { requested = null; onIntent(DnsScanPanelIntent.BackToDraft(scope)) }, modifier = Modifier.testTag("ds-back")) { Text("返回保留选择") }
        } else if (plan != null) Text("预览与当前选择或上下文不一致，请重新预览。", Modifier.testTag("ds-preview-invalid"))
        OutlinedButton(onClick = { requested = null; onIntent(DnsScanPanelIntent.Cancel(scope)) }, modifier = Modifier.testTag("ds-cancel")) { Text("取消本次操作") }
    }
}
internal fun ZoneDetails.task7Matches(scope: ZoneScope): Boolean {
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ref == scope && name.isNotBlank() && raw.text("id") == scope.zoneId && raw.text("name") == name &&
        (raw["account"] as? JsonObject)?.text("id") == scope.account.accountId
}
private fun task7RecordText(view: DnsRecordView): String =
    "${view.name} · ${view.type} · ID ${view.id}\n" + view.fields.entries.joinToString("\n") { (key, value) -> "$key: $value" } +
        (view.readOnlyReason?.let { "\n$it" } ?: "")
