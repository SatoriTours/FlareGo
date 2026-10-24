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
import com.flarego.core.model.R2NotificationCollection
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationQueue
import com.flarego.core.model.R2NotificationRule
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2NotificationWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.TaskState
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.requireR2NotificationQueueId
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2NotificationSession
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Safe, non-secret description of one prepared notification write (Task 3 display input only). */
data class R2NotificationWritePreview(
    val target: R2WriteTarget,
    val queueId: String,
    val queueName: String?,
    /** APPEND, SELECTED or ALL — the verified after-contract is chosen by exactly this value. */
    val intent: R2NotificationIntent,
    /** Existing rule count observed at preview time (never a provider total claim). */
    val existingRules: Int,
    /** New rule descriptions only; rule bodies stay in the frozen command. */
    val newRuleSummaries: List<String> = emptyList(),
    /** IDs of the selected deletion (public rule identities already in the fresh document). */
    val selectedRuleIds: List<String> = emptyList(),
)

/**
 * Previews, executes and read-only-recovers the reviewed batch C event-notification writes through
 * the shared confirmation machinery of [R2WriteRuntime].
 *
 * Action-specific contracts (never one shared guess):
 * - **append** sends only new rules and is verified by comparing the fresh pre-write document with an
 *   independent post-write read: every previous rule ID must still be present with identical content,
 *   the rule count must grow by exactly the submitted count, and each submitted rule must appear as a
 *   *new* rule (a new provider ID) with exactly the submitted content. Description equality is never
 *   used as identity.
 * - **selected deletion** requires every selected ID to exist in the fresh pre-write document; the
 *   post-write read must show exactly those IDs gone and every other previous rule unchanged.
 * - **whole-queue clear** is verified through the bucket's complete configuration list: the target
 *   queue must have no rules there. A single `GET` 404 is not an absence proof, and a queue that
 *   disappears while it should still have remaining rules is never a success.
 *
 * The final guard runs on the wire client's **own** fresh pre-write document, so the compared
 * baseline is the document that is about to be modified. A missing/unreadable/contradicting
 * observation keeps the task UNKNOWN; nothing is retried and no rule is ever rewritten to "fix" a
 * mismatch.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2NotificationController(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val store: OperationStore,
    private val storeDispatcher: CoroutineDispatcher,
    private val selectionEpoch: () -> Long = { 0L },
    private val session: suspend (AccountScope) -> R2NotificationSession,
) {
    private class Prepared(
        val plan: ChangePlan,
        val write: R2NotificationWrite,
        val target: R2WriteTarget,
        val beforeState: JsonObject,
        val desiredState: JsonObject,
        val live: LiveOperationContext,
        val preview: R2ChangePreview,
        val detail: R2NotificationWritePreview,
        /** The complete pre-write document observed at preview time (frozen, public). */
        val beforeQueue: R2NotificationQueue,
    )

    private val prepared = AtomicReference<Map<String, Prepared>>(emptyMap())

    private fun publish(extra: Pair<String, Prepared>? = null) {
        while (true) {
            val table = prepared.load()
            val retained = table.filterKeys(plans::retains)
            val next = if (extra == null) retained else retained + extra
            if (!prepared.compareAndSet(table, next)) continue
            return
        }
    }

    internal fun pruneRetiredPayloads() = publish()

    fun requestFingerprint(write: R2NotificationWrite): String {
        val target = write.target
        return digest(
            buildJsonObject {
                put("action", r2NotificationWriteKey(write))
                put("intent", r2NotificationIntentOf(write).family)
                put("selectionEpoch", selectionEpoch())
                put("connection", target.account.connectionId)
                put("account", target.account.accountId)
                put("bucket", target.bucket)
                put("jurisdiction", target.jurisdiction)
                put("queue", write.queueId)
                when (write) {
                    is R2NotificationWrite.Append -> put("rules", write.canonicalRules)
                    is R2NotificationWrite.Delete -> {
                        put(
                            "selected",
                            buildJsonArray { write.selectedIds.sorted().forEach { add(JsonPrimitive(it)) } },
                        )
                    }
                }
            },
        )
    }

    suspend fun prepare(write: R2NotificationWrite): ChangePlan {
        validateR2NotificationTarget(write.target)
        requireR2NotificationQueueId(write.queueId)
        val localIntent = R2TaskTargets.notificationReference(write.target, write.queueId, r2NotificationIntentOf(write))
        val live = requireCurrent(write)
        requireNoUnsettled(write.target)
        requireSameLive(live)
        val api = session(write.target.account)
        requireSameLive(live)
        val before = api.getConfiguration(write.target, write.queueId)
        // The controller's own scope proof: a document of another queue/bucket is never this baseline,
        // independently of what the wire client checks.
        if (before.queueId != write.queueId || !before.interpretable) throw readOnlyFailure()
        requireSameLive(live)
        when (write) {
            is R2NotificationWrite.Append -> requireR2NotificationAppendBudget(before, write.rules)
            is R2NotificationWrite.Delete -> requireR2NotificationSelectionPresent(before, write.selectedIds)
        }
        val beforeState = r2NotificationQueueState(before)
        val desired = desiredState(write, before)
        requireSameLive(live)
        val plan =
            plans.prepare(
                scope = write.target.account,
                actionKey = r2NotificationWriteKey(write),
                resourceRef = localIntent,
                beforeHash = digest(beforeState),
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
                beforeState = beforeState,
                desiredState = desired,
                live = live,
                preview = previewOf(write, before),
                detail = detailOf(write, before),
                beforeQueue = before,
            )
        try {
            publish(extra = plan.id to entry)
        } catch (failure: Exception) {
            plans.discardUnclaimed(plan.id)
            publish()
            throw failure
        }
        return plan
    }

    fun preview(planId: String): R2ChangePreview = entry(planId).preview

    /** The safe, non-secret detail of one preview (Task 3 display input). */
    fun previewDetail(planId: String): R2NotificationWritePreview = entry(planId).detail

    suspend fun submit(write: R2NotificationWrite, confirmed: ConfirmedPlan): OperationTask {
        val p = entry(confirmed.plan.id)
        requireBound(p, write, confirmed)
        requireNoUnsettled(p.target, p.plan.id)
        requireBound(p, write, confirmed)
        val guard = { requireBound(p, write, confirmed) }
        return coordinator.executeR2Dispatch(confirmed, guard, R2TaskTargets.group(p.target)) { beforeSend ->
            val api = session(p.target.account)
            guard()
            requireNoUnsettled(p.target, p.plan.id)
            val receipt =
                api.writeNotification(p.write) { before ->
                    // This callback carries the client's *own* fresh pre-write document and runs right
                    // before the typed send: compare exactly that baseline, then the composed guard.
                    if (!before.interpretable || before.queueId != p.write.queueId) {
                        throw readOnlyFailure()
                    }
                    if (digest(r2NotificationQueueState(before)) != p.plan.beforeHash) {
                        throw CloudFailure(
                            CloudFailureCode.CONFLICT,
                            "该 Queue 的通知配置已变化，请刷新后重新预览确认",
                        )
                    }
                    beforeSend()
                }
            verifyWrite(p, api, receipt)
        }
    }

    /**
     * Read-only verification of one notification task.
     *
     * The whole-queue clear is fully verifiable from the bucket's configuration list, so it can settle
     * here. An append or a selected deletion needs the frozen pre-write document to prove "these rules
     * appeared / those rules disappeared while the others stayed"; this app stores no rule payload, so
     * such a task is never upgraded later — it stays as it is instead of being guessed.
     */
    suspend fun reconcile(taskId: String): OperationTask {
        val existing = withContext(storeDispatcher) { store.get(taskId) } ?: throw invalidInputFailure()
        if (existing.state == TaskState.SUCCEEDED || existing.state == TaskState.FAILED) {
            // A terminal row is history: a later observation never rewrites it.
            return terminalRow(existing)
        }
        return coordinator.reconcile(taskId) { task ->
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
        ScopeGuard.requireOwned(task.scope, live.connection)
        requireSameLive(live)
        if (task.actionKey !in R2TaskTargets.NOTIFICATION_ACTIONS) return@reconcile Reconciliation.StillUnknown
        val location = R2TaskTargets.parseNotification(task.resourceRef) ?: throw unlocatableFailure()
        if (location.account != task.scope) throw unlocatableFailure()
        val desired = task.desiredHash ?: return@reconcile Reconciliation.StillUnknown
        // The action must match the locator's action-specific family (an append can never be recovered
        // through a deletion contract and vice versa).
        val expectedFamilies =
            when (task.actionKey) {
                R2TaskTargets.NOTIFICATION_APPEND_ACTION -> setOf(R2NotificationIntent.APPEND.family)
                R2TaskTargets.NOTIFICATION_DELETE_ACTION -> setOf(R2NotificationIntent.SELECTED.family, R2NotificationIntent.ALL.family)
                else -> emptySet()
            }
        if (location.intent.family !in expectedFamilies) throw unlocatableFailure()
        if (location.intent == R2NotificationIntent.APPEND) throw payloadRequiredFailure()
        val api = session(location.account)
        requireSameLive(live)
        val collection =
            try {
                api.listConfigurations(location.target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw refreshUnavailableFailure()
            }
        requireSameLive(live)
        // The same single evidence entry point as the immediate after-read: a document that is not this
        // bucket's own fully interpretable configuration can never prove anything here either.
        val observed =
            when (val evidence = ownQueueEvidence(collection, location.bucket, location.queueId)) {
                is QueueEvidence.Present -> observedAfterStateFor(location, evidence.queue)
                // A queue absent from this own, fully interpretable list has no rules: the legitimate end
                // state for a deletion whose frozen remaining set is empty, and a contradiction (the real
                // digest below decides) for one that had to leave rules. No original selection is guessed.
                QueueEvidence.Absent -> observedAfterStateFor(location, emptyQueue(location.queueId))
                QueueEvidence.Invalid -> return@reconcile Reconciliation.StillUnknown
            }
        val observedHash = digest(observed)
        if (observedHash == desired) Reconciliation.Matched(observedHash) else Reconciliation.StillUnknown
        }
    }

    /** The one representation of "this queue currently has no rules" (an absent queue included). */
    private fun emptyQueue(queueId: String) = R2NotificationQueue(queueId, null, emptyList(), interpretable = true)

    private fun observedAfterStateFor(location: R2NotificationLocation, queue: R2NotificationQueue): JsonObject =
        buildJsonObject {
            put("family", location.intent.family)
            put("queue", location.queueId)
            put(
                "rules",
                buildJsonArray {
                    queue.rules.map { rule -> rule.ruleId to canonicalRule(rule) }.sortedBy { it.first ?: "" }.forEach { (id, canonical) ->
                        add(
                            buildJsonObject {
                                put("ruleId", id ?: "")
                                put("content", canonical)
                            },
                        )
                    }
                },
            )
        }

    /** Ownership/locator checks a terminal row still has to pass before it is returned as-is. */
    private fun terminalRow(task: OperationTask): OperationTask {
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.scope != task.scope || live.credentialRevision < 0) throw invalidInputFailure()
        ScopeGuard.requireOwned(task.scope, live.connection)
        if (task.actionKey !in R2TaskTargets.NOTIFICATION_ACTIONS) throw invalidInputFailure()
        R2TaskTargets.parseNotification(task.resourceRef) ?: throw unlocatableFailure()
        return task
    }

    private fun entry(id: String): Prepared = prepared.load()[id] ?: throw invalidInputFailure()

    private fun requireBound(p: Prepared, write: R2NotificationWrite, confirmed: ConfirmedPlan) {
        if (confirmed.plan != p.plan) throw invalidInputFailure()
        if (p.write != write) throw invalidInputFailure()
        if (p.plan.actionKey != r2NotificationWriteKey(write)) throw invalidInputFailure()
        if (p.plan.resourceRef != R2TaskTargets.notificationReference(p.target, write.queueId, r2NotificationIntentOf(write))) {
            throw invalidInputFailure()
        }
        if (requestFingerprint(write) != p.plan.requestFingerprint) throw invalidInputFailure()
        if (p.plan.beforeHash != digest(p.beforeState)) throw invalidInputFailure()
        if (p.plan.desiredHash != digest(desiredState(p.write, p.beforeQueue))) throw invalidInputFailure()
        validateR2NotificationTarget(write.target)
        requireSameLive(p.live)
        if (plans.isExpired(p.plan)) throw invalidInputFailure()
    }

    private fun requireCurrent(write: R2NotificationWrite): LiveOperationContext {
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
        if (blocked) {
            throw CloudFailure(
                CloudFailureCode.UNKNOWN_RESULT,
                "该桶的上一操作尚未核对，请先核对结果，不要再次发送",
                uncertain = true,
            )
        }
    }

    /**
     * The frozen desired state.
     *
     * An append cannot predict provider-assigned rule IDs, so its desired state is the frozen delta and
     * its verification is structural. A deletion *can* be expressed completely: the expected remaining
     * rule set is computed from the frozen pre-write document, so the plan's desired hash equals the
     * digest of exactly the state a correct deletion must leave behind. That is what makes a later
     * read-only recovery possible without storing any rule payload.
     */
    private fun desiredState(write: R2NotificationWrite, before: R2NotificationQueue): JsonObject =
        when (write) {
            is R2NotificationWrite.Append ->
                buildJsonObject {
                    put("family", R2TaskTargets.NOTIFICATION_APPEND_FAMILY)
                    put("queue", write.queueId)
                    put("rules", Json.parseToJsonElement(write.canonicalRules))
                }
            is R2NotificationWrite.Delete -> expectedAfterState(write, before)
        }

    /** The exact semantic state a correct deletion must leave behind (rules kept + the intent). */
    private fun expectedAfterState(write: R2NotificationWrite.Delete, before: R2NotificationQueue): JsonObject {
        val intent = r2NotificationIntentOf(write)
        val selected = write.selectedIds.toSet()
        val remaining =
            if (intent == R2NotificationIntent.ALL) emptyList()
            else before.rules.filter { it.ruleId == null || it.ruleId !in selected }
        return buildJsonObject {
            put("family", intent.family)
            put("queue", write.queueId)
            put(
                "rules",
                buildJsonArray {
                    remaining.map { rule -> rule.ruleId to canonicalRule(rule) }.sortedBy { it.first ?: "" }.forEach { (id, canonical) ->
                        add(
                            buildJsonObject {
                                put("ruleId", id ?: "")
                                put("content", canonical)
                            },
                        )
                    }
                },
            )
        }
    }

    /** The same semantic state reconstructed from a fresh, complete observation of the queue. */
    private fun observedAfterState(write: R2NotificationWrite.Delete, after: R2NotificationQueue): JsonObject =
        buildJsonObject {
            put("family", r2NotificationIntentOf(write).family)
            put("queue", write.queueId)
            put(
                "rules",
                buildJsonArray {
                    after.rules.map { rule -> rule.ruleId to canonicalRule(rule) }.sortedBy { it.first ?: "" }.forEach { (id, canonical) ->
                        add(
                            buildJsonObject {
                                put("ruleId", id ?: "")
                                put("content", canonical)
                            },
                        )
                    }
                },
            )
        }

    /**
     * One independent read after the write, judged by the action-specific contract.
     *
     * Only a trusted `Completed` receipt is verified here; `Accepted`/`Partial`/`Unknown` are returned
     * unchanged and are never upgraded to a success.
     */
    private suspend fun verifyWrite(
        p: Prepared,
        api: R2NotificationSession,
        receipt: ExecutionReceipt,
    ): ExecutionReceipt {
        if (receipt !is ExecutionReceipt.Completed) return receipt
        val matched =
            when (p.write) {
                is R2NotificationWrite.Append -> {
                    val after = readQueueOrNull(api, p) ?: return ExecutionReceipt.Unknown(receipt.providerRequestId)
                    appendHeld(p.beforeQueue, after, p.write)
                }
                is R2NotificationWrite.Delete ->
                    when (val intent = r2NotificationIntentOf(p.write)) {
                        // The whole-queue clear, and a selected deletion that removes every rule, are
                        // proven by the complete configuration list: the single queue read legitimately
                        // answers 404 once no rule is left, and that 404 is never treated as proof.
                        R2NotificationIntent.ALL -> deletionHeld(api, p, expectAbsentQueue = true)
                        R2NotificationIntent.SELECTED -> {
                            val remaining = p.beforeQueue.rules.count { rule -> rule.ruleId == null || rule.ruleId !in p.write.selectedIds.toSet() }
                            if (remaining == 0) {
                                deletionHeld(api, p, expectAbsentQueue = true)
                            } else {
                                val after = readQueueOrNull(api, p) ?: return ExecutionReceipt.Unknown(receipt.providerRequestId)
                                digest(observedAfterState(p.write, after)) == p.plan.desiredHash
                            }
                        }
                        R2NotificationIntent.APPEND -> return ExecutionReceipt.Unknown(receipt.providerRequestId)
                    }
            }
        return if (matched) receipt else ExecutionReceipt.Unknown(receipt.providerRequestId)
    }

    /** The single-queue after-read, validated by the same [ownQueue] predicate used by recovery. */
    private suspend fun readQueueOrNull(api: R2NotificationSession, p: Prepared): R2NotificationQueue? =
        try {
            ownQueue(api.getConfiguration(p.target, p.write.queueId), p.write.queueId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /**
     * The deletion is proven from the bucket's complete configuration list: the target queue is either
     * absent or carries exactly the expected remaining rules. A single queue read is not consulted, so
     * a legitimate 404 cannot block the proof and a foreign/missing identity is never a baseline.
     */
    private suspend fun deletionHeld(
        api: R2NotificationSession,
        p: Prepared,
        expectAbsentQueue: Boolean,
    ): Boolean {
        val collection =
            try {
                api.listConfigurations(p.target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
        return when (val evidence = ownQueueEvidence(collection, p.target.bucket, p.write.queueId)) {
            is QueueEvidence.Present -> digest(observedAfterState(p.write, evidence.queue)) == p.plan.desiredHash
            QueueEvidence.Absent -> expectAbsentQueue && digest(observedAfterState(p.write, emptyQueue(p.write.queueId))) == p.plan.desiredHash
            QueueEvidence.Invalid -> false
        }
    }

    /**
     * The one validated view of an observation used as settlement evidence.
     *
     * [Absent] means "this own, fully interpretable document does not contain the queue", which is a
     * legitimate end state. [Invalid] means the document (or its target queue) cannot serve as
     * evidence at all — it is never turned into an empty candidate state.
     */
    private sealed interface QueueEvidence {
        data class Present(val queue: R2NotificationQueue) : QueueEvidence

        data object Absent : QueueEvidence

        data object Invalid : QueueEvidence
    }

    /**
     * The single "own + interpretable" predicate for one queue document read.
     *
     * Both the immediate after-read and recovery call it, so a queue that is not this write's own
     * address, or that the provider did not fully explain, can never become evidence.
     */
    private fun ownQueue(queue: R2NotificationQueue?, queueId: String): R2NotificationQueue? =
        queue?.takeIf { it.interpretable && it.queueId == queueId }

    /**
     * The single evidence entry point of one complete bucket configuration.
     *
     * It is the only place that may turn a document into a `Present`/`Absent` observation: the
     * document must be this bucket's own interpretable configuration with non-empty unique queue IDs
     * and identified rules, and — when the addressed queue is present — that queue itself must be
     * interpretable and carry exactly this write's queue ID. Anything else is [QueueEvidence.Invalid].
     */
    private fun ownQueueEvidence(
        collection: R2NotificationCollection?,
        bucket: String,
        queueId: String,
    ): QueueEvidence {
        val own = ownCollection(collection, bucket) ?: return QueueEvidence.Invalid
        val queue = own.queues.firstOrNull { it.queueId == queueId } ?: return QueueEvidence.Absent
        val validated = ownQueue(queue, queueId) ?: return QueueEvidence.Invalid
        return QueueEvidence.Present(validated)
    }

    /**
     * The controller's own check of one complete bucket configuration.
     *
     * `interpretable` alone is not a baseline: the document itself must be interpretable, it must name
     * exactly this bucket, and every queue/rule must carry a non-blank unique identity. The addressed
     * queue's own interpretability is checked by [ownQueueEvidence], which every settlement path uses.
     */
    private fun ownCollection(
        collection: R2NotificationCollection?,
        bucket: String,
    ): R2NotificationCollection? {
        val document = collection ?: return null
        if (!document.interpretable) return null
        if (document.bucketName != bucket) return null
        val ids = document.queues.map { it.queueId }
        if (ids.any { it.isNullOrBlank() }) return null
        if (ids.size != ids.distinct().size) return null
        if (document.queues.any { queue -> queue.rules.any { it.ruleId.isNullOrBlank() } }) return null
        return document
    }

    /** Every previous rule is intact and each submitted rule appears exactly once as a *new* rule. */
    private fun appendHeld(
        before: R2NotificationQueue,
        after: R2NotificationQueue,
        write: R2NotificationWrite.Append,
    ): Boolean {
        if (!previousRulesPreserved(before, after, removed = emptySet())) return false
        if (after.rules.size != before.rules.size + write.rules.size) return false
        val previousIds = before.rules.mapNotNull { it.ruleId }.toSet()
        val added = after.rules.filter { it.ruleId == null || it.ruleId !in previousIds }
        if (added.size != write.rules.size) return false
        // One-to-one by content: a description alone is never an identity.
        val expected = write.rules.map { canonicalNewRule(it) }.sorted()
        val observed = added.map { canonicalRule(it) }.sorted()
        return expected == observed
    }


    /** Every previous rule that was not removed is still present with identical content. */
    private fun previousRulesPreserved(
        before: R2NotificationQueue,
        after: R2NotificationQueue,
        removed: Set<String>,
    ): Boolean {
        val afterById = after.rules.mapNotNull { rule -> rule.ruleId?.let { it to canonicalRule(rule) } }.toMap()
        for (rule in before.rules) {
            val id = rule.ruleId ?: return false
            if (id in removed) continue
            if (afterById[id] != canonicalRule(rule)) return false
        }
        return true
    }

    private fun canonicalRule(rule: R2NotificationRule): String =
        buildJsonObject {
            put("actions", buildJsonArray { rule.actions.forEach { add(JsonPrimitive(it)) } })
            put("description", rule.description ?: "")
            put("prefix", rule.prefix ?: "")
            put("suffix", rule.suffix ?: "")
        }.toString()

    private fun canonicalNewRule(rule: R2NotificationRuleSpec): String =
        buildJsonObject {
            put("actions", buildJsonArray { rule.actions.forEach { add(JsonPrimitive(it)) } })
            put("description", rule.description ?: "")
            put("prefix", rule.prefix ?: "")
            put("suffix", rule.suffix ?: "")
        }.toString()

    private fun detailOf(write: R2NotificationWrite, before: R2NotificationQueue): R2NotificationWritePreview {
        val intent = r2NotificationIntentOf(write)
        return R2NotificationWritePreview(
            target = write.target,
            queueId = write.queueId,
            queueName = before.queueName,
            intent = intent,
            existingRules = before.rules.size,
            newRuleSummaries =
                when (write) {
                    is R2NotificationWrite.Append ->
                        write.rules.map { rule ->
                            val described = rule.description?.takeIf { it.isNotBlank() }
                            "${rule.actions.joinToString("+")}" + (described?.let { "（$it）" } ?: "")
                        }
                    is R2NotificationWrite.Delete -> emptyList()
                },
            selectedRuleIds = (write as? R2NotificationWrite.Delete)?.selectedIds ?: emptyList(),
        )
    }

    private fun previewOf(write: R2NotificationWrite, before: R2NotificationQueue): R2ChangePreview {
        val target = write.target
        val queue = before.queueName?.let { "$it（${write.queueId}）" } ?: write.queueId
        val impact =
            when (write) {
                is R2NotificationWrite.Append ->
                    "为 Queue $queue 追加 ${write.rules.size} 条新通知规则（当前已知 ${before.rules.size} 条）：" +
                        "只新增，不替换或修改既有规则；既有规则的身份与内容在写后独立回读中必须保持不变。" +
                        "新增规则可能触发既有消费者并产生费用；写入只证明配置被接受，不证明消息送达或消费者已执行。" +
                        (write.rules.firstOrNull { (it.prefix ?: it.suffix) == null }?.let {
                            "没有 prefix/suffix 过滤的规则会覆盖该 Queue 的全部对象事件（缺省即全部，请确认）。"
                        } ?: "")
                is R2NotificationWrite.Delete ->
                    when (write.deletion) {
                        is R2NotificationDeletion.Selected ->
                            "从 Queue $queue 删除选中的 ${write.selectedIds.size} 条规则（当前已知 ${before.rules.size} 条）：" +
                                "只删除这些规则 ID，其余规则必须保留；可能会中断依赖这些规则的事件处理。" +
                                "写入只证明配置被删除，不证明已送达或计费状态。"
                        R2NotificationDeletion.All ->
                            "清除 Queue $queue 的**全部**通知规则（当前已知 ${before.rules.size} 条）：" +
                                "可能立即中断现有事件处理；已投递消息与消费者状态不受影响，也不删除 Queue。" +
                                "写入只证明该 Queue 已无规则，不证明已送达或计费状态。"
                    }
            }
        val region =
            if (target.jurisdiction == R2TaskTargets.DEFAULT_JURISDICTION) {
                "目标地区：default（默认地区）；同名桶在其他地区是不同资源"
            } else {
                "目标地区：${target.jurisdiction}（非默认地区）；请在相同地区核对结果，App 不会退回 default 桶"
            }
        return R2ChangePreview(
            scope = target.account,
            bucket = target.bucket,
            impact = impact,
            concurrencyWarning = "$region。预览与发送前都会独立读取该 Queue 的完整配置；" +
                "服务端未提供 CAS：可观察到的漂移会被拒绝，但最终读取与写入之间仍有竞争窗口，" +
                "矛盾的回读只会保持“结果不确定”，本应用不宣称锁住并发或保证竞态一定被拒",
        )
    }

    private fun digest(value: JsonObject): String = fingerprint.sha256(CanonicalJson.encode(value))

    private fun observedAfterState(write: R2NotificationWrite, after: R2NotificationQueue): JsonObject =
        observedAfterStateFor(
            R2NotificationLocation(write.target.account, write.target.bucket, write.target.jurisdiction, write.queueId, r2NotificationIntentOf(write)),
            after,
        )
}

private fun readOnlyFailure(): CloudFailure =
    CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_NOTIFICATION_READ_ONLY_WRITE)

private fun unlocatableFailure(): CloudFailure =
    CloudFailure(
        CloudFailureCode.INVALID_INPUT,
        "该通知任务缺少可核对的只读定位（Queue 或删除意图），只能保持原状态；应用不会猜测",
    )

private fun payloadRequiredFailure(): CloudFailure =
    CloudFailure(
        CloudFailureCode.UNSUPPORTED_CAPABILITY,
        "该通知任务只能在原始预览下核对（应用不保存规则载荷，也不会凭猜测升级为成功）；请以云端配置为准",
    )

private fun refreshUnavailableFailure(): CloudFailure =
    CloudFailure(
        CloudFailureCode.UNAVAILABLE,
        "暂时无法读取该桶的通知配置；任务保持原状态，不会被降级或重发",
    )
