package com.flarego.core.cloudflare.inventory

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.serialization.json.*

/** One credential-bound account, one explicit product, one requested page. */
class CloudflareDirectoryClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val workersMode: WorkersDirectoryMode = WorkersDirectoryMode.MODERN,
) : ProductDirectoryPort {
    override suspend fun zones(account: AccountScope, request: PageRequest): CloudPage<Domain> {
        ScopeGuard.requireOwned(account, connection)
        val strategy = number(request, 5..50)
        val root = readEnvelope(gateway, "zones-get",
            "/zones?account.id=${encodePath(account.accountId)}&${Pagination.query(strategy)}")
        val items = rows(root["result"]).map { row ->
            val owner = row["account"] as? JsonObject ?: throw invalidResponse(false)
            if (owner.requiredText("id") != account.accountId) throw scopeMismatchFailure()
            Domain(row.requiredText("id"), row.requiredText("name"), row.optionalText("status") ?: "unknown", connection.id)
        }
        val metadata = Pagination.decode(root, strategy)
        return CloudPage(items, metadata.next, metadata.total)
    }

    override suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest): CloudPage<CloudResource> {
        ScopeGuard.requireOwned(account, connection)
        val base = "/accounts/${encodePath(account.accountId)}"
        val legacy = kind == ResourceKind.WORKER && workersMode == WorkersDirectoryMode.LEGACY
        val strategy = when {
            legacy -> {
                if (request.token != null) throw invalidInputFailure()
                PaginationStrategy.None
            }
            kind == ResourceKind.R2 -> {
                if (request.limit !in 1..1000 || request.token is PageToken.Number) throw invalidInputFailure()
                PaginationStrategy.Cursor((request.token as? PageToken.Cursor)?.value)
            }
            kind == ResourceKind.WORKER -> number(request, 1..100)
            kind == ResourceKind.D1 -> number(request, 10..10000)
            else -> number(request, 1..1000)
        }
        val (key, path) = when (kind) {
            ResourceKind.WORKER -> if (legacy) "worker-script-list-workers" to "$base/workers/scripts"
                else "listWorkers" to "$base/workers/workers"
            ResourceKind.R2 -> "r2-list-buckets" to "$base/r2/buckets"
            ResourceKind.D1 -> "d1-list-databases" to "$base/d1/database"
            ResourceKind.KV -> "workers-kv-namespace-list-namespaces" to "$base/storage/kv/namespaces"
        }
        val query = Pagination.query(strategy, request.limit)
        val root = readEnvelope(gateway, key, if (query.isEmpty()) path else "$path?$query")
        val result = if (kind == ResourceKind.R2) {
            val nested = root["result"] as? JsonObject ?: throw invalidResponse(false)
            nested["buckets"]
        } else root["result"]
        val items = rows(result).map { row ->
            val id = row.requiredText(when (kind) {
                ResourceKind.R2 -> "name"
                ResourceKind.D1 -> "uuid"
                else -> "id"
            })
            val name = when {
                legacy || kind == ResourceKind.R2 -> id
                kind == ResourceKind.KV -> row.requiredText("title")
                else -> row.requiredText("name")
            }
            val refKind = when (kind) {
                ResourceKind.WORKER -> if (legacy) "worker_script" else "worker"
                ResourceKind.R2 -> "r2_bucket"
                ResourceKind.D1 -> "d1_database"
                ResourceKind.KV -> "kv_namespace"
            }
            val detail = when (kind) {
                ResourceKind.WORKER -> if (legacy) "Workers · 旧接口" else "Workers"
                ResourceKind.R2 -> row.optionalText("location")?.let { "R2 · $it" } ?: "R2"
                ResourceKind.D1 -> "D1 · 数据库"
                ResourceKind.KV -> "KV · 键值存储"
            }
            CloudResource(id, name, kind, detail, source = key, legacy = legacy,
                resourceRef = ResourceRef(account, refKind, id),
                deployedOn = if (kind == ResourceKind.WORKER && !legacy) row.optionalText("deployed_on") else null)
        }
        val metadata = Pagination.decode(root, strategy)
        return CloudPage(items, metadata.next, metadata.total)
    }
}

private fun number(request: PageRequest, limits: IntRange): PaginationStrategy.Number {
    if (request.limit !in limits || request.token is PageToken.Cursor) throw invalidInputFailure()
    return PaginationStrategy.Number((request.token as? PageToken.Number)?.value ?: 1, request.limit)
}

private fun rows(element: JsonElement?): List<JsonObject> =
    (element as? JsonArray)?.map { it as? JsonObject ?: throw invalidResponse(false) } ?: throw invalidResponse(false)

private fun JsonObject.requiredText(key: String): String = optionalText(key) ?: throw invalidResponse(false)

private fun JsonObject.optionalText(key: String): String? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw invalidResponse(false)
}
