package com.flarego.platform

import kotlin.test.*
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.cloudflare.MultipartPart


/** A source whose close fails, to prove cleanup independence. */
private class ThrowingCloseSource(private val failure: Throwable) : ByteSource {
    val closed = AtomicInteger()

    override fun read(buffer: ByteArray): Int = -1

    override fun close() {
        closed.incrementAndGet()
        throw failure
    }
}

/** A sink that counts writes and closes, optionally failing its close. */
private class CountingCloseSink(private val closeFailure: Throwable? = null) : ByteSink {
    val writes = AtomicInteger()
    val closed = AtomicInteger()

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        writes.incrementAndGet()
    }

    override fun close() {
        closed.incrementAndGet()
        closeFailure?.let { throw it }
    }
}

/** A sink whose blocked write is released by close(), per the ownership contract. */
private class BlockingWriteSink : ByteSink {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    val closedLatch = CountDownLatch(1)
    val writes = AtomicInteger()
    @Volatile var closeThread: String? = null

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        writes.incrementAndGet()
        entered.countDown()
        released.await(10, TimeUnit.SECONDS)
    }

    override fun close() {
        closeThread = Thread.currentThread().name
        closedLatch.countDown()
        released.countDown()
    }
}

class CloudflareTransferTest {

    private val fiveMib = 5 * 1024 * 1024

