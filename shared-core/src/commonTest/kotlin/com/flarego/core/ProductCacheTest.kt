package com.flarego.core

import com.flarego.core.application.inventory.*
import com.flarego.core.application.cache.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductCacheTest {
    private val account = AccountScope("c", "a")
    private val owner = Connection("c", "Account", "a")
    private class Disk : ReadSnapshotStore {
        val rows = mutableMapOf<Pair<AccountScope,String>, ReadSnapshot>()
        override suspend fun read(account: AccountScope, key: String) = rows[account to key]
        override suspend fun write(account: AccountScope, key: String, value: ReadSnapshot, isCurrent: () -> Boolean) { if(isCurrent()) rows[account to key] = value }
        override suspend fun removeConnection(connectionId: String) { rows.keys.removeAll { it.first.connectionId == connectionId } }
    }
    private var reads = 0
    private var fail = false
    private var gate: CompletableDeferred<Unit>? = null
    private val factory = ProductSessionFactory {
        ProductServices(object : ProductDirectoryPort {
            override suspend fun zones(account: AccountScope, request: PageRequest): CloudPage<Domain> {
                reads++; gate?.await()
                if (fail) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "offline")
                return CloudPage(listOf(Domain("z", "example.com", "active", account.connectionId)), null, 1)
            }
            override suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest): CloudPage<CloudResource> = error("unused")
        }, object : ScopedBillingPort {
            override suspend fun history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry> = error("unused")
        }, object : CapabilityPort {
            override suspend fun status(scope: CloudScope, actionKey: String): CapabilityStatus = error("cache must not authorize")
        })
    }
    private fun TestScope.loader(disk: Disk, now: Long) = ProductLoader(backgroundScope, factory,
        MutableStateFlow(account), { 0 }, { owner }, { now }, cache = ProductSnapshots(disk))

    @Test fun coldOverviewRestoresPreviouslyReadDirectoriesWithoutSpeculativeCalls() = runTest {
        val disk=Disk();loader(disk,1000).ensure(ProductKey.ZONES);runCurrent()
        val controller=com.flarego.core.application.AppController(backgroundScope,SessionFactory { error("legacy unused") },MemoryStore(),listOf(owner),productFactory=factory,nowMs={2000},readCache=disk)
        controller.start();runCurrent()
        assertEquals("example.com",controller.state.value.inventory.domains.single().name)
        assertEquals(1,reads)
    }

    @Test fun newLoaderRestoresDatabaseSnapshotWithoutFreshNetworkRequest() = runTest {
        val disk = Disk()
        loader(disk, 1000).ensure(ProductKey.ZONES); runCurrent()
        assertEquals(1, reads)
        val reopened = loader(disk, 2000)
        reopened.ensure(ProductKey.ZONES); runCurrent()
        assertEquals(1, reads)
        assertEquals("example.com", (reopened.states.value.getValue(ProductKey.ZONES).content as ProductContent.Zones).items.single().name)
        assertTrue(reopened.states.value.getValue(ProductKey.ZONES).fromCache)
    }
    @Test fun staleSnapshotIsVisibleWhileRefreshingAndSurvivesOfflineFailure() = runTest {
        val disk = Disk()
        loader(disk, 1000).ensure(ProductKey.ZONES); runCurrent()
        gate = CompletableDeferred(); fail = true
        val reopened = loader(disk, 400_000)
        reopened.ensure(ProductKey.ZONES); runCurrent()
        val during = reopened.states.value.getValue(ProductKey.ZONES)
        assertEquals(1, during.content.count()); assertTrue(during.loading)
        gate!!.complete(Unit); runCurrent()
        val after = reopened.states.value.getValue(ProductKey.ZONES)
        assertEquals(during.content, after.content); assertNotNull(after.error)
        assertEquals(1000, after.observedAtMs)
    }
    @Test fun cacheIsScopedAndCredentialInvalidationDeletesPersistedSnapshot() = runTest {
        val disk = Disk(); val l = loader(disk, 1000)
        l.ensure(ProductKey.ZONES); runCurrent(); assertTrue(disk.rows.isNotEmpty())
        assertNull(ProductSnapshots(disk).restore(AccountScope("other", "a"), ProductKey.ZONES, WorkersDirectoryMode.MODERN, 0, 2000))
        l.invalidate(owner.id); runCurrent(); assertTrue(disk.rows.isEmpty())
    }
}
