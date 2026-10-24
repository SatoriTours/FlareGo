package com.flarego.core.application.r2

import com.flarego.core.application.operations.CanonicalJson
import com.flarego.core.application.operations.ChangePlan
import com.flarego.core.application.operations.ChangePlans
import com.flarego.core.application.operations.ConfirmedPlan
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.LiveOperationContext
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationContextResolver
import com.flarego.core.application.operations.OperationCoordinator
import com.flarego.core.application.operations.Reconciliation
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2BucketJob
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.R2JobCreateRequest
import com.flarego.core.model.R2JobDetails
import com.flarego.core.model.R2JobListQuery
import com.flarego.core.model.R2JobStatus
import com.flarego.core.model.R2JobType
import com.flarego.core.model.R2JobWrite
import com.flarego.core.model.R2StorageClass
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_JOB_MAX_PAGE_SIZE
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.TaskState
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2JobSession
import com.flarego.core.ports.R2NotificationSession
import com.flarego.core.ports.R2ConfigurationSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The client-side safety budget of one job preflight scan: 5 pages of at most 1000 jobs. */
const val R2_JOB_MAX_PREFLIGHT_PAGES: Int = 5

/** Fixed text for a preflight that cannot prove the bucket is quiescent. */
internal const val R2_JOB_PREFLIGHT_NOT_IDLE: String =
    "无法证明该桶当前没有在途作业或可识别的通知冲突；已阻止创建，请先在云端核对，不要重复提交"

/** Fixed text for an explicit whole-bucket clear that would leave a known notification conflict. */
internal const val R2_JOB_CLEAR_NOTIFICATION_CONFLICT: String =
    "该桶仍有已知的事件通知规则；清空整个桶的作业会与这些通知产生冲突，应用不会自动删除通知配置，已阻止创建"

/** Fixed text for a preflight observation that is readable but cannot be trusted as a baseline. */
internal const val R2_JOB_PREFLIGHT_UNKNOWN: String =
    "该桶当前的作业或通知配置包含应用尚未支持的内容，只能查看；已阻止创建后台作业"

/** Safe, non-secret description of one prepared job creation (Task 3 display input only). */
data class R2JobWritePreview(
    val action: R2JobType,
    val target: R2WriteTarget,
    /** Verbatim prefix of a prefix delete; empty is the explicit whole-bucket clear. */
    val prefix: String? = null,
    val isBucketClear: Boolean = false,
    val sourceStorageClass: R2StorageClass? = null,
    val destinationStorageClass: R2StorageClass? = null,
    /** Deleted objects observed by the read-only job scan of this preflight, never a total claim. */
    val observedJobs: Int = 0,
    /** Queues seen in the bucket's notification configuration at preflight (conflict awareness only). */
    val observedNotificationQueues: Int = 0,
)

