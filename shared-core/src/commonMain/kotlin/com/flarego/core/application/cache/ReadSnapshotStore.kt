package com.flarego.core.application.cache

import com.flarego.core.model.AccountScope

/** Display-only data. Never persist credentials, capabilities, drafts or executable plans. */
data class ReadSnapshot(val payload: String, val observedAtMs: Long)
interface ReadSnapshotStore {
    suspend fun read(account: AccountScope, key: String): ReadSnapshot?
    suspend fun write(account: AccountScope, key: String, value: ReadSnapshot, isCurrent: () -> Boolean = { true })
    suspend fun removeConnection(connectionId: String)
}

const val SNAPSHOT_FRESH_MS = 5 * 60 * 1000L
const val SNAPSHOT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
fun ReadSnapshot.usable(now: Long) = observedAtMs <= now && now - observedAtMs <= SNAPSHOT_RETENTION_MS
