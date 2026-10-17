package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.*
import kotlinx.serialization.json.*
import androidx.compose.ui.Modifier
import com.flarego.core.application.dns.DnsSettingsReadView
import com.flarego.core.model.*

data class DnsSettingsPanelPreview(val planId: String, val revision: Long, val epoch: Long,
    val action: DnsZoneSettingsAction, val preview: DnsZoneSettingsPreview, val ref: ResourceRef)
data class DnsSettingsPanelObservation(val scope: ZoneScope, val epoch: Long, val message: String)
data class DnsZoneSettingsPanelState(
    val scope: ZoneScope? = null, val epoch: Long = 0, val snapshot: DnsZoneSettingsSnapshot? = null,
    /** Actual domain draft published by the caller, not reconstructed from a prepared preview. */
    val draft: DnsZoneSettingsAction? = null,
    val prepared: DnsSettingsPanelPreview? = null, val pendingTasks: List<OperationTask> = emptyList(),
    val observation: DnsSettingsPanelObservation? = null,
    val errors: List<DnsTask7Error> = emptyList(), val loading: Boolean = false,
    val revision: Long = 0, val editorRevision: Long = 0,
)
sealed interface DnsZoneSettingsPanelIntent {
    data class Refresh(val scope: ZoneScope) : DnsZoneSettingsPanelIntent
    data class DraftInvalidated(val scope: ZoneScope) : DnsZoneSettingsPanelIntent
    data class DraftChanged(val action: DnsZoneSettingsAction) : DnsZoneSettingsPanelIntent
    data class Preview(val action: DnsZoneSettingsAction) : DnsZoneSettingsPanelIntent
    data class Confirm(val planId: String, val revision: Long, val acknowledgedRisks: Set<DnsSettingsRisk>) : DnsZoneSettingsPanelIntent
    data class BackToDraft(val scope: ZoneScope) : DnsZoneSettingsPanelIntent
    data class Cancel(val scope: ZoneScope) : DnsZoneSettingsPanelIntent
    data class Reconcile(val scope: ZoneScope, val taskId: String) : DnsZoneSettingsPanelIntent
    data class Clear(val scope: ZoneScope) : DnsZoneSettingsPanelIntent
}
@Composable
fun DnsZoneSettingsPanel(scope: ZoneScope, currentEpoch: Long, state: DnsZoneSettingsPanelState,
    onIntent: (DnsZoneSettingsPanelIntent) -> Unit, modifier: Modifier = Modifier) {
    val context = state.scope == scope && state.epoch == currentEpoch
    val snapshot = state.snapshot
    val owned = context && snapshot?.owner?.task7Matches(scope) == true && snapshot.settings.scope == scope
    val read = snapshot?.settings?.takeIf { owned }?.let(::dnsZoneSettingsView)
    val eligibility = snapshot?.eligibility ?: DnsSettingsEligibility()
    val errors = state.errors.filter { owned && it.scope == scope && it.epoch == currentEpoch && it.section == DnsTask7Section.SETTINGS }
    val tasks = state.pendingTasks.filter { owned && it.scope == scope && it.actionKey in DNS_MUTATION_KEYS && it.state in DNS_MUTATION_UNSETTLED }
    val hardWritable = owned && snapshot?.writeAllowed == true && tasks.isEmpty() && errors.isEmpty()
    val writable = hardWritable && !state.loading
    var edits by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var texts by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var requested by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf<DnsZoneSettingsAction?>(null) }
    val plan = state.prepared
    var acknowledged by remember(scope, currentEpoch, state.editorRevision, plan?.planId, plan?.revision) { mutableStateOf<Set<DnsSettingsRisk>>(emptySet()) }
    var nsType by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf(read?.nameservers?.panelType().orEmpty()) }
    var nsSet by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf((read?.nameservers as? DnsNameservers.Existing)?.nsSet?.toString().orEmpty()) }
    var setId by remember(scope, currentEpoch, state.editorRevision) { mutableStateOf((read?.nameservers as? DnsNameservers.CustomSet)?.id.orEmpty()) }
    fun fieldQualified(key: DnsZoneSettingKey): Boolean = hardWritable && key in eligibility.fields && read != null &&
        read.unknownReasons.keys.none { it == key.wire || it.startsWith("${key.wire}.") } &&
        (key !in setOf(DnsZoneSettingKey.SOA, DnsZoneSettingKey.INTERNAL_DNS) || key in eligibility.nestedPatchSemanticsVerified)
    fun fieldReady(key: DnsZoneSettingKey): Boolean = fieldQualified(key) && !state.loading
    fun qualification(projection: DnsSettingsProjection): String? {
        if (projection.changed.keys.any { wire -> DnsZoneSettingKey.entries.none { it.wire == wire && fieldQualified(it) } }) return "目标字段的权限、资格或安全更新方式尚未验证。"
        val ns = projection.nameservers
        if (ns != null) {
            if (ns.panelType() !in eligibility.nameserverTypes) return "此域名服务器模式的资格尚未验证。"
            if (ns is DnsNameservers.CustomSet && ns.id !in eligibility.ownedCustomSetIds) return "尚未验证所选域名服务器集合归属。"
            if (ns is DnsNameservers.Existing) {
                val selectedSet = ns.nsSet
                if (selectedSet == null) return "请明确选择已验证的集合序号。"
                if (selectedSet !in eligibility.existingNameserverSets[ns.type].orEmpty()) return "尚未验证此域名服务器集合。"
            }
        }
        if (projection.internalDns?.referenceZoneId?.let { it !in eligibility.verifiedReferenceZoneIds } == true) return "尚未验证引用域名的归属与支持关系。"
        return null
    }
    fun parse(values: Map<String, JsonElement>): Result<DnsZoneSettingsAction> = runCatching {
        requireNotNull(read)
        val projection = dnsZoneSettingsDelta(read, panelChanged(values))
        qualification(projection)?.let { throw IllegalArgumentException(it) }
        DnsZoneSettingsAction(scope, projection.changed)
    }
    val parsed = parse(edits)
    val actual = parsed.getOrNull()
    val validPlan = plan != null && hardWritable && actual != null && requested == actual && state.draft == actual &&
        plan.action == actual && plan.planId.isNotBlank() && plan.epoch == currentEpoch && plan.revision == state.revision &&
        runCatching { plan.ref == dnsZoneSettingsResourceRef(actual, read!!) }.getOrDefault(false) &&
        dnsZoneSettingsPreviewMatches(actual, plan.preview, read!!, snapshot!!.owner.name)
    LaunchedEffect(context, owned, hardWritable, actual, state.draft, plan, state.revision) {
        if (!hardWritable || requested != actual || (requested != null && state.draft != actual) || (plan != null && !validPlan)) {
            requested = null; acknowledged = emptySet()
        }
    }
    fun update(next: Map<String, JsonElement>) {
        requested = null; acknowledged = emptySet(); edits = next
        onIntent(DnsZoneSettingsPanelIntent.DraftInvalidated(scope))
        parse(next).getOrNull()?.let { onIntent(DnsZoneSettingsPanelIntent.DraftChanged(it)) }
    }
    fun put(path: String, value: JsonElement) = update(edits + (path to value))
    fun numeric(path: String, value: String) {
        texts = texts + (path to value)
        put(path, runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) })
    }
    fun nameservers(type: String, n: String, id: String) {
        nsType = type; nsSet = n; setId = id
        put("nameservers", buildJsonObject {
            this.put("type", type)
            when (type) {
                "custom" -> this.put("nameserver_set_id", id)
                "custom.account", "custom.tenant", "custom.zone" -> if (n.isNotEmpty()) this.put("ns_set", runCatching { Json.parseToJsonElement(n) }.getOrElse { JsonPrimitive(n) })
            }
        })
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("域名 DNS 配置", Modifier.testTag("dz-header"), style = MaterialTheme.typography.titleLarge)
        if (!context || !owned || read == null) {
            Text(if (!context) "账号或域名已变化，请重新读取" else "尚无可信的域名归属与配置，请重新读取", Modifier.testTag(if (!context) "dz-context-mismatch" else "dz-owner-mismatch"))
            OutlinedButton(onClick = { onIntent(DnsZoneSettingsPanelIntent.Refresh(scope)) }, modifier = Modifier.testTag("dz-refresh")) { Text("读取当前域名") }
            return@Column
        }
        Text("域名：${snapshot!!.owner.name}")
        OutlinedButton(onClick = { onIntent(DnsZoneSettingsPanelIntent.Refresh(scope)) }, modifier = Modifier.testTag("dz-refresh")) { Text("只读刷新配置") }
        if (!writable) Text(when {
            snapshot.writeAllowed != true -> "当前域名写权限未知或不可用，配置仍可只读查看。"
            tasks.isNotEmpty() -> "同域名的DNS写入结果尚未核对，请先只读核对；不要重复提交。"
            else -> "当前配置读取未完成或失败，请刷新后重新预览。"
        }, Modifier.testTag("dz-readonly"))
        errors.forEach { Text(it.message, Modifier.testTag("dz-error")) }
        tasks.forEach { task ->
            Text("${task.state}：${task.safeDetail}；仅只读核对，不重放。", Modifier.testTag("dz-task-${task.id}"))
            OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(DnsZoneSettingsPanelIntent.Reconcile(scope, task.id)) }, modifier = Modifier.testTag("dz-reconcile-${task.id}")) { Text("只读核对") }
        }
        state.observation?.takeIf { it.scope == scope && it.epoch == currentEpoch }?.let { Text(it.message, Modifier.testTag("dz-observation")) }
        DnsZoneSettingKey.entries.forEach { key ->
            Text(panelLabel(key), Modifier.testTag("dz-key-${key.wire}"), style = MaterialTheme.typography.titleMedium)
            val enabled = fieldReady(key)
            if (!enabled) Text(read.unknownReasons.entries.firstOrNull { it.key == key.wire || it.key.startsWith("${key.wire}.") }?.value
                ?: if (key in setOf(DnsZoneSettingKey.SOA, DnsZoneSettingKey.INTERNAL_DNS)) "尚未验证安全更新方式或资格，当前只读。" else "当前字段的写权限或资格尚未验证，当前只读。", Modifier.testTag("dz-reason-${key.wire}"))
            when (key) {
                DnsZoneSettingKey.FLATTEN_ALL_CNAMES, DnsZoneSettingKey.MULTI_PROVIDER, DnsZoneSettingKey.SECONDARY_OVERRIDES -> {
                    val current = when (key) { DnsZoneSettingKey.FLATTEN_ALL_CNAMES -> read.flattenAllCnames; DnsZoneSettingKey.MULTI_PROVIDER -> read.multiProvider; else -> read.secondaryOverrides }
                    Text("当前：${current?.toString() ?: "未提供"}")
                    val selected = (edits[key.wire] as? JsonPrimitive)?.booleanOrNull
                    listOf(false, true).forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == value, enabled = enabled, onClick = { put(key.wire, JsonPrimitive(value)) }, modifier = Modifier.testTag("dz-bool-${key.wire}-$value"))
                        Text(if (value) "启用" else "关闭")
                    } }
                    Text(when (key) {
                        DnsZoneSettingKey.FLATTEN_ALL_CNAMES -> "可能影响依赖原始CNAME返回的第三方验证。"
                        DnsZoneSettingKey.MULTI_PROVIDER -> "涉及多供应商NS及域名激活行为，须验证当前模式支持。"
                        else -> "仅适用于已验证支持的辅助DNS模式；与预签名等冲突需先核实。"
                    })
                }
                DnsZoneSettingKey.NS_TTL -> OutlinedTextField(value = texts["ns_ttl"] ?: read.nsTtl?.content.orEmpty(), onValueChange = { numeric("ns_ttl", it) }, enabled = enabled,
                    label = { Text("NS TTL（30–86400秒）") }, modifier = Modifier.fillMaxWidth().testTag("dz-ns_ttl"))
                DnsZoneSettingKey.ZONE_MODE -> {
                    Text("当前：${read.zoneMode ?: "未提供"}")
                    listOf("standard", "cdn_only", "dns_only").forEach { mode -> Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (edits["zone_mode"] as? JsonPrimitive)?.content == mode, enabled = enabled,
                            onClick = { put("zone_mode", JsonPrimitive(mode)) }, modifier = Modifier.testTag("dz-mode-$mode"))
                        Text(when (mode) { "standard" -> "标准模式"; "cdn_only" -> "仅CDN"; else -> "仅DNS" })
                    } }
                }
                DnsZoneSettingKey.NAMESERVERS -> {
                    Text("当前：${read.nameservers?.panelType() ?: "未提供"}")
                    listOf("cloudflare.standard", "cloudflare.advanced", "custom.account", "custom.tenant", "custom.zone", "custom").forEach { type -> Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = nsType == type, enabled = enabled && type in eligibility.nameserverTypes,
                            onClick = { nameservers(type, "", "") }, modifier = Modifier.testTag("dz-ns-$type"))
                        Text(when (type) { "cloudflare.standard" -> "Cloudflare标准"; "cloudflare.advanced" -> "Cloudflare高级"; "custom.account" -> "账号现有集合"; "custom.tenant" -> "租户现有集合"; "custom.zone" -> "域名现有集合"; else -> "已验证的自定义集合" })
                    } }
                    if (nsType in setOf("custom.account", "custom.tenant", "custom.zone")) OutlinedTextField(value = nsSet, onValueChange = { nameservers(nsType, it, "") }, enabled = enabled,
                        label = { Text("集合序号（1–5，需明确选择）") }, modifier = Modifier.fillMaxWidth().testTag("dz-ns-set"))
                    if (nsType == "custom") OutlinedTextField(value = setId, onValueChange = { nameservers(nsType, "", it) }, enabled = enabled,
                        label = { Text("已验证归属的集合ID") }, modifier = Modifier.fillMaxWidth().testTag("dz-set-id"))
                }
                DnsZoneSettingKey.SOA -> {
                    val soa = read.soa
                    Text("expire=${soa?.expire ?: "未提供"}；min_ttl=${soa?.minTtl ?: "未提供"}；mname=${if (soa?.mname == JsonNull) "供应商分配" else soa?.mname ?: "未提供"}；refresh=${soa?.refresh ?: "未提供"}；retry=${soa?.retry ?: "未提供"}；rname=${soa?.rname ?: "未提供"}；ttl=${soa?.ttl ?: "未提供"}", Modifier.testTag("dz-read-soa"))
                    val numbers = listOf("expire" to soa?.expire, "min_ttl" to soa?.minTtl, "refresh" to soa?.refresh, "retry" to soa?.retry, "ttl" to soa?.ttl)
                    numbers.forEach { (field, value) -> OutlinedTextField(value = texts["soa.$field"] ?: value?.content.orEmpty(), onValueChange = { numeric("soa.$field", it) }, enabled = enabled,
                        label = { Text("SOA $field") }, modifier = Modifier.fillMaxWidth().testTag("dz-soa-$field")) }
                    OutlinedTextField(value = texts["soa.rname"] ?: soa?.rname.orEmpty(), onValueChange = { texts = texts + ("soa.rname" to it); put("soa.rname", JsonPrimitive(it)) }, enabled = enabled,
                        label = { Text("SOA rname") }, modifier = Modifier.fillMaxWidth().testTag("dz-soa-rname"))
                    OutlinedTextField(value = texts["soa.mname"] ?: (soa?.mname as? JsonPrimitive)?.takeIf { it != JsonNull }?.content.orEmpty(), onValueChange = { texts = texts + ("soa.mname" to it); put("soa.mname", JsonPrimitive(it)) }, enabled = enabled,
                        label = { Text("指定SOA mname") }, modifier = Modifier.fillMaxWidth().testTag("dz-soa-mname"))
                    OutlinedButton(enabled = enabled, onClick = { put("soa.mname", JsonNull) }, modifier = Modifier.testTag("dz-soa-mname-null")) { Text("明确由供应商分配mname") }
                }
                DnsZoneSettingKey.INTERNAL_DNS -> {
                    Text("引用域名：${read.internalDns?.referenceZoneId ?: "未提供"}", Modifier.testTag("dz-read-internal_dns"))
                    OutlinedTextField(value = texts["internal_dns.reference_zone_id"] ?: read.internalDns?.referenceZoneId.orEmpty(), onValueChange = {
                        texts = texts + ("internal_dns.reference_zone_id" to it); put("internal_dns.reference_zone_id", JsonPrimitive(it))
                    }, enabled = enabled, label = { Text("已验证关系的引用域名ID") }, modifier = Modifier.fillMaxWidth().testTag("dz-internal-reference"))
                }
            }
        }
        Text("旧版foundation_dns：${read.foundationDns?.toString() ?: "未提供"}；已弃用，仅供查看。", Modifier.testTag("dz-foundation"))
        read.unknownReasons.filterKeys { key -> key !in DnsZoneSettingKey.entries.map { it.wire } && key != "foundation_dns" }.forEach { (key, reason) -> Text("$key：$reason") }
        if (edits.isNotEmpty() && parsed.isFailure) Text(parsed.exceptionOrNull().let { if (it is CloudFailure) it.publicMessage.replace("needs_review", "需要进一步审核") else it?.message ?: "请检查输入" }, Modifier.testTag("dz-validation"))
        Text("仅提交实际明确变更；供应商无CAS，预提交回读不保证与外部并发原子互斥，也不保证全网传播完成。")
        if (validPlan) {
            val displayed = plan!!
            Text("${displayed.preview.domain}\n实际变更：${displayed.preview.projection.changed}\n" + displayed.preview.warnings.joinToString("\n"), Modifier.testTag("dz-preview-details"))
            displayed.preview.before?.let { Text("变更前：${panelViewText(it)}") }
            displayed.preview.after?.let { Text("变更后：${panelViewText(it)}") }
            displayed.preview.risks.forEach { risk -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = risk in acknowledged, enabled = !state.loading, onCheckedChange = { checked -> acknowledged = if (checked) acknowledged + risk else acknowledged - risk }, modifier = Modifier.testTag("dz-risk-${risk.name}"))
                Text(if (risk == DnsSettingsRisk.CHANGE_NAMESERVERS) "我确认更换域名服务器可能影响委派与解析服务" else "我确认更换域名模式可能影响DNS解析或代理服务")
            } }
            Button(enabled = !state.loading && acknowledged.containsAll(displayed.preview.risks), onClick = {
                val decisions = acknowledged.toSet(); requested = null; acknowledged = emptySet()
                onIntent(DnsZoneSettingsPanelIntent.Confirm(displayed.planId, displayed.revision, decisions))
            }, modifier = Modifier.testTag("dz-confirm")) { Text("独立确认配置变更") }
            OutlinedButton(onClick = { requested = null; acknowledged = emptySet(); onIntent(DnsZoneSettingsPanelIntent.BackToDraft(scope)) }, modifier = Modifier.testTag("dz-back")) { Text("返回保留编辑") }
        } else {
            if (plan != null) Text("预览已失效或与当前实际编辑不一致，请重新预览。", Modifier.testTag("dz-preview-invalid"))
            Button(enabled = writable && actual != null, onClick = { actual?.let { requested = it; acknowledged = emptySet(); onIntent(DnsZoneSettingsPanelIntent.Preview(it)) } }, modifier = Modifier.testTag("dz-preview")) { Text("预览DNS配置变更") }
        }
        OutlinedButton(onClick = { requested = null; acknowledged = emptySet(); onIntent(DnsZoneSettingsPanelIntent.Cancel(scope)) }, modifier = Modifier.testTag("dz-cancel")) { Text("取消本次变更") }
    }
}
private fun DnsNameservers.panelType() = when (this) {
    is DnsNameservers.Cloudflare -> if (advanced) "cloudflare.advanced" else "cloudflare.standard"
    is DnsNameservers.Existing -> type
    is DnsNameservers.CustomSet -> "custom"
}
/** Serialize only controlled, explicitly edited paths; shape/diff/number semantics remain core-owned. */
private fun panelChanged(edits: Map<String, JsonElement>): JsonObject = buildJsonObject {
    edits.filterKeys { '.' !in it }.forEach { (key, value) -> put(key, value) }
    listOf("soa", "internal_dns").forEach { key ->
        val leaves = edits.filterKeys { it.startsWith("$key.") }
        if (leaves.isNotEmpty()) put(key, buildJsonObject { leaves.forEach { (path, value) -> put(path.substringAfter('.'), value) } })
    }
}
private fun panelLabel(key: DnsZoneSettingKey) = when (key) {
    DnsZoneSettingKey.FLATTEN_ALL_CNAMES -> "展开所有CNAME"
    DnsZoneSettingKey.MULTI_PROVIDER -> "多DNS供应商"
    DnsZoneSettingKey.NS_TTL -> "域名服务器TTL"
    DnsZoneSettingKey.SECONDARY_OVERRIDES -> "辅助DNS覆盖"
    DnsZoneSettingKey.ZONE_MODE -> "域名模式"
    DnsZoneSettingKey.NAMESERVERS -> "域名服务器"
    DnsZoneSettingKey.SOA -> "SOA配置"
    DnsZoneSettingKey.INTERNAL_DNS -> "内部DNS引用"
}
private fun panelViewText(view: DnsSettingsReadView) = "CNAME展开=${view.flattenAllCnames}，多供应商=${view.multiProvider}，辅助覆盖=${view.secondaryOverrides}，NS TTL=${view.nsTtl}，模式=${view.zoneMode}，域名服务器=${panelNameserverIdentity(view.nameservers)}，SOA=${view.soa?.let { "expire=${it.expire}，min_ttl=${it.minTtl}，refresh=${it.refresh}，retry=${it.retry}，ttl=${it.ttl}，mname=${if (it.mname == JsonNull) "供应商分配" else it.mname}，rname=${it.rname}" } ?: "未提供"}，内部DNS=${view.internalDns?.referenceZoneId}"

private fun panelNameserverIdentity(value: DnsNameservers?): String = when (value) {
    null -> "未提供"
    is DnsNameservers.Cloudflare -> if (value.advanced) "cloudflare.advanced" else "cloudflare.standard"
    is DnsNameservers.Existing -> "${value.type}，集合序号=${value.nsSet?.toString() ?: "未提供"}"
    is DnsNameservers.CustomSet -> "custom，集合ID=${value.id}"
}
