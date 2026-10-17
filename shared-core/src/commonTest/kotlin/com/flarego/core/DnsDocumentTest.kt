package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.validateDns
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class DnsDocumentTest {
    private val constraints = DnsConstraints()
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun input(type: String, fields: String, name: String = "@") = DnsRecordInput(type, name, obj(fields))
    private fun valid(type: String, fields: String, method: DnsWriteMethod = DnsWriteMethod.POST) =
        validateDnsInput("example.com", input(type, fields), constraints, method)
    private fun bad(type: String, fields: String, method: DnsWriteMethod = DnsWriteMethod.POST) {
        assertFailsWith<CloudException>("$type $fields") { valid(type, fields, method) }
    }

    // Values with examples are copied from the pinned OpenAPI record properties.
    // The snapshot omits examples for some strings: use concrete DNS wire-format values there.
    private val fixtures = linkedMapOf(
        "A" to """{"content":"198.51.100.4"}""",
        "AAAA" to """{"content":"2400:cb00:2049::1"}""",
        "CAA" to """{"data":{"flags":1,"tag":"issue","value":"letsencrypt.org"}}""",
        "CERT" to """{"data":{"algorithm":8,"certificate":"AQID","key_tag":1,"type":9}}""",
        "CNAME" to """{"content":"origin.example.com"}""",
        "DNSKEY" to """{"data":{"algorithm":5,"flags":1,"protocol":3,"public_key":"AQID"}}""",
        "DS" to """{"data":{"algorithm":3,"digest":"1234ABCD","digest_type":1,"key_tag":1}}""",
        "HTTPS" to """{"data":{"priority":1,"target":".","value":"alpn=\"h3,h2\" ipv4hint=\"127.0.0.1\" ipv6hint=\"::1\""}}""",
        "LOC" to """{"data":{"altitude":0,"lat_degrees":37,"lat_direction":"N","lat_minutes":46,"lat_seconds":46,"long_degrees":122,"long_direction":"W","long_minutes":23,"long_seconds":35,"precision_horz":0,"precision_vert":0,"size":100}}""",
        "MX" to """{"content":"mx.example.com","priority":10}""",
        "NAPTR" to """{"data":{"flags":"S","order":100,"preference":10,"regex":"","replacement":"_sip._udp.example.com","service":"SIP+D2U"}}""",
        "NS" to """{"content":"ns1.example.com"}""",
        "OPENPGPKEY" to """{"content":"AQID"}""",
        "PTR" to """{"content":"example.com"}""",
        "SMIMEA" to """{"data":{"certificate":"ABCD","matching_type":0,"selector":0,"usage":3}}""",
        "SRV" to """{"data":{"port":8806,"priority":10,"target":"example.com","weight":5}}""",
        "SSHFP" to """{"data":{"algorithm":2,"fingerprint":"ABCD","type":1}}""",
        "SVCB" to """{"data":{"priority":1,"target":".","value":"alpn=\"h3,h2\" ipv4hint=\"127.0.0.1\" ipv6hint=\"::1\""}}""",
        "TLSA" to """{"data":{"certificate":"ABCD","matching_type":1,"selector":0,"usage":0}}""",
        "TXT" to """{"content":"\"v=spf1 include:example.com -all\""}""",
        "URI" to """{"data":{"target":"http://example.com/example.html","weight":20},"priority":10}""",
    )

    @Test fun all21OfficialShapesValidateForCreateAndReplace() {
        assertEquals(21, DnsSchemas.types.size)
        assertEquals(fixtures.keys, DnsSchemas.types)
        fixtures.forEach { (type, fields) ->
            for (method in listOf(DnsWriteMethod.POST, DnsWriteMethod.PUT)) {
                val result = valid(type, fields, method)
                assertEquals(type, result.type)
                assertEquals("example.com", result.name)
                assertEquals(obj(fields), result.fields, "$type $method")
            }
        }
    }

    @Test fun txtQuotesNewlinesSpacesAndEmptyArePreservedButMissingAndNullAreDistinct() {
        for (text in listOf("quoted \"value\"\nline", "  verification value  ", "")) {
            val fields = buildJsonObject { put("content", text) }
            assertEquals(text, validateDnsInput("example.com", DnsRecordInput("txt", "@", fields), constraints).fields["content"]!!.jsonPrimitive.content)
        }
        bad("TXT", "{}")
        bad("TXT", """{"content":null}""")
    }

    @Test fun ipv4AndIpv6RejectMalformedAddresses() {
        for (address in listOf("256.1.2.3", "1.2.3", "a.1.2.3", "01.2.3.4")) bad("A", """{"content":"$address"}""")
        for (address in listOf("1:::2", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "1:2:3:4:5:6:7:8::", "::ffff:999.1.1.1", "fe80::1%eth0")) bad("AAAA", """{"content":"$address"}""")
        for (address in listOf("::", "::1", "2001:db8:1:2:3:4:5::", "::ffff:192.0.2.1")) valid("AAAA", """{"content":"$address"}""")
    }

    @Test fun embeddedIpv4MustEndTheOriginalIpv6Text() {
        for (address in listOf("192.0.2.1::", "1:2:3:4:5:192.0.2.1::", "::192.0.2.1:")) {
            bad("AAAA", """{"content":"$address"}""")
        }
        valid("AAAA", """{"content":"1:2:3:4:5:6:192.0.2.1"}""")
        valid("AAAA", """{"content":"::ffff:192.0.2.1"}""")
    }

    @Test fun numericBoundsAndStructuredCompletenessAreEnforced() {
        bad("MX", """{"content":"mx.example.com","priority":65536}""")
        bad("MX", """{"content":"mx.example.com","priority":-1}""")
        bad("MX", """{"content":"mx.example.com","priority":1.5}""")
        bad("SRV", """{"data":{"priority":10,"weight":5,"port":65536,"target":"example.com"}}""")
        for (type in listOf("CAA", "LOC", "TLSA")) {
            bad(type, "{}")
            bad(type, """{"data":{}}""")
            val fields = obj(fixtures.getValue(type))
            fields["data"]!!.jsonObject.keys.forEach { missing ->
                val data = JsonObject(fields["data"]!!.jsonObject - missing)
                bad(type, JsonObject(fields + ("data" to data)).toString())
            }
        }
        bad("LOC", fixtures.getValue("LOC").replace("\"lat_degrees\":37", "\"lat_degrees\":91"))
        bad("CAA", fixtures.getValue("CAA").replace("\"flags\":1", "\"flags\":256"))
        bad("TLSA", fixtures.getValue("TLSA").replace("\"usage\":0", "\"usage\":256"))
    }

    @Test fun httpsAndSvcbUseStructuredParametersWithoutContentInference() {
        for (type in listOf("HTTPS", "SVCB")) {
            valid(type, fixtures.getValue(type))
            valid(type, """{"data":{"priority":0,"target":"svc.example.com","value":""}}""")
            bad(type, """{"data":{"priority":1,"target":".","value":{}}}""")
            bad(type, """{"data":{"priority":65536,"target":".","value":""}}""")
            bad(type, """{"content":"1 . alpn=h2"}""")
            bad(type, """{"data":{"priority":1,"target":"."}}""")
        }
    }

    @Test fun ttlPlanAndProxyRulesAreExplicit() {
        val ttl30 = input("A", """{"content":"198.51.100.4","ttl":30}""")
        assertFailsWith<CloudException> { validateDnsInput("example.com", ttl30, constraints) }
        assertEquals(30, validateDnsInput("example.com", ttl30, DnsConstraints(minTtl = 30)).fields["ttl"]!!.jsonPrimitive.int)
        for (ttl in listOf(0, 2, 59, 86401)) bad("A", """{"content":"198.51.100.4","ttl":$ttl}""")
        assertEquals(1, valid("A", """{"content":"198.51.100.4","proxied":true,"ttl":3600}""").fields["ttl"]!!.jsonPrimitive.int)
        bad("TXT", """{"content":"value","proxied":true}""")
        assertFailsWith<CloudException> { validateDnsInput("example.com", input("A", """{"content":"198.51.100.4","proxied":true}"""), DnsConstraints(proxyTypes = emptySet())) }
    }

    @Test fun namesHaveDnsBoundariesAndPunycodeWithoutUnicodeGuessing() {
        for ((name, expected) in mapOf("@" to "example.com", "WWW" to "www.example.com", "WWW.EXAMPLE.COM." to "www.example.com", "*.foo" to "*.foo.example.com", "_sip._tcp" to "_sip._tcp.example.com", "xn--bcher-kva" to "xn--bcher-kva.example.com")) {
            assertEquals(expected, validateDnsInput("example.com", input("TXT", """{"content":"value"}""", name), constraints).name)
        }
        for (name in listOf("", "a..b", "a.*", "a*b", "é", "a".repeat(64), "outside.example.net.", "foo..")) {
            assertFailsWith<CloudException>(name) { validateDnsInput("example.com", input("TXT", """{"content":"value"}""", name), constraints) }
        }
        bad("CNAME", """{"content":"example.com"}""")
        bad("MX", """{"content":"bad host.example.com","priority":10}""")
    }

    @Test fun documentsPreserveFutureRawWhileWritesRejectUnknownAndReadonlyFields() {
        val raw = obj("""{"id":"r","type":"FUTURE","content":"opaque","future":{"k":[1,null]},"created_on":"later"}""")
        val document = DnsRecordDocument("r", ZoneScope(AccountScope("connection", "account"), "zone"), raw)
        assertEquals(raw, document.raw)
        assertNull(DnsSchemas.forType("FUTURE"))
        bad("FUTURE", """{"content":"opaque"}""")
        for (field in listOf("id", "created_on", "future", "name", "type")) bad("A", """{"content":"198.51.100.4","$field":"value"}""")
        bad("A", """{"content":"198.51.100.4","settings":{"future":true}}""")
        bad("CAA", """{"data":{"flags":1,"tag":"issue","value":"ca.example"},"content":"formatted"}""")
    }

    @Test fun partialPatchKeepsOmissionsAndNeverInventsReplacementData() {
        fixtures.keys.forEach { type ->
            assertEquals(obj("""{"comment":"edited"}"""), valid(type, """{"comment":"edited"}""", DnsWriteMethod.PATCH).fields)
            bad(type, """{"comment":"edited"}""", DnsWriteMethod.PUT)
        }
        assertEquals(obj("""{"data":{"port":443}}"""), valid("SRV", """{"data":{"port":443}}""", DnsWriteMethod.PATCH).fields)
        bad("SRV", """{"data":{"port":70000}}""", DnsWriteMethod.PATCH)
        val backing = mutableMapOf<String, JsonElement>("content" to JsonPrimitive("198.51.100.4"))
        val result = validateDnsInput("example.com", DnsRecordInput("A", "@", JsonObject(backing)), constraints)
        backing["content"] = JsonPrimitive("changed")
        assertEquals("198.51.100.4", result.fields["content"]!!.jsonPrimitive.content)
    }

    @Test fun localValidatorHandlesCompositionsNullableArraysPatternsAndBounds() {
        val schema = obj("""{"type":"object","required":["value"],"properties":{"value":{"allOf":[{"type":"array","minItems":1,"maxItems":2},{"items":{"oneOf":[{"type":"string","pattern":"^x+$"},{"type":"integer","minimum":1,"maximum":3}]}}]},"note":{"type":"string","nullable":true}}}""")
        assertTrue(validateDnsFields(schema, obj("""{"value":["xx",2],"note":null}""")).isEmpty())
        for (value in listOf("{}", """{"value":[]}""", """{"value":[0]}""", """{"value":["y"]}""", """{"value":[1,2,3]}""")) assertTrue(validateDnsFields(schema, obj(value)).isNotEmpty(), value)
        val ambiguous = obj("""{"properties":{"x":{"oneOf":[{"type":"number"},{"type":"integer"}]}}}""")
        assertTrue(validateDnsFields(ambiguous, obj("""{"x":1}""")).isNotEmpty())
        val refs = obj("""{"type":"object","properties":{"x":{"${'$'}ref":"#/${'$'}defs/value"}},"${'$'}defs":{"value":{"type":"string","enum":["yes"]}}}""")
        assertTrue(validateDnsFields(refs, obj("""{"x":"yes"}""")).isEmpty())
        assertTrue(validateDnsFields(refs, obj("""{"x":"no"}""")).isNotEmpty())
    }

    @Test fun unsupportedSchemasAlwaysFailIncludingUnusedBranchesAndNullable() {
        for (schema in listOf(
            """{"not":{}}""", """{"future":true}""", """{"type":["object","null"]}""",
            """{"required":"x"}""", """{"oneOf":[]}""", """{"properties":{"x":{"format":"unknown"}}}""",
            """{"anyOf":[{}, {"not":{}}]}""", """{"properties":{"x":{"nullable":true,"unknown":true}}}""",
            """{"${'$'}ref":"#/missing"}""", """{"${'$'}defs":{"loop":{"${'$'}ref":"#/${'$'}defs/loop"}},"${'$'}ref":"#/${'$'}defs/loop"}""",
        )) assertTrue(validateDnsFields(obj(schema), obj("{}")).isNotEmpty(), schema)
    }

    @Test fun legacyFiveTypeFormRetainsCompatibility() {
        val domain = Domain("zone", "example.com", "active", "live")
        for ((type, content) in mapOf("A" to "198.51.100.4", "AAAA" to "::ffff:192.0.2.1", "CNAME" to "origin.example.com", "TXT" to "  text\nvalue  ", "MX" to "mx.example.com")) {
            val draft = validateDns(domain, DnsDraft(type.lowercase(), "WWW", content, 3600, true, 10))
            assertEquals(type, draft.type)
            assertEquals("www.example.com", draft.name)
            assertEquals(content, draft.content)
            assertEquals(type in setOf("A", "AAAA", "CNAME"), draft.proxied)
            assertEquals(if (draft.proxied) 1 else 3600, draft.ttl)
            assertEquals(if (type == "MX") 10 else null, draft.priority)
        }
        for (draft in listOf(DnsDraft("TXT", "@", " "), DnsDraft("MX", "@", "mx.example.com"), DnsDraft("CAA", "@", "value"), DnsDraft("A", "@", "198.51.100.4", 30))) {
            assertFailsWith<CloudException> { validateDns(domain, draft) }
        }
    }

    @Test fun legacyOwnerNamesKeepAsciiUnderscoresAnywhere() {
        val domain = Domain("zone", "example.com", "active", "live")
        for (name in listOf("selector_1._domainkey", "selector__1._domainkey", "_acme-challenge")) {
            assertEquals("$name.example.com", validateDns(domain, DnsDraft("TXT", name, "key")).name)
        }
    }

    @Test fun documentOwnerNamesKeepAsciiUnderscoresWithoutRelaxingTargetHostnames() {
        for (name in listOf("selector_1._domainkey", "selector__1._domainkey", "_acme-challenge")) {
            assertEquals("$name.example.com", validateDnsInput("example.com", input("TXT", """{"content":"key"}""", name), constraints).name)
        }
        bad("CNAME", """{"content":"selector_1.example.com"}""")
        bad("MX", """{"content":"selector_1.example.com","priority":10}""")
    }

    @Test fun ownerLabelHyphenBoundariesRemainSeparateFromHostnameBoundaries() {
        val domain = Domain("zone", "example.com", "active", "live")
        for (name in listOf("-selector", "selector-", "_")) {
            assertEquals("$name.example.com", validateDns(domain, DnsDraft("TXT", name, "key")).name)
            assertEquals("$name.example.com", validateDnsInput("example.com", input("TXT", """{"content":"key"}""", name), constraints).name)
        }
        bad("CNAME", """{"content":"-selector.example.com"}""")
        bad("MX", """{"content":"selector-.example.com","priority":10}""")
    }
}
