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
                assertEquals(listOf(account), owner.state.value.accounts.map { it.id })
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
                assertEquals(listOf(account), owner.state.value.accounts.map { it.id })
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
                val connecting = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<CloudException> { owner.connect("", account) }
                }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) })
                owner.cancel()
                owner.begin()
                withTimeout(3_000) { connecting.await() }
                assertEquals(OAuthConnectionState(waiting = true), owner.state.value)
                assertEquals(listOf("/accounts?page=1&per_page=50"), paths)
            } finally { scope.cancel() }
        }
    }

    @Test fun callbackDiscoversButDoesNotPersistUntilExplicitVerifiedAccountSelection() = runBlocking {
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
                assertEquals(listOf(account), owner.state.value.accounts.map { it.id })
                assertEquals(listOf("/accounts?page=1&per_page=50"), paths)
                // Replayed delivery cannot exchange a second time or reset an accepted account list.
                assertTrue(owner.acceptCallback(callback))
                assertTrue(owner.state.value.authorized)
                assertEquals(1, server.requestCount)
                assertFailsWith<CloudException> { owner.connect("", "not-an-account") }
                assertNull(saved)
                owner.connect("", account)
                assertEquals("Verified account" to account, saved)
                assertEquals("/accounts/$account", paths.last())
                assertEquals(OAuthConnectionState(), owner.state.value)
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

    private fun identity(token: String, paths: MutableList<String>): CloudflareIdentityClient {
        assertEquals("access-fixture", token)
        return CloudflareIdentityClient(CloudflareGateway(HttpTransport { request ->
            paths += request.path
            val result = if (request.path.startsWith("/accounts?"))
                """{"success":true,"errors":[],"messages":[],"result":[{"id":"$account","name":"Listed account"}],"result_info":{"page":1,"per_page":50,"count":1,"total_count":1}}"""
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
