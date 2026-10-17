package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.DNS_MUTATION_UNSETTLED
import kotlinx.serialization.json.*
import androidx.compose.ui.Modifier
import com.flarego.core.model.*

data class DnsFilesPanelState(
    val scope: ZoneScope? = null,
    val epoch: Long = 0,
    val snapshot: DnsFileImportState? = null,
    val exportAllowed: Boolean? = null,
    val selection: DnsFilesSelection = DnsFilesSelection.NONE,
    val file: DnsFileRef? = null,
    val proxied: Boolean? = null,
    val prepared: DnsFilesPanelPreview? = null,
    val pendingTasks: List<OperationTask> = emptyList(),
    val outcome: DnsFilesImportObservation? = null,
    val errors: List<DnsFilesPanelError> = emptyList(),
    val exportStage: DnsFilesExportStage = DnsFilesExportStage.IDLE,
    val exportTarget: DnsExportTarget? = null,
    val exportResult: DnsFilesExportObservation? = null,
    val loading: Boolean = false,
    val revision: Long = 0,
    val editorRevision: Long = 0,
)

enum class DnsFilesSelection { NONE, SELECTING, READY, CANCELLED, PERMISSION_LOST, REJECTED }
enum class DnsFilesExportStage { IDLE, SELECTING, READY, TEMPORARY, COMMITTING, SUCCEEDED, CANCELLED, PERMISSION_LOST, FAILED }
enum class DnsFilesSection { IMPORT, EXPORT }
data class DnsFilesPanelError(val scope: ZoneScope, val epoch: Long, val section: DnsFilesSection, val message: String)
data class DnsFilesImportRequest(val scope: ZoneScope, val file: DnsFileRef, val proxied: Boolean)
data class DnsFilesPanelPreview(val planId: String, val revision: Long, val epoch: Long,
    val preview: DnsImportPreview, val request: DnsFilesImportRequest, val resourceRef: ResourceRef)
data class DnsFilesImportObservation(val scope: ZoneScope, val epoch: Long, val outcome: DnsImportOutcome)
data class DnsFilesExportObservation(val epoch: Long, val target: DnsExportTarget, val result: DnsExportResult)

sealed interface DnsFilesPanelIntent {
    data class SelectImportFile(val scope: ZoneScope) : DnsFilesPanelIntent
    data class DraftInvalidated(val scope: ZoneScope) : DnsFilesPanelIntent
    data class OptionsChanged(val scope: ZoneScope, val proxied: Boolean) : DnsFilesPanelIntent
    data class Preview(val request: DnsFilesImportRequest) : DnsFilesPanelIntent
    data class Confirm(val planId: String, val revision: Long) : DnsFilesPanelIntent
    data class BackToDraft(val scope: ZoneScope) : DnsFilesPanelIntent
    data class CancelImport(val scope: ZoneScope) : DnsFilesPanelIntent
    data class Reconcile(val scope: ZoneScope, val taskId: String) : DnsFilesPanelIntent
    data class Refresh(val scope: ZoneScope) : DnsFilesPanelIntent
    data class SelectExportTarget(val scope: ZoneScope) : DnsFilesPanelIntent
    data class Export(val scope: ZoneScope, val target: DnsExportTarget) : DnsFilesPanelIntent
    data class CancelExport(val scope: ZoneScope) : DnsFilesPanelIntent
    /** Page lifecycle only; never deletes persisted tasks or restores executable files/plans. */
    data class Clear(val scope: ZoneScope) : DnsFilesPanelIntent
}

