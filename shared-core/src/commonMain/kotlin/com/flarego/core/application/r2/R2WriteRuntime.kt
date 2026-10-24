package com.flarego.core.application.r2

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Actual editor command, supplied independently of a plan or persisted task. Memory only. */
sealed interface R2Command {
    data class Bucket(val write: R2BucketWrite) : R2Command
    data class Object(val write: R2ObjectWrite) : R2Command
    /** Batch B1 configuration write; the typed rules stay in this command, never in a plan. */
    data class Configuration(val write: R2ConfigurationWrite) : R2Command
    /**
     * Batch B2 domain write (explicit adapter for the separate domain document model).
     *
     * It shares this runtime's issuer, 64-slot plan window, task store and bucket lock; only the
     * typed write and its per-action before/after contract differ from batch B1.
     */
    data class Domain(val write: R2DomainConfigurationWrite) : R2Command
    /**
     * Batch B3 Sippy write.
     *
     * A `PutSippy` carries a memory-only credential draft; the draft stays in this command (and in
     * the controller's prepared entry) and never reaches a plan, a task row or the database.
     */
    data class Sippy(val write: R2SippyWrite) : R2Command
    /**
     * Batch C bucket-job creation.
     *
     * The command carries the frozen job kind and its explicit parameters (and, for a prefix delete,
     * the operator's manual multipart statement). It never carries a credential.
     */
    data class Job(val write: R2JobWrite) : R2Command
    /** Batch C event-notification write: append-only rules or a specific deletion intent. */
    data class Notification(val write: R2NotificationWrite) : R2Command
}
data class R2LiveSelection(val connection: Connection, val credentialRevision: Long, val routeEpoch: Long, val command: R2Command?)
data class R2PreparedPreview(val planId: String, val detail: R2ChangePreview, val targets: List<String>)

