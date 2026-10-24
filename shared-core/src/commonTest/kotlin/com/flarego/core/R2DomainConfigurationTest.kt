package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.protocol.r2DomainRegistrations
import com.flarego.core.cloudflare.r2.CloudflareR2DomainConfigurationClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.ports.R2DomainZoneLookup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch B2 domain plane, local and synthetic only.
 *
 * These assertions lock the reviewed domain rules: an explicit `enabled`, a proven zone on a DNS
 * label boundary, `enabled` never meaning ownership/SSL, `minTLS` as the only documented
 * omit-preserves field, the provider's current cipher list preserved verbatim (no explicit cipher
 * write is offered), a create defaulting to disabled + TLS 1.2, a delete proving the current state
 * first, and exactly one guarded send per write.
 */
class R2DomainConfigurationTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val foreignAccount = AccountScope("local", "account-two")
    private val target = R2WriteTarget(account, "my-bucket")
    private val eu = R2WriteTarget(account, "my-bucket", "eu")
    private val domain = "cdn.example.com"
    private val zoneId = "0123456789abcdef0123456789abcdef"
    private val requests = mutableListOf<HttpRequest>()
    private var respond: suspend (HttpRequest) -> HttpResponse = { ok("{}") }
    private var zoneFailure: CloudFailure? = null
    private val zoneCalls = mutableListOf<Pair<AccountScope, String>>()

    private fun ok(result: String = "{}") =
        HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")

    private fun domainRow(
        domain: String = this.domain,
        enabled: Boolean = false,
        extra: String = "",
        ciphers: String = """["ECDHE-ECDSA-AES128-GCM-SHA256"]""",
    ) = """{"domain":"$domain","enabled":$enabled,"minTLS":"1.2","ciphers":$ciphers,"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}$extra}"""

    private fun zones(): R2DomainZoneLookup =
        object : R2DomainZoneLookup {
            override suspend fun zoneName(account: AccountScope, zoneId: String): String {
                zoneCalls += account to zoneId
                zoneFailure?.let { throw it }
                if (account != this@R2DomainConfigurationTest.account) {
                    throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "该 Zone 不属于当前账号")
                }
                return "example.com"
            }
        }

    private fun client(beforeMutation: () -> Unit = {}) =
        CloudflareR2DomainConfigurationClient(
            connection,
            CloudflareGateway(HttpTransport { request ->
                requests += request
                respond(request)
            }),
            zones(),
            beforeMutation,
        )

    private fun HttpRequest.json(): JsonObject =
        Json.parseToJsonElement((payload as HttpBody.Text).value).jsonObject

    private fun List<HttpRequest>.mutation() = single { it.method != "GET" }

    /** Per-endpoint router: bucket proof, the managed endpoint, the collection and one domain. */
    private fun routes(
        managed: String = """{"bucketId":"b1","domain":"pub-b1.r2.dev","enabled":true}""",
        collection: String = """{"domains":[]}""",
        one: String = domainRow(),
        write: String = domainRow(),
    ): suspend (HttpRequest) -> HttpResponse = { request ->
        when {
            request.method != "GET" -> ok(write)
            request.path.endsWith("/domains/managed") -> ok(managed)
            request.path.endsWith("/domains/custom") -> ok(collection)
            request.path.endsWith("/domains/custom/$domain") -> ok(one)
            else -> ok("""{"name":"my-bucket"}""")
        }
    }

    /** The expected pre-write conversation for a single-domain write: bucket proof, snapshot, write. */
    private fun singleDomainResponder(row: String = domainRow()): suspend (HttpRequest) -> HttpResponse = { request ->
        when {
            request.method != "GET" -> ok(row)
            request.path.endsWith("/domains/custom/$domain") -> ok(row)
            else -> ok("""{"name":"my-bucket"}""")
        }
    }

    // ------------------------------------------------------------------ reviewed registrations

    @Test
    fun theSevenReviewedDomainRoutesAreRegisteredExactlyOnce() {
        val registrations = r2DomainRegistrations().associateBy { it.policy.key }
        val expected = mapOf(
            "r2-get-bucket-public-policy" to ("GET" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/managed"),
            "r2-put-bucket-public-policy" to ("PUT" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/managed"),
            "r2-list-custom-domains" to ("GET" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/custom"),
            "r2-add-custom-domain" to ("POST" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/custom"),
            "r2-get-custom-domain-settings" to ("GET" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/custom/{domain}"),
            "r2-edit-custom-domain-settings" to ("PUT" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/custom/{domain}"),
            "r2-delete-custom-domain" to ("DELETE" to "/accounts/{account_id}/r2/buckets/{bucket_name}/domains/custom/{domain}"),
        )
        assertEquals(expected.keys, registrations.keys)
        assertEquals(7, r2DomainRegistrations().size)
        assertEquals(3, r2DomainRegistrations().count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(4, r2DomainRegistrations().count { it.policy.semantics == ActionSemantics.WRITE })
        for ((id, methodAndPath) in expected) {
            val registration = assertNotNull(ActionPolicies.registry.registration(id), id)
            assertEquals(methodAndPath.first, registration.method, id)
            assertEquals(methodAndPath.second, registration.pathTemplate, id)
            assertEquals(ActionScope.ACCOUNT, registration.scope, id)
            assertFalse(registration.trailingPlaceholderSpansSegments, id)
            if (registration.policy.semantics == ActionSemantics.WRITE) {
                assertFalse(registration.policy.retry.enabled, "a domain write must never retry: $id")
            } else {
                assertEquals(RiskLevel.READ_ONLY, registration.policy.risk, id)
            }
        }
        assertEquals(RiskLevel.DESTRUCTIVE, ActionPolicies.policy("r2-delete-custom-domain")!!.risk)
        // Current107 includes B3 and C8; the exact domain per-action sets above stay unchanged.
        assertEquals(107, ActionPolicies.registry.registrations.size)
        assertEquals(55, ActionPolicies.registry.registrations.values.count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(52, ActionPolicies.registry.registrations.values.count { it.policy.semantics == ActionSemantics.WRITE })
    }

    // ------------------------------------------------------------------ locator vocabulary

    @Test
    fun theDomainLocatorVocabularyIsFixedAndFailsClosed() {
        // Batch A/B1 three-segment locators are unchanged.
        val b1 = com.flarego.core.application.r2.R2TaskTargets.reference(target, com.flarego.core.model.R2ConfigurationKind.CORS)
        assertEquals("default/my-bucket/CORS", b1.externalId)
        val parsedB1 = assertNotNull(com.flarego.core.application.r2.R2TaskTargets.parse(b1))
        assertEquals(com.flarego.core.model.R2ConfigurationKind.CORS, parsedB1.kind)
        assertNull(parsedB1.domain)
        // The managed endpoint has no domain; a single domain carries exactly that canonical domain.
        assertEquals(
            "default/my-bucket/MANAGED_DOMAIN",
            com.flarego.core.application.r2.R2TaskTargets.domainReference(target).externalId,
        )
        val domainRef = com.flarego.core.application.r2.R2TaskTargets.domainReference(target, "cdn.example.com")
        assertEquals("default/my-bucket/CUSTOM_DOMAIN/cdn.example.com", domainRef.externalId)
        val parsed = assertNotNull(com.flarego.core.application.r2.R2TaskTargets.parse(domainRef))
        assertEquals(R2DomainKind.CUSTOM_DOMAIN, parsed.domainKind)
        assertEquals("cdn.example.com", parsed.domain)
        assertNull(parsed.kind)
        // Two domains of one bucket are different locations, but the bucket lock is still shared.
        assertTrue(domainRef != com.flarego.core.application.r2.R2TaskTargets.domainReference(target, "static.example.com"))
        assertEquals(
            com.flarego.core.application.r2.R2TaskTargets.group(target),
            com.flarego.core.application.r2.R2TaskTargets.group(eu.copy(jurisdiction = "default")),
        )
        // Every malformed or mismatched locator is untrusted (fail closed).
        val bad = listOf(
            "default/my-bucket/CUSTOM_DOMAIN",
            "default/my-bucket/CUSTOM_DOMAIN/..",
            "default/my-bucket/CUSTOM_DOMAIN/a.b/c",
            "default/my-bucket/MANAGED_DOMAIN/x",
            "default/my-bucket/CORS/x",
            "mars/my-bucket/CORS",
            "default/my-bucket/NOPE",
            "default/my-bucket/CUSTOM_DOMAIN/CDN.example.com",
        )
        for (externalId in bad) {
            assertNull(
                com.flarego.core.application.r2.R2TaskTargets.parse(
                    com.flarego.core.model.ResourceRef(account, com.flarego.core.application.r2.R2TaskTargets.CONFIGURATION_KIND, externalId),
                ),
                externalId,
            )
        }
        // The action-to-family map covers the four reviewed domain writes and nothing else.
        assertEquals("CUSTOM_DOMAIN", com.flarego.core.application.r2.R2TaskTargets.familyOf("r2-add-custom-domain"))
        assertEquals("CUSTOM_DOMAIN", com.flarego.core.application.r2.R2TaskTargets.familyOf("r2-delete-custom-domain"))
        assertEquals("MANAGED_DOMAIN", com.flarego.core.application.r2.R2TaskTargets.familyOf("r2-put-bucket-public-policy"))
        assertEquals("CORS", com.flarego.core.application.r2.R2TaskTargets.familyOf("r2-put-bucket-cors-policy"))
        assertNull(com.flarego.core.application.r2.R2TaskTargets.familyOf("r2-delete-bucket"))
        assertTrue("r2-edit-custom-domain-settings" in com.flarego.core.application.r2.R2TaskTargets.ALL_WRITE_ACTIONS)
    }

    // ------------------------------------------------------------------ explicit managed state

    @Test
    fun disablingTheManagedDomainSendsAnExplicitFalse() = runTest {
        respond = routes(
            managed = """{"bucketId":"b1","domain":"pub-b1.r2.dev","enabled":true}""",
            write = """{"bucketId":"b1","domain":"pub-b1.r2.dev","enabled":false}""",
        )
        assertIs<ExecutionReceipt.Completed>(client().putManagedDomain(target, false))
        val put = requests.mutation()
        assertEquals("PUT", put.method)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/domains/managed", put.path)
        assertEquals("default", put.headers["cf-r2-jurisdiction"])
        assertEquals(JsonPrimitive(false), put.json().getValue("enabled"), "enabled=false must be sent, never omitted")
        assertEquals(1, requests.mutation().let { 1 })
    }

    @Test
    fun theManagedDomainDocumentKeepsEnabledSeparateFromEverythingElse() = runTest {
        respond = { ok("""{"bucketId":"b1","domain":"pub-b1.r2.dev","enabled":false}""") }
        val document = client().getManagedDomain(target)
        assertTrue(document.editable)
        assertEquals(R2DomainKind.MANAGED_DOMAIN, document.kind)
        assertEquals("pub-b1.r2.dev", assertNotNull(document.managed).domain)
        assertFalse(document.managed!!.enabled)
    }

    // ------------------------------------------------------------------ custom domain reads

    @Test
    fun customDomainsExposeOwnershipAndSslAsSeparateProviderStates() = runTest {
        respond = { ok(domainRow(enabled = true)) }
        val document = client().getCustomDomain(R2DomainTarget(target, domain))
        val custom = assertNotNull(document.customDomain)
        // enabled=true is public access only: it proves neither ownership nor a ready certificate.
        assertTrue(custom.enabled)
        assertEquals("pending", assertNotNull(custom.status).ownership)
        assertEquals("initializing", custom.status!!.ssl)
        assertEquals("1.2", custom.minTls)
        assertEquals(listOf("ECDHE-ECDSA-AES128-GCM-SHA256"), custom.ciphers)
        assertTrue(requests.single().path.endsWith("/domains/custom/$domain"))
    }

    @Test
    fun anUnknownDomainFieldOrTlsValueKeepsTheDocumentReadOnly() = runTest {
        respond = { ok(domainRow(extra = ""","future_flag":true""")) }
        assertFalse(client().getCustomDomain(R2DomainTarget(target, domain)).editable)
        // A complete, otherwise legal row that only changes minTLS to an unknown value.
        respond = { ok(domainRow().replace("\"minTLS\":\"1.2\"", "\"minTLS\":\"1.4\"")) }
        assertFalse(client().getCustomDomain(R2DomainTarget(target, domain)).editable, "an unknown TLS version is read-only, never reset")
    }

    // ------------------------------------------------------------------ fix1: explicit / proven preservation

    @Test
    fun anEditSendsEnabledExplicitlyAndOnlyAProvenCipherList() = runTest {
        // The provider currently has public access off and a complete cipher allowlist.
        respond = routes(one = domainRow(enabled = false))
        assertIs<ExecutionReceipt.Completed>(client().editCustomDomain(target, domain, R2CustomDomainEditInput(minTls = "1.3")))
        val body = requests.mutation().json()
        // enabled is not a documented "omitted means keep" field: the proven current value is sent.
        assertEquals(JsonPrimitive(false), body.getValue("enabled"), "enabled must be sent explicitly from the proven current state")
        // ciphers are also not documented as preserve-on-omit: the proven allowlist is re-sent verbatim.
        assertEquals(
            """["ECDHE-ECDSA-AES128-GCM-SHA256"]""",
            body.getValue("ciphers").toString(),
            "the proven current cipher allowlist is re-sent, never dropped",
        )
        assertEquals("1.3", body.getValue("minTLS").jsonPrimitive.content)
    }

    @Test
    fun anEditRefusesWhenTheCurrentCipherStateCannotBeProven() = runTest {
        // No cipher member at all: omission is not a proven "keep previous", so the edit is refused.
        respond = routes(one = """{"domain":"$domain","enabled":false,"minTLS":"1.2","zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}""")
        assertFailsWith<CloudFailure> {
            client().editCustomDomain(target, domain, R2CustomDomainEditInput(enabled = true))
        }
        assertTrue(requests.none { it.method != "GET" }, "an unproven cipher state must not be rewritten")
        // There is no reviewed evidence that any explicit cipher list (including an empty one) is
        // a supported write, so a cipher edit is refused before any send instead of being described
        // as a "clear".
        requests.clear()
        respond = routes(one = domainRow(enabled = false))
        assertFailsWith<CloudFailure> {
            client().editCustomDomain(target, domain, R2CustomDomainEditInput(ciphers = emptyList()))
        }
        assertTrue(requests.none { it.method != "GET" }, "an explicit cipher edit is never sent")
        assertFailsWith<CloudFailure> {
            client().editCustomDomain(
                target,
                domain,
                R2CustomDomainEditInput(ciphers = listOf("ECDHE-ECDSA-AES128-GCM-SHA256")),
            )
        }
        assertTrue(requests.none { it.method != "GET" })
    }

    @Test
    fun noExplicitCipherWriteIsOfferedAndTheProviderListIsOnlyPreservedVerbatim() = runTest {
        // Create: no explicit cipher list can be sent at all, syntactically plausible or not.
        respond = routes(collection = """{"domains":[]}""")
        for (ciphers in listOf(listOf("ECDHE-EXAMPLE"), listOf("ECDHE-ECDSA-AES128-GCM-SHA256"))) {
            assertFailsWith<CloudFailure> {
                client().addCustomDomain(target, R2CustomDomainCreateInput(domain, zoneId, ciphers = ciphers))
            }
        }
        assertTrue(requests.none { it.method != "GET" })

        // Read: a value outside the app's syntactic safety bound keeps the document read-only.
        respond = { ok(domainRow(ciphers = """["aes-128-gcm"]""")) }
        assertFalse(
            client().getCustomDomain(R2DomainTarget(target, domain)).editable,
            "a provider cipher value outside the syntactic bound is not re-sent",
        )
        // A syntactically safe provider value is preserved verbatim when another field is edited;
        // that is preservation of the current state, not a claim that the provider supports it.
        respond = { ok(domainRow(ciphers = """["ECDHE-EXAMPLE"]""")) }
        assertTrue(client().getCustomDomain(R2DomainTarget(target, domain)).editable)
        requests.clear()
        respond = routes(one = domainRow(enabled = false, ciphers = """["ECDHE-EXAMPLE"]"""))
        assertIs<ExecutionReceipt.Completed>(
            client().editCustomDomain(target, domain, R2CustomDomainEditInput(enabled = true)),
        )
        assertEquals(
            """["ECDHE-EXAMPLE"]""",
            requests.mutation().json().getValue("ciphers").toString(),
            "the provider's current list is re-sent verbatim",
        )
    }

    @Test
    fun aSingleDomainReadMustReturnTheRequestedDomain() = runTest {
        // The transport answers with another domain for a request of `cdn.example.com`.
        val other = "other.example.com"
        respond = { request ->
            when {
                request.method != "GET" -> ok("{}")
                request.path.endsWith("/domains/custom/$domain") -> ok(domainRow(domain = other))
                else -> ok("""{"name":"my-bucket"}""")
            }
        }
        val refused = assertFailsWith<CloudFailure> { client().getCustomDomain(R2DomainTarget(target, domain)) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, refused.code)
        // An edit may not proceed on a snapshot of a different domain; a delete proves the
        // complete collection instead (here it is unreadable, so it also refuses).
        assertFailsWith<CloudFailure> { client().editCustomDomain(target, domain, R2CustomDomainEditInput(enabled = true)) }
        assertFailsWith<CloudFailure> { client().deleteCustomDomain(target, domain) }
        assertTrue(requests.none { it.method != "GET" }, "a mismatched single-domain snapshot never authorizes a write")
    }

    @Test
    fun anOversizedDomainDocumentStaysReadOnly() = runTest {
        val huge = "x".repeat(com.flarego.core.model.R2_MAX_CONFIGURATION_DOCUMENT_BYTES)
        respond = {
            ok("""{"domain":"$domain","enabled":false,"minTLS":"1.2","ciphers":[],"zoneId":"$zoneId","zoneName":"$huge","status":{"ownership":"pending","ssl":"initializing"}}""")
        }
        val document = client().getCustomDomain(R2DomainTarget(target, domain))
        assertFalse(document.editable)
        assertTrue(document.raw.toString().length > com.flarego.core.model.R2_MAX_CONFIGURATION_DOCUMENT_BYTES)
    }

    // ------------------------------------------------------------------ zone proof

    @Test
    fun aZoneFromAnotherAccountIsRefusedBeforeAnyWrite() = runTest {
        respond = { ok("""{"name":"my-bucket"}""") }
        val foreignLookup = object : R2DomainZoneLookup {
            override suspend fun zoneName(account: AccountScope, zoneId: String) = "example.com"
        }
        // The lookup itself is bootstrapped with the current account: a foreign scope must be refused
        // even before the lookup, and a lookup failure means "not proven", never "ok".
        val foreignTarget = R2WriteTarget(foreignAccount, "my-bucket")
        assertFailsWith<CloudFailure> {
            CloudflareR2DomainConfigurationClient(connection, CloudflareGateway(HttpTransport { requests += it; ok("{}") }), foreignLookup)
                .addCustomDomain(foreignTarget, R2CustomDomainCreateInput(domain, zoneId))
        }
        zoneFailure = CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "该 Zone 不属于当前账号")
        assertFailsWith<CloudFailure> {
            client().addCustomDomain(target, R2CustomDomainCreateInput(domain, zoneId))
        }
        assertTrue(requests.none { it.method != "GET" }, "an unproven zone must never reach a domain write")
    }

    @Test
    fun aDomainOutsideTheZoneOnADnsLabelBoundaryIsRefused() = runTest {
        respond = { ok("""{"name":"my-bucket"}""") }
        // evil-example.com is not inside example.com, even though the string ends with it.
        assertFailsWith<CloudFailure> {
            client().addCustomDomain(target, R2CustomDomainCreateInput("cdn.evil-example.com", zoneId))
        }
        assertTrue(requests.none { it.method != "GET" })
        // A real subdomain of the zone is accepted.
        requests.clear()
        respond = routes(collection = """{"domains":[]}""")
        assertIs<ExecutionReceipt.Completed>(client().addCustomDomain(target, R2CustomDomainCreateInput(domain, zoneId)))
    }

    // ------------------------------------------------------------------ creation defaults

    @Test
    fun aNewCustomDomainIsExplicitlyDisabledWithTlsAtLeast12() = runTest {
        respond = routes(collection = """{"domains":[]}""")
        assertIs<ExecutionReceipt.Completed>(client().addCustomDomain(target, R2CustomDomainCreateInput(domain, zoneId)))
        val post = requests.mutation()
        assertEquals("POST", post.method)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/domains/custom", post.path)
        val body = post.json()
        assertEquals("cdn.example.com", body.getValue("domain").jsonPrimitive.content)
        assertEquals(zoneId, body.getValue("zoneId").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), body.getValue("enabled"), "a new domain is never published by default")
        assertEquals("1.2", body.getValue("minTLS").jsonPrimitive.content, "the provider would default to 1.0")
        assertEquals(1, zoneCalls.size, "the zone is proven once per create")
    }

    // ------------------------------------------------------------------ preservation semantics

    @Test
    fun anEditOnlySendsTheFieldsTheCallerChanged() = runTest {
        respond = singleDomainResponder()
        assertIs<ExecutionReceipt.Completed>(client().editCustomDomain(target, domain, R2CustomDomainEditInput(enabled = false)))
        val put = requests.mutation()
        val body = put.json()
        assertEquals(JsonPrimitive(false), body.getValue("enabled"))
        assertFalse(body.containsKey("minTLS"), "an omitted minTLS has a documented keep-previous semantic and is not sent")
        // ciphers have no documented keep-previous semantic: the proven current allowlist is re-sent.
        assertEquals("""["ECDHE-ECDSA-AES128-GCM-SHA256"]""", body.getValue("ciphers").toString())
        // An unknown TLS version is refused locally instead of being sent.
        requests.clear()
        assertFailsWith<CloudFailure> {
            client().editCustomDomain(target, domain, R2CustomDomainEditInput(minTls = "0.9"))
        }
        assertTrue(requests.none { it.method != "GET" })
    }

    // ------------------------------------------------------------------ delete proves state first

    @Test
    fun aDeleteRequiresTheCompleteCollectionAndIsOneSend() = runTest {
        // The absence proof for a delete is the complete collection, never a 404 on any read: a
        // missing/unreadable collection cannot prove that the domain is present, so no DELETE.
        respond = { request ->
            when {
                !request.method.equals("GET") -> ok("{}")
                request.path.endsWith("/domains/custom") ->
                    HttpResponse(404, """{"success":false,"errors":[],"messages":[],"result":null}""")
                else -> ok("""{"name":"my-bucket"}""")
            }
        }
        assertFailsWith<CloudFailure> { client().deleteCustomDomain(target, domain) }
        assertTrue(requests.none { it.method == "DELETE" }, "an unproven complete collection must not be deleted")

        // The domain must be present in that complete collection.
        requests.clear()
        respond = { request ->
            when {
                !request.method.equals("GET") -> ok("""{"domain":"$domain"}""")
                request.path.endsWith("/domains/custom") -> ok("""{"domains":[${domainRow()}]}""")
                else -> ok("""{"name":"my-bucket"}""")
            }
        }
        assertIs<ExecutionReceipt.Completed>(client().deleteCustomDomain(target, domain))
        val delete = requests.mutation()
        assertEquals("DELETE", delete.method)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/domains/custom/$domain", delete.path)
        assertEquals("default", delete.headers["cf-r2-jurisdiction"])
        assertEquals(HttpBody.Empty, delete.payload)
    }

    @Test
    fun aDeleteRefusesWhenTheCompleteCollectionDoesNotContainTheDomain() = runTest {
        respond = { request ->
            when {
                !request.method.equals("GET") -> ok("{}")
                request.path.endsWith("/domains/custom") -> ok("""{"domains":[${domainRow(domain = "other.example.com")}]}""")
                else -> ok("""{"name":"my-bucket"}""")
            }
        }
        assertFailsWith<CloudFailure> { client().deleteCustomDomain(target, domain) }
        assertTrue(requests.none { it.method == "DELETE" })
    }

    // ------------------------------------------------------------------ guard + single send

    @Test
    fun everyDomainWriteProvesTheBucketAndSendsOnceAfterTheFinalGuard() = runTest {
        respond = singleDomainResponder()
        val events = mutableListOf<String>()
        var writesBeforeGuard = -1
        val client = client(beforeMutation = {
            events += "guard"
            writesBeforeGuard = requests.count { it.method != "GET" }
        })
        val receipt = client.writeDomainConfiguration(
            R2DomainConfigurationWrite.EditCustomDomain(eu, domain, R2CustomDomainEditInput(enabled = true)),
        ) { document ->
            events += "beforeSend"
            assertNotNull(document)
            assertTrue(document.editable)
            assertEquals(R2DomainKind.CUSTOM_DOMAIN, document.kind)
        }
        assertIs<ExecutionReceipt.Completed>(receipt)
        assertEquals(listOf("beforeSend", "guard"), events)
        assertEquals(0, writesBeforeGuard)
        assertEquals(listOf("GET", "GET", "PUT"), requests.map { it.method })
        assertTrue(requests.all { it.headers["cf-r2-jurisdiction"] == "eu" }, "the proof, the snapshot and the write share one jurisdiction")
    }

    @Test
    fun aReadOnlySnapshotNeverBecomesAWrite() = runTest {
        respond = { request ->
            when {
                request.method != "GET" -> ok(domainRow())
                request.path.endsWith("/domains/custom/$domain") -> ok(domainRow(extra = ""","future_flag":true"""))
                else -> ok("""{"name":"my-bucket"}""")
            }
        }
        assertFailsWith<CloudFailure> {
            client().editCustomDomain(target, domain, R2CustomDomainEditInput(enabled = true))
        }
        assertTrue(requests.none { it.method != "GET" })
    }

    @Test
    fun aFailedDomainWriteIsReportedAndNeverReplayed() = runTest {
        respond = { request ->
            when {
                request.method == "PUT" -> HttpResponse(500, "")
                request.path.endsWith("/domains/custom/$domain") -> ok(domainRow())
                else -> ok("""{"name":"my-bucket"}""")
            }
        }
        assertIs<ExecutionReceipt.Unknown>(
            client().editCustomDomain(target, domain, R2CustomDomainEditInput(enabled = true)),
        )
        assertEquals(1, requests.count { it.method == "PUT" }, "an uncertain write is never replayed")
    }

    @Test
    fun theDomainListKeepsEveryRowAndRefusesAnUnknownMember() = runTest {
        respond = { ok("""{"domains":[${domainRow()}]}""") }
        val document = client().listCustomDomains(target)
        assertTrue(document.editable)
        assertEquals(1, document.customDomains.size)
        respond = { ok("""{"domains":[],"cursor":"x"}""") }
        assertFalse(client().listCustomDomains(target).editable, "an unknown list member is read-only, not an empty collection")
    }
}
