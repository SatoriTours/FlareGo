package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.safeMessage
import com.flarego.core.model.*
import com.flarego.ui.SectionCard
import kotlinx.serialization.json.*

/** The application adapter supplies session-owned evidence for the selected mutation. */
data class DnssecPanelState(
    val scope: ZoneScope? = null,
    val mutation: DnssecMutation = DnssecMutation.UPDATE,
    val snapshot: DnssecSnapshot? = null,
    val snapshotMutation: DnssecMutation? = null,
    val keys: List<DnssecKey> = emptyList(),
    val errors: List<DnssecPanelError> = emptyList(),
    val prepared: DnssecPanelPreview? = null,
    val pendingTasks: List<OperationTask> = emptyList(),
    val loading: Boolean = false,
    val revision: Long = 0,
    val editorRevision: Long = 0,
)

enum class DnssecPanelSource { DNSSEC, ZSK }
data class DnssecPanelError(val scope: ZoneScope, val source: DnssecPanelSource, val message: String)

/** Display-only frozen domain projections; never executable authority. */
data class DnssecPanelPreview(
    val planId: String,
    val revision: Long,
    val preview: DnssecPreview,
    val action: DnssecAction,
    val resourceRef: ResourceRef,
    val before: DnssecDetails,
    val after: DnssecDetails?,
)

sealed interface DnssecPanelIntent {
    data class SelectMutation(val scope: ZoneScope, val mutation: DnssecMutation) : DnssecPanelIntent
    data class Refresh(val scope: ZoneScope, val source: DnssecPanelSource = DnssecPanelSource.DNSSEC) : DnssecPanelIntent
    data class DraftInvalidated(val scope: ZoneScope) : DnssecPanelIntent
    data class Preview(val action: DnssecAction) : DnssecPanelIntent
    /** Only independent user decisions. Shared runtime issues every token and ordinary confirmation. */
    data class Confirm(val planId: String, val revision: Long, val acknowledgedRisks: Set<DnssecRisk>) : DnssecPanelIntent
    data class BackToDraft(val scope: ZoneScope) : DnssecPanelIntent
    data class Cancel(val scope: ZoneScope) : DnssecPanelIntent
    data class Reconcile(val scope: ZoneScope, val taskId: String) : DnssecPanelIntent
}

