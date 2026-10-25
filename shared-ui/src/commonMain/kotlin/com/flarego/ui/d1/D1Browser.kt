package com.flarego.ui.d1

import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.r2.SecureWindowPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class D1BrowserContext(val connection: Connection, val credentialRevision: Long, val routeEpoch: Long) {
    val account get() = AccountScope(connection.id, connection.accountId)
}
data class D1DatabaseRow(val ref: D1DatabaseRef, val name: String, val fileSize: Long?, val replication: D1Replication?)
enum class D1Editor { CREATE, PUT, PATCH, DELETE, UNDELETE, SQL, RAW, IMPORT, EXPORT, RESTORE }
enum class D1BrowseKind { SCHEMA, COLUMNS, INDEXES, ROWS }
data class D1Form(
    val name: String = "", val uuid: String = "", val jurisdiction: D1Jurisdiction? = null, val location: D1Location? = null,
    val replication: D1Replication? = D1Replication.disabled, val json: Boolean = false, val noData: Boolean = false,
    val noSchema: Boolean = false, val byTimestamp: Boolean = false, val descending: Boolean = false,
)
data class D1BrowserState(
    val context: D1BrowserContext? = null, val databases: List<D1DatabaseRow> = emptyList(), val next: PageToken? = null,
    val database: D1DatabaseRow? = null, val filter: String = "", val loading: Boolean = false, val writing: Boolean = false,
    val editor: D1Editor? = null, val form: D1Form = D1Form(), val tasks: List<OperationTask> = emptyList(),
    val error: String? = null, val notice: String? = null, val browsePage: Int = 0,
    val failureCode: CloudFailureCode? = null,
    val browseKind: D1BrowseKind? = null,
)
enum class D1DocumentKind { SQL_TEXT, SQL_JSON, IMPORT_SQL, EXPORT_RESULT }
data class D1DocumentRequest(val id: Long, val kind: D1DocumentKind, val editor: D1Editor? = null)

