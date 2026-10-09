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

    /** Shared across real worker threads: private state behind one monitor. */
    private class TestStore : OperationStore {
        private val lock = Any()
        private val rows = linkedMapOf<String, OperationTask>()
        private val recorded = mutableListOf<Operation>()
        private var puts = 0
        private var failing = false

        /** Immutable snapshots only: the internal containers are never exposed. */
        val tasks: Map<String, OperationTask> get() = synchronized(lock) { rows.toMap() }
        val summaries: List<Operation> get() = synchronized(lock) { recorded.toList() }
        val putCount: Int get() = synchronized(lock) { puts }

        var failPut: Boolean
            get() = synchronized(lock) { failing }
            set(value) { synchronized(lock) { failing = value } }

        fun clearAll() = synchronized(lock) { rows.clear() }

        override fun get(id: String): OperationTask? = synchronized(lock) { rows[id] }
        override fun list(connectionId: String): List<OperationTask> = synchronized(lock) { rows.values.filter { it.connectionId == connectionId } }
        override fun put(task: OperationTask) { synchronized(lock) { rows[task.id] = task } }
        override fun removeConnection(connectionId: String) { synchronized(lock) { rows.entries.removeAll { it.value.connectionId == connectionId } } }

        override fun putWithSummary(task: OperationTask, summary: Operation) {
            synchronized(lock) {
                puts++
                if (failing) throw IllegalStateException("synthetic store failure")
                rows[task.id] = task
                recorded += summary
            }
        }
    }

    private class Resolver(
        initial: LiveOperationContext?,
        private val liveConnections: List<Connection> = emptyList(),
    ) : OperationContextResolver {
        private val lock = Any()
        private var currentContext: LiveOperationContext? = initial
        private val revoked = mutableListOf<CloudScope>()

        override fun current(): LiveOperationContext? = synchronized(lock) { currentContext }
        override fun liveConnections() = liveConnections

        /** Controlled mutation: the backing field is never touched directly. */
        fun setLive(context: LiveOperationContext?) {
            synchronized(lock) { currentContext = context }
        }

        fun invalidateScope(scope: CloudScope) {
            synchronized(lock) { revoked += scope }
        }

        fun revokedScopes(): List<CloudScope> = synchronized(lock) { revoked.toList() }

        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            val context: LiveOperationContext?
            synchronized(lock) {
                if (scope in revoked) return false
                context = currentContext
            }
            val current = context ?: return credentialRevision != null
            if (credentialRevision != null && current.credentialRevision != credentialRevision) return false
            return true
        }
    }

    private val liveConnection = Connection("connection-1", "Live", "account-1")

    private var appScope: CoroutineScope? = null

    private val extraScopes = mutableListOf<CoroutineScope>()

    /** Every fixture scope is registered here and released, even when a test fails. */
    private val ownedJobs = mutableListOf<Job>()

    private fun newScope(
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        cancelled: Boolean = false,
    ): CoroutineScope {
        val job = SupervisorJob()
        if (cancelled) job.cancel()
        ownedJobs += job
        return CoroutineScope(job + dispatcher).also { extraScopes += it }
    }

    /** Cancel and join every owned scope with a bound, so a failed test cannot leak work. */
    @AfterTest
    fun stopApplicationScope() {
        appScope?.cancel()
        appScope = null
        extraScopes.forEach { it.cancel() }
        extraScopes.clear()
        val owned = ownedJobs.toList()
        owned.forEach { it.cancel() }
        val finished = runBlocking { withTimeoutOrNull(10_000) { owned.forEach { it.join() }; true } }
        assertTrue(finished == true, "every owned job must complete within the bound")
        ownedJobs.clear()
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
        val scope = newScope()
        appScope = scope
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
        val coordinator = OperationCoordinator(plans, store, resolver, { clock }, newScope(), Dispatchers.Unconfined)
        val confirmation = confirmOne(plans)
        // The trusted clock has moved past the 300 s window.
        clock = 1_000 + ChangePlans.TTL_MS
        var writes = 0
        assertFailsWith<CloudFailure> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, writes)

        // A changed credential revision after confirmation is refused too.
        clock = 1_000L
        val fresh = confirmOne(plans)
        resolver.setLive(live(revision = 9))
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
        val coordinator = OperationCoordinator(plans, store, resolver, { 5_000 }, newScope(), Dispatchers.Unconfined)
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
        val scope = newScope()
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
            { it.setLive(live(fp = "c".repeat(64))) },
            { it.setLive(live(revision = 99)) },
            { it.setLive(live(scope = AccountScope("connection-1", "account-1"))) },
            { it.invalidateScope(zone) },
        )) {
            val resolver = Resolver(live(), listOf(liveConnection))
            val store = TestStore()
            // This case confirms twice, so its local factory needs four real ids.
            val queue = mutableListOf("plan-1", "confirm-1", "plan-2", "confirm-2")
            val plans = ChangePlans(resolver, { 1_000 }, { queue.removeFirst() })
            val scope = newScope()
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
        val scope = newScope()
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
        val scope = newScope()
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
            // Fixed literal oracle, independent of the function under test.
            assertEquals(returned.id, canonical.id)
            assertEquals("Cloudflare 操作", canonical.title)
            assertEquals("结果待核对，请刷新后检查", canonical.detail)
            assertEquals(OperationStatus.UNKNOWN, canonical.status, "a conservative task must map to UNKNOWN")
            val repeat = coordinator.execute(confirmation) { writes++; receipt }
            assertEquals(1, writes, "a repeated confirmation must not send twice")
            assertEquals(returned, repeat, "a repeat must return the identical result")
            val repeatSummary = OperationTaskPersistence.summaryFor(repeat)
            assertEquals("Cloudflare 操作", repeatSummary.title)
            assertEquals("结果待核对，请刷新后检查", repeatSummary.detail)
            assertEquals(OperationStatus.UNKNOWN, repeatSummary.status)
            assertEquals(stored, store.tasks[returned.id], "the stored row must be unchanged after the repeat")
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
            val canonical = OperationTaskPersistence.summaryFor(returned)
            // Fixed literal oracle, independent of the function under test.
            assertEquals(returned.id, canonical.id)
            assertEquals("Cloudflare 操作", canonical.title)
            assertEquals("结果待核对，请刷新后检查", canonical.detail)
            assertEquals(OperationStatus.UNKNOWN, canonical.status)
            val repeat = coordinator.execute(confirmation) { writes++; receipt }
            assertEquals(1, writes, "a repeated confirmation must not send twice")
            assertEquals(returned, repeat, "a repeat must return the identical result")
            val repeatSummary = OperationTaskPersistence.summaryFor(repeat)
            assertEquals("Cloudflare 操作", repeatSummary.title)
            assertEquals("结果待核对，请刷新后检查", repeatSummary.detail)
            assertEquals(OperationStatus.UNKNOWN, repeatSummary.status)
            // The initial row and the row after the repeat must both be the same whole object.
            assertEquals(stored, store.tasks[returned.id], "the stored row must be unchanged by the repeat")
            assertEquals(stored.safeDetail, store.tasks[returned.id]?.safeDetail)
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

    /** A dispatcher that only runs queued work when the test drains it, counting runs. */
    private class RecordingQueue : CoroutineDispatcher() {
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        private val lock = Any()
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(lock) { queued.addLast(block) }
        }

        fun runAll() {
            while (true) {
                val next: Runnable
                synchronized(lock) { next = if (queued.isEmpty()) return else queued.removeFirst() }
                next.run()
                runs.incrementAndGet()
            }
        }
    }

    @Test
    fun theSameCreateTargetIsMutuallyExclusiveAcrossEnginesOfOneIssuer() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1", "plan-2", "confirm-2"))
        val holderScope = newScope(Dispatchers.Default)
        extraScopes += holderScope
        ownedJobs.add(holderScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val queue = RecordingQueue()
        val waitingScope = newScope(queue)
        extraScopes += waitingScope
        ownedJobs.add(waitingScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val holderEngine = OperationCoordinator(plans, store, resolver, { 1_000 }, holderScope, Dispatchers.Unconfined)
        val waitingEngine = OperationCoordinator(plans, store, resolver, { 1_000 }, waitingScope, Dispatchers.Unconfined)
        val one = confirmOne(plans, zone)
        val two = confirmOne(plans, zone)
        val holderEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val contenderEntered = java.util.concurrent.CountDownLatch(1)
        val holder =
            async(Dispatchers.Default) {
                holderEngine.execute(one) {
                    holderEntered.countDown()
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder must be released")
                    ExecutionReceipt.Completed()
                }
            }
        assertTrue(holderEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder must be inside")
        var writes = 0
        val contender = async { runCatching { waitingEngine.execute(two) { contenderEntered.countDown(); writes++; ExecutionReceipt.Completed() } } }
        runCurrent()
        // The contender's owner continuation really runs and parks at the resource lock.
        queue.runAll()
        assertTrue(queue.runs.get() > 0, "the contender's continuation must have run")
        assertFalse(contender.isCompleted, "the contender must be parked, not finished")
        assertEquals(0, writes, "the contender must not have sent while the target is held")
        assertEquals(1L, contenderEntered.count, "the contender must not have entered the write body")
        release.countDown()
        holder.await()
        // Only after the holder released may the parked contender proceed.
        queue.runAll()
        val result = contender.await()
        assertTrue(result.isSuccess, "the contender must proceed once the holder released")
        assertEquals(1, writes, "the contender must send exactly once")
        assertTrue(contenderEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the contender must have entered")
    }

    @Test
    fun oneIssuerServesDifferentResourceRefsInParallelOnRealThreads() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-a", "confirm-a", "plan-b", "confirm-b"))
        val app = newScope(Dispatchers.Default)
        extraScopes += app
        ownedJobs.add(app.coroutineContext[kotlinx.coroutines.Job]!!)
        val engineA = engine(plans, resolver, store, app, Dispatchers.Default)
        val engineB = engine(plans, resolver, store, app, Dispatchers.Default)
        val refA = ResourceRef(zone, "dns_record", "rec-1")
        val refB = ResourceRef(zone, "dns_record", "rec-2")
        val planA = plans.prepare(zone, DnsOperationLifecycle.ACTION_PATCH, refA, null, desired, fingerprint, 7, 1_000)
        val planB = plans.prepare(zone, DnsOperationLifecycle.ACTION_PATCH, refB, null, desired, fingerprint, 7, 1_000)
        val confirmA = plans.confirm(planA.id, zone, 7, fingerprint, 1_000)
        val confirmB = plans.confirm(planB.id, zone, 7, fingerprint, 1_000)
        assertNotEquals(confirmA.plan.id, confirmB.plan.id)
        val bothInside = java.util.concurrent.CountDownLatch(2)
        val holdBoth = java.util.concurrent.CountDownLatch(1)
        val jobs =
            listOf(
                async(Dispatchers.Default) {
                    engineA.execute(confirmA) {
                        bothInside.countDown()
                        assertTrue(bothInside.await(5, java.util.concurrent.TimeUnit.SECONDS), "both writes must be inside together")
                        assertTrue(holdBoth.await(5, java.util.concurrent.TimeUnit.SECONDS), "the release must arrive")
                        ExecutionReceipt.Completed()
                    }
                },
                async(Dispatchers.Default) {
                    engineB.execute(confirmB) {
                        bothInside.countDown()
                        assertTrue(bothInside.await(5, java.util.concurrent.TimeUnit.SECONDS), "both writes must be inside together")
                        assertTrue(holdBoth.await(5, java.util.concurrent.TimeUnit.SECONDS), "the release must arrive")
                        ExecutionReceipt.Completed()
                    }
                },
            )
        try {
            // Explicit simultaneity: both bodies must be inside before either may leave.
            assertTrue(bothInside.await(5, java.util.concurrent.TimeUnit.SECONDS), "two distinct refs must write at the same time")
            assertEquals(0L, bothInside.count, "both writes must be simultaneously inside")
        } finally {
            holdBoth.countDown()
        }
        val returns = jobs.awaitAll()
        assertEquals(setOf(confirmA.plan.id, confirmB.plan.id), returns.map { it.id }.toSet())
        returns.forEach { assertEquals(TaskState.SUCCEEDED, it.state, "each distinct-ref write must succeed") }
    }

    @Test
    fun theLastLivenessCallbackBeforeTheSendRevokesTheGeneration() = runTest {
        val inner = TestStore()
        var arm = false
        var coordinatorRef: OperationCoordinator? = null
        val resolver =
            object : OperationContextResolver {
                override fun current(): LiveOperationContext = live()
                override fun liveConnections() = listOf(liveConnection)
                override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
                    // Armed by the adapter only once it is about to send: this is the last
                    // liveness callback of the whole execution.
                    if (arm) coordinatorRef?.invalidateConnection("connection-1")
                    return true
                }
            }
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, inner, newScope())
        coordinatorRef = coordinator
        val confirmation = confirmOne(plans)
        var writes = 0
        val returned =
            coordinator.executeGuarded(confirmation, {}) { beforeSend ->
                // The adapter has really been entered and both metadata writes are done.
                arm = true
                beforeSend()
                writes++
                ExecutionReceipt.Completed()
            }
        assertEquals(2, inner.putCount, "exactly the QUEUED and RUNNING metadata writes must have happened")
        assertEquals(0, writes, "the typed send must not run after the final callback revoked")
        assertEquals(confirmation.plan.id, returned.id, "the original plan identity must be preserved")
        assertTrue(
            returned.state == TaskState.RUNNING || returned.state == TaskState.UNKNOWN,
            "a revoked final callback must stay conservative",
        )
    }

    @Test
    fun aLegitimateAdapterCallbackStillSendsOnceAndSucceeds() = runTest {
        val inner = TestStore()
        var coordinator: OperationCoordinator? = null
        val resolver =
            object : OperationContextResolver {
                override fun current(): LiveOperationContext = live()
                override fun liveConnections() = listOf(liveConnection)
                override fun isLive(scope: CloudScope, credentialRevision: Long?) = true
            }
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        coordinator = engine(plans, resolver, inner, newScope())
        val confirmation = confirmOne(plans)
        var writes = 0
        val returned =
            coordinator.executeGuarded(confirmation, {}) { beforeSend ->
                beforeSend()
                writes++
                ExecutionReceipt.Completed()
            }
        assertEquals(1, writes, "a legitimate adapter must send exactly once")
        assertEquals(confirmation.plan.id, returned.id)
        assertEquals(TaskState.SUCCEEDED, returned.state)
    }

    @Test
    fun theSecondOwnerContinuesIntoTheResourceLockBeforeAuthorityChanges() = runTest {
        var now = 1_000L
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = ChangePlans(resolver, { now }, idsOf("plan-1", "confirm-1", "plan-2", "confirm-2"))
        val holderScope = newScope(Dispatchers.Default)
        extraScopes += holderScope
        ownedJobs.add(holderScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val queue = RecordingQueue()
        val holderEngine = OperationCoordinator(plans, store, resolver, { now }, holderScope, Dispatchers.Unconfined)
        val waitingScope = newScope(queue)
        extraScopes += waitingScope
        ownedJobs.add(waitingScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val waitingEngine = OperationCoordinator(plans, store, resolver, { now }, waitingScope, Dispatchers.Unconfined)
        val blocker = confirmOne(plans, zone)
        val waiting = confirmOne(plans, zone)
        val holderEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder =
            async(Dispatchers.Default) {
                holderEngine.execute(blocker) {
                    holderEntered.countDown()
                    assertTrue(release.await(2, java.util.concurrent.TimeUnit.SECONDS), "the holder must be released")
                    ExecutionReceipt.Completed()
                }
            }
        assertTrue(holderEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "the holder must be inside")
        var writes = 0
        val contender = async { runCatching { waitingEngine.execute(waiting) { writes++; ExecutionReceipt.Completed() } } }
        runCurrent()
        queue.runAll()
        assertTrue(queue.runs.get() > 0, "the contender's owner continuation must really have run")
        assertFalse(contender.isCompleted, "the contender must be parked at the resource lock")
        assertEquals(0, writes, "the contender must not have sent")
        try {
            // Only now, with the contender provably parked, does authority change.
            now = 1_000L + 400_000L
        } finally {
            release.countDown()
        }
        holder.await()
        queue.runAll()
        val result = contender.await()
        assertTrue(result.isFailure, "the parked contender must be refused after the authority change")
        assertEquals(0, writes, "no send may happen after the authority change")
    }

    @Test
    fun recoveryCannotRecreateARowDeletedByItsFinalLivenessCallback() = runTest {
        val base = Resolver(live(), listOf(liveConnection))
        val inner = TestStore()
        var coordinatorRef: OperationCoordinator? = null
        var recovering = false
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val resolver =
            object : OperationContextResolver {
                override fun current(): LiveOperationContext? = base.current()
                override fun liveConnections() = base.liveConnections()
                override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
                    if (recovering && calls.incrementAndGet() >= 2) {
                        // The final liveness check of the pass revokes while reporting live, and
                        // the collaborator's delete has already removed the row.
                        coordinatorRef?.invalidateConnection("connection-1")
                        inner.removeConnection("connection-1")
                    }
                    return true
                }
            }
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, inner, newScope())
        coordinatorRef = coordinator
        // A single legitimate metadata row: seeded directly, no cloud write involved.
        inner.put(task("recover", TaskState.RUNNING))
        val before = inner.putCount
        recovering = true
        coordinator.recoverPending()
        assertEquals(2, calls.get(), "the recovery pass must reach its final liveness callback")
        assertEquals(before, inner.putCount, "no new write may follow the revoked final callback")
        assertTrue(inner.tasks.isEmpty(), "the revoked row must not be recreated")
    }

    @Test
    fun recoveryPersistsOneSettledRowWhenTheAuthorityIsUnchanged() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, store, newScope())
        store.put(task("recover", TaskState.RUNNING))
        val before = store.putCount
        coordinator.recoverPending()
        val recovered = store.tasks["recover"]
        assertNotNull(recovered)
        assertEquals(OperationStatus.UNKNOWN, OperationTaskPersistence.summaryFor(recovered).status)
        assertEquals("recover", recovered.id)
        assertEquals(before + 1, store.putCount, "an unchanged authority must persist exactly one settled row")
    }

    @Test
    fun theSettlementWriteCannotRecreateARowDeletedInsideItsOwnRowCheck() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val inner = TestStore()
        var coordinatorRef: OperationCoordinator? = null
        var deleted = false
        val store =
            object : OperationStore {
                override fun get(id: String): OperationTask? {
                    val row = inner.get(id)
                    // The settlement's own row check deletes the row and revokes, then returns
                    // the captured row it read.
                    if (!deleted && row?.state == TaskState.RUNNING) {
                        deleted = true
                        inner.removeConnection("connection-1")
                        coordinatorRef?.invalidateConnection("connection-1")
                    }
                    return row
                }
                override fun list(connectionId: String) = inner.list(connectionId)
                override fun put(task: OperationTask) = inner.put(task)
                override fun removeConnection(connectionId: String) = inner.removeConnection(connectionId)
                override fun putWithSummary(task: OperationTask, summary: Operation) = inner.putWithSummary(task, summary)
            }
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, store, newScope())
        coordinatorRef = coordinator
        val confirmation = confirmOne(plans)
        var writes = 0
        // Direct call: the engine must return the conservative original task.
        val returned = coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() }
        assertTrue(deleted, "the settlement row check must really have run")
        assertEquals(1, writes, "the send happened before the settlement check")
        assertEquals(confirmation.plan.id, returned.id, "the original plan identity must be preserved")
        assertTrue(returned.state == TaskState.RUNNING || returned.state == TaskState.UNKNOWN)
        assertEquals(2, inner.putCount, "only the QUEUED and RUNNING writes may happen")
        assertTrue(inner.tasks.isEmpty(), "the deleted row must not be recreated")
    }

    @Test
    fun aParkedContenderSeesTheCredentialRevisionChangeAfterItIsWaiting() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1", "plan-2", "confirm-2"))
        val holderScope = newScope(Dispatchers.Default)
        extraScopes += holderScope
        ownedJobs.add(holderScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val queue = RecordingQueue()
        val waitingScope = newScope(queue)
        extraScopes += waitingScope
        ownedJobs.add(waitingScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val holderEngine = OperationCoordinator(plans, store, resolver, { 1_000 }, holderScope, Dispatchers.Unconfined)
        val waitingEngine = OperationCoordinator(plans, store, resolver, { 1_000 }, waitingScope, Dispatchers.Unconfined)
        val blocker = confirmOne(plans, zone)
        val waiting = confirmOne(plans, zone)
        val holderEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder =
            async(Dispatchers.Default) {
                holderEngine.execute(blocker) {
                    holderEntered.countDown()
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder must be released")
                    ExecutionReceipt.Completed()
                }
            }
        assertTrue(holderEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder must be inside")
        var writes = 0
        val contender = async { runCatching { waitingEngine.execute(waiting) { writes++; ExecutionReceipt.Completed() } } }
        runCurrent()
        queue.runAll()
        assertTrue(queue.runs.get() > 0, "the contender's continuation must really have run")
        assertFalse(contender.isCompleted, "the contender must be parked")
        assertEquals(0, writes)
        try {
            resolver.setLive(live(revision = 99))
        } finally {
            release.countDown()
        }
        holder.await()
        queue.runAll()
        val result = contender.await()
        assertTrue(result.isFailure, "the parked contender must be refused after the revision change")
        assertEquals(0, writes, "no send may happen after the revision change")
    }

    @Test
    fun theQueuedMetadataLivenessCallbackCanRevokeBeforeAnyWrite() = runTest {
        val inner = TestStore()
        var coordinatorRef: OperationCoordinator? = null
        // Armed only once the confirmation has been signed and the collision read has happened:
        // the QUEUED metadata write's own final liveness callback then revokes.
        var armed = false
        val store =
            object : OperationStore {
                override fun get(id: String): OperationTask? {
                    armed = true
                    return inner.get(id)
                }
                override fun list(connectionId: String) = inner.list(connectionId)
                override fun put(task: OperationTask) = inner.put(task)
                override fun removeConnection(connectionId: String) = inner.removeConnection(connectionId)
                override fun putWithSummary(task: OperationTask, summary: Operation) = inner.putWithSummary(task, summary)
            }
        val resolver =
            object : OperationContextResolver {
                override fun current(): LiveOperationContext = live()
                override fun liveConnections() = listOf(liveConnection)
                override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
                    if (armed) coordinatorRef?.invalidateConnection("connection-1")
                    return true
                }
            }
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, store, newScope())
        coordinatorRef = coordinator
        val confirmation = confirmOne(plans)
        var writes = 0
        assertFailsWith<CloudFailure> { coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() } }
        assertEquals(0, inner.putCount, "the final liveness check of the QUEUED write must leave zero puts")
        assertEquals(0, writes, "no send may happen")
    }

    @Test
    fun reconcileAfterACooperativeDeleteDoesNotResurrectTheTask() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val inner = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, inner, newScope())
        val confirmation = confirmOne(plans)
        val task = coordinator.execute(confirmation) { ExecutionReceipt.Unknown() }
        val before = inner.putCount
        coordinator.withInvalidatedConnection("connection-1") { inner.removeConnection("connection-1") }
        assertTrue(inner.tasks.isEmpty(), "the cooperative delete must have removed the task")
        val result = runCatching { coordinator.reconcile(task.id) { Reconciliation.Matched(desired) } }
        assertEquals(before, inner.putCount, "reconcile must not write after a cooperative delete")
        assertTrue(inner.tasks.isEmpty(), "the deleted task must not be resurrected")
        assertTrue(result.isSuccess || result.exceptionOrNull() is CloudFailure)
    }

    @Test
    fun oneIssuerServesDifferentScopesWhenEachEngineHasItsOwnTrustedResolver() = runTest {
        val zoneA = ZoneScope(account, "zone-1")
        val zoneB = ZoneScope(account, "zone-2")
        var issuerLive = live(scope = zoneA)
        val issuerResolver =
            object : OperationContextResolver {
                override fun current(): LiveOperationContext = issuerLive
                override fun liveConnections() = listOf(liveConnection)
                override fun isLive(scope: CloudScope, credentialRevision: Long?) = true
            }
        val plans = ChangePlans(issuerResolver, { 1_000 }, idsOf("plan-a", "confirm-a", "plan-b", "confirm-b"))
        val planA = plans.prepare(zoneA, createKey, null, null, desired, fingerprint, 7, 1_000)
        val confirmationA = plans.confirm(planA.id, zoneA, 7, fingerprint, 1_000)
        // The issuer's trusted current input is switched to the second scope before issuing it.
        issuerLive = live(scope = zoneB)
        val planB = plans.prepare(zoneB, createKey, null, null, desired, fingerprint, 7, 1_000)
        val confirmationB = plans.confirm(planB.id, zoneB, 7, fingerprint, 1_000)
        val resolverA = Resolver(live(scope = zoneA), listOf(liveConnection))
        val resolverB = Resolver(live(scope = zoneB), listOf(liveConnection))
        val store = TestStore()
        val app = newScope(Dispatchers.Default)
        extraScopes += app
        ownedJobs.add(app.coroutineContext[kotlinx.coroutines.Job]!!)
        // One domain, one plans: only the engines have their own trusted resolvers.
        val engineA = OperationCoordinator(plans, store, resolverA, { 1_000 }, app, Dispatchers.Unconfined)
        val engineB = OperationCoordinator(plans, store, resolverB, { 1_000 }, app, Dispatchers.Unconfined)
        val bothInside = java.util.concurrent.CountDownLatch(2)
        val hold = java.util.concurrent.CountDownLatch(1)
        val jobs =
            listOf(
                async(Dispatchers.Default) {
                    engineA.execute(confirmationA) {
                        bothInside.countDown()
                        assertTrue(bothInside.await(5, java.util.concurrent.TimeUnit.SECONDS), "both scopes must be inside")
                        assertTrue(hold.await(5, java.util.concurrent.TimeUnit.SECONDS), "the release must arrive")
                        ExecutionReceipt.Completed()
                    }
                },
                async(Dispatchers.Default) {
                    engineB.execute(confirmationB) {
                        bothInside.countDown()
                        assertTrue(bothInside.await(5, java.util.concurrent.TimeUnit.SECONDS), "both scopes must be inside")
                        assertTrue(hold.await(5, java.util.concurrent.TimeUnit.SECONDS), "the release must arrive")
                        ExecutionReceipt.Completed()
                    }
                },
            )
        try {
            assertTrue(bothInside.await(5, java.util.concurrent.TimeUnit.SECONDS), "two scopes must write at the same time")
            assertEquals(0L, bothInside.count, "both writes must be simultaneously inside")
        } finally {
            hold.countDown()
        }
        val results = jobs.awaitAll()
        assertEquals(setOf(confirmationA.plan.id, confirmationB.plan.id), results.map { it.id }.toSet())
        results.forEach { assertEquals(TaskState.SUCCEEDED, it.state, "each scope's write must succeed") }
    }

    @Test
    fun reconcileAfterItsReadWasCooperatelyDeletedDoesNotResurrectTheTask() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val inner = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, inner, newScope())
        val confirmation = confirmOne(plans)
        val task = coordinator.execute(confirmation) { ExecutionReceipt.Unknown() }
        val before = inner.putCount
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        // The collaboration delete runs inside the trusted read closure, which holds no gate.
        val outcome =
            runCatching {
                coordinator.reconcile(task.id) { row ->
                    reads.incrementAndGet()
                    coordinator.withInvalidatedConnection(row.connectionId) { inner.removeConnection(row.connectionId) }
                    Reconciliation.Matched(desired)
                }
            }
        assertEquals(1, reads.get(), "this reconcile read must have run exactly once")
        assertEquals(before, inner.putCount, "no write may follow a delete done inside the read")
        assertTrue(inner.tasks.isEmpty(), "the deleted task must not be resurrected")
        assertTrue(outcome.isFailure, "a delete inside the trusted read must refuse the reconciliation")
    }

    @Test
    fun reconcileWithUnchangedAuthorityStillMatchesTheOriginalTask() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, store, newScope())
        val confirmation = confirmOne(plans)
        val task = coordinator.execute(confirmation) { ExecutionReceipt.Unknown() }
        val before = store.putCount
        val matched = coordinator.reconcile(task.id) { Reconciliation.Matched(desired) }
        assertEquals(task.id, matched.id, "an unchanged authority must still reconcile the original task")
        assertEquals(TaskState.SUCCEEDED, matched.state, "a strict hash match settles the task")
        assertEquals(task.scope, matched.scope, "the reconciled task must keep its original scope")
        assertEquals(before + 1, store.putCount, "the settlement must persist exactly one row")
    }

    @Test
    fun twoPatchesOnTheSameFullResourceRefSerializeAcrossEngines() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-a", "confirm-a", "plan-b", "confirm-b"))
        val ref = ResourceRef(zone, "dns_record", "rec-1")
        val planA = plans.prepare(zone, DnsOperationLifecycle.ACTION_PATCH, ref, null, desired, fingerprint, 7, 1_000)
        val confirmA = plans.confirm(planA.id, zone, 7, fingerprint, 1_000)
        val planB = plans.prepare(zone, DnsOperationLifecycle.ACTION_PATCH, ref, null, desired, fingerprint, 7, 1_000)
        val confirmB = plans.confirm(planB.id, zone, 7, fingerprint, 1_000)
        val holderScope = newScope(Dispatchers.Default)
        extraScopes += holderScope
        ownedJobs.add(holderScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val queue = RecordingQueue()
        val waitingScope = newScope(queue)
        extraScopes += waitingScope
        ownedJobs.add(waitingScope.coroutineContext[kotlinx.coroutines.Job]!!)
        val holderEngine = OperationCoordinator(plans, store, resolver, { 1_000 }, holderScope, Dispatchers.Unconfined)
        val waitingEngine = OperationCoordinator(plans, store, resolver, { 1_000 }, waitingScope, Dispatchers.Unconfined)
        val holderEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder =
            async(Dispatchers.Default) {
                holderEngine.execute(confirmA) {
                    holderEntered.countDown()
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder must be released")
                    ExecutionReceipt.Completed()
                }
            }
        assertTrue(holderEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the holder must be inside")
        var writes = 0
        val contender = async { waitingEngine.execute(confirmB) { writes++; ExecutionReceipt.Completed() } }
        runCurrent()
        queue.runAll()
        assertTrue(queue.runs.get() > 0, "the contender's continuation must really have run")
        assertFalse(contender.isCompleted, "the contender must be parked at the same full reference")
        assertEquals(0, writes, "the contender must not have sent while the reference is held")
        release.countDown()
        val first = holder.await()
        queue.runAll()
        val second = contender.await()
        assertEquals(1, writes, "the contender must send exactly once after release")
        assertEquals(confirmA.plan.id, first.id, "the holder must own its original task")
        assertEquals(TaskState.SUCCEEDED, first.state)
        assertEquals(confirmB.plan.id, second.id, "the contender must own its original task")
        assertEquals(TaskState.SUCCEEDED, second.state)
    }

    @Test
    fun anUnchangedMetadataRowCheckStillSettlesTheOriginalTask() = runTest {
        val resolver = Resolver(live(), listOf(liveConnection))
        val store = TestStore()
        val plans = scopedIssuer(resolver, idsOf("plan-1", "confirm-1"))
        val coordinator = engine(plans, resolver, store, newScope())
        val confirmation = confirmOne(plans)
        var writes = 0
        val returned = coordinator.execute(confirmation) { writes++; ExecutionReceipt.Completed() }
        assertEquals(1, writes)
        assertEquals(confirmation.plan.id, returned.id)
        assertEquals(TaskState.SUCCEEDED, returned.state)
        assertEquals(TaskState.SUCCEEDED, store.tasks[returned.id]?.state)
        assertEquals(zone, returned.scope)
    }
}
