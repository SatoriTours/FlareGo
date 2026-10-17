package com.flarego.ui.zones

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.safeMessage
import com.flarego.core.cloudflare.generated.ZoneSettingSchemas
import com.flarego.core.model.*
import com.flarego.ui.SectionCard
import kotlinx.serialization.json.*

/** Display-only data. The application adapter supplies session-owned snapshots. */
data class ZoneSettingsPanelState(
    val scope: ZoneScope? = null,
    val target: ZoneSettingsTarget = ZoneSettingsTarget.Setting(ZoneSettingKey.SSL),
    val snapshots: Map<ZoneSettingsTarget, ZoneSettingsSnapshot> = emptyMap(),
    val errors: List<ZoneSettingsPanelError> = emptyList(),
    val prepared: ZoneSettingsPanelPreview? = null,
    val pendingTasks: List<OperationTask> = emptyList(),
    val loading: Boolean = false,
    val revision: Long = 0,
    val editorRevision: Long = 0,
)

data class ZoneSettingsPanelError(val scope: ZoneScope, val target: ZoneSettingsTarget, val message: String)

/** Frozen DOMAIN values only, never executable authority or provider raw payload. */
data class ZoneSettingsPanelPreview(
    val planId: String,
    val revision: Long,
    val preview: ZoneSettingsPreview,
    val action: ZoneSettingsAction,
    val resourceRef: ResourceRef,
    val before: JsonElement?,
    val after: JsonElement?,
)

sealed interface ZoneSettingsPanelIntent {
    data class Select(val scope: ZoneScope, val target: ZoneSettingsTarget) : ZoneSettingsPanelIntent
    data class Refresh(val scope: ZoneScope, val target: ZoneSettingsTarget) : ZoneSettingsPanelIntent
    data class DraftInvalidated(val scope: ZoneScope, val target: ZoneSettingsTarget) : ZoneSettingsPanelIntent
    data class Preview(val action: ZoneSettingsAction) : ZoneSettingsPanelIntent
    /** The shared runtime, not UI, issues ordinary confirmation and the ONE selected risk token. */
    data class Confirm(val planId: String, val revision: Long, val acknowledgedRisk: ZoneSettingsRisk? = null) : ZoneSettingsPanelIntent
    data class BackToDraft(val scope: ZoneScope, val target: ZoneSettingsTarget) : ZoneSettingsPanelIntent
    data class Cancel(val scope: ZoneScope, val target: ZoneSettingsTarget) : ZoneSettingsPanelIntent
    data class Reconcile(val scope: ZoneScope, val taskId: String) : ZoneSettingsPanelIntent
}

