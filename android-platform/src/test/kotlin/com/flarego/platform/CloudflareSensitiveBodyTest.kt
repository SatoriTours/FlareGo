package com.flarego.platform

import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.buffer
import okio.Sink
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Task 5a fix2: the platform-owned sensitive request buffer is released on the write path, on a
 * call that fails before writing, and on cancellation; the body is one-shot. Only a deterministic
 * "cleared" observation is used — no reflection, no secret printing, no GC timing.
 */
class CloudflareSensitiveBodyTest {
    private val path = "/accounts/a/r2/buckets/b/sippy"
    private val contentType = "application/json; charset=utf-8"

    private fun throwingSink(): okio.BufferedSink =
        object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                throw IOException("synthetic write failure")
            }

            override fun flush() = Unit

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

    @Test
    fun aSensitiveBodyWriteFailureStillClearsItsBufferAndItIsOneShot() {
        // Larger than one Okio segment so the buffered sink forwards to the throwing sink.
        val body = HttpBody.SensitiveJson("""{"secret":"SYNTHETIC-FIX2","pad":"${"x".repeat(16_384)}"}""", contentType)
        val request = cloudflareRequest("fixture", HttpRequest("PUT", path, body))
        val native = request.body as SensitiveBytesBody
        assertTrue(native.isOneShot(), "a sensitive one-shot body must advertise isOneShot")
        assertFailsWith<IOException> { native.writeTo(throwingSink()) }
        assertTrue(native.isSensitiveBufferCleared, "a failing write still clears the sensitive buffer")
    }

    @Test
    fun aCallThatFailsBeforeWritingStillClearsTheSensitiveBuffer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            val captured = CopyOnWriteArrayList<SensitiveBytesBody>()
            val transport =
                CloudflareTransport("fixture", cloudflareHttpClient(cloudflareRewrite(server))) { captured += it }
            val body = HttpBody.SensitiveJson("""{"secret":"SYNTHETIC-FIX2"}""", contentType)
            val result = runCatching { transport.execute(HttpRequest("PUT", path, body)) }
            assertTrue(result.isFailure, "the disconnected call must fail")
            assertEquals(1, captured.size)
            assertTrue(
                captured.single().isSensitiveBufferCleared,
                "a call that failed before writing still releases the transport-owned buffer",
            )
        }
    }

    @Test
    fun aCancelledCallStillClearsTheSensitiveBuffer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val captured = CopyOnWriteArrayList<SensitiveBytesBody>()
            val transport =
                CloudflareTransport("fixture", cloudflareHttpClient(cloudflareRewrite(server))) { captured += it }
            val body = HttpBody.SensitiveJson("""{"secret":"SYNTHETIC-FIX2"}""", contentType)
            val job = launch(Dispatchers.Default) { runCatching { transport.execute(HttpRequest("PUT", path, body)) } }
            withTimeout(5_000) { while (captured.isEmpty()) delay(10) }
            job.cancel()
            // Wait for the transport-owned cleanup before asserting.
            withTimeout(5_000) {
                while (!captured.single().isSensitiveBufferCleared) delay(10)
            }
            assertTrue(captured.single().isSensitiveBufferCleared)
        }
    }

    @Test
    fun ordinaryTextAndBytesBodiesKeepTheirExistingLifecycle() {
        val text = HttpBody.Text("{}", contentType)
        assertEquals("{}", text.value)
        val bytes = HttpBody.Bytes(byteArrayOf(1, 2), contentType)
        assertEquals(2, bytes.value.size)
        // A normal body keeps its existing one-shot lifecycle (never weaker than the sensitive one).
        val request = cloudflareRequest("fixture", HttpRequest("PUT", path, text))
        assertTrue(request.body!!.isOneShot())
    }
}
