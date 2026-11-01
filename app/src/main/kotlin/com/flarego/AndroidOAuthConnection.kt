package com.flarego

import android.content.Context
import com.flarego.core.cloudflare.identity.CloudflareIdentityClient
import com.flarego.core.model.CloudException
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
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
    private val resolveReadScopes: (suspend (String) -> List<String>)? = null,
) : OAuthConnection {
    private val mutable = MutableStateFlow(OAuthConnectionState())
    override val state: StateFlow<OAuthConnectionState> = mutable.asStateFlow()
    override val configured get() = config.configured
    private var activeConfig = config
    private var flow = OAuthFlow(activeConfig, endpoint)
    // Only public catalog IDs are cached. No bootstrap credential is persisted.
    private var resolvedConfig: OAuthConfig? = null
    private var tokens: OAuthTokens? = null
    private var next: PageToken? = null
    private var generation = 0L
    private var job: Job? = null

    override fun begin(): String {
        check(!state.value.busy) { "连接正在处理中" }
        if (!state.value.waiting) {
            cancel()
            activeConfig = resolvedConfig ?: config
            flow = OAuthFlow(activeConfig, endpoint)
        }
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
                mutable.value = OAuthConnectionState(authorized = true, busy = true,
                    readingScopes = resolveReadScopes != null && resolvedConfig == null)
                if (resolveReadScopes != null && resolvedConfig == null) prepareProductGrant(expected)
                else discover(expected, null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == expected) mutable.value = OAuthConnectionState(error =
                    (failure as? CloudException)?.publicMessage
                        ?: "Cloudflare 授权未完成，请检查网络后重新授权；不会保存未验证的连接")
            }
        }
        return true
    }
    private suspend fun freshTokens(expected: Long): OAuthTokens {
        if (generation != expected) throw CloudException("授权已变化，请重新连接")
        val current = tokens ?: throw CloudException("请先完成 Cloudflare 授权")
        if (current.expiresAtMs > System.currentTimeMillis() + 30_000) return current
        val refresh = current.refreshToken ?: throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "授权已过期，请重新授权")
        val renewed = try { endpoint.refresh(activeConfig, refresh, System.currentTimeMillis()) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "授权无法续期，请重新授权") }
        if (generation != expected || tokens !== current) throw CloudException("授权已变化，请重新连接")
        return OAuthTokens(renewed.accessToken, renewed.refreshToken ?: refresh, renewed.expiresAtMs).also { tokens = it }
    }
    private suspend fun prepareProductGrant(expected: Long) {
        try {
            val granted = freshTokens(expected)
            val ids = resolveReadScopes?.invoke(granted.accessToken) ?: throw CloudException("未配置权限目录")
            if (generation != expected || tokens !== granted) return
            val upgraded = OAuthConfig(config.clientId, config.redirectUri, ids)
            if (!upgraded.configured || ids.size != 6 || ids.distinct().size != 6 ||
                ids.any { it in setOf("openid", "offline", "offline_access") }) throw CloudException("权限目录不完整")
            resolvedConfig = upgraded
            // The first grant only discovers public scope IDs. Only the second grant may be saved.
            tokens = null
            next = null
            mutable.value = OAuthConnectionState(authorized = true, productAuthorizationRequired = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (generation == expected) mutable.value = OAuthConnectionState(
                authorized = true, scopeDiscoveryFailed = true,
                reauthorizationRequired = failure is CloudFailure && failure.code == CloudFailureCode.CREDENTIAL_EXPIRED,
                error = "读取产品授权范围未完成，请重试或重新授权；不会猜测权限，也不会保存未验证的连接。",
            )
        }
    }

    private suspend fun discover(expected: Long, page: PageToken?) {
        try {
            val result = identity(freshTokens(expected).accessToken).discover(PageRequest(token = page))
            if (generation != expected) return
            next = result.next
            val accounts = (if (page == null) result.items else state.value.accounts + result.items).distinctBy { it.id }
            mutable.value = OAuthConnectionState(authorized = true,
                busy = next == null && accounts.size == 1,
                connecting = next == null && accounts.size == 1,
                accounts = accounts,
                hasMore = next != null,
                discoveryFailed = accounts.isEmpty(),
                error = if (accounts.isEmpty()) "授权已完成，但没有找到可连接的账号。请重新读取，或返回 Cloudflare 确认授权的账号。" else null)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (generation == expected) mutable.value = state.value.copy(busy = false, authorized = true, discoveryFailed = true,
                reauthorizationRequired = failure is CloudFailure && failure.code == CloudFailureCode.CREDENTIAL_EXPIRED,
                error = connectionError(failure, "读取账号"))
            return
        }
        // Only a complete, unambiguous list permits automatic selection. A partial page never does.
        if (generation == expected && next == null && state.value.accounts.size == 1) {
            verifyAndSave(expected, state.value.accounts.single().id)
        }
    }
    override fun loadMore() {
        val page = next
        if (page == null && !state.value.discoveryFailed && !state.value.scopeDiscoveryFailed) return
        if (state.value.busy || !state.value.authorized || state.value.connected || state.value.reauthorizationRequired) return
        val expected = generation
        val retryScopes = state.value.scopeDiscoveryFailed
        mutable.value = state.value.copy(busy = true, error = null, readingScopes = retryScopes)
        job = scope.launch {
            if (retryScopes) prepareProductGrant(expected) else discover(expected, page)
        }
    }
    override fun connect(accountId: String) {
        if (state.value.busy || !state.value.authorized || state.value.connected || state.value.reauthorizationRequired) return
        if (state.value.accounts.none { it.id == accountId }) return
        val expected = generation
        mutable.value = state.value.copy(busy = true, connecting = true, error = null)
        job = scope.launch { verifyAndSave(expected, accountId) }
    }
    private suspend fun verifyAndSave(expected: Long, account: String) {
        if (generation != expected) return
        mutable.value = state.value.copy(busy = true, connecting = true, error = null)
        var saving = false
        try {
            val granted = freshTokens(expected)
            val verified = identity(granted.accessToken).verifyAccount(account)
            if (generation != expected || tokens !== granted) return
            saving = true
            save(verified.name, account, granted)
            if (generation != expected) return
            tokens = null; next = null; flow.cancel()
            mutable.value = OAuthConnectionState(connected = true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (generation == expected) mutable.value = state.value.copy(busy = false, connecting = false,
                reauthorizationRequired = failure is CloudFailure && failure.code == CloudFailureCode.CREDENTIAL_EXPIRED,
                error = if (saving) "连接信息未能保存，请检查设备存储后重试。" else connectionError(failure, "连接账号"))
        }
    }

    private fun connectionError(failure: Exception, action: String): String = when ((failure as? CloudFailure)?.code) {
        CloudFailureCode.CREDENTIAL_EXPIRED -> "授权已失效或无法续期，请重新前往 Cloudflare 授权。"
        CloudFailureCode.PERMISSION_REQUIRED -> "Cloudflare 未允许$action。请确认所选账号的授权；若仍无法连接，请联系维护者检查应用的账号读取权限。"
        CloudFailureCode.RATE_LIMITED -> "请求过于频繁，请稍后重试。"
        CloudFailureCode.INVALID_RESPONSE -> "Cloudflare 返回的账号信息无法识别，请稍后重试；若持续发生，请联系维护者。"
        else -> "${action}未完成，请检查网络后重试。不会保存未验证的连接。"
    }
}