@Composable
fun DnssecPanel(scope: ZoneScope, state: DnssecPanelState, onIntent: (DnssecPanelIntent) -> Unit, modifier: Modifier = Modifier) {
    val mutation = state.mutation
    val supplied = state.snapshot
    val snapshot = supplied?.takeIf { state.scope == scope && state.snapshotMutation == mutation && it.boundTo(scope) }
    var draft by remember(scope, mutation, state.editorRevision) { mutableStateOf(DnssecDraft()) }
    var requested by remember(scope, mutation, state.editorRevision) { mutableStateOf<DnssecAction?>(null) }
    var inputError by remember(scope, mutation, state.editorRevision) { mutableStateOf<String?>(null) }
    val plan = state.prepared
    var acknowledged by remember(scope, mutation, state.editorRevision, plan?.planId, plan?.revision) { mutableStateOf(emptySet<DnssecRisk>()) }
    val tasks = state.pendingTasks.filter { it.scope == scope &&
        (it.resourceRef == dnssecReference(scope) || it.actionKey in DnssecMutation.entries.map { m -> m.operationId }) }
    val blocked = tasks.any { it.state in unsettledDnssecStates }
    val baseReason = when {
        state.scope != scope -> "账号或站点已变化，等待重新读取当前站点的 DNSSEC 状态。"
        supplied != null && snapshot == null -> "DNSSEC 信息与当前账号、站点归属、字段或所选操作权限不一致，当前只读。"
        snapshot == null -> "尚未验证当前凭据对该站点的 DNSSEC 操作权限，当前只读。"
        snapshot.writeAllowed == null -> "DNSSEC 写权限尚未验证，当前只读；读取成功不证明可写。"
        snapshot.writeAllowed == false -> "没有已验证的 DNSSEC 写权限，当前只读。"
        state.errors.any { it.scope == scope && it.source == DnssecPanelSource.DNSSEC } -> "当前 DNSSEC 读取/权限验证失败，请只读刷新后再操作。"
        blocked -> "此 Zone 的 DNSSEC 请求尚未核对，只能只读核对，不能重放或重新删除。"
        snapshot.details.status in setOf("pending", "pending-disabled") -> "供应商状态仍在等待处理；请显式只读刷新，不自动轮询或重放。"
        snapshot.details.status !in setOf("active", "disabled") -> "当前 DNSSEC 状态缺失、错误或未来未知值，不能建立安全变更，当前只读。"
        else -> null
    }
    val draftReason = when {
        mutation == DnssecMutation.UPDATE && draft.useNsec3 == true && snapshot?.details?.useNsec3 != true && snapshot?.nsec3Available != true ->
            "开启 NSEC3 需要供应商已验证的 Enterprise 资格；当前资格未知或不可用。"
        mutation == DnssecMutation.UPDATE && draft.presigned == true && snapshot?.details?.presigned != true && snapshot?.secondary() != true ->
            "开启预签名需要当前站点及供应商最新详情均确认属于辅助 DNS（secondary）站点，当前不满足。"
        else -> null
    }
    val reason = baseReason ?: draftReason
    val canEdit = baseReason == null && !state.loading
    val canPreview = reason == null && !state.loading
    val validPlan = reason == null && plan != null && snapshot != null && plan.boundTo(scope, mutation, state.revision, snapshot, requested)
    LaunchedEffect(reason, plan, validPlan) {
        if (reason != null || (plan != null && !validPlan)) {
            requested = null
            acknowledged = emptySet()
        }
    }
    val change: (DnssecDraft) -> Unit = {
        requested = null
        acknowledged = emptySet()
        inputError = null
        draft = it
        onIntent(DnssecPanelIntent.DraftInvalidated(scope))
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.scope != scope) {
            Text(baseReason.orEmpty(), Modifier.testTag("ds-readonly-reason"))
            OutlinedButton(onClick = { onIntent(DnssecPanelIntent.Refresh(scope)) }) { Text("加载当前站点 DNSSEC") }
            return@Column
        }
        if (plan != null && validPlan) {
            Text(if (mutation == DnssecMutation.DELETE) "确认独立 DELETE DNSSEC" else "确认 DNSSEC PATCH", style = MaterialTheme.typography.titleLarge)
            Text("预览编号：${plan.planId}\n草稿版本：${plan.revision}", Modifier.testTag("ds-plan"))
            SectionCard {
                Text("连接：${scope.connectionId}\n账号：${scope.account.accountId}\nZone：${plan.preview.domain}\n操作：${plan.preview.mutation}", Modifier.testTag("ds-preview-target"))
                Text("变更前：\n${plan.before.configuration()}\n变更后：\n${plan.after?.configuration() ?: "独立 DELETE 请求，不虚构已删除对象或最终状态"}", Modifier.testTag("ds-preview-values"))
                if (plan.action is DnssecAction.Update) Text("仅提交明确变更：${plan.action.input.fields()}", Modifier.testTag("ds-preview-fields"))
            }
            Text(plan.preview.registrarWarning, Modifier.testTag("ds-registrar-warning"), color = MaterialTheme.colorScheme.error)
            Text(plan.preview.impact, Modifier.testTag("ds-impact"))
            Text(plan.preview.concurrencyWarning, Modifier.testTag("ds-concurrency-warning"))
            plan.preview.risks.sortedBy { it.ordinal }.forEach { risk ->
                Text(risk.description(), color = MaterialTheme.colorScheme.error)
                Row {
                    Checkbox(risk in acknowledged, { checked -> acknowledged = if (checked) acknowledged + risk else acknowledged - risk },
                        enabled = canPreview, modifier = Modifier.testTag("ds-risk-${risk.name}"))
                    Text("我已了解并接受这项具体风险", Modifier.weight(1f).padding(top = 12.dp))
                }
            }
            Button(enabled = canPreview && acknowledged == plan.preview.risks, onClick = {
                if (canPreview && acknowledged == plan.preview.risks)
                    onIntent(DnssecPanelIntent.Confirm(plan.planId, plan.revision, acknowledged.toSet()))
            }, modifier = Modifier.fillMaxWidth().testTag("ds-confirm")) { Text("确认提交") }
            OutlinedButton(onClick = { acknowledged = emptySet(); onIntent(DnssecPanelIntent.BackToDraft(scope)) },
                modifier = Modifier.fillMaxWidth().testTag("ds-back")) { Text("返回编辑") }
            TextButton(onClick = { requested = null; acknowledged = emptySet(); onIntent(DnssecPanelIntent.Cancel(scope)) },
                modifier = Modifier.fillMaxWidth().testTag("ds-cancel")) { Text("取消") }
            return@Column
        }
        if (plan != null) Text("账号、站点、操作、草稿、权限或目标已变化，旧预览已失效，请重新预览。", color = MaterialTheme.colorScheme.error)
        Text("Zone DNSSEC", style = MaterialTheme.typography.titleLarge)
        Text("连接：${scope.connectionId}\n账号：${scope.account.accountId}")
        snapshot?.let { Text("Zone：${it.owner.name}") }
        Text("Cloudflare active 或 HTTP 200 不代表全网 DNSSEC 验证成功；注册商/父区 DS 状态未知且未探测。本应用不自动修改注册商 DS。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mutation == DnssecMutation.UPDATE, {
                requested = null; acknowledged = emptySet(); onIntent(DnssecPanelIntent.SelectMutation(scope, DnssecMutation.UPDATE))
            }, label = { Text("启停与模式 PATCH") }, modifier = Modifier.testTag("ds-select-update"))
            FilterChip(mutation == DnssecMutation.DELETE, {
                requested = null; acknowledged = emptySet(); onIntent(DnssecPanelIntent.SelectMutation(scope, DnssecMutation.DELETE))
            }, label = { Text("独立 DELETE") }, modifier = Modifier.testTag("ds-select-delete"))
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        reason?.let { Text(it, Modifier.testTag("ds-readonly-reason"), color = MaterialTheme.colorScheme.error) }
        if (supplied == null || snapshot != null) state.errors.filter { it.scope == scope }.forEach {
            Text(it.message, Modifier.testTag(if (it.source == DnssecPanelSource.ZSK) "ds-error-zsk" else "ds-error-details"), color = MaterialTheme.colorScheme.error)
        }
        snapshot?.let { snap ->
            SectionCard {
                val d = snap.details
                Text("status：${d.status ?: "未提供"} — ${statusDescription(d.status)}", Modifier.testTag("ds-status"))
                Text("DS：${d.ds ?: "未提供"}", Modifier.testTag("ds-ds"))
                Text("digest：${d.digest ?: "未提供"}", Modifier.testTag("ds-digest"))
                Text("algorithm：${d.algorithm ?: "未提供"}", Modifier.testTag("ds-algorithm"))
                Text("keyTag：${d.keyTag?.toString() ?: "未提供"}", Modifier.testTag("ds-key-tag"))
                Text("当前凭据写权限：${snap.writeAllowed?.toString() ?: "未知"}；权限验证仅适用于当前所选操作")
                Text("NSEC3 开启资格：${snap.nsec3Available?.toString() ?: "未验证"}；资格须经过验证，不能由本机选择套餐来取得。")
            }
        }
        tasks.forEach { task ->
            val lostDelete = task.actionKey == DnssecMutation.DELETE.operationId && task.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING)
            Text(buildString {
                append(if (task.state == TaskState.ACCEPTED) "已受理，等待供应商状态；不代表全网验证成功。" else "操作状态：${task.state}")
                append("\n${task.safeDetail}")
                if (lostDelete) append("\n丢失 DELETE 回执时，即使当前 disabled 或 404 也不能证明删除；可能持续 UNKNOWN，只能只读核对，不再删除。")
                if (task.state in unsettledDnssecStates) append("\n不自动轮询、重放或恢复旧写入；只读核对不保证获得终态。")
            }, Modifier.testTag("ds-task-${task.id}"))
            if (task.state in setOf(TaskState.ACCEPTED, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING))
                OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(DnssecPanelIntent.Reconcile(scope, task.id)) },
                    modifier = Modifier.fillMaxWidth().testTag("ds-reconcile-${task.id}")) { Text("只读核对请求") }
        }
        if (mutation == DnssecMutation.DELETE) {
            Text("这是独立 DELETE，不是关闭开关。已有注册商 DS 时停止签名/删除可能导致验证失败；先在注册商处理并等待父区 DS TTL。预览前不会发送。", color = MaterialTheme.colorScheme.error)
        } else {
            val d = snapshot?.details
            Text("status 只可明确请求 active 或 disabled；关闭只发送 PATCH，绝不自动 DELETE。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("active", "disabled").forEach { value ->
                    FilterChip((draft.status ?: d?.status) == value, { change(draft.copy(status = value)) }, enabled = canEdit && d?.status in setOf("active", "disabled"),
                        label = { Text(if (value == "active") "启用 active" else "关闭 disabled") }, modifier = Modifier.testTag("ds-status-$value"))
                }
            }
            DnssecFlag("multi", "multi-signer", d?.multiSigner, draft.multiSigner, canEdit,
                trueAllowed = true, explanation = "改变多供应商 DNSKEY/ZSK 协调，true/false 均需独立风险决定。") { change(draft.copy(multiSigner = it)) }
            DnssecFlag("presigned", "pre-signed", d?.presigned, draft.presigned, canEdit,
                trueAllowed = snapshot?.secondary() == true || d?.presigned == true,
                explanation = "开启仅限已重新确认的辅助 DNS（secondary）站点。所有记录将 DNS-only，不支持 Secondary DNS Overrides/Load Balancing；primary 需转 DNSKEY/RRSIG/NSEC(NSEC3)，DS 来自 primary signer。AXFR/签名转移健康/外部 DS 未探测；关闭不保证代理自动恢复。") { change(draft.copy(presigned = it)) }
            DnssecFlag("nsec3", "NSEC3", d?.useNsec3, draft.useNsec3, canEdit,
                trueAllowed = snapshot?.nsec3Available == true || d?.useNsec3 == true,
                explanation = "开启需供应商已验证 Enterprise 资格；关闭当前 true 无需开启资格，仍需写权限及对应风险决定。") { change(draft.copy(useNsec3 = it)) }
        }
        inputError?.let { Text(it, Modifier.testTag("ds-input-error"), color = MaterialTheme.colorScheme.error) }
        Button(enabled = canPreview, onClick = {
            if (canPreview && snapshot != null) {
                val action = try {
                    if (mutation == DnssecMutation.DELETE) DnssecAction.Delete(scope)
                    else DnssecAction.Update(scope, draft.changedInput(snapshot.details))
                } catch (failure: Exception) { inputError = safeMessage(failure); null }
                if (action != null) { requested = action; acknowledged = emptySet(); onIntent(DnssecPanelIntent.Preview(action)) }
            }
        }, modifier = Modifier.fillMaxWidth().testTag("ds-preview")) { Text(if (mutation == DnssecMutation.DELETE) "预览独立 DELETE" else "预览 DNSSEC PATCH") }
        TextButton(onClick = { requested = null; acknowledged = emptySet(); onIntent(DnssecPanelIntent.Cancel(scope)) },
            modifier = Modifier.fillMaxWidth().testTag("ds-cancel")) { Text("取消") }
        OutlinedButton(onClick = { onIntent(DnssecPanelIntent.Refresh(scope)) }, modifier = Modifier.fillMaxWidth().testTag("ds-refresh")) { Text("只读刷新 DNSSEC") }
        Text("公开 ZSK（实际集合，无分页）", style = MaterialTheme.typography.titleMedium)
        if (snapshot != null) {
            val keys = state.keys.filter { it.scope == scope }
            if (keys.isEmpty()) Text("当前没有公开 ZSK 数据；不虚构密钥或分页。")
            keys.forEachIndexed { index, key ->
                Text(buildString {
                    append("Name：${key.name ?: "未提供"}\nLocation：${key.location ?: "未提供"}\nTag：${key.tag ?: "未提供"}")
                    key.dnskey?.let { k ->
                        append("\nDNSKEY Algorithm：${k.algorithm ?: "未提供"}，Flags：${k.flags ?: "未提供"}，Protocol：${k.protocol ?: "未提供"}\nPublicKey：${k.publicKey ?: "未提供"}")
                        k.header?.let { append("\nHdr Name：${it.name ?: "未提供"}，Class：${it.recordClass ?: "未提供"}，Rrtype：${it.recordType ?: "未提供"}，Ttl：${it.ttl ?: "未提供"}，Rdlength：${it.rdlength ?: "未提供"}") }
                    }
                    append("\nSigning public key：${key.signingPublicKey ?: "未提供"}")
                }, Modifier.testTag("ds-zsk-$index"))
            }
        }
        OutlinedButton(onClick = { onIntent(DnssecPanelIntent.Refresh(scope, DnssecPanelSource.ZSK)) }, modifier = Modifier.fillMaxWidth().testTag("ds-refresh-zsk")) { Text("只读刷新公开 ZSK") }
    }
}

