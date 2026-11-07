package com.flarego.core.application.cache

import com.flarego.core.model.AccountScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Safe resource-directory columns only; no object bytes, KV values, SQL or credentials. */
@Serializable
data class DirectoryRow(val id: String, val name: String, val location: String? = null,
    val storageClass: String? = null, val createdAt: String? = null, val fileSize: Long? = null,
    val replication: String? = null, val supportsUrlEncoding: Boolean? = null)
@Serializable
data class DirectorySnapshot(val rows: List<DirectoryRow>, val observedAtMs: Long)
class ResourceDirectorySnapshots(private val store: ReadSnapshotStore) {
    suspend fun restore(account: AccountScope, kind: String, now: Long): DirectorySnapshot? = safely {
        require(kind in setOf("R2","KV","D1"))
        val record=store.read(account,"browser-v1/$kind")?.takeIf { it.usable(now) } ?: return@safely null
        Json.decodeFromString<DirectorySnapshot>(record.payload).copy(observedAtMs=record.observedAtMs)
    }
    suspend fun save(account: AccountScope, kind: String, rows: List<DirectoryRow>, complete: Boolean, now: Long, isCurrent: () -> Boolean = { true }) { safely {
        require(kind in setOf("R2","KV","D1"))
        val all=if(complete) rows else merge(restore(account,kind,now)?.rows.orEmpty(),rows) { it.id }
        store.write(account,"browser-v1/$kind",ReadSnapshot(Json.encodeToString(DirectorySnapshot(all,now)),now),isCurrent)
    } }
}

/** A missing next token is not proof that pagination metadata was provided. */
fun directoryComplete(next: com.flarego.core.model.PageToken?, total: Long?, count: Int) = next==null && total!=null && total==count.toLong()
