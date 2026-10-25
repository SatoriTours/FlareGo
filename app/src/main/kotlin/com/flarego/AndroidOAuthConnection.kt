package com.flarego

import android.content.Context
import com.flarego.core.cloudflare.identity.CloudflareIdentityClient
import com.flarego.core.model.CloudException
import com.flarego.core.model.PageToken
import com.flarego.core.model.PageRequest
import com.flarego.platform.*
import com.flarego.ui.OAuthConnection
import com.flarego.ui.OAuthConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun cloudflareOAuthConfig() = OAuthConfig(
    BuildConfig.CLOUDFLARE_OAUTH_CLIENT_ID,
    BuildConfig.CLOUDFLARE_OAUTH_REDIRECT_URI,
    BuildConfig.CLOUDFLARE_OAUTH_SCOPES.split(' ').filter(String::isNotBlank),
)

/** One app-wide credential lock owner: refresh/replace/remove cannot resurrect a disconnected grant. */
internal object OAuthCredentialOwner {
    private var credentials: OAuthCredentials? = null
    @Synchronized fun get(context: Context, config: OAuthConfig): OAuthCredentials = credentials ?: run {
        val vault = TokenVault(context.applicationContext)
        OAuthCredentials(config, OAuthTokenEndpoint(), vault::get, vault::put, vault::remove).also { credentials = it }
    }
}

/** ViewModel-lifetime authorization; secrets are never put into Compose state, Bundles or logs. */
internal class AndroidOAuthConnection(
    private val scope: CoroutineScope,
    private val config: OAuthConfig,
    private val identity: (String) -> CloudflareIdentityClient,
    private val save: suspend (String, String, OAuthTokens) -> Unit,
    private val endpoint: OAuthTokenEndpoint = OAuthTokenEndpoint(),
) : OAuthConnection {
    private val mutable = MutableStateFlow(OAuthConnectionState())
    override val state: StateFlow<OAuthConnectionState> = mutable.asStateFlow()
    override val configured get() = config.configured
    private val flow = OAuthFlow(config, endpoint)
    private var tokens: OAuthTokens? = null
    private var next: PageToken? = null
    private var generation = 0L
    private var job: Job? = null

    override fun begin(): String {
        if (!state.value.waiting) cancel()
        val url = flow.begin()
        mutable.value = OAuthConnectionState(waiting = true)
        return url
    }
    override fun cancel() {
        generation++
        job?.cancel(); job = null
        flow.cancel(); tokens = null; next = null
        mutable.value = OAuthConnectionState()
    }
    override fun launchFailed() {
        cancel()
        mutable.value = OAuthConnectionState(error = "无法打开浏览器，请安装或启用浏览器后重试")
    }
    fun acceptCallback(value: String): Boolean {
        if (!flow.matchesCallback(value)) return false
        // An unsolicited/replayed callback must not cancel an already accepted exchange.
        if (!state.value.waiting || state.value.busy || !flow.matchesPendingCallback(value)) return true
        val expected = generation
        mutable.value = OAuthConnectionState(busy = true)
        job = scope.launch {
            try {
                val result = flow.complete(value)
                if (generation != expected) return@launch
                tokens = result
                mutable.value = OAuthConnectionState(authorized = true, busy = true)
                discover(expected, null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (generation == expected) mutable.value = OAuthConnectionState(error = "Cloudflare 授权未完成，请重新授权；不会保存未验证的连接")
            }
        }
        return true
    }
    private suspend fun freshTokens(expected: Long): OAuthTokens {
        if (generation != expected) throw CloudException("授权已变化，请重新连接")
        val current = tokens ?: throw CloudException("请先完成 Cloudflare 授权")
        if (current.expiresAtMs > System.currentTimeMillis() + 30_000) return current
        val refresh = current.refreshToken ?: throw CloudException("授权已过期，请重新授权")
        val renewed = endpoint.refresh(config, refresh, System.currentTimeMillis())
        if (generation != expected || tokens !== current) throw CloudException("授权已变化，请重新连接")
        return OAuthTokens(renewed.accessToken, renewed.refreshToken ?: refresh, renewed.expiresAtMs).also { tokens = it }
    }
    private suspend fun discover(expected: Long, page: PageToken?) {
        try {
            val result = identity(freshTokens(expected).accessToken).discover(PageRequest(token = page))
            if (generation != expected) return
            next = result.next
            mutable.value = OAuthConnectionState(authorized = true,
                accounts = (if (page == null) result.items else state.value.accounts + result.items).distinctBy { it.id },
                hasMore = next != null,
                error = if (result.items.isEmpty() && page == null) "未发现可用账号，可手填 Account ID 验证；请核对账号读取权限" else null)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            if (generation == expected) mutable.value = state.value.copy(busy = false, authorized = true, discoveryFailed = true,
                error = "无法发现账号，可手填 Account ID 验证；请核对账号读取权限")
        }
    }
    override fun loadMore() {
        val page = next
        if (page == null && !state.value.discoveryFailed) return
        if (state.value.busy || !state.value.authorized) return
        val expected = generation
        mutable.value = state.value.copy(busy = true, error = null)
        job = scope.launch { discover(expected, page) }
    }
    override suspend fun connect(name: String, accountId: String) {
        if (state.value.busy || !state.value.authorized) throw CloudException("请先完成授权并选择账号")
        val account = accountId.trim().lowercase()
        if (!account.matches(Regex("[a-f0-9]{32}"))) throw CloudException("Account ID 必须为 32 位十六进制字符")
        val expected = generation
        mutable.value = state.value.copy(busy = true, error = null)
        try {
            val granted = freshTokens(expected)
            val verified = identity(granted.accessToken).verifyAccount(account)
            val label = name.trim().ifBlank { verified.name }
            if (label.length !in 1..80) throw CloudException("账号名称长度应为 1–80 字符")
            if (generation != expected || tokens !== granted) throw CloudException("授权已变化，请重新连接")
            save(label, account, granted)
            cancel()
        } finally {
            if (generation == expected) mutable.value = state.value.copy(busy = false)
        }
    }
}
