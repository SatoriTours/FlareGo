package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class D1FilePipelineTest {
    private val account=AccountScope("local","a".repeat(32))
    private val ref=D1DatabaseRef(account,"12345678-1234-4321-abcd-123456789abc")
    private val calls=mutableListOf<String>()
    private val receipts=ArrayDeque<D1FileReceipt>()
    private val store=D1MemoryStore()
    private val transfers=object:D1SignedTransferPort{
        override suspend fun upload(location:D1UploadLocation,content:D1Content,md5:String){calls+="upload"}
        override suspend fun download(location:D1DownloadLocation,sink:ByteSink):Long{
            calls+="download";val bytes="CREATE TABLE x(a);".encodeToByteArray();sink.write(bytes,0,bytes.size);sink.close();return bytes.size.toLong()
        }
    }
    private val unused:D1Session get()=CloudflareD1Client(Connection("local","fixture",account.accountId),CloudflareGateway(HttpTransport{error("unexpected API")}),object:TransferPort{
        override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("unexpected SQL")
    },store,{_,_,_->error("unexpected write")})
    private val session:D1Session get()=object:D1Session by unused{
        override suspend fun importDatabase(database:D1DatabaseRef,request:D1ImportRequest):D1FileReceipt{
            assertEquals(ref,database)
            calls+=when(request){is D1ImportRequest.Init->"init";is D1ImportRequest.Ingest->"ingest";is D1ImportRequest.Poll->"poll:${request.bookmark}"}
            return receipts.removeFirst()
        }
        override suspend fun exportDatabase(database:D1DatabaseRef,options:D1ExportOptions):D1FileReceipt{calls+="export";return receipts.removeFirst()}
        override suspend fun pollExport(database:D1DatabaseRef,bookmark:String):D1FileReceipt{calls+="export-poll:$bookmark";return receipts.removeFirst()}
    }
    private fun receipt(status:D1FileStatus,bookmark:String?="same",upload:Boolean=false,download:Boolean=false)=D1FileReceipt(
        status,bookmark,if(upload)D1UploadLocation("private.sql","https://fixture.invalid/key?private",ref)else null,
        if(download)D1DownloadLocation("private.sql","https://fixture.invalid/key?private",ref)else null,buildJsonObject{},ref,
    )
    private suspend fun input()=D1ImportFile.prepare(d1Content("CREATE TABLE x(a);"),D1ContentFingerprint{"0".repeat(32)})

    @Test fun initAlreadyUploadedBranchPollsOnlyAndNeverUploadsOrIngests()=runTest{
        receipts+=receipt(D1FileStatus.PROCESSING);receipts+=receipt(D1FileStatus.COMPLETE)
        val result=D1FilePipeline(session,transfers,store,{testScheduler.currentTime},sleeper={delay(it)}).startImport(ref,input())
        assertEquals(D1Acceptance.COMPLETE,result.acceptance);assertEquals(listOf("init","poll:same"),calls)
    }
    @Test fun completedImportHandsFullPrivateReceiptToIndependentResultOwner()=runTest {
        val details=Json.parseToJsonElement("""{"success":true,"type":"import","status":"complete","result":{"final_bookmark":"private-final","meta":{"changes":2},"future":"kept"}}""").jsonObject
        receipts+=D1FileReceipt(D1FileStatus.COMPLETE,null,details=details,database=ref)
        val outcome=D1FilePipeline(session,transfers,store,{0},sleeper={}).startImport(ref,input())
        assertFalse(outcome.toString().contains("private-final"));val result=assertNotNull(outcome.takeReport());outcome.close()
        assertEquals(details.toString(),d1Text(result));result.close();assertNull(outcome.takeReport())
    }
    @Test fun immediateCompleteDoesNotPollAndUploadBranchIngestsOnce()=runTest{
        receipts+=receipt(D1FileStatus.COMPLETE)
        val pipeline=D1FilePipeline(session,transfers,store,{testScheduler.currentTime},sleeper={delay(it)})
        pipeline.startImport(ref,input());assertEquals(listOf("init"),calls);calls.clear()
        receipts+=receipt(D1FileStatus.UPLOAD,null,upload=true);receipts+=receipt(D1FileStatus.PROCESSING);receipts+=receipt(D1FileStatus.COMPLETE)
        pipeline.startImport(ref,input());assertEquals(listOf("init","upload","ingest","poll:same"),calls)
    }
    @Test fun foregroundPollBudgetReturnsLocatorAndExplicitContinueOnlyPollsSameJob()=runTest{
        receipts+=receipt(D1FileStatus.PROCESSING);repeat(30){receipts+=receipt(D1FileStatus.PROCESSING)}
        val saved=mutableListOf<D1FileLocator>()
        val pipeline=D1FilePipeline(session,transfers,store,{testScheduler.currentTime},sleeper={delay(it)},onLocator={saved+=it})
        val pending=pipeline.startImport(ref,input())
        assertEquals(D1Acceptance.PROCESSING,pending.acceptance);assertEquals(30,calls.count{it=="poll:same"})
        val locator=assertNotNull(pending.locator);assertTrue(saved.isNotEmpty());assertFalse(locator.toString().contains("same"))
        receipts+=receipt(D1FileStatus.COMPLETE)
        val done=pipeline.continuePoll(locator)
        assertEquals(D1Acceptance.COMPLETE,done.acceptance);assertEquals(1,calls.count{it=="init"});assertEquals(31,calls.count{it=="poll:same"})
    }
    @Test fun wallBudgetStopsBeforeNextPollAndBookmarkDriftDoesNotRestart()=runTest{
        var now=0L
        receipts+=receipt(D1FileStatus.PROCESSING)
        val pending=D1FilePipeline(session,transfers,store,{now},sleeper={now+=120000}).startImport(ref,input())
        assertEquals(D1Acceptance.PROCESSING,pending.acceptance);assertEquals(listOf("init"),calls)
        calls.clear();receipts+=receipt(D1FileStatus.PROCESSING);receipts+=receipt(D1FileStatus.PROCESSING,"different")
        assertTrue(assertFailsWith<CloudFailure>{D1FilePipeline(session,transfers,store,{testScheduler.currentTime},sleeper={delay(it)}).startImport(ref,input())}.uncertain)
        assertEquals(listOf("init","poll:same"),calls)
    }
    @Test fun failedUploadNeverIngestsAndMalformedNextPhaseNeverRestartsInit()=runTest{
        receipts+=receipt(D1FileStatus.UPLOAD,null,upload=true)
        val fail=object:D1SignedTransferPort by transfers {
            override suspend fun upload(location:D1UploadLocation,content:D1Content,md5:String){calls+="upload";throw CloudFailure(CloudFailureCode.UNAVAILABLE,"fixture")}
        }
        assertFailsWith<CloudFailure>{D1FilePipeline(session,fail,store,{0},sleeper={}).startImport(ref,input())}
        assertEquals(listOf("init","upload"),calls)
        calls.clear();receipts+=receipt(D1FileStatus.UPLOAD,null,upload=true);receipts+=receipt(D1FileStatus.UPLOAD,null,upload=true)
        assertTrue(assertFailsWith<CloudFailure>{D1FilePipeline(session,transfers,store,{0},sleeper={}).startImport(ref,input())}.uncertain)
        assertEquals(listOf("init","upload","ingest"),calls)
    }
    @Test fun exportUsesFrozenBookmarkAndDownloadsCompleteSqlWithIndependentOwner()=runTest{
        receipts+=receipt(D1FileStatus.PROCESSING);receipts+=receipt(D1FileStatus.COMPLETE,download=true)
        val pipeline=D1FilePipeline(session,transfers,store,{testScheduler.currentTime},sleeper={delay(it)})
        val result=pipeline.startExport(ref,D1ExportOptions())
        assertEquals(listOf("export","export-poll:same"),calls)
        assertEquals(D1Acceptance.COMPLETE,result.acceptance)
        val content=pipeline.downloadExport(result);assertEquals("CREATE TABLE x(a);",d1Text(content));content.close()
        assertEquals(listOf("export","export-poll:same","download"),calls)
    }
    @Test fun failedDownloadCannotRewriteKnownCloudCompletionOrRestartExport()=runTest {
        receipts+=receipt(D1FileStatus.COMPLETE,download=true)
        val failing=object:D1SignedTransferPort by transfers{
            override suspend fun download(location:D1DownloadLocation,sink:ByteSink):Long{sink.close();throw CloudFailure(CloudFailureCode.UNAVAILABLE,"fixture")}
        }
        val pipeline=D1FilePipeline(session,failing,store,{0},sleeper={})
        val cloud=pipeline.startExport(ref,D1ExportOptions())
        assertEquals(D1Acceptance.COMPLETE,cloud.acceptance)
        assertFailsWith<CloudFailure>{pipeline.downloadExport(cloud)}
        assertEquals(D1Acceptance.COMPLETE,cloud.acceptance);assertEquals(listOf("export"),calls)
    }
    @Test fun privateImportInputRejectsOversizeBeforeHashAndReleasesOnValidationFailure()=runTest{
        var hashes=0
        val bad=d1Content("\u0000") as D1MemoryContent
        assertFailsWith<CloudFailure>{D1ImportFile.prepare(bad,D1ContentFingerprint{hashes++;"0".repeat(32)})}
        assertEquals(0,hashes);assertTrue(bad.closed)
        val valid=input();assertFalse(valid.toString().contains("000000"));valid.close()
        val oversized=object:D1Content {
            var closed=false
            override val descriptor=D1ContentDescriptor("fixture",D1_MAX_IMPORT_BYTES+1)
            override suspend fun openVerified():ByteSource=error("oversize must not be read")
            override fun close(){closed=true}
        }
        assertFailsWith<CloudFailure>{D1ImportFile.prepare(oversized,D1ContentFingerprint{hashes++;"0".repeat(32)})}
        assertTrue(oversized.closed);assertEquals(0,hashes)
    }
}
