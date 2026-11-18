package com.flarego.platform

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CredentialKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Outcome of one credential write.
 *
 * A thrown write is **not** proof that nothing was stored: SharedPreferences updates its in-memory
 * map before `commit()` returns false, so the stored value may already be the new one. Every
 * throwing path is therefore compensated and, if that also fails, reported as [TokenWrite.Uncertain]
 * instead of being treated as "unchanged".
 */
sealed interface TokenWrite {
    /** The value is stored; [rollback] can restore the previous value (or its absence). */
    class Written(val rollback: CredentialVault.TokenRollback) : TokenWrite

    /** Validation rejected the value, or a throwing write was compensated back to the old value. */
    class Unchanged(val cause: Exception) : TokenWrite

    /** The write threw and compensating it also failed (or a newer write won): stored state is unknown. */
    class Uncertain(val cause: Exception) : TokenWrite
}

/**
 * Encrypted credential storage for API Tokens.
 *
 * Store callbacks must be backed by [TokenVault] in production (Android Keystore + AES/GCM); this
 * class owns only the value semantics:
 *
 * 1. A stored Token is returned verbatim and is never logged.
 * 2. A legacy OAuth record from an older install is recognized by [LEGACY_OAUTH_PREFIX] and is
 *    **never** returned as a Token: the app was migrated to Token-only auth, so such a connection
 *    keeps its name and local record but must be reconfigured.
 * 3. A legacy payload cannot be stored as a plain Token either. A write that throws may still have
 *    taken effect, so it is compensated instead of being assumed untouched.
 */