@Composable
fun ZoneSettingsPanel(
    scope: ZoneScope,
    state: ZoneSettingsPanelState,
    onIntent: (ZoneSettingsPanelIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = state.target
    val supplied = state.snapshots[target]
    val snapshot = supplied?.takeIf { state.scope == scope && it.matches(scope, target) }
    var draft by remember(scope, target, state.editorRevision) { mutableStateOf(SettingsDraft()) }
    var requested by remember(scope, target, state.editorRevision) { mutableStateOf<ZoneSettingsAction?>(null) }
    var error by remember(scope, target, state.editorRevision) { mutableStateOf<String?>(null) }
    val plan = state.prepared
    var acceptedRisk by remember(scope, target, state.editorRevision, plan?.planId, plan?.revision) { mutableStateOf<ZoneSettingsRisk?>(null) }
    val focus = LocalFocusManager.current
    val mutate: (SettingsDraft) -> Unit = { value ->
        requested = null
        acceptedRisk = null
        error = null
        draft = value
        onIntent(ZoneSettingsPanelIntent.DraftInvalidated(scope, target))
    }
    val relevantTasks = state.pendingTasks.filter { it.scope == scope && (it.resourceRef?.scope?.let { refScope -> refScope == scope } != false) && it.affects(target) }
    val blocked = relevantTasks.any { it.blocks() }
    val reason = when {
        state.scope != scope -> "当前站点已变化，等待重新读取当前账号的站点设置。"
        supplied != null && snapshot == null -> "设置与当前账号、站点归属或所选目标不一致，当前只读。"
        snapshot == null -> "尚未验证当前凭据权限与所选设置的可修改资格，当前只读。"
        snapshot.writeAllowed == null -> "未验证当前凭据写权限或设置可修改资格，当前只读。"
        snapshot.writeAllowed == false -> "当前凭据权限或设置资格不允许此修改。"
        snapshot.setting?.editable == false || snapshot.universal?.editable == false -> "供应商明确 editable=false，当前只读。"
        state.errors.any { it.scope == scope && it.target == target } -> "当前目标读取失败，请只读刷新以重新验证；其他设置不受影响。"
        blocked -> "该目标上一请求结果尚未核对，只能只读核对，不能重新发送。"
        target is ZoneSettingsTarget.Setting && unsafeValueReplacement(target.key, snapshot.setting!!.value) ->
            "当前设置值含尚未审核的字段，无法确认替换时会保留全部内容；请使用官方控制台。"
        else -> null
    }
    val canPreview = reason == null && !state.loading
    LaunchedEffect(reason) {
        if (reason != null) {
            requested = null
            acceptedRisk = null
        }
    }
    val validPlan = reason == null && plan != null && snapshot != null && plan.matches(scope, target, state.revision, snapshot, requested)
    LaunchedEffect(validPlan) {
        if (!validPlan) acceptedRisk = null
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.scope != scope) {
            Text(reason.orEmpty(), Modifier.testTag("zs-readonly-reason"))
            OutlinedButton(onClick = { onIntent(ZoneSettingsPanelIntent.Refresh(scope, target)) }) { Text("加载当前站点设置") }
            return@Column
        }
        if (plan != null && validPlan) {
            Text("确认 Zone 设置变更", style = MaterialTheme.typography.titleLarge)
            Text("预览编号：${plan.planId}\n草稿版本：${plan.revision}", Modifier.testTag("zs-preview-plan"))
            SectionCard {
                Text("连接：${scope.connectionId}\n账号：${scope.account.accountId}\nZone：${plan.preview.domain}\n目标：${target.label()}", Modifier.testTag("zs-preview-target"))
                Text("变更前：\n${plan.before.display()}\n变更后：\n${plan.after.display()}", Modifier.testTag("zs-preview-values"))
                if (plan.action is ZoneSettingsAction.Purge) Text(purgeSummary(plan.action.input))
                Text(plan.preview.impact, Modifier.testTag("zs-preview-impact"))
            }
            Text(plan.preview.concurrencyWarning + "\n服务端未提供 CAS，预提交回读不是强原子并发保证。", Modifier.testTag("zs-preview-cas"))
            reason?.let { Text(it, Modifier.testTag("zs-readonly-reason"), color = MaterialTheme.colorScheme.error) }
            val risk = plan.preview.risk
            if (risk != null) {
                Text(when (risk) {
                    ZoneSettingsRisk.SSL_DOWNGRADE -> "独立风险决定：降低 SSL/TLS 保护可能减少加密或证书验证。"
                    ZoneSettingsRisk.DISABLE_UNIVERSAL_SSL -> "独立风险决定：关闭 Universal SSL 可能中断访客 HTTPS。"
                    ZoneSettingsRisk.PURGE_ALL -> "独立风险决定：清全站缓存可能增加源站负载；受理不代表全网完成。"
                }, color = MaterialTheme.colorScheme.error)
                Row {
                    Checkbox(acceptedRisk == risk, { acceptedRisk = if (it) risk else null }, enabled = canPreview,
                        modifier = Modifier.testTag("zs-risk-${risk.name}"))
                    Text("我已了解并接受上述具体风险", Modifier.weight(1f).padding(top = 12.dp))
                }
            }
            Button(enabled = canPreview && (risk == null || acceptedRisk == risk), onClick = {
                if (canPreview && (risk == null || acceptedRisk == risk))
                    onIntent(ZoneSettingsPanelIntent.Confirm(plan.planId, plan.revision, if (risk == null) null else acceptedRisk))
            }, modifier = Modifier.fillMaxWidth().testTag("zs-confirm")) { Text("确认提交") }
            OutlinedButton(onClick = { acceptedRisk = null; onIntent(ZoneSettingsPanelIntent.BackToDraft(scope, target)) },
                modifier = Modifier.fillMaxWidth().testTag("zs-back")) { Text("返回编辑") }
            TextButton(onClick = { requested = null; acceptedRisk = null; onIntent(ZoneSettingsPanelIntent.Cancel(scope, target)) },
                modifier = Modifier.fillMaxWidth().testTag("zs-cancel")) { Text("取消") }
            return@Column
        }
        if (plan != null) Text("账号、站点、所选目标或草稿已变化；旧确认不可使用。", color = MaterialTheme.colorScheme.error)
        Text("Zone 设置、Universal SSL 与缓存", style = MaterialTheme.typography.titleLarge)
        Text("连接：${scope.connectionId}\n账号：${scope.account.accountId}")
        snapshot?.let { Text("Zone：${it.owner.name}") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZoneSettingKey.entries.forEach { key ->
                FilterChip(target == ZoneSettingsTarget.Setting(key), {
                    requested = null; acceptedRisk = null
                    onIntent(ZoneSettingsPanelIntent.Select(scope, ZoneSettingsTarget.Setting(key)))
                }, label = { Text(key.route) }, modifier = Modifier.testTag("zs-key-${key.route}"))
            }
            FilterChip(target == ZoneSettingsTarget.UniversalSsl, { requested = null; acceptedRisk = null; onIntent(ZoneSettingsPanelIntent.Select(scope, ZoneSettingsTarget.UniversalSsl)) },
                label = { Text("Universal SSL") }, modifier = Modifier.testTag("zs-tab-universal"))
            FilterChip(target == ZoneSettingsTarget.Cache, { requested = null; acceptedRisk = null; onIntent(ZoneSettingsPanelIntent.Select(scope, ZoneSettingsTarget.Cache)) },
                label = { Text("清缓存") }, modifier = Modifier.testTag("zs-tab-cache"))
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (supplied == null || snapshot != null) state.errors.filter { it.scope == scope && it.target == target }.forEach {
            Text(it.message, Modifier.testTag("zs-target-error"), color = MaterialTheme.colorScheme.error)
        }
        reason?.let { Text(it, Modifier.testTag("zs-readonly-reason"), color = MaterialTheme.colorScheme.error) }
        snapshot?.let { snap ->
            SectionCard {
                val current = snap.setting?.value ?: snap.universal?.let { JsonPrimitive(it.enabled) }
                if (current != null) Text("当前值：\n${current.display()}", Modifier.testTag("zs-current-value"))
                val editable = snap.setting?.editable ?: snap.universal?.editable
                Text("供应商 editable：${editable?.toString() ?: "未提供"}", Modifier.testTag("zs-editable"))
                Text("当前凭据写权限与设置资格：${snap.writeAllowed?.toString() ?: "未知（默认只读）"}")
                var rawVisible by remember(scope, target) { mutableStateOf(false) }
                TextButton(onClick = { rawVisible = !rawVisible }, modifier = Modifier.testTag("zs-raw-toggle")) { Text("供应商元数据（只读）") }
                if (rawVisible) Text((snap.setting?.raw ?: snap.universal?.raw ?: JsonObject(emptyMap())).toString(),
                    Modifier.testTag("zs-raw-readonly"), style = MaterialTheme.typography.bodySmall)
            }
        }
        relevantTasks.forEach { task ->
            val acceptedPurge = task.state == TaskState.ACCEPTED && task.actionKey == "zone-purge"
            Text(if (acceptedPurge) "缓存请求仅已受理；不代表目标存在、已逐项清除或全网完成。可发起新的显式请求。"
                else "操作状态：${task.state}\n${task.safeDetail}", Modifier.testTag("zs-task-${task.id}"))
            if (!acceptedPurge && task.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.ACCEPTED, TaskState.RECONCILING)) {
                Text("只读核对不保证获得终态；不能确认时继续保留未知状态，不重发。")
                OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(ZoneSettingsPanelIntent.Reconcile(scope, task.id)) },
                    modifier = Modifier.fillMaxWidth().testTag("zs-reconcile-${task.id}")) { Text("只读核对结果") }
            }
        }
        when (target) {
            is ZoneSettingsTarget.Setting -> {
                val schema = ZoneSettingSchemas.input(target.key.route, target.key.generic)?.get("properties")?.jsonObject?.get("value") as? JsonObject
                if (schema == null) Text("该设置尚未支持编辑、已停用或仍待审核，不能修改。")
                else SettingValueEditor(target.key, schema, snapshot?.setting?.value, draft, canPreview, mutate)
            }
            ZoneSettingsTarget.UniversalSsl -> {
                Text("Universal SSL 当前 enabled：${snapshot?.universal?.enabled?.toString() ?: "尚未读取"}；修改资格须由当前凭据权限与供应商信息验证。")
                Row {
                    Checkbox(draft.universalEnabled ?: (snapshot?.universal?.enabled == true), { mutate(draft.copy(universalEnabled = it)) },
                        enabled = canPreview, modifier = Modifier.testTag("zs-universal-enabled"))
                    Text("启用 Universal SSL", Modifier.weight(1f).padding(top = 12.dp))
                }
                Text("关闭需独立风险决定，再普通确认；不会自动发送。")
            }
            ZoneSettingsTarget.Cache -> PurgeEditor(draft, snapshot?.purgeTier, canPreview, mutate)
        }
        error?.let { Text(it, Modifier.testTag("zs-input-error"), color = MaterialTheme.colorScheme.error) }
        Button(enabled = canPreview, onClick = {
            focus.clearFocus()
            if (canPreview && snapshot != null) {
                val action = try {
                    when (target) {
                        is ZoneSettingsTarget.Setting -> ZoneSettingsAction.Update(scope, target.key, settingInput(target.key, snapshot.setting!!.value, draft))
                        ZoneSettingsTarget.UniversalSsl -> ZoneSettingsAction.UniversalSsl(scope, draft.universalEnabled ?: snapshot.universal!!.enabled)
                        ZoneSettingsTarget.Cache -> ZoneSettingsAction.Purge(scope, parsePurge(draft)).also { cachePurgeBody(it.input, snapshot.purgeTier) }
                    }
                } catch (failure: Exception) { error = safeMessage(failure); null }
                if (action != null) { requested = action; acceptedRisk = null; onIntent(ZoneSettingsPanelIntent.Preview(action)) }
            }
        }, modifier = Modifier.fillMaxWidth().testTag("zs-preview")) { Text("预览当前目标变更") }
        OutlinedButton(onClick = { focus.clearFocus(); requested = null; acceptedRisk = null; onIntent(ZoneSettingsPanelIntent.Cancel(scope, target)) },
            modifier = Modifier.fillMaxWidth().testTag("zs-cancel")) { Text("取消") }
        TextButton(onClick = { onIntent(ZoneSettingsPanelIntent.Refresh(scope, target)) }, modifier = Modifier.fillMaxWidth()) { Text("只读刷新当前目标") }
        var audit by remember(scope) { mutableStateOf(false) }
        TextButton(onClick = { audit = !audit }, modifier = Modifier.testTag("zs-audit-toggle")) { Text("其他设置审核状态（只读）") }
        if (audit) ZoneSettingSchemas.genericAudit.filterKeys { id -> ZoneSettingKey.entries.none { it.route == id } }.forEach { (id, entry) ->
            val definition = entry.jsonObject
            Text("$id：${definition["status"]?.jsonPrimitive?.content}，${definition["reason"]?.jsonPrimitive?.content}；拒绝写入。",
                Modifier.testTag("zs-unavailable-$id"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class PurgeMode { ALL, FILES, HOSTS, TAGS, PREFIXES }
private data class SettingsDraft(
    val edits: Map<String, String> = emptyMap(),
    val branch: String? = null,
    val jsonText: String? = null,
    val universalEnabled: Boolean? = null,
    val purgeMode: PurgeMode = PurgeMode.FILES,
    val entries: String = "",
    val headers: String = "{}",
)

@Composable
private fun SettingValueEditor(key: ZoneSettingKey, schema: JsonObject, current: JsonElement?, draft: SettingsDraft,
    enabled: Boolean, change: (SettingsDraft) -> Unit) {
    val branches = schema["oneOf"] as? JsonArray
    val branch = draft.branch ?: if (current is JsonObject) "object" else (current as? JsonPrimitive)?.takeIf { it.isString }?.content
    val chosen = if (branches == null) schema else branches.map { it.jsonObject }.firstOrNull {
        if (branch == "object") it["type"] == JsonPrimitive("object") else (it["enum"] as? JsonArray)?.contains(JsonPrimitive(branch)) == true
    }
    if (branches != null) {
        Text("${key.route} 的受控 value 分支")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            branches.map { it.jsonObject }.forEach { part ->
                val name = if (part["type"] == JsonPrimitive("object")) "object" else part["enum"]!!.jsonArray.first().jsonPrimitive.content
                FilterChip(branch == name, { change(draft.copy(branch = name, edits = emptyMap(), jsonText = null)) }, enabled = enabled,
                    label = { Text(if (name == "object") "结构化 value" else name) }, modifier = Modifier.testTag("zs-branch-$name"))
            }
        }
    }
    if (chosen == null) { Text("请选择已审核的 value 分支；不会默认关闭。") ; return }
    if (chosen["type"] == JsonPrimitive("object")) {
        TextButton(onClick = { change(draft.copy(jsonText = if (draft.jsonText == null) (current as? JsonObject)?.toString() ?: "{}" else null)) },
            enabled = enabled, modifier = Modifier.testTag("zs-json-toggle")) { Text(if (draft.jsonText == null) "编辑已审核 value JSON" else "返回结构化字段") }
        val rawText = draft.jsonText
        if (rawText != null) {
            OutlinedTextField(rawText, { change(draft.copy(jsonText = it)) }, label = { Text("仅当前 setting 的 value JSON") },
                modifier = Modifier.fillMaxWidth().testTag("zs-value-json"), enabled = enabled, minLines = 3, maxLines = 5)
            Text("不是请求编辑器；未知字段会拒绝，不会默认为关闭。")
        } else (chosen["properties"] as? JsonObject)?.forEach { (name, field) ->
            val path = "value.$name"
            val value = draft.edits[path] ?: (current as? JsonObject)?.get(name)?.let { if (it is JsonPrimitive) it.content else it.toString() }
            SettingField(field.jsonObject, path, value, enabled) { change(draft.copy(edits = draft.edits + (path to it))) }
        }
    } else if (branches == null) {
        val value = draft.edits["value"] ?: (current as? JsonPrimitive)?.content
        SettingField(chosen, "value", value, enabled) { change(draft.copy(edits = draft.edits + ("value" to it))) }
    }
}

@Composable
private fun SettingField(schema: JsonObject, path: String, value: String?, enabled: Boolean, onChange: (String) -> Unit) {
    val choices = schema["enum"] as? JsonArray
    val tag = if (path == "value") "zs-value" else "zs-field-${path.replace('.', '-')}"
    when {
        choices != null -> {
            if (path != "value") Text(path)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { option ->
                    val text = option.jsonPrimitive.content
                    FilterChip(value == text, { onChange(text) }, enabled = enabled,
                        label = { Text(text) }, modifier = Modifier.testTag("$tag-$text"))
                }
            }
        }
        schema["type"] == JsonPrimitive("boolean") -> Row {
            Checkbox(value == "true", { onChange(it.toString()) }, enabled = enabled, modifier = Modifier.testTag(tag))
            Text("$path${if (value == null) "（尚未提供，不能推断 false）" else ""}", Modifier.weight(1f).padding(top = 12.dp))
        }
        else -> OutlinedTextField(value.orEmpty(), onChange, enabled = enabled, label = { Text(path) },
            modifier = Modifier.fillMaxWidth().testTag(tag), maxLines = 4,
            supportingText = when (schema["type"]?.jsonPrimitive?.content) {
                "integer", "number" -> ({ Text("数值范围：${schema["minimum"] ?: "未限定"}–${schema["maximum"] ?: "未限定"}；非法输入不会变成 0") })
                "array" -> ({ Text("此字段只接受已审核数组 JSON") })
                else -> null
            })
    }
}

@Composable
private fun PurgeEditor(draft: SettingsDraft, tier: CachePurgeTier?, enabled: Boolean, change: (SettingsDraft) -> Unit) {
    Text("清缓存模式必须显式选择；空或坏输入不会退化为清全站。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PurgeMode.entries.forEach { mode ->
            FilterChip(draft.purgeMode == mode, { change(draft.copy(purgeMode = mode, entries = "", headers = "{}")) },
                enabled = enabled, label = { Text(mode.name.lowercase()) }, modifier = Modifier.testTag("zs-purge-${mode.name.lowercase()}"))
        }
    }
    Text("session 套餐：${tier?.name ?: "未验证"}；URL 上限 ${if (tier == CachePurgeTier.ENTERPRISE) 500 else 100}，其他模式 100。", Modifier.testTag("zs-purge-limits"))
    if (draft.purgeMode == PurgeMode.ALL) Text("请求清除此 Zone 全部缓存；必须先单独接受 purgeAll 风险，再普通确认。", color = MaterialTheme.colorScheme.error)
    else {
        OutlinedTextField(draft.entries, { change(draft.copy(entries = it)) }, enabled = enabled,
            label = { Text(when (draft.purgeMode) { PurgeMode.FILES -> "完整 URL（每行一个）"; PurgeMode.HOSTS -> "主机名（每行一个）"; PurgeMode.TAGS -> "缓存标签（每行一个）"; else -> "主机名/前缀（每行一个）" }) },
            modifier = Modifier.fillMaxWidth().testTag("zs-purge-entries"), minLines = 2, maxLines = 4)
        if (draft.purgeMode == PurgeMode.FILES) {
            OutlinedTextField(draft.headers, { change(draft.copy(headers = it)) }, enabled = enabled,
                label = { Text("可选 cache key headers") }, supportingText = { Text("JSON：URL → headers 对象，仅以上 URL；例如 {\"https://example.com/a\":{\"CF-Device-Type\":\"mobile\"}}") },
                modifier = Modifier.fillMaxWidth().testTag("zs-purge-headers"), minLines = 2, maxLines = 4)
            Text("URL 只进入固定 Cloudflare purge 请求体，不向这些目标发送 Token。")
        }
    }
}

