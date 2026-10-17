package com.flarego.core.application.dns

import com.flarego.core.model.*
import com.flarego.core.ports.DnsFilter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import com.flarego.core.application.management.ManagementContext
import com.flarego.core.application.management.ManagementActions
import com.flarego.core.ports.DnsManagementSession
import com.flarego.core.cloudflare.generated.DnsSchemas
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonObject

/** Display projection. fields contains known writable domain fields, never a raw request body. */
data class DnsRecordView(
    val id: String,
    val type: String,
    val name: String,
    val content: String?,
    val ttl: Int?,
    val proxied: Boolean?,
    val priority: Int?,
    val fields: JsonObject,
    val readOnlyReason: String? = null,
    val unknownFields: Set<String> = emptySet(),
)

/** Display only. Confirmation authority stays with the app runtime. */
data class DnsPreparedPreview(
    val id: String,
    val revision: Long,
    val preview: DnsChangePreview,
    val target: ZoneScope,
    val recordId: String?,
    val before: DnsRecordInput?,
    val after: DnsRecordInput?,
)

data class DnsPageState(
    val scope: ZoneScope? = null,
    /** Fresh provider-owned Zone name. Null until ownership and name have been read. */
    val zoneName: String? = null,
    val records: List<DnsRecordDocument> = emptyList(),
    val rows: List<DnsRecordView> = emptyList(),
    val next: PageToken? = null,
    val total: Long? = null,
    val filter: DnsFilter = DnsFilter(),
    val loading: Boolean = false,
    val error: String? = null,
    val selected: DnsRecordDocument? = null,
    val detail: DnsRecordView? = null,
    val draft: DnsRecordInput? = null,
    val mode: DnsWriteMode? = null,
    val editingRecordId: String? = null,
    val preparedChange: DnsPreparedPreview? = null,
    val pendingTask: OperationTask? = null,
    val revision: Long = 0,
    /** Changes only on scope/editor replacement; do not key local text on every draft revision. */
    val editorRevision: Long = 0,
    val demo: Boolean = false,
)

sealed interface DnsIntent {
    data class Load(val scope: ZoneScope, val filter: DnsFilter = DnsFilter(), val page: PageRequest = PageRequest()) : DnsIntent
    data object LoadMore : DnsIntent
    data class Open(val recordId: String) : DnsIntent
    data class BeginCreate(val type: String = "A") : DnsIntent
    data class BeginEdit(val recordId: String, val mode: DnsWriteMode = DnsWriteMode.PATCH) : DnsIntent
    data class BeginDelete(val recordId: String) : DnsIntent
    /** For incomplete/unparsed leaf text: invalidate old authority without inventing domain input. */
    data object DraftInvalidated : DnsIntent
    data class DraftChanged(val input: DnsRecordInput) : DnsIntent
    /** This parsed leaf input becomes the actual live draft BEFORE a plan is issued. */
    data class Preview(val input: DnsRecordInput?, val mode: DnsWriteMode, val recordId: String? = null) : DnsIntent
    data class Confirm(val previewId: String, val revision: Long) : DnsIntent
    data object BackToDraft : DnsIntent
    data object Cancel : DnsIntent
    data class Reconcile(val taskId: String) : DnsIntent
    data object Clear : DnsIntent
}

interface DnsPagePort {
    val state: StateFlow<DnsPageState>
    fun onIntent(intent: DnsIntent)
}