/**
 * Previews, executes and read-only-recovers the reviewed batch C bucket-job creation through the
 * shared confirmation machinery of [R2WriteRuntime] (one issuer, one 64-slot plan window, one task
 * store, one account/jurisdiction/bucket lock and one unsettled gate shared with batch A/B).
 *
 * Safety contract:
 * - the preflight is a real read: the bucket itself, a bounded (5 × 1000) and complete job scan and
 *   the bucket's notification configuration. Any in-flight, uninterpretable, duplicated or overflowing
 *   observation refuses the creation — never "probably idle";
 * - the *same* preflight is re-read inside the admitted execution after the session/proof work and
 *   compared with the frozen baseline; a changed bucket state refuses the send instead of trusting the
 *   preview;
 * - the single POST is never retried; only a trustworthy same-kind job ID yields a read-only locator,
 *   a missing/foreign ID stays UNKNOWN and is never guessed;
 * - an accepted job is settled by one independent GET of the same job (kind and original parameters
 *   compared by digest). The POST's own report is never completion, and migration counters are never
 *   filled in with a guessed zero.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2JobsController(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val selectionEpoch: () -> Long = { 0L },
    /**
     * The batch B configuration session: [R2ConfigurationPort.getBucket] is the *jurisdiction-aware*
     * bucket read (it sends the reviewed `cf-r2-jurisdiction` header), so a same-named bucket of
     * another region is never mistaken for this target's.
     */
    private val bucketSession: suspend (AccountScope) -> R2ConfigurationSession,
    private val jobSession: suspend (AccountScope) -> R2JobSession,
    private val notificationSession: suspend (AccountScope) -> R2NotificationSession,
) {
    private class Prepared(
        val plan: ChangePlan,
        val write: R2JobWrite,
        val target: R2WriteTarget,
        val beforeState: JsonObject,
        val desiredState: JsonObject,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
        val detail: R2JobWritePreview,
    )

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    private fun publish(extra: Pair<String, Prepared>? = null) {
        while (true) {
            val table = prepared.load()
            val retained = table.filterKeys(plans::retains)
            val next = if (extra == null) retained else retained + extra
            if (!prepared.compareAndSet(table, next)) continue
            return
        }
    }

    internal fun pruneRetiredPayloads() = publish()

    /** Pure function of the live scope, target and typed command, for the product resolver. */
    fun requestFingerprint(write: R2JobWrite): String {
        val target = write.target
        return digest(
            buildJsonObject {
                put("action", R2TaskTargets.JOB_CREATE_ACTION)
                put("selectionEpoch", selectionEpoch())
                put("connection", target.account.connectionId)
                put("account", target.account.accountId)
                put("bucket", target.bucket)
                put("jurisdiction", target.jurisdiction)
                put("kind", write.kind.wireValue)
                when (write) {
                    is R2JobWrite.PrefixDelete -> {
                        put("prefix", write.prefix)
                        // The operator's manual multipart statement is part of the frozen command, so
                        // a command that lost it can never confirm a preview made with it.
                        put("manualMpuHandled", write.manualMpuHandled)
                    }
                    is R2JobWrite.StorageClassMigration -> {
                        put("source", write.source.wireValue)
                        put("destination", write.destination.wireValue)
                    }
                }
            },
        )
    }

    suspend fun prepare(write: R2JobWrite): ChangePlan {
        validateR2JobTarget(write.target)
        val live = requireCurrent(write)
        requireNoUnsettled(write.target)
        requireSameLive(live)
        val observed = preflight(write.target, clearRequested = write.isWholeBucketClear())
        requireSameLive(live)
        val beforeState = observed.state
        val desiredState = desiredState(write)
        val plan =
            plans.prepare(
                scope = write.target.account,
                actionKey = R2TaskTargets.JOB_CREATE_ACTION,
                resourceRef = R2TaskTargets.jobReference(write.target, write.kind),
                beforeHash = digest(beforeState),
                desiredHash = digest(desiredState),
                requestFingerprint = requestFingerprint(write),
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val entry =
            Prepared(
                plan = plan,
                write = write,
                target = write.target,
                beforeState = beforeState,
                desiredState = desiredState,
                live = live,
                preview = previewOf(write, observed),
                detail = detailOf(write, observed),
            )
        try {
            publish(extra = plan.id to entry)
        } catch (failure: Exception) {
            plans.discardUnclaimed(plan.id)
            publish()
            throw failure
        }
        return plan
    }

    fun preview(planId: String): R2ChangePreview = entry(planId).preview

    /** The safe, non-secret detail of one preview (Task 3 display input). */
    fun previewDetail(planId: String): R2JobWritePreview = entry(planId).detail

    suspend fun submit(write: R2JobWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, write, confirmed)
        requireNoUnsettled(p.target, p.plan.id)
        requireBound(p, write, confirmed)
        val guard = { requireBound(p, write, confirmed) }
        return coordinator.executeR2Dispatch(confirmed, guard, R2TaskTargets.group(p.target)) { beforeSend ->
            val api = jobSession(p.target.account)
            guard()
            requireNoUnsettled(p.target, p.plan.id)
            // Phase 1: the fresh account-level authority observation and the minted proof. Everything
            // that can suspend comes *after* this point, so the deciding reads below are the last ones.
            val authorization = api.authorizeCreate(p.target, p.write.request)
            guard()
            requireNoUnsettled(p.target, p.plan.id)
            // Phase 2: the deciding preflight (bucket/jobs/notifications of this exact jurisdiction).
            val observed = preflight(p.target, clearRequested = p.write.isWholeBucketClear())
            if (digest(observed.state) != p.plan.beforeHash) {
                throw CloudFailure(
                    CloudFailureCode.CONFLICT,
                    "该桶的作业或通知状态已变化，请刷新后重新预览确认",
                )
            }
            // Phase 3: no suspension from here — the minted proof is re-checked and the composed guard
            // runs immediately before the single typed POST.
            val submission = authorization.send { beforeSend() }
            verifySubmission(p, submission)
        }
    }

    /**
     * Read-only recovery of one job task.
     *
     * Only the task's own persisted identity is used: the locator's account/jurisdiction/bucket plus
     * the trustworthy job ID from its poll path. A missing/foreign/unparsable identity is refused
     * instead of being guessed; while the provider still reports the job as enqueued/running the task
     * stays ACCEPTED (a refresh is not a downgrade), and a terminal failure is never reported as
     * success or as "never happened".
     */
    suspend fun reconcile(taskId: String): OperationTask {
        val existing = withContext(storeDispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        if (existing.state == TaskState.SUCCEEDED || existing.state == TaskState.FAILED) {
            // A terminal row is history: a later observation (however different) never rewrites it.
            return terminalRow(existing)
        }
        return coordinator.reconcile(taskId) { task ->
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
        ScopeGuard.requireOwned(task.scope, live.connection)
        requireSameLive(live)
        if (task.actionKey !in R2TaskTargets.JOB_ACTIONS) return@reconcile Reconciliation.StillUnknown
        val identity = R2TaskTargets.jobTask(task.resourceRef, task.pollPath) ?: throw R2_JOB_TASK_UNLOCATABLE_FAILURE()
        if (identity.account != task.scope) throw R2_JOB_TASK_UNLOCATABLE_FAILURE()
        val desired = task.desiredHash ?: return@reconcile Reconciliation.StillUnknown
        val job =
            try {
                val api = jobSession(identity.target.account)
                requireSameLive(live)
                api.getJob(identity.target, identity.jobId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A read failure is not evidence about the job: keep the accepted task as it is.
                throw R2_JOB_REFRESH_UNAVAILABLE_FAILURE()
            }
        requireSameLive(live)
        if (!job.interpretable || job.id != identity.jobId) return@reconcile Reconciliation.StillUnknown
        // The controller checks the kind itself: a details document of another kind is never proof.
        if (job.jobType != identity.kind) return@reconcile Reconciliation.StillUnknown
        val observed = observedJobState(job, identity.kind) ?: return@reconcile Reconciliation.StillUnknown
        // The kind and the original parameters must match the frozen desired state exactly.
        if (digest(observed) != desired) return@reconcile Reconciliation.StillUnknown
        when (job.status) {
            R2JobStatus.COMPLETED ->
                when (val outcome = completedOutcome(job)) {
                    JobOutcome.COMPLETED -> Reconciliation.Matched(desired)
                    JobOutcome.PARTIAL -> Reconciliation.ActionRequired
                    JobOutcome.UNPROVEN -> Reconciliation.StillUnknown
                }
            R2JobStatus.FAILED, R2JobStatus.CANCELLED -> Reconciliation.ActionRequired
            // Still running: the task keeps its accepted state instead of being downgraded.
            R2JobStatus.ENQUEUED, R2JobStatus.RUNNING -> throw R2_JOB_STILL_RUNNING_FAILURE()
            null -> Reconciliation.StillUnknown
        }
        }
    }

    /**
     * Ownership checks a terminal row still has to pass before it is returned unchanged.
     *
     * A terminal row is history. A FAILED row from a trusted pre-send refusal has no job ID at all
     * (nothing was created), so only the locator's own family/scope has to be trustworthy; when a
     * locator ID is present it must still parse as this account's own job.
     */
    private fun terminalRow(task: OperationTask): OperationTask {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
        ScopeGuard.requireOwned(task.scope, live.connection)
        if (task.actionKey !in R2TaskTargets.JOB_ACTIONS) throw invalidInputFailure()
        val location = R2TaskTargets.parseJob(task.resourceRef) ?: throw R2_JOB_TASK_UNLOCATABLE_FAILURE()
        if (location.account != task.scope) throw R2_JOB_TASK_UNLOCATABLE_FAILURE()
        if (task.pollPath != null) {
            val identity = R2TaskTargets.jobTask(task.resourceRef, task.pollPath) ?: throw R2_JOB_TASK_UNLOCATABLE_FAILURE()
            if (identity.account != task.scope) throw R2_JOB_TASK_UNLOCATABLE_FAILURE()
        }
        return task
    }

    private fun entry(id: String): Prepared = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2JobWrite, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan) throw invalidInputFailure()
        if (p.write != write) throw invalidInputFailure()
        if (p.plan.actionKey != R2TaskTargets.JOB_CREATE_ACTION) throw invalidInputFailure()
        if (p.plan.resourceRef != R2TaskTargets.jobReference(p.target, p.write.kind)) throw invalidInputFailure()
        if (requestFingerprint(write) != p.plan.requestFingerprint) throw invalidInputFailure()
        if (p.plan.beforeHash != digest(p.beforeState)) throw invalidInputFailure()
        if (p.plan.desiredHash != digest(desiredState(p.write))) throw invalidInputFailure()
        validateR2JobTarget(write.target)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(write: R2JobWrite): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != write.target.account || live.requestFingerprint != requestFingerprint(write)) {
            throw invalidInputFailure()
        }
        ScopeGuard.requireOwned(write.target.account, live.connection)
        if (!resolver.isLive(write.target.account, live.credentialRevision)) throw invalidInputFailure()
        return live
    }

    private fun requireSameLive(expected: LiveOperationContext) {
        if (resolver.current() != expected || !resolver.isLive(expected.scope, expected.credentialRevision)) {
            throw invalidInputFailure()
        }
        ScopeGuard.requireOwned(expected.scope, expected.connection)
    }

    private suspend fun requireNoUnsettled(target: R2WriteTarget, excluding: String? = null) {
        val blocked =
            withContext(storeDispatcher) {
                store.list(target.account.connectionId).any { task ->
                    task.id != excluding && R2TaskTargets.blocks(task, target)
                }
            }
        if (blocked) {
            throw CloudFailure(
                CloudFailureCode.UNKNOWN_RESULT,
                "该桶的上一操作尚未核对，请先核对结果，不要再次发送",
                uncertain = true,
            )
        }
    }

    /**
     * The one preflight observation: bucket, bounded complete job scan and notification configuration.
     *
     * Every value in [state] is derived from data this app can already interpret; the digest of it is
     * the baseline the final guard compares.
     */
    private suspend fun preflight(target: R2WriteTarget, clearRequested: Boolean = false): Preflight {
        val bucket = bucketSession(target.account).getBucket(target)
        // The jurisdiction-aware read must answer for exactly this account and bucket; a document of
        // another scope/name is never this target's baseline.
        if (bucket.name != target.bucket || bucket.scope != target.account) throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
        val jobApi = jobSession(target.account)
        val seenIds = mutableSetOf<String>()
        val usedTokens = mutableSetOf<String>()
        var token: String? = null
        var pages = 0
        var jobs = 0
        val digests = mutableListOf<String>()
        while (true) {
            pages++
            if (pages > R2_JOB_MAX_PREFLIGHT_PAGES) throw R2_JOB_PREFLIGHT_NOT_IDLE_FAILURE()
            val page = jobApi.listJobs(target, R2JobListQuery(maxKeys = R2_JOB_MAX_PAGE_SIZE, continuationToken = token))
            if (!page.interpretable) throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
            for (job in page.jobs) {
                val id = job.id ?: throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
                if (!job.interpretable || job.status == null) throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
                if (!seenIds.add(id)) throw R2_JOB_PREFLIGHT_NOT_IDLE_FAILURE()
                if (job.status == R2JobStatus.ENQUEUED || job.status == R2JobStatus.RUNNING) {
                    throw R2_JOB_PREFLIGHT_NOT_IDLE_FAILURE()
                }
                jobs++
                digests += digest(
                    buildJsonObject {
                        put("id", id)
                        put("type", job.jobType?.wireValue ?: "")
                        put("status", job.status.wireValue)
                    },
                )
            }
            val next = page.nextContinuationToken ?: break
            if (!usedTokens.add(next)) throw R2_JOB_PREFLIGHT_NOT_IDLE_FAILURE()
            token = next
        }
        val notifications = notificationSession(target.account).listConfigurations(target)
        if (!notifications.interpretable) throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
        // The collection must name exactly this bucket; a missing/foreign identity is not a baseline.
        if (notifications.bucketName != target.bucket) throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
        val knownRules = notifications.queues.sumOf { it.rules.size }
        if (clearRequested && knownRules > 0) {
            // A whole-bucket clear against an existing notification configuration is a known conflict;
            // this app never removes notification rules to work around it.
            throw CloudFailure(CloudFailureCode.CONFLICT, R2_JOB_CLEAR_NOTIFICATION_CONFLICT)
        }
        val queueIds = notifications.queues.mapNotNull { it.queueId }.sorted()
        if (queueIds.size != notifications.queues.size) throw R2_JOB_PREFLIGHT_UNKNOWN_FAILURE()
        val state =
            buildJsonObject {
                put("family", R2TaskTargets.JOB_FAMILY)
                put("bucket", bucket.name)
                put("jurisdiction", target.jurisdiction)
                put("jobs", jobs)
                put(
                    "jobDigests",
                    kotlinx.serialization.json.buildJsonArray { digests.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
                )
                put(
                    "queues",
                    kotlinx.serialization.json.buildJsonArray { queueIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
                )
            }
        return Preflight(state, jobs, queueIds.size)
    }

    private class Preflight(val state: JsonObject, val jobs: Int, val queues: Int)

    /** The public state the created job must describe: kind plus the exact original parameters. */
    private fun desiredState(write: R2JobWrite): JsonObject =
        when (write) {
            is R2JobWrite.PrefixDelete ->
                jobState(R2JobType.PREFIX_DELETE, prefix = write.prefix)
            is R2JobWrite.StorageClassMigration ->
                jobState(R2JobType.STORAGE_CLASS_MIGRATION, source = write.source, destination = write.destination)
        }

    private fun jobState(
        kind: R2JobType,
        prefix: String? = null,
        source: R2StorageClass? = null,
        destination: R2StorageClass? = null,
    ): JsonObject =
        buildJsonObject {
            put("family", R2TaskTargets.JOB_FAMILY)
            put("kind", kind.wireValue)
            when (kind) {
                R2JobType.PREFIX_DELETE -> {
                    if (prefix == null) throw invalidInputFailure()
                    put("prefix", prefix)
                    put("isBucketClear", prefix.isEmpty())
                }
                R2JobType.STORAGE_CLASS_MIGRATION -> {
                    if (source == null || destination == null) throw invalidInputFailure()
                    put("source", source.wireValue)
                    put("destination", destination.wireValue)
                }
            }
        }

    /**
     * The same state reconstructed from an observed job, for exactly the expected kind.
     *
     * A details document of another kind than [expected] is never re-interpreted as that kind.
     */
    private fun observedJobState(job: R2BucketJob, expected: R2JobType): JsonObject? {
        if (job.jobType != expected) return null
        return when (expected) {
            R2JobType.PREFIX_DELETE -> {
                val details = job.details as? R2JobDetails.PrefixDelete ?: return null
                jobState(R2JobType.PREFIX_DELETE, prefix = details.details.prefix)
            }
            R2JobType.STORAGE_CLASS_MIGRATION -> {
                val details = job.details as? R2JobDetails.StorageClassMigration ?: return null
                val source = details.details.sourceStorageClass ?: return null
                val destination = details.details.destinationStorageClass ?: return null
                jobState(R2JobType.STORAGE_CLASS_MIGRATION, source = source, destination = destination)
            }
        }
    }

    /**
     * One independent read after the accepted creation.
     *
     * The POST's report is never completion: the receipt stays `Accepted` and the job is verified by a
     * separate GET of the same ID, kind and parameters. A missing/foreign/contradicting observation
     * keeps the task UNKNOWN.
     */
    private suspend fun verifySubmission(p: Prepared, submission: com.flarego.core.ports.R2JobSubmission): ExecutionReceipt {
        val locator = submission.pollLocator ?: return submission.receipt
        if (locator.target != p.target) return ExecutionReceipt.Unknown()
        // The provider's own creation response already carried a trustworthy same-kind ID: that ID is
        // the only read-only locator this task will ever have, so a failed or contradicting admission
        // read must never erase it. The POST is still never completion — the task stays ACCEPTED and
        // one independent GET is attempted here; a contradiction surfaces at the read-only recovery.
        try {
            // The observation is recorded but never rewrites the admission: the receipt stays the
            // client's `Accepted`, which is what persists the trustworthy locator.
            jobSession(p.target.account).getJob(p.target, locator.jobId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the accepted task with its locator; nothing is guessed and nothing is resent.
        }
        return submission.receipt
    }

    private enum class JobOutcome { COMPLETED, PARTIAL, UNPROVEN }

    /**
     * Whether a terminal job may be reported as its own completion.
     *
     * A prefix delete reports only that job (never that the bucket is now empty). A migration is only
     * a full success when the observed skipped and failed counters are both known and zero; missing
     * counters are never filled in with a guessed zero, so an unproven migration stays unproven.
     */
    private fun completedOutcome(job: R2BucketJob): JobOutcome =
        when (val details = job.details) {
            is R2JobDetails.PrefixDelete -> JobOutcome.COMPLETED
            is R2JobDetails.StorageClassMigration -> {
                val d = details.details
                val failedObjects = d.failedObjects
                val skippedObjects = d.skippedObjects
                val failedBytes = d.failedBytes
                val skippedBytes = d.skippedBytes
                when {
                    // A missing counter is never a zero: without all four there is no full-success proof.
                    failedObjects == null || skippedObjects == null || failedBytes == null || skippedBytes == null ->
                        JobOutcome.UNPROVEN
                    // Any observed failure or skip — objects *or* bytes — is a partial migration.
                    failedObjects > 0 || skippedObjects > 0 || failedBytes > 0 || skippedBytes > 0 ->
                        JobOutcome.PARTIAL
                    else -> JobOutcome.COMPLETED
                }
            }
            null -> JobOutcome.UNPROVEN
        }

    private fun detailOf(write: R2JobWrite, observed: Preflight): R2JobWritePreview =
        when (write) {
            is R2JobWrite.PrefixDelete ->
                R2JobWritePreview(
                    action = R2JobType.PREFIX_DELETE,
                    target = write.target,
                    prefix = write.prefix,
                    isBucketClear = write.prefix.isEmpty(),
                    observedJobs = observed.jobs,
                    observedNotificationQueues = observed.queues,
                )
            is R2JobWrite.StorageClassMigration ->
                R2JobWritePreview(
                    action = R2JobType.STORAGE_CLASS_MIGRATION,
                    target = write.target,
                    sourceStorageClass = write.source,
                    destinationStorageClass = write.destination,
                    observedJobs = observed.jobs,
                    observedNotificationQueues = observed.queues,
                )
        }

    private fun previewOf(write: R2JobWrite, observed: Preflight): R2ChangePreview {
        val target = write.target
        val impact =
            when (write) {
                is R2JobWrite.PrefixDelete ->
                    if (write.prefix.isEmpty()) {
                        "为该桶创建一个异步“清空整个桶”的 prefix delete 作业：范围是作业启动时桶内已存在的对象" +
                            "（作业启动后上传的对象**不在**该作业范围内，不会被它处理）；删除不可撤销。" +
                            "已复制的规则/域名/通知配置不属于本作业范围；作业完成只代表该作业本身结束，" +
                            "不表示现在桶为空、残留分片已清理或之后不会新增对象。" +
                            "多分片上传必须由操作者在外部先中止并暂停写入（人工声明，不是本应用已查明的云证据）。"
                    } else {
                        "为该桶创建一个异步 prefix delete 作业：只删除作业启动时已存在于 “${write.prefix}” 前缀下的对象" +
                            "（启动后新增的同前缀对象不在该作业范围内），删除不可撤销。作业完成只代表该作业本身结束，" +
                            "不表示桶为空或之后不会新增对象。多分片上传必须由操作者在外部先中止并暂停写入" +
                            "（人工声明，不是本应用已查明的云证据）。"
                    }
                is R2JobWrite.StorageClassMigration ->
                    "为该桶创建一个异步存储类别迁移作业：${write.source.wireValue} → ${write.destination.wireValue}，" +
                        "范围是该桶作业启动时已存在的对象（启动后新增对象不在该作业内）。" +
                        "作业可能产生存储/读取费用与最低保留期影响，本应用不估算价格；" +
                        "作业完成只代表该作业本身结束，迁移失败/跳过的对象会如实显示，不承诺全部迁移成功。"
            }
        val region =
            if (target.jurisdiction == R2TaskTargets.DEFAULT_JURISDICTION) {
                "目标地区：default（默认地区）；同名桶在其他地区是不同资源。" +
                    "预览时已独立读取该桶并核对已知作业与通知配置（${observed.jobs} 个已知作业，" +
                    "${observed.queues} 个通知 Queue）；发送前会再次读取并比对。"
            } else {
                "目标地区：${target.jurisdiction}（非默认地区）；请在相同地区核对结果，App 不会退回 default 桶。" +
                    "预览时已独立读取该桶并核对已知作业与通知配置（${observed.jobs} 个已知作业，" +
                    "${observed.queues} 个通知 Queue）；发送前会再次读取并比对。"
            }
        return R2ChangePreview(
            scope = target.account,
            bucket = target.bucket,
            impact = impact,
            concurrencyWarning = "$region。服务端未提供 CAS；本应用同桶写入串行并拒绝冲突写入，" +
                "但不宣称能锁住其他客户端或阻止他人同时创建作业",
        )
    }

    private fun digest(value: JsonObject): String = fingerprint.sha256(CanonicalJson.encode(value))
}

/** Only the explicit prefix-delete with an empty prefix is the whole-bucket clear mode. */
private fun R2JobWrite.isWholeBucketClear(): Boolean =
    this is R2JobWrite.PrefixDelete && prefix.isEmpty()

private fun R2_JOB_PREFLIGHT_NOT_IDLE_FAILURE(): CloudFailure =
    CloudFailure(CloudFailureCode.CONFLICT, R2_JOB_PREFLIGHT_NOT_IDLE)

private fun R2_JOB_PREFLIGHT_UNKNOWN_FAILURE(): CloudFailure =
    CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_JOB_PREFLIGHT_UNKNOWN)

private fun R2_JOB_TASK_UNLOCATABLE_FAILURE(): CloudFailure =
    CloudFailure(
        CloudFailureCode.INVALID_INPUT,
        "该后台作业任务缺少可核对的可信作业标识，只能保持原状态；应用不会按时间、前缀或列表猜测",
    )

private fun R2_JOB_REFRESH_UNAVAILABLE_FAILURE(): CloudFailure =
    CloudFailure(
        CloudFailureCode.UNAVAILABLE,
        "暂时无法读取该作业的当前状态；任务保持已受理，不会被降级或重发",
    )

private fun R2_JOB_STILL_RUNNING_FAILURE(): CloudFailure =
    CloudFailure(
        CloudFailureCode.CONFLICT,
        "该作业仍在进行中，任务保持已受理；请稍后再次只读刷新",
    )
