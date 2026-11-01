package com.flarego.platform

import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.model.CloudException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Authenticated OAuth metadata only. IDs come from Cloudflare, never from guessed display-name slugs. */
class OAuthReadScopes(private val transport: HttpTransport) {
    suspend fun resolve(): List<String> {
        try {
            val response = transport.execute(HttpRequest("GET", "/oauth/scopes"))
            if (response.status != 200 || response.bytes.size > 4 * 1024 * 1024) throw invalid()
            val root = Json.parseToJsonElement(response.body) as? JsonObject ?: throw invalid()
            if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalid()
            val rows = root["result"] as? JsonArray ?: throw invalid()
            if (rows.size !in 1..4096) throw invalid()
            val info = root["result_info"]
            if (info != null && info != JsonNull) {
                val metadata = info as? JsonObject ?: throw invalid()
                metadata["total_count"]?.let {
                    if ((it as? JsonPrimitive)?.intOrNull != rows.size) throw invalid()
                }
            }
            val matches = mutableMapOf<String, String>()
            for (item in rows) {
                val row = item as? JsonObject ?: throw invalid()
                val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid()
                if (name !in names) continue
                val id = (row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid()
                if (!id.matches(Regex("[a-z][a-z0-9_.-]{0,127}")) ||
                    id in setOf("openid", "offline", "offline_access") || matches.put(name, id) != null) throw invalid()
            }
            if (matches.keys != names.toSet() || matches.values.toSet().size != names.size) throw invalid()
            return names.map { matches.getValue(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw invalid() }
    }

    private fun invalid() = CloudException("无法读取或识别 Cloudflare 产品权限目录，请重试；不会猜测权限或保存未验证的连接。")

    companion object {
        // Explicit user-approved read-only set. D1, billing, content reads and all writes are excluded.
        private val names = listOf(
            "Account Settings Read", "Zone Read", "DNS Read", "Workers Metadata Read-Only",
            "Workers KV Storage Read", "Workers R2 Storage Read",
        )
    }
}
