package com.flarego.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.flarego.db.FlareGoDatabase

/**
 * The single database owner of the app.
 *
 * One driver and one [FlareGoDatabase] are shared by the legacy local store and the
 * task store, so neither store opens its own connection and both can join one
 * transaction. The owner is closed once, when the app view model is cleared.
 */
class LocalDatabase internal constructor(
    val driver: SqlDriver,
    val database: FlareGoDatabase,
) : AutoCloseable {
    val localStore: AndroidLocalStore = AndroidLocalStore(database)
    val operationStore: AndroidOperationStore = AndroidOperationStore(database)

    override fun close() {
        driver.close()
    }

    companion object {
        const val NAME: String = "flarego.db"

        /** Opens (and migrates) the shared database for this application. */
        fun open(context: Context): LocalDatabase = open(context, NAME)

        /**
         * Opens (and migrates) a named database file. Production uses [NAME]; tests
         * use their own file so a migration fixture can be prepared before opening.
         */
        fun open(context: Context, name: String): LocalDatabase {
            val driver = AndroidSqliteDriver(FlareGoDatabase.Schema, context.applicationContext, name)
            return LocalDatabase(driver, FlareGoDatabase(driver))
        }
    }
}
