package com.flarego.platform

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.flarego.core.model.*
import com.flarego.core.ports.LocalStore
import com.flarego.db.FlareGoDatabase

/**
 * The legacy local store.
 *
 * In production it shares the [LocalDatabase] owner's database, so its connection,
 * operation and task rows are written in one transaction and no second connection
 * is opened. The `Context` constructor remains for compatibility with existing
 * callers and tests that open their own file.
 */
class AndroidLocalStore internal constructor(private val database: FlareGoDatabase) : LocalStore {
    constructor(context: Context) :
        this(FlareGoDatabase(AndroidSqliteDriver(FlareGoDatabase.Schema, context, LocalDatabase.NAME)))

    private val queries = database.localQueries

    override fun connections() =
        queries.allConnections().executeAsList().map { Connection(it.id, it.name, it.account_id) }

    override fun putConnection(connection: Connection) {
        queries.upsertConnection(connection.id, connection.name, connection.accountId)
    }

    override fun removeConnection(id: String) {
        // One owner transaction: the account, its operations and its tasks leave together.
        database.transaction {
            queries.removeConnection(id)
            queries.removeOperations(id)
            database.tasksQueries.removeTasksForConnection(id)
        }
    }

    override fun operations() =
        queries.allOperations().executeAsList().map {
            Operation(
                it.id,
                it.connection_id,
                it.title,
                // A status written by a future version reads as UNKNOWN instead of crashing.
                OperationTaskPersistence.operationStatusOf(it.status),
                it.detail,
            )
        }

    override fun putOperation(operation: Operation) {
        queries.upsertOperation(
            operation.id,
            operation.connectionId,
            operation.title,
            operation.status.name,
            operation.detail,
        )
    }
}
