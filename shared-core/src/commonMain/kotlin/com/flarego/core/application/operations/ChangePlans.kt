package com.flarego.core.application.operations

import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionRegistration
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val gate = Mutex()
    private val prepared = mutableMapOf<String, ChangePlan>()
    private val issued = linkedMapOf<String, Issued>()

    private class Issued(val plan: ChangePlan, val confirmation: ConfirmedPlan) {
        var consumed: Boolean = false
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
            requireLiveSnapshot(scope, resourceRef, credentialRevision)
            if (nowMs < 0 || credentialRevision < 0) throw invalidInputFailure()
            requireSha256(beforeHash)
            requireSha256(desiredHash)
            requireSha256(requestFingerprint)
            val trusted = trustedNow()
            // A caller time may only shorten the window; the trusted clock caps it.
            val base = minOf(nowMs, trusted)
            if (base > Long.MAX_VALUE - TTL_MS) throw invalidInputFailure()
            val id = ids.nextId()
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
            requireLiveSnapshot(plan.scope, plan.resourceRef, plan.credentialRevision)
            val existing = issued[planId]
            if (existing != null) return existing.confirmation
            val confirmationId = ids.nextId()
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
     * Atomically consumes exactly one issued confirmation. Returns null for an
     * unknown, foreign-issuer or already consumed confirmation, and null when the
     * trusted clock has passed the expiry.
     */
    suspend fun consume(confirmation: ConfirmedPlan): ChangePlan? =
        gate.withLock {
            val entry = issued.values.firstOrNull { it.confirmation === confirmation }
            when {
                entry == null -> null
                entry.consumed -> null
                trustedNow() >= entry.plan.expiresAtMs -> null
                else -> {
                    entry.consumed = true
                    entry.plan
                }
            }
        }

    /** A cheap expiry check for the coordinating engine, using the trusted clock. */
    fun isExpired(plan: ChangePlan): Boolean = trustedNow() >= plan.expiresAtMs

    private fun trustedNow(): Long {
        val now = clock.nowMs()
        if (now < 0) throw invalidInputFailure()
        return now
    }

    private fun requireLiveSnapshot(scope: CloudScope, resourceRef: ResourceRef?, credentialRevision: Long) {
        val live = context.current() ?: throw invalidInputFailure()
        if (live.credentialRevision != credentialRevision) throw invalidInputFailure()
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
