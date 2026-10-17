package com.flarego

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.dns.CloudflareDnsFiles
import com.flarego.platform.AndroidFingerprint
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Real Application owner; synthetic token loader/HTTP factories only, never a real TokenVault. */
class ManagementPlatformOwnerIntegrationTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun connection()=Connection("b2a-"+UUID.randomUUID(),"Synthetic owner fixture","account")
    private fun transport()=HttpTransport { error("No HTTP expected for local owner admission") }
    private fun transfer()=object:TransferPort { override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("No download expected") }
    @Test fun applicationSingletonAndBorrowerReleaseKeepOtherBorrowerAlive()=runBlocking<Unit> {
        val one=ManagementPlatformOwner.forApplication(app);val two=ManagementPlatformOwner.forApplication(app)
        assertSame(one,two);assertSame(one.importLimiter,two.importLimiter);assertSame(one.files,two.files)
        val a=one.borrow();val b=two.borrow();one.releaseBorrower(a)
        val c=connection();one.registerExistingCredential(c)
        val bundle=two.acquireCredential(c,{"synthetic-not-a-real-token"},{transport()},{transfer()})
        two.requireCurrent(bundle);two.releaseBorrower(b)
        assertSame(one.importLimiter,ManagementPlatformOwner.forApplication(app).importLimiter)
    }
    @Test fun sameCredentialAcquisitionBuildsJsonStreamAndStableIssuerFromOneRead()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();val stamp=owner.registerExistingCredential(c)
        var reads=0;var json:String?=null;var stream:String?=null
        val a=owner.acquireCredential(c,{reads++;"synthetic-local-fixture"},{json=it;transport()},{stream=it;transfer()})
        val b=owner.acquireCredential(c,{reads++;"synthetic-local-fixture"},{transport()},{transfer()})
        assertEquals(2,reads);assertEquals(json,stream);assertEquals(stamp,a.stamp);assertSame(a.issuer,b.issuer)
        owner.requireCurrent(a);owner.requireCurrent(b)
    }
    @Test fun transitionRevokesOldBundleBeforeIoAndOnlyActivatesAfterPersist()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        val old=owner.acquireCredential(c,{"synthetic-old"},{transport()},{transfer()})
        val change=owner.beginCredentialChange(c)
        assertNull(owner.currentCredentialStamp(c));expectCloud { owner.requireCurrent(old) }
        var factoryCalls=0
        expectCloud { owner.acquireCredential(c,{"old vault bytes"},{factoryCalls++;transport()},{factoryCalls++;transfer()}) }
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val job=async { owner.finishCredentialChange(change) { entered.complete(Unit);release.await() } }
        entered.await();assertNull(owner.currentCredentialStamp(c));assertEquals(0,factoryCalls)
        release.complete(Unit);val fresh=job.await();assertTrue(fresh.revision>old.stamp.revision)
        val next=owner.acquireCredential(c,{"synthetic-new"},{transport()},{transfer()})
        assertNotSame(old.issuer,next.issuer);owner.requireCurrent(next);expectCloud { owner.requireCurrent(old) }
    }
    @Test fun suspendedOldReadCannotPublishTokenUnderNewRevision()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var factories=0
        val reading=async { runCatching { owner.acquireCredential(c,{entered.complete(Unit);release.await();"synthetic-stale"},{factories++;transport()},{factories++;transfer()}) } }
        entered.await();val change=owner.beginCredentialChange(c);owner.finishCredentialChange(change) {}
        release.complete(Unit);assertTrue(reading.await().exceptionOrNull() is CloudFailure);assertEquals(0,factories)
    }
    @Test fun failedPersistAndRetireStayUnavailableAndScrubSensitiveErrors()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        val change=owner.beginCredentialChange(c)
        val failure=expectCloud { owner.finishCredentialChange(change) { error("private-token-marker content://private") } }
        assertFalse(failure.toString().contains("private-token-marker"));assertNull(failure.cause);assertNull(owner.currentCredentialStamp(c))
        expectCloud { owner.registerExistingCredential(c) }
        val fresh=owner.beginCredentialChange(c);owner.finishCredentialChange(fresh) {}
        owner.retireCredential(c) {};assertNull(owner.currentCredentialStamp(c))
        expectCloud { owner.acquireCredential(c,{"synthetic-token"},{transport()},{transfer()}) }
    }
    @Test fun demoRefusesProductionAcquisitionBeforeCredentialAndFactories()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);var calls=0;val demo=connection().copy(demo=true)
        val local=owner.registerExistingCredential(demo)
        assertEquals(local,owner.currentCredentialStamp(demo))
        val localRequest=stamp(demo).copy(credential=local)
        val picker=DnsDocumentPicker(app) { localRequest }
        picker.begin(DnsDocumentKind.EXPORT,localRequest)
        assertNotNull(picker.parse(DnsDocumentKind.EXPORT,Activity.RESULT_OK,Intent().setData(document).setFlags(flags)))
        expectCloud { owner.acquireCredential(demo,{calls++;"synthetic"},{calls++;transport()},{calls++;transfer()}) }
        assertEquals(0,calls)
    }
    @Test fun overlappingReplacementCannotPublishNewStampOverLateOldVaultWrite()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        var stored="initial";val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val firstChange=owner.beginCredentialChange(c)
        val first=async { runCatching { owner.finishCredentialChange(firstChange) { entered.complete(Unit);release.await();stored="old-write" } } }
        entered.await()
        val second=runCatching { val change=owner.beginCredentialChange(c);owner.finishCredentialChange(change) { stored="new-write" } }
        // A different connection remains independent while this credential's IO is suspended.
        val other=connection();owner.registerExistingCredential(other)
        owner.finishCredentialChange(owner.beginCredentialChange(other)) {}
        release.complete(Unit);first.await()
        val current=owner.currentCredentialStamp(c)
        var loaded:String?=null
        if(current!=null) owner.acquireCredential(c,{stored.also { loaded=it }},{transport()},{transfer()})
        assertFalse("An active replacement stamp must never label a late old vault write",second.getOrNull()==current && loaded=="old-write")
        assertTrue("Overlapping replacement must fail before its persistence callback",second.exceptionOrNull() is CloudFailure)
    }
    @Test fun lateRetirementCannotDeleteNewlyActivatedCredential()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        var stored:String?="initial";val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val retirement=async { runCatching { owner.retireCredential(c) { entered.complete(Unit);release.await();stored=null } } }
        entered.await()
        val replacement=runCatching { owner.finishCredentialChange(owner.beginCredentialChange(c)) { stored="new-value" } }
        release.complete(Unit);retirement.await()
        assertFalse("Late removal must not leave an active new stamp with missing storage",owner.currentCredentialStamp(c)!=null && stored==null)
        assertTrue(replacement.exceptionOrNull() is CloudFailure)
        val reconnect=owner.beginCredentialChange(c);owner.finishCredentialChange(reconnect) { stored="explicit-reconnect" }
        owner.acquireCredential(c,{stored},{transport()},{transfer()})
    }
    @Test fun oneTransitionCannotRunPersistenceTwice()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        val change=owner.beginCredentialChange(c);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var writes=0
        val first=async { runCatching { owner.finishCredentialChange(change) { writes++;entered.complete(Unit);release.await() } } }
        entered.await()
        val repeated=runCatching { owner.finishCredentialChange(change) { writes++ } }
        release.complete(Unit);first.await()
        assertEquals("One issued transition admits one persistence callback",1,writes)
        assertTrue(repeated.exceptionOrNull() is CloudFailure)
    }
    @Test fun twoConnectionsAndBorrowerClearShareThreeSendRollingImportQuota()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val a=owner.borrow();val b=owner.borrow()
        val first=connection();val second=connection();owner.registerExistingCredential(first);owner.registerExistingCredential(second)
        val bytes="example.com. 300 IN A 192.0.2.1\n".toByteArray();val hash=AndroidFingerprint();var sends=0;var closes=0
        fun json()=HttpTransport { request ->
            val body=if(request.method=="POST") {
                sends++
                val source=((request.payload as HttpBody.Multipart).parts.single { it.name=="file" }.body as HttpBody.Stream).source
                val uploaded=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                try { while(true) { val n=source.read(buffer);if(n<0)break;uploaded.write(buffer,0,n) } } finally { source.close() }
                assertArrayEquals(bytes,uploaded.toByteArray())
                "{\"recs_added\":1,\"total_records_parsed\":1}"
            } else if(request.path.contains("dns_records")) "[]" else
                "{\"id\":\"zone\",\"name\":\"example.com\",\"type\":\"full\",\"status\":\"active\",\"paused\":false,\"account\":{\"id\":\"account\"}}"
            val pagination=if(body=="[]") ",\"result_info\":{\"page\":1,\"per_page\":20,\"count\":0,\"total_count\":0,\"total_pages\":1}" else ""
            HttpResponse(200,"{\"success\":true,\"errors\":[],\"messages\":[],\"result\":"+body+pagination+"}")
        }
        suspend fun session(c:Connection):CloudflareDnsFiles {
            val bundle=owner.acquireCredential(c,{"synthetic-quota-fixture"},{json()},{transfer()})
            return CloudflareDnsFiles(c,bundle.gateway,bundle.transfers,hash,owner.importLimiter,Dispatchers.IO,
                object:DnsFilesAuthority { override fun mayImport(scope:ZoneScope):Boolean=true }) // Synthetic quota fixture, not real permission evidence.
        }
        suspend fun send(c:Connection) {
            val input=object:ByteSource {
                var offset=0;var closed=false
                override fun read(buffer:ByteArray):Int { if(offset==bytes.size)return -1;val n=minOf(buffer.size,bytes.size-offset);bytes.copyInto(buffer,0,offset,offset+n);offset+=n;return n }
                override fun close() { if(!closed) { closed=true;closes++ } }
            }
            session(c).importRecords(ZoneScope(AccountScope(c.id,c.accountId),"zone"),input,bytes.size.toLong(),hash.sha256(bytes),false)
        }
        send(first);send(second);owner.releaseBorrower(a);send(second)
        val fourth=expectCloud { send(first) };assertEquals(CloudFailureCode.RATE_LIMITED,fourth.code)
        assertEquals(3,sends);assertEquals(4,closes);owner.releaseBorrower(b)
        assertSame(owner.importLimiter,ManagementPlatformOwner.forApplication(app).importLimiter)
    }
    @Test fun invalidatingPickerRevokesReturnedSelectionBeforeItCanBeClaimed() {
        val s=stamp(connection());val picker=DnsDocumentPicker(app) { s }
        picker.begin(DnsDocumentKind.IMPORT,s)
        val selected=requireNotNull(picker.parse(DnsDocumentKind.IMPORT,Activity.RESULT_OK,Intent().setData(document).setFlags(flags)))
        assertTrue(selected.stillCurrent!!.invoke())
        picker.invalidate()
        assertFalse("Invalidate must cover a delivered selection whose resolve has not finished",selected.stillCurrent!!.invoke())
    }
    @Test fun acquiredGatewayRetriesPastHttpDateReadImmediatelyButNeverReplaysWrite()=runBlocking<Unit> {
        val owner=ManagementPlatformOwner.forApplication(app);val c=connection();owner.registerExistingCredential(c)
        var reads=0;var writes=0
        val json=HttpTransport { request ->
            if(request.method=="GET") {
                reads++
                if(reads==1) HttpResponse(429,byteArrayOf(),mapOf("Retry-After" to listOf("Wed, 01 Jan 2020 00:00:00 GMT"))) else HttpResponse(200,"{}")
            } else { writes++;HttpResponse(429,byteArrayOf(),mapOf("Retry-After" to listOf("0"))) }
        }
        val bundle=owner.acquireCredential(c,{"synthetic-http-date-fixture"},{json},{transfer()})
        val read=withTimeout(500) { bundle.gateway.send("zones-0-get",HttpRequest("GET","/zones/zone")) }
        assertEquals(200,read.status);assertEquals(2,reads)
        val write=bundle.gateway.send("zones-0-patch",HttpRequest("PATCH","/zones/zone",HttpBody.Text("{}","application/json")))
        assertEquals(429,write.status);assertEquals(1,writes)
    }
    private fun stamp(c:Connection)=DnsDocumentRequestStamp(ZoneScope(AccountScope(c.id,c.accountId),"zone"),4,
        ManagementCredentialStamp(c.id,c.accountId,1,1),7,9)
    private val document get()=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/synthetic-document.bind")
    private val flags get()=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    @Test fun pickerBuildsOnlyFixedOpenCreateIntentsAndKeepsOneFlight() {
        val c=connection();val s=stamp(c);var current:DnsDocumentRequestStamp?=s
        val picker=DnsDocumentPicker(app) { current }
        val open=picker.begin(DnsDocumentKind.IMPORT,s)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,open.intent.action);assertEquals("*/*",open.intent.type)
        assertTrue(open.intent.hasCategory(Intent.CATEGORY_OPENABLE));assertTrue(picker.hasPending())
        runBlocking { expectCloud { picker.begin(DnsDocumentKind.EXPORT,s) } }
        assertNull(picker.parse(DnsDocumentKind.IMPORT,Activity.RESULT_CANCELED,null));assertFalse(picker.hasPending())
        val create=picker.begin(DnsDocumentKind.EXPORT,s)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,create.intent.action);assertEquals("text/plain",create.intent.type)
        assertEquals("zone.bind",create.intent.getStringExtra(Intent.EXTRA_TITLE))
        current=s.copy(operationEpoch=5)
        runBlocking { expectCloud { picker.parse(DnsDocumentKind.EXPORT,Activity.RESULT_OK,Intent().setData(document).setFlags(flags)) } }
    }
    @Test fun parserRequiresActualRequiredModesPersistableAndSingleConsistentDocument()=runBlocking<Unit> {
        val s=stamp(connection());val picker=DnsDocumentPicker(app) { s }
        val valid=picker.begin(DnsDocumentKind.IMPORT,s)
        val result=picker.parse(valid.kind,Activity.RESULT_OK,Intent().setData(document).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        assertNotNull(result)
        for(bits in listOf(0,Intent.FLAG_GRANT_READ_URI_PERMISSION,Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) {
            picker.begin(DnsDocumentKind.EXPORT,s)
            expectCloud { picker.parse(DnsDocumentKind.EXPORT,Activity.RESULT_OK,Intent().setData(document).setFlags(bits)) }
        }
        picker.begin(DnsDocumentKind.IMPORT,s)
        val multiple=Intent().setData(document).setFlags(flags).apply { clipData=ClipData.newRawUri("fixture",document).also { it.addItem(ClipData.Item(document.buildUpon().appendPath("different").build())) } }
        expectCloud { picker.parse(DnsDocumentKind.IMPORT,Activity.RESULT_OK,multiple) }
    }
    @Test fun invalidatedOldFlightCannotAttachToSameScopeNewEpochOrBeOverwritten()=runBlocking<Unit> {
        val s=stamp(connection());var current=s;val picker=DnsDocumentPicker(app) { current }
        picker.begin(DnsDocumentKind.IMPORT,s);picker.invalidate();current=s.copy(bindNonce=10)
        expectCloud { picker.begin(DnsDocumentKind.IMPORT,current) }
        expectCloud { picker.parse(DnsDocumentKind.IMPORT,Activity.RESULT_OK,Intent().setData(document).setFlags(flags)) }
        assertFalse(picker.hasPending());picker.begin(DnsDocumentKind.IMPORT,current)
        assertNull(picker.parse(DnsDocumentKind.IMPORT,Activity.RESULT_CANCELED,null))
    }
}
