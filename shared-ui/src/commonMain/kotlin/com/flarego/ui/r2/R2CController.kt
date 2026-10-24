package com.flarego.ui.r2

import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2JobWritePreview
import com.flarego.core.application.r2.R2NotificationIntent
import com.flarego.core.application.r2.R2NotificationWritePreview
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.model.*
import com.flarego.core.ports.R2JobSession
import com.flarego.core.ports.R2MetricsPort
import com.flarego.core.ports.R2NotificationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The three C surfaces. */
enum class R2CScreen { JOBS, NOTIFICATIONS, METRICS }
enum class R2CNotificationMode { APPEND, SELECTED, ALL }

/** One job row, display-only (never an executable command). */
data class R2CJobRowUi(
    val id: String,
    val kindLabel: String,
    val statusLabel: String,
    val detail: String,
    val interpretable: Boolean,
    val canRefresh: Boolean,
)

/** One notification queue with its identified rules. */
data class R2CQueueUi(
    val queueKey: String,
    val queueLabel: String,
    val rules: List<R2CRuleUi>,
    val interpretable: Boolean,
)

data class R2CRuleUi(val id: String, val label: String)

/** The account-level metrics projection; every number keeps its exact `.lexical` text. */
data class R2CMetricsUi(
    val rows: List<Pair<String, String>>,
    val note: String,
    val readOnlyReason: String? = null,
)

/** The in-memory drafts of the two creation forms. */
data class R2CJobDraft(
    val migration: Boolean = false,
    val prefix: String = "",
    val wholeBucketClear: Boolean = false,
    val source: R2StorageClass = R2StorageClass.STANDARD,
    val destination: R2StorageClass = R2StorageClass.INFREQUENT_ACCESS,
    /** The operator's manual multipart statement; never defaulted to true. */
    val manualMpuDeclared: Boolean = false,
)

data class R2CNotificationDraft(
    val actions: Set<String> = emptySet(),
    val description: String = "",
    val prefix: String = "",
    val suffix: String = "",
)

data class R2CUiState(
    val open: Boolean = false,
    val screen: R2CScreen = R2CScreen.JOBS,
    val available: Boolean = false,
    val targetLabel: String = "",
    val loading: Boolean = false,
    val preparing: Boolean = false,
    val writing: Boolean = false,
    val jobs: List<R2CJobRowUi> = emptyList(),
    val jobsReadOnly: String? = null,
    val jobDraft: R2CJobDraft = R2CJobDraft(),
    val queues: List<R2CQueueUi> = emptyList(),
    val notificationsReadOnly: String? = null,
    val notificationDraft: R2CNotificationDraft = R2CNotificationDraft(),
    val selectedRuleIds: Set<String> = emptySet(),
    val notificationMode: R2CNotificationMode = R2CNotificationMode.APPEND,
    val selectedQueueKey: String? = null,
    val jurisdiction: String = "default",
    val metrics: R2CMetricsUi? = null,
    val metricsError: String? = null,
    val previewTitle: String? = null,
    val previewDetail: String? = null,
    val confirmation: R2CConfirmationState = R2CConfirmationState(),
    val notice: String? = null,
    val error: String? = null,
)

/**
 * The batch C production root: typed reads (jobs / notifications / account metrics) and the two
 * write families through the one shared confirmation machinery.
 *
 * Safety contract:
 * - every destructive action (prefix delete including the whole-bucket clear, notification selected
 *   removal and whole-queue clear) needs the [R2CDeletionConfirmation] owner's three stages, and the
 *   **root** refuses to confirm without a consumed ticket bound to the current draft and the live
 *   route/credential binding — the Compose buttons are not the guard;
 * - a migration or an append keeps the reviewed impact preview + explicit confirm of the other
 *   families and never becomes a three-stage flow;
 * - the live command is derived from the current in-memory draft on every call, so an edited draft, a
 *   changed target/route/credential or a cancelled dialog can only invalidate, never replay;
 * - an accepted task is never cancelled by a vanished waiter, and a read-only refresh never resends.
 */
