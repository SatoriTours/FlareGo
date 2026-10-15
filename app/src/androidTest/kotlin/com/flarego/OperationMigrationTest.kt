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
        accountId: String = "account-1",
        state: TaskState = TaskState.QUEUED,
        updatedAtMs: Long = 2_000,
    ) = OperationTask(
        id,
        connectionId,
        // The scope and reference always belong to their own connection: the production
        // constructor verifies this and the fixture must not weaken it.
        AccountScope(connectionId, accountId),
        "dns-records-for-a-zone-create-dns-record",
        ResourceRef(AccountScope(connectionId, accountId), "dns_record", "record-1"),
        "a".repeat(64),
        "b".repeat(64),
        state,
        "已登记，等待执行",
        "provider-request-1",
        "/accounts/$accountId/dns_records/record-1",
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
        // The first real driver queries above have now initialised/migrated the file.
        assertEquals(
            listOf(OperationStatus.SUCCESS, OperationStatus.FAILED, OperationStatus.UNKNOWN).toSet(),
            owner.localStore.operations().map { it.status }.toSet(),
        )
        assertTrue(rawTableNames().contains("task"))
        val stored = task()
        owner.operationStore.put(stored)
        assertEquals(
            stored.copy(safeDetail = "结果待核对，请刷新后检查"),
            owner.operationStore.get(stored.id),
        )
    }

    @Test
    fun freshDatabaseCreatesTheTaskTable() {
        val owner = owner()
        // Initialise (and migrate) through the real driver before any raw check.
        assertTrue(owner.operationStore.list("connection-none").isEmpty())
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
            // 'SUCCEEDED' is a task-state name, not a legacy operation status: the legacy
            // parser must read it as UNKNOWN rather than gaining a new alias.
            database.execSQL("UPDATE operation SET status = 'SUCCEEDED' WHERE id = 'legacy-0'")
        } finally {
            database.close()
        }
        val operations = owner.localStore.operations().associateBy { it.id }
        assertEquals(OperationStatus.UNKNOWN, operations.getValue("future-op").status)
        assertEquals(OperationStatus.UNKNOWN, operations.getValue("legacy-0").status)
        assertEquals(OperationStatus.FAILED, operations.getValue("legacy-1").status)

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
        // All metadata survives, while caller detail is replaced by fixed public text.
        assertEquals(stored.copy(safeDetail = "已确认完成"), owner.operationStore.get(stored.id))
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

    /**
     * Fault injection on the store's own transaction: a SQLite trigger makes the
     * summary write fail. `putWithSummary` is called directly (no outer transaction),
     * so this proves the store rolls its task write back with its summary write.
     */
    @Test
    fun putWithSummaryRollsBackItsOwnTransactionWhenTheSummaryWriteFails() {
        val owner = owner()
        val stored = task()
        val canonical = OperationTaskPersistence.summaryFor(stored)
        owner.driver.execute(
            null,
            "CREATE TRIGGER fail_summary BEFORE INSERT ON operation BEGIN SELECT RAISE(ABORT, 'synthetic fault'); END",
            0,
        )
        try {
            try {
                owner.operationStore.putWithSummary(stored, canonical)
                fail("the injected summary fault must propagate")
            } catch (_: Exception) {
            }
            assertNull("the task write must roll back with the summary write", owner.operationStore.get(stored.id))
            assertTrue("no summary row may remain", owner.localStore.operations().none { it.id == stored.id })
        } finally {
            owner.driver.execute(null, "DROP TRIGGER IF EXISTS fail_summary", 0)
        }
        // With the fault cleared the same call succeeds, so the rollback was the fault.
        owner.operationStore.putWithSummary(stored, canonical)
        assertNotNull(owner.operationStore.get(stored.id))
    }

    /** The persisted summary is the canonical state text, never caller title/detail. */
    @Test
    fun theStoredSummaryIsCanonicalStateTextRatherThanCallerText() {
        val marker = "synthetic-caller-text"
        val owner = owner()
        val stored = task(state = TaskState.SUCCEEDED)
        val caller = Operation(stored.id, stored.connectionId, marker, OperationStatus.SUCCESS, marker)
        owner.operationStore.putWithSummary(stored, caller)
        val summary = owner.localStore.operations().single { it.id == stored.id }
        assertEquals(OperationStatus.SUCCESS, summary.status)
        assertEquals(OperationTaskPersistence.summaryTitleFor(TaskState.SUCCEEDED), summary.title)
        assertEquals(OperationTaskPersistence.summaryDetailFor(TaskState.SUCCEEDED), summary.detail)
        assertFalse(summary.title.contains(marker))
        assertFalse(summary.detail.contains(marker))
        // The stored task detail is the fixed public text rather than the caller's.
        assertEquals(
            OperationTaskPersistence.summaryDetailFor(TaskState.SUCCEEDED),
            owner.operationStore.get(stored.id)!!.safeDetail,
        )
    }

    @Test
    fun removingAConnectionClearsOnlyItsOwnTasks() {
        val owner = owner()
        owner.operationStore.put(task(id = "task-1", connectionId = "connection-1", accountId = "account-1"))
        val other = task(id = "task-2", connectionId = "connection-2", accountId = "account-2")
        owner.operationStore.put(other)
        owner.localStore.removeConnection("connection-1")
        assertNull(owner.operationStore.get("task-1"))
        assertEquals(
            other.copy(safeDetail = "结果待核对，请刷新后检查"),
            owner.operationStore.get("task-2"),
        )
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
        // It creates its own driver which AndroidLocalStore does not expose for closing,
        // so this legacy path keeps the pre-existing lifecycle limitation (documented in
        // the report); the shared-owner path above is the production wiring.
        val legacy = AndroidLocalStore(context)
        assertNotNull(legacy.operations())
        val connection = Connection("connection-legacy", "Legacy", "account-legacy")
        owner().localStore.putConnection(connection)
        assertTrue(owner().localStore.connections().any { it.id == connection.id })
    }
}
