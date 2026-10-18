package com.flarego.ui.r2

import com.flarego.core.model.*
import com.flarego.core.application.r2.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Independent selection and credential generation, supplied by the application owner. */
data class R2BrowserContext(val connection: Connection, val credentialRevision: Long, val routeEpoch: Long) {
    val account get() = AccountScope(connection.id, connection.accountId)
}
enum class R2DocumentKind { UPLOAD, DOWNLOAD }
data class R2DocumentRequest(val id: Long, val context: R2BrowserContext, val bucket: String, val key: String, val kind: R2DocumentKind)
data class R2StagedUploadInfo(val length: Long, val sha256: String)
data class R2BrowserState(
    val context: R2BrowserContext? = null,
    val buckets: R2BucketListState = R2BucketListState(),
    val objects: R2ObjectListState? = null,
    val stagedUpload: R2StagedUploadInfo? = null,
    val notice: String? = null,
    val preview: R2PreparedPreview? = null,
    val writing: Boolean = false,
)

/** Main/UI-dispatcher owner. Never authorizes writes from a successful directory read. */
class R2Browser(
    private val scope: CoroutineScope,
    private val actual: () -> R2BrowserContext?,
    private val writeRuntime: R2WriteRuntime?,
    private val session: suspend (R2BrowserContext) -> R2Session,
) {
    constructor(scope: CoroutineScope, actual: () -> R2BrowserContext?, session: suspend (R2BrowserContext) -> R2Session) : this(scope, actual, null, session)
    private var command: R2Command? = null
    var confirmationEpoch: Long = 0; private set
    fun liveCommand(): R2Command? = command.takeIf { !closed && bound == actual() }
    fun cancelPreview() { if (!mutable.value.writing) invalidateWrite() }
    private fun invalidateWrite() {
        confirmationEpoch++; command = null
        mutable.value = mutable.value.copy(preview = null, writing = false)
    }
    private fun prepareWrite(next: R2Command) {
        val runtime = writeRuntime ?: run { writeError(WRITE_NOTICE); return }
        val context = bound ?: return
        invalidateWrite(); command = next
        val stamp = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true)
        scope.launch {
            try {
                val preview = runtime.prepare(next)
                if (bound == context && actual() == context && stamp == confirmationEpoch)
                    mutable.value = mutable.value.copy(preview = preview, notice = "仅预览，尚未发送；请核对目标后显式确认")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (stamp == confirmationEpoch && actual() == context) writeError(safe(e)) }
            finally { if (stamp == confirmationEpoch && actual() == context) mutable.value = mutable.value.copy(writing = false) }
        }
    }
    fun confirmPreview() {
        if (!current() || mutable.value.writing) return
        val runtime = writeRuntime ?: return
        val preview = mutable.value.preview ?: return
        val context = bound; val stamp = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val task = runtime.confirm(preview.planId)
                if (context == actual() && stamp == confirmationEpoch) {
                    val notice = taskNotice(task)
                    mutable.value = mutable.value.copy(notice = notice, preview = null,
                        objects = mutable.value.objects?.copy(notice = notice))
                    if (command is R2Command.Object && (command as R2Command.Object).write is R2ObjectWrite.Upload) clearUpload()
                    command = null
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (context == actual() && stamp == confirmationEpoch) { writeError(safe(e)); invalidateWrite() } }
            finally { if (context == actual() && stamp == confirmationEpoch) mutable.value = mutable.value.copy(writing = false) }
        }
    }
    fun reconcileTask(id: String) {
        if (!current() || mutable.value.writing) return
        val runtime = writeRuntime ?: return
        val context = bound
        invalidateWrite(); val stamp = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true)
        scope.launch {
            try {
                val task = runtime.reconcile(id)
                if (context == actual() && stamp == confirmationEpoch) mutable.value = mutable.value.copy(notice = taskNotice(task))
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (context == actual() && stamp == confirmationEpoch) writeError(safe(e)) }
            finally { if (context == actual() && stamp == confirmationEpoch) mutable.value = mutable.value.copy(writing = false) }
        }
    }
    private fun taskNotice(task: OperationTask) = when (task.state) {
        TaskState.SUCCEEDED -> "供应商已确认该操作；请刷新列表查看当前结果"
        TaskState.FAILED -> "供应商拒绝操作；请核对任务状态和权限"
        else -> "任务状态：${task.state}。结果未完全确定；只能只读核对，不会自动重试写入"
    }
    private fun writeError(message: String) {
        val old = mutable.value
        mutable.value = old.copy(notice = message, buckets = old.buckets.copy(validationMessage = message),
            objects = old.objects?.copy(validationMessage = message))
    }
    val writeTasks: StateFlow<List<OperationTask>> = writeRuntime?.tasks ?: MutableStateFlow(emptyList())
    val writesAvailable get() = writeRuntime != null

    private val mutable = MutableStateFlow(R2BrowserState())
    val state = mutable.asStateFlow()
    private val launches = MutableStateFlow<R2DocumentRequest?>(null)
    val documentRequests = launches.asStateFlow()
    private var bound: R2BrowserContext? = null
    private var epoch = 0L
    private var job: Job? = null
    private var flight: R2DocumentRequest? = null
    private var flightEpoch = -1L
    private var draftEpoch = 0L
    private var flightDraftEpoch = -1L
    private var nextId = 0L
    private var releaseUpload: (() -> Unit)? = null
    private var uploadContent: R2UploadContent? = null
    private val seen = mutableSetOf<PageToken>()
    private var closed = false

    fun refreshContext() {
        if (closed) return
        val current = actual()
        if (current == bound) return
        epoch++; job?.cancel(); seen.clear(); clearUpload(); invalidateWrite()
        bound = current
        mutable.value = R2BrowserState(context = current, notice = if (writesAvailable) WRITE_READY_NOTICE else WRITE_NOTICE)
        if (current != null) scope.launch { runCatching { writeRuntime?.refreshTasks() } }
        if (current != null) loadBuckets(false)
    }
    private fun valid(context: R2BrowserContext?, generation: Long) =
        !closed && context != null && context == bound && context == actual() && generation == epoch
    private fun current(): Boolean {
        refreshContext()
        return !closed && bound != null && bound == actual()
    }
    // Editing a draft revokes picker/staging admission, not an unrelated in-flight read.
    private fun invalidateDraft() { draftEpoch++; clearUpload() }
    private fun clearUpload() {
        releaseUpload?.invoke(); releaseUpload = null; uploadContent = null
        mutable.value = mutable.value.copy(stagedUpload = null)
    }
    fun close() {
        if (closed) return
        epoch++; closed = true; job?.cancel(); clearUpload(); invalidateWrite(); bound = null
        mutable.value = R2BrowserState(); launches.value = null
    }

    fun onBucketIntent(intent: R2BucketIntent) {
        if (!current() || mutable.value.writing) return
        invalidateWrite()
        val b = mutable.value.buckets
        when (intent) {
            is R2BucketIntent.NameChanged -> mutable.value = mutable.value.copy(buckets = b.copy(draftName = intent.value, validationMessage = null))
            is R2BucketIntent.LocationHintChanged -> mutable.value = mutable.value.copy(buckets = b.copy(draftLocationHint = intent.value))
            is R2BucketIntent.StorageClassChanged -> mutable.value = mutable.value.copy(buckets = b.copy(draftStorageClass = intent.value))
            is R2BucketIntent.RequestDelete -> if (b.rows.any { it.name == intent.name })
                mutable.value = mutable.value.copy(buckets = b.copy(pendingDeleteName = intent.name, typedDeleteName = ""))
            is R2BucketIntent.TypedDeleteNameChanged -> mutable.value = mutable.value.copy(buckets = b.copy(typedDeleteName = intent.value))
            R2BucketIntent.CancelDelete -> mutable.value = mutable.value.copy(buckets = b.copy(pendingDeleteName = null, typedDeleteName = ""))
            R2BucketIntent.SubmitCreate -> prepareWrite(R2Command.Bucket(R2BucketWrite.Create(bound!!.account,
                R2BucketCreateInput(b.draftName, locationHint = b.draftLocationHint, storageClass = b.draftStorageClass))))
            R2BucketIntent.ConfirmDelete -> b.pendingDeleteName?.let {
                prepareWrite(R2Command.Bucket(R2BucketWrite.Delete(bound!!.account, it, b.typedDeleteName)))
            }
            is R2BucketIntent.OpenBucket -> if (b.rows.any { it.name == intent.name }) {
                invalidateDraft(); job?.cancel(); seen.clear()
                mutable.value = mutable.value.copy(objects = R2ObjectListState(intent.name))
                loadObjects(false)
            }
            R2BucketIntent.Refresh -> loadBuckets(false)
            R2BucketIntent.LoadMore -> loadBuckets(true)
        }
    }

    fun onObjectIntent(intent: R2ObjectIntent) {
        if (!current() || (mutable.value.writing && intent != R2ObjectIntent.BackToBuckets)) return
        invalidateWrite()
        val o = mutable.value.objects ?: return
        fun update(next: R2ObjectListState) { mutable.value = mutable.value.copy(objects = next) }
        when (intent) {
            R2ObjectIntent.BackToBuckets -> {
                invalidateDraft(); epoch++; job?.cancel(); seen.clear()
                mutable.value = mutable.value.copy(objects = null)
                // A canceled object load must not leave the bucket list busy.
                mutable.value = mutable.value.copy(buckets = mutable.value.buckets.copy(loading = false))
            }
            R2ObjectIntent.Refresh -> loadObjects(false)
            R2ObjectIntent.LoadMore -> loadObjects(true)
            is R2ObjectIntent.PrefixChanged -> {
                invalidateDraft(); job?.cancel(); seen.clear()
                update(o.copy(prefix = intent.value, selected = emptySet(), pendingBulkDelete = false, pendingDeleteKey = null, loading = false))
                loadObjects(false)
            }
            is R2ObjectIntent.UploadKeyChanged -> { invalidateDraft(); update(o.copy(uploadKey = intent.value, validationMessage = null)) }
            is R2ObjectIntent.RequestUpload -> {
                if (intent.key != o.uploadKey || intent.key.isBlank()) return
                if (flight == null) invalidateDraft()
                requestDocument(o, intent.key, R2DocumentKind.UPLOAD)
            }
            is R2ObjectIntent.RequestDownload -> if (o.rows.any { it.key == intent.key }) requestDocument(o, intent.key, R2DocumentKind.DOWNLOAD)
            R2ObjectIntent.CancelUpload -> { invalidateDraft(); update(o.copy(notice = "已取消文件暂存")) }
            R2ObjectIntent.ConfirmUpload -> {
                if (!writesAvailable) { writeError(WRITE_NOTICE); return }
                val content = uploadContent ?: run { writeError("请重新选择并暂存文件；展示元数据不能提供上传内容"); return }
                prepareWrite(R2Command.Object(R2ObjectWrite.Upload(bound!!.account, o.bucket, o.uploadKey,
                    { content.openSource() }, content.length, null, content.sha256)))
            }
            R2ObjectIntent.ConfirmDelete -> o.pendingDeleteKey?.let {
                prepareWrite(R2Command.Object(R2ObjectWrite.Delete(bound!!.account, o.bucket, it, o.typedDeleteKey)))
            }
            R2ObjectIntent.ConfirmBulkDelete -> {
                val visible = o.selected intersect o.rows.map { it.key }.toSet()
                if (!o.pendingBulkDelete || visible != o.selected) { writeError("请重新选择可见对象并确认"); return }
                prepareWrite(R2Command.Object(R2ObjectWrite.BulkDelete(bound!!.account, o.bucket, visible.toList())))
            }
            is R2ObjectIntent.RequestDelete -> if (o.rows.any { it.key == intent.key }) update(o.copy(pendingDeleteKey = intent.key, typedDeleteKey = ""))
            is R2ObjectIntent.TypedDeleteKeyChanged -> update(o.copy(typedDeleteKey = intent.value))
            R2ObjectIntent.CancelDelete -> update(o.copy(pendingDeleteKey = null, typedDeleteKey = ""))
            is R2ObjectIntent.ToggleSelection -> if (o.rows.any { it.key == intent.key }) {
                update(o.copy(selected = if (intent.key in o.selected) o.selected - intent.key else o.selected + intent.key, pendingBulkDelete = false))
            }
            R2ObjectIntent.SelectAllVisible -> update(o.copy(selected = o.rows.map { it.key }.toSet(), pendingBulkDelete = false))
            R2ObjectIntent.ClearSelection -> update(o.copy(selected = emptySet(), pendingBulkDelete = false))
            R2ObjectIntent.RequestBulkDelete -> {
                val visible = o.selected intersect o.rows.map { it.key }.toSet()
                update(o.copy(selected = visible, pendingBulkDelete = visible.isNotEmpty()))
            }
            R2ObjectIntent.CancelBulkDelete -> update(o.copy(pendingBulkDelete = false))
        }
    }

    private fun loadBuckets(append: Boolean) {
        val context = bound ?: return
        val old = mutable.value.buckets
        if (append && (old.loading || old.nextCursor == null)) return
        val token = if (append) PageToken.Cursor(old.nextCursor!!) else null
        epoch++; val generation = epoch; job?.cancel()
        if (!append) seen.clear()
        mutable.value = mutable.value.copy(buckets = old.copy(loading = true, error = null))
        job = scope.launch {
            try {
                val api = session(context)
                if (!valid(context, generation)) return@launch
                val page = api.listBuckets(context.account, PageRequest(50, token))
                if (!valid(context, generation)) return@launch
                checkCursor(token, page.next)
                val rows = page.items.map { R2BucketRow(it.name, it.raw.string("location"), it.raw.string("storage_class"), it.raw.string("creation_date")) }
                val merged = if (append) old.rows + rows else rows
                if (merged.map { it.name }.toSet().size != merged.size) throw invalidPage()
                mutable.value = mutable.value.copy(buckets = mutable.value.buckets.copy(
                    rows = merged, nextCursor = (page.next as? PageToken.Cursor)?.value, loading = false, error = null))
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (valid(context, generation)) mutable.value = mutable.value.copy(buckets = mutable.value.buckets.copy(loading = false, error = safe(e))) }
        }
    }
    private fun loadObjects(append: Boolean) {
        val context = bound ?: return
        val old = mutable.value.objects ?: return
        if (append && (old.loading || old.nextCursor == null)) return
        val token = if (append) PageToken.Cursor(old.nextCursor!!) else null
        epoch++; val generation = epoch; job?.cancel()
        if (!append) seen.clear()
        mutable.value = mutable.value.copy(objects = old.copy(loading = true, error = null,
            selected = emptySet(), pendingBulkDelete = false, pendingDeleteKey = null))
        job = scope.launch {
            try {
                val api = session(context)
                if (!valid(context, generation)) return@launch
                val page = api.listObjects(context.account, old.bucket, R2ObjectFilter(prefix = old.prefix), PageRequest(50, token))
                if (!valid(context, generation)) return@launch
                checkCursor(token, page.next)
                val rows = page.items.map { R2ObjectRow(it.key, it.size, it.etag, it.lastModified) }
                val merged = if (append) old.rows + rows else rows
                if (merged.map { it.key }.toSet().size != merged.size) throw invalidPage()
                mutable.value = mutable.value.copy(objects = mutable.value.objects!!.copy(
                    rows = merged, nextCursor = (page.next as? PageToken.Cursor)?.value, loading = false, error = null))
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (valid(context, generation)) mutable.value = mutable.value.copy(objects = mutable.value.objects!!.copy(loading = false, error = safe(e))) }
        }
    }
    private fun checkCursor(sent: PageToken?, next: PageToken?) {
        if (sent != null) seen.add(sent)
        if (next != null && (next !is PageToken.Cursor || next in seen)) throw invalidPage()
    }
    private fun requestDocument(o: R2ObjectListState, key: String, kind: R2DocumentKind) {
        if (flight != null) {
            mutable.value = mutable.value.copy(objects = o.copy(validationMessage = "请先完成或取消正在进行的文件选择")); return
        }
        val request = R2DocumentRequest(++nextId, bound ?: return, o.bucket, key, kind)
        flight = request; flightEpoch = epoch; flightDraftEpoch = draftEpoch; launches.value = request
    }
    fun takeDocumentRequest(id: Long): R2DocumentRequest? {
        val request = launches.value?.takeIf { it.id == id } ?: return null
        launches.value = null
        return request
    }
    fun isDocumentCurrent(id: Long): Boolean = flight?.let {
        it.id == id && valid(it.context, flightEpoch) && flightDraftEpoch == draftEpoch && mutable.value.objects?.bucket == it.bucket
    } == true

    fun acceptUpload(id: Long, info: R2StagedUploadInfo, release: () -> Unit) {
        if (!isDocumentCurrent(id) || flight?.kind != R2DocumentKind.UPLOAD) { release(); finishDocument(id); return }
        clearUpload(); releaseUpload = release
        mutable.value = mutable.value.copy(stagedUpload = info, objects = mutable.value.objects!!.copy(notice = "文件已暂存并校验；尚未上传。${if (writesAvailable) WRITE_READY_NOTICE else WRITE_NOTICE}"))
        finishDocument(id)
    }
    /** The Android bridge supplies a bound private snapshot, never a UI source/hash pair. */
    fun acceptUpload(id: Long, content: R2UploadContent) {
        if (!isDocumentCurrent(id) || flight?.kind != R2DocumentKind.UPLOAD) { content.release(); finishDocument(id); return }
        acceptUpload(id, R2StagedUploadInfo(content.length, content.sha256), content::release)
        uploadContent = content
    }
    suspend fun download(id: Long, sink: ByteSink) {
        val request = flight
        // Sink belongs to us on all paths, even rejection before transport acquisition.
        try {
            if (request == null || request.kind != R2DocumentKind.DOWNLOAD || !isDocumentCurrent(id)) throw invalidPage()
            val api = session(request.context)
            if (!isDocumentCurrent(id)) throw invalidPage()
            api.downloadObject(R2ObjectTarget(request.context.account, request.bucket, request.key), sink)
            if (!isDocumentCurrent(id)) throw invalidPage()
        } finally { sink.close() }
    }
    fun documentFinished(id: Long, success: Boolean, cancelled: Boolean = false) {
        if (isDocumentCurrent(id)) {
            mutable.value = mutable.value.copy(objects = mutable.value.objects!!.copy(notice = when {
                cancelled -> "已取消文件选择"
                success -> "对象下载已保存到所选文档"
                else -> "文件操作未完成，请检查授权、网络或可用空间后重试"
            }))
        }
        finishDocument(id)
    }
    private fun finishDocument(id: Long) {
        if (flight?.id == id) { flight = null; launches.value = null }
    }
    private fun safe(e: Exception) = (e as? CloudFailure)?.publicMessage ?: "无法加载 R2 数据，请检查权限与网络后重试"
    private fun invalidPage() = CloudFailure(CloudFailureCode.INVALID_RESPONSE, "R2 请求已失效或分页结果不完整，请刷新后重试")
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    companion object {
        const val WRITE_READY_NOTICE = "写入必须先预览再确认；每次发送前独立验证账号级 R2 写权限。未知结果只读核对，禁止重放。"
        const val WRITE_NOTICE = "R2 写入确认尚未接通；本页只读，创建、删除与上传不会发送（写权限必须另行验证）"
    }
}
