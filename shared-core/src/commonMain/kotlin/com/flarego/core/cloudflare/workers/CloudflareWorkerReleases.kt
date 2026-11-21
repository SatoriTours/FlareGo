package com.flarego.core.cloudflare.workers

import com.flarego.core.application.workers.*
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import kotlin.math.abs

/** API contract: scripts-search resolves exact IDs; scripts deployments page 1 starts with the active deployment. */
class CloudflareWorkerReleases(
    private val connection: Connection,
    private val worker: CloudResource,
    private val gateway: CloudflareGateway,
) : WorkerReleases {
    private suspend fun scriptPath(): String {
        val ref = worker.resourceRef ?: throw scopeMismatchFailure()
        val account = ref.scope as? AccountScope ?: throw scopeMismatchFailure()
        ScopeGuard.requireOwned(account, connection)
        if (connection.demo || worker.kind != ResourceKind.WORKER || ref.externalId != worker.id ||
            ref.kind != if (worker.legacy) "worker_script" else "worker"
        ) throw scopeMismatchFailure()
        val base = "/accounts/${encodePath(account.accountId)}/workers"
        val name = if (worker.legacy) ref.externalId else {
            val search = PaginationStrategy.Number(1, 2)
            val root = get("worker-script-search-workers",
                "$base/scripts-search?id=${encodePath(ref.externalId)}&${Pagination.query(search)}")
            val match = rows(root["result"]).singleOrNull()
            val meta = Pagination.decode(root, search)
            if (match == null || match.text("id") != ref.externalId || meta.next != null || (meta.total ?: 1L) != 1L) throw noScript()
            match.text("script_name") ?: throw noScript()
        }
        if (!SCRIPT_NAME.matches(name)) throw noScript()
        return "$base/scripts/${encodePath(name)}"
    }

    override suspend fun deployments(page: Int) =
        list("worker-deployments-list-deployments", "deployments", "deployments", page, WorkerDeployment::id, ::decodeDeployment)

    override suspend fun versions(page: Int) =
        list("worker-versions-list-versions", "versions", "items", page, WorkerVersion::id, ::decodeVersion)

    override suspend fun deployment(id: String): WorkerDeployment {
        requireUuid(id)
        val root = get("worker-deployments-get-deployment", "${scriptPath()}/deployments/$id")
        return decodeDeployment(root.result()).also { if (it.id != id) throw invalidResponse(false) }
    }

    override suspend fun version(id: String): WorkerVersion {
        requireUuid(id)
        // This endpoint returns metadata/runtime config; no code, secret or binding endpoints are called.
        val root = get("worker-versions-get-version-detail", "${scriptPath()}/versions/$id")
        return decodeVersion(root.result()).also { if (it.id != id) throw invalidResponse(false) }
    }

    private suspend fun <T> list(
        action: String, path: String, field: String, page: Int, id: (T) -> String, decode: (JsonObject) -> T,
    ): ReleasePage<T> {
        val paging = PaginationStrategy.Number(page, 10)
        val root = get(action, "${scriptPath()}/$path?${Pagination.query(paging)}")
        val items = rows(root.result()[field]).map(decode)
        if (items.size > paging.perPage || items.distinctBy(id).size != items.size) throw invalidResponse(false)
        val meta = Pagination.decode(root, paging)
        val info = root["result_info"] as? JsonObject
        val reportedPage = info?.get("page")
        if (reportedPage != null && (reportedPage as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != page)
            throw invalidResponse(false)
        val hasMetadata = info?.get("total_pages") != null || (meta.total != null && info?.get("per_page") != null)
        // The scripts versions envelope does not guarantee result_info. A full page can still have a successor.
        val next = (meta.next as? PageToken.Number)?.value ?: when {
            !hasMetadata && items.size == paging.perPage && page < Int.MAX_VALUE -> page + 1
            else -> null
        }
        if (items.isEmpty() && next != null) throw invalidResponse(false)
        return ReleasePage(items, next)
    }

    private suspend fun get(action: String, path: String): JsonObject {
        currentCoroutineContext().ensureActive()
        return readEnvelope(gateway, action, path)
    }
}

private val SCRIPT_NAME = Regex("[a-z0-9_][a-z0-9_-]*")
private val UUID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private fun requireUuid(id: String) {
    if (!UUID.matches(id)) throw invalidInputFailure()
}
private fun noScript() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
    "无法用 Worker ID 唯一确认目标脚本。部署与版本暂不可读；不会使用显示名称猜测。")
private fun JsonObject.result() = this["result"] as? JsonObject ?: throw invalidResponse(false)
private fun rows(value: JsonElement?) =
    (value as? JsonArray)?.map { it as? JsonObject ?: throw invalidResponse(false) } ?: throw invalidResponse(false)
private fun JsonObject.text(key: String) =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.uuid(key: String) = text(key)?.takeIf(UUID::matches) ?: throw invalidResponse(false)

private fun decodeDeployment(row: JsonObject): WorkerDeployment {
    if (row.text("strategy") != "percentage") throw invalidResponse(false)
    val allocations = rows(row["versions"]).map {
        val percentage = (it["percentage"] as? JsonPrimitive)?.takeUnless { value -> value.isString }
            ?: throw invalidResponse(false)
        val number = percentage.doubleOrNull?.takeIf { n -> n in 0.01..100.0 } ?: throw invalidResponse(false)
        Allocation(it.uuid("version_id"), percentage.content) to number
    }
    if (allocations.distinctBy { it.first.versionId }.size != allocations.size ||
        abs(allocations.sumOf { it.second } - 100.0) > 0.00001
    ) throw invalidResponse(false)
    return WorkerDeployment(
        row.uuid("id"), row.text("created_on") ?: throw invalidResponse(false), row.text("source"),
        (row["annotations"] as? JsonObject)?.text("workers/message"), allocations.map { it.first },
    )
}

private fun decodeVersion(row: JsonObject): WorkerVersion {
    val meta = row["metadata"] as? JsonObject
    val resources = row["resources"] as? JsonObject
    val runtime = resources?.get("script_runtime") as? JsonObject
    return WorkerVersion(
        row.uuid("id"), (row["number"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 },
        meta?.text("created_on"), meta?.text("source"), runtime?.text("compatibility_date"),
    )
}
