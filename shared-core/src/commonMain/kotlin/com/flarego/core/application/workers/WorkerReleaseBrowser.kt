package com.flarego.core.application.workers

import com.flarego.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Allocation(val versionId: String, val percentage: String)
data class WorkerDeployment(
    val id: String,
    val createdOn: String,
    val source: String?,
    val message: String?,
    val allocations: List<Allocation>,
)
data class WorkerVersion(
    val id: String,
    val number: Long?,
    val createdOn: String?,
    val source: String?,
    val compatibilityDate: String?,
)
data class ReleasePage<T>(val items: List<T>, val next: Int?)

interface WorkerReleases {
    suspend fun deployments(page: Int): ReleasePage<WorkerDeployment>
    suspend fun versions(page: Int): ReleasePage<WorkerVersion>
    suspend fun deployment(id: String): WorkerDeployment
    suspend fun version(id: String): WorkerVersion
}

data class ReleaseRead<T>(
    val value: T? = null,
    val observedAtMs: Long? = null,
    val loading: Boolean = false,
    val error: CloudFailure? = null,
    val appendFailed: Boolean = false,
)

enum class WorkerTab { DEPLOYMENTS, VERSIONS }

data class WorkerReleaseState(
    val worker: CloudResource,
    val tab: WorkerTab = WorkerTab.DEPLOYMENTS,
    val deployments: ReleaseRead<ReleasePage<WorkerDeployment>> = ReleaseRead(),
    val versions: ReleaseRead<ReleasePage<WorkerVersion>> = ReleaseRead(),
    val expandedDeployment: String? = null,
    val expandedVersion: String? = null,
    val deployment: ReleaseRead<WorkerDeployment> = ReleaseRead(),
    val version: ReleaseRead<WorkerVersion> = ReleaseRead(),
) {
    // Only deployments page one can establish what is serving production traffic.
    val current: WorkerDeployment? get() = deployments.value?.items?.firstOrNull()
}