private fun ZoneSettingsSnapshot.matches(scope: ZoneScope, target: ZoneSettingsTarget): Boolean = owner.ref == scope && when (target) {
    is ZoneSettingsTarget.Setting -> setting?.let { it.scope == scope && it.key == target.key } == true && universal == null
    ZoneSettingsTarget.UniversalSsl -> universal?.scope == scope && setting == null
    ZoneSettingsTarget.Cache -> setting == null && universal == null
}

private fun ZoneSettingsTarget.label(): String = when (this) { is ZoneSettingsTarget.Setting -> key.route; ZoneSettingsTarget.UniversalSsl -> "Universal SSL"; ZoneSettingsTarget.Cache -> "缓存" }
private fun targetOf(action: ZoneSettingsAction): ZoneSettingsTarget = when (action) { is ZoneSettingsAction.Update -> ZoneSettingsTarget.Setting(action.key); is ZoneSettingsAction.UniversalSsl -> ZoneSettingsTarget.UniversalSsl; is ZoneSettingsAction.Purge -> ZoneSettingsTarget.Cache }
private fun reference(scope: ZoneScope, target: ZoneSettingsTarget): ResourceRef = when (target) {
    is ZoneSettingsTarget.Setting -> ResourceRef(scope, "zone_setting", target.key.name)
    ZoneSettingsTarget.UniversalSsl -> ResourceRef(scope, "universal_ssl", "universal_ssl")
    ZoneSettingsTarget.Cache -> ResourceRef(scope, "cache_purge", scope.zoneId)
}
private fun ZoneSettingsPanelPreview.matches(scope: ZoneScope, target: ZoneSettingsTarget, actualRevision: Long,
    snapshot: ZoneSettingsSnapshot, requested: ZoneSettingsAction?): Boolean =
    planId.isNotBlank() && revision == actualRevision && preview.scope == scope && action.scope == scope &&
        preview.target == target && targetOf(action) == target && resourceRef == reference(scope, target) &&
        preview.domain == snapshot.owner.name && action == requested &&
        before == (snapshot.setting?.value ?: snapshot.universal?.let { JsonPrimitive(it.enabled) })

