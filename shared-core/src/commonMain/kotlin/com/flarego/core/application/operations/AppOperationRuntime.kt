package com.flarego.core.application.operations

import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/** App lifetime owner of confirmed DNS writes, metadata recovery and read-only reconciliation. */
class AppOperationRuntime(
    private val appScope: CoroutineScope,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val currentState: () -> AppState,
    private val selectionEpoch: () -> Long,
    private val credentialRevision: (String) -> Long,
    private val sessionFactory: SessionFactory,
    private val fingerprint: FingerprintPort,
    private val clock: OperationClock,
    private val ids: OperationIds,
    private val publish: (List<OperationTask>) -> Unit,
) : DnsOperations {
    private data class Input(val zone: ZoneScope, val action: String, val recordId: String?, val draft: DnsDraft,
        val connection: Connection, val epoch: Long, val revision: Long)
    private val input = MutableStateFlow<Input?>(null)
    private val writeLock = Mutex()
    private val resolver: OperationContextResolver = object : OperationContextResolver {
        override fun liveConnections() = currentState().connections.filterNot { it.demo }
        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            val account = when(scope) { is AccountScope -> scope; is ZoneScope -> scope.account; else -> return false }
            val owner = liveConnections().find { it.id == account.connectionId && it.accountId == account.accountId } ?: return false
            return credentialRevision == null || this@AppOperationRuntime.credentialRevision(owner.id) == credentialRevision
        }
        override fun current(): LiveOperationContext? {
            val command = input.value ?: return null
            val state = currentState()
            if (state.selected != command.connection || selectionEpoch() != command.epoch ||
                state.domain?.id != command.zone.zoneId || !isLive(command.zone,command.revision)) return null
            // Independent real command input, held in memory only; no plan/receipt values feed this hash.
            val hash = fingerprint.sha256(CanonicalJson.encode(lifecycle.semanticInput(command.action,command.zone,command.recordId,command.draft,command.connection.id)))
            return LiveOperationContext(command.connection,command.zone,command.revision,hash)
        }
    }
    private val plans = ChangePlans(resolver,clock,ids)
    private val coordinator = OperationCoordinator(plans,store,resolver,clock,appScope,storeDispatcher)
    private val lifecycle = DnsOperationLifecycle(resolver,plans,coordinator,clock,fingerprint,ZoneOwnerCheck { zone ->
        val state = currentState()
        val directory = state.products[com.flarego.core.application.inventory.ProductKey.ZONES]
        directory?.scope == zone.account && directory.credentialRevision == credentialRevision(zone.connectionId) &&
            (directory.content as? com.flarego.core.application.inventory.ProductContent.Zones)?.items?.any {
                it.id == zone.zoneId && it.connectionId == zone.connectionId
            } == true
    },LegacyDnsReceiptSource {
        val command = input.value ?: throw invalidInputFailure()
        sessionFactory.create(command.connection)
    })
    // Recovery happens exactly once per app lifetime and finishes before accepting a new write.
    private val recovery = appScope.async(start = CoroutineStart.LAZY) {
        withContext(storeDispatcher) { coordinator.recoverPending() }
        refreshTasks()
    }

    override suspend fun create(zone: ZoneScope, draft: DnsDraft, frozenBefore: DnsRecord?): OperationTask =
        submit(zone,DnsOperationLifecycle.ACTION_CREATE,null,draft) { lifecycle.create(zone,draft,frozenBefore) }
    override suspend fun update(zone: ZoneScope, recordId: String, draft: DnsDraft, frozenBefore: DnsRecord?): OperationTask =
        submit(zone,DnsOperationLifecycle.ACTION_PATCH,recordId,draft) { lifecycle.update(zone,recordId,draft,frozenBefore) }
    override suspend fun delete(zone: ZoneScope, recordId: String, frozenBefore: DnsRecord?): OperationTask =
        submit(zone,DnsOperationLifecycle.ACTION_DELETE,recordId,DnsDraft("DELETE","","")) { lifecycle.delete(zone,recordId,frozenBefore) }

    private suspend fun submit(zone: ZoneScope, action: String, recordId: String?, draft: DnsDraft, write: suspend () -> OperationTask): OperationTask {
        val owner = currentState().selected ?: throw invalidInputFailure()
        ScopeGuard.requireOwned(zone,owner)
        val frozen = Input(zone,action,recordId,draft,owner,selectionEpoch(),credentialRevision(owner.id))
        // The job belongs to the app, so cancelling a UI confirmation waiter does not drop its guard or replay it.
        return appScope.async {
          runCatching {
            recovery.await()
            if (!writeLock.tryLock()) throw CloudFailure(CloudFailureCode.CONFLICT,"另一项操作正在执行，请稍后核对")
            try {
                input.value = frozen
                if (resolver.current() == null) throw invalidInputFailure()
                write()
            } finally {
                input.value = null
                writeLock.unlock()
                // A UI list refresh is not part of the write receipt. Never replace an
                // authoritative task/result with a post-send display failure.
                try { refreshTasks() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the persisted task and the returned receipt. */ }
            }
          }
        }.await().getOrThrow()
    }
    suspend fun recover() { recovery.await() }
    private suspend fun refreshTasks() {
        val tasks = withContext(storeDispatcher) { resolver.liveConnections().flatMap { store.list(it.id) } }
        publish(tasks.filter { resolver.isLive(it.scope,null) })
    }
    suspend fun reconcile(taskId: String) {
        recovery.await()
        val selected = currentState().selected ?: throw invalidInputFailure()
        val epoch = selectionEpoch()
        val revision = credentialRevision(selected.id)
        coordinator.reconcile(taskId) { task ->
            if (task.connectionId != selected.id) throw scopeMismatchFailure()
            ScopeGuard.requireOwned(task.scope,selected)
            val zone = task.scope as? ZoneScope ?: throw invalidInputFailure()
            val session = sessionFactory.create(selected)
            if (currentState().selected != selected || selectionEpoch() != epoch || credentialRevision(selected.id) != revision) throw invalidInputFailure()
            // Read a typed DNS endpoint only. Persisted pollPath never grants arbitrary request authority.
            val records = session.records(zone.zoneId)
            if (currentState().selected != selected || selectionEpoch() != epoch || credentialRevision(selected.id) != revision) throw invalidInputFailure()
            val record = task.resourceRef?.takeIf { it.kind == "dns_record" }?.externalId?.let { id -> records.singleOrNull { it.id == id } }
            if (task.actionKey == DnsOperationLifecycle.ACTION_PATCH && record != null) {
                val observed = DnsDraft(record.type,record.name,record.content,record.ttl,record.proxied,record.priority)
                Reconciliation.Matched(fingerprint.sha256(CanonicalJson.encode(dnsDesired(selected.id,record.id,observed))))
            } else {
                // Create has no receipt identity; a missing record in a legacy read cannot prove deletion.
                Reconciliation.StillUnknown
            }
        }
        refreshTasks()
    }
    suspend fun removeConnection(connectionId: String, remove: () -> Unit) {
        coordinator.withInvalidatedConnection(connectionId,remove)
    }
}
