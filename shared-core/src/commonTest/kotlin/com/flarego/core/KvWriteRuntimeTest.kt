package com.flarego.core

import com.flarego.core.application.kv.*
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.kv.CloudflareKvClient
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.security.MessageDigest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KvWriteRuntimeTest {
    private val c = Connection("local", "fixture", "a".repeat(32))
    private val account = AccountScope(c.id, c.accountId)
    private val ns = KvNamespaceRef(account, "b".repeat(32))
    private fun deletion() = KvMutation.DeleteKey(KvKeyTarget(ns, "key"))
    private inner class Harness(val scope: TestScope) {
        var command: KvMutation? = null; var epoch = 0L; var revision = 1L; var now = 1000L; var serial = 0
        var title = "fixture"; var writeStatus = 200; var writes = 0
        var beforeGet: suspend () -> Unit = {}; var acquire: suspend () -> Unit = {}
        var beforeWire: () -> Unit = {}; var afterSend: suspend () -> Unit = {}
        val tasks = linkedMapOf<String, OperationTask>()
        val store = object : OperationStore {
            override fun get(id: String) = tasks[id]
            override fun list(connectionId: String) = tasks.values.filter { it.connectionId == connectionId }
            override fun put(task: OperationTask) { tasks[task.id] = task }
            override fun removeConnection(connectionId: String) = Unit
            override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        }
        val runtime = KvWriteRuntime(scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { n -> "%02x".format(n) } },
            OperationClock { now }, OperationIds { "id-${++serial}" },
            { KvLiveSelection(c, revision, epoch, command) }, { listOf(c) }, { revision },
            { _, guard ->
                acquire()
                CloudflareKvClient(c, CloudflareGateway(HttpTransport { r ->
                    if (r.method == "GET") {
                        beforeGet()
                        HttpResponse(200, """{"success":true,"result":{"id":"${ns.id}","title":"$title","supports_url_encoding":true}}""")
                    } else {
                        writes++; afterSend()
                        HttpResponse(writeStatus, """{"success":true,"result":{"id":"${ns.id}","title":"new","successful_key_count":1,"unsuccessful_keys":[]}}""")
                    }
                }, sleeper = {}), object : TransferPort {
                    override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("not used")
                }, KvMemoryStore(), { now / 1000 }, { _,_,_ -> beforeWire(); guard() })
            })
        suspend fun prepare(cmd: KvMutation = deletion()): KvConfirmationView { command = cmd; return runtime.prepare(cmd) }
        suspend fun final(cmd: KvMutation = deletion()): KvConfirmationView {
            val p = prepare(cmd)
            return if (p.handle.stage == KvConfirmationStage.FINAL) p else runtime.acceptTyped(runtime.acceptRisk(p.handle).handle, ns.id)
        }
    }
    @Test fun destructiveWriteRequiresThreeDistinctIssuedStagesAndExactNamespace() = runTest {
        val h = Harness(this); val p = h.prepare()
        assertEquals(KvConfirmationStage.RISK, p.handle.stage); assertEquals(listOf("key"), p.keys)
        assertFailsWith<CloudFailure> { h.runtime.submit(p.handle) }
        val typed = h.runtime.acceptRisk(p.handle)
        assertFailsWith<CloudFailure> { h.runtime.acceptRisk(p.handle) }
        assertFailsWith<CloudFailure> { h.runtime.acceptTyped(typed.handle, " ${ns.id}") }
        val final = h.runtime.acceptTyped(typed.handle, ns.id)
        assertNotSame(typed.handle, final.handle); assertEquals(0, h.writes)
        assertEquals(TaskState.ACCEPTED, h.runtime.submit(final.handle).task.state)
        assertEquals(1, h.writes)
    }
    @Test fun oldDialogCannotConfirmAnotherDraftEvenWithTheSameText() = runTest {
        val h = Harness(this); val old = h.final(); val current = h.final()
        assertFailsWith<CloudFailure> { h.runtime.submit(old.handle) }; assertEquals(0, h.writes)
        h.runtime.submit(current.handle); assertEquals(1, h.writes)
    }
    @Test fun editsRevisionEpochRevocationAndExpiryRefuseBeforeAnyWrite() = runTest {
        for (change in listOf<(Harness) -> Unit>({ it.command = deletion() }, { it.revision++ }, { it.epoch++ }, { it.runtime.revoke() }, { it.now += 300000 })) {
            val h = Harness(this); val p = h.final(); change(h)
            assertFailsWith<CloudFailure> { h.runtime.submit(p.handle) }; assertEquals(0, h.writes)
        }
    }
    @Test fun latePreflightAndFinalTransportGuardCannotSendAfterScopeChanges() = runTest {
        val h = Harness(this); h.beforeGet = { h.epoch++ }
        assertFailsWith<CloudFailure> { h.prepare() }; assertEquals(0, h.writes)
        val late = Harness(this); val p = late.final(); late.beforeWire = { late.epoch++ }
        late.runtime.submit(p.handle); assertEquals(0, late.writes)
    }
    @Test fun fullKeysAreFrozenAndPublicTasksNeverContainValueOrMetadata() = runTest {
        val h = Harness(this)
        val content = KvMemoryContent("private-value".encodeToByteArray())
        val input = KvValueInput(content, KvExpiry.Ttl(60), KvValidation.metadata("\"private-metadata\""))
        val p = h.final(KvMutation.Put(KvKeyTarget(ns, "A/键"), input))
        assertEquals(listOf("A/键"), p.keys); assertEquals(content.descriptor.length, p.bytes)
        val task = h.runtime.submit(p.handle).task
        val public = listOf(p.toString(), task.toString()).joinToString()
        assertFalse(public.contains("private-value")); assertFalse(public.contains("private-metadata"))
        assertEquals(ResourceRef(account, KvTaskTargets.NAMESPACE, ns.id), task.resourceRef)
        assertNull(task.desiredHash); assertNull(task.pollPath)
        assertFails { content.openVerified() }
    }
    @Test fun unknownPersistsAcrossRecreationRefreshAndDifferentNamespaceActions() = runTest {
        val h = Harness(this); h.writeStatus = 503; val p = h.final(); val result = h.runtime.submit(p.handle)
        assertTrue(KvTaskTargets.unsettled(result.task.state)); assertEquals(1, h.writes)
        h.runtime.revoke(); h.runtime.refreshTasks(); h.runtime.observeTask(result.task.id)
        assertFailsWith<CloudFailure> { h.prepare(KvMutation.Rename(ns, "new")) }
        assertEquals(1, h.writes)
        assertFailsWith<CloudFailure> { h.runtime.closeUncertain(result.task.id, "wrong", true) }
        assertFailsWith<CloudFailure> { h.runtime.closeUncertain(result.task.id, ns.id, false) }
        assertEquals(TaskState.ACTION_REQUIRED, h.runtime.closeUncertain(result.task.id, ns.id, true).state)
        h.writeStatus = 200; h.runtime.submit(h.final().handle); assertEquals(2, h.writes)
    }
    @Test fun repeatedConfirmationSharesOneExecutionAndNamespaceGateStopsConcurrentDelete() = runTest {
        val h = Harness(this); val p = h.final(); val sent = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.afterSend = { sent.complete(Unit); release.await() }
        val first = async { h.runtime.submit(p.handle) }; sent.await()
        val second = async { h.runtime.submit(p.handle) }; runCurrent()
        assertEquals(1, h.writes)
        assertFailsWith<CloudFailure> { h.prepare(KvMutation.DeleteNamespace(ns)) }
        release.complete(Unit); assertEquals(first.await().task.id, second.await().task.id)
        assertEquals(1, h.writes)
    }
    @Test fun createsAndRenamesHaveFinalOnlyAndCompleteBulkKeysAreNeverTruncated() = runTest {
        val h = Harness(this); assertEquals(KvConfirmationStage.FINAL, h.prepare(KvMutation.Create(account, "new")).handle.stage)
        assertEquals(KvConfirmationStage.FINAL, h.prepare(KvMutation.Rename(ns, "new")).handle.stage)
        val keys = (0..9999).map { "key-$it" }; val p = h.prepare(KvMutation.BulkDelete(ns, keys))
        assertEquals(keys, p.keys); assertEquals("key-9999", p.keys.last())
        assertFalse(p.toString().contains("KvMutation"))
    }
}
