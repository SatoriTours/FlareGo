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
import com.flarego.core.model.*
import com.flarego.ui.SectionCard

enum class ZoneFormMode { CREATE, EDIT, DELETE, ACTIVATION }
enum class ZoneEditField { PAUSED, TYPE, VANITY_NAMESERVERS }
data class ZoneFormDraft(
    val name: String = "",
    val type: String = "full",
    val editField: ZoneEditField = ZoneEditField.PAUSED,
    val paused: Boolean = false,
    val vanityNameservers: String = "",
    val typedName: String = "",
)

/** Existing targets must be fresh, owned provider snapshots supplied by the facade. */
@Composable
fun ZoneForm(
    mode: ZoneFormMode,
    account: AccountScope,
    target: ZoneDetails?,
    draft: ZoneFormDraft,
    onDraftChange: (ZoneFormDraft) -> Unit,
    onPreview: (ZoneAction) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val owned = target?.takeIf { it.ref.account == account }
    val ready = mode == ZoneFormMode.CREATE || owned != null
    val validDelete = owned != null && draft.typedName == owned.name && owned.name.isNotEmpty()
    val canPreview = ready && !busy && (mode != ZoneFormMode.DELETE || validDelete) &&
        (mode != ZoneFormMode.ACTIVATION || owned?.status == "pending")
    var error by remember(mode, account, target, draft) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (mode) {
            ZoneFormMode.CREATE -> "创建 Cloudflare Zone"
            ZoneFormMode.EDIT -> "修改 Zone 属性"
            ZoneFormMode.DELETE -> "删除 Cloudflare Zone"
            ZoneFormMode.ACTIVATION -> "请求 Zone 激活检查"
        }, style = MaterialTheme.typography.titleLarge)
        Text("此处管理 Cloudflare Zone，不会购买或注销注册域名。", Modifier.testTag("zone-registration-note"))
        Text("连接：${account.connectionId}\n账号：${account.accountId}")
        if (!ready) Text("等待当前账号最新且归属已验证的 Zone 详情；暂不能预览操作。", color = MaterialTheme.colorScheme.error)
        owned?.let { SectionCard {
            Text("目标 Zone：${it.name}", Modifier.testTag("zone-form-target"))
            Text("Zone ID：${it.ref.zoneId}\n状态：${it.status}\n类型：${it.type}")
        } }
        when (mode) {
            ZoneFormMode.CREATE -> {
                OutlinedTextField(draft.name, { onDraftChange(draft.copy(name = it)) }, label = { Text("完整域名") },
                    modifier = Modifier.fillMaxWidth().testTag("zone-create-name"), enabled = !busy, maxLines = 3)
                ZoneTypeChoices(draft.type, !busy) { onDraftChange(draft.copy(type = it)) }
                Text("创建后按供应商返回的名称服务器或验证步骤激活；不会注册域名。")
            }
            ZoneFormMode.EDIT -> {
                Text("每次 PATCH 只修改一个属性。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(ZoneEditField.PAUSED, "暂停服务", "paused"),
                        Triple(ZoneEditField.TYPE, "Zone 类型", "type"),
                        Triple(ZoneEditField.VANITY_NAMESERVERS, "自定义名称服务器", "vanity")).forEach { (field, label, tag) ->
                        FilterChip(draft.editField == field, { onDraftChange(draft.copy(editField = field)) },
                            label = { Text(label) }, enabled = !busy, modifier = Modifier.testTag("zone-edit-$tag"))
                    }
                }
                when (draft.editField) {
                    ZoneEditField.PAUSED -> Row {
                        Checkbox(draft.paused, { onDraftChange(draft.copy(paused = it)) }, enabled = !busy, modifier = Modifier.testTag("zone-paused"))
                        Text("暂停 Cloudflare 安全与性能服务（保留 DNS）", Modifier.weight(1f).padding(top = 12.dp))
                    }
                    ZoneEditField.TYPE -> ZoneTypeChoices(draft.type, !busy) { onDraftChange(draft.copy(type = it)) }
                    ZoneEditField.VANITY_NAMESERVERS -> {
                        OutlinedTextField(draft.vanityNameservers, { onDraftChange(draft.copy(vanityNameservers = it)) },
                            label = { Text("自定义名称服务器") }, supportingText = { Text("每行一个完整主机名；清空表示移除自定义值") },
                            modifier = Modifier.fillMaxWidth().testTag("zone-vanity-nameservers"), enabled = !busy, minLines = 2, maxLines = 4)
                        Text("仅供应商授权的 Business／Enterprise 套餐可用；实际权限仍由供应商确认。")
                    }
                }
            }
            ZoneFormMode.DELETE -> {
                Text("删除整个 Zone 及其 DNS 配置，可能中断服务；不会注销注册域名。", color = MaterialTheme.colorScheme.error)
                Text("请输入最新供应商详情中的完整域名，必须完全一致：${owned?.name.orEmpty()}")
                OutlinedTextField(draft.typedName, { onDraftChange(draft.copy(typedName = it)) }, label = { Text("完整域名确认") },
                    modifier = Modifier.fillMaxWidth().testTag("zone-delete-name"), enabled = ready && !busy, maxLines = 3)
            }
            ZoneFormMode.ACTIVATION -> Text(
                "仅 pending 状态可请求官方激活检查；提交检查不代表已激活，也不代表 DNS 已传播完成。服务端仍有频率限制。",
                Modifier.testTag("zone-activation-note"))
        }
        error?.let { Text(it, Modifier.testTag("zone-form-error"), color = MaterialTheme.colorScheme.error) }
        Button(enabled = canPreview, onClick = {
            focus.clearFocus()
            if (canPreview) {
                val action = try {
                    when (mode) {
                        ZoneFormMode.CREATE -> ZoneAction.Create(account, ZoneCreateInput(draft.name, draft.type))
                        ZoneFormMode.EDIT -> ZoneAction.Edit(owned!!.ref, when (draft.editField) {
                            ZoneEditField.PAUSED -> ZoneEditInput(paused = draft.paused)
                            ZoneEditField.TYPE -> ZoneEditInput(type = draft.type)
                            ZoneEditField.VANITY_NAMESERVERS -> ZoneEditInput(vanityNameservers = draft.vanityNameservers.lineSequence()
                                .filter { it.isNotBlank() }.map { it.trim() }.toList())
                        })
                        ZoneFormMode.DELETE -> ZoneAction.Delete(owned!!.ref, owned.name, draft.typedName)
                        ZoneFormMode.ACTIVATION -> ZoneAction.ActivationCheck(owned!!.ref)
                    }
                } catch (failure: Exception) { error = safeMessage(failure); null }
                if (action != null) onPreview(action)
            }
        }, modifier = Modifier.fillMaxWidth().testTag("zone-form-preview")) { Text("预览 Zone 变更") }
        OutlinedButton(onClick = { focus.clearFocus(); onCancel() }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("zone-form-cancel")) { Text("取消") }
    }
}

