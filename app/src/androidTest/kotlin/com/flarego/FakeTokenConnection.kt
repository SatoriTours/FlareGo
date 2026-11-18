package com.flarego

import com.flarego.core.application.connection.TokenConnectionStatus
import com.flarego.ui.TokenConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test double for the UI contract: it only records intents and never touches a credential. */
internal class FakeTokenConnection(
    initial: TokenConnectionStatus = TokenConnectionStatus(),
) : TokenConnection {
    val mutable = MutableStateFlow(initial)
    override val state: StateFlow<TokenConnectionStatus> = mutable
    var cancels = 0
    var submits = 0
    override fun submit(token: String) { submits++ }
    override fun retry() {}
    override fun loadMore() {}
    override fun selectAccount(accountId: String) {}
    override fun useAccountId(accountId: String) {}
    override fun cancel() { cancels++ }
}
