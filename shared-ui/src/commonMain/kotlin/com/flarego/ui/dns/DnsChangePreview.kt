package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.DnsChangePreview as Preview
import com.flarego.core.application.dns.DnsWriteMode
import com.flarego.ui.SectionCard

data class DnsPreviewTarget(val connection: String, val account: String, val zone: String, val recordId: String? = null)
data class DnsPreviewField(val path: String, val before: String?, val after: String?)

@Composable
fun DnsChangePreview(
    preview: Preview,
    target: DnsPreviewTarget,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    fields: List<DnsPreviewField> = emptyList(),
) {
    Column(modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("确认 DNS 变更", style = MaterialTheme.typography.titleLarge)
        Text(when (preview.mode) {
            DnsWriteMode.CREATE -> "POST 创建记录"
            DnsWriteMode.PATCH -> "PATCH 差量修改"
            DnsWriteMode.REPLACE -> "PUT 完整替换"
            DnsWriteMode.DELETE -> "DELETE 删除记录"
        }, Modifier.testTag("dns-preview-mode"), style = MaterialTheme.typography.titleMedium)
        SectionCard {
            Text("连接：${target.connection}\n账号：${target.account}\nZone：${target.zone}\n记录：${preview.recordName}" +
                (target.recordId?.let { "\n记录 ID：$it" } ?: ""), Modifier.testTag("dns-preview-target"))
            Text(when (preview.mode) {
                DnsWriteMode.CREATE -> "将在以上目标创建新记录。"
                DnsWriteMode.PATCH -> "仅发送明确变更的字段；未提供的字段保持原值。嵌套字段的更新语义由产品校验确认。"
                DnsWriteMode.REPLACE -> "PUT 将完整替换此记录；未提供的可写属性可能恢复默认值，请单独确认。"
                DnsWriteMode.DELETE -> "将删除以上目标记录，可能中断依赖它的服务。"
            }, Modifier.testTag("dns-preview-semantics"))
        }
        SectionCard {
            Text("变更字段", style = MaterialTheme.typography.titleMedium)
            preview.changedFields.sorted().forEach { path -> Text(path) }
            fields.forEach { field ->
                Text(field.path, style = MaterialTheme.typography.titleSmall)
                Text("变更前：\n${field.before ?: "未提供"}")
                val omitted = when (preview.mode) {
                    DnsWriteMode.PATCH -> "未提供（保持原值）"
                    DnsWriteMode.REPLACE -> "未提供（可能恢复默认值）"
                    DnsWriteMode.CREATE -> "未提供（使用默认值）"
                    DnsWriteMode.DELETE -> "删除记录"
                }
                Text("变更后：\n${field.after ?: omitted}")
            }
        }
        Text("Cloudflare 未提供 CAS。预提交回读不构成强原子并发保证；读取与提交之间仍可能发生竞争。",
            Modifier.testTag("dns-preview-cas"), style = MaterialTheme.typography.bodyMedium)
        preview.warnings.forEach { warning -> Text(warning, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().testTag("dns-preview-confirm")) { Text("确认提交") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("dns-preview-back")) { Text("返回编辑") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("dns-preview-cancel")) { Text("取消") }
    }
}