/** Pure UI only. The caller supplies its actual session epoch independently of this snapshot. */
@Composable
fun DnsFilesPanel(scope: ZoneScope, currentEpoch: Long, state: DnsFilesPanelState,
    onIntent: (DnsFilesPanelIntent) -> Unit, modifier: Modifier = Modifier) {
    // A request is remembered from the user's actual Preview click, never reconstructed from a plan.
    var requested by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf<DnsFilesRequestedPreview?>(null) }
    val proxied = state.proxied
    val contextMatches = state.scope == scope && state.epoch == currentEpoch
    val snapshot = state.snapshot
    val owned = contextMatches && snapshot?.owner?.matches(scope) == true
    val errors = if (contextMatches && owned) state.errors.filter { it.scope == scope && it.epoch == currentEpoch } else emptyList()
    val importError = errors.any { it.section == DnsFilesSection.IMPORT }
    val exportError = errors.any { it.section == DnsFilesSection.EXPORT }
    val tasks = if (contextMatches && owned) state.pendingTasks.filter { it.scope == scope && it.state in DNS_MUTATION_UNSETTLED } else emptyList()
    val observation = state.outcome?.takeIf { contextMatches && owned && it.scope == scope && it.epoch == currentEpoch && it.outcome.matches(scope) }
    val unresolved = observation?.outcome is DnsImportOutcome.Unknown || observation?.outcome is DnsImportOutcome.Partial
    val hardBindingAllowed = owned && snapshot?.writeAllowed == true && !importError && tasks.isEmpty() && !unresolved
    val writeAllowed = hardBindingAllowed && !state.loading
    val file = state.file?.takeIf { contextMatches && owned && state.selection == DnsFilesSelection.READY }
    val validFile = file?.validImport() == true
    val actualRequest = if (validFile && proxied != null) DnsFilesImportRequest(scope, file!!, proxied!!) else null
    val plan = state.prepared
    val requestMatches = actualRequest != null && requested?.request == actualRequest && requested?.revision == state.revision
    val planMatches = plan != null && hardBindingAllowed && actualRequest != null && requestMatches &&
        plan.matches(scope, currentEpoch, state.revision, snapshot!!.owner.name, actualRequest)
    // Loss of a binding is irreversible for this Preview click, even if a stale snapshot later returns.
    LaunchedEffect(contextMatches, owned, hardBindingAllowed, file, actualRequest, plan, state.revision) {
        if (!hardBindingAllowed || !requestMatches || (plan != null && !planMatches)) requested = null
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DNS 文件导入 / 导出", Modifier.testTag("df-header"), style = MaterialTheme.typography.titleLarge)
        if (!contextMatches) {
            Text("账号、站点或连接已变化，等待重新读取当前站点。", Modifier.testTag("df-context-mismatch"))
            OutlinedButton(onClick = { onIntent(DnsFilesPanelIntent.Refresh(scope)) }, modifier = Modifier.testTag("df-refresh")) { Text("读取当前站点") }
            return@Column
        }
        if (!owned) {
            Text(if (snapshot == null) "尚无已验证的站点归属与当前凭据权限，当前只读。" else "站点归属与供应商返回的详情不一致，当前只读。", Modifier.testTag("df-owner-mismatch"))
            OutlinedButton(onClick = { onIntent(DnsFilesPanelIntent.Refresh(scope)) }, modifier = Modifier.testTag("df-refresh")) { Text("读取当前站点") }
            return@Column
        }
        Text("Zone：${snapshot!!.owner.name}；这是 DNS 记录文件操作。")
        OutlinedButton(onClick = { onIntent(DnsFilesPanelIntent.Refresh(scope)) }, modifier = Modifier.testTag("df-refresh")) { Text("只读刷新") }
        Text("导入", style = MaterialTheme.typography.titleMedium)
        val readonly = when {
            snapshot.writeAllowed == null -> "DNS 导入写权限未知，需验证当前凭据权限后才可预览。"
            snapshot.writeAllowed != true -> "当前凭据没有已验证的 DNS 导入写权限。"
            tasks.isNotEmpty() || unresolved -> "同 Zone DNS 写入尚未核对：UNKNOWN / PARTIAL 仅只读核对，不重放或重新导入。"
            importError -> "本次导入读取/权限失败，请只读刷新后重新预览。"
            state.loading -> "正在读取当前站点，暂不可预览。"
            else -> null
        }
        readonly?.let { Text(it, Modifier.testTag("df-readonly")) }
        errors.filter { it.section == DnsFilesSection.IMPORT }.forEach { Text(it.message, Modifier.testTag("df-error-IMPORT")) }
        tasks.forEach { task ->
            Text("${task.actionKey} · ${task.state} · ${task.safeDetail}", Modifier.testTag("df-pending-${task.id}"))
            OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(DnsFilesPanelIntent.Reconcile(scope, task.id)) },
                modifier = Modifier.testTag("df-reconcile-${task.id}")) { Text("只读核对任务") }
        }
        observation?.let { Text(it.outcome.description(), Modifier.testTag("df-outcome")) }
        Text(when (state.selection) {
            DnsFilesSelection.NONE -> "尚未选择文件"
            DnsFilesSelection.SELECTING -> "等待文件选择器结果"
            DnsFilesSelection.READY -> if (validFile) "已选择文件" else "文件信息无效：需非空文件、有效校验摘要，最多 262144 字节"
            DnsFilesSelection.CANCELLED -> "文件选择已取消，未导入"
            DnsFilesSelection.PERMISSION_LOST -> "文件读取授权已失效，请重新选择"
            DnsFilesSelection.REJECTED -> "文件已拒绝，不可导入"
        }, Modifier.testTag("df-selection"))
        file?.let { Text(it.summary(), Modifier.testTag("df-file")) }
        OutlinedButton(enabled = writeAllowed, onClick = {
            requested = null
            onIntent(DnsFilesPanelIntent.DraftInvalidated(scope))
            onIntent(DnsFilesPanelIntent.SelectImportFile(scope))
        }, modifier = Modifier.testTag("df-select-file")) { Text("选择 BIND 文件") }
        Text("一般 proxied 选项：请明确选择；文件 cf-proxied 标签优先。")
        listOf(false, true).forEach { value ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = proxied == value, enabled = writeAllowed, onClick = {
                    if (proxied != value) {
                        requested = null
                        onIntent(DnsFilesPanelIntent.DraftInvalidated(scope))
                        onIntent(DnsFilesPanelIntent.OptionsChanged(scope, value))
                    }
                }, modifier = Modifier.testTag("df-proxy-$value"))
                Text(if (value) "true：一般选项启用代理" else "false：一般选项 DNS-only")
            }
        }
        Text("最多 262144 字节；记录数未知，尚未完整解析 BIND，也未向供应商执行试导入。\n" +
            "$" + "INCLUDE 不支持；$" + "ORIGIN / $" + "TTL / $" + "GENERATE 和标签按原字节保留，未知指令未验证。\n" +
            "cf-proxied 标签覆盖一般选项；可能部分导入，回执/总数不证明逐项匹配。\n" +
            "供应商无 CAS；读取到发送仍有外部并发窗口。全 App 滚动 60 秒最多 3 次；其他客户端也可能占用供应商额度。",
            Modifier.testTag("df-import-warnings"))
        if (planMatches) {
            val displayed = plan!!
            Text("${displayed.preview.domain}\n${displayed.preview.file.summary()}\nproxied=${displayed.preview.proxied}\n记录数未知\n" +
                displayed.preview.warnings.joinToString("\n"), Modifier.testTag("df-preview-details"))
            Text("确认将上传当前选中的文件；可能部分导入，不保证逐项语义或全网传播完成。")
            Button(enabled = !state.loading, onClick = {
                requested = null
                onIntent(DnsFilesPanelIntent.Confirm(displayed.planId, displayed.revision))
            }, modifier = Modifier.testTag("df-confirm")) { Text("独立确认导入") }
            OutlinedButton(onClick = { requested = null; onIntent(DnsFilesPanelIntent.BackToDraft(scope)) }, modifier = Modifier.testTag("df-back")) { Text("返回保留文件与选项") }
        } else {
            if (plan != null) Text("预览已失效或与实际文件/选项不匹配，请重新明确预览。", Modifier.testTag("df-preview-invalid"))
            Button(enabled = writeAllowed && actualRequest != null, onClick = {
                actualRequest?.let { requested = DnsFilesRequestedPreview(it,state.revision); onIntent(DnsFilesPanelIntent.Preview(it)) }
            }, modifier = Modifier.testTag("df-preview")) { Text("预览导入") }
        }
        OutlinedButton(onClick = { requested = null; onIntent(DnsFilesPanelIntent.CancelImport(scope)) }, modifier = Modifier.testTag("df-cancel-import")) { Text("取消本次导入") }
        HorizontalDivider()
        Text("只读导出", style = MaterialTheme.typography.titleMedium)
        Text("通过系统文件选择器新建空白导出文件；先暂存供应商返回的纯文本，校验后写入。不会修改导入原文件。系统文件访问无 CAS，无法承诺与其他应用的写入互斥。")
        val exportAllowed = state.exportAllowed == true && !exportError && !state.loading
        if (state.exportAllowed != true) Text(if (state.exportAllowed == null) "当前连接的导出读取条件未知，尚未完成验证。" else "当前连接尚不满足读取并导出的条件。", Modifier.testTag("df-export-readonly"))
        errors.filter { it.section == DnsFilesSection.EXPORT }.forEach { Text(it.message, Modifier.testTag("df-error-EXPORT")) }
        val busy = state.exportStage in setOf(DnsFilesExportStage.SELECTING, DnsFilesExportStage.TEMPORARY, DnsFilesExportStage.COMMITTING)
        Text(state.exportStage.description(), Modifier.testTag("df-export-stage"))
        val target = state.exportTarget?.takeIf { it.id.isNotBlank() && it.displayName.isNotBlank() }
        if (state.exportStage == DnsFilesExportStage.READY && target != null) Text("新空目标：${target.displayName}", Modifier.testTag("df-export-target"))
        OutlinedButton(enabled = exportAllowed && !busy, onClick = { onIntent(DnsFilesPanelIntent.SelectExportTarget(scope)) }, modifier = Modifier.testTag("df-select-export")) { Text("选择新导出目标") }
        if (state.exportStage == DnsFilesExportStage.READY) Button(enabled = exportAllowed && target != null, onClick = {
            target?.let { onIntent(DnsFilesPanelIntent.Export(scope, it)) }
        }, modifier = Modifier.testTag("df-export")) { Text("开始只读导出") }
        val result = state.exportResult
        if (state.exportStage == DnsFilesExportStage.SUCCEEDED && result?.matches(scope, currentEpoch, target) == true)
            Text("导出已完成并提交：${result.result.file.summary()}", Modifier.testTag("df-export-success"))
        if (state.exportStage in setOf(DnsFilesExportStage.SELECTING, DnsFilesExportStage.READY, DnsFilesExportStage.TEMPORARY,
                DnsFilesExportStage.COMMITTING, DnsFilesExportStage.PERMISSION_LOST, DnsFilesExportStage.FAILED))
            OutlinedButton(onClick = { onIntent(DnsFilesPanelIntent.CancelExport(scope)) }, modifier = Modifier.testTag("df-cancel-export")) { Text("取消导出 / 请求清理临时目标") }
    }
}

