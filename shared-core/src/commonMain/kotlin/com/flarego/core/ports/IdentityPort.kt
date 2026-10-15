package com.flarego.core.ports

import com.flarego.core.model.CloudPage
import com.flarego.core.model.PageRequest

enum class TokenKind { USER_OWNED, ACCOUNT_OWNED, UNKNOWN }
data class AccountSummary(val id: String, val name: String)
data class TokenVerification(val kind: TokenKind, val active: Boolean?, val notice: String?)

interface IdentityPort {
    suspend fun discover(request: PageRequest = PageRequest()): CloudPage<AccountSummary>
    suspend fun verifyAccount(accountId: String): AccountSummary
    suspend fun verifyToken(kind: TokenKind, accountId: String? = null): TokenVerification
}
