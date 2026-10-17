package com.flarego

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.dns.DNS_BATCH_KEY
import com.flarego.core.model.*
import com.flarego.platform.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Real Android SQLDelight database: fresh schema, actual migration, transaction faults and reopen. */
class DnsBatchPersistenceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="dns-batch-evidence-test.db"
    private val opened=mutableListOf<LocalDatabase>()
    private val scope=ZoneScope(AccountScope("connection","account"),"zone")
    private val fingerprint=AndroidFingerprint()
    private fun owner()=LocalDatabase.open(context,name).also { opened+=it }
    private fun proof(id:String="batch",s:ZoneScope=scope):DnsBatchTaskEvidence {
        val items=listOf(DnsBatchItemEvidence(DnsBatchKind.PATCH,0,"record",null,null,"c".repeat(64),"d".repeat(64)))
        return DnsBatchTaskEvidence(1,id,s,listOf(0,1,0,0),items,dnsBatchManifestHash(s,items,fingerprint))
    }
    private fun task(e:DnsBatchTaskEvidence,state:TaskState=TaskState.RUNNING)=OperationTask(e.taskId,e.scope.connectionId,e.scope,DNS_BATCH_KEY,ResourceRef(e.scope,"dns_batch",e.scope.zoneId),"a".repeat(64),e.manifestHash,state,"待核对",null,null,1,2)
    private fun save(o:LocalDatabase,e:DnsBatchTaskEvidence=proof()) { val t=task(e);o.operationStore.putWithSummaryAndEvidence(t,OperationTaskPersistence.summaryFor(t),e) }
    private fun rejected(block:()->Unit) { var thrown=false;try { block() } catch(_:Exception) { thrown=true };assertTrue("must reject",thrown) }
    @After fun cleanup() { opened.forEach { it.close() };opened.clear();context.deleteDatabase(name) }

    @Test fun freshDatabasePersistsBoundedManifestAndReopensWithoutPayload() {
        val o=owner();val e=proof();save(o,e);assertEquals(e,o.operationStore.batchEvidence(e.taskId))
        o.close();opened.remove(o)
        val reopened=owner();assertEquals(e,reopened.operationStore.batchEvidence(e.taskId))
        assertEquals(e.manifestHash,reopened.operationStore.get(e.taskId)!!.desiredHash)
        val raw=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY)
        try {
            for(table in listOf("dns_batch_evidence","dns_batch_item")) raw.rawQuery("PRAGMA table_info($table)",null).use { c ->
                val names=mutableListOf<String>();while(c.moveToNext()) names+=c.getString(1)
                assertTrue(names.isNotEmpty());assertTrue(names.none { it in setOf("content","data","settings","tags","comment","body","payload","header","token","confirm") })
            }
        } finally { raw.close() }
    }
    @Test fun actualV2SchemaMigratesAndPreservesLegacyTaskAndSummary() {
        val file=context.getDatabasePath(name);file.parentFile?.mkdirs()
        val db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            db.execSQL("CREATE TABLE connection(id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,account_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE operation(sequence INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,connection_id TEXT NOT NULL,title TEXT NOT NULL,status TEXT NOT NULL,detail TEXT NOT NULL)")
            db.execSQL("CREATE TABLE task(id TEXT NOT NULL PRIMARY KEY,connection_id TEXT NOT NULL,scope_kind TEXT NOT NULL,scope_connection_id TEXT NOT NULL,account_id TEXT,zone_id TEXT,user_id TEXT,organization_id TEXT,action_key TEXT NOT NULL,resource_kind TEXT,resource_external_id TEXT,request_fingerprint TEXT NOT NULL,desired_hash TEXT,state TEXT NOT NULL,safe_detail TEXT NOT NULL,provider_request_id TEXT,poll_path TEXT,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX task_connection ON task(connection_id)")
            db.execSQL("INSERT INTO connection VALUES('connection','Example','account')")
            db.execSQL("INSERT INTO operation(id,connection_id,title,status,detail) VALUES('old','connection','Old','UNKNOWN','待核对')")
            db.execSQL("INSERT INTO task VALUES('old','connection','ZONE','connection','account','zone',NULL,NULL,'old-action',NULL,NULL,'${"a".repeat(64)}',NULL,'UNKNOWN','待核对',NULL,NULL,1,2)")
            db.version=2
        } finally { db.close() }
        val o=owner();assertEquals("old",o.operationStore.get("old")!!.id);assertEquals("old",o.localStore.operations().single().id)
        save(o);assertNotNull(o.operationStore.batchEvidence("batch"));assertEquals(1,o.localStore.connections().size)
    }
    @Test fun ordinaryReplaceUpdatesPreserveEvidenceWithRecursiveTriggersOn() {
        val o=owner();val e=proof();save(o,e)
        o.driver.execute(null,"PRAGMA recursive_triggers=ON",0)
        val updated=task(e,TaskState.UNKNOWN).copy(updatedAtMs=3)
        o.operationStore.putWithSummary(updated,OperationTaskPersistence.summaryFor(updated))
        assertEquals(e,o.operationStore.batchEvidence(e.taskId));o.operationStore.put(updated.copy(updatedAtMs=4))
        assertEquals(e,o.operationStore.batchEvidence(e.taskId))
    }
    @Test fun summaryAndItemFaultsRollbackAllThreeRecords() {
        val o=owner();o.operationStore.list(scope.connectionId)
        for((table,trigger) in listOf("operation" to "fail_batch_summary","dns_batch_item" to "fail_batch_item")) {
            o.driver.execute(null,"CREATE TRIGGER $trigger BEFORE INSERT ON $table BEGIN SELECT RAISE(ABORT,'synthetic fault'); END",0)
            try { rejected { save(o) };assertNull(o.operationStore.get("batch"));assertNull(o.operationStore.batchEvidence("batch"));assertTrue(o.localStore.operations().isEmpty()) }
            finally { o.driver.execute(null,"DROP TRIGGER $trigger",0) }
        }
        save(o);assertNotNull(o.operationStore.batchEvidence("batch"))
    }
    @Test fun malformedIncompleteAndCrossScopeEvidenceCannotPersist() {
        val o=owner();val e=proof();val t=task(e)
        val bad=listOf(e.copy(version=2),e.copy(counts=listOf(0,2,0,0)),e.copy(items=emptyList()),e.copy(scope=ZoneScope(scope.account,"other")),e.copy(manifestHash="f".repeat(64)),e.copy(items=e.items+e.items),e.copy(items=e.items.map { it.copy(desiredHash="invalid") }),e.copy(counts=listOf(0,3501,0,0)))
        for(p in bad) { rejected { o.operationStore.putWithSummaryAndEvidence(t,OperationTaskPersistence.summaryFor(t),p) };assertNull(o.operationStore.get(t.id));assertNull(o.operationStore.batchEvidence(t.id)) }
        save(o);assertNotNull(o.operationStore.batchEvidence(t.id))
    }
    @Test fun bothRemovalPathsClearOwnEvidenceAndKeepOtherConnection() {
        val o=owner();save(o)
        val other=proof("other",ZoneScope(AccountScope("other-connection","other-account"),"zone"));save(o,other)
        o.operationStore.removeConnection(scope.connectionId);assertNull(o.operationStore.batchEvidence("batch"));assertTrue(o.localStore.operations().none { it.id=="batch" });assertEquals(other,o.operationStore.batchEvidence("other"))
        save(o);o.localStore.removeConnection(scope.connectionId);assertNull(o.operationStore.batchEvidence("batch"));assertEquals(other,o.operationStore.batchEvidence("other"))
    }
}