class CredentialVault(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val delete: (String) -> Unit,
    /**
     * Consulted under the per-id lock right before a credential is read. The commit gate lives in
     * the app database, so the authoritative check happens here (request time), not only in the UI
     * or in a cached capability set.
     */
    private val blocked: suspend (String) -> Boolean = { false },
    /**
     * Upper bound for waiting on the per-id lock during rollback. The synchronous disk write itself
     * is not bounded by this value; this only prevents NonCancellable compensation from waiting
     * forever behind another reader/writer.
     */
    private val lockTimeoutMs: Long = 2_000,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    /**
     * Monotonic per-id write sequence. It lets a rollback refuse to resurrect a value that was
     * already superseded by a newer write, so a late compensation can never undo a newer grant.
     */
    private val sequences = ConcurrentHashMap<String, Long>()
    private fun bump(id: String): Long = sequences.merge(id, 1L) { old, step -> old + step }!!

    /**
     * Opaque rollback handle for one credential id.
     *
     * It keeps the previous stored value in memory only, never in a log, a saved state or a plain
     * snapshot, and [toString] never shows it.
     */
    class TokenRollback internal constructor(
        internal val id: String,
        internal val previous: String?,
        internal val sequence: Long,
    ) {
        override fun toString() = "TokenRollback($id, [redacted])"
    }

    /**
     * Outcome of one credential write.
     *
     * A thrown write is **not** proof that nothing was stored: SharedPreferences updates its
     * in-memory map before `commit()` returns false, so the stored value may already be the new
     * one. Every throwing path is therefore compensated and, if that also fails, reported as
     * [Uncertain] instead of being treated as "unchanged".
     */
    /**
     * Writes a Token, compensating inside the per-id lock when the write throws.
     *
     * Snapshot, write and compensation happen under the same lock, so the handle describes
     * exactly the value this call replaced (or "absent" for a new credential).
     */
    suspend fun replaceTokenOrRestore(id: String, token: String): TokenWrite = lock(id).withLock {
        val clean = try {
            validate(token)
        } catch (failure: Exception) {
            return@withLock TokenWrite.Unchanged(failure)
        }
        val previous = read(id)
        try {
            write(id, clean)
            TokenWrite.Written(TokenRollback(id, previous, bump(id)))
        } catch (cancel: CancellationException) {
            compensateAfterThrowingWrite(id, previous, cancel)
        } catch (failure: Exception) {
            compensateAfterThrowingWrite(id, previous, failure)
        }
    }

    /** One bounded write; a failure here is the only honest "unknown" state. */
    private fun compensateAfterThrowingWrite(id: String, previous: String?, cause: Exception): TokenWrite =
        try {
            if (previous == null) delete(id) else write(id, previous)
            bump(id)
            TokenWrite.Unchanged(cause)
        } catch (_: Exception) {
            TokenWrite.Uncertain(cause)
        }

    /**
     * Restores one captured value after a failed commit.
     *
     * Refuses with CONFLICT when a newer write already superseded the handle: resurrecting an old
     * credential would silently undo a newer grant.
     */
    suspend fun restore(handle: TokenRollback) {
        // Only the lock wait is bounded; a timeout never starts a detached background write.
        val done = withTimeoutOrNull(lockTimeoutMs) {
            lock(handle.id).withLock {
                if ((sequences[handle.id] ?: 0L) != handle.sequence) throw CloudFailure(
                    CloudFailureCode.CONFLICT,
                    "凭据已被更新的操作替换，未执行回滚；请重新连接以确认当前凭据",
                )
                val previous = handle.previous
                if (previous == null) delete(handle.id) else write(handle.id, previous)
                bump(handle.id)
            }
            true
        }
        if (done != true) throw CloudFailure(
            CloudFailureCode.UNAVAILABLE,
            "回滚等待连接锁超时：凭据状态未确认，门禁保持阻塞，请重新连接确认",
        )
    }

    /** Which credential kind is stored, without returning the secret. */
    fun kind(id: String): CredentialKind {
        val raw = read(id) ?: return CredentialKind.ABSENT
        return if (raw.startsWith(LEGACY_OAUTH_PREFIX)) CredentialKind.OAUTH else CredentialKind.TOKEN
    }

    /** The stored API Token, or null when nothing is stored. */
    suspend fun token(id: String): String? = lock(id).withLock {
        // Gate first, credential second, both under the same lock: no check-then-read window.
        if (blocked(id)) throw gatedFailure()
        val raw = read(id) ?: return@withLock null
        if (raw.startsWith(LEGACY_OAUTH_PREFIX)) throw legacyOAuthRetired()
        raw
    }

    suspend fun putToken(id: String, token: String) {
        when (val write = replaceTokenOrRestore(id, token)) {
            is TokenWrite.Written -> Unit
            is TokenWrite.Unchanged -> throw write.cause
            is TokenWrite.Uncertain -> throw write.cause
        }
    }

    private fun validate(token: String): String {
        val clean = token.trim()
        if (clean.isEmpty() || clean.length !in MIN_TOKEN..MAX_TOKEN || clean.any(Char::isWhitespace)) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "Token 不能为空，且不能包含空格或换行；请粘贴完整 Token")
        }
        // An OAuth payload is not a Token; refuse it instead of sending something the API rejects.
        if (clean.startsWith(LEGACY_OAUTH_PREFIX)) throw CloudFailure(
            CloudFailureCode.INVALID_INPUT,
            "这是旧版 OAuth 授权的保存内容，不是 API Token；请在 Cloudflare 控制台创建 API Token 后粘贴",
        )
        return clean
    }

    suspend fun remove(id: String) = lock(id).withLock { delete(id); bump(id) }

    /** Fail closed while a commit is in flight or its result is unknown. */
    private fun gatedFailure() = CloudFailure(
        CloudFailureCode.CREDENTIAL_EXPIRED,
        "该账号上次凭据提交尚未确认（或正在进行）。请重新连接并确认 API Token 后再使用。",
    )

    private fun legacyOAuthRetired() = CloudFailure(
        CloudFailureCode.CREDENTIAL_EXPIRED,
        "该连接使用的是已停用的旧版 OAuth 授权；请为这个账号配置 API Token 后重新连接。原有账号名称与本地记录保留。",
    )

    companion object {
        /** Marker of the retired OAuth record format; also used to recognize legacy installs. */
        const val LEGACY_OAUTH_PREFIX: String = "flarego-oauth-v1:"
        private const val MIN_TOKEN = 10
        private const val MAX_TOKEN = 4096
    }
}
