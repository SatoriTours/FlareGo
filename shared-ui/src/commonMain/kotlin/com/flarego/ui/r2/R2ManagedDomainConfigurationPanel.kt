package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

/**
 * The r2.dev public endpoint of one bucket.
 *
 * `enabled` is always sent explicitly; closing this endpoint is described as exactly that and never
 * as "the bucket is private" or "the custom domains are closed".
 */
@Composable
fun R2ManagedDomainConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.domainWritesAvailable && state.editable && !state.writing && !state.loading
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text("r2.dev 公开入口 (Managed Domain)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.managedDomainHost ?: "尚未读取到该桶的 r2.dev 域名",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("rc-managed-host"),
            )
            Text(
                text = "关闭只影响此存储桶的 r2.dev 入口：不代表任何自定义域被关闭，也不代表桶变为私有。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rc-managed-notice"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("r2.dev 公开访问", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.managedDomainEnabled,
                    onCheckedChange = { onIntent(R2ConfigurationIntent.ManagedDomainEnabledChanged(it)) },
                    enabled = enabled,
                    modifier = Modifier.testTag("rc-managed-switch"),
                )
            }
        }

        DomainWriteConfirm(state, enabled, onIntent, needsDomain = false)

        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text("公开影响确认", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.risks.publicExposureAcknowledged,
                    onCheckedChange = {
                        onIntent(R2ConfigurationIntent.RisksChanged(state.risks.copy(publicExposureAcknowledged = it)))
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag("rc-managed-public-risk"),
                )
                Text("我已确认该公开入口变化对外可见性的影响", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}
