package com.flarego.core
import com.flarego.core.application.cache.*
import com.flarego.core.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
class ResourceDirectorySnapshotTest {
    @Test fun omittedPaginationDoesNotProveDeletionOrCompleteness() {
        assertFalse(directoryComplete(null,null,1))
        assertFalse(directoryComplete(PageToken.Number(2),2,1))
        assertTrue(directoryComplete(null,0,0))
        assertTrue(directoryComplete(null,2,2))
    }
    @Test fun partialRefreshKeepsUnseenRowsAndCompleteRefreshRemovesDeletedRows() = runTest {
        val a=AccountScope("c","a")
        val data=mutableMapOf<String,ReadSnapshot>()
        val disk=object:ReadSnapshotStore {
            override suspend fun read(account:AccountScope,key:String)=data[key]
            override suspend fun write(account:AccountScope,key:String,value:ReadSnapshot,isCurrent:()->Boolean){if(isCurrent())data[key]=value}
            override suspend fun removeConnection(connectionId:String){data.clear()}
        }
        val cache=ResourceDirectorySnapshots(disk)
        cache.save(a,"KV",listOf(DirectoryRow("1","one"),DirectoryRow("2","two")),true,1000)
        cache.save(a,"KV",listOf(DirectoryRow("1","renamed")),false,2000)
        assertEquals(listOf("renamed","two"),cache.restore(a,"KV",3000)!!.rows.map{it.name})
        cache.save(a,"KV",emptyList(),true,4000)
        assertTrue(cache.restore(a,"KV",5000)!!.rows.isEmpty())
    }
}
