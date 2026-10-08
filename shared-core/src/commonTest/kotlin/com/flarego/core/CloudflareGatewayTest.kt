package com.flarego.core

import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.CloudflareSession
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionPolicy
import com.flarego.core.cloudflare.protocol.ActionRegistration
import com.flarego.core.cloudflare.protocol.ActionRegistry
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.PageMetadata
import com.flarego.core.cloudflare.protocol.Pagination
import com.flarego.core.cloudflare.protocol.PaginationStrategy
import com.flarego.core.cloudflare.protocol.ReadRetryPolicy
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.ResponseKind
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.model.CloudException
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.PageToken
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

/** A pure in-memory transport that records requests and replays scripted responses. */
private class ScriptedTransport(private val responses: List<HttpResponse>) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses[minOf(requests.size - 1, responses.size - 1)]
    }
}

class CloudflareGatewayTest {

    private val account = "/accounts/account-1"

    private fun ok(result: String = "{}", info: String = "{}", extra: String = ""): HttpResponse =
        HttpResponse(200, """{"success":true,"result":$result,"result_info":$info$extra}""")

    private fun registration(
        key: String,
        method: String = "GET",
        template: String = "/accounts/{account_id}/d1/database",
        scope: ActionScope = ActionScope.ACCOUNT,
        semantics: ActionSemantics = ActionSemantics.READ,
        retry: ReadRetryPolicy? = null,
        responseKind: ResponseKind = ResponseKind.ENVELOPE_JSON,
    ) = ActionRegistration(
        ActionPolicy(
            key,
            semantics,
            responseKind,
            if (semantics == ActionSemantics.READ) RiskLevel.READ_ONLY else RiskLevel.CHANGE,
            retry ?: if (semantics == ActionSemantics.READ) ReadRetryPolicy(enabled = true) else ReadRetryPolicy(),
        ),
        method,
        template,
        scope,
    )

    private fun gateway(
        transport: HttpTransport,
        registry: ActionRegistry,
        sleeps: MutableList<Long> = mutableListOf(),
        now: () -> Long = { 0L },
        parseDate: (String) -> Long? = { null },
    ) = CloudflareGateway(transport, registry, { ms -> sleeps += ms }, now, parseDate)

    // ---------------------------------------------------------------- registration

    @Test
    fun productionRegistryMatchesEveryRegisteredActionExactlyOnce() {
        val cases =
            listOf(
                "accounts-account-details" to Triple("GET", account, ActionScope.ACCOUNT),
                "d1-list-databases" to Triple("GET", "$account/d1/database", ActionScope.ACCOUNT),
                "r2-list-buckets" to Triple("GET", "$account/r2/buckets", ActionScope.ACCOUNT),
                "workers-kv-namespace-list-namespaces" to Triple("GET", "$account/storage/kv/namespaces", ActionScope.ACCOUNT),
                "worker-script-list-workers" to Triple("GET", "$account/workers/scripts", ActionScope.ACCOUNT),
                "user-billing-history-(-deprecated)-billing-history-details" to Triple("GET", "/user/billing/history", ActionScope.USER),
                "zones-get" to Triple("GET", "/zones", ActionScope.USER),
                "dns-records-for-a-zone-list-dns-records" to Triple("GET", "/zones/zone-1/dns_records", ActionScope.ZONE),
                "dns-records-for-a-zone-create-dns-record" to Triple("POST", "/zones/zone-1/dns_records", ActionScope.ZONE),
                "dns-records-for-a-zone-delete-dns-record" to Triple("DELETE", "/zones/zone-1/dns_records/record-1", ActionScope.ZONE),
                "dns-records-for-a-zone-patch-dns-record" to Triple("PATCH", "/zones/zone-1/dns_records/record-1", ActionScope.ZONE),
                "accounts-list-accounts" to Triple("GET", "/accounts", ActionScope.USER),
                "user-api-tokens-verify-token" to Triple("GET", "/user/tokens/verify", ActionScope.USER),
                "account-api-tokens-verify-token" to Triple("GET", "$account/tokens/verify", ActionScope.ACCOUNT),
                "listWorkers" to Triple("GET", "$account/workers/workers", ActionScope.ACCOUNT),
                "account-billing-history-get-billing-history" to Triple("GET", "$account/billing/history", ActionScope.ACCOUNT),
            )
        assertEquals(16, cases.size)
        for ((key, expected) in cases) {
            val policy = ActionPolicies.policy(key)
            assertNotNull(policy, key)
            assertEquals(key, policy.key, key)
            val registration = ActionPolicies.registry.registration(key)
            assertNotNull(registration, key)
            assertEquals(expected.first, registration.method, key)
            assertEquals(expected.third, registration.scope, key)
            assertTrue(registration.matches(expected.first, expected.second), "$key must match its own method and path")
        }
        // exactly one handwritten registration matches each registered method+path pair
        for ((key, expected) in cases) {
            val matches =
                cases.map { it.first }.filter { candidate ->
                    ActionPolicies.registry.registration(candidate)?.matches(expected.first, expected.second) == true
                }
            assertEquals(listOf(key), matches, "$key must be the only match for ${expected.second}")
        }
    }

