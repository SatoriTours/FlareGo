package com.flarego

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.dns.*
import com.flarego.core.application.zones.*
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.ui.*
import com.flarego.ui.dns.DnsPage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real VM/runtime/controllers and product adapters, synthetic transport only; no cloud writes. */
class ManagementIntegrationTest {
    @get:Rule val compose=createComposeRule()
    private val owner=ViewModelStore()
    private var model:FlareGoViewModel?=null
    private var initialIds=emptySet<String>()
    private val accountId="d4".repeat(16)
    private val writes=AtomicInteger()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private val requests=java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    private var row=Json.parseToJsonElement("""{"id":"record","type":"A","name":"www.runtime.example.com","content":"192.0.2.1","ttl":60,"proxied":false,"comment":"old"}""").jsonObject
    private val zone="""{"id":"runtime-zone","name":"runtime.example.com","account":{"id":"$accountId"},"status":"pending","paused":false,"type":"full","name_servers":["example.ns.cloudflare.com"]}"""
    private fun ok(result:String)=HttpResponse(200,"""{"success":true,"result":$result,"result_info":{"page":1,"per_page":50,"total_count":1,"total_pages":1}}""")
    private fun create(real:Boolean):FlareGoViewModel {
        lateinit var m:FlareGoViewModel
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            m=FlareGoViewModel(app,transportForToken={
                assertTrue("demo must not acquire a cloud transport",real)
                HttpTransport { r ->
                    requests+=r
                    when {
                        r.path=="/accounts/$accountId" -> ok("""{"id":"$accountId","name":"Synthetic management fixture"}""")
                        r.path.startsWith("/zones?") -> ok("[$zone]")
                        r.path=="/zones/runtime-zone" -> ok(zone)
                        r.path.startsWith("/zones/runtime-zone/dns_records?") -> ok("[$row]")
                        r.path=="/zones/runtime-zone/dns_records/record" && r.method=="GET" -> ok(row.toString())
                        r.path=="/zones/runtime-zone/dns_records/record" && r.method=="PATCH" -> {
                            writes.incrementAndGet();row=JsonObject(row+Json.parseToJsonElement(r.body!!).jsonObject);ok(row.toString())
                        }
                        else -> error("unexpected typed management route")
                    }
                }
            })
            model=m;owner.put("management",m);initialIds=m.controller.state.value.connections.map { it.id }.toSet()
            if(real) {
                assertFalse(m.controller.state.value.connections.any { it.accountId==accountId })
                m.viewModelScope.launch { m.connect("Synthetic management fixture",accountId,"synthetic-management-token-not-real") }
            }
        }
        if(real) compose.waitUntil(10000) { m.controller.state.value.selected?.accountId==accountId }
        return m
    }
    @After fun cleanup() {
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initialIds && !it.demo }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initialIds && !it.demo }!=false }
        compose.runOnIdle { owner.clear() }
    }
    @Test fun appSearchFiltersProductionZonesAndSystemBackReturnsToList() {
        val m = create(true)
        compose.runOnIdle { m.onManagementResume() }
        compose.setContent { FlareGoApp(m.controller, m::connect, m::disconnect, {}, management = m.management) }
        compose.onNodeWithTag("nav-domains").performClick()
        compose.waitUntil(10000) { m.management.zones.state.value.zones.isNotEmpty() }
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("missing.example")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.onNodeWithTag("zone-row-runtime-zone").assertDoesNotExist()
        compose.onNodeWithText("没有匹配的域名").assertIsDisplayed()
        screenshot("zones-search-empty")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput(" RUNTIME.EXAMPLE ")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.onNodeWithTag("zone-row-runtime-zone").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消搜索").performClick()
        compose.onNodeWithTag("zone-row-runtime-zone").performClick()
        compose.onNodeWithTag("zone-detail-name").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10000) { m.management.zones.state.value.selected == null && !m.management.zones.state.value.loading }
        compose.onNodeWithTag("zone-row-runtime-zone").assertIsDisplayed()
        compose.onNodeWithTag("zone-create").performClick()
        compose.onNodeWithTag("zone-create-name").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("zone-row-runtime-zone").assertIsDisplayed()
        screenshot("zones-returned")
        compose.runOnIdle { assertEquals(0, writes.get()) }
    }

    @Test fun productionVmUsesFreshOwnerAndDeltaPatchAfterExplicitConfirmation() {
        val m=create(true)
        val connection=m.controller.state.value.selected!!
        val scope=ZoneScope(AccountScope(connection.id,accountId),"runtime-zone")
        compose.runOnIdle { m.management.dns.onIntent(DnsIntent.Load(scope)) }
        compose.waitUntil(10000) { !m.management.dns.state.value.loading }
        compose.runOnIdle {
            assertEquals("runtime.example.com",m.management.dns.state.value.zoneName)
            m.management.dns.onIntent(DnsIntent.Preview(DnsRecordInput("A","www.runtime.example.com",buildJsonObject { put("comment","confirmed change") }),DnsWriteMode.PATCH,"record"))
        }
        compose.waitUntil(10000) { !m.management.dns.state.value.loading }
        compose.runOnIdle {
            assertEquals(0,writes.get());val p=m.management.dns.state.value.preparedChange!!
            m.management.dns.onIntent(DnsIntent.Confirm(p.id,p.revision))
        }
        compose.waitUntil(10000) { m.management.dns.state.value.pendingTask!=null && !m.management.dns.state.value.loading }
        compose.runOnIdle {
            assertEquals(TaskState.SUCCEEDED,m.management.dns.state.value.pendingTask!!.state)
            assertNull(m.management.dns.state.value.mode);assertEquals(1,writes.get())
            assertEquals(setOf("comment"),Json.parseToJsonElement(requests.single { it.method=="PATCH" }.body!!).jsonObject.keys)
            assertTrue(requests.count { it.path=="/zones/runtime-zone" }>=2)
        }
    }
    @Test fun actualDemoControllerPageConfirmsAndShowsSuccessWithoutPersistentCloudTasks() {
        val m=create(false)
        compose.runOnIdle {
            m.controller.reloadConnections(m.controller.state.value.connections+com.flarego.core.demo.demoConnections)
            val demo=m.controller.state.value.connections.first { it.id=="demo-production" }
            m.controller.selectConnection(demo)
            // Match MainActivity's public lifecycle admission before dispatching the direct page load.
            m.onManagementResume()
            m.management.dns.onIntent(DnsIntent.Load(ZoneScope(AccountScope(demo.id,demo.accountId),"zone-demo")))
        }
        compose.waitUntil(10000) { m.management.dns.state.value.zoneName!=null && !m.management.dns.state.value.loading }
        val confirmedClicks=AtomicInteger()
        compose.setContent { val state by m.management.dns.state.collectAsState();FlareGoTheme { DnsPage(state,{ intent ->
            if(intent is DnsIntent.Confirm)confirmedClicks.incrementAndGet()
            m.management.dns.onIntent(intent)
        }) } }
        compose.onNodeWithTag("dns-demo-notice").assertExists()
        compose.runOnIdle { m.management.dns.onIntent(DnsIntent.BeginCreate("TXT"));m.management.dns.onIntent(DnsIntent.Preview(DnsRecordInput("TXT","integration.flarego.dev",buildJsonObject { put("content","示例集成");put("ttl",60) }),DnsWriteMode.CREATE)) }
        compose.waitUntil(10000) { m.management.dns.state.value.preparedChange!=null }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("dns-preview-confirm").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("The real visible native button must deliver exactly one Confirm intent",1,confirmedClicks.get()) }
        try {
            compose.waitUntil(10000) { m.management.dns.state.value.pendingTask?.state==TaskState.SUCCEEDED && !m.management.dns.state.value.loading }
        } catch(failure:Exception) {
            val current=m.management.dns.state.value
            compose.onRoot().printToLog("DemoDnsCompatibility")
            throw AssertionError("Native Demo confirmation did not settle: clicks=${confirmedClicks.get()}, mode=${current.mode}, loading=${current.loading}, revision=${current.revision}, prepared=${current.preparedChange!=null}, task=${current.pendingTask?.state}, safeError=${current.error}",failure)
        }
        compose.onNodeWithTag("dns-task-status").assertExists()
        compose.runOnIdle { assertTrue(requests.isEmpty());assertTrue(m.operationStore.list("demo-production").isEmpty());assertNull(m.management.dns.state.value.mode) }
    }
    @Test fun demoFullAppRoutesZoneDetailsAndDnsThroughLifetimePorts() {
        val m=create(false)
        compose.runOnIdle {
            m.controller.reloadConnections(m.controller.state.value.connections+com.flarego.core.demo.demoConnections)
            m.controller.selectConnection(m.controller.state.value.connections.first { it.id=="demo-production" })
        }
        compose.setContent { FlareGoTheme { FlareGoApp(m.controller,{_,_,_->},{},{},management=m.management) } }
        compose.onNodeWithTag("nav-domains").performClick()
        compose.waitUntil(10000) { m.management.zones.state.value.zones.isNotEmpty() }
        compose.onNodeWithTag("zone-demo-notice").assertExists()
        compose.onNodeWithTag("zone-row-zone-demo").performScrollTo().performClick()
        compose.waitUntil(10000) { m.management.zones.state.value.selected!=null }
        compose.onNodeWithTag("zone-status").assertExists()
        compose.onNodeWithTag("zone-tab-dns").performClick()
        compose.waitUntil(10000) { m.management.dns.state.value.rows.isNotEmpty() }
        compose.onNodeWithTag("dns-demo-notice").assertExists()
        compose.runOnIdle {
            assertEquals("flarego.dev",m.management.dns.state.value.zoneName)
            assertTrue(requests.isEmpty())
            m.controller.navigate(Page.RESOURCES)
            assertNull(m.management.dns.state.value.scope);assertNull(m.management.zones.state.value.account)
        }
    }

}
