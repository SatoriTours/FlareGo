package com.flarego.core

import com.flarego.core.application.inventory.*
import com.flarego.core.application.operations.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class AppOperationRuntimeTest {
    private val connection=Connection("local","Account","account")
    private val zone=ZoneScope(AccountScope(connection.id,connection.accountId),"zone")
    private val domain=Domain("zone","example.com","active",connection.id)
    private val draft=DnsDraft("A","www.example.com","192.0.2.1")
    private val fingerprint=FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){ "%02x".format(it) } }
    private var appState=AppState(listOf(connection),connection,page=Page.DNS,domain=domain,
        products=mapOf(ProductKey.ZONES to ProductLoadState(ProductContent.Zones(listOf(domain)),null,1,false,null,zone.account,0,10,true)))
    private var epoch=0L
    private var rev=0L
    private var writes=0
    private var reads=0
    private var failList=false
    private var acquire: suspend ()->Unit = {}
    private var send: suspend ()->Unit = {}
    private var records=listOf(DnsRecord("record",draft.type,draft.name,draft.content,1))
    private var published=emptyList<OperationTask>()
    private val store=object:OperationStore {
        val rows=linkedMapOf<String,OperationTask>()
        override fun get(id:String)=rows[id]
        override fun list(connectionId:String):List<OperationTask> { if(failList) error("metadata read failed");return rows.values.filter { it.connectionId==connectionId } }
        override fun put(task:OperationTask) { rows[task.id]=task }
        override fun putWithSummary(task:OperationTask,summary:Operation) { assertEquals(OperationTaskPersistence.summaryFor(task),summary);put(task) }
        override fun removeConnection(connectionId:String) { rows.entries.removeAll { it.value.connectionId==connectionId } }
    }
    private fun TestScope.runtime():AppOperationRuntime {
        var id=0
        return AppOperationRuntime(backgroundScope,store,StandardTestDispatcher(testScheduler),{appState},{epoch},{rev},SessionFactory {
            acquire()
            object:CloudSession by DemoSession(connection) {
                override suspend fun save(zoneId:String,recordId:String?,draft:DnsDraft) { writes++;send() }
                override suspend fun delete(zoneId:String,recordId:String) { writes++;send() }
                override suspend fun records(zoneId:String):List<DnsRecord> { reads++;return records }
            }
        },fingerprint,OperationClock {20},OperationIds {"id-${++id}"},{published=it})
    }
    private fun row(id:String,state:TaskState)=OperationTask(id,connection.id,zone,DnsOperationLifecycle.ACTION_PATCH,ResourceRef(zone,"dns_record","record"),"a".repeat(64),null,state,"安全摘要",null,null,1,1)
    @Test fun recoveryNeverReplaysAndPublishesCanonicalStates() = runTest {
        store.put(row("running",TaskState.RUNNING));store.put(row("queued",TaskState.QUEUED))
        val runtime=runtime();runtime.recover()
        assertEquals(TaskState.UNKNOWN,store.get("running")?.state);assertEquals(TaskState.ACTION_REQUIRED,store.get("queued")?.state)
        assertEquals(2,published.size);assertEquals(0,writes);assertEquals(0,reads)
        assertEquals(OperationTaskPersistence.summaryDetailFor(TaskState.UNKNOWN),published.first {it.id=="running"}.safeDetail)
    }
    @Test fun unitWriteIsPersistedAsUnknownThenMatchingPatchCanBeReconciledWithoutReplay() = runTest {
        val runtime=runtime(); val task=runtime.update(zone,"record",draft)
        assertEquals(1,writes);assertEquals(TaskState.UNKNOWN,task.state);assertEquals(task,store.get(task.id))
        runtime.reconcile(task.id); assertEquals(1,writes);assertEquals(1,reads);assertEquals(TaskState.SUCCEEDED,store.get(task.id)?.state)
    }
    @Test fun createWithoutProviderIdentityStaysUnknownAfterReadOnlyCheck() = runTest {
        val runtime=runtime();val task=runtime.create(zone,draft);runtime.reconcile(task.id)
        assertEquals(1,writes);assertEquals(1,reads);assertEquals(TaskState.UNKNOWN,store.get(task.id)?.state)
    }
    @Test fun missingZoneOwnershipRefusesBeforeAnySessionOrWrite() = runTest {
        appState=appState.copy(products=emptyMap());val runtime=runtime()
        assertFailsWith<CloudFailure> {runtime.create(zone,draft)};assertEquals(0,writes);assertTrue(store.rows.isEmpty())
    }
    @Test fun navigationDuringFreshSessionAcquisitionRefusesSendEvenAfterReturn() = runTest {
        val gate=CompletableDeferred<Unit>();acquire={gate.await()};val runtime=runtime()
        val result=async {runtime.create(zone,draft)};runCurrent();epoch+=2;gate.complete(Unit);runCurrent()
        assertEquals(TaskState.RUNNING,result.await().state);assertEquals(0,writes)
    }
    @Test fun tokenReplacementDuringAcquisitionRefusesSend() = runTest {
        val gate=CompletableDeferred<Unit>();acquire={gate.await()};val runtime=runtime()
        val result=async {runtime.create(zone,draft)};runCurrent();rev++;gate.complete(Unit);runCurrent()
        assertEquals(TaskState.RUNNING,result.await().state);assertEquals(0,writes)
    }
    @Test fun removalAndLateReceiptCannotRecreateDeletedMetadata() = runTest {
        val gate=CompletableDeferred<Unit>();send={gate.await()};val runtime=runtime()
        val result=async {runtime.create(zone,draft)};runCurrent();assertEquals(1,writes)
        runtime.removeConnection(connection.id) { store.removeConnection(connection.id);appState=AppState(emptyList(),null) }
        gate.complete(Unit);runCurrent();result.await();assertTrue(store.rows.isEmpty());assertTrue(published.isEmpty())
    }
    @Test fun cancellationOfUiWaiterDoesNotResendOrLoseMetadata() = runTest {
        val gate=CompletableDeferred<Unit>();send={gate.await()};val runtime=runtime()
        val waiter=launch {runtime.create(zone,draft)};runCurrent();waiter.cancel();gate.complete(Unit);runCurrent()
        assertEquals(1,writes);assertEquals(TaskState.UNKNOWN,store.rows.values.single().state);assertEquals(1,published.size)
    }
    @Test fun postWriteListFailureCannotReplaceTheAuthoritativeUnknownResult() = runTest {
        send={failList=true}
        val task=runtime().create(zone,draft)
        assertEquals(1,writes);assertEquals(TaskState.UNKNOWN,task.state)
        assertEquals(task,store.get(task.id))
    }

}
