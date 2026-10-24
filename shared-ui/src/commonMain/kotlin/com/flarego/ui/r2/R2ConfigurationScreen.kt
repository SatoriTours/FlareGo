package com.flarego.ui.r2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

@Composable
fun R2ConfigurationScreen(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
    // The app-owned private credential editor; the screen only reads it for the Sippy form.
    sippyEditor: R2SippyCredentialOwner? = null,
) {
    // Route-lifetime identity of the composition that can host the secret surface. This screen (not
    // the panel inner subtree, which a recomposition may re-create) is what disappears when the route,
    // the tab or the screen goes away; a disposal of *this* mount is the one "the surface is gone"
    // signal no intent reports. A superseded mount is ignored by the owner.
    val surfaceMount = remember(sippyEditor) { sippyEditor?.newSurfaceMount() }
    DisposableEffect(sippyEditor, surfaceMount) {
        onDispose { if (surfaceMount != null) sippyEditor?.onSurfaceMountDisposed(surfaceMount) }
    }
    val enabled = !state.loading && !state.writing
    val editableAndEnabled = enabled && state.editable
    var targetExpanded by remember { mutableStateOf(false) }
    var rawDocPageIndex by remember { mutableStateOf(0) }
    val rawChunkSize = 1000

    Column(modifier = modifier.fillMaxSize().testTag("rc-screen")) {
        // 顶部进度指示器
        if (state.loading || state.writing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("rc-progress"))
        }

        // 顶部导航栏与目标信息（紧凑化设计，折叠目标与地区以释放小屏空间）
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        // 单行截断：大字体/320dp 时固定头部不能被换行撑高，否则面板切换与正文会被挤出屏幕。
                        Text(
                            text = "存储桶配置：${state.target.bucket}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "目标：${state.target.bucket} (地区: ${state.target.jurisdiction})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("rc-target-display"),
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.preview == null) {
                            IconButton(
                                onClick = { targetExpanded = !targetExpanded },
                                modifier = Modifier.size(36.dp).testTag("rc-target-toggle"),
                            ) {
                                Icon(
                                    imageVector = if (targetExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (targetExpanded) "折叠目标设置" else "展开目标设置",
                                )
                            }
                        }
                        IconButton(
                            onClick = { onIntent(R2ConfigurationIntent.Refresh) },
                            enabled = enabled,
                            modifier = Modifier.size(36.dp).testTag("rc-refresh"),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新配置")
                        }
                        IconButton(
                            onClick = { onIntent(R2ConfigurationIntent.Close) },
                            enabled = !state.writing,
                            modifier = Modifier.size(36.dp).testTag("rc-close"),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "关闭配置")
                        }
                    }
                }

                // 面板切换 Tab
                val tabs = R2ConfigurationPanel.entries
                ScrollableTabRow(
                    selectedTabIndex = tabs.indexOf(state.panel).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth().testTag("rc-panel-tabs"),
                    edgePadding = 0.dp,
                ) {
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.CORS,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.CORS)) },
                        text = { Text("CORS") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-cors"),
                    )
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.LIFECYCLE,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LIFECYCLE)) },
                        text = { Text("生命周期") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-lifecycle"),
                    )
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.LOCAL_UPLOADS,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LOCAL_UPLOADS)) },
                        text = { Text("近端写入") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-local-uploads"),
                    )
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.LOCK,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LOCK)) },
                        text = { Text("对象锁定") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-lock"),
                    )
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.MANAGED_DOMAIN,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.MANAGED_DOMAIN)) },
                        text = { Text("r2.dev") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-managed-domain"),
                    )
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.CUSTOM_DOMAINS,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.CUSTOM_DOMAINS)) },
                        text = { Text("自定义域") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-custom-domains"),
                    )
                    Tab(
                        selected = state.panel == R2ConfigurationPanel.SIPPY,
                        onClick = { onIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.SIPPY)) },
                        text = { Text("Sippy") },
                        enabled = enabled && state.preview == null,
                        modifier = Modifier.testTag("rc-panel-sippy"),
                    )
                }
            }
        }

        // 主体滚动内容
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Bottom inset so the last control of a long panel can scroll fully into view
                // instead of sitting flush against the navigation bar.
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 目标桶与地区编辑（可折叠，未冻结预览时允许调整）。
            // 放在可滚动主体内，避免大字体/小屏时固定头部挤占正文与面板切换。
            if (state.preview == null && targetExpanded) {
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-target-editor")) {
                    OutlinedTextField(
                        value = state.target.bucket,
                        onValueChange = { onIntent(R2ConfigurationIntent.BucketChanged(it)) },
                        label = { Text("目标存储桶名") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("rc-target-bucket"),
                    )

                    Text("选择地区 (Jurisdiction)：", style = MaterialTheme.typography.bodySmall)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.target.jurisdictions.forEach { jur ->
                            FilterChip(
                                selected = state.target.jurisdiction == jur,
                                onClick = { onIntent(R2ConfigurationIntent.JurisdictionChanged(jur)) },
                                label = { Text(jur) },
                                enabled = enabled,
                                modifier = Modifier.testTag("rc-target-jurisdiction-$jur"),
                            )
                        }
                    }
                }
            }

            // 提示或错误横幅
            state.error?.let {
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-error-card")) {
                    Text("错误：$it", color = MaterialTheme.colorScheme.error)
                }
            }

            state.notice?.let {
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-notice-card")) {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }

            // 只读原因与原文保留卡片（支持分块/分页只读浏览，避免超大原文撑爆小屏）
            if (!state.editable && state.readOnlyReason != null) {
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "只读模式",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.readOnlyReason ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("rc-readonly-reason"),
                    )
                    state.rawDocumentText?.let { raw ->
                        val chunks = raw.chunked(rawChunkSize).ifEmpty { listOf("") }
                        val safePage = rawDocPageIndex.coerceIn(0, chunks.size - 1)
                        Text(
                            text = "原始配置文档 (第 ${safePage + 1}/${chunks.size} 页，共 ${raw.length} 字符)：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = chunks[safePage],
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp).testTag("rc-raw-document"),
                            )
                        }
                        if (chunks.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { if (safePage > 0) rawDocPageIndex = safePage - 1 },
                                    enabled = safePage > 0,
                                    modifier = Modifier.testTag("rc-raw-prev-page"),
                                ) {
                                    Text("上一页")
                                }
                                Text("页码: ${safePage + 1} / ${chunks.size}", style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    onClick = { if (safePage < chunks.size - 1) rawDocPageIndex = safePage + 1 },
                                    enabled = safePage < chunks.size - 1,
                                    modifier = Modifier.testTag("rc-raw-next-page"),
                                ) {
                                    Text("下一页")
                                }
                            }
                        }
                    }
                }
            }

            // 冻结预览卡片（当处于预览阶段时）
            state.preview?.let { preview ->
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-preview-card")) {
                    Text(
                        text = "配置变更预览",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("rc-preview-title"),
                    )
                    Text(
                        text = "目标桶：${preview.target}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("rc-preview-target"),
                    )
                    Text(
                        text = preview.impact,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("rc-preview-impact"),
                    )
                    preview.concurrencyWarning?.let { warn ->
                        Text(
                            text = warn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("rc-preview-concurrency"),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(R2ConfigurationIntent.CancelPreview) },
                            enabled = !state.writing,
                            modifier = Modifier.weight(1f).testTag("rc-preview-cancel"),
                        ) {
                            Text("取消预览")
                        }
                        Button(
                            onClick = { onIntent(R2ConfigurationIntent.Confirm) },
                            enabled = !state.writing,
                            modifier = Modifier.weight(1f).testTag("rc-preview-confirm"),
                        ) {
                            Text("确认执行写入")
                        }
                    }
                }
            }

            // 如果处于明确 DELETE 请求模式：展示独立风险卡片与精确桶名输入，隐藏普通表单
            if (state.preview == null && state.deleteRequested) {
                SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-delete-mode-card")) {
                    Text(
                        text = "⚠️ 确认移除配置规则集 (HTTP DELETE)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "即将通过 DELETE 请求完全移除 ${state.target.bucket} 的 ${state.panel.name} 配置规则集。注意：这是移除存储桶的配置规则，并非删除桶内数据对象；配置删除后无系统自动回滚机制。请核对破坏性影响。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.risks.destructiveImpactAcknowledged,
                            onCheckedChange = {
                                onIntent(
                                    R2ConfigurationIntent.RisksChanged(
                                        state.risks.copy(destructiveImpactAcknowledged = it)
                                    )
                                )
                            },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-risk-destructive"),
                        )
                        Text(
                            text = "我已确认移除该配置规则集的影响",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    OutlinedTextField(
                        value = state.typedTargetName,
                        onValueChange = { onIntent(R2ConfigurationIntent.TypedTargetNameChanged(it)) },
                        label = { Text("请输入完整的目标桶名以确认") },
                        placeholder = { Text(state.target.bucket) },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("rc-typed-target-name"),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onIntent(R2ConfigurationIntent.CancelDeleteConfiguration) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).testTag("rc-cancel-delete-mode"),
                        ) {
                            Text("取消删除")
                        }
                        Button(
                            onClick = { onIntent(R2ConfigurationIntent.Preview) },
                            enabled = state.canPreview && enabled,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).testTag("rc-preview-button"),
                        ) {
                            Text("预览删除请求")
                        }
                    }

                    state.validationMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("rc-validation-message"),
                        )
                    }
                }
            } else if (state.preview == null) {
                // 普通编辑模式
                when (state.panel) {
                    R2ConfigurationPanel.CORS -> R2CorsConfigurationPanel(state, onIntent)
                    R2ConfigurationPanel.LIFECYCLE -> R2LifecycleConfigurationPanel(state, onIntent)
                    R2ConfigurationPanel.LOCAL_UPLOADS -> R2LocalUploadsConfigurationPanel(state, onIntent)
                    R2ConfigurationPanel.LOCK -> R2LockConfigurationPanel(state, onIntent)
                    R2ConfigurationPanel.MANAGED_DOMAIN -> R2ManagedDomainConfigurationPanel(state, onIntent)
                    R2ConfigurationPanel.CUSTOM_DOMAINS -> R2CustomDomainConfigurationPanel(state, onIntent)
                    R2ConfigurationPanel.SIPPY -> R2SippyConfigurationPanel(state, onIntent, sippyEditor)

                    else -> Unit
                }

                // 校验错误与前置风险卡片（域面板自带域/公开/TLS 风险与删除门禁）
                if (state.panel.kind != null) {
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "写入门禁与风险确认",
                        style = MaterialTheme.typography.titleSmall,
                    )

                    // 整组替换风险确认
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.risks.fullReplacementAcknowledged,
                            onCheckedChange = {
                                onIntent(
                                    R2ConfigurationIntent.RisksChanged(
                                        state.risks.copy(fullReplacementAcknowledged = it)
                                    )
                                )
                            },
                            enabled = editableAndEnabled,
                            modifier = Modifier.testTag("rc-risk-full-replacement"),
                        )
                        Text(
                            text = "我已知晓 PUT 将整组替换该桶的已有配置规则",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // 破坏性操作风险确认（如空前缀规则）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.risks.destructiveImpactAcknowledged,
                            onCheckedChange = {
                                onIntent(
                                    R2ConfigurationIntent.RisksChanged(
                                        state.risks.copy(destructiveImpactAcknowledged = it)
                                    )
                                )
                            },
                            enabled = editableAndEnabled,
                            modifier = Modifier.testTag("rc-risk-destructive"),
                        )
                        Text(
                            text = "我已知晓并确认全桶/破坏性规则的影响",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // 精确桶名输入（当有破坏性风险时）
                    OutlinedTextField(
                        value = state.typedTargetName,
                        onValueChange = { onIntent(R2ConfigurationIntent.TypedTargetNameChanged(it)) },
                        label = { Text("破坏性操作请输入目标桶名以确认") },
                        placeholder = { Text(state.target.bucket) },
                        singleLine = true,
                        enabled = editableAndEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("rc-typed-target-name"),
                    )

                    state.validationMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("rc-validation-message"),
                        )
                    }

                    Button(
                        onClick = { onIntent(R2ConfigurationIntent.Preview) },
                        enabled = state.canPreview && enabled,
                        modifier = Modifier.fillMaxWidth().testTag("rc-preview-button"),
                    ) {
                        Text("生成写入预览 (Preview)")
                    }
                }
                }
            }
        }
    }
}
