package com.flarego.core.application.dns

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.*

@OptIn(ExperimentalAtomicApi::class)
class DnsScanController(private val resolver: OperationContextResolver, private val plans: ChangePlans,
    private val coordinator: OperationCoordinator, private val clock: OperationClock, private val fingerprint: FingerprintPort,
    private val store: OperationStore, private val dispatcher: CoroutineDispatcher, private val session: suspend (ZoneScope) -> DnsScanSession) {
    private data class Entry(val plan:ChangePlan,val action:DnsScanAction,val live:LiveOperationContext,
        val before:DnsScanSnapshot,val preview:DnsScanPreview,val notSent:AtomicReference<CloudFailure?> = AtomicReference(null))
    private val entries=AtomicReference<Map<String,Entry>>(emptyMap())
    private val gate=DnsUncertaintyGate(store,dispatcher)
    fun requestFingerprint(action: DnsScanAction): String {
        t7Scope(action.scope)
        return digest(buildJsonObject {
            put("scope",dnsScopeFields(action.scope));put("action",t7Key(action))
            if(action is DnsScanAction.Apply) {
                t7Selection(action.input)
                put("accepts",JsonArray(action.input.acceptIds.map(::JsonPrimitive)))
                put("rejects",JsonArray(action.input.rejectIds.map(::JsonPrimitive)))
                put("hashes",JsonObject(action.input.expectedCandidateHashes.mapValues { JsonPrimitive(it.value) }))
            }
        })
    }
    suspend fun prepare(action: DnsScanAction): ChangePlan {
        val frozen=t7Freeze(action);val live=resolver.current() ?: throw invalidInputFailure()
        if(live.scope!=frozen.scope || live.requestFingerprint!=requestFingerprint(frozen)) throw invalidInputFailure()
        bound(live);clear(frozen);bound(live)
        val api=session(frozen.scope);bound(live)
        val before=t7Checked(api.observe(frozen),frozen.scope,fingerprint);t7Writable(before.writeAllowed)
        if(frozen is DnsScanAction.Apply) t7ApplyPayload(before,frozen.input,before.constraints)
        bound(live);clear(frozen);bound(live)
        val plan=plans.prepare(frozen.scope,t7Key(frozen),ref(frozen),beforeHash(frozen,before),
            if(frozen is DnsScanAction.Apply) requestFingerprint(frozen) else null,requestFingerprint(frozen),live.credentialRevision,clock.nowMs())
        val input=(frozen as? DnsScanAction.Apply)?.input
        val selected=input?.let { it.acceptIds+it.rejectIds }.orEmpty()
        val preview=DnsScanPreview(frozen.scope,before.owner.name,input?.acceptIds.orEmpty(),input?.rejectIds.orEmpty(),
            selected.map { id -> before.candidates.single { it.id==id }.record },
            listOf("扫描仅受理，不证明完成；请主动刷新临时候选", "审核无服务端CAS或已证明的batch全事务语义；结果未知时只读核对，不能重发"),input?.expectedCandidateHashes.orEmpty())
        val entry=Entry(plan,frozen,live,before,preview)
        while(true) { val old=entries.load();if(entries.compareAndSet(old,old+(plan.id to entry))) break }
        return plan
    }
    fun preview(id: String): DnsScanPreview = entry(id).preview.let { p -> p.copy(acceptIds=p.acceptIds.toList(),rejectIds=p.rejectIds.toList(),
        records=p.records.map { it.copy(raw=dnsCopy(it.raw)) },warnings=p.warnings.toList(),candidateHashes=p.candidateHashes.toMap()) }
    suspend fun submit(action: DnsScanAction, confirmed: ConfirmedPlan): OperationTask {
        val p=entry(confirmed.plan.id);check(p,action,confirmed);clear(action,p.plan.id);check(p,action,confirmed)
        val guard={check(p,action,confirmed)}
        val result=coordinator.executeGuarded(confirmed,guard) { composed ->
            var admitted=false
            try {
                val api=session(action.scope);guard();clear(action,p.plan.id);guard()
                val receipt=api.write(p.action,{ snapshot ->
                    val actual=t7Checked(snapshot,action.scope,fingerprint);t7Writable(actual.writeAllowed)
                    if(beforeHash(p.action,actual)!=p.plan.beforeHash) throw dnsConflict()
                    if(p.action is DnsScanAction.Apply) t7ApplyPayload(actual,p.action.input,actual.constraints)
                    composed();admitted=true
                },guard)
                if(!admitted) throw invalidInputFailure()
                receipt
            } catch (cancel:CancellationException) { throw cancel }
            catch (failure:Exception) { if(!admitted && failure is CloudFailure) p.notSent.store(failure);ExecutionReceipt.Unknown() }
        }
        p.notSent.load()?.let { error ->
            try { coordinator.reconcile(result.id) { Reconciliation.NotApplied } }
            catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { }
            throw error
        }
        return withContext(dispatcher) { store.get(result.id) } ?: result
    }
    suspend fun reconcile(taskId: String): OperationTask {
        val task=withContext(dispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        val live=resolver.current() ?: throw invalidInputFailure();val scope=task.scope as? ZoneScope ?: throw invalidInputFailure()
        if(live.scope!=scope || task.actionKey !in setOf(DNS_SCAN_TRIGGER_OPERATION,DNS_SCAN_APPLY_OPERATION)) throw invalidInputFailure()
        bound(live)
        if(task.state==TaskState.SUCCEEDED) return task
        suspend fun read() {
            val expected=ResourceRef(scope,if(task.actionKey==DNS_SCAN_TRIGGER_OPERATION) "dns_scan_trigger" else "dns_scan_review",scope.zoneId)
            if(task.resourceRef!=expected) return
            try { val api=session(scope);bound(live);api.review(scope) }
            catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { }
            bound(live)
        }
        if(task.state in setOf(TaskState.ACCEPTED,TaskState.PARTIAL)) {
            read();if(withContext(dispatcher) { store.get(task.id) }!=task) throw invalidInputFailure();bound(live);return task
        }
        return coordinator.reconcile(task.id) { read();bound(live);Reconciliation.StillUnknown }
    }
    private suspend fun clear(action:DnsScanAction,excluding:String?=null) {
        if(action is DnsScanAction.Apply) gate.requireClear(action.scope,DNS_SCAN_APPLY_OPERATION,ref(action),excluding)
        else if(withContext(dispatcher) { store.list(action.scope.connectionId) }.any { it.id!=excluding && it.scope==action.scope &&
                it.actionKey==DNS_SCAN_TRIGGER_OPERATION && it.state in DNS_MUTATION_UNSETTLED && it.state!=TaskState.ACCEPTED })
            throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"旧扫描请求结果未知，请先只读核对；不会自动重新触发",uncertain=true)
    }
    private fun entry(id:String)=entries.load()[id] ?: throw invalidInputFailure()
    private fun bound(live:LiveOperationContext) {
        if(resolver.current()!=live || !resolver.isLive(live.scope,live.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(live.scope,live.connection)
    }
    private fun check(p:Entry,action:DnsScanAction,confirmed:ConfirmedPlan) {
        if(confirmed.plan!=p.plan || t7Freeze(action)!=p.action || p.plan.scope!=action.scope || p.plan.actionKey!=t7Key(action) ||
            p.plan.resourceRef!=ref(action) || p.plan.beforeHash!=beforeHash(p.action,p.before) ||
            p.plan.requestFingerprint!=requestFingerprint(action) || p.plan.credentialRevision!=p.live.credentialRevision || plans.isExpired(p.plan)) throw invalidInputFailure()
        bound(p.live)
    }
    private fun ref(action:DnsScanAction)=ResourceRef(action.scope,if(action is DnsScanAction.Trigger) "dns_scan_trigger" else "dns_scan_review",action.scope.zoneId)
    private fun beforeHash(action:DnsScanAction,snapshot:DnsScanSnapshot)=digest(buildJsonObject {
        put("owner",snapshot.owner.raw);put("write_allowed",snapshot.writeAllowed?.let(::JsonPrimitive) ?: JsonNull)
        put("min_ttl",snapshot.constraints.minTtl);put("max_ttl",snapshot.constraints.maxTtl);put("proxy_types",JsonArray(snapshot.constraints.proxyTypes.sorted().map(::JsonPrimitive)))
        if(action is DnsScanAction.Apply) put("candidates",JsonObject((action.input.acceptIds+action.input.rejectIds).associateWith { id ->
            snapshot.candidates.singleOrNull { it.id==id }?.let { JsonPrimitive(it.selectionHash) } ?: throw dnsConflict()
        }))
    })
    private fun digest(value:JsonElement)=fingerprint.sha256(CanonicalJson.encode(value))
}

/** Read-only display projection. No ownership, permission, confirmation or send authority is issued. */
data class DnsScanCandidateView(val scope: ZoneScope, val id: String, val selectionHash: String,
    val view: DnsRecordView, val acceptInput: DnsRecordInput?, val acceptUnavailableReason: String?)
fun dnsScanRecordView(document: DnsRecordDocument): DnsRecordView {
    val checked=requireDnsDocument(document,document.scope,t7Id(document.id))
    return dnsRecordView(checked).let { it.copy(fields=dnsCopy(it.fields),unknownFields=it.unknownFields.toSet()) }
}
fun dnsScanCandidateView(candidate: DnsScanCandidate, zoneName: String, constraints: DnsConstraints = DnsConstraints()): DnsScanCandidateView {
    if(candidate.id!=candidate.record.id || !Regex("[0-9a-f]{64}").matches(candidate.selectionHash)) throw invalidInputFailure()
    val view=dnsScanRecordView(candidate.record)
    var reason:String?=null
    val accepted=try {
        val wire=t7Accept(candidate.record,zoneName,constraints)
        DnsRecordInput(dnsText(wire,"type"),dnsText(wire,"name"),JsonObject(wire-setOf("name","type")))
    } catch (failure:CloudFailure) { reason=failure.publicMessage;null }
    return DnsScanCandidateView(candidate.record.scope,candidate.id,candidate.selectionHash,view,accepted,reason)
}
/** Display binding only. Candidate hash must originate in the trusted fresh review adapter. */
fun dnsScanPreviewMatches(action: DnsScanAction, preview: DnsScanPreview, candidates: List<DnsScanCandidateView>, zoneName: String): Boolean = try {
    if(preview.scope!=action.scope || preview.domain.lowercase().removeSuffix(".")!=zoneName.lowercase().removeSuffix(".")) false
    else if(action is DnsScanAction.Trigger) preview.acceptIds.isEmpty() && preview.rejectIds.isEmpty() && preview.records.isEmpty() && preview.candidateHashes.isEmpty()
    else {
        val input=(action as DnsScanAction.Apply).input;t7Selection(input)
        val ids=input.acceptIds+input.rejectIds
        val fresh=candidates.associateBy { it.id };val frozen=preview.records.associateBy { it.id }
        preview.acceptIds==input.acceptIds && preview.rejectIds==input.rejectIds &&
            preview.candidateHashes==input.expectedCandidateHashes && candidates.size==fresh.size &&
            preview.records.size==frozen.size && frozen.keys==ids.toSet() && ids.all { id ->
                val row=fresh[id];val doc=frozen[id]
                row!=null && doc!=null && row.scope==action.scope && doc.scope==action.scope && row.view.id==id &&
                    row.selectionHash==input.expectedCandidateHashes[id] && row.view==dnsScanRecordView(doc) &&
                    (id !in input.acceptIds || (row.acceptInput!=null && row.acceptUnavailableReason==null &&
                        dnsSemanticRecord(dnsPayload(row.acceptInput))==dnsSemanticRecord(doc.raw)))
            }
    }
} catch (_:Exception) { false }
