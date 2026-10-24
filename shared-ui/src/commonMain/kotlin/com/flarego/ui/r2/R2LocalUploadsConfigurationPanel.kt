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
fun R2LocalUploadsConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.editable && !state.writing && !state.loading
    val isDefaultJurisdiction = state.target.jurisdiction.trim().lowercase().let { it.isEmpty() || it == "default" }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "近端写入/写入复制配置 (Local Uploads)",
                style = MaterialTheme.typography.titleMedium,
            )

            if (!isDefaultJurisdiction) {
                Text(
                    text = "近端写入/写入复制仅适用于 default jurisdiction，当前已审核端点/客户端暂无对「${state.target.jurisdiction}」地区的头部支持，本地客户端直接拒绝且不发送任何 HTTP 请求。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("rc-local-uploads-unsupported"),
                )
            } else {
                Text(
                    text = "近端写入/写入复制（Local Uploads）：关闭设置仅影响后续新发起的近端写入，已开始的写入复制将继续进行；本设置不改变存储桶的公开访问性与现有对象授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("rc-local-uploads-notice"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("近端写入限制状态：", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.localUploadsEnabled,
                        onCheckedChange = { onIntent(R2ConfigurationIntent.LocalUploadsEnabledChanged(it)) },
                        enabled = enabled,
                        modifier = Modifier.testTag("rc-local-uploads-switch"),
                    )
                }
            }
        }
    }
}
