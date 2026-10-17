package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import kotlin.test.*
import com.flarego.core.application.operations.*
import com.flarego.core.application.dns.DnssecController
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.dns.CloudflareDnssecClient
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class DnssecTest {
    @Test fun fourFixedOperationsHaveExactMethodsPathsScopesAndReadOnlyRetries() {
        val expected = listOf(
            Triple("dnssec-dnssec-details", "GET", "/zones/{zone_id}/dnssec"),
            Triple("dnssec-edit-dnssec-status", "PATCH", "/zones/{zone_id}/dnssec"),
            Triple("dnssec-delete-dnssec-records", "DELETE", "/zones/{zone_id}/dnssec"),
            Triple("dnssec-list-dnssec-zsks", "GET", "/zones/{zone_id}/dnssec/zsk"),
        )
        val actual = dnssecRegistrations().associateBy { it.policy.key }
        assertEquals(expected.map { it.first }.toSet(), actual.keys)
        for ((id, method, path) in expected) {
            val r = actual.getValue(id)
            assertEquals(method, r.method); assertEquals(path, r.pathTemplate)
            assertEquals(ActionScope.ZONE, r.scope); assertEquals(ResponseKind.ENVELOPE_JSON, r.policy.responseKind)
            assertEquals(method == "GET", r.policy.retry.enabled)
            assertEquals(if (method == "GET") ActionSemantics.READ else ActionSemantics.WRITE, r.policy.semantics)
            assertEquals(if (method == "GET") RiskLevel.READ_ONLY else RiskLevel.DESTRUCTIVE, r.policy.risk)
        }
    }

    @Test fun dnssecHelperIsConnectedToTheProductionGatewayRegistry() {
        assertEquals(4, dnssecRegistrations().size)
        for (r in dnssecRegistrations()) assertEquals(r, ActionPolicies.registry.registration(r.policy.key))
    }
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val zone = ZoneScope(AccountScope("local", "account-one"), "zone-one")
    private val hash = FingerprintPort { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun ok(result: JsonElement) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
    }.toString())
    private fun baseline(status: String = "disabled") = buildJsonObject {
        put("status", status); put("ds", JsonNull); put("digest", JsonNull); put("algorithm", JsonNull); put("key_tag", JsonNull)
        put("dnssec_multi_signer", false); put("dnssec_presigned", false); put("dnssec_use_nsec3", false)
        put("future", buildJsonObject { put("keep", true) })
    }
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
    }
    private class Resolver : OperationContextResolver {
        var live: LiveOperationContext? = null; var valid = true
        override fun current() = live
        override fun liveConnections() = listOfNotNull(live?.connection)
        override fun isLive(scope: CloudScope, credentialRevision: Long?) = valid && (credentialRevision == null || live?.credentialRevision == credentialRevision)
    }
    private inner class Harness(val test: TestScope) {
        val resolver = Resolver(); val store = Store(); var now = 1_000L; var sequence = 0
        val clock = OperationClock { now }; val dispatcher = StandardTestDispatcher(test.testScheduler)
        val plans = ChangePlans(resolver, clock, OperationIds { "dnssec-${++sequence}" })
        val coordinator = OperationCoordinator(plans, store, resolver, clock, test.backgroundScope, dispatcher)
        val requests = mutableListOf<HttpRequest>(); val rows = mutableMapOf<String, JsonObject>()
        var permission: Boolean? = true; var nsec3Available: Boolean? = true; var ownerType = "secondary"; var ownerAccount: String? = "account-one"; var ownerId: String? = null
        var onOwnerRead: (() -> Unit)? = null; var onDetailsRead: (() -> Unit)? = null; var onSession: (() -> Unit)? = null
        var throwWrite = false; var applyWrite = true; var postStatus: String? = null
        var writeResponse: HttpResponse? = null; var readResponse: HttpResponse? = null
        var keyResult: JsonElement = json("""[{"Name":"zsk-real","Location":"vault","Tag":"publish","DNSKEY":{"Algorithm":13,"Flags":256,"Protocol":3,"PublicKey":"PUBLIC","Hdr":{"Name":"example.com.","Class":1,"Rrtype":48,"Ttl":3600,"Rdlength":0}},"SigningKey":{"pubkey":"PUBLIC-SIGNING","privkey":"private-key-marker","kek":"encryption-key-marker"},"future":"keep"}]""")
        val authority = object : DnssecAuthority { override fun mayWrite(scope: ZoneScope, mutation: DnssecMutation) = permission
            override fun nsec3Available(scope: ZoneScope) = nsec3Available
        }
        val transport = HttpTransport { r ->
            requests += r
            val id = r.path.split('/').getOrElse(2) { "" }
            if (r.method == "GET") {
                if (r.path.endsWith("/zsk")) ok(keyResult)
                else if (r.path.endsWith("/dnssec")) {
                    onDetailsRead?.invoke(); readResponse ?: ok(rows[id] ?: baseline())
                } else {
                    onOwnerRead?.invoke()
                    ok(buildJsonObject {
                        put("id", ownerId ?: id); put("name", if (id == "zone-two") "second.example" else "example.com")
                        put("type", ownerType); put("status", "active"); put("paused", false)
                        ownerAccount?.let { put("account", buildJsonObject { put("id", it) }) }
                    })
                }
            } else {
                if (applyWrite) {
                    val old = rows[id] ?: baseline()
                    rows[id] = if (r.method == "DELETE") baseline(postStatus ?: "disabled") else {
                        val fields = json(r.body!!).jsonObject
                        JsonObject(old + fields + (postStatus?.let { mapOf("status" to JsonPrimitive(it)) } ?: emptyMap()))
                    }
                }
                if (throwWrite) throw IllegalStateException("private-transport-marker")
                writeResponse ?: if (r.method == "DELETE") ok(JsonPrimitive("")) else ok(rows[id] ?: baseline())
            }
        }
        val api = CloudflareDnssecClient(connection, CloudflareGateway(transport), authority)
        val controller = DnssecController(resolver, plans, coordinator, clock, hash, store, dispatcher) { onSession?.invoke(); api }
        fun select(action: DnssecAction) { resolver.live = LiveOperationContext(connection, action.scope, 1, controller.requestFingerprint(action)) }
        suspend fun prepare(action: DnssecAction): ChangePlan { select(action); return controller.prepare(action) }
        fun confirm(p: ChangePlan) = plans.confirm(p.id, p.scope, p.credentialRevision, p.requestFingerprint, now)
        fun restored(): DnssecController {
            val p = ChangePlans(resolver, clock, OperationIds { "restored-${++sequence}" })
            val c = OperationCoordinator(p, store, resolver, clock, test.backgroundScope, dispatcher)
            return DnssecController(resolver, p, c, clock, hash, store, dispatcher) { api }
        }
        val writes get() = requests.filter { it.method != "GET" }
    }

    @Test fun nullableDsMaterialAndUnknownMetadataAreReadWithoutExamplesOrCrossZoneMixing() = runTest {
        val h = Harness(this)
        val absent = h.api.get(zone)
        assertEquals("disabled", absent.status); assertNull(absent.ds); assertNull(absent.digest); assertNull(absent.algorithm); assertNull(absent.keyTag)
        assertEquals(json("""{"keep":true}"""), absent.raw["future"])
        h.rows["zone-one"] = JsonObject(baseline("pending") + mapOf("ds" to JsonPrimitive("actual DS"), "digest" to JsonPrimitive("actual digest"), "algorithm" to JsonPrimitive("13"), "key_tag" to JsonPrimitive(42)))
        val pending = h.api.get(zone)
        assertEquals("pending", pending.status); assertEquals("actual DS", pending.ds); assertEquals(42.0, pending.keyTag)
        assertNull(h.api.get(zone.copy(zoneId = "zone-two")).ds)
        h.rows["zone-one"] = buildJsonObject { put("future_status", true) }
        assertNull(h.api.get(zone).status)
    }

    @Test fun invalidKnownResponseTypesAndPartialEnvelopesAreNeverInventedIntoDetails() = runTest {
        for (bad in listOf(
            ok(json("""{"status":3}""")), ok(json("""{"ds":13}""")), ok(json("""{"key_tag":"42"}""")),
            ok(json("""{"dnssec_presigned":null}""")), ok(JsonNull),
            HttpResponse(200, """{"success":true,"errors":[{"code":1001,"message":"private-provider-marker"}],"messages":[],"result":{}}"""),
            HttpResponse(200, """{"success":"true","errors":[],"messages":[],"result":{}}"""),
            HttpResponse(200, """{"success":true,"errors":[],"result":{}}"""),
        )) {
            val h = Harness(this); h.readResponse = bad
            val failure = assertFailsWith<CloudFailure> { h.api.get(zone) }
            assertFalse(failure.toString().contains("private-provider-marker"))
        }
        val future = Harness(this); future.rows["zone-one"] = baseline("future-provider-state")
        assertEquals("future-provider-state", future.api.get(zone).status)
    }

    @Test fun zskUsesActualNestedCapitalizedShapeAndNeverRetainsPrivateSigningMaterialOrPagination() = runTest {
        val h = Harness(this); val key = h.api.zsks(zone).single()
        assertEquals(zone, key.scope); assertEquals("zsk-real", key.name); assertEquals("vault", key.location); assertEquals("publish", key.tag)
        assertEquals(13L, key.dnskey?.algorithm); assertEquals(256L, key.dnskey?.flags); assertEquals(3L, key.dnskey?.protocol)
        assertEquals("example.com.", key.dnskey?.header?.name); assertEquals(3600L, key.dnskey?.header?.ttl)
        assertEquals("PUBLIC-SIGNING", key.signingPublicKey); assertEquals(JsonPrimitive("keep"), key.raw["future"])
        assertFalse(key.toString().contains("private-key-marker")); assertFalse(key.toString().contains("encryption-key-marker"))
        assertEquals(listOf("/zones/zone-one", "/zones/zone-one/dnssec/zsk"), h.requests.map { it.path })
        assertTrue(h.requests.none { '?' in it.path }); assertEquals(0, h.writes.size)
        h.keyResult = JsonArray(emptyList()); assertTrue(h.api.zsks(zone).isEmpty())
    }

    @Test fun zskCollectionAndNestedKnownFieldsRejectMalformedShapes() = runTest {
        for (value in listOf("{}", "[null]", "[{\"DNSKEY\":null}]", "[{\"DNSKEY\":{\"Flags\":1.5}}]", "[{\"SigningKey\":{\"pubkey\":3}}]")) {
            val h = Harness(this); h.keyResult = json(value)
            assertFailsWith<CloudFailure> { h.api.zsks(zone) }
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun threeBooleanInputsAcceptBothValuesAndFalseIsExplicitInPatch() = runTest {
        for (field in listOf("dnssec_multi_signer", "dnssec_presigned", "dnssec_use_nsec3")) for (value in listOf(false, true)) {
            val h = Harness(this)
            h.rows["zone-one"] = JsonObject(baseline("active") + (field to JsonPrimitive(!value)))
            val input = DnssecUpdateInput.fromFields(buildJsonObject { put(field, value) })
            h.api.update(zone, input)
            assertEquals(buildJsonObject { put(field, value) }, json(h.writes.single().body!!))
        }
        val all = DnssecUpdateInput("disabled", false, false, false).fields()
        assertEquals(setOf("status", "dnssec_multi_signer", "dnssec_presigned", "dnssec_use_nsec3"), all.keys)
    }

    @Test fun unknownEmptyNestedAndWrongInputsFailBeforeIo() = runTest {
        for (value in listOf("{}", "{\"status\":\"pending\"}", "{\"status\":null}", "{\"ds\":\"forged\"}", "{\"dnssec_presigned\":\"false\"}", "{\"dnssec_multi_signer\":{\"enabled\":true}}")) {
            assertFailsWith<CloudFailure> { DnssecUpdateInput.fromFields(json(value).jsonObject) }
        }
        val h = Harness(this)
        assertFailsWith<CloudFailure> { h.api.update(zone, DnssecUpdateInput()) }
        assertFailsWith<CloudFailure> { h.api.update(zone, DnssecUpdateInput(status = "pending")) }
        assertEquals(0, h.requests.size)
    }

    @Test fun unchangedFieldsAndUnknownBeforeValuesRefuseWrites() = runTest {
        val h = Harness(this)
        assertFailsWith<CloudFailure> { h.api.update(zone, DnssecUpdateInput(status = "disabled")) }
        h.rows["zone-one"] = buildJsonObject { put("status", "active") }
        assertFailsWith<CloudFailure> { h.api.update(zone, DnssecUpdateInput(multiSigner = false)) }
        assertEquals(0, h.writes.size)
    }

    @Test fun localScopeAndFreshProviderZoneAccountAndIdEvidenceAreAllRequired() = runTest {
        for (mode in 0..3) {
            val h = Harness(this)
            when (mode) { 0 -> h.ownerAccount = "other"; 1 -> h.ownerAccount = null; 2 -> h.ownerId = "other-zone" }
            val target = if (mode == 3) zone.copy(account = AccountScope("other-local", "account-one")) else zone
            assertFailsWith<CloudFailure> { h.api.update(target, DnssecUpdateInput(status = "active")) }
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun defaultUnknownAndDeniedWritePermissionCannotBeInferredFromSuccessfulReads() = runTest {
        for (permission in listOf<Boolean?>(null, false)) {
            val h = Harness(this); h.permission = permission
            assertEquals("disabled", h.api.get(zone).status)
            assertFailsWith<CloudFailure> { h.api.update(zone, DnssecUpdateInput(status = "active")) }
            assertEquals(0, h.writes.size)
        }
        val h = Harness(this)
        val defaultClient = CloudflareDnssecClient(connection, CloudflareGateway(h.transport))
        assertFailsWith<CloudFailure> { defaultClient.update(zone, DnssecUpdateInput(status = "active")) }
        assertEquals(0, h.writes.size)
    }

    @Test fun enableCompletesOnlyProviderConfigurationAndPreviewStatesRegistrarResponsibility() = runTest {
        val h = Harness(this); val action = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
        val p = h.prepare(action); val preview = h.controller.preview(p.id)
        assertTrue(preview.risks.isEmpty()); assertTrue(preview.registrarWarning.contains("未知")); assertTrue(preview.registrarWarning.contains("DS"))
        assertTrue(preview.impact.contains("全网")); assertTrue(preview.concurrencyWarning.contains("CAS"))
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(action, h.confirm(p)).state)
        assertEquals(1, h.writes.size); assertEquals("PATCH", h.writes.single().method)
        assertFalse(h.store.rows.values.any { it.toString().contains("future") })
    }

    @Test fun pendingAndPendingDisabledAreAcceptedUntilMatchingTerminalReadEvidence() = runTest {
        for ((before, desired, pending, risk) in listOf(
            listOf("disabled", "active", "pending", null), listOf("active", "disabled", "pending-disabled", DnssecRisk.DISABLE)
        )) {
            val h = Harness(this); h.rows["zone-one"] = baseline(before as String)
            val action = DnssecAction.Update(zone, DnssecUpdateInput(status = desired as String))
            val p = h.prepare(action); val token = (risk as? DnssecRisk)?.let { h.controller.acknowledgeRisk(p.id, it) }
            h.postStatus = pending as String
            val task = h.controller.submit(action, h.confirm(p), setOfNotNull(token))
            assertEquals(TaskState.ACCEPTED, task.state)
            assertEquals(TaskState.ACCEPTED, h.restored().reconcile(task.id).state)
            h.rows["zone-one"] = baseline(desired)
            assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun disableAndDeleteAreDifferentPlansAndRequireIndependentSpecificRiskTokens() = runTest {
        for (deleting in listOf(false, true)) {
            val h = Harness(this); h.rows["zone-one"] = baseline("active")
            val action = if (deleting) DnssecAction.Delete(zone) else DnssecAction.Update(zone, DnssecUpdateInput(status = "disabled"))
            val risk = if (deleting) DnssecRisk.DELETE else DnssecRisk.DISABLE
            val p = h.prepare(action); val confirmed = h.confirm(p)
            assertEquals(setOf(risk), h.controller.preview(p.id).risks)
            assertTrue(h.controller.preview(p.id).registrarWarning.contains("验证失败"))
            assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed) }
            assertFailsWith<CloudFailure> { h.controller.submit(action, ConfirmedPlan(p, "forged"), setOf(h.controller.acknowledgeRisk(p.id, risk))) }
            assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed, setOf(DnssecRiskAcknowledgement(p.id, risk))) }
            assertEquals(0, h.writes.size)
            val token = h.controller.acknowledgeRisk(p.id, risk)
            assertEquals(TaskState.SUCCEEDED, h.controller.submit(action, confirmed, setOf(token)).state)
            assertEquals(if (deleting) "DELETE" else "PATCH", h.writes.single().method)
        }
    }

    @Test fun eachSigningModeFlagNeedsItsOwnRiskAndTokensCannotCrossPlanOrIssuer() = runTest {
        val h = Harness(this)
        val action = DnssecAction.Update(zone, DnssecUpdateInput(multiSigner = true, presigned = true, useNsec3 = true))
        val p = h.prepare(action); val confirmed = h.confirm(p)
        val expected = setOf(DnssecRisk.CHANGE_MULTI_SIGNER, DnssecRisk.CHANGE_PRESIGNED, DnssecRisk.CHANGE_NSEC3)
        assertEquals(expected, h.controller.preview(p.id).risks)
        val tokens = expected.map { h.controller.acknowledgeRisk(p.id, it) }.toSet()
        assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed, tokens.drop(1).toSet()) }
        val other = h.prepare(action); val otherConfirmed = h.confirm(other)
        assertFailsWith<CloudFailure> { h.controller.submit(action, otherConfirmed, tokens) }
        assertFailsWith<CloudFailure> { h.restored().submit(action, confirmed, tokens) }
        assertEquals(0, h.writes.size)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(action, confirmed, tokens).state)
    }

    @Test fun independentInputContextCredentialAndExpiryCannotBeBackfilledFromPlan() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); val action = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val p = h.prepare(action); val confirmed = h.confirm(p)
            val live = h.resolver.live!!
            when (mode) {
                0 -> h.resolver.live = live.copy(requestFingerprint = "0".repeat(64))
                1 -> h.resolver.live = live.copy(scope = zone.copy(zoneId = "other-zone"))
                2 -> h.resolver.live = live.copy(credentialRevision = 2)
                3 -> h.now += ChangePlans.TTL_MS
                else -> h.resolver.valid = false
            }
            assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed) }
            assertEquals(0, h.writes.size)
        }
        val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
        val p = h.prepare(a)
        assertFailsWith<CloudFailure> { h.controller.submit(a.copy(input = DnssecUpdateInput(status = "disabled")), h.confirm(p)) }
        assertEquals(0, h.writes.size)
    }

    @Test fun typedPreSendRechecksFreshContextOwnerPermissionAndBeforeSnapshotWithoutWriting() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val p = h.prepare(a); val c = h.confirm(p)
            when (mode) {
                0 -> h.onSession = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
                1 -> h.onDetailsRead = { h.resolver.live = h.resolver.live!!.copy(requestFingerprint = "0".repeat(64)) }
                2 -> h.ownerAccount = "different"
                3 -> h.permission = false
                else -> h.rows["zone-one"] = JsonObject(baseline() + ("future" to JsonPrimitive("external change")))
            }
            val result = runCatching { h.controller.submit(a, c) }
            assertTrue(result.isFailure || result.getOrNull()?.state != TaskState.SUCCEEDED)
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun malformedPartialWrongTargetAndTimeoutWritesStayUnknownWithoutReplay() = runTest {
        for (mode in 0..7) {
            val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val p = h.prepare(a); val c = h.confirm(p)
            when (mode) {
                0 -> h.throwWrite = true
                1 -> h.writeResponse = HttpResponse(200, "private-invalid-json")
                2 -> h.writeResponse = HttpResponse(200, """{"success":true,"errors":[{"code":1001,"message":"private-marker"}],"messages":[],"result":{"status":"active"}}""")
                3 -> h.writeResponse = ok(json("""{"status":"active","zone_id":"wrong-zone"}"""))
                4 -> h.writeResponse = ok(json("{}"))
                5 -> h.writeResponse = HttpResponse(429, "private-rate-limit")
                6 -> h.applyWrite = false
                else -> h.writeResponse = ok(json("""{"status":"error"}"""))
            }
            val task = h.controller.submit(a, c)
            assertEquals(TaskState.UNKNOWN, task.state)
            assertEquals(1, h.writes.size)
            assertFalse(task.toString().contains("private-"))
            h.throwWrite = false
            runCatching { h.controller.submit(a, c) }
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun contextChangeOrReadbackFailureAfterSendCannotDeclareSuccess() = runTest {
        for (mode in 0..1) {
            val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val p = h.prepare(a); val c = h.confirm(p)
            h.onDetailsRead = {
                if (h.writes.isNotEmpty()) {
                    if (mode == 0) h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2)
                    else h.readResponse = HttpResponse(403, "private-denied")
                }
            }
            assertEquals(TaskState.UNKNOWN, h.controller.submit(a, c).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun unknownUpdateReconcilesAfterControllerRebuildWithoutPayloadOrConfirmation() = runTest {
        for (flags in listOf(false, true)) {
            val h = Harness(this)
            val a = DnssecAction.Update(zone, if (flags) DnssecUpdateInput(multiSigner = true, useNsec3 = true) else DnssecUpdateInput(status = "active"))
            val p = h.prepare(a); val c = h.confirm(p)
            val tokens = if (flags) setOf(h.controller.acknowledgeRisk(p.id, DnssecRisk.CHANGE_MULTI_SIGNER), h.controller.acknowledgeRisk(p.id, DnssecRisk.CHANGE_NSEC3)) else emptySet()
            h.throwWrite = true
            val task = h.controller.submit(a, c, tokens)
            assertEquals(TaskState.UNKNOWN, task.state)
            h.throwWrite = false; h.requests.clear()
            assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
            assertEquals(0, h.writes.size)
            assertFailsWith<CloudFailure> { h.restored().submit(a, c, tokens) }
        }
    }

    @Test fun unknownHashOwnerPermissionAndUnrecognizableTargetRemainUnknownOnReadOnlyRecovery() = runTest {
        for (mode in 0..6) {
            val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val p = h.prepare(a); h.throwWrite = true
            val task = h.controller.submit(a, h.confirm(p))
            h.throwWrite = false; h.requests.clear()
            when (mode) {
                0 -> h.rows["zone-one"] = baseline()
                1 -> h.ownerAccount = "other-account"
                2 -> h.readResponse = HttpResponse(403, "private")
                3 -> h.store.put(task.copy(resourceRef = null))
                4 -> h.store.put(task.copy(resourceRef = ResourceRef(zone, "future_dnssec", "future")))
                5 -> h.store.put(task.copy(desiredHash = "0".repeat(64)))
                else -> h.store.put(task.copy(resourceRef = ResourceRef(zone, "dnssec", "other-zone")))
            }
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun missingUnknownAndValidRefsBlockNewPrepareAndPrepreparedSecondConfirmationButNotOtherZones() = runTest {
        val counts = mutableListOf<Int>()
        for (mode in 0..2) for (preprepared in listOf(false, true)) {
            val h = Harness(this)
            val first = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val second = DnssecAction.Update(zone, DnssecUpdateInput(multiSigner = true))
            val p1 = h.prepare(first); val c1 = h.confirm(p1)
            val p2 = if (preprepared) h.prepare(second) else null
            val c2 = p2?.let { h.confirm(it) }
            val risk = p2?.let { h.controller.acknowledgeRisk(it.id, DnssecRisk.CHANGE_MULTI_SIGNER) }
            h.select(first); h.throwWrite = true
            val unknown = h.controller.submit(first, c1)
            if (mode == 1) h.store.put(unknown.copy(resourceRef = null))
            if (mode == 2) h.store.put(unknown.copy(resourceRef = ResourceRef(zone, "future", "unknown")))
            h.throwWrite = false; h.requests.clear(); h.select(second)
            val result = runCatching {
                if (c2 != null) h.controller.submit(second, c2, setOfNotNull(risk))
                else { val p = h.controller.prepare(second); h.controller.submit(second, h.confirm(p), setOf(h.controller.acknowledgeRisk(p.id, DnssecRisk.CHANGE_MULTI_SIGNER))) }
            }
            counts += h.writes.size
            assertTrue(result.isFailure || result.getOrNull()?.state != TaskState.SUCCEEDED)
            assertEquals(TaskState.UNKNOWN, h.store.get(unknown.id)!!.state)
            val unrelated = first.copy(scope = zone.copy(zoneId = "zone-two"))
            val otherPlan = h.prepare(unrelated)
            assertEquals(TaskState.SUCCEEDED, h.controller.submit(unrelated, h.confirm(otherPlan)).state)
        }
        assertEquals(List(6) { 0 }, counts)
    }

    @Test fun lostDeleteReceiptCannotBeProvenByDisabledOr404AndNeverRestoresExecutableDelete() = runTest {
        for (notFound in listOf(false, true)) {
            val h = Harness(this); h.rows["zone-one"] = baseline("active")
            val a = DnssecAction.Delete(zone); val p = h.prepare(a); val c = h.confirm(p)
            val token = h.controller.acknowledgeRisk(p.id, DnssecRisk.DELETE)
            h.throwWrite = true
            val task = h.controller.submit(a, c, setOf(token))
            h.throwWrite = false; h.requests.clear()
            if (notFound) h.readResponse = HttpResponse(404, "private-not-found")
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(0, h.writes.size)
            assertFailsWith<CloudFailure> { h.restored().submit(a, c, setOf(token)) }
        }
    }

    @Test fun deleteRequiresOfficialStringReceiptAndDisabledReadbackInsteadOfGuessingFrom404() = runTest {
        for (mode in 0..3) {
            val h = Harness(this); h.rows["zone-one"] = baseline("active")
            when (mode) {
                0 -> h.writeResponse = ok(json("{}"))
                1 -> h.postStatus = "active"
                2 -> h.postStatus = "pending-disabled"
                else -> h.readResponse = null
            }
            val receipt = h.api.write(DnssecAction.Delete(zone), {}, {})
            when (mode) { 2 -> assertIs<ExecutionReceipt.Accepted>(receipt); 3 -> assertIs<ExecutionReceipt.Completed>(receipt); else -> assertIs<ExecutionReceipt.Unknown>(receipt) }
            assertEquals(1, h.writes.size)
        }
    }
    @Test fun unspecifiedPartial2xxResponsesCannotCompleteOrAcknowledgeDnssecWrites() = runTest {
        val states = mutableListOf<TaskState>()
        for (status in listOf(201, 206, 207)) {
            val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
            val p = h.prepare(a)
            h.writeResponse = ok(baseline("active")).copy(status = status)
            states += h.controller.submit(a, h.confirm(p)).state
            assertEquals(1, h.writes.size)
        }
        assertEquals(List(3) { TaskState.UNKNOWN }, states)
    }

    @Test fun acknowledgedDeleteCanFinishAfterPendingButCompletedDeleteIsNotDowngradedByReconcile() = runTest {
        val h = Harness(this); h.rows["zone-one"] = baseline("active")
        val a = DnssecAction.Delete(zone); val p = h.prepare(a)
        h.postStatus = "pending-disabled"
        val task = h.controller.submit(a, h.confirm(p), setOf(h.controller.acknowledgeRisk(p.id, DnssecRisk.DELETE)))
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(TaskState.ACCEPTED, h.restored().reconcile(task.id).state)
        h.rows["zone-one"] = baseline("disabled"); h.requests.clear()
        val completed = h.restored().reconcile(task.id)
        assertEquals(TaskState.SUCCEEDED, completed.state)
        h.requests.clear()
        assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
        assertEquals(0, h.writes.size)
    }

    @Test fun pendingWrongHashOrWrongDirectionCannotBeReportedAsAcknowledgedTarget() = runTest {
        val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
        val p = h.prepare(a); h.postStatus = "pending"
        val task = h.controller.submit(a, h.confirm(p))
        h.store.put(task.copy(desiredHash = "0".repeat(64))); h.requests.clear()
        assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
        assertEquals(0, h.writes.size)
        val other = Harness(this); val p2 = other.prepare(a); other.postStatus = "pending-disabled"
        assertEquals(TaskState.UNKNOWN, other.controller.submit(a, other.confirm(p2)).state)
        assertEquals(1, other.writes.size)
    }

    @Test fun pendingFlagChangeNeverBecomesSucceededWithoutTerminalProviderStatus() = runTest {
        val h = Harness(this); h.rows["zone-one"] = baseline("pending")
        val a = DnssecAction.Update(zone, DnssecUpdateInput(multiSigner = true)); val p = h.prepare(a)
        val task = h.controller.submit(a, h.confirm(p), setOf(h.controller.acknowledgeRisk(p.id, DnssecRisk.CHANGE_MULTI_SIGNER)))
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(TaskState.ACCEPTED, h.restored().reconcile(task.id).state)
        h.rows["zone-one"] = JsonObject(baseline("active") + ("dnssec_multi_signer" to JsonPrimitive(true)))
        h.requests.clear()
        assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
        assertEquals(0, h.writes.size)
    }

    @Test fun providerReturnedDsNeverSuppressesUnknownExternalDsWarningOrDisableRisk() = runTest {
        val h = Harness(this)
        h.rows["zone-one"] = JsonObject(baseline("active") + ("ds" to JsonPrimitive("REAL PROVIDER DS")))
        val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "disabled")); val p = h.prepare(a)
        val preview = h.controller.preview(p.id)
        assertEquals(setOf(DnssecRisk.DISABLE), preview.risks)
        assertTrue(preview.registrarWarning.contains("未知")); assertTrue(preview.registrarWarning.contains("TTL"))
        assertFailsWith<CloudFailure> { h.controller.submit(a, h.confirm(p)) }
        assertEquals(0, h.writes.size)
        assertTrue(h.requests.all { it.path.startsWith("/zones/") })
    }

    @Test fun changedActualInputRejectsRiskTokenAndReadFailuresDoNotPoisonOtherZones() = runTest {
        val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(multiSigner = true))
        val p = h.prepare(a); val c = h.confirm(p); val token = h.controller.acknowledgeRisk(p.id, DnssecRisk.CHANGE_MULTI_SIGNER)
        val changed = a.copy(input = DnssecUpdateInput(presigned = true)); h.select(changed)
        assertFailsWith<CloudFailure> { h.controller.submit(changed, c, setOf(token)) }
        assertEquals(0, h.writes.size)
        h.readResponse = HttpResponse(403, "private-marker")
        assertFailsWith<CloudFailure> { h.api.get(zone) }
        h.readResponse = null
        val other = DnssecAction.Update(zone.copy(zoneId = "zone-two"), DnssecUpdateInput(status = "active"))
        val p2 = h.prepare(other)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(other, h.confirm(p2)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun partialOrUnexpectedReadStatusCannotServeAsFreshProviderEvidence() = runTest {
        for (status in listOf(201, 206, 207)) {
            val h = Harness(this); h.readResponse = ok(baseline()).copy(status = status)
            assertFailsWith<CloudFailure> { h.api.get(zone) }
            assertEquals(0, h.writes.size)
        }
        val gateway = CloudflareGateway(HttpTransport { r ->
            if (r.path.endsWith("/zsk")) ok(JsonArray(emptyList())).copy(status = 206)
            else ok(buildJsonObject { put("id", "zone-one"); put("name", "example.com"); put("account", buildJsonObject { put("id", "account-one") }) })
        })
        assertFailsWith<CloudFailure> { CloudflareDnssecClient(connection, gateway).zsks(zone) }
    }

    @Test fun legitimateRiskTokenFromDifferentIssuerWithIdenticalPlanFieldsIsRefused() = runTest {
        val one = Harness(this); val two = Harness(this)
        val action = DnssecAction.Update(zone, DnssecUpdateInput(multiSigner = true))
        val p1 = one.prepare(action); val c1 = one.confirm(p1)
        val p2 = two.prepare(action)
        assertEquals(p1, p2)
        val foreignToken = two.controller.acknowledgeRisk(p2.id, DnssecRisk.CHANGE_MULTI_SIGNER)
        assertFailsWith<CloudFailure> { one.controller.submit(action, c1, setOf(foreignToken)) }
        assertEquals(0, one.writes.size)
        val ownToken = one.controller.acknowledgeRisk(p1.id, DnssecRisk.CHANGE_MULTI_SIGNER)
        assertEquals(TaskState.SUCCEEDED, one.controller.submit(action, c1, setOf(ownToken)).state)
    }

    @Test fun readOnlyReconciliationRechecksActualContextAfterProviderReads() = runTest {
        val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(status = "active"))
        val p = h.prepare(a); h.throwWrite = true
        val unknown = h.controller.submit(a, h.confirm(p))
        h.throwWrite = false; h.requests.clear()
        h.onDetailsRead = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
        assertFailsWith<CloudFailure> { h.restored().reconcile(unknown.id) }
        assertEquals(TaskState.UNKNOWN, h.store.get(unknown.id)!!.state)
        assertEquals(0, h.writes.size)
    }

    @Test fun fixedSchemaZoneIdentifierMaxLengthIsEnforcedBeforeAnyIo() = runTest {
        val counts = mutableListOf<Int>(); val refused = mutableListOf<Boolean>()
        val long = zone.copy(zoneId = "z".repeat(33))
        for (mode in 0..3) {
            val h = Harness(this); h.rows[long.zoneId] = baseline("active")
            refused += runCatching {
                when (mode) {
                    0 -> h.api.get(long)
                    1 -> h.api.update(long, DnssecUpdateInput(status = "disabled"))
                    2 -> h.api.delete(long)
                    else -> h.api.zsks(long)
                }
            }.isFailure
            counts += h.requests.size
        }
        assertEquals(List(4) { 0 }, counts)
        assertTrue(refused.all { it })
        val h = Harness(this)
        assertEquals("z".repeat(32), h.api.get(zone.copy(zoneId = "z".repeat(32))).scope.zoneId)
        assertFailsWith<CloudFailure> { h.controller.requestFingerprint(DnssecAction.Delete(long)) }
    }

    @Test fun changingEachFlagToFalseStillNeedsSpecificExplicitRiskAcknowledgement() = runTest {
        val cases = listOf(
            Triple("dnssec_multi_signer", DnssecUpdateInput(multiSigner = false), DnssecRisk.CHANGE_MULTI_SIGNER),
            Triple("dnssec_presigned", DnssecUpdateInput(presigned = false), DnssecRisk.CHANGE_PRESIGNED),
            Triple("dnssec_use_nsec3", DnssecUpdateInput(useNsec3 = false), DnssecRisk.CHANGE_NSEC3),
        )
        for ((field, input, risk) in cases) {
            val h = Harness(this); h.rows["zone-one"] = JsonObject(baseline("active") + (field to JsonPrimitive(true)))
            val a = DnssecAction.Update(zone, input); val p = h.prepare(a); val confirmed = h.confirm(p)
            assertEquals(setOf(risk), h.controller.preview(p.id).risks)
            assertFailsWith<CloudFailure> { h.controller.submit(a, confirmed) }
            assertEquals(0, h.writes.size)
            assertEquals(TaskState.SUCCEEDED, h.controller.submit(a, confirmed, setOf(h.controller.acknowledgeRisk(p.id, risk))).state)
            assertEquals(JsonPrimitive(false), json(h.writes.single().body!!).jsonObject[field])
        }
    }

    @Test fun enablingNsec3NeedsVerifiedEnterpriseAvailabilityBeyondTokenWritePermission() = runTest {
        val counts = mutableListOf<Int>()
        for (available in listOf<Boolean?>(null, false)) {
            val h = Harness(this); h.nsec3Available = available
            val result = runCatching { h.api.update(zone, DnssecUpdateInput(useNsec3 = true)) }
            counts += h.writes.size
            if (result.isFailure) assertIs<CloudFailure>(result.exceptionOrNull())
        }
        assertEquals(listOf(0, 0), counts)
    }

    @Test fun enablingPresignedRequiresProviderProvenSecondaryZone() = runTest {
        val counts = mutableListOf<Int>()
        for (type in listOf("full", "partial", "unknown")) {
            val h = Harness(this); h.ownerType = type
            runCatching { h.api.update(zone, DnssecUpdateInput(presigned = true)) }
            counts += h.writes.size
        }
        assertEquals(listOf(0, 0, 0), counts)
    }

    @Test fun losingNsec3AvailabilityAfterPreviewRefusesFinalSend() = runTest {
        val h = Harness(this); val a = DnssecAction.Update(zone, DnssecUpdateInput(useNsec3 = true))
        val p = h.prepare(a); val c = h.confirm(p)
        val token = h.controller.acknowledgeRisk(p.id, DnssecRisk.CHANGE_NSEC3)
        h.nsec3Available = null
        val result = runCatching { h.controller.submit(a, c, setOf(token)) }
        assertEquals(0, h.writes.size)
        assertTrue(result.isFailure || result.getOrNull()?.state != TaskState.SUCCEEDED)
    }

    @Test fun enablingPresignedPreviewExplainsAllRecordsBecomeDnsOnlyAndFeatureLoss() = runTest {
        val h = Harness(this)
        val p = h.prepare(DnssecAction.Update(zone, DnssecUpdateInput(presigned = true)))
        val preview = h.controller.preview(p.id)
        assertEquals(setOf(DnssecRisk.CHANGE_PRESIGNED), preview.risks)
        for (term in listOf("所有记录", "DNS-only", "Secondary DNS Overrides", "Load Balancing")) {
            assertTrue(preview.impact.contains(term), "Missing concrete pre-signed impact: $term")
        }
        assertEquals(0, h.writes.size)
    }

    @Test fun presignedPreviewRequiresPrimarySignaturesAndDsWithoutInventingExternalProbes() = runTest {
        val h = Harness(this)
        val p = h.prepare(DnssecAction.Update(zone, DnssecUpdateInput(presigned = true)))
        val preview = h.controller.preview(p.id)
        val text = preview.impact + preview.registrarWarning
        for (term in listOf("DNSKEY", "RRSIG", "NSEC", "primary signer", "AXFR", "未知", "未探测")) {
            assertTrue(text.contains(term), "Missing external pre-signed prerequisite/boundary: $term")
        }
        assertEquals(0, h.writes.size)
    }
}
