package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flarego.core.model.R2_NOTIFICATION_ACTIONS
import com.flarego.core.model.R2StorageClass
import com.flarego.ui.SectionCard

/**
 * The batch C production surface: bucket background jobs, event notifications and the account-level
 * metrics entry, plus the three-stage destructive-deletion confirmation.
 *
 * It is a thin view over [R2CController]: no rule, no plan and no credential ever lives here, and the
 * destructive stages are enforced by the owner/root (this UI only drives the same state machine). The
 * final dangerous button is a separate dialog whose action is *not* the primary/focused control and
 * which never submits from an IME action.
 */
@Composable
fun R2CScreen(controller: R2CController, state: R2CUiState, accountLabel: String) {
    Column(Modifier.fillMaxSize().testTag("rc-screen")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("R2 作业与通知", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.hide() }, modifier = Modifier.testTag("rc-back")) { Text("返回") }
        }
        Text(
            "账号：$accountLabel；目标桶：${state.targetLabel}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp).testTag("rc-target"),
        )
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.screen == R2CScreen.JOBS,
                onClick = { controller.onScreenChanged(R2CScreen.JOBS) },
                label = { Text("后台作业") },
                modifier = Modifier.testTag("rc-tab-jobs"),
            )
            FilterChip(
                selected = state.screen == R2CScreen.NOTIFICATIONS,
                onClick = { controller.onScreenChanged(R2CScreen.NOTIFICATIONS) },
                label = { Text("事件通知") },
                modifier = Modifier.testTag("rc-tab-notifications"),
            )
            FilterChip(
                selected = state.screen == R2CScreen.METRICS,
                onClick = { controller.onScreenChanged(R2CScreen.METRICS) },
                label = { Text("账号指标") },
                modifier = Modifier.testTag("rc-tab-metrics"),
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("rc-loading"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            if (state.screen != R2CScreen.METRICS) {
                Text("目标地区：${state.jurisdiction}（同名桶在不同地区是不同资源）", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (region in com.flarego.core.model.R2_JURISDICTIONS) {
                        FilterChip(selected = state.jurisdiction == region,
                            onClick = { controller.onJurisdictionChanged(region) }, enabled = !state.writing,
                            label = { Text(region) }, modifier = Modifier.testTag("rc-region-$region"))
                    }
                }
            }
            when (state.screen) {
                R2CScreen.JOBS -> JobsSection(controller, state)
                R2CScreen.NOTIFICATIONS -> NotificationsSection(controller, state)
                R2CScreen.METRICS -> MetricsSection(controller, state)
            }
            if (state.preparing) Text("正在独立读取并准备预览；尚未发送", modifier = Modifier.testTag("rc-preparing"))
            state.previewTitle?.let {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rc-preview"))
                state.previewDetail?.let { detail -> Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-preview-detail")) }
                PreviewActions(controller, state)
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).testTag("rc-notice"))
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testTag("rc-error"))
            }
        }
        ConfirmationStages(controller, state)
    }
}

@Composable
private fun PreviewActions(controller: R2CController, state: R2CUiState) {
    val destructive = state.screen == R2CScreen.JOBS && !state.jobDraft.migration ||
        state.screen == R2CScreen.NOTIFICATIONS && state.notificationMode != R2CNotificationMode.APPEND
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { controller.cancelConfirmation() }, modifier = Modifier.weight(1f).testTag("rc-preview-cancel")) {
            Text("取消")
        }
        if (destructive) {
            Button(
                onClick = {
                    val kind =
                        if (state.screen == R2CScreen.JOBS) {
                            if (state.jobDraft.wholeBucketClear) R2CDeleteKind.BUCKET_CLEAR else R2CDeleteKind.PREFIX_DELETE
                        } else {
                            if (state.notificationMode == R2CNotificationMode.ALL) R2CDeleteKind.NOTIFICATION_ALL else R2CDeleteKind.NOTIFICATION_SELECTED
                        }
                    controller.beginDeletion(kind)
                },
                modifier = Modifier.weight(1f).testTag("rc-preview-begin-confirm"),
            ) { Text("开始删除确认") }
        } else {
            Button(onClick = { controller.confirmReviewed() }, enabled = !state.writing, modifier = Modifier.weight(1f).testTag("rc-preview-confirm")) {
                Text("确认执行（未发送前）")
            }
        }
    }
}

