package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

@Composable
fun R2ChangePreviewCard(
    preview: R2PreviewUi,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = preview.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("rp-title"),
        )
        Text(
            text = "目标存储桶：${preview.bucket}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("rp-bucket"),
        )
        Text(
            text = preview.impact,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("rp-impact"),
        )
        Text(
            text = preview.concurrencyWarning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("rp-concurrency-warning"),
        )
        preview.requiresTypedText?.let { typedRequirement ->
            Text(
                text = "需输入确认文本：$typedRequirement",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("rp-requires-text"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).testTag("rp-cancel"),
            ) {
                Text("取消")
            }
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.weight(1f).testTag("rp-confirm"),
            ) {
                Text("确认执行")
            }
        }
    }
}