/** Page state lives in the app scope; delayed reads cannot publish across context changes. */
class DnsController(
    private val appScope: CoroutineScope,
    private val session: suspend (ZoneScope) -> DnsManagementSession,
    private val context: (CloudScope) -> ManagementContext,
    private val actions: ManagementActions,
) : DnsPagePort {
    private val mutable = MutableStateFlow(DnsPageState())
    override val state: StateFlow<DnsPageState> = mutable.asStateFlow()
    private var readGeneration = 0L
    private var pageLimit = 50
    private var prepared:PreparedDnsChange?=null
    override fun onIntent(intent: DnsIntent) {
        when(intent) {
            is DnsIntent.Load -> load(intent.scope,intent.filter,intent.page,false)
            DnsIntent.LoadMore -> state.value.let { s -> if(!s.loading && s.scope!=null && s.next!=null) load(s.scope,s.filter,PageRequest(pageLimit,s.next),true) }
            is DnsIntent.Open -> open(intent.recordId,null)
            is DnsIntent.BeginEdit -> if(intent.mode in setOf(DnsWriteMode.PATCH,DnsWriteMode.REPLACE)) open(intent.recordId,intent.mode)
            is DnsIntent.BeginDelete -> open(intent.recordId,DnsWriteMode.DELETE)
            is DnsIntent.BeginCreate -> {
                val s=state.value
                if(s.zoneName==null || DnsSchemas.forType(intent.type)==null) { mutable.update { it.copy(error="请先读取 Zone，且只能编辑已知类型") };return }
                invalidate(true)
                mutable.update { it.copy(draft=DnsRecordInput(intent.type,s.zoneName,JsonObject(emptyMap())),mode=DnsWriteMode.CREATE,editingRecordId=null,error=null) }
            }
            DnsIntent.Clear -> { prepared=null;readGeneration++;mutable.value=DnsPageState(revision=state.value.revision+1,editorRevision=state.value.editorRevision+1) }
            DnsIntent.DraftInvalidated -> invalidate(false)
            is DnsIntent.DraftChanged -> { invalidate(false); mutable.update { it.copy(draft=intent.input.copy(fields=dnsCopy(intent.input.fields))) } }
            DnsIntent.BackToDraft -> invalidate(false)
            DnsIntent.Cancel -> { readGeneration++;invalidate(true);mutable.update { it.copy(draft=null,mode=null,editingRecordId=null,loading=false) } }
            is DnsIntent.Preview -> preview(intent)
            is DnsIntent.Confirm -> confirm(intent)
            is DnsIntent.Reconcile -> reconcile(intent.taskId)
        }
    }
    private fun invalidate(editor:Boolean) { prepared=null;mutable.update { it.copy(revision=it.revision+1,editorRevision=it.editorRevision+if(editor) 1 else 0,preparedChange=null,error=null) } }
    private fun preview(intent:DnsIntent.Preview) {
        val target=state.value.scope?:return
        val stamp=try { context(target) } catch(_:Exception) { clearError();return }
        val frozen=intent.input?.let { it.copy(fields=dnsCopy(it.fields)) }
        invalidate(false)
        mutable.update { it.copy(draft=frozen,mode=intent.mode,editingRecordId=intent.recordId,loading=true) }
        val revision=state.value.revision
        val valid={ state.value.scope==target && state.value.revision==revision && runCatching { context(target)==stamp }.getOrDefault(false) }
        appScope.launch {
            try {
                val result=actions.prepareDns(DnsWriteAction(target,intent.recordId,frozen,intent.mode),valid)
                if(!valid()) return@launch
                prepared=result
                val before=result.before?.let { row -> dnsRecordView(row).let { DnsRecordInput(it.type,it.name,it.fields) } }
                val after=result.input?.let { value ->
                    val raw=if(result.mode==DnsWriteMode.PATCH) JsonObject(result.before!!.raw+dnsPayload(value)) else dnsPayload(value)
                    dnsRecordView(DnsRecordDocument(intent.recordId?:"preview",target,raw)).let { DnsRecordInput(it.type,it.name,it.fields) }
                }
                mutable.update { it.copy(loading=false,preparedChange=DnsPreparedPreview(result.plan.id,revision,actions.dnsPreview(result.plan.id),target,intent.recordId,before,after)) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(valid()) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun confirm(intent:DnsIntent.Confirm) {
        val display=state.value.preparedChange;val command=prepared
        if(display==null || command==null || display.id!=intent.previewId || display.revision!=intent.revision || state.value.revision!=intent.revision) {
            mutable.update { it.copy(error="INVALID_INPUT：确认已失效，请重新预览") };return
        }
        val revision=state.value.revision;val target=state.value.scope
        prepared=null
        mutable.update { it.copy(preparedChange=null,loading=true,error=null) }
        appScope.launch {
            try {
                val task=actions.confirmDns(command)
                if(state.value.scope==target && state.value.revision==revision) {
                    mutable.update { it.copy(loading=false,pendingTask=task) }
                    if(task.state==TaskState.SUCCEEDED && target!=null) {
                        invalidate(true)
                        mutable.update { it.copy(mode=null,draft=null,editingRecordId=null,selected=null,detail=null) }
                        load(target,state.value.filter,PageRequest(pageLimit),false)
                    }
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(state.value.scope==target && state.value.revision==revision) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun reconcile(taskId:String) {
        val target=state.value.scope?:return;val stamp=try { context(target) } catch(_:Exception) { clearError();return }
        invalidate(false);val revision=state.value.revision
        val valid={ state.value.scope==target && state.value.revision==revision && runCatching { context(target)==stamp }.getOrDefault(false) }
        mutable.update { it.copy(loading=true) }
        appScope.launch {
            try { val task=actions.reconcileManagement(target,taskId,valid);if(valid()) mutable.update { it.copy(loading=false,pendingTask=task) } }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(valid()) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun load(scope:ZoneScope,filter:DnsFilter,page:PageRequest,append:Boolean) {
        val stamp=try { context(scope) } catch(_:Exception) { clearError();return }
        val generation=++readGeneration;pageLimit=page.limit
        if(state.value.scope!=scope) mutable.value=DnsPageState(scope=scope,revision=state.value.revision+1,editorRevision=state.value.editorRevision+1,demo=stamp.connection.demo)
        invalidate(false)
        mutable.update { it.copy(scope=scope,zoneName=null,filter=filter,loading=true,error=null) }
        appScope.launch {
            try {
                val api=session(scope); val owner=requireDnsZone(api.zone(scope),scope)
                val pageResult=api.list(scope,filter,page)
                val task=actions.pendingManagement(scope)
                val rows=pageResult.items.map { requireDnsDocument(it,scope,it.id,owner.name) }
                if(!current(scope,stamp,generation)) return@launch
                val all=if(append) (state.value.records+rows).distinctBy { it.id } else rows
                mutable.update { it.copy(zoneName=owner.name,records=all,rows=all.map(::dnsRecordView),next=pageResult.next,total=pageResult.total,loading=false,pendingTask=task) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(scope,stamp,generation)) mutable.update { it.copy(loading=false,error="DNS 读取失败，请刷新后重试") } }
        }
    }
    private fun open(id:String,mode:DnsWriteMode?) {
        val scope=state.value.scope?:return
        val stamp=try { context(scope) } catch(_:Exception) { clearError();return }
        val generation=++readGeneration;invalidate(true)
        mutable.update { it.copy(loading=true,editorRevision=it.editorRevision+1,draft=null,mode=null,editingRecordId=null) }
        appScope.launch {
            try {
                val api=session(scope);val owner=requireDnsZone(api.zone(scope),scope)
                val row=requireDnsDocument(api.get(scope,id),scope,id,owner.name);val view=dnsRecordView(row)
                if(!current(scope,stamp,generation)) return@launch
                if(mode!=null && view.readOnlyReason!=null) throw invalidInputFailure()
                mutable.update { it.copy(loading=false,zoneName=owner.name,selected=row,detail=view,mode=mode,editingRecordId=if(mode!=null) id else null,draft=if(mode!=null) DnsRecordInput(view.type,view.name,view.fields) else null) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(scope,stamp,generation)) mutable.update { it.copy(loading=false,error="DNS 详情读取失败或类型只读") } }
        }
    }
    private fun current(scope:ZoneScope,stamp:ManagementContext,generation:Long):Boolean = generation==readGeneration && state.value.scope==scope && runCatching { context(scope)==stamp }.getOrDefault(false)
    private fun clearError() { prepared=null;readGeneration++;mutable.value=DnsPageState(revision=state.value.revision+1,editorRevision=state.value.editorRevision+1,error="账号范围已变化，请重新加载") }
}

internal fun dnsRecordView(document:DnsRecordDocument):DnsRecordView {
    val raw=document.raw;val type=dnsText(raw,"type");val name=dnsText(raw,"name")
    val schema=DnsSchemas.forType(type);val unknown=mutableSetOf<String>()
    val metadata=setOf("id","created_on","modified_on","comment_modified_on","tags_modified_on","meta","proxiable","locked","zone_id","zone_name")
    fun project(value:JsonObject,nodes:List<JsonObject>,root:LocalDnsSchema,path:String):JsonObject {
        val properties=nodes.flatMap { root.properties(it).entries }.groupBy({it.key},{it.value}).mapValues { it.value.flatten() }
        return JsonObject(value.mapNotNull { (key,v) ->
            val definitions=properties[key]
            if(definitions==null || definitions.any { (it["readOnly"] as? JsonPrimitive)?.booleanOrNull==true }) {
                if(path.isNotEmpty() || key !in metadata) unknown+=path+key
                null
            } else key to if(v is JsonObject) project(v,definitions,root,"$path$key.") else v
        }.toMap())
    }
    val fields=if(schema==null) JsonObject(emptyMap()) else project(raw,listOf(schema),LocalDnsSchema(schema),"")
    return DnsRecordView(document.id,type,name,(raw["content"] as? JsonPrimitive)?.contentOrNull,(raw["ttl"] as? JsonPrimitive)?.intOrNull,(raw["proxied"] as? JsonPrimitive)?.booleanOrNull,(raw["priority"] as? JsonPrimitive)?.intOrNull,JsonObject(fields-"type"-"name"),if(schema==null) "未来 DNS 类型仅供查看" else null,unknown)
}

internal fun managementFailure(failure:Exception):String = if(failure is CloudFailure) "${failure.code}：${failure.publicMessage}" else "操作未能确认，请查看任务并只读核对"
