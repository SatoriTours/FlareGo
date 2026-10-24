package com.flarego.core.application.r2

import com.flarego.core.application.operations.CanonicalJson
import com.flarego.core.application.operations.ChangePlan
import com.flarego.core.application.operations.ChangePlans
import com.flarego.core.application.operations.ConfirmedPlan
import com.flarego.core.application.operations.ExecutionReceipt
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
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.freezeR2ConfigurationWrite
import com.flarego.core.model.TaskState
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2ConfigurationWriteKey
import com.flarego.core.model.r2ConfigurationWriteKind
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2ConfigurationSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Previews and executes the reviewed batch B1 configuration writes through the shared
 * P1 confirmation machinery.
 *
 * A prepared change is inert: the typed rule set stays in this controller's memory, the
 * plan carries hashes only, and one confirmation admits at most one send. The controller
 * re-reads the current provider document before the final guard, then performs the
 * same-lock independent read-back and compares the complete observable target — the wire
 * client deliberately returns a receipt only.
 *
 * Everything is bound to the live product state: the actual command/fingerprint, the
 * credential revision, the selection epoch (route) and the exact account/bucket/
 * jurisdiction. A plan, a persisted task or a preview can never supply a command back.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2ConfigurationController(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val selectionEpoch: () -> Long = { 0L },
    private val session: suspend (AccountScope) -> R2ConfigurationSession,
) {
    private data class Prepared(
        val plan: ChangePlan,
        val write: R2ConfigurationWrite,
        val target: R2WriteTarget,
        val kind: R2ConfigurationKind,
        /**
         * The normalized snapshot the plan binds to: the same encoding the write-after
         * read-back and [`requested`] use, so an unchanged document can never be refused
         * only because the provider omitted an optional member.
         */
        val beforeTarget: JsonObject,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
    )

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    /** Pure function of the actual scope, target and typed rules, for the product resolver. */
    fun requestFingerprint(write: R2ConfigurationWrite): String = digest(semantic(write))

    suspend fun prepare(write: R2ConfigurationWrite): ChangePlan {
        val frozen = freezeR2ConfigurationWrite(write)
        validateConfigurationWrite(frozen)
        val live = requireCurrent(frozen)
        requireNoUnsettled(frozen.target)
        requireSameLive(live)
        val api = session(frozen.target.account)
        requireSameLive(live)
        val kind = r2ConfigurationWriteKind(frozen)
        val document = readCurrent(api, frozen.target, kind)
        // The provider document must be the exact resource this plan addresses.
        if (document.target != frozen.target || document.kind != kind) throw scopeMismatchFailure()
        // A document this app cannot round-trip losslessly is never replaced by a guess.
        if (!document.editable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_CONFIGURATION_READ_ONLY_WRITE)
        }
        val beforeTarget =
            observedTargetOrNull(kind, document)
                ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_CONFIGURATION_READ_ONLY_WRITE)
        requireSameLive(live)
        val plan =
            plans.prepare(
                scope = frozen.target.account,
                actionKey = r2ConfigurationWriteKey(frozen),
                resourceRef = R2TaskTargets.reference(frozen.target, kind),
                beforeHash = digest(beforeTarget),
                desiredHash = digest(desiredTarget(frozen)),
                requestFingerprint = requestFingerprint(frozen),
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val entry = Prepared(plan, frozen, frozen.target, kind, beforeTarget, live, previewOf(frozen, beforeTarget))
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table.filterKeys(plans::retains) + (plan.id to entry))) break
        }
        return plan
    }

    fun preview(planId: String): R2ChangePreview = entry(planId).preview

    suspend fun submit(write: R2ConfigurationWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, write, confirmed)
        requireNoUnsettled(p.target, p.plan.id)
        requireBound(p, write, confirmed)
        val guard = { requireBound(p, write, confirmed) }
        return coordinator.executeR2Dispatch(confirmed, guard, R2TaskTargets.group(p.target)) { composed ->
            // Session/credential acquisition can suspend; it must finish before final checks.
            val api = session(p.target.account)
            guard()
            // The shared resource lock is held now: another preparation may have settled
            // UNKNOWN while this operation waited behind it.
            requireNoUnsettled(p.target, p.plan.id)
            val receipt =
                api.writeConfiguration(p.write) { observed ->
                    // Batch B1 has no confirmed-absence contract: a missing snapshot is an error.
                    if (observed == null) throw invalidInputFailure()
                    if (observed.target != p.target || observed.kind != p.kind) throw scopeMismatchFailure()
                    val current = observedTargetOrNull(p.kind, observed)
                    if (current == null || digest(current) != p.plan.beforeHash) {
                        throw CloudFailure(CloudFailureCode.CONFLICT, "该桶的配置已变化，请刷新并重新预览确认")
                    }
                    // No suspension between this composed final guard and the typed send.
                    composed()
                }
            verifyCompletion(p, api, receipt)
        }
    }

    /**
     * Read-only reconciliation, including after a process or controller recreation.
     *
     * The persisted locator and desired hash identify what to verify; they never restore a
     * command, a payload or a confirmation. An unreadable or differently shaped document
     * is no proof of application, so the task stays UNKNOWN without a replay.
     */
    suspend fun reconcile(taskId: String): OperationTask =
        settledRefusal(taskId) ?: coordinator.reconcile(taskId) { task ->
            val live = resolver.current() ?: throw invalidInputFailure()
            if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
            ScopeGuard.requireOwned(task.scope, live.connection)
            requireSameLive(live)
            val location = R2TaskTargets.parse(task.resourceRef) ?: return@reconcile Reconciliation.StillUnknown
            if (location.account != task.scope) return@reconcile Reconciliation.StillUnknown
            val kind = location.kind ?: return@reconcile Reconciliation.StillUnknown
            if (R2TaskTargets.CONFIGURATION_ACTIONS[task.actionKey] != kind) {
                return@reconcile Reconciliation.StillUnknown
            }
            val desired = task.desiredHash ?: return@reconcile Reconciliation.StillUnknown
            val evidence =
                try {
                    val api = session(location.account)
                    requireSameLive(live)
                    val target = R2WriteTarget(location.account, location.bucket, location.jurisdiction)
                    val document = readCurrent(api, target, location.kind)
                    if (document.target != target || document.kind != location.kind) {
                        return@reconcile Reconciliation.StillUnknown
                    }
                    val observed = observedTargetOrNull(location.kind, document)
                        ?: return@reconcile Reconciliation.StillUnknown
                    val observedHash = digest(observed)
                    if (observedHash == desired) Reconciliation.Matched(observedHash) else Reconciliation.StillUnknown
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Reconciliation.StillUnknown
                }
            requireSameLive(live)
            evidence
        }

    /** A terminal refusal stays terminal; local task metadata never restores an executable command. */
    private suspend fun settledRefusal(taskId: String): OperationTask? {
        val task = withContext(storeDispatcher) { store.get(taskId) } ?: return null
        if (task.state != TaskState.FAILED) return null
        val live = resolver.current() ?: throw invalidInputFailure()
        val location = R2TaskTargets.parse(task.resourceRef) ?: throw invalidInputFailure()
        val kind = location.kind ?: throw invalidInputFailure()
        if (task.scope !is AccountScope || live.scope != task.scope || location.account != task.scope ||
            R2TaskTargets.CONFIGURATION_ACTIONS[task.actionKey] != kind || live.credentialRevision < 0
        ) {
            throw invalidInputFailure()
        }
        requireSameLive(live)
        ScopeGuard.requireOwned(task.scope, live.connection)
        return task
    }

    /** Drop only payloads whose issuer entry was safely retired; executing claims remain owned. */
    internal fun pruneRetiredPayloads() {
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table.filterKeys(plans::retains))) break
        }
    }

    private fun entry(id: String): Prepared = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2ConfigurationWrite, confirmed: ConfirmedPlan) {
        val frozen = freezeR2ConfigurationWrite(write)
        if (confirmed.plan != p.plan || frozen != p.write ||
            requestFingerprint(frozen) != p.plan.requestFingerprint ||
            r2ConfigurationWriteKey(frozen) != p.plan.actionKey ||
            r2ConfigurationWriteKind(frozen) != p.kind || frozen.target != p.target ||
            p.plan.resourceRef != R2TaskTargets.reference(p.target, p.kind) ||
            p.plan.beforeHash != digest(p.beforeTarget) ||
            p.plan.desiredHash != digest(desiredTarget(p.write))
        ) {
            throw invalidInputFailure()
        }
        validateConfigurationWrite(frozen)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(write: R2ConfigurationWrite): LiveOperationContext {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != write.target.account || live.requestFingerprint != requestFingerprint(write)) {
            throw invalidInputFailure()
        }
        ScopeGuard.requireOwned(write.target.account, live.connection)
        if (!resolver.isLive(write.target.account, live.credentialRevision)) throw invalidInputFailure()
        return live
    }

    private fun requireSameLive(expected: LiveOperationContext) {
        if (resolver.current() != expected || !resolver.isLive(expected.scope, expected.credentialRevision)) {
            throw invalidInputFailure()
        }
        ScopeGuard.requireOwned(expected.scope, expected.connection)
    }

    private fun validateConfigurationWrite(write: R2ConfigurationWrite) {
        validateR2ConfigurationTarget(write.target)
        requireR2ConfigurationJurisdictionSupported(write.target, r2ConfigurationWriteKind(write))
        r2ConfigurationWriteBody(write)
    }

    /** Any unsettled R2 write for the same account/bucket/jurisdiction identity blocks a new one. */
    private suspend fun requireNoUnsettled(target: R2WriteTarget, excluding: String? = null) {
        val blocked =
            withContext(storeDispatcher) {
                store.list(target.account.connectionId).any { task ->
                    task.id != excluding && R2TaskTargets.blocks(task, target)
                }
            }
        if (blocked) throw unsettledFailure()
    }

    private fun unsettledFailure() =
        CloudFailure(
            CloudFailureCode.UNKNOWN_RESULT,
            "该桶的上一操作尚未核对，请先核对结果，不要再次发送",
            uncertain = true,
        )

    private suspend fun readCurrent(
        api: R2ConfigurationSession,
        target: R2WriteTarget,
        kind: R2ConfigurationKind,
    ): R2ConfigurationDocument =
        when (kind) {
            R2ConfigurationKind.CORS -> api.getCors(target)
            R2ConfigurationKind.LIFECYCLE -> api.getLifecycle(target)
            R2ConfigurationKind.LOCAL_UPLOADS -> api.getLocalUploads(target)
            R2ConfigurationKind.LOCK -> api.getLock(target)
        }

    /**
     * One independent read after a complete receipt; never another write.
     *
     * A receipt is not verified application: an unreadable, non-round-trippable or
     * contradicting snapshot keeps the task UNKNOWN, and an Accepted/Partial/Unknown
     * receipt is never upgraded by a read-back.
     */
    private suspend fun verifyCompletion(
        p: Prepared,
        api: R2ConfigurationSession,
        receipt: ExecutionReceipt,
    ): ExecutionReceipt {
        if (receipt !is ExecutionReceipt.Completed) return receipt
        val verified =
            try {
                val observed = readCurrent(api, p.target, p.kind)
                if (observed.target != p.target || observed.kind != p.kind) {
                    false
                } else {
                    val current = observedTargetOrNull(p.kind, observed)
                    current != null && digest(current) == p.plan.desiredHash
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        // Never relabel an inconclusive post-send read as a clean failure or replay it.
        return if (verified) receipt else ExecutionReceipt.Unknown(receipt.providerRequestId)
    }

    /**
     * The complete observable target of one kind, re-encoded from the typed rules so that a
     * provider document is compared by meaning (order included) and not by raw text.
     *
     * A document the app cannot round-trip losslessly yields null: unknown nested content can
     * never confirm a write. For `DELETE` the observable target is "the current snapshot
     * exposes no rules"; an arbitrary 404 is never read as absence.
     */
    private fun observedTargetOrNull(kind: R2ConfigurationKind, document: R2ConfigurationDocument): JsonObject? {
        if (!document.editable) return null
        return try {
            when (kind) {
                R2ConfigurationKind.CORS ->
                    buildJsonObject { put("rules", reEncode(document.raw, ::decodeR2CorsRule, ::encodeR2CorsRule)) }
                R2ConfigurationKind.LIFECYCLE ->
                    buildJsonObject { put("rules", reEncode(document.raw, ::decodeR2LifecycleRule, ::encodeR2LifecycleRule)) }
                R2ConfigurationKind.LOCK ->
                    buildJsonObject { put("rules", reEncode(document.raw, ::decodeR2LockRule, ::encodeR2LockRule)) }
                R2ConfigurationKind.LOCAL_UPLOADS -> {
                    val enabled = document.raw["enabled"] as? JsonPrimitive ?: return null
                    if (enabled.isString) return null
                    buildJsonObject { put("enabled", enabled.boolean) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> reEncode(
        raw: JsonObject,
        decode: (JsonObject) -> T,
        encode: (T) -> JsonObject,
    ): JsonArray {
        val rules = raw["rules"] as? JsonArray ?: throw invalidInputFailure()
        return buildJsonArray {
            rules.forEach { element ->
                val row = element as? JsonObject ?: throw invalidInputFailure()
                add(encode(decode(row)))
            }
        }
    }

    /** The target state a later read must observe; a delete observes an empty rule set. */
    private fun desiredTarget(write: R2ConfigurationWrite): JsonObject =
        r2ConfigurationWriteBody(write) ?: buildJsonObject { put("rules", buildJsonArray {}) }

    /**
     * The decision-relevant semantic form used for the request fingerprint.
     *
     * It is deliberately non-throwing so a live command can never crash the context
     * resolver; an invalid draft still cannot become a plan because validation runs first.
     */
    private fun semantic(write: R2ConfigurationWrite): JsonObject =
        buildJsonObject {
            put("action", r2ConfigurationWriteKey(write))
            put("selectionEpoch", selectionEpoch())
            put("scope", scopeFields(write.target.account))
            put("bucket", write.target.bucket)
            put("jurisdiction", write.target.jurisdiction)
            put("kind", r2ConfigurationWriteKind(write).name)
            when (write) {
                is R2ConfigurationWrite.DeleteCors, is R2ConfigurationWrite.DeleteLock -> put("deleted", true)
                else -> {
                    val body = runCatching { r2ConfigurationWriteBody(write) }.getOrNull()
                    if (body != null) put("body", body) else put("invalidBody", true)
                }
            }
        }

    private fun scopeFields(scope: CloudScope): JsonObject =
        buildJsonObject {
            if (scope !is AccountScope) throw invalidInputFailure()
            put("connection", scope.connectionId)
            put("account", scope.accountId)
        }

    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))

    private fun previewOf(write: R2ConfigurationWrite, original: JsonObject): R2ChangePreview {
        val target = write.target
        val requested = desiredTarget(write)
        val detail =
            when (write) {
                is R2ConfigurationWrite.PutCors ->
                    "替换该桶的完整 CORS 规则集（${write.rules.size} 条）。" +
                        "CORS 只控制浏览器跨域读取，不代表对象访问授权或私有/公开状态。" +
                        "空规则列表会清除全部 CORS 规则。${diffText(original, requested)}"
                is R2ConfigurationWrite.DeleteCors ->
                    "删除该桶的全部 CORS 规则（当前 ${(original?.get("rules") as? JsonArray)?.size ?: 0} 条）；" +
                        "浏览器跨域读取将不再被允许。CORS 不是访问授权。"
                is R2ConfigurationWrite.PutLifecycle ->
                    "替换该桶的完整生命周期规则集（${write.rules.size} 条），规则顺序即提交顺序。" +
                        wildcardPrefixText(write.rules.map { it.conditions.prefix }) +
                        "含对象删除、终止分片上传或存储等级迁移的规则不可撤销地影响数据保留。" +
                        "空规则列表会清除全部生命周期规则。${diffText(original, requested)}"
                is R2ConfigurationWrite.PutLocalUploads ->
                    "将近端写入复制设置为 ${if (write.enabled) "启用" else "停用"}（显式发送）。" +
                        "仅影响此后新的近端写入；停用不代表现有复制立即结束，也不代表桶变为私有。"
                is R2ConfigurationWrite.PutLock ->
                    "替换该桶的完整锁规则集（${write.rules.size} 条），锁规则保护现有对象免于覆盖/删除。" +
                        wildcardPrefixText(write.rules.map { it.prefix }) +
                        "删除或放宽规则不承诺立即解除已施加于现有对象的保护。" +
                        "空规则列表会清除全部锁规则。${diffText(original, requested)}"
                is R2ConfigurationWrite.DeleteLock ->
                    "删除该桶的全部锁规则（当前 ${(original?.get("rules") as? JsonArray)?.size ?: 0} 条）；" +
                        "既有对象的保护可能仍持续，删除规则不承诺立即解除。"
            }
        val region =
            if (target.jurisdiction == R2TaskTargets.DEFAULT_JURISDICTION) {
                "目标地区：default（默认地区）；同名桶在其他地区是不同资源"
            } else {
                "目标地区：${target.jurisdiction}（非默认地区）；请在相同地区核对结果，App 不会退回 default 桶"
            }
        return R2ChangePreview(
            target.account,
            target.bucket,
            detail,
            "$region。服务端未提供 CAS；写入前回读不保证原子并发隔离，回读与写入之间仍有竞争窗口",
        )
    }

    /** An empty prefix protects/deletes every object of the bucket; the preview says so explicitly. */
    private fun wildcardPrefixText(prefixes: List<String>): String {
        val wholeBucket = prefixes.count { it.isEmpty() }
        return if (wholeBucket == 0) "" else "其中 $wholeBucket 条规则的前缀为空，将作用于整个桶。"
    }

    private fun diffText(before: JsonObject?, after: JsonObject): String {
        val beforeRules = (before?.get("rules") as? JsonArray)?.mapIndexed { index, element ->
            keyOf(element as JsonObject, index) to element
        }?.toMap().orEmpty()
        val afterRules = (after["rules"] as? JsonArray)?.mapIndexed { index, element ->
            keyOf(element as JsonObject, index) to element
        }?.toMap().orEmpty()
        val added = afterRules.keys - beforeRules.keys
        val removed = beforeRules.keys - afterRules.keys
        val shared = afterRules.keys intersect beforeRules.keys
        val changed = shared.count { afterRules[it] != beforeRules[it] }
        return "差异：新增 ${added.size}、修改 $changed、删除 ${removed.size}、不变 ${shared.size - changed}"
    }

    private fun keyOf(rule: JsonObject, index: Int): String =
        (rule["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "#$index"

}
