package com.flarego.ui.dns

import com.flarego.core.application.dns.DnsRecordView

/** Display only: shortening never changes the provider name or a write payload. */
internal data class DnsRowPresentation(val name: String, val content: String, val status: String)
internal fun dnsRowPresentation(row: DnsRecordView, zoneName: String): DnsRowPresentation {
    val name = row.name.trimEnd('.')
    val zone = zoneName.trimEnd('.')
    val relative = when {
        zone.isEmpty() -> row.name
        name.equals(zone, ignoreCase = true) -> "@"
        name.endsWith(".$zone", ignoreCase = true) -> name.dropLast(zone.length + 1)
        else -> row.name
    }
    return DnsRowPresentation(relative,
        row.content ?: if (row.type == "TXT") "点击读取内容" else "点击查看详情",
        when (row.proxied) { true -> "已代理"; false -> "仅 DNS"; null -> "未提供" })
}