    @Test
    fun downloadAboveTheMemoryBoundStreamsIntoTheSink(): Unit = runBlocking {
        val payload = ByteArray(fiveMib) { (it % 251).toByte() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            server.start()
            val sink = RecordingSink()
            val head =
                testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
            assertEquals(200, head.status)
            assertEquals(fiveMib.toLong(), head.transferredBytes)
            assertEquals(fiveMib, sink.size())
            assertEquals(1, sink.closed.get())
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
            server.start()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransport(server).execute(HttpRequest("GET", "/accounts/a/logs"))
                }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
        }
    }

    @Test
    fun downloadWritesTheExactBytesIntoTheSink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(byteArrayOf(0, -1, -128, 42))))
            server.start()
            val sink = RecordingSink()
            val head = testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
            assertEquals(200, head.status)
            assertEquals(4L, head.transferredBytes)
            assertContentEquals(byteArrayOf(0, -1, -128, 42), sink.bytes())
            assertEquals(1, sink.closed.get())
        }
    }

    @Test
    fun errorResponseIsNotWrittenAndKeepsStatus(): Unit = runBlocking {
        for (status in listOf(404, 500)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"success":false}"""))
                server.start()
                val sink = RecordingSink()
                val head = testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
                assertEquals(status, head.status)
                assertEquals(0L, head.transferredBytes)
                assertEquals(0, sink.size())
                assertEquals(0, sink.writes.get())
                assertEquals(1, sink.closed.get())
            }
        }
    }

    @Test
    fun emptySuccessBodyTransfersZeroBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            val sink = RecordingSink()
            val head = testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
            assertEquals(204, head.status)
            assertEquals(0L, head.transferredBytes)
            assertEquals(0, sink.size())
            assertEquals(1, sink.closed.get())
        }
    }

    @Test
    fun unknownLengthResponseCountsActualBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("12345678", 4))
            server.start()
            val sink = RecordingSink()
            val head = testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
            assertEquals(200, head.status)
            assertEquals(8L, head.transferredBytes)
            assertEquals(8, sink.size())
        }
    }

    @Test
    fun redirectResponseIsNotFollowedAndNotWritten(): Unit = runBlocking {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                second.start()
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/steal")))
                first.start()
                val sink = RecordingSink()
                val head = testTransfers(first).download(HttpRequest("GET", "/accounts/a/logs"), sink)
                assertEquals(302, head.status)
                assertEquals(0L, head.transferredBytes)
                assertEquals(0, sink.size())
                assertEquals(1, sink.closed.get())
                assertEquals(1, first.requestCount)
                assertEquals(0, second.requestCount)
            }
        }
    }

    @Test
    fun connectionFailureClosesTheSink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            val sink = RecordingSink()
            assertFailsWith<IOException> {
                testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
            }
            assertEquals(0, sink.writes.get())
            assertEquals(1, sink.closed.get())
        }
    }

    @Test
    fun stalledTransferTimesOutAndClosesTheSink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val client =
                cloudflareClientBuilder()
                    .addInterceptor { chain -> chain.proceed(cloudflareRewrite(server)(chain.request())) }
                    .callTimeout(300, TimeUnit.MILLISECONDS)
                    .build()
            val sink = RecordingSink()
            assertFailsWith<IOException> {
                CloudflareTransfers("fixture", client)
                    .download(HttpRequest("GET", "/accounts/a/logs"), sink)
            }
            assertEquals(1, sink.closed.get())
        }
    }

    @Test
    fun cancellationStopsWritingAndClosesTheSink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(128 * 1024))))
            server.start()
            val firstWrite = CountDownLatch(1)
            val release = CountDownLatch(1)
            val sink =
                RecordingSink(
                    onFirstWrite = {
                        firstWrite.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                )
            val job =
                launch(Dispatchers.IO) {
                    testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
                }
            assertTrue(firstWrite.await(10, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(1, sink.writes.get())
            assertEquals(1, sink.closed.get())
        }
    }

    @Test
    fun transferIoRunsOffTheCallingThread(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            server.start()
            val caller = Thread.currentThread().name
            val sink = RecordingSink()
            testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
            assertNotNull(sink.writeThread)
            assertFalse(sink.writeThread == caller)
        }
    }


    @Test
    fun sourceCloseFailureStillClosesTheSink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false}"""))
            server.start()
            val source = ThrowingCloseSource(IOException("synthetic source close"))
            val sink = CountingCloseSink()
            assertFailsWith<IOException> {
                testTransfers(server)
                    .download(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, 0L, "application/octet-stream")), sink)
            }
            assertEquals(1, source.closed.get())
            assertEquals(1, sink.closed.get(), "a failing source close must not skip the sink close")
            assertEquals(0, sink.writes.get())
        }
    }

    @Test
    fun everyOwnedResourceIsClosedWhenOneCloseFails(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            server.start()
            val failing = ThrowingCloseSource(IOException("synthetic first source close"))
            val healthy = RecordingSource(emptyList())
            val sink = CountingCloseSink()
            val payload =
                HttpBody.Multipart(
                    listOf(
                        MultipartPart("a", null, "application/octet-stream", HttpBody.Stream(failing, 0L, "application/octet-stream")),
                        MultipartPart("b", null, "application/octet-stream", HttpBody.Stream(healthy, 0L, "application/octet-stream")),
                    )
                )
            try {
                testTransfers(server).download(HttpRequest("POST", "/accounts/a/x", payload), sink)
            } catch (_: IOException) {}
            assertEquals(1, failing.closed.get())
            assertEquals(1, healthy.closed.get(), "the second source must still be closed")
            assertEquals(1, sink.closed.get(), "the sink must still be closed")
        }
    }

    @Test
    fun alreadyCancelledEntryClosesSourceAndSinkWithoutRequest(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = RecordingSource(listOf(byteArrayOf(1)))
            val sink = CountingCloseSink()
            val entered = AtomicInteger()
            val cancelled = Job().apply { cancel() }
            val op =
                launch(cancelled, start = CoroutineStart.UNDISPATCHED) {
                    entered.incrementAndGet()
                    try {
                        testTransfers(server)
                            .download(HttpRequest("POST", "/accounts/a/x", HttpBody.Stream(source, 1L, "application/octet-stream")), sink)
                    } catch (_: Exception) {}
                }
            op.join()
            assertEquals(1, entered.get())
            assertEquals(1, source.closed.get(), "an already cancelled entry must still close owned sources")
            assertEquals(1, sink.closed.get(), "an already cancelled entry must still close the sink")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cancellingTheCallReleasesABlockedSink(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(128 * 1024))))
            server.start()
            val sink = BlockingWriteSink()
            val job =
                launch(Dispatchers.IO) {
                    try {
                        testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
                    } catch (_: Exception) {}
                }
            assertTrue(sink.entered.await(5, TimeUnit.SECONDS))
            val caller = Thread.currentThread().name
            job.cancel()
            var prompt = false
            var closeThread: String? = null
            try {
                prompt = sink.closedLatch.await(2, TimeUnit.SECONDS)
                closeThread = sink.closeThread
            } finally {
                sink.close()
                withTimeout(10_000) { job.join() }
            }
            assertTrue(prompt, "a blocked sink write must be released by close on cancellation")
            assertNotNull(closeThread)
            assertFalse(closeThread == caller, "cleanup must not run on the caller thread")
            assertEquals(1, sink.writes.get(), "no further write may happen after cancellation")
        }
    }

    @Test
    fun sinkCloseFailureDoesNotMaskThePrimaryIoFailure(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            val closeFailure = IllegalStateException("synthetic sink close")
            val sink = CountingCloseSink(closeFailure)
            val thrown =
                assertFailsWith<IOException> {
                    testTransfers(server).download(HttpRequest("GET", "/accounts/a/logs"), sink)
                }
            assertEquals(1, sink.closed.get(), "the sink close must still be attempted")
            assertTrue(
                thrown.suppressed.any { it === closeFailure },
                "the cleanup failure must be attached to the primary failure, not replace it",
            )
        }
    }


    @Test
    fun sendGateMediaTypeRejectionClosesOwnedResources(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = RecordingSource(emptyList())
            val sink = CountingCloseSink()
            // The core parser accepts this RFC quoted value, but the fixed transport
            // parser cannot, so ownership has already been transferred when the send
            // gate rejects it.
            val payload = HttpBody.Stream(source, 0L, "text/plain; note=\"a\\\"b\"")
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransfers(server).download(HttpRequest("POST", "/accounts/a/x", payload), sink)
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertEquals(1, source.closed.get(), "a send-gate rejection must still close the owned source")
            assertEquals(1, sink.closed.get(), "a send-gate rejection must still close the sink")
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun constructorRejectionHappensBeforeOwnershipTransfer(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val source = RecordingSource(emptyList())
            val sink = CountingCloseSink()
            assertFailsWith<CloudFailure> { HttpBody.Stream(source, 0L, " application/json") }
            // Nothing was handed to the transport, so nothing is closed on its behalf.
            assertEquals(0, source.closed.get())
            assertEquals(0, sink.closed.get())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun transferRejectsUnsafeRequestWithoutSendingIt(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val transfers = testTransfers(server)
            val unsafePathSink = RecordingSink()
            val pathFailure =
                assertFailsWith<CloudFailure> {
                    transfers.download(HttpRequest("GET", "/accounts/../secrets"), unsafePathSink)
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, pathFailure.code)
            assertEquals(0, unsafePathSink.size())
            assertEquals(1, unsafePathSink.closed.get())

            val headerSink = RecordingSink()
            val headerFailure =
                assertFailsWith<CloudFailure> {
                    transfers.download(
                        HttpRequest("GET", "/accounts/a/logs", HttpBody.Empty, mapOf("Host" to "evil.example")),
                        headerSink,
                    )
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, headerFailure.code)
            assertEquals(1, headerSink.closed.get())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun downloadRejectsGetWithABusinessPayload(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val sink = RecordingSink()
            val failure =
                assertFailsWith<CloudFailure> {
                    testTransfers(server)
                        .download(
                            HttpRequest("GET", "/accounts/a/logs", HttpBody.Text("{}", "application/json")),
                            sink,
                        )
                }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertEquals(1, sink.closed.get())
            assertEquals(0, server.requestCount)
        }
    }
}
