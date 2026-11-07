package com.flarego.platform

import com.flarego.core.application.cache.*
import com.flarego.core.model.AccountScope
import com.flarego.db.FlareGoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded, app-private SQLite metadata. Database/file backups are excluded by the app manifest. */
class AndroidReadSnapshotStore internal constructor(private val database: FlareGoDatabase) : ReadSnapshotStore {
    private val lock = Mutex()
    override suspend fun read(account: AccountScope, key: String): ReadSnapshot? = withContext(Dispatchers.IO) {
        lock.withLock {
            prune()
            database.readSnapshotsQueries.snapshot(account.connectionId, account.accountId, key).executeAsOneOrNull()
                ?.let { ReadSnapshot(it.payload, it.observed_at_ms) }
        }
    }
    override suspend fun write(account: AccountScope, key: String, value: ReadSnapshot, isCurrent: () -> Boolean) = withContext(Dispatchers.IO) {
        // 64 x 128 KiB: cap encoded data at 8 MiB; oversized snapshots do not break reads.
        if (value.payload.encodeToByteArray().size > 128 * 1024) return@withContext
        lock.withLock {
            if (!isCurrent()) return@withLock
            database.transaction {
                database.readSnapshotsQueries.putSnapshot(account.connectionId, account.accountId, key, value.payload, value.observedAtMs)
                prune()
            }
        }
    }
    override suspend fun removeConnection(connectionId: String) = withContext(Dispatchers.IO) {
        lock.withLock { database.readSnapshotsQueries.removeConnectionSnapshots(connectionId); Unit }
    }
    private fun prune() {
        val now = System.currentTimeMillis()
        database.readSnapshotsQueries.removeExpired(now - SNAPSHOT_RETENTION_MS, now)
        database.readSnapshotsQueries.trimSnapshots()
    }
}
