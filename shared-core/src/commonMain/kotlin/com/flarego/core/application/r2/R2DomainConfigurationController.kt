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
import com.flarego.core.model.R2CustomDomain
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.TaskState
import com.flarego.core.model.freezeR2DomainWrite
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2DomainKindOf
import com.flarego.core.model.r2DomainWriteKey
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2DomainConfigurationSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Previews and executes the reviewed batch B2 domain writes through the shared confirmation
 * machinery of [R2WriteRuntime] (one issuer, one 64-slot plan window, one task store, one
 * account/jurisdiction/bucket lock).
 *
 * Domain documents are not B1 `rules[]` documents, so every action has its own explicit
 * before/after contract:
 *
 * | action | before | after |
 * | --- | --- | --- |
 * | managed r2.dev | managed document | managed document with the requested `enabled` |
 * | add custom domain | the complete custom-domain collection, domain proven absent | that single new domain (`domain`, `enabled`, `minTLS`) |
 * | edit custom domain | that single domain | that single domain with the changed fields and every preserved field |
 * | delete custom domain | the complete collection, domain proven present | the complete collection without the domain |
 *
 * A create is located by the created domain (not by the collection it read), so a later recovery
 * verifies exactly that domain. Read-back is read-only and stays inside the same bucket lock; an
 * unreadable, non-round-trippable or contradictory observation keeps the task UNKNOWN and is never
 * replayed.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2DomainConfigurationController(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val selectionEpoch: () -> Long = { 0L },
    private val session: suspend (AccountScope) -> R2DomainConfigurationSession,
) {
    private data class Prepared(
        val plan: ChangePlan,
        val write: R2DomainConfigurationWrite,
        val target: R2WriteTarget,
        val family: R2DomainKind,
        val beforeTarget: JsonObject,
        val desiredTarget: JsonObject,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
    )

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    /** Pure function of the actual scope, target and typed write, for the product resolver. */
    fun requestFingerprint(write: R2DomainConfigurationWrite): String = digest(semantic(write))

    suspend fun prepare(write: R2DomainConfigurationWrite): ChangePlan {
        val frozen = freezeR2DomainWrite(write)
        validateDomainWrite(frozen)
        val live = requireCurrent(frozen)
        requireNoUnsettled(frozen.target)
        requireSameLive(live)
        val api = session(frozen.target.account)
        requireSameLive(live)
        val before = readBefore(api, frozen)
        requireDocumentIdentity(before, frozen.target, beforeDocKind(frozen), frozen.domain)
        if (!before.editable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_DOMAIN_READ_ONLY_WRITE)
        }
        when (frozen) {
            is R2DomainConfigurationWrite.AddCustomDomain ->
                if (before.customDomains.any { it.domain == frozen.input.domain }) {
                    throw CloudFailure(CloudFailureCode.CONFLICT, "该自定义域已存在，请刷新后再编辑或删除")
                }
            is R2DomainConfigurationWrite.EditCustomDomain -> {
                if (before.customDomain?.ciphers == null) {
                    throw CloudFailure(
                        CloudFailureCode.UNSUPPORTED_CAPABILITY,
                        "无法证实现有 cipher 允许列表，未生成域名编辑预览；本阶段不写入新的 cipher 列表",
                    )
                }
                // The zone association is part of what this edit preserves; an unproven one is not
                // replaced by a placeholder null and the edit is refused instead.
                val zone = before.customDomain?.zoneId
                if (zone == null || !isInterpretableZone(zone)) {
                    throw CloudFailure(
                        CloudFailureCode.UNSUPPORTED_CAPABILITY,
                        "无法解释该自定义域当前的 Zone 关联，未生成域名编辑预览；请刷新后核对云端状态",
                    )
                }
            }
            is R2DomainConfigurationWrite.DeleteCustomDomain ->
                if (before.customDomains.none { it.domain == frozen.domain }) {
                    throw CloudFailure(CloudFailureCode.CONFLICT, "该自定义域不在当前完整列表中，请刷新后再确认")
                }
            is R2DomainConfigurationWrite.PutManagedDomain -> Unit
        }
        val beforeTarget = beforeSnapshot(frozen, before)
            ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_DOMAIN_READ_ONLY_WRITE)
        val desiredTarget = desiredTarget(frozen, beforeTarget)
        requireSameLive(live)
        val plan =
            plans.prepare(
                scope = frozen.target.account,
                actionKey = r2DomainWriteKey(frozen),
                resourceRef = locator(frozen),
                beforeHash = digest(beforeTarget),
                desiredHash = digest(desiredTarget),
                requestFingerprint = requestFingerprint(frozen),
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val entry = Prepared(plan, frozen, frozen.target, r2DomainKindOf(frozen), beforeTarget, desiredTarget, live,
            previewOf(frozen, beforeTarget))
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table.filterKeys(plans::retains) + (plan.id to entry))) break
        }
        return plan
    }

    fun preview(planId: String): R2ChangePreview = entry(planId).preview

    suspend fun submit(write: R2DomainConfigurationWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, write, confirmed)
        requireNoUnsettled(p.target, p.plan.id)
        requireBound(p, write, confirmed)
        val guard = { requireBound(p, write, confirmed) }
        return coordinator.executeR2Dispatch(confirmed, guard, R2TaskTargets.group(p.target)) { composed ->
            val api = session(p.target.account)
            guard()
            requireNoUnsettled(p.target, p.plan.id)
            val receipt =
                api.writeDomainConfiguration(p.write) { observed ->
                    if (observed == null) throw invalidInputFailure()
                    requireDocumentIdentity(observed, p.target, beforeDocKind(p.write), p.write.domain)
                    val current = beforeSnapshot(p.write, observed)
                    if (current == null || digest(current) != p.plan.beforeHash) {
                        throw CloudFailure(CloudFailureCode.CONFLICT, "该桶的域名配置已变化，请刷新并重新预览确认")
                    }
                    // No suspension between this composed final guard and the typed send.
                    composed()
                }
            verifyCompletion(p, api, receipt)
        }
    }

    /**
     * Read-only reconciliation. The persisted locator names the exact action family and, for a
     * single domain, the exact domain to verify; it never restores a command or a permission.
     */
    suspend fun reconcile(taskId: String): OperationTask =
        settledRefusal(taskId) ?: coordinator.reconcile(taskId) { task ->
            val live = resolver.current() ?: throw invalidInputFailure()
            if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
            ScopeGuard.requireOwned(task.scope, live.connection)
            requireSameLive(live)
            val location = R2TaskTargets.parse(task.resourceRef) ?: return@reconcile Reconciliation.StillUnknown
            val family = location.domainKind ?: return@reconcile Reconciliation.StillUnknown
            if (location.account != task.scope) return@reconcile Reconciliation.StillUnknown
            if (R2TaskTargets.DOMAIN_ACTIONS[task.actionKey] != family) {
                return@reconcile Reconciliation.StillUnknown
            }
            val desired = task.desiredHash ?: return@reconcile Reconciliation.StillUnknown
            if (family == R2DomainKind.CUSTOM_DOMAIN && location.domain == null) {
                return@reconcile Reconciliation.StillUnknown
            }
            val evidence =
                try {
                    val api = session(location.account)
                    requireSameLive(live)
                    val target = R2WriteTarget(location.account, location.bucket, location.jurisdiction)
                    val document = readAfter(api, task.actionKey, target, location.domain)
                    requireDocumentIdentity(document, target, afterKind(task.actionKey), location.domain)
                    val observed = observedForAction(task.actionKey, document)
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

    private suspend fun settledRefusal(taskId: String): OperationTask? {
        val task = withContext(storeDispatcher) { store.get(taskId) } ?: return null
        if (task.state != TaskState.FAILED) return null
        val live = resolver.current() ?: throw invalidInputFailure()
        val location = R2TaskTargets.parse(task.resourceRef) ?: throw invalidInputFailure()
        val family = location.domainKind ?: throw invalidInputFailure()
        if (task.scope !is AccountScope || live.scope != task.scope || location.account != task.scope ||
            R2TaskTargets.DOMAIN_ACTIONS[task.actionKey] != family || live.credentialRevision < 0 ||
            (family == R2DomainKind.CUSTOM_DOMAIN && location.domain == null)
        ) {
            throw invalidInputFailure()
        }
        requireSameLive(live)
        ScopeGuard.requireOwned(task.scope, live.connection)
        return task
    }

    internal fun pruneRetiredPayloads() {
        while (true) {
            val table = prepared.load()
            if (prepared.compareAndSet(table, table.filterKeys(plans::retains))) break
        }
    }

    private fun entry(id: String): Prepared = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2DomainConfigurationWrite, confirmed: ConfirmedPlan) {
        val frozen = freezeR2DomainWrite(write)
        if (confirmed.plan != p.plan || frozen != p.write ||
            requestFingerprint(frozen) != p.plan.requestFingerprint ||
            r2DomainWriteKey(frozen) != p.plan.actionKey || r2DomainKindOf(frozen) != p.family ||
            frozen.target != p.target || p.plan.resourceRef != locator(p.write) ||
            p.plan.beforeHash != digest(p.beforeTarget) ||
            p.plan.desiredHash != digest(desiredTarget(p.write, p.beforeTarget))
        ) {
            throw invalidInputFailure()
        }
        validateDomainWrite(frozen)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(write: R2DomainConfigurationWrite): LiveOperationContext {
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

    private fun validateDomainWrite(write: R2DomainConfigurationWrite) {
        val identity = if (write is R2DomainConfigurationWrite.EditCustomDomain ||
            write is R2DomainConfigurationWrite.DeleteCustomDomain
        ) {
            write.domain
        } else {
            null
        }
        validateR2DomainTarget(R2DomainTarget(write.target, identity), r2DomainKindOf(write))
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain -> r2DomainWriteBody(write)?.let(::requireR2DomainBodyWithinLimits)
            is R2DomainConfigurationWrite.AddCustomDomain -> r2DomainWriteBody(write)?.let(::requireR2DomainBodyWithinLimits)
            is R2DomainConfigurationWrite.EditCustomDomain ->
                requireR2DomainBodyWithinLimits(r2EditCustomDomainBody(write.edit))
            is R2DomainConfigurationWrite.DeleteCustomDomain -> Unit
        }
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

    private fun locator(write: R2DomainConfigurationWrite) =
        if (write.domain == null) R2TaskTargets.domainReference(write.target)
        else R2TaskTargets.domainReference(write.target, write.domain!!)

    private suspend fun readBefore(
        api: R2DomainConfigurationSession,
        write: R2DomainConfigurationWrite,
    ): R2DomainDocument =
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain -> api.getManagedDomain(write.target)
            is R2DomainConfigurationWrite.AddCustomDomain -> api.listCustomDomains(write.target)
            is R2DomainConfigurationWrite.EditCustomDomain ->
                api.getCustomDomain(R2DomainTarget(write.target, write.domain))
            is R2DomainConfigurationWrite.DeleteCustomDomain -> api.listCustomDomains(write.target)
        }

    private suspend fun readAfter(
        api: R2DomainConfigurationSession,
        actionKey: String,
        target: R2WriteTarget,
        domain: String?,
    ): R2DomainDocument =
        when (actionKey) {
            "r2-put-bucket-public-policy" -> api.getManagedDomain(target)
            "r2-add-custom-domain", "r2-edit-custom-domain-settings" ->
                api.getCustomDomain(R2DomainTarget(target, domain ?: throw invalidInputFailure()))
            "r2-delete-custom-domain" -> api.listCustomDomains(target)
            else -> throw invalidInputFailure()
        }

    /**
     * The document read *before* the write. A create/delete proves the complete collection; an edit
     * proves that one domain; the managed endpoint proves the managed document. This differs from
     * the action's persisted family, so it is spelled out per action.
     */
    private fun beforeDocKind(write: R2DomainConfigurationWrite): R2DomainKind =
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain -> R2DomainKind.MANAGED_DOMAIN
            is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain ->
                R2DomainKind.CUSTOM_DOMAINS
            is R2DomainConfigurationWrite.EditCustomDomain -> R2DomainKind.CUSTOM_DOMAIN
        }

    /**
     * One independent read after a complete receipt; never another write.
     *
     * A receipt is not verified application: an unreadable, non-round-trippable or contradicting
     * document keeps the task UNKNOWN, and an Accepted/Partial/Unknown receipt is never upgraded.
     */
    private suspend fun verifyCompletion(
        p: Prepared,
        api: R2DomainConfigurationSession,
        receipt: ExecutionReceipt,
    ): ExecutionReceipt {
        if (receipt !is ExecutionReceipt.Completed) return receipt
        val actionKey = r2DomainWriteKey(p.write)
        val verified =
            try {
                val document = readAfter(api, actionKey, p.target, p.write.domain)
                requireDocumentIdentity(document, p.target, afterKind(p.write), p.write.domain)
                val observed = observedForAction(actionKey, document)
                observed != null && digest(observed) == p.plan.desiredHash
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        return if (verified) receipt else ExecutionReceipt.Unknown(receipt.providerRequestId)
    }

    /** The document each action must read after the write; a create proves the new single domain. */
    private fun afterKind(write: R2DomainConfigurationWrite): R2DomainKind =
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain -> R2DomainKind.MANAGED_DOMAIN
            is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.EditCustomDomain ->
                R2DomainKind.CUSTOM_DOMAIN
            is R2DomainConfigurationWrite.DeleteCustomDomain -> R2DomainKind.CUSTOM_DOMAINS
        }

    private fun afterKind(actionKey: String): R2DomainKind =
        when (actionKey) {
            "r2-put-bucket-public-policy" -> R2DomainKind.MANAGED_DOMAIN
            "r2-add-custom-domain", "r2-edit-custom-domain-settings" -> R2DomainKind.CUSTOM_DOMAIN
            "r2-delete-custom-domain" -> R2DomainKind.CUSTOM_DOMAINS
            else -> throw invalidInputFailure()
        }

    /**
     * The controller's own identity binding, independent of the wire client's echo check: a document
     * of another bucket or family is never evidence, and a single-domain document must carry exactly
     * the domain this write/locator names. A non-editable single-domain document may carry no decoded
     * domain; the caller's editable/snapshot checks then refuse instead of guessing.
     */
    private fun requireDocumentIdentity(
        document: R2DomainDocument,
        target: R2WriteTarget,
        kind: R2DomainKind,
        domain: String? = null,
    ) {
        if (document.target != target || document.kind != kind) throw scopeMismatchFailure()
        if (kind == R2DomainKind.CUSTOM_DOMAIN && document.customDomain != null &&
            document.customDomain.domain != domain
        ) {
            throw scopeMismatchFailure()
        }
    }

    private fun isInterpretableZone(value: String): Boolean =
        try {
            validateR2ZoneId(value)
            true
        } catch (_: CloudFailure) {
            false
        }

    private fun beforeSnapshot(write: R2DomainConfigurationWrite, document: R2DomainDocument): JsonObject? =
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain -> managedSnapshot(document)
            is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain ->
                collectionSnapshot(document)
            is R2DomainConfigurationWrite.EditCustomDomain -> singleSnapshot(document)
        }

    private fun observedForAction(actionKey: String, document: R2DomainDocument): JsonObject? =
        when (actionKey) {
            "r2-put-bucket-public-policy" -> managedSnapshot(document)
            "r2-add-custom-domain" -> addSnapshot(document)
            "r2-edit-custom-domain-settings" -> singleSnapshot(document)
            "r2-delete-custom-domain" -> collectionSnapshot(document)
            else -> null
        }

    /**
     * The action-specific after target. A managed write can only change `enabled`; a create asserts
     * exactly the fields it asked for (the provider's own cipher list is not predicted); an edit
     * asserts the complete observable state of that domain.
     */
    private fun desiredTarget(write: R2DomainConfigurationWrite, before: JsonObject): JsonObject =
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain ->
                buildJsonObject {
                    put("family", R2DomainKind.MANAGED_DOMAIN.name)
                    put("domain", before["domain"] ?: JsonNull)
                    put("enabled", write.enabled)
                }
            is R2DomainConfigurationWrite.AddCustomDomain ->
                buildJsonObject {
                    put("family", R2DomainKind.CUSTOM_DOMAIN.name)
                    put("domain", write.input.domain)
                    put("enabled", write.input.enabled)
                    put("minTLS", write.input.minTls)
                    // The requested zone is part of the exact target: a same-looking domain created
                    // in another zone is not this write's result.
                    put("zoneId", write.input.zoneId)
                }
            is R2DomainConfigurationWrite.EditCustomDomain ->
                buildJsonObject {
                    put("family", R2DomainKind.CUSTOM_DOMAIN.name)
                    put("domain", before.getValue("domain"))
                    if (write.edit.enabled != null) put("enabled", write.edit.enabled!!) else put("enabled", before.getValue("enabled"))
                    if (write.edit.minTls != null) put("minTLS", write.edit.minTls!!) else put("minTLS", before.getValue("minTLS"))
                    put("ciphers", before.getValue("ciphers"))
                    put("zoneId", before.getValue("zoneId"))
                }
            is R2DomainConfigurationWrite.DeleteCustomDomain ->
                buildJsonObject {
                    put("family", R2DomainKind.CUSTOM_DOMAINS.name)
                    put(
                        "domains",
                        JsonArray(
                            (before["domains"] as? JsonArray).orEmpty()
                                .map { it as JsonPrimitive }
                                .filter { it.content != write.domain }
                                .sortedBy { it.content },
                        ),
                    )
                }
        }

    private fun managedSnapshot(document: R2DomainDocument): JsonObject? {
        if (!document.editable || document.kind != R2DomainKind.MANAGED_DOMAIN) return null
        val managed = document.managed ?: return null
        return buildJsonObject {
            put("family", R2DomainKind.MANAGED_DOMAIN.name)
            put("domain", managed.domain)
            put("enabled", managed.enabled)
        }
    }

    private fun collectionSnapshot(document: R2DomainDocument): JsonObject? {
        if (!document.editable || document.kind != R2DomainKind.CUSTOM_DOMAINS) return null
        return buildJsonObject {
            put("family", R2DomainKind.CUSTOM_DOMAINS.name)
            put(
                "domains",
                JsonArray(document.customDomains.map { it.domain }.sorted().map(::JsonPrimitive)),
            )
        }
    }

    private fun singleSnapshot(document: R2DomainDocument): JsonObject? {
        if (!document.editable || document.kind != R2DomainKind.CUSTOM_DOMAIN) return null
        val domain = document.customDomain ?: return null
        return singleSnapshotJson(
            domain.domain,
            domain.enabled,
            domain.minTls,
            domain.ciphers,
            domain.zoneId,
        )
    }

    /** A create asserts only the fields it asked for; the provider's cipher list is not predicted. */
    private fun addSnapshot(document: R2DomainDocument): JsonObject? {
        if (!document.editable || document.kind != R2DomainKind.CUSTOM_DOMAIN) return null
        val domain = document.customDomain ?: return null
        return buildJsonObject {
            put("family", R2DomainKind.CUSTOM_DOMAIN.name)
            put("domain", domain.domain)
            put("enabled", domain.enabled)
            put("minTLS", domain.minTls)
            // A create's returned zone is part of the proof: a missing/wrong zone is not success.
            put("zoneId", domain.zoneId)
        }
    }

    private fun singleSnapshotJson(
        domain: String,
        enabled: Boolean,
        minTls: String?,
        ciphers: List<String>?,
        zoneId: String?,
    ): JsonObject =
        buildJsonObject {
            put("family", R2DomainKind.CUSTOM_DOMAIN.name)
            put("domain", domain)
            put("enabled", enabled)
            put("minTLS", minTls)
            put("ciphers", ciphers?.let { list -> JsonArray(list.map(::JsonPrimitive)) } ?: JsonNull)
            put("zoneId", zoneId)
        }

    private fun semantic(write: R2DomainConfigurationWrite): JsonObject =
        buildJsonObject {
            put("action", r2DomainWriteKey(write))
            put("selectionEpoch", selectionEpoch())
            put("scope", scopeFields(write.target.account))
            put("bucket", write.target.bucket)
            put("jurisdiction", write.target.jurisdiction)
            put("family", r2DomainKindOf(write).name)
            when (write) {
                is R2DomainConfigurationWrite.PutManagedDomain -> put("enabled", write.enabled)
                is R2DomainConfigurationWrite.AddCustomDomain -> {
                    put("domain", write.input.domain)
                    put("zoneId", write.input.zoneId)
                    put("enabled", write.input.enabled)
                    put("minTLS", write.input.minTls)
                }
                is R2DomainConfigurationWrite.EditCustomDomain -> {
                    put("domain", write.domain)
                    write.edit.enabled?.let { put("enabled", it) }
                    write.edit.minTls?.let { put("minTLS", it) }
                }
                is R2DomainConfigurationWrite.DeleteCustomDomain -> put("domain", write.domain)
            }
        }

    private fun scopeFields(scope: CloudScope): JsonObject =
        buildJsonObject {
            if (scope !is AccountScope) throw invalidInputFailure()
            put("connection", scope.connectionId)
            put("account", scope.accountId)
        }

    private fun digest(value: JsonElement) = fingerprint.sha256(CanonicalJson.encode(value))

    private fun previewOf(write: R2DomainConfigurationWrite, before: JsonObject): R2ChangePreview {
        val target = write.target
        val detail =
            when (write) {
                is R2DomainConfigurationWrite.PutManagedDomain ->
                    "将 r2.dev 公开入口设置为 ${if (write.enabled) "启用" else "停用"}（显式发送 enabled）。" +
                        "关闭 r2.dev 不会关闭自定义域，也不表示桶变为私有；enabled 不代表 ownership 或 SSL 已就绪。"
                is R2DomainConfigurationWrite.AddCustomDomain ->
                    "为该桶新增自定义域 ${write.input.domain}（Zone ${write.input.zoneId}，" +
                        "enabled=${write.input.enabled}，minTLS=${write.input.minTls}）。" +
                        "本次不创建 DNS 记录；保存成功不等于 ownership、SSL 或公网证书就绪。"
                is R2DomainConfigurationWrite.EditCustomDomain -> {
                    val changes =
                        listOfNotNull(
                            write.edit.enabled?.let { "enabled → $it" },
                            write.edit.minTls?.let { "minTLS → $it（省略时按官方说明保留原值）" },
                        ).joinToString("；").ifEmpty { "仅重发已证实的当前值" }
                    "修改自定义域 ${write.domain}：$changes。cipher 仅按当前值原样保留，不声称支持、默认或清空。" +
                        "ownership 与 SSL 是独立状态，不随 enabled 自动就绪。"
                }
                is R2DomainConfigurationWrite.DeleteCustomDomain ->
                    "从该桶移除自定义域 ${write.domain}（破坏性）。不删除 DNS Zone；" +
                        "其他入口是否仍可访问以独立状态为准。"
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
}
