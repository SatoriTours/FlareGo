package com.flarego.core.application.operations

import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionRegistration
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.model.*
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Issuer-owned execution claim. Every admissible call for one issued confirmation
 * shares this single holder, so no caller can obtain a second execution or a
 * different result.
 */
internal class ExecutionClaim {
    val outcome = CompletableDeferred<Result<OperationTask>>()
}

internal class AdmittedConfirmation(val plan: ChangePlan, val claim: ExecutionClaim, val owner: Boolean)

/** Immutable coordination state, published with compare-and-set so it is shared immediately. */
@OptIn(ExperimentalAtomicApi::class)
internal data class CoordinationState(
    val resources: Map<String, Mutex>,
    val metadataGates: Map<String, Mutex>,
    val invalidated: Set<String>,
    val generations: Map<String, Long>,
)

/**
 * The one coordination domain of an issuer: resource locks, metadata latches,
 * invalidation and generations are shared by every engine that uses this issuer, so
 * no engine can hold a different lock or miss another engine's invalidation.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CoordinationDomain {
    private val current =
        AtomicReference(
            CoordinationState(
                resources = emptyMap(),
                metadataGates = emptyMap(),
                invalidated = emptySet(),
                generations = emptyMap(),
            )
        )

    /** One stable lock per key, published with compare-and-set. */
    fun stableLock(key: String, metadata: Boolean): Mutex {
        while (true) {
            val now = current.load()
            val table = if (metadata) now.metadataGates else now.resources
            table[key]?.let { return it }
            val created = Mutex()
            val next =
                if (metadata) now.copy(metadataGates = now.metadataGates + (key to created))
                else now.copy(resources = now.resources + (key to created))
            if (current.compareAndSet(now, next)) return created
        }
    }

    /** Immediate invalidation with a generation bump; visible to every engine at once. */
    fun invalidate(connectionId: String) {
        while (true) {
            val now = current.load()
            val next =
                now.copy(
                    invalidated = now.invalidated + connectionId,
                    generations = now.generations + (connectionId to (now.generations[connectionId] ?: 0L) + 1L),
                )
            if (current.compareAndSet(now, next)) return
        }
    }

    fun isInvalidated(connectionId: String): Boolean = connectionId in current.load().invalidated

    fun generationOf(connectionId: String): Long = current.load().generations[connectionId] ?: 0L
}

/** Trusted wall clock for plan validity. */
fun interface OperationClock {
    fun nowMs(): Long
}

/** Identity source for plans and confirmations. */
fun interface OperationIds {
    fun nextId(): String
}

/**
 * An independent snapshot of what the product currently has selected and what the
 * current command really is. It is never back-filled from a plan field.
 */
data class LiveOperationContext(
    val connection: Connection,
    val scope: CloudScope,
    val credentialRevision: Long,
    val requestFingerprint: String,
)

/**
 * Resolves live product state. [current] is the current real selection and input;
 * [isLive] checks that a scope (and, when given, a revision) is still real, without
 * treating "another account is selected" as "the old account is gone";
 * [liveConnections] grants no cloud permission by itself.
 */
interface OperationContextResolver {
    fun current(): LiveOperationContext?

    fun liveConnections(): List<Connection>

    fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean
}

/** Exactly nine fields; the payload is never part of a plan. */
data class ChangePlan(
    val id: String,
    val scope: CloudScope,
    val actionKey: String,
    val resourceRef: ResourceRef?,
    val beforeHash: String?,
    val desiredHash: String?,
    val requestFingerprint: String,
    val credentialRevision: Long,
    val expiresAtMs: Long,
)

/**
 * A real issued confirmation. Not a data class and not copyable, and it carries no
 * public constructor: the only way to obtain one is [ChangePlans.confirm], and the
 * issuer registers the actual instance identity.
 */
class ConfirmedPlan internal constructor(
    val plan: ChangePlan,
    val confirmationId: String,
)

/**
 * Trusted issuance of short-lived, non-replayable write confirmations.
 *
 * A prepared plan is inert data. Confirming checks the trusted current snapshot
 * against the plan and returns one registered instance per plan; the issuer's
 * clock decides expiry, so a caller-supplied time can shorten a validity window but
 * never extend it or revive an expired plan.
 */
