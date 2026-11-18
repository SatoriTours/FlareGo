package com.flarego.core

import com.flarego.core.application.registrar.RegistrarController
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.ResponseKind
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.registrar.CloudflareRegistrarClient
import com.flarego.core.cloudflare.registrar.RegistrarKeyword
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.RegistrarIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Read-only Registrar discovery: parsing, input bounds, ownership, policy and state guards. */
class RegistrarTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val other = AccountScope("local", "account-two")

    private fun ok(result: String) =
        HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")

    private fun domains(vararg rows: String) = """{"domains":[${rows.joinToString(",")}]}"""

    private fun row(
        name: String = "example.com",
        registrable: String = "true",
        pricing: String = """{"currency":"USD","registration_cost":"8.57","renewal_cost":"8.57"}""",
    ) = """{"name":"$name","registrable":$registrable,"tier":"standard","pricing":$pricing}"""

    private fun client(
        bound: Connection = connection,
        send: suspend (HttpRequest) -> HttpResponse,
    ) = CloudflareRegistrarClient(bound, CloudflareGateway(HttpTransport(send)))

    @Test fun searchMapsCandidatesAndKeepsLexicalPrices() = runTest {
        val paths = mutableListOf<String>()
        val results = client { request ->
            paths += request.path
            ok(domains(row()))
        }.search(account, "Example.COM", limit = 3)
        assertEquals(listOf("/accounts/account-one/registrar/domain-search?q=example.com&limit=3"), paths)
        val candidate = results.single()
        assertEquals("example.com", candidate.name)
        assertEquals(true, candidate.registrable)
        assertEquals("standard", candidate.tier)
        assertEquals("USD", candidate.currency)
        assertEquals("8.57", candidate.registrationCost)
        assertEquals("8.57", candidate.renewalCost)
        assertNull(candidate.reason)
    }

    @Test fun phraseKeywordsAreWhitespaceFreeAndLowercased() = runTest {
        val paths = mutableListOf<String>()
        client { request -> paths += request.path; ok(domains(row("coffeeshop.com"))) }
            .search(account, "  Coffee  Shop ")
        assertEquals(listOf("/accounts/account-one/registrar/domain-search?q=coffeeshop&limit=8"), paths)
        assertEquals("coffeeshop", RegistrarKeyword.normalize("  Coffee  Shop "))
    }

    @Test fun untrustedFieldsAreRefusedWithoutEchoingProviderContent() = runTest {
        val bodies = listOf(
            ok("""{"domains":[{"registrable":true}]}"""),
            ok(domains("""{"name":"example.com","registrable":true,"tier":{"secret":"synthetic-provider-private-marker"}}""")),
            ok(domains("""{"name":"example.com","registrable":"yes"}""")),
            ok(domains("""{"name":"exa mple.com","registrable":true}""")),
            ok(domains("""{"name":"example.com","registrable":true,"pricing":"synthetic-provider-private-marker"}""")),
            ok(domains("""{"name":"example.com","registrable":true,"pricing":{"currency":"USD","registration_cost":8.57}}""")),
            ok(domains("""{"name":"example.com","registrable":true,"pricing":{"registration_cost":"synthetic-provider-private-marker"}}""")),
            ok(domains("""{"name":"${"a".repeat(254)}.com","registrable":true}""")),
            ok("""{"domains":"synthetic-provider-private-marker"}"""),
            ok("[]"),
            HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":null}"""),
        )
        for (body in bodies) {
            val failure = assertFailsWith<CloudFailure> {
                client { body }.search(account, "example.com")
            }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
            assertFalse(failure.publicMessage.contains("synthetic-provider-private-marker"))
            assertNull(failure.requestId)
        }
    }

    @Test fun missingOptionalFieldsStayAbsentInsteadOfBeingInvented() = runTest {
        val results = client { ok(domains("""{"name":"example.dev"}""")) }.search(account, "example")
        val candidate = results.single()
        assertEquals("example.dev", candidate.name)
        assertNull(candidate.registrable)
        assertNull(candidate.currency)
        assertNull(candidate.registrationCost)
    }

    @Test fun keywordAndLimitAreValidatedBeforeAnyRequest() = runTest {
        var requests = 0
        val api = client { requests++; ok(domains(row())) }
        for (keyword in listOf("", "   ", "example_com", "example.com/path", "ünicode.com", "a".repeat(101))) {
            assertFailsWith<CloudFailure> { api.search(account, keyword) }
        }
        for (limit in listOf(0, -1, 11)) {
            assertFailsWith<CloudFailure> { api.search(account, "example.com", limit) }
        }
        assertEquals(0, requests)
        assertEquals("a".repeat(100), RegistrarKeyword.normalize("a".repeat(100)))
    }

    @Test fun anotherConnectionsAccountIsRefusedBeforeAnyRequest() = runTest {
        var requests = 0
        val failure = assertFailsWith<CloudFailure> {
            client { requests++; ok(domains(row())) }.search(other, "example.com")
        }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code)
        assertEquals(0, requests)
    }

    @Test fun providerFailureStaysAStableAppOwnedError() = runTest {
        val failure = assertFailsWith<CloudFailure> {
            client { HttpResponse(403, "synthetic-provider-private-marker") }.search(account, "example.com")
        }
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED, failure.code)
        assertFalse(failure.publicMessage.contains("synthetic-provider-private-marker"))
    }

    @Test fun onlyTheReadSearchIsRegisteredAndRegistrationCanNeverBeSent() = runTest {
        val policy = assertNotNull(ActionPolicies.policy(CloudflareRegistrarClient.ACTION_KEY))
        assertEquals(ActionSemantics.READ, policy.semantics)
        assertEquals(ResponseKind.ENVELOPE_JSON, policy.responseKind)
        assertEquals(RiskLevel.READ_ONLY, policy.risk)
        assertTrue(policy.retry.enabled)
        val registration = assertNotNull(ActionPolicies.registry.registration(CloudflareRegistrarClient.ACTION_KEY))
        assertEquals(ActionScope.ACCOUNT, registration.scope)
        assertEquals("GET", registration.method)
        assertEquals("/accounts/{account_id}/registrar/domain-search", registration.pathTemplate)

        var requests = 0
        val gateway = CloudflareGateway(HttpTransport { requests++; ok("[]") })
        for (request in listOf(
            HttpRequest("POST", "/accounts/account-one/registrar/registrations"),
            HttpRequest("GET", "/accounts/account-one/registrar/registrations"),
            HttpRequest("POST", "/accounts/account-one/registrar/domain-check"),
        )) {
            assertFailsWith<CloudFailure> { gateway.send(CloudflareRegistrarClient.ACTION_KEY, request) }
        }
        assertEquals(0, requests)
    }

    @Test fun disabledAccountReportsUnsupportedWithoutRequesting() = runTest {
        val controller = RegistrarController(backgroundScope, { _, _, _ -> error("must not request") }, enabled = { false })
        controller.bind(account)
        assertTrue(controller.state.value.unsupported)
        controller.onIntent(RegistrarIntent.Query("example.com"))
        runCurrent()
        val state = controller.state.value
        assertTrue(state.unsupported)
        assertFalse(state.searching)
        assertTrue(state.results.isEmpty())
        assertNull(state.observedAtMs)
        assertNull(state.error)
    }

    @Test fun queryPublishesCachedResultsWithNoticeAndObservationTime() = runTest {
        val paths = mutableListOf<String>()
        val calls = mutableListOf<Triple<AccountScope, String, Int>>()
        val controller = RegistrarController(
            backgroundScope,
            { account, keyword, limit ->
                calls += Triple(account, keyword, limit)
                client { request -> paths += request.path; ok(domains(row())) }.search(account, keyword, limit)
            },
            enabled = { true },
            nowMs = { 42L },
        )
        controller.bind(account)
        controller.onIntent(RegistrarIntent.Query("  Example.COM  "))
        runCurrent()
        assertEquals(listOf(Triple(account, "example.com", RegistrarController.DEFAULT_LIMIT)), calls)
        val state = controller.state.value
        assertEquals(listOf("/accounts/account-one/registrar/domain-search?q=example.com&limit=8"), paths)
        assertEquals(account, state.scope)
        assertEquals("example.com", state.keyword)
        assertEquals(RegistrarController.NOTICE, state.notice)
        assertEquals(42L, state.observedAtMs)
        assertEquals(listOf("example.com"), state.results.map { it.name })
        assertFalse(state.searching)
        assertNull(state.error)
    }

    @Test fun failurePublishesTheSafeMessageAndRetryRepeatsTheLastKeyword() = runTest {
        var attempt = 0
        val controller = RegistrarController(
            backgroundScope,
            { account, keyword, limit ->
                client {
                    if (attempt++ == 0) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法连接，请检查网络后重试")
                    ok(domains(row()))
                }.search(account, keyword, limit)
            },
            enabled = { true },
            nowMs = { 9L },
        )
        controller.bind(account)
        controller.onIntent(RegistrarIntent.Query("example.com"))
        runCurrent()
        assertEquals("暂时无法连接，请检查网络后重试", controller.state.value.error)
        assertTrue(controller.state.value.results.isEmpty())
        assertNull(controller.state.value.observedAtMs)

        controller.onIntent(RegistrarIntent.Retry)
        runCurrent()
        assertEquals(2, attempt)
        assertNull(controller.state.value.error)
        assertEquals(1, controller.state.value.results.size)
        assertEquals(RegistrarController.NOTICE, controller.state.value.notice)

        controller.onIntent(RegistrarIntent.Clear)
        runCurrent()
        assertEquals("", controller.state.value.keyword)
        assertTrue(controller.state.value.results.isEmpty())
        assertEquals(account, controller.state.value.scope)
    }

    @Test fun accountSwitchDropsTheInFlightResult() = runTest {
        val gate = CompletableDeferred<HttpResponse>()
        val paths = mutableListOf<String>()
        val controller = RegistrarController(
            backgroundScope,
            { account, keyword, limit -> client { request -> paths += request.path; gate.await() }.search(account, keyword, limit) },
            enabled = { true },
            nowMs = { 7L },
        )
        controller.bind(account)
        controller.onIntent(RegistrarIntent.Query("example.com"))
        runCurrent()
        assertEquals(1, paths.size)
        assertTrue(controller.state.value.searching)

        controller.bind(other)
        gate.complete(ok(domains(row())))
        advanceUntilIdle()
        val state = controller.state.value
        assertEquals(other, state.scope)
        assertTrue(state.results.isEmpty())
        assertNull(state.observedAtMs)
        assertFalse(state.searching)
    }

    @Test fun invalidKeywordIsRejectedWithoutARequest() = runTest {
        val controller = RegistrarController(backgroundScope, { _, _, _ -> error("must not request") }, enabled = { true })
        controller.bind(account)
        controller.onIntent(RegistrarIntent.Query("exa mple_c"))
        runCurrent()
        assertEquals(RegistrarController.INVALID_KEYWORD, controller.state.value.error)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searching)
    }

    @Test fun queryWithoutABoundAccountIsRefused() = runTest {
        val controller = RegistrarController(backgroundScope, { _, _, _ -> error("must not request") }, enabled = { true })
        controller.onIntent(RegistrarIntent.Query("example.com"))
        runCurrent()
        assertEquals("请先选择账号后再搜索域名", controller.state.value.error)
    }
}
