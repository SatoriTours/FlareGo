package com.flarego.core

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class WriteAuthorityTest {
    private val connection = Connection("connection", "display only", "a".repeat(32))
    private val zone = ZoneScope(AccountScope(connection.id, connection.accountId), "b".repeat(32))
    private val operation = "dns-records-for-a-zone-import-dns-records"
    private val zoneResource = "com.cloudflare.api.account.zone.${zone.zoneId}"
    private val zoneScope = "com.cloudflare.api.account.zone"
    private val labels = listOf("DNS Write", "Zone DNS Settings Write", "Zone Settings Write", "Zone Write", "SSL and Certificates Write", "Cache Purge")
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun group(label: String, id: String = "synthetic-${labels.indexOf(label)}") = buildJsonObject {
        put("id", id); put("name", label); put("scopes", JsonArray(listOf(JsonPrimitive(zoneScope)))); put("is_selectable", true)
    }
    private fun policy(effect: String = "allow", groups: List<String> = listOf("synthetic-0"),
                       resources: JsonObject = buildJsonObject { put(zoneResource, "*") }) = buildJsonObject {
        put("id", "synthetic-policy"); put("effect", effect)
        put("permission_groups", JsonArray(groups.map { buildJsonObject { put("id", it); put("name", "cosmetic-sensitive-marker") } }))
        put("resources", resources)
    }
    private fun envelope(result: JsonElement, info: JsonObject? = null) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
        info?.let { put("result_info", it) }
    }.toString())
    private inner class Harness(kind: TokenKind = TokenKind.USER_OWNED, ttl: Long = 30_000) {
        var now = 1_000L
        var revision = 7L
        var tokenId = "self-token"
        var verify = buildJsonObject { put("id", tokenId); put("status", "active") }
        var details = buildJsonObject { put("id", tokenId); put("status", "active"); put("name", "sensitive-marker"); put("creator_email_at_creation", "sensitive-marker@example.test"); put("policies", JsonArray(listOf(policy()))) }
        var catalog = JsonArray(labels.map { group(it) })
        var catalogInfo: JsonObject? = null
        var ownerAccount = connection.accountId
        var userAvailable = true
        var accountAvailable = kind == TokenKind.ACCOUNT_OWNED
        var failDetails = false
        var failCatalog = false
        var rawOverride: HttpResponse? = null
        var onRead: suspend (String) -> Unit = {}
        val requests = mutableListOf<HttpRequest>()
        val issuer = TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, kind, ttl)
        val gateway = CloudflareGateway(HttpTransport { request ->
            requests += request
            check(request.method == "GET")
            val frozenCatalog = catalog
            onRead(request.path)
            rawOverride ?: when {
                request.path == "/user/tokens/verify" -> if (userAvailable) envelope(verify) else HttpResponse(401, "sensitive-marker")
                request.path == "/accounts/${connection.accountId}/tokens/verify" -> if (accountAvailable) envelope(verify) else HttpResponse(401, "sensitive-marker")
                request.path.endsWith("/permission_groups") -> if (failCatalog) HttpResponse(403, "sensitive-marker") else envelope(frozenCatalog, catalogInfo)
                request.path == "/user/tokens/$tokenId" || request.path == "/accounts/${connection.accountId}/tokens/$tokenId" -> if (failDetails) HttpResponse(403, "sensitive-marker") else envelope(details)
                request.path == "/zones/${zone.zoneId}" -> envelope(buildJsonObject { put("id", zone.zoneId); put("name", "example.com"); put("account", buildJsonObject { put("id", ownerAccount) }); put("status", "active"); put("type", "full"); put("paused", false) })
                else -> error("Unexpected synthetic read")
            }
        }, sleeper = {})
        fun client() = CloudflareWriteAuthorityClient(issuer, gateway)
        suspend fun observe(op: String = operation, force: Boolean = true) = client().observe(zone, op, force)
        suspend fun granted(op: String = operation): WriteAuthorityObservation = observe(op).also {
            assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, it.decision)
            issuer.requireCurrent(issuer.proofFor(it), zone, op)
        }
        fun policies(vararg values: JsonObject) { details = JsonObject(details + ("policies" to JsonArray(values.toList()))) }
    }

    @Test fun fourAuxiliaryReadsRouteThroughRealRegistryWithoutTokenManagementWrites() = runTest {
        val rows = listOf(
            Triple("user-api-tokens-token-details", "/user/tokens/self", ActionScope.USER),
            Triple("account-api-tokens-token-details", "/accounts/account/tokens/self", ActionScope.ACCOUNT),
            Triple("permission-groups-list-permission-groups", "/user/tokens/permission_groups", ActionScope.USER),
            Triple("account-api-tokens-list-permission-groups", "/accounts/account/tokens/permission_groups", ActionScope.ACCOUNT))
        val calls = mutableListOf<HttpRequest>()
        val gateway = CloudflareGateway(HttpTransport { calls += it; envelope(JsonObject(emptyMap())) })
        for ((key, path, scope) in rows) {
            val registration = assertNotNull(ActionPolicies.registry.registration(key))
            assertEquals(ActionSemantics.READ, registration.policy.semantics); assertEquals(RiskLevel.READ_ONLY, registration.policy.risk)
            assertEquals(ResponseKind.ENVELOPE_JSON, registration.policy.responseKind); assertEquals(scope, registration.scope)
            gateway.send(key, HttpRequest("GET", path))
            assertFailsWith<CloudFailure> { gateway.send(key, HttpRequest("POST", path)) }
        }
        assertEquals(4, calls.size)
        assertFailsWith<CloudFailure> { gateway.send("user-api-tokens-create-token", HttpRequest("POST", "/user/tokens")) }
        assertEquals(4, calls.size)
    }

    @Test fun fixedSchemaFileAndShaProveFourAuxRoutesAndExact21WriteGroupMappings() {
        val configured = System.getenv("SCHEMA_FILE")
        val file = if (configured != null) Path.of(configured) else generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(".superpowers/cloudflare/openapi.json") }.first { Files.isRegularFile(it) }
        val bytes = Files.readAllBytes(file)
        assertEquals("06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val paths = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("paths").jsonObject
        val auxiliary = listOf("/user/tokens/{token_id}" to "user-api-tokens-token-details", "/accounts/{account_id}/tokens/{token_id}" to "account-api-tokens-token-details", "/user/tokens/permission_groups" to "permission-groups-list-permission-groups", "/accounts/{account_id}/tokens/permission_groups" to "account-api-tokens-list-permission-groups")
        for ((path, id) in auxiliary) {
            val op = paths.getValue(path).jsonObject.getValue("get").jsonObject
            assertEquals(id, op.getValue("operationId").jsonPrimitive.content)
            val registration = assertNotNull(ActionPolicies.registry.registration(id)); assertEquals(path, registration.pathTemplate)
            assertEquals(if (path.startsWith("/user")) setOf("API Tokens Write", "API Tokens Read") else setOf("Account API Tokens Write", "Account API Tokens Read"), op.getValue("x-api-token-group").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
        assertEquals(21, TokenPolicyEvaluation.acceptedGroups.size)
        for ((id, groups) in TokenPolicyEvaluation.acceptedGroups) {
            val op = paths.values.asSequence().flatMap { it.jsonObject.values.asSequence() }.filterIsInstance<JsonObject>().single { it["operationId"] == JsonPrimitive(id) }
            assertEquals(op.getValue("x-api-token-group").jsonArray.map { it.jsonPrimitive.content }.toSet(), groups)
        }
    }

    @Test fun userAndAccountDetailsUseOnlyCurrentVerifiedTokenIdAndActualOwner() = runTest {
        for (kind in listOf(TokenKind.USER_OWNED, TokenKind.ACCOUNT_OWNED)) {
            val h = Harness(kind); val result = h.granted()
            val base = if (kind == TokenKind.USER_OWNED) "/user/tokens" else "/accounts/${connection.accountId}/tokens"
            assertEquals(listOf("$base/verify", "$base/self-token", "$base/permission_groups", "/zones/${zone.zoneId}"), h.requests.map { it.path })
            assertEquals(7L, result.credentialRevision); assertEquals(zone, result.scope); assertEquals(operation, result.operationId)
        }
    }

    @Test fun all21OperationsUseOnlyReviewedAcceptedGroupsAndPolicyNamesNeverGrant() = runTest {
        val h = Harness(); h.policies(policy(groups = labels.indices.map { "synthetic-$it" }))
        assertEquals(21, TokenPolicyEvaluation.acceptedGroups.size)
        for (op in TokenPolicyEvaluation.acceptedGroups.keys) h.granted(op)
        h.policies(policy(groups = listOf("unknown-id")))
        assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
    }

    @Test fun explicitDenyWinsOverAllowIncludingAlternativeAcceptedGroup() = runTest {
        val h = Harness(); h.granted(); h.policies(policy(), policy("deny"))
        assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
        h.policies(policy(groups = listOf("synthetic-0")), policy("deny", listOf("synthetic-1")))
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe("dns-settings-for-a-zone-update-dns-settings").decision)
    }

    @Test fun completeEmptyOrUnrelatedPolicyDeniesWithoutBorrowingPermission() = runTest {
        val h = Harness(); h.policies(); assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
        h.policies(policy(resources = buildJsonObject { put("com.cloudflare.api.account.zone.other", "*") }))
        assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
    }

    @Test fun threeOfficialZoneSelectorsAreSupportedAndAccountFlatDoesNotInherit() = runTest {
        val h = Harness()
        for (resources in listOf(buildJsonObject { put(zoneResource, "*") }, buildJsonObject { put("com.cloudflare.api.account.zone.*", "*") }, buildJsonObject { put("com.cloudflare.api.account.${connection.accountId}", buildJsonObject { put("com.cloudflare.api.account.zone.*", "*") }) })) {
            h.policies(policy(resources = resources)); h.granted()
        }
        h.policies(policy(resources = buildJsonObject { put("com.cloudflare.api.account.other", buildJsonObject { put("com.cloudflare.api.account.zone.*", "*") }) }))
        assertEquals(WriteAuthorityDecision.DENIED, h.observe().decision)
        h.policies(policy(resources = buildJsonObject { put("com.cloudflare.api.account.*", "*") }))
        assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
    }

    @Test fun missingPolicyFieldsOrUnknownAuthorizationSemanticsCannotBeIgnored() = runTest {
        val h = Harness(); h.granted()
        val valid = h.details
        for (bad in listOf(JsonObject(valid - "policies"), JsonObject(valid + ("policies" to JsonNull)))) {
            h.details = bad; assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        }
        for (bad in listOf(JsonObject(policy() - "effect"), JsonObject(policy() + ("condition" to obj("{\"future\":true}"))), JsonObject(policy() + ("effect" to JsonPrimitive("maybe"))), JsonObject(policy() + ("resources" to obj("{\"future.namespace.*\":\"*\"}"))))) {
            h.details = valid; h.policies(bad); assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        }
        h.details = JsonObject(valid + ("future_grant" to JsonPrimitive(true)))
        assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
    }

    @Test fun unknownDenyGroupsCannotBeFilteredOutBeforeAllow() = runTest {
        val h = Harness(); h.granted(); h.policies(policy(), policy("deny", listOf("future-group")))
        assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
    }

    @Test fun nonemptyIpOrFutureConditionsStayUnknownEvenWhenVerifyActive() = runTest {
        val h = Harness(); h.granted(); val valid = h.details
        for (condition in listOf(obj("{\"request_ip\":{\"in\":[\"192.0.2.0/24\"]}}"), obj("{\"request_ip\":{\"not_in\":[\"2001:db8::/32\"]}}"), obj("{\"request.ip\":{\"in\":[\"0.0.0.0/0\"]}}"), obj("{\"future\":true}"), JsonNull)) {
            h.details = JsonObject(valid + ("condition" to condition))
            val r = h.observe(); assertEquals(WriteAuthorityDecision.UNKNOWN, r.decision); assertEquals(WriteAuthorityReason.UNKNOWN_CONDITION, r.reason)
        }
        h.details = JsonObject(valid + ("condition" to obj("{\"request_ip\":{\"in\":[],\"not_in\":[]}}"))); h.granted()
    }

    @Test fun extraIntrospectionReadPermissionMissingNeverMeansDnsDeniedOrAvailable() = runTest {
        val h = Harness(); h.failDetails = true
        val r = h.observe(); assertEquals(WriteAuthorityDecision.UNKNOWN, r.decision)
        assertEquals(WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE, r.reason)
        assertEquals(listOf("/user/tokens/verify", "/user/tokens/self-token"), h.requests.map { it.path })
    }

    @Test fun mismatchedVerifiedIdStatusOrDatesNeverProduceProof() = runTest {
        val h = Harness(); h.granted(); val valid = h.details
        for (bad in listOf(JsonObject(valid + ("id" to JsonPrimitive("other-token"))), JsonObject(valid + ("status" to JsonPrimitive("disabled"))), JsonObject(valid + ("not_before" to JsonNull)), JsonObject(valid + ("expires_on" to JsonPrimitive("not-a-date"))))) {
            h.details = bad; assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        }
    }

    @Test fun expiryAndNotBeforeAreStrictAndCapPositiveCache() = runTest {
        val h = Harness()
        h.verify = JsonObject(h.verify + mapOf("not_before" to JsonPrimitive("1970-01-01T00:00:01Z"), "expires_on" to JsonPrimitive("1970-01-01T00:00:02Z")))
        h.details = JsonObject(h.details + mapOf("not_before" to JsonPrimitive("1970-01-01T00:00:01Z"), "expires_on" to JsonPrimitive("1970-01-01T00:00:02Z")))
        val r = h.granted(); assertEquals(2000L, r.validUntilMs)
        val proof = h.issuer.proofFor(r); h.now = 2000; assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
        h.now = 999; assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }

    @Test fun fullScopeOwnerAndUnsupportedOperationNeverReuseAnotherPermit() = runTest {
        val h = Harness(); val r = h.granted(); val proof = h.issuer.proofFor(r)
        val other = ZoneScope(AccountScope(connection.id, "other-account"), zone.zoneId)
        assertFailsWith<CloudFailure> { h.client().observe(other, operation) }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, other, operation) }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, "dnssec-edit-dnssec-status") }
        val before = h.requests.size; assertEquals(WriteAuthorityReason.UNSUPPORTED_OPERATION, h.observe("future-write").reason); assertEquals(before, h.requests.size)
        h.ownerAccount = "other-account"; assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, h.observe().decision)
    }

    @Test fun unknownKindProbesBothBranchesAndRejectsAmbiguity() = runTest {
        val h = Harness(TokenKind.UNKNOWN); h.granted()
        assertTrue(h.requests.any { it.path == "/user/tokens/verify" }); assertTrue(h.requests.any { it.path == "/accounts/${connection.accountId}/tokens/verify" })
        val both = Harness(TokenKind.UNKNOWN); both.accountAvailable = true
        val r = both.observe(); assertEquals(WriteAuthorityDecision.UNKNOWN, r.decision); assertEquals(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND, r.reason)
    }

    @Test fun catalogIsSelectableIsNotAuthorizationAndPolicyNamesAreCosmetic() = runTest {
        val h = Harness(); h.catalog = JsonArray(h.catalog.map { JsonObject(it.jsonObject + ("is_selectable" to JsonPrimitive(false))) }); h.granted()
        h.policies(JsonObject(policy() + ("permission_groups" to JsonArray(listOf(obj("{\"id\":\"synthetic-0\",\"name\":\"not DNS at all\"}")))))); h.granted()
    }

    @Test fun malformedDuplicateOrPartialCatalogNeverGrantsOrTruncates() = runTest {
        val h = Harness(); h.granted(); val valid = h.catalog
        for (bad in listOf(JsonArray(valid + valid.first()), JsonArray(valid + group("DNS Write", "different-id")), JsonArray(valid.map { JsonObject(it.jsonObject - "scopes") }), JsonArray(valid.map { JsonObject(it.jsonObject + ("scopes" to JsonArray(emptyList()))) }))) {
            h.catalog = bad; assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        }
        h.catalog = valid; h.catalogInfo = obj("{\"page\":1,\"count\":6,\"total_count\":7,\"per_page\":6}")
        assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
    }

    @Test fun catalogIdDriftTombstoneSurvivesShortClientRecreationUntilExplicitInvalidation() = runTest {
        val h = Harness(); val old = h.granted(); val proof = h.issuer.proofFor(old); val valid = h.catalog
        h.catalog = JsonArray(valid.map { if (it.jsonObject["name"] == JsonPrimitive("DNS Write")) group("DNS Write", "replacement") else it })
        assertEquals(WriteAuthorityReason.CATALOG_CHANGED, h.client().observe(zone, operation, true).reason)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        h.catalog = valid
        assertEquals(WriteAuthorityReason.CATALOG_CHANGED, h.client().observe(zone, operation, true).reason)
        h.client().invalidate(connection.id); h.granted()
    }

    @Test fun catalogSemanticScopeOrMissingBindingDriftAlsoPoisonsGeneration() = runTest {
        for (mode in listOf("rename", "scope", "missing")) {
            val h = Harness(); h.granted(); val valid = h.catalog
            h.catalog = JsonArray(when (mode) {
                "rename" -> valid.map { if (it.jsonObject["id"] == JsonPrimitive("synthetic-0")) JsonObject(it.jsonObject + ("name" to JsonPrimitive("future label"))) else it }
                "scope" -> valid.map { if (it.jsonObject["id"] == JsonPrimitive("synthetic-0")) JsonObject(it.jsonObject + ("scopes" to JsonArray(listOf(JsonPrimitive(zoneScope), JsonPrimitive("com.cloudflare.api.account"))))) else it }
                else -> valid.drop(1)
            })
            assertEquals(WriteAuthorityReason.CATALOG_CHANGED, h.observe().reason)
            h.catalog = valid; assertEquals(WriteAuthorityReason.CATALOG_CHANGED, h.client().observe(zone, operation, true).reason)
        }
    }

    @Test fun failedRefreshRevokesPositiveAndDoesNotResetFrozenCatalog() = runTest {
        val h = Harness(); val old = h.granted(); val proof = h.issuer.proofFor(old)
        h.failCatalog = true; assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        h.failCatalog = false; h.catalog = JsonArray(h.catalog.map { if (it.jsonObject["name"] == JsonPrimitive("DNS Write")) group("DNS Write", "replacement") else it })
        assertEquals(WriteAuthorityReason.CATALOG_CHANGED, h.client().observe(zone, operation, false).reason)
    }

    @Test fun changedTokenIdInSameCredentialGenerationCannotRebootstrap() = runTest {
        val h = Harness(); h.granted(); h.tokenId = "replacement-token"
        h.verify = JsonObject(h.verify + ("id" to JsonPrimitive(h.tokenId))); h.details = JsonObject(h.details + ("id" to JsonPrimitive(h.tokenId)))
        assertEquals(WriteAuthorityReason.TOKEN_ID_CHANGED, h.client().observe(zone, operation, true).reason)
        h.client().invalidate(connection.id); h.granted()
    }

    @Test fun concurrentForceRefreshSerializesBootstrapAndCannotBindTwoDifferentIds() = runTest {
        val h = Harness(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var groups = 0
        h.onRead = { if (it.endsWith("permission_groups")) { groups++; if (groups == 1) { entered.complete(Unit); release.await() } } }
        val first = async { h.client().observe(zone, operation, true) }; runCurrent(); assertTrue(entered.isCompleted)
        val second = async { h.client().observe(zone, operation, true) }; runCurrent(); assertEquals(1, groups)
        h.catalog = JsonArray(h.catalog.map { if (it.jsonObject["name"] == JsonPrimitive("DNS Write")) group("DNS Write", "replacement") else it })
        release.complete(Unit); assertEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, first.await().decision)
        assertEquals(WriteAuthorityReason.CATALOG_CHANGED, second.await().reason)
        assertEquals(2, groups)
    }

    @Test fun invalidateDuringSuspendingRefreshPreventsLateBootstrapAndOldProofResurrection() = runTest {
        val h = Harness(); val old = h.granted(); val proof = h.issuer.proofFor(old)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.onRead = { if (it.endsWith("permission_groups")) { entered.complete(Unit); release.await() } }
        val pending = async { h.client().observe(zone, operation, true) }; runCurrent(); assertTrue(entered.isCompleted)
        h.client().invalidate(connection.id); release.complete(Unit)
        assertEquals(WriteAuthorityReason.SESSION_INVALIDATED, pending.await().reason)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        h.onRead = {}; h.granted()
    }

    @Test fun credentialRevisionChangeRetiresOldIssuerAndCannotBeUndoneByShortClient() = runTest {
        val h = Harness(); val old = h.granted(); val proof = h.issuer.proofFor(old)
        h.revision++; assertEquals(WriteAuthorityReason.CREDENTIAL_CHANGED, h.client().observe(zone, operation, true).reason)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        h.revision--; assertEquals(WriteAuthorityReason.CREDENTIAL_CHANGED, h.client().observe(zone, operation, false).reason)
    }

    @Test fun positiveCacheIsBoundedAndForceRefreshReplacesProofIdentity() = runTest {
        val h = Harness(); val old = h.granted(); val proof = h.issuer.proofFor(old); val count = h.requests.size
        h.now = 30_999; assertSame(old, h.client().observe(zone, operation)); assertEquals(count, h.requests.size)
        h.now = 31_000; val fresh = h.client().observe(zone, operation); assertNotSame(old, fresh); assertTrue(h.requests.size > count)
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        h.granted(); h.failDetails = true; assertEquals(WriteAuthorityDecision.UNKNOWN, h.client().observe(zone, operation, true).decision)
        assertEquals(WriteAuthorityDecision.UNKNOWN, h.client().observe(zone, operation, false).decision)
    }

    @Test fun copiedPublicObservationAndOtherIssuerCannotCreateOrConsumeProof() = runTest {
        val h = Harness(); val real = h.granted(); val proof = h.issuer.proofFor(real)
        assertFailsWith<CloudFailure> { h.issuer.proofFor(real.copy()) }
        assertFailsWith<CloudFailure> { h.issuer.proofFor(WriteAuthorityObservation(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, WriteAuthorityReason.TOKEN_POLICY_GRANTED, zone, operation, 7, h.now, h.now + 30_000)) }
        assertFailsWith<CloudFailure> { Harness().issuer.requireCurrent(proof, zone, operation) }
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(WriteAuthorityProof(), zone, operation) }
    }

    @Test fun clockRollbackOrProofExpiringDuringOwnedReadCannotGrant() = runTest {
        val h = Harness(); val old = h.granted(); val proof = h.issuer.proofFor(old); h.now--
        assertFailsWith<CloudFailure> { h.issuer.requireCurrent(proof, zone, operation) }
        assertEquals(WriteAuthorityReason.CLOCK_ROLLBACK, h.client().observe(zone, operation, false).reason)
        val slow = Harness(); slow.onRead = { if (it.startsWith("/zones/")) slow.now += 30_000 }
        assertNotEquals(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, slow.observe().decision)
    }

    @Test fun rawSensitiveMetadataNeverAppearsInPublicObservationOrException() = runTest {
        val h = Harness(); val granted = h.granted(); assertFalse(granted.toString().contains("sensitive-marker"))
        h.rawOverride = HttpResponse(403, "sensitive-marker token-body")
        val unknown = h.observe(); assertFalse(unknown.toString().contains("sensitive-marker"))
        val failure = assertFailsWith<CloudFailure> { h.issuer.proofFor(unknown) }
        assertFalse(failure.toString().contains("sensitive-marker")); assertNull(failure.cause)
        assertTrue(h.requests.all { it.method == "GET" && it.body == null })
    }

    @Test fun malformedEnvelopeAllListBoundsAndUnknownConditionsFailClosed() = runTest {
        val h = Harness(); h.granted()
        for (body in listOf("{}", "{\"success\":\"true\",\"errors\":[],\"messages\":[],\"result\":{}}", "{\"success\":true,\"errors\":[{\"message\":\"sensitive-marker\"}],\"messages\":[],\"result\":{}}", " ".repeat(4 * 1024 * 1024 + 1))) {
            h.rawOverride = HttpResponse(200, body); assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        }
        h.rawOverride = null; h.policies(*Array(513) { policy() }); assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
        val wide = Harness(); wide.catalog = JsonArray((1..4097).map { group("DNS Write", "synthetic-wide-$it") }); assertEquals(WriteAuthorityDecision.UNKNOWN, wide.observe().decision)
    }

    @Test fun duplicateAuthorizationKeysCannotUseLastValueToOverrideDenial() = runTest {
        val h = Harness(); h.granted()
        h.rawOverride = HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":{"id":"self-token","status":"disabled","status":"active"}}""")
        assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
    }

    @Test fun unknownKindCannotChooseReadableBranchWhenOtherVerifySucceedsButDetailsAreUnreadable() = runTest {
        val h = Harness(TokenKind.UNKNOWN); h.accountAvailable = true
        h.onRead = { path -> h.rawOverride = if (path == "/accounts/${connection.accountId}/tokens/self-token") HttpResponse(403, "sensitive-marker") else null }
        val r = h.observe()
        assertEquals(WriteAuthorityDecision.UNKNOWN, r.decision)
        assertEquals(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND, r.reason)
        assertFailsWith<CloudFailure> { h.issuer.proofFor(r) }
    }

    @Test fun knownGroupLosingZoneScopePermanentlyRevokesThisGenerationEvenAfterCatalogReturns() = runTest {
        for (changedScope in listOf("com.cloudflare.api.account", "future.resource.scope")) {
            val h = Harness(); h.granted(); val valid = h.catalog
            h.catalog = JsonArray(valid.map { if (it.jsonObject["id"] == JsonPrimitive("synthetic-0"))
                JsonObject(it.jsonObject + ("scopes" to JsonArray(listOf(JsonPrimitive(changedScope))))) else it })
            assertEquals(WriteAuthorityDecision.UNKNOWN, h.observe().decision)
            h.catalog = valid
            assertEquals(WriteAuthorityReason.CATALOG_CHANGED, h.client().observe(zone, operation, true).reason)
            h.client().invalidate(connection.id); h.granted()
        }
    }

    @Test fun invalidTtlCannotCreateAnIssuerThatExtendsMaximumPositiveCache() {
        for (ttl in listOf(0L, -1L, 30_001L)) assertFailsWith<CloudFailure> { Harness(ttl = ttl) }
    }
}
