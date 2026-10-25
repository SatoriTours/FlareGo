package com.flarego.platform

import com.flarego.core.model.CloudException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test

/** Real flow, real PKCE and real HTTP; only the Cloudflare server and encrypted disk boundary are substituted. */
class CloudflareOAuthTest {
    private val config = OAuthConfig("test-client", "https://login.example.org/cloudflare/callback", listOf("account.read"))

    @Test fun reopeningPendingAuthorizationPreservesPkceUntilExpiryOrExplicitCancellation() {
        var now = 0L
        val flow = OAuthFlow(config, nowMs = { now })
        val first = flow.begin()
        now = 600_000L
        assertEquals(first, flow.begin())
        now++
        val renewed = flow.begin()
        assertNotEquals(first, renewed)
        flow.cancel()
        assertNotEquals(renewed, flow.begin())
    }

    @Test fun unrelatedStateCannotConsumePendingAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val flow = OAuthFlow(config, endpoint(server))
            val auth = flow.begin().toHttpUrl()
            for (invalid in listOf(
                config.redirectUri + "?code=other&state=unrelated",
                callback(auth) + "&state=duplicate",
                config.redirectUri + "?code=other",
            )) {
                assertFailsWith<CloudException> { flow.complete(invalid) }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(tokenResponse())
            assertEquals("access-fixture", flow.complete(callback(auth)).accessToken)
            assertEquals(1, server.requestCount)
            assertFailsWith<CloudException> { flow.complete(callback(auth)) }
            Unit
        }
    }

    @Test fun authorizationUsesPkceAndNeverSendsAClientSecret() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val flow = OAuthFlow(config, endpoint(server), { 1_000L })
            val url = flow.begin().toHttpUrl()
            assertEquals("dash.cloudflare.com", url.host)
            assertEquals("/oauth2/auth", url.encodedPath)
            assertEquals("code", url.queryParameter("response_type"))
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            assertEquals("account.read offline_access", url.queryParameter("scope"))
            assertTrue(url.queryParameter("state")!!.length >= 43)
            assertNull(url.queryParameter("client_secret"))
            assertNull(url.queryParameter("code_verifier"))
            server.enqueue(tokenResponse())
            val tokens = flow.complete(callback(url, "sample-code"))
            assertEquals("access-fixture", tokens.accessToken)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/oauth2/token", request.path)
            val body = request.body.readUtf8()
            val fields = ("https://example.org/?$body").toHttpUrl()
            assertEquals("authorization_code", fields.queryParameter("grant_type"))
            assertEquals("sample-code", fields.queryParameter("code"))
            assertEquals(config.redirectUri, fields.queryParameter("redirect_uri"))
            val verifier = fields.queryParameter("code_verifier")!!
            val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            assertEquals(expected, url.queryParameter("code_challenge"))
            assertNull(fields.queryParameter("client_secret"))
            assertFalse(tokens.toString().contains("access-fixture"))
            assertFalse(tokens.toString().contains("refresh-fixture"))
        }
    }

    @Test fun rejectsWrongOriginWrongStateDuplicateParametersExpiredAndReplayedCallbacks() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var now = 0L
            val flow = OAuthFlow(config, endpoint(server), { now })
            var auth = flow.begin().toHttpUrl()
            assertFailsWith<CloudException> { flow.complete(callback(auth).replace("login.example.org", "evil.example.org")) }
            assertEquals(0, server.requestCount)
            auth = flow.begin().toHttpUrl()
            assertFailsWith<CloudException> { flow.complete(callback(auth) + "&state=other") }
            auth = flow.begin().toHttpUrl()
            assertFailsWith<CloudException> { flow.complete(callback(auth).replace(auth.queryParameter("state")!!, "wrong-state")) }
            auth = flow.begin().toHttpUrl()
            now = 600_001
            assertFailsWith<CloudException> { flow.complete(callback(auth)) }
            assertEquals(0, server.requestCount)
            auth = flow.begin().toHttpUrl()
            server.enqueue(tokenResponse())
            flow.complete(callback(auth))
            assertFailsWith<CloudException> { flow.complete(callback(auth)) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun denialAndMalformedTokenErrorsNeverEchoProviderContent() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val flow = OAuthFlow(config, endpoint(server))
            var auth = flow.begin().toHttpUrl()
            val denied = config.redirectUri + "?state=" + auth.queryParameter("state") + "&error=access_denied&error_description=secret-fixture"
            val failure = assertFailsWith<CloudException> { flow.complete(denied) }
            assertFalse(failure.message.orEmpty().contains("secret-fixture"))
            for (response in listOf(
                MockResponse().setResponseCode(400).setBody("secret-fixture"),
                MockResponse().setBody("""{"access_token":"secret-fixture","token_type":"bearer","expires_in":0}"""),
                MockResponse().setBody("""{"access_token":"secret-fixture","token_type":"mac","expires_in":900}"""),
                MockResponse().setBody("x".repeat(65537)),
            )) {
                auth = flow.begin().toHttpUrl(); server.enqueue(response)
                assertFalse(assertFailsWith<CloudException> { flow.complete(callback(auth)) }.message.orEmpty().contains("secret-fixture"))
            }
        }
    }

    @Test fun tokenEndpointDoesNotFollowRedirects() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            other.enqueue(tokenResponse())
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.url("/steal")))
            val flow = OAuthFlow(config, endpoint(server))
            val auth = flow.begin().toHttpUrl()
            assertFailsWith<CloudException> { flow.complete(callback(auth)) }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun refreshIsSingleFlightPersistsRotationAndLegacyTokensRemainUntouched() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val disk = mutableMapOf("legacy" to "old-api-token")
            var now = 0L
            val credentials = OAuthCredentials(config, endpoint(server), { disk[it] }, { id, value -> disk[id] = value }, { disk.remove(it); Unit }, { now })
            assertEquals("old-api-token", credentials.accessToken("legacy"))
            credentials.putOAuth("oauth", OAuthTokens("first-access", "first-refresh", 1_000L))
            now = 2_000L
            server.enqueue(tokenResponse("rotated-access", "rotated-refresh").setBodyDelay(100, TimeUnit.MILLISECONDS))
            coroutineScope {
                val a = async { credentials.accessToken("oauth") }
                val b = async { credentials.accessToken("oauth") }
                assertEquals("rotated-access", a.await()); assertEquals("rotated-access", b.await())
            }
            assertEquals(1, server.requestCount)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("grant_type=refresh_token")); assertTrue(body.contains("refresh_token=first-refresh"))
            assertFalse(body.contains("client_secret"))
            val restored = OAuthCredentials(config, endpoint(server), { disk[it] }, { id, value -> disk[id] = value }, { disk.remove(it); Unit }, { now })
            assertEquals("rotated-access", restored.accessToken("oauth"))
            assertEquals("old-api-token", disk["legacy"])
            restored.remove("oauth")
            assertNull(restored.accessToken("oauth"))
        }
    }

    @Test fun refreshFailureDoesNotReplayOrOverwriteStoredCredential() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val disk = mutableMapOf<String, String>()
            val credentials = OAuthCredentials(config, endpoint(server), { disk[it] }, { id, value -> disk[id] = value }, { disk.remove(it); Unit }, { 5_000L })
            credentials.putOAuth("id", OAuthTokens("old-access", "old-refresh", 1_000L))
            val before = disk["id"]
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"))
            assertFailsWith<CloudException> { credentials.accessToken("id") }
            assertEquals(before, disk["id"])
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cachedTransportUsesRenewedTokenWithoutReplayingWritesAndClosesOnRefreshFailure() = runBlocking {
        val seen = mutableListOf<String>()
        var token = "first"
        val transport = RenewingHttpTransport({ token }) { current ->
            com.flarego.core.cloudflare.HttpTransport { seen += current; com.flarego.core.cloudflare.HttpResponse(401, byteArrayOf(), emptyMap()) }
        }
        val request = com.flarego.core.cloudflare.HttpRequest("POST", "/accounts/id/test")
        assertEquals(401, transport.execute(request).status)
        token = "renewed"
        assertEquals(401, transport.execute(request).status)
        assertEquals(listOf("first", "renewed"), seen)
        var sourceClosed = 0
        var sinkClosed = 0
        val source = object : com.flarego.core.ports.ByteSource {
            override fun read(buffer: ByteArray) = -1
            override fun close() { sourceClosed++ }
        }
        val sink = object : com.flarego.core.ports.ByteSink {
            override fun write(buffer: ByteArray, offset: Int, count: Int) { error("must not write") }
            override fun close() { sinkClosed++ }
        }
        val failing = RenewingTransferPort({ throw CloudException("expired") }) { error("must not acquire transport") }
        assertFailsWith<CloudException> { failing.download(request.copy(payload = com.flarego.core.cloudflare.HttpBody.Stream(source, 0, "application/octet-stream")), sink) }
        assertEquals(1, sourceClosed); assertEquals(1, sinkClosed)
    }

    private fun endpoint(server: MockWebServer) = OAuthTokenEndpoint(cloudflareHttpClient { request ->
        if (request.url.host == "dash.cloudflare.com") request.newBuilder().url(server.url(request.url.encodedPath)).build() else request
    })
    private fun callback(auth: okhttp3.HttpUrl, code: String = "code") =
        config.redirectUri + "?code=$code&state=" + auth.queryParameter("state")
    private fun tokenResponse(access: String = "access-fixture", refresh: String = "refresh-fixture") =
        MockResponse().setBody("""{"access_token":"$access","refresh_token":"$refresh","token_type":"bearer","expires_in":900,"scope":"account.read offline_access"}""")
}
