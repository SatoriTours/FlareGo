package com.flarego.core.application.zones

import com.flarego.core.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import com.flarego.core.application.management.ManagementContext
import com.flarego.core.application.management.ManagementActions
import com.flarego.core.application.operations.ChangePlan
import com.flarego.core.application.dns.managementFailure
import com.flarego.core.application.dns.requireDnsZone
import com.flarego.core.ports.ZoneManagementSession

enum class ZoneEditorMode { CREATE, EDIT, DELETE }

/** The action contains user-supplied domain input, never a provider raw edit body. */
data class ZonePreparedPreview(
    val id: String,
    val revision: Long,
    val preview: ZoneChangePreview,
    val action: ZoneAction,
    val before: ZoneDetails?,
)

data class ZonePageState(
    val account: AccountScope? = null,
    val zones: List<ZoneDetails> = emptyList(),
    val next: PageToken? = null,
    val total: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: ZoneDetails? = null,
    /** BeginEdit/BeginDelete refresh provider ownership and snapshot before opening the leaf. */
    val editorTarget: ZoneDetails? = null,
    val editorMode: ZoneEditorMode? = null,
    val draft: ZoneAction? = null,
    val preparedChange: ZonePreparedPreview? = null,
    val pendingTask: OperationTask? = null,
    val revision: Long = 0,
    val editorRevision: Long = 0,
    val demo: Boolean = false,
)

sealed interface ZoneIntent {
    data class Load(val account: AccountScope, val page: PageRequest = PageRequest()) : ZoneIntent
    data object LoadMore : ZoneIntent
    data class Open(val scope: ZoneScope) : ZoneIntent
    data object BeginCreate : ZoneIntent
    data class BeginEdit(val scope: ZoneScope) : ZoneIntent
    data class BeginDelete(val scope: ZoneScope) : ZoneIntent
    data object DraftInvalidated : ZoneIntent
    data class DraftChanged(val action: ZoneAction) : ZoneIntent
    /** Existing tested ZoneForm.onPreview(ZoneAction) maps directly to this intent. */
    data class Preview(val action: ZoneAction) : ZoneIntent
    /** For DELETE pass the exact typedName from the displayed leaf action; never auto-fill it. */
    data class Confirm(val previewId: String, val revision: Long, val typedName: String? = null) : ZoneIntent
    data class ActivationCheck(val scope: ZoneScope) : ZoneIntent
    data object BackToDraft : ZoneIntent
    data object Cancel : ZoneIntent
    data class Reconcile(val taskId: String) : ZoneIntent
    data object Clear : ZoneIntent
}

interface ZonePagePort {
    val state: StateFlow<ZonePageState>
    fun onIntent(intent: ZoneIntent)
}

