package com.flarego.core

import com.flarego.core.application.AppController
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
}
