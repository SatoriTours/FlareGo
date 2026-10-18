package com.flarego.core.application.r2

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Actual editor command, supplied independently of a plan or persisted task. Memory only. */
sealed interface R2Command {
    data class Bucket(val write: R2BucketWrite) : R2Command
    data class Object(val write: R2ObjectWrite) : R2Command
}
data class R2LiveSelection(val connection: Connection, val credentialRevision: Long, val routeEpoch: Long, val command: R2Command?)
data class R2PreparedPreview(val planId: String, val detail: R2ChangePreview, val targets: List<String>)

/** One ViewModel owner for R2 confirmations and read-only recovery; no executable state in the database. */
class R2WriteRuntime(
    private val appScope: CoroutineScope,
    private val store: OperationStore,
    private val dispatcher: CoroutineDispatcher,
    fingerprint: FingerprintPort,
    private val clock: OperationClock,
    ids: OperationIds,
    private val actual: () -> R2LiveSelection?,
    private val connections: () -> List<Connection>,
    private val revision: (String) -> Long,
    session: suspend (AccountScope) -> R2Session,
    private val publish: (List<OperationTask>) -> Unit = {},
) {
    private val context = object : OperationContextResolver {
        override fun current(): LiveOperationContext? {
            val live = actual() ?: return null
            val account = AccountScope(live.connection.id, live.connection.accountId)
            return LiveOperationContext(live.connection, account, live.credentialRevision,
                live.command?.let(::requestFingerprint) ?: "0".repeat(64))
        }
        override fun liveConnections() = connections()
        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            val account = scope as? AccountScope ?: return false
            val owner = connections().singleOrNull { it.id == account.connectionId && it.accountId == account.accountId } ?: return false
            val current = revision(owner.id)
            return current >= 0 && (credentialRevision == null || credentialRevision == current)
        }
    }
    private val plans = ChangePlans(context, clock, ids)
    private val coordinator = OperationCoordinator(plans, store, context, clock, appScope, dispatcher)
    private val controller = R2Controller(context, plans, coordinator, clock, fingerprint, store, dispatcher,
        selectionEpoch = { actual()?.routeEpoch ?: -1 }, session = session)
    private val prepared = mutableMapOf<String, ChangePlan>()
    private val mutableTasks = MutableStateFlow<List<OperationTask>>(emptyList())
    val tasks = mutableTasks.asStateFlow()

    private fun requestFingerprint(command: R2Command): String = when (command) {
        is R2Command.Bucket -> controller.requestFingerprint(command.write)
        is R2Command.Object -> controller.requestObjectFingerprint(command.write)
    }
    suspend fun prepare(command: R2Command): R2PreparedPreview {
        val live = actual() ?: throw stale()
        if (live.command != command) throw stale()
        val plan = when (command) {
            is R2Command.Bucket -> controller.prepare(command.write)
            is R2Command.Object -> controller.prepareObject(command.write)
        }
        if (actual() != live) throw stale()
        prepared[plan.id] = plan
        val detail = when (command) {
            is R2Command.Bucket -> controller.preview(plan.id)
            is R2Command.Object -> controller.previewObject(plan.id)
        }
        val targets = when (command) {
            is R2Command.Bucket -> listOf(detail.bucket)
            is R2Command.Object -> when (val write = command.write) {
                is R2ObjectWrite.Upload -> listOf(write.key)
                is R2ObjectWrite.Delete -> listOf(write.key)
                is R2ObjectWrite.BulkDelete -> write.keys.toList()
            }
        }
        return R2PreparedPreview(plan.id, detail, targets)
    }
    suspend fun confirm(planId: String): OperationTask {
        val live = actual() ?: throw stale()
        val command = live.command ?: throw stale()
        val plan = prepared[planId] ?: throw stale()
        if (!context.isLive(plan.scope, live.credentialRevision)) throw stale()
        val confirmed = plans.confirm(plan.id, AccountScope(live.connection.id, live.connection.accountId),
            live.credentialRevision, requestFingerprint(command), clock.nowMs())
        // App-owned waiter, not a composition-owned operation. A vanished UI cannot cancel
        // settlement/publication after the coordinator has accepted the confirmation.
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (command) {
                    is R2Command.Bucket -> controller.submit(command.write, confirmed)
                    is R2Command.Object -> controller.submitObject(command.write, confirmed)
                }
            } finally { withContext(NonCancellable) { refreshTasks() } }
        }.await()
    }
    suspend fun reconcile(taskId: String): OperationTask {
        val task = withContext(dispatcher) { store.get(taskId) } ?: throw stale()
        val result = when (task.resourceRef?.kind) {
            "r2_bucket" -> controller.reconcile(taskId)
            "r2_object" -> controller.reconcileObject(taskId)
            else -> throw stale()
        }
        refreshTasks()
        return result
    }
    suspend fun refreshTasks() {
        val all = withContext(dispatcher) { connections().flatMap { store.list(it.id) } }
        mutableTasks.value = all.filter { it.actionKey in R2_ACTIONS }
        publish(all)
    }
    companion object {
        val R2_ACTIONS = setOf("r2-create-bucket", "r2-create-bucket-by-name", "r2-patch-bucket", "r2-delete-bucket",
            "r2-put-object", "r2-delete-object", "r2-delete-objects")
        fun unsettled(state: TaskState) = state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.ACCEPTED,
            TaskState.PARTIAL, TaskState.UNKNOWN, TaskState.RECONCILING, TaskState.ACTION_REQUIRED)
    }
    private fun stale() = CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 输入、页面或凭据已变化，请重新预览确认")
}
