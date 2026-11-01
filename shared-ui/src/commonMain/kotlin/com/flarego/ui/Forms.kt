package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flarego.core.application.safeMessage
import com.flarego.core.application.validateDns
import com.flarego.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun FormBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun DnsEditor(
    domain: Domain,
    record: DnsRecord?,
    onDismiss: () -> Unit,
    preview: (DnsDraft) -> Unit,
    delete: (DnsDraft) -> Unit,
    retainedDraft: DnsDraft? = null,
) {
    var type by remember { mutableStateOf(retainedDraft?.type ?: record?.type ?: "A") }
    var name by remember { mutableStateOf(retainedDraft?.name ?: record?.name ?: "@") }
    var content by remember { mutableStateOf(retainedDraft?.content ?: record?.content ?: "") }
    var ttl by remember { mutableStateOf((retainedDraft?.ttl ?: record?.ttl ?: 1).toString()) }
    var proxied by remember { mutableStateOf(retainedDraft?.proxied ?: record?.proxied ?: true) }
    var priority by remember {
        mutableStateOf((retainedDraft?.priority ?: record?.priority ?: 10).toString())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val supported = type in setOf("A", "AAAA", "CNAME", "TXT", "MX")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record == null) "新增 DNS 记录" else "编辑 DNS 记录") },
        text = {
            FormBody {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("A", "AAAA", "CNAME", "TXT", "MX").forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (!supported) Notice("此记录类型当前只支持查看和删除；高级编辑请使用控制台")
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("名称 · @ 表示根域名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    content,
                    { content = it },
                    label = { Text("记录内容") },
                    modifier = Modifier.fillMaxWidth().testTag("dns-content"),
                    minLines = if (type == "TXT") 2 else 1,
                )
                OutlinedTextField(
                    ttl,
                    { ttl = it },
                    label = { Text("TTL 秒 · 1 为自动") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (type == "MX")
                    OutlinedTextField(
                        priority,
                        { priority = it },
                        label = { Text("MX 优先级") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                if (type in setOf("A", "AAAA", "CNAME"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cloudflare 代理")
                        Switch(proxied, { proxied = it })
                    }
                Muted("当前域名 ${domain.name}")
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (record != null)
                    TextButton(
                        onClick = {
                            delete(
                                DnsDraft(
                                    type,
                                    name,
                                    content,
                                    ttl.toIntOrNull() ?: 0,
                                    proxied,
                                    if (type == "MX") priority.toIntOrNull() else null,
                                )
                            )
                        }
                    ) {
                        Text("删除此记录", color = MaterialTheme.colorScheme.error)
                    }
            }
        },
        confirmButton = {
            TextButton(
                enabled = supported,
                onClick = {
                    try {
                        preview(
                            validateDns(
                                domain,
                                DnsDraft(
                                    type,
                                    name,
                                    content,
                                    ttl.toIntOrNull() ?: 0,
                                    proxied,
                                    if (type == "MX") priority.toIntOrNull() else null,
                                ),
                            )
                        )
                    } catch (e: Exception) {
                        error = safeMessage(e)
                    }
                },
            ) {
                Text("预览变更")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DnsConfirmation(
    domain: Domain,
    record: DnsRecord?,
    draft: DnsDraft?,
    demo: Boolean,
    onDismiss: () -> Unit,
    submit: suspend () -> Operation,
    complete: (Operation) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (draft == null) "确认删除记录" else "确认 DNS 变更") },
        text = {
            FormBody {
                Muted(domain.name)
                if (record != null) {
                    Text("变更前", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${record.type}  ${record.name}\n${record.content}\nTTL ${record.ttl} · ${if (record.proxied) "已代理" else "仅 DNS"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (draft != null) {
                    Text("变更后", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${draft.type}  ${draft.name}\n${draft.content}\nTTL ${draft.ttl} · ${if (draft.proxied) "已代理" else "仅 DNS"}${draft.priority?.let { " · 优先级 $it" } ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Notice(
                    if (demo) "演示账号，只修改本机演示数据，不调用真实 API。"
                    else "将提交到 Cloudflare。删除或修改可能影响服务；全球传播需要时间。请求中断时请先核对结果。"
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        complete(submit())
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "提交中…" else "确认提交")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
fun ResultDialog(title: String, detail: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun DisconnectDialog(connection: Connection, onDismiss: () -> Unit, remove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("断开 ${connection.name}？") },
        text = { Text("删除本机连接、加密凭据和该连接的操作记录。云上的资源不受影响。") },
        confirmButton = { TextButton(onClick = remove) { Text("断开连接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun CreateResourceDialog(onDismiss: () -> Unit, openConsole: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建云资源") },
        text = {
            FormBody {
                Text("R2 存储桶、D1 数据库和 KV 命名空间可前往「资源」页，选择对应类型后创建；是否可操作取决于当前账号权限。Workers 部署请打开 Cloudflare 控制台，完成后回到 App 刷新列表。")
                ResourceKind.entries.forEach {
                    Text("• ${it.name} · ${it.label}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = openConsole) { Text("打开控制台") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
