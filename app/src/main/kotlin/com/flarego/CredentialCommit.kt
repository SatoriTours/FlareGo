package com.flarego

import com.flarego.core.model.CredentialKind
import com.flarego.platform.CredentialVault
import com.flarego.platform.TokenWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The only honest outcomes of committing a credential together with its account row. */
internal enum class CommitState {
    /** Both stores now hold the new value. */
    COMMITTED,

    /** Nothing stored changed: validation rejected the value, or a throwing write was rolled back. */
    UNCHANGED,

    /** The new credential was written and then rolled back because the row could not be written. */
    RESTORED,

    /** The write threw and compensation failed, or a newer write superseded our rollback. */
    UNCERTAIN,
}

internal data class CommitOutcome(val state: CommitState, val cause: Exception? = null)

/**
 * Commits one credential together with its local account row.
 *
 * This is **not** a cross-store transaction and never claims to be one. The encrypted vault and
 * the SQLDelight row are independent stores, so the commit reports what actually happened:
 *
 * - the vault distinguishes *written* / *unchanged* / *uncertain* writes, because a throwing write
 *   may already have mutated storage (SharedPreferences updates memory before commit() returns);
 * - a failed row write is compensated inside [NonCancellable], so cancellation and lock contention
 *   cannot skip it; the outcome is reported before the failure/cancellation is rethrown;
 * - a rollback refuses to run when a newer write already superseded it (CONFLICT), which is
 *   reported as [CommitState.UNCERTAIN] instead of resurrecting an older credential.
 *
 * [onOutcome] is called exactly once for every path, including cancellation.
 */
internal suspend fun commitCredentialThenRow(
    vault: CredentialVault,
    connectionId: String,
    token: String,
    onOutcome: (CommitOutcome) -> Unit = {},
    writeRow: suspend () -> Unit,
): CommitOutcome = when (val write = vault.replaceTokenOrRestore(connectionId, token)) {
    is TokenWrite.Unchanged -> CommitOutcome(CommitState.UNCHANGED, write.cause).also {
        onOutcome(it)
        throw write.cause
    }

    is TokenWrite.Uncertain -> CommitOutcome(CommitState.UNCERTAIN, write.cause).also {
        onOutcome(it)
        throw write.cause
    }

    is TokenWrite.Written -> {
        try {
            writeRow()
            CommitOutcome(CommitState.COMMITTED).also(onOutcome)
        } catch (cancel: CancellationException) {
            onOutcome(compensate(vault, write.rollback, cancel))
            throw cancel
        } catch (failure: Exception) {
            onOutcome(compensate(vault, write.rollback, failure))
            throw failure
        }
    }
}

/**
 * Credential kind used for capability decisions.
 *
 * An uncertain commit is treated as "absent": the account keeps its row but nothing is requested
 * and the UI asks for a Token again. A demo connection is handled by its caller (never flagged).
 */
internal fun effectiveCredentialKind(stored: CredentialKind, uncertain: Boolean): CredentialKind =
    if (uncertain) CredentialKind.ABSENT else stored

/**
 * A row only counts as configured when a verified Token is actually stored. A legacy OAuth record
 * and a row without any credential (for example after an interrupted replacement) both need a
 * Token; a demo connection never does.
 */
internal fun needsTokenConfiguration(kind: CredentialKind, demo: Boolean): Boolean =
    !demo && kind != CredentialKind.TOKEN

/** Runs regardless of the caller's cancellation state; bounded to one vault write. */
private suspend fun compensate(
    vault: CredentialVault,
    rollback: CredentialVault.TokenRollback,
    cause: Exception,
): CommitOutcome = withContext(NonCancellable) {
    try {
        vault.restore(rollback)
        CommitOutcome(CommitState.RESTORED, cause)
    } catch (_: Exception) {
        // Includes CONFLICT (a newer write won) and storage failures: both are "unknown", not
        // "restored", and the caller must not pretend otherwise.
        CommitOutcome(CommitState.UNCERTAIN, cause)
    }
}
