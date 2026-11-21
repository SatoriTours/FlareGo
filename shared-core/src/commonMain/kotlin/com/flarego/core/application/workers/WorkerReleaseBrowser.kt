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

sealed interface WorkerRoute {
    data object Overview : WorkerRoute
    data object History : WorkerRoute
    data object Versions : WorkerRoute
    data class Deployment(val id: String) : WorkerRoute
    data class Version(val id: String) : WorkerRoute
}

data class WorkerReleaseState(
    val worker: CloudResource,
    val route: WorkerRoute = WorkerRoute.Overview,
    val deployments: ReleaseRead<ReleasePage<WorkerDeployment>> = ReleaseRead(),
    val versions: ReleaseRead<ReleasePage<WorkerVersion>> = ReleaseRead(),
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
    private val mutable = MutableStateFlow<WorkerReleaseState?>(null)
    val state = mutable.asStateFlow()
    private var connection: Connection? = null
    private var request: Job? = null
    private val stack = mutableListOf<WorkerRoute>()

    fun open(connection: Connection, worker: CloudResource) {
        clear()
        if (connection.demo || worker.kind != ResourceKind.WORKER) return
        this.connection = connection
        stack += WorkerRoute.Overview
        mutable.value = WorkerReleaseState(worker)
        load()
    }

    fun clear() {
        request?.cancel()
        request = null
        connection = null
        stack.clear()
        mutable.value = null
    }

    fun go(route: WorkerRoute) {
        val before = mutable.value ?: return
        if (before.route == route) return
        val existing = stack.indexOf(route)
        if (existing < 0) stack += route
        else stack.subList(existing + 1, stack.size).clear()
        activate(route)
    }

    fun back(): Boolean {
        if (stack.size < 2) return false
        stack.removeAt(stack.lastIndex)
        activate(stack.last())
        return true
    }

    private fun activate(route: WorkerRoute) {
        val before = mutable.value ?: return
        request?.cancel()
        request = null
        mutable.value = before.copy(
            route = route,
            deployments = before.deployments.copy(loading = false),
            versions = before.versions.copy(loading = false),
            deployment = if (route is WorkerRoute.Deployment && before.deployment.value?.id == route.id)
                before.deployment.copy(loading = false) else ReleaseRead(),
            version = if (route is WorkerRoute.Version && before.version.value?.id == route.id)
                before.version.copy(loading = false) else ReleaseRead(),
        )
        val active = mutable.value ?: return
        val read = when (route) {
            WorkerRoute.Overview, WorkerRoute.History -> active.deployments
            WorkerRoute.Versions -> active.versions
            is WorkerRoute.Deployment -> active.deployment
            is WorkerRoute.Version -> active.version
        }
        if (read.value == null && read.error == null) load()
    }

    fun load(more: Boolean = false) {
        val owner = connection ?: return
        val before = mutable.value ?: return
        if (request?.isActive == true) return
        val route = before.route
        when (route) {
            WorkerRoute.Overview, WorkerRoute.History -> {
                val page = if (more) before.deployments.value?.next ?: return else 1
                read(owner, before, before.deployments, more, { s, r -> s.copy(deployments = r) }) { api ->
                    combine(before.deployments.value.takeIf { more }, api.deployments(page), WorkerDeployment::id)
                }
            }
            WorkerRoute.Versions -> {
                val page = if (more) before.versions.value?.next ?: return else 1
                read(owner, before, before.versions, more, { s, r -> s.copy(versions = r) }) { api ->
                    combine(before.versions.value.takeIf { more }, api.versions(page), WorkerVersion::id)
                }
            }
            is WorkerRoute.Deployment -> if (!more) {
                read(owner, before, before.deployment, false, { s, r -> s.copy(deployment = r) }) { it.deployment(route.id) }
            }
            is WorkerRoute.Version -> if (!more) {
                read(owner, before, before.version, false, { s, r -> s.copy(version = r) }) { it.version(route.id) }
            }
        }
    }

    private fun <T> read(
        owner: Connection, before: WorkerReleaseState, previous: ReleaseRead<T>, more: Boolean,
        update: (WorkerReleaseState, ReleaseRead<T>) -> WorkerReleaseState, run: suspend (WorkerReleases) -> T,
    ) {
        val pending = update(before, previous.copy(loading = true, error = null, appendFailed = false))
        mutable.value = pending
        request = scope.launch {
            val result = try {
                val api = factory(owner, before.worker)
                ensureActive()
                val value = run(api)
                ReleaseRead(value, if (more) previous.observedAtMs else nowMs())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previous.copy(loading = false, appendFailed = more, error = error as? CloudFailure
                    ?: CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法读取 Workers，请检查连接后重试"))
            }
            ensureActive()
            if (mutable.value === pending) mutable.value = update(pending, result)
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
