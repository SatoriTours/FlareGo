package com.flarego

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.connection.GateRelease
import com.flarego.core.application.connection.GateState
import com.flarego.core.application.connection.CredentialGateCoordinator
import com.flarego.platform.AndroidCredentialGateStore
import com.flarego.platform.CredentialVault
import com.flarego.platform.LocalDatabase
import com.flarego.platform.TokenVault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The commit gate must be durable: arming, ownership, blocking and cold-start behaviour are all
 * verified against the real SQLite store (no memory-first cache), including a reopened database.
 */
class CredentialGateIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "gate-test.db"
    private val connectionId = "gate-connection"
    private val accountId = "c".repeat(32)
    private var database: LocalDatabase? = null

    private fun open(): LocalDatabase {
        database?.close()
        return LocalDatabase.open(context, dbName).also { database = it }
    }

    private fun store(db: LocalDatabase) = AndroidCredentialGateStore(db.database)

    @After fun cleanup(): Unit = runBlocking {
        database?.localStore?.removeConnection(connectionId)
        runCatching { database?.database?.credentialGateQueries?.removeConnectionGates(connectionId) }
        runCatching { TokenVault(context).remove(connectionId) }
        database?.close(); database = null
        context.deleteDatabase(dbName)
        Unit
    }

    @Test fun anArmedGateSurvivesAReopenedDatabase() = runBlocking {
        val first = open()
        val gate = CredentialGateCoordinator(store(first), { 1L }, { "op-1" })
        val attempt = gate.arm(connectionId, accountId)
        assertNotNull(attempt)
        assertEquals(GateState.PENDING, store(first).latest(connectionId)?.state)

        // A new database adapter reads the same durable row.
        val reopened = open()
        val row = store(reopened).latest(connectionId)
        assertNotNull(row)
        assertEquals(attempt!!.operationId, row!!.operationId)
        assertEquals(GateState.PENDING, row.state)
    }

    @Test fun aStaleOperationCannotReleaseANewerAttempt() = runBlocking {
        val db = open()
        val store = store(db)
        var op = 0
        val gate = CredentialGateCoordinator(store, { 1L }, { "op-" + (++op) })
        val stale = gate.arm(connectionId, accountId)!!
        val newer = gate.arm(connectionId, accountId)!!

        assertEquals(GateRelease.NOT_RELEASED, gate.release(stale))
        assertEquals(newer.operationId, store.latest(connectionId)?.operationId)
        assertEquals(GateRelease.RELEASED, gate.release(newer))
        assertNull(store.latest(connectionId))
    }

    @Test fun aBlockedGateSurvivesReopenAndStaysBlocked() = runBlocking {
        val first = open()
        val gate = CredentialGateCoordinator(store(first), { 1L }, { "op-block" })
        val attempt = gate.arm(connectionId, accountId)!!
        assertTrue(gate.block(attempt))

        val reopened = open()
        assertEquals(GateState.BLOCKED, store(reopened).latest(connectionId)?.state)
    }

    @Test fun aNewAttemptReplacesThePreviousGateAndReleaseMakesItUsableAfterRebuild() = runBlocking {
        val db = open()
        val store = store(db)
        var op = 0
        val gate = CredentialGateCoordinator(store, { 1L }, { "op-" + (++op) })
        val stale = gate.arm(connectionId, accountId)!!
        // Reconnection: the single row is replaced, never left as two competing gates.
        val current = gate.arm(connectionId, accountId)!!
        assertEquals(GateState.PENDING, store.latest(connectionId)?.state)
        assertEquals(current.operationId, store.latest(connectionId)?.operationId)
        assertEquals(GateRelease.RELEASED, gate.release(current))

        val reopened = open()
        assertNull("a confirmed release must survive a reopen", store(reopened).latest(connectionId))
        assertEquals(GateRelease.NOT_RELEASED, gate.release(stale))
    }

    @Test fun aFailedNewAttemptStaysBlockedAcrossARebuild() = runBlocking {
        val first = open()
        var op = 0
        val gate = CredentialGateCoordinator(store(first), { 1L }, { "op-" + (++op) })
        gate.arm(connectionId, accountId)!!
        gate.block(gate.arm(connectionId, accountId) ?: error("arm"))
        assertEquals(GateState.BLOCKED, store(first).latest(connectionId)?.state)

        val reopened = open()
        assertEquals(GateState.BLOCKED, store(reopened).latest(connectionId)?.state)
        // Only a confirmed new commit may release it; a bare arm is still PENDING (blocked).
        val next = CredentialGateCoordinator(store(reopened), { 2L }, { "op-retry" })
        next.arm(connectionId, accountId)
        assertEquals(GateState.PENDING, store(reopened).latest(connectionId)?.state)
    }

    @Test fun releaseRequiresTheExactOwnerAndAnExistingRow() = runBlocking {
        val db = open()
        val store = store(db)
        var op = 0
        val gate = CredentialGateCoordinator(store, { 1L }, { "op-" + (++op) })

        // No row at all: a release must not pretend it owned (and cleared) a gate.
        assertFalse("release without a gate must fail", store.release(connectionId, "op-missing"))

        val current = gate.arm(connectionId, accountId)!!
        // Wrong owner: the row belongs to another operation and must survive untouched.
        assertFalse("a foreign owner must not release", store.release(connectionId, "op-foreign"))
        assertEquals(current.operationId, store.latest(connectionId)?.operationId)
        assertEquals(GateState.PENDING, store.latest(connectionId)?.state)

        // Correct owner: exactly one successful release.
        assertTrue("the owning operation releases once", store.release(connectionId, current.operationId))
        assertNull(store.latest(connectionId))
        // Repeated release is not a success: there is nothing left to release.
        assertFalse("a repeated release must fail", store.release(connectionId, current.operationId))
    }

    @Test fun anOldOwnerNeverDeletesANewGateThroughRelease() = runBlocking {
        val db = open()
        val store = store(db)
        var op = 0
        val gate = CredentialGateCoordinator(store, { 1L }, { "op-" + (++op) })
        val stale = gate.arm(connectionId, accountId)!!
        val newer = gate.arm(connectionId, accountId)!!

        assertFalse("a stale release must fail", store.release(connectionId, stale.operationId))
        assertEquals(newer.operationId, store.latest(connectionId)?.operationId)
        assertTrue(store.release(connectionId, newer.operationId))
        assertNull(store.latest(connectionId))
    }

    @Test fun anUnknownStateIsReadAsBlocked() = runBlocking {
        val db = open()
        db.driver.execute(
            null,
            "INSERT INTO credential_gate(operation_id, connection_id, account_id, state, updated_at_ms) " +
                "VALUES ('op-x', ?, ?, 'WEIRD', 1)",
            2,
        ) { bindString(0, connectionId); bindString(1, accountId) }
        assertEquals(GateState.BLOCKED, store(db).latest(connectionId)?.state)
    }

    @Test fun aColdStartViewModelTreatsAGatedTokenConnectionAsNeedingReconfiguration() = runBlocking {
        // Seed a real connection row + a real stored Token + a PENDING gate, then build a fresh
        // ViewModel over the app database: it must not claim the account is usable.
        val app = context.applicationContext as Application
        val db = LocalDatabase.open(app)
        try {
            val connection = com.flarego.core.model.Connection(connectionId, "门禁账号", accountId)
            db.localStore.putConnection(connection)
            val storage = TokenVault(context)
            val vault = CredentialVault(storage::get, storage::put, storage::remove)
            vault.putToken(connectionId, "seed-token-0123456789")
            AndroidCredentialGateStore(db.database).arm(connectionId, accountId, "op-cold", 1L)
            val gated = CredentialVault(storage::get, storage::put, storage::remove,
                blocked = { AndroidCredentialGateStore(db.database).latestNow(it) != null })

            val model = FlareGoViewModel(app)
            assertTrue(
                "a PENDING gate must keep the account blocked after a cold start",
                model.needsTokenConfiguration(connection),
            )
            model.controller.selectConnection(connection)
            assertTrue(
                "a gated connection must not expose read products",
                model.controller.state.value.supportedProducts.isEmpty(),
            )
            val failure = runCatching { gated.token(connectionId) }.exceptionOrNull()
            assertNotNull("a gated credential must not be handed out", failure)
        } finally {
            runCatching { db.localStore.removeConnection(connectionId) }
            runCatching { db.database.credentialGateQueries.removeConnectionGates(connectionId) }
            runCatching { TokenVault(context).remove(connectionId) }
            db.close()
        }
    }
}
