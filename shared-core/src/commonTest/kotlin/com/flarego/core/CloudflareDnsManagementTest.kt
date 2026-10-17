package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.dns.CloudflareDnsClient
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CloudflareDnsManagementTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val zone = ZoneScope(AccountScope("local", "account-one"), "zone-one")
    private val fingerprint = FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun record(id: String = "record-one", type: String = "A", name: String = "www.example.com", content: String = "198.51.100.4", extra: String = "") =
        obj("""{"id":"$id","type":"$type","name":"$name","content":"$content","ttl":1,"proxied":false,"settings":{},"tags":[],"comment":null,"proxiable":true,"created_on":"old","modified_on":"old","meta":{}$extra}""")
    private fun owner(account: String = "account-one", id: String = "zone-one") = obj("""{"id":"$id","name":"example.com","account":{"id":"$account"},"status":"active","type":"full","paused":false}""")
    private fun ok(result: JsonElement, info: JsonObject? = null) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("result", result)
        info?.let { put("result_info", it) }
    }.toString())
    private fun input(fields: String, name: String = "www", type: String = "A") = DnsRecordInput(type, name, obj(fields))
    private val newA get() = input("""{"content":"198.51.100.4"}""")
    private val comment get() = input("""{"comment":"edited"}""")
    private class Resolver : OperationContextResolver {
        var live: LiveOperationContext? = null
        var valid = true
        override fun current() = live
        override fun liveConnections() = listOfNotNull(live?.connection)
        override fun isLive(scope: CloudScope, credentialRevision: Long?) = valid &&
            live?.scope == scope && (credentialRevision == null || live?.credentialRevision == credentialRevision)
    }
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        val summaries = mutableMapOf<String, Operation>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task); summaries[task.id] = summary }
    }
    private inner class Harness(val test: TestScope) {
        val resolver = Resolver()
        var ids = 0
        var now = 1_000L
        val clock = OperationClock { now }
        val store = Store()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val plans = ChangePlans(resolver, clock, OperationIds { "dns-${++ids}" })
        val coordinator = OperationCoordinator(plans, store, resolver, clock, test.backgroundScope, dispatcher)
        val requests = mutableListOf<HttpRequest>()
        var zoneRow = owner()
        var row: JsonObject? = record()
        var listing: List<JsonObject> = emptyList()
        var info: JsonObject? = obj("""{"total_pages":1,"total_count":0,"per_page":50}""")
        var beforeRead: (suspend (HttpRequest) -> Unit)? = null
        var onSession: (suspend () -> Unit)? = null
        var onWrite: (suspend (HttpRequest) -> HttpResponse)? = null
        val api = CloudflareDnsClient(connection, CloudflareGateway(HttpTransport { request ->
            requests += request
            if (request.method == "GET") {
                beforeRead?.invoke(request)
                when {
                    request.path == "/zones/zone-one" -> ok(zoneRow)
                    request.path.substringBefore('?') == "/zones/zone-one/dns_records" -> ok(JsonArray(listing), info)
                    else -> (row?.takeIf { it["id"]?.jsonPrimitive?.content == request.path.substringAfterLast('/') }
                        ?: listing.firstOrNull { it["id"]?.jsonPrimitive?.content == request.path.substringAfterLast('/') }
                        ?: row)?.let { ok(it) } ?: HttpResponse(404, "private-provider-marker")
                }
            } else onWrite?.invoke(request) ?: when (request.method) {
                "POST" -> { row = record(id = "provider-created"); ok(row!!) }
                "DELETE" -> { row = null; ok(obj("""{"id":"record-one"}""")) }
                else -> {
                    val payload = obj(request.body!!)
                    row = JsonObject(row.orEmpty() + payload)
                    ok(row!!)
                }
            }
        }))
        val controller = DnsChanges(resolver, plans, coordinator, clock, fingerprint, store, dispatcher) { onSession?.invoke(); api }
        fun select(value: DnsRecordInput?, mode: DnsWriteMode, recordId: String? = "record-one", scope: ZoneScope = zone, revision: Long = 1) {
            resolver.live = LiveOperationContext(connection, scope, revision, controller.requestFingerprint(scope, recordId, value, mode))
        }
        suspend fun prepare(value: DnsRecordInput?, mode: DnsWriteMode, recordId: String? = "record-one"): PreparedDnsChange {
            select(value, mode, recordId)
            return controller.prepare(zone, recordId, value, mode)
        }
        fun confirm(p: PreparedDnsChange) = plans.confirm(p.plan.id, p.plan.scope, p.plan.credentialRevision, p.plan.requestFingerprint, now)
        fun restored(): DnsChanges {
            val issuer = ChangePlans(resolver, clock, OperationIds { "restored-${++ids}" })
            return DnsChanges(resolver, issuer, OperationCoordinator(issuer, store, resolver, clock, test.backgroundScope, dispatcher), clock, fingerprint, store, dispatcher) { api }
        }
        val writes get() = requests.filter { it.method != "GET" }
    }

    @Test fun sixOperationsHaveExactPoliciesAndNeverRetryWrites() {
        val rows = listOf(
            Triple("dns-records-for-a-zone-list-dns-records", "GET", "/zones/{zone_id}/dns_records"),
            Triple("dns-records-for-a-zone-dns-record-details", "GET", "/zones/{zone_id}/dns_records/{dns_record_id}"),
            Triple("dns-records-for-a-zone-create-dns-record", "POST", "/zones/{zone_id}/dns_records"),
            Triple("dns-records-for-a-zone-patch-dns-record", "PATCH", "/zones/{zone_id}/dns_records/{dns_record_id}"),
            Triple("dns-records-for-a-zone-update-dns-record", "PUT", "/zones/{zone_id}/dns_records/{dns_record_id}"),
            Triple("dns-records-for-a-zone-delete-dns-record", "DELETE", "/zones/{zone_id}/dns_records/{dns_record_id}"),
        )
        rows.forEach { (key, method, path) ->
            val registration = assertNotNull(ActionPolicies.registry.registration(key))
            assertEquals(method, registration.method); assertEquals(path, registration.pathTemplate); assertEquals(ActionScope.ZONE, registration.scope)
            assertEquals(method == "GET", registration.policy.retry.enabled)
        }
    }

    @Test fun listingUsesSchemaQueryNamesExactMatchingAndProviderPagination() = runTest {
        val h = Harness(this)
        h.listing = listOf(record(extra = ",\"future\":{\"keep\":true}"))
        h.info = obj("""{"total_pages":3,"total_count":8,"per_page":3}""")
        val page = h.api.list(zone, DnsFilter("www.example.com", "A", "a+b=/ value", "Hello, world", listOf("team:DNS")), PageRequest(3, PageToken.Number(2)))
        assertEquals(8L, page.total); assertEquals(PageToken.Number(3), page.next)
        assertTrue(page.items.single().raw["future"]!!.jsonObject["keep"]!!.jsonPrimitive.boolean)
        val path = h.requests.last().path
        for (query in listOf("name.exact=www.example.com", "type=A", "content.exact=a%2Bb%3D%2F%20value", "comment.exact=Hello%2C%20world", "tag=team%3ADNS", "tag_match=all", "match=all", "page=2&per_page=3")) assertTrue(path.contains(query), path)
        assertFalse(path.contains("tags=")); assertEquals(2, h.requests.size)
    }

    @Test fun missingTotalsRemainUnknownAndNoUsageIsInvented() = runTest {
        val h = Harness(this); h.listing = listOf(record()); h.info = null
        val page = h.api.list(zone, DnsFilter(), PageRequest())
        assertNull(page.total); assertNull(page.next)
        assertFalse(h.requests.any { "usage" in it.path })
    }

    @Test fun multiTagScalarAmbiguityAndCursorAreExplicitlyRejected() = runTest {
        val h = Harness(this)
        assertFailsWith<CloudFailure> { h.api.list(zone, DnsFilter(tags = listOf("a:1", "b:2")), PageRequest()) }
        assertFailsWith<CloudFailure> { h.api.list(zone, DnsFilter(), PageRequest(token = PageToken.Cursor("cursor"))) }
        assertTrue(h.requests.isEmpty())
    }

    @Test fun srvAndFutureDetailsPreserveRawFields() = runTest {
        val h = Harness(this)
        h.row = record(type = "SRV", content = "10 5 443 target.example.com", extra = ",\"data\":{\"priority\":10,\"weight\":5,\"port\":443,\"target\":\"target.example.com\",\"future\":{\"keep\":true}},\"future_top\":123")
        assertEquals(h.row, h.api.get(zone, "record-one").raw)
        h.row = record(type = "FUTURE", extra = ",\"future\":[1,null]")
        assertEquals("FUTURE", h.api.get(zone, "record-one").raw["type"]!!.jsonPrimitive.content)
    }

    @Test fun providerZoneOwnershipRequiredForBothListAndGet() = runTest {
        for (which in listOf("list", "get")) for (bad in listOf(owner("other"), owner(id = "other"), obj("""{"id":"zone-one","name":"example.com"}"""))) {
            val h = Harness(this); h.zoneRow = bad
            assertFailsWith<CloudFailure> { if (which == "list") h.api.list(zone, DnsFilter(), PageRequest()) else h.api.get(zone, "record-one") }
            assertTrue(h.requests.none { "/dns_records" in it.path })
        }
    }

    @Test fun localCrossConnectionAndRecordIdentityMismatchAreRejected() = runTest {
        val h = Harness(this)
        assertFailsWith<CloudFailure> { h.api.get(ZoneScope(AccountScope("other", "account-one"), "zone-one"), "record-one") }
        assertTrue(h.requests.isEmpty())
        h.row = record(id = "other")
        assertFailsWith<CloudFailure> { h.api.get(zone, "record-one") }
    }

    @Test fun patchSendsOnlyChangedCommentAndPreservesUnknownRaw() = runTest {
        val h = Harness(this); h.row = record(extra = ",\"future_top\":{\"keep\":true}")
        val p = h.prepare(comment, DnsWriteMode.PATCH)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(p, h.confirm(p)).state)
        val write = h.writes.single()
        assertEquals("PATCH", write.method); assertEquals(setOf("comment"), obj(write.body!!).keys)
        assertEquals(obj("""{"keep":true}"""), h.row!!["future_top"])
        assertFalse(h.store.rows.values.single().toString().contains("edited"))
    }

    @Test fun fullEditorSnapshotStillDiffsOutUnchangedFieldsAndTxtEscapes() = runTest {
        val h = Harness(this)
        val fields = buildJsonObject { put("content", "quoted \"value\"\nline"); put("ttl", 1); put("proxied", false); put("comment", "edited") }
        h.row = JsonObject(record(type = "TXT") + ("content" to fields.getValue("content")))
        val p = h.prepare(DnsRecordInput("TXT", "www", fields), DnsWriteMode.PATCH)
        h.controller.submit(p, h.confirm(p))
        assertEquals(setOf("comment"), obj(h.writes.single().body!!).keys)
        val quote = "quoted \"other\"\nline"
        val value = DnsRecordInput("TXT", "www", buildJsonObject { put("content", quote) })
        val p2 = h.prepare(value, DnsWriteMode.PATCH); h.controller.submit(p2, h.confirm(p2))
        assertEquals(quote, obj(h.writes.last().body!!)["content"]!!.jsonPrimitive.content)
    }

    @Test fun knownNestedUpdateSendsCompletePreservedMap() = runTest {
        val h = Harness(this)
        h.row = record(extra = ",\"data\":{\"priority\":10,\"weight\":5,\"port\":80,\"target\":\"target.example.com\"}", type = "SRV")
        val p = h.prepare(input("""{"data":{"port":443}}""", type = "SRV"), DnsWriteMode.PATCH)
        h.controller.submit(p, h.confirm(p))
        val body = obj(h.writes.single().body!!)
        assertEquals(setOf("data"), body.keys)
        assertEquals(obj("""{"priority":10,"weight":5,"port":443,"target":"target.example.com"}"""), body["data"])
    }

    @Test fun unknownNestedEditsRejectButUnrelatedCommentCanBePatched() = runTest {
        val h = Harness(this); h.row = record(extra = ",\"settings\":{\"future\":true}")
        assertFailsWith<CloudFailure> { h.prepare(input("""{"settings":{"ipv4_only":true}}"""), DnsWriteMode.PATCH) }
        val p = h.prepare(comment, DnsWriteMode.PATCH); h.controller.submit(p, h.confirm(p))
        assertEquals(setOf("comment"), obj(h.writes.single().body!!).keys)
    }

    @Test fun putIsActualReplacementWithDistinctConfirmationAndUnsafeUnknownsBlockIt() = runTest {
        val h = Harness(this)
        val p = h.prepare(input("""{"content":"198.51.100.8"}"""), DnsWriteMode.REPLACE)
        assertTrue(h.controller.preview(p.plan.id).warnings.any { "完整替换" in it })
        assertTrue(h.controller.preview(p.plan.id).warnings.any { "原子" in it || "CAS" in it })
        h.controller.submit(p, h.confirm(p))
        assertEquals("PUT", h.writes.single().method)
        assertEquals(setOf("type", "name", "content"), obj(h.writes.single().body!!).keys)
        val other = Harness(this); other.row = record(extra = ",\"future_writable\":true")
        assertFailsWith<CloudFailure> { other.prepare(newA, DnsWriteMode.REPLACE) }
        assertTrue(other.writes.isEmpty())
    }

    @Test fun noOpAndFutureTypeWritesAreRejectedWithoutWrites() = runTest {
        val h = Harness(this)
        assertFailsWith<CloudFailure> { h.prepare(newA, DnsWriteMode.PATCH) }
        h.row = record(type = "FUTURE")
        assertFailsWith<CloudFailure> { h.prepare(input("""{"comment":"edited"}""", type = "FUTURE"), DnsWriteMode.PATCH) }
        assertTrue(h.writes.isEmpty())
    }

    @Test fun changedProviderWriteStateProducesExplicitConflictAndZeroWrites() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH); val confirmed = h.confirm(p)
        h.row = record(content = "198.51.100.99")
        val failure = assertFailsWith<CloudFailure> { h.controller.submit(p, confirmed) }
        assertEquals(CloudFailureCode.CONFLICT, failure.code); assertEquals(0, h.writes.size)
        assertTrue(h.store.rows.values.none { it.state == TaskState.SUCCEEDED })
    }

    @Test fun readonlyTimestampChangesAreNotCasOrWriteStateConflicts() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH)
        h.row = JsonObject(h.row!! + mapOf("modified_on" to JsonPrimitive("later"), "meta" to obj("""{"future":true}""")))
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(p, h.confirm(p)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun liveInputOrCredentialChangeAfterSessionAcquisitionStopsFinalSend() = runTest {
        for (revision in listOf(false, true)) {
            val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH); val c = h.confirm(p)
            h.onSession = { if (revision) h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) else h.select(input("""{"comment":"other"}"""), DnsWriteMode.PATCH) }
            assertFailsWith<CloudFailure> { h.controller.submit(p, c) }
            assertTrue(h.writes.isEmpty())
        }
    }

    @Test fun changedInputDuringOwnershipReadAlsoStopsFinalSend() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH); val c = h.confirm(p)
        h.beforeRead = { h.select(input("""{"comment":"changed"}"""), DnsWriteMode.PATCH) }
        assertFailsWith<CloudFailure> { h.controller.submit(p, c) }
        assertTrue(h.writes.isEmpty())
    }

    @Test fun copiedPreparedPayloadAndForeignConfirmationCannotAuthorizeWrites() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH); val c = h.confirm(p)
        assertFailsWith<CloudFailure> { h.controller.submit(p.copy(input = input("""{"comment":"forged"}""")), c) }
        val other = ChangePlans(h.resolver, h.clock, OperationIds { "foreign" })
        val foreignPlan = other.prepare(zone, p.plan.actionKey, p.plan.resourceRef, p.plan.beforeHash, p.plan.desiredHash, p.plan.requestFingerprint, 1, h.now)
        val foreign = other.confirm(foreignPlan.id, zone, 1, foreignPlan.requestFingerprint, h.now)
        assertFailsWith<CloudFailure> { h.controller.submit(p, foreign) }
        assertTrue(h.writes.isEmpty())
    }

    @Test fun timeoutWritesOnceAndBlocksFreshPrepareAndRestoredController() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("private-write-marker") }
        val p = h.prepare(comment, DnsWriteMode.PATCH); val c = h.confirm(p)
        assertEquals(TaskState.UNKNOWN, h.controller.submit(p, c).state)
        assertEquals(TaskState.UNKNOWN, h.controller.submit(p, c).state)
        assertEquals(1, h.writes.size)
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> { h.prepare(comment, DnsWriteMode.PATCH) }.code)
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> { h.restored().prepare(zone, "record-one", comment, DnsWriteMode.PATCH) }.code)
        assertFalse(h.store.rows.values.single().toString().contains("private-write-marker"))
    }

    @Test fun prePreparedSecondWriteCannotBypassAnUnknownFirstWrite() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val one = h.prepare(comment, DnsWriteMode.PATCH); val c1 = h.confirm(one)
        val two = h.prepare(comment, DnsWriteMode.PATCH); val c2 = h.confirm(two)
        h.controller.submit(one, c1)
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> { h.controller.submit(two, c2) }.code)
        assertEquals(1, h.writes.size)
    }

    @Test fun unknownPatchReconcilesReadOnlyInMemoryAndAfterControllerRebuild() = runTest {
        for (restore in listOf(false, true)) {
            val h = Harness(this); h.onWrite = { r -> h.row = JsonObject(h.row!! + obj(r.body!!)); throw IllegalStateException("timeout") }
            val p = h.prepare(comment, DnsWriteMode.PATCH)
            val task = h.controller.submit(p, h.confirm(p)); assertEquals(TaskState.UNKNOWN, task.state)
            val reads = h.requests.size
            val reconciled = (if (restore) h.restored() else h.controller).reconcile(task.id)
            assertEquals(TaskState.SUCCEEDED, reconciled.state); assertEquals(1, h.writes.size)
            assertTrue(h.requests.drop(reads).all { it.method == "GET" })
        }
    }

    @Test fun unmatchedReconciliationStaysUnknownAndKeepsWriteLatch() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(comment, DnsWriteMode.PATCH); val task = h.controller.submit(p, h.confirm(p))
        assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
        assertFailsWith<CloudFailure> { h.prepare(comment, DnsWriteMode.PATCH) }
        assertEquals(1, h.writes.size)
    }

    @Test fun createUsesRealProviderIdAndReadbackNeverLocalIds() = runTest {
        val h = Harness(this)
        val document = h.api.create(zone, newA)
        assertEquals("provider-created", document.id)
        assertTrue(h.requests.any { it.method == "GET" && it.path.endsWith("/dns_records/provider-created") })
        assertEquals(1, h.writes.size)
    }

    @Test fun createTimeoutOnlyUniqueNewMatchingEvidenceCanReconcile() = runTest {
        for (count in listOf(0, 1, 2)) {
            val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
            val p = h.prepare(newA, DnsWriteMode.CREATE, null); val task = h.controller.submit(p, h.confirm(p))
            h.listing = (1..count).map { record(id = "provider-$it") }
            h.info = obj("""{"total_pages":1,"total_count":$count,"per_page":50}""")
            assertEquals(if (count == 1) TaskState.SUCCEEDED else TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun preexistingCreateCandidateAndIncompletePagesAreNotUniqueEvidence() = runTest {
        val h = Harness(this); h.row = record(content = "198.51.100.3"); h.listing = listOf(h.row!!); h.info = obj("""{"total_pages":1,"total_count":1,"per_page":50}""")
        h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(newA, DnsWriteMode.CREATE, null); val task = h.controller.submit(p, h.confirm(p))
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
        h.info = null
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun malformedSuccessfulWriteAndMissingCreateIdRemainUnknown() = runTest {
        for (result in listOf(JsonNull, obj("""{"type":"A","name":"www.example.com"}"""), record(id = "different", content = "198.51.100.99"))) {
            val h = Harness(this); h.onWrite = { ok(result) }
            val p = h.prepare(newA, DnsWriteMode.CREATE, null)
            assertEquals(TaskState.UNKNOWN, h.controller.submit(p, h.confirm(p)).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun deleteNeedsExactProviderIdAndRestoredReconciliationIsReadOnly() = runTest {
        val h = Harness(this); h.onWrite = { h.row = null; throw IllegalStateException("timeout") }
        val p = h.prepare(null, DnsWriteMode.DELETE); val task = h.controller.submit(p, h.confirm(p))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
        assertEquals(listOf("DELETE"), h.writes.map { it.method })
    }

    @Test fun createUncertaintyCannotBeBypassedByAnExistingRecordWrite() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(newA, DnsWriteMode.CREATE, null)
        assertEquals(TaskState.UNKNOWN, h.controller.submit(p, h.confirm(p)).state)
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> { h.prepare(comment, DnsWriteMode.PATCH) }.code)
        assertEquals(1, h.writes.size)
    }

    @Test fun existingUncertaintyCannotBeBypassedByCreatingTheSameNameAgain() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(comment, DnsWriteMode.PATCH); h.controller.submit(p, h.confirm(p))
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> { h.prepare(newA, DnsWriteMode.CREATE, null) }.code)
        assertEquals(1, h.writes.size)
    }

    @Test fun readPermissionAndOwnershipFailureDuringReconcileNeverProveSuccess() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(comment, DnsWriteMode.PATCH); val task = h.controller.submit(p, h.confirm(p))
        h.zoneRow = owner(account = "other")
        assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun expiryAndScopeSwitchNeverSendAndForgedPersistedTargetCannotSettle() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH); val c = h.confirm(p)
        h.now += ChangePlans.TTL_MS
        assertFailsWith<CloudFailure> { h.controller.submit(p, c) }; assertTrue(h.writes.isEmpty())
        val other = Harness(this); other.onWrite = { throw IllegalStateException("timeout") }
        val q = other.prepare(comment, DnsWriteMode.PATCH); val task = other.controller.submit(q, other.confirm(q))
        other.store.rows[task.id] = task.copy(resourceRef = ResourceRef(zone, "other_kind", "record-one"))
        assertEquals(TaskState.UNKNOWN, other.restored().reconcile(task.id).state)
        other.select(comment, DnsWriteMode.PATCH, scope = ZoneScope(zone.account, "other-zone"))
        assertFailsWith<CloudFailure> { other.restored().reconcile(task.id) }
        assertEquals(1, other.writes.size)
    }

    @Test fun conflictingPlanSettlesAsNotSentAndCanBeFreshlyPreviewed() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH)
        h.row = record(content = "198.51.100.99")
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { h.controller.submit(p, h.confirm(p)) }.code)
        assertEquals(TaskState.ACTION_REQUIRED, h.store.get(p.plan.id)?.state)
        val fresh = h.prepare(comment, DnsWriteMode.PATCH)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(fresh, h.confirm(fresh)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun changedProviderCommentAlreadyAtDesiredValueStillConflictsInsteadOfSendingEmptyPatch() = runTest {
        val h = Harness(this); val p = h.prepare(comment, DnsWriteMode.PATCH)
        h.row = JsonObject(h.row!! + ("comment" to JsonPrimitive("edited")))
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { h.controller.submit(p, h.confirm(p)) }.code)
        assertEquals(0, h.writes.size)
    }

    @Test fun ipv6CreateReadbackNormalizationIsSemanticWithoutRewritingPayload() = runTest {
        val h = Harness(this)
        val expanded = "2001:0DB8:0000:0000:0000:0000:0000:1"
        h.onWrite = { h.row = record(id = "provider-created", type = "AAAA", content = "2001:db8::1"); ok(h.row!!) }
        val result = h.api.create(zone, input("""{"content":"$expanded"}""", type = "AAAA"))
        assertEquals("provider-created", result.id)
        assertEquals(expanded, obj(h.writes.single().body!!)["content"]!!.jsonPrimitive.content)
    }

    @Test fun ipv6PatchReadbackNormalizationProvesSuccess() = runTest {
        val h = Harness(this); h.row = record(type = "AAAA", content = "2001:db8::1")
        val value = input("""{"content":"2001:0DB8:0000:0000:0000:0000:0000:2"}""", type = "AAAA")
        h.onWrite = { h.row = record(type = "AAAA", content = "2001:db8::2"); ok(h.row!!) }
        val p = h.prepare(value, DnsWriteMode.PATCH)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(p, h.confirm(p)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun ipv6EquivalentGroupsAndMappedAddressesAreNoops() = runTest {
        for ((server, entered) in listOf("2001:db8::1" to "2001:0DB8:0000:0000:0000:0000:0000:1", "::ffff:c000:201" to "0:0:0:0:0:ffff:192.0.2.1")) {
            val h = Harness(this); h.row = record(type = "AAAA", content = server)
            assertEquals(CloudFailureCode.INVALID_INPUT, assertFailsWith<CloudFailure> { h.prepare(input("""{"content":"$entered"}""", type = "AAAA"), DnsWriteMode.PATCH) }.code)
            assertTrue(h.writes.isEmpty())
        }
    }

    @Test fun restoredIpv6ReconciliationUsesEquivalent128BitGroups() = runTest {
        val h = Harness(this); h.row = record(type = "AAAA", content = "2001:db8::1")
        h.onWrite = { h.row = record(type = "AAAA", content = "2001:db8::2"); throw IllegalStateException("timeout") }
        val p = h.prepare(input("""{"content":"2001:0DB8:0000:0000:0000:0000:0000:2"}""", type = "AAAA"), DnsWriteMode.PATCH)
        val task = h.controller.submit(p, h.confirm(p)); assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun srvTargetCaseAndTrailingDotAreHostnameSemanticsOnly() = runTest {
        val h = Harness(this)
        h.row = record(type = "SRV", extra = ",\"data\":{\"priority\":10,\"weight\":5,\"port\":443,\"target\":\"origin.example.com.\"}")
        assertEquals(CloudFailureCode.INVALID_INPUT, assertFailsWith<CloudFailure> { h.prepare(input("""{"data":{"target":"ORIGIN.EXAMPLE.COM"}}""", type = "SRV"), DnsWriteMode.PATCH) }.code)
        assertTrue(h.writes.isEmpty())
    }

    @Test fun malformedOrMissingIpReadbackCannotProveDesiredState() = runTest {
        for (bad in listOf("192.0.2.1::", "bad", "")) {
            val h = Harness(this); h.row = record(type = "AAAA", content = "2001:db8::1")
            h.onWrite = { h.row = record(type = "AAAA", content = bad); throw IllegalStateException("timeout") }
            val p = h.prepare(input("""{"content":"2001:db8::2"}""", type = "AAAA"), DnsWriteMode.PATCH)
            val task = h.controller.submit(p, h.confirm(p))
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun equivalentOwnerNameDoesNotEnterAnUnrelatedCommentPatch() = runTest {
        val h = Harness(this); h.row = record(name = "WWW.EXAMPLE.COM.")
        val p = h.prepare(comment, DnsWriteMode.PATCH)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(p, h.confirm(p)).state)
        assertEquals(setOf("comment"), obj(h.writes.single().body!!).keys)
        assertEquals(setOf("comment"), h.controller.preview(p.plan.id).changedFields)
    }

    @Test fun rawPatchFreezesCallerFieldsBeforeSuspendingOwnershipReads() = runTest {
        val h = Harness(this)
        val backing = mutableMapOf<String, JsonElement>("comment" to JsonPrimitive("original"))
        h.beforeRead = { backing["comment"] = JsonPrimitive("changed while suspended") }
        h.api.patch(zone, "record-one", JsonObject(backing))
        assertEquals("original", obj(h.writes.single().body!!)["comment"]!!.jsonPrimitive.content)
    }

    @Test fun persistedCreateMetadataMustStillBindItsOriginalMemoryPlan() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(newA, DnsWriteMode.CREATE, null)
        val task = h.controller.submit(p, h.confirm(p))
        h.listing = listOf(record(id = "provider-new")); h.info = obj("""{"total_pages":1,"total_count":1,"per_page":50}""")
        h.store.rows[task.id] = task.copy(resourceRef = ResourceRef(zone, "dns_record_creation", "A:other.example.com"))
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun simultaneousDuplicateConfirmationSharesOneP1Execution() = runTest {
        val h = Harness(this); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        h.onWrite = { request -> started.complete(Unit); finish.await(); h.row = JsonObject(h.row!! + obj(request.body!!)); ok(h.row!!) }
        val p = h.prepare(comment, DnsWriteMode.PATCH); val confirmed = h.confirm(p)
        val first = async { h.controller.submit(p, confirmed) }
        val second = async { h.controller.submit(p, confirmed) }
        started.await(); assertEquals(1, h.writes.size); finish.complete(Unit)
        assertEquals(TaskState.SUCCEEDED, first.await().state)
        assertEquals(first.await(), second.await()); assertEquals(1, h.store.rows.size)
    }

    @Test fun restoredCreateCanMatchUniqueProviderStateWithoutRestoringExecutablePayload() = runTest {
        val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
        val p = h.prepare(newA, DnsWriteMode.CREATE, null); val task = h.controller.submit(p, h.confirm(p))
        h.listing = listOf(record(id = "provider-new")); h.info = obj("""{"total_pages":1,"total_count":1,"per_page":50}""")
        val reads = h.requests.size
        assertEquals(TaskState.SUCCEEDED, h.restored().reconcile(task.id).state)
        assertTrue(h.requests.drop(reads).all { it.method == "GET" }); assertEquals(1, h.writes.size)
    }

    @Test fun anAlreadyIdenticalRecordMustBeViewedRatherThanCreatedAgain() = runTest {
        val h = Harness(this); h.listing = listOf(record()); h.info = obj("""{"total_pages":1,"total_count":1,"per_page":50}""")
        assertEquals(CloudFailureCode.CONFLICT, assertFailsWith<CloudFailure> { h.prepare(newA, DnsWriteMode.CREATE, null) }.code)
        assertTrue(h.writes.isEmpty())
    }

    @Test fun restoredCreateAmbiguityOrMissingMetadataNeverUnblocksWriting() = runTest {
        for (count in listOf(0, 2)) {
            val h = Harness(this); h.onWrite = { throw IllegalStateException("timeout") }
            val p = h.prepare(newA, DnsWriteMode.CREATE, null); val task = h.controller.submit(p, h.confirm(p))
            h.listing = (1..count).map { record(id = "provider-$it") }; h.info = obj("""{"total_pages":1,"total_count":$count,"per_page":50}""")
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            h.info = null
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }
}
