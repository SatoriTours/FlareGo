package com.flarego

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.platform.LocalDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Real migration chain coverage for the persistent commit gate.
 *
 * The gate was appended as migration 4 (v4 -> v5): migrations 1/2/3 keep their historical
 * meaning (task, dns_batch_*, read_snapshot), so an installed v2 or v4 database must upgrade
 * without losing data and end up equivalent to a fresh install.
 */
class CredentialGateMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "gate-migration-test.db"
    private val opened = mutableListOf<LocalDatabase>()

    private fun owner(): LocalDatabase = LocalDatabase.open(context, databaseName).also { opened += it }

    private fun raw(version: Int, script: SQLiteDatabase.() -> Unit) {
        val database = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        try {
            script(database)
            database.version = version
        } finally {
            database.close()
        }
    }

    private fun tableNames(): Set<String> {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY,
        )
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

    private fun scalar(sql: String): String? {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            database.rawQuery(sql, null).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
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

    private val connectionDdl = """
        CREATE TABLE connection (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, account_id TEXT NOT NULL);
    """.trimIndent()
    private val operationDdl = """
        CREATE TABLE operation (
            sequence INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL UNIQUE, connection_id TEXT NOT NULL,
            title TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL);
    """.trimIndent()
    private val taskDdl = """
        CREATE TABLE task (
            id TEXT NOT NULL PRIMARY KEY, connection_id TEXT NOT NULL, scope_kind TEXT NOT NULL,
            scope_connection_id TEXT NOT NULL, account_id TEXT, zone_id TEXT, user_id TEXT,
            organization_id TEXT, action_key TEXT NOT NULL, resource_kind TEXT, resource_external_id TEXT,
            request_fingerprint TEXT NOT NULL, desired_hash TEXT, state TEXT NOT NULL,
            safe_detail TEXT NOT NULL, provider_request_id TEXT, poll_path TEXT,
            created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL);
    """.trimIndent()
    private val batchEvidenceDdl = """
        CREATE TABLE dns_batch_evidence (
            task_id TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL, connection_id TEXT NOT NULL,
            account_id TEXT NOT NULL, zone_id TEXT NOT NULL, delete_count INTEGER NOT NULL,
            patch_count INTEGER NOT NULL, put_count INTEGER NOT NULL, post_count INTEGER NOT NULL,
            manifest_hash TEXT NOT NULL, transaction_receipt TEXT NOT NULL);
    """.trimIndent()
    private val batchItemDdl = """
        CREATE TABLE dns_batch_item (
            task_id TEXT NOT NULL, ordinal INTEGER NOT NULL, kind TEXT NOT NULL, item_index INTEGER NOT NULL,
            record_id TEXT, create_type TEXT, create_name TEXT, desired_hash TEXT NOT NULL,
            before_hash TEXT, provider_id TEXT, creation_precondition_version INTEGER,
            PRIMARY KEY(task_id, ordinal));
    """.trimIndent()
    private val snapshotDdl = """
        CREATE TABLE read_snapshot (
            connection_id TEXT NOT NULL, account_id TEXT NOT NULL, cache_key TEXT NOT NULL,
            payload TEXT NOT NULL, observed_at_ms INTEGER NOT NULL,
            PRIMARY KEY (connection_id, account_id, cache_key));
    """.trimIndent()

    /** A v2 install already has migration 1 applied, so the task table exists. */
    private fun SQLiteDatabase.seedV2() {
        execSQL(connectionDdl); execSQL(operationDdl); execSQL(taskDdl)
        execSQL("INSERT INTO connection(id, name, account_id) VALUES ('conn-1', '旧账号', 'a1')")
        execSQL("INSERT INTO operation(id, connection_id, title, status, detail) VALUES ('op-1', 'conn-1', 'DNS 变更', 'SUCCESS', '历史记录')")
    }

    /** A v4 install has every table up to read_snapshot; the gate table does not exist yet. */
    private fun SQLiteDatabase.seedV4() {
        seedV2()
        execSQL(batchEvidenceDdl); execSQL(batchItemDdl); execSQL(snapshotDdl)
        execSQL("INSERT INTO task(id, connection_id, scope_kind, scope_connection_id, action_key, request_fingerprint, state, safe_detail, created_at_ms, updated_at_ms) VALUES ('task-1','conn-1','ACCOUNT','conn-1','zones-post','f','QUEUED','等待',1,2)")
        execSQL("INSERT INTO dns_batch_evidence(task_id, version, connection_id, account_id, zone_id, delete_count, patch_count, put_count, post_count, manifest_hash, transaction_receipt) VALUES ('task-1',1,'conn-1','a1','zone-1',1,2,3,4,'manifest-hash','receipt')")
        execSQL("INSERT INTO read_snapshot(connection_id, account_id, cache_key, payload, observed_at_ms) VALUES ('conn-1','a1','zones','payload-1',7)")
    }

    @Test
    fun anInstalledV4DatabaseGainsTheGateWithoutLosingAnything() {
        raw(4) { seedV4() }
        val db = owner()
        // Force the migration by touching the schema through the real driver.
        assertTrue(db.localStore.connections().any { it.id == "conn-1" })
        val tables = tableNames()
        assertTrue("gate table must be added", tables.contains("credential_gate"))
        for (legacy in listOf("connection", "operation", "task", "dns_batch_evidence", "dns_batch_item", "read_snapshot")) {
            assertTrue("legacy table $legacy must survive", tables.contains(legacy))
        }
        assertEquals("旧账号", db.localStore.connections().single().name)
        assertEquals(1, db.localStore.operations().size)
        assertEquals("manifest-hash", scalar("SELECT manifest_hash FROM dns_batch_evidence WHERE task_id='task-1'"))
        assertEquals("QUEUED", scalar("SELECT state FROM task WHERE id='task-1'"))
        assertEquals("payload-1", scalar("SELECT payload FROM read_snapshot WHERE connection_id='conn-1'"))
        // The gate starts empty for an existing install: no false block, no fabricated pending row.
        assertNull(scalar("SELECT operation_id FROM credential_gate WHERE connection_id='conn-1'"))
    }

    @Test
    fun aV2DatabaseUpgradesThroughTheWholeChain() {
        raw(2) { seedV2() }
        val db = owner()
        assertEquals("旧账号", db.localStore.connections().single().name)
        val tables = tableNames()
        // 1.sqm(task), 2.sqm(dns_batch), 3.sqm(read_snapshot) and 4.sqm(gate) all ran.
        for (expected in listOf("task", "dns_batch_evidence", "dns_batch_item", "read_snapshot", "credential_gate")) {
            assertTrue("chain must create $expected", tables.contains(expected))
        }
        assertEquals(1, db.localStore.operations().size)
        assertEquals("SUCCESS", scalar("SELECT status FROM operation WHERE id='op-1'"))
    }

    @Test
    fun aMigratedDatabaseIsEquivalentToAFreshInstall() {
        raw(2) { seedV2() }
        owner().localStore.connections()
        val migrated = tableNames()

        opened.forEach { it.close() }
        opened.clear()
        context.deleteDatabase(databaseName)

        owner().localStore.connections()
        val fresh = tableNames()
        assertEquals(fresh, migrated)

        val gateColumns = mutableListOf<String>()
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            database.rawQuery("PRAGMA table_info(credential_gate)", null).use { cursor ->
                while (cursor.moveToNext()) gateColumns += cursor.getString(1)
            }
        } finally {
            database.close()
        }
        assertEquals(
            listOf("connection_id", "operation_id", "account_id", "state", "updated_at_ms"),
            gateColumns,
        )
    }
}