@Composable
private fun JobsSection(controller: R2CController, state: R2CUiState) {
    SectionCard {
        Text("创建后台作业", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !state.jobDraft.migration,
                onClick = { controller.onJobDraftChanged(state.jobDraft.copy(migration = false)) },
                label = { Text("前缀删除") },
                modifier = Modifier.testTag("rc-job-kind-prefix"),
            )
            FilterChip(
                selected = state.jobDraft.migration,
                onClick = { controller.onJobDraftChanged(state.jobDraft.copy(migration = true)) },
                label = { Text("存储类别迁移") },
                modifier = Modifier.testTag("rc-job-kind-migration"),
            )
        }
        if (!state.jobDraft.migration) {
            OutlinedTextField(
                value = state.jobDraft.prefix,
                onValueChange = { controller.onJobDraftChanged(state.jobDraft.copy(prefix = it, wholeBucketClear = false)) },
                label = { Text("前缀（非空必须以 / 结尾，按原字面值）") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                singleLine = true,
                enabled = !state.jobDraft.wholeBucketClear,
                modifier = Modifier.fillMaxWidth().testTag("rc-job-prefix"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.jobDraft.wholeBucketClear,
                    onCheckedChange = { controller.onJobDraftChanged(state.jobDraft.copy(wholeBucketClear = it, prefix = "")) },
                    modifier = Modifier.testTag("rc-job-whole-bucket"),
                )
                Text("整桶对象清理（显式选择；不是空白前缀的默认行为）", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.jobDraft.manualMpuDeclared,
                    onCheckedChange = { controller.onJobDraftChanged(state.jobDraft.copy(manualMpuDeclared = it)) },
                    modifier = Modifier.testTag("rc-job-mpu-declared"),
                )
                Text("我已在外部中止多分片上传并暂停写入（人工声明，App 未检查）", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            StorageClassPicker(
                label = "源存储类别",
                tag = "rc-job-source",
                current = state.jobDraft.source,
                onPick = { option -> controller.onJobDraftChanged(state.jobDraft.copy(source = option)) },
            )
            StorageClassPicker(
                label = "目标存储类别",
                tag = "rc-job-destination",
                current = state.jobDraft.destination,
                onPick = { option -> controller.onJobDraftChanged(state.jobDraft.copy(destination = option)) },
            )
        }
        Button(onClick = { controller.prepare() }, modifier = Modifier.fillMaxWidth().testTag("rc-job-preview")) {
            Text("预览（尚未发送）")
        }
    }
    Text("作业列表", style = MaterialTheme.typography.titleSmall)
    Button(onClick = { controller.refreshJobs() }, modifier = Modifier.testTag("rc-jobs-refresh")) { Text("只读刷新") }
    state.jobsReadOnly?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-jobs-readonly"))
    }
    for (row in state.jobs) {
        SectionCard {
            Text("${row.kindLabel} / ${row.statusLabel}", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("rc-job-${row.id}"))
            Text(row.detail, style = MaterialTheme.typography.bodySmall)
            if (row.canRefresh) {
                TextButton(onClick = { controller.refreshJobTask(row.id) }, modifier = Modifier.testTag("rc-job-refresh-${row.id}")) {
                    Text("只读核对")
                }
            }
        }
    }
}

@Composable
private fun NotificationsSection(controller: R2CController, state: R2CUiState) {
    Button(onClick = { controller.refreshNotifications() }, modifier = Modifier.testTag("rc-notif-refresh")) { Text("只读刷新") }
    state.notificationsReadOnly?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-notif-readonly"))
    }
    for (queue in state.queues) {
        SectionCard {
            FilterChip(selected = state.selectedQueueKey == queue.queueKey,
                onClick = { controller.onQueueSelected(queue.queueKey) }, enabled = !state.writing,
                label = { Text("选择 Queue：${queue.queueLabel}") }, modifier = Modifier.testTag("rc-queue-${queue.queueKey}"))
            Text("Queue ID：${queue.queueKey}", style = MaterialTheme.typography.bodySmall)
            Text("既有规则 ${queue.rules.size} 条", style = MaterialTheme.typography.bodySmall)
            for (rule in queue.rules) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.selectedQueueKey == queue.queueKey && rule.id in state.selectedRuleIds,
                        enabled = state.selectedQueueKey == queue.queueKey && !state.writing,
                        onCheckedChange = { checked ->
                            controller.onSelectionChanged(
                                if (checked) state.selectedRuleIds + rule.id else state.selectedRuleIds - rule.id,
                            )
                        },
                        modifier = Modifier.testTag("rc-rule-select-${rule.id}"),
                    )
                    Text("${rule.id}：${rule.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("rc-rule-${rule.id}"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { controller.onClearAllRequested(true) },
                    enabled = state.selectedQueueKey == queue.queueKey && !state.writing,
                    modifier = Modifier.testTag("rc-notif-clear-all"),
                ) { Text("清除该 Queue 全部规则") }
                if (state.selectedQueueKey == queue.queueKey && state.notificationMode == R2CNotificationMode.ALL) {
                    OutlinedButton(
                        onClick = { controller.onClearAllRequested(false) },
                        modifier = Modifier.testTag("rc-notif-clear-selection"),
                    ) { Text("取消全清选择") }
                }
            }
        }
    }
    Text("当前操作：" + when (state.notificationMode) {
        R2CNotificationMode.APPEND -> "追加规则"
        R2CNotificationMode.SELECTED -> "删除选中规则"
        R2CNotificationMode.ALL -> "清除该 Queue 全部规则"
    }, modifier = Modifier.testTag("rc-notif-intent"))
    SectionCard {
        Text("追加事件通知规则（只新增，不覆盖既有规则）", style = MaterialTheme.typography.titleSmall)
        Text("动作（多选）", style = MaterialTheme.typography.bodySmall)
        for (action in R2_NOTIFICATION_ACTIONS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = action in state.notificationDraft.actions,
                    onCheckedChange = { checked ->
                        controller.onNotificationDraftChanged(
                            state.notificationDraft.copy(
                                actions = if (checked) state.notificationDraft.actions + action else state.notificationDraft.actions - action,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("rc-notif-action-$action"),
                )
                Text(action, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(
            value = state.notificationDraft.prefix,
            onValueChange = { controller.onNotificationDraftChanged(state.notificationDraft.copy(prefix = it)) },
            label = { Text("prefix（原字面值；留空=全部对象，需确认）") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("rc-notif-prefix"),
        )
        OutlinedTextField(
            value = state.notificationDraft.suffix,
            onValueChange = { controller.onNotificationDraftChanged(state.notificationDraft.copy(suffix = it)) },
            label = { Text("suffix（原字面值，可选）") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("rc-notif-suffix"),
        )
        OutlinedTextField(
            value = state.notificationDraft.description,
            onValueChange = { controller.onNotificationDraftChanged(state.notificationDraft.copy(description = it)) },
            label = { Text("描述（仅展示，不作为规则身份）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("rc-notif-description"),
        )
        Button(onClick = { controller.prepare() }, modifier = Modifier.fillMaxWidth().testTag("rc-notif-preview")) {
            Text(when (state.notificationMode) {
                R2CNotificationMode.APPEND -> "预览追加（尚未发送）"
                R2CNotificationMode.SELECTED -> "预览删除选中规则（尚未发送）"
                R2CNotificationMode.ALL -> "预览全清规则（尚未发送）"
            })
        }
    }
}

@Composable
private fun MetricsSection(controller: R2CController, state: R2CUiState) {
    Button(onClick = { controller.refreshMetrics() }, modifier = Modifier.testTag("rc-metrics-refresh")) { Text("获取账号指标") }
    state.metricsError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("rc-metrics-error"))
    }
    state.metrics?.let { metrics ->
        Text("账号级统计（非当前桶、非账单、不可用于结算）", style = MaterialTheme.typography.bodySmall)
        metrics.readOnlyReason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-metrics-readonly"))
        }
        for ((label, value) in metrics.rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-metric-${label.take(12)}"))
            }
        }
        Text(metrics.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("rc-metrics-note"))
    }
}

@Composable
private fun ConfirmationStages(controller: R2CController, state: R2CUiState) {
    val confirmation = state.confirmation
    // Every callback closes over THIS dialog, not whatever dialog happens to be current later.
    val handle = confirmation.handle
    val notificationDelete = confirmation.kind == R2CDeleteKind.NOTIFICATION_ALL || confirmation.kind == R2CDeleteKind.NOTIFICATION_SELECTED
    val consequence = if (notificationDelete) "删除通知规则可能中断现有事件处理；不删除桶内对象，也不证明消费者或消息状态。"
        else "作业只处理启动时已存在的对象，不能撤销，也不表示之后桶为空或分片已清理。"
    when (confirmation.stage) {
        R2CStage.NONE -> Unit
        R2CStage.RISK -> AlertDialog(
            onDismissRequest = { controller.cancelConfirmation(handle) },
            title = { Text("第 1/3 步：核对不可逆范围") },
            text = {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(confirmation.scopeText, modifier = Modifier.testTag("rc-confirm-scope"))
                    Text("此操作不可撤销；$consequence", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { controller.acknowledgeRisk(handle) }, modifier = Modifier.testTag("rc-confirm-risk-ack")) {
                    Text("我已核对上述范围")
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.cancelConfirmation(handle) }, modifier = Modifier.testTag("rc-confirm-cancel-1")) { Text("取消") }
            },
        )
        R2CStage.TYPED_NAME -> AlertDialog(
            onDismissRequest = { controller.cancelConfirmation(handle) },
            title = { Text("第 2/3 步：精确输入桶名") },
            text = {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(confirmation.scopeText, modifier = Modifier.testTag("rc-confirm-scope"))
                    Text("目标桶：${confirmation.bucket}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = confirmation.typedBucketName,
                        onValueChange = { controller.typeBucketName(it, handle) },
                        label = { Text("输入完整桶名（不做去空格/大小写容错）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("rc-confirm-typed-name"),
                    )
                    confirmation.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = { if (controller.openFinal(handle)) Unit },
                    enabled = confirmation.typedMatches,
                    modifier = Modifier.testTag("rc-confirm-open-final"),
                ) { Text("继续到最终确认") }
            },
            dismissButton = {
                TextButton(onClick = { controller.cancelConfirmation(handle) }, modifier = Modifier.testTag("rc-confirm-cancel-2")) { Text("取消") }
            },
        )
        R2CStage.FINAL -> AlertDialog(
            onDismissRequest = { controller.cancelConfirmation(handle) },
            title = { Text("第 3/3 步：最终确认（不可撤销）") },
            text = {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(confirmation.scopeText, modifier = Modifier.testTag("rc-confirm-final-scope"))
                    Text("点击“确认执行删除”后立即接纳本次写入；$consequence",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            dismissButton = {
                // Deliberately on the opposite side from the preceding step's primary button.
                // A small, separate danger button in its own dialog: not the default
                // focused control, never submitted by an IME action, and a different touch target from
                // the previous step.
                TextButton(
                    onClick = { controller.confirmFinal(handle) },
                    enabled = !state.writing,
                    modifier = Modifier.testTag("rc-confirm-final-submit"),
                ) { Text("确认执行删除", color = MaterialTheme.colorScheme.error) }
            },
            confirmButton = {
                TextButton(onClick = { controller.cancelConfirmation(handle) }, enabled = !state.writing, modifier = Modifier.testTag("rc-confirm-cancel-3")) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StorageClassPicker(label: String, tag: String, current: R2StorageClass, onPick: (R2StorageClass) -> Unit) {
    Text("$label：${current.wireValue}", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in R2StorageClass.entries) {
            FilterChip(
                selected = option == current,
                onClick = { onPick(option) },
                label = { Text(option.wireValue) },
                modifier = Modifier.testTag("$tag-${option.wireValue}"),
            )
        }
    }
}
