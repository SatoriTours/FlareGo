package com.flarego.core.application.operations

import com.flarego.core.model.*
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.ports.OperationStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a trusted write adapter observed. [Unknown] exists for information that was
 * lost (for example a legacy Unit API); it is never completion.
 */
sealed interface ExecutionReceipt {
    data class Completed(val providerRequestId: String? = null) : ExecutionReceipt

    data class Accepted(val pollPath: String? = null, val providerRequestId: String? = null) : ExecutionReceipt

    data class Partial(val safeDetail: String, val providerRequestId: String? = null) : ExecutionReceipt

    data class Unknown(val providerRequestId: String? = null) : ExecutionReceipt
}

/** Trusted read-only reconciliation evidence for one task. */
sealed interface Reconciliation {
    data class Matched(val observedHash: String) : Reconciliation

    object NotApplied : Reconciliation

    object StillUnknown : Reconciliation

    object ActionRequired : Reconciliation
}

/**
 * Runs confirmed writes at most once, keeps the metadata authoritative, and
 * reconciles or recovers conservatively.
 *
 * Exactly one issued confirmation is consumed atomically; every concurrent caller
 * shares one task/result and at most one write, even across coordinators using the
 * same issuer. The shared job belongs to [appScope], so cancelling a UI waiter never
 * cancels the execution or resends it. Metadata is persisted before the write, and a
 * failure is never reported as "not executed" without trusted evidence.
 */
