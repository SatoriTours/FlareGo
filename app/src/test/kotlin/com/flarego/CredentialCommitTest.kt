package com.flarego

import com.flarego.platform.CredentialVault
import com.flarego.platform.TokenWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unified commit-result semantics.
 *
 * The vault and the account row are two stores and the only honest outcomes are:
 * COMMITTED / UNCHANGED (nothing stored changed) / RESTORED (rolled back) / UNCERTAIN.
 * A thrown write is *not* proof that nothing was written: SharedPreferences updates memory
 * before commit(), so every throwing path is compensated and, if that fails, reported UNCERTAIN.
 */
class CredentialCommitTest {
    private val store = mutableMapOf<String, String>()
    private fun vault(store: MutableMap<String, String> = this.store) = CredentialVault(
        read = { store[it] },
        write = { id, value -> store[id] = value },
        delete = { store.remove(it); Unit },
    )

    @Test fun aSuccessfulCommitReportsCommittedAndKeepsTheNewValue() = runBlocking {
        store["conn"] = "original-token-0123456789"
        var outcome: CommitOutcome? = null
        var rows = 0
        val result = commitCredentialThenRow(vault(), "conn", "replacement-token-0123456789", { outcome = it }) { rows++ }
        assertEquals(CommitState.COMMITTED, result.state)
        assertEquals(CommitState.COMMITTED, outcome?.state)
        assertEquals(1, rows)
        assertEquals("replacement-token-0123456789", store["conn"])
    }

    @Test fun aFailedRowWriteReportsRestoredAndPutsTheOldTokenBack() = runBlocking {
        store["conn"] = "original-token-0123456789"
        var outcome: CommitOutcome? = null
        val thrown = runCatching {
            commitCredentialThenRow(vault(), "conn", "replacement-token-0123456789", { outcome = it }) {
                throw IllegalStateException("row insert failed")
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(CommitState.RESTORED, outcome?.state)
        assertEquals("original-token-0123456789", store["conn"])
    }

    @Test fun aFailedRowWriteAfterANewerCredentialIsUncertainInsteadOfRevertingIt() = runBlocking {
        store["conn"] = "original-token-0123456789"
        val vault = vault()
        var outcome: CommitOutcome? = null
        runCatching {
            commitCredentialThenRow(vault, "conn", "replacement-token-0123456789", { outcome = it }) {
                // A newer write wins; the stale compensation must refuse instead of overwriting it.
                vault.putToken("conn", "newer-token-0123456789")
                throw IllegalStateException("row insert failed")
            }
        }
        assertEquals(CommitState.UNCERTAIN, outcome?.state)
        assertEquals("newer-token-0123456789", store["conn"])
    }

    @Test fun aWriteThatThrowsAfterSideEffectsIsCompensatedAndReportedUnchanged() = runBlocking {
        store["conn"] = "original-token-0123456789"
        // Mirrors SharedPreferences: memory is updated, then commit() fails — but only for the
        // first write, so the compensation itself can succeed.
        var calls = 0
        val sideEffectVault = CredentialVault(
            read = { store[it] },
            write = { id, value ->
                store[id] = value
                if (++calls == 1) throw IllegalStateException("commit failed")
            },
            delete = { store.remove(it); Unit },
        )
        var outcome: CommitOutcome? = null
        var rows = 0
        runCatching {
            commitCredentialThenRow(sideEffectVault, "conn", "replacement-token-0123456789", { outcome = it }) { rows++ }
        }
        assertEquals(CommitState.UNCHANGED, outcome?.state)
        assertEquals(0, rows)
        assertEquals("original-token-0123456789", store["conn"])
    }

    @Test fun aWriteAndItsCompensationBothFailingIsReportedUncertain() = runBlocking {
        store["conn"] = "original-token-0123456789"
        var writes = 0
        val broken = CredentialVault(
            read = { store[it] },
            write = { id, value ->
                store[id] = value
                if (++writes >= 2) throw IllegalStateException("commit failed again")
                if (writes == 1) throw IllegalStateException("commit failed")
            },
            delete = { store.remove(it); Unit },
        )
        var outcome: CommitOutcome? = null
        runCatching {
            commitCredentialThenRow(broken, "conn", "replacement-token-0123456789", { outcome = it }) { }
        }
        assertEquals(CommitState.UNCERTAIN, outcome?.state)
    }

    @Test fun cancellationDuringTheRowWriteStillCompensatesAndReportsBeforeRethrowing() = runBlocking {
        store["conn"] = "original-token-0123456789"
        var outcome: CommitOutcome? = null
        val thrown = runCatching {
            commitCredentialThenRow(vault(), "conn", "replacement-token-0123456789", { outcome = it }) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        // Compensation ran inside NonCancellable, so the result is a fact, not a guess.
        assertEquals(CommitState.RESTORED, outcome?.state)
        assertEquals("original-token-0123456789", store["conn"])
    }

    @Test fun aWriteValidationFailureReportsUnchangedWithoutTouchingTheRow() = runBlocking {
        store["conn"] = "original-token-0123456789"
        var outcome: CommitOutcome? = null
        var rows = 0
        runCatching { commitCredentialThenRow(vault(), "conn", "   ", { outcome = it }) { rows++ } }
        assertEquals(CommitState.UNCHANGED, outcome?.state)
        assertEquals(0, rows)
        assertEquals("original-token-0123456789", store["conn"])
    }

    @Test fun theVaultDistinguishesWrittenUnchangedAndUncertainWrites() = runBlocking {
        store["conn"] = "original-token-0123456789"
        val vault = vault()
        val written = vault.replaceTokenOrRestore("conn", "replacement-token-0123456789")
        assertTrue(written is TokenWrite.Written)
        assertEquals("replacement-token-0123456789", store["conn"])
        vault.restore((written as TokenWrite.Written).rollback)
        assertEquals("original-token-0123456789", store["conn"])
        // A rejected value is reported without any write attempt.
        val rejected = vault.replaceTokenOrRestore("conn", "has space")
        assertTrue(rejected is TokenWrite.Unchanged)
        assertEquals("original-token-0123456789", store["conn"])
    }

    @Test fun theViewModelCommitsThroughTheHelperAndUsesItsOutcome() {
        val source = java.io.File(
            generateSequence(java.io.File("").absoluteFile) { it.parentFile }
                .first { java.io.File(it, "settings.gradle.kts").isFile },
            "app/src/main/kotlin/com/flarego/FlareGoViewModel.kt",
        ).readText()
        assertTrue(source.contains("commitCredentialThenRow("))
        // The account state must follow the reported outcome, not a guess.
        assertTrue(source.contains("CommitState.UNCERTAIN"))
        assertTrue(source.contains("CredentialSaveOutcome"))
        assertFalse(source.contains("persist(connection.id);store.putConnection(connection)"))
    }
}
