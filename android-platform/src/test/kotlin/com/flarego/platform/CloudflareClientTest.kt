package com.flarego.platform

import com.flarego.core.cloudflare.HttpRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class CloudflareClientTest {
    @Test
    fun writesDoNotFollow503RetryAfter() {
        listOf("POST", "PATCH", "DELETE").forEach { method ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setResponseCode(200))
                server.start()
                val request =
                    cloudflareRequest(
                            "fixture",
                            HttpRequest(
                                method,
                                "/zones/zone/dns_records",
                                if (method == "DELETE") null else "{}",
                            ),
                        )
                        .newBuilder()
                        .url(server.url("/dns_records"))
                        .build()
                cloudflareHttpClient().newCall(request).execute().use { assertEquals(503, it.code) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun disconnectAfterWriteDoesNotSendAgain() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val request =
                cloudflareRequest("fixture", HttpRequest("POST", "/zones/zone/dns_records", "{}"))
                    .newBuilder()
                    .url(server.url("/dns_records"))
                    .build()
            try {
                cloudflareHttpClient().newCall(request).execute().use {
                    fail("A disconnected write must not become a retried success")
                }
            } catch (_: IOException) {}
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun requestsStayOnCloudflareHttpsHost() {
        val request = cloudflareRequest("fixture", HttpRequest("GET", "/accounts/a"))
        assertEquals("https", request.url.scheme)
        assertEquals("api.cloudflare.com", request.url.host)
        assertEquals("/client/v4/accounts/a", request.url.encodedPath)
    }
}
