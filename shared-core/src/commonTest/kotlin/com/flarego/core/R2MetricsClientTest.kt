package com.flarego.core

import com.flarego.core.application.r2.R2_METRICS_READ_ONLY_UNKNOWN
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.r2.CloudflareR2MetricsClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2MetricNumber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch C Task 1 (fix1): the typed account-metrics read. Synthetic transport only; the projection
 * keeps the provider's exact JSON number lexical form (never a rounded Double), never fabricates
 * zeroes/units/timestamps and never grants a bucket or jurisdiction scope.
 */
class R2MetricsClientTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val path = "/accounts/${connection.accountId}/r2/metrics"

    private val requests = mutableListOf<HttpRequest>()
    private var status = 200
    private var result = """{"standard":{"uploaded":{"metadataSize":1.5,"objects":3,"payloadSize":2.25}}}"""

    private fun envelope(result: String) =
        """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private val gateway = CloudflareGateway(
        HttpTransport { request ->
            requests += request
            assertTrue(request.path == path)
            if (status == 200) HttpResponse(200, envelope(result)) else HttpResponse(status, envelope("null"))
        },
        sleeper = {},
    )

    private val client = CloudflareR2MetricsClient(connection, gateway)

    private fun lexical(value: R2MetricNumber?): String? = value?.lexical

    @Test
    fun theAccountMetricsReadIsRegisteredExactlyOnce() {
        val policy = assertNotNull(ActionPolicies.policy("r2-get-account-level-metrics"))
        assertEquals(ActionSemantics.READ, policy.semantics)
        assertEquals(RiskLevel.READ_ONLY, policy.risk)
        assertTrue(policy.retry.enabled)
        val registration = assertNotNull(ActionPolicies.registry.registration("r2-get-account-level-metrics"))
        assertEquals("GET", registration.method)
        assertEquals("/accounts/{account_id}/r2/metrics", registration.pathTemplate)
    }

    @Test
    fun metricsRequestCarriesNoBucketOrJurisdictionHeader() = runTest {
        client.getMetrics(account)
        val request = requests.single()
        assertEquals(path, request.path)
        assertTrue(request.headers.isEmpty(), "account metrics must not carry a bucket/jurisdiction header")
        assertNull(request.headers["cf-r2-jurisdiction"])
        assertFalse(request.path.contains("/buckets/"))
    }

    @Test
    fun decimalsAndMissingFieldsKeepTheirExactLexicalValues() = runTest {
        result = """{"standard":{"uploaded":{"metadataSize":1.5,"objects":3,"payloadSize":2.25},"published":{"objects":0.5}},"infrequentAccess":{"uploaded":{"payloadSize":10}}}"""
        val metrics = client.getMetrics(account)
        assertTrue(metrics.interpretable)
        val standard = assertNotNull(metrics.standard)
        val uploaded = assertNotNull(standard.uploaded)
        assertEquals("1.5", lexical(uploaded.metadataSize))
        assertEquals("3", lexical(uploaded.objects))
        assertEquals("2.25", lexical(uploaded.payloadSize))
        val published = assertNotNull(standard.published)
        assertEquals("0.5", lexical(published.objects))
        assertNull(published.metadataSize, "a missing field stays null, never 0")
        assertNull(published.payloadSize)
        assertNull(assertNotNull(metrics.infrequentAccess).published)
        assertEquals("10", lexical(assertNotNull(assertNotNull(metrics.infrequentAccess).uploaded).payloadSize))
    }

    @Test
    fun numbersBeyondDoublePrecisionAndTinyExponentsAreNotRounded() = runTest {
        result = """{"standard":{"uploaded":{"objects":9007199254740993,"payloadSize":0.123456789012345678901234567890,"metadataSize":1e-400}}}"""
        val metrics = client.getMetrics(account)
        assertTrue(metrics.interpretable, "valid JSON numbers stay readable; they are not rounded")
        val uploaded = assertNotNull(assertNotNull(metrics.standard).uploaded)
        assertEquals("9007199254740993", lexical(uploaded.objects), "2^53+1 must not become 2^53")
        assertEquals("0.123456789012345678901234567890", lexical(uploaded.payloadSize), "long decimals stay exact")
        assertEquals("1e-400", lexical(uploaded.metadataSize), "a tiny exponent must not underflow to 0")
        assertFalse(lexical(uploaded.metadataSize) == "0")
    }

    @Test
    fun nonFiniteWrongTypedOrUnknownValuesKeepTheDocumentReadOnly() = runTest {
        result = """{"standard":{"uploaded":{"objects":1e400}}}"""
        val overflow = client.getMetrics(account)
        assertFalse(overflow.interpretable, "an overflowing number is not a metric this app can show")
        assertNull(overflow.standard!!.uploaded!!.objects)
        assertEquals(R2_METRICS_READ_ONLY_UNKNOWN, overflow.readOnlyReason)

        result = """{"standard":{"uploaded":{"objects":"3"}}}"""
        val stringly = client.getMetrics(account)
        assertFalse(stringly.interpretable)
        assertNull(stringly.standard!!.uploaded!!.objects)

        result = """{"standard":{"uploaded":{"objects":true}}}"""
        assertFalse(client.getMetrics(account).interpretable)

        result = """{"standard":{"uploaded":{"objects":3,"futureMetric":1}}}"""
        assertFalse(client.getMetrics(account).interpretable)

        result = """{"standard":{"uploaded":{"payloadSize":null}}}"""
        assertFalse(client.getMetrics(account).interpretable)

        result = """{"standard":{"uploaded":{"objects":3.0e-1}}}"""
        val exponent = client.getMetrics(account)
        assertTrue(exponent.interpretable)
        assertEquals("3.0e-1", lexical(exponent.standard!!.uploaded!!.objects), "an exponent form stays verbatim")
    }

    @Test
    fun aCrossAccountScopeIsRefusedBeforeTheRequest() = runTest {
        val foreign = AccountScope("another-connection", "b".repeat(32))
        val mismatch = assertFailsWith<CloudFailure> { client.getMetrics(foreign) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, mismatch.code)
        assertTrue(requests.isEmpty())
    }
}
