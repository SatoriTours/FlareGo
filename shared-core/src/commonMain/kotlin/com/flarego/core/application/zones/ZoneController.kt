package com.flarego.core.application.zones

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Previews and executes typed Zone lifecycle actions. The product must supply an
 * independent resolver derived from actual selected scope, input and credential
 * revision; neither this controller nor a ChangePlan populates that live state.
 * Payloads and provider snapshots stay in memory, never in OperationTask metadata.
 */
@OptIn(ExperimentalAtomicApi::class)
class ZoneController(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val session: suspend (CloudScope) -> ZoneManagementSession,
) {
    private data class Prepared(
        val plan: ChangePlan,
        val action: ZoneAction,
        val before: ZoneDetails?,
        val live: LiveOperationContext,
        val preview: ZoneChangePreview,
    )
    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    /** Pure function of actual scope/input, for the independent product resolver. */
    fun requestFingerprint(action: ZoneAction): String = digest(semantic(freezeZoneAction(action)))

    suspend fun prepare(action: ZoneAction): ChangePlan {
        val frozen = freezeZoneAction(action)
        validateDelete(frozen)
        val live = requireCurrent(frozen)
        requireNoUnsettled(frozen)
        requireSameLive(live)
        val api = session(frozen.scope)
        requireSameLive(live)
        val before = when (frozen) {
            is ZoneAction.Create -> {
                requireAbsent(api, frozen)
                null
            }
            else -> ownedSnapshot(api.get(frozen.scope as ZoneScope), frozen.scope as ZoneScope)
        }
        validateTarget(frozen, before)
        requireSameLive(live)
        val plan = plans.prepare(
            scope = frozen.scope,
            actionKey = zoneActionKey(frozen),
            // Every existing-Zone mutation shares the same coordinator resource lock.
            resourceRef = targetRef(frozen),
            beforeHash = before?.let { digest(it.raw) },
            desiredHash = if (frozen is ZoneAction.ActivationCheck) null else digest(desired(frozen)),
            requestFingerprint = requestFingerprint(frozen),
            credentialRevision = live.credentialRevision,
            nowMs = clock.nowMs(),
        )
        val entry = Prepared(plan, frozen, before, live, previewOf(frozen, before))
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table + (plan.id to entry))) break
        }
        return plan
    }

    fun preview(planId: String): ZoneChangePreview = entry(planId).preview

    suspend fun submit(action: ZoneAction, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, action, confirmed)
        requireNoUnsettled(p.action, p.plan.id)
        requireBound(p, action, confirmed)
        val guard = {
            requireBound(p, action, confirmed)
            Unit
        }
        return coordinator.executeGuarded(confirmed, guard) { composed ->
            // Session/credential acquisition can suspend. It must finish before final checks.
            val api = session(p.action.scope)
            guard()
            // The resource lock is now held. Another pre-prepared confirmation may
            // have settled UNKNOWN while this operation was queued behind it.
            requireNoUnsettled(p.action, p.plan.id)
            if (p.action is ZoneAction.Create) requireAbsent(api, p.action)
            api.write(p.action) { observed ->
                if (p.action !is ZoneAction.Create) {
                    val current = ownedSnapshot(observed ?: throw invalidInputFailure(), p.action.scope as ZoneScope)
                    validateTarget(p.action, current)
                    if (digest(current.raw) != p.plan.beforeHash) throw CloudFailure(
                        CloudFailureCode.CONFLICT, "Zone 状态已变化，请刷新并重新预览确认")
                } else if (observed != null) throw invalidInputFailure()
                // No suspension between this composed final guard and the typed send.
                composed()
            }
        }
    }

    /**
     * Reads only, including after process/controller recreation. Persisted scope,
     * locator and desired hash identify what to verify; they never reconstruct a
     * live context, payload, ChangePlan or ConfirmedPlan. A match proves current
     * desired state, not which client caused it or atomic isolation.
     */
    suspend fun reconcile(taskId: String): OperationTask = coordinator.reconcile(taskId) { task ->
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
        ScopeGuard.requireOwned(task.scope, live.connection)
        requireSameLive(live)
        val reference = task.resourceRef ?: return@reconcile Reconciliation.StillUnknown
        val validTarget = reference.scope == task.scope && when (task.actionKey) {
            "zones-post" -> task.scope is AccountScope && reference.kind == "zone_creation"
            "zones-0-patch", "zones-0-delete" -> task.scope is ZoneScope && reference.kind == "zone" &&
                reference.externalId == task.scope.zoneId
            else -> false
        }
        if (!validTarget || task.desiredHash == null) return@reconcile Reconciliation.StillUnknown
        val evidence = try {
            val api = session(task.scope)
            requireSameLive(live)
            when (task.actionKey) {
                "zones-post" -> {
                    val account = task.scope as AccountScope
                    val matches = allZones(api, account).filter { it.name.equals(reference.externalId, ignoreCase = true) }
                    if (matches.size != 1) Reconciliation.StillUnknown else {
                        val actual = matches.single()
                        matchCandidate(task, buildJsonObject {
                            put("name", actual.name.lowercase())
                            put("type", actual.type)
                        })
                    }
                }
                "zones-0-patch" -> {
                    val scope = task.scope as ZoneScope
                    val actual = ownedSnapshot(api.get(scope), scope)
                    // The fixed API permits one field per PATCH. Construct read-only
                    // candidate semantics from actual values, never an executable edit.
                    val paused = (actual.raw["paused"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    val type = (actual.raw["type"] as? JsonPrimitive)?.takeIf { it.isString }
                    val vanity = (actual.raw["vanity_name_servers"] as? JsonArray)?.takeIf { array ->
                        array.all { (it as? JsonPrimitive)?.isString == true }
                    }
                    val candidates = listOfNotNull(
                        paused?.let { buildJsonObject { put("paused", it) } },
                        type?.let { buildJsonObject { put("type", it) } },
                        vanity?.let { buildJsonObject { put("vanity_name_servers", it) } },
                    )
                    candidates.firstNotNullOfOrNull { fields ->
                        val match = matchCandidate(task, buildJsonObject { put("input", fields) })
                        match as? Reconciliation.Matched
                    } ?: Reconciliation.StillUnknown
                }
                "zones-0-delete" -> {
                    val scope = task.scope as ZoneScope
                    try {
                        ownedSnapshot(api.get(scope), scope)
                        Reconciliation.StillUnknown
                    } catch (failure: CloudFailure) {
                        if (failure.code == CloudFailureCode.NOT_FOUND) {
                            matchCandidate(task, buildJsonObject { put("deleted", true) })
                        } else throw failure
                    }
                }
                else -> Reconciliation.StillUnknown
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Permission errors, missing ownership, malformed/partial reads and
            // conflicts are no proof of application. Retain UNKNOWN without replay.
            Reconciliation.StillUnknown
        }
        // A new selection/credential during session acquisition or cloud reads must
        // fail instead of settling from a stale observation. This is outside the catch.
        requireSameLive(live)
        evidence
    }

    private fun matchCandidate(task: OperationTask, observedFields: JsonObject): Reconciliation {
        val candidate = digest(buildJsonObject {
            put("scope", scopeFields(task.scope))
            observedFields.forEach { (key, value) -> put(key, value) }
        })
        return if (candidate == task.desiredHash) Reconciliation.Matched(candidate) else Reconciliation.StillUnknown
    }

    private fun entry(id: String) = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, action: ZoneAction, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan || freezeZoneAction(action) != p.action ||
            requestFingerprint(action) != p.plan.requestFingerprint ||
            zoneActionKey(action) != p.plan.actionKey || action.scope != p.plan.scope ||
            p.plan.beforeHash != p.before?.let { digest(it.raw) } ||
            p.plan.desiredHash != if (action is ZoneAction.ActivationCheck) null else digest(desired(p.action))) {
            throw invalidInputFailure()
        }
        validateDelete(action)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(action: ZoneAction): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != action.scope || live.requestFingerprint != requestFingerprint(action)) throw invalidInputFailure()
        ScopeGuard.requireOwned(action.scope, live.connection)
        if (!resolver.isLive(action.scope, live.credentialRevision)) throw invalidInputFailure()
        return live
    }

    private fun requireSameLive(expected: LiveOperationContext) {
        if (resolver.current() != expected || !resolver.isLive(expected.scope, expected.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(expected.scope, expected.connection)
    }

    private fun validateDelete(action: ZoneAction) {
        if (action is ZoneAction.Delete && (action.name.isBlank() || action.name != action.typedName)) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "请输入与目标完全一致的完整域名后再确认删除")
        }
    }

    private fun validateTarget(action: ZoneAction, before: ZoneDetails?) {
        validateDelete(action)
        if (action is ZoneAction.Delete && before?.name != action.name) throw scopeMismatchFailure()
        if (action is ZoneAction.ActivationCheck && before?.status != "pending") throw CloudFailure(
            CloudFailureCode.INVALID_INPUT, "仅可为 pending 状态的 Zone 请求激活检查")
        if (action is ZoneAction.Edit && zoneEditFields(action.input).all { (key, value) -> before?.raw?.get(key) == value }) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "该 Zone 属性没有变化，请修改输入后再预览")
        }
    }

    private fun targetRef(action: ZoneAction): ResourceRef = when (val scope = action.scope) {
        is AccountScope -> ResourceRef(scope, "zone_creation", (action as ZoneAction.Create).input.name.lowercase())
        is ZoneScope -> ResourceRef(scope, "zone", scope.zoneId)
        else -> throw invalidInputFailure()
    }

    /** Persisted uncertainty survives controller recreation; it permits reads only. */
    private suspend fun requireNoUnsettled(action: ZoneAction, excluding: String? = null) {
        val target = targetRef(action)
        val connectionId = when (val scope = action.scope) {
            is AccountScope -> scope.connectionId
            is ZoneScope -> scope.connectionId
            else -> throw invalidInputFailure()
        }
        val blocked = withContext(storeDispatcher) {
            store.list(connectionId).any { task ->
                task.id != excluding && task.scope == action.scope &&
                    task.actionKey in setOf("zones-post", "zones-0-patch", "zones-0-delete", "put-zones-zone_id-activation_check") &&
                    (task.resourceRef == target || task.resourceRef == null) &&
                    (task.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING) ||
                        task.state == TaskState.ACCEPTED && task.actionKey != "put-zones-zone_id-activation_check")
            }
        }
        if (blocked) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "该 Zone 的上一操作尚未核对，请先核对结果，不要再次发送", uncertain = true)
    }

    /** ScopeGuard is local binding only. Check provider account AND id separately. */
    private fun ownedSnapshot(details: ZoneDetails, scope: ZoneScope): ZoneDetails {
        val raw = Json.parseToJsonElement(details.raw.toString()).jsonObject
        val owner = (raw["account"] as? JsonObject)?.get("id") as? JsonPrimitive
        val id = raw["id"] as? JsonPrimitive
        val name = raw["name"] as? JsonPrimitive
        if (details.ref != scope || owner?.isString != true || owner.content != scope.account.accountId ||
            id?.isString != true || id.content != scope.zoneId || name?.isString != true || name.content != details.name) {
            throw scopeMismatchFailure()
        }
        return details.copy(nameservers = details.nameservers.toList(), raw = raw)
    }

    private suspend fun requireAbsent(api: ZoneManagementPort, action: ZoneAction.Create) {
        if (allZones(api, action.scope).any { it.name.equals(action.input.name, ignoreCase = true) }) {
            throw CloudFailure(CloudFailureCode.CONFLICT, "该账号已存在同名 Zone，请先查看现有 Zone")
        }
    }

    /** No missing metadata, duplicate ids or partial pagination may prove absence. */
    private suspend fun allZones(api: ZoneManagementPort, account: AccountScope): List<ZoneDetails> {
        val rows = mutableListOf<ZoneDetails>()
        val ids = mutableSetOf<String>()
        var next: PageToken? = null
        var expectedTotal: Long? = null
        do {
            val page = api.list(account, PageRequest(50, next))
            val total = page.total ?: throw CloudFailure(CloudFailureCode.INVALID_RESPONSE, "Zone 列表缺少完整性信息，请刷新后核对")
            if (expectedTotal != null && expectedTotal != total) throw invalidInputFailure()
            expectedTotal = total
            for (item in page.items) {
                val scope = ZoneScope(account, item.ref.zoneId)
                val owned = ownedSnapshot(item, scope)
                if (!ids.add(scope.zoneId)) throw invalidInputFailure()
                rows += owned
            }
            val continuation = page.next
            if (continuation != null && (continuation !is PageToken.Number ||
                continuation.value <= ((next as? PageToken.Number)?.value ?: 1))) throw invalidInputFailure()
            next = continuation
        } while (next != null)
        if (rows.size.toLong() != expectedTotal) throw CloudFailure(
            CloudFailureCode.INVALID_RESPONSE, "Zone 列表尚不完整，请刷新后核对")
        return rows
    }

    private fun semantic(action: ZoneAction): JsonObject = buildJsonObject {
        put("action", zoneActionKey(action))
        put("scope", scopeFields(action.scope))
        when (action) {
            is ZoneAction.Create -> { put("name", action.input.name); put("type", action.input.type) }
            is ZoneAction.Edit -> put("input", zoneEditFields(action.input))
            is ZoneAction.Delete -> { put("name", action.name); put("typed_name", action.typedName) }
            is ZoneAction.ActivationCheck -> Unit
        }
    }

    private fun desired(action: ZoneAction, editFields: JsonObject? = null): JsonObject = buildJsonObject {
        put("scope", scopeFields(action.scope))
        when (action) {
            is ZoneAction.Create -> { put("name", action.input.name); put("type", action.input.type) }
            is ZoneAction.Edit -> put("input", editFields ?: zoneEditFields(action.input))
            is ZoneAction.Delete -> put("deleted", true)
            is ZoneAction.ActivationCheck -> put("check_requested", true)
        }
    }

    private fun scopeFields(scope: CloudScope): JsonObject = buildJsonObject {
        val account = when (scope) {
            is AccountScope -> scope
            is ZoneScope -> { put("zone", scope.zoneId); scope.account }
            else -> throw invalidInputFailure()
        }
        put("connection", account.connectionId)
        put("account", account.accountId)
    }

    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))

    private fun previewOf(action: ZoneAction, before: ZoneDetails?): ZoneChangePreview {
        val domain = (action as? ZoneAction.Create)?.input?.name ?: before?.name ?: throw invalidInputFailure()
        val impact = when (action) {
            is ZoneAction.Create -> "在所选账号创建 Cloudflare Zone；不会购买或注册域名，需按返回的名称服务器或验证流程继续激活"
            is ZoneAction.Delete -> "删除该账号的整个 Zone 及其 DNS 配置，可能导致域名服务中断；不会注销注册域名"
            is ZoneAction.Edit -> when {
                action.input.paused == true -> "暂停 Cloudflare 安全与性能服务，保留 DNS 服务；域名流量可能直接访问源站"
                action.input.paused == false -> "恢复该 Zone 的 Cloudflare 安全与性能服务"
                action.input.type != null -> "变更 Zone 类型；类型变更可能影响 DNS 托管或验证，需供应商授权的套餐或明确启用"
                else -> "变更自定义名称服务器；仅供应商授权的 Business／Enterprise 套餐可用"
            }
            is ZoneAction.ActivationCheck -> "仅请求官方激活检查；不代表 Zone 已激活或 DNS 已传播完成，Free 每小时、付费每五分钟受服务端频率限制"
        }
        return ZoneChangePreview(action.scope, domain, impact,
            "服务端未提供 CAS；预提交回读无法保证原子并发隔离，回读与写入之间仍有竞争窗口")
    }
}
