package com.flarego.platform

import com.flarego.core.model.CloudFailure
import com.flarego.core.ports.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

class KvEncryptedContentTest {
    @Test fun cancellationBeforeIoDispatchStillClosesTheHandedOffInput() = runBlocking {
        val dir=Files.createTempDirectory("kv-encrypted-test").toFile();var closed=false;var reads=0
        try {
            val store=KvEncryptedContentStore(dir)
            val child=launch(start=CoroutineStart.UNDISPATCHED){
                currentCoroutineContext().cancel()
                store.stage(object:ByteSource{
                    override fun read(buffer:ByteArray):Int{reads++;return -1}
                    override fun close(){closed=true}
                },1)
            }
            child.join();assertTrue(closed);assertEquals(0,reads);assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun writerCreationFailureStillClosesInputAndLifecycleAbortsUnsealedWriters() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        var closed = false
        try {
            val store = KvEncryptedContentStore(dir)
            val input = object : ByteSource {
                override fun read(buffer: ByteArray): Int = -1
                override fun close() { closed = true }
            }
            assertFailsWith<CloudFailure> { store.stage(input, -1) }
            assertTrue(closed)
            val writer = store.writer(10)
            writer.write(byteArrayOf(1, 2), 0, 2)
            store.releaseAll()
            assertFailsWith<CloudFailure> { writer.seal() }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    private fun source(bytes: ByteArray) = object : ByteSource {
        var offset = 0
        override fun read(buffer: ByteArray): Int {
            if (offset == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - offset)
            bytes.copyInto(buffer, 0, offset, offset + count); offset += count
            return count
        }
        override fun close() = Unit
    }
    private fun read(content: KvContent): ByteArray = runBlocking {
        val input = content.openVerified()
        try {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(7777)
            while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            output.toByteArray()
        } finally { input.close() }
    }

    @Test fun exactBinaryAndEmptyValuesAreStoredAsCiphertextAndRevoked() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        try {
            val store = KvEncryptedContentStore(dir)
            val bytes = ByteArray(3 * 65536 + 9) { (it % 251).toByte() }
            val frozen = store.stage(source(bytes), bytes.size.toLong())
            assertEquals(bytes.size.toLong(), frozen.descriptor.length)
            assertFalse(frozen.toString().contains(dir.path))
            assertContentEquals(bytes, read(frozen))
            assertContentEquals(bytes, read(frozen))
            val cipher = dir.listFiles()!!.single().readBytes()
            assertFalse((0..cipher.size - 64).any { start -> (0 until 64).all { cipher[start + it] == bytes[it] } })
            val empty = store.stage(source(byteArrayOf()), 1)
            assertContentEquals(byteArrayOf(), read(empty))
            val active = frozen.openVerified()
            frozen.close(); frozen.close()
            assertFailsWith<CloudFailure> { active.read(ByteArray(1)) }
            assertFailsWith<CloudFailure> { frozen.openVerified() }
            empty.close()
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptionInLastChunkCannotExposeEvenTheFirstPrefix() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        try {
            val store = KvEncryptedContentStore(dir)
            val content = store.stage(source(ByteArray(200000) { 31 }), 200000)
            val file = dir.listFiles()!!.single()
            java.io.RandomAccessFile(file, "rw").use { val position=it.length()-3;it.seek(position);val original=it.read();it.seek(position);it.write(original xor 1) }
            assertFailsWith<CloudFailure> { content.openVerified() }
            content.close()
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun truncatedOrAppendedOrReorderedCiphertextFailsWholeFileAuthentication() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        try {
            val store = KvEncryptedContentStore(dir)
            repeat(3) { kind ->
                val content = store.stage(source(ByteArray(180000) { (it % 173).toByte() }), 180000)
                val file = dir.listFiles()!!.single()
                java.io.RandomAccessFile(file, "rw").use {
                    when (kind) {
                        0 -> it.setLength(it.length() - 12)
                        1 -> { it.seek(it.length()); it.write(1) }
                        else -> { it.seek(100);val original=it.read();it.seek(100);it.write(original xor 1) }
                    }
                }
                assertFailsWith<CloudFailure> { content.openVerified() }
                content.close()
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedOrUnsealedTransferLeavesNoReadableContentAndNoPlaintext() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        try {
            val store = KvEncryptedContentStore(dir)
            assertFailsWith<CloudFailure> { store.stage(source(ByteArray(21)), 20) }
            assertTrue(dir.listFiles()!!.isEmpty())
            val writer = store.writer(100)
            writer.write(byteArrayOf(1, 2), 0, 2); writer.close(); writer.abort()
            assertFailsWith<CloudFailure> { writer.seal() }
            assertTrue(dir.listFiles()!!.isEmpty())
            val bad = object : ByteSource {
                override fun read(buffer: ByteArray): Int = throw java.io.IOException("private-marker")
                override fun close() = Unit
            }
            val error = assertFailsWith<CloudFailure> { store.stage(bad, 20) }
            assertFalse(error.toString().contains("private-marker"))
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun lifecycleReleaseClosesReadersAndStartupRemovesOnlyRecognizedOrphans() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        try {
            val unrelated = java.io.File(dir, "keep.txt").apply { writeText("keep") }
            val orphan = java.io.File(dir, "flarego-kv-orphan.enc").apply { writeBytes(byteArrayOf(1)) }
            val store = KvEncryptedContentStore(dir)
            assertFalse(orphan.exists()); assertTrue(unrelated.exists())
            val frozen = store.stage(source(byteArrayOf(9)), 10)
            val reader = frozen.openVerified()
            store.releaseAll()
            assertFailsWith<CloudFailure> { reader.read(ByteArray(1)) }
            assertFailsWith<CloudFailure> { frozen.openVerified() }
            assertEquals(listOf("keep.txt"), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }

    @Test fun largeStreamingStageNeverRequestsOrRetainsWholePlaintextAndClosesSource() = runBlocking {
        val dir = Files.createTempDirectory("kv-encrypted-test").toFile()
        var closed = false
        var remaining = 30L * 1024 * 1024
        val input = object : ByteSource {
            override fun read(buffer: ByteArray): Int {
                assertTrue(buffer.size <= 65536)
                if (remaining == 0L) return -1
                val n = minOf(remaining, buffer.size.toLong()).toInt()
                buffer.fill(71, 0, n); remaining -= n; return n
            }
            override fun close() { closed = true }
        }
        try {
            val store = KvEncryptedContentStore(dir)
            val content = store.stage(input, 30L * 1024 * 1024)
            assertTrue(closed)
            val reader = content.openVerified(); val buffer = ByteArray(8192); var size = 0L
            while (true) { val n = reader.read(buffer); if (n < 0) break; size += n }
            reader.close(); assertEquals(30L * 1024 * 1024, size)
            content.close()
        } finally { dir.deleteRecursively() }
    }
}
