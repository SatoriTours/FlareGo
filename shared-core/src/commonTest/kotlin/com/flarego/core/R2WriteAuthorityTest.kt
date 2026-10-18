package com.flarego.core

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2WriteAuthorityTest {
    private val connection = Connection("r2-local", "display", "a".repeat(32))
    private val target = R2WriteTarget(AccountScope(connection.id, connection.accountId), "my-bucket")
    private val operation = "r2-put-object"
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"
    private val accountScope = "com.cloudflare.api.account"
    private fun policy(effect: String = "allow", ids: List<String> = listOf("admin"), resource: String = accountResource, value: JsonElement = JsonPrimitive("*")) = buildJsonObject {
        put("id", "p-$effect"); put("effect", effect)
        put("permission_groups", JsonArray(ids.map { buildJsonObject { put("id", it) } }))
        put("resources", buildJsonObject { put(resource, value) })
    }
    private fun envelope(result: JsonElement) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
    }.toString())
    private fun catalogRow(id: String, name: String, scope: String) = buildJsonObject {
        put("id", id); put("name", name); put("scopes", JsonArray(listOf(JsonPrimitive(scope))))
    }
    private inner class Harness(kind: TokenKind = TokenKind.USER_OWNED) {
        var now = 1_000L
        var revision = 7L
        var verify = buildJsonObject { put("id", "self-token"); put("status", "active") }
        var policies = listOf(policy())
        var condition: JsonObject? = null
        var groupRows = listOf(catalogRow("admin", "Workers R2 Storage Write", accountScope),
            catalogRow("reader", "Workers R2 Storage Read", accountScope),
            catalogRow("item", "Workers R2 Storage Bucket Item Write", "com.cloudflare.edge.r2.bucket"))
        var userAvailable = true
        var userVerifyStatus: Int? = null
        var accountAvailable = kind == TokenKind.ACCOUNT_OWNED
        var failurePath: String? = null
        var rawOverride: HttpResponse? = null
        var ownerId = connection.accountId
        var onRead: suspend (String) -> Unit = {}
        val calls = mutableListOf<HttpRequest>()
        val transfers = object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("unexpected download")
        }
        fun session() = AuthorizedR2Session(connection, gateway, transfers, issuer)
        val mutations get() = calls.filter { it.method != "GET" }
        val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, kind)
        val gateway = CloudflareGateway(HttpTransport { r ->
            calls += r
            if (r.method != "GET") {
                // The real platform owns streams. Only this external IO is replaced here.
                (r.payload as? HttpBody.Stream)?.source?.let { source ->
                    try { val buffer = ByteArray(32); while (source.read(buffer) > 0) { } } finally { source.close() }
                }
                return@HttpTransport envelope(when {
                    r.path.endsWith("/objects/a.txt") -> buildJsonObject { put("key", "a.txt"); put("size", "1") }
                    r.path.endsWith("/objects") -> JsonArray(listOf(buildJsonObject { put("key", "a.txt") }))
                    else -> buildJsonObject { put("name", "my-bucket"); put("storage_class", "Standard") }
                })
            }
            onRead(r.path)
            rawOverride ?: when {
                r.path == failurePath -> HttpResponse(403, "private-marker")
                r.path == "/user/tokens/verify" -> userVerifyStatus?.let { HttpResponse(it, "private-marker") } ?: if (userAvailable) envelope(verify) else HttpResponse(401, "private-marker")
                r.path == "/accounts/${connection.accountId}/tokens/verify" -> if (accountAvailable) envelope(verify) else HttpResponse(401, "private-marker")
                r.path.endsWith("/permission_groups") -> envelope(JsonArray(groupRows))
                r.path.endsWith("/self-token") -> envelope(buildJsonObject {
                    verify.forEach { (k,v) -> put(k,v) }; put("name", "private-marker")
                    put("policies", JsonArray(policies)); condition?.let { put("condition", it) }
                })
                r.path == "/accounts/${connection.accountId}" -> envelope(buildJsonObject { put("id", ownerId); put("name", "display") })
                r.path == "/accounts/${connection.accountId}/r2/buckets/my-bucket" -> envelope(buildJsonObject { put("name", "my-bucket") })
                r.path.startsWith("/accounts/${connection.accountId}/r2/buckets?") -> envelope(buildJsonObject { put("buckets", JsonArray(emptyList())) })
                else -> error("unexpected synthetic read")
            }
        }, sleeper = {})
        fun client() = CloudflareR2WriteAuthorityClient(issuer, gateway)
        suspend fun observe(t: R2WriteTarget = target, op: String = operation) = client().observe(t, op, true)
        suspend fun proof(): R2WriteProof {
            val observation = observe()
            assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, observation.decision)
            return issuer.proofFor(observation)
        }
    }

    @Test fun accountAdminPermissionCreatesOnlyIssuerOwnedTargetBoundProof() = runTest {
        val h = Harness(); val proof = h.proof()
        h.issuer.requireCurrent(proof, target, operation)
        assertTrue(h.calls.all { it.method == "GET" })
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target.copy(bucket = "other-bucket"), operation) }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, "r2-delete-bucket") }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(R2WriteProof(), target, operation) }
    }
    @Test fun forgedGrantedObservationCannotMintProof() = runTest {
        val h = Harness(); val observation = h.observe()
        assertFailsWith<CloudFailure> { h.issuer.proofFor(observation.copy()) }
    }
    @Test fun objectItemOrReadPermissionNeverGrantsRestAdministration() = runTest {
        for (ids in listOf(listOf("reader"), listOf("item"))) {
            val h = Harness(); h.policies = listOf(policy(ids = ids))
            assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        }
    }
    @Test fun foreignAccountOrWrongJurisdictionDoesNotGetProof() = runTest {
        val h = Harness()
        assertFailsWith<CloudFailure> { h.observe(target.copy(account = AccountScope("r2-local", "b".repeat(32)))) }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe(target.copy(jurisdiction = "eu")).decision)
    }
    @Test fun accountNestedAllBucketsIsAcceptedButBucketLimitedAllowIsNotUpgraded() = runTest {
        val h = Harness()
        h.policies = listOf(policy(value = buildJsonObject { put("com.cloudflare.edge.r2.bucket.*", "*") }))
        h.proof()
        h.policies = listOf(policy(resource = "com.cloudflare.edge.r2.bucket.${connection.accountId}_default_my-bucket"))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun matchingDenyWinsAndForeignAccountAllowDoesNotMatch() = runTest {
        val h = Harness()
        h.policies = listOf(policy(), policy(effect = "deny"))
        assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
        h.policies = listOf(policy(resource = "com.cloudflare.api.account.${"b".repeat(32)}"))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun bucketSpecificDenyIsNotIgnoredUnderBroadAccountAllow() = runTest {
        val h = Harness()
        h.policies = listOf(policy(), policy(effect = "deny", resource = "com.cloudflare.edge.r2.bucket.${connection.accountId}_default_my-bucket"))
        assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
        h.policies = listOf(policy(), policy(effect = "deny", ids = listOf("unknown")))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun unsupportedResourceOrConditionFailsClosed() = runTest {
        val h = Harness(); h.policies = listOf(policy(resource = "unknown-resource"))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        h.policies = listOf(policy()); h.condition = buildJsonObject { put("unknown", true) }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun scopeMismatchedOrDuplicateCatalogNamesNeverGrant() = runTest {
        val h = Harness(); h.groupRows = listOf(catalogRow("admin", "Workers R2 Storage Write", "com.cloudflare.edge.r2.bucket"))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        h.groupRows = listOf(catalogRow("admin", "Workers R2 Storage Write", accountScope), catalogRow("different", "Workers R2 Storage Write", accountScope))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun expiredOrDisabledTokenDoesNotGrantAndProofExpiresAtBoundary() = runTest {
        val h = Harness(); h.verify = buildJsonObject { put("id", "self-token"); put("status", "disabled") }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        h.verify = buildJsonObject { put("id", "self-token"); put("status", "active") }
        val proof = h.proof(); h.now = 31_000
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, operation) }
    }
    @Test fun credentialChangeOrSessionInvalidationRevokesProofAndOldClient() = runTest {
        val h = Harness(); val oldClient = h.client(); val proof = h.proof()
        h.issuer.invalidate(connection.id)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, operation) }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, oldClient.observe(target, operation, true).decision)
        h.proof(); h.revision++
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun reconstructionDoesNotResetTokenOrCatalogBindings() = runTest {
        val h = Harness(); h.proof()
        h.groupRows = listOf(catalogRow("new-admin-id", "Workers R2 Storage Write", accountScope))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.client().observe(target, operation, true).decision)
        h.groupRows = listOf(catalogRow("admin", "Workers R2 Storage Write", accountScope))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun clockRollbackAndMidReadCredentialChangeCannotGrant() = runTest {
        val h = Harness(); h.proof(); h.now = 999
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        val changed = Harness(); changed.onRead = { changed.revision++ }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, changed.observe().decision)
    }
    @Test fun unknownKindDoesNotTreatTransientVerifyFailureAsProofOfTheOtherKind() = runTest {
        val h = Harness(TokenKind.UNKNOWN); h.accountAvailable = true; h.userVerifyStatus = 500
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun accountOwnedTokensUseOnlyTheirAccountInspectionEndpoints() = runTest {
        val h = Harness(TokenKind.ACCOUNT_OWNED); h.proof()
        assertTrue(h.calls.none { it.path.startsWith("/user/") })
    }
    @Test fun ambiguousTokenKindAndUninspectableSelfAreNotWriteAuthorization() = runTest {
        val h = Harness(TokenKind.UNKNOWN); h.accountAvailable = true
        assertEquals(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND, h.observe().reason)
        val missing = Harness(); missing.failurePath = "/user/tokens/self-token"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, missing.observe().decision)
    }
    @Test fun duplicateJsonKeysAndFutureOperationsFailClosedWithoutSensitiveEcho() = runTest {
        val h = Harness(); h.rawOverride = HttpResponse(200, """{"success":true,"success":true,"result":{"id":"private-marker"},"errors":[],"messages":[]}""")
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        val unknown = Harness(); assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, unknown.observe(op = "r2-create-temp-access-credentials").decision)
        assertTrue(unknown.calls.isEmpty())
    }
    @Test fun providerAccountOwnershipMustMatchEvenWhenTokenPolicyAllows() = runTest {
        val h = Harness(); h.ownerId = "b".repeat(32)
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    private class OneByteSource : ByteSource {
        var closes = 0
        private var read = false
        override fun read(buffer: ByteArray): Int { if (read) return -1; read = true; buffer[0] = 1; return 1 }
        override fun close() { closes++ }
    }
    @Test fun everyPublicBucketWritePassesTheGateAndDeniedReadsNeverAuthorize() = runTest {
        val h = Harness(); h.policies = listOf(policy(ids = listOf("reader")))
        val api = h.session()
        api.getBucket(target.account, "my-bucket")
        val writes: List<suspend () -> Unit> = listOf(
            { api.createBucket(target.account, R2BucketCreateInput("my-bucket")); Unit },
            { api.createBucketByName(target.account, "my-bucket", null); Unit },
            { api.patchBucketStorageClass(target.account, "my-bucket", "Standard"); Unit },
            { api.deleteBucket(target.account, "my-bucket") },
            { api.write(R2BucketWrite.Delete(target.account, "my-bucket", "my-bucket")) {}; Unit },
        )
        for (write in writes) assertFailsWith<CloudFailure> { write() }
        assertTrue(h.mutations.isEmpty())
    }
    @Test fun everyPublicObjectWritePassesTheGateAndDirectUploadRejectionClosesSource() = runTest {
        val h = Harness(); h.policies = listOf(policy(ids = listOf("item")))
        val api = h.session(); val objectTarget = R2ObjectTarget(target.account, "my-bucket", "a.txt")
        val source = OneByteSource()
        assertFailsWith<CloudFailure> { api.uploadObject(objectTarget, source, 1, null, "c".repeat(64)) }
        assertEquals(1, source.closes)
        assertFailsWith<CloudFailure> { api.deleteObject(objectTarget) }
        assertFailsWith<CloudFailure> { api.deleteObjects(target.account, "my-bucket", listOf("a.txt")) }
        var opened = false
        assertFailsWith<CloudFailure> {
            api.writeObject(R2ObjectWrite.Upload(target.account, "my-bucket", "a.txt", { opened = true; OneByteSource() }, 1, null, "c".repeat(64))) {}
        }
        assertFalse(opened)
        assertTrue(h.mutations.isEmpty())
    }
    @Test fun validAdminProofReachesEachTypedWriteWithoutReplay() = runTest {
        val objectTarget = R2ObjectTarget(target.account, "my-bucket", "a.txt")
        val cases: List<Pair<String, suspend (Harness) -> Unit>> = listOf(
            "POST" to { h -> h.session().createBucket(target.account, R2BucketCreateInput("my-bucket")); Unit },
            "PUT" to { h -> h.session().createBucketByName(target.account, "my-bucket", null); Unit },
            "PATCH" to { h -> h.session().patchBucketStorageClass(target.account, "my-bucket", "Standard"); Unit },
            "DELETE" to { h -> h.session().deleteBucket(target.account, "my-bucket") },
            "PUT" to { h ->
                val source = OneByteSource()
                val row = h.session().uploadObject(objectTarget, source, 1, null, "c".repeat(64))
                assertEquals("a.txt", row.key); assertEquals(1, source.closes)
            },
            "DELETE" to { h -> h.session().deleteObject(objectTarget) },
            "DELETE" to { h ->
                val result = h.session().deleteObjects(target.account, "my-bucket", listOf("a.txt"))
                assertEquals(1, result.confirmedDeleted); assertFalse(result.uncertain)
            },
        )
        for ((method, execute) in cases) {
            val h = Harness(); execute(h)
            assertEquals(1, h.mutations.size)
            assertEquals(method, h.mutations.single().method)
            assertTrue(h.calls.takeWhile { it.method == "GET" }.any { it.path == "/user/tokens/permission_groups" })
        }
    }
    @Test fun validProofAllowsExactlyOneTypedMutationAndNewSessionRetainsIssuer() = runTest {
        val h = Harness(); val result = h.session().createBucket(target.account, R2BucketCreateInput("my-bucket"))
        assertEquals("my-bucket", result.name)
        assertEquals(listOf("POST"), h.mutations.map { it.method })
        h.groupRows = listOf(catalogRow("new", "Workers R2 Storage Write", accountScope))
        assertFailsWith<CloudFailure> { h.session().deleteBucket(target.account, "my-bucket") }
        assertEquals(1, h.mutations.size)
    }
    @Test fun finalGuardRevokesWriteIfCredentialOrTimeChangesDuringBucketRead() = runTest {
        for (change in listOf("credential", "clock")) {
            val h = Harness()
            h.onRead = { if (it.endsWith("/r2/buckets/my-bucket")) { if (change == "credential") h.revision++ else h.now = 31_000 } }
            assertFailsWith<CloudFailure> { h.session().deleteBucket(target.account, "my-bucket") }
            assertTrue(h.mutations.isEmpty())
        }
    }
    @Test fun finalGuardRunsAfterProductGuardAndClosesAnOpenedButRejectedUploadExactlyOnce() = runTest {
        val h = Harness(); val source = OneByteSource()
        val receipt = runCatching {
            h.session().writeObject(R2ObjectWrite.Upload(target.account, "my-bucket", "a.txt", { source }, 1, null, "c".repeat(64))) {
                h.issuer.invalidate(connection.id)
            }
        }
        assertTrue(receipt.isFailure)
        assertEquals(1, source.closes)
        assertTrue(h.mutations.isEmpty())
    }

    @Test fun cancelledRefreshRevokesPreviouslyIssuedProofWithoutMaskingCancellation() = runTest {
        val h = Harness(); val proof = h.proof()
        h.onRead = { throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { h.observe() }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, operation) }
    }
    @Test fun failedForcedRefreshRevokesPreviouslyIssuedProof() = runTest {
        val h = Harness(); val proof = h.proof(); h.failurePath = "/user/tokens/permission_groups"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, operation) }
    }
}
