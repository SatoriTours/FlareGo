package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.r2ConfigurationReadOnlyReason
import com.flarego.core.application.r2.r2ConfigurationWriteBody
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.freezeR2ConfigurationWrite
import com.flarego.core.model.r2ConfigurationWriteKind
import com.flarego.core.ports.R2ConfigurationSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A configuration session double with real read-only analysis and real rule encoding.
 *
 * Only the provider itself is simulated: the documents are stored exactly as the wire
 * client would decode them, the frozen write is applied with the production encoder, and
 * the call counters observe real calls. No assertion here is made against this double's
 * own bookkeeping alone — the controller/runtime behavior is the subject.
 */
internal class FakeConfigurationSession(
    private val account: AccountScope,
) : R2ConfigurationSession {
    private val documents = mutableMapOf<String, JsonObject>()

    var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
    var failReads = false
    var refuseBeforeSend = false
    var refuseAfterGuard = false
    var skipGuard = false
    var onApply: ((R2ConfigurationWrite) -> Unit)? = null
    var onRead: ((R2WriteTarget, R2ConfigurationKind) -> Unit)? = null
    /** Suspends inside the same-lock read-back, never before the single send. */
    var readBackGate: (suspend () -> Unit)? = null
    /** Lets a test synthesize a same-looking document of the wrong target or kind. */
    var documentOverride: ((R2WriteTarget, R2ConfigurationKind) -> R2ConfigurationDocument?)? = null

    val writes = mutableListOf<R2ConfigurationWrite>()
    val sends = mutableListOf<R2ConfigurationWrite>()
    val reads = mutableListOf<Pair<R2WriteTarget, R2ConfigurationKind>>()
    var sessionOpens = 0
    var readsAfterADispatch = 0

    fun put(
        target: R2WriteTarget,
        kind: R2ConfigurationKind,
        raw: JsonObject,
    ) {
        documents[key(target, kind)] = raw
    }

    fun put(
        target: R2WriteTarget,
        kind: R2ConfigurationKind,
        json: String,
    ) = put(target, kind, kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject)

    fun raw(
        target: R2WriteTarget,
        kind: R2ConfigurationKind,
    ): JsonObject? = documents[key(target, kind)]

    fun remove(
        target: R2WriteTarget,
        kind: R2ConfigurationKind,
    ) {
        documents.remove(key(target, kind))
    }

    fun emptyRules(target: R2WriteTarget = R2WriteTarget(account, "my-bucket")) {
        R2ConfigurationKind.entries.forEach { kind ->
            if (kind == R2ConfigurationKind.LOCAL_UPLOADS) put(target, kind, buildJsonObject { put("enabled", false) })
            else put(target, kind, buildJsonObject { put("rules", JsonArray(emptyList())) })
        }
    }

    fun open() {
        sessionOpens++
    }

    private fun key(target: R2WriteTarget, kind: R2ConfigurationKind) =
        "${target.jurisdiction}|${target.bucket}|${kind.name}"

    private suspend fun document(target: R2WriteTarget, kind: R2ConfigurationKind): R2ConfigurationDocument {
        reads += target to kind
        onRead?.invoke(target, kind)
        documentOverride?.invoke(target, kind)?.let { if (sends.isNotEmpty()) readsAfterADispatch++; return it }
        if (sends.isNotEmpty()) {
            readsAfterADispatch++
            readBackGate?.invoke()
        }
        if (failReads) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "合成读取失败")
        val raw = documents[key(target, kind)]
            ?: throw CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源，请刷新后重试")
        val reason = r2ConfigurationReadOnlyReason(kind, raw)
        return R2ConfigurationDocument(target, kind, raw, reason == null, reason)
    }

    private fun apply(write: R2ConfigurationWrite) {
        val target = write.target
        val kind = r2ConfigurationWriteKind(write)
        onApply?.let { it(write); return }
        when (write) {
            is R2ConfigurationWrite.DeleteCors,
            is R2ConfigurationWrite.DeleteLock,
            -> documents.remove(key(target, kind))
            else -> documents[key(target, kind)] = r2ConfigurationWriteBody(write)
                ?: throw IllegalStateException("a replacing write always has a body")
        }
    }

    override suspend fun getBucket(target: R2WriteTarget): R2BucketDocument {
        reads += target to R2ConfigurationKind.CORS
        if (failReads) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "合成读取失败")
        return R2BucketDocument(
            target.bucket,
            target.account,
            buildJsonObject { put("name", target.bucket); put("storage_class", "Standard") },
        )
    }

    override suspend fun getCors(target: R2WriteTarget): R2ConfigurationDocument = document(target, R2ConfigurationKind.CORS)

    override suspend fun getLifecycle(target: R2WriteTarget): R2ConfigurationDocument =
        document(target, R2ConfigurationKind.LIFECYCLE)

    override suspend fun getLocalUploads(target: R2WriteTarget): R2ConfigurationDocument =
        document(target, R2ConfigurationKind.LOCAL_UPLOADS)

    override suspend fun getLock(target: R2WriteTarget): R2ConfigurationDocument = document(target, R2ConfigurationKind.LOCK)

    override suspend fun putCors(target: R2WriteTarget, rules: List<R2CorsRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutCors(target, rules.toList())) {}

    override suspend fun deleteCors(target: R2WriteTarget): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.DeleteCors(target)) {}

    override suspend fun putLifecycle(target: R2WriteTarget, rules: List<R2LifecycleRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLifecycle(target, rules.toList())) {}

    override suspend fun putLocalUploads(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLocalUploads(target, enabled)) {}

    override suspend fun putLock(target: R2WriteTarget, rules: List<R2LockRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLock(target, rules.toList())) {}

    override suspend fun deleteLock(target: R2WriteTarget): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.DeleteLock(target)) {}

    override suspend fun writeConfiguration(
        write: R2ConfigurationWrite,
        beforeSend: (R2ConfigurationDocument?) -> Unit,
    ): ExecutionReceipt {
        val frozen = freezeR2ConfigurationWrite(write)
        writes += frozen
        val observed = document(frozen.target, r2ConfigurationWriteKind(frozen))
        if (refuseBeforeSend) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "合成发送前拒绝")
        if (!skipGuard) beforeSend(observed)
        if (refuseAfterGuard) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "合成最终守卫拒绝")
        sends += frozen
        apply(frozen)
        return receipt
    }
}
