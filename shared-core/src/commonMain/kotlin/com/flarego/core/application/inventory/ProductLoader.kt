package com.flarego.core.application.inventory

import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ProductKey { ZONES, WORKERS, R2, D1, KV, BILLING }
sealed interface ProductContent {
    data class Zones(val items: List<Domain>) : ProductContent
    data class Resources(val items: List<CloudResource>) : ProductContent
    data class Billing(val items: List<BillingEntry>) : ProductContent
}
data class ProductLoadState(
    val content: ProductContent, val next: PageToken?, val total: Long?,
    val loading: Boolean, val error: CloudFailure?, val scope: AccountScope,
    val credentialRevision: Long, val observedAtMs: Long?, val allPagesLoaded: Boolean,
)
class ProductLoader(
    private val coroutineScope: CoroutineScope,
    private val factory: ProductSessionFactory,
    private val selected: StateFlow<AccountScope?>,
    private val credentialRevision: (String) -> Long,
    private val connection: (AccountScope) -> Connection?,
    private val nowMs: () -> Long = { 0L },
) {
    private val mutable = MutableStateFlow<Map<ProductKey, ProductLoadState>>(emptyMap())
    val states: StateFlow<Map<ProductKey, ProductLoadState>> = mutable.asStateFlow()
    private val jobs = mutableMapOf<ProductKey, Job>()
    private val attempts = mutableMapOf<ProductKey, Long>()
    private val seen = mutableMapOf<ProductKey, Set<PageToken>>()
    private val limits = mutableMapOf<ProductKey, Int>()
    private var boundSelection = selected.value
    private var epoch = 0L
    private var workersMode = WorkersDirectoryMode.MODERN

    init {
        coroutineScope.launch {
            selected.collect { if (it != boundSelection) resetSelection() }
        }
    }

    /** Also called synchronously by the controller, including A → B → A changes. */
    fun resetSelection() {
        epoch++
        jobs.values.forEach { it.cancel() }
        jobs.clear(); attempts.clear(); seen.clear(); limits.clear()
        boundSelection = selected.value
        mutable.value = emptyMap()
    }

    fun selectWorkersMode(mode: WorkersDirectoryMode) {
        if (workersMode == mode) return
        workersMode = mode
        jobs.remove(ProductKey.WORKERS)?.cancel()
        attempts[ProductKey.WORKERS] = (attempts[ProductKey.WORKERS] ?: 0L) + 1L
        seen.remove(ProductKey.WORKERS); limits.remove(ProductKey.WORKERS)
        mutable.update { it - ProductKey.WORKERS }
        load(ProductKey.WORKERS)
    }

    fun invalidate(connectionId: String) {
        if (boundSelection?.connectionId == connectionId) resetSelection()
    }

    fun load(product: ProductKey, request: PageRequest = PageRequest()) {
        if (boundSelection != selected.value) resetSelection()
        val account = selected.value ?: return
        val owner = connection(account) ?: return
        ScopeGuard.requireOwned(account, owner)
        val revision = credentialRevision(owner.id)
        val previous = mutable.value[product]?.takeIf { it.scope == account && it.credentialRevision == revision }
        val append = request.token != null
        // Only a continuation issued by this product's current successful chain is usable.
        if (append && (previous == null || previous.next != request.token || limits[product] != request.limit)) return
        jobs.remove(product)?.cancel()
        val attempt = (attempts[product] ?: 0) + 1
        attempts[product] = attempt
        val selectionEpoch = epoch
        val directoryMode = workersMode
        if (!append) { seen[product] = emptySet(); limits[product] = request.limit }
        val initial = previous ?: ProductLoadState(empty(product), null, null, false, null, account, revision, null, false)
        mutable.update { it + (product to initial.copy(loading = true, error = null, allPagesLoaded = false)) }
        fun current() = epoch == selectionEpoch && attempts[product] == attempt && selected.value == account &&
            credentialRevision(owner.id) == revision && connection(account) == owner
        jobs[product] = coroutineScope.launch {
            try {
                val services = factory.create(owner)
                ensureActive()
                if (!current()) return@launch
                val page: CloudPage<*> = when (product) {
                    ProductKey.ZONES -> services.directory.zones(account, request)
                    ProductKey.BILLING -> services.billing.history(account, request)
                    else -> {
                        val directory = if (product == ProductKey.WORKERS && directoryMode == WorkersDirectoryMode.LEGACY)
                            services.legacyWorkers ?: throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"旧目录未提供，请在官方控制台查看")
                        else services.directory
                        directory.resources(account, product.kind(), request)
                    }
                }
                ensureActive()
                if (!current()) return@launch
                val visited = seen[product].orEmpty() + listOfNotNull(request.token)
                if (page.next != null && page.next in visited) throw CloudFailure(CloudFailureCode.INVALID_RESPONSE, "分页返回了重复位置，请重新加载")
                val content = content(product, page, account, if (append) previous?.content else null)
                val count = content.count().toLong()
                // A missing pagination total does not prove that the provider returned everything.
                val complete = page.next == null && page.total != null && page.total == count
                seen[product] = visited
                mutable.update { it + (product to ProductLoadState(content, page.next, page.total, false, null, account, revision, nowMs(), complete)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current()) {
                    val safe = failure as? CloudFailure ?: CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法加载，请稍后重试")
                    mutable.update { it + (product to initial.copy(loading = false, error = safe, next = if (append) initial.next else null, total = if (append) initial.total else null, allPagesLoaded = false)) }
                }
            }
        }
    }

    private fun empty(key: ProductKey): ProductContent = when (key) {
        ProductKey.ZONES -> ProductContent.Zones(emptyList())
        ProductKey.BILLING -> ProductContent.Billing(emptyList())
        else -> ProductContent.Resources(emptyList())
    }

    private fun content(key: ProductKey, page: CloudPage<*>, account: AccountScope, previous: ProductContent?): ProductContent = when (key) {
        ProductKey.ZONES -> {
            val items = page.items.map { it as? Domain ?: throw invalidResponse() }
            if (items.any { it.connectionId != account.connectionId }) throw scopeMismatchFailure()
            ProductContent.Zones(((previous as? ProductContent.Zones)?.items.orEmpty() + items).distinctBy { it.id })
        }
        ProductKey.BILLING -> {
            val items = page.items.map { it as? BillingEntry ?: throw invalidResponse() }
            // This loader requests account history only. Explicit user history is a separate entry.
            if (items.any { it.scope != account }) throw scopeMismatchFailure()
            ProductContent.Billing(((previous as? ProductContent.Billing)?.items.orEmpty() + items).distinctBy { it.id })
        }
        else -> {
            val items = page.items.map { it as? CloudResource ?: throw invalidResponse() }
            if (items.any { it.resourceRef?.scope != account || it.kind != key.kind() }) throw scopeMismatchFailure()
            ProductContent.Resources(((previous as? ProductContent.Resources)?.items.orEmpty() + items).distinctBy { it.id })
        }
    }
    private fun invalidResponse() = CloudFailure(CloudFailureCode.INVALID_RESPONSE, "返回的数据格式无法识别")
}

fun ProductKey.kind(): ResourceKind = when(this) {
    ProductKey.WORKERS -> ResourceKind.WORKER
    ProductKey.R2 -> ResourceKind.R2
    ProductKey.D1 -> ResourceKind.D1
    ProductKey.KV -> ResourceKind.KV
    else -> throw invalidInputFailure()
}
fun ResourceKind.product(): ProductKey = when(this) {
    ResourceKind.WORKER -> ProductKey.WORKERS
    ResourceKind.R2 -> ProductKey.R2
    ResourceKind.D1 -> ProductKey.D1
    ResourceKind.KV -> ProductKey.KV
}
fun ProductContent.count(): Int = when(this) {
    is ProductContent.Zones -> items.size
    is ProductContent.Resources -> items.size
    is ProductContent.Billing -> items.size
}
