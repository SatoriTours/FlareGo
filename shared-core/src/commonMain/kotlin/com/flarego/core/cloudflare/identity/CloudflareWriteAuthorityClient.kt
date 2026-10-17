package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** Stable credential owner. Reuse this across short transport/client recreation; never create it per send. */
@OptIn(ExperimentalAtomicApi::class)
class TokenAuthorityIssuer(
    private val connection: Connection,
    private val credentialRevision: Long,
    private val currentCredentialRevision: () -> Long,
    private val clock: OperationClock,
    private val kindHint: TokenKind = TokenKind.UNKNOWN,
    private val cacheTtlMs: Long = 30_000,
) {
    init { if (connection.demo || credentialRevision < 0 || cacheTtlMs !in 1..30_000) throw invalidInputFailure() }
    private data class Principal(val kind: TokenKind, val tokenId: String)
    private data class Candidate(val principal: Principal, val token: AuthorityToken)
    private data class Lease(val observation: WriteAuthorityObservation, val generation: Long)
    private data class State(
        val generation: Long = 0, val highWater: Long = -1, val retired: Boolean = false,
        val principal: Principal? = null, val catalog: AuthorityCatalog? = null,
        val tombstone: WriteAuthorityReason? = null, val lease: Lease? = null,
        val proofs: Map<WriteAuthorityProof, Lease> = emptyMap(),
    )
    private val state = AtomicReference(State())
    private val refreshMutex = Mutex()
    internal fun sessionGeneration(): Long = state.load().generation
    private fun update(transform: (State) -> State) {
        while (true) { val before = state.load(); if (state.compareAndSet(before, transform(before))) return }
    }
    fun invalidate(connectionId: String) {
        if (connectionId != connection.id) return
        update { State(generation = it.generation + 1, highWater = it.highWater, retired = it.retired) }
    }
    private fun revoke(generation: Long, tombstone: WriteAuthorityReason? = null, retire: Boolean = false) {
        update { if (it.generation != generation) it else it.copy(lease = null, proofs = emptyMap(),
            tombstone = it.tombstone ?: tombstone, retired = it.retired || retire) }
    }
    private fun fail(reason: WriteAuthorityReason): Nothing = throw AuthorityDecodeFailure(reason)
    /** Synchronous and safe to call again after every suspension and in a final beforeSend guard. */
    private fun check(generation: Long): Long {
        val before = state.load()
        if (before.generation != generation) fail(WriteAuthorityReason.SESSION_INVALIDATED)
        if (before.retired || runCatching(currentCredentialRevision).getOrNull() != credentialRevision) {
            revoke(generation, retire = true); fail(WriteAuthorityReason.CREDENTIAL_CHANGED)
        }
        before.tombstone?.let { fail(it) }
        val now = runCatching { clock.nowMs() }.getOrElse { fail(WriteAuthorityReason.CLOCK_ROLLBACK) }
        if (now < 0 || now < before.highWater) { revoke(generation); fail(WriteAuthorityReason.CLOCK_ROLLBACK) }
        update { if (it.generation == generation && now > it.highWater) it.copy(highWater = now) else it }
        if (state.load().generation != generation) fail(WriteAuthorityReason.SESSION_INVALIDATED)
        return now
    }
    private fun observation(scope: ZoneScope, operationId: String, reason: WriteAuthorityReason,
                            decision: WriteAuthorityDecision = WriteAuthorityDecision.UNKNOWN) =
        WriteAuthorityObservation(decision, reason, scope, operationId, credentialRevision,
            runCatching { clock.nowMs() }.getOrDefault(0))

    internal suspend fun observe(gateway: CloudflareGateway, scope: ZoneScope, operationId: String,
                                 forceRefresh: Boolean, generation: Long): WriteAuthorityObservation = refreshMutex.withLock {
        ScopeGuard.requireOwned(scope, connection)
        if (operationId.isBlank() || operationId.length > 160 || operationId.any { it.code < 32 }) throw invalidInputFailure()
        if (operationId !in TokenPolicyEvaluation.acceptedGroups) return@withLock observation(scope, operationId, WriteAuthorityReason.UNSUPPORTED_OPERATION)
        try {
            val started = check(generation)
            state.load().lease?.let { cached ->
                if (!forceRefresh && cached.generation == generation && cached.observation.scope == scope &&
                    cached.observation.operationId == operationId && cached.observation.validUntilMs!! > started)
                    return@withLock cached.observation
            }
            // Revoke old proofs before a refresh suspends. Binding/tombstone survive transport recreation/failure.
            revoke(generation)
            val candidate = if (kindHint == TokenKind.UNKNOWN) {
                val candidates = mutableListOf<Candidate>(); var verifiedBranches = 0; var reason = WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE
                for (kind in listOf(TokenKind.USER_OWNED, TokenKind.ACCOUNT_OWNED)) {
                    try { candidates += candidate(gateway, kind, generation) { verifiedBranches++ } }
                    catch (failure: AuthorityDecodeFailure) {
                        if (failure.reason in setOf(WriteAuthorityReason.SESSION_INVALIDATED, WriteAuthorityReason.CREDENTIAL_CHANGED,
                                WriteAuthorityReason.CLOCK_ROLLBACK, WriteAuthorityReason.CATALOG_CHANGED, WriteAuthorityReason.TOKEN_ID_CHANGED)) throw failure
                        if (reason == WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE) reason = failure.reason
                    }
                }
                if (verifiedBranches > 1 || candidates.size > 1) fail(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND)
                candidates.singleOrNull() ?: fail(reason)
            } else candidate(gateway, kindHint, generation)
            check(generation)
            freezePrincipal(generation, candidate.principal)
            val base = tokenBase(candidate.principal.kind)
            val groupKey = if (candidate.principal.kind == TokenKind.USER_OWNED) "permission-groups-list-permission-groups" else "account-api-tokens-list-permission-groups"
            val catalog = TokenPolicyEvaluation.catalog(read(gateway, groupKey, "$base/permission_groups", generation))
            check(generation)
            freezeCatalog(generation, catalog)
            val evaluated = TokenPolicyEvaluation.evaluate(candidate.token, catalog, scope, operationId, check(generation))
            if (evaluated.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED)
                return@withLock observation(scope, operationId, evaluated.reason, evaluated.decision)
            // Independent supplier evidence; local ScopeGuard is never owner or permission proof.
            CloudflareZoneClient(connection, gateway).get(scope)
            val now = check(generation)
            val ttlEnd = if (started > Long.MAX_VALUE - cacheTtlMs) Long.MAX_VALUE else started + cacheTtlMs
            val expires = minOf(ttlEnd, candidate.token.identity.expires ?: Long.MAX_VALUE)
            if (now >= expires) fail(WriteAuthorityReason.TIME_NOT_VALID)
            val result = WriteAuthorityObservation(WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
                WriteAuthorityReason.TOKEN_POLICY_GRANTED, scope, operationId, credentialRevision, started, expires)
            val lease = Lease(result, generation)
            update { if (it.generation == generation && !it.retired && it.tombstone == null)
                it.copy(lease = lease, proofs = emptyMap()) else it }
            check(generation)
            if (state.load().lease !== lease) fail(WriteAuthorityReason.SESSION_INVALIDATED)
            result
        } catch (cancelled: CancellationException) {
            revoke(generation); throw cancelled
        } catch (failure: AuthorityDecodeFailure) {
            revoke(generation)
            observation(scope, operationId, failure.reason)
        } catch (failure: CloudFailure) {
            revoke(generation)
            observation(scope, operationId, if (failure.code == CloudFailureCode.SCOPE_MISMATCH) WriteAuthorityReason.SCOPE_MISMATCH else WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE)
        } catch (_: Exception) {
            revoke(generation); observation(scope, operationId, WriteAuthorityReason.INVALID_RESPONSE)
        }
    }
    private fun tokenBase(kind: TokenKind): String = when (kind) {
        TokenKind.USER_OWNED -> "/user/tokens"
        TokenKind.ACCOUNT_OWNED -> "/accounts/${encodePath(connection.accountId)}/tokens"
        TokenKind.UNKNOWN -> fail(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND)
    }
    private suspend fun candidate(gateway: CloudflareGateway, kind: TokenKind, generation: Long, onVerified: () -> Unit = {}): Candidate {
        val base = tokenBase(kind)
        val prefix = if (kind == TokenKind.USER_OWNED) "user-api-tokens" else "account-api-tokens"
        val verified = TokenPolicyEvaluation.verify(result(read(gateway, "$prefix-verify-token", "$base/verify", generation)))
        onVerified()
        val token = TokenPolicyEvaluation.token(result(read(gateway, "$prefix-token-details", "$base/${encodePath(verified.id)}", generation)), verified)
        return Candidate(Principal(kind, verified.id), token)
    }
    private fun result(root: JsonObject) = root["result"] as? JsonObject ?: fail(WriteAuthorityReason.INVALID_RESPONSE)
    private suspend fun read(gateway: CloudflareGateway, key: String, path: String, generation: Long): JsonObject {
        val response = gateway.send(key, HttpRequest("GET", path))
        check(generation)
        if (response.status != 200) fail(WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE)
        return TokenPolicyEvaluation.envelope(response.body)
    }
    private fun freezePrincipal(generation: Long, principal: Principal) {
        check(generation)
        val old = state.load().principal
        if (old != null && old != principal) { revoke(generation, WriteAuthorityReason.TOKEN_ID_CHANGED); fail(WriteAuthorityReason.TOKEN_ID_CHANGED) }
        update { if (it.generation == generation) it.copy(principal = principal) else it }
        check(generation)
    }
    private fun freezeCatalog(generation: Long, catalog: AuthorityCatalog) {
        check(generation)
        val old = state.load().catalog
        if (old != null && old.groups != catalog.groups) { revoke(generation, WriteAuthorityReason.CATALOG_CHANGED); fail(WriteAuthorityReason.CATALOG_CHANGED) }
        if (catalog.unsupportedScopes || catalog.groups.values.any { TokenPolicyEvaluation.ZONE_SCOPE !in it.scopes })
            fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
        update { if (it.generation == generation) it.copy(catalog = catalog) else it }
        check(generation)
    }
    internal fun proofFor(observation: WriteAuthorityObservation): WriteAuthorityProof {
        val lease = state.load().lease ?: throw unavailable()
        if (lease.observation !== observation) throw unavailable()
        return try {
            validate(lease, observation.scope, observation.operationId)
            val proof = WriteAuthorityProof()
            update { if (it.lease === lease && it.generation == lease.generation)
                it.copy(proofs = (it.proofs + (proof to lease)).entries.toList().takeLast(16).associate { pair -> pair.key to pair.value }) else it }
            if (state.load().proofs[proof] !== lease) throw unavailable()
            proof
        } catch (_: AuthorityDecodeFailure) { throw unavailable() }
    }
    internal fun requireCurrent(proof: WriteAuthorityProof, scope: ZoneScope, operationId: String) {
        val lease = state.load().proofs[proof] ?: throw unavailable()
        try { validate(lease, scope, operationId) }
        catch (_: AuthorityDecodeFailure) { throw unavailable() }
    }
    private fun validate(lease: Lease, scope: ZoneScope, operationId: String) {
        val now = check(lease.generation); val current = state.load()
        if (current.lease !== lease || lease.observation.scope != scope || lease.observation.operationId != operationId ||
            lease.observation.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED || now >= lease.observation.validUntilMs!!)
            throw unavailable()
    }
    private fun unavailable() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "当前 Token 策略证明已失效或不匹配，当前只读")
}

/** Short transport adapter: the stable issuer is mandatory and its generation is captured at construction. */
class CloudflareWriteAuthorityClient(private val issuer: TokenAuthorityIssuer,
                                    private val gateway: CloudflareGateway) : WriteAuthorityPort {
    private val generation = issuer.sessionGeneration()
    override suspend fun observe(scope: ZoneScope, operationId: String, forceRefresh: Boolean) =
        issuer.observe(gateway, scope, operationId, forceRefresh, generation)
    override fun invalidate(connectionId: String) = issuer.invalidate(connectionId)
}
