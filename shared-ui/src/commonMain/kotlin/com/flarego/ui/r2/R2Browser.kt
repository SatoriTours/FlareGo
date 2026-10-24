package com.flarego.ui.r2

import com.flarego.core.model.*
import com.flarego.core.application.r2.*
import com.flarego.core.ports.*
import com.flarego.ui.r2.R2CDeletionConfirmation
import com.flarego.ui.r2.R2CConfirmationTicket
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
    // Optional batch B1/B2 dependencies before the trailing `session` parameter, so the
    // historical trailing-lambda `R2Session` constructor keeps working unchanged.
    private val configurationSession: (suspend (R2BrowserContext) -> R2ConfigurationSession)? = null,
    private val domainSession: (suspend (R2BrowserContext) -> R2DomainConfigurationSession)? = null,
    // Batch B3 Sippy dependencies; both optional so every existing construction keeps working.
    private val sippySession: (suspend (R2BrowserContext) -> R2SippySession)? = null,
    private val sippySecureWindow: () -> SecureWindowPort? = { null },
    // Batch C dependencies (all optional: without them the C surfaces stay read-only).
    private val jobsSession: (suspend (R2BrowserContext) -> R2JobSession)? = null,
    private val notificationSession: (suspend (R2BrowserContext) -> R2NotificationSession)? = null,
    private val metricsSession: (suspend (R2BrowserContext) -> R2MetricsPort)? = null,
    private val session: suspend (R2BrowserContext) -> R2Session,
) {
    constructor(scope: CoroutineScope, actual: () -> R2BrowserContext?, session: suspend (R2BrowserContext) -> R2Session) : this(scope, actual, null, null, null, null, session = session)
    /**
     * The one active bucket-settings surface. It requests previews/confirmations from this
     * same route and never holds an executable plan or a raw client.
     */
    val configuration: R2ConfigurationBrowser =
        R2ConfigurationBrowser(
            scope,
            actual,
            configurationSession,
            domainSession,
            requestPreview = { write -> prepareWrite(R2Command.Configuration(write)) },
            requestDomainPreview = { write -> prepareWrite(R2Command.Domain(write)) },
            requestConfirm = { confirmPreview() },
            requestCancel = { cancelPreview() },
            sippySession = sippySession,
            requestSippyPreview = { write -> prepareWrite(R2Command.Sippy(write)) },
            secureWindow = sippySecureWindow,
        )
    /** The app-owned, memory-only Sippy credential editor; the UI never sees a credential value. */
    val sippyCredentials get() = configuration.sippyCredentials

    private var command: R2Command? = null
    var confirmationEpoch: Long = 0; private set
    /** The pending batch C command, if the prepared preview belongs to the C surface. */
    private var cCommand: R2Command? = null
    private val cBucket = { cBucketName }
    private var cBucketName: String? = null

    /**
     * The batch C root: typed reads plus the two write families, with the three-stage deletion
     * owner as its execution guard. It never prepares or confirms anything by itself — both go
     * through this browser's root path below.
     */
    val c: R2CController =
        R2CController(
            scope,
            actual,
            prepareCommand = { next -> prepareC(next) },
            confirmCommand = { ticket -> confirmC(ticket) },
            bucket = cBucket,
            runtime = writeRuntime,
            jobs = jobsSession,
            notifications = notificationSession,
            metrics = metricsSession,
            requestInvalidate = { invalidateWrite() },
        )

    /** Selects the bucket the C surface addresses (a route choice, never parsed from a display name). */
    fun onCBucketSelected(bucket: String) {
        cBucketName = bucket
        c.onTargetChanged(bucket)
    }

    /**
     * Root prepare of one C command.
     *
     * It marks the preview as a C one (so the generic confirmation path refuses it) and uses the same
     * one prepare path as every other family.
     */
    private fun prepareC(next: R2Command): Boolean {
        if (closed || writeRuntime == null || bound != actual()) return false
        if (!c.state.value.open || c.liveCommand() != next || mutable.value.writing) return false
        prepareWrite(next)
        return command === next
    }

    /**
     * Root confirm of one C command.
     *
     * This is the only reachable path to a C write. A destructive command (prefix delete, notification
     * selected/all removal) additionally requires a ticket from the three-stage owner that the C
     * controller consumed exactly once and that is bound to the current draft and live route/credential;
     * the generic [confirmPreview] refuses every C command, so no button, old callback or generic
     * confirmation can bypass the stages.
     */
    private fun confirmC(ticket: R2CConfirmationTicket?): Job? {
        val pending = cCommand ?: return null
        if (!current() || mutable.value.writing || !c.isReadyFor(pending)) return null
        val destructive =
            when (pending) {
                is R2Command.Job -> pending.write is R2JobWrite.PrefixDelete
                is R2Command.Notification -> pending.write is R2NotificationWrite.Delete
                else -> false
            }
        if (destructive && ticket == null) return null
        if (ticket != null) {
            if (!c.matchesTicket(pending, ticket)) return null
        }
        val runtime = writeRuntime ?: return null
        val preview = mutable.value.preview ?: return null
        val context = bound; val stamp = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val task = runtime.confirm(preview.planId)
                if (context == actual() && stamp == confirmationEpoch) {
                    val notice = taskNoticeFor(task)
                    mutable.value = mutable.value.copy(preview = null, notice = notice)
                    command = null
                    cCommand = null
                    c.onRootSettled(notice)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (context == actual() && stamp == confirmationEpoch) {
                    val message = safe(e)
                    writeError(message); invalidateWrite(); c.onRootRejected(message)
                }
            } finally {
                if (context == actual() && stamp == confirmationEpoch) mutable.value = mutable.value.copy(writing = false)
            }
        }
    }

    private fun taskNoticeFor(task: com.flarego.core.model.OperationTask): String =
        when (task.state) {
            com.flarego.core.model.TaskState.SUCCEEDED -> "只读核对为成功（仅该作业/该配置）"
            com.flarego.core.model.TaskState.ACCEPTED -> "已受理，仍在进行；请稍后只读刷新"
            com.flarego.core.model.TaskState.UNKNOWN -> "结果尚未确定，只能只读核对，不会自动重试"
            com.flarego.core.model.TaskState.ACTION_REQUIRED -> "需要人工核对（可能已有副作用），不会重试"
            com.flarego.core.model.TaskState.PARTIAL -> "部分成功；失败/跳过计数以供应商结果为准"
            com.flarego.core.model.TaskState.FAILED -> "已被拒绝或未发送（可信本地证据）"
            else -> "状态：${task.state.name}"
        }

    /**
     * The actual editor command of the live route.
     *
     * A Sippy route resolves it from the current private credential draft and the current window
     * protection — never from a prepared command or a plan id. When that draft is incomplete or
     * unprotected there is no live command, so a stale confirmation cannot be completed.
     */
    fun liveCommand(): R2Command? {
        if (closed || bound != actual()) return null
        if (c.state.value.open) return c.liveCommand()
        configuration.liveSippyCommand()?.let { return it }
        return command
    }
    fun cancelPreview() { if (!mutable.value.writing) invalidateWrite() }
    private fun invalidateWrite() {
        mutable.value.preview?.let { writeRuntime?.discardPreview(it.planId) }
        confirmationEpoch++; command = null; cCommand = null
        mutable.value = mutable.value.copy(preview = null, writing = false)
        // The settings surface shares this one route, so its display preview dies with it.
        configuration.onRootInvalidated()
        c.onRootInvalidated()
    }
    private fun prepareWrite(next: R2Command) {
        val runtime = writeRuntime ?: run { writeError(WRITE_NOTICE); return }
        val context = bound ?: return
        invalidateWrite(); command = next
        if (next is R2Command.Job || next is R2Command.Notification) {
            cCommand = next
            c.onRootPreparing(next)
        }
        val stamp = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true)
        scope.launch {
            try {
                val preview = runtime.prepare(next)
                if (bound == context && actual() == context && stamp == confirmationEpoch) {
                    mutable.value = mutable.value.copy(preview = preview, notice = "仅预览，尚未发送；请核对目标后显式确认")
                    when (next) {
                        is R2Command.Configuration -> configuration.onRootPreview(preview, next.write)
                        is R2Command.Domain -> configuration.onRootDomainPreview(preview, next.write)
                        is R2Command.Sippy -> configuration.onRootSippyPreview(preview, next.write)
                        is R2Command.Job -> c.onRootPreview(next,
                            runtime.jobPreviewDetail(preview.planId).describe() + "；" + preview.detail.impact + "；" + preview.detail.concurrencyWarning)
                        is R2Command.Notification -> c.onRootPreview(next,
                            "账号：${preview.detail.scope}；桶：${preview.detail.bucket}；" +
                                runtime.notificationPreviewDetail(preview.planId).describe() +
                                (next.write as? R2NotificationWrite.Append)?.rules.orEmpty().joinToString("；", prefix = "；新增规则范围：") {
                                    "${it.actions.joinToString("+")}；prefix=${it.prefix ?: "（全部对象）"}；suffix=${it.suffix ?: "（不限）"}；description=${it.description ?: "（无）"}"
                                } + "；" + preview.detail.impact + "；" + preview.detail.concurrencyWarning)
                        else -> Unit
                    }
                } else runtime.discardPreview(preview.planId)
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                if (stamp == confirmationEpoch && actual() == context) {
                    writeError(safe(e))
                    configuration.onRootInvalidated()
                    if (next is R2Command.Job || next is R2Command.Notification) c.onRootRejected(safe(e))
                }
            }
            finally {
                if (stamp == confirmationEpoch && actual() == context) {
                    mutable.value = mutable.value.copy(writing = false)
                    configuration.onRootWriting(false)
                }
            }
        }
    }
    /**
     * Confirms the current preview exactly once.
     *
     * Returns the app-owned job of *this* confirmation (null when the guard refused), so a caller can
     * observe the real outcome of the operation it started — never to re-execute it, never to skip the
     * single-flight guard. The UI callbacks adapt it to `Unit`.
     */
    fun confirmPreview(): Job? {
        // A batch C preview is only ever confirmed through the C root path (with its own guard).
        if (command is R2Command.Job || command is R2Command.Notification || cCommand != null) return null
        if (!current() || mutable.value.writing) return null
        val runtime = writeRuntime ?: return null
        val preview = mutable.value.preview ?: return null
        val context = bound; val stamp = confirmationEpoch
        mutable.value = mutable.value.copy(writing = true)
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val task = runtime.confirm(preview.planId)
                if (context == actual() && stamp == confirmationEpoch) {
                    // Batch B1 and B2 domain writes are the same confirmation family: both must
                    // settle the configuration surface, not only the root notice.
                    val wasConfiguration = command is R2Command.Configuration || command is R2Command.Domain ||
                        command is R2Command.Sippy
                    val notice = if (wasConfiguration) configurationTaskNotice(task) else taskNotice(task)
                    mutable.value = mutable.value.copy(notice = notice, preview = null,
                        objects = mutable.value.objects?.copy(notice = notice))
                    if (command is R2Command.Object && (command as R2Command.Object).write is R2ObjectWrite.Upload) clearUpload()
                    command = null
                    if (wasConfiguration) {
                        configuration.onRootSettled(notice, reload = task.state == TaskState.SUCCEEDED)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                if (context == actual() && stamp == confirmationEpoch) {
                    writeError(safe(e))
                    invalidateWrite()
                }
            }
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
                if (context == actual() && stamp == confirmationEpoch) {
                    // A configuration/domain task reconciles onto the configuration surface too.
                    val configurationTask = task.actionKey in R2TaskTargets.CONFIGURATION_WRITE_ACTIONS ||
                        task.actionKey in R2TaskTargets.DOMAIN_WRITE_ACTIONS ||
                        task.actionKey in R2TaskTargets.SIPPY_ACTIONS
                    val notice = if (configurationTask) configurationTaskNotice(task) else taskNotice(task)
                    mutable.value = mutable.value.copy(notice = notice)
                    if (configurationTask) {
                        configuration.onRootSettled(notice, reload = task.state == TaskState.SUCCEEDED)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (context == actual() && stamp == confirmationEpoch) writeError(safe(e)) }
            finally { if (context == actual() && stamp == confirmationEpoch) mutable.value = mutable.value.copy(writing = false) }
        }
    }
    /** A configuration result is only the verified configuration state, never migration history. */
    private fun configurationTaskNotice(task: OperationTask) = when (task.state) {
        TaskState.SUCCEEDED ->
            "配置写入已按同锁回读核对为当前可观察状态；请刷新查看。这不宣称对象迁移、供应商历史或内部原子隔离，也不宣称域名/SSL/连通性就绪"
        TaskState.FAILED -> "配置写入未发送，或已有可信证据证明未生效；再次操作须重新预览确认"
        else -> "配置任务状态：${task.state}。结果尚未完全确定，只能只读核对，不会自动重试写入"
    }

    private fun taskNotice(task: OperationTask) = when (task.state) {
        TaskState.SUCCEEDED -> "供应商已确认该操作；请刷新列表查看当前结果"
        TaskState.FAILED -> "操作未成功；请核对当前输入、权限和任务状态，再次操作须重新预览确认"
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
        configuration.onContextChanged(current)
        c.close()
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
        configuration.close()
        c.close()
        mutable.value = R2BrowserState(); launches.value = null
    }

    /**
     * The single route for the bucket settings surface.
     *
     * Every input/target/panel change revokes the prepared preview before the new state is
     * applied, so leaving the panel (or editing any field) can never leave a live
     * configuration command behind.
     */
    fun onConfigurationIntent(intent: R2ConfigurationIntent) {
        if (!current() || (mutable.value.writing &&
                intent != R2ConfigurationIntent.CancelPreview &&
                intent != R2ConfigurationIntent.Close)
        ) {
            return
        }
        when (intent) {
            R2ConfigurationIntent.Confirm, R2ConfigurationIntent.CancelPreview -> configuration.onIntent(intent)
            else -> {
                invalidateWrite()
                configuration.onIntent(intent)
            }
        }
    }

    fun onBucketIntent(intent: R2BucketIntent) {
        if (!current() || mutable.value.writing) return
        invalidateWrite()
        val b = mutable.value.buckets
        when (intent) {
            is R2BucketIntent.CreateModeChanged -> mutable.value = mutable.value.copy(buckets = b.copy(
                createMode = intent.mode,
                draftLocationHint = if (intent.mode == R2BucketCreateMode.PUT) null else b.draftLocationHint,
                editingBucketName = null, editingOriginalStorageClass = null,
                pendingDeleteName = null, typedDeleteName = "", validationMessage = null))
            is R2BucketIntent.RequestStorageClassEdit -> {
                val row = b.rows.singleOrNull { it.name == intent.name }
                if (row == null || row.storageClass !in b.storageClasses) {
                    mutable.value = mutable.value.copy(buckets = b.copy(editingBucketName = null,
                        editingOriginalStorageClass = null, validationMessage = if (row == null)
                            "目标不在当前存储桶列表，请刷新后再编辑" else "当前存储类型未知或缺失，只能只读查看"))
                    return
                }
                mutable.value = mutable.value.copy(buckets = b.copy(editingBucketName = row.name,
                    editingOriginalStorageClass = row.storageClass, editingStorageClass = row.storageClass!!,
                    pendingDeleteName = null, typedDeleteName = "", validationMessage = null))
            }
            is R2BucketIntent.EditStorageClassChanged -> if (b.editingBucketName != null)
                mutable.value = mutable.value.copy(buckets = b.copy(editingStorageClass = intent.value, validationMessage = null))
            R2BucketIntent.CancelStorageClassEdit -> mutable.value = mutable.value.copy(buckets = b.copy(
                editingBucketName = null, editingOriginalStorageClass = null, validationMessage = null))
            R2BucketIntent.SubmitStorageClassEdit -> {
                val target = b.editingBucketName?.takeIf { name -> b.rows.any { it.name == name } }
                    ?: run { writeError("请先选择当前列表中的存储桶"); return }
                prepareWrite(R2Command.Bucket(R2BucketWrite.PatchStorageClass(bound!!.account, target, b.editingStorageClass)))
            }
            is R2BucketIntent.NameChanged -> mutable.value = mutable.value.copy(buckets = b.copy(draftName = intent.value, validationMessage = null))
            is R2BucketIntent.LocationHintChanged -> if (b.createMode == R2BucketCreateMode.POST)
                mutable.value = mutable.value.copy(buckets = b.copy(draftLocationHint = intent.value))
            is R2BucketIntent.StorageClassChanged -> mutable.value = mutable.value.copy(buckets = b.copy(draftStorageClass = intent.value))
            is R2BucketIntent.RequestDelete -> if (b.rows.any { it.name == intent.name })
                mutable.value = mutable.value.copy(buckets = b.copy(pendingDeleteName = intent.name, typedDeleteName = "",
                    editingBucketName = null, editingOriginalStorageClass = null))
            is R2BucketIntent.TypedDeleteNameChanged -> mutable.value = mutable.value.copy(buckets = b.copy(typedDeleteName = intent.value))
            R2BucketIntent.CancelDelete -> mutable.value = mutable.value.copy(buckets = b.copy(pendingDeleteName = null, typedDeleteName = ""))
            R2BucketIntent.SubmitCreate -> prepareWrite(R2Command.Bucket(when (b.createMode) {
                R2BucketCreateMode.POST -> R2BucketWrite.Create(bound!!.account,
                    R2BucketCreateInput(b.draftName, locationHint = b.draftLocationHint, storageClass = b.draftStorageClass))
                R2BucketCreateMode.PUT -> R2BucketWrite.CreateByName(bound!!.account, b.draftName, b.draftStorageClass)
            }))
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