class OperationCoordinator(
    private val plans: ChangePlans,
    private val store: OperationStore,
    private val context: OperationContextResolver,
    private val clock: OperationClock,
    private val appScope: CoroutineScope,
    private val storeDispatcher: CoroutineDispatcher,
) {
    private val claims = Mutex()
    private val executions = mutableMapOf<String, Deferred<Result<OperationTask>>>()
    private val resources = mutableMapOf<String, Mutex>()
    private val metadataGates = mutableMapOf<String, Mutex>()
    private val invalidated = mutableSetOf<String>()

    /** Runs the confirmed write at most once and returns the authoritative task. */
    suspend fun execute(plan: ConfirmedPlan, write: suspend () -> ExecutionReceipt): OperationTask {
        val job =
            claims.withLock {
                executions[plan.confirmationId]
                    ?: appScope.async(start = CoroutineStart.LAZY) { attempt(plan, write) }.also {
                        executions[plan.confirmationId] = it
                    }
            }
        job.start()
        return job.await().getOrThrow()
    }

    private suspend fun attempt(plan: ConfirmedPlan, write: suspend () -> ExecutionReceipt): Result<OperationTask> =
        try {
            Result.success(runConfirmed(plan, write))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

    private suspend fun runConfirmed(plan: ConfirmedPlan, write: suspend () -> ExecutionReceipt): OperationTask {
        // Atomic single consumption: a cloned plan, a foreign issuer, a consumed
        // confirmation or an expired one is refused before anything is written.
        val confirmed = plans.consume(plan) ?: throw invalidInputFailure()
        return resourceLock(confirmed).withLock {
            requireAuthorized(confirmed)
            if (storeOnIo { store.get(confirmed.id) } != null) throw invalidInputFailure()
            val queued = newTask(confirmed, TaskState.QUEUED)
            persist(confirmed, queued)
            val running = advance(queued, TaskState.RUNNING)
            persist(confirmed, running)
            // Re-check immediately before the write, not only at the entry.
            requireAuthorized(confirmed)
            val receipt =
                try {
                    write()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // An unrecognized post-send failure keeps the pre-persisted RUNNING
                    // state as evidence that a send may have happened.
                    return@withLock running
                }
            val settled = settle(confirmed, running, receipt)
            persist(confirmed, settled)
            settled
        }
    }

    /**
     * Read-only reconciliation. A matched observation only succeeds when it is a
     * strict hash equal to the recorded desired hash; nothing else is replayed.
     */
    suspend fun reconcile(taskId: String, read: suspend (OperationTask) -> Reconciliation): OperationTask {
        val task = storeOnIo { store.get(taskId) } ?: throw invalidInputFailure()
        requireLiveMetadata(task)
        val outcome = read(task)
        requireLiveMetadata(task)
        val updated =
            when (outcome) {
                is Reconciliation.Matched -> {
                    requireSha256(outcome.observedHash)
                    val desired = task.desiredHash
                    if (desired != null && outcome.observedHash == desired) {
                        advance(task, TaskState.SUCCEEDED)
                    } else {
                        advance(task, TaskState.UNKNOWN)
                    }
                }
                Reconciliation.NotApplied -> advance(task, TaskState.ACTION_REQUIRED)
                Reconciliation.StillUnknown -> advance(task, TaskState.UNKNOWN)
                Reconciliation.ActionRequired -> advance(task, TaskState.ACTION_REQUIRED)
            }
        // The gate is taken once here: `persist` acquires the same non-reentrant
        // Mutex, so nesting it would self-deadlock. Deletion uses this same latch.
        withMetadataGate(task.connectionId) {
            storeOnIo { store.putWithSummary(updated, OperationTaskPersistence.summaryFor(updated)) }
        }
        return updated
    }

    /**
     * Conservative recovery. Synchronous by design: the caller must run it on an IO
     * dispatcher. It never restores a payload, never rebuilds an executable
     * confirmation and never replays a write.
     */
    fun recoverPending(): List<OperationTask> {
        val recovered = mutableListOf<OperationTask>()
        for (connection in context.liveConnections()) {
            for (task in store.list(connection.id)) {
                if (!context.isLive(task.scope, null)) continue
                val updated =
                    when (task.state) {
                        TaskState.RUNNING, TaskState.RECONCILING -> advance(task, TaskState.UNKNOWN)
                        TaskState.QUEUED -> advance(task, TaskState.ACTION_REQUIRED)
                        // ACCEPTED/PARTIAL/UNKNOWN stay awaiting verification; SUCCEEDED and
                        // FAILED are unchanged.
                        else -> task
                    }
                if (updated !== task) store.putWithSummary(updated, OperationTaskPersistence.summaryFor(updated))
                recovered += updated
            }
        }
        return recovered
    }

    /** Immediately invalidates confirmations and metadata updates for a connection. */
    fun invalidateConnection(connectionId: String) {
        invalidated += connectionId
    }

    /**
     * Runs [remove] under the same latch that guards metadata updates, on the store
     * dispatcher, so a late result cannot recreate a deleted task.
     */
    suspend fun withInvalidatedConnection(connectionId: String, remove: () -> Unit) {
        invalidateConnection(connectionId)
        // The removal callback must enter through the *same* metadata latch even though
        // the connection is now invalidated: this call IS the deletion protocol. Normal
        // metadata writes keep failing closed through withMetadataGate's check.
        metadataGate(connectionId).withLock {
            withContext(storeDispatcher) { remove() }
        }
    }

    private fun resourceLock(plan: ChangePlan): Mutex {
        val key =
            plan.resourceRef?.let { "${it.kind}:${it.externalId}:${plan.scope}" }
                ?: "${plan.actionKey}:${plan.scope}"
        return resources.getOrPut(key) { Mutex() }
    }

    private fun metadataGate(connectionId: String): Mutex = metadataGates.getOrPut(connectionId) { Mutex() }

    private suspend fun <T> withMetadataGate(connectionId: String, block: suspend () -> T): T =
        metadataGate(connectionId).withLock {
            if (connectionId in invalidated) throw invalidInputFailure()
            block()
        }

    private suspend fun <T> storeOnIo(block: suspend () -> T): T = withContext(storeDispatcher) { block() }

    private fun requireAuthorized(plan: ChangePlan) {
        if (plans.isExpired(plan)) throw invalidInputFailure()
        val live = context.current() ?: throw invalidInputFailure()
        if (live.credentialRevision != plan.credentialRevision) throw invalidInputFailure()
        // The current selection and input must still be exactly this plan's scope and
        // fingerprint, not merely a live revision.
        if (live.scope != plan.scope) throw invalidInputFailure()
        if (live.requestFingerprint != plan.requestFingerprint) throw inputChanged()
        if (!context.isLive(plan.scope, plan.credentialRevision)) throw invalidInputFailure()
    }

    private fun requireLiveMetadata(task: OperationTask) {
        // A task row has no revision field: metadata checks the live scope only.
        if (!context.isLive(task.scope, null)) throw invalidInputFailure()
    }

    private suspend fun persist(plan: ChangePlan, task: OperationTask) = persist(task.connectionId, task)

    private suspend fun persist(connectionId: String, task: OperationTask) {
        withMetadataGate(connectionId) {
            storeOnIo { store.putWithSummary(task, OperationTaskPersistence.summaryFor(task)) }
        }
    }

    private fun newTask(plan: ChangePlan, state: TaskState): OperationTask {
        val now = clock.nowMs()
        if (now < 0) throw invalidInputFailure()
        return OperationTask(
            id = plan.id,
            connectionId = connectionIdOf(plan.scope),
            scope = plan.scope,
            actionKey = plan.actionKey,
            resourceRef = plan.resourceRef,
            requestFingerprint = plan.requestFingerprint,
            desiredHash = plan.desiredHash,
            state = state,
            // Only fixed public text derived from the state ever reaches the row.
            safeDetail = OperationTaskPersistence.summaryDetailFor(state),
            providerRequestId = null,
            pollPath = null,
            createdAtMs = now,
            updatedAtMs = now,
        )
    }

    private fun advance(task: OperationTask, state: TaskState): OperationTask {
        val now = maxOf(clock.nowMs().coerceAtLeast(0), task.updatedAtMs)
        return task.copy(
            state = state,
            safeDetail = OperationTaskPersistence.summaryDetailFor(state),
            updatedAtMs = now,
        )
    }

    private fun settle(plan: ChangePlan, running: OperationTask, receipt: ExecutionReceipt): OperationTask {
        val state =
            when (receipt) {
                is ExecutionReceipt.Completed -> TaskState.SUCCEEDED
                is ExecutionReceipt.Accepted -> TaskState.ACCEPTED
                is ExecutionReceipt.Partial -> TaskState.PARTIAL
                is ExecutionReceipt.Unknown -> TaskState.UNKNOWN
            }
        val providerId = receiptProviderId(receipt)
        val pollPath = (receipt as? ExecutionReceipt.Accepted)?.pollPath?.let { safePollPathOrNull(it, plan) }
        return advance(running, state).copy(
            providerRequestId = providerId,
            pollPath = pollPath,
        )
    }

    private fun receiptProviderId(receipt: ExecutionReceipt): String? {
        val raw =
            when (receipt) {
                is ExecutionReceipt.Completed -> receipt.providerRequestId
                is ExecutionReceipt.Accepted -> receipt.providerRequestId
                is ExecutionReceipt.Partial -> receipt.providerRequestId
                is ExecutionReceipt.Unknown -> receipt.providerRequestId
            }
        val candidate = raw ?: return null
        if (candidate.isBlank() || candidate.length > 128) return null
        if (candidate.any { it.code !in 0x20..0x7E }) return null
        return candidate
    }

    /**
     * A poll locator must be a reviewed relative path for this scope; anything else
     * is dropped (null) rather than guessed, and it never grants poll permission.
     */
    private fun safePollPathOrNull(path: String, plan: ChangePlan): String? =
        try {
            OperationTask(
                id = plan.id,
                connectionId = connectionIdOf(plan.scope),
                scope = plan.scope,
                actionKey = plan.actionKey,
                resourceRef = plan.resourceRef,
                requestFingerprint = plan.requestFingerprint,
                desiredHash = plan.desiredHash,
                state = TaskState.ACCEPTED,
                safeDetail = OperationTaskPersistence.summaryDetailFor(TaskState.ACCEPTED),
                providerRequestId = null,
                pollPath = path,
                createdAtMs = 0,
                updatedAtMs = 0,
            )
            path
        } catch (_: Throwable) {
            // A malformed or cross-scope locator, or a bad post-send metadata shape,
            // must never be reported as "not sent".
            null
        }
}

internal fun inputChanged(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_INPUT, "当前输入或账号范围已变化，请重新确认后再操作")

internal fun connectionIdOf(scope: CloudScope): String =
    when (scope) {
        is AccountScope -> scope.connectionId
        is ZoneScope -> scope.connectionId
        is UserScope -> scope.connectionId
        is OrganizationScope -> scope.connectionId
    }
