package com.flarego.core

import com.flarego.core.application.validateDns
import com.flarego.core.model.*
import kotlin.test.*

class DnsValidationTest {
    private val domain = Domain("zone", "example.com", "active", "live")

    @Test
    fun txtPreservesIntentionalSpaces() {
        assertEquals(
            "  verification value  ",
            validateDns(domain, DnsDraft("TXT", "@", "  verification value  ")).content,
        )
    }

    @Test
    fun acceptsCompressedIpv6AndMappedAddress() {
        listOf("2001:db8:1:2:3:4:5::", "::1", "::ffff:192.0.2.1").forEach {
            assertEquals(it, validateDns(domain, DnsDraft("AAAA", "@", it)).content)
        }
    }

    @Test
    fun rejectsMalformedIpv6() {
        listOf(":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "1:::2", "1:2:3:4:5:6:7:8::", "::ffff:999.1.1.1")
            .forEach {
                assertFailsWith<CloudException> { validateDns(domain, DnsDraft("AAAA", "@", it)) }
            }
    }

    @Test
    fun relativeNameAndProxyTtlNormalize() {
        val draft = validateDns(domain, DnsDraft("CNAME", "WWW", "origin.example.com", 3600, true))
        assertEquals("www.example.com", draft.name)
        assertEquals(1, draft.ttl)
    }
}