/** Captured only by the user's actual Preview click; a later plan cannot change its revision. */
private data class DnsFilesRequestedPreview(val request:DnsFilesImportRequest,val revision:Long)

private fun ZoneDetails.matches(scope: ZoneScope): Boolean {
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return ref == scope && name.isNotBlank() && raw.string("id") == scope.zoneId && raw.string("name") == name &&
        (raw["account"] as? JsonObject)?.string("id") == scope.account.accountId
}
private fun DnsFileRef.validImport() = id.isNotBlank() && displayName.isNotBlank() && length in 1..DNS_IMPORT_MAX_BYTES && validHash()
private fun DnsFileRef.validHash() = Regex("[0-9a-fA-F]{64}").matches(sha256)
private fun DnsFileRef.summary() = "$displayName · $length 字节 · SHA-256 ${sha256.take(12)}…"
private fun DnsFilesPanelPreview.matches(scope: ZoneScope, epoch: Long, revision: Long, domain: String, actual: DnsFilesImportRequest) =
    planId.isNotBlank() && this.revision == revision && this.epoch == epoch && request == actual &&
        resourceRef == ResourceRef(scope, "dns_import", scope.zoneId) && preview.scope == scope && preview.domain == domain &&
        preview.file == actual.file && preview.proxied == actual.proxied && preview.recordCount == null
