package com.flarego.ui

import com.flarego.core.ports.AccountSummary
import kotlinx.coroutines.flow.StateFlow

/** No authorization code, verifier, access/refresh token, or callback URI crosses the UI boundary. */
data class OAuthConnectionState(
    val waiting: Boolean = false,
    val busy: Boolean = false,
    val authorized: Boolean = false,
    val accounts: List<AccountSummary> = emptyList(),
    val hasMore: Boolean = false,
    val error: String? = null,
)

interface OAuthConnection {
    val configured: Boolean
    val state: StateFlow<OAuthConnectionState>
    fun begin(): String
    fun cancel()
    fun launchFailed()
    fun loadMore()
    suspend fun connect(name: String, accountId: String)
}