private data class DnssecDraft(val status: String? = null, val multiSigner: Boolean? = null, val presigned: Boolean? = null, val useNsec3: Boolean? = null) {
    fun changedInput(old: DnssecDetails): DnssecUpdateInput {
        fun bool(value: Boolean?, previous: Boolean?): Boolean? {
            if (value == null || value == previous) return null
            require(previous != null) { "旧值未知，不能推断默认 false 或安全变更" }
            return value
        }
        val changedStatus = status?.takeIf { it != old.status }?.also {
            require(old.status in setOf("active", "disabled") && it in setOf("active", "disabled")) { "状态未知或不允许编辑" }
        }
        return DnssecUpdateInput(changedStatus, bool(multiSigner, old.multiSigner), bool(presigned, old.presigned), bool(useNsec3, old.useNsec3)).also {
            require(it.status != null || it.multiSigner != null || it.presigned != null || it.useNsec3 != null) { "请先明确修改至少一个已知字段；未变化字段不会发送" }
            it.fields()
        }
    }
}

@Composable
private fun DnssecFlag(tag: String, label: String, current: Boolean?, edited: Boolean?, enabled: Boolean,
    trueAllowed: Boolean, explanation: String, change: (Boolean) -> Unit) {
    Text("$label 当前值：${current?.toString() ?: "未知，不能推断 false"}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(true, false).forEach { value ->
            FilterChip((edited ?: current) == value, { change(value) }, enabled = enabled && current != null && (!value || trueAllowed),
                label = { Text(value.toString()) }, modifier = Modifier.testTag("ds-$tag-$value"))
        }
    }
    Text(explanation, style = MaterialTheme.typography.bodySmall)
}

