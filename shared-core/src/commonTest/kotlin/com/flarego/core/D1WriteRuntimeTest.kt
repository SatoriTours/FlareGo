package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.security.MessageDigest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class D1WriteRuntimeTest {
    private val connection=Connection("local","fixture","a".repeat(32))
    private val account=AccountScope(connection.id,connection.accountId)
    private val database=D1DatabaseRef(account,"12345678-1234-4321-abcd-123456789abc")
    private fun deletion()=D1Mutation.Delete(database)
    private inner class Harness(val scope:TestScope) {
        var liveConnection = connection
        var command:D1Mutation?=null;var epoch=0L;var revision=1L;var now=1000L;var serial=0;var writes=0
        var writeStatus=200;var partial=false;var createdId:String?=database.id;var receivedRunning=false
        var acquire:suspend()->Unit={};var beforeGet:suspend()->Unit={};var beforeWire:()->Unit={};var afterSend:suspend()->Unit={}
        var fileActive=false;var filePolls=0;var readBookmark="resolved-bookmark";var restoreQuery:String?=null;var receiverThrows=false
        val tasks=linkedMapOf<String,OperationTask>();val results=mutableListOf<D1ResultDocument>()
        val store=object:OperationStore {
            override fun get(id:String)=tasks[id]
            override fun list(connectionId:String)=tasks.values.filter{it.connectionId==connectionId}
            override fun put(task:OperationTask){tasks[task.id]=task}
            override fun removeConnection(connectionId:String)=Unit
            override fun putWithSummary(task:OperationTask,summary:Operation)=put(task)
        }
        val contentStore=D1MemoryStore()
        fun newRuntime()=D1WriteRuntime(scope.backgroundScope,store,StandardTestDispatcher(scope.testScheduler),
            FingerprintPort{MessageDigest.getInstance("SHA-256").digest(it).joinToString(""){b->"%02x".format(b)}},
            OperationClock{now},OperationIds{"id-${++serial}"},
            {D1LiveSelection(liveConnection,revision,epoch,command)},{listOf(liveConnection)},{revision},
            {_,guard->
                acquire()
                CloudflareD1Client(liveConnection,CloudflareGateway(HttpTransport{request->
                    fun response(value:String)=HttpResponse(if(request.method=="GET")200 else writeStatus,"""{"success":true,"result":$value}""")
                    if(request.method=="GET"){
                        beforeGet()
                        if(request.path.contains("/bookmark"))response("""{"bookmark":"$readBookmark"}""")
                        else response("""{"uuid":"${database.id}","name":"fixture","read_replication":{"mode":"auto"}}""")
                    } else {
                        writes++;receivedRunning=tasks.values.any{it.state==TaskState.RUNNING};afterSend()
                        when {
                            request.path.endsWith("/import") -> {
                                val body=request.payload as HttpBody.SensitiveJson
                                if(body.consumeBytes().decodeToString().contains("\"poll\""))filePolls++
                                response(if(fileActive)"""{"success":true,"type":"import","status":"active","at_bookmark":"same"}"""else """{"success":true,"type":"import","status":"complete","at_bookmark":"same","result":{"final_bookmark":"final","meta":{"changes":1},"num_queries":1}}""")
                            }
                            request.path.contains("/restore?") ->{restoreQuery=request.path;response("""{"bookmark":"after","previous_bookmark":"before"}""")}
                            createdId==null ->response("""{"name":"new"}""")
                            else ->response("""{"uuid":"$createdId","name":"new"}""")
                        }
                    }
                },sleeper={}),object:TransferPort{
                    override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead{
                        writes++;receivedRunning=tasks.values.any{it.state==TaskState.RUNNING}
                        (request.payload as HttpBody.Stream).source.close();afterSend()
                        val bytes=(if(partial)"""{"success":true,"result":[{"success":true,"results":[]},{"success":false,"error":"private-marker"}]}"""else """{"success":true,"result":[{"success":true,"results":[{"private-marker":9223372036854775807}]}]}""").encodeToByteArray()
                        if(writeStatus==200)sink.write(bytes,0,bytes.size);sink.close()
                        return HttpResponseHead(writeStatus,emptyMap(),if(writeStatus==200)bytes.size.toLong()else 0)
                    }
                },contentStore,{_,_,_->beforeWire();guard()})
            },
            {_,api,onLocator->D1FilePipeline(api,object:D1SignedTransferPort{
                override suspend fun upload(location:D1UploadLocation,content:D1Content,md5:String)=error("unexpected upload")
                override suspend fun download(location:D1DownloadLocation,sink:ByteSink):Long=error("unexpected download")
            },contentStore,{now},sleeper={now+=it},onLocator=onLocator)},
            {_,result->if(receiverThrows)throw IllegalStateException("private-owner-fixture");results+=result;true})
        val runtime=newRuntime()
        suspend fun prepare(cmd:D1Mutation=deletion()):D1ConfirmationView {command=cmd;return runtime.prepare(cmd)}
        suspend fun final(cmd:D1Mutation=deletion()):D1ConfirmationView {
            val risk=prepare(cmd);return runtime.acceptTyped(runtime.acceptRisk(risk.handle).handle,cmd.databaseId?:account.accountId)
        }
        suspend fun sql():D1Mutation.Sql=D1Mutation.Sql(database,D1SqlJson.fromStatements(listOf(D1Statement("SELECT 'private-marker'; PRAGMA user_version=3;",listOf("private-param"))),contentStore,false))
    }
    @Test fun everyWriteRequiresThreeIssuedStagesAndExactIdentityWithDurableRunningBeforeSend()=runTest {
        val h=Harness(this);val p=h.prepare()
        assertEquals(D1ConfirmationStage.RISK,p.handle.stage)
        assertFailsWith<CloudFailure>{h.runtime.submit(p.handle)}
        val typed=h.runtime.acceptRisk(p.handle);assertFailsWith<CloudFailure>{h.runtime.acceptRisk(p.handle)}
        assertFailsWith<CloudFailure>{h.runtime.acceptTyped(typed.handle," ${database.id}")}
        val final=h.runtime.acceptTyped(typed.handle,database.id)
        assertEquals(0,h.writes);assertEquals(TaskState.SUCCEEDED,h.runtime.submit(final.handle).task.state)
        assertEquals(1,h.writes);assertTrue(h.receivedRunning)
        assertEquals(D1ConfirmationStage.RISK,h.prepare(D1Mutation.Create(account,D1CreateInput("new"))).handle.stage)
    }
    @Test fun staleDraftEpochCredentialRevocationExpiryAndLatePreflightRefuseWrite()=runTest {
        for(change in listOf<(Harness)->Unit>({it.command=deletion()},{it.epoch++},{it.revision++},{it.runtime.revoke()},{it.now+=300000})){
            val h=Harness(this);val p=h.final();change(h);assertFailsWith<CloudFailure>{h.runtime.submit(p.handle)};assertEquals(0,h.writes)
        }
        val h=Harness(this);h.beforeGet={h.epoch++};assertFailsWith<CloudFailure>{h.prepare()};assertEquals(0,h.writes)
        val late=Harness(this);val p=late.final();late.beforeWire={late.epoch++};late.runtime.submit(p.handle);assertEquals(0,late.writes)
    }
    @Test fun inputReleasedAtCloudTerminalWhilePrivateResultRemainsReadableOutsideTask()=runTest {
        val h=Harness(this);val sql=h.sql();val p=h.final(sql)
        val task=h.runtime.submit(p.handle).task
        assertEquals(TaskState.SUCCEEDED,task.state);assertTrue((sql.input.content as D1MemoryContent).closed);assertFails{sql.input.content.openVerified()}
        val result=h.results.single();assertTrue(d1Text(result.content).contains("private-marker"))
        for(public in listOf(task.toString(),p.toString(),sql.toString())){assertFalse(public.contains("private-marker"));assertFalse(public.contains("private-param"))}
        assertNull(task.desiredHash);assertEquals(ResourceRef(account,D1TaskTargets.DATABASE,database.id),task.resourceRef)
        result.close()
    }
    @Test fun unknownAndPartialBlockEveryActionAcrossRuntimeRecreationUntilExplicitManualClosure()=runTest {
        for(partial in listOf(false,true)) {
            val h=Harness(this);h.partial=partial;h.writeStatus=if(partial)200 else 503
            val result=h.runtime.submit(h.final(h.sql()).handle)
            assertEquals(if(partial)TaskState.PARTIAL else TaskState.UNKNOWN,result.task.state)
            assertFailsWith<CloudFailure>{h.prepare(D1Mutation.PutReplication(database,D1Replication.disabled))}
            val recreated=h.newRuntime();h.command=deletion();assertFailsWith<CloudFailure>{recreated.prepare(h.command!!)}
            assertFailsWith<CloudFailure>{recreated.closeUncertain(result.task.id,"wrong",true)}
            assertEquals(TaskState.ACTION_REQUIRED,recreated.closeUncertain(result.task.id,database.id,true).state)
            h.results.forEach{it.close()}
        }
    }
    @Test fun duplicateFinalSharesOneSendAndDatabaseWideGateRejectsConcurrentDifferentAction()=runTest {
        val h=Harness(this);val p=h.final();val sent=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.afterSend={sent.complete(Unit);release.await()}
        val first=async{h.runtime.submit(p.handle)};sent.await();val second=async{h.runtime.submit(p.handle)};runCurrent()
        assertEquals(1,h.writes);assertFailsWith<CloudFailure>{h.prepare(D1Mutation.PutReplication(database,D1Replication.auto))}
        release.complete(Unit);assertEquals(first.await().task.id,second.await().task.id);assertEquals(1,h.writes)
    }
    @Test fun timestampRestoreResolvesAndFreezesBookmarkBeforeConfirmation()=runTest {
        val h=Harness(this);val p=h.final(D1Mutation.Restore(database,D1RestorePoint.Timestamp("2026-10-25T00:00:00Z")))
        assertEquals("2026-10-25T00:00:00Z",p.restoreTimestamp);assertEquals("resolved-bookmark",p.restoreBookmark)
        h.readBookmark="later";val result=h.runtime.submit(p.handle)
        assertTrue(h.restoreQuery!!.endsWith("?bookmark=resolved-bookmark"));assertEquals("before",result.restore?.previousBookmark)
    }
    @Test fun missingCreateUuidIsUnknownAndNeverAdoptedByName()=runTest {
        val h=Harness(this);h.createdId=null
        val result=h.runtime.submit(h.final(D1Mutation.Create(account,D1CreateInput("new"))).handle)
        assertEquals(TaskState.UNKNOWN,result.task.state);assertNull(result.created)
        assertFailsWith<CloudFailure>{h.prepare(D1Mutation.Create(account,D1CreateInput("new")))}
    }
    @Test fun processingImportPersistsOnlyReviewedLocatorAndExplicitContinuePollsWithoutInit()=runTest {
        val h=Harness(this);h.fileActive=true
        val input=D1ImportFile.prepare(d1Content("CREATE TABLE x(a);"),D1ContentFingerprint{"0".repeat(32)})
        val result=h.runtime.submit(h.final(D1Mutation.Import(database,input)).handle)
        assertEquals(TaskState.ACCEPTED,result.task.state);assertNotNull(result.task.pollPath);assertEquals(30,h.filePolls)
        assertFailsWith<CloudFailure>{h.prepare()};assertFails{input.content.openVerified()}
        h.fileActive=false;h.command=null
        val before=h.writes
        val done=h.newRuntime().continueFileTask(result.task.id)
        assertEquals(TaskState.SUCCEEDED,done.state);assertNull(done.pollPath);assertEquals(before+1,h.writes);assertEquals(31,h.filePolls)
    }
    @Test fun resultArrivingAfterRevocationIsClosedAndNeverDelivered()=runTest {
        val h=Harness(this);val p=h.final(h.sql());h.afterSend={h.epoch++;h.runtime.revoke()}
        h.runtime.submit(p.handle)
        assertTrue(h.results.isEmpty())
    }
    @Test fun resultOwnerFailureCannotRewriteKnownCloudAcceptance()=runTest {
        val h=Harness(this);h.receiverThrows=true
        val result=h.runtime.submit(h.final(h.sql()).handle)
        assertEquals(TaskState.SUCCEEDED,result.task.state);assertEquals(1,h.writes);assertTrue(h.results.isEmpty())
    }
    @Test fun preparingSameOwnedCommandAgainRevokesOldHandleWithoutClosingItsInput()=runTest {
        val h=Harness(this);val command=h.sql();val first=h.final(command);val second=h.final(command)
        assertFailsWith<CloudFailure>{h.runtime.submit(first.handle)}
        assertEquals(TaskState.SUCCEEDED,h.runtime.submit(second.handle).task.state)
        assertEquals(1,h.writes);h.results.forEach{it.close()}
    }
    @Test fun createConfirmationFreezesChosenReplication()=runTest {
        val h=Harness(this)
        val view=h.prepare(D1Mutation.Create(account,D1CreateInput("new",D1Jurisdiction.eu,D1Location.weur,D1Replication.auto)))
        assertEquals(D1Replication.auto,view.newReplication)
        assertEquals(D1Jurisdiction.eu,view.createJurisdiction);assertEquals(D1Location.weur,view.createLocation)
    }
    @Test fun trustedSqlCompletionIsSucceededRatherThanPendingAcceptance()=runTest {
        val h=Harness(this);val result=h.runtime.submit(h.final(h.sql()).handle)
        assertEquals(TaskState.SUCCEEDED,result.task.state);assertNull(result.task.pollPath)
        assertEquals(1,h.writes);assertEquals(1,h.results.size)
    }
    @Test fun confirmationFreezesConnectionIdentityAndRejectsSameAccountConnectionChanges()=runTest {
        val h=Harness(this);val first=h.final()
        assertEquals(connection.name,first.connectionLabel);assertEquals(connection.id,first.connectionId)
        h.liveConnection=connection.copy(name="renamed fixture")
        assertEquals(connection.name,first.connectionLabel)
        assertFailsWith<CloudFailure>{h.runtime.submit(first.handle)};assertEquals(0,h.writes)
        h.liveConnection=connection.copy(id="second-local",name="second fixture")
        val secondAccount=AccountScope(h.liveConnection.id,connection.accountId)
        val second=h.final(D1Mutation.Delete(D1DatabaseRef(secondAccount,database.id)))
        assertEquals(first.account.accountId,second.account.accountId)
        assertEquals("second-local",second.connectionId);assertEquals("second fixture",second.connectionLabel)
        assertNotEquals(first.connectionId,second.connectionId)
        assertFailsWith<CloudFailure>{h.runtime.submit(first.handle)};assertEquals(0,h.writes)
        assertEquals(TaskState.SUCCEEDED,h.runtime.submit(second.handle).task.state);assertEquals(1,h.writes)
    }
}
