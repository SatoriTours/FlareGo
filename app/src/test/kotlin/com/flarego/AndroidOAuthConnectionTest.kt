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
    private val config = OAuthConfig("client", "https://login.example.org/cloudflare/callback", listOf("account.read"))

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