/** Owned by the AppController. All intents and publications run in its UI scope. */
class WorkerReleaseBrowser(
    private val scope: CoroutineScope,
    private val factory: suspend (Connection, CloudResource) -> WorkerReleases,
    private val nowMs: () -> Long,
) {
    private enum class ReadSlot { DEPLOYMENTS, VERSIONS, DEPLOYMENT, VERSION }

    private val mutable = MutableStateFlow<WorkerReleaseState?>(null)
    val state = mutable.asStateFlow()
    private var connection: Connection? = null
    private val requests = mutableMapOf<ReadSlot, Job>()

    fun open(connection: Connection, worker: CloudResource) {
        clear()
        if (connection.demo || worker.kind != ResourceKind.WORKER) return
        this.connection = connection
        mutable.value = WorkerReleaseState(worker)
        load()
    }

    fun clear() {
        requests.values.forEach { it.cancel() }
        requests.clear()
        connection = null
        mutable.value = null
    }

    fun selectTab(tab: WorkerTab) {
        val before = mutable.value ?: return
        mutable.value = before.copy(tab = tab)
        val active = if (tab == WorkerTab.VERSIONS) before.versions else before.deployments
        if (active.value == null && active.error == null) load(tab = tab)
    }

    fun toggleDeployment(id: String) {
        val before = mutable.value ?: return
        if (before.expandedDeployment == id) {
            closeDeployment(before)
        } else {
            requests.remove(ReadSlot.DEPLOYMENT)?.cancel()
            mutable.value = before.copy(expandedDeployment = id, deployment = ReleaseRead())
            reloadDeployment()
        }
    }

    fun toggleVersion(id: String) {
        val before = mutable.value ?: return
        if (before.expandedVersion == id) closeVersion(before) else openVersion(id)
    }

    fun openVersion(id: String) {
        selectTab(WorkerTab.VERSIONS)
        val before = mutable.value ?: return
        if (before.expandedVersion == id) return
        requests.remove(ReadSlot.VERSION)?.cancel()
        mutable.value = before.copy(expandedVersion = id, version = ReleaseRead())
        reloadVersion()
    }

    private fun closeDeployment(before: WorkerReleaseState) {
        requests.remove(ReadSlot.DEPLOYMENT)?.cancel()
        mutable.value = before.copy(expandedDeployment = null, deployment = ReleaseRead())
    }

    private fun closeVersion(before: WorkerReleaseState) {
        requests.remove(ReadSlot.VERSION)?.cancel()
        mutable.value = before.copy(expandedVersion = null, version = ReleaseRead())
    }

    fun back(): Boolean {
        val before = mutable.value ?: return false
        when (before.tab) {
            WorkerTab.DEPLOYMENTS -> if (before.expandedDeployment != null) closeDeployment(before) else return false
            WorkerTab.VERSIONS -> if (before.expandedVersion != null) closeVersion(before) else return false
        }
        return true
    }

    fun load(more: Boolean = false, tab: WorkerTab? = null) {
        val owner = connection ?: return
        val before = mutable.value ?: return
        when (tab ?: before.tab) {
            WorkerTab.DEPLOYMENTS -> {
                val page = if (more) before.deployments.value?.next ?: return else 1
                read(ReadSlot.DEPLOYMENTS, owner, before, before.deployments, more, { s, r -> s.copy(deployments = r) }) { api ->
                    combine(before.deployments.value.takeIf { more }, api.deployments(page), WorkerDeployment::id)
                }
            }
            WorkerTab.VERSIONS -> {
                val page = if (more) before.versions.value?.next ?: return else 1
                read(ReadSlot.VERSIONS, owner, before, before.versions, more, { s, r -> s.copy(versions = r) }) { api ->
                    combine(before.versions.value.takeIf { more }, api.versions(page), WorkerVersion::id)
                }
            }
        }
    }

    fun reloadDeployment() {
        val owner = connection ?: return
        val before = mutable.value ?: return
        val id = before.expandedDeployment ?: return
        read(ReadSlot.DEPLOYMENT, owner, before, before.deployment, false,
            { s, r -> s.copy(deployment = r) }) { it.deployment(id) }
    }

    fun reloadVersion() {
        val owner = connection ?: return
        val before = mutable.value ?: return
        val id = before.expandedVersion ?: return
        read(ReadSlot.VERSION, owner, before, before.version, false,
            { s, r -> s.copy(version = r) }) { it.version(id) }
    }

    private fun <T> read(
        slot: ReadSlot, owner: Connection, before: WorkerReleaseState, previous: ReleaseRead<T>, more: Boolean,
        update: (WorkerReleaseState, ReleaseRead<T>) -> WorkerReleaseState, run: suspend (WorkerReleases) -> T,
    ) {
        if (requests[slot]?.isActive == true) return
        mutable.value = update(before, previous.copy(loading = true, error = null, appendFailed = false))
        requests[slot] = scope.launch {
            val result = try {
                val api = factory(owner, before.worker)
                ensureActive()
                ReleaseRead(run(api), if (more) previous.observedAtMs else nowMs())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previous.copy(loading = false, appendFailed = more, error = error as? CloudFailure
                    ?: CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法读取 Workers，请检查连接后重试"))
            }
            // Switching tabs doesn't retire a read; closing/replacing a row or changing account does.
            ensureActive()
            val latest = mutable.value ?: return@launch
            mutable.value = update(latest, result)
        }
    }

    private fun <T> combine(previous: ReleasePage<T>?, next: ReleasePage<T>, id: (T) -> String): ReleasePage<T> {
        if (previous == null) return next
        val known = previous.items.mapTo(mutableSetOf(), id)
        val added = next.items.filter { id(it) !in known }
        if (next.items.isNotEmpty() && added.isEmpty())
            throw CloudFailure(CloudFailureCode.INVALID_RESPONSE, "收到重复页，请刷新列表后重试")
        return ReleasePage(previous.items + added, next.next)
    }
}
