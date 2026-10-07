package com.flarego.core.application

import com.flarego.core.model.*

fun validateDns(domain: Domain, draft: DnsDraft): DnsDraft {
    fun requireValid(ok: Boolean, message: String) {
        if (!ok) throw CloudException(message)
    }
    val type = draft.type.uppercase()
    requireValid(type in setOf("A", "AAAA", "CNAME", "TXT", "MX"), "该记录类型目前仅支持查看，请使用官方控制台编辑")
    val input = draft.name.trim().trimEnd('.')
    val root = domain.name.lowercase().trimEnd('.')
    val name =
        when {
            input == "@" -> root
            input.lowercase() == root || input.lowercase().endsWith(".$root") -> input.lowercase()
            else -> "${input.lowercase()}.$root"
        }
    requireValid(
        input.isNotBlank() &&
            name.length <= 253 &&
            name.split('.').all { label ->
                label == "*" ||
                    (label.isNotEmpty() &&
                        label.length <= 63 &&
                        label.all { it.isLetterOrDigit() && it.code < 128 || it in "-_" })
            },
        "请输入有效记录名称；国际化域名请使用 Punycode",
    )
    val content = if (type == "TXT") draft.content else draft.content.trim()
    requireValid(content.isNotBlank(), "记录内容不能为空")
    when (type) {
        "A" -> requireValid(isIpv4(content), "请输入有效 IPv4 地址")
        "AAAA" -> {
            val parts = content.split(':').filter { it.isNotEmpty() }
            val compressed = "::" in content
            val mapped = parts.lastOrNull()?.contains('.') == true
            val count = parts.size + if (mapped) 1 else 0
            requireValid(
                content.windowed(2).count { it == "::" } <= 1 &&
                    (!content.startsWith(':') || content.startsWith("::")) &&
                    (!content.endsWith(':') || content.endsWith("::")) &&
                    (if (compressed) count < 8 else count == 8) &&
                    parts.withIndex().all { (i, part) ->
                        if (mapped && i == parts.lastIndex) isIpv4(part)
                        else
                            part.length <= 4 &&
                                part.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' }
                    },
                "请输入有效 IPv6 地址",
            )
        }
        "CNAME",
        "MX" ->
            requireValid(
                content.trimEnd('.').contains('.') && content.none(Char::isWhitespace),
                "请输入完整目标主机名",
            )
    }
    requireValid(type != "MX" || draft.priority in 0..65535, "MX 优先级必须在 0–65535 之间")
    requireValid(draft.ttl == 1 || draft.ttl in 60..86400, "TTL 请填写 1（自动）或 60–86400 秒")
    val proxied = draft.proxied && type in setOf("A", "AAAA", "CNAME")
    return draft.copy(
        type = type,
        name = name,
        content = content,
        proxied = proxied,
        ttl = if (proxied) 1 else draft.ttl,
        priority = if (type == "MX") draft.priority else null,
    )
}

private fun isIpv4(value: String) =
    value.split('.').let {
        it.size == 4 &&
            it.all { part ->
                part.isNotEmpty() && part.all { c -> c in '0'..'9' } && part.toIntOrNull() in 0..255
            }
    }
