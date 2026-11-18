package com.flarego

import com.flarego.core.application.connection.TokenConnectionController
import com.flarego.core.application.connection.TokenConnectionStatus
import com.flarego.ui.TokenConnection
import kotlinx.coroutines.flow.StateFlow

/**
 * Binds the shared-core controller to the UI contract.
 *
 * The adapter exists only because shared-core cannot depend on shared-ui; it holds no state of
 * its own, so the token never leaves [TokenConnectionController]'s private memory.
 */
internal class AndroidTokenConnection(
    private val controller: TokenConnectionController,
) : TokenConnection {
    override val state: StateFlow<TokenConnectionStatus> = controller.status
    override fun submit(token: String) = controller.submit(token)
    override fun retry() = controller.retry()
    override fun loadMore() = controller.loadMore()
    override fun selectAccount(accountId: String) = controller.selectAccount(accountId)
    override fun useAccountId(accountId: String) = controller.useAccountId(accountId)
    override fun cancel() = controller.cancel()
}
