package com.flarego.platform

import kotlin.test.*
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.MultipartPart
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import com.flarego.core.cloudflare.encodePath


/** A source whose blocked read is released by close(), per the ownership contract. */
internal class BlockingUploadSource : com.flarego.core.ports.ByteSource {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    val closedLatch = CountDownLatch(1)
    val reads = AtomicInteger()
    @Volatile var closeThread: String? = null

    override fun read(buffer: ByteArray): Int {
        reads.incrementAndGet()
        entered.countDown()
        released.await(10, TimeUnit.SECONDS)
        return -1
    }

    override fun close() {
        closeThread = Thread.currentThread().name
        closedLatch.countDown()
        released.countDown()
    }
}

/** A source that never makes progress until it is closed or the test releases it. */
internal class EndlessUploadSource : com.flarego.core.ports.ByteSource {
    val entered = CountDownLatch(1)
    val reads = AtomicInteger()
    private val closed = AtomicInteger()
    @Volatile private var releasedByTest = false

    override fun read(buffer: ByteArray): Int {
        reads.incrementAndGet()
        entered.countDown()
        return if (closed.get() > 0 || releasedByTest) -1 else 0
    }

    override fun close() {
        closed.incrementAndGet()
    }

    /** Leak prevention for a failing assertion: not a close, so it cannot mask a missing close. */
    fun releaseFromTest() {
        releasedByTest = true
    }

    fun closeCount(): Int = closed.get()
}

class CloudflareProtocolTest {

    private fun jsonBody() = HttpBody.Text("""{"a":1}""", "application/json; charset=utf-8")

