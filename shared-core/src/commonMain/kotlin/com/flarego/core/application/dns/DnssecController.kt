package com.flarego.core.application.dns

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Uses the existing app issuer/coordinator/store; never creates a runtime or ordinary confirmation. */
@OptIn(ExperimentalAtomicApi::class)
class DnssecController(private val resolver: OperationContextResolver, private val plans: ChangePlans,
    private val coordinator: OperationCoordinator, private val clock: OperationClock, private val fingerprint: FingerprintPort,
    private val store: OperationStore, private val storeDispatcher: CoroutineDispatcher,
    private val session: suspend (ZoneScope) -> DnssecSession) {
    private data class Prepared(val plan: ChangePlan, val action: DnssecAction, val live: LiveOperationContext,
        val before: DnssecSnapshot, val preview: DnssecPreview)
    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())
    private val acknowledgements = AtomicReference<Map<String, Map<DnssecRisk, DnssecRiskAcknowledgement>>>(emptyMap())

    /** Product computes this independently from actual selected scope/draft, never from a plan. */
    fun requestFingerprint(action: DnssecAction): String {
        requireDnssecScope(action.scope)
        return digest(buildJsonObject {
            put("scope", scopeFields(action.scope)); put("action", dnssecMutation(action).operationId)
            put("target", referenceFields(action.scope)); put("input", dnssecFields(action))
        })
    }
    suspend fun prepare(action: DnssecAction): ChangePlan {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != action.scope || live.requestFingerprint != requestFingerprint(action)) throw invalidInputFailure()
        requireSameLive(live)
        requireNoUnsettled(action.scope)
        val api = session(action.scope)
        requireSameLive(live)
        val before = checkedSnapshot(api.observe(action.scope, dnssecMutation(action)), action.scope)
        requireDnssecWritable(action, before)
        requireSameLive(live)
        val p = plans.prepare(action.scope, dnssecMutation(action).operationId, dnssecRef(action.scope), snapshotHash(before),
            desiredHash(action.scope, dnssecFields(action)), requestFingerprint(action), live.credentialRevision, clock.nowMs())
        val entry = Prepared(p, action, live, before, previewOf(action, before))
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table + (p.id to entry))) break
        }
        return p
    }
    fun preview(planId: String): DnssecPreview = entry(planId).preview.let { it.copy(risks = it.risks.toSet()) }

    /** Requires an explicit user decision for this displayed plan and one specific risk; never auto-ack. */
    fun acknowledgeRisk(planId: String, risk: DnssecRisk): DnssecRiskAcknowledgement {
        val p = entry(planId)
        requirePreparedLive(p)
        if (risk !in risksFor(p.action)) throw invalidInputFailure()
        while (true) {
            val table = acknowledgements.load()
            val issued = table[planId].orEmpty()
            issued[risk]?.let { return it }
            val token = DnssecRiskAcknowledgement(planId, risk)
            if (acknowledgements.compareAndSet(table, table + (planId to (issued + (risk to token))))) return token
        }
    }
    suspend fun submit(action: DnssecAction, confirmed: ConfirmedPlan, risks: Set<DnssecRiskAcknowledgement> = emptySet()): OperationTask {
        val p = entry(confirmed.plan.id)
        val tokens = risks.toSet()
        requireBound(p, action, confirmed, tokens)
        requireNoUnsettled(action.scope, p.plan.id)
        requireBound(p, action, confirmed, tokens)
        val guard = { requireBound(p, action, confirmed, tokens) }
        return coordinator.executeGuarded(confirmed, guard) { composed ->
            val api = session(action.scope)
            guard()
            requireNoUnsettled(action.scope, p.plan.id)
            api.write(p.action, { snapshot ->
                val actual = checkedSnapshot(snapshot, action.scope)
                if (snapshotHash(actual) != p.plan.beforeHash) throw CloudFailure(CloudFailureCode.CONFLICT, "DNSSEC、Zone 或权限已变化，请重新预览确认")
                requireDnssecWritable(p.action, actual)
                composed()
            }, guard)
        }
    }

    /** Only persisted metadata and new read evidence. No restored payload or prepared/confirmation lookup. */
    suspend fun reconcile(taskId: String): OperationTask {
        val initial = withContext(storeDispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        val live = reconciliationLive(initial)
        if (initial.state == TaskState.SUCCEEDED && recognized(initial)) {
            requireSameLive(live)
            return initial
        }
        // The shared coordinator has no Accepted reconciliation variant. Preserve a previously
        // acknowledged pending operation only while its metadata and read evidence still agree.
        if (initial.state == TaskState.ACCEPTED && recognized(initial)) {
            val current = readForReconciliation(initial, live)
            if (current != null && pendingMatches(initial, current.details)) {
                requireSameLive(live)
                if (withContext(storeDispatcher) { store.get(taskId) } != initial) throw invalidInputFailure()
                requireSameLive(live)
                return initial
            }
        }
        return coordinator.reconcile(taskId) { task ->
            val actualLive = reconciliationLive(task)
            if (!recognized(task)) return@reconcile Reconciliation.StillUnknown
            val current = readForReconciliation(task, actualLive) ?: return@reconcile Reconciliation.StillUnknown
            val details = current.details
            val result = when {
                details.status !in setOf("active", "disabled") -> Reconciliation.StillUnknown
                task.actionKey == DnssecMutation.DELETE.operationId -> {
                    // A previous ACCEPTED receipt plus a terminal disabled read is evidence of
                    // provider completion. UNKNOWN has no delete receipt; disabled/404 alone cannot prove deletion.
                    val observed = desiredHash(details.scope, dnssecFields(DnssecAction.Delete(details.scope)))
                    if (task.state == TaskState.ACCEPTED && details.status == "disabled" && observed == task.desiredHash)
                        Reconciliation.Matched(observed) else Reconciliation.StillUnknown
                }
                else -> readCandidateHashes(details).firstOrNull { it == task.desiredHash }?.let(Reconciliation::Matched) ?: Reconciliation.StillUnknown
            }
            requireSameLive(actualLive)
            result
        }
    }
    private suspend fun readForReconciliation(task: OperationTask, live: LiveOperationContext): DnssecSnapshot? {
        val observed = try {
            val scope = task.scope as ZoneScope
            val api = session(scope)
            requireSameLive(live)
            checkedSnapshot(api.observe(scope, if (task.actionKey == DnssecMutation.DELETE.operationId) DnssecMutation.DELETE else DnssecMutation.UPDATE), scope)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        requireSameLive(live)
        return observed
    }
    private fun pendingMatches(task: OperationTask, details: DnssecDetails): Boolean {
        if (details.status !in dnssecPendingStatuses) return false
        if (task.actionKey == DnssecMutation.DELETE.operationId) return details.status == "pending-disabled" &&
            task.desiredHash == desiredHash(details.scope, dnssecFields(DnssecAction.Delete(details.scope)))
        // A desired active/disabled request can yield pending/pending-disabled. Hash only candidate
        // semantics; never recover or construct an executable input from the old task.
        val mapped = details.copy(raw = JsonObject(details.raw + ("status" to JsonPrimitive(if (details.status == "pending") "active" else "disabled"))))
        return task.desiredHash in readCandidateHashes(mapped)
    }
    private fun readCandidateHashes(details: DnssecDetails): List<String> {
        val names = dnssecInputNames.filter { details.raw[it] != null && details.raw[it] != JsonNull }.sorted()
        return (1 until (1 shl names.size)).mapNotNull { mask ->
            val fields = buildJsonObject { names.forEachIndexed { i, name -> if (mask and (1 shl i) != 0) put(name, details.raw.getValue(name)) } }
            if (runCatching { DnssecUpdateInput.fromFields(fields) }.isFailure) null else desiredHash(details.scope, fields)
        }
    }
    private fun recognized(task: OperationTask): Boolean = task.scope is ZoneScope && task.resourceRef == dnssecRef(task.scope) &&
        task.actionKey in DnssecMutation.entries.map { it.operationId } && task.desiredHash != null
    private fun entry(id: String) = prepared.load()[id] ?: throw invalidInputFailure()
    private fun requirePreparedLive(p: Prepared) {
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }
    private fun requireBound(p: Prepared, action: DnssecAction, confirmed: ConfirmedPlan, tokens: Set<DnssecRiskAcknowledgement>) {
        if (confirmed.plan != p.plan || action != p.action || requestFingerprint(action) != p.plan.requestFingerprint ||
            p.plan.actionKey != dnssecMutation(action).operationId || p.plan.scope != action.scope || p.plan.resourceRef != dnssecRef(action.scope) ||
            p.plan.beforeHash != snapshotHash(p.before) || p.plan.desiredHash != desiredHash(action.scope, dnssecFields(action)) ||
            p.plan.credentialRevision != p.live.credentialRevision) throw invalidInputFailure()
        requirePreparedLive(p)
        val required = risksFor(p.action)
        if (tokens.size != required.size || tokens.map { it.risk }.toSet() != required || tokens.any {
            it.planId != p.plan.id || acknowledgements.load()[p.plan.id]?.get(it.risk) !== it
        }) throw CloudFailure(CloudFailureCode.INVALID_INPUT, "请分别确认当前 DNSSEC 计划的每项风险")
    }
    private fun requireSameLive(live: LiveOperationContext) {
        if (resolver.current() != live || !resolver.isLive(live.scope, live.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(live.scope, live.connection)
    }
    private fun reconciliationLive(task: OperationTask): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (task.scope !is ZoneScope || task.scope != live.scope || live.credentialRevision < 0) throw invalidInputFailure()
        requireSameLive(live)
        return live
    }
    private suspend fun requireNoUnsettled(scope: ZoneScope, excluding: String? = null) {
        val blocked = withContext(storeDispatcher) {
            store.list(scope.connectionId).any { task ->
                task.id != excluding && task.scope == scope &&
                    task.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING, TaskState.ACCEPTED) &&
                    (task.resourceRef == dnssecRef(scope) || task.actionKey in DnssecMutation.entries.map { it.operationId })
            }
        }
        if (blocked) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "此 Zone 的 DNSSEC 操作尚未核对，请勿重放", uncertain = true)
    }
    private fun checkedSnapshot(snapshot: DnssecSnapshot, scope: ZoneScope): DnssecSnapshot {
        val owner = snapshot.owner
        val raw = dnssecFreeze(owner.raw)
        val account = (raw["account"] as? JsonObject)?.get("id") as? JsonPrimitive
        val id = raw["id"] as? JsonPrimitive
        val name = raw["name"] as? JsonPrimitive
        if (owner.ref != scope || account?.isString != true || account.content != scope.account.accountId || id?.isString != true ||
            id.content != scope.zoneId || name?.isString != true || name.content != owner.name) throw scopeMismatchFailure()
        val details = decodeDnssecDetails(scope, snapshot.details.raw, false)
        if (details != snapshot.details) throw scopeMismatchFailure()
        return snapshot.copy(owner = owner.copy(raw = raw, nameservers = owner.nameservers.toList()), details = details)
    }
    private fun risksFor(action: DnssecAction): Set<DnssecRisk> = when (action) {
        is DnssecAction.Delete -> setOf(DnssecRisk.DELETE)
        is DnssecAction.Update -> setOfNotNull(
            if (action.input.status == "disabled") DnssecRisk.DISABLE else null,
            if (action.input.multiSigner != null) DnssecRisk.CHANGE_MULTI_SIGNER else null,
            if (action.input.presigned != null) DnssecRisk.CHANGE_PRESIGNED else null,
            if (action.input.useNsec3 != null) DnssecRisk.CHANGE_NSEC3 else null,
        )
    }
    private fun previewOf(action: DnssecAction, snapshot: DnssecSnapshot): DnssecPreview {
        val impact = buildList {
            add("结果只证明当前 Cloudflare 配置或请求受理，不证明全网 DNSSEC 验证成功或注册商 DS 已安装")
            if (action is DnssecAction.Delete) add("独立 DELETE 删除 DNSSEC；不会从关闭开关自动触发")
            if (action is DnssecAction.Update) {
                if (action.input.multiSigner != null) add("改变多签名模式会影响多个提供商的 DNSKEY 协调及验证")
                if (action.input.presigned == true) add("开启预签名后所有记录按 DNS-only 提供，不支持 Secondary DNS Overrides 和 Load Balancing。主供应商必须转移 DNSKEY、RRSIG 以及 NSEC/NSEC3 记录与签名；注册商 DS 必须来自主供应商签名者（primary signer）。AXFR、签名转移健康与外部 DS 的前置条件当前未知，本应用未探测这些条件")
                if (action.input.presigned == false) add("关闭预签名会改变签名来源，需重新协调供应商签名和注册商 DS；AXFR 和外部 DS 当前未知，本应用未探测，不能保证代理或其他功能会自动恢复")
                if (action.input.useNsec3 != null) add("改变 NSEC3 模式会影响否定应答签名；开启需已验证 Enterprise 资格，并与主供应商的 NSEC/NSEC3 记录和预签名模式协调")
            }
        }.joinToString("；")
        return DnssecPreview(action.scope, snapshot.owner.name, dnssecMutation(action), risksFor(action),
            "注册商/父区当前 DS 状态未知。Cloudflare 返回的 DS 仅供注册商配置，不能证明已安装。停止签名或删除前，应在注册商移除 DS 并等待父区 DS TTL 到期；仍有 DS 时停止签名会使域名验证失败。本应用不探测或修改注册商 DS。",
            impact, "服务端无 CAS；预提交回读仍有读写之间的竞争窗口，不能保证原子隔离")
    }
    private fun scopeFields(scope: ZoneScope) = buildJsonObject { put("connection", scope.connectionId); put("account", scope.account.accountId); put("zone", scope.zoneId) }
    private fun referenceFields(scope: ZoneScope) = buildJsonObject { put("kind", "dnssec"); put("id", scope.zoneId) }
    private fun desiredHash(scope: ZoneScope, fields: JsonObject) = digest(buildJsonObject { put("scope", scopeFields(scope)); put("target", referenceFields(scope)); put("input", fields) })
    private fun snapshotHash(snapshot: DnssecSnapshot) = digest(buildJsonObject {
        put("owner", snapshot.owner.raw); put("dnssec", snapshot.details.raw)
        put("write_allowed", snapshot.writeAllowed?.let(::JsonPrimitive) ?: JsonNull)
        put("nsec3_available", snapshot.nsec3Available?.let(::JsonPrimitive) ?: JsonNull)
    })
    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))
}
