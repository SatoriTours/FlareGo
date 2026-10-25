package com.flarego.core.application.d1

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One product issuer and coordination domain, all ten potentially mutating D1 actions. */
@OptIn(ExperimentalAtomicApi::class)
class D1WriteRuntime(
    private val appScope: CoroutineScope, private val store: OperationStore, private val dispatcher: CoroutineDispatcher,
    private val fingerprint: FingerprintPort, private val clock: OperationClock, private val ids: OperationIds,
    private val actual: () -> D1LiveSelection?, private val connections: () -> List<Connection>, private val revision: (String) -> Long,
    private val session: suspend (AccountScope, () -> Unit) -> D1Session,
    private val pipeline: (D1DatabaseRef, D1Session, suspend (D1FileLocator) -> Unit) -> D1FilePipeline,
    private val onResult: (D1LiveSelection, D1ResultDocument) -> Boolean,
    private val onExportReady: (D1LiveSelection, D1FileOutcome) -> Boolean = { _, _ -> false },
) {
    private class Binding(val command: D1Mutation, val live: D1LiveSelection, val nonce: String, val bookmark: String?)
    private class Prepared(val binding: Binding, val plan: ChangePlan, val preview: D1ConfirmationView) {
        val handle = AtomicReference(preview.handle)
        val created = AtomicReference<D1Database?>(null)
        val restored = AtomicReference<D1RestoreReceipt?>(null)
        val locator = AtomicReference<D1FileLocator?>(null)
    }
    private val binding = AtomicReference<Binding?>(null)
    private val prepared = AtomicReference<Prepared?>(null)
    private val context = object : OperationContextResolver {
        override fun current(): LiveOperationContext? {
            val live = actual() ?: return null
            val bound = binding.load()?.takeIf { live.command === it.command && same(live, it.live) }
            return LiveOperationContext(live.connection, AccountScope(live.connection.id, live.connection.accountId),
                live.credentialRevision, bound?.let(::digest) ?: "0".repeat(64))
        }
        override fun liveConnections() = connections()
        override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean {
            val account = scope as? AccountScope ?: return false
            val connection = connections().singleOrNull { it.id == account.connectionId && it.accountId == account.accountId } ?: return false
            val current = revision(connection.id)
            return current >= 0 && (credentialRevision == null || current == credentialRevision)
        }
    }
    private val plans = ChangePlans(context, clock, ids, retentionLimit = 64)
    private val coordinator = OperationCoordinator(plans, store, context, clock, appScope, dispatcher)

    suspend fun prepare(command: D1Mutation): D1ConfirmationView = try { prepareOwned(command) }
        catch (failure: Throwable) { release(command); throw failure }

    private suspend fun prepareOwned(command: D1Mutation): D1ConfirmationView {
        val live = actual() ?: throw stale()
        requireActual(live, command)
        ScopeGuard.requireOwned(command.account, live.connection)
        command.databaseId?.let(D1Validation::databaseId)
        requireNoUnsettled(command)
        validate(command)
        requireActual(live, command)
        val api = session(command.account) { throw stale() }
        requireActual(live, command)
        val before = command.databaseId?.takeUnless { command is D1Mutation.Undelete }?.let {
            api.getDatabase(D1DatabaseRef(command.account, it)).also { row ->
                requireActual(live, command)
                if (row.ref != D1DatabaseRef(command.account, it)) throw stale()
            }
        }
        val bookmark = if (command is D1Mutation.Restore) when (val point = command.point) {
            is D1RestorePoint.Bookmark -> D1Validation.bookmark(point.value)
            is D1RestorePoint.Timestamp -> api.getBookmark(command.database, D1Validation.timestamp(point.value))
        } else null
        requireActual(live, command)
        clearPrepared(keep = command)
        val bound = Binding(command, live, ids.nextId().also { if (it.isBlank()) throw stale() }, bookmark)
        binding.store(bound)
        try {
            val plan = plans.prepare(command.account, command.action, D1TaskTargets.ref(command.account, command.databaseId),
                null, null, digest(bound), live.credentialRevision, clock.nowMs())
            requireActual(live, command)
            if (binding.load() !== bound) throw stale()
            val preview = preview(command, before, bookmark, live.connection)
            val p = Prepared(bound, plan, preview)
            if (!prepared.compareAndSet(null, p)) { plans.discardUnclaimed(plan.id); throw stale() }
            return preview
        } catch (failure: Throwable) { binding.compareAndSet(bound, null); release(command); throw failure }
    }

    fun acceptRisk(handle: D1ConfirmationHandle) = advance(handle, D1ConfirmationStage.RISK, D1ConfirmationStage.TYPED)
    fun acceptTyped(handle: D1ConfirmationHandle, databaseId: String): D1ConfirmationView {
        val p = entry(handle)
        val command = p.binding.command
        if (databaseId != (command.databaseId ?: command.account.accountId)) throw stale()
        return advance(handle, D1ConfirmationStage.TYPED, D1ConfirmationStage.FINAL)
    }
    private fun advance(handle: D1ConfirmationHandle, from: D1ConfirmationStage, to: D1ConfirmationStage): D1ConfirmationView {
        val p = entry(handle)
        if (handle.stage != from) throw stale()
        val next = D1ConfirmationHandle(to)
        if (!p.handle.compareAndSet(handle, next)) throw stale()
        return p.preview.withHandle(next)
    }

    suspend fun submit(handle: D1ConfirmationHandle): D1Submission {
        val p = entry(handle)
        if (handle.stage != D1ConfirmationStage.FINAL) throw stale()
        val command = p.binding.command
        val confirmed = plans.confirm(p.plan.id, command.account, p.binding.live.credentialRevision, digest(p.binding), clock.nowMs())
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val task = coordinator.executeGrouped(confirmed, { requirePrepared(p) }, p.plan.resourceRef!!) { beforeSend ->
                    var mayHaveSent = false
                    try {
                        requirePrepared(p); requireNoUnsettled(command, p.plan.id); requirePrepared(p)
                        val api = session(command.account) { beforeSend(); mayHaveSent = true }
                        requirePrepared(p); requireNoUnsettled(command, p.plan.id); requirePrepared(p)
                        when (command) {
                            is D1Mutation.Create -> { p.created.store(api.createDatabase(command.account, command.input)); ExecutionReceipt.Completed() }
                            is D1Mutation.PutReplication -> { api.updateDatabase(command.database, command.replication); ExecutionReceipt.Completed() }
                            is D1Mutation.PatchReplication -> { api.patchDatabase(command.database, command.replication); ExecutionReceipt.Completed() }
                            is D1Mutation.Delete -> { api.deleteDatabase(command.database); ExecutionReceipt.Completed() }
                            is D1Mutation.Undelete -> { p.created.store(api.undeleteDatabase(command.database, command.newName)); ExecutionReceipt.Completed() }
                            is D1Mutation.Restore -> {
                                p.restored.store(api.restore(command.database, D1RestorePoint.Bookmark(p.binding.bookmark ?: throw stale())))
                                ExecutionReceipt.Completed()
                            }
                            is D1Mutation.Sql -> {
                                val result = if (command.raw) api.raw(command.database, command.input) else api.query(command.database, command.input)
                                val acceptance = result.summary.acceptance
                                deliver(p.binding.live, result)
                                receipt(acceptance)
                            }
                            is D1Mutation.Import, is D1Mutation.Export -> {
                                val database = D1DatabaseRef(command.account, command.databaseId!!)
                                val files = pipeline(database, api) { locator ->
                                    if (locator.database != database || locator.kind != if (command is D1Mutation.Import) D1FileKind.IMPORT else D1FileKind.EXPORT) throw stale()
                                    p.locator.store(locator)
                                    coordinator.recordPollLocator(p.plan.id, p.plan.resourceRef, D1TaskTargets.path(locator)) { requirePrepared(p) }
                                }
                                val result = if (command is D1Mutation.Import) files.startImport(database, command.input)
                                    else files.startExport(database, (command as D1Mutation.Export).options)
                                val observed = receipt(result.acceptance, result.locator)
                                deliverExport(p.binding.live, result)
                                observed
                            }
                        }
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) {
                        if (mayHaveSent) ExecutionReceipt.Unknown(pollPath = p.locator.load()?.let(D1TaskTargets::path)) else ExecutionReceipt.NotDispatched()
                    }
                }
                D1Submission(task, p.created.load(), p.restored.load())
            } finally { release(command) }
        }.await()
    }

    fun revoke() = clearPrepared()
    private fun clearPrepared(keep: D1Mutation? = null) {
        binding.store(null)
        prepared.exchange(null)?.let {
            plans.discardUnclaimed(it.plan.id)
            if (it.binding.command !== keep) release(it.binding.command)
        }
        plans.pruneExpired()
    }
    suspend fun refreshTasks(): List<OperationTask> = withContext(dispatcher) {
        coordinator.recoverPending().filter { it.resourceRef?.kind in setOf(D1TaskTargets.DATABASE, D1TaskTargets.ACCOUNT) && it.actionKey.startsWith("d1-") }
    }
    suspend fun observeTask(id: String): D1Database? {
        val task = task(id); val resource = task.resourceRef ?: throw stale()
        if (resource.kind == D1TaskTargets.ACCOUNT) return null
        val live = actual() ?: throw stale()
        val result = session(task.scope as AccountScope) { throw stale() }.getDatabase(D1DatabaseRef(task.scope, resource.externalId))
        requireLive(live, task.scope)
        return result
    }
    suspend fun closeUncertain(id: String, databaseId: String, understood: Boolean): OperationTask {
        val task = task(id); val resource = task.resourceRef ?: throw stale(); val live = actual() ?: throw stale()
        val expected = if (resource.kind == D1TaskTargets.ACCOUNT) (task.scope as AccountScope).accountId else resource.externalId
        if (!understood || databaseId != expected) throw stale()
        return coordinator.closeUncertain(id, resource) { requireLive(live, task.scope) }
    }
    suspend fun continueFileTask(id: String): OperationTask {
        val task = task(id); val live = actual() ?: throw stale(); val locator = D1TaskTargets.locator(task)
        return coordinator.pollGrouped(id, task.resourceRef!!, { requireLive(live, task.scope) }) { beforeSend ->
            val api = session(locator.database.account, beforeSend)
            requireLive(live, task.scope)
            val files = pipeline(locator.database, api) { observed -> if (D1TaskTargets.path(observed) != task.pollPath) throw stale() }
            val outcome = files.continuePoll(locator)
            val observed = receipt(outcome.acceptance, outcome.locator)
            deliverExport(live, outcome)
            observed
        }
    }

    private fun receipt(acceptance: D1Acceptance, locator: D1FileLocator? = null): ExecutionReceipt = when (acceptance) {
        D1Acceptance.COMPLETE -> ExecutionReceipt.Completed()
        D1Acceptance.PROCESSING -> ExecutionReceipt.Accepted(locator?.let(D1TaskTargets::path) ?: throw stale())
        D1Acceptance.PARTIAL -> ExecutionReceipt.Partial("部分完成；必须核对完整结果，不可重发")
        D1Acceptance.UNKNOWN -> ExecutionReceipt.Unknown()
    }
    private fun deliver(live: D1LiveSelection, result: D1ResultDocument) {
        var claimed = false
        try { if (liveIsCurrent(live)) claimed = onResult(live, result) }
        catch (_: Exception) { /* A local viewer failure cannot change an observed cloud result. */ }
        finally { if (!claimed) result.close() }
    }
    private fun deliverExport(live: D1LiveSelection, result: D1FileOutcome) {
        var claimed = false
        try { if ((result.download != null || result.hasReport) && liveIsCurrent(live)) claimed = onExportReady(live, result) }
        catch (_: Exception) { /* File ownership is independent of the completed cloud operation. */ }
        finally { if (!claimed) result.close() }
    }
    private fun liveIsCurrent(live: D1LiveSelection): Boolean = actual()?.let { same(it, live) } == true &&
        context.isLive(AccountScope(live.connection.id, live.connection.accountId), live.credentialRevision)
    private fun requireLive(live: D1LiveSelection, scope: CloudScope) { if (!liveIsCurrent(live) || !context.isLive(scope, live.credentialRevision)) throw stale() }
    private suspend fun task(id: String): OperationTask {
        val task = withContext(dispatcher) { store.get(id) } ?: throw stale()
        val live = actual() ?: throw stale()
        if (task.scope != AccountScope(live.connection.id, live.connection.accountId) || task.resourceRef?.kind !in setOf(D1TaskTargets.DATABASE, D1TaskTargets.ACCOUNT) || !task.actionKey.startsWith("d1-")) throw stale()
        requireLive(live, task.scope)
        return task
    }
    private suspend fun requireNoUnsettled(command: D1Mutation, except: String? = null) {
        val resource = D1TaskTargets.ref(command.account, command.databaseId)
        val blocked = withContext(dispatcher) { store.list(command.account.connectionId).any {
            it.id != except && it.scope == command.account && it.resourceRef == resource && D1TaskTargets.unsettled(it)
        } }
        if (blocked) throw CloudFailure(CloudFailureCode.CONFLICT, "此 D1 数据库有结果未结算的操作，请核对原任务或明确人工处置，不能重新提交")
    }
    private fun entry(handle: D1ConfirmationHandle): Prepared {
        val p = prepared.load() ?: throw stale()
        if (p.handle.load() !== handle) throw stale()
        requirePrepared(p); return p
    }
    private fun requirePrepared(p: Prepared) {
        if (prepared.load() !== p || binding.load() !== p.binding || plans.isExpired(p.plan)) throw stale()
        requireActual(p.binding.live, p.binding.command)
    }
    private fun requireActual(live: D1LiveSelection, command: D1Mutation) {
        val current = actual() ?: throw stale()
        if (!same(current, live) || current.command !== command || !context.isLive(command.account, live.credentialRevision)) throw stale()
    }
    private fun same(a: D1LiveSelection, b: D1LiveSelection) = a.connection == b.connection && a.credentialRevision == b.credentialRevision && a.epoch == b.epoch
    private fun digest(b: Binding) = fingerprint.sha256("d1\n${b.live.epoch}\n${b.command.account.connectionId}\n${b.command.account.accountId}\n${b.command.databaseId?.lowercase()}\n${b.command.action}\n${b.nonce}".encodeToByteArray())
    private suspend fun validate(command: D1Mutation) {
        when (command) {
            is D1Mutation.Create -> D1Validation.create(command.input)
            is D1Mutation.PutReplication, is D1Mutation.PatchReplication, is D1Mutation.Delete -> Unit
            is D1Mutation.Undelete -> command.newName?.let(D1Validation::name)
            is D1Mutation.Sql -> D1SqlJson.prepareJson(command.input.content)
            is D1Mutation.Import -> { if (command.input.content.descriptor.length !in 1..D1_MAX_IMPORT_BYTES) throw invalidInputFailure(); D1ImportText.validate(command.input.content) }
            is D1Mutation.Export -> D1Validation.exportOptions(command.options)
            is D1Mutation.Restore -> when (val p = command.point) { is D1RestorePoint.Bookmark -> D1Validation.bookmark(p.value); is D1RestorePoint.Timestamp -> D1Validation.timestamp(p.value) }
        }
    }
    private fun preview(command: D1Mutation, before: D1Database?, bookmark: String?, connection: Connection): D1ConfirmationView {
        val content = when (command) { is D1Mutation.Sql -> command.input.content; is D1Mutation.Import -> command.input.content; else -> null }
        val options = (command as? D1Mutation.Export)?.options
        val next = when (command) { is D1Mutation.Create -> command.input.replication; is D1Mutation.PutReplication -> command.replication; is D1Mutation.PatchReplication -> command.replication; else -> null }
        val warning = when (command) {
            is D1Mutation.Sql -> "任意 SQL 都按潜在写入处理，可改变数据或结构。保留完整程序，不拆分分号；批次不承诺原子回滚。"
            is D1Mutation.Import -> "可能替换或改变整个数据库；init 命中已上传文件时即可开始导入。失败可能已有部分写入。"
            is D1Mutation.Export -> "导出期间可能阻塞查询，需持续轮询。空表列表表示全库；云导出完成与文件保存分别核对。"
            is D1Mutation.Delete -> "删除精确 UUID 的数据库并可能影响 Worker 绑定。七日内可尝试恢复，不保证成功。"
            is D1Mutation.Undelete -> "仅尝试恢复七日内删除的精确 UUID；受账号限制和名称冲突影响，恢复后不能 Time Travel 至恢复时点或之前。"
            is D1Mutation.Restore -> "恢复将覆盖当前数据库；窗口取决于套餐（免费七日、付费三十日）。previous_bookmark 仅用于另一次明确确认的恢复。"
            else -> "核对精确账号、数据库及旧值与新值；省略字段表示未提交该字段。"
        } + " 结果未知时不会自动重试。"
        return D1ConfirmationView(D1ConfirmationHandle(D1ConfirmationStage.RISK), command.account, command.databaseId,
            before?.name ?: (command as? D1Mutation.Create)?.input?.name.orEmpty(), command.action, before?.replication, next,
            command is D1Mutation.PatchReplication && command.replication == null, content, options?.tables?.toList(), options?.allTables,
            options?.noData, options?.noSchema, ((command as? D1Mutation.Restore)?.point as? D1RestorePoint.Timestamp)?.value, bookmark,
            (command as? D1Mutation.Undelete)?.newName, warning,
            (command as? D1Mutation.Create)?.input?.jurisdiction,(command as? D1Mutation.Create)?.input?.location,
            connectionLabel = connection.name)
    }
    private fun release(command: D1Mutation) { when (command) { is D1Mutation.Sql -> command.input.content.close(); is D1Mutation.Import -> command.input.close(); else -> Unit } }
    private fun stale() = CloudFailure(CloudFailureCode.INVALID_INPUT, "D1 输入、页面、确认阶段或凭据已变化，请重新准备")
}