@Composable
private fun ZoneTypeChoices(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Text("Zone 类型")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("full", "partial", "secondary", "internal").forEach { type ->
            FilterChip(selected == type, { onSelect(type) }, enabled = enabled,
                label = { Text(type) }, modifier = Modifier.testTag("zone-type-$type"))
        }
    }
}

@Composable
fun ZoneChangeConfirmation(
    action: ZoneAction,
    preview: ZoneChangePreview,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val bound = action.scope == preview.scope && when (action) {
        is ZoneAction.Delete -> action.name == preview.domain && action.typedName == preview.domain
        is ZoneAction.Create -> action.input.name.equals(preview.domain, ignoreCase = true)
        else -> true
    }
    Column(modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("确认 Zone 变更", style = MaterialTheme.typography.titleLarge)
        SectionCard {
            val account = when (val scope = preview.scope) {
                is AccountScope -> scope
                is ZoneScope -> scope.account
                else -> null
            }
            Text("连接：${account?.connectionId.orEmpty()}\n账号：${account?.accountId.orEmpty()}\nZone：${preview.domain}" +
                ((preview.scope as? ZoneScope)?.let { "\nZone ID：${it.zoneId}" } ?: ""), Modifier.testTag("zone-confirm-target"))
            Text(preview.impact, Modifier.testTag("zone-confirm-impact"))
            when (action) {
                is ZoneAction.Create -> Text("创建类型：${action.input.type}")
                is ZoneAction.Edit -> Text(when {
                    action.input.paused != null -> "仅修改 paused：${action.input.paused}"
                    action.input.type != null -> "仅修改 type：${action.input.type}"
                    else -> "仅修改 vanity_name_servers：${action.input.vanityNameservers.orEmpty().joinToString("\n")}"
                })
                is ZoneAction.Delete -> Text("删除整个 Zone 及其 DNS 配置，可能中断服务；不会注销注册域名。", color = MaterialTheme.colorScheme.error)
                is ZoneAction.ActivationCheck -> Text("仅请求激活检查；结果可能仍为 pending。")
            }
        }
        Text(preview.concurrencyWarning)
        if (!bound) Text("目标或完整域名已变化，请返回重新预览。", color = MaterialTheme.colorScheme.error)
        Button(onClick = { if (bound && !busy) onConfirm() }, enabled = bound && !busy,
            modifier = Modifier.fillMaxWidth().testTag("zone-confirm-submit")) { Text(if (busy) "提交中…" else "确认提交") }
        OutlinedButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("zone-confirm-back")) { Text("返回编辑") }
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("zone-confirm-cancel")) { Text("取消") }
    }
}
