package com.flarego.core.demo

import com.flarego.core.model.*
import com.flarego.core.ports.*

val demoConnections =
    listOf(
        Connection("demo-production", "Production · 演示", "demo", true),
        Connection("demo-sandbox", "Dev sandbox · 演示", "sandbox", true),
    )

class DemoSession(private val connection: Connection) : CloudSession {
    private val records = mutableMapOf<String, MutableList<DnsRecord>>()

    override suspend fun inventory(): Inventory {
        if (connection.accountId == "sandbox" || connection.id == "b")
            return Inventory(emptyList(), emptyList())
        return Inventory(
            listOf(
                Domain("zone-demo", "flarego.dev", "active", connection.id),
                Domain("zone-app", "getflare.app", "active", connection.id),
                Domain("zone-design", "studio.design", "pending", connection.id),
                Domain("zone-io", "flarego.io", "active", connection.id),
            ),
            listOf(
                CloudResource(
                    "worker-api",
                    "api-gateway",
                    ResourceKind.WORKER,
                    "Workers · 全球边缘网络",
                    "182 万请求",
                ),
                CloudResource(
                    "worker-image",
                    "image-transform",
                    ResourceKind.WORKER,
                    "Workers · 图片处理",
                    "68 万请求",
                ),
                CloudResource(
                    "bucket-media",
                    "media-assets",
                    ResourceKind.R2,
                    "R2 · APAC",
                    "12.4 GB",
                ),
                CloudResource(
                    "bucket-backup",
                    "backup-archive",
                    ResourceKind.R2,
                    "R2 · WEUR",
                    "8.2 GB",
                ),
                CloudResource("d1-app", "app-database", ResourceKind.D1, "D1 · 数据库", "128 MB"),
                CloudResource(
                    "kv-sessions",
                    "session-cache",
                    ResourceKind.KV,
                    "KV · 键值存储",
                    "2.8 万键",
                ),
            ),
        )
    }

    override suspend fun records(zoneId: String): List<DnsRecord> =
        records
            .getOrPut(zoneId) {
                mutableListOf(
                    DnsRecord(
                        "record-a",
                        "A",
                        "flarego.dev",
                        "192.0.2.10",
                        proxied = true,
                        proxiable = true,
                    ),
                    DnsRecord(
                        "record-www",
                        "CNAME",
                        "www.flarego.dev",
                        "flarego.dev",
                        proxied = true,
                        proxiable = true,
                    ),
                    DnsRecord(
                        "record-api",
                        "CNAME",
                        "api.flarego.dev",
                        "gateway.example.workers.dev",
                        proxied = true,
                        proxiable = true,
                    ),
                    DnsRecord(
                        "record-mx",
                        "MX",
                        "flarego.dev",
                        "mail.example.com",
                        ttl = 3600,
                        priority = 10,
                    ),
                )
            }
            .toList()

    override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
        records(zoneId)
        val list = records.getValue(zoneId)
        val next =
            DnsRecord(
                recordId ?: "demo-${list.size}-${draft.name}",
                draft.type,
                draft.name,
                draft.content,
                draft.ttl,
                draft.proxied,
                draft.type in setOf("A", "AAAA", "CNAME"),
                draft.priority,
            )
        val index = list.indexOfFirst { it.id == recordId }
        if (recordId != null && index < 0) throw CloudException("记录已不存在，请刷新")
        if (index >= 0) list[index] = next else list.add(next)
    }

    override suspend fun delete(zoneId: String, recordId: String) {
        records(zoneId)
        records.getValue(zoneId).removeAll { it.id == recordId }
    }

    override suspend fun billing() =
        if (connection.accountId == "sandbox" || connection.id == "b")
            Billing(emptyList(), notice = "空白演示账号")
        else
            Billing(
                listOf(
                    Invoice("invoice-demo", "2026-09-30", "68.40", "USD", "Cloudflare 服务 · 示例账单")
                ),
                "48.62",
                "金额和用量为示例，无真实扣款",
            )
}
