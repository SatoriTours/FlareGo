package com.flarego.core.ports

import com.flarego.core.cloudflare.HttpRequest

/**
 * Synchronous byte source owned by a transport call.
 *
 * [read] returns the number of bytes written into `buffer`, or `-1` at end of
 * stream. Implementations are only called from the platform IO thread.
 */
interface ByteSource {
    fun read(buffer: ByteArray): Int

    fun close()
}

/**
 * Synchronous byte sink owned by a transport call.
 *
 * Implementations are only called from the platform IO thread.
 */
interface ByteSink {
    fun write(buffer: ByteArray, offset: Int, count: Int)

    fun close()
}

/**
 * Result head of a streamed transfer: the provider status and headers plus how
 * many bytes were actually transferred. A missing provider `Content-Length` is
 * never replaced by a fabricated total.
 */
data class HttpResponseHead(
    val status: Int,
    val headers: Map<String, List<String>>,
    val transferredBytes: Long,
)

/**
 * Streaming transfer port. Implementations must not buffer a whole upload or
 * download in memory, must leave an error response unwritten, and must close the
 * request's sources and the caller's sink on every path.
 */
interface TransferPort {
    suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead
}
