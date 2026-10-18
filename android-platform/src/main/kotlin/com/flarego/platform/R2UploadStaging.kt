package com.flarego.platform

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2_MAX_SINGLE_OBJECT_BYTES
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.R2UploadContent
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** File path is never exposed: only the completed private copy can supply upload bytes. */
class R2StagedUpload internal constructor(private val file: File, override val length: Long, override val sha256: String) : R2UploadContent {
    private val lock = Any()
    private var released = false
    private var opened = false
    override suspend fun openSource(): ByteSource {
        var owned: ByteSource? = null
        try {
            return withContext(Dispatchers.IO) {
                synchronized(lock) {
                    if (released || opened || !file.isFile || file.length() != length) throw stagingFailure()
                    val input = file.inputStream()
                    val closed = AtomicBoolean()
                    object : ByteSource {
                        override fun read(buffer: ByteArray) = input.read(buffer)
                        override fun close() { if (closed.compareAndSet(false, true)) input.close() }
                    }.also { owned = it; opened = true }
                }
            }
        } catch (cancel: CancellationException) {
            owned?.close(); throw cancel
        } catch (_: Exception) {
            owned?.close(); throw stagingFailure()
        }
    }
    override fun release() = synchronized(lock) {
        if (!released) {
            if (file.exists() && !file.delete()) throw stagingFailure()
            released = true
        }
        // Android/Linux unlink does not revoke a transport-owned open file descriptor.
    }
}

/** Copy and digest in the same bounded-buffer pass. Never rereads or edits the user's document. */
@OptIn(InternalCoroutinesApi::class)
suspend fun stageR2Upload(
    cacheDir: File,
    open: () -> InputStream,
    maxBytes: Long = R2_MAX_SINGLE_OBJECT_BYTES,
): R2StagedUpload {
    var temp: File? = null
    var result: R2StagedUpload? = null
    try {
        return withContext(Dispatchers.IO) {
            require(maxBytes in 1..R2_MAX_SINGLE_OBJECT_BYTES)
            currentCoroutineContext().ensureActive()
            val target = File.createTempFile("flarego-r2-upload-", ".tmp", cacheDir).also { temp = it }
            val hash = MessageDigest.getInstance("SHA-256")
            var length = 0L
            open().use { input ->
                val hook = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { reason ->
                    if (reason != null) runCatching { input.close() }
                }
                try {
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0 || length > maxBytes - count) throw stagingFailure()
                            output.write(buffer, 0, count); hash.update(buffer, 0, count); length += count
                        }
                    }
                } finally { hook?.dispose() }
            }
            currentCoroutineContext().ensureActive()
            R2StagedUpload(target, length, hash.digest().joinToString("") { "%02x".format(it) }).also { result = it }
        }
    } catch (cancel: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) { result?.release() ?: temp?.delete() }
        throw cancel
    } catch (_: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { result?.release() ?: temp?.delete() }
        throw stagingFailure()
    }
}
private fun stagingFailure() = CloudFailure(CloudFailureCode.INVALID_INPUT,
    "无法暂存待上传文件；请检查文档授权、可用空间和文件大小后重新选择")
