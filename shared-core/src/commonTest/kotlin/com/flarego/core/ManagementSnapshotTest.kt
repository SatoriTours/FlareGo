package com.flarego.core

import com.flarego.core.application.cache.*
import com.flarego.core.application.dns.*
import com.flarego.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.test.*

class ManagementSnapshotTest {
    private val account = AccountScope("c", "a")
    private val zone = ZoneScope(account,"z")
    private val rows = mutableMapOf<Pair<AccountScope,String>,ReadSnapshot>()
    private val disk = object : ReadSnapshotStore {
        override suspend fun read(account: AccountScope, key: String) = rows[account to key]
        override suspend fun write(account: AccountScope, key: String, value: ReadSnapshot, isCurrent: () -> Boolean) { if(isCurrent()) rows[account to key] = value }
        override suspend fun removeConnection(connectionId: String) { rows.keys.removeAll { it.first.connectionId == connectionId } }
    }
    @Test fun revokedSaveCannotResurrectSnapshotAfterSuspendedMergeRead() = runTest {
        val started=kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume=kotlinx.coroutines.CompletableDeferred<Unit>()
        var current=true
        var writes=0
        val delayed=object:ReadSnapshotStore {
            override suspend fun read(account:AccountScope,key:String):ReadSnapshot? { started.complete(Unit);resume.await();return null }
            override suspend fun write(account:AccountScope,key:String,value:ReadSnapshot,isCurrent:()->Boolean) { if(isCurrent()) writes++ }
            override suspend fun removeConnection(connectionId:String) { writes=0 }
        }
        val saving=launch {
            ManagementSnapshots(delayed).saveZones(account,emptyList(),null,false,1000) { current }
        }
        started.await();current=false;delayed.removeConnection(account.connectionId);resume.complete(Unit);saving.join()
        assertEquals(0,writes)
    }

    @Test fun zonesRestoreDisplayMetadataButNotRawProviderBodies() = runTest {
        val cache = ManagementSnapshots(disk)
        val detail = ZoneDetails(zone,"example.com","active",false,"full",listOf("ns.example.com"), buildJsonObject { put("secret","private") })
        cache.saveZones(account,listOf(detail),1,true,1000)
        val restored = cache.zones(account,2000)!!
        assertEquals("example.com",restored.items.single().name)
        assertTrue(restored.items.single().raw.isEmpty())
        assertFalse(rows.values.single().payload.contains("private"))
        assertNull(cache.zones(AccountScope("other","a"),2000))
        assertNull(cache.zones(account,SNAPSHOT_RETENTION_MS+2000))
    }
    @Test fun dnsCacheExcludesTxtValuesAndNeverRestoresWritableFields() = runTest {
        val cache = ManagementSnapshots(disk)
        cache.saveDns(zone,"example.com", listOf(
            DnsRecordView("txt","TXT","example.com","private-value",300,false,null,buildJsonObject { put("content","private-value") }),
            DnsRecordView("a","A","example.com","192.0.2.1",300,false,null,JsonObject(emptyMap()))
        ),2,true,1000)
        val restored = cache.dns(zone,2000)!!
        assertNull(restored.rows.first().content)
        assertEquals("192.0.2.1",restored.rows.last().content)
        assertTrue(restored.rows.all { it.fields.isEmpty() })
        assertFalse(rows.values.single().payload.contains("private-value"))
    }
}
