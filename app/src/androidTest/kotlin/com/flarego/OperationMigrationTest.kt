package com.flarego

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.model.AccountScope
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationStatus
import com.flarego.core.model.OperationTask
import com.flarego.core.model.OperationTaskPersistence
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState
import com.flarego.platform.AndroidLocalStore
import com.flarego.platform.LocalDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Android migration and ownership coverage for the task store.
 *
 * This test is the platform gate: it is written to run on a device or emulator
 * through `:app:connectedDebugAndroidTest`, and it must not be replaced by a host
 * SQLite or in-memory substitute.
 */
class OperationMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "flarego-migration-test.db"
    private val opened = mutableListOf<LocalDatabase>()

    private val account = AccountScope("connection-1", "account-1")

    private fun owner(): LocalDatabase = LocalDatabase.open(context, databaseName).also { opened += it }

    private fun task(
        id: String = "task-1",
        connectionId: String = "connection-1",
        state: TaskState = TaskState.QUEUED,
        updatedAtMs: Long = 2_000,
    ) = OperationTask(
        id,
        connectionId,
        account,
        "dns-records-for-a-zone-create-dns-record",
        ResourceRef(account, "dns_record", "record-1"),
        "a".repeat(64),
        "b".repeat(64),
        state,
        "已登记，等待执行",
        "provider-request-1",
        "/accounts/account-1/dns_records/record-1",
        1_000,
        updatedAtMs,
    )

    /** Creates an exact v1 database: the legacy tables only, with user_version = 1. */
    private fun createLegacyV1Database() {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            database.execSQL(
                "CREATE TABLE connection (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, account_id TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE operation (" +
                    "sequence INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, " +
                    "connection_id TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL)"
            )
            database.execSQL(
                "INSERT INTO connection(id, name, account_id) VALUES ('connection-1', 'Fixture account', 'account-1')"
            )
            for ((index, status) in listOf("SUCCESS", "FAILED", "UNKNOWN").withIndex()) {
                database.execSQL(
                    "INSERT INTO operation(id, connection_id, title, status, detail) " +
                        "VALUES ('legacy-$index', 'connection-1', 'DNS 变更', '$status', '历史记录')"
                )
            }
            database.version = 1
        } finally {
            database.close()
        }
    }

    private fun rawTableNames(): Set<String> {
        val database = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val names = mutableSetOf<String>()
            database.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
            return names
        } finally {
            database.close()
        }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        opened.clear()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun legacyV1DatabaseUpgradesWithoutLosingAccountsOrOperations() {
        createLegacyV1Database()
        val owner = owner()
        assertEquals(setOf("connection-1"), owner.localStore.connections().map { it.id }.toSet())
        assertEquals(3, owner.localStore.operations().size)
        assertEquals(
            listOf(OperationStatus.SUCCESS, OperationStatus.FAILED, OperationStatus.UNKNOWN).toSet(),
            owner.localStore.operations().map { it.status }.toSet(),
        )
        assertTrue(rawTableNames().contains("task"))
        val stored = task()
        owner.operationStore.put(stored)
        assertEquals(stored, owner.operationStore.get(stored.id))
    }

    @Test
    fun freshDatabaseCreatesTheTaskTable() {
        val owner = owner()
        assertTrue(rawTableNames().contains("task"))
        val stored = task()
        owner.operationStore.put(stored)
        assertEquals(listOf(stored.id), owner.operationStore.list("connection-1").map { it.id })
        assertNull(owner.operationStore.get("missing"))
    }

    @Test
    fun futureStoredStatusesAreReadAsUnknownWithoutCrashing() {
        createLegacyV1Database()
        val owner = owner()
        owner.localStore.putOperation(
            Operation("future-op", "connection-1", "DNS 变更", OperationStatus.UNKNOWN, "待核对")
        )
        val database = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            database.execSQL("UPDATE operation SET status = 'FUTURE_STATUS' WHERE id = 'future-op'")
            database.execSQL("UPDATE operation SET status = 'SUCCEEDED' WHERE id = 'legacy-0'")
        } finally {
            database.close()
        }
        assertEquals(OperationStatus.UNKNOWN, owner.localStore.operations().single { it.id == "future-op" }.status)
        assertEquals(OperationStatus.SUCCESS, owner.localStore.operations().single { it.id == "legacy-0" }.status)

        owner.operationStore.put(task(id = "future-task"))
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL("UPDATE task SET state = 'FUTURE_STATE' WHERE id = 'future-task'")
        } finally {
            raw.close()
        }
        assertEquals(TaskState.UNKNOWN, owner.operationStore.get("future-task")!!.state)
    }

    @Test
    fun bothStoresShareOneOwnerAndOneTransaction() {
        val owner = owner()
        val stored = task(state = TaskState.SUCCEEDED)
        owner.operationStore.putWithSummary(stored, OperationTaskPersistence.summaryFor(stored))
        assertEquals(stored, owner.operationStore.get(stored.id))
        val summary = owner.localStore.operations().single { it.id == stored.id }
        assertEquals(OperationStatus.SUCCESS, summary.status)
        assertNotEquals(stored.safeDetail, summary.detail)
    }

    @Test
    fun aSummaryFailureLeavesNeitherTheTaskNorTheSummary() {
        val owner = owner()
        val stored = task()
        // A summary that does not match the task state must be rejected before any write.
        val mismatched = Operation(stored.id, stored.connectionId, "t", OperationStatus.SUCCESS, "d")
        try {
            owner.operationStore.putWithSummary(stored, mismatched)
            fail("a mismatched summary must be rejected")
        } catch (_: Exception) {
        }
        assertNull(owner.operationStore.get(stored.id))
        assertTrue(owner.localStore.operations().none { it.id == stored.id })
    }

    @Test
    fun aFailureInsideOneTransactionRollsBackBothRows() {
        val owner = owner()
        val stored = task()
        try {
            owner.database.transaction {
                owner.operationStore.put(stored)
                owner.localStore.putOperation(
                    Operation(stored.id, stored.connectionId, "t", OperationStatus.UNKNOWN, "d")
                )
                throw IllegalStateException("synthetic transaction failure")
            }
            fail("the synthetic failure must propagate")
        } catch (_: IllegalStateException) {
        }
        assertNull(owner.operationStore.get(stored.id))
        assertTrue(owner.localStore.operations().none { it.id == stored.id })
    }

    @Test
    fun removingAConnectionClearsOnlyItsOwnTasks() {
        val owner = owner()
        owner.operationStore.put(task(id = "task-1", connectionId = "connection-1"))
        owner.operationStore.put(task(id = "task-2", connectionId = "connection-2"))
        owner.localStore.removeConnection("connection-1")
        assertNull(owner.operationStore.get("task-1"))
        assertEquals("task-2", owner.operationStore.get("task-2")!!.id)
        assertTrue(owner.operationStore.list("connection-2").isNotEmpty())
    }

    @Test
    fun ownerCloseAndLegacyConstructorRemainUsable() {
        val owner = owner()
        owner.operationStore.put(task(id = "task-1"))
        owner.close()
        opened.remove(owner)
        // Reopening the same file sees the persisted task.
        val reopened = owner()
        assertEquals("task-1", reopened.operationStore.get("task-1")!!.id)
        // The compatibility constructor still opens the production file on its own.
        val legacy = AndroidLocalStore(context)
        try {
            assertNotNull(legacy.operations())
        } finally {
            legacy.removeConnection("connection-does-not-exist")
        }
        val connection = Connection("connection-legacy", "Legacy", "account-legacy")
        val legacyOwner = owner()
        legacyOwner.localStore.putConnection(connection)
        assertTrue(legacyOwner.localStore.connections().any { it.id == connection.id })
    }
}