/** UI state facade over the frozen Task 3 operation controller. */
class ZonePageController(
    private val appScope:CoroutineScope,
    private val session:suspend(CloudScope)->ZoneManagementSession,
    private val context:(CloudScope)->ManagementContext,
    private val actions:ManagementActions,
) : ZonePagePort {
    private val mutable = MutableStateFlow(ZonePageState())
    override val state: StateFlow<ZonePageState> = mutable.asStateFlow()
    private var generation=0L
    private var pageLimit=50
    private var prepared:ChangePlan?=null
    override fun onIntent(intent:ZoneIntent) {
        when(intent) {
            is ZoneIntent.Load -> load(intent.account,intent.page,false)
            ZoneIntent.LoadMore -> state.value.let { s -> if(!s.loading && s.account!=null && s.next!=null) load(s.account,PageRequest(pageLimit,s.next),true) }
            is ZoneIntent.Open -> open(intent.scope,null)
            is ZoneIntent.BeginEdit -> open(intent.scope,ZoneEditorMode.EDIT)
            is ZoneIntent.BeginDelete -> open(intent.scope,ZoneEditorMode.DELETE)
            ZoneIntent.BeginCreate -> { if(state.value.account==null) return;invalidate(true);mutable.update { it.copy(editorMode=ZoneEditorMode.CREATE,editorTarget=null,draft=null) } }
            ZoneIntent.Clear -> { prepared=null;generation++;mutable.value=ZonePageState(revision=state.value.revision+1,editorRevision=state.value.editorRevision+1) }
            ZoneIntent.DraftInvalidated -> invalidate(false)
            is ZoneIntent.DraftChanged -> { invalidate(false);mutable.update { it.copy(draft=freezeZoneAction(intent.action)) } }
            ZoneIntent.BackToDraft -> invalidate(false)
            ZoneIntent.Cancel -> { generation++;invalidate(true);mutable.update { it.copy(editorMode=null,editorTarget=null,draft=null,loading=false) } }
            is ZoneIntent.Preview -> preview(intent.action)
            is ZoneIntent.ActivationCheck -> preview(ZoneAction.ActivationCheck(intent.scope))
            is ZoneIntent.Confirm -> confirm(intent)
            is ZoneIntent.Reconcile -> reconcile(intent.taskId)
        }
    }
    private fun invalidate(editor:Boolean) { prepared=null;mutable.update { it.copy(revision=it.revision+1,editorRevision=it.editorRevision+if(editor) 1 else 0,preparedChange=null,error=null) } }
    private fun preview(action:ZoneAction) {
        val account=state.value.account?:return
        val actionAccount=when(val scope=action.scope) { is AccountScope -> scope;is ZoneScope -> scope.account;else -> return }
        if(actionAccount!=account) return
        val stamp=try { context(action.scope) } catch(_:Exception) { clearError();return }
        val frozen=freezeZoneAction(action)
        invalidate(false);mutable.update { it.copy(draft=frozen,loading=true) }
        val revision=state.value.revision
        val valid={ state.value.account==account && state.value.revision==revision && runCatching { context(action.scope)==stamp }.getOrDefault(false) }
        appScope.launch {
            try {
                val before=(frozen.scope as? ZoneScope)?.let { requireDnsZone(session(it).get(it),it) }
                if(!valid()) return@launch
                if(before!=null && state.value.editorTarget?.name!=null && state.value.editorTarget?.name!=before.name) {
                    invalidate(true);mutable.update { it.copy(editorTarget=before,draft=null,loading=false,error="CONFLICT：Zone 名称已变化，请重新输入并确认") };return@launch
                }
                val plan=actions.prepareZone(frozen,valid)
                if(!valid()) return@launch
                prepared=plan
                mutable.update { it.copy(loading=false,preparedChange=ZonePreparedPreview(plan.id,revision,actions.zonePreview(plan.id),frozen,before)) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(valid()) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun confirm(intent:ZoneIntent.Confirm) {
        val display=state.value.preparedChange;val plan=prepared
        if(display==null || plan==null || display.id!=intent.previewId || display.revision!=intent.revision || state.value.revision!=intent.revision ||
            (display.action is ZoneAction.Delete && (intent.typedName!=display.action.name || intent.typedName!=display.action.typedName))) {
            mutable.update { it.copy(error="INVALID_INPUT：确认已失效或完整域名不匹配") };return
        }
        val revision=state.value.revision;val account=state.value.account
        prepared=null;mutable.update { it.copy(preparedChange=null,loading=true,error=null) }
        appScope.launch {
            try { val task=actions.confirmZone(display.action,plan);if(state.value.account==account && state.value.revision==revision) {
                mutable.update { it.copy(loading=false,pendingTask=task) }
                if(task.state==TaskState.SUCCEEDED && account!=null) {
                    invalidate(true);mutable.update { it.copy(editorMode=null,editorTarget=null,draft=null,selected=null) }
                    load(account,PageRequest(pageLimit),false)
                }
            } }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(state.value.account==account && state.value.revision==revision) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun reconcile(taskId:String) {
        val target:CloudScope=state.value.pendingTask?.takeIf { it.id==taskId }?.scope?:state.value.selected?.ref?:state.value.account?:return
        val stamp=try { context(target) } catch(_:Exception) { clearError();return }
        invalidate(false);val revision=state.value.revision
        val valid={ state.value.revision==revision && runCatching { context(target)==stamp }.getOrDefault(false) }
        mutable.update { it.copy(loading=true) }
        appScope.launch {
            try { val task=actions.reconcileManagement(target,taskId,valid);if(valid()) mutable.update { it.copy(loading=false,pendingTask=task) } }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { if(valid()) mutable.update { it.copy(loading=false,error=managementFailure(failure)) } }
        }
    }
    private fun load(account:AccountScope,page:PageRequest,append:Boolean) {
        val stamp=try { context(account) } catch(_:Exception) { clearError();return }
        val read=++generation;pageLimit=page.limit
        if(state.value.account!=account) mutable.value=ZonePageState(account=account,revision=state.value.revision+1,editorRevision=state.value.editorRevision+1,demo=stamp.connection.demo)
        invalidate(false);mutable.update { it.copy(loading=true,error=null) }
        appScope.launch {
            try {
                val result=session(account).list(account,page)
                val task=actions.pendingManagement(account)
                val items=result.items.map { if(it.ref.account!=account) throw scopeMismatchFailure();requireDnsZone(it,it.ref) }
                if(!current(account,stamp,read)) return@launch
                mutable.update { it.copy(zones=if(append) (it.zones+items).distinctBy { it.ref } else items,next=result.next,total=result.total,loading=false,pendingTask=task) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(account,stamp,read)) mutable.update { it.copy(loading=false,error="Zone 读取失败，请刷新后重试") } }
        }
    }
    private fun open(scope:ZoneScope,mode:ZoneEditorMode?) {
        if(scope.account!=state.value.account) return
        val stamp=try { context(scope) } catch(_:Exception) { clearError();return }
        val read=++generation;invalidate(true)
        mutable.update { it.copy(loading=true,editorTarget=null,editorMode=null,draft=null) }
        appScope.launch {
            try {
                val result=requireDnsZone(session(scope).get(scope),scope)
                if(!current(scope.account,stamp,read)) return@launch
                mutable.update { it.copy(selected=result,editorTarget=if(mode!=null) result else null,editorMode=mode,loading=false) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(current(scope.account,stamp,read)) mutable.update { it.copy(loading=false,error="Zone 详情归属或读取失败") } }
        }
    }
    private fun current(account:AccountScope,stamp:ManagementContext,read:Long)=read==generation && state.value.account==account && runCatching { context(account)==stamp }.getOrDefault(false)
    private fun clearError() { prepared=null;generation++;mutable.value=ZonePageState(revision=state.value.revision+1,editorRevision=state.value.editorRevision+1,error="账号范围已变化，请重新加载") }
}
