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

/** Integration only: drafts, confirmation ownership and task evidence stay in the browser/runtime. */
@Composable
fun R2BrowserHost(browser: R2Browser, account: AccountScope?) {
    val state by browser.state.collectAsState()
    val tasks by browser.writeTasks.collectAsState()
    LaunchedEffect(browser, account) { browser.refreshContext() }
    val current = state.context?.account == account && account != null
    Column(Modifier.fillMaxSize().testTag("r2-browser")) {
        Text("当前显示默认 jurisdiction 的桶。${if (browser.writesAvailable) R2Browser.WRITE_READY_NOTICE else R2Browser.WRITE_NOTICE}",
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("r2-read-only-notice"), style = MaterialTheme.typography.bodySmall)
        if (!current) {
            Text("正在绑定当前账号的 R2…", Modifier.padding(16.dp))
        } else {
            state.notice?.takeIf { it != R2Browser.WRITE_NOTICE && it != R2Browser.WRITE_READY_NOTICE }?.let {
                Text(it, Modifier.padding(horizontal = 12.dp).testTag("r2-write-result"), style = MaterialTheme.typography.bodySmall)
            }
            state.stagedUpload?.let { staged ->
                Text("已暂存 ${staged.length} 字节，SHA-256 校验已生成；尚未发送", Modifier.padding(horizontal = 12.dp).testTag("r2-staged-file"))
            }
            if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
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
                        ?: R2BucketList(state.buckets.copy(loading = state.buckets.loading || state.writing), browser::onBucketIntent)
                }
            }
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
