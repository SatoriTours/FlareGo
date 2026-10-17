package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${((kb * 10).toLong()) / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024.0) return "${((mb * 10).toLong()) / 10.0} MB"
    val gb = mb / 1024.0
    return "${((gb * 10).toLong()) / 10.0} GB"
}

@Composable
fun R2ObjectList(
    state: R2ObjectListState,
    onIntent: (R2ObjectIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier.fillMaxSize()) {
        if (state.loading || state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("ro-progress"))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { onIntent(R2ObjectIntent.BackToBuckets) },
                    modifier = Modifier.testTag("ro-back-buckets"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回存储桶列表",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "R2 对象",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "存储桶：${state.bucket}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { onIntent(R2ObjectIntent.Refresh) },
                    enabled = !state.loading && !state.refreshing,
                    modifier = Modifier.testTag("ro-refresh"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("ro-error"),
                )
            }

            state.validationMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ro-validation-message"),
                )
            }

            state.notice?.let { ntc ->
                Text(
                    text = ntc,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ro-notice"),
                )
            }

            // 单个对象删除确认卡片
            state.pendingDeleteKey?.let { targetKey ->
                val canConfirmDelete = state.typedDeleteKey == targetKey
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("ro-delete-card")) {
                    Text(
                        text = "确认删除对象",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "删除对象不可逆。\n目标对象键：$targetKey",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.typedDeleteKey,
                        onValueChange = { onIntent(R2ObjectIntent.TypedDeleteKeyChanged(it)) },
                        label = { Text("逐字输入对象键确认") },
                        placeholder = { Text(targetKey) },
                        modifier = Modifier.fillMaxWidth().testTag("ro-delete-input"),
                    )
                    if (!canConfirmDelete) {
                        Text(
                            text = "请输入完整的对象键「$targetKey」以确认删除",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("ro-delete-hint"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(R2ObjectIntent.CancelDelete) },
                            modifier = Modifier.weight(1f).testTag("ro-delete-cancel"),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = { onIntent(R2ObjectIntent.ConfirmDelete) },
                            enabled = canConfirmDelete && !state.loading,
                            modifier = Modifier.weight(1f).testTag("ro-delete-confirm"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("确认删除")
                        }
                    }
                }
            }

            // 批量删除确认卡片
            if (state.pendingBulkDelete) {
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("ro-bulk-delete-card")) {
                    Text(
                        text = "确认批量删除",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "即将删除 ${state.selected.size} 个已选对象。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("ro-bulk-delete-count"),
                    )
                    Text(
                        text = "供应商不保证原子性：部分 key 失败时其余 key 仍会被删除",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ro-bulk-delete-warning"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(R2ObjectIntent.CancelBulkDelete) },
                            modifier = Modifier.weight(1f).testTag("ro-bulk-delete-cancel"),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = { onIntent(R2ObjectIntent.ConfirmBulkDelete) },
                            enabled = !state.loading && state.selected.isNotEmpty(),
                            modifier = Modifier.weight(1f).testTag("ro-bulk-delete-confirm"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("确认批量删除")
                        }
                    }
                }
            }

            // 前缀筛选
            OutlinedTextField(
                value = state.prefix,
                onValueChange = { onIntent(R2ObjectIntent.PrefixChanged(it)) },
                label = { Text("按前缀过滤对象 (如 logs/)") },
                modifier = Modifier.fillMaxWidth().testTag("ro-prefix-input"),
                singleLine = true,
            )

            // 上传区域
            SectionCard(modifier = Modifier.fillMaxWidth().testTag("ro-upload-card")) {
                Text(
                    text = "上传对象",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = state.uploadKey,
                    onValueChange = { onIntent(R2ObjectIntent.UploadKeyChanged(it)) },
                    label = { Text("对象键 (Key)") },
                    placeholder = { Text("例如 path/to/file.png") },
                    modifier = Modifier.fillMaxWidth().testTag("ro-upload-key"),
                    singleLine = true,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onIntent(R2ObjectIntent.RequestUpload(state.uploadKey)) },
                        enabled = state.uploadKey.isNotBlank() && !state.loading,
                        modifier = Modifier.testTag("ro-upload-btn"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("选择文件并上传")
                    }
                    if (state.uploadKey.isNotBlank()) {
                        Button(
                            onClick = { onIntent(R2ObjectIntent.ConfirmUpload) },
                            enabled = !state.loading,
                            modifier = Modifier.testTag("ro-upload-confirm"),
                        ) {
                            Text("确认上传")
                        }
                        OutlinedButton(
                            onClick = { onIntent(R2ObjectIntent.CancelUpload) },
                            modifier = Modifier.testTag("ro-upload-cancel"),
                        ) {
                            Text("取消")
                        }
                    }
                }
            }

            // 批量操作栏
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已选 ${state.selected.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onIntent(R2ObjectIntent.SelectAllVisible) },
                            enabled = state.rows.isNotEmpty(),
                            modifier = Modifier.testTag("ro-select-all"),
                        ) {
                            Text("全选当前可见")
                        }
                        TextButton(
                            onClick = { onIntent(R2ObjectIntent.ClearSelection) },
                            enabled = state.selected.isNotEmpty(),
                            modifier = Modifier.testTag("ro-clear-selection"),
                        ) {
                            Text("清空选择")
                        }
                    }
                }
                Button(
                    onClick = { onIntent(R2ObjectIntent.RequestBulkDelete) },
                    enabled = state.selected.isNotEmpty() && !state.loading,
                    modifier = Modifier.fillMaxWidth().testTag("ro-bulk-delete-btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("批量删除已选 (${state.selected.size})")
                }
            }

            Text(
                text = "对象列表 (${state.rows.size})",
                style = MaterialTheme.typography.titleMedium,
            )

            if (state.rows.isEmpty()) {
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    when {
                        state.error != null -> {
                            Text(
                                text = "加载失败",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        state.loading -> {
                            Text(
                                text = "正在加载对象...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        else -> {
                            Text(
                                text = "尚无数据",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                state.rows.forEach { row ->
                    val isChecked = row.key in state.selected
                    SectionCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { onIntent(R2ObjectIntent.ToggleSelection(row.key)) },
                                modifier = Modifier.testTag("ro-checkbox-${row.key}"),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = row.key,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.testTag("ro-row-${row.key}"),
                                )
                                Text(
                                    text = "大小：${formatBytes(row.size)} · ETag：${row.etag ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "最后修改：${row.lastModified ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = { clipboardManager.setText(AnnotatedString(row.key)) },
                                modifier = Modifier.testTag("ro-copy-${row.key}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("复制键名")
                            }
                            OutlinedButton(
                                onClick = { onIntent(R2ObjectIntent.RequestDownload(row.key)) },
                                modifier = Modifier.testTag("ro-download-${row.key}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("下载")
                            }
                            OutlinedButton(
                                onClick = { onIntent(R2ObjectIntent.RequestDelete(row.key)) },
                                modifier = Modifier.testTag("ro-delete-${row.key}"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }

            if (state.nextCursor != null) {
                OutlinedButton(
                    onClick = { onIntent(R2ObjectIntent.LoadMore) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().testTag("ro-load-more"),
                ) {
                    Text(if (state.loading) "正在加载..." else "加载更多对象")
                }
            }
        }
    }
}
