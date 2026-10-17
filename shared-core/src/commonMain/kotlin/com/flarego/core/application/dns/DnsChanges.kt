package com.flarego.core.application.dns

import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.generated.DnsSchemas
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

enum class DnsWriteMode { CREATE, PATCH, REPLACE, DELETE }
data class DnsWriteAction(val scope: ZoneScope, val recordId: String?, val input: DnsRecordInput?, val mode: DnsWriteMode)
data class PreparedDnsChange(val plan: ChangePlan, val before: DnsRecordDocument?, val input: DnsRecordInput?, val mode: DnsWriteMode)
data class DnsChangePreview(val mode: DnsWriteMode, val recordName: String, val changedFields: Set<String>, val warnings: List<String>)

/** Inject the app's single P1 issuer/coordinator and an independent live product resolver. */
@OptIn(ExperimentalAtomicApi::class)
class DnsChanges(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val constraints: (ZoneScope) -> DnsConstraints = { DnsConstraints() },
    private val session: suspend (ZoneScope) -> DnsManagementSession,
) {
    private class Entry(
        val prepared: PreparedDnsChange,
        val action: DnsWriteAction,
        val live: LiveOperationContext,
        val preview: DnsChangePreview,
        val baseline: Set<String>,
    ) {
        val notSent = AtomicReference<CloudFailure?>(null)
    }
    private val entries = AtomicReference<Map<String, Entry>>(emptyMap())

    /** Pure fingerprint of actual draft/selection. Never populate resolver.current from a plan. */
    fun requestFingerprint(scope: ZoneScope, recordId: String?, input: DnsRecordInput?, mode: DnsWriteMode): String = digest(buildJsonObject {
        put("scope", dnsScopeFields(scope)); put("record_id", recordId?.let(::JsonPrimitive) ?: JsonNull); put("mode", mode.name)
        input?.let { put("input", buildJsonObject { put("type", it.type); put("name", it.name); put("fields", it.fields) }) }
    })

    suspend fun prepare(scope: ZoneScope, recordId: String?, input: DnsRecordInput?, mode: DnsWriteMode): PreparedDnsChange {
        requireDnsActionShape(recordId, input, mode)
        val frozenInput = input?.let { it.copy(fields = dnsCopy(it.fields)) }
        val live = requireCurrent(scope, requestFingerprint(scope, recordId, frozenInput, mode))
        if (recordId != null) requireNoUnsettled(ResourceRef(scope, "dns_record", recordId))
        requireSameLive(live)
        val api = session(scope)
        requireSameLive(live)
        val owner = requireDnsZone(api.zone(scope), scope)
        requireSameLive(live)
        val before = recordId?.let { requireDnsDocument(api.get(scope, it), scope, it, owner.name) }
        val validated = if (mode == DnsWriteMode.DELETE) null else dnsWriteInput(owner.name, before, frozenInput!!, mode, constraints(scope))
        val action = DnsWriteAction(scope, recordId, validated, mode)
        val target = dnsTarget(action)
        requireNoUnsettled(target)
        val desired = desired(action, before)
        val candidates = if (mode == DnsWriteMode.CREATE) allCandidates(api, action) else emptyList()
        // This precondition is part of issuing every task with a creation locator.
        // Restored read-only verification can then prove current unique desired
        // state, without inventing a payload or claiming who created the record.
        if (candidates.any { digest(dnsSemanticRecord(it.raw)) == digest(desired) }) throw CloudFailure(
            CloudFailureCode.CONFLICT, "已存在完全相同的 DNS 记录，请查看现有记录，不要重复创建")
        val baseline = candidates.map { it.id }.toSet()
        requireSameLive(live)
        val plan = plans.prepare(scope, dnsActionKey(mode), target, before?.let { digest(dnsSemanticRecord(it.raw)) },
            digest(desired), live.requestFingerprint, live.credentialRevision, clock.nowMs())
        val prepared = PreparedDnsChange(plan, before, validated, mode)
        val changed = if (mode == DnsWriteMode.DELETE) setOf("deleted") else validated!!.fields.keys +
            (if (before != null && validated.name != dnsText(before.raw, "name").lowercase().removeSuffix(".")) setOf("name") else emptySet())
        val warnings = listOf("预提交回读不构成强原子并发保证；Cloudflare 未提供 CAS，读取与提交之间仍可能发生竞争。") +
            if (mode == DnsWriteMode.REPLACE) listOf("PUT 将完整替换记录；未提供的可写属性可能恢复默认值，须单独确认。") else emptyList()
        val entry = Entry(prepared, action, live, DnsChangePreview(mode, validated?.name ?: before!!.raw.getValue("name").jsonPrimitive.content, changed, warnings), baseline)
        while (true) {
            val previous = entries.load()
            if (entries.compareAndSet(previous, previous + (plan.id to entry))) break
        }
        return prepared
    }

    fun preview(planId: String): DnsChangePreview = entry(planId).preview

    suspend fun submit(prepared: PreparedDnsChange, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(prepared.plan.id)
        requireBound(p, prepared, confirmed)
        requireNoUnsettled(dnsTarget(p.action), p.prepared.plan.id)
        requireBound(p, prepared, confirmed)
        val guard = { requireBound(p, prepared, confirmed) }
        val executed = coordinator.executeGuarded(confirmed, guard) { composed ->
            var admittedSend = false
            try {
                val api = session(p.action.scope)
                guard()
                // Held inside the shared P1 resource lock; a pre-prepared plan cannot
                // bypass another confirmation that has just settled UNKNOWN.
                requireNoUnsettled(dnsTarget(p.action), p.prepared.plan.id)
                if (p.action.mode == DnsWriteMode.CREATE) {
                    val candidates = allCandidates(api, p.action)
                    if (candidates.map { it.id }.toSet() != p.baseline || candidates.any {
                        digest(dnsSemanticRecord(it.raw)) == p.prepared.plan.desiredHash
                    }) throw dnsConflict()
                }
                api.write(p.action) { observed ->
                    if (p.action.mode == DnsWriteMode.CREATE) {
                        if (observed != null) throw invalidInputFailure()
                    } else {
                        val current = observed ?: throw dnsConflict()
                        requireDnsDocument(current, p.action.scope, p.action.recordId!!)
                        if (digest(dnsSemanticRecord(current.raw)) != p.prepared.plan.beforeHash) throw dnsConflict()
                    }
                    composed()
                    admittedSend = true
                }.also {
                    if (!admittedSend) throw invalidInputFailure()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!admittedSend) p.notSent.store(failure as? CloudFailure ?: invalidInputFailure())
                ExecutionReceipt.Unknown()
            }
        }
        p.notSent.load()?.let { rejected ->
            // P1 deliberately treats unclassified write-closure failures as possible
            // sends. Our trusted adapter boundary proves this particular send never
            // began, so settle through its existing read-only reconciliation path.
            // The typed error is always surfaced, even if metadata persistence fails.
            try { coordinator.reconcile(executed.id) { Reconciliation.NotApplied } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* retain the conservative persisted state */ }
            throw rejected
        }
        return withContext(storeDispatcher) { store.get(executed.id) } ?: executed
    }

    /** Restored tasks can only be read; no plan, payload or executable confirmation is recreated. */
    suspend fun reconcile(taskId: String): OperationTask {
        val task = withContext(storeDispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        val scope = task.scope as? ZoneScope ?: throw invalidInputFailure()
        if (task.actionKey !in DNS_WRITE_KEYS || task.resourceRef?.scope != scope) throw invalidInputFailure()
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != scope || !resolver.isLive(scope, null)) throw invalidInputFailure()
        ScopeGuard.requireOwned(scope, live.connection)
        if (task.state in setOf(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.ACTION_REQUIRED)) return task
        return coordinator.reconcile(taskId) { current ->
            if (current != task) throw invalidInputFailure()
            val evidence = try {
                val api = session(scope)
                requireSameLive(live)
                val owner = requireDnsZone(api.zone(scope), scope)
                when (task.actionKey) {
                    dnsActionKey(DnsWriteMode.CREATE) -> {
                        val memory = entries.load()[taskId]
                        if (memory != null && (memory.action.mode != DnsWriteMode.CREATE ||
                            memory.prepared.plan.scope != task.scope || memory.prepared.plan.actionKey != task.actionKey ||
                            memory.prepared.plan.resourceRef != task.resourceRef || memory.prepared.plan.requestFingerprint != task.requestFingerprint ||
                            memory.prepared.plan.desiredHash != task.desiredHash)) Reconciliation.StillUnknown
                        else {
                            val (type, name) = creationLocator(task, owner.name)
                            val matches = allCandidates(api, scope, name, type).filter { it.id !in memory?.baseline.orEmpty() }
                                .map { requireDnsDocument(api.get(scope, it.id), scope, it.id) }
                                .filter { digest(dnsSemanticRecord(it.raw)) == task.desiredHash }
                            if (matches.size == 1) Reconciliation.Matched(digest(dnsSemanticRecord(matches.single().raw))) else Reconciliation.StillUnknown
                        }
                    }
                    dnsActionKey(DnsWriteMode.DELETE) -> {
                        val id = existingTarget(task)
                        try { api.get(scope, id); Reconciliation.StillUnknown }
                        catch (failure: CloudFailure) {
                            if (failure.code == CloudFailureCode.NOT_FOUND) Reconciliation.Matched(digest(dnsDeletedState(scope, id))) else throw failure
                        }
                    }
                    else -> {
                        val id = existingTarget(task)
                        val actual = requireDnsDocument(api.get(scope, id), scope, id)
                        Reconciliation.Matched(digest(dnsSemanticRecord(actual.raw)))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Reconciliation.StillUnknown }
            requireSameLive(live)
            evidence
        }
    }

    private fun existingTarget(task: OperationTask): String {
        val target = task.resourceRef ?: throw invalidInputFailure()
        if (target.kind != "dns_record") throw invalidInputFailure()
        return target.externalId
    }
    private fun creationLocator(task: OperationTask, zoneName: String): Pair<String, String> {
        val target = task.resourceRef ?: throw invalidInputFailure()
        if (target.kind != "dns_record_creation" || task.desiredHash == null) throw invalidInputFailure()
        val parts = target.externalId.split(':', limit = 2)
        if (parts.size != 2) throw invalidInputFailure()
        val type = parts[0]
        val name = parts[1]
        // Validate a safe read locator only. There are no record fields here,
        // no live-resolver assignment and no executable creation input/plan.
        val normalized = try { validateDnsInput(zoneName, DnsRecordInput(type, name, JsonObject(emptyMap())), DnsConstraints(), DnsWriteMethod.PATCH) }
            catch (_: CloudException) { throw invalidInputFailure() }
        if (normalized.type != type || normalized.name != name) throw invalidInputFailure()
        return type to name
    }
    private fun entry(id: String) = entries.load()[id] ?: throw invalidInputFailure()
    private fun requireBound(p: Entry, supplied: PreparedDnsChange, confirmed: ConfirmedPlan) {
        if (supplied != p.prepared || confirmed.plan != p.prepared.plan || plans.isExpired(p.prepared.plan)) throw invalidInputFailure()
        requireSameLive(p.live)
    }
    private fun requireCurrent(scope: ZoneScope, request: String): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != scope || live.requestFingerprint != request || !resolver.isLive(scope, live.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(scope, live.connection)
        return live
    }
    private fun requireSameLive(expected: LiveOperationContext) {
        if (resolver.current() != expected || !resolver.isLive(expected.scope, expected.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(expected.scope, expected.connection)
    }
    private suspend fun requireNoUnsettled(target: ResourceRef, excluding: String? = null) {
        val scope = target.scope as ZoneScope
        DnsUncertaintyGate(store,storeDispatcher).requireClear(scope,
            dnsActionKey(if(target.kind == "dns_record_creation") DnsWriteMode.CREATE else DnsWriteMode.PATCH),target,excluding)
    }
    private suspend fun allCandidates(api: DnsManagementSession, action: DnsWriteAction): List<DnsRecordDocument> {
        val input = action.input ?: throw invalidInputFailure()
        return allCandidates(api, action.scope, input.name, input.type)
    }
    private suspend fun allCandidates(api: DnsManagementSession, scope: ZoneScope, name: String, type: String): List<DnsRecordDocument> {
        val filter = DnsFilter(name = name, type = type)
        val result = mutableListOf<DnsRecordDocument>()
        val ids = mutableSetOf<String>()
        var next: PageToken? = null
        var total: Long? = null
        var pages = 0
        do {
            if (++pages > 1000) throw dnsUnsupported("候选记录分页过多，无法完整核对，请在官方控制台检查")
            val page = api.list(scope, filter, PageRequest(50, next))
            val reported = page.total ?: throw dnsInvalidResponse()
            if (total != null && reported != total) throw dnsInvalidResponse()
            total = reported
            for (item in page.items) {
                requireDnsDocument(item, scope, item.id)
                if (!ids.add(item.id)) throw dnsInvalidResponse()
                result += item
            }
            val following = page.next
            if (following != null && (following !is PageToken.Number || following.value <= ((next as? PageToken.Number)?.value ?: 1))) throw dnsInvalidResponse()
            next = following
        } while (next != null)
        if (result.size.toLong() != total) throw dnsInvalidResponse()
        // Never trust provider filter behavior as proof of a matching target.
        return result.filter { it.raw["name"]?.jsonPrimitive?.content?.lowercase()?.removeSuffix(".") == name && it.raw["type"]?.jsonPrimitive?.content == type }
    }
    private fun desired(action: DnsWriteAction, before: DnsRecordDocument?): JsonObject = when (action.mode) {
        DnsWriteMode.DELETE -> dnsDeletedState(action.scope, action.recordId!!)
        DnsWriteMode.CREATE, DnsWriteMode.REPLACE -> dnsSemanticRecord(dnsPayload(action.input!!))
        DnsWriteMode.PATCH -> dnsSemanticRecord(JsonObject(before!!.raw + dnsPayload(action.input!!)))
    }
    private fun digest(value: JsonObject): String = fingerprint.sha256(dnsCanonical(value).toString().encodeToByteArray()).also {
        if (it.length != 64 || it.any { c -> c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F' }) throw invalidInputFailure()
    }
}

internal val DNS_WRITE_KEYS = setOf("dns-records-for-a-zone-create-dns-record", "dns-records-for-a-zone-patch-dns-record", "dns-records-for-a-zone-update-dns-record", "dns-records-for-a-zone-delete-dns-record")
internal fun dnsActionKey(mode: DnsWriteMode): String = when (mode) {
    DnsWriteMode.CREATE -> "dns-records-for-a-zone-create-dns-record"
    DnsWriteMode.PATCH -> "dns-records-for-a-zone-patch-dns-record"
    DnsWriteMode.REPLACE -> "dns-records-for-a-zone-update-dns-record"
    DnsWriteMode.DELETE -> "dns-records-for-a-zone-delete-dns-record"
}
internal fun requireDnsActionShape(id: String?, input: DnsRecordInput?, mode: DnsWriteMode) {
    if ((mode == DnsWriteMode.CREATE) != (id == null) || id?.isBlank() == true || (mode == DnsWriteMode.DELETE) != (input == null)) throw invalidInputFailure()
}
internal fun dnsTarget(action: DnsWriteAction) = ResourceRef(action.scope,
    if (action.mode == DnsWriteMode.CREATE) "dns_record_creation" else "dns_record",
    action.recordId ?: "${action.input!!.type}:${action.input.name}")
internal fun dnsCopy(value: JsonObject) = Json.parseToJsonElement(value.toString()).jsonObject
internal fun dnsPayload(input: DnsRecordInput) = JsonObject(input.fields + mapOf("name" to JsonPrimitive(input.name), "type" to JsonPrimitive(input.type)))
internal fun dnsScopeFields(scope: ZoneScope) = buildJsonObject { put("connection", scope.connectionId); put("account", scope.account.accountId); put("zone", scope.zoneId) }
internal fun dnsDeletedState(scope: ZoneScope, id: String) = buildJsonObject { put("scope", dnsScopeFields(scope)); put("id", id); put("deleted", true) }
internal fun dnsConflict() = CloudFailure(CloudFailureCode.CONFLICT, "DNS 写入状态已变化；尚未发送本次更改，请刷新并重新预览确认")
internal fun dnsUnsupported(message: String) = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, message)
internal fun dnsInvalidResponse() = CloudFailure(CloudFailureCode.INVALID_RESPONSE, "DNS 核对数据不完整或无法识别，请刷新后检查")
internal fun dnsText(raw: JsonObject, key: String): String = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: throw dnsInvalidResponse()
internal fun requireDnsZone(details: ZoneDetails, scope: ZoneScope): ZoneDetails {
    if (details.ref != scope || details.raw["account"] !is JsonObject ||
        dnsText(details.raw["account"]!!.jsonObject, "id") != scope.account.accountId ||
        dnsText(details.raw, "id") != scope.zoneId || dnsText(details.raw, "name") != details.name) throw scopeMismatchFailure()
    return details
}
internal fun requireDnsDocument(document: DnsRecordDocument, scope: ZoneScope, id: String, zoneName: String? = null): DnsRecordDocument {
    if (document.scope != scope || document.id != id || dnsText(document.raw, "id") != id) throw scopeMismatchFailure()
    val name = dnsText(document.raw, "name").lowercase().removeSuffix(".")
    dnsText(document.raw, "type")
    if (zoneName != null) {
        val root = zoneName.lowercase().removeSuffix(".")
        if (name != root && !name.endsWith(".$root")) throw scopeMismatchFailure()
    }
    document.raw["zone_id"]?.let { if (it !is JsonPrimitive || !it.isString || it.content != scope.zoneId) throw scopeMismatchFailure() }
    return document.copy(raw = dnsCopy(document.raw))
}

/** Only generated writable semantics, never timestamps/meta or formatted structured content. */
internal fun dnsSemanticRecord(raw: JsonObject): JsonObject {
    val type = dnsText(raw, "type").uppercase()
    val schema = DnsSchemas.forType(type) ?: throw dnsUnsupported("未来 DNS 类型仅供查看，无法证明写入语义")
    val keys = LocalDnsSchema(schema).properties().keys
    val result = raw.filterKeys { it in keys }.toMutableMap()
    result["type"] = JsonPrimitive(type)
    result["name"] = JsonPrimitive(dnsText(raw, "name").lowercase().removeSuffix("."))
    if ("ttl" in keys) result["ttl"] = raw["ttl"] ?: JsonPrimitive(1)
    if ("proxied" in keys) result["proxied"] = raw["proxied"] ?: JsonPrimitive(false)
    if ("private_routing" in keys) result["private_routing"] = raw["private_routing"] ?: JsonPrimitive(false)
    if ("comment" in keys) result["comment"] = raw["comment"].takeUnless { it == JsonPrimitive("") } ?: JsonNull
    if ("tags" in keys) {
        val tags = raw["tags"] ?: JsonArray(emptyList())
        if (tags !is JsonArray || tags.any { it !is JsonPrimitive || !it.isString }) throw dnsInvalidResponse()
        result["tags"] = JsonArray(tags.map { it.jsonPrimitive.content }.distinct().sorted().map(::JsonPrimitive))
    }
    if ("settings" in keys) {
        val settings = raw["settings"] ?: JsonObject(emptyMap())
        if (settings !is JsonObject) throw dnsInvalidResponse()
        // These generated boolean properties have official false defaults.
        result["settings"] = JsonObject(settings.filterNot { (key, value) -> key in setOf("ipv4_only", "ipv6_only", "flatten_cname") && value == JsonPrimitive(false) })
    }
    if (type in setOf("CNAME", "MX", "NS", "PTR")) (result["content"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
        result["content"] = JsonPrimitive(it.content.lowercase().removeSuffix("."))
    }
    if (type == "A" && !isDnsIpv4(dnsText(raw, "content"))) throw dnsInvalidResponse()
    if (type == "AAAA") result["content"] = JsonPrimitive(dnsIpv6Groups(dnsText(raw, "content")))
    if (type in setOf("SRV", "HTTPS", "SVCB")) (raw["data"] as? JsonObject)?.let { data ->
        data["target"]?.let { value ->
            val target = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw dnsInvalidResponse()
            if (!isDnsHostname(target, allowRoot = true)) throw dnsInvalidResponse()
            val canonical = if (target == ".") target else target.lowercase().removeSuffix(".")
            result["data"] = JsonObject(data + ("target" to JsonPrimitive(canonical)))
        }
    }
    return dnsCanonical(JsonObject(result)).jsonObject
}

/** Eight exact 16-bit groups, after proving the fixed validator accepts the address.
 * No resolver, platform networking API, text heuristic or payload rewrite is involved.
 */
private fun dnsIpv6Groups(value: String): String {
    if (!isDnsIpv6(value)) throw dnsInvalidResponse()
    var text = value.lowercase()
    if ('.' in text) {
        val octets = text.substringAfterLast(':').split('.').map { it.toInt() }
        val high = ((octets[0] shl 8) or octets[1]).toString(16)
        val low = ((octets[2] shl 8) or octets[3]).toString(16)
        text = text.substringBeforeLast(':') + ":$high:$low"
    }
    val halves = text.split("::")
    fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':').map { it.toInt(16) }
    val left = groups(halves[0])
    val expanded = if (halves.size == 1) left else {
        val right = groups(halves[1])
        left + List(8 - left.size - right.size) { 0 } + right
    }
    if (expanded.size != 8) throw dnsInvalidResponse()
    return expanded.joinToString(":") { it.toString(16).padStart(4, '0') }
}
internal fun dnsCanonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to dnsCanonical(it.value) })
    is JsonArray -> JsonArray(value.map(::dnsCanonical))
    is JsonPrimitive -> if (!value.isString && value.doubleOrNull?.isFinite() == true && value.double % 1.0 == 0.0 && value.double >= Long.MIN_VALUE.toDouble() && value.double < Long.MAX_VALUE.toDouble()) JsonPrimitive(value.double.toLong()) else value
}

private val DNS_READONLY_FIELDS = setOf("id", "proxiable", "created_on", "modified_on", "comment_modified_on", "tags_modified_on", "meta")
internal fun dnsWriteInput(zoneName: String, before: DnsRecordDocument?, input: DnsRecordInput, mode: DnsWriteMode, constraints: DnsConstraints, allowNoop: Boolean = false): DnsRecordInput {
    val method = when (mode) { DnsWriteMode.CREATE -> DnsWriteMethod.POST; DnsWriteMode.PATCH -> DnsWriteMethod.PATCH; DnsWriteMode.REPLACE -> DnsWriteMethod.PUT; else -> throw invalidInputFailure() }
    val type = input.type.trim().uppercase()
    val schema = DnsSchemas.forType(type, method) ?: throw dnsUnsupported("该 DNS 类型仅供查看")
    val local = LocalDnsSchema(schema)
    val properties = local.properties()
    if (mode == DnsWriteMode.REPLACE && before != null) {
        val oldSchema = DnsSchemas.forType(dnsText(before.raw, "type")) ?: throw dnsUnsupported("无法安全替换未来 DNS 类型")
        val oldLocal = LocalDnsSchema(oldSchema)
        val oldProperties = oldLocal.properties()
        val readonly = DNS_READONLY_FIELDS + if ("data" in oldProperties) setOf("content") else emptySet()
        if ((before.raw.keys - oldProperties.keys - readonly).isNotEmpty()) throw dnsUnsupported("存在未知字段，无法证明 PUT 完整替换不会丢失数据")
        for (key in listOf("data", "settings")) (before.raw[key] as? JsonObject)?.let { requireKnownNested(oldLocal, oldProperties, key, it) }
    }
    var fields = dnsCopy(input.fields)
    if (mode == DnsWriteMode.PATCH && before != null) {
        if (type != dnsText(before.raw, "type")) throw dnsUnsupported("修改记录类型需使用单独确认的完整替换")
        val merged = fields.toMutableMap()
        for (key in listOf("data", "settings")) if (key in fields) {
            val update = fields[key] as? JsonObject ?: throw invalidInputFailure()
            val current = before.raw[key]?.let { it as? JsonObject ?: throw dnsUnsupported("嵌套字段结构未知，无法安全编辑") } ?: JsonObject(emptyMap())
            requireKnownNested(local, properties, key, current)
            requireKnownNested(local, properties, key, update)
            merged[key] = JsonObject(current + update)
        }
        val proxy = merged["proxied"] ?: before.raw["proxied"]
        if (proxy == JsonPrimitive(true) && "ttl" in merged) merged["ttl"] = JsonPrimitive(1)
        fields = JsonObject(merged)
    }
    val normalized = try { validateDnsInput(zoneName, input.copy(fields = fields), constraints, method) }
        catch (_: CloudException) { throw CloudFailure(CloudFailureCode.INVALID_INPUT, "DNS 输入不符合当前类型或套餐约束，请检查字段后重新预览") }
    if (mode != DnsWriteMode.PATCH || before == null) return normalized
    val previous = dnsSemanticRecord(before.raw)
    val desired = dnsSemanticRecord(JsonObject(before.raw + dnsPayload(normalized)))
    val changed = normalized.fields.filter { (key, _) -> desired[key] != previous[key] }
    if (!allowNoop && changed.isEmpty() && desired["name"] == previous["name"]) throw CloudFailure(CloudFailureCode.INVALID_INPUT, "DNS 记录没有明确变更，请修改输入后再预览")
    return normalized.copy(fields = JsonObject(changed))
}
private fun requireKnownNested(local: LocalDnsSchema, properties: Map<String, List<JsonObject>>, key: String, value: JsonObject) {
    val definitions = properties[key] ?: throw dnsUnsupported("该类型不支持此嵌套字段")
    val known = definitions.flatMap { local.properties(it).keys }.toSet()
    if ((value.keys - known).isNotEmpty() || value.values.any { it is JsonObject || it is JsonArray }) throw dnsUnsupported("嵌套字段包含未知内容，无法安全合并；请使用官方控制台")
}
