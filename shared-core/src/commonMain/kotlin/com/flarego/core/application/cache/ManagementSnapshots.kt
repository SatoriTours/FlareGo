package com.flarego.core.application.cache

import com.flarego.core.application.dns.DnsRecordView
import com.flarego.core.model.*
import kotlinx.serialization.json.*

data class CachedZones(val items: List<ZoneDetails>, val total: Long?, val observedAtMs: Long)
data class CachedDns(val zoneName: String, val rows: List<DnsRecordView>, val total: Long?, val observedAtMs: Long)

/** Display projections only: raw API bodies and DNS writable fields never enter SQLite. */
class ManagementSnapshots(private val store: ReadSnapshotStore) {
    suspend fun zones(account: AccountScope, now: Long): CachedZones? = safely {
        val snapshot = store.read(account,"zones-v1")?.takeIf { it.usable(now) } ?: return@safely null
        val root = Json.parseToJsonElement(snapshot.payload).jsonObject
        CachedZones(root.getValue("items").jsonArray.map { item ->
            val j = item.jsonObject
            ZoneDetails(ZoneScope(account,j.text("id")),j.text("name"),j.text("status"),j.getValue("paused").jsonPrimitive.boolean,
                j.text("type"),j.getValue("ns").jsonArray.map { it.jsonPrimitive.content },JsonObject(emptyMap()))
        }, root["total"]?.jsonPrimitive?.longOrNull, snapshot.observedAtMs)
    }
    suspend fun saveZones(account: AccountScope, items: List<ZoneDetails>, total: Long?, complete: Boolean, now: Long, isCurrent: () -> Boolean = { true }) { safely {
        if (items.any { it.ref.account != account }) return@safely
        val merged = if(complete) items else merge(zones(account,now)?.items.orEmpty(),items) { it.ref }
        store.write(account,"zones-v1",ReadSnapshot(buildJsonObject {
            put("total",total); put("items",JsonArray(merged.map { z -> buildJsonObject {
                put("id",z.ref.zoneId);put("name",z.name);put("status",z.status);put("paused",z.paused);put("type",z.type)
                put("ns",JsonArray(z.nameservers.map(::JsonPrimitive)))
            } }))
        }.toString(),now),isCurrent)
    } }
    suspend fun dns(scope: ZoneScope, now: Long): CachedDns? = safely {
        val snapshot = store.read(scope.account,"dns-v1/${scope.zoneId}")?.takeIf { it.usable(now) } ?: return@safely null
        val root=Json.parseToJsonElement(snapshot.payload).jsonObject
        CachedDns(root.text("name"),root.getValue("items").jsonArray.map { item ->
            val j=item.jsonObject
            DnsRecordView(j.text("id"),j.text("type"),j.text("name"),j["content"]?.jsonPrimitive?.contentOrNull,
                j["ttl"]?.jsonPrimitive?.intOrNull,j["proxied"]?.jsonPrimitive?.booleanOrNull,j["priority"]?.jsonPrimitive?.intOrNull,
                JsonObject(emptyMap()),readOnlyReason="本地快照；打开后重新核实记录")
        },root["total"]?.jsonPrimitive?.longOrNull,snapshot.observedAtMs)
    }
    suspend fun saveDns(scope: ZoneScope, name: String, rows: List<DnsRecordView>, total: Long?, complete: Boolean, now: Long, isCurrent: () -> Boolean = { true }) { safely {
        val merged=if(complete) rows else merge(dns(scope,now)?.rows.orEmpty(),rows) { it.id }
        store.write(scope.account,"dns-v1/${scope.zoneId}",ReadSnapshot(buildJsonObject {
            put("name",name);put("total",total);put("items",JsonArray(merged.map { row -> buildJsonObject {
                put("id",row.id);put("type",row.type);put("name",row.name);put("ttl",row.ttl);put("proxied",row.proxied);put("priority",row.priority)
                // TXT and uncommon structured data can contain verification secrets or private material.
                if(row.type in setOf("A","AAAA","CNAME","MX","NS","PTR")) put("content",row.content)
            } }))
        }.toString(),now),isCurrent)
    } }
}
internal fun <T,K> merge(old: List<T>, fresh: List<T>, key: (T)->K): List<T> =
    (fresh + old.filter { prior -> fresh.none { key(it) == key(prior) } })
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
