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
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.R2SippyAction
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.model.R2SippyWritePreview
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.TaskState
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2SippySession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Previews and executes the reviewed batch B3 Sippy writes through the shared confirmation
 * machinery of [R2WriteRuntime] (one issuer, one 64-slot plan window, one task store, one
 * account/jurisdiction/bucket lock).
 *
 * Secret ownership across the preview/admission boundary:
 *
 * | step | owner of the memory-only material |
 * | --- | --- |
 * | draft / preview | the caller's draft lease; the prepared entry references it |
 * | preview expiry, discard, 64-slot eviction | released here, only while never claimed |
 * | admitted execution | the one execution claims it; the entry can no longer take it back |
 * | final guard | `admitForTransport()` (one local step, no suspension) |
 * | transport end | `completeTransport()` (the wire client's `finally`) |
 *
 * A `DELETE` needs no material. A plan, a task row, a preview and a fingerprint never carry a
 * credential: the fingerprint binds the draft's random opaque binding id, so an edited draft
 * invalidates an old preview without storing any comparable secret digest.
 *
 * Write-after is conservative: only a `Completed` receipt is verified by one same-lock safe GET
 * against the explicit public desired state, and a `PUT` task is never settled from a public GET
 * later (a public GET cannot prove that a new secret was applied).
 */
@OptIn(ExperimentalAtomicApi::class)
class R2SippyController(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val selectionEpoch: () -> Long = { 0L },
    private val session: suspend (AccountScope) -> R2SippySession,
) {
    /**
     * The controlled app-owned holder of one preview's material.
     *
     * [claim] runs inside the coordinator's write lambda, so only the admitted owner can take the
     * material, and it *moves* the material to a task-owned lease (the editor draft becomes
     * [R2SippyLeaseState.CLAIMED] and keeps no reference). From then on an editor retirement, a
     * preview discard, the 64-slot eviction or the TTL prune can neither release nor admit it.
     * [retire] only releases material that was never claimed. Every transition is a compare-and-set,
     * so a second call can never observe or take the same material twice.
     */
    private class DraftClaim(draft: R2SippySecretLease?) {
        private sealed interface State {
            class Held(val lease: R2SippySecretLease) : State
            data object Claimed : State
            data object Retired : State
            data object Absent : State
        }

        private val state = AtomicReference<State>(draft?.let { State.Held(it) } ?: State.Absent)
        private val transferred = AtomicReference<R2SippySecretLease?>(null)

        fun claim(): R2SippySecretLease? {
            while (true) {
                val current = state.load()
                if (current !is State.Held) return null
                if (!state.compareAndSet(current, State.Claimed)) continue
                val owned = current.lease.claimForTask()
                if (owned != null) transferred.store(owned)
                return owned
            }
        }

        fun retire() {
            while (true) {
                val current = state.load()
                if (current !is State.Held) return
                if (!state.compareAndSet(current, State.Retired)) continue
                current.lease.retire()
                return
            }
        }

        /** True once the material was handed to an admitted execution (a duplicate confirm shares it). */
        val isClaimed: Boolean get() = state.load() === State.Claimed

        /** Deterministic ownership observation of the task-owned lease; it never exposes the secret. */
        fun observation(): R2SippyMaterialObservation? {
            val lease = transferred.load() ?: return null
            return R2SippyMaterialObservation(lease.leaseState, lease.retainsMaterial)
        }
    }

    private class Prepared(
        val plan: ChangePlan,
        val write: R2SippyWrite,
        val target: R2WriteTarget,
        /** The normalized public snapshot the plan binds to (never a secret). */
        val beforeTarget: JsonObject,
        /** The normalized public state the write must produce. */
        val desiredTarget: JsonObject,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
        val detail: R2SippyWritePreview,
        val claim: DraftClaim,
    ) {
        /** Whether this preview already holds the exact same editor draft (one draft, one preview). */
        fun sharesDraftWith(other: R2SippyWrite): Boolean {
            val mine = (write as? R2SippyWrite.PutSippy)?.lease ?: return false
            val theirs = (other as? R2SippyWrite.PutSippy)?.lease ?: return false
            return mine === theirs
        }
    }

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    /**
     * Publishes the prepared table (optionally with a new entry) and drops every plan the issuer no
     * longer retains.
     *
     * Dropped entries release their never-claimed material *after* the winning compare-and-set, so a
     * prepare that resumed after the five-minute TTL cannot leak an expired draft even when the
     * opportunistic prune did not run first.
     */
    private fun publish(
        extra: Pair<String, Prepared>? = null,
        reject: ((Map<String, Prepared>) -> Unit)? = null,
    ) {
        while (true) {
            val table = prepared.load()
            val retained = table.filterKeys(plans::retains)
            reject?.invoke(retained)
            val next = if (extra == null) retained else retained + extra
            if (!prepared.compareAndSet(table, next)) continue
            for ((id, entry) in table) if (id !in retained) entry.claim.retire()
            return
        }
    }

    /** Pure function of the live scope, target and typed write, for the product resolver. */
    fun requestFingerprint(write: R2SippyWrite): String {
        val target = write.target
        return digest(
            buildJsonObject {
                put("action", r2SippyWriteKey(write))
                put("selectionEpoch", selectionEpoch())
                put("connection", target.account.connectionId)
                put("account", target.account.accountId)
                put("bucket", target.bucket)
                put("jurisdiction", target.jurisdiction)
                if (write is R2SippyWrite.PutSippy) {
                    // The opaque random binding id: a changed draft can never confirm an old preview,
                    // and no digest of a credential is ever stored.
                    put("credentialBinding", write.lease.bindingId)
                    put("provider", write.lease.safeSource.provider.wireValue)
                    publicSource(write.lease.safeSource)?.forEach { (key, value) -> put(key, value) }
                }
            },
        )
    }

    suspend fun prepare(write: R2SippyWrite): ChangePlan {
        validateR2SippyTarget(write.target)
        if (write is R2SippyWrite.PutSippy) write.lease.requireDraft()
        val live = requireCurrent(write)
        requireNoUnsettled(write.target)
        requireSameLive(live)
        val api = session(write.target.account)
        requireSameLive(live)
        val before = api.getSippy(write.target)
        // The controller's own scope proof: a typed snapshot that names another bucket/account is
        // never this bucket's baseline, independent of what the wire client checks.
        requireOwnSnapshot(before, write.target)
        // A configuration this app cannot interpret is never replaced by a guess, and a 4xx/read
        // failure never becomes an "unconfigured" baseline.
        if (!before.editable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_SIPPY_READ_ONLY_WRITE)
        }
        if (write is R2SippyWrite.DeleteSippy && !before.enabled) {
            throw CloudFailure(CloudFailureCode.CONFLICT, "该 Sippy 配置已是停用状态，无需再次停用")
        }
        val beforeTarget = publicState(before)
        val desired = desiredTarget(write)
        requireSameLive(live)
        val plan =
            plans.prepare(
                scope = write.target.account,
                actionKey = r2SippyWriteKey(write),
                resourceRef = R2TaskTargets.sippyReference(write.target),
                beforeHash = digest(beforeTarget),
                desiredHash = digest(desired),
                requestFingerprint = requestFingerprint(write),
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val entry =
            Prepared(
                plan = plan,
                write = write,
                target = write.target,
                beforeTarget = beforeTarget,
                desiredTarget = desired,
                live = live,
                preview = previewOf(write),
                detail = detailOf(write),
                claim = DraftClaim((write as? R2SippyWrite.PutSippy)?.lease),
            )
        try {
            publish(extra = plan.id to entry) { retained ->
                // One draft backs at most one preview: a second preview would let a discard/shift of
                // either one silently damage the material of the other.
                if (retained.values.any { it.sharesDraftWith(write) }) {
                    throw multiplePreviewsForOneDraft()
                }
            }
        } catch (failure: Exception) {
            plans.discardUnclaimed(plan.id)
            publish()
            throw failure
        }
        return plan
    }

    fun preview(planId: String): R2ChangePreview = entry(planId).preview

    /** The safe, credential-free payload of one preview (for the later UI slice). */
    fun previewDetail(planId: String): R2SippyWritePreview = entry(planId).detail

    /**
     * Fail closed before any task row exists.
     *
     * A confirmation whose draft was retired (editor input change, preview discard/expiry) or whose
     * command is no longer the exact prepared one is refused here, so it can never create a queued
     * row for a request that must not be sent. An already admitted execution is left alone: a
     * duplicate confirmation still shares its cached task instead of being refused.
     */
    fun requireDispatchable(planId: String, write: R2SippyWrite) {
        val p = entry(planId)
        if (r2SippyWriteKey(write) != p.plan.actionKey) throw invalidInputFailure()
        if (write.target != p.target) throw invalidInputFailure()
        val declared = (p.write as? R2SippyWrite.PutSippy)?.lease
        val actual = (write as? R2SippyWrite.PutSippy)?.lease
        if (declared !== actual) throw invalidInputFailure()
        if (actual != null && !p.claim.isClaimed && !actual.isDraft) throw invalidInputFailure()
    }

    suspend fun submit(write: R2SippyWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, write, confirmed)
        requireNoUnsettled(p.target, p.plan.id)
        requireBound(p, write, confirmed)
        val guard = { requireBound(p, write, confirmed) }
        return coordinator.executeR2Dispatch(confirmed, guard, R2TaskTargets.group(p.target)) { composed ->
            // Only the admitted owner reaches this lambda. The claim *moves* the material to a
            // task-owned lease before any suspension of the session/authority read phase, so an
            // editor retirement, a preview discard or the 64-slot eviction in that window can
            // neither release it nor abort the transport. The guard below is unchanged: a genuinely
            // changed route/credential/command still refuses, which is never bypassed by keeping
            // the material.
            val lease = p.claim.claim()
            if (lease == null && p.write is R2SippyWrite.PutSippy) throw invalidInputFailure()
            val executionWrite: R2SippyWrite =
                when (val declared = p.write) {
                    is R2SippyWrite.PutSippy -> R2SippyWrite.PutSippy(declared.target, lease!!)
                    is R2SippyWrite.DeleteSippy -> declared
                }
            try {
                // Session/credential acquisition can suspend; it must finish before final checks.
                val api = session(p.target.account)
                guard()
                requireNoUnsettled(p.target, p.plan.id)
                val receipt =
                    api.writeSippy(executionWrite) { observed ->
                        if (observed == null) throw invalidInputFailure()
                        // The same-lock read is scope-checked here as well: a snapshot of another
                        // bucket/account is refused before the send, never trusted as this bucket.
                        requireOwnSnapshot(observed, p.target)
                        if (!observed.editable) {
                            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_SIPPY_READ_ONLY_WRITE)
                        }
                        if (digest(publicState(observed)) != p.plan.beforeHash) {
                            throw CloudFailure(CloudFailureCode.CONFLICT, "该桶的 Sippy 配置已变化，请刷新并重新预览确认")
                        }
                        // No suspension between this composed final guard and the typed send.
                        composed()
                    }
                verifyCompletion(p, api, receipt)
            } finally {
                // Releases the task-owned material when the transport never admitted it (session/
                // proof/guard refusal). Once the wire admitted the lease this is a no-op: the
                // transport owns it until it ends. The editor draft is untouched either way.
                lease?.retire()
            }
        }
    }

    /**
     * Read-only reconciliation.
     *
     * The persisted locator names the family and the bucket; it never restores a command or a
     * credential. A public GET cannot prove that a new secret was applied, so a `PUT` task is never
     * settled from it; a `DELETE` task may only be matched by an independently observed known
     * disabled state (the current state, not deletion causation).
     */
    suspend fun reconcile(taskId: String): OperationTask =
        settledRefusal(taskId) ?: coordinator.reconcile(taskId) { task ->
            val live = resolver.current() ?: throw invalidInputFailure()
            if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
            ScopeGuard.requireOwned(task.scope, live.connection)
            requireSameLive(live)
            val location = R2TaskTargets.parse(task.resourceRef) ?: return@reconcile Reconciliation.StillUnknown
            if (location.account != task.scope || !location.sippy) return@reconcile Reconciliation.StillUnknown
            if (task.actionKey !in R2TaskTargets.SIPPY_ACTIONS) return@reconcile Reconciliation.StillUnknown
            if (task.actionKey != r2SippyWriteKey(R2SippyWrite.DeleteSippy(location.target()))) {
                // A public GET can never prove that a new credential was applied to a PUT.
                return@reconcile Reconciliation.StillUnknown
            }
            val desired = task.desiredHash ?: return@reconcile Reconciliation.StillUnknown
            val evidence =
                try {
                    val api = session(location.account)
                    requireSameLive(live)
                    val target = location.target()
                    val snapshot = api.getSippy(target)
                    val observed = observedTargetFor(R2SippyWrite.DeleteSippy(target), snapshot, target)
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

    /**
     * A failed task is already settled: reconciliation returns it unchanged instead of probing the
     * provider, and a locator/scope that does not belong to it is refused.
     */
    private suspend fun settledRefusal(taskId: String): OperationTask? {
        val task = withContext(storeDispatcher) { store.get(taskId) } ?: return null
        if (task.state != TaskState.FAILED) return null
        val live = resolver.current() ?: throw invalidInputFailure()
        val location = R2TaskTargets.parse(task.resourceRef) ?: throw invalidInputFailure()
        if (!location.sippy || task.scope !is AccountScope || live.scope != task.scope ||
            location.account != task.scope || task.actionKey !in R2TaskTargets.SIPPY_ACTIONS ||
            live.credentialRevision < 0
        ) {
            throw invalidInputFailure()
        }
        requireSameLive(live)
        ScopeGuard.requireOwned(task.scope, live.connection)
        return task
    }

    /** Opportunistic cleanup: releases previews whose plan the issuer no longer retains. */
    internal fun pruneRetiredPayloads() = publish()

    /**
     * Deterministic ownership observation of one preview's task-owned material (never the secret).
     *
     * It exists so tests can assert the claim/consume lifecycle without reaching into the material.
     */
    internal fun materialObservation(planId: String): R2SippyMaterialObservation? =
        prepared.load()[planId]?.claim?.observation()

    private fun entry(id: String): Prepared = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2SippyWrite, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan) throw invalidInputFailure()
        if (r2SippyWriteKey(write) != p.plan.actionKey) throw invalidInputFailure()
        if (write.target != p.target) throw invalidInputFailure()
        // The exact same draft instance: a new draft with equal-looking values is a different
        // credential binding and can never confirm this preview.
        val declared = (p.write as? R2SippyWrite.PutSippy)?.lease
        val actual = (write as? R2SippyWrite.PutSippy)?.lease
        if (declared !== actual) throw invalidInputFailure()
        // A draft that was retired (editor input change, preview discard/expiry) can never be
        // confirmed. A lease already handed to an admitted execution is not retired by this check:
        // a duplicate confirmation must still share the cached task instead of resending.
        if (actual != null && !p.claim.isClaimed && !actual.isDraft) throw invalidInputFailure()
        if (p.plan.resourceRef != R2TaskTargets.sippyReference(p.target)) throw invalidInputFailure()
        if (requestFingerprint(write) != p.plan.requestFingerprint) throw invalidInputFailure()
        if (p.plan.beforeHash != digest(p.beforeTarget)) throw invalidInputFailure()
        if (p.plan.desiredHash != digest(desiredTarget(p.write))) throw invalidInputFailure()
        validateR2SippyTarget(write.target)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(write: R2SippyWrite): LiveOperationContext {
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

    /**
     * One independent read after a complete receipt; never another write.
     *
     * A receipt is not verified application: an unreadable, differently shaped or contradicting
     * public state keeps the task UNKNOWN, and an Accepted/Partial/Unknown receipt is never upgraded.
     */
    private suspend fun verifyCompletion(
        p: Prepared,
        api: R2SippySession,
        receipt: ExecutionReceipt,
    ): ExecutionReceipt {
        if (receipt !is ExecutionReceipt.Completed) return receipt
        val observed =
            try {
                observedTargetFor(p.write, api.getSippy(p.target), p.target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (observed == null) return ExecutionReceipt.Unknown(receipt.providerRequestId)
        return if (digest(observed) == p.plan.desiredHash) receipt
        else ExecutionReceipt.Unknown(receipt.providerRequestId)
    }

    /**
     * The public state a write must produce.
     *
     * A `PUT` asserts the enabled configuration plus the source provider/metadata and the destination
     * account/bucket it asked for. The destination access-key id is deliberately not part of it: a
     * public GET cannot prove a secret, and an echoed key id is not evidence.
     */
    private fun desiredTarget(write: R2SippyWrite): JsonObject =
        when (write) {
            is R2SippyWrite.PutSippy ->
                buildJsonObject {
                    put("family", R2TaskTargets.SIPPY_FAMILY)
                    put("enabled", true)
                    put("detailsProvided", true)
                    put("destinationAccount", write.target.account.accountId)
                    put("destinationBucket", write.target.bucket)
                    val source = write.lease.safeSource
                    put("provider", source.provider.wireValue)
                    publicSource(source)?.forEach { (key, value) -> put(key, value) }
                }
            is R2SippyWrite.DeleteSippy ->
                buildJsonObject {
                    put("family", R2TaskTargets.SIPPY_FAMILY)
                    put("enabled", false)
                }
        }

    /** The complete observable non-secret state of one safe snapshot. */
    private fun publicState(snapshot: R2SippySnapshot): JsonObject =
        buildJsonObject {
            put("family", R2TaskTargets.SIPPY_FAMILY)
            put("enabled", snapshot.enabled)
            put("detailsProvided", snapshot.detailsProvided)
            snapshot.destination?.let { destination ->
                put("destinationAccount", destination.account)
                put("destinationBucket", destination.bucket)
            }
            snapshot.source?.let { source ->
                put("provider", source.provider.wireValue)
                publicSource(source)?.forEach { (key, value) -> put(key, value) }
            }
        }

    /** A known disabled state only; anything uninterpretable is no evidence at all. */
    private fun observedDisabledState(snapshot: R2SippySnapshot): JsonObject? {
        if (!snapshot.editable) return null
        return buildJsonObject {
            put("family", R2TaskTargets.SIPPY_FAMILY)
            put("enabled", snapshot.enabled)
        }
    }

    private fun observedTargetFor(
        write: R2SippyWrite,
        snapshot: R2SippySnapshot,
        target: R2WriteTarget,
    ): JsonObject? {
        // A snapshot whose destination names another bucket/account is no evidence at all, not even
        // for a DELETE that happens to read "disabled".
        if (!snapshotOwnedBy(snapshot, target)) return null
        return when (write) {
            is R2SippyWrite.PutSippy -> if (snapshot.editable) publicState(snapshot) else null
            is R2SippyWrite.DeleteSippy -> observedDisabledState(snapshot)
        }
    }

    /**
     * The controller's own scope proof of one typed snapshot, independent of the wire client.
     *
     * A destination, when present, must name exactly this account and bucket. The approved
     * "disabled without public details" shape may carry no destination at all; this app never fills
     * one in and never infers scope from it.
     */
    private fun requireOwnSnapshot(snapshot: R2SippySnapshot, target: R2WriteTarget) {
        if (!snapshotOwnedBy(snapshot, target)) {
            throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "该 Sippy 配置不属于当前桶或账号，请刷新后重试")
        }
    }

    private fun snapshotOwnedBy(snapshot: R2SippySnapshot, target: R2WriteTarget): Boolean {
        val destination = snapshot.destination ?: return true
        return destination.account == target.account.accountId && destination.bucket == target.bucket
    }

    private fun multiplePreviewsForOneDraft() =
        CloudFailure(
            CloudFailureCode.INVALID_INPUT,
            "同一份凭据草稿不能同时用于多个预览；请重新输入凭据并生成新的草稿",
        )

    /** The public source members only; a provider's own absent member is omitted, never null-filled. */
    private fun publicSource(source: com.flarego.core.model.R2SippySourceRef): Map<String, JsonElement>? {
        val fields = mutableMapOf<String, JsonElement>()
        source.bucket?.let { fields["sourceBucket"] = kotlinx.serialization.json.JsonPrimitive(it) }
        source.region?.let { fields["sourceRegion"] = kotlinx.serialization.json.JsonPrimitive(it) }
        source.container?.let { fields["sourceContainer"] = kotlinx.serialization.json.JsonPrimitive(it) }
        source.bucketUrl?.let { fields["sourceBucketUrl"] = kotlinx.serialization.json.JsonPrimitive(it) }
        return fields.ifEmpty { null }
    }

    private fun detailOf(write: R2SippyWrite): R2SippyWritePreview =
        when (write) {
            is R2SippyWrite.PutSippy ->
                R2SippyWritePreview(
                    action = R2SippyAction.ENABLE,
                    target = write.target,
                    provider = write.lease.safeSource.provider,
                    source = write.lease.safeSource,
                    destinationAccessKeyConfigured = true,
                )
            is R2SippyWrite.DeleteSippy ->
                R2SippyWritePreview(action = R2SippyAction.DISABLE, target = write.target)
        }

    private fun previewOf(write: R2SippyWrite): R2ChangePreview {
        val target = write.target
        val impact =
            when (write) {
                is R2SippyWrite.PutSippy -> {
                    val source = write.lease.safeSource
                    val described =
                        listOfNotNull(
                            source.bucket?.let { "源桶 $it" },
                            source.region?.let { "源地区 $it" },
                            source.container?.let { "源容器 $it" },
                            source.bucketUrl?.let { "源地址 $it" },
                        ).joinToString("，")
                    "为该桶启用 Sippy 按需迁移：源 provider=${source.provider.wireValue}" +
                        (if (described.isEmpty()) "" else "（$described）") +
                        "，目标为本桶已有的 R2 Access Key。源与目标凭据只会在本次最终确认后随请求发送一次，" +
                        "不保存、不回显、不可从公开配置恢复。保存成功只表示配置已被接受，" +
                        "不表示凭据已验证、源已连通或历史对象已迁移；App 不会访问源地址。"
                }
                is R2SippyWrite.DeleteSippy ->
                    "停用该桶的 Sippy 按需迁移。已复制的对象不会被删除或回滚；" +
                        "只证明当前配置已停用，不代表对象迁移历史，也不删除任何对象。"
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
            impact,
            "$region。服务端未提供 CAS；写入前回读不保证原子并发隔离，回读与写入之间仍有竞争窗口",
        )
    }

    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))
}

/** The account/bucket/jurisdiction target of a parsed Sippy locator. */
private fun R2ConfigurationLocation.target(): R2WriteTarget =
    R2WriteTarget(account, bucket, jurisdiction)

/** Deterministic ownership observation of the app-owned material; it never exposes the secret. */
internal data class R2SippyMaterialObservation(val state: R2SippyLeaseState, val retainsMaterial: Boolean)
