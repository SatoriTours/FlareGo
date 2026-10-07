package com.flarego.core.model

data class Connection(
    val id: String,
    val name: String,
    val accountId: String,
    val demo: Boolean = false,
)

data class Domain(val id: String, val name: String, val status: String, val connectionId: String)

enum class ResourceKind(val label: String) {
    WORKER("边缘计算"),
    R2("对象存储"),
    D1("数据库"),
    KV("键值存储"),
}

data class CloudResource(
    val id: String,
    val name: String,
    val kind: ResourceKind,
    val detail: String,
    val usage: String? = null,
)

data class DnsRecord(
    val id: String,
    val type: String,
    val name: String,
    val content: String,
    val ttl: Int = 1,
    val proxied: Boolean = false,
    val proxiable: Boolean = false,
    val priority: Int? = null,
)

data class DnsDraft(
    val type: String,
    val name: String,
    val content: String,
    val ttl: Int = 1,
    val proxied: Boolean = false,
    val priority: Int? = null,
)

data class Inventory(
    val domains: List<Domain>,
    val resources: List<CloudResource>,
    val notices: List<String> = emptyList(),
)

data class Invoice(
    val id: String,
    val date: String,
    val amount: String?,
    val currency: String,
    val description: String,
)

data class Billing(
    val invoices: List<Invoice>,
    val estimate: String? = null,
    val notice: String? = null,
)

enum class OperationStatus(val label: String) {
    SUCCESS("已完成"),
    FAILED("未完成"),
    UNKNOWN("待核对"),
}

data class Operation(
    val id: String,
    val connectionId: String,
    val title: String,
    val status: OperationStatus,
    val detail: String,
)

enum class Page(val title: String) {
    OVERVIEW("总览"),
    DOMAINS("域名"),
    RESOURCES("云资源"),
    BILLING("账单与费用"),
    ACCOUNTS("云账号"),
    DNS("DNS 解析"),
    BUY("购买域名"),
    RESOURCE("资源详情"),
    JOBS("操作记录"),
}

data class AppState(
    val connections: List<Connection>,
    val selected: Connection,
    val page: Page = Page.OVERVIEW,
    val loading: Boolean = false,
    val inventory: Inventory = Inventory(emptyList(), emptyList()),
    val billing: Billing? = null,
    val domain: Domain? = null,
    val resource: CloudResource? = null,
    val dns: List<DnsRecord> = emptyList(),
    val dnsLoading: Boolean = false,
    val error: String? = null,
    val operations: List<Operation> = emptyList(),
)

class CloudException(val publicMessage: String, val uncertain: Boolean = false) :
    Exception(publicMessage)
