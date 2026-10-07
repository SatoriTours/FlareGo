package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.updates.*

@Composable
fun UpdatePanel(controller: UpdateController) {
    val state by controller.state.collectAsState()
    var notesOpen by remember { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "应用更新",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Muted("v${state.installedName}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateChannel.entries.forEach { channel ->
                FilterChip(
                    selected = state.preferences.channel == channel,
                    onClick = { controller.setChannel(channel) },
                    enabled = !state.busy,
                    label = { Text(channel.label) },
                )
            }
        }
        if (state.preferences.channel == UpdateChannel.SNAPSHOT) Muted("最新提交可能包含尚在开发中的功能")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "自动检查更新",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(state.preferences.automatic, controller::setAutomatic, enabled = !state.busy)
        }
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        if (state.busy) {
            state.progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.available?.let { update ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${update.versionName} · ${(update.apk.size / 1024 / 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton({ notesOpen = true }) { Text("更新说明") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                { controller.check() },
                enabled = !state.busy,
                modifier = Modifier.testTag("check-update"),
            ) {
                Text("检查更新")
            }
            if (state.available != null)
                Button(
                    controller::download,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("download-update"),
                ) {
                    Text("下载并安装")
                }
        }
        Muted("下载后校验 SHA-256 与签名，由系统确认安装")
    }
    if (notesOpen)
        AlertDialog(
            onDismissRequest = { notesOpen = false },
            title = { Text("更新说明") },
            text = { Text(state.available?.notes?.take(12000).orEmpty().ifBlank { "此版本未提供更新说明" }) },
            confirmButton = { TextButton({ notesOpen = false }) { Text("关闭") } },
        )
}
