package com.flarego.core

import com.flarego.core.application.AppController
import com.flarego.core.application.operations.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
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

    // ------------------------------------------- fix 6 tracked publication contracts

    private class CoordinationStore : OperationStore {
        val tasks = linkedMapOf<String, OperationTask>()
        override fun get(id: String) = tasks[id]
        override fun list(connectionId: String) = tasks.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { tasks[task.id] = task }
        override fun removeConnection(connectionId: String) { tasks.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { tasks[task.id] = task }
    }

    private val real1 = Connection("a", "Production", "aa", demo = false)
    private val real2 = Connection("b", "Sandbox", "bb", demo = false)

    /** Opt-in wiring for real (non-demo) connections: the confirmed-write path is only active there. */
    private fun optIn(scope: kotlinx.coroutines.CoroutineScope): DnsOperationLifecycle {
        val zone = ZoneScope(AccountScope(real1.id, real1.accountId), "z")
        val live = LiveOperationContext(real1, zone, 7, "a".repeat(64))
        val resolver =
            object : OperationContextResolver {
                override fun current() = live
                override fun liveConnections() = listOf(real1, real2)
                override fun isLive(scope: CloudScope, credentialRevision: Long?) = true
            }
        val plans = ChangePlans(resolver, { 1_000 }, OperationIds { java.util.UUID.randomUUID().toString() })
        val coordinator = OperationCoordinator(plans, CoordinationStore(), resolver, { 1_000 }, scope, kotlinx.coroutines.Dispatchers.Unconfined)
        return DnsOperationLifecycle(resolver, plans, coordinator, { 1_000 }, { "a".repeat(64) }, { true }) { _, beforeSend ->
            beforeSend()
            ExecutionReceipt.Completed()
        }
    }

    private fun domain() = Domain("z", "example.com", "active", a.id)

    private fun draft() = DnsDraft("A", "@", "192.0.2.1")

    private fun marker() = Operation("marker", a.id, "marker", OperationStatus.SUCCESS, "marker")

    @Test
    fun lateSaveFailureAfterASelectionRoundTripKeepsTheFrozenUiSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session =
            object : CloudSession by DemoSession(real1) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    gate.await()
                    throw IllegalStateException("synthetic provider failure")
                }
            }
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.saveDns(domain(), null, draft()) } }
        runCurrent()
        // The UI genuinely round-trips while the write is suspended.
        controller.selectConnection(b)
        runCurrent()
        controller.selectConnection(a)
        runCurrent()
        // Freeze the resulting UI snapshot, then let an independent known marker land in the store.
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertTrue(
            controller.state.value.operations.none { it.id == "marker" },
            "a late failure must not publish into the frozen UI snapshot",
        )
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun lateDeleteFailureAfterAReloadKeepsTheFrozenUiSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val record = DnsRecord("rec-1", "A", "@", "192.0.2.1", 300, false)
        val session =
            object : CloudSession by DemoSession(real1) {
                override suspend fun delete(zoneId: String, recordId: String) {
                    gate.await()
                    throw IllegalStateException("synthetic provider failure")
                }
            }
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.deleteDns(domain(), record) } }
        runCurrent()
        controller.reloadConnections(listOf(b))
        runCurrent()
        controller.reloadConnections(listOf(real1, real2))
        runCurrent()
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertTrue(
            controller.state.value.operations.none { it.id == "marker" },
            "a late delete failure must not publish into the frozen UI snapshot",
        )
        assertEquals(listOf("marker"), store.operations().map { it.id }, "no caller-side summary may be persisted")
    }

    @Test
    fun optInValidationFailurePersistsNoCallerSummaryAndReportsFailed() = runTest {
        val store = MemoryStore()
        val session = DemoSession(real1)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1), optIn(backgroundScope))
        controller.refresh()
        runCurrent()
        val operation = controller.saveDns(domain(), null, DnsDraft("SRV", "@", "192.0.2.1"))
        runCurrent()
        assertEquals(OperationStatus.FAILED, operation.status, "a synchronous input error stays a deterministic FAILED")
    }

    @Test
    fun optInValidationFailurePersistsNoCallerSideSummaryAtAll() = runTest {
        val store = MemoryStore()
        val session = DemoSession(real1)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1), optIn(backgroundScope))
        controller.refresh()
        runCurrent()
        controller.saveDns(domain(), null, DnsDraft("SRV", "@", "192.0.2.1"))
        runCurrent()
        assertEquals(0, store.operations().size, "the opt-in path must not persist a caller-side summary")
    }

    @Test
    fun legacyValidationFailureStillPersistsAFailureSummary() = runTest {
        val store = MemoryStore()
        val session = DemoSession(real1)
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1))
        controller.refresh()
        runCurrent()
        val operation = controller.saveDns(domain(), null, DnsDraft("SRV", "@", "192.0.2.1"))
        runCurrent()
        assertEquals(OperationStatus.FAILED, operation.status)
        assertEquals(1, store.operations().size, "the default path keeps its historical behaviour")
    }

    @Test
    fun lateSaveSuccessAfterANavigationKeepsTheStoreAndUiClean() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session =
            object : CloudSession by DemoSession(real1) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    gate.await()
                }
            }
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.saveDns(domain(), null, draft()) } }
        runCurrent()
        controller.navigate(Page.RESOURCES)
        runCurrent()
        controller.navigate(Page.DOMAINS)
        runCurrent()
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertTrue(
            controller.state.value.operations.none { it.id == "marker" },
            "a late success must not publish into a UI that navigated away and back",
        )
        assertEquals(0, store.operations().count { it.id != "marker" }, "a late success must not write caller-side summaries")
    }

    @Test
    fun lateSaveSuccessAfterASessionRevisionChangeKeepsTheUiAndStoreClean() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val session =
            object : CloudSession by DemoSession(real1) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    gate.await()
                }
            }
        val controller = AppController(backgroundScope, SessionFactory { session }, store, listOf(real1, real2), optIn(backgroundScope))
        controller.refresh()
        runCurrent()
        val job = launch { runCatching { controller.saveDns(domain(), null, draft()) } }
        runCurrent()
        controller.invalidateSession(real1.id)
        runCurrent()
        store.putOperation(marker())
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertTrue(
            controller.state.value.operations.none { it.id == "marker" },
            "a late success must not publish after the session revision changed",
        )
        assertEquals(0, store.operations().count { it.id != "marker" }, "a late success must not write caller-side summaries")
    }
}
