package com.flarego.core

import com.flarego.core.application.inventory.*
import com.flarego.core.application.AppController
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.demo.DemoSession
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductLoaderTest {
    private val a = Connection("a", "A", "account-a")
    private val b = Connection("b", "B", "new-account")
    private val calls = mutableListOf<ProductKey>()
    private var read: suspend (AccountScope, ProductKey, PageRequest) -> CloudPage<*> = { s,k,_ ->
        when(k) {
            ProductKey.ZONES -> CloudPage(listOf(Domain("z", s.accountId, "active", s.connectionId)), null, 1)
            ProductKey.BILLING -> CloudPage(emptyList<BillingEntry>(), null, 0)
            else -> CloudPage(listOf(CloudResource("r", s.accountId, kind(k), "", resourceRef = ResourceRef(s,"resource","r"))), null, 1)
        }
    }
    private fun kind(k: ProductKey) = when(k) { ProductKey.WORKERS -> ResourceKind.WORKER; ProductKey.R2 -> ResourceKind.R2; ProductKey.D1 -> ResourceKind.D1; else -> ResourceKind.KV }
    @Suppress("UNCHECKED_CAST")
    private val factory = ProductSessionFactory {
        ProductServices(object : ProductDirectoryPort {
            override suspend fun zones(account: AccountScope, request: PageRequest): CloudPage<Domain> { calls += ProductKey.ZONES; return read(account,ProductKey.ZONES,request) as CloudPage<Domain> }
            override suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest): CloudPage<CloudResource> {
                val k = when(kind) { ResourceKind.WORKER -> ProductKey.WORKERS; ResourceKind.R2 -> ProductKey.R2; ResourceKind.D1 -> ProductKey.D1; ResourceKind.KV -> ProductKey.KV }
                calls += k; return read(account,k,request) as CloudPage<CloudResource>
            }
        }, object : ScopedBillingPort {
            override suspend fun history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry> { calls += ProductKey.BILLING; return read(scope as AccountScope,ProductKey.BILLING,request) as CloudPage<BillingEntry> }
        }, object : CapabilityPort { override suspend fun status(scope: CloudScope, actionKey: String): CapabilityStatus = error("No speculative permission probe") })
    }
    private fun TestScope.loader(selected: StateFlow<AccountScope?>, rev: (String)->Long = {0}, f: ProductSessionFactory = factory) =
        ProductLoader(backgroundScope, f, selected, rev, { s -> listOf(a,b).find { it.id == s.connectionId && it.accountId == s.accountId } }, {123})
    private fun account(c:Connection) = AccountScope(c.id,c.accountId)

    @Test fun productRequestsAreLazyAndIndependent() = runTest {
        val l=loader(MutableStateFlow(account(a))); runCurrent(); assertTrue(calls.isEmpty())
        l.load(ProductKey.ZONES); runCurrent(); assertEquals(listOf(ProductKey.ZONES),calls)
        l.load(ProductKey.R2); runCurrent(); assertEquals(listOf(ProductKey.ZONES,ProductKey.R2),calls)
        assertEquals(setOf(ProductKey.ZONES,ProductKey.R2),l.states.value.keys)
    }
    @Test fun failureDoesNotEraseOtherProductsOrPreviousPage() = runTest {
        val l=loader(MutableStateFlow(account(a))); l.load(ProductKey.ZONES); runCurrent()
        val old=l.states.value[ProductKey.ZONES]
        read={_,_,_->throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"需要权限")}
        l.load(ProductKey.R2); runCurrent(); assertEquals(old,l.states.value[ProductKey.ZONES])
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED,l.states.value[ProductKey.R2]?.error?.code)
        l.load(ProductKey.ZONES); runCurrent(); assertEquals(old?.content,l.states.value[ProductKey.ZONES]?.content)
    }
    @Test fun switchCancelsReadAndOldNonCancellableResultCannotPublishAfterReturn() = runTest {
        val selected=MutableStateFlow<AccountScope?>(account(a)); val gate=CompletableDeferred<Unit>()
        read={s,_,_-> withContext(NonCancellable) { gate.await() }; CloudPage(listOf(Domain("old", "old", "active",s.connectionId)),null,1) }
        val l=loader(selected); l.load(ProductKey.ZONES); runCurrent(); assertEquals(listOf(ProductKey.ZONES),calls)
        selected.value=account(b); runCurrent(); assertTrue(l.states.value.isEmpty())
        selected.value=account(a); runCurrent(); gate.complete(Unit); runCurrent(); assertTrue(l.states.value.isEmpty())
    }
    @Test fun newAccountWinsOverOldRead() = runTest {
        val selected=MutableStateFlow<AccountScope?>(account(a)); val gate=CompletableDeferred<Unit>()
        val normal=read; read={s,k,r -> if(s.connectionId==a.id) withContext(NonCancellable){gate.await()}; normal(s,k,r)}
        val l=loader(selected); l.load(ProductKey.ZONES); runCurrent(); selected.value=account(b); runCurrent()
        l.load(ProductKey.ZONES); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("new-account", l.states.value[ProductKey.ZONES]?.scope?.accountId)
    }
    @Test fun credentialChangeDuringSuspendingFactoryDiscardsOldServices() = runTest {
        var rev=0L; val gate=CompletableDeferred<Unit>(); var attempt=0
        val f=ProductSessionFactory { c -> if(++attempt==1) withContext(NonCancellable){gate.await()}; factory.create(c) }
        val l=loader(MutableStateFlow(account(a)),{rev},f); l.load(ProductKey.ZONES); runCurrent()
        rev++; l.invalidate(a.id); l.load(ProductKey.R2); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(listOf(ProductKey.R2),calls); assertEquals(1,l.states.value[ProductKey.R2]?.credentialRevision)
        assertFalse(ProductKey.ZONES in l.states.value)
    }
    @Test fun unknownPaginationNeverClaimsCompleteAndContinuationAppends() = runTest {
        var page=0
        read={s,_,r -> page++; CloudPage(listOf(Domain("z$page","page$page","active",s.connectionId)),if(r.token==null) PageToken.Number(2) else null,2) }
        val l=loader(MutableStateFlow(account(a))); l.load(ProductKey.ZONES); runCurrent()
        assertFalse(l.states.value.getValue(ProductKey.ZONES).allPagesLoaded)
        l.load(ProductKey.ZONES,PageRequest(token=PageToken.Number(2))); runCurrent()
        val result=l.states.value.getValue(ProductKey.ZONES)
        assertEquals(2,(result.content as ProductContent.Zones).items.size); assertTrue(result.allPagesLoaded)
        read={_,_,_->CloudPage(emptyList<Domain>(),null,null)}
        l.load(ProductKey.ZONES); runCurrent(); assertFalse(l.states.value.getValue(ProductKey.ZONES).allPagesLoaded)
    }
    @Test fun continuationCannotSkipFirstPageOrLoop() = runTest {
        val l=loader(MutableStateFlow(account(a))); l.load(ProductKey.ZONES,PageRequest(token=PageToken.Number(2))); runCurrent(); assertTrue(calls.isEmpty())
        read={s,_,_->CloudPage(listOf(Domain("z","zone","active",s.connectionId)),PageToken.Cursor("loop"),null)}
        l.load(ProductKey.ZONES); runCurrent(); l.load(ProductKey.ZONES,PageRequest(token=PageToken.Cursor("loop"))); runCurrent()
        assertEquals(CloudFailureCode.INVALID_RESPONSE,l.states.value[ProductKey.ZONES]?.error?.code)
        assertFalse(l.states.value.getValue(ProductKey.ZONES).allPagesLoaded)
    }
    @Test fun crossAccountContentRejectedAndGenericErrorsSanitized() = runTest {
        val l=loader(MutableStateFlow(account(a))); read={_,_,_->CloudPage(listOf(Domain("z","zone","active","other")),null,1)}
        l.load(ProductKey.ZONES); runCurrent(); assertEquals(CloudFailureCode.SCOPE_MISMATCH,l.states.value[ProductKey.ZONES]?.error?.code)
        read={_,_,_->error("Bearer secret")}; l.load(ProductKey.R2); runCurrent()
        assertFalse(l.states.value[ProductKey.R2]?.error?.publicMessage.orEmpty().contains("secret"))
    }
    @Test fun refreshOfSameProductSupersedesSuspendedRead() = runTest {
        val gate=CompletableDeferred<Unit>(); var n=0
        read={s,_,_->val id=++n; if(id==1) withContext(NonCancellable){gate.await()}; CloudPage(listOf(Domain("$id","zone","active",s.connectionId)),null,1)}
        val l=loader(MutableStateFlow(account(a))); l.load(ProductKey.ZONES); runCurrent(); l.load(ProductKey.ZONES); runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals("2",(l.states.value.getValue(ProductKey.ZONES).content as ProductContent.Zones).items.single().id)
    }
    @Test fun controllerNavigationLoadsOnlyItsProductAndOverviewDoesNotFetch() = runTest {
        val c=AppController(backgroundScope,SessionFactory { error("Legacy inventory must not run") },MemoryStore(),listOf(a,b),productFactory=factory)
        c.refresh(); runCurrent(); assertTrue(calls.isEmpty()); assertTrue(c.state.value.productLoading)
        c.navigate(Page.DOMAINS); runCurrent(); assertEquals(listOf(ProductKey.ZONES),calls)
        assertEquals(1,c.state.value.inventory.domains.size)
        c.navigate(Page.RESOURCES); runCurrent(); c.selectResourceKind(ResourceKind.R2); runCurrent()
        assertEquals(listOf(ProductKey.ZONES,ProductKey.WORKERS,ProductKey.R2),calls)
        c.navigate(Page.BILLING); runCurrent(); assertEquals(ProductKey.BILLING,calls.last())
        c.selectConnection(b); runCurrent(); assertEquals(account(b),c.state.value.products[ProductKey.BILLING]?.scope)
        assertTrue(c.state.value.inventory.domains.isEmpty())
    }
    @Test fun explicitRefreshAllKeepsSuccessfulProductsWhenOneIsForbidden() = runTest {
        val normal=read; read={s,k,r -> if(k==ProductKey.R2) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"需要权限"); normal(s,k,r)}
        val c=AppController(backgroundScope,SessionFactory { DemoSession(it) },MemoryStore(),listOf(a),productFactory=factory)
        c.refreshAllProducts(); runCurrent(); assertEquals(ProductKey.entries.toSet(),calls.toSet())
        assertEquals(6,c.state.value.products.size); assertEquals(1,c.state.value.inventory.domains.size)
        assertNull(c.state.value.products[ProductKey.D1]?.error)
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED,c.state.value.products[ProductKey.R2]?.error?.code)
    }
    @Test fun controllerRejectsForeignResourceAndClearsReadOnCredentialInvalidation() = runTest {
        val c=AppController(backgroundScope,SessionFactory { DemoSession(it) },MemoryStore(),listOf(a),productFactory=factory)
        c.navigate(Page.RESOURCES); runCurrent()
        c.openResource(CloudResource("r","foreign",ResourceKind.R2,"",resourceRef=ResourceRef(account(b),"r2_bucket","r")))
        assertEquals(Page.RESOURCES,c.state.value.page)
        c.invalidateSession(a.id); runCurrent(); assertTrue(c.state.value.products.isEmpty()); assertTrue(c.state.value.inventory.resources.isEmpty())
    }

    @Test fun legacyUserHistoryRequiresExplicitSelectionAndNeverRunsInventory() = runTest {
        var legacyCalls=0
        val c=AppController(backgroundScope,SessionFactory { object:CloudSession by DemoSession(it) {
            override suspend fun inventory():Inventory=error("No aggregate inventory")
            override suspend fun billing():Billing { legacyCalls++;return Billing(emptyList(),notice="用户级历史") }
        } },MemoryStore(),listOf(a,b),productFactory=factory)
        c.navigate(Page.BILLING);runCurrent();assertEquals(0,legacyCalls)
        c.selectBillingSource(BillingSource.USER_HISTORY_LEGACY);runCurrent();assertEquals(1,legacyCalls)
        assertEquals("用户级历史",c.state.value.billing?.notice)
        c.selectConnection(b);runCurrent();assertNull(c.state.value.billing)
        assertEquals(1,legacyCalls)
    }
    @Test fun failedFirstPageRefreshRetainsDataButCannotAppendAnOldContinuation() = runTest {
        val l=loader(MutableStateFlow(account(a)))
        read={s,_,_->CloudPage(listOf(Domain("z","zone","active",s.connectionId)),PageToken.Number(2),2)}
        l.load(ProductKey.ZONES);runCurrent()
        read={_,_,_->throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"需要权限")}
        l.load(ProductKey.ZONES);runCurrent()
        assertEquals(1,(l.states.value.getValue(ProductKey.ZONES).content as ProductContent.Zones).items.size)
        assertNull(l.states.value.getValue(ProductKey.ZONES).next)
    }

    @Test fun replacedConnectionMetadataCannotRetainOrReloadOldAccountScope() = runTest {
        val c=AppController(backgroundScope,SessionFactory {DemoSession(it)},MemoryStore(),listOf(a),productFactory=factory)
        c.navigate(Page.DOMAINS);runCurrent()
        val changed=a.copy(accountId="replacement")
        c.reloadConnections(listOf(changed));c.refresh();runCurrent()
        assertEquals(account(changed),c.state.value.products[ProductKey.ZONES]?.scope)
        assertEquals("replacement",c.state.value.inventory.domains.single().name)
    }
    @Test fun lateLegacyBillingErrorCannotBecomeTheResourcePageErrorOrStopItsLoading() = runTest {
        val gate=CompletableDeferred<Unit>();val resources=CompletableDeferred<Unit>()
        val normal=read;read={s,k,r -> if(k==ProductKey.R2) resources.await();normal(s,k,r)}
        val c=AppController(backgroundScope,SessionFactory {object:CloudSession by DemoSession(it) {
            override suspend fun billing():Billing {gate.await();throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"旧账单权限不足")}
        }},MemoryStore(),listOf(a),productFactory=factory)
        c.navigate(Page.BILLING);runCurrent();c.selectBillingSource(BillingSource.USER_HISTORY_LEGACY);runCurrent()
        c.navigate(Page.RESOURCES);c.selectResourceKind(ResourceKind.R2);runCurrent()
        gate.complete(Unit);runCurrent()
        assertNull(c.state.value.error);assertTrue(c.state.value.loading)
        resources.complete(Unit);runCurrent()
    }

}
