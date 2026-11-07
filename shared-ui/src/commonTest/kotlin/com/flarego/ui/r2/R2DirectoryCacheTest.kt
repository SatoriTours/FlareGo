package com.flarego.ui.r2

import com.flarego.core.application.cache.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2DirectoryCacheTest {
    @Test fun realPaginationWithoutTotalsCannotDeleteUnseenCachedBucketAndOfflineRestartKeepsIt() = runTest {
        val owner=Connection("c","fixture","a")
        val ctx=R2BrowserContext(owner,1,1)
        val rows=mutableMapOf<String,ReadSnapshot>()
        val disk=object:ReadSnapshotStore {
            override suspend fun read(account:AccountScope,key:String)=rows[key]
            override suspend fun write(account:AccountScope,key:String,value:ReadSnapshot,isCurrent:()->Boolean){if(isCurrent())rows[key]=value}
            override suspend fun removeConnection(connectionId:String){rows.clear()}
        }
        val cache=ResourceDirectorySnapshots(disk)
        cache.save(ctx.account,"R2",listOf(DirectoryRow("one","one"),DirectoryRow("two","two")),true,1000)
        var requests=0;var offline=false;var now=2000L
        val api=CloudflareR2Client(owner,CloudflareGateway(HttpTransport {
            requests++
            if(offline)throw CloudFailure(CloudFailureCode.UNAVAILABLE,"offline")
            HttpResponse(200,"""{"success":true,"result":{"buckets":[{"name":"one"}]}}""")
        }),object:TransferPort{
            override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("not used")
        })
        fun browser()=R2Browser(backgroundScope,{ctx},null,cache=cache,nowMs={now},session={api})
        val first=browser();first.refreshContext();runCurrent()
        assertEquals(0,requests)
        assertEquals(listOf("one","two"),first.state.value.buckets.rows.map{it.name})
        first.onBucketIntent(R2BucketIntent.Refresh);runCurrent()
        assertEquals(1,requests)
        assertEquals(listOf("one","two"),cache.restore(ctx.account,"R2",now)!!.rows.map{it.name})
        offline=true;now=400_000
        val reopened=browser();reopened.refreshContext();runCurrent()
        assertEquals(listOf("one","two"),reopened.state.value.buckets.rows.map{it.name})
        assertTrue(reopened.state.value.fromCache)
        assertNotNull(reopened.state.value.buckets.error)
    }
}