/** One actual route owner. No SQL, schema names, result text, bookmark or file URI in public state. */
class D1Browser(
    private val scope: CoroutineScope, private val actual: () -> D1BrowserContext?, private val runtime: D1WriteRuntime,
    private val store: D1ContentStore, window: () -> SecureWindowPort?, private val md5: D1ContentFingerprint,
    private val session: suspend (D1BrowserContext) -> D1Session,
    private val download: suspend (D1BrowserContext, D1FileOutcome) -> D1Content,
) {
    private val mutable = MutableStateFlow(D1BrowserState())
    val state = mutable.asStateFlow()
    val content = D1ContentOwner(store, window, ::invalidatePrepared)
    private val documents = MutableSharedFlow<D1DocumentRequest>(extraBufferCapacity = 4)
    val documentRequests = documents.asSharedFlow()
    private var documentSerial = 0L
    private var readSerial = 0L
    private var readJob: Job? = null
    private var actionJob: Job? = null
    private var preparationSerial = 0L
    private var command: D1Mutation? = null
    private var commandLease: Long? = null
    var confirmationEpoch = 0L; private set
    fun liveCommand() = command
    fun refreshContext() {
        val ctx = actual()
        if (ctx == mutable.value.context) return
        background(); mutable.value = D1BrowserState(context = ctx)
        if (ctx != null) { refreshDatabases(); refreshTasks() }
    }
    fun refreshDatabases(more: Boolean = false) {
        val ctx = current() ?: return
        val page = if (more) (mutable.value.next as? PageToken.Number)?.value ?: return else 1
        val filter = mutable.value.filter
        read(ctx) { ticket ->
            val result = session(ctx).listDatabases(ctx.account, D1PageRequest(page, name = filter.takeIf { it.isNotBlank() }))
            if (!valid(ctx, ticket) || mutable.value.filter != filter) return@read
            if (result.items.any { it.ref.account != ctx.account }) throw stale()
            mutable.value = mutable.value.copy(databases = ((if (more) mutable.value.databases else emptyList()) + result.items.map(::row)).distinctBy { it.ref }, next = result.next)
        }
    }
    fun filterName(value: String) { if (value.length > 256) return; cancel(); mutable.value = mutable.value.copy(filter = value); refreshDatabases() }
    fun openDatabase(ref: D1DatabaseRef) {
        val ctx = current() ?: return
        if (ref.account != ctx.account) return
        cancel(); content.withdraw()
        read(ctx) { ticket ->
            val database = session(ctx).getDatabase(ref)
            if (!valid(ctx, ticket)) return@read
            if (database.ref != ref) throw stale()
            mutable.value = mutable.value.copy(database = row(database), editor = null, browsePage = 0)
        }
    }
    fun back() {
        background(); mutable.value = mutable.value.copy(database = null, editor = null, loading = false, writing = false)
        refreshDatabases()
    }
    fun begin(editor: D1Editor) {
        if (current() == null || mutable.value.writing) return
        if (editor !in setOf(D1Editor.CREATE, D1Editor.UNDELETE) && mutable.value.database == null) return
        cancel()
        if (!content.begin()) { error(CloudFailure(CloudFailureCode.UNAVAILABLE, "当前窗口无法保护 D1 私有内容")); return }
        content.reveal(false)
        val db = mutable.value.database
        mutable.value = mutable.value.copy(editor = editor, form = D1Form(uuid = db?.ref?.id.orEmpty(), replication = db?.replication ?: D1Replication.disabled), error = null, notice = null)
    }
    fun edit(update: (D1Form) -> D1Form) {
        if (mutable.value.writing) return
        invalidatePrepared(); mutable.value = mutable.value.copy(form = update(mutable.value.form), error = null)
    }
    fun cancel() {
        actionJob?.cancel(); actionJob = null; invalidatePrepared()
        mutable.value = mutable.value.copy(editor = null, writing = false)
    }
    private fun invalidatePrepared() {
        if (!mutable.value.writing) {
            preparationSerial++
            actionJob?.cancel(); actionJob = null
            mutable.value = mutable.value.copy(loading = false)
        }
        confirmationEpoch++; runtime.revoke(); command = null; commandLease = null; content.clearConfirmation()
    }
    fun prepare() {
        val ctx = current() ?: return
        if (mutable.value.writing || !content.secureNow()) return
        val editor = mutable.value.editor ?: return
        val form = mutable.value.form; val db = mutable.value.database?.ref; val epoch = confirmationEpoch
        actionJob?.cancel()
        val preparation = ++preparationSerial
        mutable.value = mutable.value.copy(loading = true, error = null)
        actionJob = scope.launch {
            var created: D1Mutation? = null
            try {
                val mutation = when (editor) {
                    D1Editor.CREATE -> D1Mutation.Create(ctx.account, D1CreateInput(form.name,form.jurisdiction,form.location,form.replication))
                    D1Editor.UNDELETE -> D1Mutation.Undelete(D1DatabaseRef(ctx.account,D1Validation.databaseId(form.uuid)),form.name.takeIf { it.isNotBlank() })
                    D1Editor.PUT -> D1Mutation.PutReplication(db ?: throw stale(),form.replication ?: throw stale())
                    D1Editor.PATCH -> D1Mutation.PatchReplication(db ?: throw stale(),form.replication)
                    D1Editor.DELETE -> D1Mutation.Delete(db ?: throw stale())
                    D1Editor.SQL, D1Editor.RAW -> D1Mutation.Sql(db ?: throw stale(),content.freezeSql(form.json),editor == D1Editor.RAW)
                    D1Editor.IMPORT -> D1Mutation.Import(db ?: throw stale(),content.freezeImport(md5))
                    D1Editor.EXPORT -> D1Mutation.Export(db ?: throw stale(),D1ExportOptions(content.field(D1Field.TABLES).orEmpty().lineSequence().filter { it.isNotEmpty() }.toList(),form.noData,form.noSchema))
                    D1Editor.RESTORE -> D1Mutation.Restore(db ?: throw stale(),if (form.byTimestamp) D1RestorePoint.Timestamp(content.field(D1Field.TIMESTAMP).orEmpty()) else D1RestorePoint.Bookmark(content.field(D1Field.BOOKMARK).orEmpty()))
                }
                created = mutation
                if (!valid(ctx) || epoch != confirmationEpoch) throw stale()
                command = mutation; commandLease = content.lease()
                val preview = runtime.prepare(mutation)
                if (!valid(ctx) || epoch != confirmationEpoch || command !== mutation) throw stale()
                content.confirm(preview)
            } catch (cancel: CancellationException) { release(created); throw cancel }
            catch (failure: Exception) { release(created); if (valid(ctx) && epoch == confirmationEpoch) { invalidatePrepared(); error(failure) } }
            finally { if (valid(ctx) && preparation == preparationSerial) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    fun acceptRisk() = advance { runtime.acceptRisk(it.handle) }
    fun acceptTyped(id: String) = advance { runtime.acceptTyped(it.handle,id) }
    private fun advance(block: (D1ConfirmationView) -> D1ConfirmationView) {
        val previous = content.confirmation() ?: return
        try { content.confirm(block(previous)) } catch (failure: Exception) { invalidatePrepared(); error(failure) }
    }
    fun submit() {
        val ctx = current() ?: return
        val preview = content.confirmation() ?: return
        if (mutable.value.writing || preview.handle.stage != D1ConfirmationStage.FINAL) return
        val epoch = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true, error = null)
        actionJob = scope.launch {
            try {
                val result = runtime.submit(preview.handle)
                if (valid(ctx) && confirmationEpoch == epoch) {
                    content.clearInput(); invalidatePrepared()
                    result.restore?.previousBookmark?.let { content.set(D1Field.PREVIOUS_BOOKMARK,it) }
                    mutable.value = mutable.value.copy(writing = false, loading = false, editor = null,
                        notice = "本次云操作：${result.task.state}。请分别核对完整结果、文件下载及保存状态；不会自动重发。")
                    refreshTasks()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (valid(ctx) && confirmationEpoch == epoch) { content.clearInput(); invalidatePrepared(); mutable.value = mutable.value.copy(writing = false); error(failure); refreshTasks() } }
        }
    }
    fun claimResult(live: D1LiveSelection, result: D1ResultDocument): Boolean {
        val ctx = actual() ?: return false
        if (!valid(ctx) || ctx.connection != live.connection || ctx.credentialRevision != live.credentialRevision || live.epoch != confirmationEpoch) return false
        return content.acceptResult(result.content,commandLease ?: return false)
    }
    fun claimExport(live: D1LiveSelection, result: D1FileOutcome): Boolean {
        val ctx = actual() ?: return false
        if (!valid(ctx) || ctx.connection != live.connection || ctx.credentialRevision != live.credentialRevision || live.epoch != confirmationEpoch) return false
        return content.acceptExport(result,commandLease ?: runCatching { content.lease() }.getOrNull() ?: return false)
    }
    fun showDetails() {
        val ctx = current() ?: return; val ref = mutable.value.database?.ref ?: return
        if (!content.begin()) return
        val lease = content.lease()
        read(ctx) { ticket ->
            val result = session(ctx).getDatabase(ref)
            if (valid(ctx,ticket) && mutable.value.database?.ref == ref) {
                val sealed = store.stage(result.withPrivateDetails(::bytesSource),Long.MAX_VALUE)
                content.acceptResult(sealed,lease)
            }
        }
    }
    fun browse(kind: D1BrowseKind, page: Int = 0) {
        val ctx = current() ?: return; val ref = mutable.value.database?.ref ?: return
        if (!content.begin()) return
        val lease = content.lease(); val epoch = confirmationEpoch
        val request = try { when (kind) {
            D1BrowseKind.SCHEMA -> D1BrowseRequest.schema(page)
            D1BrowseKind.COLUMNS -> D1BrowseRequest.columns(content.field(D1Field.TABLE).orEmpty())
            D1BrowseKind.INDEXES -> D1BrowseRequest.indexes(content.field(D1Field.TABLE).orEmpty())
            D1BrowseKind.ROWS -> D1BrowseRequest.rows(content.field(D1Field.TABLE).orEmpty(),page,
                content.field(D1Field.FILTER_COLUMN)?.takeIf { it.isNotEmpty() }, content.field(D1Field.FILTER_VALUE)?.takeIf { content.field(D1Field.FILTER_COLUMN)?.isNotEmpty() == true },
                content.field(D1Field.SORT_COLUMN)?.takeIf { it.isNotEmpty() },mutable.value.form.descending)
        } } catch (failure: Exception) { error(failure); return }
        read(ctx) { ticket ->
            val result = session(ctx).browse(ref,request)
            if (valid(ctx,ticket) && epoch == confirmationEpoch && mutable.value.database?.ref == ref) {
                content.acceptResult(result.content,lease); mutable.value = mutable.value.copy(browsePage = page, browseKind = kind)
            } else result.close()
        }
    }
    fun readBookmark() {
        val ctx = current() ?: return; val ref = mutable.value.database?.ref ?: return
        if (!content.begin()) return
        val lease = content.lease(); val timestamp = content.field(D1Field.TIMESTAMP)?.takeIf { it.isNotBlank() }; val epoch = confirmationEpoch
        read(ctx) { ticket ->
            val result = session(ctx).getBookmark(ref,timestamp)
            if (valid(ctx,ticket) && epoch == confirmationEpoch && lease == content.lease()) content.set(D1Field.BOOKMARK,result)
        }
    }
    fun restorePrevious() {
        val previous = content.field(D1Field.PREVIOUS_BOOKMARK)?.takeIf { it.isNotEmpty() } ?: return
        begin(D1Editor.RESTORE); content.set(D1Field.BOOKMARK,previous)
    }
    fun pageResult(previous: Boolean = false) {
        val ctx = current() ?: return
        scope.launch { try { content.pageResult(previous) } catch (failure: Exception) { if (valid(ctx)) error(failure) } }
    }
    fun downloadExport() {
        val ctx = current() ?: return; val outcome = content.exportOutcome() ?: return; val lease = content.lease()
        read(ctx) { ticket ->
            val result = download(ctx,outcome)
            if (valid(ctx,ticket)) { if (content.acceptResult(result,lease,sql = true)) { content.clearExport(); documentNotice("完整 SQL 已下载到加密私有结果；尚未保存到外部目录") } }
            else result.close()
        }
    }
    fun document(kind: D1DocumentKind) { if (documentSerial != Long.MAX_VALUE && current() != null && content.secureNow() && !mutable.value.writing) documents.tryEmit(D1DocumentRequest(++documentSerial,kind,mutable.value.editor)) }
    suspend fun importDocument(kind: D1DocumentKind, source: ByteSource, editor: D1Editor? = null) {
        val ctx = current() ?: run { source.close(); throw stale() }
        if (!content.begin()) { source.close(); throw stale() }
        content.importInput(source,when (kind) { D1DocumentKind.SQL_TEXT -> D1InputKind.SQL_TEXT; D1DocumentKind.SQL_JSON -> D1InputKind.SQL_JSON; D1DocumentKind.IMPORT_SQL -> D1InputKind.IMPORT_SQL; else -> { source.close(); throw stale() } })
        if (!valid(ctx)) throw stale()
        mutable.value = mutable.value.copy(editor = if (kind == D1DocumentKind.IMPORT_SQL) D1Editor.IMPORT else if (editor == D1Editor.RAW) D1Editor.RAW else D1Editor.SQL,
            form = mutable.value.form.copy(json = kind == D1DocumentKind.SQL_JSON))
    }
    fun documentNotice(text: String) { mutable.value = mutable.value.copy(notice = text, error = null) }
    fun documentError() = error(CloudFailure(CloudFailureCode.UNAVAILABLE,"文件未完成或未选择；未将云操作标为文件保存成功"))
    fun documentFailure(failure: CloudFailure?) = error(failure ?: stale())
    fun refreshTasks() {
        val ctx = current() ?: return
        scope.launch { try { val tasks = runtime.refreshTasks(); if (valid(ctx)) mutable.value = mutable.value.copy(tasks = tasks.filter { it.scope == ctx.account }) } catch (failure: Exception) { if (valid(ctx)) error(failure) } }
    }
    fun continueTask(id: String) {
        val ctx = current() ?: return
        val task = mutable.value.tasks.singleOrNull { it.id == id }
        if (task?.resourceRef?.externalId != mutable.value.database?.ref?.id || mutable.value.database == null) { error(stale()); return }
        if (!content.begin()) return
        commandLease = content.lease()
        scope.launch { try { runtime.continueFileTask(id); if (valid(ctx)) refreshTasks() } catch (failure: Exception) { if (valid(ctx)) error(failure) } }
    }
    fun observeTask(id: String) {
        val ctx = current() ?: return
        scope.launch { try { val result = runtime.observeTask(id); if (valid(ctx)) documentNotice(if (result == null) "创建结果不能按名称认领；请自行核对" else "已重新读取精确 UUID 的当前信息；不会据此重放未知请求") } catch (failure: Exception) { if (valid(ctx)) error(failure) } }
    }
    fun closeTask(id: String, exact: String, acknowledged: Boolean) {
        val ctx = current() ?: return
        scope.launch { try { runtime.closeUncertain(id,exact,acknowledged); if (valid(ctx)) refreshTasks() } catch (failure: Exception) { if (valid(ctx)) error(failure) } }
    }
    fun background() {
        readSerial++; readJob?.cancel(); readJob = null; actionJob?.cancel(); actionJob = null
        invalidatePrepared(); content.withdraw(); mutable.value = mutable.value.copy(editor = null, loading = false, writing = false)
    }
    fun close() { background(); content.disposed() }
    private fun current(): D1BrowserContext? = actual()?.takeIf { it == mutable.value.context }
    private fun valid(ctx: D1BrowserContext, ticket: Long? = null) = actual() == ctx && mutable.value.context == ctx && (ticket == null || ticket == readSerial)
    private fun read(ctx: D1BrowserContext, work: suspend (Long) -> Unit) {
        val ticket = ++readSerial; readJob?.cancel(); mutable.value = mutable.value.copy(loading = true, error = null)
        readJob = scope.launch {
            try { work(ticket) } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (valid(ctx,ticket)) error(failure) }
            finally { if (valid(ctx,ticket)) mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private fun error(failure: Exception) { val code = (failure as? CloudFailure)?.code
        mutable.value = mutable.value.copy(failureCode = code, error = when (code) {
            CloudFailureCode.UNSUPPORTED_CAPABILITY -> "此操作缺少官方权限或签名传输证据，生产入口暂未开放。"
            CloudFailureCode.PERMISSION_REQUIRED -> "无法证明当前账号的 D1 写权限，未发送写入。"
            CloudFailureCode.CONFLICT -> "范围已变化或存在未结算任务，请核对原任务后重新准备。"
            else -> "D1 请求或完整文件未完成，请检查输入与当前任务；不会自动重发。"
        })
    }
    private fun row(db: D1Database) = D1DatabaseRow(db.ref,db.name,db.fileSize,db.replication)
    private fun release(command: D1Mutation?) { when (command) { is D1Mutation.Sql -> command.input.content.close(); is D1Mutation.Import -> command.input.close(); else -> Unit } }
    private fun stale() = CloudFailure(CloudFailureCode.CONFLICT,"D1 范围或私有会话已改变")
}
