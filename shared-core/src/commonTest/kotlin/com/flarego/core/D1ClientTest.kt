package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class D1ClientTest {
    private val connection=Connection("local","fixture","a".repeat(32))
    private val account=AccountScope(connection.id,connection.accountId)
    private val ref=D1DatabaseRef(account,"12345678-1234-4321-abcd-123456789abc")
    private val base="/accounts/${account.accountId}/d1/database"
    private val path="$base/${ref.id}"
    private val requests=mutableListOf<Pair<String,String>>()
    private val bodies=mutableListOf<String>()
    private val guards=mutableListOf<String>()
    private var response=HttpResponse(200,"""{"success":true,"result":{}}""")
    private var stream="""{"success":true,"result":[{"success":true,"results":[]}]}"""
    private var streamStatus=200
    private var sourceClosed=false
    private fun client(guard:(String,AccountScope,String?)->Unit={op,_,_->guards+=op}):CloudflareD1Client {
        fun observe(request:HttpRequest){
            requests+=request.method to request.path
            when(val body=request.payload){
                is HttpBody.SensitiveJson->bodies+=body.consumeBytes().decodeToString()
                is HttpBody.Stream->{val b=ByteArray(8192);val out=java.io.ByteArrayOutputStream();try{while(true){val n=body.source.read(b);if(n == -1)break;out.write(b,0,n)}}finally{body.source.close();sourceClosed=true};bodies+=out.toString("UTF-8")}
                is HttpBody.Text->bodies+=body.value
                else->Unit
            }
        }
        return CloudflareD1Client(connection,CloudflareGateway(HttpTransport{observe(it);response},sleeper={}),object:TransferPort{
            override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead{
                observe(request);val bytes=stream.encodeToByteArray()
                try{if(streamStatus==200)sink.write(bytes,0,bytes.size)}finally{sink.close()}
                return HttpResponseHead(streamStatus,emptyMap(),if(streamStatus==200)bytes.size.toLong() else 0)
            }
        },D1MemoryStore(),guard)
    }
    private fun result(json:String){response=HttpResponse(200,"""{"success":true,"result":$json}""")}
    private fun details(name:String="fixture")="""{"uuid":"${ref.id}","name":"$name","read_replication":{"mode":"auto"},"file_size":21,"new_field":{"kept":true}}"""

    @Test fun directoryAndDetailUseNumericPagingAndOwnedUuid()=runTest{
        result("[${details()}]")
        val page=client().listDatabases(account,D1PageRequest(2,10,"a+b"))
        assertEquals(ref,page.items.single().ref)
        assertEquals("$base?page=2&per_page=10&name=a%2Bb",requests.single().second)
        result(details());val info=client().getDatabase(ref)
        assertEquals(21L,info.fileSize);assertEquals(D1Replication.auto,info.replication)
        assertEquals("$path",requests.last().second)
        assertFailsWith<CloudFailure>{client().getDatabase(ref.copy(account=AccountScope("foreign",account.accountId)))}
    }
    @Test fun createPutPatchDeleteAndUndeleteSendExactTypedBodiesAndGuards()=runTest{
        result(details());val api=client()
        assertEquals(ref,api.createDatabase(account,D1CreateInput("new",D1Jurisdiction.eu,D1Location.wnam,D1Replication.auto)).ref)
        assertEquals("""{"name":"new","jurisdiction":"eu","primary_location_hint":"wnam","read_replication":{"mode":"auto"}}""",bodies.last())
        api.updateDatabase(ref,D1Replication.disabled);assertEquals("PUT",requests.last().first)
        api.patchDatabase(ref,null);assertEquals("{}",bodies.last());assertEquals("PATCH",requests.last().first)
        result("null");api.deleteDatabase(ref);assertEquals("DELETE" to path,requests.last())
        result(details());api.undeleteDatabase(ref,"restored");assertEquals("POST" to "$path/undelete",requests.last())
        assertEquals("""{"new_name":"restored"}""",bodies.last())
        assertEquals(listOf("d1-create-database","d1-update-database","d1-update-partial-database","d1-delete-database","d1-undelete-database"),guards)
    }
    @Test fun missingCreationIdentityAndWrongResponseIdentityAreUncertain()=runTest{
        result("""{"name":"same-name"}""")
        assertTrue(assertFailsWith<CloudFailure>{client().createDatabase(account,D1CreateInput("same-name"))}.uncertain)
        result(details().replace(ref.id,"99999999-1234-4321-abcd-123456789abc"))
        assertFailsWith<CloudFailure>{client().getDatabase(ref)}
        assertTrue(assertFailsWith<CloudFailure>{client().updateDatabase(ref,D1Replication.auto)}.uncertain)
    }
    @Test fun arbitrarySqlUsesOneShotStreamWithGuardWhileBrowseCannotAcceptSql()=runTest{
        val api=client();val sql="/* ; */ WITH t AS (SELECT ';') SELECT * FROM t; PRAGMA user_version=2;"
        val input=D1SqlJson.fromStatements(listOf(D1Statement(sql,listOf("001"))),D1MemoryStore(),false)
        val result=api.query(ref,input)
        assertEquals(D1Acceptance.COMPLETE,result.summary.acceptance)
        assertEquals(sql,Json.parseToJsonElement(bodies.last()).jsonObject["sql"]?.jsonPrimitive?.content)
        assertEquals(listOf("d1-query-database"),guards);assertTrue(sourceClosed)
        api.browse(ref,D1BrowseRequest.columns("a\"b"))
        assertEquals(1,guards.size)
        assertEquals("SELECT * FROM pragma_table_info(?)",Json.parseToJsonElement(bodies.last()).jsonObject["sql"]?.jsonPrimitive?.content)
        stream="""{"success":true,"result":[{"success":true,"results":{"columns":["a"],"rows":[[1]]}}]}"""
        api.raw(ref,input);assertEquals("POST" to "$path/raw",requests.last())
        assertEquals("d1-raw-database-query",guards.last())
    }
    @Test fun queryGuardFailureHasNoSendAndUnrecognizedReplyIsUncertainWithoutRetry()=runTest{
        val input=D1SqlJson.fromStatements(listOf(D1Statement("SELECT 1")),D1MemoryStore(),false)
        assertFailsWith<CloudFailure>{client{_,_,_->throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"denied")}.query(ref,input)}
        assertTrue(requests.isEmpty())
        streamStatus=503
        assertTrue(assertFailsWith<CloudFailure>{client().query(ref,input)}.uncertain)
        assertEquals(1,requests.size)
        streamStatus=200;stream="private-marker-invalid-json"
        val failure=assertFailsWith<CloudFailure>{client().query(ref,input)}
        assertTrue(failure.uncertain);assertFalse(failure.toString().contains("private-marker"))
    }
    @Test fun importExportAndTimeTravelPreserveExactPhaseAndLocator()=runTest{
        val api=client()
        result("""{"success":true,"type":"import","filename":"private.sql","upload_url":"https://fixture.r2.cloudflarestorage.com/key?sig=private"}""")
        val init=api.importDatabase(ref,D1ImportRequest.Init("0".repeat(32)))
        assertNotNull(init.upload);assertFalse(init.toString().contains("private"))
        api.importDatabase(ref,D1ImportRequest.Ingest("0".repeat(32),"private.sql"))
        assertEquals("""{"action":"ingest","etag":"${"0".repeat(32)}","filename":"private.sql"}""",bodies.last())
        api.importDatabase(ref,D1ImportRequest.Poll("bookmark/+="))
        assertEquals("""{"action":"poll","current_bookmark":"bookmark/+="}""",bodies.last())
        result("""{"success":true,"type":"export","at_bookmark":"start"}""")
        api.exportDatabase(ref,D1ExportOptions(emptyList(),noData=true))
        assertEquals("""{"output_format":"polling","dump_options":{"no_data":true,"no_schema":false,"tables":[]}}""",bodies.last())
        api.pollExport(ref,"start")
        assertEquals("""{"output_format":"polling","current_bookmark":"start"}""",bodies.last())
        result("""{"bookmark":"at-time"}""")
        assertEquals("at-time",api.getBookmark(ref,"2026-10-25T00:00:00Z"))
        assertEquals("$path/time_travel/bookmark?timestamp=2026-10-25T00%3A00%3A00Z",requests.last().second)
        result("""{"bookmark":"after","previous_bookmark":"before"}""")
        assertEquals("before",api.restore(ref,D1RestorePoint.Bookmark("at-time")).previousBookmark)
        assertEquals("POST" to "$path/time_travel/restore?bookmark=at-time",requests.last())
    }
    @Test fun onlyFileOperationsAdmitOfficial202ActiveAndPreserveErrorReceipts()=runTest{
        response=HttpResponse(202,"""{"success":true,"result":{"success":true,"type":"export","status":"active","at_bookmark":"same"}}""")
        assertEquals(D1FileStatus.PROCESSING,client().exportDatabase(ref,D1ExportOptions()).status)
        response=HttpResponse(202,"""{"success":true,"result":{"success":true,"type":"import","status":"active","at_bookmark":"same"}}""")
        assertEquals(D1FileStatus.PROCESSING,client().importDatabase(ref,D1ImportRequest.Poll("same")).status)
        result("""{"success":false,"type":"import","status":"error","at_bookmark":"same","error":"private-marker"}""")
        val error=client().importDatabase(ref,D1ImportRequest.Poll("same"))
        assertEquals(D1FileStatus.ERROR,error.status);assertFalse(error.toString().contains("private-marker"))
        response=HttpResponse(202,"""{"success":true,"result":${details()}}""")
        assertTrue(assertFailsWith<CloudFailure>{client().updateDatabase(ref,D1Replication.auto)}.uncertain)
        assertFailsWith<CloudFailure>{client().getDatabase(ref)}
    }
    @Test fun completedImportRequiresFinalBookmarkAndConsistentReceipt()=runTest {
        val api=client()
        for (body in listOf(
            """{"success":true,"type":"import","status":"complete"}""",
            """{"success":true,"type":"import","status":"complete","result":{"final_bookmark":"done","num_queries":-1}}""",
            """{"success":true,"type":"import","status":"complete","error":"private-error","result":{"final_bookmark":"done"}}"""
        )) { result(body); assertTrue(assertFailsWith<CloudFailure>{api.importDatabase(ref,D1ImportRequest.Poll("same"))}.uncertain) }
        result("""{"success":true,"type":"import","status":"complete","result":{"final_bookmark":"done","num_queries":2,"meta":{"changes":3},"future":"private-marker"}}""")
        assertEquals(D1FileStatus.COMPLETE,api.importDatabase(ref,D1ImportRequest.Poll("same")).status)
    }
}
