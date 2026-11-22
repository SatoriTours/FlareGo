package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.connection.ReadTarget
import com.flarego.core.application.inventory.ProductKey
import com.flarego.core.application.inventory.count
import com.flarego.core.model.AccountScope
import com.flarego.core.model.AppState
import com.flarego.core.model.CloudFailureCode

@Composable
internal fun SyncDetailsDialog(
    state: AppState,
    dismiss: () -> Unit,
    refresh: () -> Unit,
    upgrade: ((Set<ReadTarget>) -> Unit)?,
) {
    val connection = state.selected ?: return
    val account = AccountScope(connection.id, connection.accountId)
    val results = state.products.filterValues { it.scope == account }
    val denied = results.filterValues { it.error?.code == CloudFailureCode.PERMISSION_REQUIRED }.keys
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("sync-details"),
        title = { Text("同步详情") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectoryHeading(connection.name)
                if (connection.demo) Muted("演示 · 未调用云 API")
                ProductKey.entries.forEach { key ->
                    val result = results[key]
                    val error = result?.error
                    val status = when {
                        key !in state.supportedProducts -> "当前授权方案未支持"
                        result?.loading == true -> "正在读取"
                        error != null -> error.publicMessage
                        result?.observedAtMs == null -> "尚未读取"
                        key == ProductKey.BILLING -> "已读取 ${result.content.count()} 条"
                        else -> "已读取 ${result.content.count()} 个"
                    }
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("sync-product-${key.name.lowercase()}")) {
                        Text(key.overviewLabel(), style = MaterialTheme.typography.titleSmall)
                        Muted(status)
                        if (result?.observedAtMs != null) {
                            if (result.fromCache) Muted("本地缓存")
                            if (result.error != null) Muted("保留上次读取的结果")
                            if (!result.allPagesLoaded) Muted("仅为当前已加载范围")
                        }
                    }
                }
                if (denied.isNotEmpty() && upgrade != null) TextButton(onClick = {
                    upgrade(denied.map { productReadTarget(it, state.workersMode) }.toSet())
                }) { Text("核对并补充读取权限") }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } },
        dismissButton = { TextButton(refresh, enabled = results.values.none { it.loading } && !state.loading) { Text("立即刷新") } },
    )
}
