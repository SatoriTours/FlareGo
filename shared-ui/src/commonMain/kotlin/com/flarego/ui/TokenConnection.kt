package com.flarego.ui

import com.flarego.core.application.connection.TokenConnectionStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * API Token connection contract for the UI layer.
 *
 * A Token connection verifies a credential, discovers exactly the accounts that credential can
 * reach and saves only after provider confirmation. OAuth is retired: a legacy OAuth record is
 * recognized by storage and must be reconfigured with an API Token, never re-sent.
 *
 * No implementation may put the token value in [state], a saved state, a log or an exception.
 * [TokenConnectionStatus.tokenHeld] is the only hint that a value is still in memory.
 */
interface TokenConnection {
    val state: StateFlow<TokenConnectionStatus>

    /** Starts (or restarts) with a pasted token; nothing is saved until an account is verified. */
    fun submit(token: String)

    /** Repeats the failed step while a token is still held. */
    fun retry()

    /** Appends the next discovered account page. */
    fun loadMore()

    /** Connects one discovered account after the provider confirms it. */
    fun selectAccount(accountId: String)

    /**
     * Manual path for a restricted or account-owned token that cannot list accounts:
     * verifies this exact account before anything is stored.
     */
    fun useAccountId(accountId: String)

    /** Abandons the flow and forgets the token; an already saved connection is untouched. */
    fun cancel()
}