/** One ViewModel owner for R2 confirmations and read-only recovery; no executable state in the database. */
@OptIn(ExperimentalAtomicApi::class)
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
    // Optional batch B1 dependency. An existing batch A construction keeps working without it;
    // a real app wiring must supply it or no configuration command can be prepared.
    configSession: (suspend (AccountScope) -> R2ConfigurationSession)? = null,
    // Optional batch B2 dependency. Batch A/B1 constructions keep working without it.
    domainSession: (suspend (AccountScope) -> R2DomainConfigurationSession)? = null,
    // Optional batch B3 dependency. Existing constructions keep working without it; a real app
    // wiring must supply it or no Sippy command can be prepared (the page stays read-only).
    sippySession: (suspend (AccountScope) -> R2SippySession)? = null,
    // Optional batch C dependencies. Without them no job/notification command can be prepared and
    // nothing can be sent (fail closed, read-only).
    jobSession: (suspend (AccountScope) -> R2JobSession)? = null,
    notificationSession: (suspend (AccountScope) -> R2NotificationSession)? = null,
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
    private val plans = ChangePlans(context, clock, ids, retentionLimit = 64)
    private val coordinator = OperationCoordinator(plans, store, context, clock, appScope, dispatcher)
    private val controller = R2Controller(context, plans, coordinator, clock, fingerprint, store, dispatcher,
        selectionEpoch = { actual()?.routeEpoch ?: -1 }, session = session)
    private val configuration = configSession?.let { load ->
        R2ConfigurationController(context, plans, coordinator, clock, fingerprint, store, dispatcher,
            selectionEpoch = { actual()?.routeEpoch ?: -1 }, session = load)
    }
    private fun configuration(): R2ConfigurationController =
        configuration ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前应用未接通 R2 配置写入；本页只读，不会发送配置写入")
    private val domains = domainSession?.let { load ->
        R2DomainConfigurationController(context, plans, coordinator, clock, fingerprint, store, dispatcher,
            selectionEpoch = { actual()?.routeEpoch ?: -1 }, session = load)
    }
    private fun domains(): R2DomainConfigurationController =
        domains ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前应用未接通 R2 域名配置写入；本页只读，不会发送域名配置写入")
    private val sippy = sippySession?.let { load ->
        R2SippyController(context, plans, coordinator, clock, fingerprint, store, dispatcher,
            selectionEpoch = { actual()?.routeEpoch ?: -1 }, session = load)
    }
    private fun sippy(): R2SippyController =
        sippy ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前应用未接通 Sippy 配置写入；本页只读，不会发送 Sippy 配置写入")
    // A job create needs all three: the jurisdiction-aware bucket read (batch B configuration session),
    // the job plane, and the notification configuration used for the conflict preflight.
    private val jobs =
        if (jobSession != null && configSession != null && notificationSession != null) {
            R2JobsController(context, plans, coordinator, clock, fingerprint, store, dispatcher,
                selectionEpoch = { actual()?.routeEpoch ?: -1 }, bucketSession = configSession,
                jobSession = jobSession, notificationSession = notificationSession)
        } else {
            null
        }
    private fun jobs(): R2JobsController =
        jobs ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前应用未接通 R2 后台作业；本页只读，不会创建后台作业")
    private val notifications = notificationSession?.let { load ->
        R2NotificationController(context, plans, coordinator, clock, fingerprint, store, dispatcher,
            selectionEpoch = { actual()?.routeEpoch ?: -1 }, session = load)
    }
    private fun notifications(): R2NotificationController =
        notifications ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "当前应用未接通 R2 事件通知；本页只读，不会发送事件通知配置写入")
    private val prepared = AtomicReference<Map<String, ChangePlan>>(emptyMap())
    private val mutableTasks = MutableStateFlow<List<OperationTask>>(emptyList())
    val tasks = mutableTasks.asStateFlow()

    private fun requestFingerprint(command: R2Command): String = when (command) {
        is R2Command.Bucket -> controller.requestFingerprint(command.write)
        is R2Command.Object -> controller.requestObjectFingerprint(command.write)
        is R2Command.Configuration -> configuration().requestFingerprint(command.write)
        is R2Command.Domain -> domains().requestFingerprint(command.write)
        is R2Command.Sippy -> sippy().requestFingerprint(command.write)
        is R2Command.Job -> jobs().requestFingerprint(command.write)
        is R2Command.Notification -> notifications().requestFingerprint(command.write)
    }
    suspend fun prepare(command: R2Command): R2PreparedPreview {
        pruneRetained()
        val live = actual() ?: throw stale()
        if (live.command != command) throw stale()
        val plan = when (command) {
            is R2Command.Bucket -> controller.prepare(command.write)
            is R2Command.Object -> controller.prepareObject(command.write)
            is R2Command.Configuration -> configuration().prepare(command.write)
            is R2Command.Domain -> domains().prepare(command.write)
            is R2Command.Sippy -> sippy().prepare(command.write)
            is R2Command.Job -> jobs().prepare(command.write)
            is R2Command.Notification -> notifications().prepare(command.write)
        }
        if (actual() != live) {
            plans.discardUnclaimed(plan.id)
            pruneRetained()
            throw stale()
        }
        pruneRetained()
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table.filterKeys(plans::retains) + (plan.id to plan))) break
        }
        val detail = when (command) {
            is R2Command.Bucket -> controller.preview(plan.id)
            is R2Command.Object -> controller.previewObject(plan.id)
            is R2Command.Configuration -> configuration().preview(plan.id)
            is R2Command.Domain -> domains().preview(plan.id)
            is R2Command.Sippy -> sippy().preview(plan.id)
            is R2Command.Job -> jobs().preview(plan.id)
            is R2Command.Notification -> notifications().preview(plan.id)
        }
        val targets = when (command) {
            is R2Command.Bucket -> listOf(detail.bucket)
            is R2Command.Object -> when (val write = command.write) {
                is R2ObjectWrite.Upload -> listOf(write.key)
                is R2ObjectWrite.Delete -> listOf(write.key)
                is R2ObjectWrite.BulkDelete -> write.keys.toList()
            }
            is R2Command.Configuration -> listOf(
                "${command.write.target.jurisdiction}/${command.write.target.bucket}/${r2ConfigurationWriteKind(command.write).name}",
            )
            is R2Command.Domain -> listOf(
                "${command.write.target.jurisdiction}/${command.write.target.bucket}/${r2DomainKindOf(command.write).name}" +
                    (command.write.domain?.let { "/$it" } ?: ""),
            )
            is R2Command.Sippy -> listOf(
                "${command.write.target.jurisdiction}/${command.write.target.bucket}/${R2TaskTargets.SIPPY_FAMILY}",
            )
            is R2Command.Job -> listOf(
                "${command.write.target.jurisdiction}/${command.write.target.bucket}/${R2TaskTargets.JOB_FAMILY}",
            )
            is R2Command.Notification -> listOf(
                "${command.write.target.jurisdiction}/${command.write.target.bucket}" +
                    "/${r2NotificationIntentOf(command.write).family}/${command.write.queueId}",
            )
        }
        return R2PreparedPreview(plan.id, detail, targets)
    }
    /** Revocation is not task settlement. An admitted execution cannot be cancelled here. */
    fun discardPreview(planId: String): Boolean {
        if (prepared.load()[planId] == null) return false
        val discarded = plans.discardUnclaimed(planId)
        pruneRetained()
        return discarded
    }

    private fun pruneRetained() {
        plans.pruneExpired()
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table.filterKeys(plans::retains))) break
        }
        controller.pruneRetiredPayloads()
        configuration?.pruneRetiredPayloads()
        domains?.pruneRetiredPayloads()
        sippy?.pruneRetiredPayloads()
        jobs?.pruneRetiredPayloads()
        notifications?.pruneRetiredPayloads()
    }

    suspend fun confirm(planId: String): OperationTask {
        pruneRetained()
        val live = actual() ?: throw stale()
        val command = live.command ?: throw stale()
        val plan = prepared.load()[planId] ?: throw stale()
        if (!context.isLive(plan.scope, live.credentialRevision)) throw stale()
        val confirmed = plans.confirm(plan.id, AccountScope(live.connection.id, live.connection.accountId),
            live.credentialRevision, requestFingerprint(command), clock.nowMs())
        // A Sippy confirmation must be dispatchable before a task row exists: a retired draft or a
        // foreign command is refused here instead of becoming a queued/failed row.
        if (command is R2Command.Sippy) sippy().requireDispatchable(planId, command.write)
        // App-owned waiter, not a composition-owned operation. A vanished UI cannot cancel
        // settlement/publication after the coordinator has accepted the confirmation.
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (command) {
                    is R2Command.Bucket -> controller.submit(command.write, confirmed)
                    is R2Command.Object -> controller.submitObject(command.write, confirmed)
                    is R2Command.Configuration -> configuration().submit(command.write, confirmed)
                    is R2Command.Domain -> domains().submit(command.write, confirmed)
                    is R2Command.Sippy -> sippy().submit(command.write, confirmed)
                    is R2Command.Job -> jobs().submit(command.write, confirmed)
                    is R2Command.Notification -> notifications().submit(command.write, confirmed)
                }
            } finally { withContext(NonCancellable) { refreshTasks() } }
        }.await()
    }
    suspend fun reconcile(taskId: String): OperationTask {
        val task = withContext(dispatcher) { store.get(taskId) } ?: throw stale()
        val result = when {
            // Sippy tasks persist in the shared configuration locator; dispatch by their reviewed
            // action so no rules/document recovery path can be applied to a Sippy write.
            task.actionKey in R2TaskTargets.SIPPY_ACTIONS -> sippy().reconcile(taskId)
            // Batch C tasks: a job task is verified by its own job ID, a notification task only by its
            // action-specific contract (never by a foreign rules/document recovery path).
            task.actionKey in R2TaskTargets.JOB_ACTIONS -> jobs().reconcile(taskId)
            task.actionKey in R2TaskTargets.NOTIFICATION_ACTIONS -> notifications().reconcile(taskId)
            // Domain tasks persist in the shared configuration locator; dispatch by their reviewed
            // action so no B1 `rules[]` recovery path can be applied to a domain.
            task.actionKey in R2TaskTargets.DOMAIN_WRITE_ACTIONS -> domains().reconcile(taskId)
            task.resourceRef?.kind == R2TaskTargets.BUCKET_KIND -> controller.reconcile(taskId)
            task.resourceRef?.kind == R2TaskTargets.OBJECT_KIND -> controller.reconcileObject(taskId)
            task.resourceRef?.kind == R2TaskTargets.CONFIGURATION_KIND -> configuration().reconcile(taskId)
            else -> throw stale()
        }
        refreshTasks()
        return result
    }
    /**
     * The typed, non-secret preview detail of one prepared job / notification write.
     *
     * This is the whole non-visual contract Task 3 consumes for its own display: the job kind and its
     * explicit parameters (or the notification queue, intent and rule summaries). It grants nothing —
     * no plan, no confirmation and no send permission.
     */
    fun jobPreviewDetail(planId: String): R2JobWritePreview = jobs().previewDetail(planId)

    fun notificationPreviewDetail(planId: String): R2NotificationWritePreview = notifications().previewDetail(planId)

    /**
     * Deterministic ownership observation of one Sippy preview's task-owned material.
     *
     * It returns only the lease state and whether a material reference is still held; the secret is
     * never reachable through it. It exists for the ownership tests.
     */
    internal fun sippyMaterialObservation(planId: String): R2SippyMaterialObservation? =
        sippy?.materialObservation(planId)

    suspend fun refreshTasks() {
        val all = withContext(dispatcher) { connections().flatMap { store.list(it.id) } }
        mutableTasks.value = all.filter { it.actionKey in R2TaskTargets.ALL_WRITE_ACTIONS }
        publish(all)
    }
    companion object {
        val R2_ACTIONS = R2TaskTargets.A_ACTIONS
        fun unsettled(state: TaskState) = state in R2TaskTargets.UNSETTLED_STATES
    }
    private fun stale() = CloudFailure(CloudFailureCode.INVALID_INPUT, "R2 输入、页面或凭据已变化，请重新预览确认")
}