class ChangePlans(
    private val context: OperationContextResolver,
    private val clock: OperationClock,
    private val ids: OperationIds,
    private val retentionLimit: Int? = null,
) {
    init { require(retentionLimit == null || retentionLimit > 0) }
    // Used only by opt-in bounded issuers. Reclaimed string IDs must never identify
    // a later plan or confirmation, even if a trusted ID source repeats a value.
    private var retainedSerial = 0L

    /** Shared by every engine built on this issuer. */
    internal val coordination = CoordinationDomain()

    private val gate = Mutex()
    private val prepared = mutableMapOf<String, ChangePlan>()
    private val issued = linkedMapOf<String, Issued>()

    private class Issued(val plan: ChangePlan, val confirmation: ConfirmedPlan) {
        var claim: ExecutionClaim? = null
    }

    fun prepare(
        scope: CloudScope,
        actionKey: String,
        resourceRef: ResourceRef?,
        beforeHash: String?,
        desiredHash: String?,
        requestFingerprint: String,
        credentialRevision: Long,
        nowMs: Long,
    ): ChangePlan {
        if (!gate.tryLock()) throw invalidInputFailure()
        try {
            requireWritableRegistration(actionKey, scope)
            requireLiveSnapshot(scope, resourceRef, credentialRevision, requestFingerprint)
            if (nowMs < 0 || credentialRevision < 0) throw invalidInputFailure()
            requireSha256(beforeHash)
            requireSha256(desiredHash)
            requireSha256(requestFingerprint)
            val trusted = trustedNow()
            // A caller time may only shorten the window; the trusted clock caps it.
            val base = minOf(nowMs, trusted)
            if (base > Long.MAX_VALUE - TTL_MS) throw invalidInputFailure()
            pruneExpiredLocked(trusted)
            if (retentionLimit != null && prepared.size >= retentionLimit) {
                throw CloudFailure(CloudFailureCode.INVALID_INPUT, "待保留的预览或确认已达上限，请取消旧预览或稍后再试")
            }
            val id = nextIdentity()
            if (id.isBlank() || id in prepared) throw invalidInputFailure()
            val plan =
                ChangePlan(
                    id, scope, actionKey, resourceRef, beforeHash, desiredHash,
                    requestFingerprint, credentialRevision, base + TTL_MS,
                )
            prepared[id] = plan
            return plan
        } finally {
            gate.unlock()
        }
    }

    fun confirm(
        planId: String,
        currentScope: CloudScope,
        credentialRevision: Long,
        requestFingerprint: String,
        nowMs: Long,
    ): ConfirmedPlan {
        if (!gate.tryLock()) throw invalidInputFailure()
        try {
            val plan = prepared[planId] ?: throw invalidInputFailure()
            if (nowMs < 0) throw invalidInputFailure()
            val trusted = trustedNow()
            if (trusted >= plan.expiresAtMs) throw invalidInputFailure()
            if (currentScope != plan.scope) throw invalidInputFailure()
            if (credentialRevision != plan.credentialRevision) throw invalidInputFailure()
            if (requestFingerprint != plan.requestFingerprint) throw invalidInputFailure()
            requireWritableRegistration(plan.actionKey, plan.scope)
            requireLiveSnapshot(plan.scope, plan.resourceRef, plan.credentialRevision, requestFingerprint)
            val existing = issued[planId]
            if (existing != null) return existing.confirmation
            val confirmationId = nextIdentity()
            if (confirmationId.isBlank() || issued.values.any { it.confirmation.confirmationId == confirmationId }) {
                throw invalidInputFailure()
            }
            val confirmation = ConfirmedPlan(plan, confirmationId)
            issued[planId] = Issued(plan, confirmation)
            return confirmation
        } finally {
            gate.unlock()
        }
    }

    /**
     * Admits one execution for a confirmation. The registered instance identity is
     * verified inside the issuer's own lock *before* any cache is consulted, so a
     * clone, a foreign issuer object or a forged string can never claim or poison a
     * result. The first admissible call becomes the owner; every later call for the
     * same issued reference (including from another coordinator using this issuer)
     * shares the owner's result — success or store failure — with at most one write.
     */
    internal suspend fun admit(confirmation: ConfirmedPlan): AdmittedConfirmation? =
        gate.withLock {
            val entry = issued.values.firstOrNull { it.confirmation === confirmation } ?: return@withLock null
            if (trustedNow() >= entry.plan.expiresAtMs) return@withLock null
            val existing = entry.claim
            if (existing != null) return@withLock AdmittedConfirmation(entry.plan, existing, owner = false)
            val claim = ExecutionClaim()
            entry.claim = claim
            AdmittedConfirmation(entry.plan, claim, owner = true)
        }

    /** Discard only work which has not been admitted. Never detach an active or completed claim. */
    internal fun discardUnclaimed(planId: String): Boolean {
        if (!gate.tryLock()) return false
        try {
            if (issued[planId]?.claim != null) return false
            prepared.remove(planId)
            issued.remove(planId)
            return true
        } finally { gate.unlock() }
    }

    /** Opportunistic cleanup; no timers, no store writes and no cancellation of executions. */
    internal fun pruneExpired() {
        if (retentionLimit == null || !gate.tryLock()) return
        try {
            val now = clock.nowMs()
            if (now >= 0) pruneExpiredLocked(now) // Broken time must not prevent local revocation.
        } finally { gate.unlock() }
    }

    internal fun retains(planId: String): Boolean {
        if (!gate.tryLock()) return true // A contended issuer is not evidence that an entry is dead.
        try { return planId in prepared } finally { gate.unlock() }
    }

    private fun pruneExpiredLocked(now: Long) {
        if (retentionLimit == null) return
        val retired = prepared.values.filter {
            now >= it.expiresAtMs && issued[it.id]?.claim?.outcome?.isCompleted != false
        }.map { it.id }
        for (id in retired) { prepared.remove(id); issued.remove(id) }
    }

    private fun nextIdentity(): String {
        val value = ids.nextId()
        if (value.isBlank()) throw invalidInputFailure()
        if (retentionLimit == null) return value // Preserve all existing unbounded issuer contracts.
        if (retainedSerial == Long.MAX_VALUE) throw invalidInputFailure()
        retainedSerial++
        return "$value:bounded:$retainedSerial"
    }

    /** A cheap expiry check for the coordinating engine, using the trusted clock. */
    fun isExpired(plan: ChangePlan): Boolean = trustedNow() >= plan.expiresAtMs

    private fun trustedNow(): Long {
        val now = clock.nowMs()
        if (now < 0) throw invalidInputFailure()
        return now
    }

    private fun requireLiveSnapshot(
        scope: CloudScope,
        resourceRef: ResourceRef?,
        credentialRevision: Long,
        requestFingerprint: String,
    ) {
        val live = context.current() ?: throw invalidInputFailure()
        if (live.credentialRevision != credentialRevision) throw invalidInputFailure()
        // The independent current snapshot must still be this scope and this command:
        // plan or requested-scope values never stand in for current state.
        if (live.scope != scope) throw invalidInputFailure()
        if (live.requestFingerprint != requestFingerprint) throw inputChanged()
        if (!context.isLive(scope, credentialRevision)) throw invalidInputFailure()
        // ScopeGuard proves the local connection binding only; the product still owns
        // real zone/resource/subject ownership.
        ScopeGuard.requireOwned(scope, live.connection)
        if (resourceRef != null && resourceRef.scope != scope) throw invalidInputFailure()
    }

    companion object {
        const val TTL_MS: Long = 300_000
    }
}

internal fun inputChanged(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_INPUT, "当前输入或账号范围已变化，请重新确认后再操作")

internal fun requireSha256(value: String?) {
    if (value == null) return
    if (value.length != 64) throw invalidInputFailure()
    if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw invalidInputFailure()
}

internal fun requireSha256Strict(value: String) {
    requireSha256(value)
}

/**
 * The action must be a registered WRITE whose API scope agrees with the runtime
 * scope kind; READ and unknown actions are refused and Organization support is
 * never guessed.
 */
internal fun requireWritableRegistration(actionKey: String, scope: CloudScope): ActionRegistration {
    val registration = ActionPolicies.registry.registration(actionKey) ?: throw invalidInputFailure()
    if (registration.policy.semantics != ActionSemantics.WRITE) throw invalidInputFailure()
    val agrees =
        when (registration.scope) {
            ActionScope.ACCOUNT -> scope is AccountScope
            ActionScope.ZONE -> scope is ZoneScope
            ActionScope.USER -> scope is UserScope
        }
    if (!agrees) throw invalidInputFailure()
    return registration
}
