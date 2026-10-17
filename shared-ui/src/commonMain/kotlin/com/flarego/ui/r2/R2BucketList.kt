package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

@Composable
fun R2BucketList(
    state: R2BucketListState,
    onIntent: (R2BucketIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.loading || state.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("rb-progress"))
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "R2 存储桶",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedButton(
                    onClick = { onIntent(R2BucketIntent.Refresh) },
                    enabled = !state.loading && !state.refreshing,
                    modifier = Modifier.testTag("rb-refresh"),
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
                    modifier = Modifier.testTag("rb-error"),
                )
            }

            state.validationMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("rb-validation-message"),
                )
            }

            state.pendingDeleteName?.let { targetBucket ->
                val canConfirmDelete = state.typedDeleteName == targetBucket
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("rb-delete-card")) {
                    Text(
                        text = "确认删除存储桶",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "删除存储桶不可逆，且存储桶内所有对象必须已清空。\n目标存储桶：$targetBucket",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = state.typedDeleteName,
                        onValueChange = { onIntent(R2BucketIntent.TypedDeleteNameChanged(it)) },
                        label = { Text("逐字输入存储桶名称确认") },
                        placeholder = { Text(targetBucket) },
                        modifier = Modifier.fillMaxWidth().testTag("rb-delete-input"),
                        singleLine = true,
                    )
                    if (!canConfirmDelete) {
                        Text(
                            text = "请输入完整的存储桶名称「$targetBucket」以确认删除",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("rb-delete-hint"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(R2BucketIntent.CancelDelete) },
                            modifier = Modifier.weight(1f).testTag("rb-delete-cancel"),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = { onIntent(R2BucketIntent.ConfirmDelete) },
                            enabled = canConfirmDelete && !state.loading,
                            modifier = Modifier.weight(1f).testTag("rb-delete-confirm"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("确认删除")
                        }
                    }
                }
            }

            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "创建存储桶",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = state.draftName,
                    onValueChange = { onIntent(R2BucketIntent.NameChanged(it)) },
                    label = { Text("存储桶名称") },
                    placeholder = { Text("例如 my-bucket") },
                    modifier = Modifier.fillMaxWidth().testTag("rb-create-name"),
                    singleLine = true,
                )
                Text(
                    text = "位置提示 (Location Hint)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.draftLocationHint == null,
                        onClick = { onIntent(R2BucketIntent.LocationHintChanged(null)) },
                        label = { Text("默认 (无偏好)") },
                        modifier = Modifier.testTag("rb-location-hint-none"),
                    )
                    state.locationHints.forEach { hint ->
                        FilterChip(
                            selected = state.draftLocationHint == hint,
                            onClick = { onIntent(R2BucketIntent.LocationHintChanged(hint)) },
                            label = { Text(hint) },
                            modifier = Modifier.testTag("rb-location-hint-$hint"),
                        )
                    }
                }
                Text(
                    text = "存储类型 (Storage Class)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.storageClasses.forEach { sc ->
                        FilterChip(
                            selected = state.draftStorageClass == sc,
                            onClick = { onIntent(R2BucketIntent.StorageClassChanged(sc)) },
                            label = {
                                Text(
                                    when (sc) {
                                        "Standard" -> "标准 (Standard)"
                                        "InfrequentAccess" -> "低频 (InfrequentAccess)"
                                        else -> sc
                                    }
                                )
                            },
                            modifier = Modifier.testTag("rb-storage-class-$sc"),
                        )
                    }
                }
                Button(
                    onClick = { onIntent(R2BucketIntent.SubmitCreate) },
                    enabled = state.draftName.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth().testTag("rb-create-submit"),
                ) {
                    Text("创建存储桶")
                }
            }

            Text(
                text = "存储桶列表 (${state.rows.size})",
                style = MaterialTheme.typography.titleMedium,
            )

            if (state.rows.isEmpty()) {
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    when {
                        state.error != null -> {
                            Text(
                                text = "加载存储桶失败",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        state.loading -> {
                            Text(
                                text = "正在加载存储桶...",
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
                    SectionCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("rb-row-${row.name}"),
                                )
                                Text(
                                    text = "位置：${row.location ?: "自动"} · 类型：${row.storageClass ?: "Standard"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "创建时间：${row.createdAt ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { onIntent(R2BucketIntent.OpenBucket(row.name)) },
                                modifier = Modifier.testTag("rb-open-${row.name}"),
                            ) {
                                Text("进入存储桶")
                            }
                            IconButton(
                                onClick = { onIntent(R2BucketIntent.RequestDelete(row.name)) },
                                modifier = Modifier.testTag("rb-delete-${row.name}"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "删除存储桶",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            if (state.nextCursor != null) {
                OutlinedButton(
                    onClick = { onIntent(R2BucketIntent.LoadMore) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().testTag("rb-load-more"),
                ) {
                    Text(if (state.loading) "正在加载..." else "加载更多存储桶")
                }
            }
        }
    }
}
