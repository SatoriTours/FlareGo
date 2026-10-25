package com.flarego.ui.kv

import com.flarego.core.application.kv.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.r2.SecureWindowPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class KvBrowserContext(val connection: Connection, val credentialRevision: Long, val routeEpoch: Long) {
    val account get() = AccountScope(connection.id, connection.accountId)
}
enum class KvEditor { CREATE, RENAME, VALUE, BULK_READ, BULK_WRITE, BULK_DELETE }
enum class KvExpiryChoice { NEVER, ABSOLUTE_UTC, TTL }
data class KvForm(
    val title: String = "", val key: String = "", val keysText: String = "", val expiry: KvExpiryChoice = KvExpiryChoice.NEVER,
    val expirySeconds: String = "", val includeMetadata: Boolean = false, val overwrite: Boolean = false,
    val bulkType: KvBulkReadType = KvBulkReadType.text, val withMetadata: Boolean = false,
)
data class KvBrowserState(
    val context: KvBrowserContext? = null, val namespaces: List<KvNamespace> = emptyList(), val namespaceNext: PageToken? = null,
    val namespace: KvNamespace? = null, val keys: List<KvKeyEntry> = emptyList(), val keyNext: PageToken? = null,
    val prefix: String = "", val selectedKey: KvKeyEntry? = null, val loading: Boolean = false, val writing: Boolean = false,
    val editor: KvEditor? = null, val form: KvForm = KvForm(), val confirmation: KvConfirmationView? = null,
    val error: String? = null, val notice: String? = null, val tasks: List<OperationTask> = emptyList(),
    val failedKeys: List<String> = emptyList(),
)
enum class KvDocumentKind { IMPORT_VALUE, IMPORT_BULK_WRITE, IMPORT_BULK_DELETE, EXPORT_VALUE, EXPORT_BULK }
data class KvDocumentRequest(val id: Long, val kind: KvDocumentKind)