/** Actual Android VM/runtime/database/native panel, synthetic HTTP only. No fake page ports. */
class DnsBatchRuntimeIntegrationTest {
    @get:org.junit.Rule val compose=androidx.compose.ui.test.junit4.createComposeRule()
    private val models=androidx.lifecycle.ViewModelStore()
    private var model:FlareGoViewModel?=null
    private var initialIds=emptySet<String>()
    private val accountId="b5".repeat(16)
    private val requests=java.util.Collections.synchronizedList(mutableListOf<com.flarego.core.cloudflare.HttpRequest>())
    private var loseReceipt=false
    private var transportAcquisitions=0
    private var row=kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"batch-record","type":"A","name":"www.batch.example.com","content":"192.0.2.1","ttl":60,"proxied":false,"comment":"old","settings":{},"tags":[],"proxiable":true,"created_on":"2026-01-01T00:00:00Z","modified_on":"2026-01-01T00:00:00Z","meta":{}}""").jsonObject
    private val zone="""{"id":"batch-zone","name":"batch.example.com","account":{"id":"$accountId"},"status":"active","paused":false,"type":"full","name_servers":["example.ns.cloudflare.com"]}"""
    private fun ok(result:String)=com.flarego.core.cloudflare.HttpResponse(200,"""{"success":true,"errors":[],"messages":[],"result":$result,"result_info":{"page":1,"per_page":50,"total_count":1,"total_pages":1}}""")
    private fun posts()=synchronized(requests) { requests.filter { it.method=="POST" }.toList() }
    private fun create(connect:Boolean,cloud:Boolean=true):FlareGoViewModel {
        lateinit var m:FlareGoViewModel
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
            m=FlareGoViewModel(app,transportForToken={
                transportAcquisitions++;check(cloud) { "demo acquired credentials" }
                com.flarego.core.cloudflare.HttpTransport { r ->
                    requests+=r
                    when {
                        r.path=="/accounts/$accountId" -> ok("""{"id":"$accountId","name":"Synthetic batch fixture"}""")
                        r.path.startsWith("/zones?") -> ok("[$zone]")
                        r.path=="/zones/batch-zone" -> ok(zone)
                        r.path.startsWith("/zones/batch-zone/dns_records?") -> ok("[$row]")
                        r.path=="/zones/batch-zone/dns_records/batch-record" && r.method=="GET" -> ok(row.toString())
                        r.path=="/zones/batch-zone/dns_records/batch" && r.method=="POST" -> {
                            val body=kotlinx.serialization.json.Json.parseToJsonElement(r.body!!).jsonObject
                            val patch=body.getValue("patches").jsonArray.single().jsonObject
                            row=kotlinx.serialization.json.JsonObject(row+(patch-"id"))
                            if(loseReceipt) throw java.io.IOException("synthetic lost receipt")
                            val full=kotlinx.serialization.json.JsonObject(row+("data" to kotlinx.serialization.json.JsonObject(emptyMap())))
                            ok("""{"patches":[$full]}""")
                        }
                        else -> error("unexpected typed batch route")
                    }
                }
            })
            if(model==null) initialIds=m.controller.state.value.connections.map { it.id }.toSet()
            model=m;models.put("batch",m)
            if(connect) m.viewModelScope.launch { m.connect("Synthetic batch fixture",accountId,"synthetic-batch-token-not-real") }
        }
        if(connect) compose.waitUntil(10000) { m.controller.state.value.selected?.accountId==accountId }
        return m
    }
    private fun click(tag:String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun scope(m:FlareGoViewModel)=m.controller.state.value.selected!!.let { ZoneScope(AccountScope(it.id,it.accountId),"batch-zone") }
    private fun preview(m:FlareGoViewModel,s:ZoneScope) {
        compose.runOnIdle { m.management.batch!!.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Bind(s)) }
        compose.waitUntil(10000) { m.management.batch!!.state.value.zoneName!=null && !m.management.batch!!.state.value.loading }
        compose.runOnIdle { m.management.batch!!.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Preview(DnsBatchInput(patches=listOf(DnsBatchPatch("batch-record",kotlinx.serialization.json.buildJsonObject { put("comment","confirmed batch") }))))) }
        compose.waitUntil(10000) { m.management.batch!!.state.value.preparedChange!=null }
    }
    private fun confirm(m:FlareGoViewModel) {
        compose.runOnIdle { val p=m.management.batch!!.state.value.preparedChange!!;m.management.batch!!.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Confirm(p.id,p.revision)) }
        compose.waitUntil(10000) { m.management.batch!!.state.value.pendingTask!=null && !m.management.batch!!.state.value.loading }
    }
    @After fun cleanup() {
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initialIds && !it.demo }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initialIds && !it.demo }!=false }
        compose.runOnIdle { models.clear() }
    }
    @Test fun productionVmNativeConfirmationPersistsTaskSummaryAndSafeManifest() {
        val m=create(true)
        compose.setContent { com.flarego.ui.FlareGoTheme { com.flarego.ui.FlareGoApp(m.controller,{_,_,_->},{},{},management=m.management) } }
        compose.onNodeWithTag("nav-domains").performClick()
        compose.waitUntil(10000) { m.management.zones.state.value.zones.isNotEmpty() }
        click("zone-row-batch-zone");compose.waitUntil(10000) { m.management.zones.state.value.selected!=null }
        compose.onNodeWithTag("zone-tab-dns").performClick();compose.waitUntil(10000) { m.management.dns.state.value.rows.isNotEmpty() }
        click("dns-open-batch");compose.waitUntil(10000) { m.management.batch!!.state.value.zoneName!=null }
        click("db-add-PATCH");click("db-select-batch-record");compose.onNodeWithTag("dns-comment").performScrollTo().performTextReplacement("native confirmed")
        click("dns-preview");click("db-preview");compose.waitUntil(10000) { m.management.batch!!.state.value.preparedChange!=null }
        compose.runOnIdle { assertTrue(posts().isEmpty());assertTrue(m.operationStore.list(scope(m).connectionId).isEmpty()) }
        click("db-confirm");compose.waitUntil(10000) { m.management.batch!!.state.value.pendingTask?.state==TaskState.SUCCEEDED && !m.management.batch!!.state.value.loading }
        compose.onNodeWithTag("db-task").performScrollTo().assertExists()
        compose.runOnIdle {
            val task=m.management.batch!!.state.value.pendingTask!!;val proof=m.operationStore.batchEvidence(task.id)!!
            assertEquals(scope(m),task.scope);assertEquals(task.desiredHash,proof.manifestHash);assertEquals(DnsBatchTransaction.CONFIRMED_COMMIT,proof.transaction)
            assertEquals(1,posts().size);assertEquals(setOf("id","comment"),kotlinx.serialization.json.Json.parseToJsonElement(posts().single().body!!).jsonObject.getValue("patches").jsonArray.single().jsonObject.keys)
            val local=LocalDatabase.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try { assertEquals(OperationStatus.SUCCESS,local.localStore.operations().single { it.id==task.id }.status) } finally { local.close() }
            assertFalse(proof.toString().contains("native confirmed"));assertFalse(task.toString().contains("native confirmed"))
        }
    }
    @Test fun newVmReopenedDatabaseReconcilesUnknownReadonlyWithoutOldDraftOrConfirmation() {
        val first=create(true);val s=scope(first);loseReceipt=true;preview(first,s)
        val old=first.management.batch!!.state.value.preparedChange!!;compose.runOnIdle { assertTrue(posts().isEmpty()) };confirm(first)
        val task=first.management.batch!!.state.value.pendingTask!!;assertEquals(TaskState.UNKNOWN,task.state);assertEquals(1,posts().size)
        compose.runOnIdle { models.clear() }
        val second=create(false)
        compose.runOnIdle { second.controller.selectConnection(second.controller.state.value.connections.single { it.accountId==accountId });second.management.batch!!.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Bind(s)) }
        compose.waitUntil(10000) { second.management.batch!!.state.value.pendingTask?.id==task.id && !second.management.batch!!.state.value.loading }
        compose.runOnIdle {
            assertNull(second.management.batch!!.state.value.draft);assertNull(second.management.batch!!.state.value.preparedChange)
            second.management.batch!!.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Confirm(old.id,old.revision))
        }
        compose.setContent { val state by second.management.batch!!.state.collectAsState();com.flarego.ui.FlareGoTheme { com.flarego.ui.dns.DnsBatchPanel(s,state,second.management.batch!!::onIntent) } }
        click("db-reconcile");compose.waitUntil(10000) { second.operationStore.get(task.id)?.state==TaskState.SUCCEEDED }
        compose.runOnIdle { assertEquals(1,posts().size);assertEquals(DnsBatchTransaction.UNKNOWN,second.operationStore.batchEvidence(task.id)!!.transaction);assertNull(second.management.batch!!.state.value.draft) }
    }
    @Test fun demoNativeBatchUsesSamePortsWithoutCredentialAcquisitionOrRealDatabaseTasks() {
        val m=create(false,cloud=false)
        compose.runOnIdle { m.controller.reloadConnections(m.controller.state.value.connections+com.flarego.core.demo.demoConnections);m.controller.selectConnection(m.controller.state.value.connections.first { it.id=="demo-production" }) }
        compose.setContent { com.flarego.ui.FlareGoTheme { com.flarego.ui.FlareGoApp(m.controller,{_,_,_->},{},{},management=m.management) } }
        compose.onNodeWithTag("nav-domains").performClick();compose.waitUntil(10000) { m.management.zones.state.value.zones.isNotEmpty() }
        click("zone-row-zone-demo");compose.waitUntil(10000) { m.management.zones.state.value.selected!=null }
        compose.onNodeWithTag("zone-tab-dns").performClick();compose.waitUntil(10000) { m.management.dns.state.value.rows.isNotEmpty() }
        click("dns-open-batch");compose.waitUntil(10000) { m.management.batch!!.state.value.zoneName!=null }
        compose.onNodeWithTag("db-demo").performScrollTo().assertExists()
        click("db-add-POST");click("dns-type-TXT")
        compose.onNodeWithTag("dns-name").performScrollTo().performTextReplacement("native-example")
        compose.onNodeWithTag("dns-content").performScrollTo().performTextReplacement("example batch")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("dns-preview").performScrollTo().assertIsDisplayed()
        click("dns-preview")
        if(compose.onAllNodesWithTag("db-preview").fetchSemanticsNodes().isEmpty()) {
            val current=m.management.batch!!.state.value
            throw AssertionError("Demo state scope=${current.scope} zone=${current.zoneName} revision=${current.revision} editor=${current.editorRevision} loading=${current.loading} error=${current.error} draft=${current.draft}\n"+compose.onRoot(useUnmergedTree=true).printToString())
        }
        click("db-preview");compose.waitUntil(10000) { m.management.batch!!.state.value.preparedChange!=null }
        compose.runOnIdle { assertTrue(m.operationStore.list("demo-production").isEmpty());assertTrue(posts().isEmpty()) }
        click("db-confirm");compose.waitUntil(10000) { m.management.batch!!.state.value.pendingTask?.state==TaskState.SUCCEEDED }
        compose.runOnIdle {
            val task=m.management.batch!!.state.value.pendingTask!!;assertTrue(m.management.batch!!.state.value.demo)
            assertEquals(0,transportAcquisitions);assertTrue(requests.isEmpty());assertTrue(m.operationStore.list("demo-production").isEmpty());assertNull(m.operationStore.batchEvidence(task.id))
            assertTrue(m.management.batch!!.state.value.outcome!!.items.single().providerId!!.startsWith("example-"))
        }
    }
}
