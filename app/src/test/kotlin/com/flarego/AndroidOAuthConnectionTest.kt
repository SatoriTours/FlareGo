package com.flarego

import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.CloudflareIdentityClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.CloudException
import com.flarego.platform.*
import com.flarego.ui.OAuthConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Test
import kotlin.test.*

class AndroidOAuthConnectionTest {
    private val account = "a".repeat(32)
    private val config = OAuthConfig("client", "https://login.example.org/cloudflare/callback", listOf("account-settings.read"))

    @Test fun singleAccountIsVerifiedAndSavedAutomaticallyAfterCallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val paths = mutableListOf<String>()
                var saved: Pair<String, String>? = null
                val owner = AndroidOAuthConnection(scope, config, { identity(it, paths, multiple = false) },
                    { name, id, _ -> saved = name to id }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                server.enqueue(response())
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { !it.busy && !it.waiting } }
                assertEquals("Verified account" to account, saved)
                assertEquals(listOf("/accounts?page=1&per_page=50", "/accounts/$account"), paths)
            } finally { scope.cancel() }
        }
    }

    @Test fun reopenAndStaleCallbackLeaveActiveBrowserAuthorizationUsable() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val paths = mutableListOf<String>()
                val owner = AndroidOAuthConnection(scope, config, { identity(it, paths) },
                    { _, _, _ -> error("must not persist") }, endpoint(server))
                val first = owner.begin().toHttpUrl()
                assertEquals(first.toString(), owner.begin())
                assertTrue(owner.acceptCallback(config.redirectUri + "?code=stale&state=unrelated"))
                assertEquals(OAuthConnectionState(waiting = true), owner.state.value)
                assertEquals(0, server.requestCount)
                server.enqueue(response())
                owner.acceptCallback(config.redirectUri + "?code=current&state=" + first.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { it.authorized && !it.busy } }
                assertEquals(listOf(account, "b".repeat(32)), owner.state.value.accounts.map { it.id })
                assertEquals(1, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    @Test fun failedAccountDiscoveryCanRetryWithoutAnotherBrowserGrant() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val paths = mutableListOf<String>()
                var attempts = 0
                val owner = AndroidOAuthConnection(scope, config, { token ->
                    if (attempts++ == 0) throw CloudException("network unavailable")
                    identity(token, paths)
                }, { _, _, _ -> error("must not persist") }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                server.enqueue(response())
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { it.authorized && !it.busy } }
                assertNotNull(owner.state.value.error)
                owner.loadMore()
                withTimeout(3_000) { owner.state.first { it.accounts.isNotEmpty() && !it.busy } }
                assertNull(owner.state.value.error)
                assertEquals(listOf(account, "b".repeat(32)), owner.state.value.accounts.map { it.id })
                assertEquals(1, server.requestCount)
                assertEquals(2, attempts)
            } finally { scope.cancel() }
        }
    }

    @Test fun cancellingWhileConnectRefreshesCannotRestoreTokensOrVerifyOldAccount() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val paths = mutableListOf<String>()
                val owner = AndroidOAuthConnection(scope, config, { identity(it, paths) },
                    { _, _, _ -> error("cancelled connection must not persist") }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                val shortLived = """{"access_token":"access-fixture","refresh_token":"refresh-fixture","token_type":"bearer","expires_in":1}"""
                server.enqueue(MockResponse().setBody(shortLived))
                server.enqueue(MockResponse().setBody(shortLived))
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { it.authorized && !it.busy } }
                repeat(2) { assertNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)) }
                server.enqueue(response().setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                owner.connect(account)
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) })
                owner.cancel()
                owner.begin()
                assertEquals(OAuthConnectionState(waiting = true), owner.state.value)
                assertEquals(listOf("/accounts?page=1&per_page=50"), paths)
            } finally { scope.cancel() }
        }
    }

    @Test fun multipleAccountsWaitForSelectionThenVerifyAndSave() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var saved: Pair<String, String>? = null
                val paths = mutableListOf<String>()
                val owner = AndroidOAuthConnection(scope, config, { identity(it, paths) },
                    { name, id, tokens ->
                        assertEquals("access-fixture", tokens.accessToken)
                        saved = name to id
                    }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                assertTrue(owner.state.value.waiting)
                assertFalse(owner.acceptCallback("https://evil.example.org/callback?code=x"))
                server.enqueue(response())
                val callback = config.redirectUri + "?code=code&state=" + auth.queryParameter("state")
                assertTrue(owner.acceptCallback(callback))
                withTimeout(3_000) { owner.state.first { it.authorized && !it.busy } }
                assertNull(saved)
                assertEquals(listOf(account, "b".repeat(32)), owner.state.value.accounts.map { it.id })
                assertEquals(listOf("/accounts?page=1&per_page=50"), paths)
                // Replayed delivery cannot exchange a second time or reset an accepted account list.
                assertTrue(owner.acceptCallback(callback))
                assertTrue(owner.state.value.authorized)
                assertEquals(1, server.requestCount)
                owner.connect("not-an-account")
                assertNull(saved)
                owner.connect(account)
                withTimeout(3_000) { owner.state.first { it.connected } }
                assertEquals("Verified account" to account, saved)
                assertEquals("/accounts/$account", paths.last())
                assertTrue(owner.state.value.connected)
            } finally { scope.cancel() }
        }
    }

    @Test fun cancelledFlowAndProcessRecreationCannotAcceptUnsolicitedCallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val paths = mutableListOf<String>()
                val owner = AndroidOAuthConnection(scope, config, { identity(it, paths) },
                    { _, _, _ -> error("cancelled authorization must not persist") }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                owner.cancel()
                val callback = config.redirectUri + "?code=code&state=" + auth.queryParameter("state")
                assertTrue(owner.acceptCallback(callback))
                assertEquals(OAuthConnectionState(), owner.state.value)
                assertEquals(0, server.requestCount)
                val restored = AndroidOAuthConnection(scope, config, { identity(it, paths) },
                    { _, _, _ -> error("unrequested callback must not persist") }, endpoint(server))
                assertTrue(restored.acceptCallback(callback))
                assertEquals(0, server.requestCount)
                assertTrue(paths.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun providerScopeRejectionIsActionableAndNeverEchoesCallbackContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val owner = AndroidOAuthConnection(scope, config, { error("must not discover") },
                { _, _, _ -> error("must not save") })
            val auth = owner.begin().toHttpUrl()
            owner.acceptCallback(config.redirectUri + "?state=" + auth.queryParameter("state") +
                "&error=invalid_scope&error_description=secret-fixture")
            withTimeout(3_000) { owner.state.first { it.error != null } }
            assertTrue(owner.state.value.error!!.contains("invalid_scope"))
            assertTrue(owner.state.value.error!!.contains("维护者"))
            assertFalse(owner.state.value.error!!.contains("secret-fixture"))
            assertFalse(owner.state.value.authorized)
            assertTrue(owner.begin().isNotBlank())
        } finally { scope.cancel() }
    }

    @Test fun emptyAccountListCanRetryAndThenAutomaticallyConnect() = runBlocking {
        var queries = 0
        exercise({ request ->
            if (request.path.startsWith("/accounts?")) rows(if (queries++ == 0) emptyList() else listOf(account))
            else details(account)
        }) { owner, saved ->
            assertTrue(owner.state.value.discoveryFailed)
            assertTrue(owner.state.value.accounts.isEmpty())
            assertTrue(saved.isEmpty())
            owner.loadMore()
            withTimeout(3_000) { owner.state.first { it.connected } }
            assertEquals(listOf(account), saved)
            assertEquals(2, queries)
        }
    }

    @Test fun incompletePageDoesNotAutoConnectAndFailedNextPageCanRetry() = runBlocking {
        val other = "b".repeat(32)
        var secondPages = 0
        exercise({ request ->
            when {
                request.path.contains("page=1&") -> rows(listOf(account), total = 51)
                request.path.contains("page=2&") -> if (secondPages++ == 0) HttpResponse(503, "private-provider-body") else rows(listOf(other), page = 2, total = 51)
                else -> details(other)
            }
        }) { owner, saved ->
            assertTrue(owner.state.value.hasMore)
            assertTrue(saved.isEmpty())
            owner.loadMore()
            withTimeout(3_000) { owner.state.first { it.discoveryFailed && !it.busy } }
            assertEquals(listOf(account), owner.state.value.accounts.map { it.id })
            owner.loadMore()
            withTimeout(3_000) { owner.state.first { it.accounts.size == 2 && !it.busy } }
            assertFalse(owner.state.value.hasMore)
            assertTrue(saved.isEmpty())
            owner.connect(other)
            withTimeout(3_000) { owner.state.first { it.connected } }
            assertEquals(listOf(other), saved)
            assertEquals(2, secondPages)
        }
    }

    @Test fun accountVerificationFailureNeverSavesAndCanRetryWithoutNewAuthorization() = runBlocking {
        var detailsCalls = 0
        exercise({ request ->
            if (request.path.startsWith("/accounts?")) rows(listOf(account))
            else if (detailsCalls++ == 0) HttpResponse(503, "private-provider-body") else details(account)
        }) { owner, saved ->
            assertTrue(saved.isEmpty())
            assertFalse(owner.state.value.discoveryFailed)
            assertFalse(owner.state.value.connecting)
            assertNotNull(owner.state.value.error)
            assertFalse(owner.state.value.error!!.contains("private-provider-body"))
            owner.connect(account)
            withTimeout(3_000) { owner.state.first { it.connected } }
            assertEquals(listOf(account), saved)
        }
    }

    @Test fun expiredGrantRequiresAuthorizationInsteadOfRepeatedAccountCalls() = runBlocking {
        var calls = 0
        exercise({ calls++; HttpResponse(401, "private-provider-body") }) { owner, saved ->
            assertTrue(owner.state.value.reauthorizationRequired)
            assertTrue(owner.state.value.error!!.contains("重新"))
            owner.loadMore()
            owner.connect(account)
            assertEquals(1, calls)
            assertTrue(saved.isEmpty())
        }
    }

    @Test fun permissionFailureExplainsAccountAccessWithoutEchoingProviderBody() = runBlocking {
        exercise({ HttpResponse(403, "secret-provider-body") }) { owner, saved ->
            assertTrue(owner.state.value.error!!.contains("权限"))
            assertFalse(owner.state.value.error!!.contains("secret-provider-body"))
            assertTrue(saved.isEmpty())
        }
    }

    @Test fun cancellingSlowDiscoveryCannotSaveOrReplaceNewFlow() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var saves = 0
                val owner = AndroidOAuthConnection(scope, config, {
                    CloudflareIdentityClient(CloudflareGateway(HttpTransport {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        rows(listOf(account))
                    }))
                }, { _, _, _ -> saves++ }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                server.enqueue(response())
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { entered.await() }
                owner.cancel()
                owner.begin()
                release.complete(Unit)
                yield()
                assertEquals(OAuthConnectionState(waiting = true), owner.state.value)
                assertEquals(0, saves)
            } finally { release.complete(Unit); scope.cancel() }
        }
    }

    @Test fun completedFlowIgnoresDoubleClickAndReplayedCallback() = runBlocking {
        exercise({ request -> if (request.path.startsWith("/accounts?")) rows(listOf(account)) else details(account) }) { owner, saved ->
            assertTrue(owner.state.value.connected)
            owner.connect(account)
            owner.loadMore()
            owner.acceptCallback(config.redirectUri + "?code=old&state=old")
            assertEquals(listOf(account), saved)
            assertTrue(owner.state.value.connected)
        }
    }

    @Test fun expiredAuthorizationWithoutRefreshOffersReauthorizationAndNeverQueriesAccounts() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val owner = AndroidOAuthConnection(scope, config, { error("expired grant must not query") },
                    { _, _, _ -> error("expired grant must not save") }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                server.enqueue(MockResponse().setBody("""{"access_token":"fixture","token_type":"bearer","expires_in":1}"""))
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { !it.busy && !it.waiting } }
                assertTrue(owner.state.value.reauthorizationRequired)
                owner.loadMore()
                assertEquals(1, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    @Test fun saveFailureKeepsAccountAvailableAndDoesNotReportCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var saves = 0
                val paths = mutableListOf<String>()
                val owner = AndroidOAuthConnection(scope, config, { identity(it, paths, multiple = false) },
                    { _, _, _ -> if (saves++ == 0) throw IllegalStateException("private-vault-error") }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                server.enqueue(response())
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { !it.busy && !it.waiting } }
                assertFalse(owner.state.value.connected)
                assertFalse(owner.state.value.connecting)
                assertTrue(owner.state.value.error!!.contains("保存"))
                assertFalse(owner.state.value.error!!.contains("private-vault-error"))
                owner.connect(account)
                withTimeout(3_000) { owner.state.first { it.connected } }
                assertEquals(2, saves)
            } finally { scope.cancel() }
        }
    }

    private suspend fun exercise(
        send: suspend (HttpRequest) -> HttpResponse,
        check: suspend (AndroidOAuthConnection, List<String>) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val saved = mutableListOf<String>()
                val owner = AndroidOAuthConnection(scope, config, {
                    CloudflareIdentityClient(CloudflareGateway(HttpTransport(send)))
                }, { _, id, _ -> saved += id }, endpoint(server))
                val auth = owner.begin().toHttpUrl()
                server.enqueue(response())
                owner.acceptCallback(config.redirectUri + "?code=code&state=" + auth.queryParameter("state"))
                withTimeout(3_000) { owner.state.first { !it.busy && !it.waiting } }
                check(owner, saved)
                assertEquals(1, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    private fun rows(ids: List<String>, page: Int = 1, total: Int = ids.size): HttpResponse {
        val values = ids.joinToString(",") { """{"id":"$it","name":"Listed account"}""" }
        return HttpResponse(200, """{"success":true,"errors":[],"result":[$values],"result_info":{"page":$page,"per_page":50,"count":${ids.size},"total_count":$total}}""")
    }
    private fun details(id: String) = HttpResponse(200, """{"success":true,"result":{"id":"$id","name":"Verified account"}}""")

    private fun identity(token: String, paths: MutableList<String>, multiple: Boolean = true): CloudflareIdentityClient {
        assertEquals("access-fixture", token)
        return CloudflareIdentityClient(CloudflareGateway(HttpTransport { request ->
            paths += request.path
            val second = if (multiple) ",{\"id\":\"${"b".repeat(32)}\",\"name\":\"Second account\"}" else ""
            val count = if (multiple) 2 else 1
            val result = if (request.path.startsWith("/accounts?"))
                """{"success":true,"errors":[],"messages":[],"result":[{"id":"$account","name":"Listed account"}$second],"result_info":{"page":1,"per_page":50,"count":$count,"total_count":$count}}"""
            else """{"success":true,"errors":[],"messages":[],"result":{"id":"$account","name":"Verified account"}}"""
            HttpResponse(200, result.toByteArray(), emptyMap())
        }))
    }
    private fun endpoint(server: MockWebServer) = OAuthTokenEndpoint(OkHttpClient.Builder()
        .followRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/oauth2/token")).build())
        }.build())
    private fun response() = MockResponse().setBody(
        """{"access_token":"access-fixture","refresh_token":"refresh-fixture","token_type":"bearer","expires_in":900}""")
}
