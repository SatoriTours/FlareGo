package com.flarego.core.application.kv

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One issuer for all seven KV writes, with namespace-wide coordination and memory-only payloads. */
@OptIn(ExperimentalAtomicApi::class)
class KvWriteRuntime(
    private val appScope: CoroutineScope, private val store: OperationStore, private val dispatcher: CoroutineDispatcher,
    private val fingerprint: FingerprintPort, private val clock: OperationClock, private val ids: OperationIds,
    private val actual: () -> KvLiveSelection?, private val connections: () -> List<Connection>,
    private val revision: (String) -> Long,
    private val session: suspend (AccountScope, () -> Unit) -> KvSession,
) {
    private class Binding(val command: KvMutation, val live: KvLiveSelection, val nonce: String)
    private class Prepared(val binding: Binding, val plan: ChangePlan, val preview: KvConfirmationView) {
        val handle = AtomicReference(preview.handle)
        val receipt = AtomicReference<KvWriteReceipt?>(null)
        val created = AtomicReference<KvNamespace?>(null)
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
            val owner = connections().singleOrNull { it.id == account.connectionId && it.accountId == account.accountId } ?: return false
            val current = revision(owner.id)
            return current >= 0 && (credentialRevision == null || credentialRevision == current)
        }
    }
    private val plans = ChangePlans(context, clock, ids, retentionLimit = 64)
    private val coordinator = OperationCoordinator(plans, store, context, clock, appScope, dispatcher)

    suspend fun prepare(command: KvMutation): KvConfirmationView = try { prepareOwned(command) }
        catch (failure: Throwable) { release(command); throw failure }
    private suspend fun prepareOwned(command: KvMutation): KvConfirmationView {
        val live = actual() ?: throw stale()
        if (live.command !== command) throw stale()
        ScopeGuard.requireOwned(command.account, live.connection)
        if (!context.isLive(command.account, live.credentialRevision)) throw stale()
        command.namespaceId?.let(KvValidation::namespace)
        requireNoUnsettled(command)
        requireActual(live, command)
        validate(command)
        requireActual(live, command)
        val title = command.namespaceId?.let { id ->
            val observed = readOnly(command.account).getNamespace(KvNamespaceRef(command.account, id))
            requireActual(live, command)
            if (observed.ref != KvNamespaceRef(command.account, id)) throw stale()
            observed.title
        } ?: (command as KvMutation.Create).title
        requireActual(live, command)
        revoke()
        val bound = Binding(command, live, ids.nextId().also { if (it.isBlank()) throw stale() })
        binding.store(bound)
        try {
            val plan = plans.prepare(command.account, command.action, KvTaskTargets.ref(command.account, command.namespaceId),
                null, null, digest(bound), live.credentialRevision, clock.nowMs())
            requireActual(live, command)
            if (binding.load() !== bound) throw stale()
            val view = preview(command, title)
            val p = Prepared(bound, plan, view)
            if (!prepared.compareAndSet(null, p)) { plans.discardUnclaimed(plan.id); throw stale() }
            return view
        } catch (failure: Throwable) {
            binding.compareAndSet(bound, null)
            release(command)
            throw failure
        }
    }
    fun acceptRisk(handle: KvConfirmationHandle): KvConfirmationView = advance(handle, KvConfirmationStage.RISK, KvConfirmationStage.TYPED)
    fun acceptTyped(handle: KvConfirmationHandle, namespaceId: String): KvConfirmationView {
        val p = entry(handle)
        if (p.binding.command.namespaceId != namespaceId) throw stale()
        return advance(handle, KvConfirmationStage.TYPED, KvConfirmationStage.FINAL)
    }
    private fun advance(handle: KvConfirmationHandle, from: KvConfirmationStage, to: KvConfirmationStage): KvConfirmationView {
        val p = entry(handle)
        if (handle.stage != from) throw stale()
        val next = KvConfirmationHandle(to)
        if (!p.handle.compareAndSet(handle, next)) throw stale()
        return p.preview.copy(handle = next)
    }
    suspend fun submit(handle: KvConfirmationHandle): KvSubmission {
        val p = entry(handle)
        if (handle.stage != KvConfirmationStage.FINAL) throw stale()
        val command = p.binding.command
        val confirmed = plans.confirm(p.plan.id, command.account, p.binding.live.credentialRevision, digest(p.binding), clock.nowMs())
        // App-owned execution and metadata settlement survive cancellation of a screen waiter.
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val task = coordinator.executeGrouped(confirmed, { requirePrepared(p) }, p.plan.resourceRef!!) { beforeSend ->
                    var mayHaveSent = false
                    try {
                        requirePrepared(p)
                        requireNoUnsettled(command, p.plan.id)
                        requirePrepared(p)
                        val api = session(command.account) {
                            beforeSend()
                            mayHaveSent = true
                        }
                        requirePrepared(p)
                        requireNoUnsettled(command, p.plan.id)
                        requirePrepared(p)
                        val receipt = when (command) {
                            is KvMutation.Create -> {
                                p.created.store(api.createNamespace(command.account, command.title))
                                KvWriteReceipt(KvAcceptance.ACCEPTED)
                            }
                            is KvMutation.Rename -> api.renameNamespace(command.namespace, command.title)
                            is KvMutation.DeleteNamespace -> api.deleteNamespace(command.namespace)
                            is KvMutation.Put -> api.putValue(command.target, command.input)
                            is KvMutation.DeleteKey -> api.deleteValue(command.target)
                            is KvMutation.BulkWrite -> api.bulkWrite(command.namespace, command.input)
                            is KvMutation.BulkDelete -> api.bulkDelete(command.namespace, command.keys)
                        }
                        p.receipt.store(receipt)
                        when (receipt.acceptance) {
                            KvAcceptance.ACCEPTED -> ExecutionReceipt.Accepted()
                            KvAcceptance.PARTIAL -> ExecutionReceipt.Partial("部分接纳；完整失败键需重新准备确认")
                            KvAcceptance.UNKNOWN -> ExecutionReceipt.Unknown()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        if (mayHaveSent) ExecutionReceipt.Unknown() else ExecutionReceipt.NotDispatched()
                    }
                }
                KvSubmission(task, p.receipt.load(), p.created.load())
            } finally { release(command) }
        }.await()
    }
    fun revoke() {
        binding.store(null)
        prepared.exchange(null)?.let { plans.discardUnclaimed(it.plan.id); release(it.binding.command) }
        plans.pruneExpired()
    }
    suspend fun refreshTasks(): List<OperationTask> = withContext(dispatcher) {
        connections().flatMap { store.list(it.id) }.filter { it.resourceRef?.kind in setOf(KvTaskTargets.NAMESPACE, KvTaskTargets.ACCOUNT) && it.actionKey.startsWith("workers-kv-namespace-") }
    }
    /** Observation only: no value is persisted or replayed, and propagation remains unknown. */
    suspend fun observeTask(id: String): KvNamespace? {
        val task = task(id)
        val ref = task.resourceRef ?: throw stale()
        if (ref.kind == KvTaskTargets.ACCOUNT) return null // Missing create identity cannot be recovered by title.
        val live = actual() ?: throw stale()
        val observed = readOnly(task.scope as AccountScope).getNamespace(KvNamespaceRef(task.scope, ref.externalId))
        if (actual()?.let { same(it, live) } != true) throw stale()
        return observed
    }
    /** Explicit user closure only. ACTION_REQUIRED records manual handling, never cloud success. */
    suspend fun closeUncertain(id: String, namespaceId: String, understood: Boolean): OperationTask {
        val task = task(id)
        val ref = task.resourceRef ?: throw stale()
        val live = actual() ?: throw stale()
        val expected = if (ref.kind == KvTaskTargets.ACCOUNT) (task.scope as AccountScope).accountId else ref.externalId
        if (!understood || namespaceId != expected) throw stale()
        return coordinator.closeUncertain(id, ref) {
            if (actual()?.let { same(it, live) } != true || !context.isLive(task.scope, live.credentialRevision)) throw stale()
        }
    }
    private suspend fun task(id: String): OperationTask {
        val task = withContext(dispatcher) { store.get(id) } ?: throw stale()
        val live = actual() ?: throw stale()
        val account = AccountScope(live.connection.id, live.connection.accountId)
        if (task.scope != account || !context.isLive(account, live.credentialRevision) ||
            task.resourceRef?.kind !in setOf(KvTaskTargets.NAMESPACE, KvTaskTargets.ACCOUNT) ||
            !task.actionKey.startsWith("workers-kv-namespace-")) throw stale()
        return task
    }
    private suspend fun readOnly(account: AccountScope) = session(account) { throw stale() }
    private suspend fun requireNoUnsettled(command: KvMutation, except: String? = null) {
        val ref = KvTaskTargets.ref(command.account, command.namespaceId)
        val conflict = withContext(dispatcher) { store.list(command.account.connectionId).any {
            it.id != except && it.scope == command.account && it.resourceRef == ref && KvTaskTargets.unsettled(it.state)
        } }
        if (conflict) throw CloudFailure(CloudFailureCode.CONFLICT, "该命名空间有结果未结算的写入；请只读核对或明确人工处置，不能重发")
    }
    private fun entry(handle: KvConfirmationHandle): Prepared {
        val p = prepared.load() ?: throw stale()
        if (p.handle.load() !== handle) throw stale()
        requirePrepared(p)
        return p
    }
    private fun requirePrepared(p: Prepared) {
        if (prepared.load() !== p || binding.load() !== p.binding || plans.isExpired(p.plan)) throw stale()
        requireActual(p.binding.live, p.binding.command)
    }
    private fun requireActual(live: KvLiveSelection, command: KvMutation) {
        val current = actual() ?: throw stale()
        if (!same(current, live) || current.command !== command || !context.isLive(command.account, live.credentialRevision)) throw stale()
    }
    private fun same(a: KvLiveSelection, b: KvLiveSelection) = a.connection == b.connection && a.credentialRevision == b.credentialRevision && a.epoch == b.epoch
    private fun digest(b: Binding): String = fingerprint.sha256(
        "kv\n${b.live.epoch}\n${b.command.account.connectionId}\n${b.command.account.accountId}\n${b.command.namespaceId}\n${b.command.action}\n${b.nonce}".encodeToByteArray())
    private suspend fun validate(command: KvMutation) {
        when (command) {
            is KvMutation.Create -> KvValidation.title(command.title)
            is KvMutation.Rename -> KvValidation.title(command.title)
            is KvMutation.DeleteNamespace -> Unit
            is KvMutation.DeleteKey -> KvValidation.key(command.target.key)
            is KvMutation.Put -> {
                KvValidation.key(command.target.key); KvValidation.expiry(command.input.expiry, clock.nowMs() / 1000)
                if (command.input.content.descriptor.length !in 0..KV_MAX_VALUE_BYTES) throw invalidInputFailure()
                command.input.content.openVerified().close()
            }
            is KvMutation.BulkDelete -> KvValidation.keys(command.keys, 10000)
            is KvMutation.BulkWrite -> {
                val frozen = KvBulkJson.prepareWrite(command.input.content, clock.nowMs() / 1000)
                if (frozen.keys != command.input.keys) throw invalidInputFailure()
            }
        }
    }
    private fun preview(command: KvMutation, title: String): KvConfirmationView {
        val keys = when (command) {
            is KvMutation.Put -> listOf(command.target.key)
            is KvMutation.DeleteKey -> listOf(command.target.key)
            is KvMutation.BulkWrite -> command.input.keys.toList()
            is KvMutation.BulkDelete -> command.keys.toList()
            else -> emptyList()
        }
        val expiry = if (command is KvMutation.Put) when (val e = command.input.expiry) {
            KvExpiry.Never -> "替换为不过期（不是保留旧值）"
            is KvExpiry.Ttl -> "替换为相对 TTL：${e.seconds} 秒，从服务端受理起算"
            is KvExpiry.Absolute -> "替换为绝对 UTC Unix 秒：${e.epochSeconds}"
        } else if (command is KvMutation.BulkWrite) "逐项采用冻结文件中的 expiration / expiration_ttl；省略即不过期" else "不适用"
        val meta = when (command) {
            is KvMutation.Put -> if (command.input.metadata != null) "显式替换为已冻结 metadata；内容默认隐藏" else "未提供 metadata；不承诺保留旧 metadata"
            is KvMutation.BulkWrite -> "逐项采用冻结文件；未提供 metadata 的项不承诺保留旧 metadata"
            else -> "不适用"
        }
        val bytes = when (command) { is KvMutation.Put -> command.input.content.descriptor.length; is KvMutation.BulkWrite -> command.input.content.descriptor.length; else -> null }
        val warning = when (command) {
            is KvMutation.DeleteNamespace -> "删除本命名空间全部键值，不可回滚，可能影响 Worker 绑定；不会自动迁移或删除绑定。"
            is KvMutation.Put, is KvMutation.BulkWrite -> "可能覆盖同名值、metadata 和到期策略。没有只创建或 CAS 保证，其他客户端仍可并发修改。"
            is KvMutation.DeleteKey, is KvMutation.BulkDelete -> "永久删除列出的完整键范围，无法回滚；其他客户端仍可并发修改。"
            else -> "请核对准确账号、命名空间身份和新标题。"
        } + " API 接纳不代表全球传播完成；超时或未知结果不会自动重试。"
        val displayTitle = when (command) { is KvMutation.Create -> command.title; is KvMutation.Rename -> "$title → ${command.title}"; else -> title }
        val stage = if (command is KvMutation.Create || command is KvMutation.Rename) KvConfirmationStage.FINAL else KvConfirmationStage.RISK
        return KvConfirmationView(KvConfirmationHandle(stage), command.account, command.namespaceId, displayTitle, command.action,
            keys, expiry, meta, bytes, warning)
    }
    private fun release(command: KvMutation) { when (command) {
        is KvMutation.Put -> command.input.content.close()
        is KvMutation.BulkWrite -> command.input.content.close()
        else -> Unit
    } }
    private fun stale() = CloudFailure(CloudFailureCode.INVALID_INPUT, "KV 输入、页面、确认阶段或凭据已变化，请重新准备")
}
