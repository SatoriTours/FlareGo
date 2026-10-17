package com.flarego.platform

import com.flarego.core.model.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DnsDocumentFilesTest {
    private val bytes = "\$ORIGIN example.com.\r\n\$TTL 300\r\n@ IN TXT \"raw\" ; cf_tags=x\r\n".toByteArray()
    @Test fun fileStreamsPreserveBytesAndCloseHandlesOnce() {
        val file = File.createTempFile("flarego-dns-test-", ".bind")
        try {
            val outputCloses = AtomicInteger(); val inputCloses = AtomicInteger()
            val sink = DnsDocumentSink(object : FileOutputStream(file) {
                override fun close() { outputCloses.incrementAndGet(); super.close() }
            })
            sink.write(bytes, 0, 9); sink.write(bytes, 9, bytes.size - 9); sink.close(); sink.close()
            val source = DnsDocumentSource(object : FileInputStream(file) {
                override fun close() { inputCloses.incrementAndGet(); super.close() }
            })
            val read = ByteArrayOutputStream(); val buffer = ByteArray(7)
            while (true) { val n = source.read(buffer); if (n < 0) break; read.write(buffer, 0, n) }
            source.close(); source.close()
            assertContentEquals(bytes, read.toByteArray()); assertEquals(1, outputCloses.get()); assertEquals(1, inputCloses.get())
        } finally { file.delete() }
    }
    @Test fun diskFullScrubsPathAndClosesFailedOutputExactlyOnce() {
        val closes = AtomicInteger()
        val sink = DnsDocumentSink(object : OutputStream() {
            override fun write(b: Int) { throw IOException("ENOSPC content://private-token/source secret BIND") }
            override fun close() { closes.incrementAndGet() }
        })
        val failure = assertFailsWith<CloudFailure> { sink.write(bytes, 0, bytes.size) }
        sink.close(); sink.close()
        assertEquals(CloudFailureCode.UNAVAILABLE, failure.code); assertEquals(1, closes.get())
        assertFalse(failure.toString().contains("private-token")); assertNull(failure.cause)
    }
    @Test fun permissionFailureClosesSourceAndKeepsPrivateUriOutOfFailure() {
        val closes = AtomicInteger()
        val source = DnsDocumentSource(object : InputStream() {
            override fun read(): Int { throw SecurityException("content://private-token secret BIND") }
            override fun close() { closes.incrementAndGet() }
        })
        val failure = assertFailsWith<CloudFailure> { source.read(ByteArray(10)) }
        source.close(); source.close()
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED, failure.code); assertEquals(1, closes.get())
        assertFalse(failure.toString().contains("private-token")); assertNull(failure.cause)
    }
    @Test fun closeReleasesBlockedReadFromAnotherThread() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val source = DnsDocumentSource(object : InputStream() {
            override fun read(): Int { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); throw IOException("closed") }
            override fun close() { closes.incrementAndGet(); release.countDown() }
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<Any> { runCatching { source.read(ByteArray(8)) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); source.close(); read.get(2, TimeUnit.SECONDS)
            source.close(); assertEquals(1, closes.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun closeReleasesBlockedWriteFromAnotherThread() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val sink = DnsDocumentSink(object : OutputStream() {
            override fun write(b: Int) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); throw IOException("closed") }
            override fun close() { closes.incrementAndGet(); release.countDown() }
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val write = executor.submit<Any> { runCatching { sink.write(bytes, 0, bytes.size) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); sink.close(); write.get(2, TimeUnit.SECONDS)
            sink.close(); assertEquals(1, closes.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun closeFailuresAreTypedAndDoNotPreventOtherCleanupAttempts() {
        val closes = AtomicInteger()
        val source = DnsDocumentSource(object : ByteArrayInputStream(bytes) {
            override fun close() { closes.incrementAndGet(); throw IOException("private-path") }
        })
        val error = assertFailsWith<CloudFailure> { source.close() }; source.close()
        assertEquals(CloudFailureCode.UNAVAILABLE, error.code); assertEquals(1, closes.get())
        assertFalse(error.toString().contains("private-path"))
    }
}
