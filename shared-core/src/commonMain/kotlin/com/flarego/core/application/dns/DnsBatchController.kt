package com.flarego.core.application.dns

import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.application.operations.*
import com.flarego.core.application.management.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.flarego.core.cloudflare.dns.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject

/** Pure comparison of Task1-validated domain input; neither validation nor send authority. */
fun dnsBatchPostSemantic(input: DnsRecordInput): JsonObject = dnsSemanticRecord(dnsPayload(input))

data class PreparedDnsBatch(val plan:ChangePlan,val maxOperations:Int=200)

/** Uses the app issuer/coordinator; no second write domain is constructed here. */
@OptIn(ExperimentalAtomicApi::class)
class DnsBatchController(
    private val resolver:OperationContextResolver,private val plans:ChangePlans,private val coordinator:OperationCoordinator,
    private val clock:OperationClock,private val fingerprint:FingerprintPort,private val store:OperationStore,
    private val dispatcher:CoroutineDispatcher,private val constraints:(ZoneScope)->DnsConstraints={DnsConstraints()},
    private val session:suspend (ZoneScope)->DnsBatchManagementSession,
) {
    fun requestFingerprint(scope:ZoneScope,input:DnsBatchInput)=fingerprint.sha256(CanonicalJson.encode(buildJsonObject {
        put("connection",scope.connectionId);put("account",scope.account.accountId);put("zone",scope.zoneId)
        put("deletes",JsonArray(input.deletes.map(::JsonPrimitive)))
        put("patches",JsonArray(input.patches.map { buildJsonObject { put("id",it.id);put("fields",it.fields) } }))
        put("puts",JsonArray(input.puts.map { buildJsonObject { put("id",it.id);put("input",dnsPayload(it.input)) } }))
        put("posts",JsonArray(input.posts.map(::dnsPayload)))
    }))
    private data class ReadProof(val task:OperationTask,val live:LiveOperationContext,val outcome:DnsBatchOutcome)
    private val observations=AtomicReference<Map<String,ReadProof>>(emptyMap())
    suspend fun outcome(taskId:String,includeCurrentStateProof:Boolean=false):DnsBatchOutcome? {
        val task=withContext(dispatcher) { store.get(taskId) }?:return null
        val proof=withContext(dispatcher) { (store as? DnsBatchEvidenceStore)?.batchEvidence(taskId) }?:return null
        if(runCatching { proof.requireValid(task,fingerprint) }.isFailure) return null
        val read=observations.load()[taskId]
        if(includeCurrentStateProof && read!=null && task==read.task && resolver.current()==read.live && resolver.isLive(task.scope,read.live.credentialRevision)) return read.outcome
        entries.load()[taskId]?.outcome?.load()?.let { return it.copy(currentStateVerified=false) }
        val committed=task.state==TaskState.SUCCEEDED && proof.transaction==DnsBatchTransaction.CONFIRMED_COMMIT && proof.items.all { it.providerId!=null }
        val rejected=task.state==TaskState.FAILED && proof.transaction==DnsBatchTransaction.CONFIRMED_REJECT
        val items=proof.items.map { item -> DnsBatchItemResult(DnsBatchItemRef(item.kind,item.index,item.recordId,item.createType,item.createName),item.providerId,
            when { committed->DnsBatchVerification.MATCHED;rejected->DnsBatchVerification.NOT_APPLIED;else->DnsBatchVerification.UNKNOWN }) }
        return DnsBatchOutcome(proof.transaction,items,if(committed || rejected) emptyList() else items.map { it.ref })
    }
    private class Entry(val prepared:PreparedDnsBatch,val bundle:BatchWrite,val live:LiveOperationContext,val proof:DnsBatchTaskEvidence) {
        val notSent=AtomicReference<CloudFailure?>(null)
        val outcome=AtomicReference<DnsBatchOutcome?>(null)
    }
    private val entries=AtomicReference<Map<String,Entry>>(emptyMap())
    private val gate=DnsUncertaintyGate(store,dispatcher)
    private fun digest(value:JsonElement)=fingerprint.sha256(CanonicalJson.encode(value))
    private fun bound(live:LiveOperationContext) {
        if(resolver.current()!=live || !resolver.isLive(live.scope,live.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(live.scope,live.connection)
    }
    private fun before(bundle:BatchWrite)=digest(buildJsonObject {
        put("zone_name",bundle.snapshot.owner.name)
        put("records",buildJsonArray { bundle.snapshot.records.toSortedMap().forEach { (id,row) -> add(buildJsonObject { put("id",id);put("hash",digest(dnsSemanticRecord(row.raw))) }) } })
        put("post_candidates",buildJsonArray { bundle.snapshot.postCandidates.forEach { rows -> add(buildJsonArray { rows.sortedBy { it.id }.forEach { row -> add(buildJsonObject { put("id",row.id);put("hash",digest(dnsSemanticRecord(row.raw))) }) } }) } })
    })
    private fun proof(scope:ZoneScope,bundle:BatchWrite,taskId:String):DnsBatchTaskEvidence {
        val items=bundle.items.map { item -> DnsBatchItemEvidence(item.ref.kind,item.ref.index,item.ref.recordId,item.ref.createType,item.ref.createName,
            digest(item.desired),item.before?.let { digest(dnsSemanticRecord(it.raw)) },creationPreconditionVersion=if(item.ref.kind==DnsBatchKind.POST) 1 else null) }
        return DnsBatchTaskEvidence(1,taskId,scope,DnsBatchKind.entries.map { kind -> items.count { it.kind==kind } },items,dnsBatchManifestHash(scope,items,fingerprint))
    }
    suspend fun prepare(scope:ZoneScope,input:DnsBatchInput):PreparedDnsBatch {
        val frozen=batchFreeze(input);batchShape(frozen,3500)
        val live=resolver.current()?:throw invalidInputFailure()
        if(live.scope!=scope || live.requestFingerprint!=requestFingerprint(scope,frozen)) throw invalidInputFailure()
        bound(live);gate.requireClear(scope,DNS_BATCH_KEY);bound(live)
        val api=session(scope);bound(live)
        val snapshot=api.observeBatch(scope,frozen);bound(live)
        val bundle=batchBuild(scope,frozen,snapshot,constraints(scope))
        val preliminary=proof(scope,bundle,"pending")
        gate.requireClear(scope,DNS_BATCH_KEY);bound(live)
        val plan=plans.prepare(scope,DNS_BATCH_KEY,ResourceRef(scope,"dns_batch",scope.zoneId),before(bundle),preliminary.manifestHash,live.requestFingerprint,live.credentialRevision,clock.nowMs())
        val prepared=PreparedDnsBatch(plan,snapshot.maxOperations);val entry=Entry(prepared,bundle,live,preliminary.copy(taskId=plan.id))
        while(true) { val old=entries.load();if(entries.compareAndSet(old,old+(plan.id to entry))) break }
        return prepared
    }
    fun preview(id:String):DnsBatchPreview {
        val entry=entries.load()[id]?:throw invalidInputFailure();val scope=entry.prepared.plan.scope as ZoneScope
        return DnsBatchPreview(id,0,scope,DnsBatchKind.entries.associateWith { kind -> entry.bundle.items.count { it.ref.kind==kind } },entry.bundle.items.map { item ->
            val old=item.before?.let { row -> DnsRecordInput(dnsText(row.raw,"type"),dnsText(row.raw,"name"),dnsRecordView(row).fields) }
            val after=if(item.ref.kind==DnsBatchKind.PATCH) {
                val row=DnsRecordDocument(item.ref.recordId!!,scope,JsonObject(item.before!!.raw+item.payload!!))
                dnsRecordView(row).let { view -> DnsRecordInput(view.type,view.name,view.fields) }
            } else item.input
            DnsBatchPreviewItem(item.ref.kind,item.ref.index,item.ref.recordId,after?.name?:old!!.name,after?.type?:old?.type,old,after)
        },listOf("Cloudflare 按删除→局部编辑→完整替换→新增在单一数据库事务提交；任一子项失败整批不应用。",
            "全球 DNS 传播不是原子操作，确认事务成功不代表所有解析节点已经更新。",
            "预提交回读不是强 CAS；读取与提交之间仍可能发生外部并发变化。")+
            if(entry.bundle.items.any { it.ref.kind==DnsBatchKind.PUT }) listOf("PUT 是完整替换，未提供的可写属性可能恢复默认值。") else emptyList())
    }
    private fun executionTask(plan:ChangePlan)=OperationTask(plan.id,(plan.scope as ZoneScope).connectionId,plan.scope,plan.actionKey,plan.resourceRef,
        plan.requestFingerprint,plan.desiredHash,TaskState.QUEUED,OperationTaskPersistence.summaryDetailFor(TaskState.QUEUED),null,null,clock.nowMs(),clock.nowMs())
    suspend fun submit(confirmed:ConfirmedPlan):OperationTask {
        val p=entries.load()[confirmed.plan.id]?:throw invalidInputFailure()
        val plan=p.prepared.plan;val scope=plan.scope as ZoneScope
        fun guard() { if(confirmed.plan!=plan || plans.isExpired(plan)) throw invalidInputFailure();bound(p.live) }
        guard()
        withContext(dispatcher) { store.get(plan.id) }?.let { return it } // Issued execution is never replayed.
        gate.requireClear(scope,DNS_BATCH_KEY);guard()
        val binding=store as? DnsBatchEvidenceBinding ?: throw batchUnavailable()
        binding.stageBatchEvidence(executionTask(plan),p.proof) // Memory only; canonical QUEUED write binds it atomically.
        val executed=coordinator.executeGuarded(confirmed,::guard) { composed ->
            var admitted=false
            try {
                val api=session(scope);guard()
                gate.requireClear(scope,DNS_BATCH_KEY,excludingTaskId=plan.id);guard()
                val outcome=api.writeBatch(scope,p.bundle.input) { observed ->
                    val fresh=batchBuild(scope,p.bundle.input,observed,constraints(scope),allowNoop=true)
                    if(before(fresh)!=plan.beforeHash) throw CloudFailure(CloudFailureCode.CONFLICT,"DNS 批量预提交回读已变化，请重新读取后预览")
                    composed();admitted=true
                }
                if(!admitted) throw invalidInputFailure()
                val refs=p.bundle.items.map { it.ref }
                if(outcome.items.map { it.ref }!=refs || outcome.pending!=outcome.items.filter { it.verification==DnsBatchVerification.UNKNOWN || it.verification==DnsBatchVerification.CONFLICT }.map { it.ref }) throw invalidInputFailure()
                val evidence=p.proof.copy(transaction=outcome.transaction,items=p.proof.items.zip(outcome.items).map { (item,result) -> item.copy(providerId=result.providerId) })
                if(outcome.currentStateVerified || (outcome.transaction==DnsBatchTransaction.CONFIRMED_REJECT && outcome.items.any { it.verification!=DnsBatchVerification.NOT_APPLIED || it.providerId!=null }) ||
                    (outcome.transaction==DnsBatchTransaction.CONFIRMED_COMMIT && outcome.items.any { it.verification==DnsBatchVerification.NOT_APPLIED })) throw invalidInputFailure()
                binding.stageBatchEvidence(executionTask(plan),evidence)
                p.outcome.store(outcome.copy(currentStateVerified=false))
                when(outcome.transaction) {
                    DnsBatchTransaction.CONFIRMED_REJECT -> if(outcome.items.all { it.verification==DnsBatchVerification.NOT_APPLIED }) ExecutionReceipt.Rejected() else ExecutionReceipt.Unknown()
                    DnsBatchTransaction.CONFIRMED_COMMIT -> if(outcome.pending.isEmpty() && outcome.items.all { it.verification==DnsBatchVerification.MATCHED && it.providerId!=null }) ExecutionReceipt.Completed() else ExecutionReceipt.Unknown()
                    DnsBatchTransaction.UNKNOWN -> ExecutionReceipt.Unknown()
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) {
                if(!admitted) p.notSent.store(failure as? CloudFailure ?: invalidInputFailure())
                ExecutionReceipt.Unknown()
            }
        }
        p.notSent.load()?.let { failure ->
            try { coordinator.reconcile(executed.id) { Reconciliation.NotApplied } }
            catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { }
            throw failure
        }
        return withContext(dispatcher) { store.get(executed.id) }?:executed
    }
    /** Safe manifest read locators only. No payload, plan or old confirmation is reconstructed. */
    suspend fun reconcile(taskId:String):OperationTask {
        while(true) { val old=observations.load();if(observations.compareAndSet(old,old-taskId)) break }
        val task=withContext(dispatcher) { store.get(taskId) }?:throw invalidInputFailure()
        val scope=task.scope as? ZoneScope ?: throw invalidInputFailure()
        if(task.actionKey!=DNS_BATCH_KEY || task.resourceRef!=ResourceRef(scope,"dns_batch",scope.zoneId)) throw invalidInputFailure()
        val live=resolver.current()?:throw invalidInputFailure()
        if(live.scope!=scope) throw scopeMismatchFailure();bound(live)
        if(task.state in setOf(TaskState.FAILED,TaskState.ACTION_REQUIRED)) return task
        var observation:DnsBatchOutcome?=null
        val settled=coordinator.reconcile(taskId) { current ->
            if(current!=task) throw invalidInputFailure()
            val observed=try {
                val proof=withContext(dispatcher) { (store as? DnsBatchEvidenceStore)?.batchEvidence(taskId) }?:throw invalidInputFailure()
                proof.requireValid(task,fingerprint)
                val api=session(scope);bound(live);val owner=requireDnsZone(api.zone(scope),scope);bound(live)
                val verified=mutableListOf<DnsBatchItemResult>()
                val actual=proof.items.map { item ->
                    var actualId=item.recordId?:item.providerId
                    val hash=if(item.kind==DnsBatchKind.DELETE) {
                        try { api.get(scope,item.recordId!!);throw invalidInputFailure() }
                        catch(failure:CloudFailure) { if(failure.code!=CloudFailureCode.NOT_FOUND) throw failure;digest(dnsDeletedState(scope,item.recordId!!)) }
                    } else if(item.kind!=DnsBatchKind.POST) {
                        val row=requireDnsDocument(api.get(scope,item.recordId!!),scope,item.recordId,owner.name)
                        digest(dnsSemanticRecord(row.raw))
                    } else {
                        // Validate a readonly locator against the fresh owned Zone, not an executable POST.
                        val locator=validateDnsInput(owner.name,DnsRecordInput(item.createType!!,item.createName!!,JsonObject(emptyMap())),constraints(scope),DnsWriteMethod.PATCH)
                        if(locator.type!=item.createType || locator.name!=item.createName || item.creationPreconditionVersion!=1) throw invalidInputFailure()
                        val candidates=batchCandidates(api,scope,item.createType,item.createName,owner.name)
                        val exact=candidates.map { requireDnsDocument(api.get(scope,it.id),scope,it.id,owner.name) }.filter { digest(dnsSemanticRecord(it.raw))==item.desiredHash }
                        if(exact.size!=1 || (item.providerId!=null && exact.single().id!=item.providerId)) throw invalidInputFailure()
                        actualId=exact.single().id
                        digest(dnsSemanticRecord(exact.single().raw))
                    }
                    bound(live)
                    verified+=DnsBatchItemResult(DnsBatchItemRef(item.kind,item.index,item.recordId,item.createType,item.createName),actualId,
                        if(hash==item.desiredHash) DnsBatchVerification.MATCHED else DnsBatchVerification.UNKNOWN)
                    item.copy(desiredHash=hash)
                }
                val observedHash=dnsBatchManifestHash(scope,actual,fingerprint)
                if(observedHash==task.desiredHash && verified.size==proof.items.size && verified.isNotEmpty() && verified.all { it.verification==DnsBatchVerification.MATCHED })
                    observation=DnsBatchOutcome(proof.transaction,verified,emptyList(),currentStateVerified=true)
                Reconciliation.Matched(observedHash)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { Reconciliation.StillUnknown }
            bound(live);observed
        }
        // A read result alone is insufficient: confirm the canonical task and complete evidence survived settlement.
        val persisted=withContext(dispatcher) { store.get(taskId) }
        val proof=withContext(dispatcher) { (store as? DnsBatchEvidenceStore)?.batchEvidence(taskId) }
        if(settled.state==TaskState.SUCCEEDED && persisted==settled && proof!=null && runCatching { proof.requireValid(settled,fingerprint) }.isSuccess) {
            bound(live)
            observation?.let { outcome -> while(true) { val old=observations.load();if(observations.compareAndSet(old,old+(taskId to ReadProof(settled,live,outcome)))) break } }
        }
        return settled
    }
}

/** Domain projections for display only, never a source of executable confirmation. */
data class DnsBatchPreviewItem(val kind: DnsBatchKind, val index: Int, val recordId: String?,
    val name: String, val type: String?, val before: DnsRecordInput?, val after: DnsRecordInput?)
data class DnsBatchPreview(val id: String, val revision: Long, val scope: ZoneScope,
    val counts: Map<DnsBatchKind, Int>, val targets: List<DnsBatchPreviewItem>, val warnings: List<String>)
data class DnsBatchPageState(
    val scope: ZoneScope? = null, val zoneName: String? = null,
    val draft: DnsBatchInput? = null, val revision: Long = 0, val editorRevision: Long = 0,
    val preparedChange: DnsBatchPreview? = null, val pendingTask: OperationTask? = null,
    val outcome: DnsBatchOutcome? = null, val maxOperations: Int = 200,
    val loading: Boolean = false, val error: String? = null, val demo: Boolean = false,
)
sealed interface DnsBatchIntent {
    data class Bind(val scope: ZoneScope) : DnsBatchIntent
    data object DraftInvalidated : DnsBatchIntent
    data class DraftChanged(val input: DnsBatchInput) : DnsBatchIntent
    data class Preview(val input: DnsBatchInput) : DnsBatchIntent
    data class Confirm(val previewId: String, val revision: Long) : DnsBatchIntent
    data object BackToDraft : DnsBatchIntent
    data object Cancel : DnsBatchIntent
    data class Reconcile(val taskId: String) : DnsBatchIntent
    data object Clear : DnsBatchIntent
}
interface DnsBatchPagePort {
    val state: StateFlow<DnsBatchPageState>
    fun onIntent(intent: DnsBatchIntent)
}
interface DnsBatchActions {
    suspend fun prepareBatch(scope:ZoneScope,input:DnsBatchInput,stillCurrent:()->Boolean):PreparedDnsBatch
    fun batchPreview(id:String):DnsBatchPreview
    suspend fun confirmBatch(prepared:PreparedDnsBatch):OperationTask
    suspend fun batchOutcome(taskId:String,includeCurrentStateProof:Boolean=false):DnsBatchOutcome?
}

/** App-scoped page state. Draft revision is actual product input, never derived from a plan. */
class DnsBatchPageController(
    private val appScope:CoroutineScope,private val session:suspend (ZoneScope)->DnsBatchManagementSession,
    private val context:(CloudScope)->ManagementContext,private val actions:ManagementActions,private val batch:DnsBatchActions,
):DnsBatchPagePort {
    private val mutable=MutableStateFlow(DnsBatchPageState())
    override val state=mutable.asStateFlow()
    private var prepared:PreparedDnsBatch?=null
    private var generation=0L
    private var proofContext:ManagementContext?=null
    override fun onIntent(intent:DnsBatchIntent) {
        when(intent) {
            DnsBatchIntent.Clear -> clear()
            is DnsBatchIntent.Bind -> bind(intent.scope)
            DnsBatchIntent.DraftInvalidated -> { invalidate(false);pending() }
            is DnsBatchIntent.DraftChanged -> { invalidate(false);mutable.update { it.copy(draft=batchFreeze(intent.input)) };pending() }
            is DnsBatchIntent.Preview -> preview(intent.input)
            is DnsBatchIntent.Confirm -> confirm(intent)
            DnsBatchIntent.BackToDraft -> { invalidate(false);pending() }
            DnsBatchIntent.Cancel -> { generation++;invalidate(true);mutable.update { it.copy(draft=null,loading=false) };pending() }
            is DnsBatchIntent.Reconcile -> reconcile(intent.taskId)
        }
    }
    private fun clear() { generation++;prepared=null;proofContext=null;mutable.value=DnsBatchPageState(revision=state.value.revision+1,editorRevision=state.value.editorRevision+1) }
    private fun invalidate(editor:Boolean) { prepared=null;mutable.update { it.copy(preparedChange=null,revision=it.revision+1,editorRevision=it.editorRevision+if(editor) 1 else 0,error=null) } }
    private fun valid(scope:ZoneScope,stamp:ManagementContext,version:Long)=state.value.scope==scope && state.value.revision==version && runCatching { context(scope)==stamp }.getOrDefault(false)
    private fun stamp(scope:ZoneScope)=try { context(scope) } catch(_:Exception) { clear();mutable.update { it.copy(error="账号范围或凭据已变化，请重新绑定 Zone") };null }
    private fun unsettled(task:OperationTask?)=task?.state in DNS_MUTATION_UNSETTLED
    private fun bind(scope:ZoneScope) {
        val stamp=stamp(scope)?:return
        clear();val token=generation
        mutable.update { it.copy(scope=scope,loading=true,demo=stamp.connection.demo) }
        appScope.launch {
            try {
                val api=session(scope);val owner=requireDnsZone(api.zone(scope),scope)
                val max=api.batchMaxOperations(scope);if(max !in setOf(200,3500)) throw invalidInputFailure()
                val task=actions.pendingManagement(scope)
                val outcome=task?.takeIf { it.actionKey==DNS_BATCH_KEY }?.let { batch.batchOutcome(it.id,false) }
                if(token!=generation || state.value.scope!=scope || context(scope)!=stamp) return@launch
                mutable.update { it.copy(zoneName=owner.name,maxOperations=max,loading=false,pendingTask=task,outcome=outcome) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(token==generation && state.value.scope==scope) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun pending() {
        val scope=state.value.scope?:return;val stamp=stamp(scope)?:return;val revision=state.value.revision
        appScope.launch {
            try {
                val task=actions.pendingManagement(scope)
                val previous=state.value.outcome
                val preserve=previous?.currentStateVerified==true && proofContext==stamp && state.value.pendingTask?.id==task?.id && task?.state==TaskState.SUCCEEDED
                val outcome=if(preserve) previous else task?.takeIf { it.actionKey==DNS_BATCH_KEY }?.let { batch.batchOutcome(it.id,false) }
                if(valid(scope,stamp,revision)) mutable.update { it.copy(pendingTask=task,outcome=outcome) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(valid(scope,stamp,revision)) mutable.update { it.copy(error=managementFailure(failure)) } }
        }
    }
    private fun preview(input:DnsBatchInput) {
        val scope=state.value.scope?:return;val stamp=stamp(scope)?:return
        invalidate(false);val frozen=batchFreeze(input);mutable.update { it.copy(draft=frozen,loading=true) };val revision=state.value.revision
        val current={valid(scope,stamp,revision)}
        appScope.launch {
            try {
                val task=actions.pendingManagement(scope)
                if(!current()) return@launch
                mutable.update { it.copy(pendingTask=task) }
                if(unsettled(task)) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"同 Zone DNS 写入尚未核对，请先只读核对")
                val result=batch.prepareBatch(scope,frozen,current)
                if(!current()) return@launch
                prepared=result
                mutable.update { it.copy(loading=false,preparedChange=batch.batchPreview(result.plan.id).copy(revision=revision),maxOperations=result.maxOperations) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(current()) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun confirm(intent:DnsBatchIntent.Confirm) {
        val p=prepared;val view=state.value.preparedChange
        if(p==null || view==null || view.id!=intent.previewId || view.revision!=intent.revision || state.value.revision!=intent.revision) {
            mutable.update { it.copy(error="INVALID_INPUT：批量确认已失效，请重新预览") };return
        }
        val scope=state.value.scope?:return;val revision=state.value.revision
        prepared=null;mutable.update { it.copy(preparedChange=null,loading=true,error=null) }
        appScope.launch {
            try {
                val task=batch.confirmBatch(p);val outcome=batch.batchOutcome(task.id,false)
                if(state.value.scope!=scope || state.value.revision!=revision) return@launch
                mutable.update { it.copy(pendingTask=task,outcome=outcome,loading=false) }
                if(task.state in setOf(TaskState.SUCCEEDED,TaskState.FAILED)) {
                    invalidate(true);mutable.update { it.copy(draft=null) }
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(state.value.scope==scope && state.value.revision==revision) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun reconcile(taskId:String) {
        val scope=state.value.scope?:return;val stamp=stamp(scope)?:return
        invalidate(false);val revision=state.value.revision;val current={valid(scope,stamp,revision)}
        mutable.update { it.copy(loading=true) }
        appScope.launch {
            try {
                val task=actions.reconcileManagement(scope,taskId,current)
                val outcome=if(task.actionKey==DNS_BATCH_KEY) batch.batchOutcome(task.id,true) else null
                if(!current()) return@launch
                proofContext=if(outcome?.currentStateVerified==true) stamp else null
                invalidate(true) // Old drafts/plans are not revived by a readonly observation.
                mutable.update { it.copy(loading=false,pendingTask=task,outcome=outcome,draft=null) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(current()) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
}
