package com.flarego.core.application.connection

import java.util.UUID

/** Gate state for one connection. Only rows that exist are meaningful; absence means "no gate". */
enum class GateState { PENDING, BLOCKED }

/** The newest gate row for a connection. Contains no credential material. */
data class GateRecord(
    val operationId: String,
    val connectionId: String,
    val accountId: String,
    val state: GateState,
)

/**
 * Durable storage for the credential commit gate.
 *
 * Implementations must use a real transactional store (the app's SQLite database) and must
 * confirm the write from the store itself; reading back from a memory-first cache is not proof.
 * Every method returns/throws so the caller can distinguish "confirmed" from "unknown".
 */
interface CredentialGateStore {
    /** Inserts a new PENDING row for [operationId] and confirms it is the newest row. */
    suspend fun arm(connectionId: String, accountId: String, operationId: String, nowMs: Long): Boolean

    /** Deletes the row only when this exact operation still owns it, then confirms it is gone. */
    suspend fun release(connectionId: String, operationId: String): Boolean

    /** Marks this exact operation BLOCKED and confirms the row now says BLOCKED. */
    suspend fun block(connectionId: String, operationId: String, nowMs: Long): Boolean

    /** The newest gate row, or null when the connection has none. Throws when unreadable. */
    suspend fun latest(connectionId: String): GateRecord?

    /** Removes every gate row of a connection (explicit disconnect only). */
    suspend fun clear(connectionId: String)
}

/** One armed attempt. Carries no secret; [operationId] is the ownership token. */
data class GateAttempt(val connectionId: String, val accountId: String, val operationId: String)

/** Result of trying to release the gate after a commit finished. */
enum class GateRelease {
    /** The row was deleted by this exact operation and the store confirmed it. */
    RELEASED,

    /** Not confirmed (store threw, or the row belongs to a newer operation). Caller keeps blocked. */
    NOT_RELEASED,
}

/**
 * Minimal coordinator around [CredentialGateStore].
 *
 * Rules it enforces:
 * 1. A new attempt always arms first; if arming is not confirmed the caller must not touch the
 *    credential at all.
 * 2. Only the owning operation can release or block its own row; a newer operation's row is never
 *    overwritten by a stale one.
 * 3. Release is accepted only when the store confirms deletion, so a memory-first cache can never
 *    be mistaken for durability.
 * 4. Every read failure is reported to the caller (which must fail closed), never swallowed.
 */
class CredentialGateCoordinator(
    private val store: CredentialGateStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Arms a fresh attempt, or returns null when the gate could not be durably established. */
    suspend fun arm(connectionId: String, accountId: String): GateAttempt? {
        val operationId = newOperationId()
        val confirmed = try {
            store.arm(connectionId, accountId, operationId, nowMs())
        } catch (_: Exception) {
            false
        }
        return if (confirmed) GateAttempt(connectionId, accountId, operationId) else null
    }

    /** Releases the gate for this attempt only when the store confirms it. */
    suspend fun release(attempt: GateAttempt): GateRelease {
        val confirmed = try {
            store.release(attempt.connectionId, attempt.operationId)
        } catch (_: Exception) {
            false
        }
        return if (confirmed) GateRelease.RELEASED else GateRelease.NOT_RELEASED
    }

    /** Best-effort downgrade to BLOCKED; false means the durable state stays unknown. */
    suspend fun block(attempt: GateAttempt): Boolean =
        try {
            store.block(attempt.connectionId, attempt.operationId, nowMs())
        } catch (_: Exception) {
            false
        }

    /** True when a gate exists or the store cannot be read: both must fail closed. */
    suspend fun isBlocked(connectionId: String): Boolean =
        try {
            store.latest(connectionId) != null
        } catch (_: Exception) {
            true
        }

    suspend fun clear(connectionId: String) {
        try {
            store.clear(connectionId)
        } catch (_: Exception) {
            // An uncleared row only keeps the connection blocked, which is the safe direction.
        }
    }
}
