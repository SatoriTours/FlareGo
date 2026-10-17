package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.zones.*
import com.flarego.core.application.operations.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class ManagementRuntimeTest {
    private val connection = Connection("real", "Account", "account")
    private val account = AccountScope(connection.id, connection.accountId)
    private val zone = ZoneScope(account, "zone")
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun details(ref: ZoneScope = zone, name: String = "example.com") = ZoneDetails(ref,name,"pending",false,"full",listOf("ns.example.net"),obj("""{"id":"${ref.zoneId}","account":{"id":"${ref.account.accountId}"},"name":"$name","status":"pending","paused":false,"type":"full","name_servers":["ns.example.net"]}"""))
    private fun record(id: String = "one", type: String = "A", fields: JsonObject = obj("""{"content":"192.0.2.1","ttl":60,"comment":"old"}""")) = DnsRecordDocument(id,zone,JsonObject(fields + obj("""{"id":"$id","type":"$type","name":"www.example.com","modified_on":"yesterday"}""")))
    private inner class Harness(val scope: TestScope) {
        var state = AppState(listOf(connection),connection, page = Page.DNS, domain = Domain("zone","untrusted-parent.test","active",connection.id))
        var epoch = 0L; var revision = 0L; var ids = 0
        val tasks = linkedMapOf<String,OperationTask>()
        val store = object : OperationStore {
            override fun get(id:String) = tasks[id]
            override fun list(connectionId:String) = tasks.values.filter { it.connectionId == connectionId }
            override fun put(task:OperationTask) { tasks[task.id] = task }
            override fun putWithSummary(task:OperationTask,summary:Operation) { put(task) }
            override fun removeConnection(connectionId:String) { tasks.entries.removeAll { it.value.connectionId == connectionId } }
        }
        var zoneRow = details()
        var rows = listOf(record())
        var next: PageToken? = null
        var failRead = false
        var writes = 0
        var beforeRead: suspend () -> Unit = {}
        var acquire: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        var lastAction: DnsWriteAction? = null
        private val api = object : DnsManagementSession, ZoneManagementSession {
            override suspend fun zone(scope:ZoneScope):ZoneDetails { beforeRead(); if(failRead) throw CloudFailure(CloudFailureCode.NOT_FOUND,"safe read failure"); return zoneRow }
            override suspend fun get(scope:ZoneScope) = zone(scope)
            override suspend fun list(scope:ZoneScope,filter:DnsFilter,page:PageRequest):CloudPage<DnsRecordDocument> { beforeRead(); if(failRead) throw CloudFailure(CloudFailureCode.NOT_FOUND,"safe read failure"); return CloudPage(rows,next,rows.size.toLong()) }
            override suspend fun get(scope:ZoneScope,recordId:String) = rows.singleOrNull { it.id == recordId } ?: throw CloudFailure(CloudFailureCode.NOT_FOUND,"missing")
            override suspend fun list(account:AccountScope,page:PageRequest):CloudPage<ZoneDetails> { beforeRead(); if(failRead) throw CloudFailure(CloudFailureCode.NOT_FOUND,"safe read failure"); return CloudPage(listOf(zoneRow),next,1) }
            override suspend fun create(scope:ZoneScope,input:DnsRecordInput):DnsRecordDocument = error("use guarded write")
            override suspend fun patch(scope:ZoneScope,recordId:String,fields:JsonObject):DnsRecordDocument = error("use guarded write")
            override suspend fun replace(scope:ZoneScope,recordId:String,input:DnsRecordInput):DnsRecordDocument = error("use guarded write")
            override suspend fun delete(scope:ZoneScope,recordId:String) = error("use guarded write")
            override suspend fun create(account:AccountScope,input:ZoneCreateInput):ZoneDetails = error("use guarded write")
            override suspend fun edit(scope:ZoneScope,input:ZoneEditInput):ZoneDetails = error("use guarded write")
            override suspend fun delete(scope:ZoneScope) = error("use guarded write")
            override suspend fun activationCheck(scope:ZoneScope) = error("use guarded write")
            override suspend fun write(action:DnsWriteAction,beforeSend:(DnsRecordDocument?)->Unit):ExecutionReceipt {
                beforeWrite(); beforeSend(action.recordId?.let { id -> rows.single { it.id==id } }); writes++;lastAction=action
                rows = when(action.mode) {
                    DnsWriteMode.CREATE -> rows + DnsRecordDocument("provider-created",zone,JsonObject(dnsPayload(action.input!!)+("id" to JsonPrimitive("provider-created"))))
                    DnsWriteMode.PATCH -> rows.map { if(it.id==action.recordId) it.copy(raw=JsonObject(it.raw+dnsPayload(action.input!!))) else it }
                    DnsWriteMode.REPLACE -> rows.map { if(it.id==action.recordId) it.copy(raw=JsonObject(dnsPayload(action.input!!)+("id" to JsonPrimitive(it.id)))) else it }
                    DnsWriteMode.DELETE -> rows.filterNot { it.id==action.recordId }
                }
                afterWrite();return ExecutionReceipt.Completed()
            }
            override suspend fun write(action:ZoneAction,beforeSend:(ZoneDetails?)->Unit):ExecutionReceipt {
                beforeWrite();beforeSend(if(action is ZoneAction.Create) null else zoneRow);writes++
                zoneRow = when(action) {
                    is ZoneAction.Edit -> zoneRow.copy(paused=action.input.paused?:zoneRow.paused,raw=JsonObject(zoneRow.raw+zoneEditFields(action.input)))
                    is ZoneAction.Create -> details(ZoneScope(account,"new-zone"),action.input.name)
                    else -> zoneRow
                }
                try { afterWrite() } catch(_:Exception) { return ExecutionReceipt.Unknown() }; return ExecutionReceipt.Completed()
            }
        }
        fun runtime():AppOperationRuntime = AppOperationRuntime(scope.backgroundScope,store,StandardTestDispatcher(scope.testScheduler),{state},{epoch},{revision},SessionFactory { DemoSession(it) },FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },OperationClock { 100 }, OperationIds { "plan-${++ids}" },{},ManagementSessionFactory { acquire();ManagementServices(api,api) })
        val runtime by lazy { runtime() }
        val dns get() = runtime.management.dns
        val zones get() = runtime.management.zones
        fun load() { dns.onIntent(DnsIntent.Load(zone)) }
    }

    @Test fun dnsReadsFreshOwnedZoneNameInsteadOfParentDisplayName() = runTest {
        val h=Harness(this);h.load();runCurrent()
        assertEquals("example.com",h.dns.state.value.zoneName)
        assertEquals(listOf("one"),h.dns.state.value.rows.map { it.id })
        assertFalse(h.dns.state.value.loading)
    }
    @Test fun foreignProviderZoneCannotOpenDnsForm() = runTest {
        val h=Harness(this);h.zoneRow=details(ZoneScope(AccountScope("real","foreign"),"zone"));h.load();runCurrent()
        assertNull(h.dns.state.value.zoneName);assertNotNull(h.dns.state.value.error);assertTrue(h.dns.state.value.rows.isEmpty())
    }
    @Test fun oldAccountReadCannotPublishAfterSelectionChanges() = runTest {
        val h=Harness(this);val gate=CompletableDeferred<Unit>();h.beforeRead={gate.await()};h.load();runCurrent()
        h.state=h.state.copy(selected=Connection("other","Other","other"));h.epoch++;h.dns.onIntent(DnsIntent.Clear);gate.complete(Unit);runCurrent()
        assertNull(h.dns.state.value.scope);assertNull(h.dns.state.value.zoneName);assertTrue(h.dns.state.value.rows.isEmpty())
    }
    @Test fun paginationAppendsAndErrorsLeaveExistingRowsAvailable() = runTest {
        val h=Harness(this);h.next=PageToken.Number(2);h.load();runCurrent()
        h.rows=listOf(record("two"));h.next=null;h.dns.onIntent(DnsIntent.LoadMore);runCurrent()
        assertEquals(listOf("one","two"),h.dns.state.value.rows.map { it.id });assertNull(h.dns.state.value.next)
        h.failRead=true;h.dns.onIntent(DnsIntent.Load(zone));runCurrent()
        assertNotNull(h.dns.state.value.error);assertFalse(h.dns.state.value.loading)
    }
    @Test fun projectionKeepsRawUnknownAndMakesFutureTypeReadOnly() = runTest {
        val h=Harness(this);h.rows=listOf(record(fields=obj("""{"content":"192.0.2.1","future":"retain","data":{"future_nested":1}}""")),record("future","FUTURE"));h.load();runCurrent()
        val state=h.dns.state.value
        assertEquals("retain",state.records.first().raw["future"]?.jsonPrimitive?.content)
        assertFalse("future" in state.rows.first().fields);assertTrue("future" in state.rows.first().unknownFields)
        assertNotNull(state.rows.last().readOnlyReason)
    }
    @Test fun zoneListAndFreshDeleteEditorUseOwnedProviderName() = runTest {
        val h=Harness(this);h.zones.onIntent(ZoneIntent.Load(account));runCurrent()
        assertEquals("example.com",h.zones.state.value.zones.single().name)
        h.zoneRow=details(name="renamed.example.com");h.zones.onIntent(ZoneIntent.BeginDelete(zone));runCurrent()
        assertEquals("renamed.example.com",h.zones.state.value.editorTarget?.name)
        assertEquals(ZoneEditorMode.DELETE,h.zones.state.value.editorMode);assertNull(h.zones.state.value.draft);assertEquals(0,h.writes)
    }
    @Test fun invalidatingTextRetainsEditorRevisionButClearsOldPreview() = runTest {
        val h=Harness(this);h.load();runCurrent();h.dns.onIntent(DnsIntent.BeginEdit("one"));runCurrent()
        val before=h.dns.state.value
        h.dns.onIntent(DnsIntent.DraftInvalidated)
        assertEquals(before.editorRevision,h.dns.state.value.editorRevision)
        assertTrue(h.dns.state.value.revision>before.revision);assertNull(h.dns.state.value.preparedChange)
    }
    private val patch = DnsRecordInput("A","www.example.com",obj("""{"comment":"edited"}"""))
    private fun Harness.previewPatch() { dns.onIntent(DnsIntent.Preview(patch,DnsWriteMode.PATCH,"one")) }
    private fun Harness.confirm() { val p=dns.state.value.preparedChange!!;dns.onIntent(DnsIntent.Confirm(p.id,p.revision)) }
    @Test fun previewIsInertAndExplicitConfirmUsesDeltaAndRealProviderId() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        assertNotNull(h.dns.state.value.preparedChange);assertEquals(0,h.writes);assertTrue(h.tasks.isEmpty())
        h.confirm();runCurrent()
        assertEquals(1,h.writes);assertEquals(setOf("comment"),h.lastAction!!.input!!.fields.keys)
        assertEquals(TaskState.SUCCEEDED,h.dns.state.value.pendingTask?.state)
    }
    @Test fun draftTypeChangeAndForgedConfirmationCannotSend() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent();val p=h.dns.state.value.preparedChange!!
        h.dns.onIntent(DnsIntent.DraftChanged(DnsRecordInput("TXT","www.example.com",obj("""{"content":"new"}"""))))
        h.dns.onIntent(DnsIntent.Confirm(p.id,p.revision));runCurrent()
        assertEquals(0,h.writes);assertNull(h.dns.state.value.preparedChange)
        h.dns.onIntent(DnsIntent.Confirm("forged",h.dns.state.value.revision));runCurrent();assertEquals(0,h.writes)
    }
    @Test fun backPreservesDraftButCancelNeverSendsOrReplays() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        h.dns.onIntent(DnsIntent.BackToDraft);assertEquals(patch,h.dns.state.value.draft);assertNull(h.dns.state.value.preparedChange)
        h.previewPatch();runCurrent();h.dns.onIntent(DnsIntent.Cancel);runCurrent()
        assertEquals(0,h.writes);assertNull(h.dns.state.value.mode);assertNull(h.dns.state.value.draft)
    }
    @Test fun credentialsChangedDuringFinalAcquisitionRefuseAllWrites() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        val gate=CompletableDeferred<Unit>();h.acquire={gate.await()};h.confirm();runCurrent();h.revision++;gate.complete(Unit);runCurrent()
        assertEquals(0,h.writes);assertNotEquals(TaskState.SUCCEEDED,h.dns.state.value.pendingTask?.state)
    }
    @Test fun finalPreflightConflictIsVisibleAndZeroWrite() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        h.rows=listOf(record(fields=obj("""{"content":"192.0.2.2","ttl":60,"comment":"external"}""")))
        h.confirm();runCurrent()
        assertEquals(0,h.writes);assertTrue(h.dns.state.value.error.orEmpty().contains("CONFLICT"))
        assertEquals(TaskState.ACTION_REQUIRED,h.tasks.values.single().state)
    }
    @Test fun deleteRequiresFreshTypedNameAndActivationOnlyPreviews() = runTest {
        val h=Harness(this);h.zones.onIntent(ZoneIntent.Load(account));runCurrent();h.zones.onIntent(ZoneIntent.BeginDelete(zone));runCurrent()
        h.zones.onIntent(ZoneIntent.Preview(ZoneAction.Delete(zone,"example.com","example.com")));runCurrent()
        val p=h.zones.state.value.preparedChange!!
        h.zones.onIntent(ZoneIntent.Confirm(p.id,p.revision,"wrong.example.com"));runCurrent();assertEquals(0,h.writes)
        h.zones.onIntent(ZoneIntent.ActivationCheck(zone));runCurrent();assertNotNull(h.zones.state.value.preparedChange);assertEquals(0,h.writes)
    }
    @Test fun allProductWritesShareExclusionAndUiCancelDoesNotReplay() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        val gate=CompletableDeferred<Unit>();h.afterWrite={gate.await()};h.confirm();runCurrent();assertEquals(1,h.writes)
        h.zones.onIntent(ZoneIntent.Load(account));runCurrent()
        h.zones.onIntent(ZoneIntent.Preview(ZoneAction.Edit(zone,ZoneEditInput(paused=true))));runCurrent()
        assertNull(h.zones.state.value.preparedChange);assertNotNull(h.zones.state.value.error)
        h.dns.onIntent(DnsIntent.Cancel);gate.complete(Unit);runCurrent();assertEquals(1,h.writes)
    }
    @Test fun removalAndLateWriteCannotRecreateMetadata() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        val gate=CompletableDeferred<Unit>();h.afterWrite={gate.await()};h.confirm();runCurrent()
        h.runtime.removeConnection(connection.id) { h.store.removeConnection(connection.id);h.state=AppState(emptyList(),null) }
        gate.complete(Unit);runCurrent();assertEquals(1,h.writes);assertTrue(h.tasks.isEmpty())
    }
    @Test fun restoredUnknownUsesOnlyReadReconciliationAndCannotRestoreConfirmation() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent();val preview=h.dns.state.value.preparedChange!!
        h.afterWrite={throw IllegalStateException("lost receipt")};h.confirm();runCurrent()
        val task=h.tasks.values.single();assertEquals(TaskState.UNKNOWN,task.state)
        val rebuilt=h.runtime();rebuilt.management.dns.onIntent(DnsIntent.Load(zone));runCurrent()
        assertNull(rebuilt.management.dns.state.value.preparedChange)
        rebuilt.management.dns.onIntent(DnsIntent.Confirm(preview.id,preview.revision));runCurrent();assertEquals(1,h.writes)
        rebuilt.management.dns.onIntent(DnsIntent.Reconcile(task.id));runCurrent()
        assertEquals(TaskState.SUCCEEDED,h.tasks[task.id]?.state);assertEquals(1,h.writes)
    }

    @Test fun cancelDuringEditorReadCannotReopenAnAbandonedDraft() = runTest {
        val h=Harness(this);h.load();runCurrent();val gate=CompletableDeferred<Unit>();h.beforeRead={gate.await()}
        h.dns.onIntent(DnsIntent.BeginEdit("one"));runCurrent();h.dns.onIntent(DnsIntent.Cancel);gate.complete(Unit);runCurrent()
        assertNull(h.dns.state.value.mode);assertNull(h.dns.state.value.draft);assertFalse(h.dns.state.value.loading)
    }
    @Test fun demoUsesSameConfirmationPathWithoutRealSessionOrPersistentTasks() = runTest {
        val h=Harness(this);val demo=Connection("demo-production","示例","demo",true)
        h.state=AppState(listOf(demo),demo);h.acquire={error("demo must not acquire real credentials/session")}
        val target=ZoneScope(AccountScope(demo.id,demo.accountId),"zone-demo")
        h.dns.onIntent(DnsIntent.Load(target));runCurrent()
        assertTrue(h.dns.state.value.demo);assertEquals("flarego.dev",h.dns.state.value.zoneName)
        val input=DnsRecordInput("TXT","example.flarego.dev",obj("""{"content":"example only","ttl":60}"""))
        h.dns.onIntent(DnsIntent.BeginCreate("TXT"));h.dns.onIntent(DnsIntent.Preview(input,DnsWriteMode.CREATE));runCurrent()
        val p=h.dns.state.value.preparedChange!!;assertTrue(h.tasks.isEmpty())
        h.dns.onIntent(DnsIntent.Confirm(p.id,p.revision));runCurrent()
        assertEquals(TaskState.SUCCEEDED,h.dns.state.value.pendingTask?.state);assertTrue(h.tasks.isEmpty());assertEquals(0,h.writes)
        h.zones.onIntent(ZoneIntent.Load(target.account));runCurrent()
        assertTrue(h.zones.state.value.demo);assertTrue(h.zones.state.value.zones.any { it.name=="flarego.dev" })
    }
    @Test fun nestedPatchPreviewShowsMergedKnownFieldsAndPutStaysDistinct() = runTest {
        val h=Harness(this);h.rows=listOf(record(type="SRV",fields=obj("""{"ttl":60,"data":{"priority":1,"weight":2,"port":443,"target":"srv.example.com"}}""")));h.load();runCurrent()
        h.dns.onIntent(DnsIntent.Preview(DnsRecordInput("SRV","www.example.com",obj("""{"data":{"port":8443}}""")),DnsWriteMode.PATCH,"one"));runCurrent()
        val p=h.dns.state.value.preparedChange!!
        assertEquals(2,p.after!!.fields["data"]!!.jsonObject["weight"]!!.jsonPrimitive.int)
        assertEquals(8443,p.after!!.fields["data"]!!.jsonObject["port"]!!.jsonPrimitive.int)
        h.dns.onIntent(DnsIntent.BackToDraft);h.dns.onIntent(DnsIntent.BeginEdit("one",DnsWriteMode.REPLACE));runCurrent()
        assertEquals(DnsWriteMode.REPLACE,h.dns.state.value.mode);assertEquals(0,h.writes)
    }

    @Test fun successfulWriteReturnsToTaskSummaryAndRefreshesProviderRows() = runTest {
        val h=Harness(this);h.load();runCurrent();h.dns.onIntent(DnsIntent.BeginEdit("one"));runCurrent();h.previewPatch();runCurrent();h.confirm();runCurrent()
        assertEquals(TaskState.SUCCEEDED,h.dns.state.value.pendingTask?.state)
        assertNull(h.dns.state.value.mode);assertNull(h.dns.state.value.draft)
        assertEquals("edited",h.dns.state.value.rows.single().fields["comment"]!!.jsonPrimitive.content)
    }

    @Test fun appControllerNavigationImmediatelyClearsManagementAuthority() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent();assertNotNull(h.dns.state.value.preparedChange)
        val controller=com.flarego.core.application.AppController(backgroundScope,SessionFactory { DemoSession(it) },MemoryStore(),listOf(connection))
        controller.bindManagement(h.runtime.management)
        controller.navigate(Page.DOMAINS)
        assertNull(h.dns.state.value.scope);assertNull(h.dns.state.value.preparedChange)
    }
    @Test fun changedDraftWhileFinalPreflightSuspendsCannotSend() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent()
        val gate=CompletableDeferred<Unit>();h.beforeWrite={gate.await()};h.confirm();runCurrent()
        h.dns.onIntent(DnsIntent.DraftInvalidated);gate.complete(Unit);runCurrent()
        assertEquals(0,h.writes);assertNotEquals(TaskState.SUCCEEDED,h.tasks.values.single().state)
    }
    @Test fun changingProviderDeleteNameResetsTypedDraftAndRequiresNewPreview() = runTest {
        val h=Harness(this);h.zones.onIntent(ZoneIntent.Load(account));runCurrent();h.zones.onIntent(ZoneIntent.BeginDelete(zone));runCurrent()
        val revision=h.zones.state.value.editorRevision
        h.zoneRow=details(name="renamed.example.com")
        h.zones.onIntent(ZoneIntent.Preview(ZoneAction.Delete(zone,"example.com","example.com")));runCurrent()
        assertNull(h.zones.state.value.preparedChange);assertNull(h.zones.state.value.draft)
        assertEquals("renamed.example.com",h.zones.state.value.editorTarget?.name)
        assertTrue(h.zones.state.value.editorRevision>revision);assertEquals(0,h.writes)
    }
    @Test fun zoneEditConfirmsOnceAndReturnsToFreshList() = runTest {
        val h=Harness(this);h.zones.onIntent(ZoneIntent.Load(account));runCurrent();h.zones.onIntent(ZoneIntent.BeginEdit(zone));runCurrent()
        h.zones.onIntent(ZoneIntent.Preview(ZoneAction.Edit(zone,ZoneEditInput(paused=true))));runCurrent()
        val p=h.zones.state.value.preparedChange!!;assertEquals(0,h.writes)
        h.zones.onIntent(ZoneIntent.Confirm(p.id,p.revision));runCurrent()
        assertEquals(1,h.writes);assertEquals(TaskState.SUCCEEDED,h.zones.state.value.pendingTask?.state)
        assertNull(h.zones.state.value.editorMode);assertTrue(h.zones.state.value.zones.single().paused)
    }

    @Test fun rebuiltPageLoadsUnknownMetadataAndJobsCanReadOnlyReconcile() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent();h.afterWrite={throw IllegalStateException("lost receipt")};h.confirm();runCurrent()
        val task=h.tasks.values.single();assertEquals(TaskState.UNKNOWN,task.state)
        val rebuilt=h.runtime();rebuilt.management.dns.onIntent(DnsIntent.Load(zone));runCurrent()
        assertEquals(TaskState.UNKNOWN,rebuilt.management.dns.state.value.pendingTask?.state)
        assertNull(rebuilt.management.dns.state.value.preparedChange)
        rebuilt.reconcile(task.id)
        assertEquals(TaskState.SUCCEEDED,h.tasks[task.id]?.state);assertEquals(1,h.writes)
    }

    @Test fun jobsReconciliationRoutesToTypedManagementWithoutOldPayload() = runTest {
        val h=Harness(this);h.load();runCurrent();h.previewPatch();runCurrent();h.afterWrite={throw IllegalStateException("lost receipt")};h.confirm();runCurrent()
        val task=h.tasks.values.single();val rebuilt=h.runtime()
        rebuilt.reconcile(task.id)
        assertEquals(TaskState.SUCCEEDED,h.tasks[task.id]?.state);assertEquals(1,h.writes)
    }

    @Test fun restoredZoneListCanReconcileItsDisplayedUnknownTask() = runTest {
        val h=Harness(this);h.zones.onIntent(ZoneIntent.Load(account));runCurrent();h.zones.onIntent(ZoneIntent.Preview(ZoneAction.Edit(zone,ZoneEditInput(paused=true))));runCurrent()
        val p=h.zones.state.value.preparedChange!!;h.afterWrite={throw IllegalStateException("lost receipt")}
        h.zones.onIntent(ZoneIntent.Confirm(p.id,p.revision));runCurrent()
        val task=h.tasks.values.single();assertEquals(TaskState.UNKNOWN,task.state)
        val rebuilt=h.runtime();rebuilt.management.zones.onIntent(ZoneIntent.Load(account));runCurrent()
        assertEquals(task.id,rebuilt.management.zones.state.value.pendingTask?.id)
        rebuilt.management.zones.onIntent(ZoneIntent.Reconcile(task.id));runCurrent()
        assertEquals(TaskState.SUCCEEDED,h.tasks[task.id]?.state);assertEquals(1,h.writes)
    }

    @Test fun managementBoundDemoCannotUseSeparateLegacyWriteLock() = runTest {
        val h=Harness(this);val demo=Connection("demo-production","示例","demo",true);var legacyWrites=0;var runtimeCalls=0
        val ops=object:DnsOperations {
            override suspend fun create(zone:ZoneScope,draft:DnsDraft,frozenBefore:DnsRecord?):OperationTask { runtimeCalls++;throw CloudFailure(CloudFailureCode.CONFLICT,"runtime busy") }
            override suspend fun update(zone:ZoneScope,recordId:String,draft:DnsDraft,frozenBefore:DnsRecord?):OperationTask = create(zone,draft,frozenBefore)
            override suspend fun delete(zone:ZoneScope,recordId:String,frozenBefore:DnsRecord?):OperationTask = create(zone,DnsDraft("DELETE","",""),frozenBefore)
        }
        val controller=com.flarego.core.application.AppController(backgroundScope,SessionFactory { object:CloudSession by DemoSession(it) {
            override suspend fun save(zoneId:String,recordId:String?,draft:DnsDraft) { legacyWrites++ }
        } },MemoryStore(),listOf(demo),dnsOperations=ops)
        controller.bindManagement(h.runtime.management)
        controller.saveDns(Domain("zone-demo","flarego.dev","active",demo.id),null,DnsDraft("A","test.flarego.dev","192.0.2.1"))
        assertEquals(1,runtimeCalls);assertEquals(0,legacyWrites)
    }

    @Test fun modernJobsRetainReadOnlyProofForPersistedP1PatchMetadata() = runTest {
        val h=Harness(this)
        val desired=dnsDesired(connection.id,"one",DnsDraft("A","www.example.com","192.0.2.1",60,false))
        val hash=MessageDigest.getInstance("SHA-256").digest(CanonicalJson.encode(desired)).joinToString("") { "%02x".format(it) }
        h.store.put(OperationTask("legacy-patch",connection.id,zone,DnsOperationLifecycle.ACTION_PATCH,ResourceRef(zone,"dns_record","one"),"a".repeat(64),hash,TaskState.UNKNOWN,"结果待核对",null,null,1,1))
        h.runtime.reconcile("legacy-patch")
        assertEquals(TaskState.SUCCEEDED,h.tasks["legacy-patch"]?.state);assertEquals(0,h.writes)
    }

    @Test fun legacyReadProofCannotAcceptForeignOwnerOrWrongDesiredHash() = runTest {
        for(foreign in listOf(false,true)) {
            val h=Harness(this)
            val desired=dnsDesired(connection.id,"one",DnsDraft("A","www.example.com","192.0.2.1",60,false))
            val hash=MessageDigest.getInstance("SHA-256").digest(CanonicalJson.encode(desired)).joinToString("") { "%02x".format(it) }
            h.store.put(OperationTask("legacy",connection.id,zone,DnsOperationLifecycle.ACTION_PATCH,ResourceRef(zone,"dns_record","one"),"a".repeat(64),if(foreign) hash else "b".repeat(64),TaskState.UNKNOWN,"结果待核对",null,null,1,1))
            if(foreign) h.zoneRow=details(ZoneScope(AccountScope(connection.id,"foreign"),zone.zoneId))
            h.runtime.reconcile("legacy")
            assertEquals(TaskState.UNKNOWN,h.tasks["legacy"]?.state);assertEquals(0,h.writes)
        }
    }

}
