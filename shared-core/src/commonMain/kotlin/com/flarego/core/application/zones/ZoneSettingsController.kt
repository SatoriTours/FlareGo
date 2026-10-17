package com.flarego.core.application.zones

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.cloudflare.generated.ZoneSettingSchemas
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Uses the application's shared issuer/coordinator/store domain; it never creates one. */
@OptIn(ExperimentalAtomicApi::class)
class ZoneSettingsController(private val resolver: OperationContextResolver, private val plans: ChangePlans,
    private val coordinator: OperationCoordinator, private val clock: OperationClock, private val fingerprint: FingerprintPort,
    private val store: OperationStore, private val storeDispatcher: CoroutineDispatcher,
    private val session: suspend (ZoneScope) -> ZoneSettingsSession) {
    private data class Prepared(val plan: ChangePlan, val action: ZoneSettingsAction, val live: LiveOperationContext,
        val before: ZoneSettingsSnapshot, val preview: ZoneSettingsPreview)
    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())
    private val riskTokens = AtomicReference<Map<String, ZoneSettingsRiskAcknowledgement>>(emptyMap())

    /** Pure semantic input, computed independently from the real selection/draft by the product. */
    fun requestFingerprint(action: ZoneSettingsAction): String {
        val frozen = freezeSettingsAction(action)
        return digest(buildJsonObject {
            put("scope", scopeFields(frozen.scope)); put("action", settingsOperation(frozen))
            put("target", referenceFields(settingsReference(frozen.scope, settingsTarget(frozen))))
            put("input", settingsInput(frozen))
        })
    }

    suspend fun prepare(action: ZoneSettingsAction): ChangePlan {
        val frozen = freezeSettingsAction(action)
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != frozen.scope || live.requestFingerprint != requestFingerprint(frozen)) throw invalidInputFailure()
        ScopeGuard.requireOwned(frozen.scope, live.connection)
        requireSameLive(live)
        requireNoUnsettled(frozen)
        val api = session(frozen.scope)
        requireSameLive(live)
        val before = checkedSnapshot(api.observe(frozen.scope, settingsTarget(frozen)), frozen.scope, settingsTarget(frozen))
        requireSettingsWritable(frozen, before)
        requireSameLive(live)
        val plan = plans.prepare(frozen.scope, settingsOperation(frozen), settingsReference(frozen.scope, settingsTarget(frozen)),
            snapshotHash(before), desiredHash(frozen), requestFingerprint(frozen), live.credentialRevision, clock.nowMs())
        val entry = Prepared(plan, frozen, live, before, previewOf(frozen, before))
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table + (plan.id to entry))) break
        }
        return plan
    }

    fun preview(planId: String): ZoneSettingsPreview = entry(planId).preview

    /** Call only after a distinct explicit user decision for this displayed risk/plan. Never auto-ack. */
    fun acknowledgeRisk(planId: String, risk: ZoneSettingsRisk): ZoneSettingsRiskAcknowledgement {
        val p = entry(planId)
        requirePreparedLive(p)
        if (p.preview.risk != risk) throw invalidInputFailure()
        while (true) {
            val tokens = riskTokens.load()
            tokens[planId]?.let { return it }
            val token = ZoneSettingsRiskAcknowledgement(planId, risk)
            if (riskTokens.compareAndSet(tokens, tokens + (planId to token))) return token
        }
    }

    suspend fun submit(action: ZoneSettingsAction, confirmed: ConfirmedPlan, risk: ZoneSettingsRiskAcknowledgement? = null): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, action, confirmed, risk)
        requireNoUnsettled(p.action, p.plan.id)
        requireBound(p, action, confirmed, risk)
        val guard = { requireBound(p, action, confirmed, risk) }
        return coordinator.executeGuarded(confirmed, guard) { composed ->
            val api = session(p.action.scope)
            guard()
            // Held under the same per-target resource lock, including pre-prepared concurrent confirmations.
            requireNoUnsettled(p.action, p.plan.id)
            api.write(p.action, { observed ->
                val actual = checkedSnapshot(observed, p.action.scope, settingsTarget(p.action))
                if (snapshotHash(actual) != p.plan.beforeHash) throw CloudFailure(CloudFailureCode.CONFLICT, "设置、资格或 Zone 已变化，请重新预览确认")
                requireSettingsWritable(p.action, actual)
                composed()
            }, guard)
        }
    }

    /** Metadata plus read-only provider evidence; no prepared map, executable payload or restored confirmation. */
    suspend fun reconcile(taskId: String): OperationTask {
        val initial = withContext(storeDispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        val initialLive = reconciliationLive(initial)
        // An acknowledged purge has no global terminal-status endpoint. Keep its known
        // ACCEPTED state; checking it must not turn it UNKNOWN and permanently block new explicit requests.
        if (initial.actionKey == "zone-purge" && initial.state == TaskState.ACCEPTED) {
            requireSameLive(initialLive)
            return initial
        }
        return coordinator.reconcile(taskId) { task ->
            val live = reconciliationLive(task)
            val target = targetFrom(task)
            if (target == null || target == ZoneSettingsTarget.Cache || task.desiredHash == null) return@reconcile Reconciliation.StillUnknown
            val evidence = try {
                val api = session(task.scope as ZoneScope)
                requireSameLive(live)
                val current = checkedSnapshot(api.observe(task.scope, target), task.scope, target)
                val fields = when (target) {
                    is ZoneSettingsTarget.Setting -> readCandidates(target.key, current.setting!!.value)
                    ZoneSettingsTarget.UniversalSsl -> listOf(buildJsonObject { put("enabled", current.universal!!.enabled) })
                    ZoneSettingsTarget.Cache -> emptyList()
                }
                fields.firstNotNullOfOrNull { input ->
                    val observed = desiredFieldsHash(task.scope, target, input)
                    if (observed == task.desiredHash) Reconciliation.Matched(observed) else null
                } ?: Reconciliation.StillUnknown
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Reconciliation.StillUnknown }
            requireSameLive(live)
            evidence
        }
    }

    private fun entry(id: String) = prepared.load()[id] ?: throw invalidInputFailure()
    private fun requirePreparedLive(p: Prepared) {
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }
    private fun requireBound(p: Prepared, action: ZoneSettingsAction, confirmed: ConfirmedPlan, risk: ZoneSettingsRiskAcknowledgement?) {
        if (confirmed.plan != p.plan || freezeSettingsAction(action) != p.action || requestFingerprint(action) != p.plan.requestFingerprint ||
            p.plan.actionKey != settingsOperation(action) || p.plan.scope != action.scope ||
            p.plan.resourceRef != settingsReference(action.scope, settingsTarget(action)) ||
            p.plan.beforeHash != snapshotHash(p.before) || p.plan.desiredHash != desiredHash(p.action) ||
            p.plan.credentialRevision != p.live.credentialRevision) throw invalidInputFailure()
        requirePreparedLive(p)
        val required = p.preview.risk
        if (required != null && (risk == null || riskTokens.load()[p.plan.id] !== risk || risk.planId != p.plan.id || risk.risk != required)) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "请单独确认该风险后再提交此计划")
        }
        if (required == null && risk != null) throw invalidInputFailure()
    }
    private fun requireSameLive(expected: LiveOperationContext) {
        if (resolver.current() != expected || !resolver.isLive(expected.scope, expected.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(expected.scope, expected.connection)
    }
    private fun reconciliationLive(task: OperationTask): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (task.scope !is ZoneScope || live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
        ScopeGuard.requireOwned(task.scope, live.connection)
        requireSameLive(live)
        return live
    }
    private suspend fun requireNoUnsettled(action: ZoneSettingsAction, excluding: String? = null) {
        val target = settingsTarget(action)
        val blocked = withContext(storeDispatcher) {
            store.list(action.scope.connectionId).any { task ->
                task.id != excluding && task.scope == action.scope &&
                    (task.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING) ||
                        task.state == TaskState.ACCEPTED && task.actionKey != "zone-purge") && mayAffectTarget(task, target)
            }
        }
        if (blocked) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "该目标的上一操作结果未知，请先核对，不要重放", uncertain = true)
    }

    private fun mayAffectTarget(task: OperationTask, target: ZoneSettingsTarget): Boolean {
        val scope = task.scope as? ZoneScope ?: return false
        if (task.resourceRef == settingsReference(scope, target)) return true
        targetFrom(task)?.let { return targetsOverlap(it, target) }
        // A missing/unrecognisable locator grants no independence. Only a reviewed
        // fixed operation/path can prove a narrower target; the generic route can
        // address a setting whose key is no longer known, so it overlaps all settings.
        if (task.actionKey == ZoneSettingKey.SSL.writeOperation) return target is ZoneSettingsTarget.Setting
        val fixed = ZoneSettingKey.entries.firstOrNull { !it.generic && it.writeOperation == task.actionKey }
        if (fixed != null) return targetsOverlap(ZoneSettingsTarget.Setting(fixed), target)
        return when (task.actionKey) {
            UNIVERSAL_SSL_WRITE -> target == ZoneSettingsTarget.UniversalSsl
            "zone-purge" -> target == ZoneSettingsTarget.Cache
            else -> false
        }
    }

    private fun targetsOverlap(first: ZoneSettingsTarget, second: ZoneSettingsTarget): Boolean {
        if (first == second) return true
        if (first !is ZoneSettingsTarget.Setting || second !is ZoneSettingsTarget.Setting) return false
        val children = setOf(ZoneSettingKey.IMAGE_RESIZING, ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS, ZoneSettingKey.TRANSFORMATIONS_C2PA)
        return first.key == ZoneSettingKey.TRANSFORMATIONS_CONFIG && second.key in children ||
            second.key == ZoneSettingKey.TRANSFORMATIONS_CONFIG && first.key in children
    }
    private fun targetFrom(task: OperationTask): ZoneSettingsTarget? {
        val scope = task.scope as? ZoneScope ?: return null
        val ref = task.resourceRef ?: return null
        if (ref.scope != scope) return null
        return when (ref.kind) {
            "zone_setting" -> ZoneSettingKey.entries.firstOrNull { it.name == ref.externalId && it.writeOperation == task.actionKey }?.let(ZoneSettingsTarget::Setting)
            "universal_ssl" -> if (ref.externalId == "universal_ssl" && task.actionKey == UNIVERSAL_SSL_WRITE) ZoneSettingsTarget.UniversalSsl else null
            "cache_purge" -> if (ref.externalId == scope.zoneId && task.actionKey == "zone-purge") ZoneSettingsTarget.Cache else null
            else -> null
        }
    }

    private fun checkedSnapshot(observed: ZoneSettingsSnapshot, scope: ZoneScope, target: ZoneSettingsTarget): ZoneSettingsSnapshot {
        val owner = observed.owner
        val raw = freeze(owner.raw)
        val account = (raw["account"] as? JsonObject)?.get("id") as? JsonPrimitive
        val id = raw["id"] as? JsonPrimitive
        val name = raw["name"] as? JsonPrimitive
        if (owner.ref != scope || account?.isString != true || account.content != scope.account.accountId ||
            id?.isString != true || id.content != scope.zoneId || name?.isString != true || name.content != owner.name) throw scopeMismatchFailure()
        val setting = observed.setting?.let { value ->
            val data = freeze(value.raw)
            val key = (target as? ZoneSettingsTarget.Setting)?.key ?: throw invalidInputFailure()
            if (value.scope != scope || value.key != key || data["id"] != JsonPrimitive(key.responseId) || data["value"] != value.value ||
                (data["editable"] as? JsonPrimitive)?.booleanOrNull != value.editable) throw scopeMismatchFailure()
            value.copy(raw = data, value = data.getValue("value"))
        }
        val universal = observed.universal?.let { value ->
            val data = freeze(value.raw)
            if (target != ZoneSettingsTarget.UniversalSsl || value.scope != scope || data["enabled"] != JsonPrimitive(value.enabled) ||
                (data["editable"] as? JsonPrimitive)?.booleanOrNull != value.editable) throw scopeMismatchFailure()
            value.copy(raw = data)
        }
        if (target is ZoneSettingsTarget.Setting && setting == null || target == ZoneSettingsTarget.UniversalSsl && universal == null) throw invalidInputFailure()
        return observed.copy(owner = owner.copy(raw = raw, nameservers = owner.nameservers.toList()), setting = setting, universal = universal)
    }
    private fun snapshotHash(snapshot: ZoneSettingsSnapshot) = digest(buildJsonObject {
        put("owner", snapshot.owner.raw)
        put("setting", snapshot.setting?.raw ?: JsonNull)
        put("universal", snapshot.universal?.raw ?: JsonNull)
        put("write_allowed", snapshot.writeAllowed?.let(::JsonPrimitive) ?: JsonNull)
        put("purge_tier", snapshot.purgeTier?.name?.let(::JsonPrimitive) ?: JsonNull)
    })
    private fun desiredHash(action: ZoneSettingsAction): String? = if (action is ZoneSettingsAction.Purge) null
        else desiredFieldsHash(action.scope, settingsTarget(action), settingsInput(action))
    private fun desiredFieldsHash(scope: ZoneScope, target: ZoneSettingsTarget, fields: JsonObject) = digest(buildJsonObject {
        put("scope", scopeFields(scope)); put("target", referenceFields(settingsReference(scope, target))); put("input", fields)
    })
    private fun scopeFields(scope: ZoneScope) = buildJsonObject { put("connection", scope.connectionId); put("account", scope.account.accountId); put("zone", scope.zoneId) }
    private fun referenceFields(ref: ResourceRef) = buildJsonObject { put("kind", ref.kind); put("id", ref.externalId) }
    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))
    private fun freeze(value: JsonObject) = Json.parseToJsonElement(value.toString()).jsonObject

    /** Hash candidates only. Never turn read values or persisted hashes into a write command. */
    private fun readCandidates(key: ZoneSettingKey, actual: JsonElement): List<JsonObject> {
        val values = if (actual is JsonObject) {
            fun propertyNames(schema: JsonObject): Set<String> = (schema["properties"] as? JsonObject)?.keys.orEmpty() +
                listOf("oneOf", "anyOf", "allOf").flatMap { branch -> (schema[branch] as? JsonArray)?.flatMap { propertyNames(it.jsonObject) }.orEmpty() }
            val valueSchema = ZoneSettingSchemas.input(key.route, key.generic)?.get("properties")?.jsonObject?.get("value") as? JsonObject ?: return emptyList()
            val names = actual.keys.intersect(propertyNames(valueSchema)).sorted()
            if (names.size > 8) return emptyList() // Bounded subset proof for this fixed small schema, never an unbounded search.
            (1 until (1 shl names.size)).map { mask -> buildJsonObject {
                names.forEachIndexed { index, name -> if (mask and (1 shl index) != 0) put(name, actual.getValue(name)) }
            } }
        } else listOf(actual)
        return values.mapNotNull { value ->
            val fields = buildJsonObject { put("value", value) }
            if (runCatching { validateZoneSettingInput(key, fields) }.isSuccess) fields else null
        }
    }
    private fun previewOf(action: ZoneSettingsAction, before: ZoneSettingsSnapshot): ZoneSettingsPreview {
        val risk = when (action) {
            is ZoneSettingsAction.Update -> {
                val old = (before.setting?.value as? JsonPrimitive)?.content
                val next = (action.input["value"] as? JsonPrimitive)?.content
                val levels = when (action.key) {
                    ZoneSettingKey.SSL -> listOf("off", "flexible", "full", "strict")
                    ZoneSettingKey.MIN_TLS_VERSION -> listOf("1.0", "1.1", "1.2", "1.3")
                    else -> emptyList()
                }
                if (levels.isNotEmpty() && (old !in levels || levels.indexOf(next) < levels.indexOf(old))) ZoneSettingsRisk.SSL_DOWNGRADE else null
            }
            is ZoneSettingsAction.UniversalSsl -> if (!action.enabled) ZoneSettingsRisk.DISABLE_UNIVERSAL_SSL else null
            is ZoneSettingsAction.Purge -> if (action.input == CachePurgeInput.All) ZoneSettingsRisk.PURGE_ALL else null
        }
        val impact = when (risk) {
            ZoneSettingsRisk.SSL_DOWNGRADE -> "降低 SSL/TLS 保护级别，可能使流量缺少加密或来源证书验证；请独立确认"
            ZoneSettingsRisk.DISABLE_UNIVERSAL_SSL -> "关闭 Universal SSL 可能影响访客 HTTPS；不等于删除注册域名，请独立确认"
            ZoneSettingsRisk.PURGE_ALL -> "请求清除此 Zone 全部缓存，可能增加源站负载；请独立确认"
            null -> if (action is ZoneSettingsAction.Purge) "仅请求清除所列缓存目标；受理不代表内容存在、已逐项清除或全网完成"
                else "更新指定配置并回读目标字段；匹配只证明当前配置值，不保证证书签发或全网行为已完成"
        }
        return ZoneSettingsPreview(action.scope, before.owner.name, settingsTarget(action), risk, impact,
            "服务端未提供 CAS；预提交回读无法保证原子并发隔离，读写之间仍有竞争窗口")
    }
}
