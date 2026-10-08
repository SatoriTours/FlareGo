package com.flarego.core.cloudflare

import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.PageMetadata
import com.flarego.core.cloudflare.protocol.Pagination
import com.flarego.core.cloudflare.protocol.PaginationStrategy
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.model.*
import com.flarego.core.ports.CloudSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * The existing Cloudflare session, now routed through the handwritten action
 * policies, the shared decoders and explicit pagination strategies.
 *
 * The public API and its legacy error behaviour are unchanged: a write whose
 * outcome cannot be confirmed (a 202 acceptance, `success=false`, a server error,
 * an unreadable body) surfaces as a stable uncertain [CloudException] instead of a
 * normal return, because this legacy API cannot express an accepted or partial
 * state. Real ownership of the connection is still not proven here.
 */
class CloudflareSession(
    private val connection: Connection,
    private val transport: HttpTransport,
    private val gateway: CloudflareGateway = CloudflareGateway(transport),
) : CloudSession {
    private val accountPath = "/accounts/${encodePath(connection.accountId)}"

    suspend fun verifyAccount() {
        request("GET", "accounts-account-details", accountPath, label = "账号验证")
    }

    private suspend fun request(
        method: String,
        actionKey: String,
        path: String,
        body: String? = null,
        label: String,
    ): JsonObject {
        val policy =
            gateway.policyOf(actionKey)
                ?: throw CloudException("$label 未在本地策略中登记，请更新应用后重试")
        val writing = policy.semantics == ActionSemantics.WRITE
        val response =
            try {
                gateway.send(actionKey, HttpRequest(method, path, body))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                throw CloudException(failure.publicMessage, failure.uncertain)
            } catch (_: Exception) {
                throw CloudException(
                    if (writing) "$label 请求中断；结果待核对，请刷新后检查，勿重复提交"
                    else "$label 暂时无法连接，请检查网络后重试",
                    writing,
                )
            }
        if (writing && response.status == 202) {
            // Accepted is not completion; this legacy API has no state for it.
            throw CloudException("$label 已被接受但尚未完成；结果待核对，请刷新后检查，勿重复提交", true)
        }
        if (ResponseDecoders.isExplicitlyEmpty(response.status)) {
            throw CloudException("$label 返回了无内容状态（HTTP ${response.status}），请刷新后重试")
        }
        val root =
            try {
                ResponseDecoders.envelope(response, writing)
            } catch (failure: CloudFailure) {
                throw CloudException(failure.publicMessage, failure.uncertain)
            }
        val success = (root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (success != true) {
            throw CloudException(
                if (writing) "$label 未确认成功；结果待核对，请刷新后检查，勿重复提交"
                else "$label 未确认成功，请检查权限或输入后刷新",
                writing,
            )
        }
        return root
    }

    /**
     * Reads every page of a list action using its explicit strategy. [Pagination]
     * decides whether another page exists from the provider metadata only — the
     * number of items on a page is never used to infer a complete result set.
     */
    private suspend fun list(
        actionKey: String,
        method: String,
        path: String,
        label: String,
        strategy: PaginationStrategy,
        nested: String? = null,
    ): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var current: PaginationStrategy? = strategy
        var pages = 0
        while (current != null) {
            if (pages >= MAX_PAGES) throw CloudException("$label 结果过多，请在官方控制台查看完整列表")
            pages++
            val query = Pagination.query(current)
            val fullPath = if (query.isEmpty()) path else "$path${if ('?' in path) '&' else '?'}$query"
            val root = request(method, actionKey, fullPath, label = label)
            val result = root["result"] ?: throw CloudException("$label 返回数据缺失，请稍后重试")
            val array =
                (if (nested != null) result.jsonObject[nested] else result) as? JsonArray
                    ?: throw CloudException("$label 返回格式无法识别")
            all.addAll(array.map { it.jsonObject })
            current =
                try {
                    Pagination.next(current, Pagination.decode(root, current))
                } catch (failure: CloudFailure) {
                    throw CloudException(failure.publicMessage)
                }
        }
        return all
    }

    override suspend fun inventory(): Inventory = coroutineScope {
        val notices = mutableListOf<String>()
        suspend fun safeList(
            actionKey: String,
            method: String,
            path: String,
            label: String,
            strategy: PaginationStrategy,
            nested: String? = null,
        ): List<JsonObject> =
            try {
                list(actionKey, method, path, label, strategy, nested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: CloudException) {
                notices.add(e.publicMessage)
                emptyList()
            } catch (_: Exception) {
                notices.add("$label 返回结构无法识别，请稍后刷新或使用官方控制台")
                emptyList()
            }
        // Collection writes stay on the caller context; each endpoint may be unavailable
        // independently.
        val zones =
            safeList(
                "zones-get",
                "GET",
                "/zones?account.id=${encodePath(connection.accountId)}",
                "域名",
                PaginationStrategy.Number(),
            )
        val workers =
            safeList(
                "worker-script-list-workers",
                "GET",
                "$accountPath/workers/scripts",
                "Workers",
                PaginationStrategy.None,
            )
        val buckets =
            safeList(
                "r2-list-buckets",
                "GET",
                "$accountPath/r2/buckets",
                "R2",
                PaginationStrategy.Cursor(),
                nested = "buckets",
            )
        val databases =
            safeList(
                "d1-list-databases",
                "GET",
                "$accountPath/d1/database",
                "D1",
                PaginationStrategy.Number(),
            )
        val namespaces =
            safeList(
                "workers-kv-namespace-list-namespaces",
                "GET",
                "$accountPath/storage/kv/namespaces",
                "KV",
                PaginationStrategy.Number(),
            )
        Inventory(
            zones.map { Domain(it.text("id"), it.text("name"), it.text("status"), connection.id) },
            workers.map {
                CloudResource(
                    it.text("id"),
                    it.text("id"),
                    ResourceKind.WORKER,
                    "Workers · ${it.text("modified_on").take(10).ifBlank { "全球边缘网络" }}",
                )
            } +
                buckets.map {
                    CloudResource(
                        it.text("name"),
                        it.text("name"),
                        ResourceKind.R2,
                        "R2 · ${it.text("location").ifBlank { "默认区域" }}",
                    )
                } +
                databases.map {
                    CloudResource(it.text("uuid"), it.text("name"), ResourceKind.D1, "D1 · 数据库")
                } +
                namespaces.map {
                    CloudResource(it.text("id"), it.text("title"), ResourceKind.KV, "KV · 键值存储")
                },
            notices,
        )
    }

    override suspend fun records(zoneId: String) =
        list(
            "dns-records-for-a-zone-list-dns-records",
            "GET",
            "/zones/${encodePath(zoneId)}/dns_records",
            "DNS",
            PaginationStrategy.Number(),
        )
            .map {
                DnsRecord(
                    it.text("id"),
                    it.text("type"),
                    it.text("name"),
                    it.text("content"),
                    it["ttl"]?.jsonPrimitive?.intOrNull ?: 1,
                    it["proxied"]?.jsonPrimitive?.booleanOrNull ?: false,
                    it["proxiable"]?.jsonPrimitive?.booleanOrNull ?: false,
                    it["priority"]?.jsonPrimitive?.intOrNull,
                )
            }

    override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
        val payload = buildJsonObject {
            put("type", draft.type)
            put("name", draft.name)
            put("content", draft.content)
            put("ttl", draft.ttl)
            if (draft.type in setOf("A", "AAAA", "CNAME")) put("proxied", draft.proxied)
            draft.priority?.let { put("priority", it) }
        }
        val path =
            "/zones/${encodePath(zoneId)}/dns_records" +
                if (recordId == null) "" else "/${encodePath(recordId)}"
        request(
            if (recordId == null) "POST" else "PATCH",
            if (recordId == null) "dns-records-for-a-zone-create-dns-record" else "dns-records-for-a-zone-patch-dns-record",
            path,
            payload.toString(),
            "DNS 写入",
        )
    }

    override suspend fun delete(zoneId: String, recordId: String) {
        request(
            "DELETE",
            "dns-records-for-a-zone-delete-dns-record",
            "/zones/${encodePath(zoneId)}/dns_records/${encodePath(recordId)}",
            label = "DNS 删除",
        )
    }

    override suspend fun billing(): Billing {
        val invoices =
            list(
                "user-billing-history-(-deprecated)-billing-history-details",
                "GET",
                "/user/billing/history",
                "用户账单",
                PaginationStrategy.Number(),
            )
                .map {
                    Invoice(
                        it.text("id"),
                        it.text("occurred_at").take(10),
                        it["amount"]?.jsonPrimitive?.contentOrNull,
                        it.text("currency"),
                        it.text("description"),
                    )
                }
        return Billing(invoices, notice = "用户级账单历史，可能包含其他账号；不代表当前账号的本月费用。未提供费用预估。")
    }
}

private const val MAX_PAGES = 100

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
