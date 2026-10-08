package com.flarego.platform

import kotlin.test.*
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/** The accepted bounded in-memory response limit. */
internal const val FOUR_MIB = 4 * 1024 * 1024

/**
 * Rewrites only the production fixed Cloudflare URL onto MockWebServer, in an
 * application interceptor (before DNS/connection). Any other host is left alone,
 * so a followed redirect would really reach the second server.
 */
internal fun cloudflareRewrite(server: MockWebServer): (Request) -> Request = { request ->
    if (request.url.host == "api.cloudflare.com") {
        val query = request.url.encodedQuery
        request
            .newBuilder()
            .url(server.url(request.url.encodedPath + if (query == null) "" else "?$query"))
            .build()
    } else request
}

/** The actual production transport driven by the actual production client. */
internal fun testTransport(
    server: MockWebServer,
    client: OkHttpClient = cloudflareHttpClient(cloudflareRewrite(server)),
): CloudflareTransport = CloudflareTransport("fixture", client)

/** The actual production transfer port driven by the actual production file client. */
internal fun testTransfers(
    server: MockWebServer,
    client: OkHttpClient = cloudflareFileClient(cloudflareRewrite(server)),
): CloudflareTransfers = CloudflareTransfers("fixture", client)

/** A deterministic [ByteSource] that records reads, threads and closes. */
internal class RecordingSource(
    private val chunks: List<ByteArray>,
    private val blockBeforeEof: (() -> Unit)? = null,
) : ByteSource {
    val reads = AtomicInteger()
    val closed = AtomicInteger()
    @Volatile var readThread: String? = null
    @Volatile var closeThread: String? = null
    private var index = 0

    override fun read(buffer: ByteArray): Int {
        reads.incrementAndGet()
        readThread = Thread.currentThread().name
        if (index >= chunks.size) {
            blockBeforeEof?.invoke()
            return -1
        }
        val chunk = chunks[index++]
        chunk.copyInto(buffer, 0, 0, chunk.size)
        return chunk.size
    }

    override fun close() {
        closeThread = Thread.currentThread().name
        closed.incrementAndGet()
    }
}

/** A deterministic [ByteSink] that records writes, threads and closes. */
internal class RecordingSink(private val onFirstWrite: (() -> Unit)? = null) : ByteSink {
    private val buffer = ByteArrayOutputStream()
    val writes = AtomicInteger()
    val closed = AtomicInteger()
    @Volatile var writeThread: String? = null

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        if (writes.getAndIncrement() == 0) onFirstWrite?.invoke()
        this.buffer.write(buffer, offset, count)
        writeThread = Thread.currentThread().name
    }

    override fun close() {
        closed.incrementAndGet()
    }

    fun size(): Int = buffer.size()

    fun bytes(): ByteArray = buffer.toByteArray()
}

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

    @Test
    fun legacyStringBodyRequestStillCarriesItsJsonString() {
        val legacy = HttpRequest("POST", "/zones/zone/dns_records", """{"type":"A"}""")
        assertEquals("""{"type":"A"}""", legacy.body)
        assertTrue(legacy.payload is HttpBody.Text)
        assertNull(HttpRequest("GET", "/accounts/a").body)
        assertEquals(HttpBody.Empty, HttpRequest("GET", "/accounts/a").payload)
    }

    @Test
    fun legacyStringResponseStillExposesUtf8Body() {
        val legacy = HttpResponse(200, "ok")
        assertEquals("ok", legacy.body)
        assertEquals(2, legacy.bytes.size)
        val binary = HttpResponse(200, byteArrayOf(0, -1, -128), emptyMap())
        assertEquals(3, binary.bytes.size)
    }

    @Test
    fun fixedCloudflareHostKeepsHttpsPortAndClientPrefix() {
        val request = cloudflareRequest("fixture", HttpRequest("GET", "/accounts/a/tokens?page=1"))
        assertEquals("https", request.url.scheme)
        assertEquals(443, request.url.port)
        assertEquals("api.cloudflare.com", request.url.host)
        assertTrue(request.url.toString().startsWith("https://api.cloudflare.com/client/v4/"))
        assertEquals("/client/v4/accounts/a/tokens", request.url.encodedPath)
        assertEquals("page=1", request.url.encodedQuery)
    }

    @Test
    fun secureClientDefaultsAreKeptForNormalRequests() {
        val client = cloudflareHttpClient()
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(35_000, client.callTimeoutMillis)
    }

    @Test
    fun filePolicyUsesLongerTimeoutsButTheSameSafetyPolicy() {
        val file = cloudflareFileClient()
        assertEquals(600_000, file.callTimeoutMillis)
        assertEquals(60_000, file.readTimeoutMillis)
        assertEquals(15_000, file.connectTimeoutMillis)
        assertFalse(file.retryOnConnectionFailure)
        assertFalse(file.followRedirects)
        assertFalse(file.followSslRedirects)
    }

    @Test
    fun getAndHeadCannotCarryABusinessPayload() {
        for (method in listOf("GET", "HEAD")) {
            val request =
                HttpRequest(
                    method,
                    "/accounts/a",
                    HttpBody.Text("""{"a":1}""", "application/json"),
                )
            val failure = assertFailsWith<CloudFailure>(method) { cloudflareRequest("fixture", request) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, method)
        }
    }

    @Test
    fun emptyPostIsStillSentAsAOneShotRequestBody(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val response = testTransport(server).execute(HttpRequest("POST", "/zones/zone/x"))
            assertEquals(200, response.status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals(0, recorded.bodySize)
            assertEquals("0", recorded.getHeader("Content-Length"))
            assertEquals(1, server.requestCount)
        }
    }
}
