package com.flarego.core.application.dns

import com.flarego.core.model.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import kotlinx.serialization.json.*

fun validateDnsInput(
    zoneName: String,
    input: DnsRecordInput,
    constraints: DnsConstraints,
    method: DnsWriteMethod = DnsWriteMethod.POST,
): DnsRecordInput {
    val type = input.type.trim().uppercase()
    val schema = DnsSchemas.forType(type, method)
        ?: throw CloudException("该记录类型目前仅支持查看，请使用官方控制台编辑")
    val root = zoneName.trim().removeSuffix(".").lowercase()
    dnsRequire(isDnsHostname(root), "请输入有效 Zone 名称；国际化域名请使用 Punycode")
    val name = normalizeDnsName(root, input.name)
    val fields = input.fields.toMutableMap()
    // Preserve TXT exactly, including empty strings, quotes, whitespace and line breaks.
    val content = fields["content"] as? JsonPrimitive
    if (type != "TXT" && content?.isString == true) fields["content"] = JsonPrimitive(content.content.trim())
    val local = LocalDnsSchema(schema)
    requireKnownFields(local, schema, JsonObject(fields), "fields", setOf("type", "name"))
    val payload = JsonObject(fields + mapOf("type" to JsonPrimitive(type), "name" to JsonPrimitive(name)))
    val violations = validateDnsFields(schema, payload)
    dnsRequire(violations.isEmpty(), violations.joinToString("; ") { "${it.path}: ${it.message}" })

    val full = method != DnsWriteMethod.PATCH
    val properties = local.properties()
    // The pinned OpenAPI omits required lists. These are explicit product safety
    // rules for complete records, not changes to the generated official schema.
    if (full) {
        for (field in listOf("content", "data")) if (field in properties) dnsRequire(field in fields, "缺少 $type 的 $field 字段")
        if (type in setOf("MX", "URI")) dnsRequire("priority" in fields, "缺少 $type 的 priority 字段")
        val data = fields["data"] as? JsonObject
        if (data != null) {
            val componentNames = properties.getValue("data").flatMap { local.properties(it).keys }.toSet()
            dnsRequire(data.keys.containsAll(componentNames), "缺少 $type data 字段：${(componentNames - data.keys).joinToString()}")
        }
    }
    requireDnsNumbers(type, JsonObject(fields))
    (fields["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text ->
        if (type != "TXT") dnsRequire(text.isNotBlank(), "记录内容不能为空")
        if (type in setOf("CNAME", "MX", "NS", "PTR")) dnsRequire(isDnsHostname(text) && text.removeSuffix(".").contains('.'), "请输入完整目标主机名")
        if (type == "CNAME") dnsRequire(text.removeSuffix(".").lowercase() != name, "CNAME 目标不能与记录名称相同")
    }
    (fields["data"] as? JsonObject)?.let { data ->
        for ((key, value) in data) if (value is JsonPrimitive && value.isString) {
            val allowEmpty = (type == "NAPTR" && key in setOf("flags", "regex", "replacement", "service")) || (type in setOf("HTTPS", "SVCB") && key == "value")
            dnsRequire(allowEmpty || value.content.isNotBlank(), "$type data.$key 不能为空")
        }
        if (type in setOf("HTTPS", "SVCB")) data["target"]?.let {
            dnsRequire(isDnsHostname(it.jsonPrimitive.content, allowRoot = true), "$type target 必须是主机名或 .")
        }
        if (type == "LOC") {
            for ((degrees, minutes, seconds, maximum) in listOf(
                listOf("lat_degrees", "lat_minutes", "lat_seconds", "90"),
                listOf("long_degrees", "long_minutes", "long_seconds", "180"),
            )) {
                if (data[degrees]?.jsonPrimitive?.doubleOrNull == maximum.toDouble()) {
                    dnsRequire(listOf(minutes, seconds).all { data[it]?.jsonPrimitive?.doubleOrNull?.let { n -> n == 0.0 } != false }, "LOC 极点角度不能带非零分秒")
                }
            }
        }
    }
    fields["ttl"]?.let {
        val ttl = it.jsonPrimitive.intOrNull
        dnsRequire(ttl != null && (ttl == 1 || ttl in constraints.minTtl..constraints.maxTtl), "TTL 请填写 1（自动）或 ${constraints.minTtl}–${constraints.maxTtl} 秒；未验证 Enterprise 套餐时最低为 60 秒")
    }
    val proxied = fields["proxied"] == JsonPrimitive(true)
    dnsRequire(!proxied || type in constraints.proxyTypes, "该记录类型或当前约束不支持 Cloudflare 代理")
    if (proxied && (method != DnsWriteMethod.PATCH || "ttl" in fields)) fields["ttl"] = JsonPrimitive(1)
    (fields["settings"] as? JsonObject)?.let {
        dnsRequire(!(it["ipv4_only"] == JsonPrimitive(true) && it["ipv6_only"] == JsonPrimitive(true)), "ipv4_only 与 ipv6_only 不能同时开启")
        dnsRequire(!(proxied && it["flatten_cname"] == JsonPrimitive(true)), "代理记录不支持 flatten_cname 设置")
    }
    // Parsing detaches nested maps/lists from mutable caller-owned backing collections.
    return DnsRecordInput(type, name, Json.parseToJsonElement(JsonObject(fields).toString()).jsonObject)
}

private fun dnsRequire(ok: Boolean, message: String) { if (!ok) throw CloudException(message) }

private fun normalizeDnsName(root: String, raw: String): String {
    val input = raw.trim().lowercase()
    dnsRequire(input.isNotEmpty() && !input.endsWith(".."), "请输入有效记录名称")
    val absolute = input.endsWith('.')
    val trimmed = input.removeSuffix(".")
    val name = when {
        input == "@" -> root
        trimmed == root || trimmed.endsWith(".$root") -> trimmed
        absolute -> throw CloudException("记录名称不属于当前 Zone")
        else -> "$trimmed.$root"
    }
    dnsRequire(name.length <= 253 && name.split('.').withIndex().all { (index, label) ->
        (label == "*" && index == 0) || validDnsLabel(label, owner = true)
    }, "请输入有效记录名称；国际化域名请使用 Punycode")
    return name
}

private fun validDnsLabel(label: String, owner: Boolean = false): Boolean {
    if (label.isEmpty() || label.length > 63) return false
    // DNS owner labels retain the legacy ASCII underscore/hyphen behavior;
    // a target hostname has the narrower host-label boundary rules.
    return (owner || (label.first() != '-' && label.last() != '-')) &&
        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || (owner && it == '_') }
}

