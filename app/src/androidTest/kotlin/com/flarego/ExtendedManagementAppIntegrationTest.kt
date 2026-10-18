package com.flarego

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.zones.ZoneIntent
import com.flarego.core.application.dns.DnsWriteMode
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.dns.*
import com.flarego.ui.zones.*
import com.flarego.ui.management.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Actual VM/runtime/Android store/native leaf; only Cloudflare transport is synthetic. */
class ExtendedManagementAppIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val vmStore=ViewModelStore()
    private var model:FlareGoViewModel?=null
    private var originalIds=emptySet<String>()
    private val account="a3".repeat(16)
    private val zoneId="b3".repeat(16)
    private val requests=java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val acquisitions=AtomicInteger()
    private val streamAcquisitions=AtomicInteger()
    private val writes=AtomicInteger()
    private val importWrites=AtomicInteger()
    private val uploadSourceCloses=AtomicInteger()
    private var loseImportResponse=false
    private var listForbidden=false
    private val importBytes="extended.example.com. 60 IN A 192.0.2.1\n".toByteArray()
    private val exportBytes="; synthetic exact export\r\nextended.example.com. 60 IN TXT \"raw\"\r\n".toByteArray()
    private var exportType="text/plain"
    private var onTransfer:suspend(HttpRequest)->Unit={}
    private var receivedImport:ByteArray?=null
    private var onRead:suspend(HttpRequest)->Unit={}
    private var catalogAvailable=true
    private var sec=Json.parseToJsonElement("""{"status":"active","dnssec_multi_signer":false,"dnssec_presigned":false,"dnssec_use_nsec3":false}""").jsonObject
    private fun envelope(result:JsonElement,paginated:Boolean=true)=HttpResponse(200,buildJsonObject {
        put("success",true);put("errors",JsonArray(emptyList()));put("messages",JsonArray(emptyList()));put("result",result)
        if(paginated)put("result_info",buildJsonObject { put("page",1);put("per_page",50);put("count",(result as? JsonArray)?.size ?: 1);put("total_count",(result as? JsonArray)?.size ?: 1);put("total_pages",1) })
    }.toString())
    private fun zone(scopeAccount:String=account,scopeZone:String=zoneId)=buildJsonObject { put("id",scopeZone);put("name","extended.example.com");put("status","active");put("type","full");put("paused",false)
        put("account",buildJsonObject { put("id",scopeAccount) });put("name_servers",JsonArray(emptyList())) }
    private fun transport(scopeAccount:String=account,scopeZone:String=zoneId)=HttpTransport { r ->
        requests+=r;onRead(r)
        when {
            r.path=="/accounts/$scopeAccount" -> envelope(buildJsonObject { put("id",scopeAccount);put("name","Synthetic integration") })
            r.path.startsWith("/zones?") -> envelope(JsonArray(listOf(zone(scopeAccount,scopeZone))))
            r.path=="/zones/$scopeZone" -> envelope(zone(scopeAccount,scopeZone))
            r.path=="/user/tokens/verify" -> envelope(buildJsonObject { put("id","self");put("status","active") })
            r.path=="/accounts/$scopeAccount/tokens/verify" -> HttpResponse(401,"unavailable")
            r.path=="/user/tokens/self" -> envelope(buildJsonObject {
                put("id","self");put("status","active");put("policies",buildJsonArray { add(buildJsonObject {
                    put("id","policy");put("effect","allow");put("permission_groups",buildJsonArray { add(buildJsonObject { put("id","dns-write") }) })
                    put("resources",buildJsonObject { put("com.cloudflare.api.account.zone.$scopeZone","*") })
                }) })
            })
            r.path=="/user/tokens/permission_groups" -> if(!catalogAvailable)HttpResponse(403,"unavailable") else envelope(buildJsonArray { add(buildJsonObject {
                put("id","dns-write");put("name","DNS Write");put("scopes",buildJsonArray { add("com.cloudflare.api.account.zone") });put("is_selectable",true)
            }) },paginated=false)
            r.path=="/zones/$scopeZone/dns_records/import" && r.method=="POST" -> {
                importWrites.incrementAndGet()
                val parts=(r.payload as HttpBody.Multipart).parts
                assertEquals("false",(parts.single { it.name=="proxied" }.body as HttpBody.Text).value)
                val source=(parts.single { it.name=="file" }.body as HttpBody.Stream).source
                val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(37)
                try { while(true) { val n=source.read(buffer);if(n<0)break;bytes.write(buffer,0,n) } } finally { source.close();uploadSourceCloses.incrementAndGet() }
                receivedImport=bytes.toByteArray()
                if(loseImportResponse)throw java.io.IOException("synthetic lost response")
                envelope(buildJsonObject { put("recs_added",1);put("total_records_parsed",1) })
            }
            r.path.startsWith("/zones/$scopeZone/dns_records") && r.method=="GET" -> if(listForbidden)HttpResponse(403,"unavailable") else envelope(JsonArray(emptyList()))
            r.path=="/zones/$scopeZone/dnssec" -> { if(r.method=="PATCH") { writes.incrementAndGet();sec=JsonObject(sec+Json.parseToJsonElement(r.body!!).jsonObject) };envelope(sec) }
            r.path=="/zones/$scopeZone/dnssec/zsk" -> envelope(JsonArray(emptyList()))
            else -> HttpResponse(404,"""{"success":false,"errors":[{"code":1000,"message":"synthetic unavailable"}],"messages":[],"result":null}""")
        }
    }
    private fun create(real:Boolean):Pair<FlareGoViewModel,ZoneScope> {
        lateinit var m:FlareGoViewModel
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            m=FlareGoViewModel(app,Dispatchers.IO,transportForToken={ acquisitions.incrementAndGet();check(real);transport() },transfersForToken={
                streamAcquisitions.incrementAndGet();check(real)
                object:TransferPort { override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=withContext(Dispatchers.IO) {
                    // TransferPort owns streaming IO, matching the production CloudflareTransfers adapter.
                    assertEquals("GET",request.method);assertEquals("/zones/$zoneId/dns_records/export",request.path)
                    assertNotEquals("Streaming fixture executes IO away from Main",android.os.Looper.getMainLooper(),android.os.Looper.myLooper())
                    onTransfer(request)
                    try { sink.write(exportBytes,0,exportBytes.size) } finally { sink.close() }
                    HttpResponseHead(200,mapOf("Content-Type" to listOf(exportType)),exportBytes.size.toLong())
                } }
            })
            model=m;vmStore.put("extended",m);originalIds=m.controller.state.value.connections.map { it.id }.toSet()
            if(real) m.viewModelScope.launch { m.connect("Synthetic extended fixture",account,"synthetic-not-a-real-token") }
            else {
                m.controller.reloadConnections(m.controller.state.value.connections+com.flarego.core.demo.demoConnections)
                m.controller.selectConnection(m.controller.state.value.connections.first { it.id=="demo-production" })
            }
        }
        compose.waitUntil(10000) { m.controller.state.value.selected?.let { if(real) it.accountId==account else it.demo }==true }
        val c=m.controller.state.value.selected!!
        val z=ZoneScope(AccountScope(c.id,c.accountId),if(real) zoneId else "zone-demo")
        compose.runOnIdle { m.management.zones.onIntent(ZoneIntent.Load(z.account)) }
        compose.waitUntil(10000) { !m.management.zones.state.value.loading }
        compose.runOnIdle { m.management.zones.onIntent(ZoneIntent.Open(z)) }
        compose.waitUntil(10000) { m.management.zones.state.value.selected?.ref==z && !m.management.zones.state.value.loading }
        return m to z
    }
    @After fun cleanup() {
        compose.runOnIdle { model?.let { m->m.controller.state.value.connections.filter { it.id !in originalIds && !it.demo }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in originalIds && !it.demo }!=false }
        compose.runOnIdle { vmStore.clear() }
        testSourceNames.forEach { fixtureShell("rm -f /sdcard/Download/"+it) }
    }
    @Test fun realVmNativeExplicitRiskConfirmationSendsOneAndPersistsSafeTask() {
        val (m,z)=create(true);val port=m.panelPorts.dnssec!!
        val context=ManagementPanelContext(z,m.controller.operationEpoch)
        lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(port.bind(context)) }
        compose.waitUntil(10000) { port.state.value.state.snapshot!=null && !port.state.value.state.loading }
        assertEquals("same-credential catalog and policy must grant this synthetic scope; requests="+synchronized(requests) { requests.map { it.method+" "+it.path } },true,port.state.value.state.snapshot?.writeAllowed)
        compose.setContent { val p by port.state.collectAsState();FlareGoTheme { DnssecPanel(z,p.state,{ port.onIntent(lease,it) }) } }
        compose.onNodeWithTag("ds-status-disabled").performScrollTo().performClick()
        compose.onNodeWithTag("ds-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { port.state.value.state.prepared!=null || port.state.value.state.errors.isNotEmpty() }
        assertNotNull("Expected actual preview; safe errors="+port.state.value.state.errors+"; requests="+synchronized(requests) { requests.map { it.method+" "+it.path } },port.state.value.state.prepared)
        assertEquals(0,writes.get())
        compose.onNodeWithTag("ds-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().performClick()
        compose.onNodeWithTag("ds-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { port.state.value.state.pendingTasks.any { it.state==TaskState.SUCCEEDED } }
        assertEquals(1,writes.get())
        val task=m.operationStore.list(z.connectionId).single { it.actionKey==DnssecMutation.UPDATE.operationId }
        assertEquals(TaskState.SUCCEEDED,task.state);assertFalse(task.toString().contains("synthetic-not-a-real-token"))
    }
    @Test fun demoNativeUsesSamePortsAndRuntimeWithoutProductionAcquisitionOrTaskWrites() {
        val (m,z)=create(false);val port=m.panelPorts.dnssec!!
        lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(port.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { port.state.value.state.snapshot!=null && !port.state.value.state.loading }
        compose.setContent { val p by port.state.collectAsState();FlareGoTheme { DnssecPanel(z,p.state,{ port.onIntent(lease,it) }) } }
        compose.onNodeWithTag("ds-status-active").performScrollTo().performClick()
        compose.onNodeWithTag("ds-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { port.state.value.state.prepared!=null }
        compose.onNodeWithTag("ds-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { port.state.value.state.pendingTasks.any { it.state==TaskState.SUCCEEDED } }
        assertEquals(0,acquisitions.get());assertEquals(0,streamAcquisitions.get());assertTrue(requests.isEmpty())
        assertTrue(m.operationStore.list(z.connectionId).isEmpty())
    }
    @Test fun oldSameContextLeaseDisposeAndQueuedCallbackCannotInvalidateNewBinding() {
        val (m,z)=create(true);val p=m.panelPorts.dnssec!!;val context=ManagementPanelContext(z,m.controller.operationEpoch)
        lateinit var old:ManagementPanelLease;lateinit var fresh:ManagementPanelLease
        compose.runOnIdle { old=checkNotNull(p.bind(context));fresh=checkNotNull(p.bind(context));p.clear(old)
            p.onIntent(old,DnssecPanelIntent.Preview(DnssecAction.Update(z,DnssecUpdateInput(status="disabled")))) }
        compose.waitUntil(10000) { p.state.value.state.snapshot!=null && !p.state.value.state.loading }
        assertSame(fresh,p.state.value.lease);assertNull(p.state.value.state.prepared);assertEquals(0,writes.get())
    }
    private fun bindSec(m:FlareGoViewModel,z:ZoneScope):ManagementPanelLease {
        val p=m.panelPorts.dnssec!!
        lateinit var l:ManagementPanelLease
        compose.runOnIdle { l=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { p.state.value.state.snapshot!=null && !p.state.value.state.loading }
        return l
    }
    private fun prepareDisable(m:FlareGoViewModel,z:ZoneScope,l:ManagementPanelLease):DnssecPanelPreview {
        val p=m.panelPorts.dnssec!!
        compose.runOnIdle { p.onIntent(l,DnssecPanelIntent.Preview(DnssecAction.Update(z,DnssecUpdateInput(status="disabled")))) }
        compose.waitUntil(10000) { p.state.value.state.prepared!=null || p.state.value.state.errors.isNotEmpty() }
        return checkNotNull(p.state.value.state.prepared) { "safe prepare error: "+p.state.value.state.errors }
    }
    @Test fun unavailableRealCatalogNeverGrantsFromReadOrUserConfirmation() {
        catalogAvailable=false
        val (m,z)=create(true);val l=bindSec(m,z);val p=m.panelPorts.dnssec!!
        assertNull(p.state.value.state.snapshot?.writeAllowed)
        compose.runOnIdle {
            p.onIntent(l,DnssecPanelIntent.Preview(DnssecAction.Update(z,DnssecUpdateInput(status="disabled"))))
            p.onIntent(l,DnssecPanelIntent.Confirm("unissued",p.state.value.state.revision,setOf(DnssecRisk.DISABLE)))
        }
        compose.waitUntil(10000) { !p.state.value.state.loading }
        assertNull(p.state.value.state.prepared);assertEquals(0,writes.get());assertTrue(m.operationStore.list(z.connectionId).isEmpty())
    }
    @Test fun missingRiskAndInvalidatedActualDraftCannotSendOrReviveConfirmation() {
        val (m,z)=create(true);val l=bindSec(m,z);val p=m.panelPorts.dnssec!!
        val plan=prepareDisable(m,z,l)
        compose.runOnIdle { p.onIntent(l,DnssecPanelIntent.Confirm(plan.planId,plan.revision,emptySet())) }
        compose.waitUntil(10000) { p.state.value.state.errors.isNotEmpty() && !p.state.value.state.loading }
        assertEquals(0,writes.get());assertTrue(m.operationStore.list(z.connectionId).isEmpty())
        val retry=runBlocking { runCatching { m.extendedManagement.confirmExtended(plan.planId,plan.revision,
            com.flarego.core.application.management.ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) } }
        assertTrue("A failed handoff must revoke its old actual guard; only a new explicit draft may prepare",retry.exceptionOrNull() is CloudFailure)
        assertEquals(0,writes.get())
        val fresh=prepareDisable(m,z,l)
        compose.runOnIdle { p.onIntent(l,DnssecPanelIntent.DraftInvalidated(z));p.onIntent(l,DnssecPanelIntent.Confirm(fresh.planId,fresh.revision,setOf(DnssecRisk.DISABLE))) }
        assertNull(p.state.value.state.prepared);assertEquals(0,writes.get());assertTrue(m.operationStore.list(z.connectionId).isEmpty())
    }
    @Test fun latePrepareCompletionNeverRelabelsOldLeaseAsNewBinding() {
        val (m,z)=create(true);val old=bindSec(m,z);val p=m.panelPorts.dnssec!!
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        onRead={ r -> if(r.method=="GET" && r.path=="/zones/$zoneId/dnssec" && !entered.isCompleted) {
            entered.complete(Unit);withContext(NonCancellable) { release.await() }
        } }
        compose.runOnIdle { p.onIntent(old,DnssecPanelIntent.Preview(DnssecAction.Update(z,DnssecUpdateInput(status="disabled")))) }
        compose.waitUntil(10000) { entered.isCompleted }
        lateinit var fresh:ManagementPanelLease
        try { compose.runOnIdle { fresh=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch)));p.clear(old);m.panelPorts.onRouteExit(old) } }
        finally { release.complete(Unit) }
        compose.waitUntil(10000) { p.state.value.lease===fresh && p.state.value.state.snapshot!=null && !p.state.value.state.loading }
        assertSame(fresh,p.state.value.lease);assertNull(p.state.value.state.prepared);assertEquals(0,writes.get())
    }
    @Test fun confirmedHandoffSurvivesPresentationDisposeAndSameContextRebind() {
        val (m,z)=create(true);val l=bindSec(m,z);val p=m.panelPorts.dnssec!!;val plan=prepareDisable(m,z,l)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        onRead={ r -> if(r.method=="GET" && r.path=="/zones/$zoneId/dnssec" && !entered.isCompleted) {
            entered.complete(Unit);withContext(NonCancellable) { release.await() }
        } }
        compose.runOnIdle { p.onIntent(l,DnssecPanelIntent.Confirm(plan.planId,plan.revision,setOf(DnssecRisk.DISABLE))) }
        compose.waitUntil(10000) { entered.isCompleted }
        lateinit var fresh:ManagementPanelLease
        try { compose.runOnIdle { p.clear(l);fresh=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch)));p.clear(l) } }
        finally { release.complete(Unit) }
        compose.waitUntil(10000) { p.state.value.lease===fresh && !p.state.value.state.loading && p.state.value.state.pendingTasks.any { it.state==TaskState.SUCCEEDED } }
        assertEquals(1,writes.get());assertNull(p.state.value.state.prepared)
        assertEquals(1,m.operationStore.list(z.connectionId).count { it.state==TaskState.SUCCEEDED })
    }
    @Test fun credentialReplacementDuringConfirmedPreflightRejectsOldWrite() {
        val (m,z)=create(true);val l=bindSec(m,z);val p=m.panelPorts.dnssec!!;val plan=prepareDisable(m,z,l)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val changed=CompletableDeferred<Unit>()
        onRead={ r -> if(r.method=="GET" && r.path=="/zones/$zoneId/dnssec" && !entered.isCompleted) {
            entered.complete(Unit);withContext(NonCancellable) { release.await() }
        } }
        compose.runOnIdle { p.onIntent(l,DnssecPanelIntent.Confirm(plan.planId,plan.revision,setOf(DnssecRisk.DISABLE))) }
        compose.waitUntil(10000) { entered.isCompleted }
        try {
            compose.runOnIdle { m.viewModelScope.launch { try { m.connect("Replaced synthetic fixture",account,"replacement-synthetic-token") } finally { changed.complete(Unit) } } }
            compose.waitUntil(10000) { changed.isCompleted }
            compose.runOnIdle { m.onManagementResume();assertNull(p.state.value.lease);p.onIntent(l,DnssecPanelIntent.Confirm(plan.planId,plan.revision,setOf(DnssecRisk.DISABLE))) }
        } finally { release.complete(Unit) }
        // P1 deliberately preserves RUNNING after an unclassified guarded write closure failure.
        // Task publication occurs in appWrite's finally after the actual execution has returned.
        compose.waitUntil(10000) { m.controller.state.value.tasks.any { it.id==plan.planId } }
        assertEquals(TaskState.RUNNING,m.operationStore.list(z.connectionId).single { it.id==plan.planId }.state)
        assertEquals(0,writes.get());assertNull(p.state.value.lease)
    }

    @Test fun documentCancellationBetweenPresentationDisposeAndRebindIsNotLost() {
        val (m,z)=create(true)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val owner=ManagementPlatformOwner.forApplication(app);val borrower=owner.borrow()
        val connection=m.controller.state.value.selected!!
        val actual=ExtendedAppContext(ManagementPanelContext(z,m.controller.operationEpoch),connection,
            checkNotNull(owner.currentCredentialStamp(connection)))
        var held:ExtendedManagementAppAdapter?=null
        try {
            compose.runOnIdle {
                val adapter=ExtendedManagementAppAdapter(m.viewModelScope,m.extendedManagement,{actual},{owner},{borrower},{}).also { held=it }
                val port=adapter.ports.files!!
                for(kind in DnsDocumentKind.entries) for(cancelBeforeDispose in listOf(false,true)) {
                    val old=checkNotNull(port.bind(actual.panel))
                    port.onIntent(old,if(kind==DnsDocumentKind.IMPORT)DnsFilesPanelIntent.SelectImportFile(z)
                        else DnsFilesPanelIntent.SelectExportTarget(z))
                    val stamp=checkNotNull(adapter.documentRequestStamp())
                    if(cancelBeforeDispose)adapter.documentCancelled(kind)
                    port.clear(old) // Composition disposal while the OS picker is still in front.
                    assertEquals(stamp,adapter.documentRequestStamp())
                    if(!cancelBeforeDispose)adapter.documentCancelled(kind)
                    val fresh=checkNotNull(port.bind(actual.panel))
                    assertNotSame(old,fresh)
                    if(kind==DnsDocumentKind.IMPORT)assertEquals(DnsFilesSelection.CANCELLED,port.state.value.state.selection)
                    else assertEquals(DnsFilesExportStage.CANCELLED,port.state.value.state.exportStage)
                    assertNull(port.state.value.state.prepared)
                    adapter.ports.onRouteExit(fresh)
                    val next=checkNotNull(port.bind(actual.panel))
                    assertEquals(DnsFilesSelection.NONE,port.state.value.state.selection)
                    assertEquals(DnsFilesExportStage.IDLE,port.state.value.state.exportStage)
                    port.clear(next)
                }
            }
        } finally {
            compose.runOnIdle { held?.close() }
            runBlocking { owner.releaseBorrower(borrower) }
        }
    }

    @Test fun offMainPickerCurrentPredicateIsPureAndMainOwnsWithdrawal() {
        val (m,z)=create(true)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val owner=ManagementPlatformOwner.forApplication(app);val b=owner.borrow()
        val connection=m.controller.state.value.selected!!
        val live=java.util.concurrent.atomic.AtomicReference<ExtendedAppContext?>(ExtendedAppContext(
            ManagementPanelContext(z,m.controller.operationEpoch),connection,checkNotNull(owner.currentCredentialStamp(connection))))
        val offMainInvalidations=AtomicInteger()
        lateinit var adapter:ExtendedManagementAppAdapter;lateinit var lease:ManagementPanelLease
        compose.runOnIdle {
            adapter=ExtendedManagementAppAdapter(m.viewModelScope,m.extendedManagement,{live.get()},{owner},{b},{},
                { if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())offMainInvalidations.incrementAndGet() })
            lease=checkNotNull(adapter.ports.files!!.bind(checkNotNull(live.get()).panel))
        }
        val port=adapter.ports.files!!
        compose.waitUntil(10000) { port.state.value.state.snapshot!=null && !port.state.value.state.loading }
        try {
            runBlocking { withContext(Dispatchers.IO) {
                assertNotNull(adapter.documentRequestStamp())
                live.set(null)
                assertNull("IO guard must fail closed after independent context loss",adapter.documentRequestStamp())
                assertSame("IO predicate must not withdraw presentation; Main owner performs cleanup",lease,port.state.value.lease)
            } }
            assertEquals("No off-main route/picker mutation",0,offMainInvalidations.get())
            compose.runOnIdle { adapter.refreshActual();assertNull(port.state.value.lease) }
        } finally {
            compose.runOnIdle { adapter.close() }
            runBlocking { owner.releaseBorrower(b) }
        }
    }

    private fun systemNode(root:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        if(root==null)return null
        if(predicate(root))return root
        for(i in 0 until root.childCount)systemNode(root.getChild(i),predicate)?.let { return it }
        return null
    }
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun systemClick(label:String):Boolean {
        var n=systemNode(automation.rootInActiveWindow) { it.text?.toString()?.equals(label,true)==true || it.contentDescription?.toString()?.equals(label,true)==true } ?: return false
        while(!n.isClickable)n=n.parent ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun systemDownloads() {
        val deadline=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<deadline && automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")!=true)Thread.sleep(50)
        assertTrue("Actual OS picker",automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)
        val toolbar=systemNode(automation.rootInActiveWindow) { it.viewIdResourceName?.endsWith("/toolbar")==true }
        if(systemNode(toolbar) { it.text?.toString()=="Downloads" }!=null)return
        systemClick("Show roots")
        val end=System.currentTimeMillis()+5000
        while(System.currentTimeMillis()<end) { if(systemClick("Downloads"))return;Thread.sleep(50) }
        fail("Downloads root must be selected through the actual system picker")
    }
    private fun fixtureShell(command:String):ByteArray=automation.executeShellCommand(command).use { fd ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
    private fun nativeImportFile(m:FlareGoViewModel,z:ZoneScope,render:Boolean=true):ManagementPanelLease {
        val p=m.panelPorts.files!!
        lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { p.state.value.state.snapshot!=null && !p.state.value.state.loading }
        if(render)compose.setContent { val publication by p.state.collectAsState();FlareGoTheme { DnsFilesPanel(z,m.controller.operationEpoch,publication.state,{p.onIntent(lease,it)}) } }
        val name="flarego-b2b-import-"+UUID.randomUUID()+".bind"
        // Test fixture bytes only. The shell creates no grants; real OpenDocument supplies returned flags.
        val pipes=automation.executeShellCommandRw("sh")
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                it.write(("printf 'extended.example.com. 60 IN A 192.0.2.1\\n' > /sdcard/Download/"+name+"\ncat /sdcard/Download/"+name+"\nexit\n").toByteArray())
            }
            assertArrayEquals("Fixture bytes must exist before the real OS picker",importBytes,
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() })
        } finally { pipes.forEach { runCatching { it.close() } } }
        lateinit var launcher:androidx.activity.result.ActivityResultLauncher<android.content.Intent>
        compose.runOnIdle { launcher=compose.activity.activityResultRegistry.register("b2b-import-"+UUID.randomUUID(),ActivityResultContracts.StartActivityForResult()) {
            m.onDocumentResult(DnsDocumentKind.IMPORT,it.resultCode,it.data)
        } }
        try {
            if(render)compose.onNodeWithTag("df-select-file").performScrollTo().performClick()
            else compose.runOnIdle { p.onIntent(lease,DnsFilesPanelIntent.SelectImportFile(z)) }
            compose.runOnIdle { val event=checkNotNull(m.documentLaunches.value);launcher.launch(checkNotNull(m.takeDocumentLaunch(event.requestId)).intent) }
            systemDownloads()
            val start=System.currentTimeMillis();val deadline=start+15000
            var selected=false;var searched=false
            while(System.currentTimeMillis()<deadline && !selected) {
                var row=systemNode(automation.rootInActiveWindow) { !it.isEditable && it.text?.toString()==name }
                while(row!=null && !row.isClickable)row=row.parent
                selected=row?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                if(!selected && !searched && System.currentTimeMillis()-start>1000 && systemClick("Search")) {
                    systemNode(automation.rootInActiveWindow) { it.isEditable }?.let { field ->
                        searched=field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)
                        })
                        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
                    }
                }
                if(!selected)Thread.sleep(50)
            }
            assertTrue("The real picker must select the fixture document row, never its editable search field",selected)
            compose.waitUntil(10000) { p.state.value.state.selection==DnsFilesSelection.READY }
            assertEquals(importBytes.size.toLong(),p.state.value.state.file?.length)
            assertEquals(com.flarego.platform.AndroidFingerprint().sha256(importBytes),p.state.value.state.file?.sha256)
            return lease
        } finally {
            compose.runOnIdle { launcher.unregister() }
            if(automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true)automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            // Import may reopen the original source; retain this test-owned file until test cleanup.
            testSourceNames+=name
        }
    }
    private val testSourceNames=mutableListOf<String>()
    @Test fun nativeFileSelectionRequiresExplicitProxyBeforeAnyPreview() {
        val (m,z)=create(true);nativeImportFile(m,z);val p=m.panelPorts.files!!
        assertNull("No proxy choice may be invented on bind or file selection",p.state.value.state.proxied)
        compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        assertEquals(0,importWrites.get());assertNull(p.state.value.state.prepared)
    }
    @Test fun actualImportJobHandoffKeepsSourceAndTypedReceiptAcrossPresentationRebind() {
        val (m,z)=create(true);val l=nativeImportFile(m,z);val p=m.panelPorts.files!!
        compose.onNodeWithTag("df-proxy-false").performScrollTo().performClick()
        val requestedRevision=p.state.value.state.revision
        compose.onNodeWithTag("df-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { p.state.value.state.prepared!=null }
        assertEquals("Preview lifecycle cannot change the independently explicit file/options draft revision",requestedRevision,p.state.value.state.revision)
        assertEquals(0,importWrites.get())
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        onRead={ r -> if(r.method=="POST" && r.path.endsWith("/dns_records/import")) { entered.complete(Unit);withContext(NonCancellable) { release.await() } } }
        compose.onNodeWithTag("df-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { entered.isCompleted }
        lateinit var fresh:ManagementPanelLease
        try { compose.runOnIdle { p.clear(l);fresh=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) } }
        finally { release.complete(Unit) }
        compose.waitUntil(10000) { p.state.value.lease===fresh && !p.state.value.state.loading && p.state.value.state.pendingTasks.any { it.actionKey==DNS_IMPORT_OPERATION && it.state==TaskState.SUCCEEDED } }
        assertEquals(1,importWrites.get());assertArrayEquals(importBytes,receivedImport)
        val task=m.operationStore.list(z.connectionId).single { it.actionKey==DNS_IMPORT_OPERATION }
        val receipt=runBlocking { m.extendedManagement.importOutcome(z,task.id) } as DnsImportOutcome.ReportedSuccess
        assertEquals(1L,receipt.added);assertEquals(1L,receipt.parsed);assertEquals(0,receipt.readback.observedRecords)
        assertEquals("The current same-context presentation must consume the actual receipt, not record-count guesses",receipt,p.state.value.state.outcome?.outcome)
        assertNull(p.state.value.state.prepared)
    }

    @Test fun reopenedRealVmUnknownImportIsReadOnlyHasNoReceiptAndBlocksOtherDnsWrites() {
        loseImportResponse=true
        val (old,z)=create(true);nativeImportFile(old,z);val p=old.panelPorts.files!!
        compose.onNodeWithTag("df-proxy-false").performScrollTo().performClick()
        compose.onNodeWithTag("df-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { p.state.value.state.prepared!=null }
        val issued=p.state.value.state.prepared!!
        compose.onNodeWithTag("df-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { p.state.value.state.pendingTasks.any { it.actionKey==DNS_IMPORT_OPERATION && it.state==TaskState.UNKNOWN } }
        assertEquals(1,importWrites.get());assertArrayEquals(importBytes,receivedImport)
        val task=old.operationStore.list(z.connectionId).single { it.actionKey==DNS_IMPORT_OPERATION }
        val connection=old.controller.state.value.selected!!
        compose.runOnIdle { vmStore.clear() }
        lateinit var fresh:FlareGoViewModel
        loseImportResponse=false
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            fresh=FlareGoViewModel(app,Dispatchers.IO,{acquisitions.incrementAndGet();transport()},{streamAcquisitions.incrementAndGet();object:TransferPort {
                override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("No export during readonly recovery")
            }})
            model=fresh;vmStore.put("extended",fresh)
            fresh.controller.selectConnection(fresh.controller.state.value.connections.single { it.id==connection.id })
            fresh.management.zones.onIntent(ZoneIntent.Load(z.account))
        }
        compose.waitUntil(10000) { !fresh.management.zones.state.value.loading }
        compose.runOnIdle { fresh.management.zones.onIntent(ZoneIntent.Open(z)) }
        compose.waitUntil(10000) { fresh.management.zones.state.value.selected?.ref==z }
        val port=fresh.panelPorts.files!!;lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(port.bind(ManagementPanelContext(z,fresh.controller.operationEpoch))) }
        compose.waitUntil(10000) { !port.state.value.state.loading && port.state.value.state.pendingTasks.any { it.id==task.id } }
        assertNull(port.state.value.state.file);assertNull(port.state.value.state.prepared);assertNull(port.state.value.state.outcome)
        assertNull(runBlocking { fresh.extendedManagement.importOutcome(z,task.id) })
        val index=requests.size
        compose.runOnIdle { port.onIntent(lease,DnsFilesPanelIntent.Reconcile(z,task.id)) }
        compose.waitUntil(10000) { !port.state.value.state.loading && port.state.value.state.pendingTasks.any { it.id==task.id && it.state==TaskState.UNKNOWN } }
        assertEquals(1,importWrites.get());assertTrue(synchronized(requests) { requests.drop(index) }.all { it.method=="GET" })
        val oldConfirm=runBlocking { runCatching { fresh.extendedManagement.confirmExtended(issued.planId,issued.revision) } }
        assertTrue(oldConfirm.exceptionOrNull() is CloudFailure)
        for(command in listOf(
            com.flarego.core.application.management.ExtendedManagementCommand.Scan(DnsScanAction.Apply(z,DnsScanReviewInput(emptyList(),listOf("candidate-id"),mapOf("candidate-id" to "e".repeat(64))))),
            com.flarego.core.application.management.ExtendedManagementCommand.DnsSettings(DnsZoneSettingsAction(z,buildJsonObject { put("flatten_all_cnames",true) }))
        )) {
            val rejected=runBlocking { runCatching { fresh.extendedManagement.prepareExtended(command,1) { true } } }
            assertEquals("Persisted import UNKNOWN must block the exact same-Zone runtime family",CloudFailureCode.UNKNOWN_RESULT,(rejected.exceptionOrNull() as? CloudFailure)?.code)
        }
        val dns=fresh.management.dns
        compose.runOnIdle { dns.onIntent(com.flarego.core.application.dns.DnsIntent.Load(z)) }
        compose.waitUntil(10000) { !dns.state.value.loading && dns.state.value.zoneName!=null }
        compose.runOnIdle { dns.onIntent(com.flarego.core.application.dns.DnsIntent.Preview(DnsRecordInput("TXT","new.extended.example.com",buildJsonObject { put("content","new explicit fixture");put("ttl",60) }),DnsWriteMode.CREATE)) }
        compose.waitUntil(10000) { !dns.state.value.loading && dns.state.value.error!=null }
        assertTrue(dns.state.value.error!!.contains("UNKNOWN_RESULT"));assertNull(dns.state.value.preparedChange)
        val batch=fresh.management.batch!!
        compose.runOnIdle { batch.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Bind(z)) }
        compose.waitUntil(10000) { !batch.state.value.loading && batch.state.value.zoneName!=null }
        val batchInput=com.flarego.core.model.DnsBatchInput(posts=listOf(DnsRecordInput("TXT","batch.extended.example.com",buildJsonObject { put("content","new explicit batch");put("ttl",60) })))
        compose.runOnIdle { batch.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Preview(batchInput)) }
        compose.waitUntil(10000) { !batch.state.value.loading && batch.state.value.error!=null }
        assertTrue(batch.state.value.error!!.contains("UNKNOWN_RESULT"));assertNull(batch.state.value.preparedChange)
        compose.runOnIdle { fresh.controller.navigate(Page.DOMAINS) }
        compose.waitUntil(10000) { fresh.controller.state.value.inventory.domains.any { it.id==z.zoneId && it.connectionId==z.connectionId } }
        val domain=fresh.controller.state.value.inventory.domains.single { it.id==z.zoneId && it.connectionId==z.connectionId }
        compose.runOnIdle { fresh.controller.openDomain(domain) }
        compose.waitUntil(10000) { fresh.controller.state.value.domain==domain && !fresh.controller.state.value.dnsLoading }
        val legacy=runBlocking { fresh.controller.saveDns(domain,null,DnsDraft("TXT","legacy","new explicit legacy",60,false)) }
        assertEquals("该 Zone 的 DNS 写入结果尚未核对，请先只读核对已有任务",legacy.detail)
        assertEquals(1,importWrites.get());assertEquals(0,writes.get())
        assertEquals(TaskState.UNKNOWN,fresh.operationStore.list(z.connectionId).single { it.id==task.id }.state)
        assertTrue(synchronized(requests) { requests.drop(index) }.none { it.method in setOf("POST","PATCH","PUT","DELETE") })
    }

    @Test fun demoScanDnsSettingsAndZoneSettingsPortsUseSameRuntimeAndNeverProductionStore() {
        val (m,z)=create(false);val context=ManagementPanelContext(z,m.controller.operationEpoch)
        val scan=m.panelPorts.scan!!;lateinit var scanLease:ManagementPanelLease
        compose.runOnIdle { scanLease=checkNotNull(scan.bind(context)) }
        compose.waitUntil(10000) { scan.state.value.state.snapshot!=null && scan.state.value.state.loading.isEmpty() }
        compose.runOnIdle { scan.onIntent(scanLease,DnsScanPanelIntent.Preview(DnsScanAction.Trigger(z))) }
        compose.waitUntil(10000) { scan.state.value.state.prepared!=null }
        val scanPlan=scan.state.value.state.prepared!!
        compose.runOnIdle { scan.onIntent(scanLease,DnsScanPanelIntent.Confirm(scanPlan.planId,scanPlan.revision)) }
        compose.waitUntil(10000) { scan.state.value.state.pendingTasks.any { it.actionKey==DNS_SCAN_TRIGGER_OPERATION && it.state==TaskState.ACCEPTED } }
        val dns=m.panelPorts.dnsSettings!!;lateinit var dnsLease:ManagementPanelLease
        compose.runOnIdle { dnsLease=checkNotNull(dns.bind(context)) }
        compose.waitUntil(10000) { dns.state.value.state.snapshot!=null && !dns.state.value.state.loading }
        val dnsAction=DnsZoneSettingsAction(z,buildJsonObject { put("flatten_all_cnames",true) })
        compose.runOnIdle { dns.onIntent(dnsLease,DnsZoneSettingsPanelIntent.DraftChanged(dnsAction));dns.onIntent(dnsLease,DnsZoneSettingsPanelIntent.Preview(dnsAction)) }
        compose.waitUntil(10000) { dns.state.value.state.prepared!=null }
        val dnsPlan=dns.state.value.state.prepared!!
        compose.runOnIdle { dns.onIntent(dnsLease,DnsZoneSettingsPanelIntent.Confirm(dnsPlan.planId,dnsPlan.revision,emptySet())) }
        compose.waitUntil(10000) { dns.state.value.state.pendingTasks.any { it.actionKey==DNS_SETTINGS_PATCH_OPERATION && it.state==TaskState.SUCCEEDED } }
        val settings=m.panelPorts.zoneSettings!!;lateinit var settingsLease:ManagementPanelLease
        compose.runOnIdle { settingsLease=checkNotNull(settings.bind(context)) }
        compose.waitUntil(10000) { settings.state.value.state.snapshots.isNotEmpty() && !settings.state.value.state.loading }
        val action=ZoneSettingsAction.Update(z,ZoneSettingKey.SSL,buildJsonObject { put("value","full") })
        compose.runOnIdle { settings.onIntent(settingsLease,ZoneSettingsPanelIntent.Preview(action)) }
        compose.waitUntil(10000) { settings.state.value.state.prepared!=null }
        val settingPlan=settings.state.value.state.prepared!!
        assertEquals(JsonPrimitive("strict"),settingPlan.before);assertEquals(JsonPrimitive("full"),settingPlan.after)
        compose.runOnIdle { settings.onIntent(settingsLease,ZoneSettingsPanelIntent.Confirm(settingPlan.planId,settingPlan.revision,settingPlan.preview.risk)) }
        compose.waitUntil(10000) { settings.state.value.state.pendingTasks.any { it.state==TaskState.SUCCEEDED } }
        compose.runOnIdle { settings.onIntent(settingsLease,ZoneSettingsPanelIntent.Select(z,ZoneSettingsTarget.Cache)) }
        compose.waitUntil(10000) { settings.state.value.state.snapshots[ZoneSettingsTarget.Cache]!=null && !settings.state.value.state.loading }
        compose.runOnIdle { settings.onIntent(settingsLease,ZoneSettingsPanelIntent.Preview(ZoneSettingsAction.Purge(z,CachePurgeInput.Files(listOf(CachePurgeFile("https://flarego.dev/fixture")))))) }
        compose.waitUntil(10000) { settings.state.value.state.prepared!=null }
        val purge=settings.state.value.state.prepared!!;assertNull(purge.before);assertNull(purge.after)
        compose.runOnIdle { settings.onIntent(settingsLease,ZoneSettingsPanelIntent.Confirm(purge.planId,purge.revision,purge.preview.risk)) }
        compose.waitUntil(10000) { settings.state.value.state.pendingTasks.any { it.actionKey=="zone-purge" && it.state==TaskState.ACCEPTED } }
        assertEquals(0,acquisitions.get());assertEquals(0,streamAcquisitions.get());assertTrue(requests.isEmpty());assertTrue(m.operationStore.list(z.connectionId).isEmpty())
    }

    @Test fun twoActualVmsAndConnectionsShareProductionImportRollingBucket() {
        val (first,firstScope)=create(true)
        val firstLease=nativeImportFile(first,firstScope)
        val firstFile=checkNotNull(first.panelPorts.files!!.state.value.state.file)
        val secondAccount="c4".repeat(16);val secondZone="d4".repeat(16)
        lateinit var second:FlareGoViewModel
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        compose.runOnIdle {
            second=FlareGoViewModel(app,Dispatchers.IO,{acquisitions.incrementAndGet();transport(secondAccount,secondZone)},{streamAcquisitions.incrementAndGet();object:TransferPort {
                override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("Quota target does not export")
            }})
            model=second;vmStore.put("quota-second",second)
            second.viewModelScope.launch { second.connect("Second quota synthetic connection",secondAccount,"second-synthetic-not-a-real-token") }
        }
        compose.waitUntil(10000) { second.controller.state.value.selected?.accountId==secondAccount }
        val secondConnection=second.controller.state.value.selected!!
        val secondScope=ZoneScope(AccountScope(secondConnection.id,secondAccount),secondZone)
        compose.runOnIdle { second.management.zones.onIntent(ZoneIntent.Load(secondScope.account)) }
        compose.waitUntil(10000) { !second.management.zones.state.value.loading }
        compose.runOnIdle { second.management.zones.onIntent(ZoneIntent.Open(secondScope)) }
        compose.waitUntil(10000) { second.management.zones.state.value.selected?.ref==secondScope }
        val secondLease=nativeImportFile(second,secondScope,render=false)
        val secondFile=checkNotNull(second.panelPorts.files!!.state.value.state.file)
        assertNotEquals(firstScope.connectionId,secondScope.connectionId)
        // Both VM constructors wire forApplication(application).files/importLimiter, with no override seam.
        val firstOwner=ManagementPlatformOwner.forApplication(first.getApplication())
        val secondOwner=ManagementPlatformOwner.forApplication(second.getApplication())
        assertSame(firstOwner,secondOwner);assertSame(firstOwner.importLimiter,secondOwner.importLimiter)
        val names=setOf(firstFile.displayName,secondFile.displayName)
        fun openTestDescriptors():Int=java.io.File("/proc/self/fd").listFiles().orEmpty().count { fd ->
            runCatching { android.system.Os.readlink(fd.path) }.getOrNull()?.let { target -> names.any { target.endsWith("/"+it) } }==true
        }
        assertEquals("Actual SAF sources close after selection validation",0,openTestDescriptors())
        val resolver=compose.activity.contentResolver
        val readable=resolver.persistedUriPermissions.single { permission ->
            runCatching { resolver.query(permission.uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                it.moveToFirst() && it.getString(0)==firstFile.displayName
            }==true }.getOrDefault(false)
        }.uri
        resolver.openInputStream(readable)!!.use {
            assertTrue(it.read()>=0)
            assertTrue("Prove /proc observation can actually see this test document while it is open",openTestDescriptors()>0)
        }
        assertEquals(0,openTestDescriptors())
        // Wait a real bounded rolling window if the Application bucket has genuine prior use.
        // Never reset the owner/bucket, replace its clock, reflect internals or retry a send.
        val quietStart=android.os.SystemClock.elapsedRealtime()
        while(android.os.SystemClock.elapsedRealtime()-quietStart<60100)Thread.sleep(100)
        assertTrue(android.os.SystemClock.elapsedRealtime()-quietStart>=60000)
        val startingSends=importWrites.get();val startingCloses=uploadSourceCloses.get()
        val started=android.os.SystemClock.elapsedRealtime()
        fun attempt(vm:FlareGoViewModel,z:ZoneScope,file:DnsFileRef,revision:Long):Result<OperationTask> = runBlocking {
            runCatching {
                val command=com.flarego.core.application.management.ExtendedManagementCommand.Import(z,file,false)
                val currentStamp=firstOwner.currentCredentialStamp(vm.controller.state.value.selected!!)!!
                val plan=vm.extendedManagement.prepareExtended(command,revision) {
                    vm.controller.state.value.selected?.id==z.connectionId && vm.management.zones.state.value.selected?.ref==z &&
                        firstOwner.currentCredentialStamp(vm.controller.state.value.selected!!)==currentStamp
                }
                vm.extendedManagement.confirmExtended(plan.planId,plan.revision)
            }
        }
        assertEquals(TaskState.SUCCEEDED,attempt(first,firstScope,firstFile,1).getOrThrow().state)
        assertEquals(0,openTestDescriptors())
        compose.runOnIdle { first.panelPorts.files!!.clear(firstLease);checkNotNull(first.panelPorts.files!!.bind(ManagementPanelContext(firstScope,first.controller.operationEpoch))) }
        compose.waitUntil(10000) { !first.panelPorts.files!!.state.value.state.loading }
        assertEquals(TaskState.SUCCEEDED,attempt(second,secondScope,secondFile,2).getOrThrow().state)
        assertEquals(0,openTestDescriptors())
        compose.runOnIdle { second.panelPorts.files!!.clear(secondLease);checkNotNull(second.panelPorts.files!!.bind(ManagementPanelContext(secondScope,second.controller.operationEpoch))) }
        compose.waitUntil(10000) { !second.panelPorts.files!!.state.value.state.loading }
        assertEquals(TaskState.SUCCEEDED,attempt(first,firstScope,firstFile,3).getOrThrow().state)
        assertEquals(0,openTestDescriptors())
        val fourth=attempt(second,secondScope,secondFile,4)
        assertEquals(CloudFailureCode.RATE_LIMITED,(fourth.exceptionOrNull() as? CloudFailure)?.code)
        assertTrue("All four admissions are within the genuine rolling window",android.os.SystemClock.elapsedRealtime()-started<60000)
        assertEquals(3,importWrites.get()-startingSends);assertEquals(3,uploadSourceCloses.get()-startingCloses)
        assertEquals("The fourth pre-send source also leaves no actual SAF descriptor open",0,openTestDescriptors())
        val settled=second.operationStore.list(secondScope.connectionId).filter { it.actionKey==DNS_IMPORT_OPERATION }
        assertEquals(1,settled.count { it.state==TaskState.SUCCEEDED });assertEquals(1,settled.count { it.state==TaskState.ACTION_REQUIRED })
        assertTrue(settled.none { it.state in setOf(TaskState.UNKNOWN,TaskState.RUNNING,TaskState.FAILED) })
    }

    private fun assertDemoIsolated(m:FlareGoViewModel,z:ZoneScope) {
        assertEquals(0,acquisitions.get());assertEquals(0,streamAcquisitions.get());assertTrue(requests.isEmpty())
        assertTrue(m.operationStore.list(z.connectionId).isEmpty())
    }
    @Composable private fun EffectiveFont2(content:@Composable ()->Unit) {
        val effective=androidx.compose.ui.platform.LocalDensity.current.fontScale
        SideEffect { assertEquals("Actual native composition must inherit system font2 exactly once",2f,effective,0.001f) }
        FlareGoTheme { content() }
    }
    @Test fun demoNativeScanApplyUserPreviewConfirmUsesActualRuntimeAndNoProduction() {
        val (m,z)=create(false);val p=m.panelPorts.scan!!;lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { p.state.value.state.reviewLoaded && p.state.value.state.loading.isEmpty() }
        compose.setContent { val value by p.state.collectAsState();EffectiveFont2 { DnsScanPanel(z,m.controller.operationEpoch,value.state,{p.onIntent(lease,it)}) } }
        val candidate=p.state.value.state.snapshot!!.candidates.single().id
        compose.onNodeWithTag("ds-accept-"+candidate).performScrollTo().performClick()
        compose.onNodeWithTag("ds-preview-apply").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10000) { p.state.value.state.prepared!=null && p.state.value.state.loading.isEmpty() }
        assertTrue(p.state.value.state.pendingTasks.isEmpty());assertDemoIsolated(m,z)
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { p.state.value.state.pendingTasks.any { it.actionKey==DNS_SCAN_APPLY_OPERATION && it.state==TaskState.SUCCEEDED } }
        assertDemoIsolated(m,z)
    }
    @Test fun demoNativeDnsSettingsUserPreviewConfirmUsesActualRuntimeAndNoProduction() {
        val (m,z)=create(false);val p=m.panelPorts.dnsSettings!!;lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { p.state.value.state.snapshot!=null && !p.state.value.state.loading }
        compose.setContent { val value by p.state.collectAsState();EffectiveFont2 { DnsZoneSettingsPanel(z,m.controller.operationEpoch,value.state,{p.onIntent(lease,it)}) } }
        compose.onNodeWithTag("dz-bool-flatten_all_cnames-true").performScrollTo().performClick()
        compose.onNodeWithTag("dz-preview").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10000) { p.state.value.state.prepared!=null && !p.state.value.state.loading }
        assertTrue(p.state.value.state.pendingTasks.isEmpty());assertDemoIsolated(m,z)
        compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { p.state.value.state.pendingTasks.any { it.actionKey==DNS_SETTINGS_PATCH_OPERATION && it.state==TaskState.SUCCEEDED } }
        assertDemoIsolated(m,z)
    }
    @Test fun demoNativeZoneSettingsRiskClickPreviewConfirmUsesActualRuntimeAndNoProduction() {
        val (m,z)=create(false);val p=m.panelPorts.zoneSettings!!;lateinit var lease:ManagementPanelLease
        compose.runOnIdle { lease=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { p.state.value.state.snapshots.isNotEmpty() && !p.state.value.state.loading }
        compose.setContent { val value by p.state.collectAsState();EffectiveFont2 { ZoneSettingsPanel(z,value.state,{p.onIntent(lease,it)}) } }
        compose.onNodeWithTag("zs-value-full").performScrollTo().performClick()
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10000) { p.state.value.state.prepared!=null && !p.state.value.state.loading }
        assertTrue(p.state.value.state.pendingTasks.isEmpty());assertDemoIsolated(m,z)
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("zs-risk-SSL_DOWNGRADE").performScrollTo().performClick()
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { p.state.value.state.pendingTasks.any { it.state==TaskState.SUCCEEDED } }
        assertDemoIsolated(m,z)
    }

    @Test fun actualReadExportIgnoresList403AndUnknownWriteProofAndSurvivesPresentationRebind() {
        catalogAvailable=false;listForbidden=true
        val (m,z)=create(true);val p=m.panelPorts.files!!;lateinit var l:ManagementPanelLease
        compose.runOnIdle { l=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
        compose.waitUntil(10000) { p.state.value.state.snapshot!=null && !p.state.value.state.loading }
        assertNull(p.state.value.state.snapshot?.writeAllowed);assertEquals(true,p.state.value.state.exportAllowed)
        compose.setContent { val value by p.state.collectAsState();FlareGoTheme { DnsFilesPanel(z,m.controller.operationEpoch,value.state,{p.onIntent(l,it)}) } }
        val name="flarego-b2b-synthetic-export-"+UUID.randomUUID()+".bind";testSourceNames+=name
        lateinit var launcher:androidx.activity.result.ActivityResultLauncher<android.content.Intent>
        compose.runOnIdle { launcher=compose.activity.activityResultRegistry.register("b2b-export-"+UUID.randomUUID(),ActivityResultContracts.StartActivityForResult()) {
            m.onDocumentResult(DnsDocumentKind.EXPORT,it.resultCode,it.data)
        } }
        try {
            compose.onNodeWithTag("df-select-export").performScrollTo().performClick()
            compose.runOnIdle { val event=checkNotNull(m.documentLaunches.value);launcher.launch(checkNotNull(m.takeDocumentLaunch(event.requestId)).intent) }
            systemDownloads()
            val edit=checkNotNull(systemNode(automation.rootInActiveWindow) { it.isEditable })
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name) }))
            assertTrue(systemClick("Save"))
            compose.waitUntil(10000) { p.state.value.state.exportStage==DnsFilesExportStage.READY }
            val resolver=compose.activity.contentResolver
            val target=checkNotNull(p.state.value.state.exportTarget)
            assertTrue(target.displayName.startsWith(name.removeSuffix(".bind")))
            val uri=resolver.persistedUriPermissions.single { permission ->
                runCatching { resolver.query(permission.uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    it.moveToFirst() && it.getString(0)==target.displayName
                }==true }.getOrDefault(false)
            }.uri
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            onTransfer={ entered.complete(Unit);withContext(NonCancellable) { release.await() } }
            compose.onNodeWithTag("df-export").performScrollTo().performClick()
            compose.waitUntil(10000) { entered.isCompleted }
            lateinit var fresh:ManagementPanelLease
            try { compose.runOnIdle { p.clear(l);fresh=checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) } }
            finally { release.complete(Unit) }
            compose.waitUntil(10000) { p.state.value.lease===fresh && !p.state.value.state.loading }
            assertEquals("Completed app-owned export observation must be re-read by a fresh same-route presentation",DnsFilesExportStage.SUCCEEDED,p.state.value.state.exportStage)
            assertArrayEquals(exportBytes,compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(com.flarego.platform.AndroidFingerprint().sha256(exportBytes),p.state.value.state.exportResult?.result?.file?.sha256)
            assertEquals(0,importWrites.get());assertEquals(0,writes.get());assertTrue(m.operationStore.list(z.connectionId).isEmpty())
            val completedRequests=synchronized(requests) { requests.size }
            compose.runOnIdle { m.panelPorts.onRouteExit(fresh);checkNotNull(p.bind(ManagementPanelContext(z,m.controller.operationEpoch))) }
            compose.waitUntil(10000) { !p.state.value.state.loading }
            assertNull("Real route exit cannot restore an old export target",p.state.value.state.exportTarget)
            assertNull("Committed observation belongs to the old actual route generation",p.state.value.state.exportResult)
            assertEquals(DnsFilesExportStage.IDLE,p.state.value.state.exportStage)
            assertTrue(synchronized(requests) { requests.drop(completedRequests) }.none { it.path.endsWith("/dns_records/export") })
        } finally { compose.runOnIdle { launcher.unregister() } }
    }

    @Test fun realRunningGuardFailureRecoversUnknownInNewVmWithoutPayloadOrReplay() {
        val (old,z)=create(true);val lease=bindSec(old,z);val port=old.panelPorts.dnssec!!;val plan=prepareDisable(old,z,lease)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val changed=CompletableDeferred<Unit>()
        onRead={ r -> if(r.method=="GET" && r.path=="/zones/$zoneId/dnssec" && !entered.isCompleted) {
            entered.complete(Unit);withContext(NonCancellable) { release.await() }
        } }
        compose.runOnIdle { port.onIntent(lease,DnssecPanelIntent.Confirm(plan.planId,plan.revision,setOf(DnssecRisk.DISABLE))) }
        compose.waitUntil(10000) { entered.isCompleted }
        try {
            compose.runOnIdle { old.viewModelScope.launch { try { old.connect("Replaced before recovery",account,"replacement-synthetic-token") } finally { changed.complete(Unit) } } }
            compose.waitUntil(10000) { changed.isCompleted }
        } finally { release.complete(Unit) }
        compose.waitUntil(10000) { old.controller.state.value.tasks.any { it.id==plan.planId } }
        assertEquals(0,writes.get());val running=old.operationStore.list(z.connectionId).single { it.id==plan.planId }
        assertEquals(TaskState.RUNNING,running.state)
        val connection=old.controller.state.value.selected!!
        compose.runOnIdle { vmStore.clear() }
        lateinit var fresh:FlareGoViewModel
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            fresh=FlareGoViewModel(app,Dispatchers.IO,{acquisitions.incrementAndGet();transport()},{streamAcquisitions.incrementAndGet();object:TransferPort {
                override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("No transfer during DNSSEC recovery")
            }})
            model=fresh;vmStore.put("extended",fresh)
            fresh.controller.selectConnection(fresh.controller.state.value.connections.single { it.id==connection.id })
            fresh.management.zones.onIntent(ZoneIntent.Load(z.account))
        }
        compose.waitUntil(10000) { !fresh.management.zones.state.value.loading }
        compose.runOnIdle { fresh.management.zones.onIntent(ZoneIntent.Open(z)) }
        compose.waitUntil(10000) { fresh.management.zones.state.value.selected?.ref==z }
        val renewed=bindSec(fresh,z);val newPort=fresh.panelPorts.dnssec!!
        assertEquals(TaskState.UNKNOWN,fresh.operationStore.list(z.connectionId).single { it.id==running.id }.state)
        assertNull(newPort.state.value.state.prepared)
        val oldConfirmation=runBlocking { runCatching { fresh.extendedManagement.confirmExtended(plan.planId,plan.revision,
            com.flarego.core.application.management.ExtendedRiskChoices(dnssec=setOf(DnssecRisk.DISABLE))) } }
        assertTrue(oldConfirmation.exceptionOrNull() is CloudFailure)
        val index=requests.size
        compose.runOnIdle { newPort.onIntent(renewed,DnssecPanelIntent.Reconcile(z,running.id)) }
        compose.waitUntil(10000) { !newPort.state.value.state.loading && newPort.state.value.state.pendingTasks.any { it.id==running.id && it.state==TaskState.UNKNOWN } }
        assertTrue(synchronized(requests) { requests.drop(index) }.all { it.method=="GET" })
        assertEquals(0,writes.get());assertEquals(0,importWrites.get());assertNull(newPort.state.value.state.prepared)
        val recovered=fresh.operationStore.list(z.connectionId).single { it.id==running.id }
        assertEquals(running.requestFingerprint,recovered.requestFingerprint);assertEquals(running.desiredHash,recovered.desiredHash)
        assertEquals(running.resourceRef,recovered.resourceRef);assertEquals(TaskState.UNKNOWN,recovered.state)
    }

}