private fun DnsImportOutcome.matches(scope: ZoneScope) = when (this) {
    is DnsImportOutcome.ReportedSuccess -> readback.scope == scope
    is DnsImportOutcome.Partial -> readback?.let { it.scope == scope } ?: true
    is DnsImportOutcome.Unknown -> true
}
private fun DnsImportOutcome.description() = when (this) {
    is DnsImportOutcome.ReportedSuccess -> "供应商回执：added=$added，parsed=$parsed；当前只读记录页观察 ${readback.observedRecords} 条，total=${readback.total ?: "未知"}。不证明逐记录语义匹配或全网传播完成。"
    is DnsImportOutcome.Partial -> "PARTIAL：供应商可能部分导入（added=${added ?: "未知"}，parsed=${parsed ?: "未知"}）；仅只读核对，不恢复旧导入内容或重复导入。"
    is DnsImportOutcome.Unknown -> "UNKNOWN：未取得可信导入回执；仅只读核对，记录总数不能解除未知，不重放。"
}
private fun DnsFilesExportStage.description() = when (this) {
    DnsFilesExportStage.IDLE -> "尚未选择导出目标"
    DnsFilesExportStage.SELECTING -> "等待新空导出目标选择结果"
    DnsFilesExportStage.READY -> "新空目标已选择，等待明确开始导出"
    DnsFilesExportStage.TEMPORARY -> "正在导出至受控临时文件，尚未提交"
    DnsFilesExportStage.COMMITTING -> "正在提交并校验新目标，尚未确认完成"
    DnsFilesExportStage.SUCCEEDED -> "供应商导出与目标提交已返回；结果需绑定当前目标"
    DnsFilesExportStage.CANCELLED -> "导出已取消；清理结果由平台报告，不视为成功"
    DnsFilesExportStage.PERMISSION_LOST -> "目标授权失效；清理可能需要重新授权，不自动重新导出"
    DnsFilesExportStage.FAILED -> "导出失败；不修改原文件，不自动重试"
}
private fun DnsFilesExportObservation.matches(scope: ZoneScope, epoch: Long, target: DnsExportTarget?): Boolean {
    val file = result.file
    val type = result.head.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.singleOrNull()?.substringBefore(';')?.trim()
    return this.epoch == epoch && this.target == target && result.scope == scope && result.head.status == 200 &&
        type.equals("text/plain", true) && result.head.transferredBytes == file.length && file.length >= 0 && file.validHash() &&
        file.id.isNotBlank() && file.displayName.isNotBlank()
}