private val unsettledDnssecStates = setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.ACCEPTED, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING)
private fun dnssecReference(scope: ZoneScope) = ResourceRef(scope, "dnssec", scope.zoneId)
private fun DnssecSnapshot.secondary() = owner.type == "secondary" && owner.raw["type"] == JsonPrimitive("secondary")
private fun DnssecSnapshot.boundTo(scope: ZoneScope): Boolean {
    if (owner.ref != scope || details.scope != scope || owner.raw["id"] != JsonPrimitive(scope.zoneId) ||
        owner.raw["name"] != JsonPrimitive(owner.name) || (owner.raw["account"] as? JsonObject)?.get("id") != JsonPrimitive(scope.account.accountId)) return false
    if (listOf("id", "zone_id").any { details.raw.containsKey(it) && details.raw[it] != JsonPrimitive(scope.zoneId) }) return false
    val expected = mapOf("status" to details.status?.let(::JsonPrimitive), "dnssec_multi_signer" to details.multiSigner?.let(::JsonPrimitive),
        "dnssec_presigned" to details.presigned?.let(::JsonPrimitive), "dnssec_use_nsec3" to details.useNsec3?.let(::JsonPrimitive))
    return expected.all { (key, value) -> details.raw[key] == value }
}
private fun actionMutation(action: DnssecAction) = if (action is DnssecAction.Delete) DnssecMutation.DELETE else DnssecMutation.UPDATE
private fun actionRisks(action: DnssecAction): Set<DnssecRisk> = when (action) {
    is DnssecAction.Delete -> setOf(DnssecRisk.DELETE)
    is DnssecAction.Update -> setOfNotNull(if (action.input.status == "disabled") DnssecRisk.DISABLE else null,
        if (action.input.multiSigner != null) DnssecRisk.CHANGE_MULTI_SIGNER else null,
        if (action.input.presigned != null) DnssecRisk.CHANGE_PRESIGNED else null,
        if (action.input.useNsec3 != null) DnssecRisk.CHANGE_NSEC3 else null)
}
private fun DnssecPanelPreview.boundTo(scope: ZoneScope, mutation: DnssecMutation, actualRevision: Long, snapshot: DnssecSnapshot, requested: DnssecAction?): Boolean =
    planId.isNotBlank() && revision == actualRevision && preview.scope == scope && action.scope == scope &&
        preview.domain == snapshot.owner.name && preview.mutation == mutation && actionMutation(action) == mutation &&
        resourceRef == dnssecReference(scope) && before == snapshot.details && action == requested && preview.risks == actionRisks(action) &&
        (after == null || after.scope == scope)
