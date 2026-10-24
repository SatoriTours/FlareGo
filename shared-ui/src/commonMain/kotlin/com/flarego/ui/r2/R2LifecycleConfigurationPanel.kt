package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

@Composable
fun R2LifecycleConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.editable && !state.writing && !state.loading
    val hasEmptyPrefixRule = state.lifecycle.any { it.prefix.trim().isEmpty() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "生命周期规则配置",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "生命周期规则自动删除对象、中止未完成的分段上传或迁移存储分层。规则全桶匹配或根据前缀/标签匹配。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rc-lifecycle-notice"),
            )

            if (hasEmptyPrefixRule) {
                Text(
                    text = "⚠️ 注意：当前存在空前缀（前缀为空）规则，将匹配并影响存储桶内的所有对象！",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("rc-lifecycle-empty-prefix-warning"),
                )
            }

            Button(
                onClick = { onIntent(R2ConfigurationIntent.AddLifecycleRule) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-add-rule"),
            ) {
                Text("添加生命周期规则")
            }
        }

        if (state.lifecycle.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-empty")) {
                Text(
                    text = "暂无生命周期规则 (PUT 保存将清空所有规则)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.lifecycle.forEachIndexed { index, rule ->
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "生命周期规则 #${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = rule.enabled,
                                onCheckedChange = { onIntent(R2ConfigurationIntent.LifecycleEnabledChanged(index, it)) },
                                enabled = enabled,
                                modifier = Modifier.testTag("rc-lifecycle-enabled-$index"),
                            )
                            Text("启用", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { onIntent(R2ConfigurationIntent.RemoveLifecycleRule(index)) },
                                enabled = enabled,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("rc-lifecycle-remove-$index"),
                            ) {
                                Text("删除规则")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = rule.id,
                        onValueChange = { onIntent(R2ConfigurationIntent.LifecycleIdChanged(index, it)) },
                        label = { Text("规则 ID（必填、唯一）") },
                        placeholder = { Text("例如 cleanup-logs") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-id-$index"),
                    )

                    OutlinedTextField(
                        value = rule.prefix,
                        onValueChange = { onIntent(R2ConfigurationIntent.LifecyclePrefixChanged(index, it)) },
                        label = { Text("匹配前缀 (Prefix，留空表示全桶匹配)") },
                        placeholder = { Text("例如 logs/ 或 images/") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-prefix-$index"),
                    )

                    if (rule.prefix.trim().isEmpty()) {
                        Text(
                            text = "当前规则前缀为空，为全桶生效规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("rc-lifecycle-rule-prefix-warning-$index"),
                        )
                    }

                    // 删除模式单选 (NONE, AGE, DATE)
                    Text("删除/过期模式 (Expire / Delete Mode)：", style = MaterialTheme.typography.bodySmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = rule.deleteMode == R2LifecycleDeleteMode.NONE,
                            onClick = { onIntent(R2ConfigurationIntent.LifecycleDeleteModeChanged(index, R2LifecycleDeleteMode.NONE)) },
                            label = { Text("不自动删除") },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lifecycle-del-mode-$index-NONE"),
                        )
                        FilterChip(
                            selected = rule.deleteMode == R2LifecycleDeleteMode.AGE,
                            onClick = { onIntent(R2ConfigurationIntent.LifecycleDeleteModeChanged(index, R2LifecycleDeleteMode.AGE)) },
                            label = { Text("天数 (Age)") },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lifecycle-del-mode-$index-AGE"),
                        )
                        FilterChip(
                            selected = rule.deleteMode == R2LifecycleDeleteMode.DATE,
                            onClick = { onIntent(R2ConfigurationIntent.LifecycleDeleteModeChanged(index, R2LifecycleDeleteMode.DATE)) },
                            label = { Text("指定时间 (Date)") },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lifecycle-del-mode-$index-DATE"),
                        )
                    }

                    when (rule.deleteMode) {
                        R2LifecycleDeleteMode.AGE -> {
                            OutlinedTextField(
                                value = rule.deleteAgeSeconds,
                                onValueChange = { onIntent(R2ConfigurationIntent.LifecycleDeleteAgeChanged(index, it)) },
                                label = { Text("创建后天数（正整数）") },
                                placeholder = { Text("例如 30") },
                                enabled = enabled,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-del-age-$index"),
                            )
                        }
                        R2LifecycleDeleteMode.DATE -> {
                            OutlinedTextField(
                                value = rule.deleteDate,
                                onValueChange = { onIntent(R2ConfigurationIntent.LifecycleDeleteDateChanged(index, it)) },
                                label = { Text("到期时间 (UTC / RFC 3339 原文)") },
                                placeholder = { Text("例如 2026-12-31T23:59:59.123456789Z") },
                                enabled = enabled,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-del-date-$index"),
                            )
                        }
                        R2LifecycleDeleteMode.NONE -> {}
                    }

                    OutlinedTextField(
                        value = rule.abortAgeSeconds,
                        onValueChange = { onIntent(R2ConfigurationIntent.LifecycleAbortAgeChanged(index, it)) },
                        label = { Text("中止未完成分段上传等待时间（秒，Abort Multipart Age Seconds，可选）") },
                        placeholder = { Text("例如 86400") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-abort-age-$index"),
                    )

                    // 存储分层迁移 (Transitions)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "存储分层迁移 (Transitions, 仅支持 InfrequentAccess)：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { onIntent(R2ConfigurationIntent.AddLifecycleTransition(index)) },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lifecycle-add-trans-$index"),
                        ) {
                            Text("添加迁移")
                        }
                    }

                    rule.transitions.forEachIndexed { transIndex, trans ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("迁移 #${transIndex + 1} -> 目标存储类：${trans.storageClass}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.testTag("rc-lifecycle-trans-class-$index-$transIndex")
                                    )
                                    TextButton(
                                        onClick = { onIntent(R2ConfigurationIntent.RemoveLifecycleTransition(index, transIndex)) },
                                        enabled = enabled,
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.testTag("rc-lifecycle-remove-trans-$index-$transIndex"),
                                    ) {
                                        Text("移除")
                                    }
                                }

                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    FilterChip(
                                        selected = trans.conditionType == R2LifecycleConditionType.AGE,
                                        onClick = {
                                            onIntent(
                                                R2ConfigurationIntent.LifecycleTransitionTypeChanged(
                                                    index,
                                                    transIndex,
                                                    R2LifecycleConditionType.AGE
                                                )
                                            )
                                        },
                                        label = { Text("天数 (Age)") },
                                        enabled = enabled,
                                        modifier = Modifier.testTag("rc-lifecycle-trans-cond-$index-$transIndex-AGE"),
                                    )
                                    FilterChip(
                                        selected = trans.conditionType == R2LifecycleConditionType.DATE,
                                        onClick = {
                                            onIntent(
                                                R2ConfigurationIntent.LifecycleTransitionTypeChanged(
                                                    index,
                                                    transIndex,
                                                    R2LifecycleConditionType.DATE
                                                )
                                            )
                                        },
                                        label = { Text("指定时间 (Date)") },
                                        enabled = enabled,
                                        modifier = Modifier.testTag("rc-lifecycle-trans-cond-$index-$transIndex-DATE"),
                                    )
                                }

                                if (trans.conditionType == R2LifecycleConditionType.AGE) {
                                    OutlinedTextField(
                                        value = trans.ageSeconds,
                                        onValueChange = {
                                            onIntent(R2ConfigurationIntent.LifecycleTransitionAgeChanged(index, transIndex, it))
                                        },
                                        label = { Text("迁移前天数（正整数）") },
                                        placeholder = { Text("例如 90") },
                                        enabled = enabled,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-trans-age-$index-$transIndex"),
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = trans.utcDate,
                                        onValueChange = {
                                            onIntent(R2ConfigurationIntent.LifecycleTransitionDateChanged(index, transIndex, it))
                                        },
                                        label = { Text("迁移时间 (UTC / RFC 3339 原文)") },
                                        placeholder = { Text("例如 2026-11-01T00:00:00Z") },
                                        enabled = enabled,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().testTag("rc-lifecycle-trans-date-$index-$transIndex"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
