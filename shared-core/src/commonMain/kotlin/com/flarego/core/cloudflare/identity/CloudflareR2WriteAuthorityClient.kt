package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.r2.validateR2BucketName
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.TokenKind
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Stable Application credential owner, separate from short transport or UI lifetimes. */
@OptIn(ExperimentalAtomicApi::class)
class R2TokenAuthorityIssuer(
    private val connection: Connection,
    private val revision: Long,
    private val currentRevision: () -> Long,
    private val clock: OperationClock,
    private val kindHint: TokenKind = TokenKind.UNKNOWN,
    private val ttlMs: Long = 30_000,
) {
    init { if (connection.demo || revision < 0 || ttlMs !in 1..30_000) throw invalidInputFailure() }
    private data class Principal(val kind: TokenKind, val id: String)
    private data class Candidate(val principal: Principal, val token: AuthorityToken)
    private class UnavailableKind : Exception("Token kind endpoint refused authentication")
    private class Lease(val observation: R2WriteObservation, val generation: Long)
    private data class State(
        val generation: Long = 0,
        val highWater: Long = -1,
        val principal: Principal? = null,
        val catalog: AuthorityCatalog? = null,
        val tombstone: WriteAuthorityReason? = null,
        val lease: Lease? = null,
        val proofs: Map<R2WriteProof, Lease> = emptyMap(),
    )
    private val state = AtomicReference(State())
    private val refresh = Mutex()
    internal fun generation() = state.load().generation
    private fun update(f: (State) -> State) { while (true) { val old = state.load(); if (state.compareAndSet(old, f(old))) return } }
    fun invalidate(connectionId: String) {
        if (connectionId == connection.id) update { State(generation = it.generation + 1, highWater = it.highWater) }
    }
    private fun revoke(generation: Long, permanent: WriteAuthorityReason? = null) {
        update { if (it.generation == generation) it.copy(lease = null, proofs = emptyMap(), tombstone = it.tombstone ?: permanent) else it }
    }
    private fun fail(reason: WriteAuthorityReason): Nothing = throw AuthorityDecodeFailure(reason)
    private fun check(generation: Long): Long {
        val old = state.load()
        if (old.generation != generation) fail(WriteAuthorityReason.SESSION_INVALIDATED)
        old.tombstone?.let { fail(it) }
        if (runCatching(currentRevision).getOrNull() != revision) {
            revoke(generation, WriteAuthorityReason.CREDENTIAL_CHANGED); fail(WriteAuthorityReason.CREDENTIAL_CHANGED)
        }
        val now = runCatching { clock.nowMs() }.getOrElse { fail(WriteAuthorityReason.CLOCK_ROLLBACK) }
        if (now < 0 || now < old.highWater) { revoke(generation); fail(WriteAuthorityReason.CLOCK_ROLLBACK) }
        update { if (it.generation == generation && now > it.highWater) it.copy(highWater = now) else it }
        if (state.load().generation != generation) fail(WriteAuthorityReason.SESSION_INVALIDATED)
        return now
    }
    private fun observation(target: R2WriteTarget, operation: String, reason: WriteAuthorityReason,
                            decision: WriteAuthorityDecision = WriteAuthorityDecision.UNKNOWN) =
        R2WriteObservation(target, operation, decision, reason, revision, runCatching { clock.nowMs() }.getOrDefault(0))

    internal suspend fun observe(gateway: CloudflareGateway, target: R2WriteTarget, operation: String,
                                 force: Boolean, generation: Long): R2WriteObservation = refresh.withLock {
        ScopeGuard.requireOwned(target.account, connection)
        validateR2BucketName(target.bucket)
        // The jurisdiction itself must be one of the five pinned values: anything else is
        // refused here, before a provider read or a proof, not by the last wire client.
        // A non-default jurisdiction is admitted only for the reviewed configuration writes
        // whose endpoint declares the controlled header; everything else stays default-only.
        if (target.jurisdiction !in R2_JURISDICTIONS ||
            operation !in R2TokenPolicyEvaluation.operations ||
            (target.jurisdiction != "default" && operation !in R2TokenPolicyEvaluation.jurisdictionOperations)
        ) {
            return@withLock observation(target, operation, WriteAuthorityReason.UNSUPPORTED_OPERATION)
        }
        try {
            val started = check(generation)
            state.load().lease?.let { lease ->
                if (!force && lease.generation == generation && lease.observation.target == target &&
                    lease.observation.operationId == operation && started < lease.observation.validUntilMs!!)
                    return@withLock lease.observation
            }
            // Refresh failures must never leave a previously granted capability alive.
            revoke(generation)
            val candidate = if (kindHint == TokenKind.UNKNOWN) {
                var verified = 0
                val found = mutableListOf<Candidate>()
                for (kind in listOf(TokenKind.USER_OWNED, TokenKind.ACCOUNT_OWNED)) {
                    try { found += candidate(gateway, kind, generation) { verified++ } }
                    // Only a definite authentication refusal eliminates a kind. A transport,
                    // 5xx, malformed response or uninspectable verified principal is UNKNOWN,
                    // never affirmative evidence for whichever branch happened to respond.
                    catch (_: UnavailableKind) { }
                }
                if (verified > 1 || found.size > 1) fail(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND)
                found.singleOrNull() ?: fail(WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE)
            } else candidate(gateway, kindHint, generation) {}
            check(generation)
            state.load().principal?.let { prior ->
                if (prior != candidate.principal) {
                    revoke(generation, WriteAuthorityReason.TOKEN_ID_CHANGED); fail(WriteAuthorityReason.TOKEN_ID_CHANGED)
                }
            }
            update { if (it.generation == generation) it.copy(principal = candidate.principal) else it }
            val base = base(candidate.principal.kind)
            val prefix = if (candidate.principal.kind == TokenKind.USER_OWNED) "permission-groups-list-permission-groups" else "account-api-tokens-list-permission-groups"
            val catalog = TokenPolicyEvaluation.catalog(read(gateway, prefix, "$base/permission_groups", generation), R2TokenPolicyEvaluation.labels)
            if (catalog.unsupportedScopes) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            state.load().catalog?.let { prior ->
                if (prior.groups != catalog.groups) {
                    revoke(generation, WriteAuthorityReason.CATALOG_CHANGED); fail(WriteAuthorityReason.CATALOG_CHANGED)
                }
            }
            update { if (it.generation == generation) it.copy(catalog = catalog) else it }
            val evaluation = R2TokenPolicyEvaluation.evaluate(candidate.token, catalog, target, operation, check(generation))
            if (evaluation.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED)
                return@withLock observation(target, operation, evaluation.reason, evaluation.decision)
            // Local connection ownership is not provider evidence. Bind to a fresh account GET.
            val owner = result(read(gateway, "accounts-account-details", "/accounts/${encodePath(target.account.accountId)}", generation))
            val ownerId = (owner["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (ownerId != target.account.accountId) fail(WriteAuthorityReason.SCOPE_MISMATCH)
            val now = check(generation)
            val end = minOf(if (started > Long.MAX_VALUE - ttlMs) Long.MAX_VALUE else started + ttlMs,
                candidate.token.identity.expires ?: Long.MAX_VALUE)
            if (now >= end) fail(WriteAuthorityReason.TIME_NOT_VALID)
            val observed = R2WriteObservation(target, operation, WriteAuthorityDecision.TOKEN_POLICY_GRANTED,
                WriteAuthorityReason.TOKEN_POLICY_GRANTED, revision, started, end)
            val lease = Lease(observed, generation)
            update { if (it.generation == generation && it.tombstone == null) it.copy(lease = lease, proofs = emptyMap()) else it }
            check(generation)
            if (state.load().lease !== lease) fail(WriteAuthorityReason.SESSION_INVALIDATED)
            observed
        } catch (cancel: CancellationException) { revoke(generation); throw cancel }
        catch (failure: AuthorityDecodeFailure) { revoke(generation); observation(target, operation, failure.reason) }
        catch (_: Exception) { revoke(generation); observation(target, operation, WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE) }
    }
    private fun base(kind: TokenKind): String = when (kind) {
        TokenKind.USER_OWNED -> "/user/tokens"
        TokenKind.ACCOUNT_OWNED -> "/accounts/${encodePath(connection.accountId)}/tokens"
        TokenKind.UNKNOWN -> fail(WriteAuthorityReason.AMBIGUOUS_TOKEN_KIND)
    }
    private suspend fun candidate(gateway: CloudflareGateway, kind: TokenKind, generation: Long, verified: () -> Unit): Candidate {
        val base = base(kind)
        val prefix = if (kind == TokenKind.USER_OWNED) "user-api-tokens" else "account-api-tokens"
        val identity = TokenPolicyEvaluation.verify(result(read(gateway, "$prefix-verify-token", "$base/verify", generation, probeKind = true)))
        verified()
        val token = TokenPolicyEvaluation.token(result(read(gateway, "$prefix-token-details", "$base/${encodePath(identity.id)}", generation)),
            identity, R2TokenPolicyEvaluation::resources)
        return Candidate(Principal(kind, identity.id), token)
    }
    private suspend fun read(gateway: CloudflareGateway, operation: String, path: String, generation: Long, probeKind: Boolean = false): JsonObject {
        val response = gateway.send(operation, HttpRequest("GET", path))
        check(generation)
        if (probeKind && response.status in setOf(401, 403, 404)) throw UnavailableKind()
        if (response.status != 200) fail(WriteAuthorityReason.SELF_INSPECTION_UNAVAILABLE)
        return TokenPolicyEvaluation.envelope(response.body)
    }
    private fun result(root: JsonObject) = root["result"] as? JsonObject ?: fail(WriteAuthorityReason.INVALID_RESPONSE)
    internal fun proofFor(observed: R2WriteObservation): R2WriteProof {
        val lease = state.load().lease ?: throw unavailable()
        if (lease.observation !== observed) throw unavailable()
        validate(lease, observed.target, observed.operationId)
        val proof = R2WriteProof()
        update { if (it.lease === lease) it.copy(proofs = (it.proofs + (proof to lease)).entries.toList().takeLast(16).associate { row -> row.key to row.value }) else it }
        if (state.load().proofs[proof] !== lease) throw unavailable()
        return proof
    }
    internal fun requireCurrent(proof: R2WriteProof, target: R2WriteTarget, operation: String) {
        val lease = state.load().proofs[proof] ?: throw unavailable()
        validate(lease, target, operation)
    }
    private fun validate(lease: Lease, target: R2WriteTarget, operation: String) {
        try {
            val now = check(lease.generation)
            if (state.load().lease !== lease || lease.observation.target != target || lease.observation.operationId != operation ||
                lease.observation.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED || now >= lease.observation.validUntilMs!!)
                throw unavailable()
        } catch (_: AuthorityDecodeFailure) { throw unavailable() }
    }
    private fun unavailable() = CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "R2 Token 策略证明缺失、已过期或不匹配；未发送写入")
}

class CloudflareR2WriteAuthorityClient(private val issuer: R2TokenAuthorityIssuer, private val gateway: CloudflareGateway) {
    private val generation = issuer.generation()
    suspend fun observe(target: R2WriteTarget, operationId: String, forceRefresh: Boolean = false) =
        issuer.observe(gateway, target, operationId, forceRefresh, generation)
}