class R2CController(
    private val scope: CoroutineScope,
    private val context: () -> R2BrowserContext?,
    /** Root prepare: marks the C preview and prepares the typed command; false when refused. */
    private val prepareCommand: (R2Command) -> Boolean,
    /** The bucket this C surface is showing (chosen by the caller's route, never derived from a name). */
    private val bucket: () -> String? = { null },
    /** Kept for constructor compatibility; local task reconciliation belongs to the root task list. */
    private val runtime: R2WriteRuntime? = null,
    /** Root confirm: enforces the ticket guard and returns the app-owned job of that confirmation. */
    private val confirmCommand: (R2CConfirmationTicket?) -> Job?,
    private val jobs: (suspend (R2BrowserContext) -> R2JobSession)? = null,
    private val notifications: (suspend (R2BrowserContext) -> R2NotificationSession)? = null,
    private val metrics: (suspend (R2BrowserContext) -> R2MetricsPort)? = null,
    /** Withdraws the root-owned plan as well as this editor's display. */
    private val requestInvalidate: () -> Unit = {},
) {
    private val mutable =
        MutableStateFlow(
            R2CUiState(available = jobs != null || notifications != null || metrics != null, targetLabel = ""),
        )
    val state: StateFlow<R2CUiState> = mutable.asStateFlow()

    private var generation = 0L
    private var refreshJob: Job? = null
    private val confirmation = R2CDeletionConfirmation { liveBinding() }

    /** The exact live route/credential identity of the current selection. */
    private fun liveBinding(): String {
        val ctx = context() ?: return "none"
        return "${ctx.routeEpoch}/${ctx.credentialRevision}"
    }

    // Compare the immutable typed value, including frozen notification rules/IDs. Display strings
    // and hashCode are deliberately not identities. The opaque token is private and memory-only.
    private var identityCommand: R2Command? = null
    private var identitySequence = 0L
    private var preparingCommand: R2Command? = null
    private var readyCommand: R2Command? = null
    private var submissionSequence = 0L

    private fun fingerprintOf(command: R2Command?): String? {
        if (command == null) return null
        if (identityCommand != command) {
            identityCommand = command
            identitySequence++
        }
        return "c-editor-$identitySequence"
    }

    internal fun isReadyFor(command: R2Command): Boolean =
        readyCommand == command && liveCommand() == command && mutable.value.previewTitle != null

    internal fun matchesTicket(command: R2Command, ticket: R2CConfirmationTicket): Boolean =
        isReadyFor(command) && ticket.fingerprint == fingerprintOf(command) && ticket.liveBinding == liveBinding()

    internal fun onRootPreparing(command: R2Command) {
        preparingCommand = command
        readyCommand = null
        mutable.value = mutable.value.copy(preparing = true, previewTitle = null, previewDetail = null, error = null)
    }

    internal fun onRootPreview(command: R2Command, detail: String) {
        if (preparingCommand != command || liveCommand() != command) return
        preparingCommand = null
        readyCommand = command
        mutable.value = mutable.value.copy(preparing = false,
            previewTitle = "仅预览，尚未发送；请核对范围后显式确认", previewDetail = detail, error = null)
    }

    internal fun onRootSettled(notice: String) {
        preparingCommand = null
        readyCommand = null
        confirmation.revoke()
        finalTicket = null
        mutable.value = mutable.value.copy(preparing = false, previewTitle = null, previewDetail = null,
            confirmation = confirmation.state, notice = notice, error = null)
    }

    internal fun onRootRejected(message: String) {
        onRootInvalidated()
        mutable.value = mutable.value.copy(error = message)
    }

    internal fun onRootInvalidated() {
        preparingCommand = null
        readyCommand = null
        identityCommand = null
        confirmation.revoke()
        finalTicket = null
        mutable.value = mutable.value.copy(preparing = false, previewTitle = null, previewDetail = null,
            confirmation = confirmation.state)
    }

    private fun invalidateEditor() {
        requestInvalidate()
        onRootInvalidated()
    }

    /**
     * The live C command of the current draft, or null when it is incomplete/invalid.
     *
     * It is rebuilt from the in-memory draft on every call; nothing here reads a plan, a task or a
     * saved state.
     */
    fun liveCommand(): R2Command? {
        if (!mutable.value.open) return null
        val ctx = context() ?: return null
        val target = R2WriteTarget(ctx.account, bucket() ?: return null, mutable.value.jurisdiction)
        return when (mutable.value.screen) {
            R2CScreen.JOBS -> jobCommand(target)
            R2CScreen.NOTIFICATIONS -> notificationCommand(target)
            R2CScreen.METRICS -> null
        }
    }

    private fun jobCommand(target: R2WriteTarget): R2Command? {
        val draft = mutable.value.jobDraft
        val request =
            if (!draft.migration) {
                if (draft.wholeBucketClear) {
                    if (!draft.manualMpuDeclared) return null
                    R2JobWrite.PrefixDelete(target, "", manualMpuHandled = true)
                } else {
                    if (!draft.prefix.endsWith("/")) return null
                    if (!draft.manualMpuDeclared) return null
                    R2JobWrite.PrefixDelete(target, draft.prefix, manualMpuHandled = true)
                }
            } else {
                if (draft.source == draft.destination) return null
                R2JobWrite.StorageClassMigration(target, draft.source, draft.destination)
            }
        return runCatching { R2Command.Job(request) }.getOrNull()
    }

    private fun notificationCommand(target: R2WriteTarget): R2Command? {
        val queue = mutable.value.queues.singleOrNull() ?: mutable.value.queues.firstOrNull { it.queueKey == selectedQueue } ?: return null
        val queueId = queue.queueKey
        val selected = mutable.value.selectedRuleIds
        val draft = mutable.value.notificationDraft
        return runCatching {
            if (mutable.value.notificationMode == R2CNotificationMode.SELECTED) {
                if (selected.isEmpty()) return null
                R2Command.Notification(R2NotificationWrite.Delete(target, queueId, R2NotificationDeletion.Selected(selected.sorted())))
            } else if (mutable.value.notificationMode == R2CNotificationMode.ALL) {
                R2Command.Notification(R2NotificationWrite.Delete(target, queueId, R2NotificationDeletion.All))
            } else {
                if (draft.actions.isEmpty()) return null
                val rule = R2NotificationRuleSpec(
                    actions = draft.actions.toList().sorted(),
                    description = draft.description.takeIf { it.isNotEmpty() },
                    prefix = draft.prefix.takeIf { it.isNotEmpty() },
                    suffix = draft.suffix.takeIf { it.isNotEmpty() },
                )
                R2Command.Notification(R2NotificationWrite.Append(target, queueId, listOf(rule)))
            }
        }.getOrNull()
    }

    private var selectedQueue: String? = null

    // ------------------------------------------------------------------ reads

    fun close() {
        invalidateEditor()
        submissionSequence++
        generation++
        refreshJob?.cancel()
        confirmation.revoke()
        mutable.value = R2CUiState(available = mutable.value.available)
        selectedQueue = null
        mutable.value = mutable.value.copy(available = jobs != null || notifications != null || metrics != null)
    }

    /** Opens the C surface on the given screen (a route choice; the caller already selected the bucket). */
    fun open(screen: R2CScreen) {
        mutable.value = mutable.value.copy(open = true)
        onScreenChanged(screen)
    }

    fun hide() {
        revokeEverything()
        mutable.value = mutable.value.copy(open = false, jobDraft = R2CJobDraft(), notificationDraft = R2CNotificationDraft(),
            previewTitle = null, previewDetail = null, confirmation = R2CConfirmationState())
    }

    fun onScreenChanged(screen: R2CScreen) {
        if (mutable.value.writing) return
        revokeEverything()
        mutable.value = mutable.value.copy(screen = screen, previewTitle = null, previewDetail = null, notice = null, error = null)
        when (screen) {
            R2CScreen.JOBS -> refreshJobs()
            R2CScreen.NOTIFICATIONS -> refreshNotifications()
            R2CScreen.METRICS -> refreshMetrics()
        }
    }

    fun onTargetChanged(bucket: String) {
        val ctx = context() ?: return
        revokeEverything()
        mutable.value =
            mutable.value.copy(
                targetLabel = "${ctx.account.accountId}/${bucket}",
                jobs = emptyList(),
                queues = emptyList(),
                metrics = null,
                notice = null,
                error = null,
            )
    }

    private fun revokeEverything() {
        invalidateEditor()
        submissionSequence++
        generation++
        refreshJob?.cancel()
        confirmation.revoke()
        selectedQueue = null
        mutable.value = mutable.value.copy(selectedRuleIds = emptySet(), selectedQueueKey = null,
            notificationMode = R2CNotificationMode.APPEND, loading = false, writing = false)
    }

    fun onJurisdictionChanged(jurisdiction: String) {
        if (jurisdiction !in R2_JURISDICTIONS || mutable.value.writing) return
        if (jurisdiction == mutable.value.jurisdiction) return
        revokeEverything()
        mutable.value = mutable.value.copy(jurisdiction = jurisdiction, jobs = emptyList(), queues = emptyList())
        when (mutable.value.screen) {
            R2CScreen.JOBS -> refreshJobs()
            R2CScreen.NOTIFICATIONS -> refreshNotifications()
            R2CScreen.METRICS -> Unit // account metrics have no jurisdiction
        }
    }

    private fun target(ctx: R2BrowserContext) = R2WriteTarget(ctx.account, targetBucket(), mutable.value.jurisdiction)

    fun refreshJobs() {
        if (mutable.value.writing || mutable.value.preparing) return
        val ctx = context() ?: return
        val api = jobs ?: return
        val stamp = ++generation
        refreshJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        refreshJob =
            scope.launch {
                try {
                    val page = api(ctx).listJobs(target(ctx), R2JobListQuery(maxKeys = R2_JOB_MAX_PAGE_SIZE))

                    if (stamp != generation) return@launch
                    mutable.value =
                        mutable.value.copy(
                            loading = false,
                            jobs = page.jobs.map(::rowOf),
                            jobsReadOnly = if (page.interpretable) null else page.readOnlyReason ?: "作业列表包含应用尚未支持的内容，仅可查看",
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (stamp == generation) mutable.value = mutable.value.copy(loading = false, error = safe(e))
                }
            }
    }

    /** Provider job IDs are read locators, never local operation-task IDs or execution authority. */
    fun refreshJobTask(taskId: String) {
        if (mutable.value.writing || mutable.value.preparing) return
        val ctx = context() ?: return
        val api = jobs ?: return
        val stamp = generation
        scope.launch {
            try {
                val observed = api(ctx).getJob(target(ctx), taskId)
                if (stamp != generation || context() != ctx || !mutable.value.open) return@launch
                if (observed.id != taskId) throw CloudFailure(CloudFailureCode.INVALID_INPUT, "供应商作业身份不符，仅可查看")
                mutable.value = mutable.value.copy(
                    jobs = mutable.value.jobs.map { if (it.id == taskId) rowOf(observed) else it },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (stamp == generation) mutable.value = mutable.value.copy(error = safe(e))
            }
        }
    }

    fun refreshNotifications() {
        if (mutable.value.writing || mutable.value.preparing) return
        val ctx = context() ?: return
        val api = notifications ?: return
        val stamp = ++generation
        refreshJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        refreshJob =
            scope.launch {
                try {
                    val collection = api(ctx).listConfigurations(target(ctx))
                    if (stamp != generation) return@launch
                    val queues =
                        collection.queues.map { queue ->
                            R2CQueueUi(
                                queueKey = queue.queueId.orEmpty(),
                                queueLabel = queue.queueName ?: queue.queueId.orEmpty(),
                                rules = queue.rules.map { R2CRuleUi(it.ruleId.orEmpty(), ruleLabel(it)) },
                                interpretable = queue.interpretable,
                            )
                        }
                    selectedQueue = selectedQueue?.takeIf { key -> queues.any { it.queueKey == key } } ?: queues.singleOrNull()?.queueKey
                    mutable.value =
                        mutable.value.copy(
                            loading = false,
                            queues = queues,
                            selectedQueueKey = selectedQueue,
                            notificationsReadOnly =
                                if (collection.interpretable) null
                                else collection.readOnlyReason ?: "通知配置包含应用尚未支持的内容，仅可查看",
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (stamp == generation) mutable.value = mutable.value.copy(loading = false, error = safe(e))
                }
            }
    }

    fun refreshMetrics() {
        val ctx = context() ?: return
        val api = metrics ?: return
        val stamp = ++generation
        refreshJob?.cancel()
        mutable.value = mutable.value.copy(loading = true, metricsError = null)
        refreshJob =
            scope.launch {
                try {
                    val value = api(ctx).getMetrics(ctx.account)
                    if (stamp != generation) return@launch
                    mutable.value = mutable.value.copy(loading = false, metrics = metricsUi(value), metricsError = null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    if (stamp == generation) mutable.value = mutable.value.copy(loading = false, metricsError = safe(e))
                }
            }
    }

    // ------------------------------------------------------------------ drafts

    fun onJobDraftChanged(draft: R2CJobDraft) {
        if (mutable.value.writing) return
        invalidateEditor()
        mutable.value = mutable.value.copy(jobDraft = draft, error = null)
    }

    fun onNotificationDraftChanged(draft: R2CNotificationDraft) {
        if (mutable.value.writing) return
        invalidateEditor()
        mutable.value = mutable.value.copy(notificationDraft = draft.copy(actions = draft.actions.toSet()),
            notificationMode = R2CNotificationMode.APPEND, selectedRuleIds = emptySet(), error = null)
    }

    fun onSelectionChanged(ids: Set<String>) {
        if (mutable.value.writing) return
        invalidateEditor()
        mutable.value = mutable.value.copy(selectedRuleIds = ids.toSet(), notificationMode = R2CNotificationMode.SELECTED)
    }

    fun onClearAllRequested(requested: Boolean) {
        if (mutable.value.writing) return
        invalidateEditor()
        mutable.value = mutable.value.copy(notificationMode = if (requested) R2CNotificationMode.ALL else R2CNotificationMode.APPEND,
            selectedRuleIds = emptySet(), previewTitle = null, previewDetail = null)
    }

    fun onQueueSelected(queueKey: String) {
        if (mutable.value.writing) return
        if (mutable.value.queues.none { it.queueKey == queueKey }) return
        revokeEverything()
        selectedQueue = queueKey
        mutable.value = mutable.value.copy(selectedQueueKey = queueKey)
    }

    // ------------------------------------------------------------------ preview + confirm

    /**
     * Prepare the current draft through the root (never sends).
     *
     * The prepared preview itself lives in the root's own state (plan id + impact), so this surface
     * keeps no plan and no executable command.
     */
    fun prepare(): Boolean {
        val command = liveCommand()
        if (command == null) {
            mutable.value = mutable.value.copy(error = draftError())
            return false
        }
        if (mutable.value.writing || mutable.value.preparing) return false
        // Only the root's completed preparation may publish Ready, never this Boolean acknowledgement.
        val requested = prepareCommand(command)
        if (!requested) onRootRejected("当前环境不能准备本次预览；未发送任何请求")
        return requested
    }

    /**
     * Advance the three stages for one destructive action. The mode/selection/target must already be
     * the live draft; the owner binds it and every later change revokes it.
     */
    fun beginDeletion(kind: R2CDeleteKind) {
        val command = liveCommand()
        val bucket = targetBucket()
        if (command == null || !isReadyFor(command) || mutable.value.writing) {
            mutable.value = mutable.value.copy(error = "请先完成当前范围的预览")
            return
        }
        val actualKind = when (command) {
            is R2Command.Job -> (command.write as? R2JobWrite.PrefixDelete)?.let {
                if (it.prefix.isEmpty()) R2CDeleteKind.BUCKET_CLEAR else R2CDeleteKind.PREFIX_DELETE
            }
            is R2Command.Notification -> (command.write as? R2NotificationWrite.Delete)?.let {
                if (it.deletion is R2NotificationDeletion.All) R2CDeleteKind.NOTIFICATION_ALL else R2CDeleteKind.NOTIFICATION_SELECTED
            }
            else -> null
        }
        if (kind != actualKind) return
        confirmation.begin(
            R2CConfirmationDraft(
                kind = kind,
                bucket = bucket,
                fingerprint = fingerprintOf(command) ?: return,
                scopeText = scopeTextOf(kind, bucket),
            ),
        )
        mutable.value = mutable.value.copy(confirmation = confirmation.state, error = null)
    }

    fun acknowledgeRisk(expectedHandle: Long = confirmation.currentHandle) {
        confirmation.acknowledgeRisk(expectedHandle, fingerprintOf(liveCommand()))
        mutable.value = mutable.value.copy(confirmation = confirmation.state)
    }

    fun typeBucketName(text: String, expectedHandle: Long = confirmation.currentHandle) {
        confirmation.typeBucketName(expectedHandle, text)
        mutable.value = mutable.value.copy(confirmation = confirmation.state)
    }

    fun openFinal(expectedHandle: Long = confirmation.currentHandle): Boolean {
        if (expectedHandle != confirmation.currentHandle) return false
        val ticket = confirmation.openFinal(expectedHandle, fingerprintOf(liveCommand()), liveBinding())
        mutable.value = mutable.value.copy(confirmation = confirmation.state)
        finalTicket = ticket
        return ticket != null
    }

    private var finalTicket: R2CConfirmationTicket? = null

    /**
     * The one submission of the final dialog. The root re-checks the ticket (handle + payload + live
     * route/credential binding) and consumes it exactly once, so a repeated click or an old dialog
     * cannot reach a second write.
     */
    fun confirmFinal(expectedHandle: Long = confirmation.currentHandle): Job? {
        if (mutable.value.writing || expectedHandle != confirmation.currentHandle) return null
        val command = liveCommand() ?: return null
        if (!isReadyFor(command)) return null
        val ticket = finalTicket
        if (ticket == null) {
            mutable.value = mutable.value.copy(error = "最终确认已失效，请重新核对范围并重新确认")
            return null
        }
        if (ticket.handle != expectedHandle || ticket.fingerprint != fingerprintOf(command) || ticket.liveBinding != liveBinding()) {
            confirmation.revoke(R2C_CONFIRMATION_DRAFT_CHANGED)
            finalTicket = null
            mutable.value = mutable.value.copy(confirmation = confirmation.state)
            return null
        }
        if (!ticket.consumeOnce()) {
            mutable.value = mutable.value.copy(error = "本次确认已被使用，不能重复提交；请核对任务状态")
            return null
        }
        finalTicket = null
        return submit(ticket)
    }

    /** The one confirm of a non-destructive C action (migration / append): preview + explicit confirm. */
    fun confirmReviewed(): Job? {
        val command = liveCommand() ?: return null
        if (isDestructive(command) || !isReadyFor(command) || mutable.value.writing) return null
        return submit(null)
    }

    private fun submit(ticket: R2CConfirmationTicket?): Job? {
        val stamp = ++submissionSequence
        mutable.value = mutable.value.copy(writing = true)
        val job = confirmCommand(ticket)
        if (job == null) {
            onRootRejected("根路由拒绝了本次确认；请重新预览并核对任务状态")
            mutable.value = mutable.value.copy(writing = false)
            return null
        }
        // Observe the original app-owned job; cancelling this observer never cancels the write.
        if (job.isCompleted) mutable.value = mutable.value.copy(writing = false)
        else scope.launch {
            job.join()
            if (stamp == submissionSequence) mutable.value = mutable.value.copy(writing = false)
        }
        return job
    }

    fun cancelConfirmation(expectedHandle: Long = confirmation.currentHandle) {
        if (mutable.value.writing || expectedHandle != confirmation.currentHandle) return
        invalidateEditor()
    }

    // ------------------------------------------------------------------ helpers

    private fun isDestructive(command: R2Command): Boolean =
        when (command) {
            is R2Command.Job -> command.write is R2JobWrite.PrefixDelete
            is R2Command.Notification -> command.write is R2NotificationWrite.Delete
            else -> false
        }

    private fun targetBucket(): String = bucket().orEmpty()

    private fun scopeTextOf(kind: R2CDeleteKind, bucket: String): String {
        val ctx = context()
        val target = "桶=$bucket；地区=${mutable.value.jurisdiction}"
        val identity = "账号=${ctx?.account?.accountId}\n连接=${ctx?.account?.connectionId}"
        // Put the affected resource/IDs before long account/connection identities, while keeping
        // every literal available in the independently scrollable dialog at large font sizes.
        val affected = when (kind) {
            R2CDeleteKind.BUCKET_CLEAR -> "整桶对象清理（作业启动时已存在的对象）"
            R2CDeleteKind.PREFIX_DELETE -> "prefix=${mutable.value.jobDraft.prefix}"
            R2CDeleteKind.NOTIFICATION_SELECTED -> "Queue=${selectedQueue.orEmpty()}\n规则 IDs=${mutable.value.selectedRuleIds.sorted()}"
            R2CDeleteKind.NOTIFICATION_ALL -> "Queue=${selectedQueue.orEmpty()}\n全部规则"
        }
        return "$target\n$affected\n$identity"
    }

    private fun draftError(): String =
        when (mutable.value.screen) {
            R2CScreen.JOBS ->
                when {
                    mutable.value.jobDraft.migration && mutable.value.jobDraft.source == mutable.value.jobDraft.destination ->
                        "源与目标存储类别必须不同"
                    mutable.value.jobDraft.migration -> "目标桶不可用"
                    !mutable.value.jobDraft.prefix.endsWith("/") && !mutable.value.jobDraft.wholeBucketClear ->
                        "非空前缀必须以 / 结尾（按原字面值，不去空格）"
                    !mutable.value.jobDraft.manualMpuDeclared -> "请先确认已在外部处理多分片上传并暂停写入"
                    else -> "当前作业草稿不完整"
                }
            R2CScreen.NOTIFICATIONS -> "当前通知草稿不完整（至少一条规则或一个明确的删除意图）"
            R2CScreen.METRICS -> "本页只读"
        }

    private fun rowOf(job: R2BucketJob): R2CJobRowUi =
        R2CJobRowUi(
            id = job.id.orEmpty(),
            kindLabel = job.jobType?.name ?: "未知作业",
            statusLabel = job.status?.name ?: "未知状态",
            detail = jobDetailText(job),
            interpretable = job.interpretable,
            canRefresh = job.id != null,
        )

    private fun jobDetailText(job: R2BucketJob): String =
        when (val details = job.details) {
            is R2JobDetails.PrefixDelete ->
                "prefix=${details.details.prefix.ifEmpty { "(整桶)" }}；已删除=${details.details.deletedObjects?.toString() ?: "未知"}"
            is R2JobDetails.StorageClassMigration -> {
                val d = details.details
                "迁移 ${d.sourceStorageClass?.wireValue ?: "?"} → ${d.destinationStorageClass?.wireValue ?: "?"}；" +
                    "已迁移=${d.migratedObjects?.toString() ?: "未知"}；跳过=${d.skippedObjects?.toString() ?: "未知"}；失败=${d.failedObjects?.toString() ?: "未知"}"
            }
            null -> "详情不可用（只读）"
        }

    private fun ruleLabel(rule: R2NotificationRule): String =
        "${rule.actions.joinToString("+")}${rule.prefix?.let { " prefix=$it" } ?: ""}${rule.suffix?.let { " suffix=$it" } ?: ""}" +
            (rule.description?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")

    private fun metricsUi(value: R2AccountMetrics): R2CMetricsUi {
        val rows = mutableListOf<Pair<String, String>>()
        fun add(prefix: String, size: R2SizeMetrics?) {
            if (size == null) {
                rows += "$prefix 对象数" to "未知"
                rows += "$prefix payload" to "未知"
                rows += "$prefix metadata" to "未知"
                return
            }
            rows += "$prefix 对象数" to (size.objects?.lexical ?: "未知")
            rows += "$prefix payload" to (size.payloadSize?.lexical ?: "未知")
            rows += "$prefix metadata" to (size.metadataSize?.lexical ?: "未知")
        }
        add("标准存储", value.standard?.uploaded)
        add("标准存储（已发布）", value.standard?.published)
        add("低频存储", value.infrequentAccess?.uploaded)
        add("低频存储（已发布）", value.infrequentAccess?.published)
        return R2CMetricsUi(
            rows = rows,
            note = "账号级统计，仅供查看；不含单位为应用推断，不是当前桶数据，也不证明任何写入成功。统计可能延迟",
            readOnlyReason = if (value.interpretable) null else value.readOnlyReason ?: "指标包含应用尚未支持的内容，仅可查看",
        )
    }

    private fun taskNotice(task: OperationTask): String =
        when (task.state) {
            TaskState.SUCCEEDED -> "只读核对为成功（仅该作业）"
            TaskState.ACCEPTED -> "已受理，仍在进行；请稍后再刷新"
            TaskState.UNKNOWN -> "结果尚未完全确定，只能只读核对，不会自动重试"
            TaskState.ACTION_REQUIRED -> "需要人工核对（可能已有副作用），不会重试或强制解锁"
            TaskState.PARTIAL -> "部分成功；失败/跳过计数请以供应商结果为准"
            TaskState.FAILED -> "已被拒绝或未发送（可信本地证据）"
            else -> "状态：${task.state.name}"
        }

    private fun safe(error: Throwable): String =
        (error as? CloudFailure)?.message?.take(180) ?: "操作失败，请稍后重试"
}

/** Human-readable, non-secret summary of one job preview (Task 3 display input). */
internal fun R2JobWritePreview.describe(): String =
    buildString {
        append("动作：").append(if (action == R2JobType.PREFIX_DELETE) "prefix delete" else "存储类别迁移")
        append("；桶：").append(target.bucket).append("（").append(target.jurisdiction).append("）")
        if (action == R2JobType.PREFIX_DELETE) {
            append("；范围：").append(if (isBucketClear) "整桶（作业启动时已存在的对象）" else "prefix=$prefix")
        } else {
            append("；").append(sourceStorageClass?.wireValue).append(" → ").append(destinationStorageClass?.wireValue)
        }
        append("；预览时已知作业=").append(observedJobs).append("，通知 Queue=").append(observedNotificationQueues)
    }

/** Human-readable, non-secret summary of one notification preview. */
internal fun R2NotificationWritePreview.describe(): String =
    buildString {
        append("动作：").append(
            when (intent) {
                R2NotificationIntent.APPEND -> "追加规则（只新增，不覆盖）"
                R2NotificationIntent.SELECTED -> "删除选中规则"
                R2NotificationIntent.ALL -> "清除该 Queue 全部规则"
            },
        )
        append("；Queue：").append(queueName ?: queueId).append("（").append(queueId).append("）；既有规则=").append(existingRules)
        if (newRuleSummaries.isNotEmpty()) append("；新规则=").append(newRuleSummaries.joinToString("；"))
        if (selectedRuleIds.isNotEmpty()) append("；选中=").append(selectedRuleIds.sorted().joinToString(","))
    }
