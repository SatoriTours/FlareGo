package com.flarego.core

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.identity.AuthorizedR2SippySession
import com.flarego.core.cloudflare.identity.CloudflareR2WriteAuthorityClient
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.Connection
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 5b authority: the two Sippy writes require their own fresh account-level
 * `Workers R2 Storage Write` proof for exactly this account/bucket/jurisdiction/operation, and the
 * proof-bound final guard is installed in the wire client.
 *
 * A bucket-scoped grant or a bucket-item permission is never REST administration; a non-default
 * jurisdiction is admitted only for the reviewed endpoints that declare the controlled header.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2SippyAuthorityTest {
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

    private fun draft() = R2SippySecrets.draft(
        R2SippyAwsSourceSecrets("SYNTHETIC-AUTHORITY-SOURCE-KEY", "SYNTHETIC-AUTHORITY-SOURCE-SECRET", "source-bucket", "us-east-1"),
        R2SippyDestinationSecrets("SYNTHETIC-AUTHORITY-DEST-KEY", "SYNTHETIC-AUTHORITY-DEST-SECRET"),
    )

    private inner class Harness(kind: TokenKind = TokenKind.USER_OWNED) {
        var now = 1_000L
        var revision = 7L
        var policies = listOf(policy())
        var groupRows = listOf(
            catalogRow("admin", "Workers R2 Storage Write", accountScope),
            catalogRow("reader", "Workers R2 Storage Read", accountScope),
            catalogRow("item", "Workers R2 Storage Bucket Item Write", "com.cloudflare.edge.r2.bucket"),
        )
        val calls = mutableListOf<HttpRequest>()
        val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, kind)
        val transfers = object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("unexpected download")
        }
        val gateway = CloudflareGateway(HttpTransport { request ->
            calls += request
            if (request.method != "GET") return@HttpTransport ok()
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
                request.path.endsWith("/sippy") ->
                    envelope(buildJsonObject { put("enabled", false) })
                else -> error("unexpected synthetic read ${request.path}")
            }
        }, sleeper = {})
        val session = AuthorizedR2SippySession(connection, gateway, issuer)
        val mutations get() = calls.filter { it.method != "GET" }
        fun authority() = CloudflareR2WriteAuthorityClient(issuer, gateway)
        fun tokenReads() = calls.count { it.path.endsWith("/self-token") }

        suspend fun proof(t: R2WriteTarget, operation: String): R2WriteProof {
            val observed = authority().observe(t, operation, true)
            assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, observed.decision)
            return issuer.proofFor(observed)
        }
    }

    @Test
    fun bothSippyWritesMintTheirOwnFreshAccountLevelProof() = runTest {
        val h = Harness()
        h.session.putSippy(target, draft())
        h.session.deleteSippy(target)
        assertEquals(listOf("PUT", "DELETE"), h.mutations.map { it.method })
        assertEquals(listOf("default", "default"), h.mutations.map { it.headers["cf-r2-jurisdiction"] })
        // Each write obtained its own fresh token observation instead of reusing a prior grant.
        assertTrue(h.tokenReads() >= 2, "each Sippy write needs a fresh account-level proof")

        // A proof for one operation never authorizes the other one.
        val putProof = h.proof(target, "r2-put-bucket-sippy-config")
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(putProof, target, "r2-delete-bucket-sippy-config") }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(R2WriteProof(), target, "r2-put-bucket-sippy-config") }
        assertEquals(2, h.mutations.size)
    }

    @Test
    fun bucketScopedOrItemOnlyPermissionNeverAuthorizesASippyWrite() = runTest {
        val bucketScoped = Harness()
        bucketScoped.policies =
            listOf(policy(resource = "com.cloudflare.edge.r2.bucket.${connection.accountId}_default_my-bucket"))
        assertNotEquals(
            WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            bucketScoped.authority().observe(target, "r2-put-bucket-sippy-config", true).decision,
        )
        assertFailsWith<CloudFailure> { bucketScoped.session.putSippy(target, draft()) }
        assertEquals(0, bucketScoped.mutations.size)

        val itemOnly = Harness()
        itemOnly.policies = listOf(policy(ids = listOf("item")))
        assertNotEquals(
            WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            itemOnly.authority().observe(target, "r2-delete-bucket-sippy-config", true).decision,
        )
        assertFailsWith<CloudFailure> { itemOnly.session.deleteSippy(target) }
        assertEquals(0, itemOnly.mutations.size)
    }

    @Test
    fun anUnknownPolicySyntaxIsNeverTreatedAsAGrant() = runTest {
        val h = Harness()
        h.policies = listOf(policy(effect = "deny", ids = listOf("unreviewed-group")))
        assertNotEquals(
            WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            h.authority().observe(target, "r2-put-bucket-sippy-config", true).decision,
        )
        assertFailsWith<CloudFailure> { h.session.putSippy(target, draft()) }
        assertEquals(0, h.mutations.size)
    }

    @Test
    fun aNonDefaultJurisdictionIsAdmittedOnlyForTheReviewedSippyActions() = runTest {
        val h = Harness()
        h.session.putSippy(eu, draft())
        assertEquals(1, h.mutations.size)
        assertEquals("eu", h.mutations.single().headers["cf-r2-jurisdiction"])
        // Batch A keeps its documented default-only behaviour.
        assertNotEquals(
            WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
            h.authority().observe(eu, "r2-delete-bucket", true).decision,
        )
        assertTrue(h.mutations.size == 1)
    }

    @Test
    fun aCredentialChangeBetweenTheTwoWritesLeavesTheSecondUnsent() = runTest {
        val h = Harness()
        h.session.putSippy(target, draft())
        assertEquals(1, h.mutations.size)
        h.revision = -1
        assertFailsWith<CloudFailure> { h.session.deleteSippy(target) }
        assertEquals(1, h.mutations.size, "a revoked credential leaves zero additional sends")
    }

    @Test
    fun theProofBoundFinalGuardStillRespectsTheCredentialGenerationBeforeTheSend() = runTest {
        val h = Harness()
        // The product callback succeeds; the credential is revoked between it and the typed send, so
        // the session's synchronous final proof guard refuses with zero HTTP mutations.
        assertFailsWith<CloudFailure> {
            h.session.writeSippy(com.flarego.core.application.r2.R2SippyWrite.PutSippy(target, draft())) { h.revision = -1 }
        }
        assertEquals(0, h.mutations.size)
    }
}
