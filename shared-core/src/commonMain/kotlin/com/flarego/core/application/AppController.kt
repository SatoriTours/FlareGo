package com.flarego.core.application

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
) {
    private val mutable =
        MutableStateFlow(
            AppState(connections, connections.first(), operations = store.operations())
        )
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val sessions = mutableMapOf<String, CloudSession>()
    private val sessionVersions = mutableMapOf<String, Int>()
    private var revision = 0
    private var dnsRevision = 0
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

    fun navigate(page: Page) {
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
    }

    fun back() =
        navigate(
            when (state.value.page) {
                Page.DNS,
                Page.BUY -> Page.DOMAINS
                Page.RESOURCE -> Page.RESOURCES
                else -> Page.OVERVIEW
            }
        )

    fun selectConnection(connection: Connection) {
        if (connection !in state.value.connections) return
        revision++
        dnsRevision++
        val root =
            when (state.value.page) {
                Page.DNS,
                Page.BUY -> Page.DOMAINS
                Page.RESOURCE -> Page.RESOURCES
                else -> state.value.page
            }
        mutable.update {
            AppState(it.connections, connection, page = root, operations = store.operations())
        }
        refresh()
    }

    fun invalidateSession(id: String) {
        sessions.remove(id)
        sessionVersions[id] = (sessionVersions[id] ?: 0) + 1
    }

    fun reloadConnections(connections: List<Connection>) {
        mutable.update { it.copy(connections = connections, operations = store.operations()) }
        sessions.keys.retainAll(connections.map { it.id }.toSet())
    }

    fun openResource(resource: CloudResource) {
        mutable.update { it.copy(page = Page.RESOURCE, resource = resource, error = null) }
    }

    fun openDomain(domain: Domain) {
        if (domain.connectionId != state.value.selected.id) return
        mutable.update {
            it.copy(page = Page.DNS, domain = domain, dns = emptyList(), error = null)
        }
        loadDns(domain)
    }

    fun dismissError() {
        mutable.update { it.copy(error = null) }
    }

    fun refresh() {
        val connection = state.value.selected
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
        val connection = state.value.selected
        if (domain.connectionId != connection.id) return
        val request = ++dnsRevision
        mutable.update { it.copy(dnsLoading = true) }
        scope.launch {
            try {
                val records = session(connection).records(domain.id)
                if (request == dnsRevision && state.value.selected.id == connection.id)
                    mutable.update { it.copy(dns = records, dnsLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (request == dnsRevision)
                    mutable.update { it.copy(error = safeMessage(e), dnsLoading = false) }
            }
        }
    }

    suspend fun saveDns(domain: Domain, recordId: String?, draft: DnsDraft): Operation =
        scope
            .async {
                mutate(
                    domain,
                    "${if (recordId == null) "新增" else "修改"} ${draft.type} · ${draft.name}",
                ) { api ->
                    api.save(domain.id, recordId, validateDns(domain, draft))
                }
            }
            .await()

    suspend fun deleteDns(domain: Domain, record: DnsRecord): Operation =
        scope
            .async {
                mutate(domain, "删除 ${record.type} · ${record.name}") { api ->
                    api.delete(domain.id, record.id)
                }
            }
            .await()

    private suspend fun mutate(
        domain: Domain,
        title: String,
        action: suspend (CloudSession) -> Unit,
    ): Operation {
        val connection = state.value.selected
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
                        if (e is CloudException && e.uncertain) OperationStatus.UNKNOWN
                        else OperationStatus.FAILED,
                        safeMessage(e),
                    )
                }
            store.putOperation(operation)
            mutable.update { it.copy(operations = store.operations()) }
            if (state.value.selected.id == connection.id && state.value.domain?.id == domain.id)
                loadDns(domain)
            return operation
        } finally {
            writeLock.unlock()
        }
    }

    fun recordDemoPurchase(name: String): Operation {
        check(state.value.selected.demo)
        val operation =
            Operation(
                Random.nextLong().toString(16),
                state.value.selected.id,
                "模拟注册 $name",
                OperationStatus.SUCCESS,
                "演示操作，无真实注册或扣款",
            )
        store.putOperation(operation)
        mutable.update { it.copy(operations = store.operations()) }
        return operation
    }
}

fun safeMessage(error: Exception) =
    if (error is CloudException) error.publicMessage else "暂时无法连接，请检查网络后重试"
