package com.flarego.core.cloudflare.identity

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.CapabilityPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small explicit read-probe allowlist, with an independent expiry per scope/action.
 * One instance belongs to one credential; replacing credentials requires a new client.
 * A successful read proves only that action's read availability, never write permissions.
 */
class CloudflareCapabilityClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val nowMs: () -> Long,
    private val cacheTtlMs: Long = 60_000,
) : CapabilityPort {
    init { if (cacheTtlMs <= 0) throw invalidInputFailure() }
    private val mutex = Mutex()
    private val cache = mutableMapOf<Pair<CloudScope, String>, CapabilityStatus>()

    override suspend fun status(scope: CloudScope, actionKey: String): CapabilityStatus = mutex.withLock {
        ScopeGuard.requireOwned(scope, connection)
        val registered = gateway.policyOf(actionKey)
        val unknown = CapabilityStatus(actionKey, scope,
            implementation = if (registered == null) ImplementationLevel.NONE else ImplementationLevel.REGISTERED)
        if (registered?.semantics != ActionSemantics.READ || scope !is AccountScope) return@withLock unknown
        val account = "/accounts/${encodePath(scope.accountId)}"
        val path = when (actionKey) {
            "accounts-account-details" -> account
            "listWorkers" -> "$account/workers/workers?page=1&per_page=1"
            "account-billing-history-get-billing-history" -> "$account/billing/history?page=1&per_page=1"
            else -> return@withLock unknown
        }
        val now = nowMs()
        val key = scope to actionKey
        cache[key]?.let { saved ->
            val observed = saved.observedAtMs
            if (observed != null && now >= observed && now - observed in 0 until cacheTtlMs) return@withLock saved
        }
        val observation = try {
            readEnvelope(gateway, actionKey, path)
            unknown.copy(availability = CapabilityAvailability.AVAILABLE, observedAtMs = now)
        } catch (failure: CloudFailure) {
            val availability = when (failure.code) {
                CloudFailureCode.PERMISSION_REQUIRED -> CapabilityAvailability.PERMISSION_REQUIRED
                CloudFailureCode.CREDENTIAL_EXPIRED -> CapabilityAvailability.CREDENTIAL_REQUIRED
                CloudFailureCode.NOT_FOUND -> CapabilityAvailability.UNKNOWN
                else -> CapabilityAvailability.TEMPORARILY_UNAVAILABLE
            }
            unknown.copy(availability = availability, reason = failure.publicMessage, observedAtMs = now)
        }
        cache[key] = observation
        observation
    }
}