private fun JsonElement?.display(): String = when (this) { null -> "未提供"; is JsonPrimitive -> if (isString) content else toString(); else -> toString() }
private fun purgeSummary(input: CachePurgeInput): String = when (input) {
    CachePurgeInput.All -> "目标：整个 Zone 缓存"
    is CachePurgeInput.Files -> "URL 数量：${input.files.size}\n${input.files.joinToString("\n") { it.url }}"
    is CachePurgeInput.Hosts -> "主机数量：${input.hosts.size}\n${input.hosts.joinToString("\n")}"
    is CachePurgeInput.Tags -> "标签数量：${input.tags.size}\n${input.tags.joinToString("\n")}"
    is CachePurgeInput.Prefixes -> "前缀数量：${input.prefixes.size}\n${input.prefixes.joinToString("\n")}"
}

private fun OperationTask.blocks(): Boolean = state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.ACCEPTED, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING) &&
    !(state == TaskState.ACCEPTED && actionKey == "zone-purge")
private fun OperationTask.affects(target: ZoneSettingsTarget): Boolean {
    val ref = resourceRef
    if (ref != null) {
        if (ref == reference(scope as ZoneScope, target)) return true
        if (ref.kind == "zone_setting") {
            val key = ZoneSettingKey.entries.firstOrNull { it.name == ref.externalId }
            if (key != null) {
                val overlapping = setOf(ZoneSettingKey.TRANSFORMATIONS_CONFIG, ZoneSettingKey.IMAGE_RESIZING, ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS, ZoneSettingKey.TRANSFORMATIONS_C2PA)
                return target is ZoneSettingsTarget.Setting && key in overlapping && target.key in overlapping &&
                    (key == ZoneSettingKey.TRANSFORMATIONS_CONFIG || target.key == ZoneSettingKey.TRANSFORMATIONS_CONFIG)
            }
        } else if (ref.kind == "cache_purge" && ref.externalId == (scope as ZoneScope).zoneId) {
            return target == ZoneSettingsTarget.Cache
        } else if (ref.kind == "universal_ssl" && ref.externalId == "universal_ssl") {
            return target == ZoneSettingsTarget.UniversalSsl
        }
    }
    if (actionKey == "zone-purge") return target == ZoneSettingsTarget.Cache
    if (actionKey == "universal-ssl-settings-for-a-zone-edit-universal-ssl-settings") return target == ZoneSettingsTarget.UniversalSsl
    val dedicated = ZoneSettingKey.entries.firstOrNull { !it.generic && it.writeOperation == actionKey }
    if (dedicated != null) return target == ZoneSettingsTarget.Setting(dedicated)
    return target is ZoneSettingsTarget.Setting // Unidentifiable generic metadata conservatively blocks settings only.
}

