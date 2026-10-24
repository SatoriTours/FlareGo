package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.identity.AuthorizedR2ConfigurationSession
import com.flarego.core.cloudflare.identity.AuthorizedR2Session
import com.flarego.core.cloudflare.identity.CloudflareR2WriteAuthorityClient
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.Connection
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteProof
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.WriteAuthorityDecision
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TokenKind
import com.flarego.core.ports.TransferPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Batch B1 authority: a fresh account-level `Workers R2 Storage Write` proof for exactly
 * this account/bucket/jurisdiction/operation, the final synchronous guard after the
 * configuration read, and no admission for batch A operations, local uploads outside
 * `default`, bucket-scoped S3 item permissions or an unproven region.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2ConfigurationAuthorityTest {
    private val connection = Connection("r2-local", "display", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val eu = R2WriteTarget(account, "my-bucket", "eu")
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"
    private val accountScope = "com.cloudflare.api.account"

    private fun envelope(result: JsonElement) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
    }.toString())

    private fun ok() = envelope(buildJsonObject { put("ok", true) })

    private fun policy(
        effect: String = "allow",
        ids: List<String> = listOf("admin"),
        resource: String = accountResource,
        value: JsonElement = JsonPrimitive("*"),
    ) = buildJsonObject {
        put("id", "p-$effect"); put("effect", effect)
        put("permission_groups", JsonArray(ids.map { buildJsonObject { put("id", it) } }))
        put("resources", buildJsonObject { put(resource, value) })
    }

    private fun catalogRow(id: String, name: String, scope: String) = buildJsonObject {
        put("id", id); put("name", name); put("scopes", JsonArray(listOf(JsonPrimitive(scope))))
    }

    private inner class Harness(kind: TokenKind = TokenKind.USER_OWNED) {
        var now = 1_000L
        var revision = 7L
        var policies = listOf(policy())
        var groupRows = listOf(
            catalogRow("admin", "Workers R2 Storage Write", accountScope),
            catalogRow("reader", "Workers R2 Storage Read", accountScope),
            catalogRow("item", "Workers R2 Storage Bucket Item Write", "com.cloudflare.edge.r2.bucket"),
        )
        var onRead: suspend (HttpRequest) -> Unit = {}
        var failWrite = false
        val calls = mutableListOf<HttpRequest>()
        val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, kind)
        val transfers = object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("unexpected download")
        }
        val gateway = CloudflareGateway(HttpTransport { request ->
            calls += request
            if (request.method != "GET") {
                if (failWrite) return@HttpTransport HttpResponse(500, "private-marker")
                return@HttpTransport ok()
            }
            onRead(request)
            when {
                request.path == "/user/tokens/verify" -> envelope(buildJsonObject { put("id", "self-token"); put("status", "active") })
                request.path.endsWith("/permission_groups") -> envelope(JsonArray(groupRows))
                request.path.endsWith("/self-token") -> envelope(buildJsonObject {
                    put("id", "self-token"); put("status", "active"); put("name", "private-marker")
                    put("policies", JsonArray(policies))
                })
                request.path == "/accounts/${connection.accountId}" ->
                    envelope(buildJsonObject { put("id", connection.accountId) })
                request.path.endsWith("/r2/buckets/my-bucket") ->
                    envelope(buildJsonObject { put("name", "my-bucket") })
                request.path.endsWith("/local-uploads") -> envelope(buildJsonObject { put("enabled", false) })
                request.path.endsWith("/cors") || request.path.endsWith("/lifecycle") ||
                    request.path.endsWith("/lock") -> envelope(buildJsonObject { put("rules", JsonArray(emptyList())) })
                else -> error("unexpected synthetic read ${request.path}")
            }
        }, sleeper = {})
        val configuration = AuthorizedR2ConfigurationSession(connection, gateway, issuer)
        val batchA = AuthorizedR2Session(connection, gateway, transfers, issuer)
        val mutations get() = calls.filter { it.method != "GET" }
        fun authority() = CloudflareR2WriteAuthorityClient(issuer, gateway)

        suspend fun proof(t: R2WriteTarget, operation: String): R2WriteProof {
            val observed = authority().observe(t, operation, true)
            assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, observed.decision)
            return issuer.proofFor(observed)
        }
    }

    private val corsRule = R2CorsRule(allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example")))
    private val lifecycleRule = R2LifecycleRule(
        id = "expire", enabled = true, conditions = R2LifecycleConditions(""),
        deleteObjectsTransition = R2LifecycleCondition.Age(86_400),
    )
    private val lockRule = R2LockRule(id = "lock", enabled = true, condition = R2LockCondition.Age(3_600))

    @Test
    fun everyReviewedConfigurationWriteIsAdmittedOnceForItsOwnJurisdiction() = runTest {
        data class Case(val method: String, val header: String?, val call: suspend (AuthorizedR2ConfigurationSession, R2WriteTarget) -> Unit)
        val cases = listOf(
            Case("PUT", "default") { api, t -> api.putCors(t, listOf(corsRule)) },
            Case("DELETE", "default") { api, t -> api.deleteCors(t) },
            Case("PUT", "default") { api, t -> api.putLifecycle(t, listOf(lifecycleRule)) },
            // The pinned local-uploads endpoints declare no jurisdiction header.
            Case("PUT", null) { api, t -> api.putLocalUploads(t, false) },
            Case("PUT", "default") { api, t -> api.putLock(t, listOf(lockRule)) },
            Case("DELETE", "default") { api, t -> api.deleteLock(t) },
        )
        for (case in cases) {
            val h = Harness()
            case.call(h.configuration, target)
            assertEquals(1, h.mutations.size, case.method)
            assertEquals(case.method, h.mutations.single().method)
            assertEquals(case.header, h.mutations.single().headers["cf-r2-jurisdiction"])
        }
        // A non-default, header-capable configuration write is admitted for exactly that region.
        for (case in cases.filter { it.header != null }) {
            val h = Harness()
            case.call(h.configuration, eu)
            assertEquals(1, h.mutations.size, "eu ${case.method}")
            assertEquals("eu", h.mutations.single().headers["cf-r2-jurisdiction"])
        }
    }

    @Test
    fun nonDefaultTargetsAreRejectedForBatchAAndForLocalUploadsBeforeAnyRequest() = runTest {
        val h = Harness()
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            h.authority().observe(eu, "r2-delete-bucket", true).decision)
        assertTrue(h.calls.isEmpty())
        assertFailsWith<CloudFailure> { h.configuration.putLocalUploads(eu, true) }
        assertTrue(h.calls.isEmpty(), "a non-default local-uploads target never falls back to the default bucket")
        assertEquals(0, h.mutations.size)
    }

    @Test
    fun aProofBindsTheExactJurisdictionBucketAndOperation() = runTest {
        val h = Harness()
        val proof = h.proof(eu, "r2-put-bucket-cors-policy")
        h.issuer.requireCurrent(proof, eu, "r2-put-bucket-cors-policy")
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, "r2-put-bucket-cors-policy") }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, eu.copy(bucket = "other-bucket"), "r2-put-bucket-cors-policy") }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, eu, "r2-put-bucket-lock-configuration") }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(R2WriteProof(), eu, "r2-put-bucket-cors-policy") }
        assertTrue(h.mutations.isEmpty())
    }

    @Test
    fun bucketScopedAllowOrBucketItemPermissionStillDoesNotAuthorizeRestAdministration() = runTest {
        val bucketScoped = Harness()
        bucketScoped.policies = listOf(policy(resource = "com.cloudflare.edge.r2.bucket.${connection.accountId}_eu_my-bucket"))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            bucketScoped.authority().observe(eu, "r2-put-bucket-cors-policy", true).decision)
        val item = Harness()
        item.policies = listOf(policy(ids = listOf("item")))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            item.authority().observe(eu, "r2-put-bucket-cors-policy", true).decision)
    }

    @Test
    fun aCredentialChangeOrClockRollbackDuringTheConfigurationReadEndsInZeroWrites() = runTest {
        for (change in listOf("credential", "clock")) {
            val h = Harness()
            h.onRead = { request ->
                if (request.path.endsWith("/cors")) {
                    if (change == "credential") h.revision++ else h.now = 31_000
                }
            }
            assertFailsWith<CloudFailure> { h.configuration.putCors(eu, listOf(corsRule)) }
            assertTrue(h.mutations.isEmpty(), change)
        }
    }

    @Test
    fun theFinalGuardAlsoRevokesAWriteWhenTheCredentialChangesAfterTheSnapshotWasRead() = runTest {
        val h = Harness()
        var snapshotReads = 0
        h.onRead = { request ->
            if (request.path.endsWith("/cors")) {
                snapshotReads++
                if (snapshotReads == 1) h.revision++ // after the authority proof and bucket proof
            }
        }
        assertFailsWith<CloudFailure> { h.configuration.putCors(target, listOf(corsRule)) }
        assertEquals(1, snapshotReads)
        assertTrue(h.mutations.isEmpty())
    }

    @Test
    fun anUncertainProviderOutcomeIsOneReceiptAndNeverARetry() = runTest {
        val h = Harness()
        h.failWrite = true
        val receipt = h.configuration.writeConfiguration(R2ConfigurationWrite.PutCors(target, listOf(corsRule))) {}
        assertTrue(receipt !is ExecutionReceipt.Completed)
        assertEquals(1, h.mutations.size, "a 5xx configuration write is never replayed")
    }

    @Test
    fun batchAOperationsStillRequireTheirOwnDefaultOnlyProof() = runTest {
        val h = Harness()
        h.batchA.deleteBucket(account, "my-bucket")
        assertEquals(1, h.mutations.size)
        assertEquals("DELETE", h.mutations.single().method)
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            h.authority().observe(eu, "r2-delete-bucket", true).decision)
    }

    @Test
    fun anUnknownJurisdictionNeverGetsAProofOrAnyProviderRead() = runTest {
        val headerOperations = listOf(
            "r2-put-bucket-cors-policy", "r2-delete-bucket-cors-policy",
            "r2-put-bucket-lifecycle-configuration",
            "r2-put-bucket-lock-configuration", "r2-delete-bucket-lock-configuration",
        )
        for (operation in headerOperations + "r2-delete-bucket") {
            val h = Harness()
            val mars = R2WriteTarget(account, "my-bucket", "mars")
            assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
                h.authority().observe(mars, operation, true).decision, operation)
            assertTrue(h.calls.isEmpty(), "$operation must be refused before any provider read")
        }
        val h = Harness()
        assertFailsWith<CloudFailure> {
            h.configuration.putCors(R2WriteTarget(account, "my-bucket", "MARS"), listOf(corsRule))
        }
        assertTrue(h.calls.isEmpty())
        assertTrue(h.mutations.isEmpty())
    }

    @Test
    fun everyPinnedJurisdictionStillWorksForTheFiveHeaderCapableWrites() = runTest {
        val headerOperations = listOf("putCors", "deleteCors", "putLifecycle", "putLock", "deleteLock")
        for (jurisdiction in listOf("default", "eu", "us", "fedramp", "fedramp-high")) {
            val h = Harness()
            val region = R2WriteTarget(account, "my-bucket", jurisdiction)
            for (operation in headerOperations) {
                val observed = h.authority().observe(region, when (operation) {
                    "putCors" -> "r2-put-bucket-cors-policy"
                    "deleteCors" -> "r2-delete-bucket-cors-policy"
                    "putLifecycle" -> "r2-put-bucket-lifecycle-configuration"
                    "putLock" -> "r2-put-bucket-lock-configuration"
                    else -> "r2-delete-bucket-lock-configuration"
                }, true)
                assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, observed.decision, "$jurisdiction/$operation")
            }
        }
    }
}
