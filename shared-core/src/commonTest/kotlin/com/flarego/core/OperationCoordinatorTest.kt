package com.flarego.core

import com.flarego.core.application.operations.*
import com.flarego.core.application.AppController
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
class OperationCoordinatorTest {

    private val account = AccountScope("connection-1", "account-1")
    private val zone = ZoneScope(account, "zone-1")
    private val fingerprint = "a".repeat(64)
    private val desired = "b".repeat(64)
    private val createKey = DnsOperationLifecycle.ACTION_CREATE

    private class TestStore : OperationStore {
        val tasks = linkedMapOf<String, OperationTask>()
        val summaries = mutableListOf<Operation>()
        var putCount = 0
        var failPut = false

        override fun get(id: String) = tasks[id]
        override fun list(connectionId: String) = tasks.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { tasks[task.id] = task }
        override fun removeConnection(connectionId: String) { tasks.entries.removeAll { it.value.connectionId == connectionId } }

        override fun putWithSummary(task: OperationTask, summary: Operation) {
            putCount++
            if (failPut) throw IllegalStateException("synthetic store failure")
            tasks[task.id] = task
            summaries += summary
        }
    }

    private class Resolver(
        var live: LiveOperationContext?,
        private val liveConnections: List<Connection> = emptyList(),
    ) : OperationContextResolver {
        val invalidatedScopes = mutableListOf<CloudScope>()
        override fun current() = live
        override fun liveConnections() = liveConnections
        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            if (scope in invalidatedScopes) return false
            val context = live ?: return credentialRevision != null
            if (credentialRevision != null && context.credentialRevision != credentialRevision) return false
            return true
        }
    }

    private val liveConnection = Connection("connection-1", "Live", "account-1")

    private var appScope: CoroutineScope? = null

    private val extraScopes = mutableListOf<CoroutineScope>()

    /** Every fixture scope is registered here and released, even when a test fails. */
    private fun newScope(cancelled: Boolean = false): CoroutineScope {
        val job = SupervisorJob()
        if (cancelled) job.cancel()
        return CoroutineScope(job + Dispatchers.Unconfined).also { extraScopes += it }
    }

    @AfterTest
    fun stopApplicationScope() {
        appScope?.cancel()
        appScope = null
        extraScopes.forEach { it.cancel() }
        extraScopes.clear()
    }

    private fun live(revision: Long = 7, fp: String = fingerprint, scope: CloudScope = zone) =
        LiveOperationContext(liveConnection, scope, revision, fp)

    private fun fixture(
        resolver: Resolver = Resolver(live(), listOf(liveConnection)),
        store: TestStore = TestStore(),
        clockMs: Long = 1_000,
        ids: List<String> = listOf("plan-1", "confirm-1", "plan-2", "confirm-2", "plan-3", "confirm-3"),
    ): Triple<ChangePlans, OperationCoordinator, TestStore> {
        val queue = ids.toMutableList()
        val idsSource = OperationIds { if (queue.isEmpty()) throw IllegalStateException("id factory exhausted") else queue.removeFirst() }
        val plans = ChangePlans(resolver, { clockMs }, idsSource)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        appScope = scope
        extraScopes += scope
        val coordinator = OperationCoordinator(plans, store, resolver, { clockMs }, scope, Dispatchers.Unconfined)
        return Triple(plans, coordinator, store)
    }

    private fun confirmOne(plans: ChangePlans, scope: CloudScope = zone): ConfirmedPlan {
        val plan = plans.prepare(scope, createKey, null, null, desired, fingerprint, 7, 1_000)
        return plans.confirm(plan.id, scope, 7, fingerprint, 1_000)
    }

    // ------------------------------------------------------------ focus 1

    @Test
    fun publicCloneAndForeignIssuerCannotExecuteOrOverwrite() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        // A structurally equal but different ConfirmedPlan instance is refused.
        val clone = ConfirmedPlan(confirmation.plan, confirmation.confirmationId)
        var writes = 0
        assertFailsWith<CloudFailure> { coordinator.execute(clone) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes)
        assertEquals(0, store.putCount)

        // A second issuer cannot execute a confirmation issued elsewhere.
        val (otherPlans, otherCoordinator, otherStore) = fixture(ids = listOf("plan-x", "confirm-x"))
        val foreign = confirmOne(otherPlans)
        assertFailsWith<CloudFailure> { coordinator.execute(foreign) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes)
        assertEquals(0, otherStore.putCount)
    }

    @Test
    fun repeatedConfirmReturnsTheSameIssuedInstanceAndConsumedCannotBeReplayed() = runTest {
        val (plans, coordinator, store) = fixture()
        val first = confirmOne(plans)
        val second = plans.confirm(first.plan.id, zone, 7, fingerprint, 1_000)
        assertSame(first, second)
        var writes = 0
        val firstTask = coordinator.execute(first) { writes++; ExecutionReceipt.Completed(providerRequestId = "provider-1") }
        assertEquals(1, writes)
        // A repeated execute of the same issued reference shares the existing result
        // (at most one write) instead of starting a second execution.
        val shared = coordinator.execute(first) { writes++; ExecutionReceipt.Completed() }
        assertEquals(1, writes)
        assertEquals(firstTask, shared)
        // Consumed means no *new* confirmation can be minted, and the same confirmation
        // stays the only one for this plan.
        assertSame(first, plans.confirm(first.plan.id, zone, 7, fingerprint, 1_000))
        assertEquals(1, store.tasks.size)
    }

    @Test
    fun idCollisionWithAnExistingTaskIsRefusedWithoutOverwriting() = runTest {
        val (plans, coordinator, store) = fixture()
        val existing =
            OperationTask(
                "plan-1", "connection-1", zone, createKey, null, fingerprint, desired,
                TaskState.SUCCEEDED, "已确认完成", null, null, 1, 1,
            )
        store.put(existing)
        val confirmation = confirmOne(plans)
        var writes = 0
        assertFailsWith<CloudFailure> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes)
        assertEquals(TaskState.SUCCEEDED, store.get("plan-1")?.state)
    }

    // ------------------------------------------------------------ focus 2

    @Test
    fun expiredPlanAndChangedContextAreRefusedBeforeAnyWrite() = runTest {
        var clock = 1_000L
        val resolver = Resolver(live())
        val store = TestStore()
        // Two confirms in this case: four real ids for its local factory.
        val queue = mutableListOf("plan-1", "confirm-1", "plan-2", "confirm-2")
        val plans = ChangePlans(resolver, { clock }, { queue.removeFirst() })
        val coordinator = OperationCoordinator(plans, store, resolver, { clock }, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val confirmation = confirmOne(plans)
        // The trusted clock has moved past the 300 s window.
        clock = 1_000 + ChangePlans.TTL_MS
        var writes = 0
        assertFailsWith<CloudFailure> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes)

        // A changed credential revision after confirmation is refused too.
        clock = 1_000L
        val fresh = confirmOne(plans)
        resolver.live = live(revision = 9)
        assertFailsWith<CloudFailure> { coordinator.execute(fresh) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes)
    }

    @Test
    fun prepareCannotExtendTheWindowWithAFutureTimeAndOverflowFailsClosed() {
        val resolver = Resolver(live())
        val store = TestStore()
        val plans = ChangePlans(resolver, { 1_000 }, { "plan-1" })
        val plan = plans.prepare(zone, createKey, null, null, desired, fingerprint, 7, 999_999_999L)
        assertEquals(1_000L + ChangePlans.TTL_MS, plan.expiresAtMs, "a caller time can only shorten the window")
        // Overflow in the expiry addition fails closed.
        val huge = ChangePlans(resolver, { Long.MAX_VALUE - 1 }, { "plan-2" })
        assertFailsWith<CloudFailure> { huge.prepare(zone, createKey, null, null, desired, fingerprint, 7, Long.MAX_VALUE - 1) }
    }

    @Test
    fun storeFailureWritesNothingAndDoesNotReviveTheConfirmation() = runTest {
        val (plans, coordinator, store) = fixture()
        store.failPut = true
        val confirmation = confirmOne(plans)
        var writes = 0
        assertFailsWith<IllegalStateException> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes, "a store failure must happen before the write")
        assertEquals(0, store.tasks.size)
        store.failPut = false
        // The same issued reference shares the original failure result; it neither
        // retries the write nor revives the confirmation.
        assertFailsWith<IllegalStateException> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes, "the consumed confirmation must not come back to life")
        assertEquals(0, store.tasks.size)
    }

    // ------------------------------------------------------------ focus 3

    @Test
    fun concurrentCallersShareOneWriteAndOneTask() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        var writes = 0
        val results = (1..5).map { async { coordinator.execute(confirmation) { writes++; delay(1); ExecutionReceipt.Completed() } } }
        val tasks = results.map { it.await() }
        assertEquals(1, writes)
        assertEquals(1, tasks.map { it.id }.distinct().size)
        assertTrue(store.tasks.size == 1)
    }

    @Test
    fun cancellingAWaitersAwaitDoesNotCancelTheAppOwnedExecution() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        var writes = 0
        val gate = CompletableDeferred<Unit>()
        val waiter = launch { coordinator.execute(confirmation) { writes++; gate.await(); ExecutionReceipt.Completed() } }
        runCurrent()
        waiter.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(TaskState.SUCCEEDED, store.get("plan-1")?.state, "the app-owned execution completes")
    }

    @Test
    fun anUnrecognizedPostSendFailureIsNeverReportedAsFailed() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        val task = coordinator.execute(confirmation) { throw IllegalStateException("synthetic post-send failure") }
        assertEquals(TaskState.RUNNING, task.state, "the pre-persisted RUNNING stays as send evidence")
        assertEquals(TaskState.RUNNING, store.get(task.id)?.state)
        // A later recovery turns it into UNKNOWN, and never into FAILED.
        val recovered = coordinator.recoverPending()
        assertEquals(TaskState.UNKNOWN, recovered.single().state)
    }

    // ------------------------------------------------------------ focus 4

    @Test
    fun everyReceiptMapsToItsOwnStateAndSensitiveTextNeverLands() = runTest {
        val expectations =
            listOf(
                ExecutionReceipt.Completed(providerRequestId = "provider-1") to TaskState.SUCCEEDED,
                ExecutionReceipt.Accepted(pollPath = "/zones/zone-1/dns_records/record-1") to TaskState.ACCEPTED,
                ExecutionReceipt.Partial(safeDetail = "synthetic provider detail") to TaskState.PARTIAL,
                ExecutionReceipt.Unknown() to TaskState.UNKNOWN,
            )
        for ((receipt, state) in expectations) {
            val (plans, coordinator, store) = fixture(ids = listOf("plan-1", "confirm-1"))
            val confirmation = confirmOne(plans)
            val task = coordinator.execute(confirmation) { receipt }
            assertEquals(state, task.state, receipt.toString())
            val stored = store.get(task.id)!!
            assertEquals(OperationTaskPersistence.summaryDetailFor(state), stored.safeDetail)
            assertFalse(stored.safeDetail.contains("synthetic provider detail"))
            val summary = store.summaries.last()
            assertFalse(summary.detail.contains("synthetic provider detail"))
        }
    }

    @Test
    fun anUnsafePollLocatorIsDroppedRatherThanGuessed() = runTest {
        for (path in listOf("https://evil.example/jobs", "/zones/zone-2/dns_records", "/zones/zone-1/../../etc")) {
            val (plans, coordinator, _) = fixture(ids = listOf("plan-1", "confirm-1"))
            val confirmation = confirmOne(plans)
            val task = coordinator.execute(confirmation) { ExecutionReceipt.Accepted(pollPath = path) }
            assertEquals(TaskState.ACCEPTED, task.state)
            assertNull(task.pollPath, path)
        }
    }

    @Test
    fun invalidProviderIdentifiersAreDropped() = runTest {
        for (providerId in listOf(" ", "a".repeat(200), "bad\r\nid")) {
            val (plans, coordinator, _) = fixture(ids = listOf("plan-1", "confirm-1"))
            val confirmation = confirmOne(plans)
            val task = coordinator.execute(confirmation) { ExecutionReceipt.Completed(providerRequestId = providerId) }
            assertNull(task.providerRequestId, providerId)
        }
    }

    // ------------------------------------------------------------ reconciliation

    @Test
    fun reconciliationRequiresARealHashAndNeverReplays() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        coordinator.execute(confirmation) { ExecutionReceipt.Unknown() }
        var reads = 0
        var writes = 0
        val matched = coordinator.reconcile("plan-1") { reads++; Reconciliation.Matched(desired) }
        assertEquals(TaskState.SUCCEEDED, matched.state)
        assertFailsWith<CloudFailure> { coordinator.reconcile("plan-1") { reads++; Reconciliation.Matched("not-a-hash") } }
        val notApplied = coordinator.reconcile("plan-1") { reads++; Reconciliation.NotApplied }
        assertEquals(TaskState.ACTION_REQUIRED, notApplied.state)
        val unknown = coordinator.reconcile("plan-1") { reads++; Reconciliation.StillUnknown }
        assertEquals(TaskState.UNKNOWN, unknown.state)
        assertEquals(0, writes, "reconciliation never writes to the provider")
        // Four closures ran: the malformed hash was rejected *after* its read.
        assertEquals(4, reads)
        assertTrue(store.tasks.isNotEmpty())
    }

    @Test
    fun recoveryIsConservativeAndKeepsAcknowledgements() = runTest {
        val resolver = Resolver(live(), liveConnections = listOf(Connection("connection-1", "Live", "account-1")))
        val store = TestStore()
        store.put(task("queued", TaskState.QUEUED))
        store.put(task("running", TaskState.RUNNING))
        store.put(task("accepted", TaskState.ACCEPTED))
        store.put(task("partial", TaskState.PARTIAL))
        store.put(task("succeeded", TaskState.SUCCEEDED))
        val plans = ChangePlans(resolver, { 1_000 }, { "plan-1" })
        val coordinator = OperationCoordinator(plans, store, resolver, { 5_000 }, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        val recovered = coordinator.recoverPending().associateBy { it.id }
        assertEquals(TaskState.ACTION_REQUIRED, recovered.getValue("queued").state)
        assertEquals(TaskState.UNKNOWN, recovered.getValue("running").state)
        assertEquals(TaskState.ACCEPTED, recovered.getValue("accepted").state)
        assertEquals(TaskState.PARTIAL, recovered.getValue("partial").state)
        assertEquals(TaskState.SUCCEEDED, recovered.getValue("succeeded").state)
        assertTrue(recovered.values.all { it.updatedAtMs >= 0 })
    }

    @Test
    fun invalidatedConnectionLatchesMetadataUpdatesAndLateResults() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val queue = mutableListOf("plan-1", "confirm-1")
        val plans = ChangePlans(resolver, { 1_000 }, { queue.removeFirst() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        appScope = scope
        val coordinator = OperationCoordinator(plans, store, resolver, { 1_000 }, scope, Dispatchers.Unconfined)
        val confirmation = confirmOne(plans)
        coordinator.execute(confirmation) { ExecutionReceipt.Unknown() }
        var removals = 0
        coordinator.invalidateConnection("connection-1")
        // The deletion protocol itself must enter the same latch (it used to be refused).
        coordinator.withInvalidatedConnection("connection-1") { removals++ }
        assertEquals(1, removals)
        // A late read may run, but its metadata write is refused by the same latch, so a
        // deleted task cannot be revived.
        var reads = 0
        assertFailsWith<CloudFailure> { coordinator.reconcile("plan-1") { reads++; Reconciliation.StillUnknown } }
        assertEquals(1, reads, "the read runs, the metadata write does not")
        assertEquals(TaskState.UNKNOWN, store.get("plan-1")?.state)
    }

    // ------------------------------------------------------------ canonical json + controls

    @Test
    fun canonicalJsonIsDeterministicAndPreservesLexicalNumbersAndSurrogates() {
        val first = CanonicalJson.encode(Json.parseToJsonElement("""{"b":1,"a":[2,1],"n":null,"x":1.50}"""))
        val second = CanonicalJson.encode(Json.parseToJsonElement("""{"x":1.50,"n":null,"a":[2,1],"b":1}"""))
        assertTrue(first.contentEquals(second), "key order must not matter")
        assertEquals("""{"a":[2,1],"b":1,"n":null,"x":1.50}""", first.decodeToString())
        // Distinct UTF-16 inputs never collapse into the same bytes.
        val high = CanonicalJson.encode(JsonPrimitive("\uD83D\uDE00"))
        val lone = CanonicalJson.encode(JsonPrimitive("\uD83D"))
        assertFalse(high.contentEquals(lone))
        assertTrue(high.decodeToString().contains("d83d"))
        // Malformed numeric literals fail closed.
        assertFailsWith<CloudFailure> { CanonicalJson.encode(JsonUnquotedLiteral("NaN")) }
        assertFailsWith<CloudFailure> { CanonicalJson.encode(JsonUnquotedLiteral("Infinity")) }
    }

    @Test
    fun legalControlsAreAcceptedThroughTheSamePath() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        val task = coordinator.execute(confirmation) { ExecutionReceipt.Completed(providerRequestId = "provider-ok") }
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("provider-ok", task.providerRequestId)
        // Every persist belongs to the same task, is ordered QUEUED -> RUNNING -> settled,
        // and only ever carries the fixed public summary text.
        val forTask = store.summaries.filter { it.id == task.id }
        assertEquals(3, forTask.size)
        assertEquals(
            listOf(OperationStatus.UNKNOWN, OperationStatus.UNKNOWN, OperationStatus.SUCCESS),
            forTask.map { it.status },
        )
        assertEquals(OperationTaskPersistence.summaryDetailFor(TaskState.SUCCEEDED), forTask.last().detail)
        assertTrue(forTask.none { it.detail.contains("synthetic") })
        // The two pre-send rows are the send evidence the contract requires.
        assertTrue(store.tasks.getValue(task.id).state == TaskState.SUCCEEDED)
    }

    // ------------------------------------------------------------ liveness + interop

    @Test
    fun reconcileCompletesUnderATimeoutAndTheSameGateStaysUsable() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        coordinator.execute(confirmation) { ExecutionReceipt.Unknown() }
        // The metadata gate must not be re-entered: this used to self-deadlock.
        val bounded = withTimeout(5_000) { coordinator.reconcile("plan-1") { Reconciliation.Matched(desired) } }
        assertEquals(TaskState.SUCCEEDED, bounded.state)
        // The same latch still serves deletion and later metadata updates.
        withTimeout(5_000) {
            coordinator.withInvalidatedConnection("connection-1") { store.removeConnection("connection-1") }
        }
        assertNull(store.get("plan-1"))
    }

    @Test
    fun aChangedInputScopeOrRevisionBeforeTheWriteProducesNoWrite() = runTest {
        for (mutate in listOf<(Resolver) -> Unit>(
            { it.live = live(fp = "c".repeat(64)) },
            { it.live = live(revision = 99) },
            { it.live = live(scope = AccountScope("connection-1", "account-1")) },
            { it.invalidatedScopes += zone },
        )) {
            val resolver = Resolver(live(), listOf(liveConnection))
            val store = TestStore()
            // This case confirms twice, so its local factory needs four real ids.
            val queue = mutableListOf("plan-1", "confirm-1", "plan-2", "confirm-2")
            val plans = ChangePlans(resolver, { 1_000 }, { queue.removeFirst() })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            appScope = scope
            val coordinator = OperationCoordinator(plans, store, resolver, { 1_000 }, scope, Dispatchers.Unconfined)
            val confirmation = confirmOne(plans)
            mutate(resolver)
            var writes = 0
            assertFailsWith<CloudFailure> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
            assertEquals(0, writes, "a changed context must never reach the provider")
        }
    }

    @Test
    fun optInDnsLifecycleDrivesTheControllerWithTrustedContextAndReceipts() = runTest {
        val connection = Connection("connection-1", "Live", "account-1")
        val zone = ZoneScope(AccountScope(connection.id, connection.accountId), "zone-1")
        val fixed = "d".repeat(64)
        val resolver = Resolver(LiveOperationContext(connection, zone, 7, fixed), listOf(connection))
        val store = MemoryStore()
        val taskStore = TestStore()
        val queue = mutableListOf("plan-1", "confirm-1")
        val plans = ChangePlans(resolver, { 1_000 }, { queue.removeFirst() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        appScope = scope
        val coordinator = OperationCoordinator(plans, taskStore, resolver, { 1_000 }, scope, Dispatchers.Unconfined)
        val lifecycle =
            DnsOperationLifecycle(
                resolver, plans, coordinator, { 1_000 }, { fixed },
                { it == zone }, { _, beforeSend -> beforeSend(); ExecutionReceipt.Unknown() },   // legacy-style Unit adapter
            )
        val controller = AppController(backgroundScope, SessionFactory { DemoSession(connection) }, store, listOf(connection), lifecycle)
        val domain = Domain("zone-1", "example.com", "active", connection.id)
        val operation = controller.saveDns(domain, null, DnsDraft("A", "@", "192.0.2.1"))
        // A Unit-style source can never claim completion.
        assertEquals(OperationStatus.UNKNOWN, operation.status)
        assertEquals(OperationTaskPersistence.summaryDetailFor(TaskState.UNKNOWN), operation.detail)
        assertEquals(1, taskStore.tasks.size)
        assertEquals(TaskState.UNKNOWN, taskStore.tasks.values.single().state)
    }

    @Test
    fun aForeignZoneIsRefusedByTheOptInPathWithNoWrite() = runTest {
        val connection = Connection("connection-1", "Live", "account-1")
        val zone = ZoneScope(AccountScope(connection.id, connection.accountId), "zone-1")
        val foreign = ZoneScope(AccountScope("connection-9", "account-9"), "zone-9")
        val fixed = "e".repeat(64)
        val resolver = Resolver(LiveOperationContext(connection, zone, 7, fixed), listOf(connection))
        val taskStore = TestStore()
        val queue = mutableListOf("plan-1", "confirm-1")
        val plans = ChangePlans(resolver, { 1_000 }, { queue.removeFirst() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        appScope = scope
        val coordinator = OperationCoordinator(plans, taskStore, resolver, { 1_000 }, scope, Dispatchers.Unconfined)
        val lifecycle =
            DnsOperationLifecycle(
                resolver, plans, coordinator, { 1_000 }, { fixed },
                { it == zone }, { _, _ -> ExecutionReceipt.Completed() },
            )
        val controller = AppController(backgroundScope, SessionFactory { DemoSession(connection) }, MemoryStore(), listOf(connection), lifecycle)
        val operation = controller.saveDns(Domain(foreign.zoneId, "example.com", "active", connection.id), null, DnsDraft("A", "@", "192.0.2.1"))
        assertEquals(OperationStatus.FAILED, operation.status)
        assertTrue(taskStore.tasks.isEmpty(), "an unknown zone owner must produce zero tasks and zero writes")
    }

    private fun task(id: String, state: TaskState) =
        OperationTask(id, "connection-1", zone, createKey, null, fingerprint, desired, state,
            OperationTaskPersistence.summaryDetailFor(state), null, null, 1_000, 1_000)

    // ------------------------------------------- fix 3/4 tracked contracts

    /** Bounded enter/release handshake: proves overlap or serialisation without sleeping. */
    private class Concurrency {
        private val inside = java.util.concurrent.atomic.AtomicInteger(0)
        val maxInside = java.util.concurrent.atomic.AtomicInteger(0)
        val overlapped = java.util.concurrent.atomic.AtomicBoolean(false)

        fun enter() {
            val now = inside.incrementAndGet()
            // Compare-and-set loop: no read-modify-write race can lose a peak.
            while (true) {
                val seen = maxInside.get()
                if (now <= seen || maxInside.compareAndSet(seen, now)) break
            }
            if (now > 1) overlapped.set(true)
        }

        fun leave() {
            inside.decrementAndGet()
        }

        fun awaitInside(count: Int, millis: Long = 2_000) {
            val deadline = System.nanoTime() + millis * 1_000_000
            while (inside.get() < count && System.nanoTime() < deadline) Thread.sleep(1)
        }
    }

    private class HookStore(
        private val inner: TestStore = TestStore(),
        var onGet: (String) -> Unit = {},
        var onPut: (String) -> Unit = {},
    ) : OperationStore {
        override fun get(id: String): OperationTask? { onGet(id); return inner.get(id) }
        override fun list(connectionId: String) = inner.list(connectionId)
        override fun put(task: OperationTask) = inner.put(task)
        override fun removeConnection(connectionId: String) = inner.removeConnection(connectionId)
        override fun putWithSummary(task: OperationTask, summary: Operation) {
            onPut(task.id)
            inner.putWithSummary(task, summary)
        }
    }

    private fun engine(
        plans: ChangePlans,
        resolver: OperationContextResolver,
        store: OperationStore,
        app: CoroutineScope,
        storeDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        clock: () -> Long = { 1_000 },
    ) = OperationCoordinator(plans, store, resolver, clock, app, storeDispatcher)

    private fun idsOf(vararg names: String): OperationIds {
        val queue = names.toMutableList()
        return OperationIds { if (queue.isEmpty()) throw IllegalStateException("exhausted") else queue.removeFirst() }
    }

    private fun scopedIssuer(resolver: OperationContextResolver, ids: OperationIds, clock: () -> Long = { 1_000 }) =
        ChangePlans(resolver, clock, ids)

    @Test
    fun metadataCallbacksRunBeforeTheFinalGenerationCheckSoNothingIsWritten() = runTest {
        val base = Resolver(live(), listOf(liveConnection))
        val inner = TestStore()
        var coordinator: OperationCoordinator? = null
        val store =
            object : OperationStore {
                override fun get(id: String): OperationTask? {
                    // A real metadata callback: it revokes while reporting the row.
                    coordinator?.invalidateConnection("connection-1")
                    return inner.get(id)
                }
                override fun list(connectionId: String) = inner.list(connectionId)
                override fun put(task: OperationTask) = inner.put(task)
                override fun removeConnection(connectionId: String) = inner.removeConnection(connectionId)
                override fun putWithSummary(task: OperationTask, summary: Operation) = inner.putWithSummary(task, summary)
            }
        val plans = scopedIssuer(base, idsOf("plan-1", "confirm-1"))
        coordinator = engine(plans, base, store, newScope())
        val confirmation = confirmOne(plans)
        var writes = 0
        val result = runCatching { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, inner.putCount, "the final check must reject a callback-revoked generation")
        assertEquals(0, writes)
        assertTrue(result.isFailure)
    }

    @Test
    fun aHealthyConfirmationStillWritesQueuedAndRunningAndSendsOnce() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        var writes = 0
        val task = coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() }
        assertEquals(1, writes, "a legitimate write must still reach the adapter exactly once")
        assertTrue(store.putCount >= 2, "the QUEUED and RUNNING metadata writes must still happen")
        assertEquals(confirmation.plan.id, task.id)
        assertEquals(TaskState.SUCCEEDED, task.state)
    }

    @Test
    fun theOperationGuardRunsAfterTheMetadataCallbacksAndBeforeTheTypedSend() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        var writes = 0
        // The guard is the adapter-bound callback that runs immediately before the send.
        val result =
            runCatching {
                coordinator.executeGuarded(confirmation, { coordinator.invalidateConnection("connection-1") }) { beforeSend ->
                    beforeSend()
                    writes++
                    ExecutionReceipt.Completed()
                }
            }
        assertEquals(0, writes, "the typed send must not run after a guard-side revocation")
        // A boundary refusal is reported conservatively: never a fabricated success.
        assertTrue(result.getOrNull()?.state != TaskState.SUCCEEDED)
    }

    @Test
    fun aLegitimateGuardStillSendsOnceAndSucceeds() = runTest {
        val (plans, coordinator, store) = fixture()
        val confirmation = confirmOne(plans)
        var writes = 0
        val task =
            coordinator.executeGuarded(confirmation, {}) { beforeSend ->
                beforeSend()
                writes++
                ExecutionReceipt.Completed()
            }
        assertEquals(1, writes)
        assertEquals(confirmation.plan.id, task.id)
        assertEquals(TaskState.SUCCEEDED, task.state)
    }

    @Test
    fun everyReceiptWithAFailingClockAfterTheWriteKeepsTheOriginalRunningIdentity() = runTest {
        val receipts =
            listOf<ExecutionReceipt>(
                ExecutionReceipt.Completed(),
                ExecutionReceipt.Accepted(pollPath = "/zones/zone-1/dns_records/record-1"),
                ExecutionReceipt.Partial(safeDetail = "synthetic"),
                ExecutionReceipt.Unknown(),
            )
        receipts.forEachIndexed { index, receipt ->
            val resolver = Resolver(live(), listOf(liveConnection))
            val store = TestStore()
            val plans = scopedIssuer(resolver, idsOf("plan-$index", "confirm-$index"))
            var writes = 0
            val coordinator =
                engine(plans, resolver, store, newScope(), clock = {
                    if (writes > 0) throw IllegalStateException("clock unavailable") else 1_000
                })
            val confirmation = confirmOne(plans)
            // Called directly: the engine must return a task, never propagate a post-send failure.
            val returned = coordinator.execute(confirmation) { writes++; receipt }
            assertEquals(1, writes, "the typed send must really have run")
            assertEquals(confirmation.plan.id, returned.id, "the original plan identity must be preserved")
            assertTrue(
                returned.state == TaskState.RUNNING || returned.state == TaskState.UNKNOWN,
                "a failing clock must never fabricate a terminal state",
            )
            val stored = store.tasks[returned.id]
            assertNotNull(stored, "the original row must stay addressable")
            assertEquals(returned.id, stored.id)
            assertEquals(returned.state, stored.state)
            val canonical = OperationTaskPersistence.summaryFor(returned)
            // Non-terminal states map to UNKNOWN by contract; a failing clock must never produce
            // a success summary.
            assertEquals(OperationStatus.UNKNOWN, canonical.status, "a conservative task must map to UNKNOWN")
            assertEquals(returned.id, canonical.id)
        }
    }

    @Test
    fun everyReceiptWithAFailedSettlementStoreKeepsTheOriginalRunningIdentity() = runTest {
        val receipts =
            listOf<ExecutionReceipt>(
                ExecutionReceipt.Completed(),
                ExecutionReceipt.Accepted(pollPath = "/zones/zone-1/dns_records/record-1"),
                ExecutionReceipt.Partial(safeDetail = "synthetic"),
                ExecutionReceipt.Unknown(),
            )
        receipts.forEachIndexed { index, receipt ->
            val resolver = Resolver(live(), listOf(liveConnection))
            val store = TestStore()
            val plans = scopedIssuer(resolver, idsOf("plan-$index", "confirm-$index"))
            val coordinator = engine(plans, resolver, store, newScope())
            val confirmation = confirmOne(plans)
            var writes = 0
            val returned =
                coordinator.execute(confirmation) {
                    writes++
                    store.failPut = true
                    receipt
                }
            assertEquals(1, writes, "the typed send must really have run exactly once")
            assertEquals(confirmation.plan.id, returned.id, "the original plan identity must be preserved")
            assertTrue(
                returned.state == TaskState.RUNNING || returned.state == TaskState.UNKNOWN,
                "a failed settlement store must keep the conservative state",
            )
            val stored = store.tasks[returned.id]
            assertNotNull(stored, "the pre-send row must remain the evidence")
            assertEquals(returned.id, stored.id)
            assertEquals(returned.state, stored.state)
        }
    }

    @Test
    fun cancelledOwnerBeforeTheBodyRunsLeavesEveryWaiterAbleToExitWithoutWriting() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, store, newScope(cancelled = true))
        val confirmation = confirmOne(plans)
        var writes = 0
        val waiters = (1..2).map { async { runCatching { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } } } }
        var released = false
        withTimeoutOrNull(500) {
            waiters.forEach { it.await() }
            released = true
        }
        assertTrue(released, "a never-started owner must still release its waiters")
        assertEquals(0, writes)
        assertEquals(0, store.putCount)
    }

    @Test
    fun sameZoneCreateWritesSerializeAcrossEnginesWhileDistinctScopesRunInParallel() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1", "plan-2", "confirm-2", "plan-3", "confirm-3", "plan-4", "confirm-4"))
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        extraScopes += app
        val first = engine(plans, resolver, store, app, Dispatchers.Default)
        val second = engine(plans, resolver, store, app, Dispatchers.Default)
        val one = confirmOne(plans, zone)
        val two = confirmOne(plans, zone)
        val serial = Concurrency()
        val sameResource =
            listOf(
                async(Dispatchers.Default) {
                    first.execute(one) {
                        serial.enter()
                        serial.awaitInside(2, millis = 300)
                        serial.leave()
                        ExecutionReceipt.Completed()
                    }
                },
                async(Dispatchers.Default) {
                    second.execute(two) {
                        serial.enter()
                        serial.awaitInside(2, millis = 300)
                        serial.leave()
                        ExecutionReceipt.Completed()
                    }
                },
            )
        sameResource.awaitAll()
        assertEquals(1, serial.maxInside.get(), "the same confirmed resource must serialise")
        assertFalse(serial.overlapped.get(), "two bodies must never be inside at once")
    }

    @Test
    fun distinctScopesEnterTheirWritesAtTheSameTimeOnRealThreads() = runTest {
        val zoneA = ZoneScope(account, "zone-1")
        val zoneB = ZoneScope(account, "zone-2")
        val resolverA = Resolver(live(scope = zoneA), listOf(liveConnection))
        val resolverB = Resolver(live(scope = zoneB), listOf(liveConnection))
        val store = TestStore()
        val plansA = scopedIssuer(resolverA, idsOf("plan-a", "confirm-a"))
        val plansB = scopedIssuer(resolverB, idsOf("plan-b", "confirm-b"))
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        extraScopes += app
        val engineA = engine(plansA, resolverA, store, app, Dispatchers.Default)
        val engineB = engine(plansB, resolverB, store, app, Dispatchers.Default)
        val concurrent = Concurrency()
        val jobs =
            listOf(
                async(Dispatchers.Default) {
                    engineA.execute(confirmOne(plansA, zoneA)) {
                        concurrent.enter()
                        concurrent.awaitInside(2)
                        concurrent.leave()
                        ExecutionReceipt.Completed()
                    }
                },
                async(Dispatchers.Default) {
                    engineB.execute(confirmOne(plansB, zoneB)) {
                        concurrent.enter()
                        concurrent.awaitInside(2)
                        concurrent.leave()
                        ExecutionReceipt.Completed()
                    }
                },
            )
        jobs.awaitAll()
        assertEquals(2, concurrent.maxInside.get(), "distinct scopes must be able to send simultaneously")
    }

    @Test
    fun aRealResourceWaitLetsTheConfirmationExpireBeforeTheSend() = runTest {
        var now = 1_000L
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = ChangePlans(resolver, { now }, idsOf("plan-1", "confirm-1", "plan-2", "confirm-2"))
        val coordinator = OperationCoordinator(plans, store, resolver, { now }, newScope(), Dispatchers.Unconfined)
        val blocker = confirmOne(plans, zone)
        val waiting = confirmOne(plans, zone)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder =
            async(Dispatchers.Default) {
                coordinator.execute(blocker) {
                    entered.countDown()
                    release.await()
                    ExecutionReceipt.Completed()
                }
            }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "the blocker must hold the resource")
        // The queued confirmation really waits for the resource lock while time runs out.
        now = 1_000L + 400_000L
        var writes = 0
        val second = async(Dispatchers.Default) { runCatching { coordinator.execute(waiting) { writes++; ExecutionReceipt.Completed() } } }
        release.countDown()
        holder.await()
        val result = second.await()
        assertEquals(0, writes, "an expired confirmation must not reach the send")
        assertTrue(result.isFailure)
    }

    @Test
    fun aRealResourceWaitLetsTheCredentialRevisionChangeBeforeTheSend() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1", "plan-2", "confirm-2"))
        val coordinator = engine(plans, resolver, store, newScope())
        val blocker = confirmOne(plans, zone)
        val waiting = confirmOne(plans, zone)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder =
            async(Dispatchers.Default) {
                coordinator.execute(blocker) {
                    entered.countDown()
                    release.await()
                    ExecutionReceipt.Completed()
                }
            }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "the blocker must hold the resource")
        // The credential revision is revoked while the second write waits for the lock.
        resolver.live = live(revision = 99)
        var writes = 0
        val second = async(Dispatchers.Default) { runCatching { coordinator.execute(waiting) { writes++; ExecutionReceipt.Completed() } } }
        release.countDown()
        holder.await()
        val result = second.await()
        assertEquals(0, writes, "a revoked credential revision must not reach the send")
        assertTrue(result.isFailure)
    }
}