    @Test
    fun writeAndReadSemanticsComeFromTheRegistryNotTheHttpMethod() = runTest {
        val readOnlyPost = registry(registration("trusted-read-post", method = "POST", template = "/accounts/{account_id}/query", retry = ReadRetryPolicy()))
        val policy = readOnlyPost.policy("trusted-read-post")
        assertNotNull(policy)
        assertEquals(ActionSemantics.READ, policy.semantics, "the method must not decide the semantics")
        val transport = ScriptedTransport(listOf(HttpResponse(503, "SECRET")))
        val gateway = gateway(transport, readOnlyPost)
        val response = gateway.send("trusted-read-post", HttpRequest("POST", "$account/query", "{}"))
        assertEquals(503, response.status, "the gateway preserves the provider status")
        val readFailure = assertFailsWith<CloudFailure> { ResponseDecoders.envelope(response, writing = policy.semantics == ActionSemantics.WRITE) }
        assertFalse(readFailure.uncertain, "a registered read-only POST must not be marked as an unknown write")
        assertFalse(readFailure.publicMessage.contains("SECRET"))
        assertNull(readFailure.cause)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun policyLookupIsNullForUnknownKeys() {
        assertNull(ActionPolicies.policy("not-registered"))
    }

    // ---------------------------------------------------------------- matching

    @Test
    fun unregisteredKeyIsRejectedBeforeAnyCall() = runTest {
        val transport = ScriptedTransport(listOf(ok()))
        val gateway = gateway(transport, ActionPolicies.registry)
        val failure = assertFailsWith<CloudFailure> { gateway.send("unknown-key", HttpRequest("GET", account)) }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun registeredKeyWithMismatchedRequestIsRejectedBeforeAnyCall() = runTest {
        val transport = ScriptedTransport(listOf(ok()))
        val gateway = gateway(transport, ActionPolicies.registry)
        val mismatches =
            listOf(
                "dns-records-for-a-zone-list-dns-records" to HttpRequest("DELETE", "/zones/zone-1/dns_records"),
                "dns-records-for-a-zone-list-dns-records" to HttpRequest("GET", account),
                "d1-list-databases" to HttpRequest("GET", "$account/d1/database/extra"),
                "d1-list-databases" to HttpRequest("GET", "/accounts//d1/database"),
                "d1-list-databases" to HttpRequest("GET", "$account/../d1/database"),
                "d1-list-databases" to HttpRequest("GET", "$account/%2e%2e/d1/database"),
                "d1-list-databases" to HttpRequest("GET", "https://evil.example$account/d1/database"),
                "d1-list-databases" to HttpRequest("GET", "$account/d1/database#frag"),
                "d1-list-databases" to HttpRequest("GET", "$account/d1/databases"),
                "accounts-list-accounts" to HttpRequest("GET", "/accounts/account-1"),
            )
        for ((key, request) in mismatches) {
            val failure = assertFailsWith<CloudFailure>("$key ${request.method} ${request.path}") { gateway.send(key, request) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, "$key ${request.path}")
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun registeredPathsWithQueriesStillMatch() = runTest {
        val transport = ScriptedTransport(listOf(ok("[]")))
        val gateway = gateway(transport, ActionPolicies.registry)
        gateway.send("zones-get", HttpRequest("GET", "/zones?account.id=account-1&per_page=50&page=1"))
        assertEquals(1, transport.requests.size)
        assertEquals("/zones?account.id=account-1&per_page=50&page=1", transport.requests.single().path)
    }

    // ---------------------------------------------------------------- retry

    @Test
    fun readRetryHonoursRetryAfterSecondsWithinBudget() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "5"), ok()))
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
        val response = gateway.send("retry-read", HttpRequest("GET", "$account/d1/database"))
        assertEquals(200, response.status)
        assertEquals(listOf(5000L), sleeps)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun retryAfterHeaderIsCaseInsensitive() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("retry-after", "0"), ok()))
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
        assertEquals(200, gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(listOf(0L), sleeps)
    }

    @Test
    fun retryAfterHttpDateWithinBudgetIsHonoured() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "Sun, 06 Nov 1994 08:49:47 GMT"), ok()))
        val gateway =
            gateway(
                transport,
                registry(registration("retry-read")),
                sleeps,
                now = { 784_111_777_000L },
                parseDate = { 784_111_787_000L },
            )
        assertEquals(200, gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(listOf(10_000L), sleeps)
    }

    @Test
    fun retryAfterPastDateWaitsNothing() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "Sun, 06 Nov 1994 08:49:37 GMT"), ok()))
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps, now = { 784_111_777_000L }, parseDate = { 784_111_700_000L })
        assertEquals(200, gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(listOf(0L), sleeps)
    }

    @Test
    fun missingRetryAfterUsesBoundedBackoff() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}"), HttpResponse(429, "{}"), ok()))
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
        assertEquals(200, gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(listOf(1000L, 2000L), sleeps)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun sixtySecondBoundaryIsAllowedAndAboveItIsRateLimited() = runTest {
        val sleeps = mutableListOf<Long>()
        val allowed = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "60"), ok()))
        val allowedGateway = gateway(allowed, registry(registration("retry-read")), sleeps)
        assertEquals(200, allowedGateway.send("retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(listOf(60_000L), sleeps)

        val over = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "61"), ok()))
        val overSleeps = mutableListOf<Long>()
        val overGateway = gateway(over, registry(registration("retry-read")), overSleeps)
        val failure = assertFailsWith<CloudFailure> { overGateway.send("retry-read", HttpRequest("GET", "$account/d1/database")) }
        assertEquals(CloudFailureCode.RATE_LIMITED, failure.code)
        assertEquals(0, overSleeps.size, "an over-budget wait must not be truncated and sent early")
        assertEquals(1, over.requests.size)
        assertFalse(failure.publicMessage.contains("61"))
    }

    @Test
    fun invalidOrContradictoryRetryAfterValuesFailClosed() = runTest {
        val values = listOf("abc", "-5", "99999999999999999999", "", " 5 s")
        for (value in values) {
            val sleeps = mutableListOf<Long>()
            val transport = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", value), ok()))
            val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
            val failure = assertFailsWith<CloudFailure>(value) { gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")) }
            assertEquals(CloudFailureCode.RATE_LIMITED, failure.code, value)
            assertEquals(0, sleeps.size, value)
            assertEquals(1, transport.requests.size, value)
        }
        val contradictory =
            HttpResponse(429, "{}")
                .withHeader("Retry-After", "1")
                .withHeader("retry-after", "2")
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(contradictory, ok()))
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
        val failure = assertFailsWith<CloudFailure> { gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")) }
        assertEquals(CloudFailureCode.RATE_LIMITED, failure.code)
        assertEquals(1, transport.requests.size)
        assertEquals(0, sleeps.size)
    }

    @Test
    fun retryBudgetIsCumulativePerSend() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport =
            ScriptedTransport(
                listOf(
                    HttpResponse(429, "{}").withHeader("Retry-After", "40"),
                    HttpResponse(429, "{}").withHeader("Retry-After", "40"),
                    ok(),
                )
            )
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
        val failure = assertFailsWith<CloudFailure> { gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")) }
        assertEquals(CloudFailureCode.RATE_LIMITED, failure.code)
        assertEquals(listOf(40_000L), sleeps, "the second 40s wait exceeds the remaining budget")
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun retryExhaustionReturnsRateLimited() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}"), HttpResponse(429, "{}"), HttpResponse(429, "{}"), ok()))
        val gateway = gateway(transport, registry(registration("retry-read")), sleeps)
        val failure = assertFailsWith<CloudFailure> { gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")) }
        assertEquals(CloudFailureCode.RATE_LIMITED, failure.code)
        assertEquals(3, transport.requests.size, "one request plus at most two retries")
        assertEquals(listOf(1000L, 2000L), sleeps)
    }

    @Test
    fun writesAreNeverRetriedAndRetryDisabledReadsAreNotEither() = runTest {
        val writeTransport = ScriptedTransport(listOf(HttpResponse(429, "{}"), ok()))
        val writeGateway =
            gateway(
                writeTransport,
                registry(registration("write-action", method = "POST", semantics = ActionSemantics.WRITE)),
            )
        assertEquals(429, writeGateway.send("write-action", HttpRequest("POST", "$account/d1/database", "{}")).status)
        assertEquals(1, writeTransport.requests.size)

        val disabledTransport = ScriptedTransport(listOf(HttpResponse(429, "{}"), ok()))
        val disabledGateway = gateway(disabledTransport, registry(registration("no-retry-read", retry = ReadRetryPolicy())))
        assertEquals(429, disabledGateway.send("no-retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(1, disabledTransport.requests.size)
    }

    @Test
    fun serverErrorsAndStreamingBodiesAreNeverRetried() = runTest {
        val serverTransport = ScriptedTransport(listOf(HttpResponse(503, "{}"), ok()))
        val serverGateway = gateway(serverTransport, registry(registration("retry-read")))
        assertEquals(503, serverGateway.send("retry-read", HttpRequest("GET", "$account/d1/database")).status)
        assertEquals(1, serverTransport.requests.size)

        val stream = HttpBody.Stream(StreamSource(), null, "application/octet-stream")
        val streamTransport = ScriptedTransport(listOf(HttpResponse(429, "{}"), ok()))
        val streamGateway =
            gateway(streamTransport, registry(registration("stream-action", method = "POST", semantics = ActionSemantics.WRITE)))
        assertEquals(429, streamGateway.send("stream-action", HttpRequest("POST", "$account/d1/database", stream)).status)
        assertEquals(1, streamTransport.requests.size, "a one-shot stream must never be replayed")
    }

    @Test
    fun cancellationDuringTheWaitStopsWithoutAnotherCall() = runTest {
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}"), ok()))
        val gate = CompletableDeferred<Unit>()
        val gateway =
            CloudflareGateway(
                transport,
                registry(registration("retry-read")),
                { gate.await() },
                { 0L },
                { null },
            )
        val job = launch { gateway.send("retry-read", HttpRequest("GET", "$account/d1/database")) }
        gate.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, transport.requests.size, "a cancelled wait must not send another request")
    }

    // ---------------------------------------------------------------- decoders

    @Test
    fun envelopeKeepsPartialProviderResultWithoutEchoingProviderText() {
        val response =
            HttpResponse(
                200,
                """{"success":false,"errors":[{"message":"Bearer SECRET"}],"result":{"partial":true,"done":1}}""",
            )
        val root = ResponseDecoders.envelope(response, writing = true)
        assertEquals(false, root["success"]?.jsonPrimitive?.boolean)
        assertEquals(true, root["result"]?.jsonObject?.get("partial")?.jsonPrimitive?.boolean)
        assertEquals(1, root["result"]?.jsonObject?.get("done")?.jsonPrimitive?.int)
    }

    @Test
    fun malformedEnvelopeIsStableWithoutProviderText() {
        for (body in listOf("unexpected html", "[]", "null", "\"text\"", """{"success":[]}""", """{"success":"true"}""", "{}")) {
            val failure =
                assertFailsWith<CloudFailure>(body) {
                    ResponseDecoders.envelope(HttpResponse(200, body), writing = false)
                }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code, body)
            assertFalse(failure.publicMessage.contains("unexpected"), body)
            assertFalse(failure.publicMessage.contains("SECRET"), body)
            assertNull(failure.cause, body)
        }
    }

    @Test
    fun nonSuccessStatusesMapToStableErrorsWithWritingAwareUncertainty() {
        val expected =
            mapOf(
                400 to CloudFailureCode.INVALID_INPUT,
                401 to CloudFailureCode.CREDENTIAL_EXPIRED,
                403 to CloudFailureCode.PERMISSION_REQUIRED,
                404 to CloudFailureCode.NOT_FOUND,
                409 to CloudFailureCode.CONFLICT,
                429 to CloudFailureCode.RATE_LIMITED,
                500 to CloudFailureCode.UNAVAILABLE,
                503 to CloudFailureCode.UNAVAILABLE,
                418 to CloudFailureCode.INVALID_RESPONSE,
            )
        for ((status, code) in expected) {
            val read = assertFailsWith<CloudFailure>(status.toString()) { ResponseDecoders.envelope(HttpResponse(status, """{"errors":[{"message":"SECRET"}]}"""), writing = false) }
            assertEquals(code, read.code, status.toString())
            assertFalse(read.uncertain, status.toString())
            assertFalse(read.publicMessage.contains("SECRET"), status.toString())
            assertNull(read.cause, status.toString())

            val write = assertFailsWith<CloudFailure>(status.toString()) { ResponseDecoders.envelope(HttpResponse(status, "{}"), writing = true) }
            assertEquals(code, write.code, status.toString())
            // A write is uncertain whenever the outcome is rate limited, unavailable or
            // unrecognized; a definite client-side rejection stays certain.
            val expectedUncertain = code == CloudFailureCode.RATE_LIMITED || code == CloudFailureCode.UNAVAILABLE || code == CloudFailureCode.INVALID_RESPONSE
            assertEquals(expectedUncertain, write.uncertain, "writing uncertainty for $status")
        }
    }

    @Test
    fun explicitEmptyStatusesBypassTheEnvelope() {
        assertTrue(ResponseDecoders.isExplicitlyEmpty(204))
        assertTrue(ResponseDecoders.isExplicitlyEmpty(304))
        assertFalse(ResponseDecoders.isExplicitlyEmpty(200))
        for (status in listOf(204, 304)) {
            val failure = assertFailsWith<CloudFailure> { ResponseDecoders.envelope(HttpResponse(status, ""), writing = false) }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code, status.toString())
        }
    }

    @Test
    fun rawJsonAndTextDecodersParseTheirOwnShapes() {
        assertEquals(3, ResponseDecoders.json(HttpResponse(200, "3"), writing = false).jsonPrimitive.int)
        assertEquals("plain", ResponseDecoders.text(HttpResponse(200, "plain"), writing = false))
        assertEquals("""{"a":1}""", ResponseDecoders.text(HttpResponse(200, """{"a":1}"""), writing = false))
        val failure = assertFailsWith<CloudFailure> { ResponseDecoders.json(HttpResponse(200, "not json"), writing = false) }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
        val textFailure = assertFailsWith<CloudFailure> { ResponseDecoders.text(HttpResponse(503, "SECRET"), writing = true) }
        assertEquals(CloudFailureCode.UNAVAILABLE, textFailure.code)
        assertFalse(textFailure.publicMessage.contains("SECRET"))
    }

    // ---------------------------------------------------------------- pagination

    @Test
    fun numberStrategyBuildsQueryAndDecodesMetadata() {
        assertEquals("page=2&per_page=50", Pagination.query(PaginationStrategy.Number(2, 50)))
        val root = Json.parseToJsonElement("""{"result_info":{"total_pages":3,"total_count":101,"per_page":50,"page":2}}""").jsonObject
        val metadata = Pagination.decode(root, PaginationStrategy.Number(2, 50))
        assertEquals(PageToken.Number(3), metadata.next)
        assertEquals(101L, metadata.total)
    }

    @Test
    fun numberStrategyWithoutMetadataStopsAndKeepsUnknownTotal() {
        val root = Json.parseToJsonElement("""{"result_info":{}}""").jsonObject
        val metadata = Pagination.decode(root, PaginationStrategy.Number(1, 50))
        assertNull(metadata.next, "an unknown page count must not be inferred from the item count")
        assertNull(metadata.total)
    }

    @Test
    fun numberStrategyPreservesALegalZeroTotalAndStops() {
        val root = Json.parseToJsonElement("""{"result_info":{"total_count":0}}""").jsonObject
        val metadata = Pagination.decode(root, PaginationStrategy.Number(1, 50))
        assertEquals(0L, metadata.total)
        assertNull(metadata.next)
    }

    @Test
    fun numberStrategyRejectsMalformedMetadata() {
        val malformed =
            listOf(
                """{"result_info":{"total_count":-1}}""",
                """{"result_info":{"total_count":1.5}}""",
                """{"result_info":{"total_count":"many"}}""",
                """{"result_info":{"total_count":99999999999999999999}}""",
                """{"result_info":{"total_pages":"2"}}""",
                """{"result_info":{"per_page":0}}""",
            )
        for (body in malformed) {
            val root = Json.parseToJsonElement(body).jsonObject
            val failure = assertFailsWith<CloudFailure>(body) { Pagination.decode(root, PaginationStrategy.Number(1, 50)) }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code, body)
        }
    }

    @Test
    fun cursorStrategyBuildsEncodedCursorQueryAndTerminatesOnEmptyCursor() {
        assertEquals("per_page=50", Pagination.query(PaginationStrategy.Cursor(null)))
        assertEquals("per_page=50&cursor=${encodePath("next+/=")}", Pagination.query(PaginationStrategy.Cursor("next+/=")))
        assertEquals("", Pagination.query(PaginationStrategy.None))

        val withCursor = Json.parseToJsonElement("""{"result_info":{"cursor":"next+/="}}""").jsonObject
        assertEquals(PageToken.Cursor("next+/="), Pagination.decode(withCursor, PaginationStrategy.Cursor(null)).next)
        val empty = Json.parseToJsonElement("""{"result_info":{"cursor":"  "}}""").jsonObject
        assertNull(Pagination.decode(empty, PaginationStrategy.Cursor(null)).next, "an empty cursor terminates")
        val absent = Json.parseToJsonElement("""{"result_info":{}}""").jsonObject
        assertNull(Pagination.decode(absent, PaginationStrategy.Cursor(null)).next)
    }

    @Test
    fun cursorStrategyFailsOnARepeatedCursor() {
        val root = Json.parseToJsonElement("""{"result_info":{"cursor":"again"}}""").jsonObject
        val failure =
            assertFailsWith<CloudFailure> {
                Pagination.decode(root, PaginationStrategy.Cursor("again", setOf("again")))
            }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
        val advanced = Pagination.next(PaginationStrategy.Cursor(null), PageMetadata(PageToken.Cursor("again"), null))
        assertEquals(PaginationStrategy.Cursor("again", setOf("again")), advanced)
        assertNull(Pagination.next(PaginationStrategy.Cursor(null), PageMetadata(null, null)))
    }

    @Test
    fun numberStrategyAdvancesOnlyWhenTheProviderReportedMorePages() {
        assertEquals(
            PaginationStrategy.Number(2, 50),
            Pagination.next(PaginationStrategy.Number(1, 50), PageMetadata(PageToken.Number(2), null)),
        )
        assertNull(Pagination.next(PaginationStrategy.Number(1, 50), PageMetadata(null, 10L)))
        assertNull(Pagination.next(PaginationStrategy.None, PageMetadata(null, null)))
    }

    // ---------------------------------------------------------------- gateway status

    @Test
    fun acceptedAndNotModifiedStatusesArePreservedBySend() = runTest {
        val acceptedTransport = ScriptedTransport(listOf(HttpResponse(202, """{"success":true,"result":{}}""")))
        val acceptedGateway = gateway(acceptedTransport, ActionPolicies.registry)
        val accepted = acceptedGateway.send("dns-records-for-a-zone-create-dns-record", HttpRequest("POST", "/zones/zone-1/dns_records", "{}"))
        assertEquals(202, accepted.status)
        assertEquals("""{"success":true,"result":{}}""", accepted.body)

        val notModifiedTransport = ScriptedTransport(listOf(HttpResponse(304, "")))
        val notModifiedGateway = gateway(notModifiedTransport, ActionPolicies.registry)
        val notModified = notModifiedGateway.send("accounts-account-details", HttpRequest("GET", account))
        assertEquals(304, notModified.status)
        assertTrue(ResponseDecoders.isExplicitlyEmpty(notModified.status))
    }

    // ---------------------------------------------------------------- legacy session bridge

    @Test
    fun legacySessionBridgesAcceptedAndPartialWritesToUncertainErrors() = runTest {
        val accepted = CloudflareSession(Connection("live", "Live", "account-1"), ScriptedTransport(listOf(HttpResponse(202, """{"success":true,"result":{}}"""))))
        assertTrue(assertFailsWith<CloudException> { accepted.delete("zone", "record") }.uncertain)

        val partial =
            CloudflareSession(
                Connection("live", "Live", "account-1"),
                ScriptedTransport(listOf(HttpResponse(200, """{"success":false,"result":{"partial":true},"errors":[{"message":"SECRET"}]}"""))),
            )
        val failure = assertFailsWith<CloudException> { partial.save("zone", null, com.flarego.core.model.DnsDraft("A", "example.com", "192.0.2.1")) }
        assertTrue(failure.uncertain)
        assertFalse(failure.message!!.contains("SECRET"))
    }

    @Test
    fun legacySessionRejectsExplicitEmptyReadStatus() = runTest {
        val api = CloudflareSession(Connection("live", "Live", "account-1"), ScriptedTransport(listOf(HttpResponse(204, ""))))
        val failure = assertFailsWith<CloudException> { api.verifyAccount() }
        assertFalse(failure.uncertain)
        assertFalse(failure.message!!.contains("204").not())
    }

    @Test
    fun legacySessionKeepsUncertaintyForUnknownWritesAndSafeReads() = runTest {
        val serverFailure = CloudflareSession(Connection("live", "Live", "account-1"), ScriptedTransport(listOf(HttpResponse(503, "SECRET"))))
        assertTrue(assertFailsWith<CloudException> { serverFailure.delete("zone", "record") }.uncertain)
        assertFalse(assertFailsWith<CloudException> { serverFailure.records("zone") }.uncertain)

        val networkFailure =
            CloudflareSession(
                Connection("live", "Live", "account-1"),
                HttpTransport { throw IllegalStateException("network down SECRET") },
            )
        assertTrue(assertFailsWith<CloudException> { networkFailure.delete("zone", "record") }.uncertain)
        assertFalse(assertFailsWith<CloudException> { networkFailure.records("zone") }.uncertain)
    }

    @Test
    fun legacySessionUsesTheProductionRegistryForItsActions() = runTest {
        val transport = ScriptedTransport(listOf(ok()))
        val api = CloudflareSession(Connection("live", "Live", "account-1"), transport)
        api.verifyAccount()
        assertEquals(1, transport.requests.size)
        assertEquals("GET", transport.requests.single().method)
        assertEquals("/accounts/account-1", transport.requests.single().path)
    }


    // ---------------------------------------------------------------- R1 request shape

    @Test
    fun unsafeRequestShapesAreRejectedBeforeAnyCall() = runTest {
        val unsafe =
            listOf(
                "/accounts/a?x=1#fragment",
                "/accounts/a%23fragment",
                "/accounts/a%2Fb",
                "/accounts/a%C0%AF",
                "/accounts/a?cursor=%GG",
                "/accounts/a%5c..%5c..",
                "/accounts/%252e%252e/x",
                "/accounts/a%00",
                "/accounts/a%7f",
                "/accounts/a%c2%80",
            )
        val transport = ScriptedTransport(listOf(ok()))
        val gateway = gateway(transport, ActionPolicies.registry)
        for (path in unsafe) {
            val failure = assertFailsWith<CloudFailure>(path) { gateway.send("accounts-account-details", HttpRequest("GET", path)) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
            assertNull(failure.cause, path)
        }
        assertEquals(0, transport.requests.size, "no unsafe request shape may reach the transport")
    }

    @Test
    fun controlCodePointsAreRejectedAtEveryDepthAndPositionBeforeAnyCall() = runTest {
        val transport = ScriptedTransport(listOf(ok()))
        val gateway = gateway(transport, ActionPolicies.registry)
        var attempts = 0
        var rejected = 0
        for (code in (0..31).toList() + (127..159).toList()) {
            var value = code.toChar().toString()
            repeat(5) {
                value = encodePath(value)
                for (path in listOf("/accounts/a$value", "/accounts/a?cursor=$value")) {
                    attempts++
                    try {
                        gateway.send("accounts-account-details", HttpRequest("GET", path))
                    } catch (failure: CloudFailure) {
                        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
                        assertNull(failure.cause, path)
                        rejected++
                    }
                }
            }
        }
        assertEquals(650, attempts)
        assertEquals(650, rejected)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun encodedUnicodeAndOpaqueIdentitiesPassUnchangedThroughTheGateway() = runTest {
        for (value in listOf("é", "汉字", "🙂", "e\u0301", "id%GG", "x y", "a+b")) {
            val path = "/accounts/${encodePath(value)}"
            val transport = ScriptedTransport(listOf(ok()))
            assertEquals(
                200,
                gateway(transport, ActionPolicies.registry).send("accounts-account-details", HttpRequest("GET", path)).status,
                value,
            )
            assertEquals(path, transport.requests.single().path, value)
        }
        val cursor = "/accounts/a?cursor=${encodePath("next+/=汉字%GG")}"
        val transport = ScriptedTransport(listOf(ok()))
        assertEquals(200, gateway(transport, ActionPolicies.registry).send("accounts-account-details", HttpRequest("GET", cursor)).status)
        assertEquals(cursor, transport.requests.single().path)
    }

    // ---------------------------------------------------------------- R2 pagination shape

    @Test
    fun presentButUnusableResultInfoIsAStableError() {
        for (body in listOf("""{"result_info":[]}""", """{"result_info":3}""", """{"result_info":null}""", """{"result_info":"x"}""")) {
            val failure =
                assertFailsWith<CloudFailure>(body) {
                    Pagination.decode(Json.parseToJsonElement(body).jsonObject, PaginationStrategy.Number())
                }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code, body)
        }
        val cursorFailure =
            assertFailsWith<CloudFailure> {
                Pagination.decode(Json.parseToJsonElement("""{"result_info":3}""").jsonObject, PaginationStrategy.Cursor())
            }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, cursorFailure.code)
    }

    @Test
    fun theCurrentCursorAlsoCountsAsARepeatedCursor() {
        val root = Json.parseToJsonElement("""{"result_info":{"cursor":"again"}}""").jsonObject
        val failure = assertFailsWith<CloudFailure> { Pagination.decode(root, PaginationStrategy.Cursor("again")) }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun paginationArithmeticNeverOverflowsOrInventsAPage() {
        val huge =
            Json.parseToJsonElement("""{"result_info":{"total_count":9223372036854775807,"per_page":9223372036854775807}}""").jsonObject
        assertNull(Pagination.decode(huge, PaginationStrategy.Number(2)).next, "a multiplied page budget must not overflow into a next page")
        val maxPage = Json.parseToJsonElement("""{"result_info":{"total_pages":2147483648}}""").jsonObject
        val failure = assertFailsWith<CloudFailure> { Pagination.decode(maxPage, PaginationStrategy.Number(Int.MAX_VALUE)) }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code, "unrepresentable provider metadata is a stable response error")
        val more = Json.parseToJsonElement("""{"result_info":{"total_pages":3}}""").jsonObject
        assertEquals(PageToken.Number(2), Pagination.decode(more, PaginationStrategy.Number(1)).next)
        val manyItems = Json.parseToJsonElement("""{"result_info":{"total_count":101,"per_page":50}}""").jsonObject
        assertEquals(PageToken.Number(3), Pagination.decode(manyItems, PaginationStrategy.Number(2)).next)
        val exactlyConsumed = Json.parseToJsonElement("""{"result_info":{"total_count":100,"per_page":50}}""").jsonObject
        assertNull(Pagination.decode(exactlyConsumed, PaginationStrategy.Number(2)).next)
    }

    // ---------------------------------------------------------------- R3 retry arithmetic and header shapes

    @Test
    fun retryBudgetAndDateArithmeticNeverOverflow() = runTest {
        val sleeps = mutableListOf<Long>()
        val transport = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "date"), ok()))
        val failure =
            assertFailsWith<CloudFailure> {
                gateway(transport, registry(registration("retry-read")), sleeps, now = { Long.MIN_VALUE }, parseDate = { 1L })
                    .send("retry-read", HttpRequest("GET", "$account/d1/database"))
            }
        assertEquals(CloudFailureCode.RATE_LIMITED, failure.code)
        assertEquals(1, transport.requests.size)
        assertEquals(0, sleeps.size)

        val cumulative =
            ScriptedTransport(
                listOf(
                    HttpResponse(429, "{}").withHeader("Retry-After", "1"),
                    HttpResponse(429, "{}").withHeader("Retry-After", "date"),
                    ok(),
                )
            )
        val cumulativeSleeps = mutableListOf<Long>()
        val cumulativeFailure =
            assertFailsWith<CloudFailure> {
                gateway(cumulative, registry(registration("retry-read")), cumulativeSleeps, now = { 0L }, parseDate = { Long.MAX_VALUE })
                    .send("retry-read", HttpRequest("GET", "$account/d1/database"))
            }
        assertEquals(CloudFailureCode.RATE_LIMITED, cumulativeFailure.code)
        assertEquals(2, cumulative.requests.size)
        assertEquals(listOf(1000L), cumulativeSleeps, "the cumulative budget must not overflow into an early send")
    }

    @Test
    fun presentButEmptyRetryHeaderIsNotMissingAndSignedSecondsAreNotDeltaSeconds() = runTest {
        val empty = ScriptedTransport(listOf(HttpResponse(429, "{}".encodeToByteArray(), mapOf("Retry-After" to emptyList())), ok()))
        val emptySleeps = mutableListOf<Long>()
        val emptyFailure =
            assertFailsWith<CloudFailure> {
                gateway(empty, registry(registration("retry-read")), emptySleeps).send("retry-read", HttpRequest("GET", "$account/d1/database"))
            }
        assertEquals(CloudFailureCode.RATE_LIMITED, emptyFailure.code)
        assertEquals(1, empty.requests.size)
        assertEquals(0, emptySleeps.size, "a present but empty header is not a missing header")

        val signed = ScriptedTransport(listOf(HttpResponse(429, "{}").withHeader("Retry-After", "+1"), ok()))
        val signedSleeps = mutableListOf<Long>()
        val signedFailure =
            assertFailsWith<CloudFailure> {
                gateway(signed, registry(registration("retry-read")), signedSleeps).send("retry-read", HttpRequest("GET", "$account/d1/database"))
            }
        assertEquals(CloudFailureCode.RATE_LIMITED, signedFailure.code)
        assertEquals(1, signed.requests.size)
        assertEquals(0, signedSleeps.size, "a signed value is not a valid delta-seconds value")
    }

    // ---------------------------------------------------------------- R4 write uncertainty

    @Test
    fun unrecognizedWriteStatusesStayUncertain() {
        for (status in listOf(302, 418)) {
            val write = assertFailsWith<CloudFailure>(status.toString()) { ResponseDecoders.envelope(HttpResponse(status, "{}"), writing = true) }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, write.code, status.toString())
            assertTrue(write.uncertain, "an unrecognized status after a write must stay uncertain")
            assertNull(write.cause, status.toString())
            val read = assertFailsWith<CloudFailure> { ResponseDecoders.envelope(HttpResponse(status, "{}"), writing = false) }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, read.code)
            assertFalse(read.uncertain)
        }
    }

    @Test
    fun legacyEmptyWriteIsNotReportedAsDefinitelyFailed() = runTest {
        val api = CloudflareSession(Connection("live", "Live", "account-1"), ScriptedTransport(listOf(HttpResponse(204, ""))))
        val failure = assertFailsWith<CloudException> { api.delete("zone", "record") }
        assertTrue(failure.uncertain, "204 is not evidence that a write was not executed")
    }


    // ---------------------------------------------------------------- fix 2 residuals

    @Test
    fun rawTraversalPathsNeverReachTheTransport() = runTest {
        val cases =
            listOf(
                Triple("accounts-account-details", "GET", "/accounts/."),
                Triple("accounts-account-details", "GET", "/accounts/.."),
                Triple("dns-records-for-a-zone-list-dns-records", "GET", "/zones/../dns_records"),
                Triple("dns-records-for-a-zone-delete-dns-record", "DELETE", "/zones/z/dns_records/.."),
            )
        for ((key, method, path) in cases) {
            val transport = ScriptedTransport(listOf(ok()))
            val failure =
                assertFailsWith<CloudFailure>("$method $path") {
                    gateway(transport, ActionPolicies.registry).send(key, HttpRequest(method, path))
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
            assertNull(failure.cause, path)
            assertEquals(0, transport.requests.size, "$method $path reached the transport")
        }
    }

    @Test
    fun theInitialCurrentCursorStaysInTheAdvancementHistory() {
        fun root(cursor: String) = Json.parseToJsonElement("""{"result_info":{"cursor":"$cursor"}}""").jsonObject
        val first = PaginationStrategy.Cursor("first")
        val second = assertNotNull(Pagination.next(first, Pagination.decode(root("second"), first)))
        assertEquals("second", (second as PaginationStrategy.Cursor).cursor)
        val failure = assertFailsWith<CloudFailure> { Pagination.decode(root("first"), second) }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
        assertNull(failure.cause)
    }

    @Test
    fun aPresentEmptyRetryEntryCannotBeMaskedByAnotherCaseVariant() = runTest {
        val response =
            HttpResponse(
                429,
                "{}".encodeToByteArray(),
                mapOf("Retry-After" to emptyList(), "retry-after" to listOf("1")),
            )
        val transport = ScriptedTransport(listOf(response, ok()))
        val sleeps = mutableListOf<Long>()
        val failure =
            assertFailsWith<CloudFailure> {
                gateway(transport, registry(registration("retry-read")), sleeps).send("retry-read", HttpRequest("GET", "$account/d1/database"))
            }
        assertEquals(CloudFailureCode.RATE_LIMITED, failure.code)
        assertEquals(1, transport.requests.size, "a present invalid entry must not be ignored before retrying")
        assertEquals(0, sleeps.size)
        assertNull(failure.cause)
    }

    private fun registry(vararg registrations: ActionRegistration): ActionRegistry = ActionRegistry.of(*registrations)

    private fun HttpResponse.withHeader(name: String, value: String): HttpResponse =
        HttpResponse(status, bytes, headers + (name to listOf(value)))
}

/** A minimal one-shot stream source for the no-replay contract. */
private class StreamSource : com.flarego.core.ports.ByteSource {
    override fun read(buffer: ByteArray): Int = -1

    override fun close() {}
}
