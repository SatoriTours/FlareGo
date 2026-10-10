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
        private val lock = Any()
        private val rows = linkedMapOf<String, OperationTask>()

        /** Immutable snapshot: the internal container is never exposed. */
        val tasks: Map<String, OperationTask> get() = synchronized(lock) { rows.toMap() }

        fun clearAll() = synchronized(lock) { rows.clear() }

        override fun get(id: String): OperationTask? = synchronized(lock) { rows[id] }
        override fun list(connectionId: String): List<OperationTask> = synchronized(lock) { rows.values.filter { it.connectionId == connectionId } }
        override fun put(task: OperationTask) { synchronized(lock) { rows[task.id] = task } }
        override fun removeConnection(connectionId: String) { synchronized(lock) { rows.entries.removeAll { it.value.connectionId == connectionId } } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { synchronized(lock) { rows[task.id] = task } }
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
        var lastCommand: DnsWriteCommand? = null
        /** When set, the write runs against a replaced credential for the same connection. */
        var alternate: CloudSession? = null

        override suspend fun write(command: DnsWriteCommand, beforeSend: () -> Unit): ExecutionReceipt {
            lastCommand = command
            entered.complete(Unit)
            gate?.await()
            failure?.let { throw it() }
            // The guard is evaluated immediately before the typed send.
            beforeSend()
            sends.incrementAndGet()
            val target = alternate ?: session
            val recordId = command.recordId
            if (recordId == null) target.save(command.scope.zoneId, null, command.draft)
            else target.delete(command.scope.zoneId, recordId)
            return ExecutionReceipt.Completed()
        }
    }

    /** A dispatcher that really holds queued work until it is explicitly drained. */
    private class PausableDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queued = ArrayDeque<Runnable>()
        private var held = false

        var paused: Boolean
            get() = synchronized(lock) { held }
            set(value) { synchronized(lock) { held = value } }

        fun drain() {
            synchronized(lock) { held = false }
            while (true) {
                val next: Runnable
                synchronized(lock) { next = if (queued.isEmpty()) return else queued.removeFirst() }
                next.run()
            }
        }

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            val hold: Boolean
            synchronized(lock) {
                hold = held
                if (hold) queued.addLast(block)
            }
            if (!hold) block.run()
        }
    }

    /** A coordination store whose read can be held on a queued dispatcher and then fail. */
    private class FailingGetStore(private val onFail: () -> Unit) : OperationStore {
        private val lock = Any()
        private val rows = linkedMapOf<String, OperationTask>()
        private var nextFails = false
        private var readCount = 0

        /** Immutable snapshot; the internal container never escapes. */
        val tasks: Map<String, OperationTask> get() = synchronized(lock) { rows.toMap() }

        var failNext: Boolean
            get() = synchronized(lock) { nextFails }
            set(value) { synchronized(lock) { nextFails = value } }

        val reads: Int get() = synchronized(lock) { readCount }

        override fun get(id: String): OperationTask? {
            val fails: Boolean
            synchronized(lock) {
                readCount++
                fails = nextFails
            }
            // The collaborating callback runs outside the lock: it may touch the same fixture.
            if (fails) {
                onFail()
                throw IllegalStateException("synthetic store failure")
            }
            return synchronized(lock) { rows[id] }
        }
        override fun list(connectionId: String): List<OperationTask> = synchronized(lock) { rows.values.filter { it.connectionId == connectionId } }
        override fun put(task: OperationTask) { synchronized(lock) { rows[task.id] = task } }
        override fun removeConnection(connectionId: String) { synchronized(lock) { rows.entries.removeAll { it.value.connectionId == connectionId } } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { synchronized(lock) { rows[task.id] = task } }
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

    private fun zoneFor(connection: Connection) = ZoneScope(AccountScope(connection.id, connection.accountId), "z")

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
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(receipts.entered.isCompleted, "the write must really be suspended inside the receipt path")
        assertFalse(deferred.isCompleted, "the save must still be in flight")
        controller.selectConnection(real2)
        runCurrent()
        controller.selectConnection(real1)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(0, receipts.sends.get(), "no session write may happen after a pre-send failure")
        // A failure inside the receipt is a post-send uncertainty: the engine returns the
        // original task conservatively, never a fabricated FAILED.
        assertEquals(OperationStatus.UNKNOWN, operation.status, "the conservative canonical result must be returned")
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
        val deferred = async { controller.deleteDns(domain(), record) }
        runCurrent()
        assertTrue(receipts.entered.isCompleted, "the delete must really be suspended inside the receipt path")
        assertFalse(deferred.isCompleted, "the delete must still be in flight")
        controller.reloadConnections(listOf(real2))
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(0, receipts.sends.get(), "no session write may happen after a pre-send failure")
        // A failure inside the receipt is a post-send uncertainty: the engine returns the
        // original task conservatively, never a fabricated FAILED.
        assertEquals(OperationStatus.UNKNOWN, operation.status, "the conservative canonical result must be returned")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a late failure must not refresh the frozen UI")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun lateSaveSuccessAfterANavigationKeepsTheFrozenUiAndNeverWrites() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session = DemoSession(real1)
        val receipts = ReceiptPath(session, gate)
        val fixture = optInFixture(backgroundScope, receipts)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(receipts.entered.isCompleted)
        assertFalse(deferred.isCompleted, "the write must still be in flight")
        controller.navigate(Page.RESOURCES)
        runCurrent()
        controller.navigate(Page.DOMAINS)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(OperationStatus.SUCCESS, operation.status, "the legitimate result must succeed")
        assertEquals(1, receipts.sends.get(), "the legitimate session write must happen exactly once")
        assertEquals(
            ZoneScope(AccountScope(real1.id, real1.accountId), "z"),
            receipts.lastCommand?.scope,
            "the write must stay bound to the original scope",
        )
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a late success must not refresh the frozen UI")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(operation.id, stored.id, "the published result must be the original task identity")
        assertEquals(TaskState.SUCCEEDED, stored.state)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
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
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(receipts.entered.isCompleted)
        assertFalse(deferred.isCompleted)
        controller.invalidateSession(real1.id)
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(OperationStatus.SUCCESS, operation.status)
        assertEquals(1, receipts.sends.get())
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

    /** The opt-in wiring, exposed so a test can observe the real coordination state. */
    private class OptInFixture(
        val lifecycle: DnsOperationLifecycle,
        val coordinator: OperationCoordinator,
        val plans: ChangePlans,
        val tasks: CoordinationStore,
        val receipts: ReceiptPath,
    )

    private fun optInFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        receipts: ReceiptPath = ReceiptPath(DemoSession(real1)),
        coordination: CoordinationStore = CoordinationStore(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ): OptInFixture {
        val zone = ZoneScope(AccountScope(real1.id, real1.accountId), "z")
        val live = LiveOperationContext(real1, zone, 7, "a".repeat(64))
        val resolver =
            object : OperationContextResolver {
                override fun current() = live
                override fun liveConnections() = listOf(real1, real2)
                override fun isLive(scope: CloudScope, credentialRevision: Long?) = true
            }
        val plans = ChangePlans(resolver, { 1_000 }, OperationIds { java.util.UUID.randomUUID().toString() })
        val coordinator = OperationCoordinator(plans, coordination, resolver, { 1_000 }, scope, dispatcher)
        val lifecycle = DnsOperationLifecycle(resolver, plans, coordinator, { 1_000 }, { "a".repeat(64) }, { true }, receipts)
        return OptInFixture(lifecycle, coordinator, plans, coordination, receipts)
    }

    @Test
    fun cooperativeDeletionThenInvalidDraftIsFailedWithoutResurrection() = runTest {
        val store = MemoryStore()
        val fixture = optInFixture(backgroundScope)
        val controller =
            AppController(backgroundScope, SessionFactory { DemoSession(real1) }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        // The collaboration delete runs through the same metadata latch as the writes.
        fixture.coordinator.withInvalidatedConnection(real1.id) {
            fixture.tasks.clearAll()
            store.removeConnection(real1.id)
        }
        val operation = controller.saveDns(domain(), null, DnsDraft("SRV", "@", "192.0.2.1"))
        runCurrent()
        assertEquals(OperationStatus.FAILED, operation.status, "an invalid draft stays a deterministic FAILED")
        assertEquals(0, fixture.receipts.sends.get(), "no session write may happen")
        assertTrue(fixture.tasks.tasks.isEmpty(), "the deleted task must not be resurrected")
        assertEquals(0, store.operations().size, "no caller-side summary may be persisted")
    }

    @Test
    fun lateSaveSuccessAfterACredentialReplacementKeepsTheFrozenUi() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        var first = true
        val factory =
            SessionFactory {
                if (first) {
                    first = false
                    DemoSession(real1)
                } else {
                    object : CloudSession by DemoSession(real1) {
                        override suspend fun inventory() = Inventory(emptyList(), emptyList())
                    }
                }
            }
        val receipts = ReceiptPath(DemoSession(real1), gate)
        var replacedSends = java.util.concurrent.atomic.AtomicInteger(0)
        val replaced =
            object : CloudSession by DemoSession(real1) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    replacedSends.incrementAndGet()
                }
            }
        val fixture = optInFixture(backgroundScope, receipts)
        val controller = AppController(backgroundScope, factory, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(receipts.entered.isCompleted, "the write must be inside the receipt path")
        assertFalse(deferred.isCompleted)
        // The same connection id gets a brand new credential/session while the write is in flight.
        controller.invalidateSession(real1.id)
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        val frozen = controller.state.value.operations.map { it.id }
        store.putOperation(marker())
        // The credential identity is swapped only now, while the write is provably suspended.
        receipts.alternate = replaced
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(OperationStatus.SUCCESS, operation.status)
        assertEquals(1, receipts.sends.get())
        assertEquals(1, replacedSends.get(), "the write must really have used the replaced credential")
        assertEquals(frozen, controller.state.value.operations.map { it.id }, "a replaced credential must not publish the old result")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    // ------------------------------------------ fix 22: independent authority + real acquisition

    /** Trusted authority for these tests: never derived from the UI selection or page. */
    private class CredentialState(
        private val connection: Connection,
        private val zoneId: String,
        private var revision: Long,
        private var inputFingerprint: String,
        private var live: Boolean = true,
    ) {
        private val lock = Any()

        fun snapshot(): LiveOperationContext? =
            synchronized(lock) {
                if (!live) null
                else LiveOperationContext(connection, ZoneScope(AccountScope(connection.id, connection.accountId), zoneId), revision, inputFingerprint)
            }

        fun bumpRevision() { synchronized(lock) { revision += 1 } }

        fun changeFingerprint() { synchronized(lock) { inputFingerprint = "b".repeat(64) } }

        fun revoke() { synchronized(lock) { live = false } }

        fun connections(): List<Connection> = listOf(connection)

        val isLive: Boolean get() = synchronized(lock) { live }
    }

    private class AuthorityResolver(private val authority: CredentialState) : OperationContextResolver {
        override fun current(): LiveOperationContext? = authority.snapshot()

        override fun liveConnections(): List<Connection> = authority.connections()

        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            val snapshot = authority.snapshot() ?: return false
            // The trusted snapshot must cover exactly this scope, connection, account and zone.
            // Zone scope equality already covers connection, account and zone.
            if (snapshot.scope != scope) return false
            if (credentialRevision != null && snapshot.credentialRevision != credentialRevision) return false
            return true
        }
    }

    private class AuthorityFixture(
        val lifecycle: DnsOperationLifecycle,
        val coordinator: OperationCoordinator,
        val plans: ChangePlans,
        val tasks: CoordinationStore,
        val resolver: AuthorityResolver,
        val authority: CredentialState,
    )

    private fun authorityFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        authority: CredentialState,
        receipts: DnsReceiptSource,
    ): AuthorityFixture {
        val resolver = AuthorityResolver(authority)
        val tasks = CoordinationStore()
        val plans = ChangePlans(resolver, { 1_000 }, OperationIds { java.util.UUID.randomUUID().toString() })
        val coordinator = OperationCoordinator(plans, tasks, resolver, { 1_000 }, scope, kotlinx.coroutines.Dispatchers.Unconfined)
        val lifecycle = DnsOperationLifecycle(resolver, plans, coordinator, { 1_000 }, { "a".repeat(64) }, { true }, receipts)
        return AuthorityFixture(lifecycle, coordinator, plans, tasks, resolver, authority)
    }

    /** Test-only completed receipt: the guard runs first, then the typed write is dispatched. */
    private class CompletedReceipt(private val session: CountingSession) : DnsReceiptSource {
        val sends = java.util.concurrent.atomic.AtomicInteger(0)
        var lastCommand: DnsWriteCommand? = null

        override suspend fun write(command: DnsWriteCommand, beforeSend: () -> Unit): ExecutionReceipt {
            lastCommand = command
            beforeSend()
            sends.incrementAndGet()
            when (command.actionKey) {
                DnsOperationLifecycle.ACTION_CREATE, DnsOperationLifecycle.ACTION_PATCH ->
                    session.save(command.scope.zoneId, command.recordId, command.draft)
                else -> session.delete(command.scope.zoneId, command.recordId.orEmpty())
            }
            return ExecutionReceipt.Completed()
        }
    }

    /** A test session that counts, signals and can gate the typed write it receives. */
    private class CountingSession(private val base: CloudSession) : CloudSession by base {
        private val lock = Any()
        private var saves = 0
        private var deletes = 0
        val entered = java.util.concurrent.CountDownLatch(1)
        var gate: CompletableDeferred<Unit>? = null

        val saveCount: Int get() = synchronized(lock) { saves }
        val deleteCount: Int get() = synchronized(lock) { deletes }

        override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
            synchronized(lock) { saves++ }
            entered.countDown()
            gate?.await()
        }

        override suspend fun delete(zoneId: String, recordId: String) {
            synchronized(lock) { deletes++ }
            entered.countDown()
            gate?.await()
        }
    }

    private fun newSessionFactory(counter: java.util.concurrent.atomic.AtomicInteger, identities: MutableList<CloudSession>): SessionFactory =
        SessionFactory {
            val fresh = CountingSession(DemoSession(it))
            counter.incrementAndGet()
            identities += fresh
            fresh
        }

    @Test
    fun postSendSaveSelectionRoundTripKeepsTheFrozenUiAndTheWholeRow() = runTest {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val session = CountingSession(DemoSession(real1))
        val receipts = CompletedReceipt(session)
        val fixture = authorityFixture(backgroundScope, authority, receipts)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        // The typed write really happened before the UI changes.
        assertTrue(session.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the typed save must have happened")
        assertEquals(1, session.saveCount, "exactly one typed save")
        assertFalse(deferred.isCompleted, "the write must still be in flight")
        val preSend = fixture.tasks.tasks.values.single()
        assertEquals(TaskState.RUNNING, preSend.state, "the pre-send metadata row must be RUNNING")
        controller.selectConnection(real2)
        runCurrent()
        controller.selectConnection(real1)
        runCurrent()
        val frozenOperations = controller.state.value.operations
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(1, receipts.sends.get())
        assertEquals(1, session.saveCount)
        assertEquals(frozenOperations, controller.state.value.operations, "the frozen UI must not be republished")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(preSend.id, stored.id)
        assertEquals(preSend.scope, stored.scope)
        assertEquals(preSend.connectionId, stored.connectionId)
        assertEquals(TaskState.SUCCEEDED, stored.state)
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation, "the returned summary must match the stored row")
        val command = receipts.lastCommand
        assertNotNull(command)
        assertEquals(stored.scope, command.scope, "the write must stay bound to the original scope")
    }

    @Test
    fun postSendDeleteSelectionRoundTripKeepsTheFrozenUiAndTheWholeRow() = runTest {
        val store = MemoryStore()
        val record = DnsRecord("rec-1", "A", "@", "192.0.2.1", 300, false)
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val session = CountingSession(DemoSession(real1))
        val receipts = CompletedReceipt(session)
        val fixture = authorityFixture(backgroundScope, authority, receipts)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val deferred = async { controller.deleteDns(domain(), record) }
        runCurrent()
        assertTrue(session.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the typed delete must have happened")
        assertEquals(1, session.deleteCount, "exactly one typed delete")
        assertFalse(deferred.isCompleted)
        controller.selectConnection(real2)
        runCurrent()
        controller.selectConnection(real1)
        runCurrent()
        val frozenOperations = controller.state.value.operations
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(0, session.saveCount)
        assertEquals(1, session.deleteCount)
        assertEquals(frozenOperations, controller.state.value.operations)
        assertEquals(listOf("marker"), store.operations().map { it.id })
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
    }

    @Test
    fun legacyFreshFactoryAcquisitionIsRealAndUnchangedControlReportsUnknown() = runTest {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val created = java.util.concurrent.atomic.AtomicInteger(0)
        val identities = mutableListOf<CloudSession>()
        val factory = newSessionFactory(created, identities)
        val acquired = java.util.concurrent.CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val legacy = LegacyDnsReceiptSource {
            val fresh = factory.create(real1)
            acquired.countDown()
            release.await()
            fresh
        }
        val fixture = authorityFixture(backgroundScope, authority, legacy)
        val controller = AppController(backgroundScope, factory, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val initial = created.get()
        assertTrue(initial >= 1, "the controller refresh must have acquired a session")
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS), "the fresh factory acquisition must be entered")
        assertEquals(initial + 1, created.get(), "the legacy path must acquire its own fresh session")
        val fresh = identities.last() as CountingSession
        assertEquals(0, fresh.saveCount, "no typed write may happen before the acquisition completes")
        release.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(1, fresh.saveCount, "the acquired session must receive the typed write")
        assertEquals(OperationStatus.UNKNOWN, operation.status, "the legacy Unit adapter reports UNKNOWN")
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
    }

    @Test
    fun legacyFreshAcquisitionWithARevokedAuthoritySendsNothing() = runTest {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val created = java.util.concurrent.atomic.AtomicInteger(0)
        val identities = mutableListOf<CloudSession>()
        val factory = newSessionFactory(created, identities)
        val acquired = java.util.concurrent.CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val legacy = LegacyDnsReceiptSource {
            val fresh = factory.create(real1)
            acquired.countDown()
            release.await()
            fresh
        }
        val fixture = authorityFixture(backgroundScope, authority, legacy)
        val controller = AppController(backgroundScope, factory, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        try {
            assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS), "the fresh acquisition must be entered")
            assertFalse(deferred.isCompleted, "the write must still be in flight")
            // The authority is revoked while the fresh session is still being acquired.
            authority.revoke()
        } finally {
            release.complete(Unit)
        }
        val operation = deferred.await()
        val fresh = identities.last() as CountingSession
        assertEquals(0, fresh.saveCount, "a revoked authority must not reach the typed write")
        assertEquals(OperationStatus.UNKNOWN, operation.status, "the refusal stays conservative, never a fabricated FAILED")
        val stored = fixture.tasks.tasks.values.single()
        assertTrue(stored.state == TaskState.RUNNING || stored.state == TaskState.UNKNOWN)
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
    }

    @Test
    fun optInSelectionRoundTripWithACompletedReceiptKeepsTheFrozenUi() = runTest {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val session = CountingSession(DemoSession(real1))
        val receipts = CompletedReceipt(session)
        val fixture = authorityFixture(backgroundScope, authority, receipts)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(session.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the typed save must have happened")
        assertEquals(1, session.saveCount)
        assertFalse(deferred.isCompleted, "the write must still be in flight")
        controller.selectConnection(real2)
        runCurrent()
        controller.selectConnection(real1)
        runCurrent()
        val frozen = controller.state.value.operations
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(OperationStatus.SUCCESS, operation.status, "a completed receipt must report SUCCESS")
        assertEquals(1, receipts.sends.get())
        assertEquals(frozen, controller.state.value.operations, "the frozen UI must not be republished")
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(TaskState.SUCCEEDED, stored.state)
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
    }

    @Test
    fun legacyFactoryAcquisitionUnchangedControlReturnsUnknown() = runTest {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val session = CountingSession(DemoSession(real1))
        val acquisitions = java.util.concurrent.atomic.AtomicInteger(0)
        val acquired = java.util.concurrent.CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        // The legacy adapter acquires its session through the supplier and then guards and writes.
        val legacy = LegacyDnsReceiptSource {
            acquisitions.incrementAndGet()
            acquired.countDown()
            release.await()
            session
        }
        val fixture = authorityFixture(backgroundScope, authority, legacy)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS), "the supplier acquisition must be entered")
        assertFalse(deferred.isCompleted, "the write must still be in flight")
        assertEquals(0, session.saveCount, "no typed write may happen before the acquisition completes")
        release.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(1, acquisitions.get())
        assertEquals(1, session.saveCount, "the acquired session must receive the typed write")
        assertEquals(OperationStatus.UNKNOWN, operation.status, "the legacy Unit adapter reports UNKNOWN")
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(TaskState.UNKNOWN, stored.state, "the settled legacy row is the literal UNKNOWN state")
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
    }

    @Test
    fun postSendSaveMembershipRoundTripKeepsTheFrozenUi() = runTest {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val session = CountingSession(DemoSession(real1))
        val receipts = CompletedReceipt(session)
        val fixture = authorityFixture(backgroundScope, authority, receipts)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val deferred = async { controller.saveDns(domain(), null, draft()) }
        runCurrent()
        assertTrue(session.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the typed save must have happened")
        assertEquals(1, session.saveCount)
        assertFalse(deferred.isCompleted)
        // Membership changes while the write is parked, then returns to the original set.
        controller.reloadConnections(listOf(real2))
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        val frozen = controller.state.value.operations
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(1, session.saveCount)
        assertEquals(frozen, controller.state.value.operations, "a membership change must not be republished into")
        assertEquals(listOf("marker"), store.operations().map { it.id })
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(TaskState.SUCCEEDED, stored.state)
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
    }

    @Test
    fun postSendDeleteMembershipRoundTripKeepsTheFrozenUi() = runTest {
        val store = MemoryStore()
        val record = DnsRecord("rec-1", "A", "@", "192.0.2.1", 300, false)
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val session = CountingSession(DemoSession(real1))
        val receipts = CompletedReceipt(session)
        val fixture = authorityFixture(backgroundScope, authority, receipts)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val deferred = async { controller.deleteDns(domain(), record) }
        runCurrent()
        assertTrue(session.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the typed delete must have happened")
        assertEquals(1, session.deleteCount)
        assertFalse(deferred.isCompleted)
        controller.reloadConnections(listOf(real2))
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        val frozen = controller.state.value.operations
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        val operation = deferred.await()
        assertEquals(0, session.saveCount)
        assertEquals(1, session.deleteCount)
        assertEquals(frozen, controller.state.value.operations)
        assertEquals(listOf("marker"), store.operations().map { it.id })
        val stored = fixture.tasks.tasks.values.single()
        assertEquals(TaskState.SUCCEEDED, stored.state)
        assertEquals(OperationTaskPersistence.summaryFor(stored), operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
    }

    /** Owns every gate, job and drainable a scenario creates; teardown joins with a real bound. */
    private class ManagedResources {
        private val lock = Any()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        private val jobs = mutableListOf<kotlinx.coroutines.Job>()
        private val drains = mutableListOf<() -> Unit>()

        fun gate(gate: CompletableDeferred<Unit>) { synchronized(lock) { gates += gate } }
        fun job(job: kotlinx.coroutines.Job) { synchronized(lock) { jobs += job } }
        fun drainWith(action: () -> Unit) { synchronized(lock) { drains += action } }

        /** Release every gate and drain the queues so parked work can proceed. */
        fun releaseGates() {
            synchronized(lock) { gates.toList() }.forEach { it.complete(Unit) }
            synchronized(lock) { drains.toList() }.forEach { action -> runCatching { action() } }
        }

        /** Release, then cancel and really join every owned job with a bounded wait. */
        fun teardown() {
            releaseGates()
            val owned = synchronized(lock) { jobs.toList() }
            owned.forEach { it.cancel() }
            val finished = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(10_000) { owned.forEach { it.join() }; true } }
            assertTrue(finished == true, "every owned job must complete within the real bound")
            synchronized(lock) {
                gates.clear()
                jobs.clear()
                drains.clear()
            }
        }
    }

    /** A real SessionFactory whose fresh create awaits its gate *inside* create. */
    private class ManagedFactory : SessionFactory {
        private val lock = Any()
        private var creates = 0
        private val identities = mutableListOf<CloudSession>()
        private val parameters = mutableListOf<Connection>()
        private var freshGate: CompletableDeferred<Unit>? = null
        val freshEntered = java.util.concurrent.CountDownLatch(1)

        val createCount: Int get() = synchronized(lock) { creates }
        val lastIdentity: CloudSession get() = synchronized(lock) { identities.last() }
        val firstIdentity: CloudSession get() = synchronized(lock) { identities.first() }
        val lastParameter: Connection get() = synchronized(lock) { parameters.last() }

        fun gateTheFreshAcquisition(gate: CompletableDeferred<Unit>) { synchronized(lock) { freshGate = gate } }

        override suspend fun create(connection: Connection): CloudSession {
            val session = CountingSession(DemoSession(connection))
            val gate: CompletableDeferred<Unit>?
            val count: Int
            synchronized(lock) {
                creates++
                count = creates
                identities += session
                parameters += connection
                gate = freshGate
            }
            if (count >= 2 && gate != null) {
                freshEntered.countDown()
                // The gate is awaited inside create, so create has not returned yet.
                gate.await()
            }
            return session
        }
    }

    private enum class AuthorityMutation { NONE, REVISION, FINGERPRINT, REVOKE, UI_EPOCH_ONLY }

    private class LegacyAwaitCell(
        val controller: AppController,
        val fixture: AuthorityFixture,
        val store: MemoryStore,
        val factory: ManagedFactory,
        val authority: CredentialState,
        val actualInitial: CloudSession,
        val fresh: CountingSession,
        val operation: Operation,
        val preSend: OperationTask,
    )

    private suspend fun runLegacyFactoryAwaitCell(
        mutation: AuthorityMutation,
        uiRoundTrip: Boolean,
        scope: kotlinx.coroutines.CoroutineScope,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ): LegacyAwaitCell {
        val store = MemoryStore()
        val authority = CredentialState(real1, "z", 7, "a".repeat(64))
        val factory = ManagedFactory()
        val legacy = LegacyDnsReceiptSource { factory.create(real1) }
        val fixture = authorityFixture(scope, authority, legacy)
        val controller = AppController(scope, factory, store, listOf(real1, real2), fixture.lifecycle)
        controller.refresh()
        scheduler.runCurrent()
        // The session the controller really acquired during refresh.
        val actualInitial = factory.firstIdentity
        assertEquals(1, factory.createCount, "the refresh must acquire exactly one session")
        assertEquals(real1, factory.lastParameter, "the factory must be asked for the trusted connection")
        val resources = ManagedResources()
        var caller: kotlinx.coroutines.Deferred<Operation>? = null
        var firstFailure: Throwable? = null
        var preSend: OperationTask? = null
        try {
            val gate = CompletableDeferred<Unit>()
            resources.gate(gate)
            factory.gateTheFreshAcquisition(gate)
            caller = kotlinx.coroutines.CoroutineScope(scope.coroutineContext).async { controller.saveDns(domain(), null, draft()) }
            resources.job(caller)
            scheduler.runCurrent()
            assertTrue(factory.freshEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the fresh create must be entered")
            assertEquals(2, factory.createCount, "the fresh acquisition must be the second create")
            assertFalse(caller.isCompleted, "the write must still be in flight")
            val freshNow = factory.lastIdentity as CountingSession
            assertNotSame(actualInitial, freshNow, "the fresh acquisition must be a different session identity")
            assertEquals(0, freshNow.saveCount, "no typed write may happen while create is still parked")
            assertEquals(0, freshNow.deleteCount, "no typed delete may happen while create is still parked")
            assertEquals(real1, factory.lastParameter, "the fresh acquisition must use the trusted connection")
            // The metadata row that exists before any settlement decision.
            val captured = fixture.tasks.tasks.values.single()
            assertEquals(TaskState.RUNNING, captured.state, "the captured row must be the pre-send RUNNING row")
            assertEquals(zoneFor(real1), captured.scope, "the captured row must keep the original scope")
            preSend = captured
            when (mutation) {
                AuthorityMutation.REVISION -> authority.bumpRevision()
                AuthorityMutation.FINGERPRINT -> authority.changeFingerprint()
                AuthorityMutation.REVOKE -> authority.revoke()
                AuthorityMutation.NONE, AuthorityMutation.UI_EPOCH_ONLY -> Unit
            }
            if (uiRoundTrip) {
                // A pure UI epoch change: navigation only, so the original authority stays live.
                controller.navigate(Page.RESOURCES)
                scheduler.runCurrent()
                controller.navigate(Page.DOMAINS)
                scheduler.runCurrent()
            }
            store.putOperation(marker())
        } catch (failure: Throwable) {
            firstFailure = failure
        } finally {
            // Release only: the caller must be able to finish before the registry joins it.
            resources.releaseGates()
        }
        scheduler.runCurrent()
        val operation = caller?.await() ?: throw IllegalStateException("the caller was never created")
        resources.teardown()
        firstFailure?.let { throw it }
        val fresh = factory.lastIdentity as CountingSession
        val captured = requireNotNull(preSend) { "the pre-send row must have been captured" }
        return LegacyAwaitCell(controller, fixture, store, factory, authority, actualInitial, fresh, operation, captured)
    }

    @Test
    fun legacyFactoryAwaitUnchangedControlReportsUnknownWithTheWholeRow() = runTest {
        val cell = runLegacyFactoryAwaitCell(AuthorityMutation.NONE, uiRoundTrip = false, backgroundScope, testScheduler)
        assertNotSame(cell.actualInitial, cell.fresh, "the fresh acquisition must be a different session identity")
        assertEquals(2, cell.factory.createCount, "one initial and one fresh acquisition")
        assertEquals(1, cell.fresh.saveCount, "the fresh session must receive the typed write")
        assertEquals(OperationStatus.UNKNOWN, cell.operation.status, "the legacy Unit adapter reports UNKNOWN")
        val stored = cell.fixture.tasks.tasks.values.single()
        assertEquals(TaskState.UNKNOWN, stored.state, "the settled legacy row is the literal UNKNOWN state")
        assertEquals(cell.preSend.copy(state = TaskState.UNKNOWN), stored, "only the state may change on settlement")
        assertEquals(OperationTaskPersistence.summaryFor(stored), cell.operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
        assertEquals(listOf("marker"), cell.store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun legacyFactoryAwaitWithARevisionChangeSendsNothing() = runTest {
        val cell = runLegacyFactoryAwaitCell(AuthorityMutation.REVISION, uiRoundTrip = false, backgroundScope, testScheduler)
        assertNotSame(cell.actualInitial, cell.fresh)
        assertEquals(0, cell.fresh.saveCount, "a revoked revision must not reach the typed write")
        assertEquals(0, cell.fresh.deleteCount)
        assertEquals(OperationStatus.UNKNOWN, cell.operation.status, "the refusal stays conservative")
        val stored = cell.fixture.tasks.tasks.values.single()
        assertEquals(cell.preSend, stored, "a refused send must leave the pre-send row unchanged")
        assertEquals(OperationTaskPersistence.summaryFor(stored), cell.operation)
        assertEquals(ZoneScope(AccountScope(real1.id, real1.accountId), "z"), stored.scope)
    }

    @Test
    fun legacyFactoryAwaitWithAFingerprintChangeSendsNothing() = runTest {
        val cell = runLegacyFactoryAwaitCell(AuthorityMutation.FINGERPRINT, uiRoundTrip = false, backgroundScope, testScheduler)
        assertEquals(0, cell.fresh.saveCount, "a changed input fingerprint must not reach the typed write")
        assertEquals(OperationStatus.UNKNOWN, cell.operation.status)
        val stored = cell.fixture.tasks.tasks.values.single()
        assertEquals(cell.preSend, stored, "a refused send must leave the pre-send row unchanged")
        assertEquals(OperationTaskPersistence.summaryFor(stored), cell.operation)
    }

    @Test
    fun legacyFactoryAwaitWithARevokedAuthoritySendsNothing() = runTest {
        val cell = runLegacyFactoryAwaitCell(AuthorityMutation.REVOKE, uiRoundTrip = false, backgroundScope, testScheduler)
        assertEquals(0, cell.fresh.saveCount, "a revoked authority must not reach the typed write")
        assertEquals(OperationStatus.UNKNOWN, cell.operation.status)
        val stored = cell.fixture.tasks.tasks.values.single()
        assertEquals(cell.preSend, stored, "a revoked authority must leave the pre-send row unchanged")
        assertEquals(OperationTaskPersistence.summaryFor(stored), cell.operation)
    }

    @Test
    fun legacyFactoryAwaitWithOnlyAuiEpochChangeStillWritesOnce() = runTest {
        val cell = runLegacyFactoryAwaitCell(AuthorityMutation.UI_EPOCH_ONLY, uiRoundTrip = true, backgroundScope, testScheduler)
        assertEquals(1, cell.fresh.saveCount, "a UI-only change must not revoke the authority")
        assertEquals(OperationStatus.UNKNOWN, cell.operation.status)
        val stored = cell.fixture.tasks.tasks.values.single()
        assertEquals(cell.preSend.copy(state = TaskState.UNKNOWN), stored, "only the state may change on settlement")
        assertEquals(OperationTaskPersistence.summaryFor(stored), cell.operation)
        assertEquals(listOf("marker"), cell.store.operations().map { it.id })
    }
}
