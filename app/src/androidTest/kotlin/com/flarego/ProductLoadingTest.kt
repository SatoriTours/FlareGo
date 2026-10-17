package com.flarego

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.AppController
import com.flarego.core.application.inventory.*
import com.flarego.core.cloudflare.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.platform.LocalDatabase
import com.flarego.ui.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProductLoadingTest {
    @get:Rule val compose=createComposeRule()
    private val connection=Connection("local","Account","c".repeat(32))
    private val account=AccountScope(connection.id,connection.accountId)
    private val calls=mutableListOf<ProductKey>()
    private var controller:AppController?=null
    private val owner=ViewModelStore()
    private var model:FlareGoViewModel?=null
    private var initialIds=emptySet<String>()
    private var failR2=false
    private var checked=0
    private fun install(page:Page=Page.OVERVIEW,tasks:List<OperationTask> = emptyList()) {
        compose.setContent {
            val scope=rememberCoroutineScope()
            val c=remember {
                AppController(scope,SessionFactory { DemoSession(it) },MemoryStore(),listOf(connection),productFactory=ProductSessionFactory {
                    ProductServices(object:ProductDirectoryPort {
                        override suspend fun zones(account:AccountScope,request:PageRequest):CloudPage<Domain> { calls+=ProductKey.ZONES;return CloudPage(listOf(Domain("zone","zone.example","active",connection.id)),null,1) }
                        override suspend fun resources(account:AccountScope,kind:ResourceKind,request:PageRequest):CloudPage<CloudResource> {
                            calls+=kind.product()
                            if(kind==ResourceKind.R2 && failR2) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"需要 R2 权限")
                            val second=request.token!=null
                            return CloudPage(listOf(CloudResource(if(second) "r2" else "r1", if(second) "second-resource" else "first-resource",kind,"真实目录",resourceRef=ResourceRef(account,"resource",if(second) "r2" else "r1"))),if(second) null else PageToken.Number(2),2)
                        }
                    },object:ScopedBillingPort {
                        override suspend fun history(scope:CloudScope,request:PageRequest):CloudPage<BillingEntry> { calls+=ProductKey.BILLING;return CloudPage(listOf(BillingEntry("charge",scope,null,null,"USD","account-charge",null,null,null)),null,1) }
                    },object:CapabilityPort { override suspend fun status(scope:CloudScope,actionKey:String)=CapabilityStatus(actionKey,scope) })
                }).also { controller=it;it.publishTasks(tasks);it.navigate(page) }
            }
            FlareGoTheme { FlareGoApp(c,{_,_,_->},{},{},reconcileTask={checked++}) }
        }
    }
    @After fun cleanup() {
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter {it.id !in initialIds}.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initialIds } != false }
        compose.runOnIdle { owner.clear() }
    }
    @Test fun overviewDoesNotInventResourceTotalsOrFetchProducts() {
        install();compose.onAllNodesWithText("未加载").assertCountEquals(2)
        compose.onNodeWithText("0 个已激活").assertDoesNotExist()
        compose.runOnIdle {assertTrue(calls.isEmpty())}
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("刷新全部产品"))
        compose.onNodeWithText("刷新全部产品").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(ProductKey.entries.toSet(),calls.toSet())}
    }
    @Test fun domainTabOnlyLoadsZonesAndShowsLoadedCount() {
        install();compose.onNodeWithTag("nav-domains").performClick()
        compose.onNodeWithText("zone.example").assertIsDisplayed()
        compose.onNodeWithText("已加载 1 · 全部").assertIsDisplayed()
        compose.runOnIdle {assertEquals(listOf(ProductKey.ZONES),calls)}
    }
    @Test fun productSelectorLoadsOnlyRequestedKindAndPreservesOtherSuccess() {
        failR2=true;install(Page.RESOURCES)
        compose.onNodeWithText("R2").performClick()
        compose.onNodeWithText("需要 R2 权限").assertIsDisplayed()
        compose.onNodeWithText("Workers").performClick()
        compose.onNodeWithText("first-resource").assertIsDisplayed()
        compose.runOnIdle {assertEquals(listOf(ProductKey.WORKERS,ProductKey.R2),calls)}
    }
    @Test fun paginationAppendsAndOnlyThenLabelsComplete() {
        install(Page.RESOURCES)
        compose.onNodeWithText("已加载 1 · 尚未完整").assertIsDisplayed()
        compose.onNodeWithText("加载更多").performClick()
        compose.onNodeWithText("second-resource").assertIsDisplayed()
        compose.onNodeWithText("已加载 2 · 全部").assertIsDisplayed()
    }
    @Test fun accountBillingIsMountedWithoutUserFallbackOrFakeAmount() {
        install(Page.BILLING)
        compose.onNodeWithText("当前账号账单历史").assertIsDisplayed()
        compose.onNodeWithText("未知 USD").assertIsDisplayed()
        compose.onNodeWithText("用户级账单历史 · 旧接口").assertDoesNotExist()
        compose.runOnIdle {assertEquals(listOf(ProductKey.BILLING),calls)}
    }
    @Test fun taskPageShowsNewStateAndReadOnlyReconcileAction() {
        val task=OperationTask("task",connection.id,ZoneScope(account,"zone"),"dns-records-for-a-zone-create-dns-record",null,"a".repeat(64),null,TaskState.UNKNOWN,"结果待核对",null,null,1,1)
        install(Page.JOBS,listOf(task))
        compose.onNodeWithText("结果待核对").assertIsDisplayed()
        compose.onNodeWithText("只读核对").performClick()
        compose.runOnIdle {assertEquals(1,checked)}
    }
    @Test fun productionViewModelUsesModernLazyPortsWithAccountBilling() {
        val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m=FlareGoViewModel(app) { HttpTransport { r ->
                requests+=r.path
                val result=when {
                    r.path=="/accounts/${connection.accountId}" -> """{"id":"${connection.accountId}","name":"Account"}"""
                    r.path.startsWith("/zones?") -> """[{"id":"zone","name":"zone.example","status":"active","account":{"id":"${connection.accountId}"}}]"""
                    r.path.startsWith("/accounts/${connection.accountId}/workers/workers?") -> """[{"id":"worker","name":"modern-worker"}]"""
                    r.path=="/accounts/${connection.accountId}/workers/scripts" -> """[{"id":"old-script"}]"""
                    r.path.startsWith("/user/billing/history?") -> """[{"id":"user-charge","amount":1,"currency":"USD"}]"""
                    r.path.startsWith("/accounts/${connection.accountId}/billing/history?") -> """[{"id":"charge","amount":0,"currency":"USD"}]"""
                    else -> error("Unexpected product request")
                }
                HttpResponse(200,"""{"success":true,"result":$result,"result_info":{"page":1,"per_page":50,"total_count":1}}""")
            } }
            model=m;owner.put("product",m);initialIds=m.controller.state.value.connections.map{it.id}.toSet()
            assertFalse(m.controller.state.value.connections.any {it.accountId==connection.accountId})
            m.viewModelScope.launch {m.connect("Account",connection.accountId,"synthetic-product-token")}
        }
        compose.waitUntil(10000) {model?.controller?.state?.value?.selected?.accountId==connection.accountId}
        compose.runOnIdle { assertEquals(listOf("/accounts/${connection.accountId}"),requests.toList());model!!.controller.navigate(Page.DOMAINS) }
        compose.waitUntil(10000) {model!!.controller.state.value.inventory.domains.isNotEmpty()}
        compose.runOnIdle { model!!.controller.navigate(Page.RESOURCES) }
        compose.waitUntil(10000) {model!!.controller.state.value.inventory.resources.any {it.name=="modern-worker"}}
        compose.runOnIdle {model!!.controller.navigate(Page.BILLING)}
        compose.waitUntil(10000) {model!!.controller.state.value.products[ProductKey.BILLING]?.observedAtMs!=null}
        compose.runOnIdle {
            assertEquals(4,requests.size)
            assertTrue(requests.none {it.startsWith("/user/") || it.contains("/workers/scripts")})
            assertEquals("0",(model!!.controller.state.value.products.getValue(ProductKey.BILLING).content as ProductContent.Billing).items.single().amount)
        }
        compose.runOnIdle {model!!.controller.selectBillingSource(BillingSource.USER_HISTORY_LEGACY)}
        compose.waitUntil(10000) {model!!.controller.state.value.billing?.invoices?.isNotEmpty()==true}
        compose.runOnIdle {model!!.controller.navigate(Page.RESOURCES);model!!.controller.selectWorkersMode(WorkersDirectoryMode.LEGACY)}
        compose.waitUntil(10000) {model!!.controller.state.value.inventory.resources.any {it.id=="old-script" && it.legacy}}
        compose.runOnIdle {assertEquals(6,requests.size)}
    }
    @Test fun oldUserBillingIsAnExplicitSeparatelyLabeledChoice() {
        install(Page.BILLING)
        compose.onNodeWithText("用户账单（旧接口）").performClick()
        compose.onNodeWithText("用户级账单历史 · 旧接口").assertIsDisplayed()
        compose.onNodeWithText("当前账号账单历史").assertDoesNotExist()
    }
    @Test fun workerLegacyDirectoryRequiresAnExplicitChoice() {
        install(Page.RESOURCES)
        compose.onNodeWithText("Workers 旧目录").performClick()
        compose.onNodeWithText("旧目录未提供，请在官方控制台查看").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ProductKey.WORKERS),calls) }
    }

    @Test fun productionStartupRecoversStoredTaskWithoutLoadingCredentialsOrReplaying() {
        var network=0
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val database=LocalDatabase.open(app)
            initialIds=database.localStore.connections().map {it.id}.toSet()
            val saved=Connection("task9-recovery","Recovery","d".repeat(32))
            assertFalse(saved.id in initialIds)
            database.localStore.putConnection(saved)
            val task=OperationTask("recovery-task",saved.id,ZoneScope(AccountScope(saved.id,saved.accountId),"zone"),"dns-records-for-a-zone-create-dns-record",null,"a".repeat(64),null,TaskState.RUNNING,"执行中",null,null,1,1)
            database.operationStore.putWithSummary(task,OperationTaskPersistence.summaryFor(task))
            database.close()
            val m=FlareGoViewModel(app) { HttpTransport {network++;error("Recovery must not send") } }
            model=m;owner.put("recovery",m)
        }
        compose.waitUntil(10000) {model!!.controller.state.value.tasks.any {it.id=="recovery-task" && it.state==TaskState.UNKNOWN}}
        compose.runOnIdle {
            assertEquals(0,network)
            val task=model!!.operationStore.get("recovery-task")!!
            assertEquals(TaskState.UNKNOWN,task.state)
            assertEquals(OperationTaskPersistence.summaryDetailFor(TaskState.UNKNOWN),task.safeDetail)
        }
    }

    @Test fun productionDnsWritePersistsUnknownAndReadOnlyPatchCheckConfirmsWithoutReplay() {
        val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
        var writeDone=false
        var reconcileStart=0
        val providerOwner="""{"id":"zone","name":"zone.example","status":"active","account":{"id":"${connection.accountId}"},"paused":false,"type":"full","name_servers":[]}"""
        val providerRecord="""{"id":"dns-record","type":"A","name":"www.zone.example","content":"192.0.2.1","ttl":1,"proxied":false}"""
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m=FlareGoViewModel(app) { HttpTransport { r ->
                requests+="${r.method} ${r.path}"
                val result=when {
                    r.path=="/accounts/${connection.accountId}" -> """{"id":"${connection.accountId}","name":"Account"}"""
                    r.path.startsWith("/zones?") -> """[{"id":"zone","name":"zone.example","status":"active","account":{"id":"${connection.accountId}"}}]"""
                    r.method=="PATCH" && r.path=="/zones/zone/dns_records/dns-record" -> """{"id":"dns-record"}"""
                    r.method=="GET" && r.path=="/zones/zone" -> providerOwner
                    r.method=="GET" && r.path=="/zones/zone/dns_records/dns-record" -> providerRecord
                    r.method=="GET" && r.path.startsWith("/zones/zone/dns_records?") -> "[$providerRecord]"
                    else -> error("Unexpected DNS request")
                }
                HttpResponse(200,"""{"success":true,"result":$result,"result_info":{"page":1,"per_page":50,"total_count":1}}""")
            } }
            model=m;owner.put("dns",m);initialIds=m.controller.state.value.connections.map{it.id}.toSet()
            assertFalse(m.controller.state.value.connections.any {it.accountId==connection.accountId})
            m.viewModelScope.launch {m.connect("Account",connection.accountId,"synthetic-dns-token");m.controller.navigate(Page.DOMAINS)}
        }
        compose.waitUntil(10000) {model!!.controller.state.value.inventory.domains.isNotEmpty()}
        compose.runOnIdle {
            val m=model!!;val domain=m.controller.state.value.inventory.domains.single();m.controller.openDomain(domain)
            m.viewModelScope.launch {m.controller.saveDns(domain,"dns-record",DnsDraft("A","www.zone.example","192.0.2.1"));writeDone=true}
        }
        compose.waitUntil(10000) {writeDone && model!!.controller.state.value.tasks.size==1}
        compose.runOnIdle {
            val m=model!!;val task=m.controller.state.value.tasks.single()
            assertEquals(TaskState.UNKNOWN,task.state)
            assertEquals(task,m.operationStore.get(task.id))
            reconcileStart=requests.size
            m.viewModelScope.launch {m.reconcileTask(task.id)}
        }
        compose.waitUntil(10000) {model!!.controller.state.value.tasks.single().state==TaskState.SUCCEEDED}
        compose.runOnIdle {
            val actual=synchronized(requests) { requests.toList() }
            assertEquals(1,actual.count {it.startsWith("PATCH ")})
            val readback=actual.drop(reconcileStart)
            assertTrue("Reconciliation must only read",readback.isNotEmpty() && readback.all {it.startsWith("GET ")})
            assertTrue("Provider owner must be freshly verified",readback.contains("GET /zones/zone"))
            assertTrue("Provider detail must prove this exact persisted target",readback.contains("GET /zones/zone/dns_records/dns-record"))
            assertEquals(TaskState.SUCCEEDED,model!!.operationStore.get(model!!.controller.state.value.tasks.single().id)!!.state)
        }
    }

    @Test fun productionDisconnectDoesNotBlockUiOnMetadataIo() {
        val dispatcher=object:CoroutineDispatcher() {
            @Volatile var pause=false
            val completed=java.util.concurrent.atomic.AtomicInteger()
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {
                val work=Runnable {try {block.run()} finally {completed.incrementAndGet()}}
                if(pause) Thread {Thread.sleep(1200);Dispatchers.IO.dispatch(context,work)}.start()
                else Dispatchers.IO.dispatch(context,work)
            }
        }
        lateinit var saved:Connection
        compose.runOnIdle {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val database=LocalDatabase.open(app);initialIds=database.localStore.connections().map{it.id}.toSet()
            saved=Connection("task9-disconnect","Disconnect","e".repeat(32))
            assertFalse(saved.id in initialIds);database.localStore.putConnection(saved);database.close()
            val m=FlareGoViewModel(app,dispatcher) {HttpTransport {error("No cloud requests")}}
            model=m;owner.put("disconnect",m)
        }
        compose.waitUntil(10000) {dispatcher.completed.get()>=2}
        var elapsed=0L
        compose.runOnIdle {dispatcher.pause=true;val started=System.nanoTime();model!!.disconnect(saved);elapsed=(System.nanoTime()-started)/1000000}
        compose.waitUntil(10000) {model!!.controller.state.value.connections.none {it.id==saved.id}}
        assertTrue("disconnect must return to UI before paused metadata IO finishes; elapsed=$elapsed",elapsed<500)
    }

}
