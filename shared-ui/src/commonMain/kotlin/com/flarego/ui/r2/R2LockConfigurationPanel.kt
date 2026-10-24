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
fun R2LockConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.editable && !state.writing && !state.loading
    val hasEmptyPrefixRule = state.lock.any { it.prefix.trim().isEmpty() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "对象锁定规则配置 (Bucket Lock)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "对象锁定通过保持期防止对象被删除或覆盖。配置无自动撤销不等于供应商确定不可撤销；删除锁定规则将完全移除整个桶的锁定配置 (DELETE)，但不能承诺立即解除已有对象的已有锁定保护。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rc-lock-notice"),
            )

            if (hasEmptyPrefixRule) {
                Text(
                    text = "⚠️ 注意：当前存在空前缀（前缀为空）规则，将匹配并锁定存储桶内的所有对象！",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("rc-lock-empty-prefix-warning"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onIntent(R2ConfigurationIntent.AddLockRule) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("rc-lock-add-rule"),
                ) {
                    Text("添加锁定规则")
                }
                OutlinedButton(
                    onClick = { onIntent(R2ConfigurationIntent.RequestDeleteConfiguration) },
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).testTag("rc-lock-request-delete"),
                ) {
                    Text("删除锁定配置 (DELETE)")
                }
            }
        }

        if (state.lock.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-lock-empty")) {
                Text(
                    text = "暂无对象锁定规则 (PUT 保存将清空所有规则)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.lock.forEachIndexed { index, rule ->
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "锁定规则 #${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = rule.enabled,
                                onCheckedChange = { onIntent(R2ConfigurationIntent.LockEnabledChanged(index, it)) },
                                enabled = enabled,
                                modifier = Modifier.testTag("rc-lock-enabled-$index"),
                            )
                            Text("启用", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { onIntent(R2ConfigurationIntent.RemoveLockRule(index)) },
                                enabled = enabled,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("rc-lock-remove-$index"),
                            ) {
                                Text("删除规则")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = rule.id,
                        onValueChange = { onIntent(R2ConfigurationIntent.LockIdChanged(index, it)) },
                        label = { Text("规则 ID（必填、唯一）") },
                        placeholder = { Text("例如 compliance-lock") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-lock-id-$index"),
                    )

                    OutlinedTextField(
                        value = rule.prefix,
                        onValueChange = { onIntent(R2ConfigurationIntent.LockPrefixChanged(index, it)) },
                        label = { Text("匹配前缀 (Prefix，留空表示全桶锁定)") },
                        placeholder = { Text("例如 archives/ 或 留空") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-lock-prefix-$index"),
                    )

                    if (rule.prefix.trim().isEmpty()) {
                        Text(
                            text = "当前规则前缀为空，为全桶生效锁定规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("rc-lock-rule-prefix-warning-$index"),
                        )
                    }

                    // 锁定条件类型单选 (AGE_SECONDS, DATE, INDEFINITE)
                    Text("锁定条件类型 (Condition Type)：", style = MaterialTheme.typography.bodySmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = rule.conditionType == R2LockConditionType.AGE_SECONDS,
                            onClick = { onIntent(R2ConfigurationIntent.LockConditionTypeChanged(index, R2LockConditionType.AGE_SECONDS)) },
                            label = { Text("秒数 (Age)") },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lock-cond-$index-AGE_SECONDS"),
                        )
                        FilterChip(
                            selected = rule.conditionType == R2LockConditionType.DATE,
                            onClick = { onIntent(R2ConfigurationIntent.LockConditionTypeChanged(index, R2LockConditionType.DATE)) },
                            label = { Text("固定时间 (Date)") },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lock-cond-$index-DATE"),
                        )
                        FilterChip(
                            selected = rule.conditionType == R2LockConditionType.INDEFINITE,
                            onClick = { onIntent(R2ConfigurationIntent.LockConditionTypeChanged(index, R2LockConditionType.INDEFINITE)) },
                            label = { Text("无限期锁定 (Indefinite)") },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-lock-cond-$index-INDEFINITE"),
                        )
                    }

                    when (rule.conditionType) {
                        R2LockConditionType.AGE_SECONDS -> {
                            OutlinedTextField(
                                value = rule.ageSeconds,
                                onValueChange = { onIntent(R2ConfigurationIntent.LockAgeSecondsChanged(index, it)) },
                                label = { Text("锁定保持秒数（正整数）") },
                                placeholder = { Text("例如 2592000 (30天)") },
                                enabled = enabled,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rc-lock-age-$index"),
                            )
                        }
                        R2LockConditionType.DATE -> {
                            OutlinedTextField(
                                value = rule.date,
                                onValueChange = { onIntent(R2ConfigurationIntent.LockDateChanged(index, it)) },
                                label = { Text("锁定保持至时间 (UTC / RFC 3339 原文)") },
                                placeholder = { Text("例如 2027-01-01T00:00:00.000Z") },
                                enabled = enabled,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rc-lock-date-$index"),
                            )
                        }
                        R2LockConditionType.INDEFINITE -> {
                            Text(
                                text = "⚠️ 注意：无限期保护 (Indefinite) 将使匹配对象的保护状态持续生效；修改或删除锁定规则不能承诺立即解除已有对象的保护状态！",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("rc-lock-indefinite-warning-$index"),
                            )
                        }
                    }
                }
            }
        }
    }
}
