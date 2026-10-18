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
        val requests = mutableListOf<HttpRequest>(); val store = Store()
        val api = CloudflareR2Client(connection, CloudflareGateway(HttpTransport { r ->
            requests += r
            if (r.method == "GET" && !exists) HttpResponse(404, "not found")
            else if (r.method == "GET") HttpResponse(200,"""{"success":true,"result":{"name":"my-bucket","storage_class":"Standard"}}""")
            else if (failWrite) HttpResponse(500, "private-marker")
            else { exists = r.method != "DELETE"; HttpResponse(200,"""{"success":true,"result":{"name":"my-bucket","storage_class":"Standard"}}""") }
        }), object : TransferPort { override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("not download") })
        val runtime = R2WriteRuntime(scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "runtime-${++ids}" }, { actual }, { listOf(connection) }, { revision }, { api })
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
    @Test fun credentialInvalidationRejectsBeforeAnyMutation() = runTest {
        val h = Harness(this); val command = h.create(); h.select(command)
        val preview = h.runtime.prepare(command); h.revision = -1
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertTrue(h.requests.all { it.method == "GET" })
    }
}