private fun DnssecDetails.configuration() = "status：${status ?: "未知"}\nmulti-signer：${multiSigner ?: "未知"}\npre-signed：${presigned ?: "未知"}\nNSEC3：${useNsec3 ?: "未知"}"
private fun statusDescription(status: String?) = when (status) {
    "active" -> "供应商当前启用；不证明外部 DS 或全网验证"
    "disabled" -> "供应商当前关闭；不证明注册商 DS 已移除"
    "pending" -> "等待供应商启用，尚未完成"
    "pending-disabled" -> "等待供应商关闭，尚未完成"
    "error" -> "供应商报告错误，当前只读"
    null -> "缺失，当前只读"
    else -> "未来未知状态，保留只读"
}
private fun DnssecRisk.description() = when (this) {
    DnssecRisk.DISABLE -> "独立风险：关闭签名。仍有注册商/父区 DS 时可能验证失败，需在注册商处理并等待父区 TTL。"
    DnssecRisk.DELETE -> "独立风险：DELETE DNSSEC。已有 DS 时可能验证失败；不代表私钥物理擦除或全网验证终态。"
    DnssecRisk.CHANGE_MULTI_SIGNER -> "独立风险：改变 multi-signer，包括关闭，会改变多供应商 DNSKEY/ZSK 协调及验证。"
    DnssecRisk.CHANGE_PRESIGNED -> "独立风险：改变 pre-signed，包括关闭，会改变签名来源。开启后所有记录 DNS-only；外部签名转移与 DS 状态未知且未探测。"
    DnssecRisk.CHANGE_NSEC3 -> "独立风险：改变 NSEC3，包括关闭，会影响否定应答签名，需与 primary 签名模式协调。"
}
