package com.flarego.core.application.dns

import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.dns.t7SettingsChecked
import com.flarego.core.cloudflare.dns.t7SettingsWritable
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.*

/** Same application issuer/coordinator/store; in-memory inputs and risk identities are never restored. */
@OptIn(ExperimentalAtomicApi::class)
class DnsZoneSettingsController(private val resolver: OperationContextResolver, private val plans: ChangePlans,
    private val coordinator: OperationCoordinator, private val clock: OperationClock, private val fingerprint: FingerprintPort,
    private val store: OperationStore, private val dispatcher: CoroutineDispatcher, private val session: suspend (ZoneScope) -> DnsZoneSettingsSession) {
    private data class Entry(val plan:ChangePlan,val action:DnsZoneSettingsAction,val live:LiveOperationContext,
        val before:DnsZoneSettingsSnapshot,val projection:DnsSettingsProjection,val preview:DnsZoneSettingsPreview,
        val notSent:AtomicReference<CloudFailure?> = AtomicReference(null))
    private val entries=AtomicReference<Map<String,Entry>>(emptyMap())
    private val acknowledgements=AtomicReference<Map<String,Map<DnsSettingsRisk,DnsSettingsRiskAcknowledgement>>>(emptyMap())
    private val gate=DnsUncertaintyGate(store,dispatcher)
    fun project(changed:JsonObject):DnsSettingsProjection=projectDnsZoneSettings(changed)
    fun requestFingerprint(action:DnsZoneSettingsAction):String {
        t7Scope(action.scope);project(action.changed)
        return digest(buildJsonObject { put("scope",dnsScopeFields(action.scope));put("action",DNS_SETTINGS_PATCH_OPERATION);put("input",action.changed) })
    }
    suspend fun prepare(action:DnsZoneSettingsAction):ChangePlan {
        val frozen=action.copy(changed=dnsCopy(action.changed));val live=resolver.current() ?: throw invalidInputFailure()
        if(live.scope!=frozen.scope || live.requestFingerprint!=requestFingerprint(frozen)) throw invalidInputFailure()
        bound(live);clear(frozen.scope,frozen.changed.keys);bound(live)
        val api=session(frozen.scope);bound(live)
        val before=t7SettingsChecked(api.observe(frozen),frozen.scope)
        val view=dnsZoneSettingsView(before.settings);val delta=dnsZoneSettingsDelta(view,frozen.changed)
        t7SettingsWritable(before,delta);val after=dnsZoneSettingsAfter(view,delta)
        bound(live);clear(frozen.scope,delta.changed.keys);bound(live)
        val p=plans.prepare(frozen.scope,DNS_SETTINGS_PATCH_OPERATION,ref(frozen.scope,delta.changed.keys),beforeHash(before,delta.changed.keys),
            desiredHash(frozen.scope,after,delta.changed.keys),requestFingerprint(frozen),live.credentialRevision,clock.nowMs())
        val preview=DnsZoneSettingsPreview(frozen.scope,before.owner.name,delta,t7SettingRisks(delta.changed),listOf(
            "更改nameservers或zone_mode可能影响解析、代理及服务可用性；注册商配置与外部状态未探测",
            "预提交回读没有服务端CAS；匹配只证明当前供应商设置，不证明历史执行者或全球传播"),view,after)
        val entry=Entry(p,frozen,live,before,delta,preview)
        while(true) { val old=entries.load();if(entries.compareAndSet(old,old+(p.id to entry))) break }
        return p
    }
    fun preview(id:String):DnsZoneSettingsPreview=entry(id).preview.let { p -> p.copy(projection=project(p.projection.changed),
        risks=p.risks.toSet(),warnings=p.warnings.toList(),before=p.before?.let(::copyView),after=p.after?.let(::copyView)) }
    /** Trusted app calls once per explicit displayed user choice; pure UI only emits an acknowledgement enum. */
    fun acknowledgeRisk(planId:String,risk:DnsSettingsRisk):DnsSettingsRiskAcknowledgement {
        val p=entry(planId);preparedLive(p)
        if(risk !in p.preview.risks) throw invalidInputFailure()
        while(true) {
            val old=acknowledgements.load();val issued=old[planId].orEmpty();issued[risk]?.let { return it }
            val token=DnsSettingsRiskAcknowledgement(planId,risk)
            if(acknowledgements.compareAndSet(old,old+(planId to (issued+(risk to token))))) return token
        }
    }
    suspend fun submit(action:DnsZoneSettingsAction,confirmed:ConfirmedPlan,risks:Set<DnsSettingsRiskAcknowledgement> = emptySet()):OperationTask {
        val p=entry(confirmed.plan.id);val tokens=risks.toSet()
        check(p,action,confirmed,tokens);clear(action.scope,p.projection.changed.keys,p.plan.id);check(p,action,confirmed,tokens)
        val guard={check(p,action,confirmed,tokens)}
        val result=coordinator.executeGuarded(confirmed,guard) { composed ->
            var admitted=false
            try {
                val api=session(action.scope);guard();clear(action.scope,p.projection.changed.keys,p.plan.id);guard()
                val receipt=api.write(p.action,{ snapshot ->
                    val actual=t7SettingsChecked(snapshot,action.scope)
                    if(beforeHash(actual,p.projection.changed.keys)!=p.plan.beforeHash) throw dnsConflict()
                    val projection=dnsZoneSettingsDelta(dnsZoneSettingsView(actual.settings),p.action.changed)
                    t7SettingsWritable(actual,projection)
                    if(projection.changed!=p.projection.changed) throw dnsConflict()
                    composed();admitted=true
                },guard)
                if(!admitted) throw invalidInputFailure()
                receipt
            } catch(cancel:CancellationException) { throw cancel }
            catch(failure:Exception) { if(!admitted && failure is CloudFailure) p.notSent.store(failure);ExecutionReceipt.Unknown() }
        }
        p.notSent.load()?.let { error ->
            try { coordinator.reconcile(result.id) { Reconciliation.NotApplied } }
            catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { }
            throw error
        }
        return withContext(dispatcher) { store.get(result.id) } ?: result
    }
    /** Persisted finite key locator and independently owned reads only; never an executable payload. */
    suspend fun reconcile(taskId:String):OperationTask {
        val initial=withContext(dispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        val live=resolver.current() ?: throw invalidInputFailure();val scope=initial.scope as? ZoneScope ?: throw invalidInputFailure()
        if(live.scope!=scope || initial.actionKey!=DNS_SETTINGS_PATCH_OPERATION) throw invalidInputFailure()
        bound(live)
        if(initial.state==TaskState.SUCCEEDED && targetKeys(initial)!=null) return initial
        return coordinator.reconcile(taskId) { current ->
            bound(live)
            val keys=targetKeys(current) ?: return@reconcile Reconciliation.StillUnknown
            val observed=try {
                val api=session(scope);bound(live)
                val actual=api.settings(scope)
                if(actual.scope!=scope) throw scopeMismatchFailure()
                desiredHash(scope,dnsZoneSettingsView(actual),keys)
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { null }
            bound(live)
            if(observed!=null && observed==current.desiredHash) Reconciliation.Matched(observed) else Reconciliation.StillUnknown
        }
    }
    private fun targetKeys(task:OperationTask):Set<String>? {
        val scope=task.scope as? ZoneScope ?: return null;val reference=task.resourceRef ?: return null
        if(task.actionKey!=DNS_SETTINGS_PATCH_OPERATION || reference.scope!=scope || reference.kind!="dns_settings_v1" || task.desiredHash==null) return null
        val keys=reference.externalId.split(',')
        if(keys.isEmpty() || keys.distinct().size!=keys.size || keys.any { it !in t7SettingsKeys } || keys!=keys.sorted()) return null
        return keys.toSet()
    }
    private suspend fun clear(scope:ZoneScope,keys:Set<String>,excluding:String?=null)=gate.requireClear(scope,DNS_SETTINGS_PATCH_OPERATION,ref(scope,keys),excluding)
    private fun entry(id:String)=entries.load()[id] ?: throw invalidInputFailure()
    private fun preparedLive(p:Entry) { bound(p.live);if(plans.isExpired(p.plan)) throw invalidInputFailure() }
    private fun check(p:Entry,action:DnsZoneSettingsAction,confirmed:ConfirmedPlan,tokens:Set<DnsSettingsRiskAcknowledgement>) {
        if(confirmed.plan!=p.plan || action!=p.action || p.plan.scope!=action.scope || p.plan.actionKey!=DNS_SETTINGS_PATCH_OPERATION ||
            p.plan.resourceRef!=ref(action.scope,p.projection.changed.keys) || p.plan.beforeHash!=beforeHash(p.before,p.projection.changed.keys) ||
            p.plan.desiredHash!=desiredHash(action.scope,p.preview.after ?: throw invalidInputFailure(),p.projection.changed.keys) ||
            p.plan.requestFingerprint!=requestFingerprint(action) || p.plan.credentialRevision!=p.live.credentialRevision) throw invalidInputFailure()
        preparedLive(p)
        if(tokens.size!=p.preview.risks.size || tokens.map { it.risk }.toSet()!=p.preview.risks || tokens.any {
            it.planId!=p.plan.id || acknowledgements.load()[p.plan.id]?.get(it.risk) !== it
        }) throw CloudFailure(CloudFailureCode.INVALID_INPUT,"请分别确认当前DNS设置计划的每项风险")
    }
    private fun bound(live:LiveOperationContext) {
        if(resolver.current()!=live || !resolver.isLive(live.scope,live.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(live.scope,live.connection)
    }
    private fun ref(scope:ZoneScope,keys:Set<String>)=t7SettingsReference(scope,keys)
    private fun desiredHash(scope:ZoneScope,view:DnsSettingsReadView,keys:Set<String>):String {
        if(view.scope!=scope || keys.any { key -> view.unknownReasons.keys.any { it==key || it.startsWith("$key.") } }) throw t7ReviewSetting()
        val known=t7ViewJson(view)
        return digest(buildJsonObject { put("scope",dnsScopeFields(scope));put("action",DNS_SETTINGS_PATCH_OPERATION)
            put("values",t7Semantic(JsonObject(keys.associateWith { known[it] ?: throw t7ReviewSetting() }))) })
    }
    private fun beforeHash(before:DnsZoneSettingsSnapshot,keys:Set<String>):String=digest(buildJsonObject {
        put("owner",before.owner.raw);put("settings",t7Semantic(JsonObject(keys.associateWith { before.settings.raw[it] ?: throw t7ReviewSetting() })))
        put("write_allowed",before.writeAllowed?.let(::JsonPrimitive) ?: JsonNull)
        val q=before.eligibility
        fun names(set:Set<String>)=JsonArray(set.sorted().map(::JsonPrimitive))
        put("fields",names(q.fields.map { it.wire }.toSet()));put("nested",names(q.nestedPatchSemanticsVerified.map { it.wire }.toSet()))
        put("nameserver_types",names(q.nameserverTypes));put("custom_sets",names(q.ownedCustomSetIds));put("references",names(q.verifiedReferenceZoneIds))
        put("existing_sets",JsonObject(q.existingNameserverSets.mapValues { JsonArray(it.value.sorted().map(::JsonPrimitive)) }))
    })
    private fun copyView(view:DnsSettingsReadView)=view.copy(unknownReasons=view.unknownReasons.toMap())
    private fun digest(value:JsonElement)=fingerprint.sha256(CanonicalJson.encode(value))
}

/** Known display fields only. null reference means missing/invalid, JsonNull mname is explicit provider allocation. */
data class DnsSettingsReadView(val scope: ZoneScope, val flattenAllCnames: Boolean?, val multiProvider: Boolean?,
    val secondaryOverrides: Boolean?, val nsTtl: kotlinx.serialization.json.JsonPrimitive?, val zoneMode: String?,
    val nameservers: DnsNameservers?, val soa: DnsSoaPatch?, val internalDns: DnsInternalDnsPatch?,
    val foundationDns: Boolean?, val unknownReasons: Map<String,String>)
/** Pure schema/product-shape validation; never permission/qualification/confirmation authority. */
fun projectDnsZoneSettings(changed: JsonObject): DnsSettingsProjection = t7Project(changed)
fun dnsZoneSettingsSemanticallyEqual(left: JsonElement, right: JsonElement): Boolean = try { t7Same(left,right) } catch (_:Exception) { false }
fun dnsZoneSettingsView(settings: DnsZoneSettings): DnsSettingsReadView {
    val raw=settings.raw;val reasons=mutableMapOf<String,String>()
    fun missing(key:String) { reasons[key]="供应商未提供该字段，不能推测默认值" }
    fun value(key:String):JsonElement?=raw[key].also { if(it==null) missing(key) }
    fun validated(key:String):DnsSettingsProjection? {
        val v=value(key) ?: return null
        return try { t7Project(buildJsonObject { put(key,v) }) } catch (_:Exception) { reasons[key]="字段未知或无法安全识别，仅供查看";null }
    }
    fun boolean(key:String):Boolean? = validated(key)?.changed?.get(key)?.jsonPrimitive?.booleanOrNull
    val flatten=boolean("flatten_all_cnames");val multi=boolean("multi_provider");val secondary=boolean("secondary_overrides")
    val ttl=validated("ns_ttl")?.changed?.get("ns_ttl") as? JsonPrimitive
    val mode=validated("zone_mode")?.changed?.get("zone_mode")?.jsonPrimitive?.content
    val ns=validated("nameservers")?.nameservers
    fun nested(key:String,known:Set<String>):JsonObject? {
        val v=value(key) ?: return null
        if(v !is JsonObject) { reasons[key]="嵌套字段无法识别，仅供查看";return null }
        val safe=mutableMapOf<String,JsonElement>()
        for((field,entry) in v) {
            if(field !in known) { reasons["$key.$field"]="未知嵌套字段必须保留，只读";continue }
            try { t7Project(buildJsonObject { put(key,buildJsonObject { put(field,entry) }) });safe[field]=entry }
            catch (_:Exception) { reasons["$key.$field"]="嵌套值无法安全识别，只读" }
        }
        for(field in known-v.keys) missing("$key.$field")
        return JsonObject(safe)
    }
    val soaRaw=nested("soa",t7SoaKeys)
    val soa=soaRaw?.takeIf { it.isNotEmpty() }?.let { t7Project(buildJsonObject { put("soa",it) }).soa }
    val internalRaw=nested("internal_dns",setOf("reference_zone_id"))
    val internal=internalRaw?.takeIf { it.isNotEmpty() }?.let { t7Project(buildJsonObject { put("internal_dns",it) }).internalDns }
    val foundation=(raw["foundation_dns"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    reasons["foundation_dns"]="已弃用，仅供查看；不能写入"
    for(key in raw.keys-t7SettingsKeys-setOf("foundation_dns","zone_id")) reasons[key]="未知字段仅供查看，不参与写入"
    return DnsSettingsReadView(settings.scope,flatten,multi,secondary,ttl,mode,ns,soa,internal,foundation,reasons.toMap())
}
internal fun t7ViewJson(view: DnsSettingsReadView): JsonObject=buildJsonObject {
    view.flattenAllCnames?.let { put("flatten_all_cnames",it) };view.multiProvider?.let { put("multi_provider",it) }
    view.secondaryOverrides?.let { put("secondary_overrides",it) };view.nsTtl?.let { put("ns_ttl",it) }
    view.zoneMode?.let { put("zone_mode",it) };view.nameservers?.let { put("nameservers",t7NameserverJson(it)) }
    view.soa?.let { put("soa",t7SoaJson(it)) };view.internalDns?.let { put("internal_dns",buildJsonObject { put("reference_zone_id",it.referenceZoneId) }) }
    view.foundationDns?.let { put("foundation_dns",it) }
}
fun dnsZoneSettingsDelta(before: DnsSettingsReadView, changed: JsonObject): DnsSettingsProjection {
    val actual=projectDnsZoneSettings(changed);val known=t7ViewJson(before);val delta=mutableMapOf<String,JsonElement>()
    for((key,value) in actual.changed) {
        if(before.unknownReasons.keys.any { it==key || it.startsWith("$key.") }) throw t7ReviewSetting()
        val prior=known[key] ?: throw t7ReviewSetting()
        if(key in setOf("soa","internal_dns")) {
            val old=prior as? JsonObject ?: throw t7ReviewSetting()
            val leaves=value.jsonObject.filter { (field,target) -> old[field]?.let { !t7Same(it,target) } ?: true }
            if(leaves.isNotEmpty()) delta[key]=JsonObject(leaves)
        } else if(!t7Same(prior,value)) delta[key]=value
    }
    if(delta.isEmpty()) throw CloudFailure(CloudFailureCode.INVALID_INPUT,"没有实际DNS设置变化，请修改后再预览")
    return projectDnsZoneSettings(JsonObject(delta))
}
private fun projectionMatches(a: DnsSettingsProjection, b: DnsSettingsProjection): Boolean =
    t7Same(a.changed,b.changed) && a.nameservers==b.nameservers && a.internalDns==b.internalDns &&
        ((a.soa==null && b.soa==null) || (a.soa!=null && b.soa!=null && t7Same(t7SoaJson(a.soa),t7SoaJson(b.soa))))
fun dnsZoneSettingsAfter(before: DnsSettingsReadView, projection: DnsSettingsProjection): DnsSettingsReadView {
    val parsed=projectDnsZoneSettings(projection.changed)
    if(!projectionMatches(parsed,projection)) throw t7BadSetting()
    val known=t7ViewJson(before).toMutableMap()
    for((key,value) in parsed.changed) known[key]=if(key in setOf("soa","internal_dns")) JsonObject((known[key] as? JsonObject).orEmpty()+value.jsonObject) else value
    val after=dnsZoneSettingsView(DnsZoneSettings(before.scope,JsonObject(known)))
    return after.copy(unknownReasons=after.unknownReasons+before.unknownReasons)
}
internal fun t7SettingRisks(changed: JsonObject)=buildSet {
    if("nameservers" in changed) add(DnsSettingsRisk.CHANGE_NAMESERVERS)
    if("zone_mode" in changed) add(DnsSettingsRisk.CHANGE_ZONE_MODE)
}
private fun viewMatches(a: DnsSettingsReadView,b: DnsSettingsReadView)=a.scope==b.scope && a.unknownReasons==b.unknownReasons && t7Same(t7ViewJson(a),t7ViewJson(b))
fun dnsZoneSettingsPreviewMatches(action: DnsZoneSettingsAction, preview: DnsZoneSettingsPreview,
    actualBefore: DnsSettingsReadView, zoneName: String): Boolean = try {
    val delta=dnsZoneSettingsDelta(actualBefore,action.changed)
    preview.scope==action.scope && actualBefore.scope==action.scope &&
        preview.domain.lowercase().removeSuffix(".")==zoneName.lowercase().removeSuffix(".") &&
        projectionMatches(delta,preview.projection) && preview.risks==t7SettingRisks(delta.changed) &&
        preview.before?.let { viewMatches(it,actualBefore) }==true &&
        preview.after?.let { viewMatches(it,dnsZoneSettingsAfter(actualBefore,delta)) }==true
} catch (_:Exception) { false }

/** Exact finite locator only; validation is neither write permission nor an executable plan. */
fun dnsZoneSettingsResourceRef(action:DnsZoneSettingsAction,before:DnsSettingsReadView):ResourceRef {
    if(before.scope!=action.scope) throw scopeMismatchFailure()
    return t7SettingsReference(action.scope,dnsZoneSettingsDelta(before,action.changed).changed.keys)
}
internal fun t7SettingsReference(scope:ZoneScope,keys:Set<String>):ResourceRef {
    t7Scope(scope)
    if(keys.isEmpty() || (keys-t7SettingsKeys).isNotEmpty()) throw t7BadSetting()
    return ResourceRef(scope,"dns_settings_v1",keys.sorted().joinToString(","))
}
