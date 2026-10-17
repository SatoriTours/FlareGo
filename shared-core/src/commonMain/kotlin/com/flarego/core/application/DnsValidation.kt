package com.flarego.core.application

import com.flarego.core.application.dns.validateDnsInput
import com.flarego.core.model.*
import kotlinx.serialization.json.*

/** Compatibility adapter for the existing five-type form. */
fun validateDns(domain: Domain, draft: DnsDraft): DnsDraft {
    val type = draft.type.uppercase()
    if (type !in setOf("A", "AAAA", "CNAME", "TXT", "MX")) {
        throw CloudException("该记录类型目前仅支持查看，请使用官方控制台编辑")
    }
    // Keep the old form's blank-content guard, including its TXT behavior.
    if (draft.content.isBlank()) throw CloudException("记录内容不能为空")
    val fields = buildJsonObject {
        put("content", draft.content)
        put("ttl", draft.ttl)
        put("proxied", draft.proxied && type in setOf("A", "AAAA", "CNAME"))
        if (type == "MX" && draft.priority != null) put("priority", draft.priority)
    }
    val input = validateDnsInput(domain.name, DnsRecordInput(type, draft.name, fields), DnsConstraints())
    return draft.copy(
        type = input.type,
        name = input.name,
        content = input.fields.getValue("content").jsonPrimitive.content,
        ttl = input.fields.getValue("ttl").jsonPrimitive.int,
        proxied = input.fields.getValue("proxied").jsonPrimitive.boolean,
        priority = if (type == "MX") input.fields.getValue("priority").jsonPrimitive.int else null,
    )
}
