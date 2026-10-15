package com.flarego.core.cloudflare.identity

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.serialization.json.*

/** Credential-bound identity reads. Discovery and token health never grant account access. */
class CloudflareIdentityClient(private val gateway: CloudflareGateway) : IdentityPort {
    override suspend fun discover(request: PageRequest): CloudPage<AccountSummary> {
        if (request.limit !in 5..50 || request.token is PageToken.Cursor) throw invalidInputFailure()
        val strategy = PaginationStrategy.Number((request.token as? PageToken.Number)?.value ?: 1, request.limit)
        val root = readEnvelope(gateway, "accounts-list-accounts", "/accounts?${Pagination.query(strategy)}")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { account(it as? JsonObject ?: throw invalidResponse(false)) }
        val metadata = Pagination.decode(root, strategy)
        return CloudPage(items, metadata.next, metadata.total)
    }

    override suspend fun verifyAccount(accountId: String): AccountSummary {
        if (accountId.isBlank()) throw invalidInputFailure()
        val root = readEnvelope(gateway, "accounts-account-details", "/accounts/${encodePath(accountId)}")
        val account = account(root["result"] as? JsonObject ?: throw invalidResponse(false))
        if (account.id != accountId) throw invalidResponse(false)
        return account
    }

    override suspend fun verifyToken(kind: TokenKind, accountId: String?): TokenVerification {
        val route = when (kind) {
            TokenKind.USER_OWNED -> "user-api-tokens-verify-token" to "/user/tokens/verify"
            TokenKind.ACCOUNT_OWNED -> {
                if (accountId.isNullOrBlank()) throw invalidInputFailure()
                "account-api-tokens-verify-token" to "/accounts/${encodePath(accountId)}/tokens/verify"
            }
            TokenKind.UNKNOWN -> return TokenVerification(kind, null, "请选择 Token 类型后验证；仍可直接验证指定账号")
        }
        val root = readEnvelope(gateway, route.first, route.second)
        val result = root["result"] as? JsonObject ?: throw invalidResponse(false)
        result.requiredText("id")
        val active = when (result.requiredText("status")) {
            "active" -> true
            "disabled", "expired" -> false
            else -> null
        }
        return TokenVerification(kind, active, when (active) {
            true -> "Token 当前有效；这不表示拥有全部权限，连接前仍需验证指定账号"
            false -> "Token 未处于有效状态，请检查禁用状态或有效期"
            null -> "Token 状态无法确认，请验证指定账号或检查官方控制台"
        })
    }

    private fun account(row: JsonObject) = AccountSummary(row.requiredText("id"), row.requiredText("name"))
}

private fun JsonObject.requiredText(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw invalidResponse(false)