class KvBrowser(
    private val scope: CoroutineScope, private val actual: () -> KvBrowserContext?,
    private val runtime: KvWriteRuntime, store: KvContentStore, secureWindow: () -> SecureWindowPort?,
    private val nowSeconds: () -> Long, private val session: suspend (KvBrowserContext) -> KvSession,
) {
    private val mutable=MutableStateFlow(KvBrowserState())
    val state=mutable.asStateFlow()
    var confirmationEpoch=0L;private set
    private var command: KvMutation?=null
    val content=KvContentOwner(store,secureWindow,::invalidatePrepared)
    private val documents=MutableSharedFlow<KvDocumentRequest>(extraBufferCapacity=4)
    val documentRequests=documents.asSharedFlow()
    private var documentSerial=0L
    private var readSerial=0L
    private var readJob:Job?=null
    private var actionJob:Job?=null
    private var seenCursors=emptySet<String>()
    fun liveCommand():KvMutation?=command
    fun refreshContext() {
        val current=actual()
        if(current==mutable.value.context)return
        background();seenCursors=emptySet();mutable.value=KvBrowserState(context=current)
        if(current!=null){refreshNamespaces();refreshTasks()}
    }
    fun refreshNamespaces(more:Boolean=false) {
        val ctx=current()?:return
        if(mutable.value.namespace!=null)return
        val page=if(more)(mutable.value.namespaceNext as? PageToken.Number)?.value?:return else 1
        read(ctx){ticket->
            val result=session(ctx).listNamespaces(ctx.account,KvNamespacePageRequest(page))
            if(!valid(ctx,ticket))return@read
            if(result.items.any{it.ref.account!=ctx.account})throw stale()
            mutable.value=mutable.value.copy(namespaces=(if(more)mutable.value.namespaces else emptyList()).plus(result.items).distinctBy{it.ref},namespaceNext=result.next,loading=false)
        }
    }
    fun openNamespace(ref:KvNamespaceRef) {
        val ctx=current()?:return
        if(ref.account!=ctx.account)return
        cancel();seenCursors=emptySet()
        mutable.value=mutable.value.copy(namespace=KvNamespace(ref,"正在读取",null),keys=emptyList(),keyNext=null,selectedKey=null,prefix="")
        read(ctx){ticket->
            val api=session(ctx);val ns=api.getNamespace(ref)
            if(!valid(ctx,ticket)||mutable.value.namespace?.ref!=ref)return@read
            if(ns.ref!=ref)throw stale()
            mutable.value=mutable.value.copy(namespace=ns)
            val result=api.listKeys(ref,KvKeyPageRequest())
            if(!valid(ctx,ticket)||mutable.value.namespace?.ref!=ref)return@read
            mutable.value=mutable.value.copy(keys=result.items,keyNext=result.next,loading=false)
        }
    }
    fun backToNamespaces() {
        cancel();readSerial++;readJob?.cancel();seenCursors=emptySet()
        mutable.value=mutable.value.copy(namespace=null,keys=emptyList(),keyNext=null,selectedKey=null,prefix="",loading=false)
        refreshNamespaces()
    }
    fun refreshKeys(more:Boolean=false) {
        val ctx=current()?:return;val ref=mutable.value.namespace?.ref?:return
        val cursor=if(more)(mutable.value.keyNext as? PageToken.Cursor)?.value?:return else null
        val prefix=mutable.value.prefix
        if(!more)seenCursors=emptySet()
        val seen=seenCursors.toSet()
        read(ctx){ticket->
            val result=session(ctx).listKeys(ref,KvKeyPageRequest(prefix=prefix,cursor=cursor,seen=seen))
            if(!valid(ctx,ticket)||mutable.value.namespace?.ref!=ref||mutable.value.prefix!=prefix)return@read
            // Empty pages with a next cursor remain pageable. Only a successful page advances history.
            seenCursors=seen+listOfNotNull(cursor)
            mutable.value=mutable.value.copy(keys=(if(more)mutable.value.keys else emptyList()).plus(result.items).distinctBy{it.key},keyNext=result.next,loading=false)
        }
    }
    fun filterPrefix(prefix:String) {
        if(prefix.encodeToByteArray().size>512)return
        cancel();mutable.value=mutable.value.copy(prefix=prefix,keys=emptyList(),keyNext=null,selectedKey=null)
        refreshKeys()
    }
    fun selectKey(key:KvKeyEntry) {
        if(key !in mutable.value.keys)return
        cancel();mutable.value=mutable.value.copy(selectedKey=key,form=KvForm(key=key.key))
    }
    fun begin(editor:KvEditor) {
        if(current()==null||mutable.value.writing)return
        if(editor!=KvEditor.CREATE&&mutable.value.namespace==null)return
        cancel()
        if(editor in setOf(KvEditor.VALUE,KvEditor.BULK_READ,KvEditor.BULK_WRITE,KvEditor.BULK_DELETE)&&!content.begin()){
            error("无法保护当前窗口；值与 metadata 编辑未开启");return
        }
        mutable.value=mutable.value.copy(editor=editor,form=KvForm(title=if(editor==KvEditor.RENAME)mutable.value.namespace?.title.orEmpty() else "",key=mutable.value.selectedKey?.key.orEmpty()))
    }
    fun edit(change:(KvForm)->KvForm) {
        if(mutable.value.writing)return
        val next=change(mutable.value.form)
        if(next.title.length>512||next.key.encodeToByteArray().size>512||next.keysText.encodeToByteArray().size>52000||next.expirySeconds.length>24)return
        invalidatePrepared();mutable.value=mutable.value.copy(form=next,error=null)
    }
    fun cancel() {
        invalidatePrepared();content.withdraw()
        mutable.value=mutable.value.copy(editor=null,error=null,form=KvForm(key=mutable.value.selectedKey?.key.orEmpty()))
    }
    fun readValue() {
        val target=selectedTarget()?:return
        invalidatePrepared()
        if(!content.begin()){error("无法保护当前窗口；未读取敏感内容");return}
        mutable.value=mutable.value.copy(editor=KvEditor.VALUE,form=KvForm(key=target.key))
        action { ctx,stamp->
            val result=session(ctx).readValue(target)
            if(!actionValid(ctx,stamp)){result.content.close();return@action}
            content.acceptValue(result.content)
            mutable.value=mutable.value.copy(notice="本次读取已观察到值；不代表所有边缘节点已传播")
        }
    }
    fun readMetadata() {
        val target=selectedTarget()?:return
        invalidatePrepared()
        if(!content.begin()){error("无法保护当前窗口；未读取 metadata");return}
        action { ctx,stamp->
            val result=session(ctx).readMetadata(target)
            if(!actionValid(ctx,stamp))return@action
            content.acceptMetadata(result)
            mutable.value=mutable.value.copy(editor=KvEditor.VALUE,form=mutable.value.form.copy(key=target.key,includeMetadata=true),notice="本次观察的完整 metadata 已私有保存；写回只能保留这次观察，无法锁定其他客户端")
        }
    }
    fun bulkRead() {
        val ns=mutable.value.namespace?.ref?:return;val form=mutable.value.form
        invalidatePrepared()
        if(!content.begin()){error("无法保护当前窗口；未读取批量内容");return}
        action { ctx,stamp->
            val input=KvBulkReadInput(KvValidation.keys(form.keysText.split('\n'),100),form.bulkType,form.withMetadata)
            val result=session(ctx).bulkRead(ns,input)
            if(!actionValid(ctx,stamp)){result.content.close();return@action}
            content.acceptBulkResponse(result.content,input)
            mutable.value=mutable.value.copy(notice="完整批量响应已加密暂存；可选择单键有界查看或导出完整 JSON；本次观察不代表全球一致")
        }
    }
    fun prepare() {
        val editor=mutable.value.editor?:return;val ns=mutable.value.namespace?.ref;val form=mutable.value.form
        prepareCommand {
            when(editor){
                KvEditor.CREATE->KvMutation.Create(current()?.account?:throw stale(),KvValidation.title(form.title))
                KvEditor.RENAME->KvMutation.Rename(ns?:throw stale(),KvValidation.title(form.title))
                KvEditor.VALUE->{
                    if(!form.overwrite)throw CloudFailure(CloudFailureCode.INVALID_INPUT,"请明确接受可能覆盖同名值、metadata 和到期时间")
                    val expiry=when(form.expiry){KvExpiryChoice.NEVER->KvExpiry.Never;KvExpiryChoice.TTL->KvExpiry.Ttl(form.expirySeconds.toLongOrNull()?:throw stale());KvExpiryChoice.ABSOLUTE_UTC->KvExpiry.Absolute(form.expirySeconds.toLongOrNull()?:throw stale())}
                    KvValidation.expiry(expiry,nowSeconds());val target=KvKeyTarget(ns?:throw stale(),KvValidation.key(form.key))
                    KvMutation.Put(target,content.freezeValue(expiry,form.includeMetadata))
                }
                KvEditor.BULK_WRITE->{if(!form.overwrite)throw stale();KvMutation.BulkWrite(ns?:throw stale(),content.freezeBulkWrite(nowSeconds()))}
                KvEditor.BULK_DELETE->KvMutation.BulkDelete(ns?:throw stale(),content.bulkDeleteKeys())
                KvEditor.BULK_READ->throw stale()
            }
        }
    }
    fun prepareDeleteKey() {val target=selectedTarget()?:return;prepareCommand {KvMutation.DeleteKey(target)}}
    fun prepareDeleteNamespace() {val ns=mutable.value.namespace?.ref?:return;prepareCommand {KvMutation.DeleteNamespace(ns)}}
    private fun prepareCommand(make:suspend()->KvMutation) {
        invalidatePrepared()
        action {ctx,stamp->
            val draft=make()
            if(!actionValid(ctx,stamp)){release(draft);return@action}
            command=draft
            val preview=runtime.prepare(draft)
            if(!actionValid(ctx,stamp)){if(command===draft)invalidatePrepared();return@action}
            mutable.value=mutable.value.copy(confirmation=preview)
        }
    }
    fun acceptRisk(handle:KvConfirmationHandle)=transition(handle){runtime.acceptRisk(handle)}
    fun acceptTyped(handle:KvConfirmationHandle,text:String)=transition(handle){runtime.acceptTyped(handle,text)}
    private fun transition(handle:KvConfirmationHandle,next:()->KvConfirmationView){
        if(mutable.value.confirmation?.handle!==handle)return
        try{mutable.value=mutable.value.copy(confirmation=next(),error=null)}catch(_:Exception){error("确认阶段或精确 namespace ID 不匹配；未发送写入")}
    }
    fun submit(handle:KvConfirmationHandle) {
        val ctx=current()?:return
        if(mutable.value.confirmation?.handle!==handle||mutable.value.writing)return
        if(command is KvMutation.Put||command is KvMutation.BulkWrite||command is KvMutation.BulkDelete){if(!content.secureNow()){invalidatePrepared();return}}
        mutable.value=mutable.value.copy(writing=true,error=null)
        val stamp=confirmationEpoch
        scope.launch {
            try{
                val result=runtime.submit(handle)
                if(!actionValid(ctx,stamp))return@launch
                // The submitted frozen copy and its original editor input have both ended.
                // Retain only safe receipt/failed keys; continuing requires a fresh draft.
                content.withdraw()
                mutable.value=mutable.value.copy(editor=null,form=KvForm(),confirmation=null,failedKeys=result.receipt?.failedKeys.orEmpty(),notice=when(result.task.state){
                    TaskState.ACCEPTED->"请求已接纳；全球传播仍未知，请只读核对，不会自动重试"
                    TaskState.PARTIAL->"请求部分接纳；请核对完整失败键。仅可人工处置后重新导入失败子集、重新确认"
                    TaskState.FAILED->"本地发送前被阻止；请检查输入与当前权限后重新准备"
                    else->"请求结果未知；同命名空间写入已阻挡，请只读核对或明确人工处置"
                })
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){if(current()==ctx)error("无法提交当前确认；未自动重试，请先核对任务")}
            finally{if(current()==ctx){mutable.value=mutable.value.copy(writing=false);refreshTasks()}}
        }
    }
    fun refreshTasks() {
        val ctx=current()?:return
        scope.launch {try{val tasks=runtime.refreshTasks();if(current()==ctx)mutable.value=mutable.value.copy(tasks=tasks.filter{it.scope==ctx.account})}catch(_:Exception){}}
    }
    fun observeTask(id:String) = action {_,_->
        val observed=runtime.observeTask(id)
        mutable.value=mutable.value.copy(notice=if(observed==null)"该创建任务缺少可信 ID，不能按标题认领或重发；请在云端人工核对"else"已读取该 namespace 的当前状态；值与全球传播仍未知，任务门禁保持")
    }
    fun closeTask(id:String,text:String,acknowledged:Boolean) = action {_,_->
        runtime.closeUncertain(id,text,acknowledged)
        mutable.value=mutable.value.copy(notice="已按明确人工声明解除本地门禁；云端结果仍未知，历史请求不可重放")
        refreshTasks()
    }
    fun requestDocument(kind:KvDocumentKind) {
        if(current()==null||mutable.value.writing)return
        if(documentSerial==Long.MAX_VALUE){error("文件请求已失效，请重新打开页面");return}
        documents.tryEmit(KvDocumentRequest(++documentSerial,kind))
    }
    suspend fun importDocument(kind:KvDocumentKind,source:ByteSource) {
        val ctx=current()
        if(ctx==null||!content.begin()){source.close();throw stale()}
        when(kind){
            KvDocumentKind.IMPORT_VALUE->{content.importValue(source);mutable.value=mutable.value.copy(editor=KvEditor.VALUE)}
            KvDocumentKind.IMPORT_BULK_WRITE->{content.importBulk(source,KvBulkContentKind.WRITE,nowSeconds());mutable.value=mutable.value.copy(editor=KvEditor.BULK_WRITE)}
            KvDocumentKind.IMPORT_BULK_DELETE->{content.importBulk(source,KvBulkContentKind.DELETE,nowSeconds());mutable.value=mutable.value.copy(editor=KvEditor.BULK_DELETE)}
            else->{source.close();throw stale()}
        }
        if(current()!=ctx){content.withdraw();throw stale()}
        mutable.value=mutable.value.copy(notice="完整输入已加密暂存；尚未发送云端")
    }
    fun documentNotice(message:String){mutable.value=mutable.value.copy(notice=message)}
    fun documentError(){error("文件操作未完成；未发送云端写入，请检查所选目录后重新选择")}
    fun documentFailure(failure:CloudFailure?){error(if(failure?.uncertain==true)
        "文件完成或清理结果未知；可能留有 KV 待完成文档或完整导出，请检查所选目录，不要自动重试"
        else if(failure?.code==CloudFailureCode.UNSUPPORTED_CAPABILITY) "该文件提供方不支持安全的新建、删除和完成后改名，请选择其他目录"
        else "文件操作未完成；未发送云端写入，请检查所选目录、空间与权限")}
    fun previewBulk(key:String) = action {_,_->content.previewBulk(key)}
    fun background() {
        readSerial++;readJob?.cancel();actionJob?.cancel();invalidatePrepared();content.withdraw()
        mutable.value=mutable.value.copy(loading=false,editor=null)
    }
    fun close() {background();content.disposed()}
    private fun invalidatePrepared() {
        confirmationEpoch++;command=null;runtime.revoke()
        mutable.value=mutable.value.copy(confirmation=null)
    }
    private fun current():KvBrowserContext?=actual()?.takeIf{it==mutable.value.context}
    private fun valid(ctx:KvBrowserContext,ticket:Long)=current()==ctx&&readSerial==ticket
    private fun actionValid(ctx:KvBrowserContext,stamp:Long)=current()==ctx&&confirmationEpoch==stamp
    private fun read(ctx:KvBrowserContext,block:suspend(Long)->Unit){
        val ticket=++readSerial;readJob?.cancel();mutable.value=mutable.value.copy(loading=true,error=null)
        readJob=scope.launch{try{block(ticket)}catch(cancel:CancellationException){throw cancel}catch(failure:Exception){if(valid(ctx,ticket)){error(safe(failure));mutable.value=mutable.value.copy(loading=false)}}}
    }
    private fun action(block:suspend(KvBrowserContext,Long)->Unit){
        val ctx=current()?:return;val stamp=confirmationEpoch
        if(mutable.value.writing)return
        actionJob?.cancel();mutable.value=mutable.value.copy(error=null)
        actionJob=scope.launch{try{block(ctx,stamp)}catch(cancel:CancellationException){throw cancel}catch(failure:Exception){if(current()==ctx)error(safe(failure))}}
    }
    private fun selectedTarget():KvKeyTarget? {
        if(current()==null)return null
        val s=mutable.value;return KvKeyTarget(s.namespace?.ref?:return null,s.selectedKey?.key?:return null)
    }
    private fun error(message:String){mutable.value=mutable.value.copy(error=message)}
    private fun safe(failure:Exception)=when((failure as? CloudFailure)?.code){
        CloudFailureCode.PERMISSION_REQUIRED->"当前凭据无法证明 KV 写权限；未发送写入，只读能力仍可使用"
        CloudFailureCode.CONFLICT->"该 namespace 有未结算写入或状态冲突，请先只读核对与人工处置"
        CloudFailureCode.INVALID_INPUT->"输入、确认或窗口无效；请核对键、完整文件、到期策略和覆盖声明后重新准备"
        else->"KV 操作未完成；已保留当前列表与待重试游标，写入不会自动重试"
    }
    private fun release(command:KvMutation){when(command){is KvMutation.Put->command.input.content.close();is KvMutation.BulkWrite->command.input.content.close();else->Unit}}
    private fun stale()=CloudFailure(CloudFailureCode.INVALID_INPUT,"KV 当前范围或输入已失效")
}
