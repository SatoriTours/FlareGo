package com.flarego

import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.cache.ReadSnapshot
import com.flarego.core.model.*
import com.flarego.platform.LocalDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadSnapshotDatabaseTest {
    @Test fun diskSnapshotSurvivesCloseAndIsDeletedWithAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "read-snapshot-test.db"
        val account = AccountScope("cache-test", "account")
        try {
            LocalDatabase.open(context, name).use { db ->
                db.localStore.putConnection(Connection(account.connectionId,"Test",account.accountId))
                db.readSnapshots.write(account, "directory-v1/ZONES/default", ReadSnapshot("{\"items\":[]}", System.currentTimeMillis()))
            }
            LocalDatabase.open(context, name).use { db ->
                assertNotNull(db.readSnapshots.read(account,"directory-v1/ZONES/default"))
                assertNull(db.readSnapshots.read(AccountScope("other","account"),"directory-v1/ZONES/default"))
                db.readSnapshots.removeConnection(account.connectionId)
                db.readSnapshots.write(account,"directory-v1/ZONES/default",ReadSnapshot("old",System.currentTimeMillis())) { false }
                assertNull(db.readSnapshots.read(account,"directory-v1/ZONES/default"))
                db.localStore.removeConnection(account.connectionId)
                assertNull(db.readSnapshots.read(account,"directory-v1/ZONES/default"))
                // A late writer cannot resurrect an account that was removed.
                db.readSnapshots.write(account,"directory-v1/ZONES/default", ReadSnapshot("{}",System.currentTimeMillis()))
                assertNull(db.readSnapshots.read(account,"directory-v1/ZONES/default"))
            }
        } finally { context.deleteDatabase(name) }
    }
}