private fun settingSchema(key: ZoneSettingKey): JsonObject = ZoneSettingSchemas.input(key.route, key.generic)!!.getValue("properties").jsonObject.getValue("value").jsonObject
private fun unsafeValueReplacement(key: ZoneSettingKey, current: JsonElement): Boolean {
    if (current !is JsonObject || ZoneSettingSchemas.definition(key.route, key.generic)?.get("partialObjectPatch") == JsonPrimitive(true)) return false
    val schema = settingSchema(key)
    val objectSchema = if (schema["type"] == JsonPrimitive("object")) schema else (schema["oneOf"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { it["type"] == JsonPrimitive("object") }
    return objectSchema == null || current.keys.any { it !in objectSchema["properties"]!!.jsonObject }
}

private fun parsedField(schema: JsonObject, text: String): JsonElement = when (schema["type"]?.jsonPrimitive?.content) {
    "string" -> JsonPrimitive(text)
    "integer" -> {
        require(Regex("-?(0|[1-9][0-9]*)").matches(text)) { "请输入有效整数；不会自动转换为 0" }
        JsonPrimitive(requireNotNull(text.toLongOrNull()) { "数值过大；原始输入已保留" })
    }
    "boolean" -> { require(text in setOf("true", "false")) { "请选择明确布尔值，不推断关闭" }; JsonPrimitive(text == "true") }
    "array" -> Json.parseToJsonElement(text).also { require(it is JsonArray) { "该字段必须是 JSON 数组" } }
    else -> error("未审核的 value 类型不能编辑")
}

private fun settingInput(key: ZoneSettingKey, current: JsonElement, draft: SettingsDraft): JsonObject {
    val schema = settingSchema(key)
    val desired = if (draft.jsonText != null) Json.parseToJsonElement(draft.jsonText) else {
        val branches = schema["oneOf"] as? JsonArray
        val branch = draft.branch ?: if (current is JsonObject) "object" else (current as? JsonPrimitive)?.content
        val chosen = if (branches == null) schema else branches.map { it.jsonObject }.firstOrNull {
            if (branch == "object") it["type"] == JsonPrimitive("object") else (it["enum"] as? JsonArray)?.contains(JsonPrimitive(branch)) == true
        } ?: throw IllegalArgumentException("请选择已审核的 value 分支")
        if (chosen["type"] == JsonPrimitive("object")) {
            val props = chosen.getValue("properties").jsonObject
            val partial = ZoneSettingSchemas.definition(key.route, key.generic)?.get("partialObjectPatch") == JsonPrimitive(true)
            val fields = if (partial) mutableMapOf<String, JsonElement>() else (current as? JsonObject).orEmpty().filterKeys { it in props }.toMutableMap()
            draft.edits.forEach { (path, text) ->
                require(path.startsWith("value."))
                val name = path.removePrefix("value.")
                val field = props[name] as? JsonObject ?: throw IllegalArgumentException("未知字段不能发送")
                fields[name] = parsedField(field, text)
            }
            JsonObject(fields)
        } else if (branches != null) JsonPrimitive(requireNotNull(branch))
        else parsedField(chosen, draft.edits["value"] ?: (current as? JsonPrimitive)?.content ?: throw IllegalArgumentException("请填写明确的 value"))
    }
    return buildJsonObject { put("value", desired) }.also { validateZoneSettingInput(key, it) }
}

private fun parsePurge(draft: SettingsDraft): CachePurgeInput {
    val entries = draft.entries.lineSequence().filter(String::isNotEmpty).toList()
    return when (draft.purgeMode) {
        PurgeMode.ALL -> CachePurgeInput.All
        PurgeMode.FILES -> {
            val map = Json.parseToJsonElement(draft.headers) as? JsonObject ?: throw IllegalArgumentException("headers 必须为 URL → headers 对象")
            require(map.keys.all { it in entries }) { "headers 只能对应当前填写的 URL" }
            CachePurgeInput.Files(entries.map { url ->
                val headers = map[url]?.let { it as? JsonObject ?: throw IllegalArgumentException("每个 URL 的 headers 必须为对象") }
                CachePurgeFile(url, headers.orEmpty().mapValues { (_, item) ->
                    require(item is JsonPrimitive && item.isString) { "headers 值必须为字符串" }; item.content
                })
            })
        }
        PurgeMode.HOSTS -> CachePurgeInput.Hosts(entries)
        PurgeMode.TAGS -> CachePurgeInput.Tags(entries)
        PurgeMode.PREFIXES -> CachePurgeInput.Prefixes(entries)
    }
}