    @Test
    fun binaryResponseIsReturnedByteForByte(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0, -1, -128))))
            server.start()
            val response = testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
            assertEquals(200, response.status)
            assertContentEquals(byteArrayOf(0, -1, -128), response.bytes)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun errorStatusIsPreservedWithoutThrowing(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false}"""))
            server.start()
            val response = testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
            assertEquals(500, response.status)
            assertEquals("""{"success":false}""", response.body)
        }
    }

    @Test
    fun responseAtExactlyFourMibIsAccepted(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(FOUR_MIB))))
            server.start()
            val response = testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
            assertEquals(200, response.status)
            assertEquals(FOUR_MIB, response.bytes.size)
        }
    }

    @Test
    fun responseAboveFourMibIsRejected(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(FOUR_MIB + 1))))
            server.start()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
                }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
            assertTrue(failure.publicMessage.isNotBlank())
            assertFalse(failure.uncertain)
        }
    }

    @Test
    fun emptyResponseBodyIsAccepted(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val response = testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
            assertEquals(200, response.status)
            assertEquals(0, response.bytes.size)
        }
    }

    @Test
    fun unknownContentLengthResponseIsStillBounded(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(ByteArray(FOUR_MIB)), 8192)
            )
            server.start()
            val accepted = testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
            assertEquals(FOUR_MIB, accepted.bytes.size)
        }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(ByteArray(FOUR_MIB + 1)), 8192)
            )
            server.start()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
                }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
        }
    }

    @Test
    fun unsafePathsAreRejectedBeforeAnyRequest(): Unit = runBlocking {
        val unsafe =
            listOf(
                "//evil.example/client/v4/accounts",
                "https://evil.example/steal",
                "accounts/a",
                "/accounts/../zones",
                "/accounts/%2e%2e/%2e%2e/zones",
                "/accounts/%252e%252e/zones",
                "/accounts\\..\\zones",
                "/accounts/a\r\nX-Evil: 1",
                "/accounts/a#frag",
                "/accounts/a%zz",
                "/accounts//a",
            )
        MockWebServer().use { server ->
            server.start()
            val transport = testTransport(server)
            for (path in unsafe) {
                val failure =
                    assertFailsWith<CloudFailure>(path) {
                        transport.execute(HttpRequest("GET", path))
                    }
                assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
                assertFalse(failure.publicMessage.contains(path), path)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun encodedQueryCursorStaysLegal(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val path = "/accounts/a/r2/buckets?per_page=50&cursor=next%2B%2F%3D"
            val response = testTransport(server).execute(HttpRequest("GET", path))
            assertEquals(200, response.status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertTrue(recorded.path!!.endsWith("cursor=next%2B%2F%3D"))
        }
    }

    @Test
    fun unsafeBusinessHeadersAreRejectedBeforeAnyRequest() {
        val unsafe =
            listOf(
                "Authorization" to "Bearer injected",
                "Host" to "evil.example",
                "Proxy-Authorization" to "Basic injected",
                "Connection" to "close",
                "Transfer-Encoding" to "chunked",
                "Content-Length" to "0",
                "X-Unknown" to "v",
                "If-Match" to "a\r\nX-Evil: 1",
                "Accept" to "text/plain",
            )
        for ((name, value) in unsafe) {
            val request = HttpRequest("GET", "/accounts/a", HttpBody.Empty, mapOf(name to value))
            val failure =
                assertFailsWith<CloudFailure>(name) { cloudflareRequest("fixture", request) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, name)
        }
    }

    @Test
    fun conditionalAndRangeHeadersAreForwarded(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val headers =
                mapOf(
                    "If-Match" to "\"etag-1\"",
                    "If-None-Match" to "\"etag-2\"",
                    "If-Modified-Since" to "Wed, 21 Oct 2015 07:28:00 GMT",
                    "If-Unmodified-Since" to "Wed, 21 Oct 2015 07:28:00 GMT",
                    "Range" to "bytes=0-99",
                )
            testTransport(server).execute(HttpRequest("GET", "/accounts/a", HttpBody.Empty, headers))
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            for ((name, value) in headers) {
                assertEquals(value, recorded.getHeader(name), name)
            }
            assertNotNull(recorded.getHeader("Accept"))
            assertNotNull(recorded.getHeader("Authorization"))
        }
    }

    @Test
    fun contentTypeHeaderCannotConflictWithATypedBody() {
        val matching =
            HttpRequest("POST", "/accounts/a/x", jsonBody(), mapOf("Content-Type" to "application/json; charset=utf-8"))
        assertEquals("application/json; charset=utf-8", cloudflareRequest("fixture", matching).body?.contentType()?.toString())

        val conflicting =
            HttpRequest("POST", "/accounts/a/x", jsonBody(), mapOf("Content-Type" to "text/plain"))
        val failure =
            assertFailsWith<CloudFailure> { cloudflareRequest("fixture", conflicting) }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)

        val multipart =
            HttpRequest(
                "POST",
                "/accounts/a/x",
                HttpBody.Multipart(
                    listOf(MultipartPart("field", null, "text/plain", HttpBody.Text("v", "text/plain")))
                ),
                mapOf("Content-Type" to "multipart/form-data"),
            )
        val multipartFailure =
            assertFailsWith<CloudFailure> { cloudflareRequest("fixture", multipart) }
        assertEquals(CloudFailureCode.INVALID_INPUT, multipartFailure.code)
    }

    @Test
    fun multipartPartsCarryNameFilenameAndContentType(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val payload =
                HttpBody.Multipart(
                    listOf(
                        MultipartPart("field", null, "text/plain", HttpBody.Text("value", "text/plain")),
                        MultipartPart(
                            "file",
                            "a.bin",
                            "application/octet-stream",
                            HttpBody.Bytes(byteArrayOf(1, 2, 3), "application/octet-stream"),
                        ),
                    )
                )
            val response = testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", payload))
            assertEquals(200, response.status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            val raw = recorded.body.readByteArray()
            val text = String(raw, Charsets.ISO_8859_1)
            val lower = text.lowercase()
            assertTrue(text.contains("name=\"field\""))
            assertTrue(text.contains("name=\"file\""))
            assertTrue(text.contains("filename=\"a.bin\""))
            assertTrue(lower.contains("content-type: text/plain"))
            assertTrue(lower.contains("content-type: application/octet-stream"))
            assertTrue(text.contains("value"))
            assertTrue(text.contains(String(byteArrayOf(1, 2, 3), Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun multipartRejectsEmptyNestedAndUnsafeMetadata(): Unit = runBlocking {
        val text = HttpBody.Text("v", "text/plain")
        assertInvalidInput("empty part") {
            MultipartPart("field", null, "text/plain", HttpBody.Empty)
        }
        assertInvalidInput("nested multipart") {
            MultipartPart(
                "field",
                null,
                "text/plain",
                HttpBody.Multipart(listOf(MultipartPart("inner", null, "text/plain", text))),
            )
        }
        assertInvalidInput("blank part name") { MultipartPart(" ", null, "text/plain", text) }
        assertInvalidInput("crlf part name") { MultipartPart("a\r\nX: 1", null, "text/plain", text) }
        assertInvalidInput("crlf filename") {
            MultipartPart("field", "a\r\nX: 1", "text/plain", text)
        }
        assertInvalidInput("quoted filename") { MultipartPart("field", "a\"b", "text/plain", text) }
        assertInvalidInput("crlf content type") {
            MultipartPart("field", null, "text/plain\r\nX: 1", text)
        }
        assertInvalidInput("blank content type") { MultipartPart("field", null, " ", text) }
        assertInvalidInput("empty multipart") { HttpBody.Multipart(emptyList()) }
    }

    @Test
    fun streamBodyWithKnownLengthIsSentExactly(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val source = RecordingSource(listOf(byteArrayOf(1, 2, 3, 4, 5)))
            val response =
                testTransport(server)
                    .execute(
                        HttpRequest(
                            "POST",
                            "/accounts/a/x",
                            HttpBody.Stream(source, 5L, "application/octet-stream"),
                        )
                    )
            assertEquals(200, response.status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals(5, recorded.bodySize)
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), recorded.body.readByteArray())
            assertEquals(1, source.closed.get())
        }
    }

    @Test
    fun streamBodyWithUnknownLengthIsSentWithoutPadding(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val source = RecordingSource(listOf(byteArrayOf(7, 8, 9)))
            testTransport(server)
                .execute(
                    HttpRequest(
                        "POST",
                        "/accounts/a/x",
                        HttpBody.Stream(source, null, "application/octet-stream"),
                    )
                )
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals(3, recorded.bodySize)
            assertContentEquals(byteArrayOf(7, 8, 9), recorded.body.readByteArray())
        }
    }

    @Test
    fun streamBodyWithMismatchedKnownLengthFails(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val source = RecordingSource(listOf(byteArrayOf(1, 2, 3)))
            assertFailsWith<IOException> {
                testTransport(server)
                    .execute(
                        HttpRequest(
                            "POST",
                            "/accounts/a/x",
                            HttpBody.Stream(source, 6L, "application/octet-stream"),
                        )
                    )
            }
            assertEquals(1, source.closed.get())
        }
    }

    @Test
    fun negativeStreamLengthIsRejected(): Unit = runBlocking {
        val source = RecordingSource(emptyList())
        assertInvalidInput("negative length") {
            HttpBody.Stream(source, -1L, "application/octet-stream")
        }
    }

    @Test
    fun payloadSourceIsClosedOnSuccessFailureAndRejection(): Unit = runBlocking {
        val onSuccess = RecordingSource(listOf(byteArrayOf(1)))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            testTransport(server)
                .execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(onSuccess, 1L, "application/octet-stream")))
            assertEquals(1, onSuccess.closed.get())
        }
        val onFailure = RecordingSource(listOf(byteArrayOf(1)))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            assertFailsWith<IOException> {
                testTransport(server)
                    .execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(onFailure, 1L, "application/octet-stream")))
            }
            assertEquals(1, onFailure.closed.get())
        }
        val onRejection = RecordingSource(listOf(byteArrayOf(1)))
        MockWebServer().use { server ->
            server.start()
            val rejection =
                HttpRequest(
                    "POST",
                    "/accounts/a/x",
                    HttpBody.Stream(onRejection, 1L, "application/octet-stream"),
                    mapOf("Authorization" to "Bearer injected"),
                )
            assertInvalidInput("rejected header") { testTransport(server).execute(rejection) }
            assertEquals(1, onRejection.closed.get())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun sharedSourceIsClosedOnlyOnce(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            // One source instance referenced by two parts: it is closed once, and a
            // zero-length declared part stays consistent with the empty source.
            val shared = RecordingSource(emptyList())
            val payload =
                HttpBody.Multipart(
                    listOf(
                        MultipartPart("a", null, "application/octet-stream", HttpBody.Stream(shared, 0L, "application/octet-stream")),
                        MultipartPart("b", null, "application/octet-stream", HttpBody.Stream(shared, 0L, "application/octet-stream")),
                    )
                )
            testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", payload))
            assertEquals(1, shared.closed.get())
        }
    }

    @Test
    fun redirectToAnotherHostIsNotFollowed(): Unit = runBlocking {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                second.start()
                first.enqueue(
                    MockResponse().setResponseCode(302).setHeader("Location", second.url("/steal"))
                )
                first.start()
                val response = testTransport(first).execute(HttpRequest("GET", "/accounts/a"))
                assertEquals(302, response.status)
                assertEquals(1, first.requestCount)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test
    fun writeIsNotReplayedWhenTheServerAsksForImmediateRetry(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val response =
                testTransport(server).execute(HttpRequest("POST", "/zones/zone/dns_records", jsonBody()))
            assertEquals(503, response.status)
            // the provider's own Retry-After value is preserved, not rewritten
            assertEquals(listOf("0"), response.headers["Retry-After"])
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun disconnectedWriteIsNotReplayed(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            assertFailsWith<IOException> {
                testTransport(server).execute(HttpRequest("POST", "/zones/zone/dns_records", jsonBody()))
            }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun responseHeadersKeepMultiValueAndCase(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "a=1")
                    .addHeader("Set-Cookie", "b=2")
                    .addHeader("X-Synthetic", "v")
                    .setBody("{}")
            )
            server.start()
            val response = testTransport(server).execute(HttpRequest("GET", "/accounts/a"))
            assertEquals(listOf("a=1", "b=2"), response.headers["Set-Cookie"])
            assertEquals(listOf("v"), response.headers["X-Synthetic"])
            assertEquals(1, response.headers.keys.count { it.equals("X-Synthetic", ignoreCase = true) })
        }
    }

    @Test
    fun payloadIoRunsOffTheCallingThread(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val caller = Thread.currentThread().name
            val source = RecordingSource(listOf(byteArrayOf(1)))
            testTransport(server)
                .execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, 1L, "application/octet-stream")))
            assertNotNull(source.readThread)
            assertFalse(source.readThread == caller)
            assertNotNull(source.closeThread)
            assertFalse(source.closeThread == caller)
        }
    }

    @Test
    fun cancellingTheCallCancelsTheRequestAndStopsIo(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val calls = CopyOnWriteArrayList<Call>()
            val client =
                cloudflareClientBuilder()
                    .addInterceptor { chain -> chain.proceed(cloudflareRewrite(server)(chain.request())) }
                    .eventListener(
                        object : EventListener() {
                            override fun callStart(call: Call) {
                                calls.add(call)
                            }
                        }
                    )
                    .build()
            val source = RecordingSource(listOf(byteArrayOf(1, 2, 3)))
            val transport = CloudflareTransport("fixture", client)
            val job =
                launch(Dispatchers.IO) {
                    transport.execute(
                        HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, 3L, "application/octet-stream"))
                    )
                }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
            assertEquals(1, source.closed.get())
            assertEquals(2, source.reads.get())
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.all { it.isCanceled() })
        }
    }


    @Test
    fun escapedBackslashAndControlPathsAreRejected() = runBlocking {
        val unsafe =
            listOf(
                "/accounts/a%5c..%5c..%5c",
                "/accounts/a%0d%0a",
                "/accounts/a%00",
                "/accounts/a%5c",
            )
        MockWebServer().use { server ->
            server.start()
            val transport = testTransport(server)
            for (path in unsafe) {
                val failure = assertFailsWith<CloudFailure>(path) { transport.execute(HttpRequest("GET", path)) }
                assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
                assertFalse(failure.publicMessage.contains(path), path)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun deeplyEncodedTraversalIsRejectedAtEveryDecodedStage() = runBlocking {
        var segment = "%2e%2e"
        repeat(5) { segment = segment.replace("%", "%25") }
        MockWebServer().use { server ->
            server.start()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransport(server).execute(HttpRequest("GET", "/accounts/$segment/zones"))
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun escapedQueryControlIsRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransport(server).execute(HttpRequest("GET", "/accounts/a?cursor=%0d%0a"))
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun opaqueIdentifierWithALiteralPercentIsPreserved() {
        // encodePath("id%GG") == "id%25GG": a legitimate opaque identifier, not a new escape.
        val request = cloudflareRequest("fixture", HttpRequest("GET", "/accounts/id%25GG"))
        assertEquals("/client/v4/accounts/id%25GG", request.url.encodedPath)
        val doubleEncoded = cloudflareRequest("fixture", HttpRequest("GET", "/accounts/a%25GG/records"))
        assertEquals("/client/v4/accounts/a%25GG/records", doubleEncoded.url.encodedPath)
    }

    @Test
    fun invalidMediaTypesAreRejectedWithoutEchoingTheValue() {
        val invalid =
            listOf(
                "invalid mime/type",
                "text",
                "text/",
                "/plain",
                "a b/c",
                "text/plain; charset",
                "text/plain; =utf-8",
                "text/plain; charset=ä",
                "text/plain; charset=\"unterminated",
            )
        for (value in invalid) {
            val failure =
                assertFailsWith<CloudFailure>(value) {
                    HttpBody.Text("x", value)
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, value)
            assertFalse(failure.publicMessage.contains(value), value)
        }
    }

    @Test
    fun invalidBusinessContentTypeIsRejected() {
        val failure =
            assertFailsWith<CloudFailure> {
                cloudflareRequest(
                    "fixture",
                    HttpRequest("POST", "/accounts/a/x", HttpBody.Empty, mapOf("Content-Type" to "invalid mime/type")),
                )
            }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
    }

    @Test
    fun validMediaTypesAreAcceptedThroughEverySendPath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val transport = testTransport(server)
            assertEquals(200, transport.execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Text("x", "application/json; charset=utf-8"))).status)
            assertEquals(200, transport.execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Bytes(byteArrayOf(1), "text/plain"))).status)
            assertEquals(
                200,
                transport.execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(RecordingSource(listOf(byteArrayOf(1))), 1L, "application/octet-stream"))).status,
            )
            assertEquals(
                200,
                transport.execute(
                    HttpRequest(
                        "POST",
                        "/accounts/a/x",
                        HttpBody.Multipart(listOf(MultipartPart("field", null, "text/plain; charset=\"utf-8\"", HttpBody.Text("v", "text/plain")))),
                    )
                ).status,
            )
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun cancellingTheCallReleasesABlockedUploadSource(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val source = BlockingUploadSource()
            val job =
                launch(Dispatchers.IO) {
                    try {
                        testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, null, "application/octet-stream")))
                    } catch (_: Exception) {}
                }
            assertTrue(source.entered.await(3, TimeUnit.SECONDS))
            val caller = Thread.currentThread().name
            job.cancel()
            var prompt = false
            var closeThread: String? = null
            try {
                prompt = source.closedLatch.await(2, TimeUnit.SECONDS)
                closeThread = source.closeThread
            } finally {
                source.close()
                withTimeout(10_000) { job.join() }
            }
            assertTrue(prompt, "a blocked upload read must be released by close on cancellation")
            assertNotNull(closeThread)
            assertFalse(closeThread == caller, "cleanup must not run on the caller thread")
            assertEquals(1, source.reads.get())
        }
    }

    @Test
    fun nonProgressUploadStopsWhenCancelled(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = EndlessUploadSource()
            val job =
                launch(Dispatchers.IO) {
                    try {
                        testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, null, "application/octet-stream")))
                    } catch (_: Exception) {}
                }
            assertTrue(source.entered.await(3, TimeUnit.SECONDS))
            job.cancel()
            try {
                withTimeout(5_000) { job.join() }
            } finally {
                source.releaseFromTest()
                withTimeout(5_000) { job.join() }
            }
            assertTrue(job.isCancelled)
            assertEquals(1, source.closeCount(), "the source must be closed exactly once on cancellation")
        }
    }

    @Test
    fun alreadyCancelledEntryClosesOwnedUploadSourceWithoutRequest(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = RecordingSource(listOf(byteArrayOf(1)))
            val entered = AtomicInteger()
            val cancelled = Job().apply { cancel() }
            val op =
                launch(cancelled, start = CoroutineStart.UNDISPATCHED) {
                    entered.incrementAndGet()
                    try {
                        testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, 1L, "application/octet-stream")))
                    } catch (_: Exception) {}
                }
            op.join()
            assertEquals(1, entered.get())
            assertEquals(1, source.closed.get(), "an already cancelled entry must still close owned sources")
            assertEquals(0, server.requestCount)
        }
    }


    @Test
    fun unicodeOpaqueIdentifiersAndQueryArePreserved(): Unit = runBlocking {
        val escaped = encodePath("é")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val response = testTransport(server).execute(HttpRequest("GET", "/accounts/$escaped"))
            assertEquals(200, response.status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("/client/v4/accounts/$escaped", recorded.requestUrl!!.encodedPath)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            testTransport(server).execute(HttpRequest("GET", "/accounts/a?name=$escaped"))
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("name=$escaped", recorded.requestUrl!!.encodedQuery)
            assertEquals("/client/v4/accounts/a", recorded.requestUrl!!.encodedPath)
        }
        // ASCII identity, literal percent and a real cursor stay preserved next to it
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            testTransport(server)
                .execute(HttpRequest("GET", "/accounts/plain-id%25GG?cursor=next%2B%2F%3D"))
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("/client/v4/accounts/plain-id%25GG", recorded.requestUrl!!.encodedPath)
            assertEquals("cursor=next%2B%2F%3D", recorded.requestUrl!!.encodedQuery)
        }
    }

    @Test
    fun unicodeDoesNotBypassPathStructureOrByteValidation() = runBlocking {
        val unsafe =
            listOf(
                "/accounts/${encodePath("é")}/../zones",
                "/accounts/${encodePath("é")}/%2e%2e/zones",
                "/accounts/%C3",
                "/accounts/%80",
                "/accounts/%ED%A0%80",
                "/accounts/%C0%AF",
            )
        MockWebServer().use { server ->
            server.start()
            val transport = testTransport(server)
            for (path in unsafe) {
                val failure = assertFailsWith<CloudFailure>(path) { transport.execute(HttpRequest("GET", path)) }
                assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
            }
            assertEquals(0, server.requestCount)
        }
    }

    private suspend fun assertMimeIsRejectedOrSent(kind: String, value: String, label: String) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            var rejected: CloudFailure? = null
            try {
                val payload =
                    when (kind) {
                        "text" -> HttpBody.Text("x", value)
                        "bytes" -> HttpBody.Bytes(byteArrayOf(1), value)
                        "stream" -> HttpBody.Stream(RecordingSource(emptyList()), 0L, value)
                        else ->
                            HttpBody.Multipart(
                                listOf(MultipartPart("field", null, value, HttpBody.Text("x", "text/plain")))
                            )
                    }
                testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", payload))
            } catch (thrown: CloudFailure) {
                rejected = thrown
            }
            if (rejected != null) {
                assertEquals(CloudFailureCode.INVALID_INPUT, rejected.code, label)
                assertEquals(0, server.requestCount, "$label: a rejected media type must not be sent")
            } else {
                val recorded = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(recorded, label)
                if (kind == "part") {
                    val body = String(recorded.body.readByteArray(), Charsets.ISO_8859_1).lowercase()
                    assertTrue(body.contains("content-type:"), "$label: the part content type must not be dropped")
                } else {
                    assertTrue(recorded.getHeader("Content-Type") != null, "$label: the body content type must not be dropped")
                }
            }
        }
    }

    @Test
    fun textMediaTypeIsNeverSilentlyDropped(): Unit = runBlocking {
        for (value in listOf(" application/json", "application/json ", "application/json; charset =utf-8")) {
            assertMimeIsRejectedOrSent("text", value, "text\"$value\"")
        }
    }

    @Test
    fun bytesMediaTypeIsNeverSilentlyDropped(): Unit = runBlocking {
        for (value in listOf(" application/json", "application/json ", "application/json; charset =utf-8")) {
            assertMimeIsRejectedOrSent("bytes", value, "bytes\"$value\"")
        }
    }

    @Test
    fun streamMediaTypeIsNeverSilentlyDropped(): Unit = runBlocking {
        for (value in listOf(" application/json", "application/json ", "application/json; charset =utf-8")) {
            assertMimeIsRejectedOrSent("stream", value, "stream\"$value\"")
        }
    }

    @Test
    fun partMediaTypeIsNeverSilentlyDropped(): Unit = runBlocking {
        for (value in listOf(" application/json", "application/json ", "application/json; charset =utf-8")) {
            assertMimeIsRejectedOrSent("part", value, "part\"$value\"")
        }
    }

    @Test
    fun quotedParameterMediaTypeIsAcceptedAndSent(): Unit = runBlocking {
        val value = "text/plain; note=\"a;b\""
        val request = cloudflareRequest("fixture", HttpRequest("POST", "/accounts/a/x", HttpBody.Text("x", value)))
        assertNotNull(request.body!!.contentType(), "a quoted semicolon must stay a valid media type")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val response = testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Text("x", value)))
            assertEquals(200, response.status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertTrue(recorded.getHeader("Content-Type") != null, "the quoted parameter media type must be sent")
        }
        // an empty quoted value is legal and must also be sent
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val empty = "text/plain; note=\"\""
            assertEquals(200, testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Text("x", empty))).status)
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertTrue(recorded.getHeader("Content-Type") != null)
        }
    }

    @Test
    fun quotedPairMediaTypeIsRejectedBeforeSending(): Unit = runBlocking {
        // The fixed transport parser (OkHttp 4.12) has no quoted-pair support, so a
        // value it cannot parse must fail closed before the request is sent instead of
        // being sent without its content type.
        val escapedPair = "text/plain; note=\"a\\\"b\""
        MockWebServer().use { server ->
            server.start()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransport(server).execute(HttpRequest("POST", "/accounts/a/x", HttpBody.Text("x", escapedPair)))
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun brokenQuotedMediaTypesAreRejected() {
        val invalid =
            listOf(
                "text/plain; note=\"a;b",
                "text/plain; note=\"a\"b\"",
                "text/plain; note=a b",
                "text/plain; note=",
            )
        for (value in invalid) {
            val failure = assertFailsWith<CloudFailure>(value) { HttpBody.Text("x", value) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, value)
            assertFalse(failure.publicMessage.contains(value), value)
        }
    }

    private suspend fun assertInvalidInput(label: String, block: suspend () -> Any) {
        val failure: CloudFailure =
            try {
                block()
                fail("$label: expected a CloudFailure")
            } catch (thrown: CloudFailure) {
                thrown
            }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, label)
        assertFalse(failure.uncertain, label)
    }
}
