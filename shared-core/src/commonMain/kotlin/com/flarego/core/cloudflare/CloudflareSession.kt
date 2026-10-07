package com.flarego.core.cloudflare

import com.flarego.core.model.*
import com.flarego.core.ports.CloudSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class CloudflareSession(private val connection: Connection, private val transport: HttpTransport) :
    CloudSession {
    private val accountPath = "/accounts/${encodePath(connection.accountId)}"

    suspend fun verifyAccount() {
        request("GET", accountPath, label = "账号验证")
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        label: String,
    ): JsonObject {
        val writing = method != "GET"
        val response =
            try {
                transport.execute(HttpRequest(method, path, body))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw CloudException(
                    if (writing) "$label 请求中断；结果待核对，请刷新后检查，勿重复提交" else "$label 暂时无法连接，请检查网络后重试",
                    writing,
                )
            }
        if (response.status !in 200..299) {
            val text =
                when (response.status) {
                    401,
                    403 -> "$label 无访问权限；请检查 Token 的服务权限和账号范围"
                    429 -> "$label 请求过于频繁，请稍后重试"
                    in 500..599 -> "$label 服务暂不可用${if (writing) "；结果待核对，请刷新检查" else ""}"
                    else -> "$label 未被接受（HTTP ${response.status}），请检查输入并刷新"
                }
            throw CloudException(text, writing && response.status >= 500)
        }
        val root =
            try {
                Json.parseToJsonElement(response.body).jsonObject
            } catch (_: Exception) {
                throw CloudException("$label 响应无法识别${if (writing) "；结果待核对" else ""}", writing)
            }
        val success = (root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (success == null)
            throw CloudException("$label 响应结构无法识别${if (writing) "；结果待核对" else ""}", writing)
        if (!success) throw CloudException("$label 未确认成功，请检查权限或输入后刷新")
        return root
    }

    private suspend fun list(
        path: String,
        label: String,
        nested: String? = null,
        paginated: Boolean = true,
    ): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page = 1
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(100) {
            val query = if (cursor != null) "cursor=${encodePath(cursor)}" else "page=$page"
            val root =
                request(
                    "GET",
                    if (paginated) "$path${if ('?' in path) '&' else '?'}per_page=50&$query"
                    else path,
                    label = label,
                )
            val result = root["result"] ?: throw CloudException("$label 返回数据缺失，请稍后重试")
            val array =
                (if (nested != null) result.jsonObject[nested] else result) as? JsonArray
                    ?: throw CloudException("$label 返回格式无法识别")
            all.addAll(array.map { it.jsonObject })
            if (!paginated) return all
            val info = root["result_info"] as? JsonObject
            val next = info?.text("cursor")?.takeIf { it.isNotBlank() }
            if (next != null) {
                if (!seen.add(next)) throw CloudException("$label 分页游标重复，请稍后刷新")
                cursor = next
            } else {
                if (cursor != null) return all
                val pages =
                    info?.get("total_pages")?.jsonPrimitive?.intOrNull
                        ?: info?.get("total_count")?.jsonPrimitive?.intOrNull?.let { count ->
                            (count + (info["per_page"]?.jsonPrimitive?.intOrNull ?: 50) - 1) /
                                (info["per_page"]?.jsonPrimitive?.intOrNull ?: 50)
                        }
                if (pages != null && page >= pages || pages == null && array.size < 50) return all
                page++
            }
        }
        throw CloudException("$label 结果过多，请在官方控制台查看完整列表")
    }

    override suspend fun inventory(): Inventory = coroutineScope {
        val notices = mutableListOf<String>()
        suspend fun safeList(
            path: String,
            label: String,
            nested: String? = null,
            paginated: Boolean = true,
        ): List<JsonObject> =
            try {
                list(path, label, nested, paginated)
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
        val zones = safeList("/zones?account.id=${encodePath(connection.accountId)}", "域名")
        val workers = safeList("$accountPath/workers/scripts", "Workers", paginated = false)
        val buckets = safeList("$accountPath/r2/buckets", "R2", "buckets")
        val databases = safeList("$accountPath/d1/database", "D1")
        val namespaces = safeList("$accountPath/storage/kv/namespaces", "KV")
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
        list("/zones/${encodePath(zoneId)}/dns_records", "DNS").map {
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
        request(if (recordId == null) "POST" else "PATCH", path, payload.toString(), "DNS 写入")
    }

    override suspend fun delete(zoneId: String, recordId: String) {
        request(
            "DELETE",
            "/zones/${encodePath(zoneId)}/dns_records/${encodePath(recordId)}",
            label = "DNS 删除",
        )
    }

    override suspend fun billing(): Billing {
        val invoices =
            list("/user/billing/history", "用户账单").map {
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

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
