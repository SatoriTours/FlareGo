package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.r2.R2DomainConfigurationEditing
import com.flarego.core.model.R2_MIN_TLS_VERSIONS
import com.flarego.ui.SectionCard

/**
 * The custom-domain collection, the create form, one selected editor and the delete mode.
 *
 * Every write control is disabled unless the bucket's domain writes are really available and the
 * document is editable. Ownership and the SSL certificate are provider states shown separately from
 * `enabled`: saving a configuration never claims a certificate, ownership or public readiness.
 * Ciphers are display-only in this stage — there is no input, no clear action and no intent that
 * could send a cipher list.
 */
@Composable
fun R2CustomDomainConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.domainWritesAvailable && state.editable && !state.writing && !state.loading
    val selected = state.customDomains.firstOrNull { it.domain == state.selectedDomain }
    val editLoweringTls = state.customDomainEdit.minTls?.let { R2DomainConfigurationEditing.isTlsDowngrade(selected?.minTls, it) } == true
    val createLoweringTls = R2DomainConfigurationEditing.isTlsDowngrade("1.2", state.customDomainCreate.minTls)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.editable) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Text("只读模式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(
                    text = state.readOnlyReason ?: "无法确认该桶的域名配置",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("rc-domain-readonly-reason"),
                )
            }
        }

        if (state.domainDeleteRequested) {
            SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-domain-delete-card")) {
                Text(
                    text = "⚠️ 删除自定义域 (HTTP DELETE)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "将通过 DELETE 移除该桶对 ${state.selectedDomain ?: ""} 的自定义域绑定；这不是写入空规则，也不会删除桶内对象。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.risks.destructiveImpactAcknowledged,
                        onCheckedChange = {
                            onIntent(R2ConfigurationIntent.RisksChanged(state.risks.copy(destructiveImpactAcknowledged = it)))
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("rc-domain-delete-risk"),
                    )
                    Text("我已确认移除该自定义域绑定的影响", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = state.typedTargetName,
                    onValueChange = { onIntent(R2ConfigurationIntent.TypedTargetNameChanged(it)) },
                    label = { Text("请输入目标桶名以确认") },
                    placeholder = { Text(state.target.bucket) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("rc-typed-target-name"),
                )
                OutlinedTextField(
                    value = state.typedDomainName,
                    onValueChange = { onIntent(R2ConfigurationIntent.TypedDomainNameChanged(it)) },
                    label = { Text("请输入完整域名以确认") },
                    placeholder = { Text(state.selectedDomain ?: "") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("rc-typed-domain-name"),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onIntent(R2ConfigurationIntent.CancelDeleteCustomDomain) },
                        enabled = !state.writing,
                        modifier = Modifier.weight(1f).testTag("rc-domain-cancel-delete"),
                    ) { Text("取消删除") }
                    Button(
                        onClick = { onIntent(R2ConfigurationIntent.Preview) },
                        enabled = state.canPreview && enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).testTag("rc-domain-delete-submit"),
                    ) { Text("预览删除请求") }
                }
            }
            return@Column
        }

        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text("自定义域 (Custom Domains)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "列表来自该桶当前的自定义域配置；读取失败或权限不足时不会当作空集合。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.customDomains.isEmpty()) {
                Text(
                    text = "当前没有读取到自定义域",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("rc-domain-empty"),
                )
            } else {
                state.customDomains.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("rc-domain-row-${row.domain}"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${row.domain}（访问开关：${if (row.enabled) "开启" else "关闭"}，状态未代表已生效；TLS ${row.minTls ?: "未知"}）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { onIntent(R2ConfigurationIntent.SelectCustomDomain(row.domain)) },
                            enabled = enabled && !state.writing,
                            modifier = Modifier.testTag("rc-domain-select-${row.domain}"),
                        ) { Text(if (state.selectedDomain == row.domain) "已选择" else "选择") }
                    }
                }
            }
        }

        if (selected == null) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text("新增自定义域（当前模式：新增）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.customDomainCreate.domain,
                onValueChange = { onIntent(R2ConfigurationIntent.CustomDomainCreateDomainChanged(it)) },
                label = { Text("域名（如 cdn.example.com）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rc-domain-create-domain"),
            )
            OutlinedTextField(
                value = state.customDomainCreate.zoneId,
                onValueChange = { onIntent(R2ConfigurationIntent.CustomDomainCreateZoneIdChanged(it)) },
                label = { Text("所属 Zone ID（会独立核实归属）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rc-domain-create-zone"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("新域公开访问（默认关闭）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.customDomainCreate.enabled,
                    onCheckedChange = { onIntent(R2ConfigurationIntent.CustomDomainCreateEnabledChanged(it)) },
                    enabled = enabled,
                    modifier = Modifier.testTag("rc-domain-create-enabled"),
                )
            }
            Text("最低 TLS 版本（默认 1.2，弱版本需要单独确认）", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                R2_MIN_TLS_VERSIONS.sorted().forEach { version ->
                    FilterChip(
                        selected = state.customDomainCreate.minTls == version,
                        onClick = { onIntent(R2ConfigurationIntent.CustomDomainCreateMinTlsChanged(version)) },
                        label = { Text(version) },
                        enabled = enabled,
                        modifier = Modifier.testTag("rc-domain-create-tls-$version"),
                    )
                }
            }
            if (state.customDomainCreate.enabled) {
                PublicRisk(state, enabled, onIntent, "rc-domain-create-public-risk")
            }
            if (createLoweringTls) {
                TlsDowngradeRisk(state, enabled, onIntent)
            }
            DomainWriteConfirm(state, enabled, onIntent, needsDomain = true)
        }
        } else {
            OutlinedButton(
                onClick = { onIntent(R2ConfigurationIntent.SelectCustomDomain(null)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rc-domain-new"),
            ) { Text("返回新增（不修改已选中的域）") }
        }

        if (selected != null) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                Text("编辑 ${selected.domain}", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "保存成功只代表配置已提交；所有权与证书状态由 Cloudflare 单独决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("rc-domain-provider-states-notice"),
                )
                Text(
                    text = "所有权 (ownership)：${selected.ownership ?: "未知"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("rc-domain-ownership"),
                )
                Text(
                    text = "证书 (SSL)：${selected.ssl ?: "未知"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("rc-domain-ssl"),
                )
                Text(
                    text = "所属 Zone：${selected.zoneName ?: "未知"}（${selected.zoneId ?: "未知 ID"}）",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Display only: the current provider cipher allowlist. No input, no clear action.
                Text(
                    text = "当前 cipher（只读）：" + (selected.ciphers?.joinToString(", ")?.ifEmpty { "（空允许列表）" } ?: "未读取到"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("rc-domain-ciphers"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "公开访问（当前 ${if (selected.enabled) "开启" else "关闭"}）",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.customDomainEdit.enabled ?: selected.enabled,
                        onCheckedChange = { onIntent(R2ConfigurationIntent.CustomDomainEditEnabledChanged(it)) },
                        enabled = enabled,
                        modifier = Modifier.testTag("rc-domain-edit-enabled"),
                    )
                }
                Text("最低 TLS（当前 ${selected.minTls ?: "未知"}）", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    R2_MIN_TLS_VERSIONS.sorted().forEach { version ->
                        FilterChip(
                            selected = (state.customDomainEdit.minTls ?: selected.minTls) == version,
                            onClick = { onIntent(R2ConfigurationIntent.CustomDomainEditMinTlsChanged(version)) },
                            label = { Text(version) },
                            enabled = enabled,
                            modifier = Modifier.testTag("rc-domain-edit-tls-$version"),
                        )
                    }
                }
                // The gate asks for this acknowledgement whenever the edit explicitly enables public
                // access (write.edit.enabled == true), including turning it back on after a disable.
                if (state.customDomainEdit.enabled == true) {
                    PublicRisk(state, enabled, onIntent, "rc-domain-edit-public-risk")
                }
                if (editLoweringTls) {
                    TlsDowngradeRisk(state, enabled, onIntent)
                }
                DomainWriteConfirm(state, enabled, onIntent, needsDomain = true)
                OutlinedButton(
                    onClick = { onIntent(R2ConfigurationIntent.RequestDeleteCustomDomain) },
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("rc-domain-request-delete"),
                ) { Text("删除该自定义域 (DELETE)") }
            }
        }
    }
}

/**
 * The real confirmation gate of a domain write.
 *
 * The browser requires the exact bucket name for every domain write and the exact domain name for a
 * custom-domain write, so both inputs are part of the panel itself; the one Preview entry stays
 * disabled unless the state says a preview is allowed. There is no rule-set/full-replacement text
 * here: a domain write is not a `rules[]` replacement.
 */
@Composable
internal fun DomainWriteConfirm(
    state: R2ConfigurationUiState,
    enabled: Boolean,
    onIntent: (R2ConfigurationIntent) -> Unit,
    needsDomain: Boolean,
) {
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Text("写入门禁与确认", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = state.typedTargetName,
            onValueChange = { onIntent(R2ConfigurationIntent.TypedTargetNameChanged(it)) },
            label = { Text("请输入目标桶名以确认") },
            placeholder = { Text(state.target.bucket) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("rc-typed-target-name"),
        )
        if (needsDomain) {
            OutlinedTextField(
                value = state.typedDomainName,
                onValueChange = { onIntent(R2ConfigurationIntent.TypedDomainNameChanged(it)) },
                label = { Text("请输入目标域名以确认") },
                placeholder = { Text(state.selectedDomain ?: state.customDomainCreate.domain) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rc-typed-domain-name"),
            )
        }
        state.validationMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("rc-validation-message"),
            )
        }
        Button(
            onClick = { onIntent(R2ConfigurationIntent.Preview) },
            enabled = state.canPreview && enabled,
            modifier = Modifier.fillMaxWidth().testTag("rc-preview-button"),
        ) { Text("生成写入预览 (Preview)") }
    }
}

@Composable
private fun PublicRisk(
    state: R2ConfigurationUiState,
    enabled: Boolean,
    onIntent: (R2ConfigurationIntent) -> Unit,
    tag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.risks.publicExposureAcknowledged,
            onCheckedChange = {
                onIntent(R2ConfigurationIntent.RisksChanged(state.risks.copy(publicExposureAcknowledged = it)))
            },
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
        Text("我已确认公开访问带来的对外可见性影响", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TlsDowngradeRisk(
    state: R2ConfigurationUiState,
    enabled: Boolean,
    onIntent: (R2ConfigurationIntent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.risks.tlsDowngradeAcknowledged,
            onCheckedChange = {
                onIntent(R2ConfigurationIntent.RisksChanged(state.risks.copy(tlsDowngradeAcknowledged = it)))
            },
            enabled = enabled,
            modifier = Modifier.testTag("rc-domain-tls-downgrade-risk"),
        )
        Text("降低最低 TLS 版本属于安全降级，我已单独确认", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