internal fun isDnsHostname(value: String, allowRoot: Boolean = false): Boolean {
    if (allowRoot && value == ".") return true
    val name = value.removeSuffix(".")
    return name.length in 1..253 && name.split('.').all { validDnsLabel(it) }
}

internal fun isDnsIpv4(value: String): Boolean = value.split('.').let { parts ->
    parts.size == 4 && parts.all {
        it.isNotEmpty() && (it.length == 1 || !it.startsWith('0')) && it.all { c -> c in '0'..'9' } && it.toIntOrNull() in 0..255
    }
}

internal fun isDnsIpv6(value: String): Boolean {
    if (value.isEmpty() || '%' in value || ":::" in value) return false
    val compressed = "::" in value
    if (value.windowed(2).count { it == "::" } > 1 || (value.startsWith(':') && !value.startsWith("::")) || (value.endsWith(':') && !value.endsWith("::"))) return false
    val parts = value.split(':').filter { it.isNotEmpty() }
    val mapped = parts.lastOrNull()?.contains('.') == true
    // Empty-group filtering must not move an embedded IPv4 group past a final ::.
    if (mapped && value.substringAfterLast(':') != parts.last()) return false
    val count = parts.size + if (mapped) 1 else 0
    return (if (compressed) count < 8 else count == 8) && parts.withIndex().all { (index, part) ->
        if (mapped && index == parts.lastIndex) isDnsIpv4(part)
        else part.length in 1..4 && part.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }
}

private fun requireKnownFields(local: LocalDnsSchema, schema: JsonObject, value: JsonObject, path: String, reserved: Set<String> = emptySet()) {
    val properties = local.properties(schema)
    for ((key, field) in value) {
        dnsRequire(key !in reserved && key in properties, "$path.$key 不是已知可写字段")
        if (field is JsonObject) {
            val nested = properties.getValue(key)
            // allOf can declare disjoint properties (notably CNAME settings).
            val combined = buildJsonObject { put("allOf", JsonArray(nested)) }
            requireKnownFields(local, combined, field, "$path.$key")
        }
    }
}

private fun requireDnsNumbers(type: String, fields: JsonObject) {
    fun visit(value: JsonObject, path: String) {
        value.forEach { (key, entry) ->
            if (entry is JsonObject) visit(entry, "$path.$key")
            else if (entry is JsonPrimitive && !entry.isString && entry.doubleOrNull != null) {
                val decimal = type == "LOC" && path == "fields.data" && key in setOf("altitude", "lat_seconds", "long_seconds", "precision_horz", "precision_vert", "size")
                dnsRequire(decimal || entry.double % 1.0 == 0.0, "$path.$key 必须是整数")
            }
        }
    }
    visit(fields, "fields")
}
