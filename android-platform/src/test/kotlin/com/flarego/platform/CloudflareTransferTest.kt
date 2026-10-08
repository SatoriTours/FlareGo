package com.flarego.platform

import kotlin.test.*
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

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
