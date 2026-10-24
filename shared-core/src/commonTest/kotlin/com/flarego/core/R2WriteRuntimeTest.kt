package com.flarego.core

import com.flarego.core.application.operations.*
import com.flarego.core.application.r2.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2WriteRuntimeTest {
    private val connection = Connection("local", "fixture", "account")
    private val account = AccountScope(connection.id, connection.accountId)
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
    }
    private inner class Harness(scope: TestScope) {
        var actual = R2LiveSelection(connection, 1, 1, null)
        var revision = 1L; var ids = 0; var exists = false; var failWrite = false
        var now = 1_000L
        var reuseIds = false
        var onIdentity: () -> Unit = {}
        var beforeReceipt: suspend () -> Unit = {}
        val requests = mutableListOf<HttpRequest>(); val store = Store()
        val api = CloudflareR2Client(connection, CloudflareGateway(HttpTransport { r ->
            requests += r
            if (r.method == "GET" && !exists) HttpResponse(404, "not found")
            else if (r.method == "GET") HttpResponse(200,"""{"success":true,"result":{"name":"my-bucket","storage_class":"Standard"}}""")
            else if (failWrite) HttpResponse(500, "private-marker")
            else { beforeReceipt(); exists = r.method != "DELETE"; HttpResponse(200,"""{"success":true,"result":{"name":"my-bucket","storage_class":"Standard"}}""") }
        }), object : TransferPort { override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("not download") })
        val runtime = R2WriteRuntime(scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { now }, OperationIds { onIdentity(); if (reuseIds) "runtime-1" else "runtime-${++ids}" }, { actual }, { listOf(connection) }, { revision }, { api })
        fun create() = R2Command.Bucket(R2BucketWrite.Create(account, R2BucketCreateInput("my-bucket")))
        fun select(command: R2Command) { actual = actual.copy(command = command) }
    }
    @Test fun prepareDoesNotSendAndConfirmationUsesOneRealTaskForRepeatedClicks() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val preview = h.runtime.prepare(command)
        assertTrue(h.requests.all { it.method == "GET" })
        val first = h.runtime.confirm(preview.planId)
        val second = h.runtime.confirm(preview.planId)
        assertEquals(first.id, second.id)
        assertEquals(TaskState.SUCCEEDED, first.state)
        assertEquals(1, h.requests.count { it.method == "POST" })
        assertEquals(1, h.runtime.tasks.value.size)
    }
    @Test fun changedInputOrRouteCannotBorrowAnEarlierConfirmation() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val preview = h.runtime.prepare(command)
        h.actual = h.actual.copy(routeEpoch = 2)
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        h.actual = h.actual.copy(routeEpoch = 1, command = R2Command.Bucket(R2BucketWrite.Create(account, R2BucketCreateInput("other-bucket"))))
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun unknownWriteBlocksNewPlansAndReadOnlyReconciliationNeverResends() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val preview = h.runtime.prepare(command); h.failWrite = true
        val task = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.UNKNOWN, task.state)
        assertFailsWith<CloudFailure> { h.runtime.prepare(command) }
        val writes = h.requests.count { it.method != "GET" }
        assertEquals(TaskState.UNKNOWN, h.runtime.reconcile(task.id).state)
        assertEquals(writes, h.requests.count { it.method != "GET" })
    }
    @Test fun aFullRetentionWindowRefusesNewPreviewsWithoutEvictingValidConfirmations() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val first = h.runtime.prepare(command)
        repeat(63) { h.runtime.prepare(command) }
        assertFailsWith<CloudFailure> { h.runtime.prepare(command) }
        assertTrue(h.requests.all { it.method == "GET" })
        val task = h.runtime.confirm(first.planId)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(task.id, h.runtime.confirm(first.planId).id)
        assertEquals(1, h.requests.count { it.method == "POST" })
    }
    @Test fun expiredPreviewsFreeCapacityButReusedIdInputsCannotReviveTheOldPreview() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val old = h.runtime.prepare(command)
        repeat(63) { h.runtime.prepare(command) }
        h.now += ChangePlans.TTL_MS
        h.reuseIds = true
        val fresh = h.runtime.prepare(command)
        assertNotEquals(old.planId, fresh.planId)
        assertFailsWith<CloudFailure> { h.runtime.confirm(old.planId) }
        assertTrue(h.requests.all { it.method == "GET" })
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(fresh.planId).state)
        assertEquals(1, h.requests.count { it.method == "POST" })
    }
    @Test fun repeatedExpiryReclaimsBucketAndObjectPayloadTablesAsWellAsIssuerMetadata() = runTest {
        val h = Harness(this); h.exists = true
        repeat(4) {
            repeat(32) { index ->
                val command = if (index % 2 == 0) R2Command.Bucket(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
                    else R2Command.Object(R2ObjectWrite.Delete(account, "my-bucket", "a.txt", "a.txt"))
                h.select(command); h.runtime.prepare(command)
            }
            h.now += ChangePlans.TTL_MS
        }
        val command = R2Command.Bucket(R2BucketWrite.PatchStorageClass(account, "my-bucket", "InfrequentAccess"))
        h.select(command); h.runtime.prepare(command)
        fun member(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun entries(owner: Any, name: String): Int {
            val value = member(owner, name)
            @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
            return (when (value) {
                is Map<*, *> -> value
                is kotlin.concurrent.atomics.AtomicReference<*> -> value.load() as Map<*, *>
                else -> error("Not a retained plan table")
            }).size
        }
        val issuer = member(h.runtime, "plans"); val controller = member(h.runtime, "controller")
        assertEquals(1, entries(h.runtime, "prepared"))
        assertEquals(1, entries(issuer, "prepared"))
        assertEquals(0, entries(issuer, "issued"))
        assertEquals(1, entries(controller, "prepared"))
        assertEquals(0, entries(controller, "preparedObjects"))
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun discardedPreviewsCannotConfirmOrExhaustCapacityEvenWithTheSameLiveCommandAndIdInput() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command); h.reuseIds = true
        val ids = mutableSetOf<String>()
        repeat(96) {
            val preview = h.runtime.prepare(command)
            assertTrue(ids.add(preview.planId))
            assertTrue(h.runtime.discardPreview(preview.planId))
            assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        }
        assertTrue(h.requests.all { it.method == "GET" })
        val fresh = h.runtime.prepare(command)
        val task = h.runtime.confirm(fresh.planId)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertFalse(h.runtime.discardPreview(fresh.planId), "a completed admission must keep its repeat-click result")
        assertEquals(task.id, h.runtime.confirm(fresh.planId).id)
        assertEquals(1, h.requests.count { it.method == "POST" })
    }
    @Test fun latePreparationsWhichLoseTheirLiveContextDoNotExhaustCapacity() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        h.onIdentity = { h.actual = h.actual.copy(routeEpoch = h.actual.routeEpoch + 1) }
        repeat(96) { assertFailsWith<CloudFailure> { h.runtime.prepare(command) } }
        h.onIdentity = {}
        assertNotNull(h.runtime.prepare(command))
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun expiryAndDiscardDoNotDetachAnInFlightAdmissionOrRecreateItsPersistedTask() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val release = CompletableDeferred<Unit>()
        h.beforeReceipt = { release.await() }
        val preview = h.runtime.prepare(command)
        val first = async { h.runtime.confirm(preview.planId) }; runCurrent()
        val second = async { h.runtime.confirm(preview.planId) }; runCurrent()
        assertEquals(1, h.requests.count { it.method == "POST" })
        assertFalse(h.runtime.discardPreview(preview.planId))
        h.now += ChangePlans.TTL_MS
        val other = R2Command.Bucket(R2BucketWrite.Create(account, R2BucketCreateInput("other-bucket")))
        h.select(other)
        val another = h.runtime.prepare(other)
        assertFalse(h.runtime.discardPreview(preview.planId), "expiry is not cancellation of a running admission")
        assertFalse(first.isCompleted)
        release.complete(Unit); runCurrent()
        val task = first.await()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(task.id, second.await().id)
        assertTrue(h.runtime.discardPreview(another.planId))
        assertEquals(1, h.store.rows.size)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.getValue(task.id).state)
        assertEquals(1, h.requests.count { it.method == "POST" })
    }
    @Test fun revocationDoesNotRequireAUsableClockOrAuthorizeNewWork() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val preview = h.runtime.prepare(command)
        h.now = -1
        assertTrue(h.runtime.discardPreview(preview.planId))
        assertFailsWith<CloudFailure> { h.runtime.prepare(command) }
        h.now = 1_000
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertTrue(h.requests.all { it.method == "GET" })
    }
    @Test fun credentialInvalidationRejectsBeforeAnyMutation() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val preview = h.runtime.prepare(command); h.revision = -1
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertTrue(h.requests.all { it.method == "GET" })
    }
}
