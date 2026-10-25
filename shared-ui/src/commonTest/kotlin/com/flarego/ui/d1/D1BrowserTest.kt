package com.flarego.ui.d1

import com.flarego.core.application.d1.*
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.kv.KvOwnerSecurePort
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class D1BrowserTest {
    private inner class Harness(val scope: TestScope) {
        val connection = Connection("d1-ui", "synthetic", "a".repeat(32))
        val account = AccountScope(connection.id, connection.accountId)
        val uuid = "12345678-1234-4321-abcd-123456789abc"
        var context: D1BrowserContext? = D1BrowserContext(connection, 1, 0)
        val content = D1UiMemoryStore(); val window = KvOwnerSecurePort()
        var serial = 0; var now = 1000L; var writes = 0; var reads = 0; var beforeList: suspend () -> Unit = {}
        val tasks = mutableMapOf<String, OperationTask>()
        val store = object : OperationStore {
            override fun get(id: String) = tasks[id]
            override fun list(connectionId: String) = tasks.values.toList()
            override fun put(task: OperationTask) { tasks[task.id] = task }
            override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
            override fun removeConnection(connectionId: String) = Unit
        }
        fun api(guard: () -> Unit = {}): D1Session = CloudflareD1Client(connection, CloudflareGateway(HttpTransport { r ->
            if (r.method == "GET") { reads++; if (r.path.contains("?")) beforeList() } else writes++
            val row = """{"uuid":"$uuid","name":"fixture","read_replication":{"mode":"auto"}}"""
            HttpResponse(200, """{"success":true,"result":${if (r.path.contains("?page=")) "[$row]" else row}}""")
        }), object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
                writes++; (request.payload as HttpBody.Stream).source.close()
                val b = """{"success":true,"result":[{"success":true,"results":[{"secret-column":"secret-result"}]}]}""".encodeToByteArray()
                sink.write(b,0,b.size); sink.close(); return HttpResponseHead(200, emptyMap(), b.size.toLong())
            }
        }, content, { _, _, _ -> guard() })
        val runtime: D1WriteRuntime by lazy { D1WriteRuntime(scope.backgroundScope,store,StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { MessageDigest.getInstance("SHA-256").digest(it).joinToString(""){b -> "%02x".format(b)} },
            OperationClock { now },OperationIds { "ui-${++serial}" },
            { context?.let { D1LiveSelection(it.connection,it.credentialRevision,browser.confirmationEpoch,browser.liveCommand()) } },
            { listOf(connection) }, { context?.credentialRevision ?: -1 }, { _, guard -> api(guard) },
            { _, _, _ -> error("not a file test") }, { live, result -> browser.claimResult(live,result) }) }
        val browser: D1Browser by lazy { D1Browser(scope.backgroundScope,{context},runtime,content,{window},D1ContentFingerprint { "0".repeat(32) },{api()},{ _, _ -> error("not a file test")}) }
        suspend fun install() { browser.refreshContext(); scope.testScheduler.advanceUntilIdle(); browser.refreshDatabases(); scope.testScheduler.advanceUntilIdle(); browser.openDatabase(D1DatabaseRef(account,uuid)); scope.testScheduler.advanceUntilIdle() }
        fun drain() { scope.testScheduler.runCurrent() }
    }
    @Test fun realBrowserLoadsDirectoryDetailsAndDiscardsLateDifferentAccountRead() = runTest {
        val h = Harness(this); h.browser.refreshContext(); h.drain(); assertEquals(1,h.browser.state.value.databases.size)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.beforeList = { started.complete(Unit); release.await() }; h.browser.refreshDatabases(); h.drain(); started.await()
        h.context = null; h.browser.refreshContext(); release.complete(Unit); h.drain()
        assertTrue(h.browser.state.value.databases.isEmpty()); assertNull(h.browser.state.value.context)
    }
    @Test fun sqlRunsOnlyAfterThreeStagesKeepsPrivateResultAndClearsInputAtTerminal() = runTest {
        val h = Harness(this); h.browser.refreshContext(); h.drain(); h.browser.openDatabase(D1DatabaseRef(h.account,h.uuid)); h.drain()
        h.browser.begin(D1Editor.SQL); assertTrue(h.browser.content.set(D1Field.SQL,"SELECT 'secret-sql';"))
        h.browser.prepare(); withContext(Dispatchers.Default) { withTimeout(5000) { h.browser.state.first { !it.loading } } }; h.drain(); assertEquals(0,h.writes)
        assertEquals(D1ConfirmationStage.RISK,h.browser.content.state.value.confirmation?.stage)
        h.browser.acceptRisk(); h.browser.acceptTyped(h.uuid); h.browser.submit(); withContext(Dispatchers.Default) { withTimeout(5000) { h.browser.state.first { !it.writing } } }; h.drain()
        assertEquals(1,h.writes); assertTrue(h.browser.content.state.value.resultReady)
        assertEquals("",h.browser.content.field(D1Field.SQL)); assertNull(h.browser.content.state.value.confirmation)
        assertFalse(h.browser.state.value.toString().contains("secret-")); assertTrue(h.tasks.values.any { it.state == TaskState.SUCCEEDED })
    }
    @Test fun privateEditInvalidatesFrozenConfirmationAndBackgroundRevokesEverything() = runTest {
        val h=Harness(this); h.browser.refreshContext(); h.drain(); h.browser.openDatabase(D1DatabaseRef(h.account,h.uuid)); h.drain()
        h.browser.begin(D1Editor.SQL); h.browser.content.set(D1Field.SQL,"SELECT 1"); h.browser.prepare(); withContext(Dispatchers.Default) { withTimeout(5000) { h.browser.state.first { !it.loading } } }; h.drain()
        assertNotNull(h.browser.content.state.value.confirmation); h.browser.content.set(D1Field.PARAMS,"[]")
        assertNull(h.browser.content.state.value.confirmation); h.browser.submit(); h.drain(); assertEquals(0,h.writes)
        h.browser.background(); assertNull(h.browser.content.field(D1Field.SQL)); assertFalse(h.browser.content.state.value.secure)
    }
    @Test fun rejectedPreparationReleasesBusyStateAndAllowsCorrectedForm() = runTest {
        val h=Harness(this);h.browser.refreshContext();h.drain();h.browser.begin(D1Editor.CREATE)
        h.browser.edit{it.copy(name="invalid/name")};h.browser.prepare();h.drain()
        assertNotNull(h.browser.state.value.error);assertFalse(h.browser.state.value.loading)
        h.browser.edit{it.copy(name="valid-name")};h.browser.prepare();h.drain()
        assertNotNull(h.browser.content.state.value.confirmation);assertFalse(h.browser.state.value.loading);assertEquals(0,h.writes)
    }
}
