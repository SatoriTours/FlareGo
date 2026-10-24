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
    fun onlyReviewedBusinessHeadersReachTheWire() {
        // The R2 storage-class and controlled jurisdiction headers are the reviewed additions.
        val accepted = cloudflareRequest("fixture",
            HttpRequest("PATCH", "/accounts/a/r2/buckets/b", headers = mapOf("cf-r2-storage-class" to "Standard")))
        assertEquals("Standard", accepted.header("cf-r2-storage-class"))
        // The jurisdiction header carries only the pinned schema enum, verbatim.
        for (jurisdiction in listOf("default", "eu", "us", "fedramp", "fedramp-high")) {
            val withJurisdiction =
                cloudflareRequest(
                    "fixture",
                    HttpRequest("PUT", "/accounts/a/r2/buckets/b/cors", headers = mapOf("cf-r2-jurisdiction" to jurisdiction)),
                )
            assertEquals(jurisdiction, withJurisdiction.header("cf-r2-jurisdiction"))
        }
        for (invalid in listOf("", "eu ", " EU", "EU", "mars", "default,eu", "eu;us")) {
            val failure =
                assertFailsWith<CloudFailure>(invalid) {
                    cloudflareRequest(
                        "fixture",
                        HttpRequest("PUT", "/accounts/a/r2/buckets/b/cors", headers = mapOf("cf-r2-jurisdiction" to invalid)),
                    )
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, invalid)
        }
        for (name in listOf("x-custom", "host", "authorization")) {
            val failure = assertFailsWith<CloudFailure>(name) {
                cloudflareRequest("fixture",
                    HttpRequest("PATCH", "/accounts/a/r2/buckets/b", headers = mapOf(name to "1")))
            }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, name)
        }
    }

    @Test
    fun theDataCatalogCheckHeaderIsOnlyAdmittedAsFixedTrueOnACreateJobPost() {
        val accepted =
            cloudflareRequest(
                "fixture",
                HttpRequest(
                    "POST",
                    "/accounts/a/r2/buckets/b/jobs",
                    HttpBody.Text("""{"jobType":"prefixDelete","prefix":""}""", "application/json; charset=utf-8"),
                    mapOf("cf-r2-jurisdiction" to "default", "cf-r2-data-catalog-check" to "true"),
                ),
            )
        assertEquals("true", accepted.header("cf-r2-data-catalog-check"))
        assertEquals("default", accepted.header("cf-r2-jurisdiction"))

        for (value in listOf("false", "True", "TRUE", "1", "", " true", "true ")) {
            val failure = assertFailsWith<CloudFailure>(value) {
                cloudflareRequest(
                    "fixture",
                    HttpRequest(
                        "POST",
                        "/accounts/a/r2/buckets/b/jobs",
                        HttpBody.Text("""{"jobType":"prefixDelete","prefix":""}""", "application/json; charset=utf-8"),
                        mapOf("cf-r2-data-catalog-check" to value),
                    ),
                )
            }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, value)
        }
        // The protection header belongs only to the reviewed create-job POST route.
        assertFailsWith<CloudFailure> {
            cloudflareRequest(
                "fixture",
                HttpRequest("GET", "/accounts/a/r2/buckets/b/jobs", headers = mapOf("cf-r2-data-catalog-check" to "true")),
            )
        }
        assertFailsWith<CloudFailure> {
            cloudflareRequest(
                "fixture",
                HttpRequest(
                    "POST",
                    "/accounts/a/r2/buckets/b/cors",
                    HttpBody.Text("""{}""", "application/json; charset=utf-8"),
                    mapOf("cf-r2-data-catalog-check" to "true"),
                ),
            )
        }
        assertFailsWith<CloudFailure> {
            cloudflareRequest(
                "fixture",
                HttpRequest(
                    "POST",
                    "/accounts/a/r2/buckets/b/jobs",
                    HttpBody.Text("""{}""", "application/json; charset=utf-8"),
                    mapOf("cf-r2-data-catalog-check" to "true", "cf-r2-data-catalog-check " to "true"),
                ),
            )
        }
        // The route is an exact structure, not a substring match.
        for (wrongRoute in listOf(
            "/accounts/a/r2/buckets/b/other/jobs",
            "/accounts/a/r2/buckets/b/jobs/extra",
            "/accounts/a/r2/buckets/b//jobs",
            "/accounts/a/r2/buckets/b/../jobs",
            "/x/accounts/a/r2/buckets/b/jobs",
        )) {
            assertFailsWith<CloudFailure>(wrongRoute) {
                cloudflareRequest(
                    "fixture",
                    HttpRequest(
                        "POST",
                        wrongRoute,
                        HttpBody.Text("""{}""", "application/json; charset=utf-8"),
                        mapOf("cf-r2-data-catalog-check" to "true"),
                    ),
                )
            }
        }
        // A case-variant duplicate of the same header is refused, not silently doubled.
        assertFailsWith<CloudFailure> {
            cloudflareRequest(
                "fixture",
                HttpRequest(
                    "POST",
                    "/accounts/a/r2/buckets/b/jobs",
                    HttpBody.Text("""{}""", "application/json; charset=utf-8"),
                    linkedMapOf(
                        "cf-r2-data-catalog-check" to "true",
                        "CF-R2-Data-Catalog-Check" to "true",
                    ),
                ),
            )
        }
    }

    @Test
    fun aSensitiveJsonBodyIsSentExactlyOnceAndNeverEchoedByToString(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val secretBody =
                HttpBody.SensitiveJson(
                    """{"secret":"SYNTHETIC-PLATFORM-VALUE"}""",
                    "application/json; charset=utf-8",
                )
            val request =
                HttpRequest(
                    "PUT",
                    "/accounts/a/r2/buckets/b/sippy",
                    secretBody,
                    mapOf("cf-r2-jurisdiction" to "default"),
                )
            assertFalse(request.toString().contains("SYNTHETIC-PLATFORM-VALUE"))
            assertFalse(secretBody.toString().contains("SYNTHETIC-PLATFORM-VALUE"))
            val response = testTransport(server).execute(request)
            assertEquals(200, response.status)
            val recorded = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
            assertEquals("""{"secret":"SYNTHETIC-PLATFORM-VALUE"}""", recorded.body.readUtf8())
            // The one-shot body cannot be turned into a second request.
            val failure = assertFailsWith<CloudFailure> { testTransport(server).execute(request) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
            assertFalse(response.toString().contains("SYNTHETIC-PLATFORM-VALUE"))
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
