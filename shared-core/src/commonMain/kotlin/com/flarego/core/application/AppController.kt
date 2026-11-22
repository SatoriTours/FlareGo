package com.flarego.core.application

import com.flarego.core.application.inventory.*
import com.flarego.core.application.operations.DnsOperations
import com.flarego.core.application.workers.WorkerReleaseBrowser
import com.flarego.core.application.workers.WorkerReleases
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

class AppController(
    private val scope: CoroutineScope,
    private val factory: SessionFactory,
    private val store: LocalStore,
    connections: List<Connection>,
    /** Optional opt-in confirmed-write path for DNS writes; the legacy path is unchanged. */
    private val dnsOperations: DnsOperations? = null,
    private val productFactory: ProductSessionFactory? = null,
    private val credentialRevisionQuery: ((String) -> Long)? = null,
    private val nowMs: () -> Long = { 0L },
    private val availableProducts: (Connection) -> Set<ProductKey> = { ProductKey.entries.toSet() },
    private val readCache: com.flarego.core.application.cache.ReadSnapshotStore? = null,
    workerReleasesFactory: (suspend (Connection, CloudResource) -> WorkerReleases)? = null,
) {
    val workerReleases = workerReleasesFactory?.let { WorkerReleaseBrowser(scope, it, nowMs) }
    private val mutable =
        MutableStateFlow(
            AppState(connections, connections.firstOrNull(), operations = store.operations(), productLoading = productFactory != null,
                supportedProducts = connections.firstOrNull()?.let(availableProducts).orEmpty())
        )
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private var management: com.flarego.core.application.management.CloudflareManagement? = null
    /** Attach the app-lifetime page ports; they never own another execution runtime. */
    fun bindManagement(pages: com.flarego.core.application.management.CloudflareManagement) { management = pages }
    private val sessions = mutableMapOf<String, CloudSession>()
    private val sessionVersions = mutableMapOf<String, Int>()
    private var uiEpoch = 0
    private var revision = 0
    private var dnsRevision = 0
    private var objectReturnPage: Page? = null
    private val recentByAccount = mutableMapOf<AccountScope, List<RecentVisit>>()

    private fun recordVisit(visit: RecentVisit) {
        val owner = account(state.value.selected) ?: return
        fun sameObject(other: RecentVisit) = when {
            visit is RecentVisit.Zone && other is RecentVisit.Zone -> visit.domain.id == other.domain.id
            visit is RecentVisit.Resource && other is RecentVisit.Resource ->
                visit.resource.kind == other.resource.kind && visit.resource.id == other.resource.id
            else -> false
        }
        val recent = (listOf(visit) + recentByAccount[owner].orEmpty().filterNot(::sameObject)).take(3)
        recentByAccount[owner] = recent
        mutable.update { it.copy(recent = recent) }
    }
    private val writeLock = Mutex()

    private suspend fun session(connection: Connection): CloudSession {
        sessions[connection.id]?.let {
            return it
        }
        val version = sessionVersions[connection.id] ?: 0
        return factory.create(connection).also {
            if (
                (sessionVersions[connection.id] ?: 0) == version &&
                    connection in state.value.connections
            )
                sessions[connection.id] = it
        }
    }

    private fun account(connection: Connection?) = connection?.let { AccountScope(it.id,it.accountId) }
    private val selectedScope = MutableStateFlow(account(state.value.selected))
    private val loader = productFactory?.let {
        ProductLoader(scope, it, selectedScope,
            { id -> credentialRevisionQuery?.invoke(id) ?: (sessionVersions[id] ?: 0).toLong() },
            { account -> state.value.connections.find { it.id == account.connectionId && it.accountId == account.accountId } }, nowMs, availableProducts, readCache?.let { com.flarego.core.application.cache.ProductSnapshots(it) })
    }
    val operationEpoch: Long get() = uiEpoch.toLong()

    init {
        loader?.let { products -> scope.launch {
            products.states.collect { loaded ->
                mutable.update { current ->
                    val valid = loaded.filterValues { it.scope == account(current.selected) }
                    current.copy(products = valid, inventory = Inventory(
                        (valid[ProductKey.ZONES]?.content as? ProductContent.Zones)?.items.orEmpty(),
                        valid.values.flatMap { (it.content as? ProductContent.Resources)?.items.orEmpty() }),
                        loading = productsLoading(current, valid))
                }
            }
        } }
    }

    private fun productsLoading(current: AppState, products: Map<ProductKey, ProductLoadState>): Boolean = when {
        current.page == Page.OVERVIEW -> products.values.any { it.loading }
        current.page == Page.BILLING && current.billingSource == BillingSource.USER_HISTORY_LEGACY -> current.legacyBillingLoading
        else -> activeProduct(current)?.let { products[it]?.loading } == true
    }

    private fun activeProduct(current: AppState = state.value): ProductKey? = when(current.page) {
        Page.DOMAINS -> ProductKey.ZONES
        Page.RESOURCES -> current.resourceKind.product()
        Page.BILLING -> if (current.billingSource == BillingSource.ACCOUNT_HISTORY) ProductKey.BILLING else null
        else -> null
    }
    private fun loadActive(force: Boolean = false) {
        val key = activeProduct() ?: run { if(state.value.page==Page.OVERVIEW) loader?.restoreOverview();return }
        if (force) loader?.load(key) else loader?.ensure(key)
    }
    private fun resetProducts() {
        selectedScope.value = account(state.value.selected)
        loader?.resetSelection()
        if (loader != null) mutable.update { it.copy(products = emptyMap(), inventory = Inventory(emptyList(),emptyList()), billing = null, loading = false,
            supportedProducts = it.selected?.let(availableProducts).orEmpty()) }
    }
    fun start() { if (loader == null) refresh() else loadActive() }
    fun selectBillingSource(source: BillingSource) {
        if (loader == null || state.value.billingSource == source) return
        revision++
        mutable.update { it.copy(billingSource = source, billing = null, legacyBillingLoading = false, loading = false, error = null) }
        refresh()
    }
    fun selectWorkersMode(mode: WorkersDirectoryMode) {
        if (loader == null) return
        mutable.update { it.copy(workersMode = mode) }
        loader.selectWorkersMode(mode)
    }

    fun selectResourceKind(kind: ResourceKind) {
        mutable.update { it.copy(resourceKind = kind, loading = loader?.states?.value?.get(kind.product())?.loading == true) }
        if (loader != null) loadActive()
    }
    fun loadMore(product: ProductKey) {
        val next = state.value.products[product]?.next ?: return
        loader?.load(product, PageRequest(token = next))
    }
    fun refreshAllProducts() {
        if (loader == null) refresh() else ProductKey.entries.forEach {
            // An explicit refresh must not cancel and restart an already running read.
            if (loader.states.value[it]?.loading != true) loader.load(it)
        }
    }
    fun publishTasks(tasks: List<OperationTask>) {
        mutable.update { current -> current.copy(tasks = tasks.filter { t -> current.connections.any { it.id == t.connectionId } }, operations = store.operations()) }
    }

    fun navigate(page: Page) {
        objectReturnPage = null
        workerReleases?.clear()
        bumpUiEpoch()
        dnsRevision++
        mutable.update {
            it.copy(
                page = page,
                domain = null,
                resource = null,
                dns = emptyList(),
                dnsLoading = false,
                error = null,
            )
        }
        if (loader != null) {
            mutable.update { it.copy(loading = productsLoading(it, loader.states.value)) }
            loadActive()
            if (page == Page.BILLING && state.value.billingSource == BillingSource.USER_HISTORY_LEGACY && state.value.billing == null) refresh()
        }
    }

    fun back() {
        if (state.value.page == Page.RESOURCE && workerReleases?.back() == true) return
        navigate(
            objectReturnPage ?: when (state.value.page) {
                Page.DNS,
                Page.BUY -> Page.DOMAINS
                Page.RESOURCE -> Page.RESOURCES
                else -> Page.OVERVIEW
            }
        )
    }

    fun selectConnection(connection: Connection) {
        bumpUiEpoch()
        if (connection !in state.value.connections) return
        val fromDetail = objectReturnPage
        objectReturnPage = null
        workerReleases?.clear()
        revision++
        dnsRevision++
        val root =
            fromDetail ?: when (state.value.page) {
                Page.DNS,
                Page.BUY -> Page.DOMAINS
                Page.RESOURCE -> Page.RESOURCES
                else -> state.value.page
            }
        mutable.update {
            AppState(it.connections, connection, page = root, operations = store.operations(), productLoading = loader != null, resourceKind = it.resourceKind, tasks = it.tasks, workersMode = it.workersMode,
                recent = recentByAccount[account(connection)].orEmpty())
        }
        resetProducts()
        // Selection/startup remain lazy; only an explicit overview refresh reads all products.
        if (loader != null) loadActive() else refresh()
    }

    /** Restore only the same, still-selected workspace after a confirmed credential replacement. */
    fun resumeAfterTokenUpgrade(connection: Connection, previous: AppState) {
        val owner = account(connection)
        if (account(previous.selected) != owner || account(state.value.selected) != owner ||
            state.value.page != previous.page) return
        val returnPage = objectReturnPage
        selectConnection(connection)
        val domain = previous.domain?.takeIf { it.connectionId == connection.id }
        when {
            previous.page == Page.DNS && domain != null && management != null ->
                openDomain(domain, fromHome = returnPage == Page.OVERVIEW, managed = true)
            previous.page == Page.BILLING && previous.billingSource != state.value.billingSource ->
                selectBillingSource(previous.billingSource)
        }
    }

    fun invalidateSession(id: String) {
        bumpUiEpoch()
        revision++
        dnsRevision++
        if (state.value.selected?.id == id) {
            workerReleases?.clear()
            resetProducts()
            mutable.update { it.copy(page = if (it.page == Page.RESOURCE) Page.RESOURCES else it.page,
                domain = null, resource = null, dns = emptyList(), dnsLoading = false, error = null) }
        }
        loader?.invalidate(id)
        sessions.remove(id)
        sessionVersions[id] = (sessionVersions[id] ?: 0) + 1
    }

    fun reloadConnections(connections: List<Connection>) {
        bumpUiEpoch()
        recentByAccount.keys.retainAll(connections.mapNotNull(::account).toSet())
        val selected = state.value.selected
        val retained = connections.firstOrNull { it.id == selected?.id }
        sessions.keys.retainAll(connections.map { it.id }.toSet())
        if (retained != null) {
            mutable.update { it.copy(connections = connections, selected = retained, operations = store.operations(), tasks = it.tasks.filter { task -> connections.any { c -> c.id == task.connectionId } }) }
            if (retained != selected) {
                sessions.remove(retained.id)
                sessionVersions[retained.id] = (sessionVersions[retained.id] ?: 0) + 1
                selectConnection(retained)
            }
        } else {
            // Invalidate every outstanding read before dropping the old account's data.
            objectReturnPage = null
            workerReleases?.clear()
            revision++
            dnsRevision++
            mutable.update {
                AppState(connections, connections.firstOrNull(), operations = store.operations(), productLoading = loader != null,
                    recent = recentByAccount[account(connections.firstOrNull())].orEmpty())
            }
            resetProducts()
            if (loader != null) loadActive() else refresh()
        }
    }

    fun openResource(resource: CloudResource) = openResource(resource, fromHome = false)

    private fun openResource(resource: CloudResource, fromHome: Boolean) {
        val connection = state.value.selected ?: return
        if (loader != null && (resource.resourceRef?.scope != account(connection) ||
            (!fromHome && resource !in state.value.inventory.resources))) return
        objectReturnPage = if (fromHome) Page.OVERVIEW else Page.RESOURCES
        bumpUiEpoch()
        mutable.update { it.copy(page = Page.RESOURCE, resource = resource, error = null) }
        workerReleases?.open(connection, resource)
        if (resource.kind in setOf(ResourceKind.WORKER, ResourceKind.R2)) recordVisit(RecentVisit.Resource(resource))
    }

    fun recordBucketOpened(owner: AccountScope, bucket: String) {
        if (owner != account(state.value.selected) || state.value.page != Page.RESOURCES ||
            state.value.resourceKind != ResourceKind.R2) return
        recordVisit(RecentVisit.Resource(CloudResource(bucket, bucket, ResourceKind.R2, "")))
    }

    fun openDomain(domain: Domain) = openDomain(domain, fromHome = false, managed = false)

    private fun openDomain(domain: Domain, fromHome: Boolean, managed: Boolean) {
        if (domain.connectionId != state.value.selected?.id) return
        if (loader != null && !fromHome && !managed && domain !in state.value.inventory.domains) return
        objectReturnPage = if (fromHome) Page.OVERVIEW else Page.DOMAINS
        bumpUiEpoch()
        workerReleases?.clear()
        mutable.update { it.copy(page = Page.DNS, domain = domain, dns = emptyList(), error = null) }
        if (!managed) loadDns(domain)
        recordVisit(RecentVisit.Zone(domain))
    }

    fun openManagedZone(ref: ZoneScope, page: com.flarego.core.application.zones.ZonePageState) {
        if (ref.account != account(state.value.selected) || page.account != ref.account) return
        val zone = page.zones.firstOrNull { it.ref == ref } ?: return
        openDomain(Domain(ref.zoneId, zone.name, zone.status, ref.connectionId), fromHome = false, managed = true)
    }

    fun openRecent(visit: RecentVisit, managedZones: Boolean) {
        val owner = account(state.value.selected) ?: return
        if (state.value.page != Page.OVERVIEW || visit !in recentByAccount[owner].orEmpty()) return
        when (visit) {
            is RecentVisit.Zone -> openDomain(visit.domain, fromHome = true, managed = managedZones)
            is RecentVisit.Resource -> openResource(visit.resource, fromHome = true)
        }
    }

    fun reportDisconnectFailure() {
        mutable.update { it.copy(error = "本机断开未完成，请重试；云端操作结果仍需核对") }
    }

    fun dismissError() {
        mutable.update { it.copy(error = null) }
    }

    fun refresh() {
        if (state.value.page == Page.RESOURCE && workerReleases?.state?.value != null) {
            workerReleases.load()
            return
        }
        if (state.value.page == Page.BILLING && ProductKey.BILLING !in state.value.supportedProducts) return
        if (loader != null && state.value.page == Page.BILLING && state.value.billingSource == BillingSource.USER_HISTORY_LEGACY) {
            val connection = state.value.selected ?: return
            val request = ++revision
            val sessionVersion = sessionVersions[connection.id] ?: 0
            mutable.update { it.copy(legacyBillingLoading = true, loading = true, error = null) }
            scope.launch {
                try {
                    val result = session(connection).billing()
                    if (request == revision && state.value.selected == connection && (sessionVersions[connection.id] ?: 0) == sessionVersion && state.value.billingSource == BillingSource.USER_HISTORY_LEGACY)
                        mutable.update { it.copy(billing = result, legacyBillingLoading = false,
                            loading = if (it.page == Page.BILLING) false else it.loading) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (request == revision && state.value.selected == connection && (sessionVersions[connection.id] ?: 0) == sessionVersion && state.value.billingSource == BillingSource.USER_HISTORY_LEGACY)
                        mutable.update { it.copy(billing = Billing(emptyList(),notice = safeMessage(failure)), legacyBillingLoading = false,
                            loading = if (it.page == Page.BILLING) false else it.loading) }
                }
            }
            return
        }
        if (loader != null) {
            if (state.value.page == Page.OVERVIEW) refreshAllProducts()
            else loadActive(force = true)
            state.value.domain?.let(::loadDns)
            return
        }
        val connection = state.value.selected ?: return
        val request = ++revision
        mutable.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val result = session(connection).inventory()
                if (request == revision)
                    mutable.update { it.copy(inventory = result, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (request == revision)
                    mutable.update { it.copy(loading = false, error = safeMessage(e)) }
            }
        }
        scope.launch {
            val billing =
                try {
                    session(connection).billing()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Billing(emptyList(), notice = safeMessage(e))
                }
            if (request == revision) mutable.update { it.copy(billing = billing) }
        }
        state.value.domain?.let(::loadDns)
    }

    fun loadDns(domain: Domain) {
        val connection = state.value.selected ?: return
        if (domain.connectionId != connection.id) return
        val request = ++dnsRevision
        mutable.update { it.copy(dnsLoading = true) }
        scope.launch {
            try {
                val records = session(connection).records(domain.id)
                if (request == dnsRevision && state.value.selected?.id == connection.id)
                    mutable.update { it.copy(dns = records, dnsLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (request == dnsRevision)
                    mutable.update { it.copy(error = safeMessage(e), dnsLoading = false) }
            }
        }
    }

    suspend fun saveDns(domain: Domain, recordId: String?, draft: DnsDraft): Operation {
        val title = "${if (recordId == null) "新增" else "修改"} ${draft.type} · ${draft.name}"
        val operations = dnsOperations
        if (operations != null && operationsActive()) {
            val connection = requireConnection()
            // Freeze the original selection and session revision: a late result may only be
            // published into the UI when both are still current.
            val selectionId = connection.id
            val sessionVersion = sessionVersions[connection.id] ?: 0
            val epoch = uiEpoch
            // A user input error becomes a recorded failure, exactly as the legacy path.
            val validated =
                try {
                    validateDns(domain, draft)
                } catch (failure: Exception) {
                    // The opt-in path never writes a caller-side summary for any failure: only
                    // the coordinator's metadata latch may persist, and the default path keeps
                    // its legacy behaviour.
                    return recordedFailure(
                        connection,
                        title,
                        failure,
                        publish = stillPublishing(connection.id, selectionId, sessionVersion, epoch),
                        // The opt-in path always refuses a caller-side summary; the default
                        // factory keeps the historical behaviour.
                        persist = false,
                    )
                }
            val zone = ZoneScope(AccountScope(connection.id, connection.accountId), domain.id)
            val task =
                try {
                    if (recordId == null) operations.create(zone, validated)
                    else operations.update(zone, recordId, validated)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return recordedFailure(
                        connection,
                        title,
                        failure,
                        publish = stillPublishing(connection.id, selectionId, sessionVersion, epoch),
                        persist = false,
                    )
                }
            return task.toOperation(connection, stillPublishing(connection.id, selectionId, sessionVersion, epoch))
        }
        return scope.async { mutate(domain, title) { api -> api.save(domain.id, recordId, validateDns(domain, draft)) } }.await()
    }

    /**
     * Monotonic publication epoch: every UI ownership change (navigation, selection,
     * opening a domain or resource, reloading or removing connections) invalidates it, so a
     * result that started under an older epoch can never be published — not even when the
     * user navigated away and back to the same selection or the same connection id.
     */
    private fun bumpUiEpoch() {
        uiEpoch += 1
        management?.dns?.onIntent(com.flarego.core.application.dns.DnsIntent.Clear)
        management?.zones?.onIntent(com.flarego.core.application.zones.ZoneIntent.Clear)
        management?.batch?.onIntent(com.flarego.core.application.dns.DnsBatchIntent.Clear)
    }

    private fun requireConnection(): Connection =
        state.value.selected ?: throw CloudException("请先添加服务并绑定 API Token")

    private fun operationsActive(): Boolean = state.value.selected?.let { !it.demo || management != null } == true

    private fun recordedFailure(
        connection: Connection,
        title: String,
        failure: Exception,
        publish: Boolean = true,
        persist: Boolean = true,
    ): Operation {
        // An opt-in failure is only FAILED when nothing was sent and the failure is
        // certain; anything else stays UNKNOWN so a possible send is never denied.
        val status =
            if (failure is CloudException && failure.uncertain) OperationStatus.UNKNOWN
            else OperationStatus.FAILED
        val operation = Operation(Random.nextLong().toString(16), connection.id, title, status, safeMessage(failure))
        // The opt-in path never writes a caller-side summary: a deleted operation must not be
        // recreated outside the coordinator's metadata latch. Only the legacy path persists.
        if (persist) store.putOperation(operation)
        if (publish) mutable.update { it.copy(operations = store.operations()) }
        return operation
    }

    private fun OperationTask.toOperation(connection: Connection, publish: Boolean): Operation {
        val summary = OperationTaskPersistence.summaryFor(this)
        // The coordinator already persisted this canonical summary under its own metadata
        // latch; the UI never writes a second lifecycle row. A late result may only refresh
        // the list when the original selection, session and UI revisions still match.
        if (publish) mutable.update { it.copy(operations = store.operations()) }
        return Operation(summary.id, connection.id, summary.title, summary.status, summary.detail)
    }

    /** True when the frozen original selection/session revision is still the current one. */
    private fun stillPublishing(connectionId: String, selectionId: String, sessionVersion: Int, epoch: Int): Boolean =
        uiEpoch == epoch &&
            state.value.selected?.id == selectionId &&
            (sessionVersions[connectionId] ?: 0) == sessionVersion &&
            connectionId in state.value.connections.map { it.id }

    suspend fun deleteDns(domain: Domain, record: DnsRecord): Operation {
        val operations = dnsOperations
        if (operations != null && operationsActive()) {
            val connection = requireConnection()
            val selectionId = connection.id
            val sessionVersion = sessionVersions[connection.id] ?: 0
            val epoch = uiEpoch
            val zone = ZoneScope(AccountScope(connection.id, connection.accountId), domain.id)
            val task =
                try {
                    operations.delete(zone, record.id, record)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return recordedFailure(
                        connection,
                        "删除 ${record.type} · ${record.name}",
                        failure,
                        publish = stillPublishing(connection.id, selectionId, sessionVersion, epoch),
                        persist = false,
                    )
                }
            return task.toOperation(connection, stillPublishing(connection.id, selectionId, sessionVersion, epoch))
        }
        return scope
            .async {
                mutate(domain, "删除 ${record.type} · ${record.name}") { api ->
                    api.delete(domain.id, record.id)
                }
            }
            .await()
    }

    private suspend fun mutate(
        domain: Domain,
        title: String,
        action: suspend (CloudSession) -> Unit,
    ): Operation {
        val connection = requireConnection()
        fun failed(detail: String) =
            Operation(
                Random.nextLong().toString(16),
                connection.id,
                title,
                OperationStatus.FAILED,
                detail,
            )
        if (domain.connectionId != connection.id) return failed("账号已切换，请重新打开此域名")
        if (!writeLock.tryLock()) return failed("另一项操作正在执行，请稍后")
        try {
            val pending =
                Operation(
                    Random.nextLong().toString(16),
                    connection.id,
                    title,
                    OperationStatus.UNKNOWN,
                    "请求正在提交，结果尚未确认；应用中断后请先刷新并核对，勿重复提交",
                )
            store.putOperation(pending)
            mutable.update { it.copy(operations = store.operations()) }
            val operation =
                try {
                    action(session(connection))
                    Operation(
                        pending.id,
                        connection.id,
                        title,
                        OperationStatus.SUCCESS,
                        if (connection.demo) "演示变更已保存；未调用云 API" else "Cloudflare 已确认；DNS 全球传播仍需要时间",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Operation(
                        pending.id,
                        connection.id,
                        title,
                        if ((e is CloudException && e.uncertain) ||
                            (e is CloudFailure && e.uncertain)) {
                            OperationStatus.UNKNOWN
                        } else {
                            OperationStatus.FAILED
                        },
                        safeMessage(e),
                    )
                }
            store.putOperation(operation)
            mutable.update { it.copy(operations = store.operations()) }
            if (state.value.selected?.id == connection.id && state.value.domain?.id == domain.id)
                loadDns(domain)
            return operation
        } finally {
            writeLock.unlock()
        }
    }

}

fun safeMessage(error: Exception) =
    when (error) {
        is CloudException -> error.publicMessage
        is CloudFailure -> error.publicMessage
        else -> "暂时无法连接，请检查网络后重试"
    }
