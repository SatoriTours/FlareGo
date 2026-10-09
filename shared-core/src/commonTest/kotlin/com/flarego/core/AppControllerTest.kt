package com.flarego.core

import com.flarego.core.application.AppController
import com.flarego.core.application.operations.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val a = Connection("a", "Production", "aa", true)
    private val b = Connection("b", "Sandbox", "bb", true)

    @Test
    fun writeSurvivesCancelledUiAndHasDurablePendingRecord() = runTest {
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val session =
            object : CloudSession by DemoSession(a) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    writes++
                    gate.await()
                }
            }
        val store = MemoryStore()
        val controller =
            AppController(backgroundScope, SessionFactory { session }, store, listOf(a))
        controller.refresh()
        runCurrent()
        val uiJob = launch {
            controller.saveDns(
                Domain("z", "example.com", "active", "a"),
                null,
                DnsDraft("A", "@", "192.0.2.1"),
            )
        }
        runCurrent()
        assertEquals(OperationStatus.UNKNOWN, store.operations().single().status)
        uiJob.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(OperationStatus.SUCCESS, store.operations().single().status)
    }

    @Test
    fun switchKeepsRootTabAndClearsDetail() = runTest {
        val controller =
            AppController(
                backgroundScope,
                SessionFactory { DemoSession(it) },
                MemoryStore(),
                listOf(a, b),
            )
        controller.navigate(Page.RESOURCES)
        controller.selectConnection(b)
        runCurrent()
        assertEquals(Page.RESOURCES, controller.state.value.page)
        assertEquals(b, controller.state.value.selected)
        assertTrue(controller.state.value.inventory.resources.isEmpty())
    }

    @Test
    fun lateOldRequestCannotReplaceNewAccount() = runTest {
        val slow = CompletableDeferred<Inventory>()
        val controller =
            AppController(
                backgroundScope,
                SessionFactory { connection ->
                    object : CloudSession by DemoSession(connection) {
                        override suspend fun inventory() =
                            if (connection.id == "a") slow.await()
                            else Inventory(emptyList(), emptyList())
                    }
                },
                MemoryStore(),
                listOf(a, b),
            )
        controller.refresh()
        runCurrent()
        controller.selectConnection(b)
        runCurrent()
        slow.complete(Inventory(listOf(Domain("old", "old.example", "active", "a")), emptyList()))
        runCurrent()
        assertEquals(b, controller.state.value.selected)
        assertTrue(controller.state.value.inventory.domains.isEmpty())
    }

    @Test
    fun invalidDnsDoesNotWrite() = runTest {
        var writes = 0
        val session =
            object : CloudSession by DemoSession(a) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    writes++
                }
            }
        val controller =
            AppController(backgroundScope, SessionFactory { session }, MemoryStore(), listOf(a))
        controller.refresh()
        runCurrent()
        val outcome =
            controller.saveDns(
                Domain("z", "example.com", "active", "a"),
                null,
                DnsDraft("A", "@", "999.1.1.1"),
            )
        assertEquals(OperationStatus.FAILED, outcome.status)
        assertEquals(0, writes)
    }

    @Test
    fun uncertainWriteIsNotRetried() = runTest {
        var writes = 0
        val session =
            object : CloudSession by DemoSession(a) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    writes++
                    throw CloudException("网络中断，结果待核对", true)
                }
            }
        val store = MemoryStore()
        val controller =
            AppController(backgroundScope, SessionFactory { session }, store, listOf(a))
        controller.refresh()
        runCurrent()
        val outcome =
            controller.saveDns(
                Domain("z", "example.com", "active", "a"),
                null,
                DnsDraft("A", "@", "192.0.2.1"),
            )
        assertEquals(OperationStatus.UNKNOWN, outcome.status)
        assertEquals(1, writes)
        assertEquals(OperationStatus.UNKNOWN, store.operations().single().status)
    }

    @Test
    fun cannotWriteZoneFromOtherConnection() = runTest {
        val controller =
            AppController(
                backgroundScope,
                SessionFactory { DemoSession(it) },
                MemoryStore(),
                listOf(a, b),
            )
        controller.refresh()
        runCurrent()
        assertEquals(
            OperationStatus.FAILED,
            controller
                .saveDns(
                    Domain("z", "example.com", "active", "b"),
                    null,
                    DnsDraft("A", "@", "192.0.2.1"),
                )
                .status,
        )
    }

    @Test
    fun demoEditPersistsWithinSession() = runTest {
        val demo = DemoSession(a)
        val first = demo.records("zone-demo").first()
        demo.save("zone-demo", first.id, DnsDraft("A", first.name, "192.0.2.99", proxied = true))
        assertEquals("192.0.2.99", demo.records("zone-demo").first().content)
    }

    @Test
    fun invalidatedCredentialCannotBeRecachedByOldFactory() = runTest {
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val controller =
            AppController(
                backgroundScope,
                SessionFactory { connection ->
                    val old = count++ < 2
                    if (old) gate.await()
                    object : CloudSession by DemoSession(connection) {
                        override suspend fun inventory() =
                            Inventory(
                                listOf(
                                    Domain(
                                        if (old) "old" else "new",
                                        if (old) "old.example" else "new.example",
                                        "active",
                                        connection.id,
                                    )
                                ),
                                emptyList(),
                            )
                    }
                },
                MemoryStore(),
                listOf(a),
            )
        controller.refresh()
        runCurrent()
        controller.invalidateSession(a.id)
        controller.refresh()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        controller.refresh()
        runCurrent()
        assertEquals("new.example", controller.state.value.inventory.domains.single().name)
    }

    // ------------------------------------------- tracked publication contracts (real receipt path)

    private class CoordinationStore : OperationStore {
        val tasks = linkedMapOf<String, OperationTask>()
        override fun get(id: String) = tasks[id]
        override fun list(connectionId: String) = tasks.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { tasks[task.id] = task }
        override fun removeConnection(connectionId: String) { tasks.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { tasks[task.id] = task }
    }

    /**
     * A receipt source that really runs the write: it must call [beforeSend] and then the
     * session, so a suspended gate is genuinely inside the confirmed-write path.
     */
    private class ReceiptPath(
        private val session: CloudSession,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : DnsReceiptSource {
        val entered = CompletableDeferred<Unit>()
        val sends = java.util.concurrent.atomic.AtomicInteger(0)
        var failure: (() -> Throwable)? = null

        override suspend fun write(command: DnsWriteCommand, beforeSend: () -> Unit): ExecutionReceipt {
            beforeSend()
            entered.complete(Unit)
            gate?.await()
            failure?.let { throw it() }
            sends.incrementAndGet()
            val recordId = command.recordId
            if (recordId == null) session.save(command.scope.zoneId, null, command.draft)
            else session.delete(command.scope.zoneId, recordId)
            return ExecutionReceipt.Completed()
        }
    }

    /** A dispatcher that really holds queued work until it is explicitly drained. */
    private class PausableDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        var paused = false

        fun drain() {
            paused = false
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (paused) queued.addLast(block) else block.run()
        }
    }

    /** A coordination store whose read can be held on a queued dispatcher and then fail. */
    private class FailingGetStore(private val onFail: () -> Unit) : OperationStore {
        val tasks = linkedMapOf<String, OperationTask>()
        var failNext = false
        var reads = 0
        override fun get(id: String): OperationTask? {
            reads++
            if (failNext) {
                onFail()
                throw IllegalStateException("synthetic store failure")
            }
            return tasks[id]
        }
        override fun list(connectionId: String) = tasks.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { tasks[task.id] = task }
        override fun removeConnection(connectionId: String) { tasks.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { tasks[task.id] = task }
    }

    private val real1 = Connection("a", "Production", "aa", demo = false)
    private val real2 = Connection("b", "Sandbox", "bb", demo = false)

    /** Opt-in wiring for real (non-demo) connections: only there is the confirmed path active. */
    private fun optIn(
        scope: kotlinx.coroutines.CoroutineScope,
        receipts: ReceiptPath,
        coordination: OperationStore = CoordinationStore(),
        storeDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ): DnsOperationLifecycle {
        val zone = ZoneScope(AccountScope(real1.id, real1.accountId), "z")
        val live = LiveOperationContext(real1, zone, 7, "a".repeat(64))
        val resolver =
            object : OperationContextResolver {
                override fun current() = live
                override fun liveConnections() = listOf(real1, real2)
                override fun isLive(scope: CloudScope, credentialRevision: Long?) = true
            }
        val plans = ChangePlans(resolver, { 1_000 }, OperationIds { java.util.UUID.randomUUID().toString() })
        val coordinator = OperationCoordinator(plans, coordination, resolver, { 1_000 }, scope, storeDispatcher)
        return DnsOperationLifecycle(resolver, plans, coordinator, { 1_000 }, { "a".repeat(64) }, { true }, receipts)
    }

    private fun domain() = Domain("z", "example.com", "active", real1.id)

    private fun draft() = DnsDraft("A", "@", "192.0.2.1")

    private fun marker() = Operation("marker", real1.id, "marker", OperationStatus.SUCCESS, "marker")

    @Test
    fun lateSaveFailureInsideTheReceiptPathNeverPublishesOrPersists() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session, gate)
        receipts.failure = { IllegalStateException("synthetic provider failure") }
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope, receipts))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.saveDns(domain(), null, draft()) } }
        runCurrent()
        assertTrue(receipts.entered.isCompleted, "the write must really be suspended inside the receipt path")
        assertTrue(job.isActive, "the save must still be in flight")
        controller.selectConnection(real2)
        runCurrent()
        controller.selectConnection(real1)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertEquals(0, receipts.sends.get(), "no session write may happen after a pre-send failure")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a late failure must not refresh the frozen UI")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun lateDeleteFailureInsideTheReceiptPathNeverPublishesOrPersists() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val record = DnsRecord("rec-1", "A", "@", "192.0.2.1", 300, false)
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session, gate)
        receipts.failure = { IllegalStateException("synthetic provider failure") }
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope, receipts))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.deleteDns(domain(), record) } }
        runCurrent()
        assertTrue(receipts.entered.isCompleted, "the delete must really be suspended inside the receipt path")
        assertTrue(job.isActive, "the delete must still be in flight")
        controller.reloadConnections(listOf(real2))
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertEquals(0, receipts.sends.get(), "no session write may happen after a pre-send failure")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a late failure must not refresh the frozen UI")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun lateSaveSuccessAfterANavigationKeepsTheFrozenUiAndNeverWrites() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session, gate)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope, receipts))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.saveDns(domain(), null, draft()) } }
        runCurrent()
        assertTrue(receipts.entered.isCompleted)
        controller.navigate(Page.RESOURCES)
        runCurrent()
        controller.navigate(Page.DOMAINS)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertEquals(1, receipts.sends.get(), "the legitimate session write must happen exactly once")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a late success must not refresh the frozen UI")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun lateSaveSuccessAfterASessionRevisionChangeKeepsTheFrozenUi() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session, gate)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope, receipts))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.saveDns(domain(), null, draft()) } }
        runCurrent()
        assertTrue(receipts.entered.isCompleted)
        controller.invalidateSession(real1.id)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a late success must not refresh a new session revision's UI")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun aPublicationControlStillRefreshesTheCurrentUi() = runTest {
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope, receipts))
        controller.refresh()
        runCurrent()
        store.putOperation(marker())
        controller.saveDns(domain(), null, draft())
        runCurrent()
        assertEquals(1, receipts.sends.get())
        assertTrue(
            controller.state.value.operations.any { it.id == "marker" },
            "a legitimate result must still refresh the current UI",
        )
    }

    @Test
    fun optInValidationFailurePersistsNoCallerSummaryAndReportsFailed() = runTest {
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1), optIn(backgroundScope, receipts))
        controller.refresh()
        runCurrent()
        val operation = controller.saveDns(domain(), null, DnsDraft("SRV", "@", "192.0.2.1"))
        runCurrent()
        assertEquals(OperationStatus.FAILED, operation.status, "a synchronous input error stays a deterministic FAILED")
        assertEquals(0, receipts.sends.get())
        assertEquals(0, store.operations().size, "the opt-in path must not persist a caller-side summary")
    }

    @Test
    fun legacyValidationFailureStillPersistsAFailureSummary() = runTest {
        val store = MemoryStore()
        val session = DemoSession(a)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(a))
        controller.refresh()
        runCurrent()
        val operation = controller.saveDns(domain(), null, DnsDraft("SRV", "@", "192.0.2.1"))
        runCurrent()
        assertEquals(OperationStatus.FAILED, operation.status)
        assertEquals(1, store.operations().size, "the default path keeps its historical behaviour")
    }

    @Test
    fun queuedSavePreSendFailureIsReportedFailedAndNeverPublished() = runTest {
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session)
        var markerInserted = false
        val dispatcher = PausableDispatcher()
        dispatcher.paused = true
        val coordination = FailingGetStore { store.putOperation(marker()); markerInserted = true }
        val controller =
            AppController(
                backgroundScope,
                SessionFactory { session },
                store,
                listOf(real1, real2),
                optIn(backgroundScope, receipts, coordination, dispatcher),
            )
        controller.refresh()
        runCurrent()
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertFalse(deferred.isCompleted, "the save must still be queued on the store dispatcher")
        assertEquals(0, coordination.reads, "the queued store read must not have run yet")
        assertFalse(markerInserted)
        controller.selectConnection(real2)
        runCurrent()
        controller.selectConnection(real1)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        coordination.failNext = true
        dispatcher.drain()
        advanceUntilIdle()
        val operation = deferred.await()
        assertEquals(OperationStatus.FAILED, operation.status, "a pre-send store failure is a deterministic FAILED")
        assertEquals(0, receipts.sends.get(), "no session write may happen")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "the frozen UI must not be republished")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
        assertTrue(coordination.tasks.isEmpty(), "no task row may be created by a failed pre-send read")
    }

    @Test
    fun queuedDeletePreSendFailureIsReportedFailedAndNeverPublished() = runTest {
        val store = MemoryStore()
        val record = DnsRecord("rec-1", "A", "@", "192.0.2.1", 300, false)
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session)
        var markerInserted = false
        val dispatcher = PausableDispatcher()
        dispatcher.paused = true
        val coordination = FailingGetStore { store.putOperation(marker()); markerInserted = true }
        val controller =
            AppController(
                backgroundScope,
                SessionFactory { session },
                store,
                listOf(real1, real2),
                optIn(backgroundScope, receipts, coordination, dispatcher),
            )
        controller.refresh()
        runCurrent()
        val deferred = async { controller.deleteDns(domain(), record) }
        runCurrent()
        assertFalse(deferred.isCompleted, "the delete must still be queued on the store dispatcher")
        assertEquals(0, coordination.reads, "the queued store read must not have run yet")
        assertFalse(markerInserted)
        controller.reloadConnections(listOf(real2))
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        coordination.failNext = true
        dispatcher.drain()
        advanceUntilIdle()
        val operation = deferred.await()
        assertEquals(OperationStatus.FAILED, operation.status, "a pre-send store failure is a deterministic FAILED")
        assertEquals(0, receipts.sends.get(), "no session write may happen")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "the frozen UI must not be republished")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
        assertTrue(coordination.tasks.isEmpty(), "no task row may be created by a failed pre-send read")
    }
}
