package com.flarego.platform

import com.flarego.core.application.connection.CredentialGateStore
import com.flarego.core.application.connection.GateRecord
import com.flarego.core.application.connection.GateState
import com.flarego.db.FlareGoDatabase

/**
 * SQLite-backed commit gate with exactly one logical row per connection.
 *
 * The gate lives in the app's own database so the *store itself* confirms arm/release inside one
 * transaction; a memory-first cache (SharedPreferences) can report success before the value is
 * durable, which is why it is not used here. Credentials never enter this table.
 *
 * Replacement is atomic: arming deletes the previous row and inserts the new PENDING row in the
 * same transaction, so an observer never sees a connection without a gate while an attempt runs.
 */
class AndroidCredentialGateStore(
    private val database: FlareGoDatabase,
) : CredentialGateStore {
    private val queries get() = database.credentialGateQueries

    override suspend fun arm(
        connectionId: String,
        accountId: String,
        operationId: String,
        nowMs: Long,
    ): Boolean = database.transactionWithResult {
        queries.deleteConnectionGate(connectionId)
        queries.insertGate(connectionId, operationId, accountId, nowMs)
        val row = queries.gateForConnection(connectionId).executeAsOneOrNull()
        // Confirmed by the store: the single row must be exactly this operation.
        row != null && row.operation_id == operationId && row.account_id == accountId &&
            row.state == PENDING
    }

    override suspend fun release(connectionId: String, operationId: String): Boolean =
        database.transactionWithResult {
            // Owner check and delete happen inside one transaction, so no external check can race
            // with the write. A missing row is NOT a successful release: nothing was owned here,
            // which also makes a repeated release fail instead of reporting a second success.
            val current = queries.gateForConnection(connectionId).executeAsOneOrNull()
                ?: return@transactionWithResult false
            if (current.operation_id != operationId) return@transactionWithResult false
            queries.releaseGate(connectionId, operationId)
            // Confirmed: our row is gone and no newer owner replaced it.
            queries.gateForConnection(connectionId).executeAsOneOrNull() == null
        }

    override suspend fun block(connectionId: String, operationId: String, nowMs: Long): Boolean =
        database.transactionWithResult {
            // Owner check first: the UPDATE only matches our own row, then we verify it.
            queries.blockGate(nowMs, connectionId, operationId)
            val row = queries.gateForConnection(connectionId).executeAsOneOrNull()
            row != null && row.operation_id == operationId && row.state == BLOCKED
        }

    override suspend fun latest(connectionId: String): GateRecord? = gate(connectionId)

    /** Startup read: the connection's gate row, or null. Throws when the database cannot be read. */
    fun latestNow(connectionId: String): GateRecord? = gate(connectionId)

    private fun gate(connectionId: String): GateRecord? =
        queries.gateForConnection(connectionId).executeAsOneOrNull()?.let { row ->
            GateRecord(
                operationId = row.operation_id,
                connectionId = row.connection_id,
                accountId = row.account_id,
                // Unknown/corrupt states are treated as BLOCKED (needs reconfiguration), never
                // as a benign in-flight PENDING.
                state = if (row.state == PENDING) GateState.PENDING else GateState.BLOCKED,
            )
        }

    override suspend fun clear(connectionId: String) {
        queries.removeConnectionGates(connectionId)
    }

    private companion object {
        const val PENDING = "PENDING"
        const val BLOCKED = "BLOCKED"
    }
}
