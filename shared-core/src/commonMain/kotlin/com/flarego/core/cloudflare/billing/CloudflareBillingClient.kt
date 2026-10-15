package com.flarego.core.cloudflare.billing

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.ScopedBillingPort
import kotlinx.serialization.json.*

/** Account history by default; legacy user history requires an explicit owner binding. */
class CloudflareBillingClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val userScope: UserScope? = null,
) : ScopedBillingPort {
    init { userScope?.let { ScopeGuard.requireOwned(it, connection) } }

    override suspend fun history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry> {
        ScopeGuard.requireOwned(scope, connection)
        val (key, path) = when (scope) {
            is AccountScope -> "account-billing-history-get-billing-history" to
                "/accounts/${encodePath(scope.accountId)}/billing/history"
            is UserScope -> {
                val owner = userScope ?: throw unsupported()
                if (owner != scope) throw scopeMismatchFailure()
                if (request.limit !in 5..50) throw invalidInputFailure()
                "user-billing-history-(-deprecated)-billing-history-details" to "/user/billing/history"
            }
            else -> throw unsupported()
        }
        if (request.token is PageToken.Cursor) throw invalidInputFailure()
        val strategy = PaginationStrategy.Number((request.token as? PageToken.Number)?.value ?: 1, request.limit)
        val root = readEnvelope(gateway, key, "$path?${Pagination.query(strategy)}")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { value ->
            val row = value as? JsonObject ?: throw invalidResponse(false)
            BillingEntry(
                id = row.optionalText("id") ?: throw invalidResponse(false),
                scope = scope,
                occurredAt = row.optionalText("occurred_at"),
                amount = row.amount(),
                currency = row.optionalText("currency"),
                description = row.optionalText("description"),
                status = if (scope is AccountScope) row.optionalText("status") else null,
                invoiceId = if (scope is AccountScope) row.optionalText("invoice_id") else null,
                receiptId = if (scope is AccountScope) row.optionalText("receipt_id") else null,
            )
        }
        val metadata = Pagination.decode(root, strategy)
        return CloudPage(items, metadata.next, metadata.total)
    }
}

private val JsonNumber = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

/** The lexical JSON number is retained exactly; no Double or inferred amount. */
private fun JsonObject.amount(): String? {
    val value = this["amount"]
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw invalidResponse(false)
    if (primitive.isString || !JsonNumber.matches(primitive.content)) throw invalidResponse(false)
    return primitive.content
}

private fun JsonObject.optionalText(key: String): String? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw invalidResponse(false)
    if (!primitive.isString) throw invalidResponse(false)
    return primitive.content.takeIf { it.isNotBlank() }
}

private fun unsupported() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,
    "此账单范围尚未启用；用户级旧接口需要显式绑定用户范围")
