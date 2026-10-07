package com.flarego.platform

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.flarego.core.model.*
import com.flarego.core.ports.LocalStore
import com.flarego.db.FlareGoDatabase

class AndroidLocalStore(context: Context) : LocalStore {
    private val database =
        FlareGoDatabase(AndroidSqliteDriver(FlareGoDatabase.Schema, context, "flarego.db"))
    private val queries = database.localQueries

    override fun connections() =
        queries.allConnections().executeAsList().map { Connection(it.id, it.name, it.account_id) }

    override fun putConnection(connection: Connection) {
        queries.upsertConnection(connection.id, connection.name, connection.accountId)
    }

    override fun removeConnection(id: String) {
        database.transaction {
            queries.removeConnection(id)
            queries.removeOperations(id)
        }
    }

    override fun operations() =
        queries.allOperations().executeAsList().map {
            Operation(
                it.id,
                it.connection_id,
                it.title,
                OperationStatus.valueOf(it.status),
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
