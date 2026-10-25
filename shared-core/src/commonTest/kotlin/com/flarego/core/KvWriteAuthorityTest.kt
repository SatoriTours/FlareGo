package com.flarego.core

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.application.kv.KvBulkJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class KvWriteAuthorityTest {
    private val connection = Connection("kv-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = KvWriteTarget(account, "b".repeat(32))
    private val operation = "workers-kv-namespace-delete-key-value-pair"
    private val scope = "com.cloudflare.api.account"
    private fun policy(group: String = "writer", effect: String = "allow", resource: String = "$scope.${account.accountId}", value: JsonElement = JsonPrimitive("*")) = buildJsonObject {
        put("id", "p-$effect"); put("effect", effect)
        put("permission_groups", JsonArray(listOf(buildJsonObject { put("id", group) })))
        put("resources", buildJsonObject { put(resource, value) })
    }
    private inner class Harness(kind: TokenKind = TokenKind.USER_OWNED) {
        var now = 1000L; var revision = 7L; var owner = account.accountId
        var policies = listOf(policy()); var condition: JsonElement? = null
        var status = "active"; var failPath: String? = null
        var userAvailable = kind != TokenKind.ACCOUNT_OWNED; var accountAvailable = kind == TokenKind.ACCOUNT_OWNED
        var onRead: (String) -> Unit = {}
        var rows = listOf(row("writer", "Workers KV Storage Write"), row("reader", "Workers KV Storage Read"), row("r2", "Workers R2 Storage Write"), row("dns", "DNS Write"))
        val calls = mutableListOf<HttpRequest>()
        val issuer = KvTokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, kind)
        private fun row(id: String, name: String, scopes: String = scope) = buildJsonObject {
            put("id", id); put("name", name); put("scopes", JsonArray(listOf(JsonPrimitive(scopes))))
        }
        val gateway = CloudflareGateway(HttpTransport { r ->
            calls += r; onRead(r.path)
            fun envelope(value: JsonElement) = HttpResponse(200, buildJsonObject {
                put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", value)
            }.toString())
            val identity = buildJsonObject { put("id", "self-token"); put("status", status) }
            when {
                r.path == failPath -> HttpResponse(503, "private-marker")
                r.path == "/user/tokens/verify" -> if (userAvailable) envelope(identity) else HttpResponse(401, "private-marker")
                r.path == "/accounts/${account.accountId}/tokens/verify" -> if (accountAvailable) envelope(identity) else HttpResponse(401, "private-marker")
                r.path.endsWith("/self-token") -> envelope(buildJsonObject {
                    identity.forEach { (k,v) -> put(k,v) }; put("policies", JsonArray(policies)); condition?.let { put("condition", it) }
                })
                r.path.endsWith("/permission_groups") -> envelope(JsonArray(rows))
                r.path == "/accounts/${account.accountId}" -> envelope(buildJsonObject { put("id", owner) })
                r.method != "GET" -> envelope(buildJsonObject {
                    put("id", target.namespaceId); put("title", "fixture")
                    put("successful_key_count", 1); put("unsuccessful_keys", JsonArray(emptyList()))
                })
                else -> error("unexpected synthetic request")
            }
        }, sleeper = {})
        fun client() = CloudflareKvWriteAuthorityClient(issuer, gateway)
        fun session(guard: () -> Unit = {}) = AuthorizedKvSession(connection, gateway, object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("not used")
        }, KvMemoryStore(), { now / 1000 }, issuer, guard)
        suspend fun observe(t: KvWriteTarget = target, op: String = operation) = client().observe(t, op, true)
        suspend fun proof(): KvWriteProof {
            val observation = observe(); assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, observation.decision,
                "reason=${observation.reason}; calls=${calls.map { it.path }}")
            return issuer.proofFor(observation)
        }
    }
    @Test fun accountPermissionMintsOnlyIssuerOwnedNamespaceAndActionBoundProof() = runTest {
        val h = Harness(); val proof = h.proof()
        h.issuer.requireCurrent(proof, target, operation)
        assertTrue(h.calls.all { it.method == "GET" })
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target.copy(namespaceId = "c".repeat(32)), operation) }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, "workers-kv-namespace-remove-a-namespace") }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(KvWriteProof(), target, operation) }
        assertFailsWith<CloudFailure> { Harness().issuer.requireCurrent(proof, target, operation) }
    }
    @Test fun copiedOrForgedObservationCannotMintProof() = runTest {
        val h = Harness(); val observation = h.observe()
        assertFailsWith<CloudFailure> { h.issuer.proofFor(observation.copy()) }
    }
    @Test fun kvReadAndR2OrDnsPermissionsNeverGrantKvWrites() = runTest {
        for (group in listOf("reader", "r2", "dns")) {
            val h = Harness(); h.policies = listOf(policy(group))
            assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        }
    }
    @Test fun explicitAllAccountsResourceStillRequiresTheSelectedAccountOwnerRead() = runTest {
        val h = Harness(); h.policies = listOf(policy(resource = "$scope.*")); h.proof()
        h.owner = "foreign"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun matchingDenyForeignAccountAndUnknownResourceAreNotGranted() = runTest {
        val h = Harness()
        for (policies in listOf(listOf(policy(), policy(effect = "deny")), listOf(policy(resource = "$scope.${"c".repeat(32)}")), listOf(policy(resource = "unknown.resource")), listOf(policy(value = buildJsonObject { put("unknown", "*") })))) {
            h.policies = policies; assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        }
    }
    @Test fun conditionsCatalogAmbiguityAndInactiveTokensFailClosed() = runTest {
        val h = Harness(); h.condition = buildJsonObject { put("request_ip", buildJsonObject { put("in", JsonArray(listOf(JsonPrimitive("127.0.0.1")))) }) }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        h.condition = null; h.status = "disabled"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        h.status = "active"; h.rows = h.rows + h.rows.first()
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }
    @Test fun revisionGenerationClockRollbackExpiryAndFailedRefreshRevokeExistingProofs() = runTest {
        for (invalidate in listOf<(Harness) -> Unit>({ it.revision++ }, { it.issuer.invalidate(connection.id) }, { it.now-- }, { it.now += 30000 })) {
            val h = Harness(); val proof = h.proof(); invalidate(h)
            assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, operation) }
        }
        val h = Harness(); val proof = h.proof(); h.failPath = "/user/tokens/verify"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, target, operation) }
    }
    @Test fun unknownTokenKindIsProbedButAmbiguousOrInterruptedKindIsNeverGuessed() = runTest {
        val h = Harness(TokenKind.UNKNOWN); h.proof()
        h.accountAvailable = true
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        val failed = Harness(TokenKind.UNKNOWN); failed.failPath = "/accounts/${account.accountId}/tokens/verify"
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, failed.observe().decision)
    }
    @Test fun invalidatedObserverOrCrossAccountCannotAcquireLaterProof() = runTest {
        val h = Harness(); val observer = h.client(); h.issuer.invalidate(connection.id)
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, observer.observe(target, operation, true).decision)
        assertFailsWith<CloudFailure> { h.observe(target.copy(account = account.copy(accountId = "foreign"))) }
    }
    @Test fun exactSevenOperationsAndAccountCreateTargetAreExplicitlyBound() = runTest {
        val h = Harness()
        for (suffix in listOf("create-a-namespace", "rename-a-namespace", "remove-a-namespace", "write-key-value-pair-with-metadata", "delete-key-value-pair", "write-multiple-key-value-pairs", "delete-multiple-key-value-pairs")) {
            val t = if (suffix == "create-a-namespace") target.copy(namespaceId = null) else target
            assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe(t, "workers-kv-namespace-$suffix").decision)
        }
        for (op in listOf("r2-put-object", "workers-kv-namespace-get-multiple-key-value-pairs", "workers-kv-namespace-delete-multiple-key-value-pairs-deprecated")) {
            assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe(op = op).decision)
        }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe(target.copy(namespaceId = null)).decision)
    }
    @Test fun allSevenProductionWritesRefreshAuthorityThenRunTheFinalGuardOnce() = runTest {
        val h = Harness(); var guards = 0; val s = h.session { guards++ }
        val ns = KvNamespaceRef(account, target.namespaceId!!); val key = KvKeyTarget(ns, "key")
        s.createNamespace(account, "fixture"); s.renameNamespace(ns, "new"); s.deleteNamespace(ns)
        s.putValue(key, KvValueInput(KvMemoryContent(byteArrayOf()), KvExpiry.Never, null)); s.deleteValue(key)
        s.bulkWrite(ns, KvBulkJson.prepareWrite(KvMemoryContent("""[{"key":"key","value":""}]""".encodeToByteArray()), 1))
        s.bulkDelete(ns, listOf("key"))
        assertEquals(7, guards); assertEquals(7, h.calls.count { it.method != "GET" })
        assertEquals(7, h.calls.count { it.path == "/user/tokens/verify" })
    }
    @Test fun invalidationInsideFinalProductGuardIsCheckedAgainBeforeWire() = runTest {
        val h = Harness(); val ns = KvNamespaceRef(account, target.namespaceId!!)
        assertFailsWith<CloudFailure> { h.session { h.issuer.invalidate(connection.id) }.deleteValue(KvKeyTarget(ns, "key")) }
        assertEquals(0, h.calls.count { it.method != "GET" })
        val denied = Harness(); denied.policies = listOf(policy("reader"))
        assertFailsWith<CloudFailure> { denied.session().deleteNamespace(ns) }
        assertEquals(0, denied.calls.count { it.method != "GET" })
    }
}
