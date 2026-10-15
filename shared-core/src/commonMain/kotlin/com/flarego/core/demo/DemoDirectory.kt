package com.flarego.core.demo

import com.flarego.core.model.*
import com.flarego.core.ports.*

/** Explicit demo-only data, with in-memory paging; never a fallback for a real failure. */
class DemoDirectory(private val connection: Connection) : ProductDirectoryPort {
    init {
        if (!connection.demo) throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "演示目录只用于显式演示连接")
    }
    private val session = DemoSession(connection)

    override suspend fun zones(account: AccountScope, request: PageRequest): CloudPage<Domain> {
        ScopeGuard.requireOwned(account, connection)
        return page(session.inventory().domains, request)
    }

    override suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest): CloudPage<CloudResource> {
        ScopeGuard.requireOwned(account, connection)
        val refKind = when (kind) {
            ResourceKind.WORKER -> "worker"
            ResourceKind.R2 -> "r2_bucket"
            ResourceKind.D1 -> "d1_database"
            ResourceKind.KV -> "kv_namespace"
        }
        val items = session.inventory().resources.filter { it.kind == kind }.map { resource ->
            resource.copy(detail = "${resource.detail} · 示例", source = "demo", legacy = false,
                resourceRef = ResourceRef(account, refKind, resource.id))
        }
        return page(items, request)
    }

    private fun <T> page(items: List<T>, request: PageRequest): CloudPage<T> {
        if (request.token is PageToken.Cursor) throw invalidInputFailure()
        val current = (request.token as? PageToken.Number)?.value ?: 1
        val offset = (current.toLong() - 1) * request.limit
        val total = items.size.toLong()
        if (offset >= total) return CloudPage(emptyList(), null, total)
        val end = minOf(offset + request.limit, total).toInt()
        val next = if (end < items.size && current < Int.MAX_VALUE) PageToken.Number(current + 1) else null
        return CloudPage(items.subList(offset.toInt(), end).toList(), next, total)
    }
}
