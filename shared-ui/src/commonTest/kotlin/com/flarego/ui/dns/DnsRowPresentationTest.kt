package com.flarego.ui.dns

import com.flarego.core.application.dns.DnsRecordView
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class DnsRowPresentationTest {
    private fun row(name: String, proxy: Boolean? = false, content: String? = "192.0.2.10", type: String = "A") =
        DnsRecordView("record", type, name, content, 3600, proxy, null, JsonObject(emptyMap()))

    @Test fun namesAreRelativeOnlyWithinTheExactZone() {
        assertEquals("@", dnsRowPresentation(row("example.com"), "example.com").name)
        assertEquals("www", dnsRowPresentation(row("www.example.com"), "example.com").name)
        assertEquals("api.eu", dnsRowPresentation(row("api.eu.EXAMPLE.com."), "example.com").name)
        assertEquals("notexample.com", dnsRowPresentation(row("notexample.com"), "example.com").name)
        assertEquals("other.net", dnsRowPresentation(row("other.net"), "example.com").name)
    }
    @Test fun missingProxyIsNotMisrepresentedAsDnsOnly() {
        assertEquals("已代理", dnsRowPresentation(row("@", true), "example.com").status)
        assertEquals("仅 DNS", dnsRowPresentation(row("@", false), "example.com").status)
        assertEquals("未提供", dnsRowPresentation(row("@", null), "example.com").status)
    }
    @Test fun uncachedContentIsExplicitAndProviderTextIsPreserved() {
        assertEquals("点击查看详情", dnsRowPresentation(row("@", content = null, type = "SRV"), "example.com").content)
        assertEquals("点击读取内容", dnsRowPresentation(row("@", content = null, type = "TXT"), "example.com").content)
        assertEquals("  txt value  ", dnsRowPresentation(row("@", content = "  txt value  ", type = "TXT"), "example.com").content)
    }
}
