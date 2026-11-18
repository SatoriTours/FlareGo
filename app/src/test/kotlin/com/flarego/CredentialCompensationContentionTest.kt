package com.flarego

import com.flarego.platform.CredentialVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real mutex contention for the compensating rollback.
 *
 * The per-connection lock is held by an actual in-flight reader that starts *after* the credential
 * write and before the compensating write, so the only thing under test is the production lock
 * wait inside NonCancellable compensation — never a stubbed fast path.
 */
class CredentialCompensationContentionTest {
    private val store = mutableMapOf<String, String>()

    private fun vault(lockTimeoutMs: Long, holding: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        CredentialVault(
            read = { store[it] },
            write = { id, value -> store[id] = value },
            delete = { store.remove(it); Unit },
            blocked = { holding.complete(Unit); release.await(); false },
            lockTimeoutMs = lockTimeoutMs,
        )

    @Test
    fun compensationWaitsForTheRealLockAndSucceedsOnceReleased(): Unit = runBlocking {
        store["conn"] = "original-token-0123456789"
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vault = vault(5_000, holding, release)
        var outcome: CommitOutcome? = null
        var reader: Job? = null
        val readers = CoroutineScope(Dispatchers.Default + Job())
        val commit = launch(Dispatchers.Default) {
            runCatching {
                commitCredentialThenRow(vault, "conn", "replacement-token-0123456789", { outcome = it }) {
                    // The credential is already written; an independent reader grabs the lock.
                    reader = readers.launch { vault.token("conn") }
                    holding.await()
                    throw IllegalStateException("row insert failed")
                }
            }
        }
        // Compensation is now waiting on the same-id lock held by the real reader.
        delay(150)
        assertEquals("replacement-token-0123456789", store["conn"])
        assertTrue(!commit.isCompleted)
        release.complete(Unit)
        commit.join(); reader?.join(); readers.cancel()

        assertEquals(CommitState.RESTORED, outcome?.state)
        assertEquals("original-token-0123456789", store["conn"])
    }

    @Test
    fun aTimedOutCompensationReportsUncertainAndKeepsTheNewCredential(): Unit = runBlocking {
        store["conn"] = "original-token-0123456789"
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vault = vault(100, holding, release)
        var outcome: CommitOutcome? = null
        var reader: Job? = null
        val readers = CoroutineScope(Dispatchers.Default + Job())

        val commit = launch(Dispatchers.Default) {
            runCatching {
                commitCredentialThenRow(vault, "conn", "replacement-token-0123456789", { outcome = it }) {
                    reader = readers.launch { vault.token("conn") }
                    holding.await()
                    throw IllegalStateException("row insert failed")
                }
            }
        }
        delay(400)

        assertTrue("compensation must have given up", commit.isCompleted)
        assertEquals(CommitState.UNCERTAIN, outcome?.state)
        // No detached write: the new credential is still stored because the restore timed out.
        assertEquals("replacement-token-0123456789", store["conn"])
        release.complete(Unit)
        reader?.join(); readers.cancel()
    }

    @Test
    fun aTimedOutCompensationNeverRunsInTheBackgroundAfterwards(): Unit = runBlocking {
        store["conn"] = "original-token-0123456789"
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vault = vault(100, holding, release)
        var outcome: CommitOutcome? = null
        var reader: Job? = null
        val readers = CoroutineScope(Dispatchers.Default + Job())

        val commit = launch(Dispatchers.Default) {
            runCatching {
                commitCredentialThenRow(vault, "conn", "replacement-token-0123456789", { outcome = it }) {
                    reader = readers.launch { vault.token("conn") }
                    holding.await()
                    throw IllegalStateException("row insert failed")
                }
            }
        }
        delay(400)
        assertEquals(CommitState.UNCERTAIN, outcome?.state)
        // Release long after the timeout: a detached compensation would flip the value now.
        release.complete(Unit)
        commit.join(); reader?.join(); readers.cancel()
        delay(150)
        assertEquals("replacement-token-0123456789", store["conn"])
    }
}
