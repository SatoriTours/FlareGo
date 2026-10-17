package com.flarego.ui.zones

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.zones.*
import com.flarego.core.model.*
import com.flarego.ui.SectionCard
import com.flarego.ui.management.ZoneManagementSlots
import com.flarego.ui.management.ZoneManagementTab

@Composable
fun ZonePage(
    state: ZonePageState,
    onIntent: (ZoneIntent) -> Unit,
    modifier: Modifier = Modifier,
    dnsContent: @Composable (ZoneDetails) -> Unit = {},
 ) = ZonePageContent(state,onIntent,modifier,null,dnsContent)

/** Required slots keep the original trailing-lambda overload source-compatible. */
@Composable
fun ZonePage(
    state:ZonePageState,
    onIntent:(ZoneIntent)->Unit,
    slots:ZoneManagementSlots,
    modifier:Modifier=Modifier,
    dnsContent:@Composable (ZoneDetails)->Unit = {},
) = ZonePageContent(state,onIntent,modifier,slots,dnsContent)

@Composable
private fun ZonePageContent(
    state:ZonePageState,onIntent:(ZoneIntent)->Unit,modifier:Modifier,
    slots:ZoneManagementSlots?,dnsContent:@Composable (ZoneDetails)->Unit,
) {
    val account = state.account
    val editorMode = state.editorMode
    val selected = state.selected?.takeIf { it.ref.account == account }
    val editorTarget = state.editorTarget?.takeIf { it.ref.account == account }
    val task = state.pendingTask?.takeIf { task -> when (val scope = task.scope) {
        is AccountScope -> scope == account
        is ZoneScope -> scope.account == account
        else -> false
    } }
    val blocked = task?.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.ACCEPTED, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING)
    var draft by remember(account, state.editorRevision, state.editorMode, editorTarget?.ref, editorTarget?.name) {
        val action = state.draft
        mutableStateOf(when (action) {
            is ZoneAction.Create -> ZoneFormDraft(name = action.input.name, type = action.input.type)
            is ZoneAction.Edit -> ZoneFormDraft(type = action.input.type ?: editorTarget?.type ?: "full",
                paused = action.input.paused ?: editorTarget?.paused ?: false,
                editField = when { action.input.type != null -> ZoneEditField.TYPE; action.input.vanityNameservers != null -> ZoneEditField.VANITY_NAMESERVERS; else -> ZoneEditField.PAUSED },
                vanityNameservers = action.input.vanityNameservers.orEmpty().joinToString("\n"))
            is ZoneAction.Delete -> ZoneFormDraft(typedName = action.typedName)
            else -> ZoneFormDraft(type = editorTarget?.type ?: "full", paused = editorTarget?.paused ?: false)
        })
    }
    var localTab by remember(account, selected?.ref) { mutableStateOf("details") }
    val tab = slots?.selectedTab?.name?.lowercase() ?: localTab
    Column(modifier.fillMaxSize()) {
        if (state.demo) Text("示例 · 未调用云 API", Modifier.padding(12.dp).testTag("zone-demo-notice"))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, Modifier.padding(12.dp).testTag("zone-page-error"), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            val prepared = state.preparedChange
            when {
                account == null -> Text("请选择账号后加载 Cloudflare Zone。", Modifier.padding(16.dp))
                prepared != null -> {
                    val previewAccount = when (val scope = prepared.preview.scope) { is AccountScope -> scope; is ZoneScope -> scope.account; else -> null }
                    if (previewAccount == account && prepared.revision == state.revision && !blocked) ZoneChangeConfirmation(
                        prepared.action, prepared.preview,
                        onConfirm = {
                            if (!state.loading) onIntent(ZoneIntent.Confirm(prepared.id, prepared.revision,
                                (prepared.action as? ZoneAction.Delete)?.typedName))
                        }, onBack = { onIntent(ZoneIntent.BackToDraft) }, onCancel = { onIntent(ZoneIntent.Cancel) }, busy = state.loading)
                    else Column(Modifier.padding(16.dp)) {
                        Text("预览已失效或上一操作尚未核对。")
                        ZoneTaskSummary(task, onIntent)
                        OutlinedButton(onClick = { onIntent(ZoneIntent.BackToDraft) }) { Text("返回编辑") }
                    }
                }
                editorMode != null && !blocked -> ZoneForm(
                    when (editorMode) { ZoneEditorMode.CREATE -> ZoneFormMode.CREATE; ZoneEditorMode.EDIT -> ZoneFormMode.EDIT; ZoneEditorMode.DELETE -> ZoneFormMode.DELETE },
                    account, editorTarget, draft,
                    onDraftChange = { value -> onIntent(ZoneIntent.DraftInvalidated); draft = value },
                    onPreview = { if (!state.loading) onIntent(ZoneIntent.Preview(it)) },
                    onCancel = { onIntent(ZoneIntent.Cancel) }, busy = state.loading)
                selected == null -> Column(
                    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cloudflare Zone", style = MaterialTheme.typography.titleLarge)
                    Text("Zone 是 DNS 托管与云服务区域，不是注册域名购买。")
                    ZoneTaskSummary(task, onIntent)
                    Button(onClick = { onIntent(ZoneIntent.BeginCreate) }, enabled = !blocked && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("zone-create")) { Text("创建 Zone") }
                    state.zones.filter { it.ref.account == account }.forEach { zone ->
                        OutlinedButton(onClick = { onIntent(ZoneIntent.Open(zone.ref)) },
                            modifier = Modifier.fillMaxWidth().testTag("zone-row-${zone.ref.zoneId}")) {
                            Column(Modifier.fillMaxWidth()) { Text(zone.name); Text("${zone.status} · ${zone.type}") }
                        }
                    }
                    if (state.zones.none { it.ref.account == account }) Text("当前账号尚无 Zone；可刷新或检查 Zone Read 权限。")
                    if (state.next != null) OutlinedButton(onClick = { onIntent(ZoneIntent.LoadMore) }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("zone-load-more")) { Text("加载更多 Zone") }
                    OutlinedButton(onClick = { onIntent(ZoneIntent.Load(account)) }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("zone-refresh")) { Text("刷新 Zone") }
                }
                else -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                    Text(selected.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("zone-detail-name"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("details" to "详情", "dns" to "DNS", "settings" to "设置", "security" to "安全").forEach { (key, label) ->
                            FilterChip(tab == key, { if(slots!=null) slots.onTabChanged(ZoneManagementTab.valueOf(key.uppercase())) else localTab=key }, label = { Text(label) }, modifier = Modifier.testTag("zone-tab-$key"))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            "dns" -> dnsContent(selected)
                            "settings" -> if(slots!=null) slots.settings(selected) else Text("Zone 设置待接入：通用与专用设置、SSL 和缓存清理尚未在本页面接线。", Modifier.testTag("zone-module-pending"))
                            "security" -> if(slots!=null) slots.security(selected) else Text("安全待接入：DNSSEC 读取、启停及 ZSK 状态尚未在本页面接线。", Modifier.testTag("zone-module-pending"))
                            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("这是 Cloudflare Zone；不会购买或注销注册域名。")
                                SectionCard {
                                    Text("状态 status：${selected.status}", Modifier.testTag("zone-status"))
                                    Text("暂停 paused：${selected.paused}", Modifier.testTag("zone-paused-value"))
                                    Text("类型 type：${selected.type}", Modifier.testTag("zone-type-value"))
                                    Text("名称服务器：\n${selected.nameservers.joinToString("\n").ifEmpty { "供应商尚未提供" }}", Modifier.testTag("zone-nameservers"))
                                    if (selected.status == "pending") Text("尚待激活；请按供应商流程设置名称服务器。请求检查不代表已激活。")
                                }
                                ZoneTaskSummary(task, onIntent)
                                OutlinedButton(onClick = { onIntent(ZoneIntent.BeginEdit(selected.ref)) }, enabled = !blocked && !state.loading,
                                    modifier = Modifier.fillMaxWidth().testTag("zone-detail-edit")) { Text("修改 Zone 属性") }
                                OutlinedButton(onClick = { onIntent(ZoneIntent.BeginDelete(selected.ref)) }, enabled = !blocked && !state.loading,
                                    modifier = Modifier.fillMaxWidth().testTag("zone-detail-delete")) { Text("删除整个 Zone") }
                                OutlinedButton(onClick = { onIntent(ZoneIntent.ActivationCheck(selected.ref)) }, enabled = !blocked && !state.loading && selected.status == "pending",
                                    modifier = Modifier.fillMaxWidth().testTag("zone-activation-check")) { Text("预览激活检查") }
                                TextButton(onClick = { onIntent(ZoneIntent.Clear); onIntent(ZoneIntent.Load(account)) },
                                    modifier = Modifier.fillMaxWidth().testTag("zone-back-list")) { Text("返回 Zone 列表") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneTaskSummary(task: OperationTask?, onIntent: (ZoneIntent) -> Unit) {
    task ?: return
    Text("操作状态：${task.state}\n${task.safeDetail}", Modifier.testTag("zone-task-status"))
    if (task.state in setOf(TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.ACCEPTED, TaskState.RECONCILING)) {
        Text("结果尚未确定，只能只读核对，不重复发送。")
        OutlinedButton(enabled = task.state != TaskState.RECONCILING, onClick = { onIntent(ZoneIntent.Reconcile(task.id)) },
            modifier = Modifier.fillMaxWidth().testTag("zone-task-reconcile")) { Text("只读核对 Zone 结果") }
    }
}
