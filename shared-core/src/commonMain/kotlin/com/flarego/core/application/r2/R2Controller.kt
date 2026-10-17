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
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.TaskState
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2BucketWriteKey
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2ManagementSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Previews and executes the typed R2 bucket lifecycle through the shared P1 machinery.
 *
 * It reuses [ChangePlans] and [OperationCoordinator] rather than inventing a second
 * confirmation mechanism: a prepared change is inert, one confirmation admits at most
 * one send, and every uncertain outcome is reconciled by reading before anything else.
 * Payloads and provider snapshots stay in memory; only safe locators are persisted.
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
    private val session: suspend (AccountScope) -> R2ManagementSession,
) {
    private data class Prepared(
        val plan: ChangePlan,
        val write: R2BucketWrite,
        val name: String,
        val before: R2BucketDocument?,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
    )

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    /** Pure function of the actual scope and input, for the independent product resolver. */
    fun requestFingerprint(write: R2BucketWrite): String = digest(semantic(freezeForFingerprint(write)))

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
                // Every write for one bucket shares the same coordinator resource lock, so a
                // bucket delete can never race an edit of the same bucket.
                resourceRef = targetRef(write),
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

    suspend fun submit(write: R2BucketWrite, confirmed: ConfirmedPlan): com.flarego.core.model.OperationTask {
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
    suspend fun reconcile(taskId: String): com.flarego.core.model.OperationTask =
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
                            val present =
                                try {
                                    api.getBucket(task.scope, reference.externalId)
                                    true
                                } catch (failure: CloudFailure) {
                                    if (failure.code == CloudFailureCode.NOT_FOUND) false else throw failure
                                }
                            if (present) Reconciliation.StillUnknown else matchCandidate(task, buildJsonObject { put("deleted", true) })
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

    private fun entry(id: String) = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2BucketWrite, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan || freezeForComparison(write) != p.write || requestFingerprint(write) != p.plan.requestFingerprint ||
            r2BucketWriteKey(write) != p.plan.actionKey || write.scope != p.plan.scope ||
            p.plan.beforeHash != p.before?.let { digest(it.raw) } || p.plan.desiredHash != digest(desired(p.write))
        ) {
            throw invalidInputFailure()
        }
        validateDelete(write)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(write: R2BucketWrite): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != write.scope || live.requestFingerprint != requestFingerprint(write)) throw invalidInputFailure()
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

    private fun validatedName(write: R2BucketWrite): String =
        when (write) {
            is R2BucketWrite.Create -> validateR2CreateInput(write.input).name
            is R2BucketWrite.CreateByName -> validateR2BucketName(write.name)
            is R2BucketWrite.PatchStorageClass -> validateR2BucketName(write.name)
            is R2BucketWrite.Delete -> validateR2BucketName(write.name)
        }

    private fun targetRef(write: R2BucketWrite): ResourceRef = ResourceRef(write.scope, BUCKET_KIND, validatedName(write))

    /** Persisted uncertainty survives controller recreation; it permits reads only. */
    private suspend fun requireNoUnsettled(write: R2BucketWrite, excluding: String? = null) {
        val target = targetRef(write)
        val blocked =
            withContext(storeDispatcher) {
                store.list(write.scope.connectionId).any { task ->
                    task.id != excluding && task.scope == write.scope &&
                        task.actionKey in BUCKET_ACTIONS &&
                        (task.resourceRef == target || task.resourceRef == null) &&
                        task.state in UNSETTLED_STATES
                }
            }
        if (blocked) {
            throw CloudFailure(
                CloudFailureCode.UNKNOWN_RESULT,
                "该桶的上一操作尚未核对，请先核对结果，不要再次发送",
                uncertain = true,
            )
        }
    }

    /**
     * ScopeGuard only proves the local connection binding, so the provider document is
     * checked separately: the scope must be the requested account and the returned name
     * must be the requested bucket.
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
    private suspend fun requireAbsent(api: R2ManagementSession, scope: AccountScope, name: String) {
        val exists =
            try {
                api.getBucket(scope, name)
                true
            } catch (failure: CloudFailure) {
                if (failure.code == CloudFailureCode.NOT_FOUND) false else throw failure
            }
        if (exists) throw CloudFailure(CloudFailureCode.CONFLICT, "该账号已存在同名桶，请先查看现有桶")
    }

    private fun matchCandidate(task: com.flarego.core.model.OperationTask, observedFields: JsonObject): Reconciliation {
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

    private fun scopeFields(scope: com.flarego.core.model.CloudScope): JsonObject =
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
                    "在所选账号创建 R2 桶；桶名为全局唯一，创建后位置与 jurisdiction 不可更改"
                is R2BucketWrite.PatchStorageClass ->
                    "变更该桶**新上传对象**的默认存储等级；已有对象的存储等级不变，单次上传仍可覆盖"
                is R2BucketWrite.Delete ->
                    "删除该桶及其全部对象与配置。供应商要求桶为空（无对象、无未完成的分片上传、无事件通知规则）否则会失败"
            }
        return R2ChangePreview(
            write.scope,
            bucket,
            impact,
            "服务端未提供 CAS；写入前回读无法保证原子并发隔离，回读与写入之间仍有竞争窗口",
        )
    }

    /** A write carries no mutable payload beyond value fields, so a plain copy is the snapshot. */
    private fun freezeForComparison(write: R2BucketWrite): R2BucketWrite = write

    /** Fingerprint input must not depend on caller-held collections that can still change. */
    private fun freezeForFingerprint(write: R2BucketWrite): R2BucketWrite = write

    private companion object {
        const val BUCKET_KIND = "r2_bucket"
        val BUCKET_ACTIONS = setOf("r2-create-bucket", "r2-create-bucket-by-name", "r2-patch-bucket", "r2-delete-bucket")
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
