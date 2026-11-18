package com.flarego.core

import com.flarego.core.application.connection.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Persistent commit gate: arming, ownership, release confirmation and fail-closed reads. */
class CredentialGateTest {
    private class FakeStore : CredentialGateStore {
        val rows = mutableMapOf<String, GateRecord>()
        var armThrows = false
        var armConfirms = true
        var releaseThrows = false
        var releaseAfterSideEffectThrows = false
        var readThrows = false
        var blockThrows = false
        var blockConfirms = true

        override suspend fun arm(connectionId: String, accountId: String, operationId: String, nowMs: Long): Boolean {
            if (armThrows) throw IllegalStateException("db down")
            if (!armConfirms) return false
            rows[connectionId] = GateRecord(operationId, connectionId, accountId, GateState.PENDING)
            return true
        }

        override suspend fun release(connectionId: String, operationId: String): Boolean {
            if (releaseAfterSideEffectThrows) {
                // Database applied the delete, then the confirmation step failed.
                rows.remove(connectionId)
                throw IllegalStateException("confirmation failed")
            }
            if (releaseThrows) throw IllegalStateException("db down")
            val row = rows[connectionId] ?: return false
            if (row.operationId != operationId) return false
            rows.remove(connectionId)
            return true
        }

        override suspend fun block(connectionId: String, operationId: String, nowMs: Long): Boolean {
            if (blockThrows) throw IllegalStateException("db down")
            val row = rows[connectionId] ?: return false
            if (row.operationId != operationId) return false
            if (!blockConfirms) return false
            rows[connectionId] = row.copy(state = GateState.BLOCKED)
            return true
        }

        override suspend fun latest(connectionId: String): GateRecord? {
            if (readThrows) throw IllegalStateException("unreadable")
            return rows[connectionId]
        }

        override suspend fun clear(connectionId: String) { rows.remove(connectionId) }
    }

    private fun coordinator(store: FakeStore) = CredentialGateCoordinator(store, { 1L }, { "op-" + store.rows.size })

    @Test fun armingMustBeConfirmedBeforeAnyCredentialWork() = runTest {
        val store = FakeStore().apply { armConfirms = false }
        assertNull(coordinator(store).arm("conn", "account"))
        store.armThrows = true
        assertNull(coordinator(store).arm("conn", "account"))
        assertTrue(store.rows.isEmpty())
    }

    @Test fun aConfirmedArmRecordsPendingWithTheOwningOperation() = runTest {
        val store = FakeStore()
        val attempt = assertNotNull(coordinator(store).arm("conn", "account"))
        assertEquals("conn", attempt.connectionId)
        assertEquals(GateState.PENDING, store.rows.getValue("conn").state)
        assertEquals(attempt.operationId, store.rows.getValue("conn").operationId)
    }

    @Test fun releaseIsOnlyAcceptedWhenTheStoreConfirmsIt() = runTest {
        val store = FakeStore()
        val gate = coordinator(store)
        val attempt = assertNotNull(gate.arm("conn", "account"))
        assertEquals(GateRelease.RELEASED, gate.release(attempt))
        assertTrue(store.rows.isEmpty())

        val second = assertNotNull(gate.arm("conn", "account"))
        store.releaseThrows = true
        assertEquals(GateRelease.NOT_RELEASED, gate.release(second))

        // A delete that took effect but whose confirmation threw is still NOT_RELEASED:
        // reporting SAVED would claim durability that was never observed.
        store.releaseThrows = false
        store.releaseAfterSideEffectThrows = true
        val third = assertNotNull(gate.arm("conn", "account"))
        assertEquals(GateRelease.NOT_RELEASED, gate.release(third))
        assertFalse(store.rows.containsKey("conn"))
    }

    @Test fun aStaleOperationCannotClearANewerAttempt() = runTest {
        val store = FakeStore()
        val gate = CredentialGateCoordinator(store, { 1L }, { "op" })
        val stale = assertNotNull(gate.arm("conn", "account"))
        // A newer attempt arms over the stale one.
        store.rows["conn"] = store.rows.getValue("conn").copy(operationId = "op-new")
        assertEquals(GateRelease.NOT_RELEASED, gate.release(stale))
        assertEquals("op-new", store.rows.getValue("conn").operationId)
        assertFalse(gate.block(stale))
        assertEquals(GateState.PENDING, store.rows.getValue("conn").state)
    }

    @Test fun blockingIsConfirmedAndFailureLeavesTheCallerUncertain() = runTest {
        val store = FakeStore()
        val gate = coordinator(store)
        val attempt = assertNotNull(gate.arm("conn", "account"))
        assertTrue(gate.block(attempt))
        assertEquals(GateState.BLOCKED, store.rows.getValue("conn").state)
        store.blockConfirms = false
        assertFalse(gate.block(attempt))
        store.blockThrows = true
        assertFalse(gate.block(attempt))
    }

    @Test fun anUnreadableGateFailsClosed() = runTest {
        val store = FakeStore().apply { readThrows = true }
        assertTrue(coordinator(store).isBlocked("conn"))
        store.readThrows = false
        assertFalse(coordinator(store).isBlocked("conn"))
        store.rows["conn"] = GateRecord("op", "conn", "account", GateState.PENDING)
        assertTrue(coordinator(store).isBlocked("conn"))
        store.rows["conn"] = store.rows.getValue("conn").copy(state = GateState.BLOCKED)
        assertTrue(coordinator(store).isBlocked("conn"))
    }

    @Test fun clearingIsBestEffortAndNeverThrows() = runTest {
        val store = FakeStore()
        store.rows["conn"] = GateRecord("op", "conn", "account", GateState.BLOCKED)
        coordinator(store).clear("conn")
        assertNull(store.rows["conn"])
    }
}
