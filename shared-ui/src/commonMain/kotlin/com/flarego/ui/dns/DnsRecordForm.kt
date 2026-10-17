package com.flarego.ui.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.dns.validateDnsInput
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.*
import com.flarego.ui.SectionCard
import kotlinx.serialization.json.*

/**
 * Caller-owned outgoing fields, never a provider's raw document. For PATCH, supply
 * only intentional changes. Unknown input fields are retained and rejected, never dropped.
 * Edits retain incomplete/invalid text; keep this draft while displaying the preview.
 */
data class DnsFormDraft(
    val type: String = "A",
    val name: String = "@",
    val fields: JsonObject = JsonObject(emptyMap()),
    val edits: Map<String, String> = emptyMap(),
)

@Composable
fun DnsRecordForm(
    draft: DnsFormDraft,
    zoneName: String,
    onDraftChange: (DnsFormDraft) -> Unit,
    onPreview: (DnsRecordInput) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    method: DnsWriteMethod = DnsWriteMethod.POST,
    constraints: DnsConstraints = DnsConstraints(),
    actionLabel: String = "预览变更",
) {
    val schema = remember(draft.type, method) { DnsSchemas.forType(draft.type, method) }
    val specs = remember(schema) { schema?.let { DnsFormSchema(it).fields() }.orEmpty() }
    var error by remember(draft, zoneName, method, constraints) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val edit: (String, String) -> Unit = { path, value ->
        onDraftChange(draft.copy(edits = draft.edits + (path to value)))
    }
    Column(
        modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DNS 记录", style = MaterialTheme.typography.titleLarge)
        Text("Zone：$zoneName", style = MaterialTheme.typography.bodyMedium)
        if (schema == null) {
            SectionCard {
                Text("${draft.type} 目前仅支持查看；尚无受信任的可写 Schema，请使用官方控制台编辑。",
                    Modifier.testTag("dns-readonly-reason"))
                Text("记录名称：${draft.name}")
                Text(draft.fields.toString(), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            SectionCard {
                Text("记录类型", style = MaterialTheme.typography.titleMedium)
                Text("快捷类型：A、AAAA、CNAME、TXT、MX", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val quick = listOf("A", "AAAA", "CNAME", "TXT", "MX")
                    (quick.filter { it in DnsSchemas.types } + DnsSchemas.types.filter { it !in quick }.sorted()).forEach { type ->
                        FilterChip(
                            selected = draft.type == type,
                            onClick = {
                                if (draft.type != type) {
                                    // Keep the owner name; every type-specific field/edit is discarded,
                                    // including identically named content/data fields from the old type.
                                    onDraftChange(DnsFormDraft(type = type, name = draft.name))
                                }
                            },
                            label = { Text(type) },
                            modifier = Modifier.testTag("dns-type-$type"),
                        )
                    }
                }
                OutlinedTextField(draft.name, { onDraftChange(draft.copy(name = it)) },
                    label = { Text("记录名称") },
                    supportingText = { Text("@ 表示 Zone 根名称，也可填写相对名称") },
                    modifier = Modifier.fillMaxWidth().testTag("dns-name"), maxLines = 3)
            }
            if (method == DnsWriteMethod.PATCH) Text("PATCH 只预览草稿中明确提供的变更；留空且未编辑的字段不会添加到请求。",
                style = MaterialTheme.typography.bodySmall)
            val ordered = specs.sortedBy {
                when {
                    it.path == "content" -> 0
                    it.path.startsWith("data.") -> 1
                    it.path == "priority" -> 2
                    it.path == "ttl" -> 3
                    it.path == "proxied" -> 4
                    else -> 5
                }
            }
            SectionCard {
                ordered.forEach { spec ->
                    val value = draft.edits[spec.path] ?: draft.fields.at(spec.path)?.let {
                        if (it is JsonPrimitive) it.content else it.toString()
                    }.orEmpty()
                    val tag = "dns-${spec.path.replace('.', '-')}"
                    if (spec.kind == "boolean") {
                        val proxyAllowed = spec.path != "proxied" || draft.type in constraints.proxyTypes
                        Column {
                            Row {
                                Checkbox(checked = value == "true", onCheckedChange = { edit(spec.path, it.toString()) },
                                    enabled = proxyAllowed, modifier = Modifier.testTag(tag))
                                Text(if (spec.path == "proxied") "Cloudflare 代理" else spec.path,
                                    modifier = Modifier.weight(1f).padding(top = 12.dp))
                            }
                            if (!proxyAllowed) Text("此类型或当前约束不支持代理", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { edit(spec.path, it) },
                            label = { Text(when (spec.path) {
                                "content" -> if (draft.type == "TXT") "TXT 内容" else "记录内容"
                                "ttl" -> "TTL（秒）"
                                "priority" -> "优先级 priority"
                                "comment" -> "备注 comment"
                                else -> spec.path
                            }) },
                            modifier = Modifier.fillMaxWidth().testTag(tag),
                            minLines = if (spec.path == "content" && draft.type == "TXT") 3 else 1,
                            maxLines = 4,
                            supportingText = if (spec.kind == "array") ({ Text("仅编辑此字段的 JSON 数组，例如 [\"team:dns\"]") })
                                else if (spec.path == "content" && draft.type == "TXT") ({ Text("保留空格、引号和换行") })
                                else if (spec.choices.isNotEmpty()) ({ Text("允许值：${spec.choices.joinToString()}") })
                                else null,
                        )
                    }
                    if (spec.path == "ttl") Text(
                        "TTL 可为 1 或 ${constraints.minTtl}–${constraints.maxTtl} 秒。" +
                            if (constraints.minTtl == 60) "套餐未知或尚未验证 Enterprise 时最低为 60 秒。代理记录使用自动 TTL。"
                            else "已验证的套餐约束允许最低 ${constraints.minTtl} 秒。代理记录使用自动 TTL。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let { Text(it, Modifier.testTag("dns-form-error"), color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            focus.clearFocus()
            val validated = try {
                validateDnsInput(zoneName, DnsRecordInput(draft.type, draft.name,
                    draft.payload(specs, complete = method != DnsWriteMethod.PATCH)), constraints, method)
            } catch (failure: IllegalArgumentException) {
                error = failure.message ?: "请检查记录字段"; null
            } catch (failure: CloudException) {
                error = failure.message ?: "请检查记录字段"; null
            }
            if (validated != null) onPreview(validated)
        }, enabled = schema != null, modifier = Modifier.fillMaxWidth().testTag("dns-preview")) {
            Text(actionLabel)
        }
        OutlinedButton(onClick = { focus.clearFocus(); onCancel() }, modifier = Modifier.fillMaxWidth().testTag("dns-form-cancel")) {
            Text("取消")
        }
    }
}
