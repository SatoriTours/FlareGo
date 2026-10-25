package com.flarego.platform

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.ports.*
import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*

/**
 * AES-256-GCM records of at most 64KiB, with unique per-record nonces. AAD binds
 * header/ordinal/length; an authenticated terminal binds total bytes and count.
 * A full authentication pass precedes opening a streaming reader of the sealed file.
 * CipherInputStream's provider-dependent whole-message buffering is avoided.
 */
class KvEncryptedContentStore(private val directory: File) : KvContentStore {
    private val owners = mutableMapOf<Any, () -> Unit>()
    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw cacheFailure()
        synchronized(liveFiles) {
            directory.listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".enc") }
                ?.filter { it.absolutePath !in liveFiles }?.forEach { it.delete() }
        }
    }
    override fun writer(maxBytes: Long): KvContentWriter {
        if (maxBytes < 0) throw cacheFailure()
        return try { Writer(maxBytes).also { w -> synchronized(owners) { owners[w] = w::abort } } }
        catch (_: Exception) { throw cacheFailure() }
    }
    override suspend fun stage(source: ByteSource, maxBytes: Long): KvContent {
        var published:KvContent?=null
        try { return withContext(Dispatchers.IO) {
        val writer = try { writer(maxBytes) } catch (failure: Exception) { runCatching { source.close() }; throw failure }
        @OptIn(InternalCoroutinesApi::class)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) runCatching { source.close() }
        }
        try {
            val buffer = ByteArray(CHUNK)
            while (true) {
                ensureActive()
                val n = source.read(buffer)
                if (n == -1) break
                if (n !in 1..buffer.size) throw cacheFailure()
                writer.write(buffer, 0, n)
            }
            buffer.fill(0); writer.close(); writer.seal().also{published=it}
        } catch (cancel: CancellationException) { writer.abort(); throw cancel
        } catch (_: Exception) { writer.abort(); throw cacheFailure()
        } finally { cancellation?.dispose(); runCatching { source.close() } }
        } } catch(failure:Throwable){published?.close();throw failure}
        finally{runCatching{source.close()}}
    }
    override fun releaseAll() {
        val all = synchronized(owners) { owners.values.toList().also { owners.clear() } }
        all.forEach { runCatching { it() } }
    }

    private inner class Writer(private val max: Long) : KvContentWriter {
        private val file = File.createTempFile(PREFIX, ".enc", directory)
        private val key = ByteArray(32).also(random::nextBytes)
        private val header = MAGIC + ByteArray(8).also(random::nextBytes)
        private val pending = ByteArray(CHUNK)
        private var used = 0
        private var total = 0L
        private var ordinal = 0
        private var closed = false
        private var aborted = false
        private var sealed = false
        private val output: DataOutputStream
        init {
            synchronized(liveFiles) { liveFiles.add(file.absolutePath) }
            try {
                if (!file.setReadable(false, false) || !file.setWritable(false, false) ||
                    !file.setReadable(true, true) || !file.setWritable(true, true)) throw cacheFailure()
                output = DataOutputStream(BufferedOutputStream(FileOutputStream(file), CHUNK))
                output.write(header)
            } catch (_: Exception) { key.fill(0); delete(file); throw cacheFailure() }
        }
        @Synchronized override fun write(buffer: ByteArray, offset: Int, count: Int) {
            if (closed || aborted || sealed || offset < 0 || count < 0 || offset > buffer.size - count) throw cacheFailure()
            if (count.toLong() > max - total) { abort(); throw cacheFailure() }
            try {
                var at = offset; var remaining = count
                while (remaining > 0) {
                    val n = minOf(CHUNK - used, remaining)
                    buffer.copyInto(pending, used, at, at + n)
                    used += n; at += n; remaining -= n; total += n
                    if (used == CHUNK) flushChunk()
                }
            } catch (_: Exception) { abort(); throw cacheFailure() }
        }
        private fun flushChunk() {
            if (ordinal == Int.MAX_VALUE) throw cacheFailure()
            output.writeInt(used)
            output.write(crypt(Cipher.ENCRYPT_MODE, key, header, ordinal++, used, pending, used))
            pending.fill(0); used = 0
        }
        @Synchronized override fun close() {
            if (closed || aborted || sealed) return
            try {
                if (used > 0) flushChunk()
                val terminal = ByteBuffer.allocate(12).putLong(total).putInt(ordinal).array()
                output.writeInt(0)
                output.write(crypt(Cipher.ENCRYPT_MODE, key, header, ordinal, 0, terminal, terminal.size))
                output.close(); closed = true
            } catch (_: Exception) { abort(); throw cacheFailure() }
        }
        override suspend fun seal(): KvContent = withContext(Dispatchers.IO) {
            val content = synchronized(this@Writer) {
                if (aborted || sealed) throw cacheFailure()
                close()
                Content(file, key.copyOf(), header.copyOf(), total)
            }
            try {
                content.openVerified().close()
                synchronized(this@Writer) {
                    if (aborted) throw cacheFailure()
                    sealed = true; key.fill(0)
                    synchronized(owners) { owners.remove(this@Writer); owners[content] = content::close }
                }
                content
            } catch (cancel: CancellationException) { content.close(); abort(); throw cancel
            } catch (_: Exception) { content.close(); abort(); throw cacheFailure() }
        }
        @Synchronized override fun abort() {
            if (sealed || aborted) return
            aborted = true; pending.fill(0); key.fill(0)
            runCatching { output.close() }; delete(file)
            synchronized(owners) { owners.remove(this) }
        }
    }

    private inner class Content(
        private val file: File, private val key: ByteArray, private val header: ByteArray, length: Long,
    ) : KvContent {
        override val descriptor = KvContentDescriptor(UUID.randomUUID().toString(), length)
        private val revoked = AtomicBoolean(false)
        private val readers = mutableSetOf<Reader>()
        override suspend fun openVerified(): ByteSource {
            var published:Reader?=null
            try { return withContext(Dispatchers.IO) {
            var verifier: Reader? = null
            try {
                if (revoked.get()) throw cacheFailure()
                verifier = openReader()
                val buffer = ByteArray(CHUNK)
                while (true) { ensureActive(); if (verifier.read(buffer) < 0) break }
                buffer.fill(0); verifier.close(); verifier = null
                ensureActive(); openReader().also{published=it}
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { throw cacheFailure()
            } finally { verifier?.close() }
            } } catch(failure:Throwable){published?.close();throw failure}
        }
        @Synchronized private fun openReader(): Reader {
            if (revoked.get()) throw cacheFailure()
            val reader = Reader(file, key.copyOf(), header, descriptor.length, revoked) { synchronized(this) { readers.remove(it) } }
            readers.add(reader); return reader
        }
        override fun close() {
            if (!revoked.compareAndSet(false, true)) return
            val all = synchronized(this) { key.fill(0); readers.toList().also { readers.clear() } }
            all.forEach { it.close() }; delete(file)
            synchronized(owners) { owners.remove(this) }
        }
        override fun toString() = "KvContent(redacted)"
    }

    private class Reader(
        file: File, private val key: ByteArray, private val expectedHeader: ByteArray,
        private val expectedLength: Long, private val revoked: AtomicBoolean,
        private val onClose: (Reader) -> Unit,
    ) : ByteSource {
        private val input = DataInputStream(BufferedInputStream(FileInputStream(file), CHUNK))
        private val closed = AtomicBoolean(false)
        private var chunk = byteArrayOf()
        private var offset = 0
        private var ordinal = 0
        private var total = 0L
        private var ended = false
        init {
            try {
                val header = ByteArray(expectedHeader.size); input.readFully(header)
                if (!header.contentEquals(expectedHeader)) throw cacheFailure()
            } catch (_: Exception) { input.close(); key.fill(0); throw cacheFailure() }
        }
        override fun read(buffer: ByteArray): Int {
            if (closed.get() || revoked.get() || buffer.isEmpty()) throw cacheFailure()
            if (ended) return -1
            try {
                if (offset == chunk.size) {
                    chunk.fill(0); offset = 0
                    val size = input.readInt()
                    if (size !in 0..CHUNK || ordinal == Int.MAX_VALUE) throw cacheFailure()
                    val encrypted = ByteArray(if (size == 0) 28 else size + 16)
                    input.readFully(encrypted)
                    chunk = crypt(Cipher.DECRYPT_MODE, key, expectedHeader, ordinal, size, encrypted, encrypted.size)
                    if (size == 0) {
                        val terminal = ByteBuffer.wrap(chunk)
                        if (terminal.long != total || terminal.int != ordinal || total != expectedLength || input.read() != -1) throw cacheFailure()
                        ended = true; chunk.fill(0); return -1
                    }
                    total += size; ordinal++
                    if (total > expectedLength || chunk.size != size) throw cacheFailure()
                }
                if (revoked.get() || closed.get()) throw cacheFailure()
                val n = minOf(buffer.size, chunk.size - offset)
                chunk.copyInto(buffer, 0, offset, offset + n); offset += n; return n
            } catch (_: Exception) { close(); throw cacheFailure() }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { input.close() }; key.fill(0); chunk.fill(0); onClose(this)
        }
    }

    private companion object {
        const val PREFIX = "flarego-kv-"
        const val CHUNK = 65536
        val MAGIC = byteArrayOf(70, 71, 75, 86, 1, 0, 0, 0)
        val random = SecureRandom()
        val liveFiles = mutableSetOf<String>()
        fun delete(file: File) { file.delete(); synchronized(liveFiles) { liveFiles.remove(file.absolutePath) } }
        fun crypt(mode: Int, key: ByteArray, header: ByteArray, ordinal: Int, length: Int, bytes: ByteArray, count: Int): ByteArray {
            val nonce = header.copyOfRange(MAGIC.size, header.size) + ByteBuffer.allocate(4).putInt(ordinal).array()
            val aad = header + ByteBuffer.allocate(8).putInt(ordinal).putInt(length).array()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
                doFinal(bytes, 0, count)
            }
        }
    }
}

private fun cacheFailure() = CloudFailure(CloudFailureCode.INVALID_INPUT, "私有内容不可用，请重新选择内容")
