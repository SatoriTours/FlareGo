package com.flarego.core

import com.flarego.core.application.operations.*
import com.flarego.core.application.zones.ZoneController
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlinx.serialization.json.*

class ZoneManagementTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val zone = ZoneScope(account, "zone-one")
    private val hash = FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private fun row(owner: String = account.accountId, id: String = zone.zoneId, name: String = "example.com",
        paused: Boolean = false, status: String = "pending", type: String = "full", servers: String = "[]") =
        """{"id":"$id","account":{"id":"$owner"},"name":"$name","status":"$status","paused":$paused,"type":"$type","name_servers":$servers,"original_registrar":"Example Registrar","future":{"preserve":true}}"""
    private fun ok(result: String, info: String? = null) = HttpResponse(200,
        """{"success":true,"errors":[],"messages":[],"result":$result${info?.let { ",\"result_info\":$it" } ?: ""}}""")
    private fun client(send: suspend (HttpRequest) -> HttpResponse) = CloudflareZoneClient(connection, CloudflareGateway(HttpTransport(send)))
    private class Resolver : OperationContextResolver {
        var live: LiveOperationContext? = null
        var valid = true
        override fun current() = live
        override fun liveConnections() = listOfNotNull(live?.connection)
        override fun isLive(scope: CloudScope, credentialRevision: Long?) = valid &&
            (credentialRevision == null || credentialRevision == live?.credentialRevision)
    }
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
    }
    private inner class Harness(scope: TestScope) {
        private val appScope = scope.backgroundScope
        val resolver = Resolver()
        var now = 1_000L
        var ids = 0
        val clock = OperationClock { now }
        val plans = ChangePlans(resolver, clock, OperationIds { "zone-${++ids}" })
        val store = Store()
        val coordinator = OperationCoordinator(plans, store, resolver, clock, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler))
        val requests = mutableListOf<HttpRequest>()
        var readRow = row()
        var listing = "[]"
        var listInfo: String? = """{"total_pages":1,"total_count":0,"per_page":50}"""
        var onRead: (() -> Unit)? = null
        var onSession: (() -> Unit)? = null
        var readStatus: Int? = null
        var write: suspend (HttpRequest) -> HttpResponse = { r ->
            when (r.method) {
                "POST" -> ok(row())
                "PATCH" -> ok(row(paused = true))
                else -> ok("""{"id":"zone-one"}""")
            }
        }
        val api = client { r ->
            requests += r
            if (r.method == "GET") {
                if (r.path.startsWith("/zones?")) ok(listing, listInfo)
                else { onRead?.invoke(); readStatus?.let { HttpResponse(it, "private-read-marker") } ?: ok(readRow) }
            } else write(r)
        }
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller = ZoneController(resolver, plans, coordinator, clock, hash, store, dispatcher) { onSession?.invoke(); api }
        fun select(action: ZoneAction, revision: Long = 1) {
            resolver.live = LiveOperationContext(connection, action.scope, revision, controller.requestFingerprint(action))
        }
        suspend fun prepare(action: ZoneAction): ChangePlan { select(action); return controller.prepare(action) }
        fun confirm(plan: ChangePlan) = plans.confirm(plan.id, plan.scope, plan.credentialRevision, plan.requestFingerprint, now)
        fun restored(): ZoneController {
            val newPlans = ChangePlans(resolver, clock, OperationIds { "restored-${++ids}" })
            val newCoordinator = OperationCoordinator(newPlans, store, resolver, clock, appScope, dispatcher)
            return ZoneController(resolver, newPlans, newCoordinator, clock, hash, store, dispatcher) { onSession?.invoke(); api }
        }
        val writes get() = requests.filter { it.method != "GET" }
    }

    @Test fun listIsAccountFilteredOneRequestedPageAndPreservesZoneState() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val api = client { requests += it; ok("[${row(paused = true, status = "active", servers = "[\"a.ns.example\",\"b.ns.example\"]")}]") }
        val page = api.list(account, PageRequest(5, PageToken.Number(2)))
        val item = page.items.single()
        assertEquals(zone, item.ref); assertEquals("active", item.status); assertTrue(item.paused)
        assertEquals(listOf("a.ns.example", "b.ns.example"), item.nameservers)
        assertEquals("Example Registrar", item.raw["original_registrar"]?.jsonPrimitive?.content)
        assertTrue(item.raw["future"]!!.jsonObject["preserve"]!!.jsonPrimitive.boolean)
        assertNull(page.total); assertNull(page.next)
        assertTrue(requests.single().path.contains("account.id=account-one"))
        assertTrue(requests.single().path.contains("page=2&per_page=5"))
    }

    @Test fun missingNameserversStayEmptyAndZoneActiveDoesNotClaimDomainRegistration() = runTest {
        val item = client { ok(row(status = "active").replace(",\"name_servers\":[]", "")) }.get(zone)
        assertEquals("active", item.status); assertTrue(item.nameservers.isEmpty())
        assertFalse(item.raw.containsKey("registered"))
    }

    @Test fun get404And403RemainDistinctSafeFailures() = runTest {
        for ((status, code) in listOf(404 to CloudFailureCode.NOT_FOUND, 403 to CloudFailureCode.PERMISSION_REQUIRED)) {
            val failure = assertFailsWith<CloudFailure> { client { HttpResponse(status, "private-provider-marker") }.get(zone) }
            assertEquals(code, failure.code); assertFalse(failure.toString().contains("private-provider-marker"))
        }
    }

    @Test fun ownershipRequiresReturnedAccountAndExactZoneId() = runTest {
        for (bad in listOf(row(owner = "other"), row(id = "other"), row().replace("\"account\":{\"id\":\"account-one\"},", ""))) {
            assertFailsWith<CloudFailure> { client { ok(bad) }.get(zone) }
            var writes = 0
            val api = client { if (it.method != "GET") writes++; ok(bad) }
            assertFailsWith<CloudFailure> { api.delete(zone) }
            assertEquals(0, writes)
        }
        assertFailsWith<CloudFailure> { client { ok("[${row(owner = "other")}]") }.list(account, PageRequest()) }
    }

    @Test fun foreignLocalScopeAndInvalidPagingCauseNoIo() = runTest {
        var calls = 0
        val api = client { calls++; ok(row()) }
        for (foreign in listOf(AccountScope("foreign", account.accountId), AccountScope("local", "foreign"))) {
            assertFailsWith<CloudFailure> { api.list(foreign, PageRequest()) }
            assertFailsWith<CloudFailure> { api.get(ZoneScope(foreign, "zone-one")) }
            assertFailsWith<CloudFailure> { api.create(foreign, ZoneCreateInput("example.com", "full")) }
        }
        for (page in listOf(PageRequest(4), PageRequest(51), PageRequest(token = PageToken.Cursor("x")))) {
            assertFailsWith<CloudFailure> { api.list(account, page) }
        }
        assertEquals(0, calls)
    }

    @Test fun createUsesSelectedAccountAndOnlySchemaFields() = runTest {
        var body: JsonObject? = null
        val result = client { body = Json.parseToJsonElement(it.body!!).jsonObject; ok(row()) }
            .create(account, ZoneCreateInput("example.com", "full"))
        assertEquals(account.accountId, body!!["account"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(setOf("account", "name", "type"), body.keys)
        assertEquals("pending", result.status); assertTrue(result.nameservers.isEmpty())
    }

    @Test fun editSendsExactlyOneChangedPropertyAndRetainsUnknownMetadata() = runTest {
        var patch: JsonObject? = null
        val result = client { r ->
            if (r.method == "GET") ok(row()) else { patch = Json.parseToJsonElement(r.body!!).jsonObject; ok(row(paused = true)) }
        }.edit(zone, ZoneEditInput(paused = true))
        assertEquals(buildJsonObject { put("paused", true) }, patch)
        assertTrue(result.paused); assertTrue(result.raw.containsKey("future"))
    }

    @Test fun typeAndVanityEditUseOfficialFieldsOnly() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val api = client { r ->
            if (r.method == "GET") ok(row()) else {
                val body = Json.parseToJsonElement(r.body!!).jsonObject; bodies += body
                ok(if (body.containsKey("type")) row(type = "partial") else
                    row().dropLast(1) + ",\"vanity_name_servers\":[\"ns1.example.com\"]}")
            }
        }
        api.edit(zone, ZoneEditInput(type = "partial"))
        api.edit(zone, ZoneEditInput(vanityNameservers = listOf("ns1.example.com")))
        assertEquals(listOf(setOf("type"), setOf("vanity_name_servers")), bodies.map { it.keys })
    }

    @Test fun invalidTypesNamesAndMultiFieldPatchesHaveZeroIo() = runTest {
        var calls = 0
        val api = client { calls++; ok(row()) }
        for (type in listOf("future", "FULL", "")) {
            assertFailsWith<CloudFailure> { api.create(account, ZoneCreateInput("example.com", type)) }
            assertFailsWith<CloudFailure> { api.edit(zone, ZoneEditInput(type = type)) }
        }
        for (name in listOf("", "example", "https://example.com", " example.com", "a".repeat(64) + ".com")) {
            assertFailsWith<CloudFailure> { api.create(account, ZoneCreateInput(name, "full")) }
        }
        assertFailsWith<CloudFailure> { api.edit(zone, ZoneEditInput()) }
        assertFailsWith<CloudFailure> { api.edit(zone, ZoneEditInput(paused = true, type = "partial")) }
        assertFailsWith<CloudFailure> { api.edit(zone, ZoneEditInput(vanityNameservers = listOf("https://bad"))) }
        assertEquals(0, calls)
    }

    @Test fun allFourOfficialZoneTypesAreAdmitted() = runTest {
        for (type in listOf("full", "partial", "secondary", "internal")) {
            assertEquals(type, client { ok(row(type = type)) }.create(account, ZoneCreateInput("example.com", type)).type)
        }
    }

    @Test fun activationUsesOnlyOfficialPutAndNoBody() = runTest {
        val requests = mutableListOf<HttpRequest>()
        client { r -> requests += r; if (r.method == "GET") ok(row()) else ok("""{"id":"zone-one"}""") }.activationCheck(zone)
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        assertEquals("/zones/zone-one/activation_check", requests.last().path)
        assertSame(HttpBody.Empty, requests.last().payload)
    }

    @Test fun deleteUsesExactZoneAndValidatesReturnedIdentity() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val api = client { r -> requests += r; if (r.method == "GET") ok(row()) else ok("""{"id":"zone-one"}""") }
        api.delete(zone)
        assertEquals(listOf("GET", "DELETE"), requests.map { it.method })
        assertEquals("/zones/zone-one", requests.last().path)
        assertFailsWith<CloudFailure> { client { if (it.method == "GET") ok(row()) else ok("""{"id":"other"}""") }.delete(zone) }
    }

    @Test fun createRegistrationIsNarrowlyAccountBoundWithoutWeakeningOtherTemplates() {
        val registration = ActionPolicies.registry.registration("zones-post")!!
        assertEquals(ActionScope.ACCOUNT, registration.scope)
        assertEquals(ActionSemantics.WRITE, registration.policy.semantics)
        assertFalse(registration.policy.retry.enabled)
        assertEquals(RiskLevel.DESTRUCTIVE, ActionPolicies.policy("zones-0-delete")!!.risk)
        assertEquals(ActionScope.USER, ActionPolicies.registry.registration("zones-get")!!.scope)
        for ((key, method, path) in listOf(Triple("arbitrary", "POST", "/zones"), Triple("zones-post", "GET", "/zones"),
            Triple("zones-post", "POST", "/user/test"), Triple("zones-post", "POST", "/accounts"))) {
            assertFailsWith<CloudFailure> { ActionRegistration(ActionPolicy(key, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON,
                RiskLevel.CHANGE, ReadRetryPolicy()), method, path, ActionScope.ACCOUNT) }
        }
        assertFailsWith<CloudFailure> { ActionRegistration(ActionPolicy("zones-post", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON,
            RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)), "POST", "/zones", ActionScope.ACCOUNT) }
    }

    @Test fun createPlanRequiresIndependentCurrentInputAndAccountConfirmation() = runTest {
        val h = Harness(this)
        val action = ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))
        h.select(action)
        h.resolver.live = h.resolver.live!!.copy(requestFingerprint = "a".repeat(64))
        assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        val plan = h.prepare(action)
        assertEquals(account, plan.scope); assertEquals("zones-post", plan.actionKey)
        assertFailsWith<CloudFailure> { h.plans.confirm(plan.id, UserScope("local", "user"), 1, plan.requestFingerprint, h.now) }
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(action, h.confirm(plan)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun deleteRequiresFullExactDomainAndChangingInputInvalidatesOldConfirmation() = runTest {
        val h = Harness(this)
        for (name in listOf("", "example", "other.com", "EXAMPLE.COM", "example.com ")) {
            val bad = ZoneAction.Delete(zone, "example.com", name)
            h.select(bad); assertFailsWith<CloudFailure> { h.controller.prepare(bad) }
        }
        val action = ZoneAction.Delete(zone, "example.com", "example.com")
        val plan = h.prepare(action); val confirmed = h.confirm(plan)
        val preview = h.controller.preview(plan.id)
        assertEquals("example.com", preview.domain); assertEquals(zone, preview.scope)
        assertTrue(preview.impact.contains("删除")); assertTrue(preview.concurrencyWarning.contains("原子"))
        val changed = action.copy(typedName = "other.com")
        h.select(changed)
        assertFailsWith<CloudFailure> { h.controller.submit(changed, confirmed) }
        assertEquals(0, h.writes.size)
    }

    @Test fun deleteDomainMustAlsoMatchProviderEvidence() = runTest {
        val h = Harness(this)
        val action = ZoneAction.Delete(zone, "forged.com", "forged.com")
        h.select(action); assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        assertEquals(0, h.writes.size)
    }

    @Test fun pausePreviewIncludesDomainScopeAndImpactAndOnlyOneWriteIsSent() = runTest {
        val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
        val plan = h.prepare(action); val confirmed = h.confirm(plan)
        val preview = h.controller.preview(plan.id)
        assertEquals("example.com", preview.domain); assertEquals(zone, preview.scope)
        assertTrue(preview.impact.contains("安全")); assertTrue(preview.impact.contains("性能"))
        val first = h.controller.submit(action, confirmed)
        assertEquals(TaskState.SUCCEEDED, first.state)
        assertEquals(first, h.controller.submit(action, confirmed)); assertEquals(1, h.writes.size)
    }

    @Test fun switchedAccountOrCredentialOrExpiredPlanCannotSend() = runTest {
        for (mutation in 0..2) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
            val plan = h.prepare(action); val confirmed = h.confirm(plan)
            when (mutation) {
                0 -> h.resolver.live = h.resolver.live!!.copy(scope = ZoneScope(AccountScope("local", "other"), "zone-one"))
                1 -> h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2)
                2 -> h.now = plan.expiresAtMs
            }
            assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed) }
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun changingSelectionDuringSessionAcquisitionOrOwnershipReadCannotSend() = runTest {
        for (atSession in listOf(true, false)) {
            val h = Harness(this); val action = ZoneAction.Delete(zone, "example.com", "example.com")
            val plan = h.prepare(action); val confirmed = h.confirm(plan)
            val mutate = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
            if (atSession) h.onSession = mutate else h.onRead = mutate
            val result = runCatching { h.controller.submit(action, confirmed) }.getOrNull()
            assertEquals(0, h.writes.size)
            assertTrue(result == null || result.state != TaskState.SUCCEEDED)
        }
    }

    @Test fun changedCloudStateOrAccountOwnershipAtFinalReadRefusesWrite() = runTest {
        for (changed in listOf(row(paused = true), row(owner = "other"), row().replace("\"preserve\":true", "\"preserve\":false"))) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
            val plan = h.prepare(action); val confirmed = h.confirm(plan)
            h.readRow = changed
            val result = runCatching { h.controller.submit(action, confirmed) }.getOrNull()
            assertEquals(0, h.writes.size)
            assertTrue(result == null || result.state != TaskState.SUCCEEDED)
        }
    }

    @Test fun pendingActivationRemainsAcceptedAndNeverClaimsPropagationCompletion() = runTest {
        val h = Harness(this); val action = ZoneAction.ActivationCheck(zone)
        val plan = h.prepare(action)
        val task = h.controller.submit(action, h.confirm(plan))
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals("PUT", h.writes.single().method)
        assertFalse(task.safeDetail.contains("传播完成"))
        assertTrue(h.controller.preview(plan.id).impact.contains("检查"))
    }

    @Test fun activationForNonPendingZoneIsRefused() = runTest {
        val h = Harness(this); h.readRow = row(status = "active")
        val action = ZoneAction.ActivationCheck(zone)
        h.select(action); assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        assertEquals(0, h.writes.size)
    }

    @Test fun createTimeoutOnlyReconcilesSameAccountNameWithoutAutomaticReplay() = runTest {
        val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))
        val plan = h.prepare(action); val confirmed = h.confirm(plan)
        h.write = { throw IllegalStateException("private-transport-marker") }
        val task = h.controller.submit(action, confirmed)
        assertEquals(TaskState.UNKNOWN, task.state); assertEquals(1, h.writes.size)
        h.listing = "[${row()}]"; h.listInfo = """{"total_pages":1,"total_count":1,"per_page":50}"""
        val reconciled = h.controller.reconcile(task.id)
        assertEquals(TaskState.SUCCEEDED, reconciled.state)
        assertEquals(1, h.writes.size)
        assertTrue(h.requests.filter { it.method == "GET" }.all { "account.id=account-one" in it.path })
        assertFalse(task.toString().contains("private-transport-marker"))
    }

    @Test fun createReconciliationWithNoMatchWrongTypeOrAmbiguousNamesStaysUnknown() = runTest {
        for ((listing, total) in listOf("[]" to 0, "[${row(type = "partial")}]" to 1, "[${row()},${row(id = "second")} ]" to 2)) {
            val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))
            val plan = h.prepare(action); h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, h.confirm(plan))
            h.listing = listing
            h.listInfo = """{"total_pages":1,"total_count":$total,"per_page":50}"""
            assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun existingZoneBlocksCreateAndIncompleteListCannotProveAbsence() = runTest {
        val action = ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))
        for ((listing, info) in listOf("[${row()}]" to """{"total_pages":1}""", "[]" to null)) {
            val h = Harness(this); h.listing = listing; h.listInfo = info
            h.select(action); assertFailsWith<CloudFailure> { h.controller.prepare(action) }
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun write429AndMalformedSuccessAreUnknownAndNeverRetried() = runTest {
        for (response in listOf(HttpResponse(429, "private"), HttpResponse(200, "not json"), ok("null"),
            HttpResponse(200, """{"success":false,"result":null}"""), ok("""{"id":"other"}"""))) {
            val h = Harness(this); val action = ZoneAction.Delete(zone, "example.com", "example.com")
            val plan = h.prepare(action); h.write = { response }
            assertEquals(TaskState.UNKNOWN, h.controller.submit(action, h.confirm(plan)).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun acceptedWriteDoesNotBecomeSucceeded() = runTest {
        val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
        val plan = h.prepare(action)
        h.write = { HttpResponse(202, ok(row(paused = true)).body) }
        assertEquals(TaskState.ACCEPTED, h.controller.submit(action, h.confirm(plan)).state)
    }

    @Test fun immutableFrozenVanityInputRejectsLaterMutation() = runTest {
        val h = Harness(this); val names = mutableListOf("ns1.example.com")
        val action = ZoneAction.Edit(zone, ZoneEditInput(vanityNameservers = names))
        val plan = h.prepare(action); val confirmed = h.confirm(plan)
        names += "ns2.example.com"
        h.select(action)
        assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed) }
        assertEquals(0, h.writes.size)
    }

    @Test fun unchangedPausedOrTypeCannotProduceAnExecutablePatchPlan() = runTest {
        for (input in listOf(ZoneEditInput(paused = false), ZoneEditInput(type = "full"))) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, input)
            h.select(action)
            assertFailsWith<CloudFailure> { h.controller.prepare(action) }
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun unknownWriteBlocksNewPlansAndAlreadyPreparedSecondConfirmation() = runTest {
        val h = Harness(this); val action = ZoneAction.Delete(zone, "example.com", "example.com")
        val first = h.prepare(action); val confirmedFirst = h.confirm(first)
        val second = h.prepare(action); val confirmedSecond = h.confirm(second)
        h.write = { HttpResponse(500, "private") }
        assertEquals(TaskState.UNKNOWN, h.controller.submit(action, confirmedFirst).state)
        assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        val result = runCatching { h.controller.submit(action, confirmedSecond) }.getOrNull()
        assertTrue(result == null || result.state != TaskState.SUCCEEDED)
        assertEquals(1, h.writes.size)
    }

    @Test fun unknownCreateBlocksRebuildingUntilReadOnlyReconciliation() = runTest {
        val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))
        val plan = h.prepare(action)
        h.write = { throw IllegalStateException("timeout") }
        val task = h.controller.submit(action, h.confirm(plan))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        assertEquals(1, h.writes.size)
    }

    @Test fun restoredControllerCannotIgnorePersistedUnknownZoneWrite() = runTest {
        val h = Harness(this); val action = ZoneAction.Delete(zone, "example.com", "example.com")
        val plan = h.prepare(action); h.write = { HttpResponse(500, "private") }
        assertEquals(TaskState.UNKNOWN, h.controller.submit(action, h.confirm(plan)).state)
        val restored = ZoneController(h.resolver, h.plans, h.coordinator, h.clock, hash, h.store, h.dispatcher) { h.api }
        assertFailsWith<CloudFailure> { restored.prepare(action) }
        assertEquals(1, h.writes.size)
    }

    @Test fun uppercaseCreationFreezesAsciiDomainWithConsistentIndependentFingerprint() = runTest {
        val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("Example.COM", "full"))
        val plan = h.prepare(action)
        assertEquals(h.controller.requestFingerprint(ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))), plan.requestFingerprint)
        assertEquals("example.com", h.controller.preview(plan.id).domain)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(action, h.confirm(plan)).state)
        assertEquals("example.com", Json.parseToJsonElement(h.writes.single().body!!).jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(account.accountId, Json.parseToJsonElement(h.writes.single().body!!).jsonObject["account"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun uppercaseCreationTimeoutReconcilesLowercaseProviderName() = runTest {
        val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("Example.COM", "full"))
        val plan = h.prepare(action); h.write = { throw IllegalStateException("timeout") }
        val task = h.controller.submit(action, h.confirm(plan))
        assertEquals(TaskState.UNKNOWN, task.state)
        h.listing = "[${row(name = "example.com")}]"
        h.listInfo = """{"total_pages":1,"total_count":1,"per_page":50}"""
        assertEquals(TaskState.SUCCEEDED, h.controller.reconcile(task.id).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun restoredControllerReconcilesUnknownCreationWithoutRestoringPayloadOrConfirmation() = runTest {
        val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("Example.COM", "full"))
        val plan = h.prepare(action); val confirmed = h.confirm(plan)
        h.write = { throw IllegalStateException("timeout") }
        val task = h.controller.submit(action, confirmed)
        h.listing = "[${row()}]"; h.listInfo = """{"total_pages":1,"total_count":1,"per_page":50}"""
        // The new live context is real current state, not reconstructed from task metadata.
        h.resolver.live = LiveOperationContext(connection, account, 2, "b".repeat(64))
        val restored = h.restored()
        assertEquals(TaskState.SUCCEEDED, restored.reconcile(task.id).state)
        assertFailsWith<CloudFailure> { restored.preview(task.id) }
        assertFailsWith<CloudFailure> { restored.submit(action, confirmed) }
        assertEquals(1, h.writes.size)
        assertTrue(h.requests.filter { it.method == "GET" }.all { "account.id=account-one" in it.path })
    }

    @Test fun restoredControllerReconcilesEverySingleFieldPatchAndUnblocksFreshPreview() = runTest {
        val cases = listOf(
            ZoneEditInput(paused = true) to row(paused = true),
            ZoneEditInput(type = "partial") to row(type = "partial"),
            ZoneEditInput(vanityNameservers = listOf("ns1.example.com")) to
                (row().dropLast(1) + ",\"vanity_name_servers\":[\"ns1.example.com\"]}"),
        )
        for ((input, observed) in cases) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, input)
            val plan = h.prepare(action); val confirmed = h.confirm(plan)
            h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, confirmed)
            h.readRow = observed
            val restored = h.restored()
            assertEquals(TaskState.SUCCEEDED, restored.reconcile(task.id).state)
            assertEquals(1, h.writes.size)
            assertFailsWith<CloudFailure> { restored.submit(action, confirmed) }
            val fresh = ZoneAction.Edit(zone, ZoneEditInput(paused = input.paused != true))
            h.select(fresh)
            assertEquals(zone, restored.prepare(fresh).scope)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun restoredControllerReconcilesDeleteOnlyFromTrustedTarget404() = runTest {
        for (status in listOf(200, 403, 404, 500)) {
            val h = Harness(this); val action = ZoneAction.Delete(zone, "example.com", "example.com")
            val plan = h.prepare(action); val confirmed = h.confirm(plan)
            h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, confirmed)
            h.readStatus = status.takeUnless { it == 200 }
            val restored = h.restored()
            assertEquals(if (status == 404) TaskState.SUCCEEDED else TaskState.UNKNOWN, restored.reconcile(task.id).state)
            assertEquals("/zones/zone-one", h.requests.last().path)
            assertEquals("GET", h.requests.last().method)
            assertEquals(1, h.writes.size)
            assertFailsWith<CloudFailure> { restored.submit(action, confirmed) }
        }
    }

    @Test fun restoredCreateWithWrongTypeAccountAmbiguousNameOrIncompleteListStaysUnknown() = runTest {
        val cases = listOf(
            "[${row(type = "partial")}]" to 1,
            "[${row(owner = "foreign")}]" to 1,
            "[${row(name = "different.com")}]" to 1,
            "[${row()},${row(id = "another")}]" to 2,
            "[${row()}]" to 0,
        )
        for ((listing, total) in cases) {
            val h = Harness(this); val action = ZoneAction.Create(account, ZoneCreateInput("example.com", "full"))
            val plan = h.prepare(action); h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, h.confirm(plan))
            h.listing = listing; h.listInfo = """{"total_pages":1,"total_count":$total,"per_page":50}"""
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun restoredPatchNeedsRealDesiredFieldAndOwnerAndNeverTreats404AsApplied() = runTest {
        for (change in 0..3) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
            val plan = h.prepare(action); h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, h.confirm(plan))
            when (change) {
                0 -> h.readRow = row(paused = false)
                1 -> h.readRow = row(owner = "foreign", paused = true)
                2 -> h.readStatus = 404
                3 -> h.readStatus = 403
            }
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun restoredReconciliationCannotUseForeignSelectionOrChangedCredentialDuringRead() = runTest {
        for (changeAtRead in listOf(false, true)) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
            val plan = h.prepare(action); h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, h.confirm(plan)); h.readRow = row(paused = true)
            if (changeAtRead) h.onRead = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
            else h.resolver.live = h.resolver.live!!.copy(scope = ZoneScope(AccountScope("local", "foreign"), "zone-one"))
            assertFailsWith<CloudFailure> { h.restored().reconcile(task.id) }
            assertEquals(TaskState.UNKNOWN, h.store.get(task.id)!!.state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun restoredReconciliationRefusesUnknownActionWrongReferenceAndMissingDesiredHash() = runTest {
        for (change in 0..3) {
            val h = Harness(this); val action = ZoneAction.Edit(zone, ZoneEditInput(paused = true))
            val plan = h.prepare(action); h.write = { HttpResponse(500, "private") }
            val task = h.controller.submit(action, h.confirm(plan)); h.readRow = row(paused = true)
            h.store.put(when (change) {
                0 -> task.copy(actionKey = "future-write")
                1 -> task.copy(resourceRef = ResourceRef(zone, "dns_record", zone.zoneId))
                2 -> task.copy(resourceRef = ResourceRef(zone, "zone", "foreign-zone"))
                else -> task.copy(desiredHash = null)
            })
            val count = h.requests.size
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(count, h.requests.size)
            assertEquals(1, h.writes.size)
        }
    }
}
