package com.flarego.core.application.r2

import com.flarego.core.application.operations.CanonicalJson
import com.flarego.core.application.operations.ChangePlan
import com.flarego.core.application.operations.ChangePlans
import com.flarego.core.application.operations.ConfirmedPlan
import com.flarego.core.application.operations.LiveOperationContext
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationContextResolver
import com.flarego.core.application.operations.OperationCoordinator
import com.flarego.core.application.operations.Reconciliation
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudScope
import com.flarego.core.model.OperationTask
import com.flarego.core.model.PageRequest
import com.flarego.core.model.PageToken
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2_MAX_BULK_DELETE
import com.flarego.core.model.R2_MAX_SINGLE_OBJECT_BYTES
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.TaskState
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2BucketWriteKey
import com.flarego.core.model.r2ObjectWriteKey
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2Session
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Previews and executes the typed R2 writes through the shared P1 machinery.
 *
 * It reuses [ChangePlans] and [OperationCoordinator] rather than inventing a second
 * confirmation mechanism: a prepared change is inert, one confirmation admits at most
 * one send, and every uncertain outcome is reconciled by reading before anything else.
 * Payloads and provider snapshots stay in memory; only safe locators are persisted.
 *
 * Bucket lifecycle writes and object writes are separate products of the same pattern:
 * a bucket change is keyed by the bucket, an object change by the object locator, and
 * both block on any unsettled R2 write for the same bucket.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2Controller(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val session: suspend (AccountScope) -> R2Session,
) {
    private data class Prepared(
        val plan: ChangePlan,
        val write: R2BucketWrite,
        val name: String,
        val before: R2BucketDocument?,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
    )

    private data class PreparedObject(
        val plan: ChangePlan,
        val write: R2ObjectWrite,
        val bucket: String,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
    )

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())
    private val preparedObjects = AtomicReference<Map<String, PreparedObject>>(emptyMap())

    // ---------------------------------------------------------------- bucket lifecycle

    /** Pure function of the actual scope and input, for the independent product resolver. */
    fun requestFingerprint(write: R2BucketWrite): String = digest(semantic(write))

    suspend fun prepare(write: R2BucketWrite): ChangePlan {
        validateDelete(write)
        val live = requireCurrent(write)
        requireNoUnsettled(write)
        requireSameLive(live)
        val api = session(write.scope)
        requireSameLive(live)
        val name = validatedName(write)
        val before =
            when (write) {
                is R2BucketWrite.Create, is R2BucketWrite.CreateByName -> {
                    requireAbsent(api, write.scope, name)
                    null
                }
                else -> ownedSnapshot(api.getBucket(write.scope, name), write.scope, name)
            }
        validateTarget(write, before)
        requireSameLive(live)
        val plan =
            plans.prepare(
                scope = write.scope,
                actionKey = r2BucketWriteKey(write),
                resourceRef = ResourceRef(write.scope, BUCKET_KIND, name),
                beforeHash = before?.let { digest(it.raw) },
                desiredHash = digest(desired(write)),
                requestFingerprint = requestFingerprint(write),
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val entry = Prepared(plan, write, name, before, live, previewOf(write, before))
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table + (plan.id to entry))) break
        }
        return plan
    }

    fun preview(planId: String): R2ChangePreview = entry(planId).preview

    suspend fun submit(write: R2BucketWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, write, confirmed)
        requireNoUnsettled(p.write, p.plan.id)
        requireBound(p, write, confirmed)
        val guard = { requireBound(p, write, confirmed) }
        return coordinator.executeGuarded(confirmed, guard) { composed ->
            // Session/credential acquisition can suspend; it must finish before final checks.
            val api = session(p.write.scope)
            guard()
            // The resource lock is held now. Another prepared confirmation may have settled
            // UNKNOWN while this operation waited behind it.
            requireNoUnsettled(p.write, p.plan.id)
            val creating = p.write is R2BucketWrite.Create || p.write is R2BucketWrite.CreateByName
            if (creating) requireAbsent(api, p.write.scope, p.name)
            api.write(p.write) { observed ->
                if (!creating) {
                    val current = ownedSnapshot(observed ?: throw invalidInputFailure(), p.write.scope, p.name)
                    validateTarget(p.write, current)
                    if (digest(current.raw) != p.plan.beforeHash) {
                        throw CloudFailure(CloudFailureCode.CONFLICT, "桶状态已变化，请刷新并重新预览确认")
                    }
                } else if (observed != null) {
                    throw invalidInputFailure()
                }
                // No suspension between this composed final guard and the typed send.
                composed()
            }
        }
    }

    /**
     * Reads only, including after a process or controller recreation. Persisted scope,
     * locator and desired hash identify what to verify; they never reconstruct a live
     * context, a payload or a confirmation.
     */
    suspend fun reconcile(taskId: String): OperationTask =
        coordinator.reconcile(taskId) { task ->
            val live = resolver.current() ?: throw invalidInputFailure()
            if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
            ScopeGuard.requireOwned(task.scope, live.connection)
            requireSameLive(live)
            val reference = task.resourceRef ?: return@reconcile Reconciliation.StillUnknown
            if (task.scope !is AccountScope || reference.scope != task.scope || reference.kind != BUCKET_KIND) {
                return@reconcile Reconciliation.StillUnknown
            }
            if (task.desiredHash == null || reference.externalId.isBlank()) return@reconcile Reconciliation.StillUnknown
            val evidence =
                try {
                    val api = session(task.scope as AccountScope)
                    requireSameLive(live)
                    when (task.actionKey) {
                        "r2-delete-bucket" -> {
                            if (bucketPresent(api, task.scope, reference.externalId)) {
                                Reconciliation.StillUnknown
                            } else {
                                matchCandidate(task, buildJsonObject { put("deleted", true) })
                            }
                        }
                        else -> {
                            val actual = ownedSnapshot(api.getBucket(task.scope, reference.externalId), task.scope, reference.externalId)
                            matchCandidate(task, observedState(actual))
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Permission failures, missing ownership, malformed reads and conflicts are
                    // no proof of application, so the task stays UNKNOWN without a replay.
                    Reconciliation.StillUnknown
                }
            requireSameLive(live)
            evidence
        }

    // -------------------------------------------------------------------- object writes

    /** Pure function of the actual scope, target and staged content, for the product resolver. */
    fun requestObjectFingerprint(write: R2ObjectWrite): String = digest(objectSemantic(write))

    /**
     * Prepares one object write.
     *
     * The REST API has no cheap "head object", so an upload records no before-state: its
     * binding is the frozen target plus the staged content fingerprint, which is what stops
     * a file that changed after the preview from riding an earlier approval. A single delete
     * additionally requires the user to type the exact key.
     */
    suspend fun prepareObject(write: R2ObjectWrite): ChangePlan {
        validateObjectWrite(write)
        val live = requireCurrentObject(write)
        requireNoUnsettledObject(write)
        requireSameLive(live)
        val api = session(write.scope)
        requireSameLive(live)
        val bucket = validateR2BucketName(write.bucket)
        // The bucket must still be ours before any object request can be prepared.
        ownedSnapshot(api.getBucket(write.scope, bucket), write.scope, bucket)
        validateObjectWrite(write)
        requireSameLive(live)
        val plan =
            plans.prepare(
                scope = write.scope,
                actionKey = r2ObjectWriteKey(write),
                resourceRef = ResourceRef(write.scope, OBJECT_KIND, objectLocator(bucket, objectKeyOf(write))),
                beforeHash = null,
                desiredHash = digest(objectDesired(write)),
                requestFingerprint = requestObjectFingerprint(write),
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val entry = PreparedObject(plan, write, bucket, live, objectPreviewOf(write))
        while (true) {
            val table = preparedObjects.load()
            if (preparedObjects.compareAndSet(table, table + (plan.id to entry))) break
        }
        return plan
    }

    fun previewObject(planId: String): R2ChangePreview = objectEntry(planId).preview

    suspend fun submitObject(write: R2ObjectWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = objectEntry(confirmed.plan.id)
        requireBoundObject(p, write, confirmed)
        requireNoUnsettledObject(p.write, p.plan.id)
        requireBoundObject(p, write, confirmed)
        val guard = { requireBoundObject(p, write, confirmed) }
        return coordinator.executeGuarded(confirmed, guard) { composed ->
            val api = session(p.write.scope)
            guard()
            requireNoUnsettledObject(p.write, p.plan.id)
            ownedSnapshot(api.getBucket(p.write.scope, p.bucket), p.write.scope, p.bucket)
            api.writeObject(p.write) { observed ->
                // An object write never pretends to have an existing document to compare.
                if (observed != null) throw invalidInputFailure()
                // Re-check the frozen target after the last suspending call.
                requireBoundObject(p, write, confirmed)
                composed()
            }
        }
    }

    /**
     * Read-only reconciliation for object writes.
     *
     * Membership is decided by exhausting the provider's listing for exactly that key
     * prefix, so absence is real evidence and never an inference from one page. A bulk
     * delete gets [Reconciliation.ActionRequired]: proving that up to a thousand keys are
     * gone needs a per-key readback the app cannot do cheaply, and claiming otherwise would
     * be worse than telling the user to check the console.
     */
    suspend fun reconcileObject(taskId: String): OperationTask =
        coordinator.reconcile(taskId) { task ->
            val live = resolver.current() ?: throw invalidInputFailure()
            if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
            ScopeGuard.requireOwned(task.scope, live.connection)
            requireSameLive(live)
            val reference = task.resourceRef ?: return@reconcile Reconciliation.StillUnknown
            if (task.scope !is AccountScope || reference.scope != task.scope || reference.kind != OBJECT_KIND) {
                return@reconcile Reconciliation.StillUnknown
            }
            val bucket = bucketOfObjectRef(reference) ?: return@reconcile Reconciliation.StillUnknown
            val key = keyOfObjectRef(reference)
            if (task.desiredHash == null) return@reconcile Reconciliation.StillUnknown
            val evidence =
                try {
                    val api = session(task.scope as AccountScope)
                    requireSameLive(live)
                    when (task.actionKey) {
                        "r2-delete-objects" -> Reconciliation.ActionRequired
                        "r2-delete-object" -> {
                            if (key == null) return@reconcile Reconciliation.StillUnknown
                            if (objectPresent(api, task.scope, bucket, key)) {
                                Reconciliation.StillUnknown
                            } else {
                                matchCandidate(task, buildJsonObject { put("bucket", bucket); put("deleted", true) })
                            }
                        }
                        "r2-put-object" -> {
                            if (key != null && objectPresent(api, task.scope, bucket, key)) {
                                matchCandidate(task, buildJsonObject { put("bucket", bucket); put("key", key) })
                            } else {
                                Reconciliation.StillUnknown
                            }
                        }
                        else -> Reconciliation.StillUnknown
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Reconciliation.StillUnknown
                }
            requireSameLive(live)
            evidence
        }

    // ------------------------------------------------------------------------ internals

    private fun entry(id: String) = prepared.load()[id] ?: throw invalidInputFailure()

    private fun objectEntry(id: String) = preparedObjects.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2BucketWrite, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan || write != p.write || requestFingerprint(write) != p.plan.requestFingerprint ||
            r2BucketWriteKey(write) != p.plan.actionKey || write.scope != p.plan.scope ||
            p.plan.beforeHash != p.before?.let { digest(it.raw) } || p.plan.desiredHash != digest(desired(p.write))
        ) {
            throw invalidInputFailure()
        }
        validateDelete(write)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireBoundObject(p: PreparedObject, write: R2ObjectWrite, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan || !sameObjectWrite(write, p.write) ||
            requestObjectFingerprint(write) != p.plan.requestFingerprint ||
            r2ObjectWriteKey(write) != p.plan.actionKey || write.scope != p.plan.scope ||
            p.plan.desiredHash != digest(objectDesired(p.write))
        ) {
            throw invalidInputFailure()
        }
        validateObjectWrite(write)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    /** The one-shot stream is never compared; every decision-relevant field is. */
    private fun sameObjectWrite(left: R2ObjectWrite, right: R2ObjectWrite): Boolean =
        when {
            left is R2ObjectWrite.Upload && right is R2ObjectWrite.Upload ->
                left.scope == right.scope && left.bucket == right.bucket && left.key == right.key &&
                    left.length == right.length && left.contentType == right.contentType &&
                    left.contentFingerprint == right.contentFingerprint

            left is R2ObjectWrite.Delete && right is R2ObjectWrite.Delete ->
                left.scope == right.scope && left.bucket == right.bucket && left.key == right.key && left.typedKey == right.typedKey

            left is R2ObjectWrite.BulkDelete && right is R2ObjectWrite.BulkDelete ->
                left.scope == right.scope && left.bucket == right.bucket && left.keys == right.keys

            else -> false
        }

    private fun requireCurrent(write: R2BucketWrite): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != write.scope || live.requestFingerprint != requestFingerprint(write)) throw invalidInputFailure()
        ScopeGuard.requireOwned(write.scope, live.connection)
        if (!resolver.isLive(write.scope, live.credentialRevision)) throw invalidInputFailure()
        return live
    }

    private fun requireCurrentObject(write: R2ObjectWrite): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != write.scope || live.requestFingerprint != requestObjectFingerprint(write)) throw invalidInputFailure()
        ScopeGuard.requireOwned(write.scope, live.connection)
        if (!resolver.isLive(write.scope, live.credentialRevision)) throw invalidInputFailure()
        return live
    }

    private fun requireSameLive(expected: LiveOperationContext) {
        if (resolver.current() != expected || !resolver.isLive(expected.scope, expected.credentialRevision)) throw invalidInputFailure()
        ScopeGuard.requireOwned(expected.scope, expected.connection)
    }

    private fun validateDelete(write: R2BucketWrite) {
        if (write is R2BucketWrite.Delete && (write.name.isBlank() || write.name != write.typedName)) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "请输入与目标完全一致的桶名后再确认删除")
        }
    }

    private fun validateTarget(write: R2BucketWrite, before: R2BucketDocument?) {
        validateDelete(write)
        if (write is R2BucketWrite.Delete && before?.name != write.name) throw scopeMismatchFailure()
        if (write is R2BucketWrite.PatchStorageClass && before?.raw?.get("storage_class") == JsonPrimitive(write.storageClass)) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "该桶的默认存储等级没有变化，请修改后再预览")
        }
    }

    private fun validateObjectWrite(write: R2ObjectWrite) {
        validateR2BucketName(write.bucket)
        when (write) {
            is R2ObjectWrite.Upload -> {
                validateR2ObjectKey(write.key)
                if (!write.contentFingerprint.matches(SHA256)) {
                    throw CloudFailure(CloudFailureCode.INVALID_INPUT, "待上传文件的内容指纹无效，请重新选择文件")
                }
                if (write.length !in 0..R2_MAX_SINGLE_OBJECT_BYTES) {
                    throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "单个对象超过 REST 直传上限，分片上传属于后续批次")
                }
            }

            is R2ObjectWrite.Delete -> {
                validateR2ObjectKey(write.key)
                if (write.typedKey.isBlank() || write.typedKey != write.key) {
                    throw CloudFailure(CloudFailureCode.INVALID_INPUT, "请输入与目标完全一致的对象 key 后再确认删除")
                }
            }

            is R2ObjectWrite.BulkDelete -> {
                if (write.keys.size !in 1..R2_MAX_BULK_DELETE) throw invalidInputFailure()
                if (write.keys.toSet().size != write.keys.size) {
                    throw CloudFailure(CloudFailureCode.INVALID_INPUT, "批量删除列表存在重复 key，请先去重")
                }
                write.keys.forEach { validateR2ObjectKey(it) }
            }
        }
    }

    private fun validatedName(write: R2BucketWrite): String =
        when (write) {
            is R2BucketWrite.Create -> validateR2CreateInput(write.input).name
            is R2BucketWrite.CreateByName -> validateR2BucketName(write.name)
            is R2BucketWrite.PatchStorageClass -> validateR2BucketName(write.name)
            is R2BucketWrite.Delete -> validateR2BucketName(write.name)
        }

    private fun bucketFor(write: R2BucketWrite): String = validatedName(write)

    private fun bucketFor(write: R2ObjectWrite): String = validateR2BucketName(write.bucket)

    /** Persisted uncertainty survives controller recreation; it permits reads only. */
    private suspend fun requireNoUnsettled(write: R2BucketWrite, excluding: String? = null) {
        if (anyUnsettledForBucket(write.scope, bucketFor(write), excluding)) throw unsettledFailure()
    }

    private suspend fun requireNoUnsettledObject(write: R2ObjectWrite, excluding: String? = null) {
        if (anyUnsettledForBucket(write.scope, bucketFor(write), excluding)) throw unsettledFailure()
    }

    /**
     * Any unsettled R2 write for the same bucket blocks a new one, whether it addressed the
     * bucket itself or one of its objects: an unresolved object delete can still change what
     * a bucket operation sees.
     */
    private suspend fun anyUnsettledForBucket(scope: AccountScope, bucket: String, excluding: String?): Boolean =
        withContext(storeDispatcher) {
            store.list(scope.connectionId).any { task ->
                task.id != excluding && task.scope == scope && task.actionKey in ALL_ACTIONS &&
                    task.resourceRef.blocksBucket(scope, bucket) && task.state in UNSETTLED_STATES
            }
        }

    private fun ResourceRef?.blocksBucket(scope: AccountScope, bucket: String): Boolean =
        when (this) {
            null -> true
            else -> this.scope == scope && when (kind) {
                BUCKET_KIND -> externalId == bucket
                OBJECT_KIND -> externalId.startsWith("$bucket/")
                else -> false
            }
        }

    private fun unsettledFailure() =
        CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "该桶的上一操作尚未核对，请先核对结果，不要再次发送", uncertain = true)

    /**
     * ScopeGuard only proves the local connection binding, so the provider document is
     * checked separately: the scope must be the requested account and the returned name must
     * be the requested bucket.
     */
    private fun ownedSnapshot(document: R2BucketDocument, scope: AccountScope, name: String): R2BucketDocument {
        val raw = Json.parseToJsonElement(document.raw.toString()).jsonObject
        val providerName = (raw["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (document.scope != scope || document.name != name || providerName != name) throw scopeMismatchFailure()
        return document.copy(raw = raw)
    }

    /**
     * A 404 is the only sound proof of absence here: the bucket list carries no total, so a
     * partial list may never be read as "does not exist".
     */
    private suspend fun requireAbsent(api: R2Session, scope: AccountScope, name: String) {
        if (bucketPresent(api, scope, name)) throw CloudFailure(CloudFailureCode.CONFLICT, "该账号已存在同名桶，请先查看现有桶")
    }

    private suspend fun bucketPresent(api: R2Session, scope: AccountScope, name: String): Boolean =
        try {
            api.getBucket(scope, name)
            true
        } catch (failure: CloudFailure) {
            if (failure.code == CloudFailureCode.NOT_FOUND) false else throw failure
        }

    /**
     * Whether the exact key really exists right now, decided by exhausting the provider
     * listing for that key's prefix. Reaching the page cap raises instead of answering,
     * because a partial listing can never prove absence.
     */
    private suspend fun objectPresent(api: R2Session, scope: AccountScope, bucket: String, key: String): Boolean {
        var token: PageToken? = null
        var pages = 0
        do {
            val page = api.listObjects(scope, bucket, R2ObjectFilter(prefix = key), PageRequest(limit = 100, token = token))
            if (page.items.any { it.key == key }) return true
            token = page.next
            pages++
            if (pages >= MAX_RECONCILE_PAGES && token != null) {
                throw CloudFailure(CloudFailureCode.INVALID_RESPONSE, "对象列表未能完整读取，请稍后重试")
            }
        } while (token != null)
        return false
    }

    private fun matchCandidate(task: OperationTask, observedFields: JsonObject): Reconciliation {
        val candidate =
            digest(
                buildJsonObject {
                    put("scope", scopeFields(task.scope))
                    observedFields.forEach { (key, value) -> put(key, value) }
                },
            )
        return if (candidate == task.desiredHash) Reconciliation.Matched(candidate) else Reconciliation.StillUnknown
    }

    /** The provider-observable state a read can confirm, never an executable payload. */
    private fun observedState(document: R2BucketDocument): JsonObject =
        buildJsonObject {
            put("scope", scopeFields(document.scope))
            put("name", document.name)
            document.raw["storage_class"]?.let { put("storageClass", it) }
        }

    private fun semantic(write: R2BucketWrite): JsonObject =
        buildJsonObject {
            put("action", r2BucketWriteKey(write))
            put("scope", scopeFields(write.scope))
            when (write) {
                is R2BucketWrite.Create -> {
                    put("name", write.input.name)
                    write.input.locationHint?.let { put("locationHint", it) }
                    write.input.storageClass?.let { put("storageClass", it) }
                }

                is R2BucketWrite.CreateByName -> {
                    put("name", write.name)
                    write.storageClass?.let { put("storageClass", it) }
                }

                is R2BucketWrite.PatchStorageClass -> {
                    put("name", write.name)
                    put("storageClass", write.storageClass)
                }

                is R2BucketWrite.Delete -> {
                    put("name", write.name)
                    put("typedName", write.typedName)
                }
            }
        }

    private fun desired(write: R2BucketWrite): JsonObject =
        buildJsonObject {
            put("scope", scopeFields(write.scope))
            when (write) {
                is R2BucketWrite.Create -> put("name", write.input.name)
                is R2BucketWrite.CreateByName -> {
                    put("name", write.name)
                    write.storageClass?.let { put("storageClass", it) }
                }

                is R2BucketWrite.PatchStorageClass -> {
                    put("name", write.name)
                    put("storageClass", write.storageClass)
                }

                is R2BucketWrite.Delete -> put("deleted", true)
            }
        }

    private fun objectSemantic(write: R2ObjectWrite): JsonObject =
        buildJsonObject {
            put("action", r2ObjectWriteKey(write))
            put("scope", scopeFields(write.scope))
            put("bucket", write.bucket)
            when (write) {
                is R2ObjectWrite.Upload -> {
                    put("key", write.key)
                    put("length", write.length)
                    write.contentType?.let { put("contentType", it) }
                    put("contentFingerprint", write.contentFingerprint)
                }

                is R2ObjectWrite.Delete -> {
                    put("key", write.key)
                    put("typedKey", write.typedKey)
                }

                is R2ObjectWrite.BulkDelete -> put("keys", JsonArray(write.keys.map { JsonPrimitive(it) }))
            }
        }

    private fun objectDesired(write: R2ObjectWrite): JsonObject =
        buildJsonObject {
            put("scope", scopeFields(write.scope))
            put("bucket", write.bucket)
            when (write) {
                is R2ObjectWrite.Upload -> put("key", write.key)
                is R2ObjectWrite.Delete -> {
                    put("key", write.key)
                    put("deleted", true)
                }

                is R2ObjectWrite.BulkDelete -> put("keys", JsonArray(write.keys.map { JsonPrimitive(it) }))
            }
        }

    private fun scopeFields(scope: CloudScope): JsonObject =
        buildJsonObject {
            if (scope !is AccountScope) throw invalidInputFailure()
            put("connection", scope.connectionId)
            put("account", scope.accountId)
        }

    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))

    private fun previewOf(write: R2BucketWrite, before: R2BucketDocument?): R2ChangePreview {
        val bucket = before?.name ?: validatedName(write)
        val impact =
            when (write) {
                is R2BucketWrite.Create, is R2BucketWrite.CreateByName ->
                    "在所选账号创建 R2 桶；桶名全局唯一，创建后的位置与 jurisdiction 不可更改"

                is R2BucketWrite.PatchStorageClass ->
                    "变更该桶**新上传对象**的默认存储等级；已有对象的存储等级不变，单次上传仍可覆盖"

                is R2BucketWrite.Delete ->
                    "删除该桶及其全部对象与配置。供应商要求桶为空（无对象、无未完成的分片上传、无事件通知规则），否则会失败"
            }
        return R2ChangePreview(
            write.scope,
            bucket,
            impact,
            "服务端未提供 CAS；写入前回读无法保证原子并发隔离，回读与写入之间仍有竞争窗口",
        )
    }

    private fun objectPreviewOf(write: R2ObjectWrite): R2ChangePreview {
        val impact =
            when (write) {
                is R2ObjectWrite.Upload -> "向该桶上传一个对象；同名对象的内容会被覆盖"
                is R2ObjectWrite.Delete -> "永久删除该对象的当前版本，无法恢复"
                is R2ObjectWrite.BulkDelete ->
                    "一次删除 ${write.keys.size} 个对象。供应商不保证原子性：部分 key 失败时其余 key 仍会被删除"
            }
        return R2ChangePreview(
            write.scope,
            write.bucket,
            impact,
            "服务端未提供 CAS；列表回读可以证明当前状态，但回读与写入之间仍有竞争窗口",
        )
    }

    private fun objectKeyOf(write: R2ObjectWrite): String? =
        when (write) {
            is R2ObjectWrite.Upload -> write.key
            is R2ObjectWrite.Delete -> write.key
            is R2ObjectWrite.BulkDelete -> null
        }

    private fun objectLocator(bucket: String, key: String?): String = "$bucket/${key ?: ""}"

    private fun bucketOfObjectRef(reference: ResourceRef): String? =
        reference.externalId.substringBefore('/').takeIf { it.isNotBlank() }

    private fun keyOfObjectRef(reference: ResourceRef): String? =
        reference.externalId.substringAfter('/', "").takeIf { it.isNotBlank() }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        const val MAX_RECONCILE_PAGES = 5
        const val BUCKET_KIND = "r2_bucket"
        const val OBJECT_KIND = "r2_object"
        val BUCKET_ACTIONS = setOf("r2-create-bucket", "r2-create-bucket-by-name", "r2-patch-bucket", "r2-delete-bucket")
        val OBJECT_ACTIONS = setOf("r2-put-object", "r2-delete-object", "r2-delete-objects")
        val ALL_ACTIONS = BUCKET_ACTIONS + OBJECT_ACTIONS
        val UNSETTLED_STATES =
            setOf(
                TaskState.QUEUED,
                TaskState.RUNNING,
                TaskState.UNKNOWN,
                TaskState.PARTIAL,
                TaskState.RECONCILING,
                TaskState.ACCEPTED,
            )
    }
}
