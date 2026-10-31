package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.model.AccountScope
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.ui.AppBackHandler

/** Integration only: drafts, confirmation ownership and task evidence stay in the browser/runtime. */
@Composable
fun R2BrowserHost(browser: R2Browser, account: AccountScope?, backEnabled: Boolean = true) {
    val state by browser.state.collectAsState()
    val tasks by browser.writeTasks.collectAsState()
    val configState by browser.configuration.state.collectAsState()
    val cState by browser.c.state.collectAsState()
    var cBucketChoice by remember(browser, account) { mutableStateOf<String?>(null) }
    var cBucketMenu by remember(browser, account) { mutableStateOf(false) }
    LaunchedEffect(browser, account) { browser.refreshContext() }
    val current = state.context?.account == account && account != null
    val busy = state.writing || configState.writing || configState.sippy.writing || cState.writing
    AppBackHandler(backEnabled && current && (busy || cState.open || configState.open ||
        state.preview != null || state.objects != null || state.buckets.pendingDeleteName != null ||
        state.buckets.editingBucketName != null)) {
        // Consume Back while a write is in flight; leaving the surface must not cancel it.
        if (!busy) when {
            cState.open -> browser.c.hide()
            configState.open -> browser.onConfigurationIntent(when {
                configState.preview != null -> R2ConfigurationIntent.CancelPreview
                configState.deleteRequested -> R2ConfigurationIntent.CancelDeleteConfiguration
                configState.domainDeleteRequested -> R2ConfigurationIntent.CancelDeleteCustomDomain
                configState.sippy.disableRequested -> R2ConfigurationIntent.CancelDisableSippy
                configState.sippy.secureReady -> R2ConfigurationIntent.EndSippySecureEditing
                else -> R2ConfigurationIntent.Close
            })
            state.preview != null -> browser.cancelPreview()
            state.objects != null -> browser.onObjectIntent(when {
                state.objects!!.pendingDeleteKey != null -> R2ObjectIntent.CancelDelete
                state.objects!!.pendingBulkDelete -> R2ObjectIntent.CancelBulkDelete
                state.stagedUpload != null -> R2ObjectIntent.CancelUpload
                state.objects!!.selected.isNotEmpty() -> R2ObjectIntent.ClearSelection
                else -> R2ObjectIntent.BackToBuckets
            })
            state.buckets.pendingDeleteName != null -> browser.onBucketIntent(R2BucketIntent.CancelDelete)
            else -> browser.onBucketIntent(R2BucketIntent.CancelStorageClassEdit)
        }
    }
    if (cState.open) {
        // The batch C surface takes over the route while it is open (its own back button closes it).
        R2CScreen(browser.c, cState, account?.accountId ?: "-")
        return
    }
    Column(Modifier.fillMaxSize().testTag("r2-browser")) {
        if (!configState.open) {
            Text("当前显示默认 jurisdiction 的桶。${if (browser.writesAvailable) R2Browser.WRITE_READY_NOTICE else R2Browser.WRITE_NOTICE}",
                modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("r2-read-only-notice"), style = MaterialTheme.typography.bodySmall)
        }
        if (!current) {
            Text("正在绑定当前账号的 R2…", Modifier.padding(16.dp))
        } else {
            if (!configState.open) {
                state.notice?.takeIf { it != R2Browser.WRITE_NOTICE && it != R2Browser.WRITE_READY_NOTICE }?.let {
                    Text(it, Modifier.padding(horizontal = 12.dp).testTag("r2-write-result"), style = MaterialTheme.typography.bodySmall)
                }
                state.stagedUpload?.let { staged ->
                    Text("已暂存 ${staged.length} 字节，SHA-256 校验已生成；尚未发送", Modifier.padding(horizontal = 12.dp).testTag("r2-staged-file"))
                }
                if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                val bucket = cBucketChoice?.takeIf { selected -> state.buckets.rows.any { it.name == selected } }
                    ?: state.buckets.rows.singleOrNull()?.name
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    if (state.buckets.rows.size > 1) {
                        Box {
                            OutlinedButton(onClick = { cBucketMenu = true }, modifier = Modifier.testTag("r2-c-bucket-choice")) {
                                Text(bucket?.let { "目标桶：$it" } ?: "选择作业／通知的目标桶")
                            }
                            DropdownMenu(expanded = cBucketMenu, onDismissRequest = { cBucketMenu = false }) {
                                state.buckets.rows.forEach { row ->
                                    DropdownMenuItem(text = { Text(row.name) }, onClick = { cBucketChoice = row.name; cBucketMenu = false },
                                        modifier = Modifier.testTag("r2-c-bucket-${row.name}"))
                                }
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { bucket?.let { browser.onCBucketSelected(it); browser.c.open(R2CScreen.JOBS) } },
                            enabled = bucket != null, modifier = Modifier.testTag("r2-c-jobs"),
                        ) { Text("后台作业") }
                        OutlinedButton(
                            onClick = { bucket?.let { browser.onCBucketSelected(it); browser.c.open(R2CScreen.NOTIFICATIONS) } },
                            enabled = bucket != null, modifier = Modifier.testTag("r2-c-notifications"),
                        ) { Text("事件通知") }
                        OutlinedButton(
                            onClick = { browser.c.open(R2CScreen.METRICS) },
                            modifier = Modifier.testTag("r2-c-metrics"),
                        ) { Text("账号指标") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (configState.open) {
                    R2ConfigurationScreen(
                        state = configState,
                        onIntent = browser::onConfigurationIntent,
                        modifier = Modifier.fillMaxSize(),
                        sippyEditor = browser.sippyCredentials,
                    )
                } else {
                    val preview = state.preview
                    if (preview != null) {
                        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                            items(preview.targets) { target -> Text("目标：$target", Modifier.padding(8.dp)) }
                            item {
                                R2ChangePreviewCard(
                                    R2PreviewUi("R2 写入预览", preview.detail.bucket, preview.detail.impact, preview.detail.concurrencyWarning),
                                    !state.writing, browser::confirmPreview, browser::cancelPreview)
                            }
                        }
                    } else {
                        state.objects?.let { R2ObjectList(it.copy(loading = it.loading || state.writing), browser::onObjectIntent) }
                            ?: R2BucketList(
                                state = state.buckets.copy(loading = state.buckets.loading || state.writing),
                                onIntent = browser::onBucketIntent,
                                onOpenConfiguration = { bucket ->
                                    browser.onConfigurationIntent(R2ConfigurationIntent.Open(bucket))
                                },
                            )
                    }
                }
            }
            if (!configState.open) {
                val visibleTasks = tasks.filter { it.scope == account }.sortedByDescending { it.updatedAtMs }.take(20)
                if (visibleTasks.isNotEmpty()) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("r2-tasks")) {
                        items(visibleTasks, key = { it.id }) { task ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${task.actionKey}：${task.state}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if (R2WriteRuntime.unsettled(task.state)) OutlinedButton(
                                    onClick = { browser.reconcileTask(task.id) }, enabled = !state.writing,
                                    modifier = Modifier.testTag("r2-reconcile-${task.id}")) { Text("只读核对") }
                            }
                        }
                    }
                }
            }
        }
    }
}
